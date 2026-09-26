package com.zergatul.cheatutils.mixins.common;

import com.zergatul.cheatutils.modules.esp.SoundEsp;
import net.minecraft.client.resources.sounds.SoundInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Catches every sound the client plays, including sounds created by other mods that
 * bypass vanilla sound packets (e.g. CSMC reconstructs enemy gunshots as custom
 * EntityBoundSoundInstance subclasses). SoundEngine queries getX()/getY()/getZ() every
 * frame for each active channel, so this is the single common denominator.
 */
@Mixin(SoundInstance.class)
public interface MixinSoundInstance {

    @Inject(
            at = @At("HEAD"),
            require = 1,
            method = "getX()D")
    private void onGetX(CallbackInfoReturnable<Double> info) {
        SoundEsp.instance.onActiveSound((SoundInstance) (Object) this);
    }
}
