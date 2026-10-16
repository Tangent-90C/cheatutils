package com.zergatul.cheatutils.modules.esp;

import com.zergatul.cheatutils.common.Events;
import com.zergatul.cheatutils.configs.ConfigStore;
import com.zergatul.cheatutils.configs.SoundEntry;
import com.zergatul.cheatutils.configs.SoundEspConfig;
import com.zergatul.cheatutils.modules.utilities.RenderUtilities;
import com.zergatul.cheatutils.render.LineRenderer;
import com.zergatul.cheatutils.common.events.RenderWorldLastEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.EntityBoundSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import com.zergatul.cheatutils.mixins.common.accessors.EntityBoundSoundInstanceAccessor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.awt.Color;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.UUID;

/**
 * Marks positions of sounds with Entity-ESP style boxes and tracers.
 *
 * There is exactly one source: every sound the sound engine starts, hooked at
 * SoundEngine.play. Mods that replay sounds without a server packet end up there too -
 * CSMC plays enemy gunshots locally as custom AbstractSoundInstance subclasses, and no
 * sound packet is ever sent for them.
 *
 * Which sounds get a marker is decided by one list, SoundEspConfig.sounds. Each entry has
 * an id (a trailing "*" matches by prefix), its own render switches - marker, tracer,
 * through walls, only-when-not-visible - and a color. A sound with no entry is never
 * marked - there is no default beyond the entries shipped with the config.
 */
public class SoundEsp {

    public static final SoundEsp instance = new SoundEsp();

    private static final int MAX_MARKERS = 1024;
    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private static final Logger LOGGER = LogManager.getLogger(SoundEsp.class);

    private static final double BOX_HALF_WIDTH = 0.3;
    private static final double BOX_HEIGHT = 1.8;

    private final Minecraft mc = Minecraft.getInstance();
    private final ArrayDeque<Marker> markers = new ArrayDeque<>();

    private int errorCount;
    private int soundCount;
    private int addedMarkerCount;
    private int ownOrNearSkippedCount;
    private long lastStatsNanos;

    private BufferedWriter csvWriter;
    private boolean csvFailed;

    private SoundEsp() {
        Events.AfterRenderWorld.add(this::onRenderWorld);
        Events.ClientTickEnd.add(this::onClientTickEnd);
    }

    // region capture

    /**
     * Every sound the engine starts. Marked when the config lists it and it is not the
     * local player's own sound.
     */
    public void onSoundPlayed(SoundInstance sound) {
        SoundEspConfig config = getConfig();
        if (!config.enabled || mc.level == null || sound == null) {
            return;
        }

        try {
            soundCount++;

            String id = sound.getLocation().toString();
            SoundEntry entry = config.findSound(id);
            if (entry == null || !entry.enabled) {
                return;
            }

            if (config.debug) {
                LOGGER.info("SoundEsp sound: {} at {}", id, formatPos(new Vec3(sound.getX(), sound.getY(), sound.getZ())));
            }

            UUID entityUuid = null;
            Vec3 entityPos = null;
            if (sound instanceof EntityBoundSoundInstance bound) {
                Entity entity = ((EntityBoundSoundInstanceAccessor) bound).getEntity_CU();
                if (entity != null) {
                    entityUuid = entity.getUUID();
                    entityPos = entity.position();
                    if (entity == mc.player) {
                        ownOrNearSkippedCount++;
                        return; // own sound
                    }
                }
            }

            LocalPlayer player = mc.player;
            Vec3 pos = new Vec3(sound.getX(), sound.getY(), sound.getZ());
            double ownDistanceSqr = config.ownSoundDistance * config.ownSoundDistance;
            if (entityUuid == null && config.ownSoundDistance >= 0 && player != null
                    && pos.distanceToSqr(player.position()) < ownDistanceSqr) {
                ownOrNearSkippedCount++;
                return; // non-entity sound right next to us - almost certainly own/UI
            }

            addMarker(new Marker(pos, sound.getLocation(), sound.getSource(), entityUuid, entityPos, System.nanoTime()));
            addedMarkerCount++;

            if (config.writeCsv) {
                writeCsv("SOUND", id, sound.getSource().getName(),
                        new Marker(pos, sound.getLocation(), sound.getSource(), entityUuid, entityPos, System.nanoTime()),
                        entityUuid != null);
            }
        } catch (Throwable t) {
            logError("sound", t);
        }
    }

    /** Every sound played locally through SoundEngine - CSV only, never marked. */
    public void onLocalSoundInstance(SoundInstance sound) {
        SoundEspConfig config = getConfig();
        if (!config.enabled || !config.writeCsv || mc.level == null || sound == null) {
            return;
        }

        try {
            Vec3 pos = new Vec3(sound.getX(), sound.getY(), sound.getZ());
            UUID entityUuid = null;
            Vec3 entityPos = null;
            if (sound instanceof EntityBoundSoundInstance bound) {
                Entity entity = ((EntityBoundSoundInstanceAccessor) bound).getEntity_CU();
                if (entity != null) {
                    entityUuid = entity.getUUID();
                    entityPos = entity.position();
                }
            }

            long now = System.nanoTime();
            writeCsv("SOUND_LOCAL", sound.getLocation().toString(), sound.getSource().getName(),
                    new Marker(pos, sound.getLocation(), sound.getSource(), entityUuid, entityPos, now),
                    entityUuid != null);
        } catch (Throwable t) {
            logError("local sound", t);
        }
    }

    private void addMarker(Marker marker) {
        marker.color = colorFor(marker);
        markers.addLast(marker);
        while (markers.size() > MAX_MARKERS) {
            markers.removeFirst();
        }
    }

    // endregion

    // region world rendering

    private void onRenderWorld(RenderWorldLastEvent event) {
        SoundEspConfig config = getConfig();
        if (!config.enabled || mc.level == null || markers.isEmpty()) {
            return;
        }

        long now = System.nanoTime();
        pruneMarkers(now);
        if (markers.isEmpty()) {
            return;
        }

        LineRenderer renderer = RenderUtilities.instance.getLineRenderer();

        // depth test is a setting of the whole line batch, so entries that draw through
        // walls need their own pass - drawn last, so nothing covers them
        for (int pass = 0; pass < 2; pass++) {
            boolean throughWallsPass = pass == 1;

            boolean any = false;
            for (Marker marker : markers) {
                SoundEntry entry = config.findSound(entryId(marker));
                if ((entry == null || entry.throughWalls) == throughWallsPass) {
                    any = true;
                    break;
                }
            }
            if (!any) {
                continue;
            }

            renderer.begin(event, !throughWallsPass);
            for (Marker marker : markers) {
                SoundEntry entry = config.findSound(entryId(marker));
                if ((entry == null || entry.throughWalls) != throughWallsPass) {
                    continue;
                }
                drawMarker(renderer, config, event, marker, entry, now);
            }
            renderer.end();
        }
    }

    private static String entryId(Marker marker) {
        return marker.location == null ? null : marker.location.toString();
    }

    /** One marker in one depth pass: fade, the entry's visibility filter, shape, tracer. */
    private void drawMarker(LineRenderer renderer, SoundEspConfig config, RenderWorldLastEvent event,
                            Marker marker, SoundEntry entry, long now) {
        long remain = marker.time + (long) (config.markerDuration * NANOS_PER_SECOND) - now;
        float alpha = (float) remain / NANOS_PER_SECOND; // fade during the last second
        if (alpha > 1) {
            alpha = 1;
        }
        if (alpha <= 0) {
            return;
        }

        boolean drawMarker = entry == null || entry.drawMarker;
        boolean drawTracer = entry == null || entry.drawTracer;
        if (!drawMarker && !drawTracer) {
            return;
        }

        if (entry != null && entry.onlyNotVisible && isVisible(marker.pos)) {
            return;
        }

        double offset = entry != null ? entry.offsetYOrDefault() : 0.0;
        boolean ring = entry != null && entry.shapeOrDefault() == SoundEntry.SHAPE_RING;
        double x = marker.pos.x;
        double y = marker.pos.y - offset;
        double z = marker.pos.z;

        float r = marker.color.getRed() / 255f;
        float g = marker.color.getGreen() / 255f;
        float b = marker.color.getBlue() / 255f;

        if (drawMarker) {
            if (ring) {
                drawRing(renderer, x, y, z, r, g, b, alpha);
            } else {
                renderer.cuboid(
                        x - BOX_HALF_WIDTH, y, z - BOX_HALF_WIDTH,
                        x + BOX_HALF_WIDTH, y + BOX_HEIGHT, z + BOX_HALF_WIDTH,
                        r, g, b, alpha);
            }
        }
        if (drawTracer) {
            Vec3 tracerCenter = event.getTracerCenter();
            renderer.line(
                    tracerCenter.x, tracerCenter.y, tracerCenter.z,
                    r, g, b, alpha,
                    x, y + BOX_HEIGHT / 2, z,
                    r, g, b, alpha);
        }
    }

    /** Color of a marker: the entry's own color when it has one, the category color otherwise. */
    private Color colorFor(Marker marker) {
        SoundEntry entry = marker.location == null ? null : getConfig().findSound(marker.location.toString());
        if (entry != null && entry.color != null) {
            return entry.color;
        }
        return switch (marker.source) {
            case PLAYERS -> new Color(255, 64, 64);   // red
            case HOSTILE -> new Color(255, 153, 26);  // orange
            case NEUTRAL -> new Color(255, 255, 51);  // yellow
            case BLOCKS -> new Color(153, 153, 153);  // gray
            case VOICE -> new Color(102, 153, 255);   // blue
            default -> new Color(51, 230, 230);       // cyan
        };
    }

    /**
     * Can the player see this point? A single raycast from the eyes to the sound position -
     * a hit means a block is in the way, so the sound is one you hear but cannot see.
     */
    private boolean isVisible(Vec3 pos) {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            return false;
        }
        Vec3 eye = player.getEyePosition();
        ClipContext context = new ClipContext(eye, pos, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player);
        return mc.level.clip(context).getType() == HitResult.Type.MISS;
    }

    /** Circle on the ground at the sound position - LineRenderer only draws lines. */
    private void drawRing(LineRenderer renderer, double x, double y, double z,
                          float r, float g, float b, float a) {
        final int segments = 24;
        for (int i = 0; i < segments; i++) {
            double a1 = 2 * Math.PI * i / segments;
            double a2 = 2 * Math.PI * (i + 1) / segments;
            renderer.line(
                    x + Math.cos(a1) * BOX_HALF_WIDTH, y, z + Math.sin(a1) * BOX_HALF_WIDTH,
                    r, g, b, a,
                    x + Math.cos(a2) * BOX_HALF_WIDTH, y, z + Math.sin(a2) * BOX_HALF_WIDTH,
                    r, g, b, a);
        }
    }

    // endregion

    // region csv

    private void openCsv() {
        closeCsv();
        csvFailed = false;
        try {
            Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("sound_debug");
            Files.createDirectories(dir);
            String file = "sounds_" + Instant.now().toString().replace(':', '-') + ".csv";
            csvWriter = Files.newBufferedWriter(
                    dir.resolve(file), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            csvWriter.write("epoch_ms,type,sound_id,source,x,y,z,entity_uuid,entity_x,entity_y,entity_z");
            csvWriter.newLine();
        } catch (IOException e) {
            csvFailed = true;
        }
    }

    private void closeCsv() {
        if (csvWriter != null) {
            try {
                csvWriter.close();
            } catch (IOException ignored) {
            }
            csvWriter = null;
        }
    }

    private void writeCsv(String type, String soundId, String sourceName, Marker marker,
                          boolean hasEntity) {
        if (csvFailed) {
            return;
        }
        if (csvWriter == null) {
            openCsv();
            if (csvWriter == null) {
                return;
            }
        }
        try {
            StringBuilder sb = new StringBuilder(256);
            sb.append(System.currentTimeMillis()).append(',');
            sb.append(type).append(',');
            appendCsvEscaped(sb, soundId).append(',');
            appendCsvEscaped(sb, sourceName).append(',');
            sb.append(marker.pos.x).append(',');
            sb.append(marker.pos.y).append(',');
            sb.append(marker.pos.z).append(',');
            sb.append(marker.entityUuid != null ? marker.entityUuid : "").append(',');
            if (hasEntity && marker.entityPos != null) {
                sb.append(marker.entityPos.x).append(',');
                sb.append(marker.entityPos.y).append(',');
                sb.append(marker.entityPos.z);
            } else {
                sb.append(",,");
            }
            csvWriter.write(sb.toString());
            csvWriter.newLine();
        } catch (IOException e) {
            csvFailed = true;
        }
    }

    private StringBuilder appendCsvEscaped(StringBuilder sb, String value) {
        if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0) {
            return sb.append('"').append(value.replace("\"", "\"\"")).append('"');
        }
        return sb;
    }

    // endregion

    private void onClientTickEnd() {
        if (csvWriter != null) {
            try {
                csvWriter.flush();
            } catch (IOException e) {
                csvFailed = true;
            }
        }

        logStats();
    }

    /**
     * Every 5 seconds, when debug logging is on: how many sounds the engine hook saw, how
     * many became markers, and what the last one was. Sounds the hook never saw cannot be
     * marked, so this separates "no sound arrived" from "sound arrived but was dropped".
     */
    private void logStats() {
        if (!getConfig().debug) {
            return;
        }
        long now = System.nanoTime();
        if (lastStatsNanos == 0) {
            lastStatsNanos = now;
            return;
        }
        if (now - lastStatsNanos < 5 * NANOS_PER_SECOND) {
            return;
        }

        Marker last = markers.peekLast();
        String lastDesc = "none";
        if (last != null) {
            String dist = "?";
            if (mc.player != null) {
                dist = String.format("%.1f", Math.sqrt(last.pos.distanceToSqr(mc.player.position())));
            }
            lastDesc = String.format("%s at %s at %s blocks", last.location, formatPos(last.pos), dist);
        }

        LOGGER.info("SoundEsp stats: sounds={} markers={} skippedAsOwnOrNear={} live={} listed={} last={}",
                soundCount, addedMarkerCount, ownOrNearSkippedCount, markers.size(),
                getConfig().sounds == null ? 0 : getConfig().sounds.size(), lastDesc);
        lastStatsNanos = now;
    }

    private static String formatPos(Vec3 pos) {
        return String.format("(%.1f, %.1f, %.1f)", pos.x, pos.y, pos.z);
    }

    /**
     * Sound hooks used to swallow their exceptions, which is why a silently failing mixin
     * looked like "the module does nothing". Rate limited, so a broken path is visible
     * without flooding the log when it fires per sound.
     */
    private void logError(String path, Throwable t) {
        errorCount++;
        if (errorCount <= 5 || errorCount % 500 == 0) {
            LOGGER.info("SoundEsp {} failed ({})", path, errorCount);
        }
    }

    private void pruneMarkers(long now) {
        SoundEspConfig config = getConfig();
        long lifetime = (long) (config.markerDuration * NANOS_PER_SECOND);
        while (!markers.isEmpty() && now - markers.getFirst().time > lifetime) {
            markers.removeFirst();
        }
    }

    private static SoundEspConfig getConfig() {
        return ConfigStore.instance.getConfig().soundEspConfig;
    }

    private static class Marker {

        final Vec3 pos;
        final ResourceLocation location;
        final SoundSource source;
        final UUID entityUuid;
        final Vec3 entityPos;
        final long time;
        Color color;

        Marker(Vec3 pos, ResourceLocation location, SoundSource source, UUID entityUuid,
               Vec3 entityPos, long time) {
            this.pos = pos;
            this.location = location;
            this.source = source;
            this.entityUuid = entityUuid;
            this.entityPos = entityPos;
            this.time = time;
        }
    }
}
