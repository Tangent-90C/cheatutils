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
 * <p>The rewrite keeps the original's separation of concerns, so the two things the original mixin had
 * to reach into bytecode ordinals for are plain named methods here:</p>
 * <ul>
 * <li>the spread cone is a number the rewrite publishes on its own -
 * {@code WeaponAccuracyRuntime.spread()}. Zeroing it removes scatter for every pellet;</li>
 * <li>the recoil reaches the shot direction through the two offset arguments of
 * {@code TableRecoil.direction}, called once per round from {@code shotDirections}. Zeroing those two
 * arguments removes the recoil table's contribution to where the bullet goes.</li>
 * </ul>
 *
 * <p>Zeroing the direction offsets does not touch the view punch. The rewrite routes the punch through
 * the camera ({@code Frame.cameraPitch/cameraYaw}) and only the residual
 * {@code pitchTarget/yawTarget} into the shot direction, so the view still kicks while the impacts
 * stop following it - which is exactly the "punch kept" half of the legacy locked mode.</p>
 *
 * <p>Both callbacks take only primitives, and both hooked methods are static, so nothing here needs the
 * rewrite on the compile classpath: the targets stay strings, the way the obfuscated mod's mixins do.
 * The owner and descriptor in the {@code @At} are the rewrite's own classes, which remapping leaves
 * alone, so they are stable across the rewrite's intermediary remap.</p>
 */
@Mixin(targets = "net.csmc.rewrite.fabric.settings.WeaponAccuracyRuntime")
public abstract class MixinWeaponAccuracyRuntime {

    /**
     * No spread. Answered at HEAD so the number of return statements in {@code spread} does not matter;
     * the original body is skipped entirely, which is what "the cone is zero" means.
     */
    @Inject(method = "spread", at = @At("HEAD"), cancellable = true)
    private static void onSpread(CallbackInfoReturnable<Double> info) {
        if (RewriteSpreadOptions.noSpread()) {
            info.setReturnValue(0.0);
        }
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
