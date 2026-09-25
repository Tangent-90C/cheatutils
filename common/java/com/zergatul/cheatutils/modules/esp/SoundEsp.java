package com.zergatul.cheatutils.modules.esp;

import com.zergatul.cheatutils.common.Events;
import com.zergatul.cheatutils.configs.ConfigStore;
import com.zergatul.cheatutils.configs.SoundEspConfig;
import com.zergatul.cheatutils.mixins.common.accessors.EntityBoundSoundInstanceAccessor;
import com.zergatul.cheatutils.modules.utilities.RenderUtilities;
import com.zergatul.cheatutils.render.LineRenderer;
import com.zergatul.cheatutils.common.events.RenderGuiEvent;
import com.zergatul.cheatutils.common.events.RenderWorldLastEvent;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.EntityBoundSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.network.Connection;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Locale;
import java.util.UUID;

/**
 * Marks positions of sounds received by the client (gunshots, footsteps, etc.) and
 * optionally logs every sound event to CSV together with player entity spawn events,
 * so sound-to-entity lag can be measured offline.
 */
public class SoundEsp {

    public static final SoundEsp instance = new SoundEsp();

    private static final int MAX_MARKERS = 1024;
    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final Minecraft mc = Minecraft.getInstance();
    private final ArrayDeque<Marker> markers = new ArrayDeque<>();

    private BufferedWriter csvWriter;
    private boolean csvFailed;
    private long sessionNanos;

    private SoundEsp() {
        Events.AfterRenderWorld.add(this::onRenderWorld);
        Events.PreRenderGui.add(this::onPreRenderGui);
        Events.ClientTickEnd.add(this::onClientTickEnd);
        Events.EntityAdded.add(this::onEntityAdded);
        Events.ClientPlayerLoggingIn.add(this::onLoggingIn);
        Events.ClientPlayerLoggingOut.add(this::onLoggingOut);
        Events.LevelUnload.add(this::onLevelUnload);
    }

    // region capture

    public void onSoundInstance(SoundInstance sound) {
        SoundEspConfig config = getConfig();
        if (!config.enabled || mc.level == null || sound == null) {
            return;
        }

        try {
            ResourceLocation location = sound.getLocation();
            SoundSource source = sound.getSource();
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

            Marker marker = new Marker(
                    pos, location, source,
                    sound.getVolume(), sound.getPitch(),
                    sound.isRelative(), false,
                    entityUuid, entityPos, System.nanoTime());
            addMarker(marker);

            if (config.writeCsv) {
                writeCsv("SOUND", location.toString(), source.getName(), marker,
                        entityUuid != null);
            }
        } catch (Throwable t) {
            // never break sound playback because of our tooling
        }
    }

    private void onEntityAdded(Entity entity) {
        SoundEspConfig config = getConfig();
        if (!config.enabled || !config.markPlayerSpawn || mc.level == null) {
            return;
        }
        if (!(entity instanceof Player) || entity instanceof LocalPlayer) {
            return;
        }

        try {
            Vec3 pos = entity.position();
            long now = System.nanoTime();
            Marker marker = new Marker(
                    pos, null, SoundSource.PLAYERS,
                    1, 1, false, true,
                    entity.getUUID(), pos, now);
            addMarker(marker);

            if (config.writeCsv) {
                writeCsv("PLAYER_ADD", "entity_spawn", SoundSource.PLAYERS.getName(), marker,
                        true);
            }
        } catch (Throwable t) {
            // ignore
        }
    }

    private void addMarker(Marker marker) {
        markers.addLast(marker);
        while (markers.size() > MAX_MARKERS) {
            markers.removeFirst();
        }
    }

    // endregion

    // region world rendering

    private void onRenderWorld(RenderWorldLastEvent event) {
        SoundEspConfig config = getConfig();
        if (!config.enabled || !config.showMarkers || mc.level == null) {
            return;
        }

        long now = System.nanoTime();
        pruneMarkers(now);

        LineRenderer renderer = RenderUtilities.instance.getLineRenderer();
        renderer.begin(event, true);

        Vec3 playerPos = mc.player != null ? mc.player.position() : null;
        for (Marker marker : markers) {
            long remain = marker.time + config.markerDuration * NANOS_PER_SECOND - now;
            float alpha = (float) remain / NANOS_PER_SECOND; // fade during the last second
            if (alpha > 1) {
                alpha = 1;
            }
            if (alpha <= 0) {
                continue;
            }

            float r, g, b;
            if (marker.isPlayerSpawn) {
                r = 0.2f;
                g = 1f;
                b = 0.2f;
            } else {
                switch (marker.source) {
                    case PLAYERS -> {
                        r = 1f;
                        g = 0.25f;
                        b = 0.25f;
                    }
                    case HOSTILE -> {
                        r = 1f;
                        g = 0.6f;
                        b = 0.1f;
                    }
                    case NEUTRAL -> {
                        r = 1f;
                        g = 1f;
                        b = 0.2f;
                    }
                    case BLOCKS -> {
                        r = 0.6f;
                        g = 0.6f;
                        b = 0.6f;
                    }
                    case VOICE -> {
                        r = 0.4f;
                        g = 0.6f;
                        b = 1f;
                    }
                    default -> {
                        r = 0.2f;
                        g = 0.9f;
                        b = 0.9f;
                    }
                }
            }

            drawMarker(renderer, marker.pos, r, g, b, alpha);
        }

        renderer.end();
    }

    private void drawMarker(LineRenderer renderer, Vec3 pos, float r, float g, float b, float a) {
        double x = pos.x;
        double y = pos.y;
        double z = pos.z;
        double h = 2.5;
        double s = 0.4;

        // vertical beam
        renderer.line(x - s, y, z, r, g, b, a, x - s, y + h, z, r, g, b, a);
        renderer.line(x + s, y, z, r, g, b, a, x + s, y + h, z, r, g, b, a);
        renderer.line(x, y, z - s, r, g, b, a, x, y + h, z - s, r, g, b, a);
        renderer.line(x, y, z + s, r, g, b, a, x, y + h, z + s, r, g, b, a);

        // base square
        renderer.line(x - s, y, z - s, r, g, b, a, x + s, y, z - s, r, g, b, a);
        renderer.line(x + s, y, z - s, r, g, b, a, x + s, y, z + s, r, g, b, a);
        renderer.line(x + s, y, z + s, r, g, b, a, x - s, y, z + s, r, g, b, a);
        renderer.line(x - s, y, z + s, r, g, b, a, x - s, y, z - s, r, g, b, a);

        // top square
        double t = y + h;
        renderer.line(x - s, t, z - s, r, g, b, a, x + s, t, z - s, r, g, b, a);
        renderer.line(x + s, t, z - s, r, g, b, a, x + s, t, z + s, r, g, b, a);
        renderer.line(x + s, t, z + s, r, g, b, a, x - s, t, z + s, r, g, b, a);
        renderer.line(x - s, t, z + s, r, g, b, a, x - s, t, z - s, r, g, b, a);
    }

    // endregion

    // region hud

    private void onPreRenderGui(RenderGuiEvent event) {
        SoundEspConfig config = getConfig();
        if (!config.enabled || !config.showHudList || config.hudMaxEntries <= 0 || mc.level == null) {
            return;
        }
        if (markers.isEmpty() || mc.font == null) {
            return;
        }

        long now = System.nanoTime();
        Vec3 playerPos = mc.player != null ? mc.player.position() : null;

        var graphics = event.graphics();
        int x = 4;
        int y = 4;
        int line = mc.font.lineHeight + 2;

        graphics.drawString(mc.font, "Sound ESP", x, y, 0xFF00FF00, true);
        y += line;

        int count = 0;
        Iterator<Marker> it = markers.descendingIterator();
        while (it.hasNext() && count < config.hudMaxEntries) {
            Marker marker = it.next();
            long ageMs = (now - marker.time) / 1_000_000L;
            if (ageMs > config.markerDuration * 1000L) {
                continue;
            }

            String name = marker.isPlayerSpawn
                    ? "PLAYER_SPAWN"
                    : (marker.location != null ? marker.location.getPath() : "?");
            String text = String.format(Locale.ROOT, "%6dms %s %s", ageMs, name,
                    formatPos(marker.pos));
            if (playerPos != null) {
                text += String.format(Locale.ROOT, " %.1fm", playerPos.distanceTo(marker.pos));
            }

            int color = marker.isPlayerSpawn ? 0xFF40FF40 : 0xFFFFDD55;
            graphics.drawString(mc.font, text, x, y, color, true);
            y += line;
            count++;
        }
    }

    private String formatPos(Vec3 pos) {
        return String.format(Locale.ROOT, "(%.1f, %.1f, %.1f)", pos.x, pos.y, pos.z);
    }

    // endregion

    // region csv

    private void onLoggingIn(Connection connection) {
        SoundEspConfig config = getConfig();
        sessionNanos = System.nanoTime();
        if (!config.enabled || !config.writeCsv) {
            return;
        }
        openCsv();
    }

    private void onLoggingOut() {
        closeCsv();
    }

    private void onLevelUnload() {
        closeCsv();
    }

    private void openCsv() {
        closeCsv();
        csvFailed = false;
        try {
            Path dir = FabricLoader.getInstance().getGameDir().resolve("sound_debug");
            Files.createDirectories(dir);
            String file = "sounds_" + Instant.now().toString().replace(':', '-') + ".csv";
            csvWriter = Files.newBufferedWriter(
                    dir.resolve(file), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            csvWriter.write("epoch_ms,session_ms,type,sound_id,source,volume,pitch,x,y,z,relative,entity_uuid,entity_x,entity_y,entity_z");
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
            sb.append((System.nanoTime() - sessionNanos) / 1_000_000L).append(',');
            sb.append(type).append(',');
            appendCsvEscaped(sb, soundId).append(',');
            appendCsvEscaped(sb, sourceName).append(',');
            sb.append(marker.volume).append(',');
            sb.append(marker.pitch).append(',');
            sb.append(marker.pos.x).append(',');
            sb.append(marker.pos.y).append(',');
            sb.append(marker.pos.z).append(',');
            sb.append(marker.relative).append(',');
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
        return sb.append(value);
    }

    // endregion

    private void onClientTickEnd() {
        // flush csv once per tick to keep file fresh without per-line syscalls
        if (csvWriter != null) {
            try {
                csvWriter.flush();
            } catch (IOException e) {
                csvFailed = true;
            }
        }
    }

    private void pruneMarkers(long now) {
        SoundEspConfig config = getConfig();
        long lifetime = config.markerDuration * NANOS_PER_SECOND;
        while (!markers.isEmpty() && now - markers.getFirst().time > lifetime) {
            markers.removeFirst();
        }
    }

    private static SoundEspConfig getConfig() {
        return ConfigStore.instance.getConfig().soundEspConfig;
    }

    private record Marker(
            Vec3 pos,
            ResourceLocation location,
            SoundSource source,
            float volume,
            float pitch,
            boolean relative,
            boolean isPlayerSpawn,
            UUID entityUuid,
            Vec3 entityPos,
            long time) {
    }
}
