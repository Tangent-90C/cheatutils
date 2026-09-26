package com.zergatul.cheatutils.scripting.modules;

import com.zergatul.cheatutils.configs.AimAssistConfig;
import com.zergatul.cheatutils.configs.ConfigStore;
import com.zergatul.cheatutils.modules.automation.AimAssist;
import com.zergatul.cheatutils.scripting.ApiType;
import com.zergatul.cheatutils.scripting.ApiVisibility;
import net.minecraft.world.entity.Entity;

@SuppressWarnings("unused")
public class AimAssistApi {

    public boolean isBowAssistEnabled() {
        return getConfig().bowAssist;
    }

    public void toggleBowAssist() {
        AimAssistConfig config = getConfig();
        config.bowAssist = !config.bowAssist;
        ConfigStore.instance.requestWrite();
    }

    public boolean hasBowAssistTarget() {
        return AimAssist.instance.getBowAssistTarget() != null;
    }

    public int getBowAssistEntityId() {
        Entity target = AimAssist.instance.getBowAssistTarget();
        return target != null ? target.getId() : Integer.MIN_VALUE;
    }

    public boolean isTargetLockEnabled() {
        return AimAssist.instance.isTargetLockEnabled();
    }

    public void enableTargetLock() {
        AimAssist.instance.enableTargetLock();
    }

    public void disableTargetLock() {
        AimAssist.instance.disableTargetLock();
    }

    public boolean isAimCorrectionEnabled() {
        return getConfig().aimCorrection;
    }

    public void toggleAimCorrection() {
        AimAssistConfig config = getConfig();
        config.aimCorrection = !config.aimCorrection;
        ConfigStore.instance.requestWrite();
    }

    /**
     * Holds Aim Correction on for as long as a key is down, without touching the
     * saved config. Pair with {@code events.onHandleKeys} to bind a key.
     */
    @ApiVisibility(ApiType.ACTION)
    public void enableAimCorrection() {
        AimAssist.instance.enableAimCorrection();
    }

    @ApiVisibility(ApiType.ACTION)
    public void disableAimCorrection() {
        AimAssist.instance.disableAimCorrection();
    }

    public boolean isAimCorrectionActive() {
        return AimAssist.instance.isAimCorrectionActive();
    }

    public boolean hasAimTarget() {
        return AimAssist.instance.getAimTarget() != null;
    }

    public int getAimTargetEntityId() {
        Entity target = AimAssist.instance.getAimTarget();
        return target != null ? target.getId() : Integer.MIN_VALUE;
    }

    private AimAssistConfig getConfig() {
        return ConfigStore.instance.getConfig().aimAssist;
    }
}