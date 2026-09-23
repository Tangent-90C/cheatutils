package com.zergatul.cheatutils.modules.hacks;

import com.zergatul.cheatutils.ballistics.WeaponFingerprint;
import com.zergatul.cheatutils.common.Events;
import com.zergatul.cheatutils.configs.ConfigStore;
import com.zergatul.cheatutils.modules.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Reports aiming to the server while a TaCZ gun is used, so TaCZ classifies every shot as
 * the AIM inaccuracy stance (the lowest spread a gun offers) instead of stand, move, sneak
 * or lie. TaCZ derives the stance from the server-side aiming state, which is also what
 * drives the ADS movement-speed penalty and blocks sprinting, so those trade-offs apply
 * while this module is enabled.
 *
 * TaCZ picks the spread on the server from the aiming state the client reports, so the only
 * lever is the report itself: the same {@code ClientMessagePlayerAim} the mod sends when the
 * player toggles the aim key. It is sent through TaCZ's own network channel via reflection
 * to keep TaCZ out of the compile classpath.
 */
@NullMarked
public class TaczAlwaysAim implements Module {

    public static final String TACZ_AIM_KEY = "key.tacz.aim.desc";
    private static final Logger LOGGER = LogManager.getLogger(TaczAlwaysAim.class);
    private static final String NETWORK_HANDLER = "com.tacz.guns.network.NetworkHandler";
    private static final String AIM_MESSAGE = "com.tacz.guns.network.message.ClientMessagePlayerAim";
    private static final String GUN_OPERATOR = "com.tacz.guns.api.entity.IGunOperator";
    private static final UUID TACZ_EXTRA_SPEED_MODIFIER =
            UUID.fromString("4D5696AE-A7C5-C59C-80E9-2A2DC8373C46");
    public static final TaczAlwaysAim instance = new TaczAlwaysAim();

    private final Minecraft mc = Minecraft.getInstance();

    private boolean channelResolved;
    private boolean reflectionFailed;
    private @Nullable Object channel;
    private @Nullable Constructor<?> messageConstructor;
    private @Nullable Method sendToServer;
    private @Nullable Method fromLivingEntity;
    private @Nullable Method getSynIsAiming;
    private @Nullable Method getSynAimingProgress;

    private boolean aimingReported;

    private TaczAlwaysAim() {
        Events.InGameTickEnd.add(this::onTickEnd);
    }

    public boolean isActive() {
        return ConfigStore.instance.getConfig().taczAlwaysAim.enabled;
    }

    public boolean isAimKeyForced() {
        return isActive() && isGunInHand();
    }

    private boolean isGunInHand() {
        return mc.player != null && WeaponFingerprint.create(mc,
                mc.player.getItemInHand(InteractionHand.MAIN_HAND)).isPresent();
    }

    /**
     * Synced aiming state as the server sees it, for diagnostics: TiCZ picks the inaccuracy
     * stance from this progress, and the progress only reaches 1.0 when the aim state stays
     * on - so this line shows whether the AIM stance can apply at all.
     */
    public @Nullable String describeAimState() {
        if (reflectionFailed || mc.player == null) {
            return null;
        }
        try {
            if (fromLivingEntity == null) {
                Class<?> operatorClass = Class.forName(GUN_OPERATOR);
                fromLivingEntity = operatorClass.getMethod("fromLivingEntity", LivingEntity.class);
                getSynIsAiming = operatorClass.getMethod("getSynIsAiming");
                getSynAimingProgress = operatorClass.getMethod("getSynAimingProgress");
            }
            Object operator = fromLivingEntity.invoke(null, mc.player);
            if (operator == null) {
                return null;
            }
            boolean aiming = Boolean.TRUE.equals(getSynIsAiming.invoke(operator));
            Object progress = getSynAimingProgress.invoke(operator);
            return (aiming ? "aiming" : "not aiming") + ", progress="
                    + String.format(java.util.Locale.ROOT, "%.2f", ((Number) progress).floatValue());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            reflectionFailed = true;
            LOGGER.error("Cannot read TaCZ aiming state, TacZ Always Aim is disabled", e);
            return null;
        }
    }

    private void onTickEnd() {
        if (mc.player == null) {
            return;
        }

        // TaCZ cancels aiming when the game window loses focus and keeping it re-armed
        // would flip the state every tick, so stay out of the way while unfocused.
        if (!mc.isWindowActive()) {
            aimingReported = false;
            return;
        }

        boolean gunInHand = isGunInHand();
        if (!isActive() || !gunInHand) {
            if (aimingReported) {
                sendAim(false);
                aimingReported = false;
            }
            return;
        }

        if (ConfigStore.instance.getConfig().taczAlwaysAim.keepMovement) {
            stripMovementPenalty();
        }

        if (isServerAiming()) {
            aimingReported = true;
            return;
        }
        if (sendAim(true)) {
            aimingReported = true;
        }
    }

    /**
     * TaCZ applies the aiming movement-speed penalty as an attribute modifier with a fixed
     * UUID on the server, which syncs the reduced value to the client. Removing it locally
     * restores full client-side movement; the server keeps its own copy, so a server that
     * compares movement against its attributes instead of vanilla speed checks could still
     * notice.
     */
    private void stripMovementPenalty() {
        AttributeInstance attribute = mc.player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (attribute != null) {
            attribute.removeModifier(TACZ_EXTRA_SPEED_MODIFIER);
        }
    }

    private boolean isServerAiming() {
        if (reflectionFailed || mc.player == null) {
            return false;
        }
        try {
            if (fromLivingEntity == null) {
                Class<?> operatorClass = Class.forName(GUN_OPERATOR);
                fromLivingEntity = operatorClass.getMethod("fromLivingEntity", LivingEntity.class);
                getSynIsAiming = operatorClass.getMethod("getSynIsAiming");
            }
            Object operator = fromLivingEntity.invoke(null, mc.player);
            return operator != null && Boolean.TRUE.equals(getSynIsAiming.invoke(operator));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            reflectionFailed = true;
            LOGGER.error("Cannot read TaCZ aiming state, TacZ Always Aim is disabled", e);
            return false;
        }
    }

    private boolean sendAim(boolean isAim) {
        if (reflectionFailed) {
            return false;
        }
        try {
            if (!channelResolved) {
                Class<?> handlerClass = Class.forName(NETWORK_HANDLER);
                Class<?> messageClass = Class.forName(AIM_MESSAGE);
                channel = handlerClass.getField("CHANNEL").get(null);
                messageConstructor = messageClass.getConstructor(boolean.class);
                sendToServer = channel.getClass().getMethod("sendToServer", Object.class);
                channelResolved = true;
            }
            Object packet = messageConstructor.newInstance(isAim);
            sendToServer.invoke(channel, packet);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            reflectionFailed = true;
            LOGGER.error("Cannot send TaCZ aim message, TacZ Always Aim is disabled", e);
            return false;
        }
    }
}
