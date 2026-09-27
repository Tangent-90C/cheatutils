package com.zergatul.cheatutils.compatibility.csmc;

import java.lang.reflect.Method;

/**
 * Reads the per-shot timestamp the CSMC fire handler mixin records. The mixin and its holder live in the
 * fabric-only csmc source set, so this bridge resolves them by reflection - on a loader without that
 * source set (or after a CSMC update that renames the handler, where the mixin is not applied at all) the
 * class is simply absent and {@link #lastShotAt()} reports 0.
 */
public final class CsmcShotSignalBridge {

    private static Method lastShotAt;
    private static boolean searched;

    private CsmcShotSignalBridge() {
    }

    /** Milliseconds of the last shot the CSMC fire handler reported, or 0 when there is no such signal. */
    public static long lastShotAt() {
        try {
            if (lastShotAt == null && !searched) {
                lastShotAt = Class
                        .forName("com.zergatul.cheatutils.csmc.CsmcShotSignal")
                        .getMethod("lastShotAt");
                searched = true;
            }
            if (lastShotAt != null) {
                return (Long) lastShotAt.invoke(null);
            }
        } catch (Throwable error) {
            // absent on loaders without the csmc source set, or the class was renamed - no shot signal
        }
        return 0;
    }
}
