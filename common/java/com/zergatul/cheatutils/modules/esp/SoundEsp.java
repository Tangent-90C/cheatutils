package com.zergatul.cheatutils.modules.esp;

import com.zergatul.cheatutils.common.Events;
import com.zergatul.cheatutils.configs.ConfigStore;
import com.zergatul.cheatutils.configs.SoundEspConfig;
import com.zergatul.cheatutils.modules.utilities.RenderUtilities;
import com.zergatul.cheatutils.render.LineRenderer;
import com.zergatul.cheatutils.common.events.RenderWorldLastEvent;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.EntityBoundSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import com.zergatul.cheatutils.mixins.common.accessors.EntityBoundSoundInstanceAccessor;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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
 * Marks positions of sounds the server explicitly sends with xyz coordinates
 * (ClientboundSoundPacket - usually actions of hidden/removed entities), and logs
 * every sound event to CSV together with player entity spawn events, so
 * sound-to-entity lag can be measured offline.
 *
 * Sound sources are separated by path:
 * - PACKET_COORD: server sound packet with explicit xyz - marked in the world
 * - PACKET_ENTITY: server sound packet bound to an entity id - optional marking
 * - LOCAL: anything played through SoundEngine without such packets (own sounds,
 *   UI, client-predicted sounds) - CSV only, never marked
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

    private BufferedWriter csvWriter;
    private boolean csvFailed;
    private long sessionNanos;

    private SoundEsp() {
        Events.AfterRenderWorld.add(this::onRenderWorld);
        Events.ClientTickEnd.add(this::onClientTickEnd);
        Events.EntityAdded.add(this::onEntityAdded);
        Events.ClientPlayerLoggingIn.add(this::onLoggingIn);
        Events.ClientPlayerLoggingOut.add(this::onLoggingOut);
        Events.LevelUnload.add(this::onLevelUnload);
    }

    // region capture

    /** Sound packet with explicit xyz coordinates - the only source marked in the world. */
    public void onServerCoordinateSound(ClientboundSoundPacket packet) {
        SoundEspConfig config = getConfig();
        if (!config.enabled || mc.level == null) {
            return;
        }

        try {
            ResourceLocation location = packet.getSound().value().location();
            Vec3 pos = new Vec3(packet.getX(), packet.getY(), packet.getZ());
            long now = System.nanoTime();
            LOGGER.info("SoundEsp coordinate sound packet: {} at {}, {}, {}", location, pos.x, pos.y, pos.z);

            Marker marker = new Marker(
                    pos, location, packet.getSource(),
                    packet.getVolume(), packet.getPitch(),
                    false, false,
                    null, null, now);
            addMarker(marker);

            if (config.writeCsv) {
                writeCsv("SOUND_PACKET_COORD", location.toString(), packet.getSource().getName(),
                        marker, false);
            }
        } catch (Throwable t) {
            // never break packet processing because of our tooling
        }
    }

    /** Sound packet bound to an entity id (position read from the entity object). */
    public void onServerEntitySound(ClientboundSoundEntityPacket packet) {
        SoundEspConfig config = getConfig();
        if (!config.enabled || mc.level == null) {
            return;
        }

        try {
            ResourceLocation location = packet.getSound().value().location();
            LOGGER.info("SoundEsp entity sound packet: {}", location);
            Vec3 pos = null;
            UUID uuid = null;
            Entity entity = mc.level.getEntity(packet.getId());
            if (entity != null) {
                pos = entity.position();
                uuid = entity.getUUID();
            }

            if (config.writeCsv) {
                long now = System.nanoTime();
                Marker marker = new Marker(
                        pos != null ? pos : Vec3.ZERO, location, packet.getSource(),
                        packet.getVolume(), packet.getPitch(),
                        false, false,
                        uuid, pos, now);
                writeCsv("SOUND_PACKET_ENTITY", location.toString(), packet.getSource().getName(),
                        marker, uuid != null);
            }

            if (config.showEntitySoundPackets && pos != null) {
                long now = System.nanoTime();
                Marker marker = new Marker(
                        pos, location, packet.getSource(),
                        packet.getVolume(), packet.getPitch(),
                        false, false,
                        uuid, pos, now);
                addMarker(marker);
            }
        } catch (Throwable t) {
            // ignore
        }
    }

    /** Any sound played locally through SoundEngine - CSV only, never marked. */
    public void onLocalSoundInstance(SoundInstance sound) {
        SoundEspConfig config = getConfig();
        if (!config.enabled || mc.level == null || sound == null) {
            return;
        }
        LOGGER.info("SoundEsp local sound: {}", sound.getLocation());

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

            if (config.writeCsv) {
                long now = System.nanoTime();
                Marker marker = new Marker(
                        pos, sound.getLocation(), sound.getSource(),
                        sound.getVolume(), sound.getPitch(),
                        sound.isRelative(), false,
                        entityUuid, entityPos, now);
                writeCsv("SOUND_LOCAL", sound.getLocation().toString(), sound.getSource().getName(),
                        marker, entityUuid != null);
            }
        } catch (Throwable t) {
            // ignore
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
        if (!config.enabled || mc.level == null || markers.isEmpty()) {
            return;
        }

        long now = System.nanoTime();
        pruneMarkers(now);

        boolean drawBoxes = config.showMarkers;
        boolean drawTracers = config.drawTracers;
        if (!drawBoxes && !drawTracers) {
            return;
        }

        LineRenderer renderer = RenderUtilities.instance.getLineRenderer();
        renderer.begin(event, true);

        for (Marker marker : markers) {
            long remain = marker.time + config.markerDuration * NANOS_PER_SECOND - now;
            float alpha = (float) remain / NANOS_PER_SECOND; // fade during the last second
            if (alpha > 1) {
                alpha = 1;
            }
            if (alpha <= 0) {
                continue;
            }

            if (config.skipMusic &&
                    (marker.source == SoundSource.MUSIC || marker.source == SoundSource.RECORDS)) {
                continue;
            }

            float[] rgb = markerColor(marker);
            float r = rgb[0];
            float g = rgb[1];
            float b = rgb[2];

            if (drawBoxes) {
                renderer.cuboid(
                        marker.pos.x - BOX_HALF_WIDTH, marker.pos.y, marker.pos.z - BOX_HALF_WIDTH,
                        marker.pos.x + BOX_HALF_WIDTH, marker.pos.y + BOX_HEIGHT, marker.pos.z + BOX_HALF_WIDTH,
                        r, g, b, alpha);
            }

            if (drawTracers) {
                Vec3 tracerCenter = event.getTracerCenter();
                renderer.line(
                        tracerCenter.x, tracerCenter.y, tracerCenter.z,
                        r, g, b, alpha,
                        marker.pos.x, marker.pos.y + BOX_HEIGHT / 2, marker.pos.z,
                        r, g, b, alpha);
            }
        }

        renderer.end();
    }

    private float[] markerColor(Marker marker) {
        if (marker.isPlayerSpawn) {
            return new float[]{0.2f, 1f, 0.2f};                 // green
        }
        return switch (marker.source) {
            case PLAYERS -> new float[]{1f, 0.25f, 0.25f};      // red
            case HOSTILE -> new float[]{1f, 0.6f, 0.1f};        // orange
            case NEUTRAL -> new float[]{1f, 1f, 0.2f};          // yellow
            case BLOCKS -> new float[]{0.6f, 0.6f, 0.6f};       // gray
            case VOICE -> new float[]{0.4f, 0.6f, 1f};          // blue
            default -> new float[]{0.2f, 0.9f, 0.9f};           // cyan
        };
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
