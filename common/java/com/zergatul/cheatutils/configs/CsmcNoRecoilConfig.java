package com.zergatul.cheatutils.configs;

import com.zergatul.cheatutils.utils.MathUtils;

public class CsmcNoRecoilConfig extends ModuleConfig implements ValidatableConfig {

    public int amount;

    public CsmcNoRecoilConfig() {
        enabled = false;
        amount = 100;
    }

    public float getAmount() {
        return MathUtils.clamp(amount, 0, 100) / 100.0f;
    }

    @Override
    public void validate() {
        amount = MathUtils.clamp(amount, 0, 100);
    }
}