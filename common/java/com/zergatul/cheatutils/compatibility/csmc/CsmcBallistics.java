package com.zergatul.cheatutils.compatibility.csmc;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.Base64;
import java.util.Optional;

/**
 * Per-gun ballistics of CSMCMod, read from the ItemStack the server already
 * replicated to us. No CSMC classes are touched, so this survives mod updates
 * as long as the NBT keys stay.
 *
 * The gun item carries two blobs inside {@code DataComponents.CUSTOM_DATA}:
 * <ul>
 * <li>{@code CLIENT_SHOOT_PREDICTION} - Base64 of the weapon descriptor:
 * int, int, boolean, int weaponCount, then one record per weapon. The record
 * starts long, long, boolean, int maxBullets, double baseSpeed,
 * double targetSpeed, double gravity. Only single-weapon blobs are accepted,
 * every observed blob is one.</li>
 * <li>{@code CLIENT_WEAPON_RUNTIME_DATA} - version byte 1, unsigned short
 * flags. Bit 512 is the per-gun CLIENT_BALLISTIC_COMPUTER switch.</li>
 * </ul>
 *
 * Measured values across a full gun pack: baseSpeed is a constant 600,
 * targetSpeed is the real muzzle velocity in blocks per second (280 for
 * pistols up to 940 for DMRs), gravity is a constant 0.08 blocks per tick².
 * CSMCMod's own ballistic computer solves the exact same trajectory with
 * these numbers, so the elevation here replicates it including its fixed
 * +0.1° calibration bias.
 */
public final class CsmcBallistics {

    public record Ballistics(double muzzleVelocity, double gravity, boolean hasBallisticComputer) {
    }

    private static final String PREDICTION_KEY = "CLIENT_SHOOT_PREDICTION";
    private static final String RUNTIME_DATA_KEY = "CLIENT_WEAPON_RUNTIME_DATA";

    /** CLIENT_BALLISTIC_COMPUTER inside the CLIENT_WEAPON_RUNTIME_DATA flag short. */
    private static final int BALLISTIC_COMPUTER_FLAG = 512;

    /** CSMCMod's ballistic computer adds this many degrees of downward pitch to its solution. */
    public static final double CALIBRATION_DEGREES = 0.1;

    private static final int TICKS_PER_SECOND = 20;

    private CsmcBallistics() {
    }

    /**
     * True when the item carries the weapon prediction key, i.e. it is a CSMC gun. Deliberately only
     * checks the key - not the blob layout - so a mod update that changes the descriptor degrades the
     * drop compensation while still identifying the weapon.
     */
    public static boolean isWeapon(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData == null) {
            return false;
        }
        return !customData.copyTag().getStringOr(PREDICTION_KEY, "").isBlank();
    }

    /**
     * Ballistics of the given weapon, or null when this is not a CSMC gun or
     * the prediction blob cannot be parsed with the layout above.
     */
    public static Ballistics fromItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }

        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData == null) {
            return null;
        }
        CompoundTag tag = customData.copyTag();

        String prediction = tag.getStringOr(PREDICTION_KEY, "");
        if (prediction.isBlank()) {
            return null;
        }

        byte[] blob;
        try {
            blob = Base64.getDecoder().decode(prediction);
        } catch (IllegalArgumentException e) {
            return null;
        }

        // int, int, boolean, int weaponCount, then weapon records
        if (blob.length < 13 + 45) {
            return null;
        }
        int weaponCount = readInt(blob, 9);
        if (weaponCount != 1) {
            // multi-weapon blob: picking the wrong record would aim with another gun's velocity
            return null;
        }
        // weapon record: long, long, boolean, int, double, double, double
        int record = 13;
        double baseSpeed = readDouble(blob, record + 21);
        double targetSpeed = readDouble(blob, record + 29);
        double gravity = readDouble(blob, record + 37);
        // sanity gates, so a changed format degrades to "no data" instead of wild aim
        if (!isFinite(baseSpeed) || !isFinite(targetSpeed) || !isFinite(gravity)) {
            return null;
        }
        if (targetSpeed <= 0 || targetSpeed > 4000 || gravity <= 0 || gravity >= 1) {
            return null;
        }

        boolean hasBallisticComputer = false;
        Optional<byte[]> runtimeData = tag.getByteArray(RUNTIME_DATA_KEY);
        if (runtimeData.isPresent() && runtimeData.get().length >= 3 && runtimeData.get()[0] == 1) {
            int flags = ((runtimeData.get()[1] & 0xFF) << 8) | (runtimeData.get()[2] & 0xFF);
            hasBallisticComputer = (flags & BALLISTIC_COMPUTER_FLAG) != 0;
        }

        return new Ballistics(targetSpeed, gravity, hasBallisticComputer);
    }

    /**
     * Pitch offset in radians that makes a projectile dropped by this gun's
     * ballistics land on a point {@code horizontal} blocks away and
     * {@code vertical} blocks above the eye. Negative result means aim up.
     * Exact replication of CSMCMod's ballistic computer: low-arc solution of
     * the projectile equation plus its +0.1° calibration bias. Null when
     * there is no solution (target out of reach).
     */
    public static Double pitchOffsetRadians(Ballistics ballistics, double horizontal, double vertical) {
        if (horizontal < 1e-6) {
            return null;
        }
        double v = ballistics.muzzleVelocity() / TICKS_PER_SECOND;
        double g = ballistics.gravity();
        double vv = v * v;
        // discriminant of the low-arc projectile solution, height difference up-positive
        double discriminant = vv * vv - g * g * horizontal * horizontal - 2 * g * vv * vertical;
        if (g <= 0 || discriminant < 0) {
            return null;
        }
        double elevation = Math.atan((vv - Math.sqrt(discriminant)) / (g * horizontal));
        return -elevation + Math.toRadians(CALIBRATION_DEGREES);
    }

    private static int readInt(byte[] data, int offset) {
        return (data[offset] << 24) | (data[offset + 1] << 16) | (data[offset + 2] << 8) | data[offset + 3];
    }

    private static double readDouble(byte[] data, int offset) {
        long bits = 0;
        for (int i = 0; i < 8; i++) {
            bits = (bits << 8) | (data[offset + i] & 0xFFL);
        }
        return Double.longBitsToDouble(bits);
    }

    private static boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }
}
