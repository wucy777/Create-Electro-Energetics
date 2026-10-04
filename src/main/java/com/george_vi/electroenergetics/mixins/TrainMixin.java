package com.george_vi.electroenergetics.mixins;

import com.george_vi.electroenergetics.CEEElectricTrainSoundTypes;
import com.george_vi.electroenergetics.CEERegistries;
import com.george_vi.electroenergetics.config.CEEConfigs;
import com.george_vi.electroenergetics.content.railway_electrification.ElectricTrainData;
import com.george_vi.electroenergetics.content.railway_electrification.TrainHudData;
import com.george_vi.electroenergetics.content.railway_electrification.TrainTractionModel;
import com.george_vi.electroenergetics.content.railway_electrification.sound_effects.TrainSoundModifier;
import com.george_vi.electroenergetics.content.railway_electrification.sound_effects.sound_types.ElectricTrainSoundType;
import com.george_vi.electroenergetics.mixin_interfaces.ICEETrainExtension;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.DimensionPalette;
import com.simibubi.create.content.trains.graph.TrackGraph;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Mixin(Train.class)
public class TrainMixin implements ICEETrainExtension {
    @Unique
    ElectricTrainSoundType electroenergetics$soundType;

    @Unique
    ElectricTrainData electroenergetics$electricTrainData = new ElectricTrainData();

    @Unique
    public Set<TrainSoundModifier> electroEnergetics$soundModifyingBlocks = new HashSet<>();

    @Override
    public ElectricTrainSoundType getSoundType() {
        return electroenergetics$soundType;
    }

    @Override
    public void setSoundType(ElectricTrainSoundType type) {
        electroenergetics$soundType = type;
    }

    @Override
    public Set<TrainSoundModifier> getSoundModifyingBlocks() {
        return electroEnergetics$soundModifyingBlocks;
    }

    @Override
    public ElectricTrainData getElectricTrainData() {
        return electroenergetics$electricTrainData;
    }

    // Wrap method so its compatible with Create: Power Loader
    @WrapMethod(method = "write")
    public CompoundTag electroEnergetics$write(DimensionPalette dimensions, HolderLookup.Provider registries, @NotNull Operation<CompoundTag> original) {
        CompoundTag tag = original.call(dimensions, registries);
        ResourceLocation id = CEERegistries.ELECTRIC_TRAIN_SOUND_TYPE.getKey(electroenergetics$soundType);
        if (id != null)
            tag.putString("CEETrainSoundType", id.toString());
        tag.putInt("CEEAccumulators", electroenergetics$electricTrainData.accumulators);
        tag.putDouble("CEEAccumulatorCharge", electroenergetics$electricTrainData.accumulatorCharge);
        tag.putDouble("CEEAccumulatorChargeVoltage", electroenergetics$electricTrainData.accumulatorChargeVoltage);
        tag.putDouble("CEEAccumulatorActualVoltage", electroenergetics$electricTrainData.accumulatorActualVoltage);
        tag.putBoolean("CEECreativeSource", electroenergetics$electricTrainData.hasCreativeSource);
        tag.putDouble("CEELastVoltage", electroenergetics$electricTrainData.lastVoltage);
        return tag;
    }

    // Wrap method so its compatible with Create: Power Loader
    @WrapMethod(method = "read")
    private static Train electroEnergetics$read(CompoundTag tag, HolderLookup.Provider registries, Map<UUID, TrackGraph> trackNetworks, DimensionPalette dimensions, Operation<Train> original) {
        Train originalTrain = original.call(tag, registries, trackNetworks, dimensions);
        String id = tag.getString("CEETrainSoundType");
        ResourceLocation location = ResourceLocation.tryParse(id);
        ElectricTrainSoundType soundType = null;
        if (location != null)
            soundType = CEERegistries.ELECTRIC_TRAIN_SOUND_TYPE.get(location);
        if (soundType == null)
            soundType = CEEElectricTrainSoundTypes.MODERN.get();
        ICEETrainExtension train = (ICEETrainExtension) originalTrain;
        train.setSoundType(soundType);
        ElectricTrainData electricTrainData = train.getElectricTrainData();
        electricTrainData.accumulators = tag.getInt("CEEAccumulators");
        electricTrainData.accumulatorCharge = tag.getDouble("CEEAccumulatorCharge");
        electricTrainData.accumulatorChargeVoltage = tag.getDouble("CEEAccumulatorChargeVoltage");
        electricTrainData.accumulatorActualVoltage = tag.getDouble("CEEAccumulatorActualVoltage");
        electricTrainData.hasCreativeSource = tag.getBoolean("CEECreativeSource");
        electricTrainData.lastVoltage = tag.getDouble("CEELastVoltage");
        return originalTrain;
    }

    @WrapMethod(method = "maxSpeed")
    public float electroEnergetics$maxSpeed(Operation<Float> original) {
        ElectricTrainData electricTrainData = electroenergetics$electricTrainData;
        float result;

        if (electricTrainData.isPowered) {
            // maxSpeed is in Blocks/Second, maxSpeed() must return Blocks/Tick.
            result = electricTrainData.maxSpeed / 20f;
        } else {
            result = original.call();

            // On the client the electrical simulation never runs, so isPowered is
            // always false and we would fall through to Create's own top speed.
            // Create's TrainHUD sizes its 18-segment speed bar as
            // |speed| / (maxSpeed() * manualTrainSpeedModifier), so that stale
            // denominator is what made the bar disagree with the train. Use the
            // ceiling the server synced instead.
            Train self = (Train) (Object) this;
            float synced = TrainHudData.maxSpeed(self.id);
            if (synced > 0f)
                result = synced / 20f;
        }
        return result;
    }

    @WrapMethod(method = "maxTurnSpeed")
    public float electroEnergetics$maxTurnSpeed(Operation<Float> original) {
        ElectricTrainData electricTrainData = electroenergetics$electricTrainData;

        // Curves impose no limit in this model, so a turning train is allowed the
        // same speed as on straight track.
        if (electricTrainData.isPowered)
            return electricTrainData.maxSpeed / 20f;

        return original.call();
    }

    /**
     * Create uses {@code acceleration()} for two different jobs: ramping the
     * speed up in {@code approachTargetSpeed()}, and computing braking distance
     * as {@code speed^2 / (2 * acceleration())}.
     *
     * <p>Those want different numbers. A power-limited train accelerates slowly
     * at high speed but brakes at a roughly constant rate; if we returned the
     * tapered traction figure here, a 350 km/h train would believe it needed
     * ~12 km to stop. So this returns the service braking rate, and the
     * traction taper is applied in {@code approachTargetSpeed()} instead.
     */
    @WrapMethod(method = "acceleration")
    public float electroEnergetics$acceleration(Operation<Float> original) {
        ElectricTrainData electricTrainData = electroenergetics$electricTrainData;

        if (electricTrainData.isPowered) {
            // Blocks/Second² -> Blocks/Tick²
            return CEEConfigs.server().trainValues.electricTrainBrakeDeceleration.getF() / 400f;
        }
        return original.call();
    }

    /**
     * Coasting, which is not the same thing as braking.
     *
     * <p>Create uses one number for both: {@code acceleration()} sets the ramp-up
     * rate, the braking rate <i>and</i> the rate a train loses speed at with
     * nothing driving it. Since {@code approachTargetSpeed} brakes at the same
     * figure, releasing the throttle, holding reverse and scrolling the speed
     * wheel below the current speed all decelerated identically - there was no
     * brake at all, just a fixed friction-free stop.
     *
     * <p>Physically they are different by an order of magnitude. Coasting is
     * running resistance alone: at 100 m/s a carriage sheds about 0.16 m/s², so
     * it coasts for kilometres. Service braking is a controlled ~0.8 m/s² that
     * stops the same train in a fraction of the distance and puts energy back
     * into the line. So this replaces only the coasting path; braking stays in
     * {@code approachTargetSpeed} and {@code acceleration()}, where Create's
     * braking-distance maths also reads it.
     *
     * <p>Only powered electric trains are affected. Anything else - a fuel train,
     * an unpowered one, or a scheduled one with a destination - falls through to
     * Create unchanged.
     */
    @WrapMethod(method = "tickPassiveSlowdown")
    private void electroEnergetics$tickPassiveSlowdown(Operation<Void> original) {
        Train self = (Train) (Object) this;
        ElectricTrainData data = electroenergetics$electricTrainData;

        // manualTick is true while a driver holds the controls, so their braking
        // already happened in approachTargetSpeed; a destination means the
        // schedule is stopping the train and Create's rate is the right one.
        if (!data.isPowered || self.manualTick || self.navigation.destination != null || self.speed == 0) {
            original.call();
            return;
        }

        // Coasting, so the motors are not being driven and there is nothing to
        // recover. Clearing this here matters: the flag is read by the circuit
        // build on the following tick, and if it were only ever set it would latch
        // on and the train would report regeneration for the rest of its life.
        data.braking = false;

        double speedMs = Math.abs(self.speed) * 20d;
        int carriages = Math.max(1, self.carriages.size());
        // Blocks/Second² -> Blocks/Tick².
        double perTick = TrainTractionModel.coastDeceleration(speedMs, data.trackGrade, carriages) / 400d;

        if (self.speed > 0)
            self.speed = Math.max(self.speed - perTick, 0);
        else
            self.speed = Math.min(self.speed + perTick, 0);

        // Create's own method clears this unconditionally, and a driver's next
        // control() tick depends on it being clear when they let go.
        self.manualTick = false;
    }

    /**
     * Speed ramping, where the constant-power taper belongs.
     *
     * <p>Speeding up uses the traction the motors can actually deliver at the
     * current speed (which falls off as power becomes the limit); slowing down
     * uses the braking rate.
     */
    @WrapMethod(method = "approachTargetSpeed")
    public void electroEnergetics$approachTargetSpeed(float accelerationMod, Operation<Void> original) {
        ElectricTrainData data = electroenergetics$electricTrainData;

        // Cleared here, once, before any branch can return early. The flag is read
        // by the next circuit build, so a path that set it and then returned -
        // reaching the target exactly, losing power mid-brake, a station call -
        // would leave the train reporting regeneration for the rest of its life.
        // Setting it only in the braking branch below, and clearing it here, means
        // every other outcome is covered without having to enumerate them.
        data.braking = false;

        if (!data.isPowered) {
            original.call(accelerationMod);
            return;
        }

        Train self = (Train) (Object) this;
        double actualTarget = self.targetSpeed;
        if (Mth.equal(actualTarget, self.speed))
            return;
        if (self.manualTick)
            self.leaveStation();

        // Blocks/Second² -> Blocks/Tick². Before the electrical simulation has
        // produced a figure (the very first ticks of a freshly powered train)
        // fall back to the configured starting acceleration, otherwise the
        // train would sit still forever. A genuinely traction-limited case is
        // still safe: the target speed is then at or below the current speed,
        // so the braking branch below is taken instead.
        float traction = data.availableAcceleration > 0f
                ? data.availableAcceleration
                : CEEConfigs.server().trainValues.electricTrainMaxAcceleration.getF();
        double up = Math.max(traction, 0f) / 400d;

        if (self.speed < actualTarget) {
            data.braking = false;
            self.speed = Math.min(self.speed + up * accelerationMod, actualTarget);
        } else {
            // The brake is a NET rate, not an added force: it is what the train
            // does while the brake is held, on any gradient. That is what lets a
            // train be stopped and held on Create's steepest track - a 1-in-3
            // slope pulls at g*slope = 3.27 m/s^2, so the configured figure (3.5
            // by default) has to exceed it on its own rather than being an extra
            // term added to gravity. It also means the stopping distance is the
            // same uphill, downhill and on the level, which is how a driver
            // expects a brake to behave.
            double down = CEEConfigs.server().trainValues.electricTrainBrakeDeceleration.getF() / 400d;
            data.braking = true;
            self.speed = Math.max(self.speed - down * accelerationMod, actualTarget);
        }
    }
}
