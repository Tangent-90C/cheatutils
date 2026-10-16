package com.zergatul.cheatutils.configs;

import java.awt.Color;

/**
 * Per-sound entry: whether its marker is drawn, how it is drawn, and in which color.
 * <p>
 * A null render setting means the entry was saved before that setting existed, which is
 * how the config migration tells a legacy row from one whose values the user chose.
 */
public class SoundEntry {

    /** Box - cuboid the height of a player. Ring - circle on the ground. */
    public static final int SHAPE_BOX = 0;
    public static final int SHAPE_RING = 1;

    public String id;
    public boolean enabled;
    /** null - use the color derived from the sound. */
    public Color color;
    /** {@link #SHAPE_BOX} or {@link #SHAPE_RING}. null - not saved yet, treated as {@link #SHAPE_BOX}. */
    public Integer shape;
    /** Blocks to move the marker down. null - not saved yet, treated as 0. */
    public Double offsetY;

    /** Draw the shape chosen by {@link #shape}. */
    public boolean drawMarker = true;
    /** Draw the tracer line from the camera to the marker. */
    public boolean drawTracer = true;
    /** Draw without depth test, so the marker stays visible behind walls and terrain. */
    public boolean throughWalls = true;
    /** Hide the marker while its position can be seen - for sounds that matter blocked only. */
    public boolean onlyNotVisible = false;

    public SoundEntry() {
    }

    public SoundEntry(String id, boolean enabled, Color color, int shape, double offsetY) {
        this.id = id;
        this.enabled = enabled;
        this.color = color;
        this.shape = shape;
        this.offsetY = offsetY;
    }

    public SoundEntry(SoundEntry other) {
        this.id = other.id;
        this.enabled = other.enabled;
        this.color = other.color;
        this.shape = other.shape;
        this.offsetY = other.offsetY;
        this.drawMarker = other.drawMarker;
        this.drawTracer = other.drawTracer;
        this.throughWalls = other.throughWalls;
        this.onlyNotVisible = other.onlyNotVisible;
    }

    public int shapeOrDefault() {
        return shape == null ? SHAPE_BOX : shape;
    }

    public double offsetYOrDefault() {
        return offsetY == null ? 0.0 : offsetY;
    }
}
