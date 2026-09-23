package com.zergatul.cheatutils.common;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Constructor;
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
}
