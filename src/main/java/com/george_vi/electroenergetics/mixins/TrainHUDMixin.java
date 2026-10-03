package com.george_vi.electroenergetics.mixins;

import com.george_vi.electroenergetics.content.railway_electrification.ElectricManualSpeed;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.simibubi.create.content.contraptions.actors.trainControls.ControlsHandler;
import com.simibubi.create.content.trains.TrainHUD;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Keeps Create's experience-bar speed readout in step with the speed a manually
 * driven electric train can actually reach.
 *
 * <p>Create sizes that bar from
 * {@code |speed| / (maxSpeed() * manualTrainSpeedModifier)}. The server-side
 * override in {@code CarriageContraptionEntityMixin} drops the 0.75 handicap for
 * electric trains, so without this the bar would fill at three quarters of the
 * speed the train is really doing. Both sides now share one factor.
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

    /** The train whose controls the local player holds, or {@code null}. */
    private static Train drivenTrain() {
        if (!(ControlsHandler.getContraption() instanceof CarriageContraptionEntity cce))
            return null;
        Carriage carriage = cce.getCarriage();
        return carriage == null ? null : carriage.train;
    }
}
