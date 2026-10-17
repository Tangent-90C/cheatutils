package com.zergatul.cheatutils.rewrite;

/**
 * Values the CSMC-rewrite mixin reads. Lives in the source set compiled without the mixin annotation
 * processor, so the main source set pushes the config in here by reflection every tick instead of a
 * compile dependency - the same arrangement {@code CsmcSpreadOptions} has for the obfuscated mod.
 *
 * <p>The rewrite keeps the original's separation of concerns, which is what makes this mapping possible
 * without touching any rewrite code. Three independent things have to be reached:</p>
 * <ul>
 * <li>the spread cone is a number the rewrite publishes on its own -
 * {@code WeaponAccuracyRuntime.spread()};</li>
 * <li>the recoil reaches the shot direction as the two offset arguments of
 * {@code TableRecoil.direction}, and reaches the view as the camera delta
 * {@code CameraLookCompensation.apply} folds into the player rotation.</li>
 * </ul>
 *
 * <p>{@link #holdView()} is the reason no aim snapshot is needed. The original mixin had to rebuild the
 * shot direction from a rotation captured at render HEAD, which races the mod's own render-HEAD hook and
 * is only correct if that hook runs later. The rewrite's {@code apply} returns its input untouched when
 * its {@code enabled} argument is false, so forcing that argument false stops the recoil from ever
 * entering the player rotation - the rotation stays the clean aim, the view stops kicking, and the shot
 * direction needs no correction at all. No ordering assumption, no snapshot.</p>
 */
public final class RewriteSpreadOptions {

    private static volatile boolean noSpread;
    private static volatile boolean zeroRecoil;
    private static volatile boolean holdView;

    private RewriteSpreadOptions() {
    }

    public static void update(boolean noSpread, boolean zeroRecoil, boolean holdView) {
        RewriteSpreadOptions.noSpread = noSpread;
        RewriteSpreadOptions.zeroRecoil = zeroRecoil;
        RewriteSpreadOptions.holdView = holdView;
    }

    public static boolean noSpread() {
        return noSpread;
    }

    public static boolean zeroRecoil() {
        return zeroRecoil;
    }

    public static boolean holdView() {
        return holdView;
    }
}
