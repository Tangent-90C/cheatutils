package com.zergatul.cheatutils.modules.esp;

import com.zergatul.cheatutils.common.Events;
import com.zergatul.cheatutils.common.TaczCompat;
import com.zergatul.cheatutils.common.events.RenderWorldLastEvent;
import com.zergatul.cheatutils.configs.ConfigStore;
import com.zergatul.cheatutils.configs.ShooterEspConfig;
import com.zergatul.cheatutils.font.GlyphFontRenderer;
import com.zergatul.cheatutils.font.StylizedText;
import com.zergatul.cheatutils.font.TextBounds;
import com.zergatul.cheatutils.mixins.common.accessors.AbstractSoundInstanceAccessor;
import com.zergatul.cheatutils.mixins.common.accessors.EntityBoundSoundInstanceAccessor;
import com.zergatul.cheatutils.modules.Module;
import com.zergatul.cheatutils.modules.utilities.RenderUtilities;
import com.zergatul.cheatutils.render.InstancedCuboidLineRenderer;
import com.zergatul.cheatutils.render.InstancedTracerRenderer;
import com.zergatul.cheatutils.render.RenderedEntityTracker;
import com.zergatul.cheatutils.render.TextureStateTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector4f;

import java.awt.Color;
import java.awt.Font;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Boxes players that fired a gun while the game itself was not drawing them.
 *
 * <p>Sounds and rendering are two independent gates on the client. A gunshot is played whenever
 * the shooter's entity id resolves in the client level - which only needs the add-entity packet
 * the server sent for being inside its tracking range, a purely horizontal test that ignores
 * chunk load state and terrain. Rendering additionally requires the entity to be inside the
 * entity render distance, inside the view frustum and not culled by an occlusion pass. So a
 * shooter can be fully audible at 150 blocks, through a hill, while nothing draws them.
 *
 * <p>The shot is caught in {@code SoundManager.play}, where TaCZ has already bound the sound to
 * the shooter, so the box is placed at the shooter's position instead of triangulating from pan.
 * The box is drawn for a few seconds after the shot and only while the entity is missing from
 * the per-frame rendered-entity set.
 */
public class ShooterEsp implements Module {

    public static final ShooterEsp instance = new ShooterEsp();

    /**
     * Volume TaCZ sends with a third-person shot. The client scales it down linearly to zero at
     * the shooter-gun distance the server configured, so the pair (volume, distance at arrival)
     * recovers that distance - see {@link #inferSoundRange}.
     */
    private static final float BaseShotVolume = 0.8f;

    private final Minecraft mc = Minecraft.getInstance();
    private final Map<Integer, ShotRecord> shots = new ConcurrentHashMap<>();

    private GlyphFontRenderer fontRenderer;
    private int fontSize = 16;

    private ShooterEsp() {
        Events.RenderWorldLast.add(this::render);
        Events.WorldUnload.add(this::clear);
        Events.DimensionChange.add(this::clear);
    }

    /**
     * Called on the client thread for every sound the game starts playing.
     *
     * @param sound sound instance, any sound; filtered to remote TaCZ gunshots inside
     */
    public void onSound(SoundInstance sound) {
        ShooterEspConfig config = ConfigStore.instance.getConfig().shooterEspConfig;
        if (!config.enabled || !TaczCompat.isRemoteGunShotSound(sound)) {
            return;
        }

        LivingEntity shooter;
        try {
            Entity entity = ((EntityBoundSoundInstanceAccessor) sound).getEntity_CU();
            if (!(entity instanceof LivingEntity living)) {
                return;
            }
            shooter = living;
        } catch (RuntimeException e) {
            return;
        }

        if (shooter == mc.player || shooter.isRemoved()) {
            return;
        }

        shots.put(shooter.getId(), new ShotRecord(
                shooter,
                ((AbstractSoundInstanceAccessor) sound).getVolumeRaw_CU(),
                System.currentTimeMillis(),
                TaczCompat.getGunSoundId(sound)));
    }

    private void render(RenderWorldLastEvent event) {
        ShooterEspConfig config = ConfigStore.instance.getConfig().shooterEspConfig;
        if (!config.enabled) {
            if (!shots.isEmpty()) {
                shots.clear();
            }
            return;
        }

        if (mc.player == null || mc.level == null || shots.isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();
        Iterator<Map.Entry<Integer, ShotRecord>> iterator = shots.entrySet().iterator();
        while (iterator.hasNext()) {
            ShotRecord record = iterator.next().getValue();
            if (now - record.millis > config.displayMillis) {
                iterator.remove();
            }
        }

        float partialTicks = event.getTickDelta();
        Vec3 cameraPos = event.getCamera().getPosition();
        Vec3 playerPos = event.getPlayerPos();

        RenderUtilities utilities = RenderUtilities.instance;
        InstancedCuboidLineRenderer cuboidRenderer = utilities.getInstancedCuboidLineRenderer();
        InstancedTracerRenderer tracerRenderer = utilities.getInstancedTracerRenderer();
        cuboidRenderer.begin();
        tracerRenderer.begin();

        try {
            for (Iterator<Map.Entry<Integer, ShotRecord>> it = shots.entrySet().iterator(); it.hasNext(); ) {
                ShotRecord record = it.next().getValue();
                Entity shooter = mc.level.getEntity(record.entityId);
                if (shooter == null || shooter.isRemoved()) {
                    it.remove();
                    continue;
                }

                double dx = shooter.getX() - playerPos.x;
                double dy = shooter.getY() - playerPos.y;
                double dz = shooter.getZ() - playerPos.z;
                double distanceSqr = dx * dx + dy * dy + dz * dz;
                if (distanceSqr > config.maxDistance * config.maxDistance) {
                    continue;
                }

                boolean rendered = RenderedEntityTracker.isRendered(shooter.getId());
                if (config.onlyNotRendered && rendered) {
                    continue;
                }

                double distance = Math.sqrt(distanceSqr);
                Color boxColor = rendered ? config.visibleBoundingBoxColor : config.boundingBoxColor;
                Color tracerColor = rendered ? config.visibleTracerColor : config.tracerColor;
                double boxWidth = rendered ? config.visibleBoundingBoxWidth : config.boundingBoxWidth;
                double lineWidth = rendered ? config.visibleTracerWidth : config.tracerWidth;

                Vec3 pos = shooter.getPosition(partialTicks);
                AABB box = shooter.getDimensions(shooter.getPose()).makeBoundingBox(pos);

                if (config.drawBoundingBox) {
                    cuboidRenderer.cuboid(
                            (float) (box.minX - cameraPos.x),
                            (float) (box.minY - cameraPos.y),
                            (float) (box.minZ - cameraPos.z),
                            (float) (box.maxX - cameraPos.x),
                            (float) (box.maxY - cameraPos.y),
                            (float) (box.maxZ - cameraPos.z),
                            boxColor,
                            (float) boxWidth);
                }
                if (config.drawTracers) {
                    tracerRenderer.tracer(
                            (float) (pos.x - cameraPos.x),
                            (float) (pos.y - cameraPos.y),
                            (float) (pos.z - cameraPos.z),
                            tracerColor,
                            (float) lineWidth);
                }
                if (config.drawLabels) {
                    drawLabel(event, record, pos, distance);
                }
            }
        } finally {
            tracerRenderer.end(event);
            cuboidRenderer.end(event);
        }
    }

    private void drawLabel(
            RenderWorldLastEvent event,
            ShotRecord record,
            Vec3 pos,
            double distance
    ) {
        try {
            ensureFont();
            if (fontRenderer == null) {
                return;
            }

            String range = inferSoundRange(record.volume, distance);
            String text = String.format("%s  %.0fm  %s%s",
                    record.name,
                    distance,
                    record.soundId != null ? record.soundId.getPath() : "?",
                    range);

            double invScale = 1.0 / 64.0;
            TextBounds bounds = fontRenderer.getTextSize(text);
            double width = bounds.width() * invScale;

            var pose = event.getMatrixStack();
            pose.pushPose();
            pose.translate(
                    (float) (pos.x - event.getCamera().getPosition().x),
                    (float) (pos.y - event.getCamera().getPosition().y) + 0.35f,
                    (float) (pos.z - event.getCamera().getPosition().z));
            fontRenderer.drawText(
                    pose,
                    StylizedText.of(text, ConfigStore.instance.getConfig().shooterEspConfig.labelColor.getRGB()),
                    (float) (-width / 2),
                    0.0f,
                    invScale);
            pose.popPose();
        } catch (RuntimeException e) {
            // never let a label problem break the world render
        }
    }

    /**
     * Recovers the shooter-side sound range from the played volume. TaCZ computes the volume of a
     * third-person shot as {@code 0.8 * (1 - min(1, distance / range))} once, at the moment the
     * packet is handled, and the entity position the client uses is the same one used there, so
     * the range can be solved for exactly. Reported as an empty string when it cannot be
     * determined, which happens when the shot was fired from the listener's own position.
     */
    private String inferSoundRange(float volume, double distance) {
        double remaining = 1.0 - volume / BaseShotVolume;
        if (volume <= 0) {
            return "  range=0";
        }
        if (volume >= BaseShotVolume) {
            return "";
        }
        double range = distance / remaining;
        return String.format("  range=%.0f", range);
    }

    private void ensureFont() {
        int size = ConfigStore.instance.getConfig().entityTitleConfig.fontSize;
        if (fontRenderer == null || size != fontSize) {
            if (fontRenderer != null) {
                fontRenderer.dispose();
                fontRenderer = null;
            }
            fontSize = size;
            fontRenderer = new GlyphFontRenderer(new Font("Monospaced", Font.PLAIN, size), true);
        }
    }

    private void clear() {
        shots.clear();
        if (fontRenderer != null) {
            fontRenderer.dispose();
            fontRenderer = null;
        }
    }

    private static class ShotRecord {
        public final int entityId;
        public final String name;
        public final ResourceLocation soundId;
        public final float volume;
        public final long millis;

        public ShotRecord(LivingEntity shooter, float volume, long millis, ResourceLocation soundId) {
            this.entityId = shooter.getId();
            this.name = shooter.getName().getString();
            this.volume = volume;
            this.millis = millis;
            this.soundId = soundId;
        }
    }
}
