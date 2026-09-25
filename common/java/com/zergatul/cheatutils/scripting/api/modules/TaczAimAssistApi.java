package com.zergatul.cheatutils.scripting.api.modules;

import com.zergatul.cheatutils.configs.ConfigStore;
import com.zergatul.cheatutils.configs.TaczAimAssistConfig;
import com.zergatul.cheatutils.modules.hacks.TaczAimAssist;
import com.zergatul.cheatutils.scripting.api.ApiType;
import com.zergatul.cheatutils.scripting.api.ApiVisibility;
import net.minecraft.world.entity.Entity;

@SuppressWarnings("unused")
public class TaczAimAssistApi {

    public boolean isBowAssistEnabled() {
        return getConfig().enabled;
    }

    public void toggleBowAssist() {
        TaczAimAssistConfig config = getConfig();
        config.enabled = !config.enabled;
        ConfigStore.instance.requestWrite();
    }

    public boolean hasBowAssistTarget() {
        return TaczAimAssist.instance.getTarget() != null;
    }

    public int getBowAssistEntityId() {
        Entity target = TaczAimAssist.instance.getTarget();
        return target != null ? target.getId() : Integer.MIN_VALUE;
    }

    public boolean isTargetLockEnabled() {
        return TaczAimAssist.instance.isTargetLockEnabled();
    }

    public void enableTargetLock() {
        TaczAimAssist.instance.enableTargetLock();
    }

    public void disableTargetLock() {
        TaczAimAssist.instance.disableTargetLock();
    }

    /**
     * Manually triggers the Teleport Repositioning search: finds the closest enemy that is
     * fully hidden behind cover, looks for a standable spot with line of sight to it and
     * teleports there. Shares the cooldown with the automatic trigger; no-op when the
     * feature is off or a visible target is already engaged.
     *
     * @return true when a teleport was performed
     */
    @ApiVisibility(ApiType.ACTION)
    public boolean repositionToHiddenEnemy() {
        return TaczAimAssist.instance.repositionManually();
    }

    private TaczAimAssistConfig getConfig() {
        return ConfigStore.instance.getConfig().taczAimAssist;
    }
}
