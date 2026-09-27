package com.zergatul.cheatutils.mixins.common;

import com.zergatul.cheatutils.modules.automation.AimAssist;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Applies the AimAssist rotation correction to the player turn.
 *
 * <p>What {@code MouseHandler.turnPlayer} hands to {@code LocalPlayer.turn} is a mouse delta
 * scaled by sensitivity, and {@code Entity.turn} applies yet another factor of 0.15 to it before
 * it reaches the rotation - both are pixel units, never degrees. Adding degrees to the mouse
 * delta therefore lands as a fraction of the intended angle (15% of it, at any sensitivity), so
 * the correction is injected after vanilla is done with the delta and converted into the units
 * {@code turn} expects.</p>
 */
@Mixin(MouseHandler.class)
public abstract class MixinMouseHandlerAimAssist {

    private static final double TURN_UNITS_PER_DEGREE = 1.0D / 0.15D;

    @Shadow
    @Final
    private Minecraft minecraft;

    @Inject(method = "turnPlayer(D)V", at = @At("RETURN"))
    private void onTurnPlayer(double timeDelta, CallbackInfo info) {
        if (this.minecraft.player == null) {
            return;
        }

        double yaw = AimAssist.instance.getYawCorrection();
        double pitch = AimAssist.instance.getPitchCorrection();
        if (yaw == 0.0D && pitch == 0.0D) {
            return;
        }

        this.minecraft.player.turn(yaw * TURN_UNITS_PER_DEGREE, pitch * TURN_UNITS_PER_DEGREE);
    }
}
