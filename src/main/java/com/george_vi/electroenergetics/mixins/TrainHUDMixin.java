package com.george_vi.electroenergetics.mixins;

import com.george_vi.electroenergetics.content.railway_electrification.CabOrientation;
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
     * Makes the experience-bar direction arrow follow the train, not the S key.
     *
     * <p>Create decides that arrow with {@code reversing =
     * ControlsHandler.currentlyPressed.contains(1)}, and 1 is the S key. On an
     * electric train S does nothing, so the arrow flipped whenever the driver
     * happened to press S - an indicator pointing backwards while the train was
     * plainly going forwards.
     *
     * <p>What it follows instead, in order:
     * <ol>
     *   <li>the direction the train is actually moving, so a braked train rolling
     *       backwards on a descent shows that rather than the lever's position;</li>
     *   <li>failing that - at a standstill - the reverse position of the lever, which
     *       is the only lever position that commands the opposite direction.</li>
     * </ol>
     * The first point is what makes the arrow trustworthy: it reports travel, and the
     * brake having no direction of its own is exactly why reading the lever for it was
     * wrong.
     *
     * <p>{@code train.speed} is used rather than anything this mod syncs, because
     * Create already replicates it - see Create's Train#write/read and the fact that
     * the speed readout on the same HUD uses it. So the direction costs no extra
     * traffic, which rule 0 asks about.
     *
     * <p>The field is read three times in that method (lines 190, 192, 193) and only
     * the first is the direction test, so the injection is pinned by ORDINAL 0 rather
     * than by target alone. That leaves the two steering reads alone, which is what
     * keeps the A/D slant - the junction indication the driver needs - working
     * untouched.
     *
     * <p>Only for electric trains: a fuel train's arrow keeps following its own keys.
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
        return reversingView(original, isReversing(train));
    }

    /**
     * Whether the cab should be shown as travelling in reverse, which is relative to
     * the CAB the driver is at, not to the train.
     *
     * <p>This is the same quantity Create's own arrow derives from the S key, so it has
     * to be expressed the same way: "the train is going the way this cab does not
     * face". For a double-ended train the two cabs face opposite ways, so the same
     * motion is forwards at one and backwards at the other, and reading train.speed
     * alone would point the arrow the wrong way at one end.
     *
     * <p>Motion wins over the lever, and the lever only speaks once the train has
     * stopped. A train sliding backwards under the brake is genuinely moving backwards
     * and the arrow should say so; conversely the brake and coast positions command no
     * direction at all, so at rest they must not claim one.
     */
    private static boolean isReversing(Train train) {
        // Movement decides when there is any; the lever decides at a stand.
        if (train.speed > SPEED_DEADBAND || train.speed < -SPEED_DEADBAND) {
            boolean movingNegative = train.speed < -SPEED_DEADBAND;
            // "reversing" means travelling against the way this cab faces, so it is
            // true when the sign of the motion disagrees with the cab's forward sign.
            return movingNegative != cabInverted();
        }
        TrainHudData.GearState gear = TrainHudData.gear(train.id);
        // At a standstill only REVERSE commands a direction, and it commands the
        // direction opposite the cab - which is what "backwards" means to the driver.
        return gear != null && gear.gear() == TrainGear.REVERSE.ordinal();
    }

    /**
     * Whether the cab the local player holds faces against the contraption.
     *
     * <p>Worked out locally rather than synced: the client already knows which controls
     * block its own player is holding - {@code ControlsHandler} keeps it for the input
     * path - and that is exactly the cab this HUD belongs to. So the arrow needs no
     * extra packet, and cannot be shown for a cab the player is not at.
     */
    private static boolean cabInverted() {
        // Narrowed to OrientedContraptionEntity because that is where
        // getInitialOrientation lives; a carriage contraption always is one, and any
        // other kind is not a train cab at all, so falling back to false is right.
        if (!(ControlsHandler.getContraption()
                instanceof com.simibubi.create.content.contraptions.OrientedContraptionEntity oriented))
            return false;
        return CabOrientation.isInverted(oriented, ControlsHandler.getControlsPos());
    }

    /** {@code train.speed} is a fraction of top speed; this is a slow creep. */
    private static final double SPEED_DEADBAND = 1e-4d;

    /**
     * A view of {@code current} that reports "reversing" (key index 1) exactly when
     * asked to, leaving every other entry - the steering keys - alone.
     */
    private static Collection<Integer> reversingView(Collection<Integer> current, boolean reversing) {
        boolean saysReversing = current.contains(REVERSE_KEY_INDEX);
        if (reversing == saysReversing)
            return current;
        if (!reversing)
            return current.stream().filter(i -> i != REVERSE_KEY_INDEX).toList();
        List<Integer> withReverse = new java.util.ArrayList<>(current);
        withReverse.add(REVERSE_KEY_INDEX);
        return withReverse;
    }

    /**
     * Index of the S key in Create's control list, which is what the direction arrow
     * tests. See {@code ControlsUtil.getControls}: W, S, A, D, jump, shift.
     */
    private static final int REVERSE_KEY_INDEX = 1;

    /** The train whose controls the local player holds, or {@code null}. */
    private static Train drivenTrain() {
        if (!(ControlsHandler.getContraption() instanceof CarriageContraptionEntity cce))
            return null;
        Carriage carriage = cce.getCarriage();
        return carriage == null ? null : carriage.train;
    }
}
