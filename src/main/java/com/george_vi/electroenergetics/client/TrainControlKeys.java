package com.george_vi.electroenergetics.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

/**
 * The electric train's own key bindings.
 *
 * <p>Real {@link KeyMapping}s, registered by ModEvents, so they show up in Options -
 * Controls and the driver can move them. That is the whole point: this is a
 * mod-heavy pack where most convenient keys are already taken, and the right answer
 * is to let the driver choose a free key rather than for the mod to seize a vanilla
 * key and hope. Anything the cab reads goes through a binding, so rebinding one moves
 * both the lever and the key suppression together and a rebind cannot be half-applied.
 *
 * <p>Defaults are on the right hand and away from WASD, since the left hand holds the
 * movement keys while driving.
 *
 * <p>Read through {@link KeyMapping#isDown()}, so a rebind is honoured with no further
 * work and the lever cannot disagree with the suppression about which key is the cab's.
 */
@OnlyIn(Dist.CLIENT)
public final class TrainControlKeys {

    private static final String CATEGORY = "key.categories.electroenergetics";

    /** Notch the lever towards ACCELERATE. */
    public static final KeyMapping LEVER_UP = new KeyMapping(
            "key.electroenergetics.train_lever_up",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UP, CATEGORY);

    /** Notch the lever towards REVERSE. */
    public static final KeyMapping LEVER_DOWN = new KeyMapping(
            "key.electroenergetics.train_lever_down",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_DOWN, CATEGORY);

    /** Vigilance acknowledgement. */
    public static final KeyMapping CONFIRM = new KeyMapping(
            "key.electroenergetics.train_confirm",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, CATEGORY);

    /** Emergency brake. */
    public static final KeyMapping EMERGENCY = new KeyMapping(
            "key.electroenergetics.train_emergency",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, CATEGORY);

    private static final KeyMapping[] ALL = {LEVER_UP, LEVER_DOWN, CONFIRM, EMERGENCY};

    private TrainControlKeys() {}

    /** Every cab binding, for registration and for the suppression test. */
    public static KeyMapping[] all() {
        return ALL;
    }

    /** Whether a binding is held. */
    public static boolean isDown(KeyMapping mapping) {
        return mapping.isDown();
    }

    /**
     * Whether a raw GLFW key code is one of the cab's.
     *
     * <p>Resolved from the live bindings rather than from constants, so a player who
     * rebinds gets the suppression on their new key too.
     */
    public static boolean isCabKey(int glfwKey) {
        for (KeyMapping mapping : ALL) {
            if (mapping.getKey().getType() == InputConstants.Type.KEYSYM
                    && mapping.getKey().getValue() == glfwKey)
                return true;
        }
        return false;
    }
}
