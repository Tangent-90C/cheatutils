package com.zergatul.cheatutils.modules.automation;

import com.zergatul.cheatutils.common.Events;
import com.zergatul.cheatutils.common.events.PlayerReleaseUsingItemEvent;
import com.zergatul.cheatutils.common.events.PlayerTurnByMouseEvent;
import com.zergatul.cheatutils.configs.AimAssistConfig;
import com.zergatul.cheatutils.configs.ConfigStore;
import com.zergatul.cheatutils.controllers.NetworkPacketsController;
import com.zergatul.cheatutils.mixins.common.accessors.KeyMappingAccessor;
import com.zergatul.cheatutils.modules.Module;
import com.zergatul.cheatutils.utils.MathUtils;
import com.zergatul.cheatutils.utils.Rotation;
import com.zergatul.cheatutils.utils.RotationUtils;
import com.zergatul.cheatutils.utils.ServerBehavior;
import com.zergatul.cheatutils.utils.TeamUtils;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class AimAssist implements Module {

    public static final AimAssist instance = new AimAssist();

    private static final Logger LOGGER = LogManager.getLogger(AimAssist.class);
    private static final double TICKS_PER_SECOND = 20;
    private static final long DEBUG_INTERVAL_MS = 1000;

    private final Minecraft mc = Minecraft.getInstance();

    private boolean isTargetLockEnabled;
    private Entity bowAssistTarget;
    private Entity targetLockEntity;

    private Entity aimTarget;
    private Vec3 aimPoint;
    private Rotation aimRotation;
    private long lastDebugLogTime;
    private long debugCorrections;
    private Entity combatTarget;
    private boolean combatAligned;
    private boolean combatHolding;
    private long nextCombatClickNanos;
    private double yawCorrection;
    private double pitchCorrection;

    private AimAssist() {
        Events.ClientTickEnd.add(this::onTickEnd);
        Events.PlayerReleaseUsingItem.add(this::onPlayerReleaseUsingItem);
        Events.RenderTickStart.add(this::onRenderTickStart);
        Events.PlayerTurnByMouse.add(this::onPlayerTurnByMouse);
    }

    public boolean isTargetLockEnabled() {
        return isTargetLockEnabled;
    }

    public void enableTargetLock() {
        isTargetLockEnabled = true;
    }

    public void disableTargetLock() {
        isTargetLockEnabled = false;
    }

    public Entity getBowAssistTarget() {
        return bowAssistTarget;
    }

    private void onTickEnd() {
        bowAssistTarget = null;

        updateAimCorrection();

        if (!ConfigStore.instance.getConfig().aimAssist.bowAssist) {
            return;
        }
        if (mc.player == null || mc.level == null) {
            return;
        }
        if (!mc.player.getItemInHand(InteractionHand.MAIN_HAND).is(Items.BOW)) {
            return;
        }
        if (!mc.player.isUsingItem()) {
            return;
        }

        Rotation playerRot = new Rotation(mc.player.getXRot(), mc.player.getYRot());
        if (bowAssistTarget == null) {
            bowAssistTarget = findTarget(playerRot);
            if (bowAssistTarget == null) {
                return;
            }
        }

        int ticks = mc.player.getTicksUsingItem();
        float power = BowItem.getPowerForTime(ticks);
        float speed = power * 3;

        List<Rotation> rotations = findRotations(mc.player, speed);
        if (rotations.isEmpty()) {
            bowAssistTarget = null;
        }
    }

    private void onPlayerReleaseUsingItem(PlayerReleaseUsingItemEvent event) {
        if (!ConfigStore.instance.getConfig().aimAssist.bowAssist) {
            return;
        }
        if (mc.player == null || mc.level == null || bowAssistTarget == null) {
            return;
        }

        Rotation playerRot = new Rotation(mc.player.getXRot(), mc.player.getYRot());

        int ticks = mc.player.getTicksUsingItem();
        float power = BowItem.getPowerForTime(ticks);
        float speed = power * 3;

        List<Rotation> rotations = findRotations(mc.player, speed);
        Optional<Rotation> closest = rotations.stream().min(Comparator.comparingDouble(playerRot::distanceSqrTo));
        if (closest.isPresent()) {
            Rotation rotation = closest.get();
            NetworkPacketsController.instance.sendPacket(new ServerboundMovePlayerPacket.Rot(
                    rotation.yRot(), rotation.xRot(),
                    mc.player.onGround(), mc.player.horizontalCollision));
        }
    }

    private void onRenderTickStart(DeltaTracker delta) {
        if (mc.player == null || !isTargetLockEnabled) {
            targetLockEntity = null;
            return;
        }

        float partialTicks = delta.getGameTimeDeltaPartialTick(true);
        if (targetLockEntity == null) {
            Rotation rotation = new Rotation(mc.player.getXRot(partialTicks), mc.player.getYRot(partialTicks));
            targetLockEntity = findTarget(rotation);
        }

        if (targetLockEntity != null) {
            AABB box = targetLockEntity.getDimensions(targetLockEntity.getPose()).makeBoundingBox(targetLockEntity.getPosition(partialTicks));
            Rotation rotation = RotationUtils.getRotation(mc.player.getEyePosition(partialTicks), box.getCenter());
            mc.player.setXRot(rotation.xRot());
            mc.player.setYRot(rotation.yRot());
        }
    }

    private void onPlayerTurnByMouse(PlayerTurnByMouseEvent event) {
        if (targetLockEntity != null) {
            event.cancel();
        }
    }

    public Entity getAimTarget() {
        return aimTarget;
    }

    /**
     * Extra yaw, in degrees, that the mouse handler adds to the player turn so
     * the crosshair converges on the current aim rotation.
     */
    public double getYawCorrection() {
        updateCorrections();
        return yawCorrection;
    }

    /**
     * Extra pitch, in degrees, that the mouse handler adds to the player turn.
     */
    public double getPitchCorrection() {
        updateCorrections();
        return pitchCorrection;
    }

    private void updateAimCorrection() {
        AimAssistConfig config = ConfigStore.instance.getConfig().aimAssist;

        if (!config.aimCorrection || mc.player == null || mc.level == null) {
            aimTarget = null;
            aimRotation = null;
            releaseCombatClick();
            return;
        }
        if (mc.screen != null) {
            aimTarget = null;
            aimRotation = null;
            releaseCombatClick();
            return;
        }
        if (!config.aimWhileBlocking && mc.player.isUsingItem()) {
            aimTarget = null;
            aimRotation = null;
            releaseCombatClick();
            return;
        }

        // keep the current target while it stays valid, otherwise re-acquire
        updateAimTarget();

        computeAimRotation();
        updateCombatClick();
        logDiagnostics();
    }

    /**
     * Resolves the aim point once per tick. {@link #resolveAimPoint} raycasts up
     * to four times, so it must not run per consumer.
     */
    private void updateAimTarget() {
        aimPoint = null;
        if (aimTarget != null && getInvalidReason(aimTarget) == null) {
            aimPoint = resolveAimPoint(aimTarget);
            if (aimPoint != null) {
                return;
            }
            // LOS can be lost mid-lock, e.g. the target steps behind cover
            aimTarget = null;
        }

        aimTarget = findAimTarget();
    }

    /**
     * Steps the current rotation toward the aim point by at most
     * rotationSpeed degrees per tick. The mouse handler then closes whatever
     * gap is left, so the crosshair lands exactly on the aim point.
     */
    private void computeAimRotation() {
        if (aimTarget == null || mc.player == null) {
            aimRotation = null;
            return;
        }

        AimAssistConfig config = ConfigStore.instance.getConfig().aimAssist;
        Rotation current = new Rotation(mc.player.getXRot(), mc.player.getYRot());
        Rotation target = RotationUtils.getRotation(mc.player.getEyePosition(), aimPoint);

        double deltaX = Mth.wrapDegrees(target.xRot() - current.xRot());
        double deltaY = Mth.wrapDegrees(target.yRot() - current.yRot());
        double step = config.rotationSpeed / TICKS_PER_SECOND;
        double length = Math.sqrt(deltaX * deltaX + deltaY * deltaY);
        if (length <= step) {
            aimRotation = target;
            return;
        }

        double factor = step / length;
        aimRotation = new Rotation(
                (float) (current.xRot() + deltaX * factor),
                (float) (current.yRot() + deltaY * factor));
    }

    private void updateCorrections() {
        AimAssistConfig config = ConfigStore.instance.getConfig().aimAssist;
        if (!config.aimCorrection || aimRotation == null || mc.player == null) {
            yawCorrection = 0;
            pitchCorrection = 0;
            return;
        }

        double deltaYaw = Mth.wrapDegrees(aimRotation.yRot() - mc.player.getYRot());
        double deltaPitch = Mth.wrapDegrees(aimRotation.xRot() - mc.player.getXRot());
        if (Math.hypot(deltaYaw, deltaPitch) <= config.precision) {
            yawCorrection = 0;
            pitchCorrection = 0;
            return;
        }

        yawCorrection = deltaYaw;
        pitchCorrection = deltaPitch;
        debugCorrections++;
    }

    private void updateCombatClick() {
        AimAssistConfig config = ConfigStore.instance.getConfig().aimAssist;
        long now = System.nanoTime();

        boolean aligned = config.combatMode
                && aimTarget != null
                && aimPoint != null
                && mc.player != null
                && mc.level != null
                && mc.screen == null
                && mc.isWindowActive()
                && getAngleTo(aimPoint) <= config.attackTolerance;
        if (!aligned) {
            releaseCombatClick();
            combatTarget = null;
            combatAligned = false;
            return;
        }

        boolean firstShot = aimTarget != combatTarget || !combatAligned;
        if (firstShot) {
            releaseCombatClick();
            nextCombatClickNanos = now + config.firstAttackDelay * 1_000_000L;
        }
        combatTarget = aimTarget;
        combatAligned = true;

        if (config.isHoldMode()) {
            if (now < nextCombatClickNanos) {
                releaseCombatClick();
                return;
            }
            if (!combatHolding) {
                KeyMapping.set(getAttackKey(), true);
                combatHolding = true;
            }
            return;
        }

        if (combatHolding) {
            KeyMapping.set(getAttackKey(), false);
            combatHolding = false;
            return;
        }
        if (now < nextCombatClickNanos) {
            return;
        }

        KeyMapping.click(getAttackKey());
        nextCombatClickNanos =
                now + (long) (1_000_000_000D / config.clicksPerSecond);
    }

    private void releaseCombatClick() {
        if (!combatHolding) {
            return;
        }

        KeyMapping.set(getAttackKey(), false);
        combatHolding = false;
    }

    private InputConstants.Key getAttackKey() {
        return ((KeyMappingAccessor) mc.options.keyAttack).getKey_CU();
    }

    /**
     * Single source of truth for "can this entity be aimed at", shared by
     * target selection, combat mode and debug logging so they can never
     * disagree.
     */
    private String getInvalidReason(Entity entity) {
        AimAssistConfig config = ConfigStore.instance.getConfig().aimAssist;

        if (entity == null) {
            return "no_target";
        }
        if (mc.player == null || mc.level == null) {
            return "no_player";
        }
        if (entity == mc.player) {
            return "self";
        }
        if (entity.isRemoved()) {
            return "removed";
        }
        if (!(entity instanceof LivingEntity living)) {
            return "not_living";
        }
        if (living.getHealth() <= 0) {
            return "dead";
        }
        if (entity.isSpectator()) {
            return "spectator";
        }
        if (config.filterTeammates && TeamUtils.isTeammate(mc.player, entity)) {
            return "teammate";
        }
        if (mc.player.distanceToSqr(entity) > config.range * config.range) {
            return "outside_range";
        }

        Vec3 point = config.checkLineOfSight ? resolveAimPoint(entity) : preferredAimPoint(entity);
        if (point == null) {
            return "no_line_of_sight";
        }

        if (config.fov < 360) {
            Rotation current = new Rotation(mc.player.getXRot(), mc.player.getYRot());
            Rotation toEntity = RotationUtils.getRotation(mc.player.getEyePosition(), point);
            if (current.distanceSqrTo(toEntity) > Math.pow(config.fov / 2.0, 2)) {
                return "outside_fov";
            }
        }
        return null;
    }

    private Entity findAimTarget() {
        if (mc.player == null) {
            return null;
        }

        AimAssistConfig config = ConfigStore.instance.getConfig().aimAssist;
        Entity best = null;
        Vec3 bestPoint = null;
        double bestAngleSqr = Double.MAX_VALUE;
        for (Entity entity : mc.player.clientLevel.entitiesForRendering()) {
            if (getInvalidReason(entity) != null) {
                continue;
            }

            Vec3 point = config.checkLineOfSight
                    ? resolveAimPoint(entity)
                    : preferredAimPoint(entity);
            if (point == null) {
                continue;
            }

            double angleSqr = new Rotation(mc.player.getXRot(), mc.player.getYRot())
                    .distanceSqrTo(RotationUtils.getRotation(mc.player.getEyePosition(), point));
            if (angleSqr < bestAngleSqr) {
                bestAngleSqr = angleSqr;
                best = entity;
                bestPoint = point;
            }
        }

        if (best != null) {
            aimPoint = bestPoint;
        }
        return best;
    }

    /**
     * Point on the hitbox the selected {@link AimAssistConfig#aimAt} mode asks
     * for, ignoring whether blocks block it.
     */
    private Vec3 preferredAimPoint(Entity entity) {
        AimAssistConfig config = ConfigStore.instance.getConfig().aimAssist;
        return switch (config.aimAt) {
            case AimAssistConfig.AimAtHead -> entity.getEyePosition();
            case AimAssistConfig.AimAtCenter -> entity.getBoundingBox().getCenter();
            case AimAssistConfig.AimAtFeet -> entity.position().add(0, 0.001, 0);
            default -> closestAimPoint(entity);
        };
    }

    private Vec3 closestAimPoint(Entity entity) {
        Vec3 eyes = mc.player.getEyePosition();
        AABB box = entity.getBoundingBox();
        if (box.contains(eyes)) {
            return eyes;
        }

        // nearest point of the hitbox to the eyes, so the crosshair only has to
        // travel to whatever part of the target is actually exposed
        return new Vec3(
                MathUtils.clamp(eyes.x, box.minX, box.maxX),
                MathUtils.clamp(eyes.y, box.minY, box.maxY),
                MathUtils.clamp(eyes.z, box.minZ, box.maxZ));
    }

    /**
     * Aim point that is actually reachable, or null when the whole hitbox is
     * behind blocks. Candidates are ordered by the selected aim mode, but any
     * exposed part still makes the target attackable - checking only one point
     * would reject partially covered targets that vanilla can still hit.
     */
    private Vec3 resolveAimPoint(Entity entity) {
        Vec3 candidate = preferredAimPoint(entity);
        if (candidate != null && hasLineOfSight(candidate)) {
            return candidate;
        }

        for (Vec3 fallback : fallbackAimPoints(entity)) {
            if (hasLineOfSight(fallback)) {
                return fallback;
            }
        }
        return null;
    }

    private List<Vec3> fallbackAimPoints(Entity entity) {
        List<Vec3> points = new ArrayList<>(List.of(
                entity.getEyePosition(),
                entity.getBoundingBox().getCenter(),
                entity.position().add(0, 0.001, 0)));
        Vec3 preferred = preferredAimPoint(entity);
        points.removeIf(point -> point.distanceToSqr(preferred) < 1e-6);
        return points;
    }

    private double getAngleTo(Vec3 point) {
        if (mc.player == null) {
            return Double.MAX_VALUE;
        }

        Rotation current = new Rotation(mc.player.getXRot(), mc.player.getYRot());
        return Math.sqrt(current.distanceSqrTo(
                RotationUtils.getRotation(mc.player.getEyePosition(), point)));
    }

    private boolean hasLineOfSight(Vec3 point) {
        ClipContext context = new ClipContext(mc.player.getEyePosition(), point,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player);
        return mc.level.clip(context).getType() == HitResult.Type.MISS;
    }

    private void logDiagnostics() {
        AimAssistConfig config = ConfigStore.instance.getConfig().aimAssist;
        if (!config.debugLogging) {
            resetDebugCounters();
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastDebugLogTime < DEBUG_INTERVAL_MS) {
            return;
        }
        lastDebugLogTime = now;

        Map<String, Integer> rejections = new LinkedHashMap<>();
        int living = 0;
        int valid = 0;
        if (mc.player != null && mc.level != null) {
            for (Entity entity : mc.player.clientLevel.entitiesForRendering()) {
                if (!(entity instanceof LivingEntity) || entity == mc.player) {
                    continue;
                }
                living++;

                String reason = getInvalidReason(entity);
                if (reason == null) {
                    valid++;
                } else {
                    rejections.merge(reason, 1, Integer::sum);
                }
            }
        }

        LOGGER.info("[AimAssistDebug] range={} fov={} speed={} precision={} los={} teammates={} combat={} fireMode={} cps={} firstDelay={} tolerance={} living={} valid={} target={} rejections={} corrections={}",
                config.range, config.fov, config.rotationSpeed, config.precision,
                config.checkLineOfSight, config.filterTeammates, config.combatMode,
                config.combatFireMode, config.clicksPerSecond, config.firstAttackDelay,
                config.attackTolerance, living, valid,
                aimTarget == null ? "none" : aimTarget.getName().getString(),
                rejections, debugCorrections);
        resetDebugCounters();
    }

    private void resetDebugCounters() {
        debugCorrections = 0;
    }

    private Entity findTarget(Rotation playerRot) {
        assert mc.player != null;

        Entity target = null;
        double bestDeltaAngleSqr = Double.MAX_VALUE;
        for (Entity entity : mc.player.clientLevel.entitiesForRendering()) {
            if (entity == mc.player) {
                continue;
            }
            if (entity instanceof LivingEntity) {
                Vec3 center = getEntityCenter(entity);
                Rotation rotation = RotationUtils.getRotation(mc.player.getEyePosition(), center);
                double deltaAngleSqr = playerRot.distanceSqrTo(rotation);
                if (deltaAngleSqr < bestDeltaAngleSqr) {
                    bestDeltaAngleSqr = deltaAngleSqr;
                    target = entity;
                }
            }
        }

        return target;
    }

    private List<Rotation> findRotations(LocalPlayer player, float speed) {
        assert bowAssistTarget != null;

        Rotation straight = RotationUtils.getRotation(player.getEyePosition(), bowAssistTarget.position());
        Rotation rot1 = findRotation(player, speed, straight.withXRot(-90));
        Rotation rot2 = findRotation(player, speed, straight.withXRot(90));
        if (rot1 == null && rot2 == null) {
            return List.of();
        }
        if (rot1 == null) {
            return List.of(rot2);
        }
        if (rot2 == null) {
            return List.of(rot1);
        }
        if (rot1.approximateEquals(rot2, 0.05F)) {
            return List.of(rot1);
        }
        return List.of(rot1, rot2);
    }

    private Rotation findRotation(LocalPlayer player, float speed, Rotation initial) {
        Rotation rotation = initial;
        double bestDistance = calculatePath(player, speed, rotation.yRot(), rotation.xRot()).getClosestDistance();

        float delta = 5;
        while (delta > 0.02F) {
            Path path;
            boolean changed = false;

            path = calculatePath(player, speed, rotation.yRot() + delta, rotation.xRot());
            if (path.getClosestDistance() < bestDistance) {
                rotation = rotation.addYRot(delta);
                bestDistance = path.getClosestDistance();
                changed = true;
            }

            path = calculatePath(player, speed, rotation.yRot() - delta, rotation.xRot());
            if (path.getClosestDistance() < bestDistance) {
                rotation = rotation.addYRot(-delta);
                bestDistance = path.getClosestDistance();
                changed = true;
            }

            if (rotation.xRot() + delta <= 90) {
                path = calculatePath(player, speed, rotation.yRot(), rotation.xRot() + delta);
                if (path.getClosestDistance() < bestDistance) {
                    rotation = rotation.addXRot(delta);
                    bestDistance = path.getClosestDistance();
                    changed = true;
                }
            }

            if (rotation.xRot() - delta >= -90) {
                path = calculatePath(player, speed, rotation.yRot(), rotation.xRot() - delta);
                if (path.getClosestDistance() < bestDistance) {
                    rotation = rotation.addXRot(-delta);
                    bestDistance = path.getClosestDistance();
                    changed = true;
                }
            }

            if (!changed) {
                delta /= 5;
            }
        }

        if (bestDistance < bowAssistTarget.getBbWidth() / 2) {
            return rotation;
        } else {
            return null;
        }
    }

    private Path calculatePath(LocalPlayer player, float speed, float yRot, float xRot) {
        float speedX = -Mth.sin(yRot * ((float)Math.PI / 180F)) * Mth.cos(xRot * ((float)Math.PI / 180F));
        float speedY = -Mth.sin(xRot * ((float)Math.PI / 180F));
        float speedZ = Mth.cos(yRot * ((float)Math.PI / 180F)) * Mth.cos(xRot * ((float)Math.PI / 180F));
        Vec3 deltaMovement = new Vec3(speedX, speedY, speedZ)
                .normalize()
                .scale(speed)
                .add(ServerBehavior.predictPlayerKnownMovement());

        Path path = new Path();
        path.entityPosition[0] = getEntityCenter(bowAssistTarget);
        path.arrowPosition[0] = player.getEyePosition();

        Vec3 entityDeltaMovement = getEntitySpeed(bowAssistTarget);
        for (int i = 1; i < 200; i++) {
            path.entityPosition[i] = path.entityPosition[i - 1].add(entityDeltaMovement);
            path.arrowPosition[i] = path.arrowPosition[i - 1].add(deltaMovement);
            deltaMovement = deltaMovement.scale(0.99F).add(0, -0.05, 0);
        }

        return path;
    }

    private Vec3 getEntityCenter(Entity entity) {
        return entity.position().add(0, entity.getBbHeight() / 2, 0);
    }

    private Vec3 getEntitySpeed(Entity entity) {
        Entity vehicle = entity.getVehicle();
        if (vehicle != null) {
            return getEntitySpeed(vehicle);
        }

        return new Vec3(entity.getX() - entity.xo, entity.getY() - entity.yo, entity.getZ() - entity.zo);
    }

    private static class Path {

        public final Vec3[] entityPosition;
        public final Vec3[] arrowPosition;

        public Path() {
            entityPosition = new Vec3[200];
            arrowPosition = new Vec3[200];
        }

        public double getClosestDistance() {
            double closest = Double.MAX_VALUE;
            for (int i = 1; i < 200; i++) {
                double distanceSqr = getPointToLineSegmentDistanceSqr(entityPosition[i], arrowPosition[i - 1], arrowPosition[i]);
                if (distanceSqr < closest) {
                    closest = distanceSqr;
                }
            }
            return Math.sqrt(closest);
        }

        private double getPointToLineSegmentDistanceSqr(Vec3 point, Vec3 line1, Vec3 line2) {
            Vec3 lineVector = line2.subtract(line1);
            Vec3 pointVec1 = point.subtract(line1);

            // Point is lagging behind start of the segment, so perpendicular distance is not viable.
            // Use distance to start of segment instead.
            if (pointVec1.dot(lineVector) <= 0) {
                return pointVec1.lengthSqr();
            }

            Vec3 pointVec2 = point.subtract(line2);

            // Point is advanced past the end of the segment, so perpendicular distance is not viable.
            // Use distance to end of the segment instead.
            if (pointVec2.dot(lineVector) >= 0) {
                return pointVec2.lengthSqr();
            }

            return lineVector.cross(pointVec1).lengthSqr() / lineVector.lengthSqr();
        }
    }
}