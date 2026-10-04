package com.george_vi.electroenergetics.mixins;

import com.george_vi.electroenergetics.client.TrainControlInput;
import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stops the cab's keys reaching anything else while an electric train is being
 * driven.
 *
 * <p>The problem this solves is a modpack one. Every convenient key is already
 * claimed by something, so whichever keys the cab uses would also fire whatever else
 * is bound to them - opening a map, toggling a HUD, firing a tool - and there is no
 * binding the player could move out of the way, because the conflict is with a mod
 * they want to keep. So while driving an electric train, the cab's four keys are
 * consumed here and never reach a KeyMapping or another mod's input handler.
 *
 * <p>{@code KeyboardHandler.keyPress} is the single point every key press passes
 * through: it is where vanilla dispatches KeyMappings and where NeoForge posts its
 * key event, so cancelling here stops both at once rather than one route at a time.
 *
 * <p>Deliberately narrow. Only the four cab keys, and only while actually driving an
 * electric train. Everything else - Escape, the inventory, chat, and any other mod's
 * keys - behaves exactly as it did, because suppressing them would make the game
 * unusable rather than the cab usable.
 *
 * <p>The cab still reads its own keys, because {@link TrainControlInput} asks GLFW
 * directly rather than listening for events. So the keys are dead to the rest of the
 * game and alive to the lever, which is the whole intent.
 */
@Mixin(KeyboardHandler.class)
public class KeyboardHandlerMixin {

    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true, remap = false)
    private void electroEnergetics$consumeCabKeys(long windowPointer, int key, int scanCode,
                                                  int action, int modifiers, CallbackInfo ci) {
        // Only on press and auto-repeat. The release must still get through, or a
        // KeyMapping would be left stuck down for the rest of the session - the game
        // would think the player is still holding whatever the cab key was bound to.
        if (action == org.lwjgl.glfw.GLFW.GLFW_RELEASE)
            return;
        if (!TrainControlInput.isCabKey(key))
            return;
        if (!TrainControlInput.isDrivingElectricTrain())
            return;
        ci.cancel();
    }
}
