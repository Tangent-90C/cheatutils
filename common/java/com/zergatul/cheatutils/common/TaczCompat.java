package com.zergatul.cheatutils.common;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Reflection bridge to the TaCZ mod, used by the TaCZ-related modules. TaCZ is deliberately
 * kept off the compile classpath so the mod builds and runs without it installed.
 */
public final class TaczCompat {

    private static final Logger LOGGER = LogManager.getLogger(TaczCompat.class);
    private static final String NETWORK_HANDLER = "com.tacz.guns.network.NetworkHandler";
    private static final String DRAW_MESSAGE =
            "com.tacz.guns.network.message.ClientMessagePlayerDrawGun";
    private static final String WEAPON_CAPABILITY_PROVIDER =
            "com.tacz.guns.inventory.WeaponCapabilityProvider";
    private static final String WEAPON_INVENTORY = "com.tacz.guns.inventory.WeaponInventory";
    private static final String GUN_SOUND_INSTANCE = "com.tacz.guns.client.sound.GunSoundInstance";
    private static final String SHOOT_KEY = "com.tacz.guns.client.input.ShootKey";
    private static final String TIMELESS_API = "com.tacz.guns.api.TimelessAPI";
    private static final String GUN_OPERATOR = "com.tacz.guns.api.entity.IGunOperator";
    private static final String SHOOTER_DATA_HOLDER = "com.tacz.guns.entity.shooter.ShooterDataHolder";

    private static boolean soundResolved;
    private static boolean soundFailed;
    private static Field gunSoundMonoField;
    private static Method gunSoundRegistryNameMethod;
    private static boolean shootResolved;
    private static boolean shootFailed;
    private static Method autoShootController;
    private static Method semiShootController;
    private static boolean gunDataResolved;
    private static boolean gunDataFailed;
    private static Method getCommonGunIndex;
    private static Method getGunData;
    private static Method getBurstData;
    private static Method isContinuousShoot;
    private static boolean originResolved;
    private static boolean originFailed;
    private static Method fromLivingEntity;
    private static Method getOperatorDataHolder;
    private static Field stableShootEyePosField;
    private static boolean resolved;
    private static boolean failed;
    private static Object channel;
    private static Constructor<?> drawMessageConstructor;
    private static Method sendToServer;
    private static Method getCapability;
    private static Object weaponCapability;
    private static Method lazyResolve;
    private static Method getSelectedSlot;

    private TaczCompat() {
    }

    public static boolean holdsTaczGun(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        CompoundTag tag = stack.getTag();
        return tag != null && tag.contains("GunId", Tag.TAG_STRING)
                && !tag.getString("GunId").isBlank();
    }

    /**
     * Sends the draw message with the player's current slots, which makes the server
     * re-snapshot the shoot origin - the position bullets spawn from - at the current
     * position. TaCZ only refreshes that origin when a gun is drawn, so after flying or
     * travelling far the bullets would otherwise spawn from where the gun was last drawn.
     *
     * @return false when TaCZ is absent, the slots cannot be read, or the send failed
     */
    public static synchronized boolean syncShootOrigin(LocalPlayer player) {
        if (failed || player == null) {
            return false;
        }
        try {
            if (!resolved) {
                Class<?> handlerClass = Class.forName(NETWORK_HANDLER);
                Class<?> messageClass = Class.forName(DRAW_MESSAGE);
                Class<?> providerClass = Class.forName(WEAPON_CAPABILITY_PROVIDER);
                Class<?> inventoryClass = Class.forName(WEAPON_INVENTORY);
                Class<?> capabilityClass =
                        Class.forName("net.minecraftforge.common.capabilities.Capability");
                Class<?> lazyOptionalClass =
                        Class.forName("net.minecraftforge.common.util.LazyOptional");
                channel = handlerClass.getField("CHANNEL").get(null);
                drawMessageConstructor = messageClass.getConstructor(int.class, int.class);
                sendToServer = channel.getClass().getMethod("sendToServer", Object.class);
                weaponCapability = providerClass.getField("WEAPON_CAP").get(null);
                getCapability = player.getClass().getMethod("getCapability", capabilityClass);
                lazyResolve = lazyOptionalClass.getMethod("resolve");
                getSelectedSlot = inventoryClass.getMethod("getSelectedSlot");
                resolved = true;
            }

            // The draw handler replaces the selected weapon slot and hotbar slot with the
            // values in the packet, so the current ones have to be sent back unchanged.
            int weaponSlot = -1;
            Optional<?> inventory = (Optional<?>) lazyResolve.invoke(
                    getCapability.invoke(player, weaponCapability));
            if (inventory.isPresent()) {
                weaponSlot = (Integer) getSelectedSlot.invoke(inventory.get());
            }
            int hotbarSlot = player.getInventory().selected;

            sendToServer.invoke(channel,
                    drawMessageConstructor.newInstance(weaponSlot, hotbarSlot));
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            failed = true;
            LOGGER.error("Cannot sync TaCZ shoot origin", e);
            return false;
        }
    }

    /**
     * TaCZ plays every gun sound through one client-side class, both the first-person sound of
     * the local gun and the third-person sound of other players. The two cases are told apart by
     * the private {@code mono} flag: {@link com.tacz.guns.client.sound.SoundPlayManager} passes
     * {@code true} only for the {@code shoot_3p}/{@code silence_3p} sounds that arrive in the
     * {@code ServerMessageSound} packet, and {@code false} for the local sound and for the
     * nearby reload/draw/inspect sounds. That makes the flag an exact marker for "another
     * player just fired a gun", which is what the sound ESP keys on.
     *
     * @param sound client sound instance, unchecked on purpose so TaCZ is not needed at compile time
     * @return {@code true} when the instance is a third-person shot fired by another entity
     */
    public static boolean isRemoteGunShotSound(Object sound) {
        if (sound == null || !GUN_SOUND_INSTANCE.equals(sound.getClass().getName())) {
            return false;
        }
        resolveSound();
        try {
            if (gunSoundMonoField != null) {
                return gunSoundMonoField.getBoolean(sound);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            soundFailed = true;
        }
        return false;
    }

    /**
     * TaCZ resolves the actual sound file of a gun lazily through
     * {@code GunSoundInstance#getRegistryName()}, so the event registered in sounds.json
     * (always {@code tacz:gun}) says nothing about the weapon. The registry name does.
     *
     * @return per-gun sound id, e.g. {@code tacz:ak47_shoot_3p}, or {@code null} when unreadable
     */
    public static ResourceLocation getGunSoundId(Object sound) {
        if (sound == null || !GUN_SOUND_INSTANCE.equals(sound.getClass().getName())) {
            return null;
        }
        resolveSound();
        try {
            if (gunSoundRegistryNameMethod != null) {
                Object result = gunSoundRegistryNameMethod.invoke(sound);
                if (result instanceof ResourceLocation) {
                    return (ResourceLocation) result;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            soundFailed = true;
        }
        return null;
    }

    private static void resolveSound() {
        if (soundResolved || soundFailed) {
            return;
        }
        try {
            Class<?> clazz = Class.forName(GUN_SOUND_INSTANCE);
            gunSoundMonoField = clazz.getDeclaredField("mono");
            gunSoundMonoField.setAccessible(true);
            gunSoundRegistryNameMethod = clazz.getMethod("getRegistryName");
            soundResolved = true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            // TaCZ absent or renamed internals - callers fall back to "" and keep working.
            soundFailed = true;
        }
    }

    /**
     * Pulls the trigger through the TaCZ client shoot controllers - the same entry points the
     * mod's own input handling uses - so all of its gates apply unchanged: fire mode routing
     * (AUTO and continuous burst via autoShootController, SEMI and short burst via
     * semiShootController), the per-gun cooldown, dry fire sound on an empty magazine, and
     * shoot-then-empty-reload. Both calls no-op when the main hand item is not a gun, when a
     * screen is open or when the window is unfocused. Safe to poll every tick; each call is
     * rate-limited by the gun's own cooldown gate.
     *
     * @return true when a shot was actually fired by this call
     */
    public static synchronized boolean tryShoot() {
        if (!resolveShootMethods()) {
            return false;
        }
        try {
            if ((Boolean) autoShootController.invoke(null)) {
                return true;
            }
            return (Boolean) semiShootController.invoke(null, true);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            shootFailed = true;
            LOGGER.error("Cannot trigger TaCZ shoot", e);
            return false;
        }
    }

    /**
     * Releases the semi-auto trigger state; call right before each {@link #tryShoot()} pull.
     * The controllers mark the trigger consumed after a shot, and only a pull following a
     * release can route an empty magazine into the first-pull auto-reload path.
     */
    public static synchronized void releaseShootTrigger() {
        if (!resolveShootMethods()) {
            return;
        }
        try {
            semiShootController.invoke(null, false);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            shootFailed = true;
            LOGGER.error("Cannot release TaCZ shoot trigger", e);
        }
    }

    private static boolean resolveShootMethods() {
        if (shootResolved) {
            return true;
        }
        if (shootFailed) {
            return false;
        }
        try {
            Class<?> clazz = Class.forName(SHOOT_KEY);
            autoShootController = clazz.getMethod("autoShootController");
            semiShootController = clazz.getMethod("semiShootController", boolean.class);
            shootResolved = true;
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOGGER.warn("TaCZ shoot controllers not available, auto fire disabled");
            shootFailed = true;
            return false;
        }
    }

    /**
     * Reports whether the held gun keeps firing while the trigger is held: fire mode AUTO from
     * the gun NBT, or a BURST gun whose burst data is marked continuous. SEMI and short-burst
     * guns return false. Classifying BURST needs the common gun index, resolved by gun id from
     * the same NBT.
     *
     * @return false for non-guns, unreadable guns and when TaCZ data is unavailable
     */
    public static synchronized boolean isFullAutoGun(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains("GunFireMode", Tag.TAG_STRING)) {
            return false;
        }
        String fireMode = tag.getString("GunFireMode");
        if ("AUTO".equals(fireMode)) {
            return true;
        }
        if ("SEMI".equals(fireMode) || !"BURST".equals(fireMode)) {
            return false;
        }
        if (!tag.contains("GunId", Tag.TAG_STRING) || !resolveGunDataMethods()) {
            return false;
        }
        try {
            String gunId = tag.getString("GunId");
            Object index = ((Optional<?>) getCommonGunIndex.invoke(null, new ResourceLocation(gunId)))
                    .orElse(null);
            if (index == null) {
                return false;
            }
            Object gunData = getGunData.invoke(index);
            if (gunData == null) {
                return false;
            }
            Object burstData = getBurstData.invoke(gunData);
            return burstData != null && (Boolean) isContinuousShoot.invoke(burstData);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            gunDataFailed = true;
            LOGGER.warn("Cannot read TaCZ burst data", e);
            return false;
        }
    }

    private static boolean resolveGunDataMethods() {
        if (gunDataResolved) {
            return true;
        }
        if (gunDataFailed) {
            return false;
        }
        try {
            getCommonGunIndex = Class.forName(TIMELESS_API)
                    .getMethod("getCommonGunIndex", ResourceLocation.class);
            Class<?> commonGunIndex = Class.forName("com.tacz.guns.resource.index.CommonGunIndex");
            getGunData = commonGunIndex.getMethod("getGunData");
            Class<?> gunDataClass = Class.forName("com.tacz.guns.resource.pojo.data.gun.GunData");
            getBurstData = gunDataClass.getMethod("getBurstData");
            Class<?> burstDataClass = Class.forName("com.tacz.guns.resource.pojo.data.gun.BurstData");
            isContinuousShoot = burstDataClass.getMethod("isContinuousShoot");
            gunDataResolved = true;
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOGGER.warn("TaCZ gun data API not available");
            gunDataFailed = true;
            return false;
        }
    }

    /**
     * The position bullets actually spawn from: the eye position where the current gun was
     * drawn. TaCZ refreshes it only on draw, so after travelling it can sit far away from
     * the player while every shot still leaves from there. Aiming must be computed from this
     * point rather than the current eyes, or the bullet line misses whatever the crosshair
     * sees - most visibly clipping cover when peeking.
     *
     * @return the stable shoot origin; null when unavailable (TaCZ absent or not drawn yet)
     */
    public static synchronized Vec3 getStableShootOrigin(LocalPlayer player) {
        if (player == null || originFailed || !resolveOriginMethods()) {
            return null;
        }
        try {
            Object operator = fromLivingEntity.invoke(null, player);
            Object holder = getOperatorDataHolder.invoke(operator);
            return (Vec3) stableShootEyePosField.get(holder);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            originFailed = true;
            LOGGER.warn("Cannot read TaCZ shoot origin", e);
            return null;
        }
    }

    private static boolean resolveOriginMethods() {
        if (originResolved) {
            return true;
        }
        if (originFailed) {
            return false;
        }
        try {
            Class<?> operatorClass = Class.forName(GUN_OPERATOR);
            fromLivingEntity = operatorClass.getMethod("fromLivingEntity", LivingEntity.class);
            getOperatorDataHolder = operatorClass.getMethod("getDataHolder");
            stableShootEyePosField = Class.forName(SHOOTER_DATA_HOLDER).getField("stableShootEyePos");
            originResolved = true;
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOGGER.warn("TaCZ shooter data not available");
            originFailed = true;
            return false;
        }
    }
}
