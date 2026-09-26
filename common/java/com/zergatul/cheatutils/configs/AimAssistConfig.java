package com.zergatul.cheatutils.configs;

import com.zergatul.cheatutils.utils.MathUtils;

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
    public String aimControlMode;
    public String aimAt;
    public double range;
    public int rotationSpeed;
    public int fov;
    public double precision;
    public boolean checkLineOfSight;
    public boolean aimWhileBlocking;
    public boolean filterTeammates;

    public boolean combatMode;
    public String combatFireMode;
    public double clicksPerSecond;
    public int firstAttackDelay;
    public double attackTolerance;

    public boolean debugLogging;

    public AimAssistConfig() {
        aimControlMode = ControlModeConfig;
        aimAt = AimAtAuto;
        range = 128;
        rotationSpeed = 600;
        fov = 120;
        precision = 0.1;
        checkLineOfSight = true;
        aimWhileBlocking = false;
        filterTeammates = true;
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
     * In Config mode the checkbox above is the only authority and the scripting
     * switch is ignored. In Key mode the switch decides while the checkbox acts
     * as a master enable.
     */
    public boolean isKeyControlMode() {
        return ControlModeKey.equals(aimControlMode);
    }

    @Override
    public void validate() {
        range = MathUtils.clamp(range, 1, 1024);
        rotationSpeed = MathUtils.clamp(rotationSpeed, 10, 3600);
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
    }
}
