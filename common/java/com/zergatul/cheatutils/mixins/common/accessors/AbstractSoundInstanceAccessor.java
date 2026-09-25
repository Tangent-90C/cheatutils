package com.zergatul.cheatutils.mixins.common.accessors;

import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AbstractSoundInstance.class)
public interface AbstractSoundInstanceAccessor {

    /**
     * Raw volume field, as passed to the sound instance constructor.
     *
     * <p>{@code SoundInstance#getVolume} multiplies this by a value sampled from the resolved
     * {@code Sound} object, which is only filled in once the sound engine has looked the sound up.
     * Reading it before that - for example at {@code SoundManager#play} - throws. The raw field is
     * also the one that carries sounds positioned by the caller, which is what TaCZ uses for
     * third-person shot volume.
     */
    @Accessor("volume")
    float getVolumeRaw_CU();
}
