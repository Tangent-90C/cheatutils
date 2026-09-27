package com.zergatul.cheatutils.csmc;

/**
 * Timestamp of the last shot CSMCMod 6.0 fired, written by {@code MixinCsmcShot} on the fire handler.
 * Lives in the source set compiled without the mixin annotation processor, so the main source set reads it
 * by reflection the same way it pushes the no-recoil config into {@link CsmcSpreadOptions}.
 */
public final class CsmcShotSignal {

    private static volatile long lastShotAt;

    private CsmcShotSignal() {
    }

    public static void onShot() {
        lastShotAt = System.currentTimeMillis();
    }

    public static long lastShotAt() {
        return lastShotAt;
    }
}
