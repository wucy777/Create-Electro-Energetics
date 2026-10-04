package com.george_vi.electroenergetics.mixins;

import com.george_vi.electroenergetics.content.railway_electrification.ElectricManualSpeed;
import com.george_vi.electroenergetics.content.railway_electrification.TrainGear;
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

import java.util.Collection;
import java.util.List;

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
    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true, remap = false)
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

    /**
     * Makes the experience-bar direction arrow follow the lever instead of S.
     *
     * <p>Create decides that arrow with {@code reversing =
     * ControlsHandler.currentlyPressed.contains(1)}, and 1 is the S key. On an
     * electric train S does nothing, so the arrow flipped whenever the driver
     * happened to press S - an indicator pointing backwards while the train was
     * plainly going forwards, which is the "arrow in the middle of the experience
     * bar" that gives the game away.
     *
     * <p>The field is read three times in that method (lines 190, 192, 193) and only
     * the first is the direction test, so the injection is pinned by ORDINAL 0 rather
     * than by target alone. Without that it would also rewrite the steering-key reads
     * that follow, and hitching the arrow to the lever would break the steering
     * indication on the same HUD.
     *
     * <p>Only for electric trains: a fuel train's arrow keeps following its own keys,
     * and its Motion-based handle is untouched.
     */
    @ModifyExpressionValue(
            method = "renderOverlay",
            at = @At(value = "FIELD",
                    target = "Lcom/simibubi/create/content/contraptions/actors/trainControls/ControlsHandler;currentlyPressed:Ljava/util/Collection;",
                    ordinal = 0),
            remap = false)
    private static Collection<Integer> electroEnergetics$leverDrivesDirectionArrow(Collection<Integer> original) {
        Train train = drivenTrain();
        if (train == null || !TrainHudData.leverDriven(train.id))
            return original;
        TrainHudData.GearState gear = TrainHudData.gear(train.id);
        if (gear == null)
            return original;
        // Hand Create a view that reports "reversing" exactly when the lever does,
        // leaving everything else in the collection alone.
        boolean reversing = gear.gear() == TrainGear.REVERSE.ordinal();
        if (!reversing) {
            if (!original.contains(1))
                return original;
            return original.stream().filter(i -> i != 1).toList();
        }
        if (original.contains(1))
            return original;
        List<Integer> withReverse = new java.util.ArrayList<>(original);
        withReverse.add(1);
        return withReverse;
    }

    /** The train whose controls the local player holds, or {@code null}. */
    private static Train drivenTrain() {
        if (!(ControlsHandler.getContraption() instanceof CarriageContraptionEntity cce))
            return null;
        Carriage carriage = cce.getCarriage();
        return carriage == null ? null : carriage.train;
    }
}
