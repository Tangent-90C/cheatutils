/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package com.zergatul.cheatutils.ballistics;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

final class BallisticProfileStore {

    private static final int FILE_VERSION = 2;
    private static final Gson GSON =
        new GsonBuilder().setPrettyPrinting().create();
    private final Path path;

    BallisticProfileStore(Path path) {
        this.path = path;
    }

    Map<WeaponFingerprint, BallisticProfile> load() throws IOException {
        JsonElement parsed;
        try (Reader reader = Files.newBufferedReader(path,
            StandardCharsets.UTF_8)) {
            parsed = JsonParser.parseReader(reader);
        } catch (NoSuchFileException e) {
            return new LinkedHashMap<>();
        }

        if (!parsed.isJsonObject()) {
            throw new JsonParseException("Ballistic profile root is not an object");
        }
        JsonObject root = parsed.getAsJsonObject();
        if (getAsInt(root.get("version"), -1) != FILE_VERSION) {
            throw new JsonParseException("Unsupported ballistic profile version");
        }

        JsonElement profilesElement = root.get("profiles");
        if (profilesElement == null || !profilesElement.isJsonArray()) {
            throw new JsonParseException("Ballistic profiles array is missing");
        }

        Map<WeaponFingerprint, BallisticProfile> result = new LinkedHashMap<>();
        for (JsonElement element : profilesElement.getAsJsonArray()) {
            try {
                BallisticProfile profile = parseProfile(element);
                result.put(profile.getFingerprint(), profile);
            } catch (RuntimeException e) {
                // Ignore one malformed entry without discarding all valid
                // profiles.
            }
        }
        return result;
    }

    private BallisticProfile parseProfile(JsonElement element)
        throws JsonParseException {
        if (!element.isJsonObject()) {
            throw new JsonParseException("Profile entry is not an object");
        }
        JsonObject json = element.getAsJsonObject();
        JsonElement fingerprintElement = json.get("fingerprint");
        if (fingerprintElement == null || !fingerprintElement.isJsonObject()) {
            throw new JsonParseException("Profile fingerprint is missing");
        }
        WeaponFingerprint fingerprint =
            WeaponFingerprint.fromJson(fingerprintElement.getAsJsonObject());

        ArrayList<Double> speeds = parseDoubleArray(json, "acceptedSpeeds");
        ArrayList<Double> drags = parseDoubleArray(json, "acceptedDrags");
        ArrayList<Double> gravities =
            parseDoubleArray(json, "acceptedGravities");

        int rejectedShots =
            Math.max(0, getAsInt(json.get("rejectedShots"), 0));
        long lastUpdated =
            Math.max(0, getAsLong(json.get("lastUpdatedEpochMillis"), 0));
        return new BallisticProfile(fingerprint, speeds, drags, gravities,
            rejectedShots, lastUpdated);
    }

    private ArrayList<Double> parseDoubleArray(JsonObject json, String key)
        throws JsonParseException {
        JsonElement valuesElement = json.get(key);
        if (valuesElement == null || !valuesElement.isJsonArray()) {
            throw new JsonParseException(key + " array is missing");
        }
        ArrayList<Double> values = new ArrayList<>();
        for (JsonElement value : valuesElement.getAsJsonArray()) {
            if (isNumber(value)) {
                values.add(value.getAsDouble());
            }
        }
        return values;
    }

    void save(Collection<BallisticProfile> profiles) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("version", FILE_VERSION);
        JsonArray profilesJson = new JsonArray();
        profiles.stream()
            .sorted((a, b) -> a.getFingerprint().shortDescription()
                .compareTo(b.getFingerprint().shortDescription()))
            .map(this::toJson).forEach(profilesJson::add);
        root.add("profiles", profilesJson);

        Files.createDirectories(path.getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary)) {
                GSON.toJson(root, writer);
            }
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, path,
                    StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private JsonObject toJson(BallisticProfile profile) {
        JsonObject json = new JsonObject();
        json.add("fingerprint", profile.getFingerprint().toJson());
        JsonArray speeds = new JsonArray();
        profile.getAcceptedSpeeds().forEach(speeds::add);
        json.add("acceptedSpeeds", speeds);
        JsonArray drags = new JsonArray();
        profile.getAcceptedDrags().forEach(drags::add);
        json.add("acceptedDrags", drags);
        JsonArray gravities = new JsonArray();
        profile.getAcceptedGravities().forEach(gravities::add);
        json.add("acceptedGravities", gravities);
        json.addProperty("rejectedShots", profile.getRejectedShotCount());
        json.addProperty("lastUpdatedEpochMillis",
            profile.getLastUpdatedEpochMillis());
        return json;
    }

    private static boolean isNumber(JsonElement json) {
        if (json == null || !json.isJsonPrimitive()) {
            return false;
        }
        return json.getAsJsonPrimitive().isNumber();
    }

    private static int getAsInt(JsonElement json, int fallback) {
        if (!isNumber(json)) {
            return fallback;
        }
        return json.getAsInt();
    }

    private static long getAsLong(JsonElement json, long fallback) {
        if (!isNumber(json)) {
            return fallback;
        }
        return json.getAsLong();
    }
}
