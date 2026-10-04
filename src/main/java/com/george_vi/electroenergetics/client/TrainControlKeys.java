package com.george_vi.electroenergetics.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

/**
 * The electric train's driving keys.
 *
 * <p>Deliberately raw {@link InputConstants} rather than {@link KeyMapping}. A
 * KeyMapping is polled by matching whatever the options file has bound, so another
 * mod that has taken the same key would win, or the key would be remappable out from
 * under the cab. These are read straight from GLFW for the driving case, which is
 * what makes the cab usable in a pack where every convenient key is already spoken
 * for.
 *
 * <p>They are only consulted while a player is driving an electric train. Everywhere
 * else the mod does not look at these keys at all, so a conflicting binding in
 * another mod keeps working normally outside the cab.
 */
@OnlyIn(Dist.CLIENT)
public final class TrainControlKeys {

    /** Notch the lever towards ACCELERATE / towards REVERSE. */
    public static final int LEVER_UP = GLFW.GLFW_KEY_UP;
    public static final int LEVER_DOWN = GLFW.GLFW_KEY_DOWN;

    /** Vigilance acknowledgement. */
    public static final int CONFIRM = GLFW.GLFW_KEY_J;

    /** Emergency brake. */
    public static final int EMERGENCY = GLFW.GLFW_KEY_K;

    private TrainControlKeys() {}

    /** Whether a raw key is down right now. */
    public static boolean isDown(int key) {
        return InputConstants.isKeyDown(
                net.minecraft.client.Minecraft.getInstance().getWindow().getWindow(), key);
    }
}
