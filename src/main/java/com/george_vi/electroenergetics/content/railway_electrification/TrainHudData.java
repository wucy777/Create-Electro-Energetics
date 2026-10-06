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
                         float designMaxSpeed) {}

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
                              float designMaxSpeed) {
        SAMPLES.put(trainId, new Sample(maxSpeed, power, voltage, grade, powered,
                carriages, motorCars, powerPerCarriage, manualFullSpeed, designMaxSpeed));
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
        return s != null && s.motorCars() > 0 && GEARS.containsKey(trainId);
    }

    /** Drop samples for trains that no longer exist, so the map cannot grow forever. */
    public static void retain(java.util.function.Predicate<UUID> keep) {
        SAMPLES.keySet().removeIf(id -> !keep.test(id));
        GEARS.keySet().removeIf(id -> !keep.test(id));
    }
}
