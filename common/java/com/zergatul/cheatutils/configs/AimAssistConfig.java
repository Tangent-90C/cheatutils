package com.zergatul.cheatutils.configs;

import com.zergatul.cheatutils.collections.ImmutableList;
import com.zergatul.cheatutils.utils.MathUtils;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.Objects;

public class AimAssistConfig implements ModuleStateProvider, ValidatableConfig {

    public static final String ClickMode = "Click";
    public static final String HoldMode = "Hold";

    public static final String AimAtAuto = "Auto";
    public static final String AimAtHead = "Head";
    public static final String AimAtCenter = "Center";
    public static final String AimAtFeet = "Feet";

    public static final String ControlModeConfig = "Config";
    public static final String ControlModeKey = "Key";

    public boolean bowAssist;

    public boolean aimCorrection;
    public boolean bulletDropCompensation;
    public String aimControlMode;
    public ImmutableList<Class<?>> targetClasses;
    public String aimAt;
    public double range;
    public int rotationSpeed;
    public int fov;
    public double precision;
    public boolean checkLineOfSight;
    public boolean aimWhileBlocking;
    public boolean filterTeammates;

    public boolean combatMode;
    public String combatControlMode;
    public String combatFireMode;
    public double clicksPerSecond;
    public int firstAttackDelay;
    public double attackTolerance;

    public boolean debugLogging;

    public AimAssistConfig() {
        aimControlMode = ControlModeConfig;
        combatControlMode = ControlModeConfig;
        // players only, so passive mobs like chickens are never picked up
        targetClasses = ImmutableList.from(Player.class);
        aimAt = AimAtAuto;
        range = 128;
        rotationSpeed = 600;
        fov = 120;
        precision = 0.1;
        checkLineOfSight = true;
        aimWhileBlocking = false;
        filterTeammates = true;
        // only ever acts on CSMC guns without their own ballistic computer
        bulletDropCompensation = true;
        combatFireMode = ClickMode;
        clicksPerSecond = 10;
        firstAttackDelay = 0;
        attackTolerance = 1;
    }

    @Override
    public boolean isEnabled() {
        return bowAssist || aimCorrection;
    }

    public boolean isHoldMode() {
        return HoldMode.equals(combatFireMode);
    }

    /**
     * In Config mode the checkbox is the only authority for Aim Correction and the scripting
     * switch is ignored. In Key mode the switch decides while the checkbox acts as a master
     * enable.
     */
    public boolean isAimKeyControlMode() {
        return ControlModeKey.equals(aimControlMode);
    }

    /**
     * Same as {@link #isAimKeyControlMode}, for Combat Mode and its own scripting switch.
     */
    public boolean isCombatKeyControlMode() {
        return ControlModeKey.equals(combatControlMode);
    }

    /**
     * Whether the entity is of a configured type. An empty list means "any living
     * entity", so clearing every entry widens the filter instead of disabling it.
     */
    public boolean canTarget(Entity entity) {
        if (targetClasses == null || targetClasses.size() == 0) {
            return true;
        }
        for (Class<?> clazz : targetClasses) {
            if (clazz != null && clazz.isInstance(entity)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void validate() {
        // classes become null when a mod that provided them is removed
        targetClasses = targetClasses.removeIf(Objects::isNull);
        range = MathUtils.clamp(range, 1, 1024);
        // 0 disables the stepping, the aim point is applied as is
        rotationSpeed = MathUtils.clamp(rotationSpeed, 0, 3600);
        fov = MathUtils.clamp(fov, 1, 360);
        precision = MathUtils.clamp(precision, 0.01, 5);
        clicksPerSecond = MathUtils.clamp(clicksPerSecond, 1, 20);
        firstAttackDelay = MathUtils.clamp(firstAttackDelay, 0, 1000);
        attackTolerance = MathUtils.clamp(attackTolerance, 0.01, 10);
        if (combatFireMode == null
                || !combatFireMode.equals(ClickMode) && !combatFireMode.equals(HoldMode)) {
            combatFireMode = ClickMode;
        }
        if (aimAt == null || !aimAt.equals(AimAtAuto) && !aimAt.equals(AimAtHead)
                && !aimAt.equals(AimAtCenter) && !aimAt.equals(AimAtFeet)) {
            aimAt = AimAtAuto;
        }
        if (aimControlMode == null
                || !aimControlMode.equals(ControlModeConfig)
                && !aimControlMode.equals(ControlModeKey)) {
            aimControlMode = ControlModeConfig;
        }
        if (combatControlMode == null
                || !combatControlMode.equals(ControlModeConfig)
                && !combatControlMode.equals(ControlModeKey)) {
            combatControlMode = ControlModeConfig;
        }
    }
}
