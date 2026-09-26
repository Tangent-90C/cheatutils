package com.zergatul.cheatutils.mixins.common;

import com.zergatul.cheatutils.modules.automation.AimAssist;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Adds the AimAssist rotation correction to the player turn. Hooking the
 * LocalPlayer.turn call rather than the raw mouse delta means vanilla has
 * already converted sensitivity, inverted Y and the spyglass multiplier, so the
 * correction only needs to be expressed in degrees.
 *
 * <p>The default priority keeps this injector after MixinMouseHandlerZoom, which
 * scales the same call with a deliberately lower priority.</p>
 */
@Mixin(MouseHandler.class)
public abstract class MixinMouseHandlerAimAssist {

    @ModifyArg(method = "turnPlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"), index = 0)
    private double onModifyTurnYRot(double yRot) {
        return yRot + AimAssist.instance.getYawCorrection();
    }

    @ModifyArg(method = "turnPlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"), index = 1)
    private double onModifyTurnXRot(double xRot) {
        return xRot + AimAssist.instance.getPitchCorrection();
    }
}
