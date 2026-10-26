package com.george_vi.electroenergetics.content.railway_electrification;

import com.george_vi.electroenergetics.CEEPantographTypes;
import com.george_vi.electroenergetics.content.railway_electrification.pantograph.TrainPantographEntry;
import com.george_vi.electroenergetics.foundation.nodes.AttachedNode;
import com.george_vi.electroenergetics.simulation.infrastructure.WireSimulationState;

import java.util.ArrayList;
import java.util.List;

public class ElectricTrainData {
    public List<TrainPantographEntry> pantographs = new ArrayList<>();
    public int accumulators = 0;
    public double accumulatorCharge = 0d;
    public double accumulatorChargeVoltage = 0d;
    public double accumulatorActualVoltage = 0d;
    public boolean hasCreativeSource = false;
    public boolean isPowered = false;

    /**
     * Whether this consist is an electric train at all.
     *
     * <p>Two conditions, both structural: at least one traction motor AND at least one
     * pantograph, which may sit on different carriages. A set with motors but no way to
     * collect current is not an electric train in any useful sense - it can never be
     * energised - so it keeps Create's controls rather than showing a lever that could
     * never do anything. The same applies in reverse.
     *
     * <p>This is what decides that a train is an <i>electric</i> train, as opposed to a
     * fuel train. It is deliberately not {@link #isPowered}: a set that has run off the
     * end of the catenary is still an electric train. Which control scheme is actually
     * driving it is a separate and faster-moving question - see {@link #controlMode}.
     *
     * <p>Latched once true, since a consist does not lose its motors or its pantographs
     * without being taken apart and reassembled.
     */
    public boolean hasTractionMotors = false;

    /**
     * Whether at least one pantograph is BOTH raised by the driver AND within reach of
     * catenary right now.
     *
     * <p>This is the condition for the train being able to collect current at all. It is
     * recomputed from geometry every solve, because the answer changes as the train moves:
     * entering a neutral section makes it false without anything the driver did.
     */
    public boolean hasWorkingPantograph = false;

    /**
     * The driver's AUTOMATIC-RAISE switch, which restores upstream's behaviour while it is on.
     *
     * <p>Off by default, and that default is the feature: how many pantographs are up is the driver's
     * call, and the only writer of a raise was the driver's packet. This switch is the one deliberate
     * exception, and it is a switch the driver holds.
     *
     * <p>It exists because a rigid carriage on a curve cannot keep its pantograph under the wire.
     * The operator's track is a compact loop: heading into a bend the arm loses the contact, the
     * automatic lowering fires, and by the time the train is straight again the speed has already
     * fallen. Selective manual raising cannot fix that - it is not a decision problem, it is a
     * frequency problem - so upstream's "raise whenever there is wire in reach" is the right
     * behaviour for that track, and the driver should be able to ask for it.
     *
     * <p>When on, the raise condition is exactly upstream's: wire in reach, no further test. The
     * automatic LOWERING that is already kept then completes the pair, so on a bendy line the arm
     * rides the wire instead of dropping off it. A manual lowering while the switch is on does not
     * stick - the next solve raises it again, which is what "automatic" means and is why the switch is
     * the thing the driver turns off.
     *
     * <p>Deliberately NOT persisted per train: it is an operating habit, not a property of the
     * consist, and a train reassembled should not inherit a mode nobody chose again. See the config
     * default for the default.
     */
    public boolean autoRaisePantographs = false;

    /**
     * What {@link #autoRaisePantographs} was when the driver was last sent a sync.
     *
     * <p>Exists so the sender can tell "the switch just moved" from "nothing happened", and send
     * immediately on the former. Same purpose as {@code lastSyncedControlsLocked}: a driver who
     * pressed a key is looking at the panel, and a quarter-second of disagreement reads as a broken
     * key rather than as latency.
     */
    public boolean lastSyncedAutoRaisePantographs = false;

    /**
     * Which control scheme is driving this train right now.
     *
     * <p>Recomputed each solve. The distinction that matters is between the two states
     * where this mod owns the train ({@link TrainControlMode#ELECTRIC} and
     * {@link TrainControlMode#DEAD_COAST}) and {@link TrainControlMode#CREATE}, where
     * nothing of this mod's driving remains.
     */
    public TrainControlMode controlMode = TrainControlMode.CREATE;

    /**
     * Whether this mod's traction model owns the train's speed this tick.
     *
     * <p>True in both of this mod's states. It is deliberately NOT {@code isPowered}: in
     * {@link TrainControlMode#DEAD_COAST} there is no tractive effort but the brake and
     * the vigilance clock are still this mod's, because losing the wire must not also
     * lose the brake.
     *
     * <p>The single question the driving path should ask, so that the several places
     * which gate on it cannot disagree.
     */
    public boolean underElectricControl() {
        return hasTractionMotors && !railShoeOnly() && controlMode != TrainControlMode.CREATE;
    }

    /**
     * Whether this train collects current through a RAIL CONTACT SHOE and nothing else.
     *
     * <p>Such a train keeps upstream's behaviour entirely: Create's W/S drive it, its speed bar is
     * Create's, and none of this mod's lever, vigilance clock, traction model or ceiling apply. That
     * is the operator's ruling, and the reasoning is that the two systems are not the same vehicle:
     * "弓就是动车，靴就是地铁" - a pantograph is a mainline train, a third-rail shoe is a metro. A metro
     * does not need a 350 km/h traction model, and imposing one on it would change how an upstream
     * feature behaves for anyone who builds one.
     *
     * <p>Computed from the pantograph list rather than stored as a flag, so it cannot drift: a
     * carriage added, removed or reassembled changes the answer on the next solve with no maintenance.
     * A list is shoe-only when it is non-empty and EVERY entry is a rail shoe; an empty list is not
     * this case at all (that train has no collector, so it is not an electric train and is handled by
     * {@link #hasTractionMotors} being false).
     *
     * <p>Mixed consists resolve to the LEVER: "有受电弓就算我的". The operator does not build them - a
     * train is either an EMU or a metro - but one had to be chosen, and a train carrying a pantograph
     * is a mainline train with an odd extra collector, not a metro.
     */
    public boolean railShoeOnly() {
        if (pantographs.isEmpty())
            return false;
        for (TrainPantographEntry entry : pantographs) {
            if (entry.type != CEEPantographTypes.RAIL_CONTACT_SHOE.get())
                return false;
        }
        return true;
    }

    /**
     * Whether a lever position can be selected at all right now.
     *
     * <p>The three positions that command traction - ACCELERATE, CRUISE and REVERSE - need power
     * to mean anything. With no supply the tractive effort computes to exactly zero (the supply
     * scaling in {@code TrainTractionModel.powerScaleForVoltage} returns 0 below the minimum
     * voltage, and the effort is multiplied by it), so selecting one would leave the driver holding
     * a position that commands traction and gets none - a lever reading "accelerate" while the
     * train coasts. Refusing the selection is the honest answer.
     *
     * <p>BRAKE and COAST are always available, and that is deliberate. The brake is friction plus,
     * at most, the motor brake, and the friction part is pneumatic on a real train - it works
     * whether or not the pantograph is up, which is precisely when a driver most needs it. COAST is
     * the position that commands nothing, so it can never be wrong.
     *
     * <p>This is the single authority for the rule. The packet handler that receives a lever move
     * and the panel that draws the positions both ask it, so a position cannot be offered in the
     * cab and then refused by the server - which would show the driver their handle moving and then
     * snapping back.
     */
    public boolean gearAvailable(TrainGear gear) {
        // Over capacity the train is CAPPED, not immobilised. Refusing accelerate here locked the lever out
        // entirely: from a standstill there is no position that starts the train, so "run slowly" became
        // "cannot depart" - and it fired on a driver whose own seat entry had been pruned. The ceiling is
        // enforced by RiderSeatGate.clamp and by the brake in the tick, which is where a speed limit
        // belongs; availability is about whether the position is legal at all.
        if (gear.appliesTraction())
            return isPowered || hasCreativeSource;
        return true;
    }

    /**
     * Whether the emergency brake may be TRIGGERED.
     *
     * <p>In this mod the emergency brake is NOT a separate air brake. It is reached through REVERSE
     * and stops the train by driving the motors in reverse against the direction of travel, so it is
     * a motor brake and triggering it needs the supply - the operator's point.
     *
     * <p>Note carefully that this governs TRIGGERING ONLY. Once pulled, an emergency application
     * runs to a stand whatever happens to the supply: the motor share of the braking is scaled by
     * the supply, so a train that loses its wire part-way through the stop finishes on friction.
     * Nothing may cancel the application, which is why no power test is placed around it.
     */
    public boolean emergencyBrakeAvailable() {
        return isPowered || hasCreativeSource;
    }

    public double lastVoltage = 0d;

    public double lastSpeed;

    // Speed the traction model can currently sustain, in Blocks/Second.
    // Already accounts for running resistance, gradient and the supply.
    public float maxSpeed;

    // Acceleration the traction can currently deliver, in Blocks/Second².
    // Tapers with speed because the effort is power-limited.
    public float availableAcceleration;

    // Gradient along the direction of travel (rise / run). Positive = uphill.
    public double trackGrade = 0d;

    // Electrical power actually being drawn by the traction [W]. Reported on
    // the driver's HUD.
    public double displayPower = 0d;

    public AttachedNode trainNode;
    public AttachedNode groundNode;
    public WireSimulationState connectedWireState;
    public WireSimulationState.WireCutHandle wireCutHandle;

    // Total current draw for display on ammeters attached to train contraptions
    public double displayCurrent = 0;

    // Power the motors pushed back into the line while braking [W]. Zero unless
    // the train is actually slowing under the brake, and reported on the HUD.
    public double regenPower = 0d;

    // Whether the brake is being held this tick. Set by the drive path's braking
    // branch and read by the next circuit build, which is the same one-tick lag
    // the load resistance already has.
    public boolean braking = false;

    // The driver's lever, vigilance timer and emergency state. Never null, so the
    // control path and the packet handler do not have to null-check it.
    public final TrainDriverState driver = new TrainDriverState();

    // What cruise decided this tick, for the display.
    public TrainTractionModel.CruiseState cruiseState = TrainTractionModel.CruiseState.STOPPED;

    // Fraction of its rating the supply can currently deliver, from the terminal
    // voltage of the last solve. Kept here because the gear law runs on the train's
    // own tick while the voltage is only known after the circuit solves, so this is
    // necessarily the previous tick's figure - the same one-tick lag the load
    // resistance already has.
    public double powerScale = 1d;

    // Scratch space for the gear law's result, reused every tick so the driving path
    // allocates nothing. Read by the circuit build on the following tick to work out
    // how much of the braking the motors are doing and therefore what can be
    // regenerated; pads make heat and recover nothing.
    public final GearStep gearStep = new GearStep();

    // Packet throttling for gauge data sync
    public double lastSyncedVoltage = 0;
    public double lastSyncedCurrent = 0;
    public int ticksSinceGaugeSync = 0;

    /**
     * Ticks since the pantograph and control-lock state was last sent to the driver.
     *
     * <p>Separate from {@link #ticksSinceGaugeSync} because the two travel on different paths: the
     * gauge sync only happens while the train is connected, and the lock state must reach the
     * driver precisely when it is NOT - a stop finished on friction, with the pantographs down.
     */
    public int ticksSincePantographSync = 0;

    /**
     * The lock state last sent, so a change can be delivered immediately.
     *
     * <p>Without this the driver would learn the controls are locked up to five ticks late, which
     * is long enough to press a key and see nothing happen - the exact confusion the lock's
     * message exists to prevent.
     */
    public boolean lastSyncedControlsLocked = false;
}
