package com.george_vi.electroenergetics.content.railway_electrification;

import com.george_vi.electroenergetics.config.CEEConfigs;
import com.george_vi.electroenergetics.mixin_interfaces.ICEETrainExtension;
import com.simibubi.create.content.trains.entity.Train;

/**
 * Create's manual-driving speed cap, resolved for an electric train.
 *
 * <p>Create drives a manually controlled train to
 * {@code maxSpeed() * manualTrainSpeedModifier} (0.75 by default) and sizes the
 * experience-bar speed readout from that same product. For a fuel train that is
 * a deliberate handicap. For an electric train it is simply wrong: the traction
 * model has already worked out a real ceiling from the power rating, running
 * resistance and the gradient, so scaling it down again silently threw away a
 * quarter of the speed and made the configured {@code electricTrainMaxSpeed}
 * unreachable - a 100 m/s set topped out at 75 m/s.
 *
 * <p>Both call sites are patched ({@code CarriageContraptionEntity.control} on
 * the server, {@code TrainHUD.tick} on the client) so the speed the train
 * actually reaches and the bar that displays it always agree.
 */
public final class ElectricManualSpeed {

    private ElectricManualSpeed() {}

    /**
     * The manual-driving factor to actually use.
     *
     * @param original Create's configured value, returned unchanged for anything
     *                 that is not a live electric train
     */
    public static float modifierFor(Train train, float original) {
        if (!(original > 0f))
            return original;
        return usesFullSpeed(train) ? 1f : original;
    }

    /**
     * Whether this train should ignore Create's manual-driving handicap.
     *
     * <p>The two sides answer differently on purpose. On the server the electrical
     * simulation runs, so the traction state and the config are both right there.
     * On the client neither is available - the simulation never runs there and a
     * server-side config cannot be read - so the answer has to come from what the
     * server synced, otherwise the bar and the train would use different factors.
     */
    private static boolean usesFullSpeed(Train train) {
        if (train == null)
            return false;

        if (((ICEETrainExtension) train).getElectricTrainData().isPowered) {
            var config = CEEConfigs.server();
            return config != null && config.trainValues.electricTrainManualFullSpeed.get();
        }

        TrainHudData.Sample sample = TrainHudData.get(train.id);
        return sample != null && sample.powered() && sample.manualFullSpeed();
    }
}
