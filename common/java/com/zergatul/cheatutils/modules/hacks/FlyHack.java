package com.zergatul.cheatutils.modules.hacks;

import com.zergatul.cheatutils.common.TaczCompat;
import com.zergatul.cheatutils.configs.ConfigStore;
import com.zergatul.cheatutils.configs.FlyHackConfig;
import com.zergatul.cheatutils.controllers.NetworkPacketsController;
import com.zergatul.cheatutils.accessors.ServerboundMovePlayerPacketAccessor;
import com.zergatul.cheatutils.modules.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.InteractionHand;

public class FlyHack implements Module {

    public static final FlyHack instance = new FlyHack();

    private final Minecraft mc = Minecraft.getInstance();

    private FlyHack() {
        NetworkPacketsController.instance.addClientPacketHandler(this::onClientPacket);
    }

    private void onClientPacket(NetworkPacketsController.ClientPacketArgs args) {
        if (args.packet instanceof ServerboundMovePlayerPacket packet) {
            FlyHackConfig config = ConfigStore.instance.getConfig().flyHackConfig;
            if (config.enabled) {
                // TaCZ refuses to fire while the player is airborne and far from the position
                // the gun was drawn at, unless the server sees the player as on the ground.
                boolean onGround = config.onGroundFlag
                        || config.reportOnGroundWithGun && holdsTaczGun();
                ((ServerboundMovePlayerPacketAccessor) packet).setOnGround_CU(onGround);
            }
        }
    }

    private boolean holdsTaczGun() {
        return mc.player != null && TaczCompat.holdsTaczGun(
                mc.player.getItemInHand(InteractionHand.MAIN_HAND));
    }
}
