package com.zergatul.cheatutils.controllers;

import com.zergatul.cheatutils.common.Events;
import com.zergatul.cheatutils.common.events.RenderWorldLastEvent;
import com.zergatul.cheatutils.configs.ConfigStore;
import com.zergatul.cheatutils.configs.CsmcBlinkConfig;
import com.zergatul.cheatutils.modules.utilities.RenderUtilities;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.awt.Color;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Holds outgoing movement while you stay where the server thinks you are, and pushes the buffer out
 * the moment a shot leaves - so the server sees you where you stand before it resolves the shot.
 * <p>
 * CSMC does not report a shot with a packet of its own. It cancels the vanilla attack path and runs
 * its own fire handler, which ends with
 * {@code ServerboundPlayerActionPacket(Action.SWAP_ITEM_WITH_OFFHAND)} - the packet vanilla sends
 * when you press F to swap hands, reused as the fire signal. That is what the module watches for,
 * together with the rest of the action family, since weapons that stay on the vanilla path still
 * send an interact or a use-item packet.
 * <p>
 * The buffer is released through {@link NetworkPacketsController} with handlers stopped, the same way
 * {@code Blink} releases its buffer, so the held packets are not buffered again on the way out. The
 * packet for the position held right now is sent too: the move packet of the current tick is built
 * later inside {@code LocalPlayer.tick()} and is sent after the fire packet, so the buffer alone
 * would leave the server one tick behind. Releasing before the fire packet keeps the ordering the
 * server needs: history, current position, then the shot.
 */
public class CsmcBlinkController {

    public static final CsmcBlinkController instance = new CsmcBlinkController();

    private final Minecraft mc = Minecraft.getInstance();
    private final Logger logger = LogManager.getLogger(CsmcBlinkController.class);

    private final List<ServerboundMovePlayerPacket> packets = new ArrayList<>();
    private double startX;
    private double startY;
    private double startZ;
    private boolean armed;
    private boolean releasing;

    // how long the position the buffer ends with has been the position you are standing in: holding
    // movement you are not making only leaves the box stuck behind you, so it is released.
    // The shot flush is a separate path and does not depend on this
    private int stillTicks;
    private static final int STILL_TICKS_TO_RELEASE = 5;

    // Packets this controller released, kept by identity so they are not buffered again when some
    // other layer replays them. CSMC's outbound jitter queue re-sends delayed packets through
    // Connection.send, which routes them back through doSendPacket and therefore through our own
    // handler - buffering them again would release them again, forever.
    private final Map<ServerboundMovePlayerPacket, Long> releasedPackets = new IdentityHashMap<>();
    private static final long RELEASED_PACKET_EXPIRY_MS = 2000;

    // the player instance this session tracks. A respawn builds a whole new LocalPlayer, which is
    // the one moment the buffer describes a place you are no longer at
    private LocalPlayer trackedPlayer;

    // rewind state: armed marks the respawn button being clicked with a buffer to keep, pending
    // marks the buffer actually being held, which stops buffering from resuming on its own so the
    // claims it holds stay the last thing the server is told about
    private boolean rewindArmed;
    private boolean rewindPending;

    // diagnostics: what the packet handler actually did, sampled once a second
    private int heldPackets;
    private int passedPackets;
    private int releases;
    private int logTimer;
    private final Map<String, Integer> packetTypes = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest) {
            return size() > 12;
        }
    };

    // diagnostics: what came back from the server, sampled once a second. A teleport or a payload
    // right after a flush is the server disagreeing with the position it was just told about
    private final Map<String, Integer> inboundTypes = new LinkedHashMap<>();

    // last position that actually reached the server - what the server believes about you
    private boolean serverPositionKnown;
    private int renderLogTimer;
    private double serverX;
    private double serverY;
    private double serverZ;

    private CsmcBlinkController() {
        NetworkPacketsController.instance.addClientPacketHandler(this::onClientPacket);
        NetworkPacketsController.instance.addServerPacketHandler(this::onServerPacket);
        Events.ClientTickEnd.add(this::onClientTickEnd);
        Events.ClientPlayerLoggingOut.add(this::onAbort);
        Events.DimensionChange.add(this::onAbort);
        Events.AfterRenderWorld.add(this::render);
    }

    public boolean isEnabled() {
        CsmcBlinkConfig config = ConfigStore.instance.getConfig().csmcBlinkConfig;
        return config.enabled;
    }

    public boolean isArmed() {
        return armed;
    }

    public int getPackets() {
        return packets.size();
    }

    public double getDistance() {
        if (!armed || mc.player == null) {
            return 0;
        }

        double dx = startX - mc.player.getX();
        double dy = startY - mc.player.getY();
        double dz = startZ - mc.player.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * How far the server-side position is from where you actually stand. Same idea as
     * {@link #getDistance()} but measured against the last position the server was told about, so it
     * stays meaningful after a flush that re-armed the buffer.
     */
    public double getServerDistance() {
        if (!serverPositionKnown || mc.player == null) {
            return 0;
        }

        double dx = serverX - mc.player.getX();
        double dy = serverY - mc.player.getY();
        double dz = serverZ - mc.player.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public double getServerX() {
        return serverX;
    }

    public double getServerY() {
        return serverY;
    }

    public double getServerZ() {
        return serverZ;
    }

    /**
     * Starts buffering movement. Safe to call repeatedly - the buffer is not dropped.
     */
    public void enable() {
        CsmcBlinkConfig config = ConfigStore.instance.getConfig().csmcBlinkConfig;
        config.enabled = true;
        if (mc.player != null) {
            arm();
        }
    }

    /**
     * Stops buffering and releases what is held, so the server catches up with the movement.
     */
    public void disable() {
        CsmcBlinkConfig config = ConfigStore.instance.getConfig().csmcBlinkConfig;
        config.enabled = false;
        release();
    }

    /**
     * Releases what is held without turning the module off. Used by the fire trigger: the shot is
     * preceded by the release, so it is resolved against the position you are standing in, and the
     * module keeps running so the next shot is preceded by another one.
     */
    public void flush() {
        release(true, true);
    }

    /**
     * Replays the buffer as it was recorded, without reporting the position held now. Meant for a
     * respawn rewind: the buffer then describes where you died, and the last position the server is
     * told about has to be that one, because a fresh claim for where you just respawned would erase
     * it again.
     */
    public void recall() {
        release(false, false);
    }

    private void onClientTickEnd() {
        CsmcBlinkConfig config = ConfigStore.instance.getConfig().csmcBlinkConfig;
        if (mc.player == null) {
            trackedPlayer = null;
            packets.clear();
            releasedPackets.clear();
            armed = false;
            return;
        }

        if (mc.player != trackedPlayer) {
            handlePlayerSwap(config);
            log();
            return;
        }

        if (config.enabled && !rewindPending) {
            if (!armed) {
                arm();
            } else if (limitReached(config)) {
                // the limits cap how far the buffer may drift, they do not end the session: empty the
                // buffer and keep buffering from the position you are in now
                release(true, true);
            } else if (config.releaseWhenStopped && stoppedMoving()) {
                // nothing new is moving into the buffer, so what it holds is only the leftover offset
                // from the last run - the stutter comes from movement, not from standing still, and
                // holding the rest just keeps the debug box behind you
                if (++stillTicks >= STILL_TICKS_TO_RELEASE) {
                    release(true, true);
                }
            } else {
                stillTicks = 0;
            }
        } else if (armed) {
            release();
        }

        log();
    }

    private void log() {
        CsmcBlinkConfig config = ConfigStore.instance.getConfig().csmcBlinkConfig;
        if (!config.debugLogging || ++logTimer < 20) {
            return;
        }

        logTimer = 0;
        logger.info("csmc-blink armed={} enabled={} buffer={} rewind={} held/s={} passed/s={} releases/s={} drift={} serverPos={}/{}/{} packets={} inbound={}",
                armed, config.enabled, packets.size(), rewindPending, heldPackets, passedPackets, releases,
                round(getServerDistance()), round(serverX), round(serverY), round(serverZ), packetTypes, inboundTypes);
        heldPackets = 0;
        passedPackets = 0;
        releases = 0;
        packetTypes.clear();
        inboundTypes.clear();
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /**
     * Packet label for the diagnostics. Custom payloads are counted per channel, because they are
     * the only packets whose type name says nothing about what they carry - a shooter reports its
     * shot on a channel, and that is the one that has to be picked out of the count.
     */
    private static String describe(Packet<?> packet) {
        if (packet instanceof ServerboundCustomPayloadPacket payload) {
            return "out[" + payload.payload().type().id() + "]";
        }
        return packet.getClass().getSimpleName();
    }

    private void onClientPacket(NetworkPacketsController.ClientPacketArgs args) {
        Packet<?> packet = args.packet;
        if (ConfigStore.instance.getConfig().csmcBlinkConfig.debugLogging) {
            packetTypes.merge(describe(packet), 1, Integer::sum);
        }

        if (packet instanceof ServerboundMovePlayerPacket move) {
            if (armed && !releasing) {
                if (isReleasedPacket(move)) {
                    // our own release coming back through a replay queue: it has to reach the server,
                    // holding it again would start another release round
                    if (hasPosition(move)) {
                        passedPackets++;
                        setServerPosition(move.getX(0), move.getY(0), move.getZ(0));
                    }
                    return;
                }
                // buffered: the server is never told about this position
                heldPackets++;
                args.skip = true;
                if (packets.isEmpty() || !sameAsLast(move)) {
                    packets.add(move);
                }
            } else if (hasPosition(move)) {
                // going out as-is, so this is what the server believes about you now
                passedPackets++;
                setServerPosition(move.getX(0), move.getY(0), move.getZ(0));
            }
        } else if (armed && !releasing && isFlushTrigger(packet)) {
            CsmcBlinkConfig config = ConfigStore.instance.getConfig().csmcBlinkConfig;
            if (config.flushOnShoot) {
                // keep buffering afterwards: the release is what syncs the shot, so ending the module
                // here would leave the next shot unresolved and take the debug box with it
                if (config.debugLogging) {
                    logger.info("csmc-blink action {} -> flush {} packets, drift={} playerPos={}/{}/{}",
                            packet.getClass().getSimpleName(), packets.size(), round(getServerDistance()),
                            round(mc.player.getX()), round(mc.player.getY()), round(mc.player.getZ()));
                }
                release(true, true);
                if (config.debugLogging) {
                    logger.info("csmc-blink flushed -> serverPos={}/{}/{} gap={}",
                            round(serverX), round(serverY), round(serverZ), round(getServerDistance()));
                }
            }
        } else if (packet instanceof ServerboundClientCommandPacket command) {
            CsmcBlinkConfig config = ConfigStore.instance.getConfig().csmcBlinkConfig;
            if (command.getAction() == ServerboundClientCommandPacket.Action.PERFORM_RESPAWN &&
                    config.respawnRewind && !packets.isEmpty() && !releasing) {
                // the click on the respawn button, before the server has moved you anywhere: the
                // buffer still describes the spot you died at, which is where a rewind replays it
                rewindArmed = true;
            }
        }
    }

    private void onServerPacket(NetworkPacketsController.ServerPacketArgs args) {
        if (!ConfigStore.instance.getConfig().csmcBlinkConfig.debugLogging) {
            return;
        }

        Packet<?> packet = args.packet;
        if (packet instanceof ClientboundPlayerPositionPacket) {
            inboundTypes.merge("ServerTeleport", 1, Integer::sum);
        } else if (packet instanceof ClientboundCustomPayloadPacket serverPayload) {
            inboundTypes.merge("in[" + serverPayload.payload().type().id() + "]", 1, Integer::sum);
        }
    }

    private boolean isReleasedPacket(ServerboundMovePlayerPacket packet) {
        if (releasedPackets.isEmpty()) {
            return false;
        }

        Long at = releasedPackets.get(packet);
        if (at == null) {
            return false;
        }
        if (System.currentTimeMillis() - at > RELEASED_PACKET_EXPIRY_MS) {
            releasedPackets.remove(packet);
            return false;
        }
        return true;
    }

    /**
     * Action packets: anything the player does that the server resolves against the position it
     * has been told about. The shot itself is the offhand swap CSMC fires for every weapon, and the
     * rest of the family covers weapons that still run on the vanilla attack and item-use paths.
     * <p>
     * Custom payloads are deliberately not here, even though other shooters use them: CSMC sends
     * them tens of times per second for its own state sync, so treating them as an action releases
     * the buffer on every tick and the drift never grows.
     * <p>
     * Bookkeeping packets - movement, keep-alive, hotbar switches, chat - are rejected for the same
     * reason: they either carry the position themselves or happen all the time.
     */
    private static boolean isFlushTrigger(Packet<?> packet) {
        return packet instanceof ServerboundPlayerActionPacket
                || packet instanceof ServerboundInteractPacket
                || packet instanceof ServerboundUseItemPacket
                || packet instanceof ServerboundUseItemOnPacket
                || packet instanceof ServerboundSwingPacket;
    }

    /**
     * Only position-carrying variants report a position; a rotation-only or status-only packet leaves
     * the server-side position where it was, and reading it from those would write garbage.
     */
    private static boolean hasPosition(ServerboundMovePlayerPacket packet) {
        return packet instanceof ServerboundMovePlayerPacket.Pos
                || packet instanceof ServerboundMovePlayerPacket.PosRot;
    }

    private void setServerPosition(double x, double y, double z) {
        serverX = x;
        serverY = y;
        serverZ = z;
        serverPositionKnown = true;
    }

    private void arm() {
        if (mc.player == null || rewindPending) {
            return;
        }

        packets.clear();
        startX = mc.player.getX();
        startY = mc.player.getY();
        startZ = mc.player.getZ();
        stillTicks = 0;
        armed = true;
    }

    /**
     * Respawn and dimension change both build a new player instance, and the ones that come with a
     * rewind pending keep the buffer instead of dropping it: replaying it afterwards is what pulls
     * you back to the position it was recorded at.
     */
    private void handlePlayerSwap(CsmcBlinkConfig config) {
        boolean holding = rewindArmed && !packets.isEmpty();
        rewindArmed = false;
        if (holding) {
            // stop buffering and keep what the buffer has: the claims the client is about to make
            // for the position it was moved to are what would erase the old ones if they were
            // replayed after them, so they are not buffered at all
            rewindPending = true;
            armed = false;
            releasedPackets.clear();
            if (config.debugLogging) {
                logger.info("csmc-blink holding {} packets recorded before the respawn", packets.size());
            }
        } else {
            onAbort();
        }

        trackedPlayer = mc.player;
        setServerPosition(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        if (config.enabled && !holding) {
            arm();
        }
    }

    private void release() {
        release(false, ConfigStore.instance.getConfig().csmcBlinkConfig.sendCurrentPosition);
    }

    /**
     * @param keepBuffering when true the module keeps running after the buffer is emptied, so the
     *     session continues from the position the server has just been told about. Every release that
     *     is not the module being switched off uses it: ending the session on a release would take the
     *     debug box with it.
     * @param includeCurrentPosition appends a claim for the position held right now behind the buffer,
     *     which is what a release paired with an action needs, and what a rewind must not do.
     */
    private void release(boolean keepBuffering, boolean includeCurrentPosition) {
        CsmcBlinkConfig config = ConfigStore.instance.getConfig().csmcBlinkConfig;

        if (mc.player == null || mc.player.connection == null) {
            packets.clear();
            armed = false;
            return;
        }

        releases++;
        LocalPlayer player = mc.player;
        NetworkPacketsController controller = NetworkPacketsController.instance;

        releasing = true;
        controller.stopHandlers();
        try {
            ServerboundMovePlayerPacket last = null;
            for (ServerboundMovePlayerPacket packet : packets) {
                controller.sendPacket(packet);
                releasedPackets.put(packet, System.currentTimeMillis());
                if (hasPosition(packet)) {
                    last = packet;
                }
            }
            packets.clear();
            if (includeCurrentPosition) {
                ServerboundMovePlayerPacket current = new ServerboundMovePlayerPacket.PosRot(
                        player.getX(),
                        player.getY(),
                        player.getZ(),
                        player.getYRot(),
                        player.getXRot(),
                        player.onGround(),
                        player.horizontalCollision);
                controller.sendPacket(current);
                releasedPackets.put(current, System.currentTimeMillis());
                last = current;
            }
            if (last != null) {
                setServerPosition(last.getX(0), last.getY(0), last.getZ(0));
            }
        } finally {
            controller.resumeHandlers();
            releasing = false;
        }

        rewindPending = false;
        rewindArmed = false;
        stillTicks = 0;
        if (config.enabled && mc.player != null && keepBuffering) {
            arm();
        } else {
            armed = false;
        }
    }

    private void onAbort() {
        packets.clear();
        releasedPackets.clear();
        armed = false;
        rewindArmed = false;
        rewindPending = false;
    }

    /**
     * Draws the position the server believes you are at, so the offset between it and where you
     * actually stand is visible. A line runs from that position to the real one. Drawn whenever the
     * module is on, buffering or not: while buffering it is the drift, and when idle it sits exactly
     * on top of you, which is how you tell the tracked position is still correct.
     */
    private void render(RenderWorldLastEvent event) {
        if (mc.player == null || !serverPositionKnown) {
            return;
        }

        CsmcBlinkConfig config = ConfigStore.instance.getConfig().csmcBlinkConfig;
        // not gated on enabled: the box shows where the server believes you are, which stays
        // meaningful while the module is off too - and a release must never take it away
        if (!config.debugBox) {
            return;
        }

        Vec3 serverPos = new Vec3(serverX, serverY, serverZ);
        AABB box = mc.player.getDimensions(mc.player.getPose()).makeBoundingBox(serverPos);
        Vec3 playerPos = mc.player.getPosition(event.getTickDelta());
        Color color = config.debugBoxColor;
        float r = color.getRed() / 255f;
        float g = color.getGreen() / 255f;
        float b = color.getBlue() / 255f;
        float a = color.getAlpha() / 255f;

        if (config.debugLogging && ++renderLogTimer >= 20) {
            renderLogTimer = 0;
            logger.info("csmc-blink draw serverPos={}/{}/{} playerPos={}/{}/{} gap={}",
                    round(serverX), round(serverY), round(serverZ),
                    round(playerPos.x), round(playerPos.y), round(playerPos.z), round(getServerDistance()));
        }

        RenderUtilities.instance.getLineRenderer().begin(event, false);
        RenderUtilities.instance.getLineRenderer().cuboid(
                box.minX, box.minY, box.minZ,
                box.maxX, box.maxY, box.maxZ,
                r, g, b, a);
        RenderUtilities.instance.getLineRenderer().line(
                serverPos.x, serverPos.y, serverPos.z,
                r, g, b, a / 2,
                playerPos.x, playerPos.y, playerPos.z,
                r, g, b, a / 2);
        RenderUtilities.instance.getLineRenderer().end();
    }

    private boolean limitReached(CsmcBlinkConfig config) {
        if (config.maxPackets > 0 && packets.size() >= config.maxPackets) {
            return true;
        }
        return config.maxDistance > 0 && getDistance() >= config.maxDistance;
    }

    /**
     * The position the buffer ends with is the position you are standing in, so there is no movement
     * left to hold. Rotation-only changes still count: they carry no movement either.
     */
    private boolean stoppedMoving() {
        if (packets.isEmpty() || mc.player == null) {
            return false;
        }

        ServerboundMovePlayerPacket last = packets.get(packets.size() - 1);
        return last.getX(0) == mc.player.getX() &&
                last.getY(0) == mc.player.getY() &&
                last.getZ(0) == mc.player.getZ();
    }

    private boolean sameAsLast(ServerboundMovePlayerPacket packet) {
        ServerboundMovePlayerPacket last = packets.get(packets.size() - 1);
        return last.getX(0) == packet.getX(0) &&
                last.getY(0) == packet.getY(0) &&
                last.getZ(0) == packet.getZ(0) &&
                last.getYRot(0) == packet.getYRot(0) &&
                last.getXRot(0) == packet.getXRot(0) &&
                last.isOnGround() == packet.isOnGround();
    }
}