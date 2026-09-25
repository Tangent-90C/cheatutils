package com.zergatul.cheatutils.configs;

public class MoveFreedomConfig extends ModuleConfig {

    public boolean ignoreFracture;
    public boolean ignoreOverweight;

    public void copyFrom(MoveFreedomConfig jsonConfig) {
        enabled = jsonConfig.enabled;
        ignoreFracture = jsonConfig.ignoreFracture;
        ignoreOverweight = jsonConfig.ignoreOverweight;
    }
}
