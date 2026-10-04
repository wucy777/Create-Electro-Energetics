package com.george_vi.electroenergetics.mixins;

import com.george_vi.electroenergetics.content.railway_electrification.ElectricTrainData;
import com.george_vi.electroenergetics.mixin_interfaces.ICEETrainExtension;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;

/**
 * Records that a driver is at the controls of an electric train.
 *
 * <p>This is the heartbeat that lets the lever own the train. {@code control()}
 * runs every tick while someone holds the controls, so it is the natural place to
 * note their presence; {@code TrainDriverState} then counts that presence down on
 * its own when they step away, rather than needing a matching "driver left" event
 * that might never arrive (a disconnect or a chunk unload would strand the train).
 *
 * <p>Deliberately does <b>not</b> cancel the call. Steering, the station prompts and
 * the coupler prompts all live in {@code control()} and a lever-driven train still
 * needs them. Only the speed authority moves: the speed such a train obeys is
 * written by {@code gearAcceleration} through {@code tickPassiveSlowdown}, and
 * {@code approachTargetSpeed} - where held keys and the speed wheel would otherwise
 * set it - returns early for a driven electric train. So W/S and the wheel stop
 * affecting speed while everything else about the controls keeps working.
 */
@Mixin(CarriageContraptionEntity.class)
public class CarriageContraptionEntityControlMixin {

    @Inject(method = "control", at = @At("HEAD"), remap = false)
    private void electroEnergetics$markDriverPresent(net.minecraft.core.BlockPos controlsLocalPos,
                                                     Collection<Integer> heldControls,
                                                     net.minecraft.world.entity.player.Player player,
                                                     CallbackInfoReturnable<Boolean> cir) {
        CarriageContraptionEntity self = (CarriageContraptionEntity) (Object) this;
        if (self.level().isClientSide)
            return;   // the server owns the lever; the client only draws it

        Carriage carriage = self.getCarriage();
        if (carriage == null)
            return;
        Train train = carriage.train;
        if (train == null)
            return;

        ElectricTrainData data = ((ICEETrainExtension) train).getElectricTrainData();
        // Keyed on the consist carrying traction motors rather than on it being
        // energised this instant. An electric train that has run off the end of the
        // catenary is still an electric train, and it still needs its lever, its
        // brake and its vigilance timer; keying this on the supply would hand it
        // back to Create's controls, with W/S suddenly live again, the moment the
        // voltage dipped. Fuel trains never set this flag, so they are untouched.
        if (data.hasTractionMotors)
            data.driver.markDriverPresent();
    }
}
