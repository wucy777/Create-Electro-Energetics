package com.george_vi.electroenergetics.mixins;

import com.george_vi.electroenergetics.content.railway_electrification.CabOrientation;
import com.george_vi.electroenergetics.content.railway_electrification.ElectricManualSpeed;
import com.george_vi.electroenergetics.content.railway_electrification.ElectricTrainData;
import com.george_vi.electroenergetics.mixin_interfaces.ICEETrainExtension;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
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
     * Carry a seated player without the collision test, on a train faster than Create's design.
     *
     * <h2>The defect this fixes</h2>
     *
     * <p>Create positions riders through {@code AbstractContraptionEntity.positionRider}, which hands
     * the target position to a {@code MoveFunction}. Vanilla's is collision-aware - that is what makes
     * a passenger slide along a wall instead of through it, and on an ordinary contraption moving a
     * Block or two a tick it is exactly right.
     *
     * <p>At this mod's speeds it is not. A carriage at 100 Blocks/Second advances 5 Blocks per tick
     * against a player box 0.6 x 1.8 x 0.6, so a 5-Block step resolves as "you have run into
     * something" and the move is clipped or refused. Measured on a live session at 80 Blocks/Second:
     * rider displacement pinned at 0.90 Blocks per tick while the carriage moved 4.03, and then, on the
     * tick the seat was left, a 16.08 Block jump as the accumulated deficit settled at once. That is
     * the throw. The same clipping lets a player walk through the floor and walls at speed, and makes
     * jumping appear to drift backwards: own velocity 4-5 Blocks/s against a train's 60-80.
     *
     * <h2>What this does</h2>
     *
     * <p>Replaces the callback with a direct {@code setPos}, for an electric train only and only for
     * the seated player. The destination comes from {@code contraption.getSeatOf}, so the rider is
     * inside the carriage by construction - there is nothing for a collision test to resolve except
     * the carriage's own boxes, which is what it kept hitting.
     *
     * <p>Applied to the callback argument rather than to the resulting position, so ordinary Create
     * trains keep vanilla behaviour unchanged.
     *
     * <p>{@code hasTractionMotors} rather than {@code underElectricControl}: the carry must stay rigid
     * while the train is coasting or handed to Create as well, because those are the states in which a
     * player is most likely aboard at speed. A fuel train has no motors and is untouched.
     */
    @ModifyVariable(
            method = "positionRider(Lnet/minecraft/world/entity/Entity;"
                    + "Lnet/minecraft/world/entity/Entity$MoveFunction;)V",
            at = @At("HEAD"),
            argsOnly = true,
            index = 2,
            remap = false)
    private Entity.MoveFunction electroEnergetics$carryWithoutCollision(
            Entity.MoveFunction original,
            Entity passenger,
            CarriageContraptionEntity self) {
        if (!(passenger instanceof Player))
            return original;
        Carriage carriage = self.getCarriage();
        if (carriage == null || carriage.train == null)
            return original;
        ElectricTrainData data = ((ICEETrainExtension) carriage.train).getElectricTrainData();
        if (data == null || !data.hasTractionMotors)
            return original;
        return (entity, x, y, z) -> entity.setPos(x, y, z);
    }
}
