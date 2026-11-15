package com.george_vi.electroenergetics.mixins;

import com.george_vi.electroenergetics.content.railway_electrification.CabOrientation;
import com.george_vi.electroenergetics.content.railway_electrification.ElectricManualSpeed;
import com.george_vi.electroenergetics.content.railway_electrification.ElectricTrainData;
import com.george_vi.electroenergetics.content.railway_electrification.TrainTractionModel;
import com.george_vi.electroenergetics.mixin_interfaces.ICEETrainExtension;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.station.GlobalStation;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;

/**
 * Lets a manually driven electric train reach the speed its traction model
 * actually allows, instead of Create's fuel-train handicap.
 *
 * <p>This is the server-side half: {@code control()} computes
 * {@code topSpeed = maxSpeed() * manualTrainSpeedModifier} and feeds it to
 * {@code targetSpeed}. Leaving the factor at 0.75 capped a 100 m/s set at
 * 75 m/s. See {@link ElectricManualSpeed}.
 *
 * <p>The call site is inside the branch that has already returned on the client,
 * so this only ever runs on the server.
 */
@Mixin(CarriageContraptionEntity.class)
public class CarriageContraptionEntityMixin {

    @ModifyExpressionValue(
            method = "control",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/createmod/catnip/config/ConfigBase$ConfigFloat;getF()F"),
            remap = false)
    private float electroEnergetics$electricTrainManualTopSpeed(float original) {
        Carriage carriage = ((CarriageContraptionEntity) (Object) this).getCarriage();
        return ElectricManualSpeed.topSpeedFactor(carriage == null ? null : carriage.train, original);
    }

    /**
     * Record which way the cab the driver is holding faces, once a tick.
     *
     * <p>{@code control()} is the right hook because it is the one method that knows
     * BOTH the driving player and the exact controls block they are standing at, and
     * the server calls it every tick while the controls are held. Create computes the
     * same value here as a local and uses it to flip its own {@code targetSpeed}; the
     * lever does not go through that path, so it has to be told separately.
     *
     * <p>Recorded rather than passed down because the gear law runs on the train's own
     * tick, which is not this call: by the time the law runs, the controls block
     * position is long out of scope. Storing it on the train's driver state also keys
     * it to the same driver identity the lever packets are checked against.
     *
     * <p>Only for a consist with traction motors, so a fuel train's state is never
     * touched. {@code control()} already returns early on the client and for an invalid
     * driver, so a value is only ever written for someone genuinely at the controls.
     */
    @Inject(method = "control", at = @At("HEAD"), remap = false)
    private void electroEnergetics$recordCabOrientation(BlockPos controlsLocalPos,
                                                       Collection<Integer> heldControls,
                                                       Player player,
                                                       CallbackInfoReturnable<Boolean> cir) {
        CarriageContraptionEntity self = (CarriageContraptionEntity) (Object) this;
        Carriage carriage = self.getCarriage();
        if (carriage == null || carriage.train == null)
            return;
        ElectricTrainData data = ((ICEETrainExtension) carriage.train).getElectricTrainData();
        if (data == null || !data.hasTractionMotors)
            return;
        // Only while this mod actually owns the train. Once it has been handed to Create there is no
        // lever of ours to orient, and Create computes the cab's direction for its own controls.
        if (!data.underElectricControl())
            return;
        data.driver.setDriverCabInverted(CabOrientation.isInverted(self, controlsLocalPos));

        // Track the space key for automatic station arrival.
        //
        // Recorded here because this is the only place that sees the held keys, and it
        // is refreshed every tick the driver holds anything at all. Index 4 is jump,
        // which the controls block reads as "take me to the next station" - the button
        // Create's own HUD calls the automatic arrival control.
        //
        // A countdown rather than a flag: Create stops calling this method entirely once
        // no key is held, so the release of space is only ever visible as the absence of
        // further reports, and there is no event to catch. See TrainDriverState.tickSpace.
        if (heldControls.contains(4))
            data.driver.setSpaceHeld();
    }

    /**
     * THE APPROACH GATE - one verdict, one moment, for both what the driver SEES and what the train
     * DOES. Create offers "Hold SPACE to approach ..." for any station its scan finds, however
     * close, and deciding the offer here (mid-tick, at the same instant as the display) instead of
     * at ServerTickEvent.Post is what makes the operator's guarantee hold: a shown prompt plus a
     * held key ends in an arrival, because arming happened on the very numbers that drew the
     * prompt. The old split - the display here, the arming a tick later at Post - let an
     * accelerating train outgrow its braking distance between the two evaluations: the prompt
     * showed, the hold was refused, the lever (left at ACCELERATE) drove on, and the front
     * listener's stop at the platform looked exactly like the speed assignment it replaced.
     *
     * <p>Stoppable (x >= v^2/(2 a_eff), the drag the operator asked for inside a_eff): the prompt
     * shows AND the approach arms here - no warning exists on this path, which is the operator's
     * ruling. Not stoppable: no prompt (a promise the train cannot keep), one warning per press
     * with the numbers, and the lever keeps the train; driving in anyway ends in the front
     * listener's stop AT the platform - the dispatcher's remote stop (GameEvents parks the train
     * there). Once armed, this gate stands down: the approach owns the speed and the prompt keeps
     * showing for what it already controls. Fuel trains and unowned trains keep Create's prompt
     * exactly as it was.
     */
    @Inject(method = "displayApproachStationMessage", at = @At("HEAD"), remap = false, cancellable = true)
    private void electroEnergetics$approachGate(Player player, GlobalStation station,
                                                CallbackInfo ci) {
        CarriageContraptionEntity self = (CarriageContraptionEntity) (Object) this;
        Carriage carriage = self.getCarriage();
        if (carriage == null || carriage.train == null || carriage.train.navigation == null)
            return;
        Train train = carriage.train;
        ElectricTrainData data = ((ICEETrainExtension) train).getElectricTrainData();
        if (data == null || !data.underElectricControl() || data.driver == null)
            return;
        if (train.navigation.destination != station)
            return;
        if (data.driver.autoArrive)
            return;                          // engaged: the servo owns the speed, the prompt just shows
        double vMs = Math.abs(train.speed) * 20d;
        double gradeTravel = train.speed < 0 ? -data.trackGrade : data.trackGrade;
        double needed = TrainTractionModel.stoppingDistance(vMs, gradeTravel, train.carriages.size());
        if (train.navigation.distanceToDestination >= needed) {
            data.driver.arrivalRefused = false;
            data.driver.autoArrive = true;
            // Remember what the driver had selected, so cancelling can restore it.
            data.driver.autoArriveGear = data.driver.gear;
            return;
        }
        ci.cancel();                         // no prompt where the approach cannot be honoured
        if (!data.driver.arrivalRefused) {
            data.driver.arrivalRefused = true;
            player.displayClientMessage(Component.translatable(
                    "electroenergetics.train.arrival.tooClose",
                    String.format("%.0f", vMs * 3.6d),
                    (int) Math.ceil(needed),
                    (int) Math.floor(Math.max(train.navigation.distanceToDestination, 0d))), true);
        }
    }
}
