package com.zergatul.cheatutils.rewrite;

/**
 * Values the CSMC-rewrite mixin reads. Lives in the source set compiled without the mixin annotation
 * processor, so the main source set pushes the config in here by reflection every tick instead of a
 * compile dependency - the same arrangement {@code CsmcSpreadOptions} has for the obfuscated mod.
 *
 * <p>The rewrite keeps the original's separation of concerns, which is what makes this mapping possible
 * without touching any rewrite code: the spread cone is a number the rewrite hands out on its own
 * ({@code WeaponAccuracyRuntime.spread}), and the recoil reaches the shot direction through the two
 * offset arguments of {@code TableRecoil.direction}. Zeroing the first removes scatter, zeroing the
 * second removes the recoil table's contribution to where the bullet goes - while the view punch, which
 * the rewrite routes through the camera instead, stays untouched.</p>
 */
public final class RewriteSpreadOptions {

    private static volatile boolean noSpread;
    private static volatile boolean zeroRecoil;

    private RewriteSpreadOptions() {
    }

    public static void update(boolean noSpread, boolean zeroRecoil) {
        RewriteSpreadOptions.noSpread = noSpread;
        RewriteSpreadOptions.zeroRecoil = zeroRecoil;
    }

    public static boolean noSpread() {
        return noSpread;
    }

    public static boolean zeroRecoil() {
        return zeroRecoil;
    }
}
