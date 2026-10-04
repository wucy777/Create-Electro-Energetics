package com.george_vi.electroenergetics.content.railway_electrification;

/**
 * Output of one step of the gear driving law.
 *
 * <p>Mutable and reused rather than rebuilt, because this is written once per
 * train per tick and allocate-per-tick in that path is exactly the kind of thing
 * that shows up as GC churn on a busy server. Each train owns one instance on its
 * {@code ElectricTrainData}.
 *
 * <p>The second field exists because the caller cannot re-derive it: how much of
 * the braking came from the motors rather than the pads depends on the gear, the
 * gradient and what the supply could deliver, all of which the law has already
 * worked out. Only the motor's share can be regenerated - pads make heat - so
 * getting this wrong would either claim recovery from friction or miss it.
 */
public final class GearStep {

    /** Acceleration in world coordinates (+x) [m/s²]. */
    public double acceleration;

    /**
     * Of that, the part contributed by the motor brake [m/s²], positive when the
     * motors are retarding the train. Zero unless braking electrically.
     */
    public double dynamicBrake;

    public void clear() {
        acceleration = 0d;
        dynamicBrake = 0d;
    }
}
