package com.zergatul.cheatutils.mixins.fabric.compatibility.csmc;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.zergatul.cheatutils.csmc.CsmcShotSignal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Hooks the fire handler of CSMCMod 6.0: {@code me.fadeorite.csmcmod.b$5qu.a} - the only method in the
 * class, static, ASCII-named, and the single external caller of the trajectory computation
 * ({@code b$5qz}) that feeds the shot direction {@code b$5os} the no-recoil module hooks. It is the 6.0
 * equivalent of 5.14's {@code b$2cs}, the handler that used to end in the offhand-swap packet.
 * <p>
 * The method runs the CSMC gates and then either returns false (cooldown, empty, not a gun) or performs
 * the shot - effects, trajectory, recoil - and returns true. Returning true is therefore exactly one shot,
 * which is the per-shot signal the blink module releases its buffer on. 6.0 sends no packet for the shot
 * itself, so this is the only signal that survives: the vanilla click is consumed by CSMC while a gun is
 * in hand, and the mouse mixin cancels the vanilla attack path, so a swing packet only happens on the
 * physical click, not once per shot of an automatic weapon.
 * <p>
 * Nothing is modified - the original return value passes through - so the worst a wrong target can do is
 * report shots that never happened, never break firing.
 */
@Mixin(targets = "me.fadeorite.csmcmod.b$5qu")
public abstract class MixinCsmcShot {

    @ModifyReturnValue(
            method = "a",
            at = @At("RETURN"))
    private static boolean onShot(boolean original) {
        if (original) {
            CsmcShotSignal.onShot();
        }
        return original;
    }
}