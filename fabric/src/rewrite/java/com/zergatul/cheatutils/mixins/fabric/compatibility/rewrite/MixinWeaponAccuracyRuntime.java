package com.zergatul.cheatutils.mixins.fabric.compatibility.rewrite;

import com.zergatul.cheatutils.rewrite.RewriteSpreadOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hooks the CSMC protocol rewrite's own accuracy code, which is the equivalent of what
 * {@code MixinCsmcSpread} does inside the obfuscated mod's shot-direction method.
 *
 * <p>The rewrite keeps the original's separation of concerns, so the three things the obfuscated mixin
 * had to reach into bytecode ordinals for are plain named calls here, and every callback needs only
 * primitives:</p>
 * <ul>
 * <li>the spread cone is published on its own by {@code WeaponAccuracyRuntime.spread()}. Zeroing it
 * removes scatter for every pellet;</li>
 * <li>the recoil reaches the shot direction as the two offset arguments of
 * {@code TableRecoil.direction}, called exactly once per round from {@code shotDirections}. Zeroing
 * those two arguments removes the recoil table's contribution to where the bullet goes;</li>
 * <li>the recoil reaches the view as the camera delta {@code CameraLookCompensation.apply} folds into
 * the player rotation. Its {@code enabled} argument makes it return its input untouched, so forcing it
 * false keeps the rotation clean instead of correcting it afterwards.</li>
 * </ul>
 *
 * <p>That last one is what makes dead aim work without an aim snapshot. The original mixin rebuilt the
 * shot direction from a rotation captured at render HEAD, which only holds if the mod's own render-HEAD
 * hook runs after it. Here the recoil never enters the rotation in the first place, so
 * {@code shotDirections} already receives the clean aim and no ordering assumption is involved.</p>
 *
 * <p>Both hooked methods are static, so the callbacks are static too. Nothing here needs the rewrite on
 * the compile classpath: the targets stay strings, the way the obfuscated mod's mixins do. The owners
 * and descriptors in the {@code @At} are the rewrite's own classes, which intermediary remapping leaves
 * alone, so they are stable across the rewrite's remapped jar.</p>
 */
@Mixin(targets = "net.csmc.rewrite.fabric.settings.WeaponAccuracyRuntime")
public abstract class MixinWeaponAccuracyRuntime {

    /**
     * No spread - but only on the crosshair. {@code spread()} feeds nothing but
     * {@code crosshairSpread()}, so this stops the reticle blooming and nothing else.
     * <p>
     * The rewrite never applies scatter to the directions it sends: {@code shotDirections} hands every
     * pellet of a round the identical {@code Direction}, and the C2S {@code Shot} frame (id 10) carries
     * only the yaw/pitch lists with no inaccuracy field. Where the pellets actually land is therefore
     * not decided on this side, and no hook here can change it. This is kept because a held crosshair
     * is part of what dead aim looks like, not because it groups the impacts.</p>
     */
    @Inject(method = "spread", at = @At("HEAD"), cancellable = true)
    private static void onSpread(CallbackInfoReturnable<Double> info) {
        if (RewriteSpreadOptions.noSpread()) {
            info.setReturnValue(0.0);
        }
    }

    /**
     * Hold the view: the camera delta is not folded into the player rotation, so the rotation the
     * rewrite later reads back for the shot direction is still the clean aim. {@code advanceRecoil}
     * runs before this call and is unaffected, so the recoil table keeps advancing and the crosshair
     * spread keeps updating - only the rotation write is neutralized.
     */
    @ModifyArg(
            method = "applyCameraLook",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/csmc/rewrite/domain/combat/CameraLookCompensation;apply(FFFFZ)" +
                            "Lnet/csmc/rewrite/domain/combat/CameraLookCompensation$Look;",
                    ordinal = 0),
            index = 4)
    private static boolean onLookEnabled(boolean value) {
        return RewriteSpreadOptions.holdView() ? false : value;
    }

    /**
     * No recoil in the shot direction: the pitch offset the rewrite would otherwise rotate the bullet
     * by is dropped. Index 2 of {@code direction(float,float,double,double)} is the pitch offset, the
     * value that survives after the view punch has been taken out for the camera.
     */
    @ModifyArg(
            method = "shotDirections",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/csmc/rewrite/domain/combat/TableRecoil;direction(FFDD)" +
                            "Lnet/csmc/rewrite/domain/combat/TableRecoil$Direction;",
                    ordinal = 0),
            index = 2)
    private static double onShotPitchOffset(double value) {
        return RewriteSpreadOptions.zeroRecoil() ? 0.0 : value;
    }

    /** No recoil in the shot direction: the yaw offset, same call site as the pitch one. */
    @ModifyArg(
            method = "shotDirections",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/csmc/rewrite/domain/combat/TableRecoil;direction(FFDD)" +
                            "Lnet/csmc/rewrite/domain/combat/TableRecoil$Direction;",
                    ordinal = 0),
            index = 3)
    private static double onShotYawOffset(double value) {
        return RewriteSpreadOptions.zeroRecoil() ? 0.0 : value;
    }
}
