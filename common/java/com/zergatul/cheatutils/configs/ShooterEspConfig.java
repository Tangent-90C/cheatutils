package com.zergatul.cheatutils.configs;

import com.zergatul.cheatutils.utils.MathUtils;

import java.awt.*;

public class ShooterEspConfig extends ModuleConfig {

    /**
     * Vanilla stops drawing an entity once the squared distance to the camera exceeds
     * {@code boundingBox.getSize() * 64 * entityDistanceScaling}, which is roughly 121 blocks
     * for a player. Anything beyond that is delivered by the protocol but never drawn.
     */
    public static final int VanillaEntityRenderLimit = 128;

    // Shooters the game did not draw - the cases this module exists for.

    public boolean drawBoundingBox;
    public double boundingBoxWidth = 2;
    public Color boundingBoxColor;

    public boolean drawTracers;
    public double tracerWidth = 2;
    public Color tracerColor;

    // Shooters the game did draw. Only used when onlyNotRendered is off, so the two states can
    // be told apart at a glance instead of both using the same box color.

    public double visibleBoundingBoxWidth = 2;
    public Color visibleBoundingBoxColor;

    public double visibleTracerWidth = 2;
    public Color visibleTracerColor;

    public boolean drawLabels;
    public Color labelColor;

    /** Skip shooters the game drew this frame, and box only the invisible ones. */
    public boolean onlyNotRendered = true;

    /** How long a shot stays boxed after it was heard. */
    public int displayMillis = 4000;

    public double maxDistance;

    public void copyFrom(ShooterEspConfig jsonConfig) {
        enabled = jsonConfig.enabled;

        drawBoundingBox = jsonConfig.drawBoundingBox;
        boundingBoxWidth = jsonConfig.boundingBoxWidth;
        boundingBoxColor = jsonConfig.boundingBoxColor;

        drawTracers = jsonConfig.drawTracers;
        tracerWidth = jsonConfig.tracerWidth;
        tracerColor = jsonConfig.tracerColor;

        visibleBoundingBoxWidth = jsonConfig.visibleBoundingBoxWidth;
        visibleBoundingBoxColor = jsonConfig.visibleBoundingBoxColor;

        visibleTracerWidth = jsonConfig.visibleTracerWidth;
        visibleTracerColor = jsonConfig.visibleTracerColor;

        drawLabels = jsonConfig.drawLabels;
        labelColor = jsonConfig.labelColor;

        onlyNotRendered = jsonConfig.onlyNotRendered;

        displayMillis = jsonConfig.displayMillis;

        maxDistance = jsonConfig.maxDistance;

        sanitize();
    }

    public void sanitize() {
        boundingBoxWidth = sanitizeWidth(boundingBoxWidth);
        tracerWidth = sanitizeWidth(tracerWidth);
        visibleBoundingBoxWidth = sanitizeWidth(visibleBoundingBoxWidth);
        visibleTracerWidth = sanitizeWidth(visibleTracerWidth);
        maxDistance = sanitizeDistance(maxDistance, 512);
        displayMillis = MathUtils.clamp(displayMillis, 100, 60000);

        if (boundingBoxColor == null) {
            boundingBoxColor = Color.ORANGE;
        }
        if (tracerColor == null) {
            tracerColor = Color.RED;
        }
        if (visibleBoundingBoxColor == null) {
            visibleBoundingBoxColor = Color.YELLOW;
        }
        if (visibleTracerColor == null) {
            visibleTracerColor = Color.YELLOW;
        }
        if (labelColor == null) {
            labelColor = Color.WHITE;
        }
    }

    private static double sanitizeWidth(double value) {
        return Double.isFinite(value) ? MathUtils.clamp(value, 0.5, 100) : 1;
    }

    private static double sanitizeDistance(double value, double fallback) {
        return Double.isFinite(value) && value > 0 ? value : fallback;
    }
}
