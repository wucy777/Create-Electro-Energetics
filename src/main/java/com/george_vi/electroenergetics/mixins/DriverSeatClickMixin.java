package com.george_vi.electroenergetics.mixins;

import com.george_vi.electroenergetics.content.railway_electrification.DriverAuthority;
import com.george_vi.electroenergetics.content.railway_electrification.RiderSeatGate;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A control desk's own seat cannot be clicked into.
 *
 * <p>The operator's rule: the seat nearest a controls block is the DRIVER's seat, it belongs with the desk
 * rather than with the passenger seating, and the way into it is to take the controls - not to click it.
 *
 * <p>Hooked on {@code AbstractContraptionEntity.handlePlayerInteraction} rather than on
 * {@code SeatInteractionBehaviour.handlePlayerInteraction}. The seat behaviour is only one of the ways a click
 * can reach a seat, and the operator reported that a click still seated them after the behaviour was refused -
 * so the guard belongs at the single point every contraption interaction passes through, keyed on the block
 * that was actually clicked.
 *
 * <p>Passenger seats are untouched. Only a seat that is currently RESERVED for a desk is refused, which is the
 * same set the seating pass treats as illegal for anybody else.
 */
@Mixin(value = AbstractContraptionEntity.class, remap = false)
public class DriverSeatClickMixin {

    @Inject(method = "handlePlayerInteraction", at = @At("HEAD"), cancellable = true, remap = false)
    private void electroEnergetics$refuseDriverSeatClick(Player player, BlockPos localPos, Direction face,
                                                         InteractionHand hand,
                                                         CallbackInfoReturnable<Boolean> cir) {
        try {
            if (!((Object) this instanceof CarriageContraptionEntity cce))
                return;
            Carriage carriage = cce.getCarriage();
            if (carriage == null || carriage.train == null || !DriverAuthority.appliesTo(carriage.train))
                return;
            Contraption contraption = cce.getContraption();
            if (contraption == null)
                return;
            int seat = contraption.getSeats().indexOf(localPos);
            if (seat < 0) {
                // A CLICK ON A SEAT BLOCK MAY NOT ARRIVE AS THE SEAT'S OWN POSITION.
                //
                // handlePlayerInteraction is called for every contraption block the player targets, and the
                // position it carries is the block that was clicked. Create's seat behaviour accepts the seat
                // block itself, but the interaction also reaches this method for the block BEHIND a seat, for a
                // seat two high, and - when the click is resolved against the carriage's collision shape - for a
                // position a fraction off. Matching only an exact seat position therefore missed clicks that
                // still seated the player, which is what the operator reported after the exact-match version.
                //
                // So a click within one block of a reserved seat is refused too. Passenger seats are unaffected:
                // they are never in the reserved set.
                int near = -1;
                for (int i = 0; i < contraption.getSeats().size(); i++) {
                    if (contraption.getSeats().get(i).distManhattan(localPos) <= 1) {
                        near = i;
                        break;
                    }
                }
                if (near < 0)
                    return;
                seat = near;
            }
            int carriageIndex = RiderSeatGate.carriageIndexOf(carriage.train, cce);
            if (carriageIndex < 0)
                return;
            if (RiderSeatGate.reservedDriverSeats(carriage.train)
                    .contains(new RiderSeatGate.SeatRef(carriageIndex, seat))) {
                cir.setReturnValue(false);      // consumed, and nothing happens
            }
        } catch (Throwable ignored) {
            // A refused seat is a rule; it must never be the thing that breaks interaction.
        }
    }
}
