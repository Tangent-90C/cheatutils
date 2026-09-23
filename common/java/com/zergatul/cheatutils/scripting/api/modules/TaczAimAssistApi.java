package com.zergatul.cheatutils.scripting.api.modules;

import com.zergatul.cheatutils.configs.ConfigStore;
import com.zergatul.cheatutils.configs.TaczAimAssistConfig;
import com.zergatul.cheatutils.modules.hacks.TaczAimAssist;
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

    private TaczAimAssistConfig getConfig() {
        return ConfigStore.instance.getConfig().taczAimAssist;
    }
}
