package com.zergatul.cheatutils.configs;

import com.zergatul.cheatutils.utils.MathUtils;

public class FlyHackConfig extends ModuleConfig implements Sanitizable {

    public boolean overrideFlyingSpeed;
    public float flyingSpeed;
    public boolean onGroundFlag;
    public boolean reportOnGroundWithGun;

    public FlyHackConfig() {
        enabled = false;
        overrideFlyingSpeed = false;
        flyingSpeed = 0.05f;
        reportOnGroundWithGun = true;
    }

    @Override
    public void sanitize() {
        flyingSpeed = MathUtils.clamp(flyingSpeed, 0.001f, 10f);
    }
}