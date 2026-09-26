package com.zergatul.cheatutils.mixins.common;

import com.zergatul.cheatutils.modules.hacks.ElytraFly;
import net.minecraft.client.resources.sounds.ElytraOnPlayerSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SoundManager.class)
public abstract class MixinSoundManager {

    @Inject(
            at = @At("HEAD"),
            // explicit descriptor: SoundManager has two "play" overloads, a bare name
            // is ambiguous and the injection silently fails (defaultRequire = 0)
            method = "play(Lnet/minecraft/client/resources/sounds/SoundInstance;)Lnet/minecraft/client/sounds/SoundEngine$PlayResult;",
            cancellable = true)
    private void onPlay(SoundInstance sound, CallbackInfoReturnable<SoundEngine.PlayResult> info) {
        if (sound instanceof ElytraOnPlayerSoundInstance) {
            if (!ElytraFly.instance.shouldPlaySound()) {
                info.cancel();
            }
        }
    }

    @Inject(
            at = @At("HEAD"),
            method = "play(Lnet/minecraft/client/resources/sounds/SoundInstance;I)V",
            cancellable = true)
    private void onPlayDelayed(SoundInstance sound, int delay, CallbackInfo info) {
    }
}