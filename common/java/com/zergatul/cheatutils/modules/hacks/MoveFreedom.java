package com.zergatul.cheatutils.modules.hacks;

import com.zergatul.cheatutils.configs.ConfigStore;
import com.zergatul.cheatutils.configs.MoveFreedomConfig;
import com.zergatul.cheatutils.modules.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.UUID;

/**
 * Cancels the TaCZ medical movement penalties - fracture and inventory overload.
 *
 * <p>Two different mechanisms, handled at two different places:
 *
 * <ul>
 * <li>The slowdown is an attribute modifier on {@code MOVEMENT_SPEED} applied by
 * {@code com.tacz.guns.effect.MedicalEffectHelper} under fixed UUIDs. The server applies it
 * on its side and never syncs it down, so on the client it never exists; the modifier removal
 * in {@link #onTravel} stays as a no-op safety net.</li>
 * <li>Fracture additionally blocks jumping client-side: a {@code MovementInputUpdateEvent}
 * handler zeroes {@code input.jumping} and clears {@code LivingEntity.jumping} every tick.
 * {@link #onMovementInput} runs at the lowest event priority, after TaCZ, and restores both
 * from the real key state.</li>
 * </ul>
 *
 * <p>Disabling the hack needs no restore logic - TaCZ re-applies its effects every tick.
 */
public class MoveFreedom implements Module {

    public static final MoveFreedom instance = new MoveFreedom();

    private static final Logger LOGGER = LogManager.getLogger(MoveFreedom.class);

    // com.tacz.guns.effect.MedicalEffectHelper static initializer.
    private static final String OVERBURDEN_SPEED_MODIFIER_ID = "a5820110-b73e-45af-9635-1e04c1b5b34d";
    private static final String FRACTURE_SPEED_MODIFIER_ID = "980a3d10-3e21-457d-b4b6-4e5d77acfb10";

    private static final long LOG_INTERVAL_MS = 1000;

    private final Minecraft mc = Minecraft.getInstance();

    private long lastLog;
    private double accumulatedDistance;
    private Vec3 lastPos;
    private long lastJumpTraceTick = -1;

    /** Set by {@code MixinMedicalEffectHelper} when it actually forces the TaCZ gate to false. */
    public static volatile boolean jumpGateMixinFired;
    /** Set when the ParCoolCompat action-cancel gate (FastRun etc. blocked while injured) gets forced false. */
    public static volatile boolean actionGateMixinFired;
    /** Set when the overweight jump-velocity penalty (halve, or zero when super) gets cancelled. */
    public static volatile boolean burdenGateMixinFired;
    /** Set when the ParCool-side compat gate (jump cancel / stamina multiplier) gets forced off. */
    public static volatile boolean parcoolGateMixinFired;

    private MoveFreedom() {
    }

    /**
     * Queried by {@code MixinMedicalEffectHelper} at the head of TaCZ's
     * {@code shouldBlockJump} - the single gate for the whole fracture jump suppression chain.
     */
    public boolean isFractureJumpUnblocked() {
        MoveFreedomConfig config = ConfigStore.instance.getConfig().moveFreedomConfig;
        return config.enabled && config.ignoreFracture;
    }

    /** Queried by {@code MixinMedicalEffectHelper} for the ParCool action-cancel gate. */
    public boolean isOverweightUnblocked() {
        MoveFreedomConfig config = ConfigStore.instance.getConfig().moveFreedomConfig;
        return config.enabled && config.ignoreOverweight;
    }

    /**
     * Called from {@code MixinLivingEntity} at the head of {@code LivingEntity.travel} for
     * every living entity; filters to the local player inside.
     */
    public void onTravel(LocalPlayer player) {
        MoveFreedomConfig config = ConfigStore.instance.getConfig().moveFreedomConfig;
        if (!config.enabled || player == null) {
            return;
        }

        AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null) {
            return;
        }

        double valueBefore = speed.getValue();
        boolean overweight = remove(speed, OVERBURDEN_SPEED_MODIFIER_ID, config.ignoreOverweight);
        boolean fracture = remove(speed, FRACTURE_SPEED_MODIFIER_ID, config.ignoreFracture);

        if (player == mc.player) {
            trackActualSpeed(player, valueBefore, overweight, fracture);
        }
    }

    /**
     * Called from the Forge event wrapper at the lowest priority for
     * {@code LivingTickEvent} - inside {@code LivingEntity.aiStep}, after every TaCZ handler
     * (normal priority) has had its chance to clear the jump flag, and before the vanilla
     * ground-jump check in the same method reads {@code LivingEntity.jumping}.
     *
     * <p>Also runs the jump/walk chain tracers: while the jump key is held, one line every 10
     * ticks records every link of the jump chain (key → input.jumping → entity.jumping →
     * noJumpDelay → onGround); while walking, one line every 40 ticks records the walk chain
     * (forced sneak, input impulses, actual velocity). These traces locate the exact link
     * where an external clamp intercepts movement.
     */
    public void onLivingTick(net.minecraft.world.entity.LivingEntity entity) {
        if (entity != mc.player || mc.player == null) {
            return;
        }
        LocalPlayer player = mc.player;

        // restore, when the hack demands it
        MoveFreedomConfig config = ConfigStore.instance.getConfig().moveFreedomConfig;
        if (config.enabled && config.ignoreFracture) {
            entity.setJumping(mc.options.keyJump.isDown());
        }

        traceJumpChain(player, entity);
        traceWalkChain(player);
    }

    private void traceJumpChain(LocalPlayer player, net.minecraft.world.entity.LivingEntity entity) {
        boolean keyDown = mc.options.keyJump.isDown();
        if (!keyDown || player.tickCount % 10 != 0) {
            lastJumpTraceTick = -1;
            return;
        }
        if (player.tickCount - lastJumpTraceTick < 10) {
            return;
        }
        lastJumpTraceTick = player.tickCount;
        boolean inputJumping = player.input != null && player.input.jumping;
        LOGGER.info("jumptrace: input.jumping={} entity.jumping={} noJumpDelay={} onGround={} sprinting={} usingItem={}",
                inputJumping,
                ((com.zergatul.cheatutils.mixins.common.accessors.LivingEntityAccessor) entity).getJumping_CU(),
                ((com.zergatul.cheatutils.mixins.common.accessors.LivingEntityAccessor) entity).getNoJumpDelay_CU(),
                player.onGround(),
                player.isSprinting(),
                player.isUsingItem());
    }

    private void traceWalkChain(LocalPlayer player) {
        if (player.input == null || player.tickCount % 40 != 0) {
            return;
        }
        Vec3 delta = player.getDeltaMovement();
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z) * 20;
        LOGGER.info("walktrace: shift={} fwdImpulse={} leftImpulse={} deltaH={} m/tick pose={}",
                player.input.shiftKeyDown,
                String.format("%.2f", player.input.forwardImpulse),
                String.format("%.2f", player.input.leftImpulse),
                String.format("%.2f", horizontal),
                player.getPose());
    }

    /**
     * Called from the Forge event wrapper at the lowest priority for
     * {@code MovementInputUpdateEvent} - after TaCZ's handler zeroed the jump input.
     */
    public void onMovementInput(Player player, Input input) {
        MoveFreedomConfig config = ConfigStore.instance.getConfig().moveFreedomConfig;
        if (!config.enabled || !config.ignoreFracture || player == null || input == null) {
            return;
        }
        if (player != mc.player) {
            return;
        }

        input.jumping = mc.options.keyJump.isDown();
    }

    /**
     * Called from {@code MixinLivingEntity} at the tail of {@code LivingEntity.jumpFromGround}.
     * If the vanilla jump executes, this line appears in the log; its absence while the key is
     * held means the jump check upstream rejected the attempt.
     */
    public void traceJumpExecuted(LocalPlayer player) {
        Vec3 delta = player.getDeltaMovement();
        LOGGER.info("jumptrace: jumpFromGround FIRED, resulting vy={}", String.format("%.3f", delta.y));
    }

    private static boolean remove(AttributeInstance speed, String id, boolean enabled) {
        AttributeModifier modifier = speed.getModifier(UUID.fromString(id));
        if (enabled && modifier != null) {
            speed.removePermanentModifier(modifier.getId());
            return true;
        }
        return false;
    }

    /**
     * Diagnostic: measures how far the player actually moves per second, so a test run's log
     * tells apart client-side movement (which this module controls) from anything imposed
     * elsewhere. Full walk is ~4.3 m/s, full sprint ~5.6 m/s.
     */
    private void trackActualSpeed(LocalPlayer player, double attributeBefore, boolean overweight, boolean fracture) {
        Vec3 pos = player.position();
        if (lastPos != null) {
            accumulatedDistance += pos.distanceTo(lastPos);
        }
        lastPos = pos;

        long now = System.currentTimeMillis();
        if (now - lastLog >= LOG_INTERVAL_MS) {
            double seconds = (now - lastLog) / 1000.0;
            double mps = seconds > 0 ? accumulatedDistance / seconds : 0;
            lastLog = now;
            accumulatedDistance = 0;
            LOGGER.info("MoveFreedom: attr {} -> {}, overweight {}, fracture {}, jumpgate {}, actiongate {}, burdengate {}, parcoolgate {}, actual speed {} m/s",
                    String.format("%.4f", attributeBefore),
                    String.format("%.4f", player.getAttributeValue(Attributes.MOVEMENT_SPEED)),
                    overweight ? "present" : "absent",
                    fracture ? "present" : "absent",
                    jumpGateMixinFired ? "fired" : "not-fired",
                    actionGateMixinFired ? "fired" : "not-fired",
                    burdenGateMixinFired ? "fired" : "not-fired",
                    parcoolGateMixinFired ? "fired" : "not-fired",
                    String.format("%.1f", mps));
        }
    }
}
