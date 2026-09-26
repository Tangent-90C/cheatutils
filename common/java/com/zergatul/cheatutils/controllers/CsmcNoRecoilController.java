package com.zergatul.cheatutils.controllers;

import com.zergatul.cheatutils.configs.ConfigStore;
import com.zergatul.cheatutils.configs.CsmcNoRecoilConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;

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
    private float yRot;
    private float xRot;

    private CsmcNoRecoilController() {

    }

    public boolean isActive() {
        CsmcNoRecoilConfig config = ConfigStore.instance.getConfig().csmcNoRecoilConfig;
        return config.enabled && config.amount > 0 && mc.player != null;
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
}