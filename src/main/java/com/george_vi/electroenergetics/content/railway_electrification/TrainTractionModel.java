package com.george_vi.electroenergetics.content.railway_electrification;

import com.george_vi.electroenergetics.config.CEEConfigs;

/**
 * Realistic electric traction model.
 *
 * <p>1 block = 1 metre, so Blocks/Second is m/s and Blocks/Second² is m/s².
 * The train behaves like a real electric locomotive:
 * <ul>
 *   <li>constant tractive <b>effort</b> below the base speed,</li>
 *   <li>constant tractive <b>power</b> above it,</li>
 *   <li>gradient resistance added to (uphill) or subtracted from (downhill) the
 *       force the traction has to produce,</li>
 *   <li>curves imposing no limit at all.</li>
 * </ul>
 *
 * <p>All quantities are computed from the same numbers the electrical simulation
 * uses, so the drawn current always matches the mechanical situation.
 */
public final class TrainTractionModel {

    public static final double G = 9.81;

    /** Below this speed the constant-power law is not evaluated (avoids division by zero). */
    private static final double MIN_SPEED_FOR_POWER = 1.0d;

    private TrainTractionModel() {}

    // ------------------------------------------------------------------
    // Consist parameters, read straight from the server config
    // ------------------------------------------------------------------

    /** Rated electrical power of the whole consist [W]. */
    public static double ratedElectricalPower(int carriages) {
        return Math.max(1, carriages) * CEEConfigs.server().trainValues.electricTrainPowerPerCarriage.get();
    }

    /** Mechanical power available at the rail after drive losses [W]. */
    public static double ratedMechanicalPower(int carriages) {
        return ratedElectricalPower(carriages) * driveEfficiency();
    }

    public static double driveEfficiency() {
        return Math.max(CEEConfigs.server().resistanceValues.electricTrainDriveEfficiency.get(), 1e-4d);
    }

    public static double totalMass(int carriages) {
        return Math.max(1, carriages) * CEEConfigs.server().resistanceValues.electricTrainMassPerCarriage.get();
    }

    public static double designMaxSpeed() {
        return Math.max(CEEConfigs.server().trainValues.electricTrainMaxSpeed.get(), 0.1d);
    }

    /**
     * Whether a train at this speed has arrived at its design ceiling, so ACCELERATE
     * should hand over to CRUISE.
     *
     * <p>A small tolerance below the ceiling rather than an exact equality, because the
     * speed approaches the ceiling asymptotically as drag eats the remaining thrust and
     * will never equal it exactly. Too tight and the lever never moves; too loose and
     * ACCELERATE gives up early. 0.5 m/s below 100 is half a percent, reached within a
     * few ticks of the ceiling and far below anything a driver could notice.
     *
     * <p>Not penalty-aware, and it does not need to be. The emergency cap is not enforced
     * here at all: it is a cap on {@code Train.maxSpeed()}, which both control schemes ask,
     * because after an emergency stop the train is always under Create's controls. By the
     * time the cap matters this lever is not the thing driving the train.
     */
    public static boolean atDesignCeiling(double speedMs, int carriages) {
        return speedMs >= designMaxSpeed() - CEILING_HANDOVER_BAND;
    }

    /** How far below the design ceiling still counts as having reached it [m/s]. */
    // Tight, not half a metre per second. The handover to CRUISE fires when the train is this close to
    // the design ceiling; at 0.5 it fired at 99.5 and the driver never saw 100, on a train that had
    // another half a m/s of headroom. Handing over at 99.95 changes nothing about stability - the
    // hold branch meets drag with traction at the ceiling and never brakes there.
    private static final double CEILING_HANDOVER_BAND = 0.05d;

    /** Peak tractive effort in the constant-effort region [N]. */
    public static double startingEffort(int carriages) {
        return startingEffort(carriages, 1d);
    }

    /** Peak tractive effort scaled by how much of its rating the train may use [N]. */
    public static double startingEffort(int carriages, double powerScale) {
        double gamma = CEEConfigs.server().resistanceValues.electricTrainRotatingMassFactor.get();
        double aMax = CEEConfigs.server().trainValues.electricTrainMaxAcceleration.get();
        return (1d + gamma) * totalMass(carriages) * aMax * clampScale(powerScale);
    }

    /**
     * Fraction of its rating a train may actually use, given the terminal
     * voltage. A single train on a stiff supply gets 1.0; when several trains
     * share one catenary the voltage sags and every train gets less, which is
     * what makes them share the available power instead of melting the wire.
     */
    public static double powerScaleForVoltage(double voltage) {
        int minV = CEEConfigs.server().voltageValues.trainMinVoltage.get();
        int maxV = CEEConfigs.server().voltageValues.trainMaxVoltage.get();
        if (maxV <= minV)
            return 1d;
        double scale = (Math.abs(voltage) - minV) / (maxV - minV);
        return clampScale(scale);
    }

    private static double clampScale(double scale) {
        if (Double.isNaN(scale) || scale <= 0d)
            return 0d;
        return Math.min(scale, 1d);
    }

    // ------------------------------------------------------------------
    // Forces
    // ------------------------------------------------------------------

    /**
     * Davis running resistance [N].
     * {@code F = (A + B·v + C·v²) · m · g · 10⁻³}
     */
    public static double runningResistance(double speed, int carriages) {
        var r = CEEConfigs.server().resistanceValues;
        double v = Math.abs(speed);
        double coeff = r.electricTrainBasicResistanceA.get()
                + r.electricTrainBasicResistanceB.get() * v
                + r.electricTrainBasicResistanceC.get() * v * v;
        return coeff * totalMass(carriages) * G * 1e-3;
    }

    /** Gravity component along the track [N]. Positive grade = uphill = opposes motion. */
    public static double gradeResistance(double grade, int carriages) {
        if (!CEEConfigs.server().trainValues.electricTrainGradeResistance.get())
            return 0d;
        return totalMass(carriages) * G * grade;
    }

    /** Inertia force for the requested acceleration, including rotating mass [N]. */
    public static double inertiaForce(double acceleration, int carriages) {
        double gamma = CEEConfigs.server().resistanceValues.electricTrainRotatingMassFactor.get();
        return (1d + gamma) * totalMass(carriages) * acceleration;
    }

    // ------------------------------------------------------------------
    // Traction envelope
    // ------------------------------------------------------------------

    /**
     * Tractive effort the motors can put down at this speed [N].
     * Constant effort below the base speed, constant power above it.
     */
    public static double availableEffort(double speed, int carriages) {
        return availableEffort(speed, carriages, 1d);
    }

    /**
     * Tractive effort the motors can put down at this speed, scaled by the
     * share of the rating the supply can actually deliver [N].
     */
    public static double availableEffort(double speed, int carriages, double powerScale) {
        double v = Math.abs(speed);
        double scale = clampScale(powerScale);
        double effortLimit = startingEffort(carriages, scale);
        double mechanical = ratedMechanicalPower(carriages) * scale;
        if (v < MIN_SPEED_FOR_POWER)
            return effortLimit;
        return Math.min(effortLimit, mechanical / v);
    }

    /** Base speed: where the constant-effort region ends and constant power begins [m/s]. */
    public static double baseSpeed(int carriages) {
        double effort = startingEffort(carriages);
        if (effort <= 0d)
            return designMaxSpeed();
        return ratedMechanicalPower(carriages) / effort;
    }

    /**
     * Highest speed the consist can sustain against running resistance and the
     * current gradient [m/s]. On level track this is where the available effort
     * equals the running resistance; uphill it is lower, downhill higher.
     *
     * @param powerScale fraction of the rating the supply can deliver
     */
    public static double maxSustainableSpeed(double grade, int carriages, double powerScale) {
        double ceiling = designMaxSpeed();

        double lo = 0d, hi = ceiling;
        for (int i = 0; i < 40; i++) {
            double mid = (lo + hi) * 0.5d;
            // Must go through gradeResistance(), not a local mass*g*grade: that
            // method is where electricTrainGradeResistance is honoured, and the
            // other two users of the gravity term (availableAcceleration and
            // electricalDemand) both go through it. Building the term locally
            // here made the ceiling drop on a climb while the current draw
            // stayed at its level-track value - an engine pulling a grade for
            // free, and the two figures disagreeing.
            double need = runningResistance(mid, carriages)
                    + gradeResistance(grade, carriages);
            double have = availableEffort(mid, carriages, powerScale);
            if (have >= need)
                lo = mid;
            else
                hi = mid;
        }
        return Math.max(0d, lo);
    }

    /**
     * Acceleration the consist can currently deliver [m/s²].
     * Tapers off as speed rises, because the effort is power-limited.
     *
     * @param powerScale fraction of the rating the supply can deliver
     */
    public static double availableAcceleration(double speed, double grade, int carriages, double powerScale) {
        double v = Math.abs(speed);
        double net = availableEffort(v, carriages, powerScale)
                - runningResistance(v, carriages)
                - gradeResistance(grade, carriages);
        if (net <= 0d)
            return 0d;
        double gamma = CEEConfigs.server().resistanceValues.electricTrainRotatingMassFactor.get();
        return net / ((1d + gamma) * totalMass(carriages));
    }

    // ------------------------------------------------------------------
    // Electrical demand
    // ------------------------------------------------------------------

    /**
     * Electrical power the consist asks from the catenary for the given
     * mechanical situation [W].
     *
     * <p>The demanded effort is what the train <i>needs</i> (resistance +
     * gradient + requested acceleration), capped by what the motors can
     * actually deliver. The cap is what stops a consist from drawing more than
     * its rating and melting the catenary. A downhill gradient reduces the
     * demand, so gravity genuinely saves power.
     */
    public static double electricalDemand(double speed, double acceleration, double grade, int carriages) {
        return electricalDemand(speed, acceleration, grade, carriages, 1d);
    }

    /**
     * Electrical power the consist asks from the catenary for the given
     * mechanical situation, limited by the share of its rating the supply can
     * deliver [W].
     */
    public static double electricalDemand(double speed, double acceleration, double grade,
                                          int carriages, double powerScale) {
        return electricalDemand(speed, acceleration, grade, carriages, powerScale, false);
    }

    /**
     * As above, plus the current the motors draw when they are being driven AGAINST the train.
     *
     * <p>During an emergency application - and during REVERSE used as a brake - the motors are at full
     * power in reverse. That is real current coming out of the catenary, and it is the whole reason the
     * operator's rule says the emergency brake draws power and does not regenerate.
     *
     * <p>It was previously invisible: the demand was derived from the train's realised acceleration, and
     * a braking train has negative acceleration, so the inertia term cancelled the running resistance and
     * the demand came out at zero. The HUD accordingly read about 0 kW through an emergency stop while the
     * motors were working at rating - a number that contradicted the physics it was meant to report.
     *
     * <p>The force is taken from {@link #motorReverseBrake} and converted back to newtons with the same
     * effective mass that method divides by, so the two cannot disagree about how hard the motors are
     * working.
     */
    public static double electricalDemand(double speed, double acceleration, double grade,
                                          int carriages, double powerScale, boolean motorBraking) {
        double v = Math.abs(speed);
        double needed = runningResistance(v, carriages)
                + gradeResistance(grade, carriages)
                + inertiaForce(acceleration, carriages);

        // Effort the motors are expending while braking. Added to the demand rather than allowed to cancel
        // against it, because it is current drawn regardless of what the train's acceleration is doing.
        double motorBrakeEffort = 0d;
        if (motorBraking) {
            double gamma = CEEConfigs.server().resistanceValues.electricTrainRotatingMassFactor.get();
            double effective = (1d + gamma) * totalMass(carriages);
            motorBrakeEffort = motorReverseBrake(v, carriages, powerScale) * effective;
        }

        var res = CEEConfigs.server().resistanceValues;
        double margin = Math.max(res.electricTrainMarginFactor.get(), 1e-4d);
        double auxFactor = Math.max(res.electricTrainAuxiliaryLoadFactor.get(), 1e-4d);

        double demandForce = Math.max(needed, 0d) + motorBrakeEffort;

        double tractionPower;
        if (demandForce <= 0d) {
            // Gravity alone is enough to keep rolling; the motors draw nothing
            // useful and only the auxiliaries load the supply.
            tractionPower = 0d;
        } else {
            double deliverable = availableEffort(v, carriages, powerScale);
            double effort = Math.min(demandForce, deliverable);
            // Auxiliaries are fed from the same supply, so they subtract from
            // the force available at the rail.
            tractionPower = effort * Math.max(v, MIN_SPEED_FOR_POWER) / (driveEfficiency() * auxFactor);
        }

        double result = tractionPower * margin + auxiliaryPower(carriages);

        // The train is modelled as a resistor of V^2 / P, so the demand must
        // never be zero or non-finite or the circuit solver divides by zero.
        if (Double.isNaN(result) || result < MIN_DEMAND)
            return MIN_DEMAND;
        return result;
    }

    /** Smallest demand ever presented to the electrical simulation [W]. */
    public static final double MIN_DEMAND = 1.0d;

    /** Baseline auxiliary draw (lights, HVAC, controls), always present while powered. */
    public static double auxiliaryPower(int carriages) {
        return Math.max(1, carriages) * 2_000d;
    }

    // ------------------------------------------------------------------
    // Cruise (the 匀速 running position)
    // ------------------------------------------------------------------

    /**
     * What the cruise position is doing about speed, so the driver can be told.
     *
     * <p>Cruise asks the motors for exactly the power that holds the current
     * speed on this gradient - {@code P = v * (runningResistance + grade)}. Two
     * things can go wrong and both are worth saying out loud rather than hiding:
     * uphill the demand can exceed the rating, in which case the train runs at
     * full power and loses speed; downhill gravity can exceed what the running
     * resistance eats, in which case holding the speed needs the brake.
     */
    public enum CruiseState {
        /** Level or gentle: holding the speed on traction alone. */
        HOLDING,
        /** Climb too steep for the rating: full power, losing speed. */
        POWER_LIMITED,
        /** Descent steep enough that the brake is needed to hold the speed. */
        BRAKING,
        /** Too slow to ask anything sensible of the motors. */
        STOPPED
    }

    /** Speed below which cruise has nothing meaningful to hold [m/s]. */
    public static final double CRUISE_MIN_SPEED = 0.5d;

    /**
     * Decide what cruise should do at this speed and gradient.
     *
     * @param powerScale fraction of the rating the supply can deliver
     */
    public static CruiseState cruiseState(double speedMs, double grade, int carriages, double powerScale) {
        double v = Math.abs(speedMs);
        if (v < CRUISE_MIN_SPEED)
            return CruiseState.STOPPED;

        // Traction needed to hold this speed: running resistance plus the gravity
        // component. Negative means gravity alone would accelerate the train.
        double needed = runningResistance(v, carriages) + gradeResistance(grade, carriages);

        if (needed <= 0d)
            return CruiseState.BRAKING;

        double deliverable = availableEffort(v, carriages, powerScale);
        return needed > deliverable ? CruiseState.POWER_LIMITED : CruiseState.HOLDING;
    }

    // ------------------------------------------------------------------
    // Longitudinal motion (gear-driven driving)
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // Gear-driven motion
    // ------------------------------------------------------------------

    /** Speed band around the cruise setting where neither traction nor brake is applied [m/s]. */
    private static final double CRUISE_DEADBAND = 0.5d;

    /**
     * How much more adhesion the emergency brake can find than the service brake.
     *
     * <p>Emergency braking is not a different mechanism, it is the same friction
     * brake run at the limit of adhesion with sanding, so the extra is a modest
     * factor rather than the order-of-magnitude jump a separate "emergency rate"
     * would imply. Real figures are around 1.3-1.5x the service rate.
     */
    /**
     * @deprecated No longer used. The emergency brake is the motors in reverse plus the ordinary friction
     *             brake; multiplying friction by 1.5 asked for more adhesion than the wheels have.
     */
    @Deprecated
    public static final double EMERGENCY_ADHESION_FACTOR = 1.5d;

    // ------------------------------------------------------------------
    // Braking: friction and electric, blended
    // ------------------------------------------------------------------
    //
    // A real EMU has two brakes and they do not fade out together:
    //
    //   * Friction (pads on discs, or tread brakes). Roughly 0.8-1.0 m/s² in
    //     service, and it is the ONLY one that works at a standstill, because it
    //     needs no speed to generate its force.
    //   * Electric (motors driven as generators, sometimes called dynamic or
    //     rheostatic braking). Worth 2-3 m/s² at line speed, and it collapses to
    //     nothing as the train slows: the motors' back-EMF falls with speed, so
    //     there is progressively less to push current against and at a standstill
    //     there is none at all. A real train therefore always finishes a stop on
    //     friction alone.
    //
    // Blending them is what makes the model both realistic and honest. The two
    // agree with practice at speed - around 3.5 m/s² combined - while the rate
    // that remains at walking pace is the friction figure alone. That last point
    // is the one that decides whether a train can be held on a gradient, and it
    // cannot be talked around: an electric brake cannot hold a stationary train.
    //
    // Consequence, stated plainly: a consist holds on grades up to
    // frictionBrake()/g. At the realistic default of 1.0 that is about 10%, well
    // short of the 1-in-3 (18.43°, 3.27 m/s²) that Create can lay. On anything
    // steeper than 10% the train will creep away with the brake held, which is
    // exactly what a real train does. Raise electricTrainBrakeDeceleration if
    // steep grades matter more than realism.

    /** Friction (adhesion) service braking rate [m/s²]. The only part that holds at rest. */
    public static double frictionBrake() {
        return Math.max(CEEConfigs.server().trainValues.electricTrainBrakeDeceleration.get(), 0d);
    }

    /**
     * Retarding acceleration the motors can provide by being driven in reverse
     * [m/s²], which is what the REVERSE position adds on top of the friction brake.
     *
     * <p>This is NOT regenerative braking, and the difference matters: it uses the
     * same tractive-effort curve as driving ({@link #availableEffort}), so at a
     * standstill it is the full starting figure rather than nothing. A motor under
     * power holds torque at zero speed - that is how a real drive holds a train on a
     * grade - whereas a motor being back-driven as a generator cannot, because its
     * back-EMF has collapsed. So unlike the regenerative brake, this one does count
     * toward holding a stationary train.
     *
     * <p>Above the base speed it is power-limited, exactly like traction.
     */
    public static double motorReverseBrake(double speedMs, int carriages, double powerScale) {
        double gamma = CEEConfigs.server().resistanceValues.electricTrainRotatingMassFactor.get();
        double effective = (1d + gamma) * totalMass(carriages);
        if (effective <= 0d)
            return 0d;
        double effort = availableEffort(speedMs, carriages, powerScale);
        double a = effort / effective;
        return Double.isFinite(a) && a > 0d ? a : 0d;
    }

    /**
     * Steepest gradient the train can be held on, as rise over run, with the given
     * braking deceleration available [m/s²].
     *
     * <p>Both the brake and gravity are divided by the same effective mass
     * {@code (1+γ)·m} on their way to an acceleration, so the rotating-mass factor
     * cancels and the test reduces to {@code brake >= g·grade}. That makes this exact
     * rather than approximate, and it is the number to design a railway against.
     */
    public static double maxHoldableGrade(double brakeMs2) {
        return brakeMs2 / G;
    }

    /** Electric braking rate at full effect [m/s²], before the low-speed fade. */
    public static double dynamicBrake() {
        return Math.max(CEEConfigs.server().trainValues.electricTrainDynamicBrakeDeceleration.get(), 0d);
    }

    /** Speed at which the electric brake reaches full effect [m/s]. */
    public static double dynamicBrakeMinSpeed() {
        return Math.max(CEEConfigs.server().trainValues.electricTrainDynamicBrakeMinSpeed.get(), 0d);
    }

    /**
     * How much of the electric brake is available at this speed, 0 to 1.
     *
     * <p>Linear in speed, which is the shape the physics gives: the retarding
     * force is proportional to the current the motors can be made to carry, and
     * that falls off with the back-EMF.
     */
    public static double dynamicBrakeFade(double speedMs) {
        double v = Math.abs(speedMs);
        double min = dynamicBrakeMinSpeed();
        if (min <= 0d)
            return v > 0d ? 1d : 0d;
        return Math.min(v / min, 1d);
    }

    /** Total service braking rate at this speed [m/s²]: friction plus electric. */
    public static double serviceBrakeDeceleration(double speedMs) {
        return frictionBrake() + dynamicBrake() * dynamicBrakeFade(speedMs);
    }

    // emergencyBrakeDeceleration(speedMs) used to live here and has been removed.
    //
    // It returned frictionBrake()*EMERGENCY_ADHESION_FACTOR + dynamicBrake()*fade, which was wrong
    // in the one way that mattered: the electric share was applied at full effect with no supply at
    // all, because the supply scaling lives in gearAcceleration and this method never saw it. A
    // train that had lost its wire would have been credited with braking it could not produce.
    //
    // Its only caller built the two parts separately so the motor half could be scaled, which left
    // this method unused. Keeping an unused method that encodes the wrong physics is a trap for
    // whoever reaches for it next, so it is gone rather than left as a convenience.

    /**
     * Braking rate that is still available at a standstill [m/s²].
     *
     * <p>Friction only, and this is the figure that decides whether a held brake
     * pins a train on a slope. Kept as its own method so the holding test cannot
     * accidentally be written against the blended rate, which would let a train
     * appear to hold on a gradient it would really slide down.
     */
    public static double holdingBrakeDeceleration() {
        return frictionBrake();
    }

    /** Fastest the train may be driven backwards from rest [m/s]. A shunting speed. */
    public static double reverseMaxSpeed() {
        return Math.max(CEEConfigs.server().trainValues.electricTrainReverseMaxSpeed.get(), 0d);
    }

    /**
     * Acceleration in world coordinates (+x) for the current lever position
     * [m/s²]. Kept out of the mixin so it can be exercised without a running game.
     *
     * <p>Deliberately world-frame rather than "along the direction of travel".
     * A travel-frame figure flips sign when the train passes through zero, so a
     * brake held on a train that momentarily reaches a standstill starts pushing
     * it the other way and the train accelerates away backwards. Returning the
     * world-frame value means the caller simply does {@code speed += a * dt}, and
     * a brake always opposes motion whichever way the train is going.
     *
     * <p>The train is treated as a point mass on a slope:
     * <pre>
     *   a = [ traction(gear) - sign(v)·drag - g·grade ] / ((1+γ)·m)
     * </pre>
     * where {@code grade} is rise-over-run towards +x, so a positive grade is a
     * climb in +x and gravity pulls towards −x. Drag is proportional to v² and
     * always opposes motion; at a standstill it is zero, which is why the brake
     * is what holds a train on a hill and drag does not.
     *
     * @param signedSpeedMs current speed, positive towards +x
     * @param gradeToPlusX rise over run towards +x; positive is uphill towards +x
     * @param powerScale    fraction of the rating the supply can deliver
     * @param cruiseSpeedMs speed the cruise position holds, as a magnitude
     * @param brakeMs2      configured FRICTION service braking rate
     * @param emergency     whether the emergency brake is being applied
     * @param out           filled with the acceleration and the motor-brake share;
     *                      pass {@code null} when only the acceleration is wanted.
     *                      The share is reported rather than recomputed by the
     *                      caller because it depends on the gear, the gradient and
     *                      what the supply could deliver, all decided here.
     */
    public static double gearAcceleration(double signedSpeedMs, double gradeToPlusX, int carriages,
                                          double powerScale, TrainGear gear, double cruiseSpeedMs,
                                          double brakeMs2, boolean emergency,
                                          GearStep out) {
        if (out != null)
            out.clear();
        double v = signedSpeedMs;
        double speed = Math.abs(v);
        double gamma = CEEConfigs.server().resistanceValues.electricTrainRotatingMassFactor.get();
        // Effective mass, for inertia: a train has to accelerate its own rotating
        // parts too, so gamma is added here and every force below is divided by it.
        double mass = (1d + gamma) * totalMass(carriages);
        if (mass <= 0d)
            return 0d;

        // Drag opposes motion and vanishes at a standstill.
        double force = -Math.signum(v) * runningResistance(speed, carriages);
        // Gravity pulls down the slope, i.e. towards -x for a climb towards +x.
        //
        // Deliberately gradeResistance() rather than mass*G*grade: gravity acts on the
        // REAL mass, not the effective one, because the rotating parts do not weigh
        // more just because they also have to be spun up. Using the effective mass
        // here - as this originally did - overstated gravity by (1+gamma) = 6%, which
        // made every slope in the gear law 6% steeper than the same slope in
        // availableAcceleration, maxSustainableSpeed and the HUD, all of which go
        // through gradeResistance. Sharing the function is what keeps them consistent;
        // a second copy of the expression is what let them drift.
        force -= gradeResistance(gradeToPlusX, carriages);

        double effort = availableEffort(speed, carriages, powerScale);
        // There is no emergency-speed-cap term here any more, and its absence is deliberate.
        //
        // The cap is enforced on Train.maxSpeed() instead, because after an emergency stop the
        // train always ends up under CREATE's controls: the stop lowers the pantographs, the driver
        // is barred from raising them until the next station, so the speed gate hands the train
        // over. Create computes its target from maxSpeed(), so that is the one place a cap is
        // obeyed by both schemes. Enforcing it here as well would have been a second copy that
        // could silently disagree with the first.
        double ceiling = designMaxSpeed();
        // Which way the train is going, or would go if released from rest. Used
        // only to aim traction and the brake; gravity never needs it.
        double heading = speed < 1e-6d ? 1d : Math.signum(v);

        // The direction the BRAKE should push at a standstill.
        //
        // A brake opposes motion, and at a standstill there is none - so it has to
        // oppose the direction the train is about to move instead, which is the way
        // gravity is pulling. Using `heading` here was wrong in a way that only shows
        // on a gradient: heading is +1 at rest regardless of the slope, so on an
        // uphill the brake pushed towards -x, the same direction as gravity, and a
        // train that could not hold slid away FASTER than gravity alone would take it
        // - the brake behaving as a downhill assist. On a downhill it happened to
        // look right, which is why it survived the earlier tests: they only ever
        // checked the descent. Verified by simulation in _cache/calc_max_grade.py,
        // which reproduces the runaway and now agrees with the closed form.
        //
        // Zero on level ground at rest, which is correct: there is nothing to hold
        // against, so the brake has no direction to push.
        double brakeHeading = speed < 1e-6d ? -Math.signum(gradeToPlusX) : Math.signum(v);

        // Braking is blended rather than a single number, and the blend depends on
        // speed: the electric part fades out as the train slows, the friction part
        // does not. `brakeMs2` is the caller's friction setting, which is what the
        // config means; the electric contribution is added on top. See the block
        // comment above serviceBrakeDeceleration for why this is not one figure.
        // The motor brake is a MOTOR brake: it retards the train by working the motors against the
        // direction of travel, so it needs the supply exactly as traction does. Scaling it by the
        // supply is what makes that true in the model.
        //
        // Without this the electric share of the brake was applied at full effect even with no
        // supply at all, because nothing in the brake path ever consulted powerScale - only the
        // traction path did. A train that had lost its wire would therefore brake harder than the
        // friction alone can account for, which is not a stop it could really make. The friction
        // part is untouched and remains available with no power, which is the whole reason the
        // brake is kept while coasting.
        double dynamicPart = dynamicBrake() * dynamicBrakeFade(speed) * powerScale;
        // Blended, not stacked.
        //
        // It used to be brakeMs2 + dynamicPart, so turning the regenerative brake on made the train brake
        // HARDER than the driver asked for: 0.8 service + 0.5 electric = 1.3 m/s^2. A real unit blends the
        // two to the commanded total, and the electric half simply takes over part of the work.
        //
        // max() is that blend for the case that matters: the commanded brake is the floor, and if the
        // electric brake can produce more than friction alone it may, but never by silently adding itself
        // to what the driver selected.
        double brakeTotal = Math.max(brakeMs2, dynamicPart);
        // Accumulated as the motor brake is applied, so the caller knows how much
        // of the stop the motors are doing and can regenerate only that share.
        double dynamicApplied = 0d;
        // The world-frame force the lever's braking contributed, so the emergency
        // brake can REPLACE it rather than pile on top. See the block after the
        // switch.
        double leverBrakeForce = 0d;

        // An emergency brake takes the traction off. Every real one does - it is a
        // brake pipe application, and on an electric train it trips the traction
        // contactors - and it has to here or the lever would decide whether the
        // train can be stopped at all. With the power left on, a train at
        // ACCELERATE on a descent kept its motors pulling against the brake, and on
        // the steepest grade that out-pulled it: the harness showed an emergency
        // stop from 100 km/h on a 1-in-3 descent that never terminated.
        boolean tractionCut = emergency && speed > 1e-6d;

        switch (gear) {
            case ACCELERATE -> {
                if (!tractionCut) {
                    if (speed < ceiling) {
                        force += effort;
                    }
                    // At or above the ceiling: NO traction and NO brake, which is what
                    // "accelerate to top speed and then hold it" means.
                    //
                    // This used to apply the service brake above the ceiling, to stop a
                    // descent ignoring the emergency speed cap. That fixed the cap and
                    // broke the top of the speed range: from about 70 m/s the remaining
                    // net thrust is small but positive, so the train overshot 100 m/s by
                    // a hair, the brake slammed on at 0.8 m/s^2, it fell back below the
                    // ceiling and accelerated again. The driver saw the train
                    // oscillating between accelerating and braking - "蹦迪" - and every
                    // overshoot wasted the acceleration it had just spent.
                    //
                    // The design ceiling is still enforced without the oscillation, but NOT by
                    // drag - it is enforced by the `speed < ceiling` test immediately above,
                    // which stops adding tractive effort once the train is at its design speed.
                    //
                    // An earlier version of this comment claimed "drag alone holds the train
                    // there, because the ceiling is derived from the same drag". That is not true
                    // of the configured figures: with the defaults the tractive effort equals the
                    // running resistance at about 115 m/s, well ABOVE the 100 m/s ceiling. Without
                    // the test above the train would run on to 115 m/s. (Verified numerically in
                    // _cache/verify_physics_spec.py.) Overshoot is still bounded by one tick of
                    // motion, and nothing brakes a train that is merely at its design speed.
                    //
                    // That reasoning holds ONLY for the design ceiling. It does NOT hold for a
                    // ceiling imposed from outside - the emergency cap or a curve - because
                    // nothing about travelling at 100 m/s makes drag hold a train to
                    // 11 m/s on a curve. Both of those are enforced by the targeted
                    // blocks below instead, which brake a train that is genuinely over
                    // the limit and leave one that is not alone. That split is the
                    // point: "at the ceiling" and "over the ceiling" are different
                    // problems, and only the second needs the brake.
                }
            }
            case REVERSE -> {
                // The reverse position is two different things depending on what the
                // train is doing, which is what makes it the strongest brake on the
                // train as well as the only reverse gear.
                //
                // 静止挂入 = 倒车挡: from a standstill with nothing pulling the train
                // forward, the motors drive it backwards. No brake, so gravity gets to
                // help on a downhill and the shunt can be brisk - hence the higher cap.
                //
                // 向前运动 / 停止但有下坡前溜趋势 = 制动 + 电机反拖: the lever becomes a
                // brake, and the motors drive in reverse against the motion on top of
                // the friction pads. Because a powered motor holds torque at zero
                // speed, unlike a generator, this is the one position that can hold a
                // stationary train on a grade far steeper than the pads alone.
                // Gravity's pull along +x, as a force on the REAL mass.
                double gravityTowardForward = -gradeResistance(gradeToPlusX, carriages);
                boolean forwardTendency = v > 1e-6d
                        || (speed < 1e-6d && gravityTowardForward > 0d);
                if (forwardTendency) {
                    if (speed > 1e-6d) {
                        // MOVING: the motors are being back-driven by the train, so they
                        // generate. Same blended friction-plus-motor brake as the BRAKE
                        // position, and the motor share is fed back to the line.
                        //
                        // This used to drive the motors against the motion instead, which
                        // is the physical opposite: a back-driven machine is a generator,
                        // and forcing current through it to oppose its own rotation draws
                        // full power from the catenary while recovering nothing. The
                        // driver reported exactly that - the reverse position stopping the
                        // train with the traction at full draw and no regeneration.
                        leverBrakeForce = -brakeHeading * brakeTotal * mass;
                        force += leverBrakeForce;
                        dynamicApplied = dynamicPart;
                    } else {
                        // STOPPED with a downhill tendency: now the motor really is
                        // POWERED and holding torque, which is the one case that cannot
                        // regenerate - a stationary machine has no back-EMF to work
                        // against, so holding costs line current. This is what makes
                        // REVERSE the strongest hold on the train, and it is the only
                        // case where drawing power to stand still is correct.
                        double motorHold = motorReverseBrake(0d, carriages, powerScale);
                        leverBrakeForce = -brakeHeading * (brakeMs2 + motorHold) * mass;
                        force += leverBrakeForce;
                    }
                } else if (!tractionCut && speed < Math.min(ceiling, reverseMaxSpeed())) {
                    force -= effort;
                }
            }
            case CRUISE -> {
                // The held speed is capped by the design ceiling. The emergency cap is NOT applied
                // here - it lives on Train.maxSpeed(), which is what Create obeys once the train has
                // been handed over.
                double target = Math.min(Math.max(cruiseSpeedMs, 0d), ceiling);
                // Commanded to STOP, which is how an automatic arrival at a station
                // finishes: Create's navigation zeroes its target speed once the train is
                // inside the braking distance.
                //
                // This has to be handled as its own case rather than left to the
                // deadband below, because the deadband is a BAND and the hold branch
                // inside it applies whatever force equals drag - so a target of zero
                // would have the motors hold the train at the edge of the band, creeping
                // towards the platform at a walking pace and never arriving. Braking to a
                // genuine stand and then holding it is what an approach needs, and it is
                // also what the driver expects of a lever set to hold zero.
                if (target < CRUISE_DEADBAND) {
                    if (speed > 1e-6d) {
                        leverBrakeForce = -brakeHeading * brakeTotal * mass;
                        force += leverBrakeForce;
                        dynamicApplied = dynamicPart;
                    } else {
                        // At a stand. Friction only, aimed by brakeHeading exactly as the
                        // BRAKE position aims it: on the level brakeHeading is zero and
                        // NOTHING is applied, so a train held at zero has no force on it
                        // and cannot be walked off by a rounding error; on a gradient it
                        // pushes against the fall, which is what actually keeps a stopped
                        // train stopped. No motors here, because a stationary machine has
                        // no back-EMF and holding with one would draw line current to
                        // stand still.
                        force -= brakeHeading * frictionBrake() * mass;
                    }
                } else if (speed < target - CRUISE_DEADBAND) {
                    // Holding a speed takes traction; an emergency brake takes that
                    // away and lets the brake do the work instead, exactly as it does
                    // in the other positions.
                    if (!tractionCut && speed < ceiling)
                        force += heading * effort;
                } else if (speed > target + CRUISE_DEADBAND || tractionCut) {
                    leverBrakeForce = -brakeHeading * brakeTotal * mass;
                    force += leverBrakeForce;
                    dynamicApplied = dynamicPart;
                } else {
                    // Hold the setting exactly. The world-frame force needed to
                    // cancel drag and gravity is
                    //     sign(v)*drag + m*g*grade
                    // which is positive on a climb and negative on a descent steep
                    // enough to overcome drag. Positive is met by the motors, up to
                    // their limit; a negative result means the brake has to take up
                    // the surplus. That surplus is met by the motors in reverse
                    // first - regeneration, which costs nothing and recovers energy -
                    // and only then by friction. That is the "climbing at full power
                    // and braking on the way down" behaviour, and it is also why the
                    // descent case draws no power rather than feeding the line hard.
                    // gradeResistance, not mass*G*grade. Gravity acts on the REAL mass; the effective mass
                    // includes the rotating parts, which do not weigh more on a slope. Every other grade
                    // term in this file already goes through gradeResistance and the comment at 574-582
                    // says why - this branch was the one place that did not, and it read 6% high because
                    // of it.
                    double needed = Math.signum(v) * runningResistance(speed, carriages)
                            + gradeResistance(gradeToPlusX, carriages);
                    if (needed >= 0d) {
                        force += Math.min(needed, effort);
                    } else {
                        double brakeNeed = -needed;
                        // The electric brake is capped by both what it can absorb at
                        // this speed and what the motors are rated for; the pads make
                        // up whatever is left.
                        double dynamicAvail = Math.min(dynamicPart * mass, effort);
                        double fromMotors = Math.min(brakeNeed, dynamicAvail);
                        double fromFriction = Math.min(brakeNeed - fromMotors, brakeMs2 * mass);
                        leverBrakeForce = -(fromMotors + fromFriction);
                        force += leverBrakeForce;
                        dynamicApplied = fromMotors / mass;
                    }
                }
            }
            case COAST -> {
                // Nothing but drag and gravity, which is the point of the position:
                // it rolls on a slope and holds nothing, including at a standstill.
                // Note the electric brake is NOT applied here: cutting the power
                // means cutting it, and this is the position that is meant to roll.
            }
            case BRAKE -> {
                leverBrakeForce = -brakeHeading * brakeTotal * mass;
                force += leverBrakeForce;
                dynamicApplied = dynamicPart;
            }
        }

        // The emergency speed cap used to be enforced here, by braking a train already above it.
        //
        // It is gone on purpose, not by oversight. The cap now lives on Train.maxSpeed(), which is
        // the one figure both control schemes ask, because after an emergency stop the train is
        // always Create's: the stop lowers the pantographs and the driver is barred from raising
        // them until the next station, so the speed gate hands the train over and this lever is no
        // longer the thing driving it. Enforcing the cap in both places would have been two copies
        // that could disagree, and the one here could never have been reached.
        //
        // This leaves no externally imposed ceiling in the model at all. Curves deliberately do
        // not have one either: see TrainMixin.electroEnergetics$maxTurnSpeed for why an electric
        // train takes a curve at the same speed as straight track, and for the round in which I
        // wrongly added a limit here and had to take it back out.

        // Emergency braking REPLACES the lever's braking instead of adding to it.
        //
        // Whichever the lever was applying is undone here and the emergency rate put in its place,
        // so the position of the lever does not change how hard the emergency brake bites. Adding
        // them gave 3.5 + 4.0 = 7.5 m/s^2, roughly twice what any real train can do and a stopping
        // distance to match.
        //
        // The rate is built from its two parts rather than taken from emergencyBrakeDeceleration,
        // because the MOTOR half has to be scaled by the supply. That method returns the full
        // electric share unconditionally, which is correct for a reading taken with power available
        // but wrong for the physics here: with no supply the motor brake cannot contribute at all,
        // and leaving it in would give a train that had lost its wire more braking than it can
        // really produce. `dynamicPart` already carries the supply scaling.
        if (emergency && speed > 1e-6d) {
            force -= leverBrakeForce;
            // The emergency brake is the MOTORS at full power in reverse, plus the friction brake.
            //
            // It used to be friction*1.5 + the dynamic brake. That was wrong twice over. The 1.5 factor
            // asked the wheels for more adhesion than they have, and the dynamic brake is the REGENERATIVE
            // brake - an emergency application must not return energy to the line, it must draw it.
            //
            // motorReverseBrake() is already scaled by powerScale, so a train that has lost its wire
            // contributes nothing from the motors and stops on friction alone. That falls out of the
            // model rather than being special-cased, which is why the "no power, no emergency brake"
            // rule needs no test here: REVERSE cannot be selected without a supply in the first place.
            double motorEmergency = motorReverseBrake(speed, carriages, powerScale);
            force -= Math.signum(v) * (frictionBrake() + motorEmergency) * mass;
            dynamicApplied = 0d;
        }

        double a = force / mass;

        // At a standstill, a brake that can cover the slope pins the train instead
        // of leaving a residual creep from the gravity term. If it cannot cover the
        // slope the value is left alone and the train slides, which is honest and
        // is the whole reason the friction figure matters so much here.
        //
        // What counts toward holding depends on the position, and the distinction is
        // physical rather than bookkeeping:
        //   BRAKE   friction only. The regenerative part has already faded to zero by
        //           the time the train is stopped, because a generator's back-EMF has
        //           collapsed; crediting it would let a train appear to hold a grade
        //           it would really roll down.
        //   REVERSE friction PLUS the motor brake. This is the position where the
        //           motors are driven, not back-driven, and a powered motor holds its
        //           torque at zero speed. That is exactly how a real drive holds a
        //           train on a grade, and it is why this position is the strongest
        //           hold on the train.
        if (speed < 1e-6d && a != 0d) {
            // Gravity's contribution to the ACCELERATION, which is what this test
            // compares against: gradeResistance is a force on the real mass, and the
            // equation of motion divides every force by the effective mass, so the
            // pull appears as g*grade/(1+gamma). Dividing by (1+gamma) is not a fudge
            // - the rotating parts genuinely resist being accelerated down the slope
            // - and leaving it out made the test 6% stricter than the physics, so a
            // train would have been told it could not hold a grade it could.
            double pull = Math.abs(G * gradeToPlusX) / (1d + gamma);
            double hold = 0d;
            boolean braked = false;
            if (gear == TrainGear.BRAKE) {
                hold = frictionBrake();
                braked = true;
            } else if (gear == TrainGear.REVERSE) {
                // Only when REVERSE is acting as a BRAKE, which it is only if the
                // train is stopped with a downhill tendency - the spec's
                // "停止但有下坡前溜趋势时挂入就是制动 + 发动机反向制动". Otherwise REVERSE is a
                // reverse GEAR, and a gear drives; it does not hold.
                //
                // Crediting the hold unconditionally was a bug that made reverse
                // completely unusable from rest on level ground: pull is zero there,
                // so "hold >= pull" was trivially true, the method returned 0 before
                // the reverse branch could apply any effort, and the train stayed
                // pinned forever. Reproduced in _cache/verify_reported_bugs.py. On a
                // descent the test still fires exactly as before, because that is the
                // case where REVERSE really is holding the train.
                boolean gravityPullsForward = -gradeResistance(gradeToPlusX, carriages) > 0d;
                if (gravityPullsForward) {
                    hold = frictionBrake() + motorReverseBrake(0d, carriages, powerScale);
                    braked = true;
                }
            }
            if (emergency) {
                // Same pairing as the moving case: friction plus the motors holding in reverse.
                hold = Math.max(hold, frictionBrake() + motorReverseBrake(0d, carriages, powerScale));
                braked = true;
            }
            if (braked && hold >= pull) {
                if (out != null)
                    out.dynamicBrake = 0d;   // stopped: no regenerative brake, by definition
                return 0d;
            }
        }

        if (out != null) {
            out.acceleration = Double.isFinite(a) ? a : 0d;
            out.dynamicBrake = dynamicApplied;
        }
        return Double.isFinite(a) ? a : 0d;
    }

    /**
     * Convenience form for callers that only want the acceleration and not the
     * motor-brake share. The full form taking a {@link GearStep} is what the
     * driving path uses, because regeneration has to know that share.
     *
     * <p>No curve limit, so this is for callers with no track geometry to hand - a
     * harness, or a readout that is not tied to where the train is standing.
     */
    public static double gearAcceleration(double signedSpeedMs, double gradeToPlusX, int carriages,
                                          double powerScale, TrainGear gear, double cruiseSpeedMs,
                                          double brakeMs2, boolean emergency) {
        return gearAcceleration(signedSpeedMs, gradeToPlusX, carriages, powerScale, gear,
                cruiseSpeedMs, brakeMs2, emergency, null);
    }

    /**
     * Electrical power the motors can push back into the line while braking [W].
     *
     * <p>The retarding force is the same inertia term the train accelerates
     * against, {@code (1+γ)·m·a}, and the mechanical power that has to go
     * somewhere is {@code F·v}. A real EMU converts a share of that back to
     * electricity through the motors and the converter; the rest is friction
     * (brake pads blending in) and losses. {@code regenFraction} is that share.
     *
     * <p>There is no speed fade in this conversion. Recovery does fall away as the
     * train slows, but that already lives in {@link #dynamicBrakeFade}: a motor's
     * back-EMF is proportional to speed, so the electric brake itself fades out and
     * a real train finishes every stop on friction. Fading a second time here would
     * square the effect and kill recovery far sooner than a real train's does,
     * which is why the caller passes only the motor share that was actually
     * applied rather than a nominal rate.
     *
     * @param speedMs        current speed [m/s]; sign is ignored
     * @param electricDecelerationMs2 deceleration contributed by the MOTOR brake
     *                      alone [m/s²], positive. Friction must not be included:
     *                      brake pads dissipate their energy as heat and recover
     *                      nothing, so only the motors' share can come back.
     * @param regenFraction  share of that power the motors recover, 0-1; the rest
     *                      is motor, converter and gear loss
     * @return recoverable power [W], zero when not braking meaningfully
     */
    public static double regenerativePower(double speedMs, double electricDecelerationMs2,
                                           int carriages, double regenFraction) {
        double v = Math.abs(speedMs);
        if (v <= 0d || electricDecelerationMs2 <= 0d || regenFraction <= 0d)
            return 0d;

        // No speed fade here. The electric brake already fades with speed in
        // dynamicBrakeFade, because that is where the physics lives - a motor's
        // back-EMF collapses as it slows. Fading a second time in this conversion
        // would square the effect and make recovery fall away far faster than a
        // real train's does.
        double retardingForce = inertiaForce(electricDecelerationMs2, carriages);
        double mechanical = retardingForce * v;

        // The motors cannot absorb more than they are rated for. Without this cap
        // a single carriage braking from 100 m/s would report ~18 MW against a
        // 1.375 MW rating, because the retarding force needed for a hard stop at
        // that speed is far beyond what the motors can convert. Real trains blend
        // in the friction brakes for exactly this reason: the motors take what they
        // can and the pads dissipate the rest, which is also why recovery is poor
        // during hard stops from high speed.
        double rated = ratedElectricalPower(carriages);

        double power = Math.min(mechanical * Math.min(regenFraction, 1d), rated);
        return Double.isFinite(power) && power > 0d ? power : 0d;
    }
}
