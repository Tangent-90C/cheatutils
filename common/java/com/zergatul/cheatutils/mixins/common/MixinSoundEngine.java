package com.zergatul.cheatutils.mixins.common;

import com.zergatul.cheatutils.modules.esp.SoundEsp;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SoundEngine.class)
public abstract class MixinSoundEngine {

    @Inject(
            at = @At("HEAD"),
            method = "play(Lnet/minecraft/client/resources/sounds/SoundInstance;)Lnet/minecraft/client/sounds/SoundEngine$PlayResult;")
    private void onPlay(SoundInstance sound, CallbackInfoReturnable<SoundEngine.PlayResult> info) {
        SoundEsp.instance.onLocalSoundInstance(sound);
    }
}
