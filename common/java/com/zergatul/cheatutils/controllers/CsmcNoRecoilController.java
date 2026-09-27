package com.zergatul.cheatutils.controllers;

import com.zergatul.cheatutils.common.Events;
import com.zergatul.cheatutils.configs.ConfigStore;
import com.zergatul.cheatutils.configs.CsmcNoRecoilConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;

import java.lang.reflect.Method;

/**
 * Removes rotation offset that mods add to the view, for example the recoil kick of a shooter mod.
 * <p>
 * Mouse look is applied after the client tick starts and before the frame is rendered, so the rotation
 * captured at {@code GameRenderer.render} HEAD is the one the player actually aimed at: it contains the
 * mouse movement and none of the view offsets added later while rendering. The camera and the player
 * rotation are then pulled back towards it. Amount scales the correction, so a partial value confirms the
 * offset is really being removed instead of hiding a different problem.
 */
public class CsmcNoRecoilController {

    public static final CsmcNoRecoilController instance = new CsmcNoRecoilController();

    private final Minecraft mc = Minecraft.getInstance();

    private boolean snapshotValid;
    private boolean sending;
    private boolean spreadOptionsSearched;
    private Method spreadOptionsSetter;
    private float yRot;
    private float xRot;
    private float sentYRot;
    private float sentXRot;

    private CsmcNoRecoilController() {
        Events.ClientTickStart.add(this::onTickStart);
        Events.BeforeSendPlayerPos.add(this::onBeforeSendPosition);
        Events.AfterSendPlayerPos.add(this::onAfterSendPosition);
    }

        public boolean isActive() {
        CsmcNoRecoilConfig config = ConfigStore.instance.getConfig().csmcNoRecoilConfig;
        // the view hold is what a locked crosshair means; mode 2 leaves the punch visible
        return config.enabled
                && config.mode != 2
                && config.amount > 0
                && mc.player != null;
    }

    /**
     * Whether the locked-precise override is replacing CSMC's shot direction with the clean
     * aim snapshot. That override runs after CSMCMod's own ballistic computer, so the gun's
     * bullet-drop elevation is discarded - AimAssist then has to compensate itself even for
     * guns that have the ballistic computer.
     */
    public boolean isDiscardingBallisticElevation() {
        CsmcNoRecoilConfig config = ConfigStore.instance.getConfig().csmcNoRecoilConfig;
        return config.enabled && config.mode == 4 && snapshotValid;
    }

        private void onTickStart() {
        CsmcNoRecoilConfig config = ConfigStore.instance.getConfig().csmcNoRecoilConfig;
        // every flag must die with the module: the hooks live inside CSMC's own code path, so without
        // this gate a "disabled" module would keep reshaping shot direction
        boolean active = config.enabled && mc.player != null;
        // punch scale: dead aim zeroes it (stream sits low), every other mode keeps it. Locked precise
        // goes further: the shot direction itself is rebuilt from the pre-punch aim snapshot, so the
        // direction leaving the client is the crosshair automatically - no tuning.
        float patternScale = config.mode == 1 ? 0.0f : 1.0f;
        pushSpreadOptions(
                active && config.mode == 1,
                active ? patternScale : 1.0f,
                active,
                active && config.mode == 4 && snapshotValid,
                yRot,
                xRot);
    }

    /**
     * The CSMC mixin lives in a source set that is compiled without the mixin annotation processor and is
     * not on this source set's compile classpath, and common sources are shared with loaders that don't have
     * it at all. Reflection keeps both directions free of a compile dependency.
     * <p>
     * Throwable rather than ReflectiveOperationException: Mixin rejects loading classes from inside a mixin
     * package with an IllegalClassLoadError, which is an Error. This runs every tick, so a surprise here must
     * degrade to doing nothing instead of taking the client down.
     */
        private void pushSpreadOptions(boolean smoothRecoil, float patternScale, boolean noSpread,
            boolean overrideReturn, float snapshotYaw, float snapshotPitch) {
        try {
            if (spreadOptionsSetter == null && !spreadOptionsSearched) {
                spreadOptionsSetter = Class
                        .forName("com.zergatul.cheatutils.csmc.CsmcSpreadOptions")
                        .getMethod("update", boolean.class, float.class, boolean.class,
                                boolean.class, float.class, float.class);
                spreadOptionsSearched = true;
            }
            if (spreadOptionsSetter != null) {
                spreadOptionsSetter.invoke(null, smoothRecoil, patternScale, noSpread,
                        overrideReturn, snapshotYaw, snapshotPitch);
            }
        } catch (Throwable error) {
            // absent on loaders without the csmc source set, or the class was renamed - nothing to push
        }
    }

    /**
     * Called at the very start of a rendered frame, after mouse look has been applied.
     */
    public void snapshot() {
        if (mc.player == null) {
            snapshotValid = false;
            return;
        }
        yRot = mc.player.getYRot();
        xRot = mc.player.getXRot();
        snapshotValid = true;
    }

    public float removeYawOffset(float current) {
        if (!snapshotValid) {
            return current;
        }
        // wrapDegrees keeps the correction sign correct across the +/-180 wrap and around full turns
        return current - amount() * Mth.wrapDegrees(current - yRot);
    }

    public float removePitchOffset(float current) {
        if (!snapshotValid) {
            return current;
        }
        return current - amount() * (current - xRot);
    }

    private float amount() {
        return ConfigStore.instance.getConfig().csmcNoRecoilConfig.getAmount();
    }

    /**
     * Sends the aimed-at rotation instead of whatever offset a mod applied after it. Recoil offsets show up
     * in the position the server receives even when the view itself is already corrected, because the packet
     * is built before the render frame restores the view. Reusing the frame snapshot keeps "what you see is
     * what the server gets".
     */
    private void onBeforeSendPosition() {
        if (!snapshotValid || mc.player == null || !ConfigStore.instance.getConfig().csmcNoRecoilConfig.sendCleanRotation) {
            return;
        }
        sending = true;
        sentYRot = mc.player.getYRot();
        sentXRot = mc.player.getXRot();
        mc.player.setYRot(yRot);
        mc.player.setXRot(xRot);
    }

    private void onAfterSendPosition() {
        if (!sending || mc.player == null) {
            return;
        }
        sending = false;
        mc.player.setYRot(sentYRot);
        mc.player.setXRot(sentXRot);
    }
}