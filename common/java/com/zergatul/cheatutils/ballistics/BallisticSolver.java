/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package com.zergatul.cheatutils.ballistics;

import java.util.Optional;
import java.util.function.DoubleUnaryOperator;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

public enum BallisticSolver {
    ;

    private static final double DRAG_EPSILON = 1e-8;
    private static final double EQUATION_EPSILON = 1e-9;
    private static final double MIN_FLIGHT_TICKS = 0.05;
    private static final double SCAN_STEP_TICKS = 0.5;
    private static final double ROOT_TOLERANCE = 1e-6;
    private static final int BISECTION_ITERATIONS = 64;

    public static Optional<Solution> solve(Input input) {
        return solve(input, Arc.LOW);
    }

    public static Optional<Solution> solve(Input input, Arc arc) {
        String invalidReason = validate(input);
        if (invalidReason != null) {
            return Optional.empty();
        }

        Vec3 launchPosition = input.muzzleNow()
            .add(input.shooterVelocity().scale(input.latencyTicks()));
        Vec3 targetAtLaunch = input.targetNow()
            .add(input.targetVelocity().scale(input.latencyTicks()));
        Vec3 relativeTarget = targetAtLaunch.subtract(launchPosition);

        double flightTicks;
        if (Math.abs(1 - input.drag()) < DRAG_EPSILON
            && Math.abs(input.gravity()) < EQUATION_EPSILON) {
            Optional<Double> noDragTime = solveNoDragTime(relativeTarget,
                input.targetVelocity(), input.shooterVelocity(),
                input.velocityInheritance(), input.muzzleSpeed(), arc);
            if (noDragTime.isEmpty()
                || noDragTime.get() > input.maxFlightTicks()) {
                return Optional.empty();
            }
            flightTicks = noDragTime.get();
        } else {
            DoubleUnaryOperator residual =
                time -> residual(time, relativeTarget, input.targetVelocity(),
                    input.shooterVelocity(), input.velocityInheritance(),
                    input.muzzleSpeed(), input.drag(), input.gravity());
            Optional<Double> root = findPositiveRoot(residual, MIN_FLIGHT_TICKS,
                input.maxFlightTicks(), arc);
            if (root.isEmpty()) {
                return Optional.empty();
            }
            flightTicks = root.get();
        }

        double dragSum = dragSum(input.drag(), flightTicks);
        double gravityDrop =
            gravityDrop(input.drag(), input.gravity(), flightTicks);
        Vec3 targetFuture =
            targetAtLaunch.add(input.targetVelocity().scale(flightTicks));
        Vec3 leadMotion =
            input.targetVelocity().scale(input.latencyTicks() + flightTicks);
        Vec3 leadGravity = new Vec3(0, gravityDrop, 0);
        Vec3 leadShooter = input.shooterVelocity()
            .scale(-dragSum * input.velocityInheritance());
        Vec3 aimPoint = targetFuture.add(leadGravity).add(leadShooter);
        Vec3 directionVector = aimPoint.subtract(launchPosition);
        if (directionVector.lengthSqr() < EQUATION_EPSILON) {
            return Optional.empty();
        }
        Vec3 direction = directionVector.normalize();

        double verificationMiss = simulateClosestMiss(launchPosition,
            direction.scale(input.muzzleSpeed())
                .add(input.shooterVelocity()
                    .scale(input.velocityInheritance())),
            targetAtLaunch, input.targetVelocity(), input.drag(),
            input.gravity(), flightTicks);
        if (!Double.isFinite(verificationMiss)
            || verificationMiss > input.allowedMissRadius()) {
            return Optional.empty();
        }

        Vec3 leadTotal = aimPoint.subtract(input.targetNow());
        Angles angles = minecraftAngles(direction);
        return Optional.of(new Solution(flightTicks, targetFuture, aimPoint,
            leadMotion, leadGravity, leadShooter, leadTotal, direction,
            angles.yaw(), angles.pitch(), verificationMiss));
    }

    private static String validate(Input input) {
        if (input == null || !isFinite(input.muzzleNow())
            || !isFinite(input.targetNow()) || !isFinite(input.targetVelocity())
            || !isFinite(input.shooterVelocity())) {
            return "invalid_vector";
        }
        if (!Double.isFinite(input.muzzleSpeed()) || input.muzzleSpeed() <= 0) {
            return "invalid_muzzle_speed";
        }
        if (!Double.isFinite(input.drag()) || input.drag() <= 0) {
            return "invalid_drag";
        }
        if (!Double.isFinite(input.gravity()) || input.gravity() < 0) {
            return "invalid_gravity";
        }
        if (!Double.isFinite(input.velocityInheritance())
            || input.velocityInheritance() < 0) {
            return "invalid_inheritance";
        }
        if (!Double.isFinite(input.latencyTicks()) || input.latencyTicks() < 0) {
            return "invalid_latency";
        }
        if (!Double.isFinite(input.maxFlightTicks())
            || input.maxFlightTicks() < MIN_FLIGHT_TICKS) {
            return "invalid_max_flight_time";
        }
        if (!Double.isFinite(input.allowedMissRadius())
            || input.allowedMissRadius() <= 0) {
            return "invalid_miss_radius";
        }
        return null;
    }

    private static boolean isFinite(Vec3 vector) {
        return Double.isFinite(vector.x) && Double.isFinite(vector.y)
            && Double.isFinite(vector.z);
    }

    public static double dragSum(double drag, double time) {
        if (Math.abs(1 - drag) < DRAG_EPSILON) {
            return time;
        }
        return -Math.expm1(time * Math.log(drag)) / (1 - drag);
    }

    public static double gravityDrop(double drag, double gravity, double time) {
        if (Math.abs(1 - drag) < DRAG_EPSILON) {
            return gravity * time * (time - 1) * 0.5;
        }
        double sum = dragSum(drag, time);
        return gravity / (1 - drag) * (time - sum);
    }

    public static Vec3 projectilePosition(Vec3 initialPosition,
        Vec3 initialVelocity, double drag, double gravity, double time) {
        return initialPosition
            .add(initialVelocity.scale(dragSum(drag, time)))
            .add(0, -gravityDrop(drag, gravity, time), 0);
    }

    static double residual(double time, Vec3 relativeTarget,
        Vec3 targetVelocity, Vec3 shooterVelocity, double velocityInheritance,
        double muzzleSpeed, double drag, double gravity) {
        double sum = dragSum(drag, time);
        double drop = gravityDrop(drag, gravity, time);
        Vec3 required =
            relativeTarget.add(targetVelocity.scale(time)).add(0, drop, 0)
                .subtract(shooterVelocity.scale(sum * velocityInheritance));
        return required.length() - muzzleSpeed * sum;
    }

    static Optional<Double> solveNoDragTime(Vec3 relativeTarget,
        Vec3 targetVelocity, Vec3 shooterVelocity, double velocityInheritance,
        double muzzleSpeed, Arc arc) {
        Vec3 relativeVelocity = targetVelocity
            .subtract(shooterVelocity.scale(velocityInheritance));
        double a = relativeVelocity.lengthSqr() - muzzleSpeed * muzzleSpeed;
        double b = 2 * relativeTarget.dot(relativeVelocity);
        double c = relativeTarget.lengthSqr();
        if (Math.abs(a) < EQUATION_EPSILON) {
            if (Math.abs(b) < EQUATION_EPSILON) {
                return Optional.empty();
            }
            double root = -c / b;
            return validRoot(root);
        }

        double discriminant = b * b - 4 * a * c;
        if (discriminant < 0 || !Double.isFinite(discriminant)) {
            return Optional.empty();
        }
        double squareRoot = Math.sqrt(discriminant);
        double first = (-b - squareRoot) / (2 * a);
        double second = (-b + squareRoot) / (2 * a);
        double smallest = Double.POSITIVE_INFINITY;
        double largest = Double.NEGATIVE_INFINITY;
        if (first > 0 && Double.isFinite(first)) {
            smallest = first;
            largest = first;
        }
        if (second > 0 && Double.isFinite(second)) {
            smallest = Math.min(smallest, second);
            largest = Math.max(largest, second);
        }
        return validRoot(arc == Arc.HIGH ? largest : smallest);
    }

    private static Optional<Double> validRoot(double root) {
        return root > 0 && Double.isFinite(root) ? Optional.of(root)
            : Optional.empty();
    }

    private static Optional<Double> findPositiveRoot(
        DoubleUnaryOperator residual, double minimum, double maximum, Arc arc) {
        double previousTime = minimum;
        double previousValue = residual.applyAsDouble(previousTime);
        double olderAbsolute = Double.POSITIVE_INFINITY;
        double bestMinimumTime = Double.NaN;
        double bestRoot = Double.NaN;
        double bestMinimumAbsolute = Double.POSITIVE_INFINITY;

        for (double time = minimum + SCAN_STEP_TICKS; time <= maximum
            + EQUATION_EPSILON; time += SCAN_STEP_TICKS) {
            double currentTime = Math.min(time, maximum);
            double currentValue = residual.applyAsDouble(currentTime);
            if (Double.isFinite(previousValue) && Double.isFinite(currentValue)) {
                if (Math.abs(previousValue) <= ROOT_TOLERANCE) {
                    if (arc == Arc.LOW) {
                        return Optional.of(previousTime);
                    }
                    bestMinimumTime = previousTime;
                }
                if (Math.signum(previousValue) != Math.signum(currentValue)) {
                    double root = bisect(residual, previousTime, currentTime,
                        previousValue);
                    if (arc == Arc.LOW) {
                        return Optional.of(root);
                    }
                    bestRoot = root;
                }

                double previousAbsolute = Math.abs(previousValue);
                if (previousAbsolute <= olderAbsolute
                    && previousAbsolute <= Math.abs(currentValue)
                    && previousAbsolute < bestMinimumAbsolute) {
                    bestMinimumAbsolute = previousAbsolute;
                    bestMinimumTime = previousTime;
                }
                olderAbsolute = previousAbsolute;
            }

            if (currentTime >= maximum) {
                break;
            }
            previousTime = currentTime;
            previousValue = currentValue;
        }
        if (arc == Arc.HIGH && Double.isFinite(bestRoot)) {
            return Optional.of(bestRoot);
        }

        if (Double.isFinite(bestMinimumTime)) {
            double lower = Math.max(minimum, bestMinimumTime - SCAN_STEP_TICKS);
            double upper = Math.min(maximum, bestMinimumTime + SCAN_STEP_TICKS);
            double minimumTime = minimizeAbsolute(residual, lower, upper);
            if (Math.abs(residual.applyAsDouble(minimumTime)) <= ROOT_TOLERANCE) {
                return Optional.of(minimumTime);
            }
        }
        return Optional.empty();
    }

    public enum Arc {
        LOW,
        HIGH
    }

    private static double bisect(DoubleUnaryOperator residual, double lower,
        double upper, double lowerValue) {
        for (int i = 0; i < BISECTION_ITERATIONS; i++) {
            double middle = (lower + upper) * 0.5;
            double middleValue = residual.applyAsDouble(middle);
            if (Math.abs(middleValue) <= ROOT_TOLERANCE) {
                return middle;
            }
            if (Math.signum(lowerValue) == Math.signum(middleValue)) {
                lower = middle;
                lowerValue = middleValue;
            } else {
                upper = middle;
            }
        }
        return (lower + upper) * 0.5;
    }

    private static double minimizeAbsolute(DoubleUnaryOperator function,
        double lower, double upper) {
        for (int i = 0; i < BISECTION_ITERATIONS; i++) {
            double first = lower + (upper - lower) / 3;
            double second = upper - (upper - lower) / 3;
            if (Math.abs(function.applyAsDouble(first)) <= Math
                .abs(function.applyAsDouble(second))) {
                upper = second;
            } else {
                lower = first;
            }
        }
        return (lower + upper) * 0.5;
    }

    private static double simulateClosestMiss(Vec3 bulletPosition,
        Vec3 bulletVelocity, Vec3 targetPosition, Vec3 targetVelocity,
        double drag, double gravity, double flightTicks) {
        double closest = bulletPosition.distanceTo(targetPosition);
        int simulationTicks = Math.max(1, (int)Math.ceil(flightTicks) + 1);
        for (int tick = 0; tick < simulationTicks; tick++) {
            Vec3 relativePosition = bulletPosition.subtract(targetPosition);
            Vec3 relativeVelocity = bulletVelocity.subtract(targetVelocity);
            double denominator = relativeVelocity.lengthSqr();
            double fraction = denominator < EQUATION_EPSILON ? 0
                : Mth.clamp(-relativePosition.dot(relativeVelocity)
                    / denominator, 0, 1);
            closest = Math.min(closest, relativePosition
                .add(relativeVelocity.scale(fraction)).length());

            bulletPosition = bulletPosition.add(bulletVelocity);
            targetPosition = targetPosition.add(targetVelocity);
            bulletVelocity = bulletVelocity.scale(drag).add(0, -gravity, 0);
        }
        return closest;
    }

    private static Angles minecraftAngles(Vec3 direction) {
        double horizontal = Math.hypot(direction.x, direction.z);
        double yaw = Math.toDegrees(Math.atan2(-direction.x, direction.z));
        double pitch = -Math.toDegrees(Math.atan2(direction.y, horizontal));
        return new Angles(yaw, pitch);
    }

    public record Input(Vec3 muzzleNow, Vec3 targetNow, Vec3 targetVelocity,
        Vec3 shooterVelocity, double latencyTicks, double velocityInheritance,
        double muzzleSpeed, double drag, double gravity, double maxFlightTicks,
        double allowedMissRadius) {}

    public record Solution(double flightTicks, Vec3 targetFuture,
        Vec3 aimPoint, Vec3 leadMotion, Vec3 leadGravity, Vec3 leadShooter,
        Vec3 leadTotal, Vec3 direction, double yaw, double pitch,
        double verificationMiss) {}

    private record Angles(double yaw, double pitch) {}
}
