package com.george_vi.electroenergetics.mixins;

import com.george_vi.electroenergetics.content.railway_electrification.RiderSeatGate;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The second guard on a player leaving a seat at speed.
 *
 * <p>{@code Player.wantsToStopRiding} is the precise question - "does this player want to get off" - and it
 * lives in its own mixin. It was not enough on its own: a dismount can also arrive through
 * {@code stopRiding} from elsewhere.
 *
 * <p>This must target {@link Entity}, not {@link net.minecraft.world.entity.player.Player}. The first
 * version put the injection on Player, where the method is only INHERITED, so Mixin found no target and
 * refused to apply the whole mixin - a startup crash with an empty error message. Declaring it on the class
 * that actually owns the method costs one more mixin and removes the failure.
 *
 * <p>Teleports and dimension changes are let through deliberately. They dismount through this same method,
 * and refusing them would strand the player or leave a ghost passenger behind; neither has anything to do
 * with a player choosing to get off.
 */
@Mixin(value = Entity.class, remap = false)
public class RiderStopRidingLockMixin {

    @Inject(method = "stopRiding", at = @At("HEAD"), cancellable = true, remap = false)
    private void electroEnergetics$lockStopRiding(CallbackInfo ci) {
        try {
            if (!((Object) this instanceof ServerPlayer player))
                return;
            if (player.isChangingDimension())
                return;
            if (((TeleportPendingAccessor) player.connection)
                    .electroEnergetics$awaitingPositionFromClient() != null)
                return;
            if (!RiderSeatGate.shouldLockDismount(player))
                return;
            ci.cancel();
            RiderSeatGate.refuseDismount(player);
        } catch (Throwable ignored) {
            // Letting the dismount through is the safe failure.
        }
    }
}