package com.zergatul.cheatutils.controllers;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Answers "is this entity shootable from where I am standing" for Entity Esp color coding.
 * <p>
 * A target counts as covered when blocks block the line from the player eyes to <b>both</b> sampled
 * points of its hitbox. Checking more than the eye alone matters because a target that only peeked
 * the head over cover is still shootable there, while one fully behind it is not - both cases look
 * the same from the eye point alone. Two points keep the raycast count at two per entity per frame.
 * <p>
 * Results are cached per rendered frame: {@code EntityEsp} asks the same entity several times while
 * drawing its box, tracer, outline and overlay, and a frame is a stable position, so one answer is
 * shared by all of them.
 */
public class CoverDetector {

    public static final CoverDetector instance = new CoverDetector();

    private static final float SAMPLE_STEP = 0.35f;

    private final Minecraft mc = Minecraft.getInstance();

    private Map<Entity, Boolean> cache;

    private CoverDetector() {}

    /**
     * Must be called once per rendered frame, before any {@link #isCovered} call of that frame.
     */
    public void nextFrame() {
        if (cache == null) {
            // entities are removed by the level every frame, so identity-ish entries clear out by
            // themselves; the map is dropped instead so nothing is held across world changes
            cache = new WeakHashMap<>();
        } else {
            cache.clear();
        }
    }

    public boolean isCovered(Entity entity) {
        return cache != null && cache.computeIfAbsent(entity, this::compute);
    }

    private boolean compute(Entity entity) {
        LocalPlayer player = mc.player;
        if (player == null || entity == player) {
            return false;
        }

        Vec3 eye = player.getEyePosition();
        AABB box = entity.getBoundingBox();
        if (eye.distanceToSqr(box.getCenter()) > 4096) {
            return false;
        }

        return !isPointVisible(eye, box, entity.getEyePosition())
                && !isPointVisible(eye, box, box.getCenter());
    }

    private boolean isPointVisible(Vec3 from, AABB box, Vec3 point) {
        if (!box.contains(point)) {
            return false;
        }
        ClipContext context = new ClipContext(
                from, point, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player);
        if (mc.level.clip(context).getType() != HitResult.Type.MISS) {
            return false;
        }
        // projectile sized sample: several small rays make an interrupted cover count as cover
        for (float dx = -SAMPLE_STEP; dx <= SAMPLE_STEP; dx += SAMPLE_STEP) {
            for (float dy = -SAMPLE_STEP; dy <= SAMPLE_STEP; dy += SAMPLE_STEP) {
                Vec3 sample = point.add(dx, dy, 0);
                if (!box.contains(sample)) {
                    continue;
                }
                ClipContext sub = new ClipContext(
                        from, sample, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player);
                if (mc.level.clip(sub).getType() == HitResult.Type.MISS) {
                    return true;
                }
            }
        }
        return false;
    }
}
