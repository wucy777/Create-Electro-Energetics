package com.george_vi.electroenergetics.mixins;

import com.george_vi.electroenergetics.content.railway_electrification.DriverAuthority;
import com.george_vi.electroenergetics.content.railway_electrification.RiderSeatGate;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The floor under every route into a driver's seat.
 *
 * <p>{@link DriverSeatClickMixin} refuses the click on the carriage, and that covers the ordinary path. This one
 * covers the rest: Create seats a player through {@code addSittingPassenger} from its own seat behaviour, from its
 * passenger loadout code, and from anything else that decides to put somebody in a seat. Rather than enumerate those
 * callers, the seat itself is checked here - the last point every one of them passes through.
 *
 * <p>The rule is narrow on purpose. Only a seat currently RESERVED for a control desk refuses a player who is not its
 * driver: a passenger seat takes passengers exactly as before, and the driver's own seating goes through
 * {@code RiderSeatGate}, which marks itself in SEATING and is let through. A train with no driver holds every desk's
 * seat empty, so clicking one does nothing until somebody takes the controls - which is the operator's rule: the way
 * into the driver's seat is to become the driver.
 */
@Mixin(value = AbstractContraptionEntity.class, remap = false)
public class DriverSeatAdmissionMixin {

    @Inject(method = "addSittingPassenger", at = @At("HEAD"), cancellable = true, remap = false)
    private void electroEnergetics$refuseUnreservedDriverSeat(Entity passenger, int seatIndex, CallbackInfo ci) {
        try {
            if (!((Object) this instanceof CarriageContraptionEntity cce))
                return;
            if (cce.level() == null || cce.level().isClientSide())
                return;
            if (!(passenger instanceof net.minecraft.world.entity.player.Player player))
                return;
            Carriage carriage = cce.getCarriage();
            if (carriage == null || carriage.train == null || !DriverAuthority.appliesTo(carriage.train))
                return;
            if (RiderSeatGate.isSeatingPassenger(player))
                return;                     // the gate's own seating pass
            int carriageIndex = RiderSeatGate.carriageIndexOf(carriage.train, cce);
            if (carriageIndex < 0)
                return;
            if (RiderSeatGate.reservedDriverSeats(carriage.train)
                    .contains(new RiderSeatGate.SeatRef(carriageIndex, seatIndex)))
                ci.cancel();                // nothing happens, and the seat stays empty
        } catch (Throwable ignored) {
            // A refused seat is a rule; it must never be the thing that breaks seating.
        }
    }
}
