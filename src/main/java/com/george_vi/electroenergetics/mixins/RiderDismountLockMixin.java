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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Refuses a player-initiated dismount from a seat while the train is above Create's ceiling.
 *
 * <h2>The hook, and why it is this one</h2>
 *
 * <p>{@code Player.wantsToStopRiding()} answers exactly one question - does this player want to get off -
 * and is consulted only from the ride tick. Cancelling it refuses the dismount and touches nothing else.
 *
 * <p>The first version hooked {@code Entity.stopRiding} instead, which is the shared exit for every reason a
 * passenger can be detached: this key, but also teleports, dimension changes, death and the vehicle being
 * removed. Refusing there would have blocked a teleport or left a ghost passenger behind when a train
 * unloaded. Those paths do not ask whether the player wants to get off, so they never reach this hook.
 *
 * <h2>The guard, and the mistake it repeated</h2>
 *
 * <p>A seated passenger on a train does not ride a {@code SeatEntity}. They ride the carriage itself, and
 * Create records their seat in {@code Contraption.seatMapping}. The first version tested for a SeatEntity,
 * so on a train the test could never be true and the lock was dead code - the same assumption that had
 * already been corrected in RiderSeatGate, made again in a second file.
 *
 * <p>Seated is therefore "the carriage's seat mapping contains this player". The vehicle is checked both
 * ways anyway: a seat block standing in the world still uses a SeatEntity, and if one is ever used as a
 * carriage seat this keeps working.
 *
 * <h2>Why the message goes only to the player</h2>
 *
 * <p>They are the one being refused, and they are looking at the train. The driver already sees the speed
 * and the seat state on the panel.
 */
@Mixin(value = Player.class, remap = false)
public class RiderDismountLockMixin {

    @Inject(method = "wantsToStopRiding", at = @At("RETURN"), cancellable = true, remap = false)
    private void electroEnergetics$lockSeatAboveCeiling(CallbackInfoReturnable<Boolean> cir) {
        try {
            if (!cir.getReturnValue())
                return;                                  // not trying to leave; nothing to refuse
            if (!((Object) this instanceof ServerPlayer player))
                return;

            CarriageContraptionEntity carriage = null;
            if (player.getVehicle() instanceof CarriageContraptionEntity direct)
                carriage = direct;
            else if (player.getVehicle() instanceof SeatEntity seat
                    && seat.getRootVehicle() instanceof CarriageContraptionEntity root)
                carriage = root;
            if (carriage == null)
                return;

            Contraption contraption = carriage.getContraption();
            if (contraption == null || !contraption.getSeatMapping().containsKey(player.getUUID()))
                return;                                  // not in a seat on this carriage

            var c = carriage.getCarriage();
            if (c == null || c.train == null)
                return;
            if (!RiderSeatGate.atOrAboveCeiling(c.train))
                return;                                  // slow enough: getting off is safe

            cir.setReturnValue(false);
            player.displayClientMessage(
                    Component.translatable("electroenergetics.train.riders.seatLocked"), true);
        } catch (Throwable ignored) {
            // Falling through to the normal dismount is always safe.
        }
    }
}