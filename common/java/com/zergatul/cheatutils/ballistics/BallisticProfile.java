/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package com.zergatul.cheatutils.ballistics;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public final class BallisticProfile {

    public static final int MAX_ACCEPTED_SHOTS = 20;
    public static final int MAX_TRAJECTORY_SAMPLES = 100;
    public static final int MIN_USABLE_SAMPLES = 3;

    private final WeaponFingerprint fingerprint;
    private final RobustWindow speeds =
        new RobustWindow(MAX_ACCEPTED_SHOTS, 0.25, 0.02, false);
    private final RobustWindow drags =
        new RobustWindow(MAX_TRAJECTORY_SAMPLES, 0.002, 0.005, false);
    private final RobustWindow gravities =
        new RobustWindow(MAX_TRAJECTORY_SAMPLES, 0.005, 0.02, true);
    private int rejectedShots;
    private long lastUpdatedEpochMillis;

    public BallisticProfile(WeaponFingerprint fingerprint) {
        this.fingerprint = fingerprint;
    }

    BallisticProfile(WeaponFingerprint fingerprint,
        Collection<Double> acceptedSpeeds, Collection<Double> acceptedDrags,
        Collection<Double> acceptedGravities, int rejectedShots,
        long lastUpdatedEpochMillis) {
        this(fingerprint);
        speeds.restore(acceptedSpeeds);
        drags.restore(acceptedDrags);
        gravities.restore(acceptedGravities);
        this.rejectedShots = Math.max(0, rejectedShots);
        this.lastUpdatedEpochMillis = Math.max(0, lastUpdatedEpochMillis);
    }

    public UpdateResult addShot(double muzzleSpeed, double drag, double gravity) {
        UpdateResult result = speeds.add(muzzleSpeed);
        if (result == UpdateResult.REJECTED_INVALID
            || result == UpdateResult.REJECTED_OUTLIER) {
            rejectedShots++;
        }

        drags.add(drag);
        gravities.add(gravity);
        if (result == UpdateResult.ACCEPTED || result == UpdateResult.REPLACED) {
            lastUpdatedEpochMillis = System.currentTimeMillis();
        }
        return result;
    }

    public boolean isSpeedUsable() {
        return speeds.size() >= MIN_USABLE_SAMPLES;
    }

    public boolean hasTrajectoryModel() {
        return isSpeedUsable() && drags.size() >= MIN_USABLE_SAMPLES
            && gravities.size() >= MIN_USABLE_SAMPLES;
    }

    public double getMuzzleSpeed() {
        return speeds.median();
    }

    public double getDrag() {
        return drags.median();
    }

    public double getGravity() {
        return gravities.median();
    }

    public double getSpeedMad() {
        return speeds.mad();
    }

    public double getDragMad() {
        return drags.mad();
    }

    public double getGravityMad() {
        return gravities.mad();
    }

    public Confidence getConfidence() {
        if (!hasTrajectoryModel()) {
            return Confidence.WARMUP;
        }
        if (speeds.size() >= 10 && speeds.relativeMad() <= 0.02
            && drags.relativeMad() <= 0.002 && gravities.relativeMad() <= 0.02) {
            return Confidence.HIGH;
        }
        if (speeds.size() >= 5 && speeds.relativeMad() <= 0.05
            && drags.relativeMad() <= 0.005 && gravities.relativeMad() <= 0.05) {
            return Confidence.MEDIUM;
        }
        return Confidence.LOW;
    }

    public WeaponFingerprint getFingerprint() {
        return fingerprint;
    }

    public List<Double> getAcceptedSpeeds() {
        return speeds.values();
    }

    public List<Double> getAcceptedDrags() {
        return drags.values();
    }

    public List<Double> getAcceptedGravities() {
        return gravities.values();
    }

    public int getAcceptedShotCount() {
        return speeds.size();
    }

    public int getTrajectorySampleCount() {
        return Math.min(drags.size(), gravities.size());
    }

    public int getRejectedShotCount() {
        return rejectedShots;
    }

    public long getLastUpdatedEpochMillis() {
        return lastUpdatedEpochMillis;
    }

    static double median(Collection<Double> values) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int middle = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return sorted.get(middle);
        }
        return (sorted.get(middle - 1) + sorted.get(middle)) / 2;
    }

    static double mad(Collection<Double> values) {
        if (values.isEmpty()) {
            return 0;
        }
        double median = median(values);
        List<Double> deviations =
            values.stream().map(value -> Math.abs(value - median)).toList();
        return median(deviations);
    }

    public enum UpdateResult {
        ACCEPTED,
        REPLACED,
        REJECTED_INVALID,
        REJECTED_OUTLIER
    }

    public enum Confidence {
        WARMUP,
        LOW,
        MEDIUM,
        HIGH
    }

    private static final class RobustWindow {

        private static final int MIN_OUTLIER_SAMPLE_COUNT = 5;
        private static final int REPLACEMENT_SAMPLE_COUNT = 5;
        private final int maximumSize;
        private final double absoluteTolerance;
        private final double relativeTolerance;
        private final boolean allowZero;
        private final ArrayDeque<Double> accepted = new ArrayDeque<>();
        private final ArrayDeque<Double> replacements = new ArrayDeque<>();

        private RobustWindow(int maximumSize, double absoluteTolerance,
            double relativeTolerance, boolean allowZero) {
            this.maximumSize = maximumSize;
            this.absoluteTolerance = absoluteTolerance;
            this.relativeTolerance = relativeTolerance;
            this.allowZero = allowZero;
        }

        private UpdateResult add(double value) {
            if (!Double.isFinite(value) || value < 0 || value == 0 && !allowZero) {
                return UpdateResult.REJECTED_INVALID;
            }
            if (accepted.size() >= MIN_OUTLIER_SAMPLE_COUNT && isOutlier(value)) {
                addTo(replacements, value, REPLACEMENT_SAMPLE_COUNT);
                if (replacements.size() == REPLACEMENT_SAMPLE_COUNT
                    && relativeMad(replacements) <= 0.05) {
                    accepted.clear();
                    for (double replacement : replacements) {
                        addTo(accepted, replacement, maximumSize);
                    }
                    replacements.clear();
                    return UpdateResult.REPLACED;
                }
                return UpdateResult.REJECTED_OUTLIER;
            }

            replacements.clear();
            addTo(accepted, value, maximumSize);
            return UpdateResult.ACCEPTED;
        }

        private boolean isOutlier(double value) {
            double median = median();
            double threshold = Math.max(3 * 1.4826 * mad(),
                Math.max(absoluteTolerance, median * relativeTolerance));
            return Math.abs(value - median) > threshold;
        }

        private void restore(Collection<Double> values) {
            for (double value : values) {
                if (Double.isFinite(value)
                    && (value > 0 || value == 0 && allowZero)) {
                    addTo(accepted, value, maximumSize);
                }
            }
        }

        private static void addTo(ArrayDeque<Double> values, double value,
            int maximumSize) {
            values.addLast(value);
            while (values.size() > maximumSize) {
                values.removeFirst();
            }
        }

        private int size() {
            return accepted.size();
        }

        private double median() {
            return BallisticProfile.median(accepted);
        }

        private double mad() {
            return BallisticProfile.mad(accepted);
        }

        private double relativeMad() {
            return relativeMad(accepted);
        }

        private double relativeMad(Collection<Double> values) {
            double median = BallisticProfile.median(values);
            return median > 0 ? BallisticProfile.mad(values) / median
                : Double.POSITIVE_INFINITY;
        }

        private List<Double> values() {
            return List.copyOf(accepted);
        }
    }
}
