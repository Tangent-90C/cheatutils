package com.zergatul.cheatutils.scripting.modules;

import com.mojang.blaze3d.platform.InputConstants;
import com.zergatul.cheatutils.mixins.common.accessors.InputConstantsKeyAccessor;
import com.zergatul.scripting.MethodDescription;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@SuppressWarnings("unused")
public class InputApi {

    private static final Minecraft mc = Minecraft.getInstance();

    private final Map<String, InputConstants.Key> keyMap = new HashMap<>();

    public InputApi() {
        for (InputConstants.Key key: InputConstantsKeyAccessor.getNameMap().values()) {
            StringBuilder sb = new StringBuilder();
            key.getDisplayName().visit(cc -> {
                sb.append(cc);
                return Optional.empty();
            });
            keyMap.put(sb.toString(), key);
        }
    }

    public boolean isShiftDown() {
        return Screen.hasShiftDown();
    }

    public boolean isControlDown() {
        return Screen.hasControlDown();
    }

    public boolean isAltDown() {
        return Screen.hasAltDown();
    }

    @MethodDescription("""
            Use exactly the same key names you see in Key Binds screen
            """)
    public boolean isKeyDown(String key) {
        if (!mc.isWindowActive()) {
            return false;
        }

        InputConstants.Key inputKey = keyMap.get(key);
        if (inputKey == null) {
            return false;
        }
        if (inputKey.getType() == InputConstants.Type.KEYSYM) {
            return InputConstants.isKeyDown(mc.getWindow().getWindow(), inputKey.getValue());
        }
        if (inputKey.getType() == InputConstants.Type.MOUSE) {
            return isMouseDown(inputKey.getValue());
        }

        return false;
    }

    /**
     * Button numbers are raw GLFW, matching what {@code glfwGetMouseButton} takes:
     * 0 is left, 1 is right, 2 is middle, and 3 and 4 are the two side buttons.
     *
     * <p>{@link #isKeyDown} can't be used for this because
     * {@code InputConstants.isKeyDown} reports keyboard keys only, and side
     * buttons aren't in the name map until something binds them.</p>
     */
    @MethodDescription("""
            Checks if a mouse button is currently pressed. Button numbers are raw GLFW numbers,
            as printed by the "Mouse" column in the Key Binds screen: 0 - left, 1 - right,
            2 - middle, 3 and 4 - side buttons.
            """)
    public boolean isMouseDown(int button) {
        if (!mc.isWindowActive()) {
            return false;
        }

        return GLFW.glfwGetMouseButton(mc.getWindow().getWindow(), button) == GLFW.GLFW_PRESS;
    }
}