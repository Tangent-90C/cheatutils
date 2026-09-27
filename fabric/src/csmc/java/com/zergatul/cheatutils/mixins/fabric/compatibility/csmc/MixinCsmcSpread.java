package com.zergatul.cheatutils.mixins.fabric.compatibility.csmc;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.zergatul.cheatutils.csmc.CsmcSpreadOptions;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Hooks the predicted shot direction of CSMCMod 6.0: aimed-at direction + random spread + two recoil parts -
 * the smoothed recoil magnitude and the fixed full-auto punch carried in from outside and applied through
 * an ASCII-named vector helper. The three parts are independent flags in {@link CsmcSpreadOptions};
 * the module modes map onto them: dead aim (no spread + both recoil parts, view held), view kick (no
 * spread only), locked legacy (no spread + smooth recoil, punch kept).
 * <p>
 * Seams, all verified against the shipped 6.0 jar with the runtime mixin's own parser ({@link
 * MixinCsmcSpreadLegacy} holds the 5.14 names these replace):
 * <ul>
 * <li>the hooked method is {@code me.fadeorite.csmcmod.b$5os.a} - static, ASCII, returns the predicted
 * {@code class_243} and takes the direction state plus the base direction (5.14: {@code b$2j}). It is the
 * branch the fire path takes when the gun's spread state says so; its facade {@code b$5or} calls it;</li>
 * <li>the spread magnitude and the smoothed recoil magnitude are boxed into the offset record with
 * {@code Double.valueOf(D)}, which is called exactly twice - ordinal 0 is the spread, ordinal 1 the
 * smoothed recoil. 6.0 renamed both magnitude helpers to non-ASCII owners, which the runtime validator
 * rejects even for owner-only selectors, so the boxing is the ASCII seam that survives;</li>
 * <li>the punch goes through the basis helper {@code b$5pg.a(Vec3, Vec3, double)}, called six times: the
 * first two scale with the hooked method's own parameters (ordinals 0-1, left alone), the remaining four
 * carry the punch record's scales (ordinals 2-5, bytecode 382/400/465/499) - the same geometry 5.14 had in
 * {@code b$37.a}.</li>
 * </ul>
 * The hooked method is static, so the callbacks must be static too. Original expressions still run - only
 * their values are replaced, so a flag turned off restores stock behaviour. FabricMixinPlugin decides
 * whether this mixin applies at all, so a CSMC update that renames these classes only loses this feature.
 */
@Mixin(targets = "me.fadeorite.csmcmod.b$5os")
public abstract class MixinCsmcSpread {

    @ModifyArg(
            method = "a",
            at = @At(
                    value = "INVOKE",
                    target = "java.lang.Double.valueOf(D)Ljava/lang/Double;",
                    ordinal = 0))
    private static double onSpreadMagnitude(double value) {
        if (CsmcSpreadOptions.noSpread()) {
            return 0.0;
        }
        return value;
    }

    @ModifyArg(
            method = "a",
            at = @At(
                    value = "INVOKE",
                    target = "java.lang.Double.valueOf(D)Ljava/lang/Double;",
                    ordinal = 1))
    private static double onSmoothedRecoilMagnitude(double value) {
        if (CsmcSpreadOptions.smoothRecoil()) {
            return 0.0;
        }
        return value;
    }

    @ModifyArg(
            method = "a",
            at = @At(
                    value = "INVOKE",
                    target = "me.fadeorite.csmcmod.b$5pg.a(Lnet/minecraft/class_243;Lnet/minecraft/class_243;D)Lnet/minecraft/class_243;",
                    ordinal = 2))
    private static double onPunchVerticalTail1(double scale) {
        return scale * CsmcSpreadOptions.patternScale();
    }

    @ModifyArg(
            method = "a",
            at = @At(
                    value = "INVOKE",
                    target = "me.fadeorite.csmcmod.b$5pg.a(Lnet/minecraft/class_243;Lnet/minecraft/class_243;D)Lnet/minecraft/class_243;",
                    ordinal = 3))
    private static double onPunchHorizontalTail1(double scale) {
        return scale * CsmcSpreadOptions.patternScale();
    }

    @ModifyArg(
            method = "a",
            at = @At(
                    value = "INVOKE",
                    target = "me.fadeorite.csmcmod.b$5pg.a(Lnet/minecraft/class_243;Lnet/minecraft/class_243;D)Lnet/minecraft/class_243;",
                    ordinal = 4))
    private static double onPunchVerticalTail2(double scale) {
        return scale * CsmcSpreadOptions.patternScale();
    }

    @ModifyArg(
            method = "a",
            at = @At(
                    value = "INVOKE",
                    target = "me.fadeorite.csmcmod.b$5pg.a(Lnet/minecraft/class_243;Lnet/minecraft/class_243;D)Lnet/minecraft/class_243;",
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
