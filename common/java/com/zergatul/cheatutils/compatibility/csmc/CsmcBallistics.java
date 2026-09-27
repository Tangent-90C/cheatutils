package com.zergatul.cheatutils.compatibility.csmc;

import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.TypedDataComponent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CollectionTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
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
 * double targetSpeed, double gravity. A dual-wield item - Dual Berettas among
 * them - carries two records for the same gun, so the first record is read
 * whatever the count is; it is bounded only so a corrupt or changed layout
 * cannot send the parser off the end of the blob.</li>
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

    /** Upper bound on the record count field, so a changed layout cannot walk past the blob. */
    private static final int MAX_WEAPON_RECORDS = 64;

    /** Depth cap for the nested key walk, so a pathological tag cannot recurse unbounded. */
    private static final int MAX_SEARCH_DEPTH = 8;

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
        Failure failure = new Failure();
        Ballistics ballistics = fromItem(stack, failure);
        if (ballistics == null && !failure.reason.isEmpty()) {
            Failure.reported = failure.reason;
        }
        return ballistics;
    }

    /** Reason the last {@link #fromItem} attempt returned null, for diagnostics. */
    public static String lastFailureReason() {
        return Failure.reported;
    }

    private static Ballistics fromItem(ItemStack stack, Failure failure) {
        if (stack == null || stack.isEmpty()) {
            failure.reason = "no_item";
            return null;
        }

        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData == null) {
            failure.reason = "no_custom_data";
            return null;
        }
        CompoundTag tag = customData.copyTag();

        String prediction = findPrediction(tag);
        if (prediction == null || prediction.isBlank()) {
            prediction = findPredictionInCache(stack);
        }
        if (prediction == null || prediction.isBlank()) {
            String cacheNote = Failure.reported.startsWith("cache_") ? ",cache=" + Failure.reported : "";
            failure.reason = "no_prediction_key[toplevel_keys=" + tag.keySet().size()
                    + ",sample=" + keySample(tag)
                    + ",deep=" + deepKeyDetails(tag)
                    + ",components=" + componentIds(stack) + cacheNote + "]";
            return null;
        }

        byte[] blob;
        try {
            blob = Base64.getDecoder().decode(prediction);
        } catch (IllegalArgumentException e) {
            failure.reason = "bad_base64";
            return null;
        }

        // int, int, boolean, int weaponCount, then weapon records
        if (blob.length < 13 + 45) {
            failure.reason = "blob_too_short(" + blob.length + ")";
            return null;
        }
        int weaponCount = readInt(blob, 9);
        if (weaponCount < 1 || weaponCount > MAX_WEAPON_RECORDS) {
            failure.reason = "weapon_count(" + weaponCount + ")";
            return null;
        }
        // weapon record: long, long, boolean, int, double, double, double. Dual-wield
        // items repeat the same gun per hand, so the first record describes the weapon.
        int record = 13;
        double baseSpeed = readDouble(blob, record + 21);
        double targetSpeed = readDouble(blob, record + 29);
        double gravity = readDouble(blob, record + 37);
        // sanity gates, so a changed format degrades to "no data" instead of wild aim
        if (!isFinite(baseSpeed) || !isFinite(targetSpeed) || !isFinite(gravity)) {
            failure.reason = "non_finite_values";
            return null;
        }
        if (targetSpeed <= 0 || targetSpeed > 4000 || gravity <= 0 || gravity >= 1) {
            failure.reason = "value_out_of_range";
            return null;
        }

        boolean hasBallisticComputer = hasBallisticComputer(tag);

        return new Ballistics(targetSpeed, gravity, hasBallisticComputer);
    }

    private static final class Failure {
        static String reported = "";
        String reason = "";
    }

    /**
     * CSMCMod caches every weapon descriptor it has seen on disk under
     * {@code <gameDir>/csmc_cache/shoot_prediction/<version>/<hash>.bin}, as a 20-byte
     * CSPD envelope around the same blob the item used to carry in the Base64 string.
     * CSMCMod 6.0 keeps only the prediction hash on the item - under
     * {@code CLIENT_SHOOT_PREDICTION_HASH} on older servers, under the server plugin's
     * own key name on newer ones - so the cache is where the numbers still are.
     * <p>
     * Rather than hardcode that plugin key, every integer in the item's custom data is
     * tried as a hash: a value only counts when a cache file with its name actually
     * exists, which a random integer will not.
     */
    private static String findPredictionInCache(ItemStack stack) {
        CompoundTag tag = customDataOf(stack);
        if (tag == null) {
            Failure.reported = "cache_no_tag";
            return null;
        }
        List<Path> directories = cacheDirectories();
        if (directories.isEmpty()) {
            Failure.reported = "cache_no_dir";
            return null;
        }
        List<Long> candidates = collectIntegersDeep(tag, 0);
        for (Long hash : candidates) {
            String name = cacheFileName(hash);
            for (Path directory : directories) {
                Path file = directory.resolve(name);
                byte[] content = readAllBytes(file);
                if (content == null || content.length <= CACHE_HEADER_SIZE + 45) {
                    continue;
                }
                byte[] blob = new byte[content.length - CACHE_HEADER_SIZE];
                System.arraycopy(content, CACHE_HEADER_SIZE, blob, 0, blob.length);
                int weaponCount = readInt(blob, 9);
                if (weaponCount >= 1 && weaponCount <= MAX_WEAPON_RECORDS) {
                    return Base64.getEncoder().encodeToString(blob);
                }
            }
        }
        Failure.reported = "cache_miss[candidates=" + candidates.size()
                + ",dirs=" + directories
                + ",gameDir=" + Minecraft.getInstance().gameDirectory + "]";
        return null;
    }

    /**
     * Every 32-bit integer entry in the tag and its nested compounds. Only int and byte
     * sized values are collected: the prediction hash is an int, and wider doubles and
     * longs on the item are timestamps or cooldowns that can never name a cache file.
     */
    private static List<Long> collectIntegersDeep(CompoundTag tag, int depth) {
        List<Long> values = new ArrayList<>();
        if (depth >= MAX_SEARCH_DEPTH) {
            return values;
        }
        for (String key : tag.keySet()) {
            Tag value = tag.get(key);
            if (value == null) {
                continue;
            }
            if (value.getId() == 3 || value.getId() == 1) {
                values.add(((NumericTag) value).intValue() & 0xFFFFFFFFL);
            }
            Optional<CompoundTag> compound = tag.getCompound(key);
            compound.ifPresent(childTag -> values.addAll(collectIntegersDeep(childTag, depth + 1)));
        }
        return values;
    }

    /**
     * Whether the gun runs its own ballistic computer, read from the runtime flags blob.
     * The known location is {@code CLIENT_WEAPON_RUNTIME_DATA}; 6.0 servers keep it under
     * a plugin key, so any byte array that carries the version byte and the flag bit
     * counts. A gun whose blob is missing simply reports false, and compensation runs.
     */
    private static boolean hasBallisticComputer(CompoundTag tag) {
        Optional<byte[]> known = findBytesDeep(tag, RUNTIME_DATA_KEY, 0);
        if (known.isPresent()) {
            Boolean flag = ballisticComputerFlag(known.get());
            if (flag != null) {
                return flag;
            }
        }
        for (byte[] blob : collectByteArraysDeep(tag, 0)) {
            Boolean flag = ballisticComputerFlag(blob);
            if (flag != null) {
                return flag;
            }
        }
        return false;
    }

    /**
     * Version byte 1 followed by an unsigned short of flags, bit 512 being
     * CLIENT_BALLISTIC_COMPUTER. Null when the blob does not have that shape.
     */
    private static Boolean ballisticComputerFlag(byte[] blob) {
        if (blob.length < 3 || blob[0] != 1) {
            return null;
        }
        int flags = ((blob[1] & 0xFF) << 8) | (blob[2] & 0xFF);
        return (flags & BALLISTIC_COMPUTER_FLAG) != 0;
    }

    private static List<byte[]> collectByteArraysDeep(CompoundTag tag, int depth) {
        List<byte[]> arrays = new ArrayList<>();
        if (depth >= MAX_SEARCH_DEPTH) {
            return arrays;
        }
        for (String key : tag.keySet()) {
            Tag value = tag.get(key);
            if (value instanceof ByteArrayTag byteArrayTag) {
                arrays.add(byteArrayTag.getAsByteArray());
            }
            Optional<CompoundTag> compound = tag.getCompound(key);
            compound.ifPresent(childTag -> arrays.addAll(collectByteArraysDeep(childTag, depth + 1)));
        }
        return arrays;
    }

    private static CompoundTag customDataOf(ItemStack stack) {
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        return customData == null ? null : customData.copyTag();
    }

    /** CSPD magic + int + int + int + int length. */
    private static final int CACHE_HEADER_SIZE = 20;

    /**
     * Cache file names are the prediction hash as an unsigned 32-bit hex, so a negative
     * int from the item has to be truncated before formatting.
     */
    private static String cacheFileName(long value) {
        return String.format("%08x.bin", value & 0xFFFFFFFFL);
    }

    private static List<Path> cacheDirectories() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gameDirectory == null) {
            return List.of();
        }
        Path root = mc.gameDirectory.toPath().resolve("csmc_cache").resolve("shoot_prediction");
        List<Path> directories = new ArrayList<>();
        try (var stream = Files.list(root)) {
            stream.filter(Files::isDirectory).forEach(directories::add);
        } catch (IOException e) {
            return List.of();
        }
        return directories;
    }

    private static byte[] readAllBytes(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Every key name the custom data tag holds, nested ones included, with the value
     * abbreviated, so the diagnostics can show exactly what the server plugin put on the
     * gun and which entry carries the ballistics.
     */
    private static String deepKeyDetails(CompoundTag tag) {
        StringBuilder details = new StringBuilder();
        collectKeysDeep(tag, 0, details);
        return details.isEmpty() ? "<none>" : details.toString();
    }

    private static void collectKeysDeep(CompoundTag tag, int depth, StringBuilder details) {
        if (depth >= MAX_SEARCH_DEPTH || details.length() > 600) {
            return;
        }
        for (String key : tag.keySet()) {
            Tag tagValue = tag.get(key);
            if (tagValue == null) {
                continue;
            }
            if (details.length() > 0) {
                details.append(' ');
            }
            details.append(key).append('#').append(tagValue.getId())
                    .append('=').append(abbreviate(tagValue));
            Optional<CompoundTag> compound = tag.getCompound(key);
            compound.ifPresent(childTag -> collectKeysDeep(childTag, depth + 1, details));
        }
    }

    private static String abbreviate(Tag value) {
        return switch (value.getId()) {
            case 7 -> {
                byte[] bytes = ((ByteArrayTag) value).getAsByteArray();
                yield "[b" + bytes.length + ":" + hexPrefix(bytes) + "]";
            }
            case 8 -> {
                String text = ((StringTag) value).value();
                yield "[s" + text.length() + ":" + (text.length() > 24 ? text.substring(0, 24) : text) + "]";
            }
            case 9 -> "[list" + ((CollectionTag) value).size() + "]";
            case 10 -> "[compound" + ((CompoundTag) value).keySet().size() + "]";
            default -> "[id" + value.getId() + ":" + value + "]";
        };
    }

    private static String hexPrefix(byte[] bytes) {
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < Math.min(8, bytes.length); i++) {
            hex.append(String.format("%02x", bytes[i]));
        }
        return hex.toString();
    }

    /**
     * Data component ids on the item, so the diagnostics can show whether 6.0 moved the
     * weapon data into a component other than custom data.
     */
    private static String componentIds(ItemStack stack) {
        StringBuilder ids = new StringBuilder();
        int shown = 0;
        for (TypedDataComponent<?> component : stack.getComponents()) {
            ResourceLocation id = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(component.type());
            if (shown > 0) {
                ids.append(',');
            }
            ids.append(id);
            if (++shown == 12) {
                break;
            }
        }
        return ids.isEmpty() ? "<none>" : ids.toString();
    }

    /**
     * The weapon blob is read from the custom data tag, and from nested compounds when it is
     * not on top: CSMCMod 6.0 keeps the gun keys inside a child compound whose name it
     * generates at runtime, and on a Bukkit server the custom data also carries the plugin
     * marker compound. A depth-first walk finds the key wherever it sits without knowing
     * any of those names.
     */
    private static String findPrediction(CompoundTag tag) {
        String direct = tag.getStringOr(PREDICTION_KEY, "");
        if (!direct.isBlank()) {
            return direct;
        }
        return findKeyDeep(tag, PREDICTION_KEY, 0);
    }

    private static String findKeyDeep(CompoundTag tag, String key, int depth) {
        if (depth >= MAX_SEARCH_DEPTH) {
            return null;
        }
        for (String child : tag.keySet()) {
            Optional<CompoundTag> compound = tag.getCompound(child);
            if (compound.isEmpty()) {
                continue;
            }
            CompoundTag childTag = compound.get();
            String value = childTag.getStringOr(key, "");
            if (!value.isBlank()) {
                return value;
            }
            String nested = findKeyDeep(childTag, key, depth + 1);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    private static Optional<byte[]> findBytesDeep(CompoundTag tag, String key, int depth) {
        if (depth >= MAX_SEARCH_DEPTH) {
            return Optional.empty();
        }
        for (String child : tag.keySet()) {
            Optional<CompoundTag> compound = tag.getCompound(child);
            if (compound.isEmpty()) {
                continue;
            }
            CompoundTag childTag = compound.get();
            Optional<byte[]> value = childTag.getByteArray(key);
            if (value.isPresent()) {
                return value;
            }
            Optional<byte[]> nested = findBytesDeep(childTag, key, depth + 1);
            if (nested.isPresent()) {
                return nested;
            }
        }
        return Optional.empty();
    }

    /**
     * First few key names of a tag, so the diagnostics can show whether the weapon
     * data moved to a nested compound after a mod update.
     */
    private static String keySample(CompoundTag tag) {
        StringBuilder sample = new StringBuilder();
        int shown = 0;
        for (String key : tag.keySet()) {
            if (shown > 0) {
                sample.append(',');
            }
            sample.append(key);
            if (++shown == 8) {
                break;
            }
        }
        return sample.isEmpty() ? "<empty>" : sample.toString();
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
