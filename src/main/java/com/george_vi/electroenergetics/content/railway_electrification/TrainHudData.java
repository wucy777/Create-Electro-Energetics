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
     */
    public record Sample(float maxSpeed, float power, float voltage, double grade,
                         boolean powered, int carriages, int motorCars,
                         float powerPerCarriage) {}

    private static final Map<UUID, Sample> SAMPLES = new ConcurrentHashMap<>();

    private TrainHudData() {}

    public static void update(UUID trainId, float maxSpeed, float power, float voltage,
                              double grade, boolean powered, int carriages, int motorCars,
                              float powerPerCarriage) {
        SAMPLES.put(trainId, new Sample(maxSpeed, power, voltage, grade, powered,
                carriages, motorCars, powerPerCarriage));
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

    /** Drop samples for trains that no longer exist, so the map cannot grow forever. */
    public static void retain(java.util.function.Predicate<UUID> keep) {
        SAMPLES.keySet().removeIf(id -> !keep.test(id));
    }
}
