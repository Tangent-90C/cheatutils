package com.zergatul.cheatutils.scripting.api.modules;

import com.zergatul.cheatutils.modules.hacks.TeleportHack;
import com.zergatul.cheatutils.scripting.api.ApiType;
import com.zergatul.cheatutils.scripting.api.ApiVisibility;
import com.zergatul.cheatutils.utils.MathUtils;

public class TeleportApi {

    @ApiVisibility(ApiType.ACTION)
    public boolean toCrosshair(double distance, int repeats) {
        distance = MathUtils.clamp(distance, 1, 1000);
        repeats = MathUtils.clamp(repeats, 0, 100);
        return TeleportHack.instance.teleportToCrosshair(distance, repeats);
    }

    @ApiVisibility(ApiType.ACTION)
    public boolean toPosition(double x, double y, double z, int repeats) {
        x = MathUtils.clamp(x, -30000000, 30000000);
        y = MathUtils.clamp(y, -30000000, 30000000);
        z = MathUtils.clamp(z, -30000000, 30000000);
        repeats = MathUtils.clamp(repeats, 0, 100);
        return TeleportHack.instance.teleportTo(x, y, z, repeats);
    }

    @ApiVisibility(ApiType.ACTION)
    public boolean vertical(double distance, int repeats) {
        distance = MathUtils.absClamp(distance, 1, 1000);
        repeats = MathUtils.clamp(repeats, 0, 100);
        return TeleportHack.instance.verticalTeleport(distance, repeats);
    }

    @ApiVisibility(ApiType.ACTION)
    public boolean vertical(double fromDistance, double toDistance, boolean findSurface, int repeats) {
        fromDistance = MathUtils.absClamp(fromDistance, -1000, 1000);
        toDistance = MathUtils.absClamp(toDistance, -1000, 1000);
        repeats = MathUtils.clamp(repeats, 0, 100);
        return TeleportHack.instance.verticalTeleport(fromDistance, toDistance, findSurface, repeats);
    }
}