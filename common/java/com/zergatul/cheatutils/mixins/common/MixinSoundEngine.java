package com.zergatul.cheatutils.mixins.common;

import com.zergatul.cheatutils.modules.esp.SoundEsp;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * SoundEngine.play is the single point every sound passes through, including mods
 * that skip vanilla sound packets (CSMC replays enemy gunshots locally as custom
 * AbstractSoundInstance subclasses). It has to be hooked here because SoundInstance
 * became an interface in 1.20.2, so its getX()/getY()/getZ() have no body to inject
 * into - the former MixinSoundInstance failed to apply on 1.21.6.
 */
@Mixin(SoundEngine.class)
public abstract class MixinSoundEngine {

    @Inject(
            at = @At("HEAD"),
            // require = 1 so the injection cannot fail silently (cheatutils mixin
            // config uses defaultRequire = 0, which hides descriptor mismatches)
            require = 1,
            method = "play(Lnet/minecraft/client/resources/sounds/SoundInstance;)Lnet/minecraft/client/sounds/SoundEngine$PlayResult;")
    private void onPlay(SoundInstance sound, CallbackInfoReturnable<SoundEngine.PlayResult> info) {
        SoundEsp.instance.onSoundPlayed(sound);
        SoundEsp.instance.onLocalSoundInstance(sound);
    }
}
