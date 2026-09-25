package com.zergatul.cheatutils.mixins.common.compatibility.tacz;

import com.zergatul.cheatutils.modules.hacks.MoveFreedom;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * String-target mixin into {@code com.tacz.guns.compat.parcool.ParCoolCompat} (merged into the
 * server's TaCZ jar). Its {@code shouldCancelActionEvent} is consulted by myzraid's
 * {@code onParCoolActionEvent} for every ParCool {@code TryTo} action attempt, and cancels
 * <em>every</em> parkour action - FastRun included - while the player is super-overburdened,
 * plus a fixed list of actions (Vault, WallJump, ClimbUp, ...) while fractured. Losing FastRun
 * is what makes normal walking feel slow; forcing the gate false restores the full parkour set.
 */
@Mixin(targets = "com.tacz.guns.compat.parcool.ParCoolCompat", remap = false)
public abstract class MixinTaczParCoolCompat {

    @Inject(
            method = "shouldCancelActionEvent(Ljava/lang/Object;)Z",
            at = @At("HEAD"),
            cancellable = true,
            remap = false)
    private static void onShouldCancelActionEvent(CallbackInfoReturnable<Boolean> cir) {
        if (MoveFreedom.instance.isFractureJumpUnblocked() || MoveFreedom.instance.isOverweightUnblocked()) {
            MoveFreedom.actionGateMixinFired = true;
            cir.setReturnValue(false);
        }
    }
}
