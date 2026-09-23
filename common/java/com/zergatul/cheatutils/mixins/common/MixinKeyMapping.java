package com.zergatul.cheatutils.mixins.common;

import com.zergatul.cheatutils.modules.hacks.TaczAlwaysAim;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * TaCZ keeps the client-side aiming state in sync with the aim key every client tick and
 * sends an aim-off message as soon as the key is released, which fights any attempt to keep
 * the player aiming. Reporting the aim key as held makes TaCZ's own sync keep aiming.
 */
@Mixin(KeyMapping.class)
public abstract class MixinKeyMapping {

    @Inject(method = "isDown", at = @At("HEAD"), cancellable = true)
    private void onIsDown(CallbackInfoReturnable<Boolean> info) {
        KeyMapping key = (KeyMapping) (Object) this;
        if (TaczAlwaysAim.TACZ_AIM_KEY.equals(key.getName())
                && TaczAlwaysAim.instance.isAimKeyForced()) {
            info.setReturnValue(true);
        }
    }
}
