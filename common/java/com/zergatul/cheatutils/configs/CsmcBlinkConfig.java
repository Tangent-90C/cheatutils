package com.zergatul.cheatutils.configs;

import com.zergatul.cheatutils.utils.MathUtils;

import java.awt.*;

public class CsmcBlinkConfig extends ModuleConfig implements ValidatableConfig {

    public boolean flushOnShoot;
    public boolean sendCurrentPosition;
    public boolean respawnRewind;
    /**
     * Only meaningful with {@link #respawnRewind}: replay the held buffer at the respawn itself,
     * instead of leaving it until {@code csmcBlink.recall()} is called.
     */
    public boolean recallImmediately;
    public boolean releaseWhenStopped;
    /**
     * Ticks without movement before "Release When Stopped" fires. 5 ticks is a quarter of a second,
     * which is what the hardcoded default was. An int is right here: the value counts ticks and 0 has
     * no meaning for it, so {@link #validate()} clamps to 1 rather than leaving a 0 that would release
     * on the very first tick of standing still.
     */
    public int stillTicks;
    public boolean debugBox;
    public Color debugBoxColor;
    public boolean debugLogging;
    public int maxPackets;
    /** blocks, as a double: 0 disables it, and sub-block values only survive as a non-int field */
    public double maxDistance;

    public CsmcBlinkConfig() {
        enabled = false;
        flushOnShoot = true;
        sendCurrentPosition = true;
        respawnRewind = false;
        recallImmediately = false;
        releaseWhenStopped = true;
        stillTicks = 5;
        debugBox = true;
        debugBoxColor = new Color(0x00E5FF);
        debugLogging = false;
        maxPackets = 60;
        maxDistance = 16.0;
    }

    @Override
    public void validate() {
        maxPackets = MathUtils.clamp(maxPackets, 0, 1000);
        maxDistance = MathUtils.clamp(maxDistance, 0, 100);
        stillTicks = MathUtils.clamp(stillTicks, 1, 200);
    }
}