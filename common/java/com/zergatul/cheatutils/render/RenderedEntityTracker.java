package com.zergatul.cheatutils.render;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;

/**
 * Tracks which entities the game actually drew in the current frame.
 *
 * <p>{@code LevelRenderer.renderEntity} is only reached for entities that survive every cull
 * pass vanilla applies - the entity render distance in {@link net.minecraft.world.entity.Entity},
 * the view frustum, chunk state and any occlusion culling a rendering mod adds on top. Entities
 * outside all of that have their position, metadata and sounds delivered by the protocol all the
 * same, so they can be audible and aimable while nothing draws them. Comparing a shoot sound
 * against this set is what separates "shot fired by someone I can't see" from an ordinary shot.
 *
 * <p>All calls happen on the client render thread: {@link #beginFrame()} from the start of
 * {@code renderLevel}, {@link #markRendered(int)} from {@code renderEntity}.
 */
public final class RenderedEntityTracker {

    private static final IntSet rendered = new IntOpenHashSet();

    private RenderedEntityTracker() {
    }

    public static void beginFrame() {
        rendered.clear();
    }

    public static void markRendered(int entityId) {
        rendered.add(entityId);
    }

    public static boolean isRendered(int entityId) {
        return rendered.contains(entityId);
    }

    public static void clear() {
        rendered.clear();
    }
}
