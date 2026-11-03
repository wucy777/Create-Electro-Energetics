package com.george_vi.electroenergetics.mixins;

import com.george_vi.electroenergetics.content.railway_electrification.DriverAuthority;
import com.george_vi.electroenergetics.content.railway_electrification.RiderSeatGate;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.actors.seat.SeatInteractionBehaviour;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import net.minecraft.core.BlockPos;
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
 * rather than with the passenger seating, and the way into it is to take the controls - not to click it. So a
 * right-click on it does nothing, and {@link DriverAuthority} seats the player who claims the desk.
 *
 * <p>Without this the driver's seat behaved like any other: it could be clicked by a passenger before the
 * driver arrived, and then the claim would have to evict them - which is a worse experience than the seat
 * simply not being offered.
 *
 * <p>Passenger seats are untouched. Only a seat that is currently RESERVED for a desk is refused, which is
 * the same set the seating pass treats as illegal for anybody else.
 */
@Mixin(value = SeatInteractionBehaviour.class, remap = false)
public class DriverSeatClickMixin {

    @Inject(method = "handlePlayerInteraction", at = @At("HEAD"), cancellable = true, remap = false)
    private void electroEnergetics$refuseDriverSeatClick(Player player, InteractionHand hand, BlockPos localPos,
                                                         AbstractContraptionEntity contraptionEntity,
                                                         CallbackInfoReturnable<Boolean> cir) {
        try {
            if (!(contraptionEntity instanceof CarriageContraptionEntity cce))
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
                    .contains(new RiderSeatGate.SeatRef(carriageIndex, seat)))
                cir.setReturnValue(false);
        } catch (Throwable ignored) {
            // A refused seat is a rule; it must never be the thing that breaks interaction.
        }
    }
}
