package com.zergatul.cheatutils.configs;

import com.zergatul.cheatutils.utils.MathUtils;

import java.awt.*;

public class CsmcBlinkConfig extends ModuleConfig implements ValidatableConfig {

    public boolean flushOnShoot;
    public boolean sendCurrentPosition;
    public boolean respawnRewind;
    public boolean releaseWhenStopped;
    public boolean debugBox;
    public Color debugBoxColor;
    public boolean debugLogging;
    public int maxPackets;
    public int maxDistance;

    public CsmcBlinkConfig() {
        enabled = false;
        flushOnShoot = true;
        sendCurrentPosition = true;
        respawnRewind = false;
        releaseWhenStopped = true;
        debugBox = true;
        debugBoxColor = new Color(0x00E5FF);
        debugLogging = false;
        maxPackets = 60;
        maxDistance = 16;
    }

    @Override
    public void validate() {
        maxPackets = MathUtils.clamp(maxPackets, 0, 1000);
        maxDistance = MathUtils.clamp(maxDistance, 0, 100);
    }
}