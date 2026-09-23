/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package com.zergatul.cheatutils.ballistics;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

public record WeaponFingerprint(String serverId, String dimensionId,
    String itemId, String gunId, String fireMode, String dataHash) {

    private static final Set<String> VOLATILE_NBT_KEYS =
        Set.of("GunCurrentAmmoCount", "HasBulletInBarrel", "Damage",
            "RepairCost", "display", "HeatAmount", "OverHeated");

    public static Optional<WeaponFingerprint> create(Minecraft client,
        ItemStack stack) {
        if (client.level == null || stack == null || stack.isEmpty()) {
            return Optional.empty();
        }

        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        String dimensionId = client.level.dimension().location().toString();
        String serverId = getServerId(client);
        CompoundTag nbt = stack.getTag();
        String gunId = getString(nbt, "GunId");
        if (gunId.isBlank()) {
            return Optional.empty();
        }

        String fireMode = getString(nbt, "GunFireMode");
        String dataHash = sha256(canonicalizeNbt(nbt));
        return Optional.of(new WeaponFingerprint(serverId, dimensionId, itemId,
            gunId, fireMode, dataHash));
    }

    private static String getServerId(Minecraft client) {
        ServerData server = client.getCurrentServer();
        if (server != null && server.ip != null && !server.ip.isBlank()) {
            return server.ip.trim().toLowerCase(Locale.ROOT);
        }
        if (client.isSingleplayer()) {
            return "singleplayer";
        }
        return "unknown";
    }

    private static String getString(CompoundTag nbt, String key) {
        if (nbt == null || !nbt.contains(key, Tag.TAG_STRING)) {
            return "";
        }
        return nbt.getString(key);
    }

    private static CompoundTag getCompound(CompoundTag nbt, String key) {
        if (nbt == null || !nbt.contains(key, Tag.TAG_COMPOUND)) {
            return new CompoundTag();
        }
        return nbt.getCompound(key);
    }

    static String canonicalizeNbt(Tag element) {
        if (element == null) {
            return "null";
        }

        if (element instanceof CompoundTag compound) {
            StringBuilder result = new StringBuilder("{");
            compound.getAllKeys().stream()
                .filter(key -> !VOLATILE_NBT_KEYS.contains(key))
                .sorted(Comparator.naturalOrder()).forEach(key -> {
                    appendLengthPrefixed(result, key);
                    appendLengthPrefixed(result,
                        canonicalizeNbt(compound.get(key)));
                });
            return result.append('}').toString();
        }

        if (element instanceof ListTag list) {
            StringBuilder result = new StringBuilder("[");
            for (Tag child : list) {
                appendLengthPrefixed(result, canonicalizeNbt(child));
            }
            return result.append(']').toString();
        }

        return element.getId() + ":" + element;
    }

    private static void appendLengthPrefixed(StringBuilder builder,
        String value) {
        builder.append(value.length()).append(':').append(value);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public String shortDescription() {
        return gunId + "/" + fireMode + "/"
            + dataHash.substring(0, Math.min(12, dataHash.length()));
    }

    JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("serverId", serverId);
        json.addProperty("dimensionId", dimensionId);
        json.addProperty("itemId", itemId);
        json.addProperty("gunId", gunId);
        json.addProperty("fireMode", fireMode);
        json.addProperty("dataHash", dataHash);
        return json;
    }

    static WeaponFingerprint fromJson(JsonObject json) throws JsonParseException {
        String serverId = getAsString(json.get("serverId"));
        String dimensionId = getAsString(json.get("dimensionId"));
        String itemId = getAsString(json.get("itemId"));
        String gunId = getAsString(json.get("gunId"));
        String fireMode = getAsString(json.get("fireMode"), "");
        String dataHash = getAsString(json.get("dataHash"));
        if (serverId.isBlank() || dimensionId.isBlank() || itemId.isBlank()
            || gunId.isBlank() || dataHash.isBlank()) {
            throw new JsonParseException("Incomplete weapon fingerprint");
        }
        return new WeaponFingerprint(serverId, dimensionId, itemId, gunId,
            fireMode, dataHash);
    }

    private static boolean isString(JsonElement json) {
        if (json == null || !json.isJsonPrimitive()) {
            return false;
        }
        return json.getAsJsonPrimitive().isString();
    }

    private static String getAsString(JsonElement json)
        throws JsonParseException {
        if (!isString(json)) {
            throw new JsonParseException("Not a string: " + json);
        }
        return json.getAsString();
    }

    private static String getAsString(JsonElement json, String fallback) {
        if (!isString(json)) {
            return fallback;
        }
        return json.getAsString();
    }
}
