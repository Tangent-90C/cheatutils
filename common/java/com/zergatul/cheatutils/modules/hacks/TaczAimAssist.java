package com.zergatul.cheatutils.modules.hacks;

import com.zergatul.cheatutils.ballistics.BallisticCalibrationManager;
import com.zergatul.cheatutils.ballistics.BallisticCalibrationManager.BallisticParameters;
import com.zergatul.cheatutils.ballistics.BallisticSolver;
import com.zergatul.cheatutils.ballistics.WeaponFingerprint;
import com.zergatul.cheatutils.collections.ImmutableList;
import com.zergatul.cheatutils.common.Events;
import com.zergatul.cheatutils.common.Registries;
import com.zergatul.cheatutils.common.TaczCompat;
import com.zergatul.cheatutils.common.events.RenderGuiEvent;
import com.zergatul.cheatutils.common.events.RenderTickStartEvent;
import com.zergatul.cheatutils.common.events.RenderWorldLastEvent;
import com.zergatul.cheatutils.common.events.PlayerTurnByMouseEvent;
import com.zergatul.cheatutils.configs.ConfigStore;
import com.zergatul.cheatutils.configs.TaczAimAssistConfig;
import com.zergatul.cheatutils.configs.TaczAimAssistConfig.TargetEntityEntry;
import com.zergatul.cheatutils.configs.TaczAimAssistConfig.PenetrableBlockEntry;
import com.zergatul.cheatutils.modules.Module;
import com.zergatul.cheatutils.modules.utilities.RenderUtilities;
import com.zergatul.cheatutils.render.LineRenderer;
import com.zergatul.cheatutils.utils.Rotation;
import com.zergatul.cheatutils.utils.RotationUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@NullMarked
public class TaczAimAssist implements Module {

    private static final Logger LOGGER = LogManager.getLogger(TaczAimAssist.class);
    private static final double BLOCK_SAMPLE_STEP = 0.25;
    private static final long SHOOT_ORIGIN_SYNC_INTERVAL_MS = 1500;

    public static final TaczAimAssist instance = new TaczAimAssist();

    private final Minecraft mc = Minecraft.getInstance();

    private final BallisticCalibrationManager calibration;
    private final Map<Integer, Entity> seenProjectiles = new HashMap<>();
    private @Nullable EntityType<?> taczBulletType;

    private @Nullable Entity target;
    private boolean targetLocked;
    private boolean targetLockEnabled = true;
    private long lastShootOriginSync;
    private @Nullable Vec3 lastShootOriginSyncPos;

    private ImmutableList<TargetEntityEntry> cachedTargetEntities;
    private Map<String, Boolean> targetEntries = Map.of();
    private ImmutableList<PenetrableBlockEntry> cachedPenetrableBlocks;
    private Set<Block> penetrableBlocks = Set.of();

    private TaczAimAssist() {
        calibration = new BallisticCalibrationManager(
                mc,
                getDataFile(),
                () -> ConfigStore.instance.getConfig().taczAimAssist.automaticCalibration,
                () -> ConfigStore.instance.getConfig().taczAimAssist.debugLogging);
        calibration.initialize();

        Events.InGameTickEnd.add(this::onTickEnd);
        Events.RenderTickStart.add(this::onRenderTickStart);
        Events.PlayerTurnByMouse.add(this::onPlayerTurnByMouse);
        Events.RenderWorldLast.add(this::onRenderWorldLast);
        Events.PostRenderGui.add(this::onPostRenderGui);
        Events.WorldUnload.add(calibration::onWorldUnload);
        Events.Close.add(calibration::onClientStopping);
    }

    private static Path getDataFile() {
        File configDir = new File(Minecraft.getInstance().gameDirectory, "config");
        if (!configDir.exists() && !configDir.mkdirs()) {
            LOGGER.error("Cannot create config directory");
        }
        return new File(configDir, "cheatutils.tacz-aim-assist.json").toPath();
    }

    private TaczAimAssistConfig getConfig() {
        return ConfigStore.instance.getConfig().taczAimAssist;
    }

    private void onTickEnd() {
        if (mc.player == null || mc.level == null) {
            return;
        }

        TaczAimAssistConfig config = getConfig();
        refreshCaches(config);
        if (config.syncShootOrigin) {
            syncShootOrigin();
        }
        if (!config.enabled && !config.automaticCalibration && !config.taczTrajectory) {
            return;
        }

        scanProjectiles();
        calibration.onClientTick();

        if (config.enabled && targetLockEnabled) {
            updateTarget(config);
        } else {
            target = null;
        }
    }

    /**
     * TaCZ spawns bullets from the position the gun was drawn at and only refreshes that
     * origin when a gun is drawn, so after flying, falling or travelling far the bullets keep
     * spawning from the old spot. Re-sending the draw message re-snapshots the origin at the
     * current position. Each sync costs the gun's put-away time before the next shot goes
     * through, so it is done while repositioning - once every 1.5 seconds at most, and only
     * after leaving the previous synced position - instead of while firing.
     */
    private void syncShootOrigin() {
        assert mc.player != null && mc.level != null;

        if (mc.player.onGround()
                || !TaczCompat.holdsTaczGun(mc.player.getItemInHand(InteractionHand.MAIN_HAND))) {
            lastShootOriginSyncPos = null;
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastShootOriginSync < SHOOT_ORIGIN_SYNC_INTERVAL_MS) {
            return;
        }

        Vec3 position = mc.player.position();
        if (lastShootOriginSyncPos != null
                && position.distanceToSqr(lastShootOriginSyncPos) <= 2 * 2) {
            return;
        }
        if (TaczCompat.syncShootOrigin(mc.player)) {
            lastShootOriginSync = now;
            lastShootOriginSyncPos = position;
        }
    }

    private void scanProjectiles() {
        assert mc.level != null;

        // Bullets that leave the render distance without being removed stay in the map;
        // cap it so a long session cannot grow it without bound.
        if (seenProjectiles.size() > 512) {
            seenProjectiles.clear();
        }
        seenProjectiles.values().removeIf(Entity::isRemoved);
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (isTaczBullet(entity) && !seenProjectiles.containsKey(entity.getId())) {
                seenProjectiles.put(entity.getId(), entity);
                calibration.onEntityAdded(entity);
            }
        }
    }

    private boolean isTaczBullet(Entity entity) {
        if (taczBulletType == null) {
            taczBulletType = Registries.ENTITY_TYPES.getValue(new ResourceLocation("tacz:bullet"));
        }
        return taczBulletType != null && entity.getType() == taczBulletType;
    }

    private void refreshCaches(TaczAimAssistConfig config) {
        if (cachedTargetEntities != config.targetEntities) {
            cachedTargetEntities = config.targetEntities;
            Map<String, Boolean> entries = new HashMap<>();
            for (TaczAimAssistConfig.TargetEntityEntry entry : config.targetEntities) {
                if (entry != null && entry.id != null && !entry.id.isBlank()) {
                    entries.putIfAbsent(entry.id, entry.enabled);
                }
            }
            targetEntries = entries;
        }
        if (cachedPenetrableBlocks != config.penetrableBlocks) {
            cachedPenetrableBlocks = config.penetrableBlocks;
            Set<Block> blocks = new HashSet<>();
            for (PenetrableBlockEntry entry : config.penetrableBlocks) {
                if (entry != null && entry.block != null && entry.enabled) {
                    blocks.add(entry.block);
                }
            }
            penetrableBlocks = blocks;
        }
    }

    private boolean isTargetEntity(Entity entity) {
        String typeId = Registries.ENTITY_TYPES.getKey(entity.getType()).toString();
        MobCategory category = entity.getType().getCategory();
        for (Map.Entry<String, Boolean> entry : targetEntries.entrySet()) {
            if (!entry.getValue()) {
                continue;
            }
            String id = entry.getKey();
            if (id.startsWith(TaczAimAssistConfig.GROUP_PREFIX)) {
                if (matchesGroup(id.substring(TaczAimAssistConfig.GROUP_PREFIX.length()), category)) {
                    return true;
                }
            } else if (id.equals(typeId)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesGroup(String groupId, MobCategory category) {
        return category != null && category.name().toLowerCase(Locale.ROOT).equals(groupId);
    }

    private void updateTarget(TaczAimAssistConfig config) {
        assert mc.player != null && mc.level != null;

        if (target != null && !isValidTarget(config, target)) {
            target = null;
        }
        if (target == null || config.switchTargets) {
            target = findBestTarget(config);
        }
    }

    private boolean isValidTarget(TaczAimAssistConfig config, Entity entity) {
        assert mc.player != null && mc.level != null;

        if (entity == mc.player || entity.isRemoved() || mc.level != entity.level()) {
            return false;
        }
        if (!isTargetEntity(entity)) {
            return false;
        }

        Vec3 eyes = mc.player.getEyePosition();
        double distanceSqr = eyes.distanceToSqr(entity.getEyePosition());
        if (distanceSqr > config.range * config.range) {
            return false;
        }

        Vec3 direction = entity.getEyePosition().subtract(eyes);
        double angle = angleBetween(mc.player.getViewVector(1.0F), direction);
        if (angle > config.fov / 2) {
            return false;
        }
        return config.checkLineOfSight
                ? resolveAimPoint(entity, 1.0F, eyes, config.aimAtMode) != null
                : true;
    }

    private @Nullable Entity findBestTarget(TaczAimAssistConfig config) {
        assert mc.player != null && mc.level != null;

        Vec3 eyes = mc.player.getEyePosition();
        Vec3 look = mc.player.getViewVector(1.0F);

        Entity best = null;
        double bestAngle = Double.MAX_VALUE;
        double bestDistance = Double.MAX_VALUE;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity == mc.player || !isTargetEntity(entity)) {
                continue;
            }

            Vec3 eyesPosition = entity.getEyePosition();
            double distanceSqr = eyes.distanceToSqr(eyesPosition);
            if (distanceSqr > config.range * config.range) {
                continue;
            }

            double angle = angleBetween(look, eyesPosition.subtract(eyes));
            if (angle > config.fov / 2) {
                continue;
            }

            Vec3 aimPoint = config.checkLineOfSight
                    ? resolveAimPoint(entity, 1.0F, eyes, config.aimAtMode)
                    : getAimPoint(entity, 1.0F, config.aimAtMode);
            if (aimPoint == null) {
                continue;
            }
            angle = angleBetween(look, aimPoint.subtract(eyes));
            if (angle > config.fov / 2) {
                continue;
            }

            boolean better;
            if (TaczAimAssistConfig.TARGET_PRIORITY_DISTANCE.equals(config.targetPriority)) {
                better = distanceSqr < bestDistance;
            } else {
                better = angle < bestAngle;
            }
            if (better) {
                best = entity;
                bestAngle = angle;
                bestDistance = distanceSqr;
            }
        }

        return best;
    }

    private static double angleBetween(Vec3 first, Vec3 second) {
        double length = first.length() * second.length();
        if (length < 1.0E-4) {
            return 0;
        }
        double dot = first.dot(second) / length;
        return Math.toDegrees(Math.acos(Mth.clamp(dot, -1.0, 1.0)));
    }

    /**
     * Walks the ray in fixed steps and treats every block in the penetrable list as
     * pass-through, so leaves, glass, iron bars and similar blocks don't hide a target.
     * Sampling is used instead of clipping from each penetrated block because a clip
     * starting inside a collision shape can skip that shape, which would let a solid
     * block right behind a penetrable one be ignored.
     */
    private boolean isBlocked(Vec3 from, Vec3 to) {
        assert mc.level != null;

        Vec3 direction = to.subtract(from);
        double length = direction.length();
        if (length < 1.0E-4) {
            return false;
        }
        // Any ray that travels more than the step size inside a block is always sampled;
        // only rays grazing a block corner can slip between two samples.
        Vec3 step = direction.scale(BLOCK_SAMPLE_STEP / length);
        int steps = (int) Math.ceil(length / BLOCK_SAMPLE_STEP);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 0; i <= steps; i++) {
            Vec3 point = from.add(step.scale(i));
            pos.set(point.x, point.y, point.z);
            BlockState state = mc.level.getBlockState(pos);
            if (state.isAir() || penetrableBlocks.contains(state.getBlock())) {
                continue;
            }
            return true;
        }
        return false;
    }

    public boolean isTargetLockEnabled() {
        return targetLockEnabled;
    }

    public void enableTargetLock() {
        targetLockEnabled = true;
    }

    public void disableTargetLock() {
        targetLockEnabled = false;
        target = null;
        targetLocked = false;
    }

    public @Nullable Entity getTarget() {
        return target;
    }

    private void onRenderTickStart(RenderTickStartEvent event) {
        TaczAimAssistConfig config = getConfig();
        if (!config.enabled || !targetLockEnabled || mc.player == null
                || target == null || target.isRemoved()) {
            targetLocked = false;
            return;
        }

        ItemStack stack = mc.player.getItemInHand(InteractionHand.MAIN_HAND);
        Optional<WeaponFingerprint> fingerprint = WeaponFingerprint.create(mc, stack);
        if (config.onlyAimWithGuns && fingerprint.isEmpty()) {
            targetLocked = false;
            return;
        }

        float partialTicks = event.getPartialTicks();
        Rotation rotation = solveAimRotation(config, target, partialTicks);
        if (rotation == null) {
            targetLocked = false;
            return;
        }

        mc.player.setYRot(rotation.yRot());
        mc.player.setXRot(rotation.xRot());
        targetLocked = true;
    }

    private void onPlayerTurnByMouse(PlayerTurnByMouseEvent event) {
        if (targetLocked) {
            event.cancel();
        }
    }

    private @Nullable Rotation solveAimRotation(TaczAimAssistConfig config, Entity entity, float partialTicks) {
        assert mc.player != null;

        Vec3 eyes = mc.player.getEyePosition(partialTicks);
        Vec3 targetNow = resolveAimPoint(entity, partialTicks, eyes, config.aimAtMode);
        if (targetNow == null) {
            return null;
        }

        Vec3 velocity = config.predictMovement ? entity.getDeltaMovement() : Vec3.ZERO;
        double latencyTicks = config.predictionTime * 20;

        BallisticParameters parameters = calibration.resolveCurrentParameters(
                config.automaticCalibration, config.useLocalGunData, config.bulletSpeed);
        if (parameters.muzzleSpeed() <= 0) {
            return RotationUtils.getRotation(eyes, targetNow.add(velocity.scale(latencyTicks)));
        }

        BallisticSolver.Input input = new BallisticSolver.Input(
                eyes,
                targetNow,
                velocity,
                mc.player.getDeltaMovement(),
                latencyTicks,
                config.shooterVelocityInheritance,
                parameters.muzzleSpeed(),
                parameters.drag(),
                parameters.gravity(),
                config.maxFlightTime * 20,
                config.allowedMissRadius);
        return BallisticSolver.solve(input)
                .map(solution -> new Rotation((float) solution.pitch(), (float) solution.yaw()))
                .orElseGet(() -> RotationUtils.getRotation(eyes,
                        targetNow.add(velocity.scale(latencyTicks))));
    }

    private static Vec3 getAimPoint(Entity entity, float partialTicks, String aimAtMode) {
        if (TaczAimAssistConfig.AIM_ASSIST_HEAD.equals(aimAtMode)) {
            return entity.getEyePosition(partialTicks);
        }
        AABB box = entity.getBoundingBox();
        return new Vec3(
                Mth.lerp(0.5, box.minX, box.maxX),
                box.minY + entity.getBbHeight() / 2,
                Mth.lerp(0.5, box.minZ, box.maxZ));
    }

    /**
     * Resolves the point to aim at on the target: the configured aim point when it has line
     * of sight, otherwise the nearest point on the hitbox that is visible. Returns null when
     * no part of the target is visible, so a target only partially behind cover is still
     * aimed at - just not at the configured point.
     */
    private @Nullable Vec3 resolveAimPoint(Entity entity, float partialTicks, Vec3 eyes, String aimAtMode) {
        Vec3 preferred = getAimPoint(entity, partialTicks, aimAtMode);
        if (!isBlocked(eyes, preferred)) {
            return preferred;
        }
        return findNearestVisiblePoint(eyes, entity.getBoundingBox());
    }

    private @Nullable Vec3 findNearestVisiblePoint(Vec3 eyes, AABB box) {
        List<Vec3> points = new ArrayList<>(19);
        addPoint(points, box, 0.5, 0.5, 1.0);
        addPoint(points, box, 0.5, 0.5, 0.0);
        addPoint(points, box, 0.0, 0.5, 0.5);
        addPoint(points, box, 1.0, 0.5, 0.5);
        addPoint(points, box, 0.5, 0.0, 0.5);
        addPoint(points, box, 0.5, 1.0, 0.5);
        addPoint(points, box, 0.5, 0.5, 0.5);
        for (int corner = 0; corner < 8; corner++) {
            addPoint(points, box,
                    (corner & 1) != 0 ? 1.0 : 0.0,
                    (corner & 2) != 0 ? 1.0 : 0.0,
                    (corner & 4) != 0 ? 1.0 : 0.0);
        }
        points.sort(Comparator.comparingDouble(eyes::distanceToSqr));
        for (Vec3 point : points) {
            if (!isBlocked(eyes, point)) {
                return point;
            }
        }
        return null;
    }

    private static void addPoint(List<Vec3> points, AABB box, double x, double y, double z) {
        points.add(new Vec3(
                Mth.lerp(x, box.minX, box.maxX),
                Mth.lerp(y, box.minY, box.maxY),
                Mth.lerp(z, box.minZ, box.maxZ)));
    }

    private void onRenderWorldLast(RenderWorldLastEvent event) {
        TaczAimAssistConfig config = getConfig();
        if (!config.taczTrajectory || mc.player == null || mc.level == null) {
            return;
        }

        ItemStack stack = mc.player.getItemInHand(InteractionHand.MAIN_HAND);
        if (WeaponFingerprint.create(mc, stack).isEmpty()) {
            return;
        }

        BallisticParameters parameters = calibration.resolveCurrentParameters(
                config.automaticCalibration, config.useLocalGunData, config.bulletSpeed);
        if (!isUsable(parameters)) {
            return;
        }
        if (config.taczTrajectoryMaxSpeed > 0
                && parameters.speedBlocksPerSecond() > config.taczTrajectoryMaxSpeed) {
            return;
        }

        float partialTicks = event.getTickDelta();
        float xRot = mc.player.getViewXRot(partialTicks);
        float yRot = mc.player.getViewYRot(partialTicks);
        Vec3 start = mc.player.getEyePosition(partialTicks);
        Vec3 initialVelocity = startingMotion(yRot, xRot, parameters.muzzleSpeed())
                .add(mc.player.getDeltaMovement().scale(config.shooterVelocityInheritance));

        double maxTicks = config.maxFlightTime * 20;
        int steps = (int) Math.ceil(maxTicks);
        Vec3 previous = start;
        LineRenderer renderer = RenderUtilities.instance.getLineRenderer();
        renderer.begin(event, true);
        for (int step = 1; step <= steps; step++) {
            double time = Math.min(step, maxTicks);
            Vec3 next = BallisticSolver.projectilePosition(
                    start, initialVelocity, parameters.drag(), parameters.gravity(), time);

            if (hasCollision(previous, next)) {
                renderer.line(
                        previous.x, previous.y, previous.z,
                        next.x, next.y, next.z,
                        1.0F, 0.3F, 0.3F, 1.0F);
                break;
            }

            renderer.line(
                    previous.x, previous.y, previous.z,
                    next.x, next.y, next.z,
                    1.0F, 1.0F, 1.0F, 1.0F);
            previous = next;
        }
        renderer.end();
    }

    private boolean hasCollision(Vec3 from, Vec3 to) {
        if (isBlocked(from, to)) {
            return true;
        }
        assert mc.level != null && mc.player != null;

        AABB box = new AABB(from, to).inflate(1.0);
        return ProjectileUtil.getEntityHitResult(
                mc.level, mc.player, from, to, box,
                entity -> entity != mc.player && isTargetEntity(entity)) != null;
    }

    private static boolean isUsable(BallisticParameters parameters) {
        return Double.isFinite(parameters.muzzleSpeed())
                && parameters.muzzleSpeed() > 0
                && Double.isFinite(parameters.drag()) && parameters.drag() > 0
                && Double.isFinite(parameters.gravity())
                && parameters.gravity() >= 0;
    }

    private static Vec3 startingMotion(float yRot, float xRot, double speed) {
        float motionX = -Mth.sin(yRot * ((float) Math.PI / 180F)) * Mth.cos(xRot * ((float) Math.PI / 180F));
        float motionY = -Mth.sin(xRot * ((float) Math.PI / 180F));
        float motionZ = Mth.cos(yRot * ((float) Math.PI / 180F)) * Mth.cos(xRot * ((float) Math.PI / 180F));
        return new Vec3(motionX, motionY, motionZ).normalize().scale(speed);
    }

    private void onPostRenderGui(RenderGuiEvent event) {
        TaczAimAssistConfig config = getConfig();
        if (!config.calibrationHud || (!config.enabled && !config.taczTrajectory)) {
            return;
        }
        if (!config.automaticCalibration && !config.useLocalGunData) {
            return;
        }

        BallisticParameters parameters = calibration.resolveCurrentParameters(
                config.automaticCalibration, config.useLocalGunData, config.bulletSpeed);
        boolean weaponDetected = parameters.fingerprint() != null;
        String state;
        int stateColor;
        if (!weaponDetected) {
            state = "NO SUPPORTED GUN";
            stateColor = 0xFFFF5555;
        } else if (parameters.source() == BallisticCalibrationManager.ParameterSource.LOCAL_DATA) {
            state = "LOCAL DATA";
            stateColor = 0xFF55FF55;
        } else if (parameters.source() == BallisticCalibrationManager.ParameterSource.AUTOMATIC
                && parameters.fullModel()) {
            state = "ACTIVE";
            stateColor = 0xFF55FF55;
        } else if (parameters.source() == BallisticCalibrationManager.ParameterSource.AUTOMATIC_SPEED_ONLY) {
            state = "SPEED ONLY";
            stateColor = 0xFFFFFF55;
        } else {
            state = "WARMING UP " + parameters.acceptedShots() + "/"
                    + com.zergatul.cheatutils.ballistics.BallisticProfile.MIN_USABLE_SAMPLES;
            stateColor = 0xFFFFFF55;
        }

        java.util.List<String> lines = new java.util.ArrayList<>();
        lines.add("Gun Auto Calibration - " + state);
        String aimState = TaczAlwaysAim.instance.describeAimState();
        if (aimState != null) {
            lines.add("Server aim: " + aimState);
        }
        if (target != null) {
            lines.add("Aiming at: " + target.getName().getString() + " ("
                    + Registries.ENTITY_TYPES.getKey(target.getType()) + ")");
        } else {
            lines.add("Aiming at: nothing");
        }
        if (weaponDetected) {
            lines.add("Weapon: " + parameters.fingerprint().gunId() + " ["
                    + emptyAsUnknown(parameters.fingerprint().fireMode()) + "]");
            lines.add("Profile: " + shortHash(parameters.fingerprint().dataHash())
                    + "  |  Server: " + parameters.fingerprint().serverId());
        } else {
            lines.add("Weapon: not detected in main hand");
        }
        lines.add("Effective source: " + parameters.source().toString().replace('_', ' '));
        lines.add("Effective s: " + format(parameters.speedBlocksPerSecond())
                + " blocks/s  |  " + format(parameters.muzzleSpeed()) + " blocks/tick");
        lines.add("Effective k: " + formatSix(parameters.drag())
                + "  |  Effective g: " + formatSix(parameters.gravity()));
        if (config.useLocalGunData) {
            lines.add("Local data: " + calibration.getLastLocalLookup().source());
            if (!calibration.getLastLocalLookup().missingFields().isEmpty()) {
                lines.add("Missing fields: "
                        + String.join(", ", calibration.getLastLocalLookup().missingFields()));
            }
            lines.add("Gun files: " + calibration.getLocalDataStatus().gunCount()
                    + "  Parse errors: " + calibration.getLocalDataStatus().errors());
            lines.add("Scan: " + calibration.getLocalDataStatus().state());
        }
        if (parameters.acceptedShots() > 0) {
            lines.add("Calibrated s: " + format(parameters.calibratedMuzzleSpeed() * 20)
                    + " blocks/s  |  " + format(parameters.calibratedMuzzleSpeed()) + " blocks/tick");
            lines.add("Calibrated k: " + formatSix(parameters.calibratedDrag())
                    + "  |  Calibrated g: " + formatSix(parameters.calibratedGravity()));
        }
        lines.add("Samples: " + parameters.acceptedShots() + " shots, "
                + parameters.trajectorySamples() + " trajectory  |  " + parameters.confidence());
        lines.add("Stored profiles: " + calibration.getKnownProfileCount() + "  |  "
                + (calibration.hasPendingProfileSave() ? "SAVE PENDING" : "SAVED"));

        renderPanel(event.getGuiGraphics(), lines, stateColor);
    }

    private void renderPanel(net.minecraft.client.gui.GuiGraphics graphics, java.util.List<String> lines, int stateColor) {
        int padding = 5;
        int lineHeight = mc.font.lineHeight + 2;
        int width = 0;
        for (String line : lines) {
            width = Math.max(width, mc.font.width(line));
        }
        width += padding * 2 + 3;
        int height = lines.size() * lineHeight + padding * 2 - 2;
        int x = Math.max(2, graphics.guiWidth() - width - 6);
        int y = Math.max(2, graphics.guiHeight() - height - 6);

        graphics.fill(x, y, x + width, y + height, 0xC0000000);
        graphics.fill(x, y, x + 3, y + height, stateColor);
        for (int i = 0; i < lines.size(); i++) {
            graphics.drawString(mc.font, lines.get(i), x + padding + 3,
                    y + padding + i * lineHeight, i == 0 ? stateColor : 0xFFFFFFFF, true);
        }
    }

    private static String emptyAsUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    private static String shortHash(String hash) {
        if (hash == null || hash.isBlank()) {
            return "unknown";
        }
        return hash.substring(0, Math.min(12, hash.length()));
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    private static String formatSix(double value) {
        return String.format(java.util.Locale.ROOT, "%.6f", value);
    }
}
