package com.george_vi.electroenergetics.mixins;

import com.george_vi.electroenergetics.content.railway_electrification.ElectricManualSpeed;
import com.george_vi.electroenergetics.content.railway_electrification.TrainHudData;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.simibubi.create.content.contraptions.actors.trainControls.ControlsHandler;
import com.simibubi.create.content.trains.TrainHUD;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps Create's experience-bar speed readout in step with the speed a manually
 * driven electric train can actually reach, and takes the speed wheel away from
 * its driver.
 *
 * <p>Create sizes that bar from
 * {@code |speed| / (maxSpeed() * manualTrainSpeedModifier)}. The server-side
 * override in {@code CarriageContraptionEntityMixin} drops the 0.75 handicap for
 * electric trains, so without the first injection the bar would fill at three
 * quarters of the speed the train is really doing. Both sides now share one
 * factor.
 *
 * <p>The train is looked up the same way Create's own {@code getCarriage()} does,
 * rather than captured from a local, so the injection does not depend on a local
 * variable table that another mixin could shift.
 */
@Mixin(TrainHUD.class)
public class TrainHUDMixin {

    @ModifyExpressionValue(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/createmod/catnip/config/ConfigBase$ConfigFloat;getF()F"),
            remap = false)
    private static float electroEnergetics$electricTrainSpeedBarScale(float original) {
        return ElectricManualSpeed.speedBarFactor(drivenTrain(), original);
    }

    /**
     * Takes the speed wheel away from an electric train's driver.
     *
     * <p>An electric train is driven by the lever, and the wheel is Create's
     * throttle control: leaving it live would let the same train be commanded two
     * ways at once, and it draws a throttle bar that no longer corresponds to
     * anything the driver can see. Fuel trains are untouched.
     *
     * <p>Suppressed at the source rather than by ignoring the value downstream,
     * because {@code onScroll} is also what sends the throttle packet to the
     * server. Wrapping the return to false stops the edit AND the packet, so an
     * electric train's throttle field is never written and no traffic is spent on
     * it.
     */
    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private static void electroEnergetics$noSpeedWheelOnElectricTrains(double delta,
                                                                        CallbackInfoReturnable<Boolean> cir) {
        Train train = drivenTrain();
        if (train == null)
            return;
        // Tested on the consist carrying traction motors, not on it being powered
        // right now. An electric train that has run off the end of the catenary, or
        // whose accumulator is flat, is still an electric train: it should not
        // suddenly grow a working throttle wheel because its supply dropped, and
        // then lose it again when the pantograph picks up. The same shared test the
        // lever panel uses, so the two can never disagree about which train this is.
        if (TrainHudData.leverDriven(train.id))
            cir.setReturnValue(false);
    }

    /** The train whose controls the local player holds, or {@code null}. */
    private static Train drivenTrain() {
        if (!(ControlsHandler.getContraption() instanceof CarriageContraptionEntity cce))
            return null;
        Carriage carriage = cce.getCarriage();
        return carriage == null ? null : carriage.train;
    }
}
