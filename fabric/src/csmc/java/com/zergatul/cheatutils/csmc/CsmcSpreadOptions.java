package com.zergatul.cheatutils.csmc;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Values the CSMC mixin reads. Lives in the source set compiled without the mixin annotation processor, so
 * the main source set pushes the config in here by reflection every tick instead of a compile dependency.
 */
public final class CsmcSpreadOptions {

    private static volatile boolean smoothRecoil;
    private static volatile float patternScale;
    private static volatile boolean noSpread;
    private static volatile boolean overrideReturn;
    private static volatile float aimYaw;
    private static volatile float aimPitch;

    private CsmcSpreadOptions() {
    }

    public static void update(boolean smoothRecoil, float patternScale, boolean noSpread,
            boolean overrideShotDirection, float snapshotYaw, float snapshotPitch) {
        CsmcSpreadOptions.smoothRecoil = smoothRecoil;
        CsmcSpreadOptions.patternScale = patternScale;
        CsmcSpreadOptions.noSpread = noSpread;
        overrideReturn = overrideShotDirection;
        aimYaw = snapshotYaw;
        aimPitch = snapshotPitch;
    }

    public static boolean smoothRecoil() {
        return smoothRecoil;
    }

    public static float patternScale() {
        return patternScale;
    }

    public static boolean noSpread() {
        return noSpread;
    }

    public static boolean overrideReturn() {
        return overrideReturn;
    }

    /**
     * Rebuilds the pure aim direction from the pre-punch rotation snapshot (vanilla rotation math via
     * Mth, so the csmc source set keeps no main-class dependency). The direction the client produces is
     * what actually lands (proven: impacts move with client-side scaling), so replacing the exit value
     * of the shot-direction method with this vector is full automatic compensation - the direction
     * leaving the client is always the crosshair, no matter which recoil offsets were mixed in.
     */
    public static Vec3 aimDirection() {
        float f = aimPitch * ((float) Math.PI / 180F);
        float g = -aimYaw * ((float) Math.PI / 180F);
        float h = Mth.cos(g);
        float i = Mth.sin(g);
        float j = Mth.cos(f);
        float k = Mth.sin(f);
        return new Vec3(i * j, -k, h * j);
    }
}
