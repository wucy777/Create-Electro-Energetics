package com.george_vi.electroenergetics.content.railway_electrification;

import com.george_vi.electroenergetics.config.CEEConfigs;

/**
 * Decides which control scheme drives an electric train, and handles the handover.
 *
 * <h2>The problem this solves</h2>
 *
 * <p>A train can lose its supply at any speed, and the obvious rule - "no working pantograph
 * means Create drives it" - is wrong at speed. Create clamps its <i>target</i> speed, not the
 * train's, and then ramps towards it at an acceleration tuned for a 28 Blocks/Second design
 * ceiling (confirmed by disassembling it: {@code Train.approachTargetSpeed} only ever moves
 * {@code speed} towards {@code targetSpeed}). Handing it a train doing 100 Blocks/Second does not
 * crash anything, but Create would spend a very long time dragging that speed down, and every
 * electric readout would be meaningless in the meantime.
 *
 * <p>So the handover is gated on speed. Until the train is slow enough for Create to hold it,
 * this mod keeps it in {@link TrainControlMode#DEAD_COAST}: no traction, but the brake, the lever
 * and the vigilance clock all still work, and running resistance plus gradient bring the speed
 * down on their own.
 *
 * <h2>Why the brake stays with this mod while coasting</h2>
 *
 * <p>Losing the wire must not lose the brake. On a real train the brake is pneumatic and works
 * whether or not the pantograph is up, and a driver who has just lost their supply is exactly the
 * person who most needs to slow down. So the electric side keeps the brake and the vigilance timer
 * during a coast, and only the tractive effort is gone - {@code isPowered} already handles that.
 *
 * <h2>Nothing follows the train into CREATE</h2>
 *
 * <p>Once {@link TrainControlMode#CREATE} is entered the electric machinery is silent: no lever,
 * no vigilance timer, no electric ceiling, no regeneration. Create behaves exactly as it does for
 * a fuel train, which is what was asked for.
 *
 * <h2>Why this class owns the driver-state edges</h2>
 *
 * <p>There are two pieces of state describing the same fact - {@code data.controlMode} and
 * {@code driver.createOwned} - and they must never disagree, because {@code underGearControl()}
 * answers from the second while the first is what the rest of the code reads. Keeping both
 * transitions in one method is what guarantees that. A caller that changed one and not the other
 * would leave the gear law and Create both writing the train's speed, and it would accelerate at
 * twice the correct rate.
 */
public final class TrainControlModeLogic {

    private TrainControlModeLogic() {}

    /**
     * Recompute the control mode and apply the transition, after a solve has determined whether
     * any pantograph is working.
     *
     * @param data       the train's electric state, updated in place
     * @param trainSpeed current speed in Blocks/Tick; the magnitude is used
     * @param powered    whether the traction is energised this tick. Passed in rather than read from
     *                   {@code data.isPowered}, because that field is written a few lines LATER in
     *                   the same solve - reading it here would decide this tick's mode from last
     *                   tick's supply and delay every handover by a tick.
     */
    public static void update(ElectricTrainData data, double trainSpeed, boolean powered) {
        TrainControlMode mode = decide(data, trainSpeed, powered);

        // Keep the driver state in step. Guarded on the flag rather than applied unconditionally,
        // because retakeFromCreate puts the lever in COAST and doing that every tick would fight
        // the driver for the rest of the journey.
        if (mode == TrainControlMode.CREATE) {
            if (!data.driver.createOwned)
                data.driver.releaseToCreate();
        } else {
            if (data.driver.createOwned)
                data.driver.retakeFromCreate();
        }

        data.controlMode = mode;
    }

    /** The mode this train should be in, given the facts just computed. */
    private static TrainControlMode decide(ElectricTrainData data, double trainSpeed,
                                           boolean powered) {
        // Not an electric train at all: motors without a pantograph, or the reverse. Create's
        // controls, permanently.
        if (!data.hasTractionMotors)
            return TrainControlMode.CREATE;

        // A working pantograph hands the train back to this mod, from whatever state it was in.
        // This is the only route out of CREATE, and it requires the driver to have raised a
        // pantograph onto live wire - there is no automatic raise.
        if (data.hasWorkingPantograph) {
            return powered
                    ? TrainControlMode.ELECTRIC
                    : TrainControlMode.DEAD_COAST;
        }

        // An emergency brake application must FINISH before the train changes hands, and this is
        // tested BEFORE the "already handed over" case below.
        //
        // This is what makes "an emergency brake cannot be interrupted" true rather than merely
        // intended. Without it there was a hole at speed: a driver who pulled the emergency handle
        // and then lost the supply would reach the handover threshold part-way through the stop -
        // the gear law stops running the moment the train is Create's, so Create's own controls
        // would take it and the driver could accelerate away from an emergency brake that was
        // supposed to be unstoppable.
        //
        // Ordered first so the rule has no exceptions. The practical case is a train that was
        // ELECTRIC and lost its wire mid-stop, which is handled either way; placing it above the
        // CREATE branch means a train that is somehow already Create's also finishes the stop,
        // rather than the flag being latched with nothing left to act on it.
        //
        // No supply test is needed: a working pantograph returned ELECTRIC above, so reaching this
        // line means there is none, and a powered stop without a working pantograph is a dead coast.
        if (data.driver.emergencyArmed)
            return TrainControlMode.DEAD_COAST;

        // No working pantograph, and not stopping under the emergency brake. Already handed over:
        // stay there. Re-deciding from the speed alone would snatch the train back the moment it
        // stopped, taking Create's controls away from the driver while they were using them.
        if (data.controlMode == TrainControlMode.CREATE)
            return TrainControlMode.CREATE;

        // Slow enough for Create to hold, so hand it over; otherwise keep it, coasting, until it is.
        return Math.abs(trainSpeed) <= handoverSpeed()
                ? TrainControlMode.CREATE
                : TrainControlMode.DEAD_COAST;
    }

    /**
     * Speed at or below which the train can be handed to Create, in Blocks/Tick.
     *
     * <p>Create's own unpowered ceiling by default, so the receiving scheme is handed a train
     * inside the range its code was written for. Using this mod's own ceiling instead would hand
     * over a train still far faster than Create's acceleration is designed to manage, which is the
     * whole problem this gating exists to avoid.
     *
     * <p>Configurable, because the right figure depends on how the pack is tuned.
     */
    public static double handoverSpeed() {
        return Math.max(0.05d,
                CEEConfigs.server().trainValues.electricTrainHandoverSpeed.getF() / 20d);
    }
}
