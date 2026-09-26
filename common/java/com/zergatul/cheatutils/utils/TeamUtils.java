package com.zergatul.cheatutils.utils;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

public class TeamUtils {

    private static final ThreadLocal<Boolean> comparingTeamColor = new ThreadLocal<>();

    private TeamUtils() {}

    /**
     * Detects teammates the way servers identify them: scoreboard team,
     * spectator status, or identical glowing color. Servers usually color
     * allied players with team-based glow, so equal glow color plus "both are
     * glowing" is treated as a team signal.
     * <p>
     * {@code getTeamColor} is hooked by MixinEntity to return the EntityEsp glow color, so it can lead
     * back into this method through {@code isValidEntity}. The flag below makes that re-entry return
     * false instead of recursing until the stack blows.
     */
    public static boolean isTeammate(Entity self, Entity other) {
        if (!(self instanceof Player selfPlayer) || !(other instanceof Player otherPlayer)) {
            return false;
        }

        if (otherPlayer.isSpectator() || selfPlayer.isAlliedTo(otherPlayer)) {
            return true;
        }

        if (comparingTeamColor.get() != null) {
            return false;
        }
        comparingTeamColor.set(Boolean.TRUE);
        try {
            return selfPlayer.isCurrentlyGlowing()
                    && otherPlayer.isCurrentlyGlowing()
                    && selfPlayer.getTeamColor() == otherPlayer.getTeamColor();
        } finally {
            comparingTeamColor.remove();
        }
    }
}
