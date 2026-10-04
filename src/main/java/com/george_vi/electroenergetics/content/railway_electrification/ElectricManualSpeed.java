package com.george_vi.electroenergetics.content.railway_electrification;

import com.george_vi.electroenergetics.config.CEEConfigs;
import com.george_vi.electroenergetics.config.CServer;
import com.george_vi.electroenergetics.mixin_interfaces.ICEETrainExtension;
import com.simibubi.create.content.trains.entity.Train;

/**
 * Create's manual-driving speed cap, resolved for an electric train.
 *
 * <p>Create drives a manually controlled train to
 * {@code maxSpeed() * manualTrainSpeedModifier * throttle}. The 0.75 factor is a
 * deliberate handicap for a fuel train. For an electric train it is simply
 * wrong: the traction model has already worked out a real ceiling from the power
 * rating, running resistance and the gradient, so scaling it down again silently
 * threw away a quarter of the speed and made the configured
 * {@code electricTrainMaxSpeed} unreachable - a 100 m/s set topped out at
 * 75 m/s.
 *
 * <p>Clearing the handicap is only half of it. Three figures come off the same
 * decision, and two of them are readouts that must agree with the speed the train
 * is really obeying:
 *
 * <ul>
 *   <li>{@link #topSpeedFactor} is what the server feeds into
 *       {@code CarriageContraptionEntity.control()}: the handicap is dropped, so
 *       the raw ceiling becomes {@code maxSpeed()}. Create multiplies the throttle
 *       in straight afterwards, exactly as it does for a fuel train, so pulling the
 *       speed wheel back really does cap the train.</li>
 *   <li>{@link #speedBarFactor} is the divisor for the speed readouts - Create's
 *       18-segment experience bar and this mod's driver HUD. It is the train's own
 *       ceiling with <b>no</b> throttle term, so the bar reads the true speed
 *       against the full scale at all times and the throttle changes how fast the
 *       bar fills rather than what full means.</li>
 * </ul>
 *
 * <p>Fuel trains get Create's own factor back untouched and keep dividing by
 * {@code maxSpeed() * manualTrainSpeedModifier}, so nothing about them changes.
 */
public final class ElectricManualSpeed {

    private ElectricManualSpeed() {}

    /**
     * What this train is, electrically, as far as the current side can tell.
     *
     * @param electric whether an electrical simulation is driving this train at all
     * @param waived   whether that train ignores Create's manual-driving handicap
     */
    private record Traction(boolean electric, boolean waived) {}

    /**
     * Factor applied to {@code maxSpeed()} to get the raw speed ceiling for a
     * manually driven train, before the throttle.
     *
     * @param original Create's configured value, returned unchanged for anything
     *                 that is not a live electric train
     */
    public static float topSpeedFactor(Train train, float original) {
        // Note the order: an electric train ignores the configured value outright
        // rather than only when it is positive. A config of 0 would otherwise fall
        // through and leave the train unable to move, which is the opposite of
        // what this option promises.
        return traction(train).waived() ? 1f : original;
    }

    /**
     * Divisor for the speed readouts: what speed fills the bar completely.
     *
     * <p>This is the train's own ceiling and deliberately <b>not</b> the throttle.
     * The bar used to be scaled by the throttle as well, so scrolling the speed
     * wheel down shrank the denominator and the bar ran ahead of the train - at
     * half throttle, doing half the ceiling speed, it already read full. The
     * request is that the bar always reads the true speed against the full 100,
     * so pulling the throttle back visibly drops the reading instead of hiding it.
     *
     * <p>Fuel trains are untouched: they keep Create's
     * {@code maxSpeed() * manualTrainSpeedModifier} and no throttle term, exactly
     * as before.
     *
     * <p>Both readouts, this mod's panel and Create's experience bar, may only be
     * touched for a train that is actually electrically driven - hence the
     * explicit check rather than folding this into the handicap test.
     */
    public static float speedBarFactor(Train train, float original) {
        Traction traction = traction(train);
        if (!traction.electric())
            return original;
        return traction.waived() ? 1f : original;
    }

    /**
     * Whether this train is electrically driven, and whether it therefore ignores
     * Create's manual-driving handicap.
     *
     * <p>The two sides answer differently on purpose. On the server the electrical
     * simulation runs, so the traction state and the config are both right there.
     * On the client neither is available - the simulation never runs there and a
     * server-side config cannot be read - so the answer has to come from what the
     * server synced, otherwise the readouts and the train would use different
     * factors.
     *
     * <p>The config is null-checked rather than assumed: this is also called while
     * merely drawing a HUD, and a client that has not yet received a synced sample
     * must not throw.
     */
    private static Traction traction(Train train) {
        if (train == null)
            return new Traction(false, false);

        if (((ICEETrainExtension) train).getElectricTrainData().isPowered) {
            CServer config = CEEConfigs.server();
            boolean waived = config != null
                    && config.trainValues.electricTrainManualFullSpeed.get();
            return new Traction(true, waived);
        }

        TrainHudData.Sample sample = TrainHudData.get(train.id);
        if (sample == null || !sample.powered())
            return new Traction(false, false);
        return new Traction(true, sample.manualFullSpeed());
    }
}
