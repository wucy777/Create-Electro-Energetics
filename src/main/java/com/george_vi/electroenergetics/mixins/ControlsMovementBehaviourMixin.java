package com.george_vi.electroenergetics.mixins;

import com.george_vi.electroenergetics.content.railway_electrification.TrainGear;
import com.george_vi.electroenergetics.content.railway_electrification.TrainHudData;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Makes the cab's speed handle follow the lever instead of W/S, on electric trains.
 *
 * <p>Create animates that handle from the measured motion of the carriage:
 * {@code angles.speed.chase(context.motion.length())}. So the handle moves when the
 * train moves, which in practice means it moves when W or S is held - and on an
 * electric train W/S do nothing at all, so the handle twitches at a control the
 * driver is not using while the lever that IS driving the train is invisible.
 *
 * <p>Replaced with the lever position, mapped so the handle's travel matches the
 * lever's: accelerating at one end, reverse at the other, and the handle resting
 * between brake and power-off for the neutral positions. The steering handle is
 * deliberately left alone - A/D still steer, and Create's own reading of those keys
 * is already correct.
 *
 * <p>Only for electric trains. A fuel train's handle keeps following its motion, so
 * its cab behaves exactly as it did.
 */
@Mixin(value = com.simibubi.create.content.contraptions.actors.trainControls.ControlsMovementBehaviour.class,
        remap = false)
public class ControlsMovementBehaviourMixin {

    /**
     * Handle travel, as a fraction of the lever's range: the value Create chases is
     * clamped to +/-0.5 in its own code, so these are chosen on the same scale.
     *
     * <p>ACCELERATE is positive because the handle leans forward under power, and
     * REVERSE is negative because it leans back. COAST sits just below the brake so
     * the neutral positions are distinguishable at a glance on the dial.
     */
    private static float electroEnergetics$handleFor(TrainGear gear) {
        return switch (gear) {
            case ACCELERATE -> 0.5f;
            case CRUISE -> 0.3f;
            case COAST -> -0.05f;
            case BRAKE -> -0.2f;
            case REVERSE -> -0.5f;
        };
    }

    /**
     * Redirect the motion measurement feeding the speed handle.
     *
     * <p>Targets the {@code Vec3.length()} call rather than the chaser, because that
     * call is where the carriage's motion enters the animation and replacing its
     * result leaves Create's own chaser, easing and clamping in charge. Returning a
     * constant from here means the handle eases to the lever position exactly as
     * smoothly as it used to ease to the motion value.
     */
    @Redirect(
            method = "renderInContraption",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/phys/Vec3;length()D"),
            remap = false)
    private double electroEnergetics$leverHandlePosition(net.minecraft.world.phys.Vec3 motion) {
        // The driving train is looked up from the synced lever state rather than
        // from the render context, which is not reachable from an injection handler.
        // That is also the only source available on the client, where this renders.
        Train train = electroEnergetics$drivenElectricTrain();
        if (train == null)
            return motion.length();

        TrainHudData.GearState gear = TrainHudData.gear(train.id);
        if (gear == null)
            return motion.length();

        TrainGear[] values = TrainGear.values();
        int index = gear.gear();
        if (index < 0 || index >= values.length)
            return motion.length();

        return electroEnergetics$handleFor(values[index]);
    }

    /** The electric train whose lever is being shown, or null to leave Create alone. */
    private static Train electroEnergetics$drivenElectricTrain() {
        if (!(com.simibubi.create.content.contraptions.actors.trainControls.ControlsHandler
                .getContraption() instanceof CarriageContraptionEntity cce))
            return null;
        com.simibubi.create.content.trains.entity.Carriage carriage = cce.getCarriage();
        if (carriage == null)
            return null;
        Train train = carriage.train;
        if (train == null)
            return null;
        return TrainHudData.leverDriven(train.id) ? train : null;
    }
}
