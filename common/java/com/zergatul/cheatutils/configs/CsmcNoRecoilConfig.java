package com.zergatul.cheatutils.configs;

import com.zergatul.cheatutils.utils.MathUtils;

public class CsmcNoRecoilConfig extends ModuleConfig implements ValidatableConfig {

    // 1 = dead aim: view held, no spread + both recoil parts - everything hits the crosshair
    // 2 = view kick: view punches as usual, no spread only - impacts group on the original point
    // 3 = locked legacy: view held, no spread + smooth recoil, punch kept - the old pinned feel
    public int mode;
    public int amount;
    public boolean sendCleanRotation;

    public CsmcNoRecoilConfig() {
        enabled = false;
        mode = 1;
        amount = 100;
        sendCleanRotation = false;
    }

    public float getAmount() {
        return MathUtils.clamp(amount, 0, 100) / 100.0f;
    }

    @Override
    public void validate() {
        mode = MathUtils.clamp(mode, 1, 3);
        amount = MathUtils.clamp(amount, 0, 100);
    }
}
