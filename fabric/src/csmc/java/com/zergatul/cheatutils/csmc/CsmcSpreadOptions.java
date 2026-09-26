package com.zergatul.cheatutils.csmc;

/**
 * Values the CSMC mixin reads. Lives in the source set compiled without the mixin annotation processor, so
 * the main source set pushes the config in here by reflection every tick instead of a compile dependency.
 */
public final class CsmcSpreadOptions {

    private static volatile boolean smoothRecoil;
    private static volatile boolean patternPunch;
    private static volatile boolean noSpread;

    private CsmcSpreadOptions() {
    }

    public static void update(boolean smoothRecoil, boolean patternPunch, boolean noSpread) {
        CsmcSpreadOptions.smoothRecoil = smoothRecoil;
        CsmcSpreadOptions.patternPunch = patternPunch;
        CsmcSpreadOptions.noSpread = noSpread;
    }

    public static boolean smoothRecoil() {
        return smoothRecoil;
    }

    public static boolean patternPunch() {
        return patternPunch;
    }

    public static boolean noSpread() {
        return noSpread;
    }
}
