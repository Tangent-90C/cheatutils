package com.zergatul.cheatutils.configs;

import com.zergatul.cheatutils.collections.ImmutableList;
import com.zergatul.cheatutils.utils.MathUtils;
import net.minecraft.world.level.block.Block;

import java.util.Objects;

public class TaczAimAssistConfig extends ModuleConfig implements Sanitizable {

    public static final String AIM_ASSIST_CENTER = "AIM_ASSIST_CENTER";
    public static final String AIM_ASSIST_HEAD = "AIM_ASSIST_HEAD";
    public static final String TARGET_PRIORITY_CROSSHAIR = "CROSSHAIR";
    public static final String TARGET_PRIORITY_DISTANCE = "DISTANCE";
    public static final String AUTO_FIRE_MODE_GUN = "GUN";
    public static final String AUTO_FIRE_MODE_SINGLE = "SINGLE";
    public static final String AUTO_FIRE_MODE_FULL = "FULL";
    public static final String REPOSITION_TRIGGER_AUTO = "AUTO";
    public static final String REPOSITION_TRIGGER_MANUAL = "MANUAL";
    public static final String GROUP_PREFIX = "group:";

    public ImmutableList<TargetEntityEntry> targetEntities;
    public ImmutableList<PenetrableBlockEntry> penetrableBlocks;

    public boolean automaticCalibration;
    public boolean useLocalGunData;
    public boolean calibrationHud;
    public boolean taczTrajectory;
    public double taczTrajectoryMaxSpeed;
    public boolean onlyAimWithGuns;
    public double bulletSpeed;
    public double maxFlightTime;
    public double allowedMissRadius;
    public double shooterVelocityInheritance;
    public double range;
    public double fov;
    public String aimAtMode;
    public String targetPriority;
    public boolean checkLineOfSight;
    public boolean predictMovement;
    public double predictionTime;
    public boolean switchTargets;
    public boolean syncShootOrigin;
    public boolean autoFire;
    public String autoFireMode;
    public boolean autoPenetrableBlocks;
    public boolean treatGlassAsPassable;
    public boolean teleportReposition;
    public String teleportRepositionTrigger;
    public double teleportRepositionRange;
    public int teleportRepositionRepeats;
    public boolean debugLogging;

    public TaczAimAssistConfig() {
        targetEntities = ImmutableList.from(new TargetEntityEntry("minecraft:player", true));
        penetrableBlocks = new ImmutableList<>();        automaticCalibration = false;
        useLocalGunData = true;
        calibrationHud = false;
        taczTrajectory = false;
        taczTrajectoryMaxSpeed = 0;
        onlyAimWithGuns = true;
        bulletSpeed = 0;
        maxFlightTime = 5;
        allowedMissRadius = 0.75;
        shooterVelocityInheritance = 0;
        range = 64;
        fov = 120;
        aimAtMode = AIM_ASSIST_CENTER;
        targetPriority = TARGET_PRIORITY_CROSSHAIR;
        checkLineOfSight = true;
        predictMovement = true;
        predictionTime = 0.2;
        switchTargets = false;
        syncShootOrigin = true;
        autoFire = false;
        autoFireMode = AUTO_FIRE_MODE_GUN;
        autoPenetrableBlocks = true;
        treatGlassAsPassable = true;
        teleportReposition = false;
        teleportRepositionTrigger = REPOSITION_TRIGGER_AUTO;
        teleportRepositionRange = 24;
        teleportRepositionRepeats = 5;
        debugLogging = false;
    }

    @Override
    public void sanitize() {
        if (targetEntities == null) {
            targetEntities = ImmutableList.from(new TargetEntityEntry("minecraft:player", true));
        } else {
            targetEntities = targetEntities
                    .removeIf(Objects::isNull)
                    .removeIf(entry -> entry.id == null || entry.id.isBlank());
            ImmutableList<TargetEntityEntry> deduplicated = new ImmutableList<>();
            for (TargetEntityEntry entry : targetEntities) {
                if (deduplicated.stream().noneMatch(e -> e.id.equals(entry.id))) {
                    deduplicated = deduplicated.add(entry);
                }
            }
            targetEntities = deduplicated;
        }
        if (penetrableBlocks == null) {
            penetrableBlocks = new ImmutableList<>();
        } else {
            penetrableBlocks = penetrableBlocks
                    .removeIf(Objects::isNull)
                    .removeIf(entry -> entry.block == null);
            ImmutableList<PenetrableBlockEntry> deduplicated = new ImmutableList<>();
            for (PenetrableBlockEntry entry : penetrableBlocks) {
                if (deduplicated.stream().noneMatch(e -> e.block == entry.block)) {
                    deduplicated = deduplicated.add(entry);
                }
            }
            penetrableBlocks = deduplicated;
        }
        taczTrajectoryMaxSpeed = MathUtils.clamp(taczTrajectoryMaxSpeed, 0, 10000);
        bulletSpeed = MathUtils.clamp(bulletSpeed, 0, 5000);
        maxFlightTime = MathUtils.clamp(maxFlightTime, 0.1, 10);
        allowedMissRadius = MathUtils.clamp(allowedMissRadius, 0.05, 3);
        shooterVelocityInheritance = MathUtils.clamp(shooterVelocityInheritance, 0, 2);
        range = MathUtils.clamp(range, 1, 256);
        fov = MathUtils.clamp(fov, 30, 360);
        predictionTime = MathUtils.clamp(predictionTime, 0, 5);
        teleportRepositionRange = MathUtils.clamp(teleportRepositionRange, 4, 64);
        teleportRepositionRepeats = MathUtils.clamp(teleportRepositionRepeats, 0, 100);
        if (!AIM_ASSIST_HEAD.equals(aimAtMode)) {
            aimAtMode = AIM_ASSIST_CENTER;
        }
        if (!TARGET_PRIORITY_DISTANCE.equals(targetPriority)) {
            targetPriority = TARGET_PRIORITY_CROSSHAIR;
        }
        if (!AUTO_FIRE_MODE_SINGLE.equals(autoFireMode) && !AUTO_FIRE_MODE_FULL.equals(autoFireMode)) {
            autoFireMode = AUTO_FIRE_MODE_GUN;
        }
        if (!REPOSITION_TRIGGER_MANUAL.equals(teleportRepositionTrigger)) {
            teleportRepositionTrigger = REPOSITION_TRIGGER_AUTO;
        }
    }

    public static class TargetEntityEntry {

        public String id;
        public boolean enabled;

        public TargetEntityEntry() {
        }

        public TargetEntityEntry(String id, boolean enabled) {
            this.id = id;
            this.enabled = enabled;
        }
    }

    public static class PenetrableBlockEntry {

        public Block block;
        public boolean enabled;

        public PenetrableBlockEntry() {
        }

        public PenetrableBlockEntry(Block block, boolean enabled) {
            this.block = block;
            this.enabled = enabled;
        }
    }
}
