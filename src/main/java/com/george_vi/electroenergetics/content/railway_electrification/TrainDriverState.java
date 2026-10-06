package com.george_vi.electroenergetics.content.railway_electrification;

/**
 * Server-side driving state for one electric train, held on
 * {@link ElectricTrainData}.
 *
 * <p>Kept separate from the traction figures so it is obvious what is a command
 * from the driver and what is a computed result. Everything in here is written by
 * the control path and read by the simulation.
 */
public class TrainDriverState {

    /**
     * Seconds between driver-confirmation prompts, matching a real vigilance timer.
     *
     * <p>This is the interval between prompts while the driver IS acknowledging. The
     * warning escalation below starts partway through it rather than after it, which is
     * what makes the timer a warning system rather than a trap: the driver is told the
     * prompt is coming before it becomes overdue.
     */
    public static final int CONFIRM_INTERVAL_TICKS = 30 * 20;

    /**
     * When the amber warning comes on [ticks since the last acknowledgement].
     *
     * <p>The requested escalation: amber from 20 s, red from 30 s, trip at 36 s. The
     * three figures are deliberately tight together - a real vigilance device gives the
     * driver a few seconds of escalating alarm, not a minute - and the amber stage is
     * the long one, which is where a driver who is paying attention notices and acts.
     */
    public static final int WARN_AMBER_TICKS = 20 * 20;

    /** When the warning turns red: the prompt is now overdue [ticks]. */
    public static final int WARN_RED_TICKS = 30 * 20;

    /**
     * When the vigilance device trips: it brakes the train to a stand and drops the
     * lever to the brake [ticks].
     *
     * <p>Note this is a brake application, NOT the emergency brake. Chosen explicitly:
     * a forgotten acknowledgement should stop the train, but it should not also cost
     * the driver the 40 km/h penalty for the rest of the leg, which is what pressing
     * the emergency brake does. Wanting the trip to be recoverable is the whole reason
     * it is not wired to the emergency path.
     */
    public static final int TRIP_TICKS = 36 * 20;

    /** The lever. Defaults to the brake, which is where every failure lands. */
    public TrainGear gear = TrainGear.BRAKE;

    /**
     * The player currently holding this train's controls, or {@code null}.
     *
     * <p>Set once a tick from Create's own control record, so it is the authority on
     * who is driving: it is what the lever packet is checked against, and what
     * decides whether the gear law owns the train's speed at all.
     */
    public java.util.UUID driverId = null;

    /**
     * Ticks remaining in which a driver is considered to be at the controls.
     *
     * <p>A short countdown rather than reading {@link #driverId} directly, so that a
     * player who is momentarily not reported - a chunk unload, a dimension change -
     * does not make the train lurch back into Create's control scheme for a tick or
     * two. A few ticks of grace, and no more.
     */
    public int driverTicks = 0;

    /** True while a driver is at the controls. */
    public boolean isDriven() {
        return driverTicks > 0;
    }

    /**
     * True while a train whose driver has walked away is still run by the gear law.
     *
     * <p>This exists so that leaving the controls does not hand the train back to
     * Create's control scheme. It used to: with no driver the gear law stopped running
     * and Create's passive slowdown took the train, braking it to a halt immediately and
     * with no regeneration, because that path is not an electric brake - it is a plain
     * friction stop that knows nothing about the catenary. An electric train that has
     * its own traction model should be driven by that model whether or not somebody is
     * standing at the desk.
     *
     * <p>The behaviour while unmanned is exactly "the driver is still there but pressing
     * nothing": the lever keeps the position it was left in, nothing new is commanded,
     * and the only thing that changes is that the vigilance clock keeps running. When it
     * reaches {@link #TRIP_TICKS} the train brakes to a stand and parks in the brake
     * position, which is the fail-safe a real train has - and until then another driver
     * can take the controls and carry on, which is the point of leaving the window open.
     *
     * <p>Only ever latched for a train that a player has actually driven (see
     * {@link #wasDriven}), and released when automation takes the train over, so a
     * scheduled train is never captured by this mode.
     */
    public boolean unmanned = false;

    /**
     * Whether a player has ever taken these controls, so that walking away should leave
     * the train unmanned rather than handing it back to Create.
     *
     * <p>The distinguishing test matters: a train that nobody has ever driven - a
     * schedule-driven one, or one placed on the track and left - must NOT become
     * unmanned, or this mode would seize every automated train on the network and hold
     * it in whatever lever position it happened to have.
     */
    public boolean wasDriven = false;

    /**
     * Whether the gear law owns this train's speed.
     *
     * <p>The single question every caller should ask, rather than {@link #isDriven}: a
     * driverless electric train is still this mode's responsibility.
     */
    public boolean underGearControl() {
        return isDriven() || unmanned;
    }

    /** Hand the train back to Create's automation, so this mode stops driving it. */
    public void releaseUnmanned() {
        unmanned = false;
    }

    /** Record who is driving, or {@code null} for nobody. Called once a tick. */
    public void setDriverPresent(java.util.UUID id) {
        driverId = id;
        if (id != null) {
            driverTicks = 5;
            wasDriven = true;
            // Somebody is at the controls again, whether or not it is the same person.
            // Clearing here is what lets a second driver take over inside the window.
            unmanned = false;
        }
    }

    /** Called each train tick, before the motion is integrated. */
    public void tickDriverPresence() {
        if (driverTicks > 0) {
            driverTicks--;
            if (driverTicks == 0) {
                driverId = null;
                // Only on the TRANSITION, not every tick the controls are empty:
                // re-latching unconditionally would make releaseUnmanned a no-op and a
                // scheduled train could never escape this mode.
                if (wasDriven)
                    unmanned = true;
            }
        }
    }

    /**
     * Whether the cab the driver is standing at faces the opposite way along the
     * consist, so its "forward" is the train's -x.
     *
     * <p>This exists because a train can have a cab at each end, and the two cabs
     * command opposite directions. Create already handles this for its own controls
     * - {@code CarriageContraptionEntity.control} computes {@code inverted} from the
     * controls block's FACING against the contraption's initial orientation and then
     * does {@code if (inverted) targetSpeed *= -1} - but the lever does not go through
     * that code path at all, so without this the far cab's lever was mirrored and
     * every position did the opposite of what it said.
     *
     * <p>Written once a tick from the carriage the driver is at the controls of, so it
     * is the same authority as {@link #driverId}. Defaults to false, so a train with no
     * driver - or one whose cab cannot be determined - behaves as it did before.
     */
    public boolean driverCabInverted = false;

    /** Record which way the driving cab faces. Called once a tick with the driver. */
    public void setDriverCabInverted(boolean inverted) {
        driverCabInverted = inverted;
    }

    /** Whether this player is the one driving, so may command the lever. */
    public boolean isDriver(java.util.UUID id) {
        return id != null && id.equals(driverId);
    }

    /**
     * Whether the lever has already been dropped to COAST for a loss of supply, so it is
     * not dropped again every tick.
     *
     * <p>Latched for the same reason as {@link #blockedParked}: a rule that re-asserts
     * itself every tick fights the driver forever. A driver who is unpowered on a descent
     * may deliberately leave the handle at ACCELERATE so the train picks up the moment the
     * wire resumes, and pinning it back to COAST every tick would make that impossible.
     * Dropping it once when the supply goes, and rearming when it comes back, leaves the
     * driver in charge of the handle.
     */
    public boolean powerLossCoasted = false;

    // ------------------------------------------------------------------
    // Automatic station arrival (Create's "hold space" mode)
    // ------------------------------------------------------------------

    /**
     * Whether automatic arrival is in progress.
     *
     * <p>While this is set, Create's navigation owns the target speed instead of the
     * lever, which is the whole point: the driver holds space, picks a station, and the
     * train brakes at the right distance for it. The lever's position is remembered
     * rather than obeyed, because obeying it is the bug this exists to avoid - an
     * ACCELERATE lever would otherwise command traction against the approach and drive
     * straight through the platform.
     */
    public boolean autoArrive = false;

    /** The lever position to restore if the driver cancels before arriving. */
    public TrainGear autoArriveGear = TrainGear.BRAKE;

    /**
     * Ticks since the controls last reported the space key held.
     *
     * <p>A countdown rather than a flag because Create stops calling
     * {@code CarriageContraptionEntity.control} the moment no key is held at all - so
     * there is no "space was released" event to listen for, only the absence of
     * further reports. A short grace, the same idea as {@link #driverTicks}, keeps a
     * dropped packet from cancelling an approach mid-brake.
     */
    public int spaceTicks = 0;

    /** Record that the space key is held, refreshing the grace. Called from control(). */
    public void setSpaceHeld() {
        spaceTicks = 5;
    }

    /** Called each train tick, before the motion is integrated. */
    public void tickSpace() {
        if (spaceTicks > 0)
            spaceTicks--;
    }

    /** Whether the driver appears to be holding space, within the grace period. */
    public boolean isSpaceHeld() {
        return spaceTicks > 0;
    }

    /** Speed cruise is holding, in Blocks/Second. Captured when cruise is selected. */
    public double cruiseSpeed = 0d;

    /** Whether the emergency brake is armed (reverse selected while moving). */
    public boolean emergencyArmed = false;

    /** Whether the emergency brake has been fired. Locks out traction until the next station. */
    public boolean emergencyPenalty = false;

    /**
     * Ticks since the driver last acknowledged, counted up from zero.
     *
     * <p>One counter rather than the previous prompt-plus-grace pair, because the
     * escalation is now purely a function of elapsed time: amber at 20 s, red at 30 s,
     * trip at 36 s. Two counters made "how overdue is this" a question about which of
     * them was active, and the whole point of the escalation is that it is one clock.
     *
     * <p>Acknowledgement resets it to zero, which is what makes pressing early reset
     * the 30 s as well as clear the warning - there is no separate prompt timer left to
     * disagree with it.
     */
    public int confirmWaiting = 0;

    /** Whether the vigilance device has tripped and the train is being stopped. */
    public boolean vigilanceTripped = false;

    /** Ticks the trip brake has been applied, so it can be released if it cannot stop. */
    public int tripTicks = 0;

    /**
     * Whether the lever has already been dropped to the brake because the train ran
     * out of track, so it is not dropped again every tick.
     *
     * <p>This latch exists to avoid the bug that made an earlier version deadlock: a
     * rule that re-asserts the brake every tick while blocked fights the driver
     * forever, and the train can never be reversed off the buffer stop. Parking once
     * and rearming only when the train actually moves leaves the driver free to select
     * reverse and back away.
     */
    public boolean blockedParked = false;

    /**
     * The trip brake is applied for this long, or until the train is at a stand.
     *
     * <p>Longer than it needs to be to stop from line speed, because it is cleared by
     * reaching a stand rather than by the clock running out - the timer is only a
     * backstop for a train that somehow cannot stop. See {@code applyVigilanceTrip}.
     */
    public static final int TRIP_BRAKE_TICKS = 15 * 20;

    /** Ticks of emergency-brake application remaining, while it is slowing the train. */
    public int emergencyTicks = 0;

    /**
     * Speed cap applied while the emergency penalty is in force [Blocks/Second].
     * The penalty lasts until the next station call, so a driver who uses it has
     * to live with a slow train for the rest of that leg.
     */
    public static final double PENALTY_SPEED = 40d / 3.6d;   // 40 km/h

    /** Reset the per-leg state when a scheduled stop is reached. */
    public void clearPenalty() {
        emergencyPenalty = false;
        emergencyArmed = false;
        emergencyTicks = 0;
    }

    /** Drop the lever to the brake and cancel anything in progress. */
    public void failSafe() {
        gear = TrainGear.BRAKE;
        emergencyArmed = false;
    }

    /**
     * The vigilance device tripping: brake the train to a stand and drop the lever.
     *
     * <p>Deliberately not the emergency brake. A forgotten acknowledgement should stop
     * the train, but it should not also cost the driver the 40 km/h penalty until the
     * next station, which is what firing the emergency brake does. The trip is
     * recoverable by design: acknowledge, re-engage a gear, carry on.
     */
    public void trip() {
        failSafe();
        vigilanceTripped = true;
    }

    /** Clear the trip, once the train is at a stand and the driver has acknowledged. */
    public void clearTrip() {
        vigilanceTripped = false;
    }

    /** How overdue the driver is, for the lamps: 0 none, 1 amber, 2 red. */
    public int warningStage() {
        if (vigilanceTripped)
            return 2;
        if (confirmWaiting < WARN_AMBER_TICKS)
            return 0;
        if (confirmWaiting < WARN_RED_TICKS)
            return 1;
        return 2;
    }
}
