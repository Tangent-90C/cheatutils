package com.zergatul.cheatutils.utils;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

public class TeamUtils {

    private TeamUtils() {}

    /**
     * Detects teammates the way servers identify them: scoreboard team,
     * spectator status, or identical glowing color. Servers usually color
     * allied players with team-based glow, so equal glow color plus "both are
     * glowing" is treated as a team signal.
     */
    public static boolean isTeammate(Entity self, Entity other) {
        if (!(self instanceof Player selfPlayer) || !(other instanceof Player otherPlayer)) {
            return false;
        }

        if (otherPlayer.isSpectator() || selfPlayer.isAlliedTo(otherPlayer)) {
            return true;
        }

        return selfPlayer.isCurrentlyGlowing()
                && otherPlayer.isCurrentlyGlowing()
                && selfPlayer.getTeamColor() == otherPlayer.getTeamColor();
    }
}
