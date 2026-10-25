package com.george_vi.electroenergetics.content.railway_electrification;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side cache of the per-train traction state the driver's HUD needs.
 *
 * <p>The electrical simulation only runs on the server, so the client has no
 * way to know a train's modelled ceiling, power draw or gradient on its own.
 * The server pushes them with {@code SyncTrainGaugeDataPacket} and this holds
 * the latest copy for {@link com.george_vi.electroenergetics.client.ElectricTrainHud}
 * to read while rendering, and for the speed-bar fix in {@code TrainMixin}.
 *
 * <p>Deliberately free of any client-only types so it is safe to load on a
 * dedicated server (where it simply stays empty).
 */
public final class TrainHudData {

    /**
     * One update's worth of traction state for a train.
     *
     * @param maxSpeed         modelled ceiling against resistance and gradient,
     *                         before Create's manual-driving factor [Blocks/Second]
     * @param power            electrical power being drawn [W]
     * @param voltage          catenary voltage at the train [V]
     * @param grade            gradient along travel, positive uphill
     * @param powered          whether the traction is energised
     * @param carriages        carriage count of the consist
     * @param motorCars        how many of those carry a traction motor
     * @param powerPerCarriage rated traction power of one carriage [W]
     * @param manualFullSpeed  whether the server has waived Create's manual-driving
     *                         speed handicap for this train. Carried explicitly
     *                         because it comes from a server-side config the
     *                         client cannot read, and the speed bar has to use
     *                         the same factor the train is actually driven with.
     * @param designMaxSpeed   the ceiling this consist is ALLOWED, independent of
     *                         whether it has supply right now. Distinct from
     *                         {@code maxSpeed}, which is what it can currently
     *                         sustain against gradient and supply.
     */
    public record Sample(float maxSpeed, float power, float voltage, double grade,
                         boolean powered, int carriages, int motorCars,
                         float powerPerCarriage, boolean manualFullSpeed,
                         float designMaxSpeed, int controlMode) {}

    private static final Map<UUID, Sample> SAMPLES = new ConcurrentHashMap<>();

    /**
     * Lever and driver state, kept apart from {@link Sample} because it changes on
     * a different schedule: the traction figures come from the electrical solve,
     * while the lever only moves when the driver does something. Merging them
     * would mean re-sending the whole traction block on every lever nudge.
     *
     * @param gear             lever position, a {@code TrainGear} ordinal
     * @param confirmDue       the vigilance prompt is waiting; the driver must press
     * @param vigilanceStage   0 none, 1 amber, 2 red - how overdue the acknowledgement
     *                         is. Sent rather than derived from the other flags because
     *                         the escalation is the whole point of the warning: an
     *                         amber lamp and a red one mean different things and the
     *                         client cannot work out which from a boolean.
     * @param emergencyArmed   the emergency brake is available
     * @param emergencyPenalty the speed cap from a previous emergency use is in force
     * @param cruiseState      what cruise is doing, a {@code CruiseState} ordinal
     * @param regen            the motors are feeding the line right now
     * @param autoArrive       Create's navigation is running an automatic station arrival
     * @param unmanned         the driver has left the controls and the clock is running
     */
    public record GearState(int gear, boolean confirmDue, int vigilanceStage,
                            boolean emergencyArmed, boolean emergencyPenalty,
                            int cruiseState, boolean regen,
                            boolean autoArrive, boolean unmanned) {}

    private static final Map<UUID, GearState> GEARS = new ConcurrentHashMap<>();

    private TrainHudData() {}

    public static void update(UUID trainId, float maxSpeed, float power, float voltage,
                              double grade, boolean powered, int carriages, int motorCars,
                              float powerPerCarriage, boolean manualFullSpeed,
                              float designMaxSpeed, int controlMode) {
        SAMPLES.put(trainId, new Sample(maxSpeed, power, voltage, grade, powered,
                carriages, motorCars, powerPerCarriage, manualFullSpeed, designMaxSpeed,
                controlMode));
    }

    /**
     * Whether this train is currently driven by this mod rather than handed to Create.
     *
     * <p>Sent over the wire because the client cannot work it out: the decision depends on whether
     * a pantograph is within reach of catenary, which is server-side geometry the client never
     * sees. A client that guessed would show the electric panel to a driver whose train Create is
     * actually driving, and their lever keys would do nothing.
     */
    public static boolean electricControl(UUID trainId) {
        Sample s = SAMPLES.get(trainId);
        return s != null && s.controlMode() != TrainControlMode.CREATE.ordinal();
    }

    /**
     * Whether the emergency brake can be used right now.
     *
     * <p>Read from the synced sample so the cab and the server agree: the emergency brake is a
     * driven-motor brake in this mod, so it needs the supply, and the client cannot determine the
     * supply itself.
     */
    public static boolean emergencyBrakeAvailable(UUID trainId) {
        Sample s = SAMPLES.get(trainId);
        return s != null && s.powered();
    }

    /**
     * Whether a lever position may be selected, judged from the synced supply.
     *
     * <p>Mirrors {@code ElectricTrainData.gearAvailable} on the client side. Both exist because the
     * server is the authority while the panel must not offer a position that would be refused.
     */
    public static boolean gearAvailable(UUID trainId, TrainGear gear) {
        // Over capacity, the accelerate position is refused on the SERVER, so the panel must not offer it:
        // a lever that moves and then snaps back reads as a broken key. Mirrors ElectricTrainData exactly.
        if (gear == TrainGear.ACCELERATE && OVERLOADED.contains(trainId))
            return false;
        if (!gear.appliesTraction())
            return true;
        Sample s = SAMPLES.get(trainId);
        return s != null && s.powered();
    }

    /**
     * Whether the server last reported this train as having a supply.
     *
     * <p>The server's own verdict, not a client-side guess: it already folds in the creative source
     * and the minimum-voltage test, neither of which the client can evaluate.
     *
     * <p>False when no sample has arrived, which is the conservative direction - a train whose
     * supply state is unknown is not claimed to be powered.
     */
    public static boolean powered(UUID trainId) {
        Sample s = SAMPLES.get(trainId);
        return s != null && s.powered();
    }

    public static void updateGear(UUID trainId, int gear, boolean confirmDue, int vigilanceStage,
                                  boolean emergencyArmed, boolean emergencyPenalty,
                                  int cruiseState, boolean regen,
                                  boolean autoArrive, boolean unmanned) {
        GEARS.put(trainId, new GearState(gear, confirmDue, vigilanceStage, emergencyArmed,
                emergencyPenalty, cruiseState, regen, autoArrive, unmanned));
    }

    /** Lever state, or {@code null} when the server has not sent one. */
    public static GearState gear(UUID trainId) {
        return GEARS.get(trainId);
    }

    /** Latest sample, or {@code null} when the server has not sent one. */
    public static Sample get(UUID trainId) {
        return SAMPLES.get(trainId);
    }

    /**
     * Modelled speed ceiling in Blocks/Second, or {@code 0} when it should not
     * be used as an authoritative figure.
     *
     * <p>Returns the synced ceiling only for a sample the server reported as
     * <i>powered</i>. That guard matters because this static map is shared
     * between the client and an integrated server in single-player: without it,
     * a train that genuinely has no supply would fall through the
     * {@code isPowered} check in {@code TrainMixin} and pick up the client's
     * ceiling, letting a dead train target a speed it cannot reach.
     *
     * <p>Used as the denominator for Create's experience-bar speed readout so
     * that bar reflects this mod's train instead of Create's own top speed.
     */
    public static float maxSpeed(UUID trainId) {
        Sample s = SAMPLES.get(trainId);
        return s == null || !s.powered() ? 0f : s.maxSpeed();
    }

    /**
     * The ceiling this consist is ALLOWED, whether or not it has supply right now.
     *
     * <p>Used by {@code Train.maxSpeed()} so that an electric train does not fall back
     * to Create's own 40 Blocks/Second ceiling the moment the catenary ends. That
     * fallback was a genuine conflict: a 100 Blocks/Second train crossing a neutral
     * section, or running on to an unelectrified line, had every "how fast may I go"
     * question answered with 40 - Navigation's target speed, the schedule's throttle
     * cap, and the automatic-arrival command. The traction force is separately scaled by
     * the supply voltage, so returning the design ceiling while dead grants no power:
     * the train coasts.
     *
     * <p>Read on BOTH sides, and it has to be: the client never runs the electrical
     * simulation, so it cannot compute this itself. Unlike {@link #maxSpeed(UUID)} this
     * deliberately does not check {@code powered}, because being unpowered is exactly the
     * case it exists to answer.
     */
    public static float maxSpeedFor(UUID trainId) {
        Sample s = SAMPLES.get(trainId);
        if (s == null || s.motorCars() <= 0)
            return 0f;
        return s.designMaxSpeed() > 0f ? s.designMaxSpeed() : s.maxSpeed();
    }

    /**
     * Whether this train is one the lever drives, i.e. an electric train the server
     * has sent lever state for.
     *
     * <p>One place, so the panel renderer, the click handler and the speed-wheel
     * suppression cannot disagree about which trains are lever-driven. The test is
     * the consist carrying traction motors, not being energised right now: a set
     * that has lost its supply is still an electric train and keeps its controls.
     *
     * <p>False for a fuel train even though the server syncs a sample for those too,
     * because that packet is also what feeds the carriage voltmeters. Without the
     * motor count here the lever panel would appear on a diesel crew's screen.
     */
    public static boolean leverDriven(UUID trainId) {
        Sample s = SAMPLES.get(trainId);
        if (s == null || s.motorCars() <= 0 || !GEARS.containsKey(trainId))
            return false;
        // Handed to Create: its controls are in charge, so the lever panel must not be shown and
        // the cab must not take the movement keys. This is the same question `electricControl`
        // answers, asked here so the several callers that gate on leverDriven cannot disagree
        // about it.
        return s.controlMode() != TrainControlMode.CREATE.ordinal();
    }

    /**
     * The control mode the server last reported, or {@code null} when it has sent no sample.
     *
     * <p>Needed by the panel, which has to say WHICH of the three states the train is in. The
     * numeric value was already crossing the wire - it is what {@link #leverDriven} and
     * {@link #electricControl} test - but nothing displayed it, so a driver in DEAD_COAST saw a
     * lever that refused to engage traction with no explanation of why. "No working pantograph" and
     * "supply below the minimum" both land in that same state, and neither is something the client
     * can work out for itself: reach is server-side geometry and the voltage is server-side
     * simulation.
     *
     * <p>Returns null rather than a default, because a mode the server has not yet stated is not
     * the same as one it has stated as ELECTRIC, and the panel says nothing at all in that case
     * rather than asserting something false.
     */
    public static TrainControlMode controlMode(UUID trainId) {
        Sample s = SAMPLES.get(trainId);
        if (s == null)
            return null;
        int ordinal = s.controlMode();
        TrainControlMode[] modes = TrainControlMode.values();
        return ordinal >= 0 && ordinal < modes.length ? modes[ordinal] : null;
    }

    /** Drop samples for trains that no longer exist, so the map cannot grow forever. */
    public static void retain(java.util.function.Predicate<UUID> keep) {
        SAMPLES.keySet().removeIf(id -> !keep.test(id));
        GEARS.keySet().removeIf(id -> !keep.test(id));
        PANTOGRAPHS.keySet().removeIf(id -> !keep.test(id));
        PANTOGRAPH_LOCKED.removeIf(id -> !keep.test(id));
        AUTO_RAISE.removeIf(id -> !keep.test(id));
        OVERLOADED.removeIf(id -> !keep.test(id));
    }

    // ------------------------------------------------------------------
    // Pantographs
    // ------------------------------------------------------------------

    /**
     * The train's pantographs as the server last reported them, or an empty list.
     *
     * <p>Needed on the client because a client-side carriage carries no pantograph list at all:
     * carriages are filled in by {@code Carriage.read}/{@code setContraption}, both server-only,
     * while the client builds its carriages from {@code AddTrainPacket}. Without this the cab would
     * have nothing to name or operate.
     *
     * <p>Each entry's {@code reachable} is an affordance only - it decides whether the panel offers
     * the raise action. The server re-checks it before acting, so a stale copy cannot authorise a
     * raise.
     */
    private static final Map<UUID, java.util.List<
            com.george_vi.electroenergetics.content.railway_electrification.pantograph
                    .SyncTrainPantographsPacket.Entry>> PANTOGRAPHS = new ConcurrentHashMap<>();

    /**
     * Whether raising is barred until the next station, per train.
     *
     * <p>Set by the emergency brake. Kept apart from the entry list because it is a rule about the
     * driver rather than about any pantograph, and because it has to be readable even when the list
     * is momentarily empty - the panel needs to explain why a raise did nothing.
     */
    private static final java.util.Set<UUID> PANTOGRAPH_LOCKED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** The train's pantographs, in carriage order; empty when the server has sent none. */
    public static java.util.List<
            com.george_vi.electroenergetics.content.railway_electrification.pantograph
                    .SyncTrainPantographsPacket.Entry> pantographs(UUID trainId) {
        return PANTOGRAPHS.getOrDefault(trainId, java.util.List.of());
    }

    /**
     * Whether this train carries at least one real OVERHEAD pantograph, as opposed to only rail shoes.
     *
     * <p>The distinction decides whether the driver has electric controls at all. A rail-shoe train
     * keeps upstream's behaviour - Create's W/S - so the cab must not appear on it, and the server
     * reports it as CREATE mode to make that automatic. This predicate is what the client uses to keep
     * a shoe's entry out of the "is there anything to operate" test, because a shoe in the list would
     * otherwise make {@code isAtElectricTrainControls} true and draw a pantograph panel for a train
     * that has no pantographs.
     *
     * <p>Empty list counts as false: no collector is not the same as a pantograph.
     */
    public static boolean hasOverheadPantograph(UUID trainId) {
        for (var entry : pantographs(trainId)) {
            if (!entry.railShoe())
                return true;
        }
        return false;
    }

    /** Whether raising a pantograph is barred until the next station. */
    public static boolean controlsLocked(UUID trainId) {
        return PANTOGRAPH_LOCKED.contains(trainId);
    }

    /**
     * Whether the driver's automatic-raise switch is on, per train.
     *
     * <p>Kept apart from the entry list for the same reason as {@link #PANTOGRAPH_LOCKED}: it is a
     * property of the train's operation, not of any one arm, and the panel has to display it even
     * while the list is momentarily empty.
     *
     * <p>Absent means OFF, which is the default and the safe direction: a train whose state has not
     * arrived yet must not be drawn as if it were raising itself.
     */
    /** Carriages in each train's consist, as last reported. */
    private static final java.util.Map<UUID, Integer> CARRIAGE_COUNT = new ConcurrentHashMap<>();

    /** How many carriages this train has, or 0 when the server has not said. */
    public static int carriageCount(UUID trainId) {
        return CARRIAGE_COUNT.getOrDefault(trainId, 0);
    }

    /** Trains the server last reported as over capacity. */
    private static final java.util.Set<UUID> OVERLOADED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Whether the server last reported this train as over capacity. */
    public static boolean overloaded(UUID trainId) {
        return OVERLOADED.contains(trainId);
    }

    public static void setOverloaded(UUID trainId, boolean overloaded) {
        if (overloaded)
            OVERLOADED.add(trainId);
        else
            OVERLOADED.remove(trainId);
    }

    private static final java.util.Set<UUID> AUTO_RAISE =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Whether the driver has turned automatic raising on for this train. */
    public static boolean autoRaise(UUID trainId) {
        return AUTO_RAISE.contains(trainId);
    }

    public static void updatePantographs(UUID trainId, boolean locked, boolean autoRaise,
                                        int carriageCount,
                                         java.util.List<
            com.george_vi.electroenergetics.content.railway_electrification.pantograph
                    .SyncTrainPantographsPacket.Entry> entries) {
        PANTOGRAPHS.put(trainId, java.util.List.copyOf(entries));
        CARRIAGE_COUNT.put(trainId, carriageCount);
        if (locked)
            PANTOGRAPH_LOCKED.add(trainId);
        else
            PANTOGRAPH_LOCKED.remove(trainId);
        if (autoRaise)
            AUTO_RAISE.add(trainId);
        else
            AUTO_RAISE.remove(trainId);
    }
}
