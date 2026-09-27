package com.zergatul.cheatutils.mixins.fabric.compatibility.csmc;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.zergatul.cheatutils.csmc.CsmcSpreadOptions;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Hooks the predicted shot direction of CSMCMod: aimed-at direction + random spread + two recoil parts -
 * the smoothed recoil magnitude and the fixed full-auto punch carried in from outside and applied through
 * an ASCII-named vector helper. The three parts are independent flags in {@link CsmcSpreadOptions};
 * the module modes map onto them: dead aim (no spread + both recoil parts, view held), view kick (no
 * spread only), locked legacy (no spread + smooth recoil, punch kept).
 * <p>
 * The seams cannot be ordinary @At targets: Mixin's runtime validator accepts only word characters in
 * member names, descriptors and owners, and CSMC's obfuscated names are not ASCII. Owner-only selectors
 * pass validation and match every call to that owner inside the method, so the exact site is pinned by
 * ordinal - verified against 5.14 with the runtime mixin's own parser: the spread owner is called five
 * times with the magnitude second (the only double return), the smoothing owner twice with the magnitude
 * second (also the only double); the helper is invoked six times, the first two pass plain parameters of
 * the hooked method (left alone), ordinals 2-5 carry the punch (bytecode 382/400, 465/499).
 * <p>
 * The hooked method is static, so the callbacks must be static too. Original expressions still run - only
 * their values are replaced, so a flag turned off restores stock behaviour. FabricMixinPlugin decides
 * whether this mixin applies at all, so a CSMC update that renames these classes only loses this feature.
 */
@Mixin(targets = "me.fadeorite.csmcmod.b$2j")
public abstract class MixinCsmcSpread {

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