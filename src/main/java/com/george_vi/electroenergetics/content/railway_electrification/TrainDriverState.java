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

    /**
     * The same three moments as SECONDS, which is what they are measured in.
     *
     * <p>The tick constants above stay for the default-rate path and for anything that reads them directly, but the
     * device itself now counts real time. The operator's rule: 35 seconds of inattention is 35 seconds whether the
     * server is running 20 ticks a second or 200. Counting ticks meant a rate of 200 tripped the device in 3.6
     * seconds - a safety timer that fires early is worse than none, because the driver cannot learn its rhythm.
     */
    public static final double WARN_AMBER_SECONDS = 20d;

    /** Red at this many seconds of inattention. */
    public static final double WARN_RED_SECONDS = 30d;

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

    /** The trip, in seconds. See {@link #WARN_AMBER_SECONDS}. */
    public static final double TRIP_SECONDS = 36d;

    /** The lever. Defaults to the brake, which is where every failure lands. */
    public TrainGear gear = TrainGear.BRAKE;

    /**
     * The player who OWNS this train's controls, or {@code null} when the train is free.
     *
     * <p>This is the authority for the one-driver rule, and it is written by exactly two
     * places: {@link #claim} when somebody right-clicks a controls block and the train is
     * free, and {@link #releaseClaim} on the four documented cancel routes (see
     * {@link com.george_vi.electroenergetics.content.railway_electrification.DriverAuthority}).
     * Nothing derives it from Create's own control record: that record is only a MIRROR,
     * kept so Create's own W/S path keeps working, and a player who is not this owner is
     * never allowed to become Create's controlling player in the first place.
     *
     * <p>A claim is not the same thing as {@link #driverId}. {@code driverId} is "who is
     * present at the controls this tick", refreshed once a tick from the claim (or from
     * Create's record on a train this feature does not gate) and allowed to lapse for a
     * few ticks; this field is "who is ALLOWED to be there", and it changes only when
     * somebody interacts. Every control packet is checked against this field, so the
     * moment a driver cancels, their next packet is already refused.
     */
    public java.util.UUID claimOwner = null;

    /**
     * The controls block the claim was taken at, in that carriage's local space, or
     * {@code null} when there is no claim.
     *
     * <p>Recorded because the claim is about a DESK, not just a train: the cancel rule is
     * "right-click a controls block of this train again", and the automatic release rule is
     * "the driver is no longer within reach of this desk". Both need the position, and it
     * is deliberately not re-read from Create - Create stores only the player, and the
     * block a driver is standing at must not change under them.
     */
    public net.minecraft.core.BlockPos driverDesk = null;

    /**
     * Index into {@code train.carriages} of the carriage holding {@link #driverDesk}, or
     * {@code -1} when there is no claim.
     *
     * <p>Stored as an index because that is how the carriage is named by every other part
     * of this code (pantograph sync, seat gate), and because the carriage object itself is
     * not stable across a reassembly - the index is revalidated each tick, and a claim whose
     * carriage no longer exists is released.
     */
    public int driverCarriage = -1;

    /**
     * Consecutive ticks on which the claim could not be corroborated, so a momentary frame
     * does not release a driver.
     *
     * <p>The checks behind it are: the player is still within reach of the desk, the
     * claimed carriage still has a present entity, and Create's mirror record still says
     * this player is at the controls. Any of those can flicker for a tick (a contraption
     * entity being replaced, a position packet landing late), and dropping the claim on a
     * flicker would take the controls away from a driver who did nothing wrong. Five ticks
     * of continuous disagreement is the line; a real cancel (ESC, sneak, walking away)
     * obviously persists past it.
     */
    public int claimInvalidTicks = 0;

    /**
     * The player currently holding this train's controls, or {@code null}.
     *
     * <p>"Holding" in the physical sense: set once a tick, from {@link #claimOwner} on a
     * train this feature gates (see {@code DriverAuthority.appliesTo}) and from Create's
     * own control record on any other train. It decides whether the gear law owns the
     * train's speed at all, and it is what the HUD sync addresses. It is NOT the
     * permission check - packets are checked against {@link #claimOwner}, which does not
     * lag a tick.
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

    /**
     * Take the controls of this train, at the desk the player right-clicked.
     *
     * <p>Callers are the server-side controls-block interaction. The claim and the
     * driver presence are set together, so a packet arriving in the same tick as the
     * right-click already sees the new owner. Nothing here touches Create's record: the
     * caller lets Create's own interaction run afterwards, which is what makes Create's
     * W/S path work for the driver in CREATE mode.
     */
    public void claim(java.util.UUID player, int carriageIndex, net.minecraft.core.BlockPos desk) {
        claimOwner = player;
        driverDesk = desk;
        driverCarriage = carriageIndex;
        claimInvalidTicks = 0;
        setDriverPresent(player);
    }

    /**
     * Give up the claim, on any of the documented cancel routes.
     *
     * <p>Deliberately does NOT clear {@link #driverTicks}: that countdown is the grace that
     * keeps the gear law running for a few ticks after the driver leaves, and it is what
     * lets the train fall into {@link #unmanned} (the lever keeps its position, the
     * vigilance clock keeps running) instead of being dropped to Create's friction brake.
     * It does clear {@link #driverId}, so the very next control packet from the ex-driver
     * is refused.
     *
     * <p>Does not message anybody and does not touch Create's record: those are the
     * caller's job, because only the caller knows whether Create's record needs stopping
     * (it does when the driver released from their own cab, it does not when Create already
     * let go) and whether the player is still there to be told.
     */
    public void releaseClaim() {
        claimOwner = null;
        driverDesk = null;
        driverCarriage = -1;
        claimInvalidTicks = 0;
        driverId = null;
    }

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
     *
     * <p>False once the train has been handed to Create ({@link #createOwned}). This is checked
     * here, in the one place, rather than at each of the dozen call sites that ask this question -
     * so that "Create is driving now" cannot be half-applied. A site that missed the check would
     * keep writing the train's speed from the electric law while Create also wrote it, and the
     * train would accelerate at twice the correct rate. That exact doubling bug has happened in
     * this file before, when a second writer was added without gating it.
     */
    public boolean underGearControl() {
        if (createOwned)
            return false;
        return isDriven() || unmanned;
    }

    /**
     * Whether this train has been handed to Create's own controls and nothing of this mod's
     * driving applies.
     *
     * <p>Written only by {@link #releaseToCreate()} and {@link #retakeFromCreate()}, which are
     * called on the two edges of the control-mode transition, so it always agrees with
     * {@code ElectricTrainData.controlMode}.
     */
    public boolean createOwned = false;

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

    /**
     * Whether this player owns this train's controls, so may command the lever and the
     * pantographs.
     *
     * <p>Checked against the CLAIM, not against {@link #driverId}. The claim is the
     * server's authority and changes the instant a driver cancels, while {@code driverId}
     * deliberately lags a few ticks so the gear law can survive a momentary absence. A
     * permission check must not inherit that lag: a player who has just right-clicked a
     * desk to release it must not be able to move the lever on the way out.
     *
     * <p>The {@link #driverId} fallback exists for trains this feature does not gate (no
     * traction motors, or a rail-shoe-only metro). Those keep upstream's behaviour in
     * full, including the old rule that the player Create records as controlling may work
     * the pantographs; they never take a claim, so the fallback is the only path they
     * reach. On a gated train the claim is non-null for every player who may control, and
     * a released claim clears {@code driverId} on the same line, so the fallback can never
     * resurrect an ex-driver.
     */
    public boolean isDriver(java.util.UUID id) {
        if (id == null)
            return false;
        if (claimOwner != null)
            return id.equals(claimOwner);
        return id.equals(driverId);
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

    /**
     * The speed cruise is holding, in Blocks/Second, SIGNED in the driving cab's frame.
     *
     * <p>Positive is the normal case: the train was already running the way the ACCELERATE
     * position drives, so cruise holds that speed in that direction. Negative means the notch
     * was taken while the train was running the other way. That is not a speed this position
     * can hold - cruise holds speed in the direction it drives - so it is a direction mismatch
     * which the law has to brake away; see the CRUISE branch of
     * {@code TrainTractionModel.gearAcceleration}.
     *
     * <p>Stored SIGNED on purpose, and never through {@code Math.abs}. The magnitude used to be
     * taken here and compared with the magnitude of the live speed in the law, so a train
     * reversing at 5 m/s "exactly matched" a captured 5 m/s: cruise concluded there was nothing
     * to do, the train went on reversing at a constant speed, and the force the hold branch used
     * to keep that speed constant was the motor brake - so the regeneration readout said the
     * motors were recovering energy while the train neither slowed nor stopped. The sign is what
     * lets the law tell "hold 5 forwards" from "was doing 5 backwards when the notch was taken".
     */
    public double cruiseSpeed = 0d;

    /**
     * The lever position the gear law ran with on the previous tick.
     *
     * <p>Used only to notice the move INTO cruise, so the setpoint can be captured from the speed
     * the train actually has at that moment. It is captured here, in the law, because this is the
     * one place that has both the speed the law will use and the cab frame it is expressed in; a
     * packet arrives between ticks and cannot know which cab is driving. The packet's own capture
     * (and the capacity rule's) therefore only fill in a provisional value, which this overwrites
     * on the tick the lever arrives at cruise - so every route into the position ends up with the
     * same signed figure, from the same rule.
     *
     * <p>Not persisted: a reload leaves the lever in the brake, so there is no cruise setpoint to
     * preserve either.
     */
    public TrainGear leverAtLastLaw = TrainGear.BRAKE;

    /**
     * Capture the speed cruise is to hold, from a speed already expressed in the driving cab's
     * frame [Blocks/Second].
     *
     * <p>Every writer goes through here so the sign convention cannot drift between the driver's
     * packet, the capacity rule and the gear law. The value is signed: see {@link #cruiseSpeed}.
     */
    public void captureCruiseSetpoint(double cabFrameSpeedBpt) {
        cruiseSpeed = Double.isFinite(cabFrameSpeedBpt) ? cabFrameSpeedBpt : 0d;
    }

    /**
     * Capture the speed cruise is to hold from the train's own (world-frame) speed
     * [Blocks/Second].
     *
     * <p>The lever works in the driver's cab frame, so an inverted cab mirrors the figure - the
     * same mirror the gear law applies to the speed it drives with. Callers that already hold a
     * cab-frame speed (the gear law) use {@link #captureCruiseSetpoint} instead.
     */
    public void captureCruiseSetpointFromWorld(double worldSpeedBpt) {
        captureCruiseSetpoint(worldSpeedBpt * (driverCabInverted ? -1d : 1d));
    }

    /**
     * Whether the emergency brake is IN FORCE.
     *
     * <p>Latched, and deliberately not cancellable by anything except the train reaching a stand.
     * Once the driver pulls it the train stops, whether or not there is still power: the emergency
     * brake is a decision that has been made, not a request that can be withdrawn by circumstance.
     *
     * <p>It is also NOT released by a loss of supply, which is the case that matters. What changes
     * without power is only HOW the braking is produced - the motors can no longer be driven
     * against the direction of travel, so the train finishes the stop on friction alone. See
     * {@code TrainTractionModel.gearAcceleration}, where the motor share is scaled by the supply.
     */
    public boolean emergencyArmed = false;

    /**
     * Whether the post-emergency speed cap is in force: 40 km/h until the next station.
     *
     * <p>This is the penalty for using the emergency brake, and it applies in BOTH control schemes.
     * It has to: once the train has stopped it is handed to Create (there is no pantograph up, so
     * the speed gate has certainly been met), and a penalty that vanished at the handover would not
     * be a penalty at all. Create's own driving is capped by {@code Train.maxSpeed()} returning
     * this figure while the flag is set.
     *
     * <p>It also blocks raising a pantograph. The operator's rule: having used the emergency brake,
     * the driver may not bring the motors back to finish the stop with, and may not collect current
     * again until the next station.
     */
    public boolean emergencyPenalty = false;

    /**
     * Set once this train has been seen AT a station while carrying the speed cap.
     *
     * <p>This exists because "the cap clears at the next station" cannot be tested by asking whether
     * {@code currentStation} is non-null right now. That value is transient: Create clears it the
     * moment the train departs (`Train.leaveStation`, called from `approachTargetSpeed`), so a
     * per-tick test only ever sees it during the dwell. Worse, there is a way to make it never come
     * back at all: relocating the train with Create's wrench calls `Train.leaveStation()` AND
     * `navigation.cancelNavigation()`, and arriving at a station requires a destination
     * (`Train.frontSignalListener` returns early when `navigation.destination != station`). A
     * free-driven train has no way to obtain a destination again, so after a relocation the cap could
     * never clear - permanently, since it is persisted across reloads.
     *
     * <p>So the event to record is "a station was reached", latched, rather than "a station is being
     * stood at", sampled. The latch is set the first time the cap and a station coincide, and cleared
     * only when the cap itself is cleared or re-armed.
     *
     * <p>Not persisted. It is derived state that can only ever be set by an event occurring in the
     * current session; a cap restored from NBT is cleared by the same station test as a fresh one,
     * because the station test does not depend on this flag's history.
     */
    public boolean penaltyStationReached = false;

    /**
     * Distance run, in Blocks, while the speed cap was in force.
     *
     * <p>Only advanced while {@link #emergencyPenalty} is set and the train is moving. Cleared with
     * the cap.
     *
     * <p>This is the cap's backstop, and it exists because the station test alone had a hole that the
     * operator found in play: relocating a capped train with Create's wrench clears both
     * {@code currentStation} and {@code navigation.destination}, and reaching a station requires a
     * destination - so a free-driven train could carry the cap with no way to ever satisfy the test,
     * permanently, since the cap is persisted.
     *
     * <p>Distance rather than elapsed time on purpose: the punishment is "the rest of this leg at
     * 40 km/h", and a train standing in a yard has not travelled a leg. A timer would forgive it for
     * standing still, which is not the behaviour being punished.
     */
    public double penaltyDistance = 0d;

    /**
     * Distance after which the speed cap clears regardless of stations [Blocks].
     *
     * <p>Long enough to be a real punishment and to never pre-empt the ordinary rule, short enough
     * that a player who has been stranded by it notices it lifting rather than giving up.
     *
     * <p>At the cap itself (11.1 Blocks/s) 10 000 Blocks is 900 s, fifteen minutes of running. An
     * ordinary leg between stations is a fraction of that, so in normal play the station test always
     * fires first; this only ever decides the case where it cannot.
     */
    public static final double PENALTY_BACKSTOP_BLOCKS = 10_000d;

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

    /**
     * Ticks of emergency-brake application remaining.
     *
     * <p>No longer a timer that ends the application. It is kept only as a safety backstop for a
     * train that somehow cannot stop at all, and the application itself runs until the train is at
     * a stand - see {@link #EMERGENCY_BACKSTOP_TICKS}.
     */
    public int emergencyTicks = 0;

    /**
     * The backstop that gives up on an emergency brake which cannot stop the train.
     *
     * <p>Long enough that reaching it means something is genuinely wrong - a train sliding down a
     * gradient the friction brake cannot hold, for instance. It exists for the same reason the
     * vigilance trip has one: a rule that latches forever on a train that cannot satisfy it would
     * leave that train unusable, and there would be no way for the driver to recover it.
     *
     * <p>The previous value was 100 ticks (5 seconds), which was NOT a backstop - it was the actual
     * duration, and it released the brake long before a train at line speed had stopped. A 100 m/s
     * train needs roughly 40 seconds at the emergency rate.
     */
    public static final int EMERGENCY_BACKSTOP_TICKS = 90 * 20;

    /**
     * Speed cap applied while the emergency penalty is in force [Blocks/Second].
     * The penalty lasts until the next station call, so a driver who uses it has
     * to live with a slow train for the rest of that leg.
     */
    public static final double PENALTY_SPEED = 40d / 3.6d;   // 40 km/h

    /**
     * Ticks the panel stays locked after an emergency stop has finished [ticks].
     *
     * <p>This is the whole of the lockout's duration, and separating it from the speed penalty is
     * deliberate - see {@link #releaseLockout()}. Two seconds is long enough that the driver cannot
     * notch the lever while the train is still settling, and short enough that nobody reads the inert
     * panel as a broken mod.
     */
    public static final int LOCKOUT_TAIL_TICKS = 2 * 20;

    /**
     * Release the panel lock and the "no second emergency application" latch, keeping the speed
     * penalty.
     *
     * <p>THE LOCK AND THE SPEED CAP ARE TWO DIFFERENT PENALTIES and used to be cleared together by
     * {@link #clearPenalty()}, whose only gate is {@code train.getCurrentStation() != null}. That gate
     * is unreachable for a train assembled away from any station and never given a schedule:
     * {@code currentStation} is written only by assembly at a station ({@code StationBlockEntity:888})
     * and by {@code Train.arriveAt}, which needs a schedule destination - and a schedule can itself
     * only be applied at a station ({@code applyAutoSchedule} requires {@code currentStation ==
     * station}). The two routes need each other, so neither can happen.
     *
     * <p>The result was a permanently inert panel. Not an immobilised train - Create's own W/S still
     * drove it - but a driver pressing keys at a panel that never answers, whose only way out was to
     * reload the world. That is the worst kind of bug: silent, and with a workaround nobody would
     * guess.
     *
     * <p>The fix keeps the punitive half and bounds the operational half:
     * <ul>
     *   <li><b>Speed cap stays</b> until the next station, exactly as before. It costs the driver
     *       something real - the rest of the leg at 40 km/h - and it needs no interaction, so it
     *       cannot become a stuck panel.</li>
     *   <li><b>The lock lifts</b> a short moment after the train has come to a stand. It exists to
     *       guarantee the emergency stop runs to completion without the driver interfering, and that
     *       requirement ends when the train stops.</li>
     * </ul>
     *
     * <p>A driver cannot farm this: pulling the emergency brake again only stops the train again and
     * re-locks the panel for another {@link #LOCKOUT_TAIL_TICKS}, while the speed cap is untouched by
     * either action - {@link #emergencyPenalty} is still cleared only by {@link #clearPenalty()}.
     */
    public void releaseLockout() {
        controlsLocked = false;
        emergencyArmed = false;
        lockoutTailTicks = 0;
    }

    /**
     * Ticks elapsed since the emergency stop finished, counted only while the panel is locked.
     *
     * <p>Counted rather than stored as a deadline so it needs no world clock and cannot be affected
     * by a paused server. Reset whenever the lock is off, so the next application starts fresh.
     */
    public int lockoutTailTicks = 0;

    /**
     * Reset the per-leg state when a scheduled stop is reached.
     *
     * <p>Deliberately does NOT clear {@link #emergencyArmed}. Reaching a station is not the same
     * event as coming to a stand under the emergency brake, and the operator's rule is that an
     * emergency application runs until the train has stopped and cannot be cut short. Clearing the
     * flag here would have been a way to interrupt it: arrive at a platform while the stop is still
     * in progress and the brake would be released. The application is released by one thing only -
     * the standstill test in GameEvents.
     *
     * <p>This now clears only the SPEED penalty. The panel lock is released by
     * {@link #releaseLockout()} when the stop finishes, because tying it to a station made it
     * permanent for any train that never reaches one.
     */
    public void clearPenalty() {
        emergencyPenalty = false;
        emergencyTicks = 0;
        penaltyStationReached = false;
        penaltyDistance = 0d;
    }

    /**
     * Whether the driver is barred from ALL controls while an emergency stop is running, and briefly
     * after it finishes.
     *
     * <p>Set when the emergency brake is used, and it covers everything: the lever positions and
     * the pantographs. The operator's flow is that a triggered emergency brake runs to a stand
     * without accepting any input at all, so this is not merely "no traction" - the driver's whole
     * panel is inert until the stop has finished and control has passed to Create.
     *
     * <p>For the lever the lockout is enforced inside {@code SetTrainGearPacket}, which refuses
     * every command while {@code emergencyArmed}. For the pantographs it is this flag, which also
     * has to outlive the brake application: it stays set past the end of the brake, so that a driver
     * whose supply failed part-way through the stop cannot raise a pantograph and finish the stop on
     * the motors, which is exactly what the rule forbids.
     *
     * <p>BOTH places that arm the emergency brake set this flag: the emergency button, and throwing
     * the lever into reverse at speed. Until they did, this paragraph claimed the two flags "are set
     * together, so the brake cannot be running while the panel is live" - which was true for the
     * lever, whose lockout reads {@code emergencyArmed}, and false for the pantographs, whose lockout
     * reads only this flag. Slam the handle into reverse at speed and a pantograph could still be
     * raised mid-stop.
     *
     * <p>Released by {@link #releaseLockout()} a short {@link #LOCKOUT_TAIL_TICKS} after the stop has
     * finished, NOT at the next station. It used to be released only by {@link #clearPenalty()}, whose
     * gate a train can never satisfy if it was assembled away from any station and has no schedule -
     * which left the panel dead forever. The lock exists to protect the stop, and that requirement
     * ends when the train stands; the speed penalty is what lasts to the next station.
     */
    public boolean controlsLocked = false;

    /**
     * Drop the lever to the brake and cancel anything in progress - EXCEPT the emergency brake.
     *
     * <p>The emergency application is deliberately left alone. This method is called by the
     * vigilance trip and by the automatic-arrival finish, and clearing {@link #emergencyArmed} here
     * meant either of those could cancel an emergency stop that was still in progress - a lapsed
     * vigilance clock would silently release the very brake the driver had pulled. The operator's
     * rule is that a triggered emergency brake runs until the train is at a stand and is not
     * cancellable, so nothing but reaching a stand (or the backstop) may clear it.
     */
    public void failSafe() {
        gear = TrainGear.BRAKE;
    }

    /**
     * Latched once while the train is being held to a stand because somebody aboard is on their feet.
     *
     * <p>Only guards the message, not the brake: the brake is reapplied every tick the condition holds, so
     * a driver who moves the lever back to a traction position while the train is still too fast is simply
     * overruled until it is slow enough. A standstill message repeated twenty times a second would be noise,
     * which is what this latch prevents.
     */
    public boolean riderBrakeNotified = false;

    /**
     * True while there are more players aboard than there are seats for them.
     *
     * <p>The only thing this caps: while it holds, the train may not exceed Create's ceiling and the
     * accelerate position is refused. It is a capacity limit, not a "somebody is standing" penalty - a
     * player on their feet below the ceiling is free to walk around, which is the whole point of seating
     * them in the first place.
     */
    public boolean overloaded = false;

    /**
     * Ticks the train has been seen with somebody standing while there are seats to give them.
     *
     * <p>Seating needs a round trip: the server asks each standing player which seat they are nearest, the
     * client answers, and the answer is applied on a later tick. Judging overload on the very first tick that
     * somebody is standing therefore reports "overloaded" and applies the ceiling brake for people who are
     * about to be seated anyway - reported as "the train has plenty of empty seats but it complains about
     * overloading and brakes to 28, then seats me".
     *
     * <p>So the verdict waits {@link RiderSeatGate#SEAT_INTENT_GRACE_TICKS} ticks. Seating is attempted on
     * every one of those ticks; only a standing player who is still standing after the grace is overload.
     */
    public int unseatedTicks = 0;

    /**
     * The position the lever was in when the capacity limit forced it down to CRUISE, or null if it did not.
     *
     * <p>At the ceiling the accelerate position is dropped to cruise - the train holds the limit instead of
     * pushing against it. When the overload clears, the drop is lifted: the lever goes back to what it was
     * if the driver has not moved it in the meantime, and if they have, their choice stands.
     */
    public TrainGear gearBeforeOverloadDrop = null;

    /**
     * True while the capacity rule is braking the train down to the ceiling.
     *
     * <p>Without this the brake was indistinguishable from one the driver had applied, so nothing released
     * it when the speed reached the ceiling: the train braked past 28 and went on to a stand. A limit that
     * stops the train is not a limit.
     */
    public boolean overloadBraking = false;

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
        // Time, not ticks - see WARN_AMBER_SECONDS. The lamps have to share the trip's clock or the escalation is
        // wrong at every rate but 20: at 200 ticks a second the amber lamp would have become red instantly.
        double tps = com.george_vi.electroenergetics.content.railway_electrification.RiderSeatGate
                .ticksPerSecond();
        if (confirmWaiting < WARN_AMBER_SECONDS * tps)
            return 0;
        if (confirmWaiting < WARN_RED_SECONDS * tps)
            return 1;
        return 2;
    }

    /**
     * Hand the train to Create, discarding everything of this mod's driving.
     *
     * <p>Called on the one-way transition into {@code TrainControlMode.CREATE}. The user's
     * instruction is explicit that the electric machinery must not follow the train into that
     * state: Create behaves as it always has, with no lever, no vigilance timer and no electric
     * ceiling imposed on it.
     *
     * <p>So this releases the train rather than freezing it. {@link #unmanned} is the wrong thing
     * to leave set, because it means "this mod still drives a train whose driver walked away" and
     * would keep the gear law running. {@link #wasDriven} is cleared with it so a later re-raise
     * starts clean rather than latching unmanned on the first tick nobody is at the controls.
     *
     * <p>The vigilance clock is zeroed rather than left mid-count, so that if a pantograph is
     * later raised the driver gets a full window instead of inheriting a countdown that ran while
     * Create was in charge and they had no way to acknowledge it.
     */
    public void releaseToCreate() {
        createOwned = true;
        unmanned = false;
        wasDriven = false;
        confirmWaiting = 0;
        vigilanceTripped = false;
        tripTicks = 0;
        // emergencyArmed is deliberately NOT cleared here. This runs on the transition into
        // CREATE, which for an emergency stop happens the moment the train reaches a stand - and
        // clearing the flag there would race the very code that lowers the pantographs on the same
        // transition, leaving a powered train with its pantograph still up and therefore not
        // actually handed over. The emergency release is owned solely by the standstill test in
        // GameEvents, so there is one writer and no ordering to get wrong.
        autoArrive = false;
        spaceTicks = 0;
        powerLossCoasted = false;
        blockedParked = false;
    }

    /**
     * Take the train back from Create, leaving the lever where it should resume.
     *
     * <p>Called when a pantograph is raised again onto live wire. The lever goes to
     * {@link TrainGear#COAST} rather than resuming its previous position: the driver has been
     * driving under Create's W/S controls, so the old position is stale and could command traction
     * the instant control returns. COAST does nothing until the driver asks for something, which
     * is the safe way to resume - and it is what the user described, as engaging the one clutch
     * while the other is cut.
     */
    public void retakeFromCreate() {
        createOwned = false;
        gear = TrainGear.COAST;
        confirmWaiting = 0;
        vigilanceTripped = false;
        tripTicks = 0;
        emergencyArmed = false;
    }
}
