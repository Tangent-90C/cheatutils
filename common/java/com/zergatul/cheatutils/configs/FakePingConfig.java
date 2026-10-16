package com.zergatul.cheatutils.configs;

import com.zergatul.cheatutils.utils.MathUtils;

public class FakePingConfig extends ModuleConfig implements ValidatableConfig {

    // artificial delay for keep-alive responses, milliseconds
    public int delay;

    // write capture/flush lines to the game log
    public boolean log;

    public FakePingConfig() {
        delay = 0;
    }

    @Override
    public void validate() {
        // server disconnects if a keep-alive response doesn't arrive within ~15 seconds of the request
        delay = MathUtils.clamp(delay, 0, 10000);
    }
}
