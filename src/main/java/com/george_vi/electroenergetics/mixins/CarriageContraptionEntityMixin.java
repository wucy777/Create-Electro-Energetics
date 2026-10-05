package com.george_vi.electroenergetics.mixins;

import com.george_vi.electroenergetics.content.railway_electrification.CabOrientation;
import com.george_vi.electroenergetics.content.railway_electrification.ElectricManualSpeed;
import com.george_vi.electroenergetics.content.railway_electrification.ElectricTrainData;
import com.george_vi.electroenergetics.mixin_interfaces.ICEETrainExtension;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
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
        data.driver.setDriverCabInverted(CabOrientation.isInverted(self, controlsLocalPos));
    }
}
