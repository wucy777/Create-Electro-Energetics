package com.george_vi.electroenergetics.mixins;

import com.george_vi.electroenergetics.content.railway_electrification.RiderSeatGate;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.actors.seat.SeatEntity;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Refuses a player-initiated dismount from a seat while the train is at or above Create's ceiling.
 *
 * <h2>Two hooks, on purpose</h2>
 *
 * <p>{@code Player.wantsToStopRiding} is the precise question - "does this player want to get off" - and is
 * consulted from the ride tick. It is the right place and it is not sufficient: a dismount can also arrive
 * through {@code stopRiding} from elsewhere, and in testing the player still left the seat at speed.
 *
 * <p>{@code Entity.stopRiding} therefore carries a second guard. It is the shared exit for teleports,
 * dimension changes and vehicle removal as well, so it refuses only when nothing else explains the call: no
 * teleport in flight, no dimension change. Those paths must not be blocked - a refused teleport strands the
 * player, and a refused removal leaves a ghost passenger behind.
 *
 * <h2>The guard, and the mistake it repeated</h2>
 *
 * <p>A seated passenger on a train does not ride a {@code SeatEntity}. They ride the carriage, and Create
 * records their seat in {@code Contraption.seatMapping}. The first version tested for a SeatEntity, so on a
 * train the test could never be true and the lock was dead code.
 *
 * <p>Seated is therefore "the carriage's seat mapping contains this player". The vehicle is accepted either
 * as the carriage directly or through a SeatEntity, so a seat block used as a carriage seat keeps working.
 */
@Mixin(value = Player.class, remap = false)
public class RiderDismountLockMixin {

    @Inject(method = "wantsToStopRiding", at = @At("RETURN"), cancellable = true, remap = false)
    private void electroEnergetics$lockSeatAboveCeiling(CallbackInfoReturnable<Boolean> cir) {
        try {
            if (!cir.getReturnValue())
                return;
            if (!electroEnergetics$shouldLock((Player) (Object) this))
                return;
            cir.setReturnValue(false);
            electroEnergetics$refuse((Player) (Object) this);
        } catch (Throwable ignored) {
            // Falling through to the normal dismount is always safe.
        }
    }

    @Inject(method = "stopRiding", at = @At("HEAD"), cancellable = true, remap = false)
    private void electroEnergetics$lockStopRiding(CallbackInfo ci) {
        try {
            Player self = (Player) (Object) this;
            if (!electroEnergetics$shouldLock(self))
                return;
            if (!(self instanceof ServerPlayer player))
                return;
            if (player.isChangingDimension())
                return;                                  // the dimension change must be allowed to finish
            if (((TeleportPendingAccessor) player.connection).electroEnergetics$awaitingPositionFromClient() != null)
                return;                                  // a teleport is in flight; it owns this dismount
            ci.cancel();
            electroEnergetics$refuse(player);
        } catch (Throwable ignored) {
            // Same reasoning: letting the dismount through is the safe failure.
        }
    }

    /** The shared condition: seated on a train carriage, at or above the ceiling. */
    private static boolean electroEnergetics$shouldLock(Player player) {
        if (!(player instanceof ServerPlayer))
            return false;
        if (player.level().isClientSide())
            return false;

        CarriageContraptionEntity carriage = null;
        if (player.getVehicle() instanceof CarriageContraptionEntity direct)
            carriage = direct;
        else if (player.getVehicle() instanceof SeatEntity seat
                && seat.getRootVehicle() instanceof CarriageContraptionEntity root)
            carriage = root;
        if (carriage == null)
            return false;

        Contraption contraption = carriage.getContraption();
        if (contraption == null || !contraption.getSeatMapping().containsKey(player.getUUID()))
            return false;

        var c = carriage.getCarriage();
        if (c == null || c.train == null)
            return false;
        return RiderSeatGate.atOrAboveCeiling(c.train);
    }

    private static void electroEnergetics$refuse(Player player) {
        player.displayClientMessage(
                Component.translatable("electroenergetics.train.riders.seatLocked"), true);
    }
}