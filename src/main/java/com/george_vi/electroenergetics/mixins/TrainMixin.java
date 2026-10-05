package com.george_vi.electroenergetics.mixins;

import com.george_vi.electroenergetics.CEEElectricTrainSoundTypes;
import com.george_vi.electroenergetics.CEERegistries;
import com.george_vi.electroenergetics.config.CEEConfigs;
import com.george_vi.electroenergetics.content.railway_electrification.ElectricTrainData;
import com.george_vi.electroenergetics.content.railway_electrification.TrainDriverState;
import com.george_vi.electroenergetics.content.railway_electrification.TrainGear;
import com.george_vi.electroenergetics.content.railway_electrification.TrainHudData;
import com.george_vi.electroenergetics.content.railway_electrification.TrainTractionModel;
import com.george_vi.electroenergetics.content.railway_electrification.sound_effects.TrainSoundModifier;
import com.george_vi.electroenergetics.content.railway_electrification.sound_effects.sound_types.ElectricTrainSoundType;
import com.george_vi.electroenergetics.mixin_interfaces.ICEETrainExtension;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.entity.TravellingPoint;
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
import java.util.List;
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

        if (!electricTrainData.isPowered)
            return original.call();

        // A curve limits speed by PHYSICS, not by Create's flat figure.
        //
        // Create returns one constant (14-20 m/s) for every curve in the world, which is
        // wrong in both directions: a broad sweeping curve is safe far above it, and a
        // tight one is not safe at 100 m/s. So a radius-based limit is used instead -
        // v = sqrt(g * r * lateralLimit) - which is the cant-deficiency formula and
        // needs nothing but the curve's own radius.
        //
        // This method used to return the design ceiling outright, on the reasoning that
        // "curves impose no limit in this model". That was a mistake, and it showed up
        // as a bug in its own right: people standing on a train were thrown out of it on
        // curves at speed. Create carries a standing rider by their CONTACT POINT, whose
        // per-tick displacement scales with the yaw rate - so removing the curve limit
        // removes the very thing that keeps a rider aboard. A curve limit is not
        // paperwork to be discarded; it is what makes the ride survivable.
        //
        // Falls back to Create's own figure when the radius cannot be read, which is the
        // conservative direction: an unknown curve is treated as a tight one.
        Train self = (Train) (Object) this;
        double radius = leadingCurveRadius(self);
        if (!(radius > 0d))
            return original.call();

        double limit = TrainTractionModel.curveSpeedLimit(radius);
        // Create returns Blocks/Tick here (its constants are m/s divided by 20).
        return (float) Math.min(limit / 20d, electricTrainData.maxSpeed / 20f);
    }

    /**
     * Radius of the curve the train is entering or on, or {@code 0} when it is on
     * straight track or the geometry cannot be read.
     *
     * <p>Both ends are checked, not just the leading one: a train's rear carriage is
     * still on a curve after its nose has straightened out, and at these speeds the
     * difference is most of the consist. A limit that only looked ahead would let a long
     * train straighten up while its tail was still swinging.
     *
     * <p>Read from the track edge, which is where Create keeps the data
     * ({@code TrackEdge.getTurn()} returns the {@code BezierConnection}, whose
     * {@code getRadius()} is the figure wanted). Guarded throughout, because this runs
     * on the train's tick and a partially loaded graph is a normal state, not an error -
     * failing to Create's own limit is always safe.
     */
    private static double leadingCurveRadius(Train train) {
        try {
            if (train.carriages.isEmpty())
                return 0d;
            double smallest = 0d;
            for (Carriage carriage : List.of(train.carriages.get(0),
                    train.carriages.get(train.carriages.size() - 1))) {
                for (TravellingPoint point : List.of(carriage.getLeadingPoint(),
                        carriage.getTrailingPoint())) {
                    if (point.edge == null || !point.edge.isTurn())
                        continue;
                    double r = point.edge.getTurn().getRadius();
                    if (r > 0d && (smallest == 0d || r < smallest))
                        smallest = r;
                }
            }
            return smallest;
        } catch (Throwable ignored) {
            return 0d;
        }
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
        //
        // underGearControl() rather than isDriven(): a train whose driver has walked
        // away stays with this mode, running the lever position that was left set.
        // Handing it back to Create here is what used to brake a driverless electric
        // train to a halt at once - Create's passive slowdown doesn't know about the
        // catenary, so it recovered nothing and stopped the train for no reason a
        // driver would recognise. The vigilance clock still runs while unmanned and
        // still brakes the train at TRIP_TICKS, so this defers the stop, it does not
        // remove it.
        if (data.hasTractionMotors && data.driver.underGearControl()) {
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

        // A TRIPPED vigilance device overrides the lever: the train is braked to a
        // stand and stays that way until the driver acknowledges.
        //
        // The lever's own position is deliberately left alone rather than being forced
        // back here, so the driver can see where they put the handle - and the red lamp
        // and the stationary train are what tell them to acknowledge. Re-engaging a gear
        // without acknowledging therefore does not restart the train, which is the
        // behaviour a real vigilance device has: the handle moving does nothing until
        // the alarm is cancelled.
        TrainGear effectiveGear = st.vigilanceTripped ? TrainGear.BRAKE : st.gear;

        // AUTOMATIC ARRIVAL: Create's navigation commands the speed, not the lever.
        //
        // While the driver holds space with a destination set, Create's Navigation runs
        // its own braking curve - it decides a target speed from the remaining distance
        // and the braking distance, and stops the train exactly at the platform. The
        // lever must NOT also be obeyed during that, or an ACCELERATE position would
        // command traction straight through the station.
        //
        // It is routed through CRUISE rather than given its own branch because CRUISE
        // already IS an approach controller: hold the commanded speed with traction up
        // to the motors' limit, coast inside the deadband, brake above it, and recover
        // energy on the way down. Feeding it Navigation's target each tick means the
        // arrival inherits the real power-limited acceleration and the regenerative
        // brake instead of Create's flat friction stop, with no second law to maintain.
        //
        // The command is taken as a MAGNITUDE and the direction is left to the existing
        // heading logic. Navigation's targetSpeed is signed (speedMod), but a train on
        // an approach is already travelling the way it intends to arrive, and feeding a
        // negative magnitude into a mirror-symmetric law would cancel the mirror rather
        // than reverse the train - the mirror already carries "which way is forward".
        double cruiseCommand = st.cruiseSpeed;
        if (st.autoArrive && !st.vigilanceTripped) {
            effectiveGear = TrainGear.CRUISE;
            cruiseCommand = Math.abs(self.targetSpeed);
        }

        // Release the station before commanding traction.
        //
        // Create does this inside approachTargetSpeed (if (manualTick)
        // leaveStation()), and that is exactly the method this mode bypasses for a
        // driven electric train - so without this the station would never be
        // released and the train would sit at the platform with currentStation set
        // forever. A hard softlock rather than a cosmetic bug.
        //
        // Only for a gear that commands movement: selecting the brake or cutting the
        // power at a platform should leave the train checked in, which is what lets
        // the schedule resume and what makes the arrival look like an arrival.
        //
        // Never while arriving automatically: releasing the station there would undo
        // the arrival the moment the train touched the platform, and the train would
        // pull straight back out - a station the driver cannot actually stop at.
        if (!st.autoArrive && effectiveGear.appliesTraction() && self.getCurrentStation() != null)
            self.leaveStation();

        // WHICH WAY THE DRIVING CAB FACES.
        //
        // A double-ended train has a cab at each end and the two command opposite
        // directions. Create handles this for its own controls (CarriageContraptionEntity
        // .control does `if (inverted) targetSpeed *= -1`) but the lever never goes
        // through that method, so without this the far cab's lever is mirrored and every
        // position does the opposite of what it says. Reported as: accelerate did
        // nothing, reverse moved the train forwards, and accelerating from there moved
        // it backwards - each one a sign flip of what was asked for.
        //
        // The whole system is mirrored about x for the duration of the calculation:
        // speed, gradient and the resulting acceleration. Mirroring rather than
        // negating the output is what keeps every position meaning the same thing in
        // the driver's own frame - ACCELERATE accelerates the way the cab faces,
        // REVERSE drives it the other way, and the brake still opposes motion - because
        // "opposes motion" and "which way is forward" both survive a mirror. It is also
        // exactly what Create's targetSpeed *= -1 amounts to.
        boolean inverted = st.driverCabInverted;
        double mirror = inverted ? -1d : 1d;

        double signedSpeedMs = self.speed * 20d * mirror;

        // trainGrade is a rise-over-run along the consist, which for a train
        // moving in -x is the grade towards -x, so it is mirrored into +x. The sign is
        // taken from the UNMIRRORED speed: this conversion is about the world, so it
        // must not be done in the cab frame. Doing it after the mirror would reverse
        // gravity at an inverted cab whenever the train was at rest - where the sign
        // has to come from the consist rather than from the motion - and the train
        // would roll uphill. Only then is the result rotated into the cab frame, where
        // the law works and where the cab's +x is the world's mirror*x.
        double gradeToPlusX = (self.speed < 0 ? -data.trackGrade : data.trackGrade) * mirror;

        double a = TrainTractionModel.gearAcceleration(
                signedSpeedMs, gradeToPlusX, carriages, data.powerScale,
                effectiveGear, cruiseCommand,
                TrainTractionModel.frictionBrake(),
                st.emergencyTicks > 0 && st.emergencyArmed,
                st.emergencyPenalty, data.gearStep);

        // Back out of the mirror: the law worked in the cab's frame, and the speed it
        // writes is the train's own.
        a *= mirror;

        // m/s² -> Blocks/Tick². 1 block = 1 m, so only the tick conversion is needed.
        double next = self.speed + a / 400d;

        // ACCELERATE hands over to CRUISE once the train is at its design speed.
        //
        // This is the behaviour that was asked for and that the ACCELERATE branch alone
        // cannot express: there, reaching the ceiling must mean "stop pulling" and not
        // "apply the brake", because the remaining thrust at the top of the range is a
        // fraction of a m/s^2 and a brake there fights a train that is doing nothing
        // wrong. That produced a visible accelerate/brake oscillation near 100 m/s.
        //
        // Handing over to CRUISE is what makes the top of the range stable and is also
        // what the position means to a driver: accelerate until you are at speed, then
        // hold it. CRUISE already holds a speed with traction, coasts within its
        // deadband, and brakes on a descent, which is exactly the right behaviour once
        // the train has arrived at the ceiling - so nothing new has to be written, the
        // lever just moves to the position that already does it.
        //
        // Checked against the speed just written, so the switch happens on the tick the
        // ceiling is reached rather than a tick late. The lever position is written
        // directly rather than sent as a packet: this is the server's own decision about
        // its own train, and the client learns of it on the next gear sync. The driver
        // sees the handle move to CRUISE, which is the honest report of what the train
        // is now doing.
        if (st.gear == TrainGear.ACCELERATE && !st.vigilanceTripped && !st.autoArrive
                && TrainTractionModel.atDesignCeiling(Math.abs(next) * 20d, carriages,
                        st.emergencyPenalty)) {
            st.gear = TrainGear.CRUISE;
            // Cruise holds the speed it is engaged at, which is where the train just
            // arrived. Captured here because this lever move did not come from a packet,
            // and CRUISE with a stale or zero target would command the wrong speed.
            st.cruiseSpeed = Math.abs(next) * 20d;
        }

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
        //
        // underGearControl() and not isDriven(), for the same reason as the wrap above:
        // an unmanned train's speed is written by the gear law, and if that method is
        // allowed to run as well then BOTH write train.speed every tick and the train
        // accelerates at twice the correct rate. This is the second half of the pair -
        // missing it would have made the unmanned feature look like a physics bug.
        if (data.hasTractionMotors && data.driver.underGearControl())
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
