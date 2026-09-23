/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package com.zergatul.cheatutils.ballistics;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

/** Reads installed gun-pack data without bundling or hardcoding gun values. */
public final class LocalBallisticDataSource {

    private static final Pattern TACZ_GUN_PATH =
        Pattern.compile("(?:^|/)data/([^/]+)/data/guns/([^/]+)_data\\.json$");
    private static final long REFRESH_INTERVAL_MILLIS = 2000;
    private static final double TACZ_DEFAULT_SPEED_MODIFIER = 2;
    private static final Set<String> IGNORE_JSON_NAMES =
        Set.of("gunpack.meta.json", "gunpack_info.json");

    private final Map<String, Entry> entries = new HashMap<>();
    private final Path gameDirOverride;
    private long lastRefreshMillis;
    private Status status = new Status("not loaded", 0, 0, 0);

    public LocalBallisticDataSource() {
        this(null);
    }

    LocalBallisticDataSource(Path gameDir) {
        gameDirOverride = gameDir;
    }

    void refreshNow() {
        lastRefreshMillis = System.currentTimeMillis();
        refresh();
    }

    public Lookup lookup(WeaponFingerprint fingerprint, ItemStack stack) {
        refreshIfNeeded();
        if (fingerprint == null) {
            return Lookup.missing("no supported weapon fingerprint");
        }

        List<String> candidates = new ArrayList<>();
        if (!fingerprint.gunId().isBlank()) {
            candidates.add(fingerprint.gunId());
        }
        if (!fingerprint.itemId().isBlank()) {
            candidates.add(fingerprint.itemId());
        }

        for (String candidate : candidates) {
            Entry entry = entries.get(candidate);
            if (entry == null) {
                continue;
            }
            Path gameDir = gameDirOverride != null ? gameDirOverride
                : Minecraft.getInstance().gameDirectory.toPath();
            CompoundTag nbt = stack == null ? null : stack.getTag();
            return entry.resolve(nbt, fingerprint.fireMode(),
                taczSpeedModifier(gameDir));
        }
        return Lookup.missing("gun data not found for " + fingerprint.gunId());
    }

    Lookup lookup(WeaponFingerprint fingerprint, CompoundTag nbt) {
        refreshIfNeeded();
        if (fingerprint == null) {
            return Lookup.missing("no supported weapon fingerprint");
        }
        Entry entry = entries.get(fingerprint.gunId());
        if (entry == null) {
            return Lookup.missing("gun data not found for "
                + fingerprint.gunId());
        }
        Path gameDir = gameDirOverride != null ? gameDirOverride
            : Minecraft.getInstance().gameDirectory.toPath();
        return entry.resolve(nbt, fingerprint.fireMode(),
            taczSpeedModifier(gameDir));
    }

    public Status getStatus() {
        refreshIfNeeded();
        return status;
    }

    private void refreshIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastRefreshMillis < REFRESH_INTERVAL_MILLIS) {
            return;
        }
        lastRefreshMillis = now;
        refresh();
    }

    private void refresh() {
        entries.clear();
        int files = 0;
        int errors = 0;
        int modJars = 0;
        Path gameDir = gameDirOverride != null ? gameDirOverride
            : Minecraft.getInstance().gameDirectory.toPath();
        Path tacz = gameDir.resolve("tacz");
        if (Files.isDirectory(tacz)) {
            try {
                List<Path> paths;
                try (var stream = Files.list(tacz)) {
                    paths = stream.sorted().toList();
                }
                for (Path path : paths) {
                    if (Files.isDirectory(path)) {
                        int[] counts = scanTaczDirectory(path);
                        files += counts[0];
                        errors += counts[1];
                    } else if (path.getFileName().toString().endsWith(".zip")) {
                        int[] counts = scanTaczZip(path);
                        files += counts[0];
                        errors += counts[1];
                    }
                }
            } catch (IOException e) {
                errors++;
            }
        }

        // TaCZ ships its default gun pack inside the mod jar itself, and gun packs are
        // often installed as mods too, so jar scanning is the only source on many installs.
        Path mods = gameDir.resolve("mods");
        if (Files.isDirectory(mods)) {
            try (var stream = Files.list(mods)) {
                for (Path path : stream
                    .filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .sorted().toList()) {
                    modJars++;
                    int[] counts = scanTaczZip(path);
                    files += counts[0];
                    errors += counts[1];
                }
            } catch (IOException ignored) {
            }
        }

        String source = Files.isDirectory(tacz) ? "tacz dir + " + modJars
            + " mod jars" : "tacz directory not found, " + modJars
            + " mod jars scanned";
        status = new Status(source, entries.size(), files, errors);
    }

    private int[] scanTaczDirectory(Path root) {
        int files = 0;
        int errors = 0;
        try (var stream = Files.walk(root)) {
            for (Path path : stream.filter(Files::isRegularFile).sorted()
                .toList()) {
                Matcher matcher = TACZ_GUN_PATH.matcher(
                    root.relativize(path).toString().replace('\\', '/'));
                if (!matcher.find()) {
                    continue;
                }
                files++;
                try (Reader reader =
                    Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    putEntry(matcher.group(1) + ":" + matcher.group(2), Entry
                        .tacz(JsonParser.parseReader(reader),
                            path.toString()));
                } catch (Exception e) {
                    errors++;
                }
            }
        } catch (IOException e) {
            errors++;
        }
        return new int[]{files, errors};
    }

    private int[] scanTaczZip(Path path) {
        int files = 0;
        int errors = 0;
        try (ZipFile zip = new ZipFile(path.toFile())) {
            for (ZipEntry zipEntry : zip.stream()
                .sorted(Comparator.comparing(ZipEntry::getName)).toList()) {
                Matcher matcher = TACZ_GUN_PATH.matcher(zipEntry.getName());
                if (!matcher.find()) {
                    continue;
                }
                files++;
                try (InputStream input = zip.getInputStream(zipEntry);
                    Reader reader =
                    new InputStreamReader(input, StandardCharsets.UTF_8)) {
                    putEntry(matcher.group(1) + ":" + matcher.group(2),
                        Entry.tacz(JsonParser.parseReader(reader),
                            path + "!" + zipEntry.getName()));
                } catch (Exception e) {
                    errors++;
                }
            }
        } catch (IOException e) {
            errors++;
        }
        return new int[]{files, errors};
    }

    private void putEntry(String id, Entry entry) {
        if (!IGNORE_JSON_NAMES.contains(id)) {
            entries.put(id, entry);
        }
    }

    private static double number(JsonObject object, String key,
        double fallback) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive()
            && value.getAsJsonPrimitive().isNumber() ? value.getAsDouble()
                : fallback;
    }

    private static double taczSpeedModifier(Path gameDir) {
        Path config = gameDir.resolve("config/tacz-common.toml");
        if (!Files.isRegularFile(config)) {
            return TACZ_DEFAULT_SPEED_MODIFIER;
        }
        try {
            for (String line : Files.readAllLines(config,
                StandardCharsets.UTF_8)) {
                if (line.trim().startsWith("GlobalBulletSpeedModifier")) {
                    String value = line.substring(line.indexOf('=') + 1).trim();
                    return Double.parseDouble(value);
                }
            }
        } catch (Exception ignored) {
        }
        return TACZ_DEFAULT_SPEED_MODIFIER;
    }

    public record Lookup(boolean found, boolean complete, double muzzleSpeed,
        double drag, double gravity, String source, List<String> missingFields)
    {
        static Lookup missing(String reason) {
            return new Lookup(false, false, 0, 1, 0, reason, List.of(reason));
        }
    }

    public record Status(String state, int gunCount, int files, int errors) {}

    public static final class Entry {

        private final JsonObject json;
        private final String source;

        private Entry(JsonObject json, String source) {
            this.json = json;
            this.source = source;
        }

        private static Entry tacz(JsonElement json, String source) {
            return new Entry(json.getAsJsonObject(), source);
        }

        private Lookup resolve(CompoundTag nbt, String fireMode,
            double taczSpeedModifier) {
            JsonObject effective = json.deepCopy();
            List<String> missing = new ArrayList<>();
            JsonObject bullet = child(effective, "bullet");
            double speed = number(bullet, "speed", Double.NaN);
            if (Double.isFinite(speed) && !fireMode.isBlank()) {
                JsonObject adjust =
                    child(child(effective, "fire_mode_adjust"),
                        fireMode.toLowerCase());
                speed += number(adjust, "speed", 0);
            }
            double drag = 1 - number(bullet, "friction", 0);
            double gravity = number(bullet, "gravity", 0);
            if (!Double.isFinite(speed)) {
                missing.add("bullet.speed");
            }
            if (!bullet.has("friction")) {
                missing.add("bullet.friction");
            }
            speed = speed * taczSpeedModifier / 20;
            return new Lookup(true, missing.isEmpty()
                && Double.isFinite(speed) && speed > 0, speed, drag, gravity,
                source, List.copyOf(missing));
        }

        private static JsonObject child(JsonObject object, String key) {
            JsonElement value = object.get(key);
            return value != null && value.isJsonObject()
                ? value.getAsJsonObject() : new JsonObject();
        }
    }
}
