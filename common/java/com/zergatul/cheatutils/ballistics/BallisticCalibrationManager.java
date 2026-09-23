/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package com.zergatul.cheatutils.ballistics;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.JsonParseException;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

public final class BallisticCalibrationManager {

    private static final Logger LOGGER =
        LogManager.getLogger(BallisticCalibrationManager.class);
    private static final Set<String> SUPPORTED_PROJECTILE_IDS =
        Set.of("tacz:bullet");
    private static final int OWNER_WAIT_TICKS = 3;
    private static final int MAX_TRACKING_TICKS = 20;
    private static final int MAX_VALID_TRANSITIONS = 10;
    private static final int BATCH_SETTLE_TICKS = MAX_TRACKING_TICKS + 1;
    private static final int SAVE_INTERVAL_TICKS = 40;
    private static final double SPEED_MISMATCH_LIMIT = 0.01;

    private final Minecraft client;
    private final BallisticProfileStore store;
    private final LocalBallisticDataSource localDataSource;
    private final BooleanSupplier enabledSupplier;
    private final BooleanSupplier debugSupplier;
    private final Map<WeaponFingerprint, BallisticProfile> profiles =
        new LinkedHashMap<>();
    private final Map<UUID, ProjectileTrack> tracks = new HashMap<>();
    private final Map<ShotBatchKey, ShotBatch> shotBatches = new HashMap<>();
    private ClientLevel trackedWorld;
    private long clientTick;
    private long lastSaveTick;
    private boolean dirty;
    private LocalBallisticDataSource.Lookup lastLocalLookup =
        LocalBallisticDataSource.Lookup.missing("local data disabled");

    public BallisticCalibrationManager(Minecraft client, Path path,
        BooleanSupplier enabledSupplier, BooleanSupplier debugSupplier) {
        this.client = client;
        store = new BallisticProfileStore(path);
        localDataSource = new LocalBallisticDataSource();
        this.enabledSupplier = enabledSupplier;
        this.debugSupplier = debugSupplier;
    }

    public void initialize() {
        try {
            profiles.putAll(store.load());
            LOGGER.info("Loaded {} ballistic calibration profiles",
                profiles.size());
        } catch (IOException | JsonParseException e) {
            LOGGER.warn("Could not load ballistic calibration profiles", e);
        }
    }

    public void onEntityAdded(Entity entity) {
        if (!enabledSupplier.getAsBoolean() || client.player == null
            || client.level == null) {
            return;
        }
        if (trackedWorld != client.level) {
            flushBatches(true);
            tracks.clear();
            trackedWorld = client.level;
        }

        String entityType =
            BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        if (!isSupportedProjectileId(entityType)) {
            if (entity instanceof Projectile projectile
                && isOwnedByLocalPlayer(projectile)) {
                debug("rejected entityType={} reason=unsupported_projectile",
                    entityType);
            }
            return;
        }

        if (!(entity instanceof Projectile projectile)) {
            debug("rejected entityType={} class={} reason=not_projectile",
                entityType, entity.getClass().getName());
            return;
        }

        Entity owner = projectile.getOwner();
        if (owner != null && !owner.getUUID().equals(client.player.getUUID())) {
            return;
        }

        Optional<WeaponFingerprint> fingerprint =
            WeaponFingerprint.create(client, client.player.getMainHandItem());
        if (fingerprint.isEmpty()) {
            debug(
                "rejected entityType={} reason=no_supported_weapon_fingerprint",
                entityType);
            return;
        }
        if (!isFingerprintCompatible(entityType, fingerprint.get())) {
            debug("rejected entityType={} weapon={} reason=weapon_mod_mismatch",
                entityType, fingerprint.get().shortDescription());
            return;
        }

        boolean ownerConfirmed = owner != null;
        ProjectileTrack track = new ProjectileTrack(projectile,
            fingerprint.get(), clientTick, projectile.position(),
            projectile.getDeltaMovement(), isInvalidMedium(projectile));
        track.ownerConfirmed = ownerConfirmed;
        tracks.put(projectile.getUUID(), track);
        debug("tracking projectile={} weapon={} ownerConfirmed={} velocity={}",
            projectile.getUUID(), fingerprint.get().shortDescription(),
            track.ownerConfirmed, format(getSpeed(projectile) * 20));
    }

    public void onClientTick() {
        clientTick++;
        if (client.level != trackedWorld) {
            flushBatches(true);
            tracks.clear();
            trackedWorld = client.level;
            if (dirty) {
                saveNow();
            }
        }

        if (!enabledSupplier.getAsBoolean() || client.player == null
            || client.level == null) {
            flushBatches(true);
            tracks.clear();
            return;
        }

        Iterator<ProjectileTrack> iterator = tracks.values().iterator();
        while (iterator.hasNext()) {
            ProjectileTrack track = iterator.next();
            long age = clientTick - track.loadTick;
            captureTransition(track);
            if (!confirmOwner(track)) {
                if (track.ownerRejected || age > OWNER_WAIT_TICKS) {
                    debug("rejected projectile={} weapon={} reason={}",
                        track.projectile.getUUID(),
                        track.fingerprint.shortDescription(),
                        track.ownerRejected ? "other_owner" : "owner_missing");
                    iterator.remove();
                }
                continue;
            }

            if (track.projectile.isRemoved()
                || track.dragSamples.size() >= MAX_VALID_TRANSITIONS
                || age >= MAX_TRACKING_TICKS) {
                finishTrack(track,
                    track.projectile.isRemoved() ? "removed" : "complete");
                iterator.remove();
            }
        }

        flushBatches(false);
        if (dirty && clientTick - lastSaveTick >= SAVE_INTERVAL_TICKS) {
            saveNow();
        }
    }

    private boolean confirmOwner(ProjectileTrack track) {
        if (track.ownerConfirmed) {
            return true;
        }
        Entity owner = track.projectile.getOwner();
        if (owner == null) {
            return false;
        }
        if (client.player == null
            || !owner.getUUID().equals(client.player.getUUID())) {
            track.ownerRejected = true;
            return false;
        }
        track.ownerConfirmed = true;
        return true;
    }

    private void captureTransition(ProjectileTrack track) {
        if (track.lastSampleTick == clientTick) {
            return;
        }
        long elapsedTicks = clientTick - track.lastSampleTick;
        Vec3 position = track.projectile.position();
        Vec3 velocity = track.projectile.getDeltaMovement();
        boolean invalidMedium = isInvalidMedium(track.projectile);

        if (elapsedTicks != 1 || track.lastInvalidMedium || invalidMedium) {
            debugTransitionRejection(track, "invalid_tick_or_medium");
            updateLastSample(track, position, velocity, invalidMedium);
            return;
        }

        double expectedDistance = track.lastVelocity.length();
        double actualDistance = position.distanceTo(track.lastPosition);
        if (expectedDistance <= 0.001 || !Double.isFinite(actualDistance)) {
            debugTransitionRejection(track, "invalid_distance");
            updateLastSample(track, position, velocity, invalidMedium);
            return;
        }

        double mismatch =
            Math.abs(actualDistance - expectedDistance) / expectedDistance;
        if (mismatch >= SPEED_MISMATCH_LIMIT) {
            debugTransitionRejection(track,
                "position_velocity_mismatch=" + format(mismatch));
            updateLastSample(track, position, velocity, invalidMedium);
            return;
        }

        double horizontalPrevious =
            Math.hypot(track.lastVelocity.x, track.lastVelocity.z);
        double horizontalCurrent = Math.hypot(velocity.x, velocity.z);
        if (horizontalPrevious <= 0.001 || !Double.isFinite(horizontalCurrent)) {
            debugTransitionRejection(track, "invalid_horizontal_speed");
            updateLastSample(track, position, velocity, invalidMedium);
            return;
        }

        double drag = horizontalCurrent / horizontalPrevious;
        double gravity = drag * track.lastVelocity.y - velocity.y;
        if (!Double.isFinite(drag) || !Double.isFinite(gravity) || drag <= 0
            || drag > 1.05 || gravity < 0 || gravity > 1) {
            debugTransitionRejection(track, "invalid_parameters drag="
                + format(drag) + " gravity=" + format(gravity));
            updateLastSample(track, position, velocity, invalidMedium);
            return;
        }

        if (track.muzzleSpeed <= 0) {
            track.muzzleSpeed = track.lastVelocity.length();
        }
        track.dragSamples.add(drag);
        track.gravitySamples.add(gravity);
        updateLastSample(track, position, velocity, invalidMedium);
    }

    private void updateLastSample(ProjectileTrack track, Vec3 position,
        Vec3 velocity, boolean invalidMedium) {
        track.lastPosition = position;
        track.lastVelocity = velocity;
        track.lastInvalidMedium = invalidMedium;
        track.lastSampleTick = clientTick;
    }

    private void debugTransitionRejection(ProjectileTrack track,
        String reason) {
        debug("transition rejected projectile={} weapon={} reason={}",
            track.projectile.getUUID(), track.fingerprint.shortDescription(),
            reason);
    }

    private void finishTrack(ProjectileTrack track, String reason) {
        if (!track.ownerConfirmed || track.muzzleSpeed <= 0
            || track.dragSamples.isEmpty() || track.gravitySamples.isEmpty()) {
            debug(
                "track rejected projectile={} weapon={} transitions={} reason={}",
                track.projectile.getUUID(),
                track.fingerprint.shortDescription(), track.dragSamples.size(),
                reason);
            return;
        }

        ShotBatchKey key = new ShotBatchKey(track.loadTick, track.fingerprint);
        TrackEstimate estimate = new TrackEstimate(track.muzzleSpeed,
            BallisticProfile.median(track.dragSamples),
            BallisticProfile.median(track.gravitySamples));
        shotBatches.computeIfAbsent(key, ignored -> new ShotBatch()).estimates
            .add(estimate);
        debug(
            "track complete projectile={} weapon={} transitions={} speedBps={} drag={} gravity={} reason={}",
            track.projectile.getUUID(), track.fingerprint.shortDescription(),
            track.dragSamples.size(), format(estimate.muzzleSpeed * 20),
            format(estimate.drag), format(estimate.gravity), reason);
    }

    private void flushBatches(boolean force) {
        Iterator<Map.Entry<ShotBatchKey, ShotBatch>> iterator =
            shotBatches.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<ShotBatchKey, ShotBatch> entry = iterator.next();
            if (!force
                && clientTick - entry.getKey().loadTick <= BATCH_SETTLE_TICKS) {
                continue;
            }
            List<TrackEstimate> estimates = entry.getValue().estimates;
            updateProfile(entry.getKey().fingerprint,
                BallisticProfile.median(estimates.stream()
                    .map(TrackEstimate::muzzleSpeed).toList()),
                BallisticProfile.median(
                    estimates.stream().map(TrackEstimate::drag).toList()),
                BallisticProfile.median(
                    estimates.stream().map(TrackEstimate::gravity).toList()),
                estimates.size());
            iterator.remove();
        }
    }

    private void updateProfile(WeaponFingerprint fingerprint, double speed,
        double drag, double gravity, int projectileCount) {
        BallisticProfile profile =
            profiles.computeIfAbsent(fingerprint, BallisticProfile::new);
        BallisticProfile.UpdateResult result =
            profile.addShot(speed, drag, gravity);
        dirty = true;
        debug(
            "profile update weapon={} result={} batchProjectiles={} shotSpeedBps={} profileSpeedBps={} drag={} gravity={} shots={} trajectorySamples={} rejected={} speedMad={} dragMad={} gravityMad={} confidence={}",
            fingerprint.shortDescription(), result, projectileCount,
            format(speed * 20), format(profile.getMuzzleSpeed() * 20),
            format(profile.getDrag()), format(profile.getGravity()),
            profile.getAcceptedShotCount(), profile.getTrajectorySampleCount(),
            profile.getRejectedShotCount(), format(profile.getSpeedMad()),
            format(profile.getDragMad()), format(profile.getGravityMad()),
            profile.getConfidence());
    }

    private boolean isOwnedByLocalPlayer(Projectile projectile) {
        Entity owner = projectile.getOwner();
        return owner != null && client.player != null
            && owner.getUUID().equals(client.player.getUUID());
    }

    static boolean isSupportedProjectileId(String entityType) {
        return SUPPORTED_PROJECTILE_IDS.contains(entityType);
    }

    static boolean isFingerprintCompatible(String entityType,
        WeaponFingerprint fingerprint) {
        if (fingerprint == null) {
            return false;
        }
        return switch (entityType) {
            case "tacz:bullet" -> fingerprint.itemId().startsWith("tacz:");
            default -> false;
        };
    }

    private double getSpeed(Projectile projectile) {
        return projectile.getDeltaMovement().length();
    }

    private boolean isInvalidMedium(Projectile projectile) {
        return projectile.isInWater() || projectile.onGround();
    }

    public BallisticParameters resolveCurrentParameters(
        boolean automaticEnabled, double manualSpeedBlocksPerSecond) {
        return resolveCurrentParameters(automaticEnabled, false,
            manualSpeedBlocksPerSecond);
    }

    public BallisticParameters resolveCurrentParameters(
        boolean automaticEnabled, boolean localDataEnabled,
        double manualSpeedBlocksPerSecond) {
        Optional<WeaponFingerprint> fingerprint = Optional.empty();
        ItemStack stack = ItemStack.EMPTY;
        if (client.player != null && client.level != null) {
            stack = client.player.getMainHandItem();
            fingerprint = WeaponFingerprint.create(client, stack);
        }

        if (localDataEnabled && fingerprint.isPresent()) {
            lastLocalLookup = localDataSource.lookup(fingerprint.get(), stack);
            if (lastLocalLookup.complete()) {
                return new BallisticParameters(lastLocalLookup.muzzleSpeed(),
                    lastLocalLookup.drag(), lastLocalLookup.gravity(),
                    ParameterSource.LOCAL_DATA, fingerprint.get(), 0, 0, 0, 0,
                    0, BallisticProfile.Confidence.HIGH, true,
                    lastLocalLookup.muzzleSpeed(), lastLocalLookup.drag(),
                    lastLocalLookup.gravity());
            }
        } else {
            lastLocalLookup = LocalBallisticDataSource.Lookup
                .missing(localDataEnabled ? "no supported weapon fingerprint"
                    : "local data disabled");
        }

        if (!automaticEnabled) {
            return manualParameters(manualSpeedBlocksPerSecond,
                ParameterSource.MANUAL, fingerprint.orElse(null));
        }
        if (client.player == null || client.level == null) {
            return manualParameters(manualSpeedBlocksPerSecond,
                ParameterSource.MANUAL_FALLBACK, null);
        }
        if (fingerprint.isEmpty()) {
            return manualParameters(manualSpeedBlocksPerSecond,
                ParameterSource.MANUAL_FALLBACK, null);
        }

        BallisticProfile profile = profiles.get(fingerprint.get());
        if (profile == null || !profile.isSpeedUsable()) {
            return fallbackParameters(manualSpeedBlocksPerSecond,
                fingerprint.get(), profile);
        }

        /*
         * A profile can have useful drag/gravity samples before it reaches the
         * confidence threshold required by hasTrajectoryModel(). Returning the
         * hard-coded 1/0 fallback in that window made Trajectories render a
         * straight line even though calibration had already collected a
         * physical
         * trajectory. Keep the source/state honest, but use the robust profile
         * estimates as soon as at least one trajectory sample is available.
         */
        boolean hasTrajectorySamples = profile.getTrajectorySampleCount() > 0;
        boolean fullModel = profile.hasTrajectoryModel();
        double drag = hasTrajectorySamples ? profile.getDrag() : 1;
        double gravity = hasTrajectorySamples ? profile.getGravity() : 0;
        return new BallisticParameters(profile.getMuzzleSpeed(), drag, gravity,
            fullModel ? ParameterSource.AUTOMATIC
                : ParameterSource.AUTOMATIC_SPEED_ONLY,
            fingerprint.get(), profile.getAcceptedShotCount(),
            profile.getTrajectorySampleCount(), profile.getSpeedMad(),
            profile.getDragMad(), profile.getGravityMad(),
            profile.getConfidence(), fullModel, profile.getMuzzleSpeed(),
            profile.getDrag(), profile.getGravity());
    }

    private BallisticParameters fallbackParameters(
        double manualSpeedBlocksPerSecond, WeaponFingerprint fingerprint,
        BallisticProfile profile) {
        double speed = Math.max(0, manualSpeedBlocksPerSecond) / 20;
        return new BallisticParameters(speed, speed > 0 ? 1 : 0, 0,
            speed > 0 ? ParameterSource.MANUAL_FALLBACK : ParameterSource.OFF,
            fingerprint, profile == null ? 0 : profile.getAcceptedShotCount(),
            profile == null ? 0 : profile.getTrajectorySampleCount(),
            profile == null ? 0 : profile.getSpeedMad(),
            profile == null ? 0 : profile.getDragMad(),
            profile == null ? 0 : profile.getGravityMad(),
            profile == null ? BallisticProfile.Confidence.WARMUP
                : profile.getConfidence(),
            false, profile == null ? 0 : profile.getMuzzleSpeed(),
            profile == null ? 0 : profile.getDrag(),
            profile == null ? 0 : profile.getGravity());
    }

    private BallisticParameters manualParameters(
        double manualSpeedBlocksPerSecond, ParameterSource source,
        WeaponFingerprint fingerprint) {
        double speed = Math.max(0, manualSpeedBlocksPerSecond) / 20;
        return new BallisticParameters(speed, speed > 0 ? 1 : 0, 0,
            speed > 0 ? source : ParameterSource.OFF, fingerprint, 0, 0, 0, 0,
            0, BallisticProfile.Confidence.WARMUP, false, 0, 0, 0);
    }

    public int getKnownProfileCount() {
        return profiles.size();
    }

    public LocalBallisticDataSource.Lookup getLastLocalLookup() {
        return lastLocalLookup;
    }

    public LocalBallisticDataSource.Status getLocalDataStatus() {
        return localDataSource.getStatus();
    }

    public boolean hasPendingProfileSave() {
        return dirty;
    }

    public void onWorldUnload() {
        onClientStopping();
    }

    public void onClientStopping() {
        for (ProjectileTrack track : tracks.values()) {
            finishTrack(track, "client_stopping");
        }
        tracks.clear();
        flushBatches(true);
        saveNow();
    }

    private void saveNow() {
        if (!dirty) {
            return;
        }
        try {
            store.save(profiles.values());
            dirty = false;
            lastSaveTick = clientTick;
        } catch (IOException | JsonParseException e) {
            LOGGER.warn("Could not save ballistic calibration profiles", e);
        }
    }

    private void debug(String message, Object... arguments) {
        if (debugSupplier.getAsBoolean()) {
            LOGGER.info("[BallisticCalibrationDebug] " + message, arguments);
        }
    }

    private String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.6f", value);
    }

    private static final class ProjectileTrack {

        private final Projectile projectile;
        private final WeaponFingerprint fingerprint;
        private final long loadTick;
        private final List<Double> dragSamples =
            new ArrayList<>(MAX_VALID_TRANSITIONS);
        private final List<Double> gravitySamples =
            new ArrayList<>(MAX_VALID_TRANSITIONS);
        private Vec3 lastPosition;
        private Vec3 lastVelocity;
        private long lastSampleTick;
        private double muzzleSpeed = -1;
        private boolean lastInvalidMedium;
        private boolean ownerConfirmed;
        private boolean ownerRejected;

        private ProjectileTrack(Projectile projectile,
            WeaponFingerprint fingerprint, long loadTick, Vec3 lastPosition,
            Vec3 lastVelocity, boolean lastInvalidMedium) {
            this.projectile = projectile;
            this.fingerprint = fingerprint;
            this.loadTick = loadTick;
            this.lastPosition = lastPosition;
            this.lastVelocity = lastVelocity;
            this.lastInvalidMedium = lastInvalidMedium;
            lastSampleTick = loadTick;
        }
    }

    private record ShotBatchKey(long loadTick, WeaponFingerprint fingerprint) {}

    private static final class ShotBatch {

        private final List<TrackEstimate> estimates = new ArrayList<>();
    }

    private record TrackEstimate(double muzzleSpeed, double drag,
        double gravity) {}

    public record BallisticParameters(double muzzleSpeed, double drag,
        double gravity, ParameterSource source, WeaponFingerprint fingerprint,
        int acceptedShots, int trajectorySamples, double speedMad,
        double dragMad, double gravityMad,
        BallisticProfile.Confidence confidence, boolean fullModel,
        double calibratedMuzzleSpeed, double calibratedDrag,
        double calibratedGravity) {

        public double speedBlocksPerSecond() {
            return muzzleSpeed * 20;
        }

        public String describe() {
            String weapon =
                fingerprint == null ? "none" : fingerprint.shortDescription();
            return "source=" + source + ",speedBps="
                + String.format(java.util.Locale.ROOT, "%.3f",
                    speedBlocksPerSecond())
                + ",drag=" + String.format(java.util.Locale.ROOT, "%.6f", drag)
                + ",gravity="
                + String.format(java.util.Locale.ROOT, "%.6f", gravity)
                + ",weapon=" + weapon + ",shots=" + acceptedShots
                + ",trajectorySamples=" + trajectorySamples + ",speedMad="
                + String.format(java.util.Locale.ROOT, "%.6f", speedMad)
                + ",dragMad="
                + String.format(java.util.Locale.ROOT, "%.6f", dragMad)
                + ",gravityMad="
                + String.format(java.util.Locale.ROOT, "%.6f", gravityMad)
                + ",confidence=" + confidence + ",fullModel=" + fullModel
                + ",calibratedSpeedBps="
                + String.format(java.util.Locale.ROOT, "%.3f",
                    calibratedMuzzleSpeed * 20)
                + ",calibratedDrag="
                + String.format(java.util.Locale.ROOT, "%.6f", calibratedDrag)
                + ",calibratedGravity=" + String.format(java.util.Locale.ROOT,
                    "%.6f", calibratedGravity);
        }
    }

    public enum ParameterSource {
        LOCAL_DATA,
        AUTOMATIC,
        AUTOMATIC_SPEED_ONLY,
        MANUAL,
        MANUAL_FALLBACK,
        OFF
    }
}
