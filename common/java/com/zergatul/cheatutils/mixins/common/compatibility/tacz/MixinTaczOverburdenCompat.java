package com.zergatul.cheatutils.mixins.common.compatibility.tacz;

import com.zergatul.cheatutils.modules.hacks.MoveFreedom;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * String-target mixin into the ParCool side of the TaCZ medical integration
 * ({@code com.alrex.parcool.compat.TaczOverburdenCompat}). That class reads the TaCZ
 * {@code fracture}/{@code overburdened} MobEffects by reflection and independently re-imposes
 * the penalties on the ParCool side - cancelling the jump from {@code LivingJumpEvent} and
 * multiplying parkour stamina consumption - which is why neutralising TaCZ's own gates is not
 * enough: the two compat layers are parallel, not chained.
 *
 * <p>{@code blocksJump} gates the fracture jump cancel; {@code getStaminaConsumptionMultiplier}
 * / {@code multiplyStaminaConsumption} gate the overweight stamina drain that kills FastRun and
 * every other parkour move; {@code blocksParCool} cancels all actions when super-overburdened.
 * Forcing them off restores vanilla-parity parkour while the hack is enabled.
 */
@Mixin(targets = "com.alrex.parcool.compat.TaczOverburdenCompat", remap = false)
public abstract class MixinTaczOverburdenCompat {

    @Inject(
            method = "blocksJump(Lnet/minecraft/world/entity/player/Player;)Z",
            at = @At("HEAD"),
            cancellable = true,
            remap = false)
    private static void onBlocksJump(CallbackInfoReturnable<Boolean> cir) {
        if (MoveFreedom.instance.isFractureJumpUnblocked()) {
            MoveFreedom.parcoolGateMixinFired = true;
            cir.setReturnValue(false);
        }
    }

    @Inject(
            method = "blocksParCool(Lnet/minecraft/world/entity/player/Player;)Z",
            at = @At("HEAD"),
            cancellable = true,
            remap = false)
    private static void onBlocksParCool(CallbackInfoReturnable<Boolean> cir) {
        if (MoveFreedom.instance.isOverweightUnblocked()) {
            MoveFreedom.parcoolGateMixinFired = true;
            cir.setReturnValue(false);
        }
    }

    @Inject(
            method = "getStaminaConsumptionMultiplier(Lnet/minecraft/world/entity/player/Player;)I",
            at = @At("HEAD"),
            cancellable = true,
            remap = false)
    private static void onStaminaMultiplier(CallbackInfoReturnable<Integer> cir) {
        if (MoveFreedom.instance.isOverweightUnblocked()) {
            MoveFreedom.parcoolGateMixinFired = true;
            cir.setReturnValue(1);
        }
    }

    @Inject(
            method = "multiplyStaminaConsumption(Lnet/minecraft/world/entity/player/Player;I)I",
            at = @At("HEAD"),
            cancellable = true,
            remap = false)
    private static void onMultiplyStaminaConsumption(net.minecraft.world.entity.player.Player player,
                                                     int stamina,
                                                     CallbackInfoReturnable<Integer> cir) {
        if (MoveFreedom.instance.isOverweightUnblocked()) {
            MoveFreedom.parcoolGateMixinFired = true;
            cir.setReturnValue(stamina);
        }
    }
}
