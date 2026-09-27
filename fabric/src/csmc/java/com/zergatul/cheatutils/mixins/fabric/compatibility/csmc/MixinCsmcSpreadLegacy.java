package com.zergatul.cheatutils.mixins.fabric.compatibility.csmc;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.zergatul.cheatutils.csmc.CsmcSpreadOptions;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Same seams as {@link MixinCsmcSpread} for CSMCMod 5.14 and earlier, where the shot-direction class was
 * {@code b$2j} and the magnitude helpers were the ASCII classes {@code W} (spread) and {@code S} (recoil
 * smoothing). Mixin cannot name CSMC's obfuscated members directly: the runtime validator accepts only
 * word characters in member names, descriptors and owners, so the spread/recoil seams are pinned with
 * owner-only selectors (name and descriptor left null, which skips validation) plus the ordinal that the
 * bytecode makes unique - the spread owner is called five times with the magnitude second (the only double
 * return), the smoothing owner twice with the magnitude second (also the only double). 6.0 renamed both
 * owners to non-ASCII classes, which even owner-only selectors reject, so that version is served by
 * {@link MixinCsmcSpread} instead. FabricMixinPlugin applies exactly one of the two: the names were
 * reshuffled between versions, and 6.0 kept {@code b$2j} for an unrelated resource loader, so the gate
 * checks a helper the class must reference rather than the name alone.
 */
@Mixin(targets = "me.fadeorite.csmcmod.b$2j")
public abstract class MixinCsmcSpreadLegacy {

    @ModifyExpressionValue(
            method = "a",
            at = @At(value = "INVOKE", target = "Lme/fadeorite/csmcmod/W;", ordinal = 1))
    private static double onSpreadMagnitude(double original) {
        if (CsmcSpreadOptions.noSpread()) {
            return 0.0;
        }
        return original;
    }

    @ModifyExpressionValue(
            method = "a",
            at = @At(value = "INVOKE", target = "Lme/fadeorite/csmcmod/S;", ordinal = 1))
    private static double onSmoothedRecoilMagnitude(double original) {
        if (CsmcSpreadOptions.smoothRecoil()) {
            return 0.0;
        }
        return original;
    }

    @ModifyArg(
            method = "a",
            at = @At(
                    value = "INVOKE",
                    target = "me.fadeorite.csmcmod.b$37.a(Lnet/minecraft/class_243;Lnet/minecraft/class_243;D)Lnet/minecraft/class_243;",
                    ordinal = 2))
    private static double onPunchVerticalTail1(double scale) {
        return scale * CsmcSpreadOptions.patternScale();
    }

    @ModifyArg(
            method = "a",
            at = @At(
                    value = "INVOKE",
                    target = "me.fadeorite.csmcmod.b$37.a(Lnet/minecraft/class_243;Lnet/minecraft/class_243;D)Lnet/minecraft/class_243;",
                    ordinal = 3))
    private static double onPunchHorizontalTail1(double scale) {
        return scale * CsmcSpreadOptions.patternScale();
    }

    @ModifyArg(
            method = "a",
            at = @At(
                    value = "INVOKE",
                    target = "me.fadeorite.csmcmod.b$37.a(Lnet/minecraft/class_243;Lnet/minecraft/class_243;D)Lnet/minecraft/class_243;",
                    ordinal = 4))
    private static double onPunchVerticalTail2(double scale) {
        return scale * CsmcSpreadOptions.patternScale();
    }

    @ModifyArg(
            method = "a",
            at = @At(
                    value = "INVOKE",
                    target = "me.fadeorite.csmcmod.b$37.a(Lnet/minecraft/class_243;Lnet/minecraft/class_243;D)Lnet/minecraft/class_243;",
                    ordinal = 5))
    private static double onPunchHorizontalTail2(double scale) {
        return scale * CsmcSpreadOptions.patternScale();
    }

    @ModifyReturnValue(
            method = "a",
            at = @At("RETURN"))
    private static Vec3 onShotDirection(Vec3 original) {
        if (CsmcSpreadOptions.overrideReturn()) {
            return CsmcSpreadOptions.aimDirection();
        }
        return original;
    }
}