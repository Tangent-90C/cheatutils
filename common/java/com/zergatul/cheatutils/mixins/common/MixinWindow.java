package com.zergatul.cheatutils.mixins.common;

import com.mojang.blaze3d.platform.Window;
import com.zergatul.cheatutils.common.Events;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Runs at the head of {@link Window#updateDisplay}, which is the first thing that happens after the
 * frame is rendered. That position matters: {@code Window.updateDisplay} calls
 * {@code RenderSystem.pollEvents}, which calls {@code GLFW.glfwPollEvents} - and the mouse button
 * events of the window are dispatched from inside that call. So this is the last point in a frame
 * before the client can act on this frame's mouse input.
 */
@Mixin(Window.class)
public abstract class MixinWindow {

    @Inject(at = @At("HEAD"), method = "updateDisplay(Lcom/mojang/blaze3d/TracyFrameCapture;)V")
    private void onBeforeUpdateDisplay(CallbackInfo info) {
        Events.BeforeUpdateDisplay.trigger();
    }
}
