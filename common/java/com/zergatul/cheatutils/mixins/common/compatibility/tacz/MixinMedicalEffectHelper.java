package com.zergatul.cheatutils.mixins.common.compatibility.tacz;

import com.zergatul.cheatutils.modules.hacks.MoveFreedom;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * String-target mixin: {@code com.tacz.guns.effect.MedicalEffectHelper} is deliberately kept
 * off the compile classpath, so the target is referenced by name. When TaCZ is not installed
 * the mixin is skipped with a log line, exactly like the iris compatibility mixins.
 *
 * <p>{@code shouldBlockJump} is the gate for the fracture jump suppression chain: the client
 * {@code MovementInputUpdateEvent} handler zeroes {@code input.jumping} only when it returns
 * true, and {@code suppressFractureJumpInput} / {@code applyFractureJumpPenalty} (which clear
 * {@code LivingEntity.jumping} and zero upward velocity) re-check it internally. Forcing it to
 * false short-circuits the whole chain.
 *
 * <p>{@code applyBurdenJumpPenalty} is the overweight jump penalty fired from
 * {@code LivingJumpEvent} on both sides; it halves the jump velocity, or zeroes it outright
 * for the super-overburdened state, and it does <em>not</em> consult {@code shouldBlockJump},
 * so it needs its own gate.
 */
@Mixin(targets = "com.tacz.guns.effect.MedicalEffectHelper", remap = false)
public abstract class MixinMedicalEffectHelper {

    @Inject(
            method = "shouldBlockJump(Lnet/minecraft/world/entity/LivingEntity;)Z",
            at = @At("HEAD"),
            cancellable = true,
            remap = false)
    private static void onShouldBlockJump(CallbackInfoReturnable<Boolean> cir) {
        if (MoveFreedom.instance.isFractureJumpUnblocked()) {
            MoveFreedom.jumpGateMixinFired = true;
            cir.setReturnValue(false);
        }
    }

    @Inject(
            method = "applyBurdenJumpPenalty(Lnet/minecraft/world/entity/player/Player;)V",
            at = @At("HEAD"),
            cancellable = true,
            remap = false)
    private static void onApplyBurdenJumpPenalty(Player player, CallbackInfo ci) {
        if (MoveFreedom.instance.isOverweightUnblocked()) {
            MoveFreedom.burdenGateMixinFired = true;
            ci.cancel();
        }
    }
}
