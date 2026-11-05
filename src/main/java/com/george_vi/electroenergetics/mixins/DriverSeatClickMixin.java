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
            if (seat < 0)
                return;
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
