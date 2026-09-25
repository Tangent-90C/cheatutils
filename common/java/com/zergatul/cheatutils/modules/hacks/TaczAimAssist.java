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
import net.minecraft.resources.ResourceLocation;import net.minecraft.tags.TagKey;import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.AbstractGlassBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.StainedGlassPaneBlock;
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
import java.util.Random;
import java.util.Set;

@NullMarked
public class TaczAimAssist implements Module {

    private static final Logger LOGGER = LogManager.getLogger(TaczAimAssist.class);
    private static final double BLOCK_SAMPLE_STEP = 0.25;
    /**
     * The tag TaCZ's own bullet ray-trace ignores (BlockRayTrace / ModBlocks.BULLET_IGNORE_BLOCKS).
     * Minecraft syncs tags from the server on join, so checking it client-side reflects what the
     * server's bullets actually do.
     */
    private static final TagKey<Block> BULLET_IGNORE_TAG =
            TagKey.create(net.minecraft.core.registries.Registries.BLOCK, new ResourceLocation("tacz", "bullet_ignore"));
    private static final long SHOOT_ORIGIN_SYNC_INTERVAL_MS = 1500;
    /** Debounce between single-shot auto fire pulls so a flickering target cannot drain the magazine. */
    private static final long AUTO_FIRE_MIN_INTERVAL_MS = 300;
    /** How often the held trigger is bounced in full-auto so an empty magazine can reach auto-reload. */
    private static final long AUTO_FIRE_TRIGGER_RESET_MS = 1000;
    /** Target must stay unengageable this many ticks before a reposition teleport fires. */
    private static final int REPOSITION_TRIGGER_TICKS = 10;
    private static final long REPOSITION_COOLDOWN_MS = 3000;
    private static final double REPOSITION_RING_STEP = 2;
    private static final int REPOSITION_AZIMUTHS = 16;
    /** Vertical reach of the standable-column scan, relative to the current feet level. */
    private static final int REPOSITION_MAX_UP = 20;
    private static final int REPOSITION_MAX_DOWN = 8;
    /** Standable levels examined per column, highest first. */
    private static final int REPOSITION_Y_PER_COLUMN = 3;
    /**
     * Tier-one spots: the eye ends up this far above the enemy's eye line - high ground that
     * shoots over walls and other cover - and keeps this much distance to the enemy.
     */
    private static final double REPOSITION_MIN_ELEVATION = 2;
    private static final double REPOSITION_PREFERRED_ENGAGE = 12;

    public static final TaczAimAssist instance = new TaczAimAssist();

    private final Minecraft mc = Minecraft.getInstance();

    private final BallisticCalibrationManager calibration;
    private final Map<Integer, Entity> seenProjectiles = new HashMap<>();
    private @Nullable EntityType<?> taczBulletType;

    private @Nullable Entity target;
    private boolean targetLocked;
    private boolean targetLockEnabled = true;
    private boolean lastRotationBallistic = true;
    private @Nullable Entity firedTarget;
    private long lastAutoFireShot;
    private boolean autoFireTrigger;
    private long lastAutoFireReset;
    private int repositionTicks;
    private long lastRepositionAt;
    private @Nullable Vec3 lastRepositionSpot;
    private final Random random = new Random();
    private long lastShootOriginSync;
    private @Nullable Vec3 lastShootOriginSyncPos;

    private ImmutableList<TargetEntityEntry> cachedTargetEntities;
    private Map<String, Boolean> targetEntries = Map.of();
    private ImmutableList<PenetrableBlockEntry> cachedPenetrableBlocks;
    private Set<Block> penetrableBlocks = Set.of();
    private Set<Block> blockingBlocks = Set.of();

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
            firedTarget = null;
            autoFireTrigger = false;
            return;
        }

        TaczAimAssistConfig config = getConfig();
        refreshCaches(config);
        if (config.syncShootOrigin) {
            syncShootOrigin(config);
        }

        if (config.enabled && targetLockEnabled) {
            updateTarget(config);
        } else {
            target = null;
        }

        updateAutoFire(config);
        updateReposition(config);

        if (!config.enabled && !config.automaticCalibration && !config.taczTrajectory) {
            return;
        }

        scanProjectiles();
        calibration.onClientTick();
    }

    /**
     * TaCZ spawns bullets from the position the gun was drawn at and only refreshes that
     * origin when a gun is drawn, so after flying, falling or travelling far the bullets keep
     * spawning from the old spot. Re-sending the draw message re-snapshots the origin at the
     * current position. Each sync costs the gun's put-away time before the next shot goes
     * through, so it is done while repositioning - once every 1.5 seconds at most, and only
     * after leaving the previous synced position - instead of while firing.
     */
    /**
     * The point bullets leave from - the eye position of the last gun draw - falling back to
     * the current eyes when TaCZ data is unavailable. All aiming, visibility and ballistic
     * math must use this, not the current eyes: after travelling, the bullet line and the
     * sight line diverge, which is what makes peeked shots clip cover.
     */
    private Vec3 resolveShootOrigin() {
        assert mc.player != null;

        Vec3 origin = TaczCompat.getStableShootOrigin(mc.player);
        return origin != null ? origin : mc.player.getEyePosition();
    }

    private void syncShootOrigin(TaczAimAssistConfig config) {
        assert mc.player != null && mc.level != null;

        if (!TaczCompat.holdsTaczGun(mc.player.getItemInHand(InteractionHand.MAIN_HAND))) {
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

        // Airborne movement always drifts the origin. On the ground the trigger is an
        // engagement: the player sees a target, but bullets would still leave from the stale
        // draw position and clip whatever cover stands between - re-draw so the origin
        // catches up before the first shot.
        boolean airborne = !mc.player.onGround();
        if (!airborne && !playerSeesTargetEntity(config)) {
            return;
        }
        if (TaczCompat.syncShootOrigin(mc.player)) {
            lastShootOriginSync = now;
            lastShootOriginSyncPos = position;
        }
    }

    /** Whether the player can currently see any target-type entity - engagement likely. */
    private boolean playerSeesTargetEntity(TaczAimAssistConfig config) {
        assert mc.player != null && mc.level != null;

        Vec3 eyes = mc.player.getEyePosition();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity == mc.player || !isTargetEntity(entity)) {
                continue;
            }
            if (eyes.distanceToSqr(entity.getEyePosition()) > config.range * config.range) {
                continue;
            }
            if (!isBlocked(eyes, getAimPoint(entity, 1.0F, config.aimAtMode))) {
                return true;
            }
        }
        return false;
    }

    /** Unconditionally re-snapshots the shoot origin; used right after a reposition TP. */
    private void forceShootOriginSync() {
        assert mc.player != null;

        if (TaczCompat.syncShootOrigin(mc.player)) {
            lastShootOriginSync = System.currentTimeMillis();
            lastShootOriginSyncPos = mc.player.position();
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
            Set<Block> penetrable = new HashSet<>();
            Set<Block> blocking = new HashSet<>();
            for (PenetrableBlockEntry entry : config.penetrableBlocks) {
                if (entry != null && entry.block != null) {
                    (entry.enabled ? penetrable : blocking).add(entry.block);
                }
            }
            penetrableBlocks = penetrable;
            blockingBlocks = blocking;
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
                ? resolveAimPoint(entity, 1.0F, resolveShootOrigin(), config.aimAtMode) != null
                : true;
    }

    private @Nullable Entity findBestTarget(TaczAimAssistConfig config) {
        assert mc.player != null && mc.level != null;

        Vec3 eyes = mc.player.getEyePosition();
        Vec3 origin = resolveShootOrigin();
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
                    ? resolveAimPoint(entity, 1.0F, origin, config.aimAtMode)
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
     * Mirrors what a TaCZ bullet actually does on this line. BlockRayTrace clips bullets
     * against block collision shapes, ignores the {@code tacz:bullet_ignore} tag, and this
     * build has no solid-block penetration - a bullet that reaches a block dies on it. So a
     * block passes when it is air, in the air-like tag, or has no collision shape (tall
     * grass, flowers, snow layers...). The manual list stays as an override: enabled entries
     * are treated as pass-through and disabled entries as blocking, whatever the auto rule
     * says.
     */
    private boolean isBlocked(Vec3 from, Vec3 to) {
        assert mc.level != null;

        TaczAimAssistConfig config = getConfig();
        boolean auto = config.autoPenetrableBlocks;
        boolean glassPassable = config.treatGlassAsPassable;
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
            if (blockingBlocks.contains(state.getBlock())) {
                return true;
            }
            if (auto && (state.is(BULLET_IGNORE_TAG)
                    || state.getCollisionShape(mc.level, pos).isEmpty())) {
                continue;
            }
            if (glassPassable && isGlass(state)) {
                continue;
            }
            return true;
        }
        return false;
    }

    /** Glass family: plain, tinted, stained and stained panes - bullets shatter it on hit. */
    private static boolean isGlass(BlockState state) {
        Block block = state.getBlock();
        return block instanceof AbstractGlassBlock || block instanceof StainedGlassPaneBlock;
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

    /**
     * Auto fire, in one of three modes: SINGLE fires one shot the moment the target lock
     * acquires a new enemy; FULL keeps firing for as long as the lock holds a verified firing
     * solution; GUN follows the held gun's own fire mode (AUTO and continuous-burst guns keep
     * firing, SEMI and short-burst guns fire one shot per target). All shots go through the
     * TaCZ client controllers, so the gun's own cooldown and fire mode routing apply
     * unchanged.
     */
    private void updateAutoFire(TaczAimAssistConfig config) {
        if (!config.enabled || !config.autoFire || target == null) {
            firedTarget = null;
            autoFireTrigger = false;
            return;
        }

        if (target.isRemoved() || !targetLocked || !lastRotationBallistic
                || !mc.player.isAlive()
                || !TaczCompat.holdsTaczGun(mc.player.getItemInHand(InteractionHand.MAIN_HAND))) {
            autoFireTrigger = false;
            return;
        }

        boolean fullAuto;
        if (TaczAimAssistConfig.AUTO_FIRE_MODE_FULL.equals(config.autoFireMode)) {
            fullAuto = true;
        } else if (TaczAimAssistConfig.AUTO_FIRE_MODE_SINGLE.equals(config.autoFireMode)) {
            fullAuto = false;
        } else {
            fullAuto = TaczCompat.isFullAutoGun(mc.player.getItemInHand(InteractionHand.MAIN_HAND));
        }

        if (fullAuto) {
            holdTrigger(config);
        } else {
            fireSingleShot(config);
        }
    }

    /**
     * Polling the shoot controllers every tick is the equivalent of holding the trigger;
     * each call is rate-limited by the gun's own cooldown gate. The trigger is released when
     * an engagement starts and then bounced once a second, so an empty magazine can reach the
     * controllers' first-pull auto-reload path.
     */
    private void holdTrigger(TaczAimAssistConfig config) {
        long now = System.currentTimeMillis();
        if (!autoFireTrigger) {
            TaczCompat.releaseShootTrigger();
            autoFireTrigger = true;
            lastAutoFireReset = now;
        } else if (now - lastAutoFireReset >= AUTO_FIRE_TRIGGER_RESET_MS) {
            TaczCompat.releaseShootTrigger();
            lastAutoFireReset = now;
        }
        TaczCompat.tryShoot();
    }

    private void fireSingleShot(TaczAimAssistConfig config) {
        autoFireTrigger = false;
        if (target == firedTarget) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastAutoFireShot < AUTO_FIRE_MIN_INTERVAL_MS) {
            return;
        }

        // Release first so this pull is fresh and an empty magazine can reach auto-reload.
        TaczCompat.releaseShootTrigger();
        if (TaczCompat.tryShoot()) {
            firedTarget = target;
            lastAutoFireShot = now;
            if (config.debugLogging) {
                LOGGER.info("TaCZ auto fire: single shot at {}",
                        Registries.ENTITY_TYPES.getKey(target.getType()));
            }
        }
    }

    /**
     * Automatic reposition trigger: when an enemy stays fully hidden behind cover, searches
     * the surroundings for a standable spot with line of sight to it and teleports there
     * through the teleport hack. Such an enemy never passes the target lock's line-of-sight
     * check, so it is never acquired as a target - which is why the scan here runs
     * independently of the lock, on the closest hidden enemy in range. Disabled when the
     * reposition trigger mode is MANUAL; scripts drive it through
     * {@link #repositionManually()} instead.
     */
    private void updateReposition(TaczAimAssistConfig config) {
        boolean automatic = config.enabled && config.teleportReposition && targetLockEnabled
                && TaczAimAssistConfig.REPOSITION_TRIGGER_AUTO.equals(config.teleportRepositionTrigger);
        if (!automatic || target != null) {
            // engaged with something visible - repositioning has nothing to solve
            repositionTicks = 0;
            return;
        }

        Entity hidden = findOccludedEnemy(config);
        if (hidden == null) {
            repositionTicks = 0;
            return;
        }

        repositionTicks++;
        if (repositionTicks < REPOSITION_TRIGGER_TICKS) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastRepositionAt < REPOSITION_COOLDOWN_MS) {
            return;
        }
        if (!mc.player.isAlive()
                || !TaczCompat.holdsTaczGun(mc.player.getItemInHand(InteractionHand.MAIN_HAND))) {
            return;
        }

        attemptReposition(config, hidden);
    }

    /**
     * Scripting entry point: searches a firing position against the closest fully hidden
     * enemy and teleports there immediately. Shares the cooldown with the automatic trigger;
     * fails without side effects when the feature is off, a visible target is already
     * engaged, the cooldown has not elapsed, or no hidden enemy or valid spot exists. The
     * script author decides what to hold, so unlike the automatic trigger no gun is
     * required.
     */
    public boolean repositionManually() {
        TaczAimAssistConfig config = getConfig();
        if (!config.teleportReposition
                || mc.player == null || mc.level == null || target != null
                || !mc.player.isAlive()) {
            return false;
        }
        if (System.currentTimeMillis() - lastRepositionAt < REPOSITION_COOLDOWN_MS) {
            return false;
        }
        Entity hidden = findOccludedEnemy(config);
        return hidden != null && attemptReposition(config, hidden);
    }

    private boolean attemptReposition(TaczAimAssistConfig config, Entity hidden) {
        long now = System.currentTimeMillis();
        Vec3 spot = findFiringPosition(config, hidden);
        if (spot == null) {
            // Searching is not free either; retry on a short backoff instead of every tick.
            lastRepositionAt = now - REPOSITION_COOLDOWN_MS + 500;
            return false;
        }
        if (TeleportHack.instance.teleportTo(spot.x, spot.y, spot.z, config.teleportRepositionRepeats)) {
            lastRepositionAt = now;
            lastRepositionSpot = spot;
            repositionTicks = 0;
            // Bullets would leave from the pre-teleport origin; re-draw so the fresh
            // position is also the fresh shoot origin before the target is engaged.
            if (TaczCompat.holdsTaczGun(mc.player.getItemInHand(InteractionHand.MAIN_HAND))) {
                forceShootOriginSync();
            }
            if (config.debugLogging) {
                LOGGER.info("TaCZ repositioned to ({}, {}, {}) to engage {}", spot.x, spot.y, spot.z,
                        Registries.ENTITY_TYPES.getKey(hidden.getType()));
            }
            return true;
        }
        return false;
    }

    /**
     * Closest target-type entity with no exposed aim point - the enemy the target lock
     * deliberately ignores because it is fully behind cover. No FOV check: an enemy hiding
     * behind cover is a threat regardless of where you are looking.
     */
    private @Nullable Entity findOccludedEnemy(TaczAimAssistConfig config) {
        assert mc.player != null && mc.level != null;

        Vec3 eyes = mc.player.getEyePosition();
        Entity best = null;
        double bestDistanceSqr = Double.MAX_VALUE;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity == mc.player || !isTargetEntity(entity)) {
                continue;
            }
            double distanceSqr = eyes.distanceToSqr(entity.getEyePosition());
            if (distanceSqr > config.range * config.range) {
                continue;
            }
            if (!isBlocked(eyes, getAimPoint(entity, 1.0F, config.aimAtMode))) {
                continue; // visible - the target lock handles this one
            }
            if (distanceSqr < bestDistanceSqr) {
                best = entity;
                bestDistanceSqr = distanceSqr;
            }
        }
        return best;
    }

    private @Nullable Vec3 findFiringPosition(TaczAimAssistConfig config, Entity enemy) {
        assert mc.player != null && mc.level != null && enemy != null;

        Vec3 aimPoint = getAimPoint(enemy, 1.0F, config.aimAtMode);
        double enemyEyeY = enemy.getEyePosition().y;
        double maxRangeSqr = config.range * config.range;
        double preferredEngageSqr = REPOSITION_PREFERRED_ENGAGE * REPOSITION_PREFERRED_ENGAGE;
        double eyeHeight = mc.player.getEyeHeight();
        BlockPos base = mc.player.blockPosition();
        double sector = 2 * Math.PI / REPOSITION_AZIMUTHS;

        List<Vec3> goodSpots = new ArrayList<>();
        List<Vec3> anySpots = new ArrayList<>();
        List<Integer> standableYs = new ArrayList<>(REPOSITION_Y_PER_COLUMN);

        for (double radius = 4; radius <= config.teleportRepositionRange; radius += REPOSITION_RING_STEP) {
            for (int i = 0; i < REPOSITION_AZIMUTHS; i++) {
                double angle = sector * (i + 0.5) + (random.nextDouble() - 0.5) * sector;
                int x = base.getX() + (int) Math.round(Math.cos(angle) * radius);
                int z = base.getZ() + (int) Math.round(Math.sin(angle) * radius);

                standableYs.clear();
                findStandableYs(base.getY(), x, z, standableYs);
                for (int y : standableYs) {
                    Vec3 eye = new Vec3(x + 0.5, y + eyeHeight, z + 0.5);
                    double engageSqr = eye.distanceToSqr(aimPoint);
                    if (engageSqr > maxRangeSqr || isBlocked(eye, aimPoint)) {
                        continue;
                    }
                    Vec3 spot = new Vec3(x + 0.5, y, z + 0.5);
                    anySpots.add(spot);
                    // High ground that shoots over walls and cover, kept away from the enemy -
                    // this is the "climb the hill behind" class of position.
                    if (eye.y >= enemyEyeY + REPOSITION_MIN_ELEVATION
                            && engageSqr >= preferredEngageSqr) {
                        goodSpots.add(spot);
                    }
                }
            }
        }

        // High ground at a healthy distance wins; anything with a sight line is the fallback.
        // Spots are drawn at random, never the last attempted one: a fixed choice would keep
        // re-selecting the same position and repeat the same failure whenever the server
        // rejects the teleport.
        Vec3 spot = pickRepositionSpot(goodSpots);
        return spot != null ? spot : pickRepositionSpot(anySpots);
    }

    private @Nullable Vec3 pickRepositionSpot(List<Vec3> valid) {
        if (valid.isEmpty()) {
            return null;
        }
        Vec3 last = lastRepositionSpot;
        if (valid.size() > 1 && last != null) {
            List<Vec3> fresh = valid.stream()
                    .filter(spot -> spot.distanceToSqr(last) >= 0.01)
                    .toList();
            if (!fresh.isEmpty()) {
                valid = fresh;
            }
        }
        return valid.get(random.nextInt(valid.size()));
    }

    /**
     * Standable feet levels at (x, z) - two air blocks with a non-air block below - scanned
     * from {@code REPOSITION_MAX_UP} above the current feet level down to
     * {@code REPOSITION_MAX_DOWN} below it, highest first. High positions come first because
     * elevation is what shoots over walls and other cover.
     */
    private void findStandableYs(int feetY, int x, int z, List<Integer> out) {
        assert mc.level != null;

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dy = REPOSITION_MAX_UP; dy >= -REPOSITION_MAX_DOWN && out.size() < REPOSITION_Y_PER_COLUMN; dy--) {
            int y = feetY + dy;
            pos.set(x, y, z);
            if (!mc.level.getBlockState(pos).isAir()) {
                continue;
            }
            pos.set(x, y + 1, z);
            if (!mc.level.getBlockState(pos).isAir()) {
                continue;
            }
            pos.set(x, y - 1, z);
            if (mc.level.getBlockState(pos).isAir()) {
                continue;
            }
            out.add(y);
        }
    }

    private void onRenderTickStart(RenderTickStartEvent event) {
        TaczAimAssistConfig config = getConfig();
        if (!config.enabled || !targetLockEnabled || mc.player == null
                || target == null || target.isRemoved()) {
            targetLocked = false;
            lastRotationBallistic = false;
            return;
        }

        ItemStack stack = mc.player.getItemInHand(InteractionHand.MAIN_HAND);
        Optional<WeaponFingerprint> fingerprint = WeaponFingerprint.create(mc, stack);
        if (config.onlyAimWithGuns && fingerprint.isEmpty()) {
            targetLocked = false;
            lastRotationBallistic = false;
            return;
        }

        float partialTicks = event.getPartialTicks();
        Rotation rotation = solveAimRotation(config, target, partialTicks);
        if (rotation == null) {
            targetLocked = false;
            lastRotationBallistic = false;
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

        // Everything is computed from the bullet origin, not the current eyes: the rotation
        // that lands bullets on the target is the one solved from where they actually spawn.
        Vec3 origin = resolveShootOrigin();
        Vec3 targetNow = resolveAimPoint(entity, partialTicks, origin, config.aimAtMode);
        if (targetNow == null) {
            return null;
        }

        Vec3 velocity = config.predictMovement ? entity.getDeltaMovement() : Vec3.ZERO;
        double latencyTicks = config.predictionTime * 20;

        BallisticParameters parameters = calibration.resolveCurrentParameters(
                config.automaticCalibration, config.useLocalGunData, config.bulletSpeed);
        if (parameters.muzzleSpeed() <= 0) {
            // No ballistic data at all - straight aim is the best available solution.
            lastRotationBallistic = true;
            return RotationUtils.getRotation(origin, targetNow.add(velocity.scale(latencyTicks)));
        }

        BallisticSolver.Input input = new BallisticSolver.Input(
                origin,
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
                .map(solution -> {
                    lastRotationBallistic = true;
                    return new Rotation((float) solution.pitch(), (float) solution.yaw());
                })
                .orElseGet(() -> {
                    // Solver had data but cannot reach the target - not a firing solution.
                    lastRotationBallistic = false;
                    return RotationUtils.getRotation(origin,
                            targetNow.add(velocity.scale(latencyTicks)));
                });
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
     * of sight from the bullet origin, otherwise the most exposed visible point - biased
     * from the cover silhouette toward the inside of the hitbox, see
     * {@link #biasAimIntoHitbox}. Returns null when no part of the target is hittable.
     */
    private @Nullable Vec3 resolveAimPoint(Entity entity, float partialTicks, Vec3 origin, String aimAtMode) {
        Vec3 preferred = getAimPoint(entity, partialTicks, aimAtMode);
        if (!isBlocked(origin, preferred)) {
            return preferred;
        }
        Vec3 exposed = findMostExposedPoint(origin, entity.getBoundingBox(), preferred);
        if (exposed == null) {
            return null;
        }
        return biasAimIntoHitbox(origin, exposed, entity.getBoundingBox());
    }

    /**
     * Peek shots: the most exposed point hugs the cover silhouette by construction, and a
     * bullet that grazes the silhouette edge clips the cover. Aim halfway from the exposed
     * point to where the bullet line leaves the hitbox - between the gap and the far side of
     * the bounding box. The crosshair then rests on body mass; the bullet itself strikes the
     * first flesh it meets along the same line, with margin against the cover edge.
     */
    private static Vec3 biasAimIntoHitbox(Vec3 origin, Vec3 point, AABB box) {
        Vec3 direction = point.subtract(origin);
        double[] dir = {direction.x, direction.y, direction.z};
        double[] org = {origin.x, origin.y, origin.z};
        double[] min = {box.minX, box.minY, box.minZ};
        double[] max = {box.maxX, box.maxY, box.maxZ};

        double tExit = Double.POSITIVE_INFINITY;
        for (int i = 0; i < 3; i++) {
            if (Math.abs(dir[i]) < 1.0E-7) {
                continue;
            }
            double t1 = (min[i] - org[i]) / dir[i];
            double t2 = (max[i] - org[i]) / dir[i];
            tExit = Math.min(tExit, Math.max(t1, t2));
        }
        if (!Double.isFinite(tExit)) {
            return point;
        }

        // The exposed point sits at t = 1 on this ray; step halfway to the box exit.
        double tAim = (1.0 + Math.max(tExit, 1.0)) / 2.0;
        return origin.add(direction.scale(tAim));
    }

    /** Grid divisions per axis of the visible-point search. */
    private static final int AIM_GRID_DIVISIONS = 3;
    /** Extra passes that narrow the grid around the best point found so far. */
    private static final int AIM_REFINEMENT_ROUNDS = 3;
    /** Upper bound of tolerated lateral shot deviation, in blocks at the target. */
    private static final double AIM_MAX_CLEARANCE = 0.45;
    /** Clearance past which the point is good enough and refinement stops. */
    private static final double AIM_GOOD_ENOUGH = 0.25;
    /** Binary search iterations for the clearance; 4 iterations resolve ~0.03 blocks. */
    private static final int AIM_CLEARANCE_STEPS = 4;
    /** Clearance difference below which two points count as equally exposed. */
    private static final double AIM_CLEARANCE_EPSILON = 0.01;
    /** Candidates kept from a grid pass; occlusion leaves only a handful visible anyway. */
    private static final int AIM_MAX_CANDIDATES = 8;

    /**
     * Finds the visible point with the largest exposure clearance - the radius around it that
     * stays both inside the hitbox and visible from the eyes.
     *
     * <p>Distance to the hitbox faces was tried first and is the wrong metric: the deepest point
     * of the hitbox can sit a hair above the cover silhouette, where the crosshair visually rests
     * on the cover and a bullet - which spawns at the muzzle below the eyes - clips it. What
     * actually predicts a hit is how much room there is around the point, in the plane
     * perpendicular to the sight line, before a ray stops being either visible or inside the
     * target. That is measured here by eroding: binary-searching the largest offset for which
     * the four neighbours at that offset are all still hittable.
     *
     * <p>The search is coarse-to-fine: a grid over the whole hitbox, then the same grid over a
     * window that halves around the best point. The first pass samples every face centre, edge
     * and corner, so any visible part is found by it; later passes only deepen the choice within
     * the exposed region. Distance to the configured aim point breaks ties so the point stays
     * near the intended spot.
     */
    private @Nullable Vec3 findMostExposedPoint(Vec3 origin, AABB box, Vec3 preferred) {
        Vec3 half = new Vec3(box.getXsize() / 2, box.getYsize() / 2, box.getZsize() / 2);

        List<Vec3> candidates = new ArrayList<>(AIM_GRID_DIVISIONS * AIM_GRID_DIVISIONS * AIM_GRID_DIVISIONS);
        collectVisibleGridPoints(origin, box, box.getCenter(), half, candidates);
        if (candidates.isEmpty()) {
            return null;
        }

        @Nullable Vec3 best = null;
        double bestClearance = -1;
        double bestPenalty = Double.POSITIVE_INFINITY;

        for (int round = 0; round <= AIM_REFINEMENT_ROUNDS; round++) {
            candidates.sort(Comparator.comparingDouble(preferred::distanceToSqr));
            int scored = 0;
            for (Vec3 point : candidates) {
                if (scored++ >= AIM_MAX_CANDIDATES) {
                    break;
                }
                double clearance = exposureClearance(origin, point, box);
                double penalty = preferred.distanceToSqr(point);
                if (clearance > bestClearance + AIM_CLEARANCE_EPSILON ||
                        (clearance > bestClearance - AIM_CLEARANCE_EPSILON && penalty < bestPenalty)) {
                    best = point;
                    bestClearance = clearance;
                    bestPenalty = penalty;
                }
            }

            if (best == null || bestClearance >= AIM_MAX_CLEARANCE || bestClearance >= AIM_GOOD_ENOUGH) {
                // Either nothing visible, or the point already tolerates the full lateral
                // deviation a realistic shot can have - refinement cannot do better.
                break;
            }

            Vec3 next = new Vec3(half.x / 2, half.y / 2, half.z / 2);
            if (round < AIM_REFINEMENT_ROUNDS) {
                candidates.clear();
                collectVisibleGridPoints(origin, box, best, next, candidates);
                half = next;
            }
        }

        return best;
    }

    private void collectVisibleGridPoints(Vec3 origin, AABB box, Vec3 centre, Vec3 half, List<Vec3> out) {
        for (int i = 0; i < AIM_GRID_DIVISIONS; i++) {
            for (int j = 0; j < AIM_GRID_DIVISIONS; j++) {
                for (int k = 0; k < AIM_GRID_DIVISIONS; k++) {
                    Vec3 point = new Vec3(
                            Mth.clamp(centre.x + (i - 1) * half.x, box.minX, box.maxX),
                            Mth.clamp(centre.y + (j - 1) * half.y, box.minY, box.maxY),
                            Mth.clamp(centre.z + (k - 1) * half.z, box.minZ, box.maxZ));
                    if (!isBlocked(origin, point)) {
                        out.add(point);
                    }
                }
            }
        }
    }

    /**
     * Largest radius such that shifting the point by that radius along either axis of the plane
     * perpendicular to the sight line leaves it inside the hitbox and visible - i.e. how far a
     * shot can deviate laterally and still land on exposed flesh.
     */
    private double exposureClearance(Vec3 origin, Vec3 point, AABB box) {
        Vec3 sight = point.subtract(origin);
        double dist = sight.length();
        if (dist < 1.0E-6) {
            return 0;
        }
        Vec3 dir = sight.scale(1 / dist);
        Vec3 up = Math.abs(dir.y) > 0.9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        Vec3 u = dir.cross(up).normalize();
        Vec3 v = u.cross(dir).normalize();

        double lo = 0;
        double hi = AIM_MAX_CLEARANCE;
        for (int i = 0; i < AIM_CLEARANCE_STEPS; i++) {
            double r = (lo + hi) / 2;
            boolean clear = isHittable(origin, point.add(u.scale(r)), box)
                    && isHittable(origin, point.subtract(u.scale(r)), box)
                    && isHittable(origin, point.add(v.scale(r)), box)
                    && isHittable(origin, point.subtract(v.scale(r)), box);
            if (clear) {
                lo = r;
            } else {
                hi = r;
            }
        }
        return lo;
    }

    private boolean isHittable(Vec3 origin, Vec3 point, AABB box) {
        return box.contains(point) && !isBlocked(origin, point);
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
        Vec3 start = resolveShootOrigin();
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
        if (config.autoFire) {
            String fireState;
            if (autoFireTrigger) {
                fireState = "FIRING";
            } else if (System.currentTimeMillis() - lastAutoFireShot < 500) {
                fireState = "SHOT";
            } else {
                fireState = target != null ? "armed" : "standby";
            }
            lines.add("Auto fire (" + config.autoFireMode.toLowerCase(Locale.ROOT) + "): " + fireState);
        }
        if (config.teleportReposition) {
            lines.add("Reposition: " + (repositionTicks > 0
                    ? "hidden enemy - hunting spot (" + repositionTicks + "t)"
                    : "standby"));
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
