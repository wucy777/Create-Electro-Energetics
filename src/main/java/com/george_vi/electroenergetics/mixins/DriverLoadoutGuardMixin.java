package com.george_vi.electroenergetics.mixins;

import com.george_vi.electroenergetics.content.railway_electrification.DriverAuthority;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.Train;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps the driver aboard when Create trims the passenger loadout.
 *
 * <h2>What Create does</h2>
 *
 * <p>{@code DimensionalCarriageEntity.updatePassengerLoadout} walks the carriage's recorded passengers and drops
 * anyone whose seat falls outside {@code [minAllowedLocalCoord, maxAllowedLocalCoord]}. Those two bounds are
 * derived from {@code cutoff}, which is 0 or 1 for a carriage squarely on one track segment - and then the bounds
 * are the whole number line, so nobody is dropped. On a curve the cutoff takes intermediate values and the bounds
 * collapse to a narrow window around the carriage's own centre.
 *
 * <p>A seat near the MIDDLE of a carriage is always inside that window. A seat at the very end - which is exactly
 * where a rear cab's nearest seat is - falls outside it. That is why one end of a twin-cab train threw its driver
 * out on a curve and the other end never did: seat 0 against seat 10, on the same carriage, with the same code.
 *
 * <p>The drop does not go through {@code Entity.stopRiding} on the path that matters, which is why neither
 * dismount lock ever saw it and the diagnostic log showed nothing at the moment of the throw.
 *
 * <h2>What this does</h2>
 *
 * <p>Refuses the drop for the player who holds this train's driving claim. Nobody else is affected: an ordinary
 * passenger is still trimmed exactly as Create intends, and a train with no claim behaves as it always has.
 */
@Mixin(targets = "com.simibubi.create.content.trains.entity.Carriage$DimensionalCarriageEntity")
public class DriverLoadoutGuardMixin {

    @Inject(method = "dismountPlayer", at = @At("HEAD"), cancellable = true, remap = false)
    private void electroEnergetics$keepDriverAboard(net.minecraft.server.level.ServerLevel level,
                                                    ServerPlayer player,
                                                    Integer seat,
                                                    boolean savePassenger,
                                                    CallbackInfo ci) {
        try {
            if (player == null)
                return;
            // The train is reached from the PLAYER's own ride rather than from the mixin target: this$0 is a
            // compiler-generated field and cannot be named from source. The player being removed is by definition
            // riding the carriage this loadout belongs to, so their vehicle answers the same question.
            if (!(player.getVehicle() instanceof com.simibubi.create.content.trains.entity.CarriageContraptionEntity cce))
                return;
            Carriage carriage = cce.getCarriage();
            if (carriage == null || carriage.train == null)
                return;
            Train train = carriage.train;
            if (!DriverAuthority.appliesTo(train))
                return;
            // EVERY player is protected, not only the claim owner. The trim exists for the curve-segment
            // bookkeeping (which graph edge a seat's local coordinate belongs to), and a dropped seat means
            // the seating pass starts over: the passengers stand again, the overload verdict arms, the lever
            // cuts to the brake, and the re-seating happens without the client's report - the assigned seats
            // stop being the visually nearest ones. Seats are fixed Blocks on the carriage; a rider staying
            // in one is visually correct at every segment crossing. Mobs and other riders keep the trim.
            if (player instanceof ServerPlayer)
                ci.cancel();
        } catch (Throwable ignored) {
            // A failed guard must never be the thing that removes somebody.
        }
    }
}
