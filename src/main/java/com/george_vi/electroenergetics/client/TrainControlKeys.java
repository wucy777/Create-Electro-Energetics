package com.george_vi.electroenergetics.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
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
 * <p>Read by polling GLFW, NOT through {@link KeyMapping#isDown()}. This is not a
 * style choice and it is the one subtle thing in this class.
 *
 * <p>{@code KeyMapping.isDown()} returns a field that is only ever written by
 * {@code KeyMapping.set(...)}, which vanilla calls from
 * {@code KeyboardHandler.keyPress} - and {@code KeyboardHandlerMixin} cancels exactly
 * that method for these keys while a driver is at the controls, so the field would
 * never be set and every binding would read as released. The lever would be dead.
 * Polling GLFW has no such dependency: the key state comes from the window, so it is
 * unaffected by the event being suppressed.
 *
 * <p>Bindings are still real KeyMappings, so they are rebindable; what is read from
 * them is their resolved key code, not their pressed state. That keeps a rebind
 * working while leaving the read independent of the suppressed event.
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

    /**
     * Engage the selected lever position.
     *
     * <p>The lever is two-step: the arrow keys move the handle, and nothing happens
     * to the train until this is pressed. That is how a real lever works - you are
     * able to move it through positions without the train responding to each one as
     * you pass - and it also means a stray arrow key cannot change what the train is
     * doing.
     */
    public static final KeyMapping ENGAGE = new KeyMapping(
            "key.electroenergetics.train_engage",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_ENTER, CATEGORY);

    /** Emergency brake. */
    public static final KeyMapping EMERGENCY = new KeyMapping(
            "key.electroenergetics.train_emergency",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, CATEGORY);

    /**
     * Step through the consist's pantographs, choosing which one the other two keys act on.
     *
     * <p>A selection key rather than one key per pantograph: the number of pantographs is whatever
     * the player built, so a fixed set of keys could not address them, and number keys are already
     * the hotbar. Stepping through them also means the driver can see which one they are about to
     * move before they move it, which matters because the wrong one drops the train's supply.
     */
    /**
     * Step the selected pantograph DOWN the list (towards the first carriage).
     *
     * <p>Left/right rather than letter keys, because the arrow cluster is already the cab's control
     * surface: up/down notch the lever, so left/right choosing WHICH pantograph those keys and the
     * toggle key act on is the same gesture in a second axis. It also frees N, which was an
     * arbitrary letter.
     *
     * <p>A is taken - it steers trains at junctions, and that is Create's own binding, not this
     * mod's to move. So selection is the arrow keys only, and the whole cluster reads as one control
     * panel: up/down the lever, left/right the pantograph, Enter to engage.
     */
    public static final KeyMapping PANTOGRAPH_PREV = new KeyMapping(
            "key.electroenergetics.pantograph_prev",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT, CATEGORY);

    /** Step the selected pantograph UP the list (towards the last carriage). */
    public static final KeyMapping PANTOGRAPH_NEXT = new KeyMapping(
            "key.electroenergetics.pantograph_next",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT, CATEGORY);

    /** Raise or lower the selected pantograph, depending on how it currently stands. */
    public static final KeyMapping PANTOGRAPH_TOGGLE = new KeyMapping(
            "key.electroenergetics.pantograph_toggle",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_M, CATEGORY);

    /**
     * Turn automatic raising on or off FOR THIS TRAIN.
     *
     * <p>Per train, not global: the switch lives on the train's own {@code ElectricTrainData}, so two
     * consists in the same world keep their own settings and a driver who turns it on for the bendy
     * loop does not silently change every other train on the map.
     *
     * <p>A switch, not an action, and that is why it is a third key rather than a mode of the
     * existing two. On, it restores upstream's behaviour - wire in reach, therefore up - which is
     * what a compact line with curves needs: a rigid carriage loses the contact laterally on every
     * bend, and manual raising cannot be pressed often enough. Off is the default and the operator's
     * original rule: how many pantographs are up is the driver's call.
     *
     * <p>Comma rather than a letter: the letters around the movement hand are taken by the cab and by
     * the pack, and comma does not collide with either. It is rebindable like every other cab key.
     */
    public static final KeyMapping PANTOGRAPH_AUTO = new KeyMapping(
            "key.electroenergetics.pantograph_auto",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_COMMA, CATEGORY);

    private static final KeyMapping[] ALL = {LEVER_UP, LEVER_DOWN, CONFIRM, ENGAGE, EMERGENCY,
            PANTOGRAPH_PREV, PANTOGRAPH_NEXT, PANTOGRAPH_TOGGLE, PANTOGRAPH_AUTO};

    private TrainControlKeys() {}

    /** Every cab binding, for registration and for the suppression test. */
    public static KeyMapping[] all() {
        return ALL;
    }

    /**
     * Whether a binding is held right now.
     *
     * <p>Deliberately reads the window rather than the KeyMapping's own state; see the
     * class comment. The binding is used only to find out which key to poll, so a
     * rebind is still honoured.
     */
    public static boolean isDown(KeyMapping mapping) {
        return InputConstants.isKeyDown(
                Minecraft.getInstance().getWindow().getWindow(),
                mapping.getKey().getValue());
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
