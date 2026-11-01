package com.george_vi.electroenergetics.mixins;

import com.george_vi.electroenergetics.content.railway_electrification.DriverAuthority;
import com.george_vi.electroenergetics.content.railway_electrification.ElectricTrainData;
import com.george_vi.electroenergetics.content.railway_electrification.TrainDriverState;
import com.george_vi.electroenergetics.mixin_interfaces.ICEETrainExtension;
import com.simibubi.create.content.contraptions.actors.trainControls.ControlsInputPacket;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Refuses Create's own W/S input from anybody but the train's driver.
 *
 * <h2>Why this is needed on top of the interaction gate</h2>
 *
 * <p>Create's driving keys are not an interaction: once a client believes it holds the
 * controls, it sends {@code ControlsInputPacket} every few ticks, and the vanilla handler
 * only checks that the sender is near the contraption. It does not check WHO is at the
 * controls, because upstream only ever has one player's client in that state. A denied
 * player whose client never learned better - a hand-made packet, a stale predication, a
 * second player with a modified client - could therefore drive the train by sending the
 * packet directly.
 *
 * <p>So the packet is stopped here, on the server, before it can create a control context.
 * This is the authoritative half of the rule. The client-side half (a viewer's cab not
 * sending anything) is a convenience for well-behaved clients; the enforcement belongs
 * where it cannot be skipped.
 *
 * <p>The {@code stopControlling} packet is also gated, and for a stronger reason than the
 * key presses: it clears the controlling player on the contraption entity, so a viewer
 * pressing ESC while looking at a read-only cab would otherwise cancel the REAL driver's
 * controls. From the driver it is a cancel, and this mod treats it as one of the documented
 * cancel routes (see {@link DriverAuthority} for the full list).
 */
@Mixin(value = ControlsInputPacket.class, remap = false)
public class ControlsInputGateMixin {

    @Inject(method = "handle", at = @At("HEAD"), cancellable = true, remap = false)
    private void electroEnergetics$driverOnly(ServerPlayer player, CallbackInfo ci) {
        ControlsInputPacket self = (ControlsInputPacket) (Object) this;

        Entity entity = player.level().getEntity(self.contraptionEntityId());
        if (!(entity instanceof CarriageContraptionEntity cce))
            return;
        Carriage carriage = cce.getCarriage();
        Train train = carriage == null ? null : carriage.train;
        if (train == null || !DriverAuthority.appliesTo(train))
            return;   // not a train this feature owns: upstream behaviour, untouched

        ElectricTrainData data = ((ICEETrainExtension) train).getElectricTrainData();
        TrainDriverState driver = data.driver;
        if (!driver.isDriver(player.getUUID())) {
            // Not the driver. Nothing this packet could ask for is theirs to ask.
            ci.cancel();
            return;
        }

        if (self.stopControlling()) {
            // ESC on the driver's cab. Release the claim here rather than leaving it to the
            // mirror check: the player has explicitly said they are done, and waiting five
            // ticks to agree would leave the cab locked against everybody for that window.
            DriverAuthority.release(train, driver);
            ci.cancel();
        }
    }
}
