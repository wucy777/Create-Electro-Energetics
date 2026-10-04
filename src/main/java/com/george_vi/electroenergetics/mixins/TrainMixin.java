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
            // Blocks/Second² -> Blocks/Tick². This method takes no speed argument, so
            // it cannot apply the electric brake's low-speed fade and reports the
            // blended rate at full effect (friction + electric). That is the figure
            // Create's stopping-distance maths wants, since it is judging an approach
            // from line speed. The speed-dependent blend is applied in
            // approachTargetSpeed, which does know the speed.
            return (float) ((TrainTractionModel.frictionBrake()
                    + TrainTractionModel.dynamicBrake()) / 400d);
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

        // Gear driving owns the speed of a driven electric train, and this is the
        // one place per tick that writes it. Doing it here rather than in
        // approachTargetSpeed keeps a single writer: that method is called from
        // control() and from Navigation at points that do not line up with the
        // train's own tick, so integrating there as well would double-apply the
        // law and make the train accelerate at twice the rate.
        if (data.hasTractionMotors && data.driver.isDriven()) {
            applyGearLaw(self, data);
            return;
        }

        original.call();
    }

    /**
     * Integrate one tick of the gear driving law.
     *
     * <p>Speed is advanced by {@code a*dt} in world coordinates, which is why
     * {@link TrainTractionModel#gearAcceleration} returns a world-frame figure:
     * a travel-frame one flips sign as the train passes through zero and a held
     * brake then drives the train away backwards.
     *
     * <p>Gravity is included here and nowhere else, which is what makes the
     * positions differ on a slope - cutting the power rolls, and only the brake
     * holds.
     */
    private void applyGearLaw(Train self, ElectricTrainData data) {
        TrainDriverState st = data.driver;
        int carriages = Math.max(1, self.carriages.size());
        double signedSpeedMs = self.speed * 20d;

        // Release the station before commanding traction.
        //
        // Create does this inside approachTargetSpeed (if (manualTick)
        // leaveStation()), and that is exactly the method this mode bypasses for a
        // driven electric train - so without this the station would never be
        // released. The train would sit at the platform with currentStation set
        // forever, and because the driver-state tick drops the lever to the brake
        // while a station is held, a reversing train would additionally be stuck
        // unable to select any gear. A hard softlock rather than a cosmetic bug.
        //
        // Only for a gear that commands movement: selecting the brake or cutting the
        // power at a platform should leave the train checked in, which is what lets
        // the schedule resume and what makes the arrival look like an arrival.
        if (st.gear.appliesTraction() && self.getCurrentStation() != null)
            self.leaveStation();

        // trainGrade is a rise-over-run along the consist, which for a train
        // moving in -x is the grade towards -x, so it is mirrored into +x.
        double gradeToPlusX = self.speed < 0 ? -data.trackGrade : data.trackGrade;

        double a = TrainTractionModel.gearAcceleration(
                signedSpeedMs, gradeToPlusX, carriages, data.powerScale,
                st.gear, st.cruiseSpeed,
                TrainTractionModel.frictionBrake(),
                st.emergencyTicks > 0 && st.emergencyArmed,
                st.emergencyPenalty, data.gearStep);

        // m/s² -> Blocks/Tick². 1 block = 1 m, so only the tick conversion is needed.
        double next = self.speed + a / 400d;

        // Bring a stop to rest instead of through it.
        //
        // Any deceleration applied to a speed that is nearly zero steps past it and
        // comes out negative, which is the train reversing - physically wrong for a
        // brake, and visible as a train that answers a full stop by rolling gently
        // backwards. The test is on the ACCELERATION rather than on the lever
        // position: an earlier version enumerated BRAKE and COAST and therefore
        // missed the emergency brake fired from any other position, which the
        // harness showed as a train that never stopped. Opposing the motion is what
        // makes it a stop; anything else is a genuine reversal, as REVERSE from rest.
        if (Math.abs(self.speed) > 1e-9d && Math.signum(a) != Math.signum(self.speed)
                && Math.signum(next) != Math.signum(self.speed))
            next = 0d;

        self.speed = next;

        // Regeneration is reported for the next circuit build, which runs before the
        // following tick's motion.
        //
        // Taken from the electric deceleration the law actually applied, rather than
        // enumerated from the lever position. Enumerating was wrong in both
        // directions: it credited the brake even when the motors had faded out at low
        // speed and the pads were doing all of it, and it missed the penalty case
        // where an over-speed train is braked while the lever sits at ACCELERATE. The
        // law already works out exactly how much of the retarding is electrical, so
        // that figure is the honest test for "are the motors generating".
        data.braking = data.gearStep.dynamicBrake > 0d;

        // Create's own method clears this, and control() re-sets it every tick the
        // driver holds the controls.
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

        // A gear-driven train has its speed written by the gear law each tick, so
        // Create's target-speed ramping must not also touch it. Selecting the
        // lever is what commands this train now, not held keys and the speed
        // wheel, and letting both run would have them fight over the same field.
        //
        // This return is deliberately ABOVE the brake-flag clear below. control()
        // reaches here on its 5-tick keepalive, which can land after the gear law
        // has already set the flag for this tick; clearing first would then wipe
        // the regeneration report once every five ticks, so a braking train drew
        // its recovered power in a stutter. The gear law owns both the speed and
        // the flag for a driven train, so this method leaves both alone.
        if (data.hasTractionMotors && data.driver.isDriven())
            return;

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
            data.gearStep.dynamicBrake = 0d;
            self.speed = Math.min(self.speed + up * accelerationMod, actualTarget);
        } else {
            // A scheduled train brakes at the blended service rate for its current
            // speed: friction plus whatever the motor brake can still absorb. The
            // electric part is reported back so the circuit build can regenerate it,
            // which is the same route the gear-driven path uses.
            double speedMs = Math.abs(self.speed) * 20d;
            double electric = TrainTractionModel.dynamicBrake()
                    * TrainTractionModel.dynamicBrakeFade(speedMs);
            data.gearStep.dynamicBrake = electric;
            double down = TrainTractionModel.serviceBrakeDeceleration(speedMs) / 400d;
            data.braking = true;
            self.speed = Math.max(self.speed - down * accelerationMod, actualTarget);
        }
    }
}
