package com.zergatul.cheatutils.modules.hacks;

import com.zergatul.cheatutils.ballistics.WeaponFingerprint;
import com.zergatul.cheatutils.common.Events;
import com.zergatul.cheatutils.common.events.RenderTickStartEvent;
import com.zergatul.cheatutils.common.events.RenderWorldLastEvent;
import com.zergatul.cheatutils.configs.ConfigStore;
import com.zergatul.cheatutils.modules.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import org.jspecify.annotations.NullMarked;

/**
 * TaCZ applies recoil by writing the player rotation from the camera setup, which on Forge
 * happens inside the camera angles event that runs after {@code Camera.setup} and before the
 * level is rendered. The rotation is snapshotted at the start of the frame, before the event,
 * and restored after the level render, so the recoil never survives a frame: the camera stays
 * still and the rotation the server is told about never contains the recoil.
 */
@NullMarked
public class TaczNoRecoil implements Module {

    public static final TaczNoRecoil instance = new TaczNoRecoil();

    private final Minecraft mc = Minecraft.getInstance();

    private boolean snapshotTaken;
    private float savedXRot;
    private float savedYRot;

    private TaczNoRecoil() {
        Events.RenderTickStart.add(this::onRenderTickStart);
        Events.RenderWorldLast.add(this::onRenderWorldLast);
    }

    private boolean isActive() {
        if (!ConfigStore.instance.getConfig().taczNoRecoil.enabled) {
            return false;
        }
        if (mc.player == null || mc.level == null) {
            return false;
        }
        return WeaponFingerprint.create(mc,
                mc.player.getItemInHand(InteractionHand.MAIN_HAND)).isPresent();
    }

    private void onRenderTickStart(RenderTickStartEvent event) {
        snapshotTaken = false;
        if (!isActive()) {
            return;
        }

        savedXRot = mc.player.getXRot();
        savedYRot = mc.player.getYRot();
        snapshotTaken = true;
    }

    private void onRenderWorldLast(RenderWorldLastEvent event) {
        if (!snapshotTaken) {
            return;
        }
        snapshotTaken = false;
        if (mc.player == null) {
            return;
        }
        if (mc.player.getXRot() != savedXRot) {
            mc.player.setXRot(savedXRot);
        }
        if (mc.player.getYRot() != savedYRot) {
            mc.player.setYRot(savedYRot);
        }
    }
}
