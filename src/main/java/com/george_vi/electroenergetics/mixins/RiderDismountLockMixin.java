package com.george_vi.electroenergetics.mixins;

import com.george_vi.electroenergetics.content.railway_electrification.RiderSeatGate;
import com.simibubi.create.content.contraptions.actors.seat.SeatEntity;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Refuses a shift-dismount from a seat while the train is above Create's ceiling.
 *
 * <h2>What the lock is for, and what it is not for</h2>
 *
 * <p>Getting off a train that is already faster than the rider model supports is the one action that
 * produces the state this whole feature exists to prevent - and the brake it would trigger makes the train
 * stop for everybody. So the dismount is refused while the train is too fast, and the player is told why.
 *
 * <p>It is NOT a general "cannot leave the seat" rule. Teleporting out and dying both leave the seat and
 * both are allowed: the player is no longer on the train, so the train is unaffected. Blocking them would
 * mean blocking /tp and blocking death, which is not what was asked and would be dangerous to implement.
 *
 * <h2>How the two are told apart</h2>
 *
 * <p>Shift. A player-initiated dismount from a seat happens with the shift key held, which is the client's
 * only way to ask for one; teleports and deaths call {@code stopRiding} with shift not held. Reading the
 * key state is therefore exactly the discrimination needed, and it needs no packet, because vanilla 1.21 has
 * no dismount packet - the client dismounts locally and the server follows.
 *
 * <p>A player who happens to be holding shift while being teleported is refused as well. That is a narrow
 * enough overlap to accept: the alternative is a lock that cannot be applied at all.
 *
 * <h2>Server only</h2>
 *
 * <p>The client cannot evaluate this - {@code getCarriage()} is null there, so the train's speed is not
 * available - and the server is the authority on the passenger list anyway. The client may dismount locally
 * for a tick and is corrected when the server refuses.
 */
@Mixin(value = Entity.class, remap = false)
public class RiderDismountLockMixin {

    @Inject(method = "stopRiding", at = @At("HEAD"), cancellable = true, remap = false)
    private void electroEnergetics$lockSeatAboveCeiling(CallbackInfo ci) {
        try {
            if (!((Object) this instanceof ServerPlayer player))
                return;
            if (player.level().isClientSide())
                return;
            if (!player.isShiftKeyDown())
                return;                         // not a player-initiated dismount
            if (!(player.getVehicle() instanceof SeatEntity))
                return;                         // not sitting
            if (!(player.getRootVehicle() instanceof CarriageContraptionEntity carriage))
                return;

            var c = carriage.getCarriage();
            if (c == null || c.train == null)
                return;
            if (Math.abs(c.train.speed) <= RiderSeatGate.CREATE_CEILING_BPT)
                return;                         // slow enough: getting off is safe

            ci.cancel();
            player.displayClientMessage(
                    Component.translatable("electroenergetics.train.riders.seatLocked"), true);
        } catch (Throwable ignored) {
            // Falling through to the normal dismount is always safe.
        }
    }
}