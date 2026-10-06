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
     * Fastest a train may take a curve of this radius without tipping its passengers
     * over, from the balance of centripetal and gravitational acceleration [m/s].
     *
     * <p>{@code v = sqrt(g · r · tan(θ))}, the standard cant-deficiency formula, with
     * {@code θ} a permitted UNCOMPENSATED lateral acceleration rather than a real
     * track cant - Minecraft has no cant, so this is the whole of the allowance. At
     * 0.65 m/s² - about the comfortable limit for standing passengers, and roughly what
     * a real railway allows before it starts tilting bodies or slowing trains down -
     * the figures are:
     *
     * <pre>
     *   radius  20 m (a tight Create curve)  ->  11.3 m/s  (41 km/h)
     *   radius 50 m                          ->  17.9 m/s  (64 km/h)
     *   radius 100 m                         ->  25.0 m/s  (90 km/h)
     *   radius 300 m                         ->  43.3 m/s (156 km/h)
     *   radius 1000 m                        ->  79.1 m/s (285 km/h)
     * </pre>
     *
     * <p>This exists because the mod previously removed Create's curve limit outright
     * ({@code Train.maxTurnSpeed} returns a flat 14-20 m/s for its own trains, and the
     * electric override replaced that with the design ceiling). That was reported as a
     * bug in its own right: people standing on a train were thrown out of it, and the
     * cause is that Create carries a rider by their contact point, whose per-tick
     * displacement on a curve scales with the YAW RATE - which a train taking a tight
     * curve at 100 m/s makes enormous. A curve limit is therefore not a Create
     * formality to be discarded; it is the thing that keeps a rider on the train.
     *
     * <p>Returned in Blocks/Second, like every other speed in this model.
     */
    public static double curveSpeedLimit(double radiusBlocks) {
        if (!(radiusBlocks > 0d) || !Double.isFinite(radiusBlocks))
            return Double.MAX_VALUE;
        double lateral = Math.max(
                CEEConfigs.server().trainValues.electricTrainCurveLateralLimit.get(), 1e-3d);
        return Math.sqrt(G * radiusBlocks * lateral);
    }

    /**
     * Whether a train at this speed has arrived at the ceiling it is allowed, so
     * ACCELERATE should hand over to CRUISE.
     *
     * <p>A small tolerance below the ceiling rather than an exact equality, because the
     * speed approaches the ceiling asymptotically as drag eats the remaining thrust and
     * will never equal it exactly. Too tight and the lever never moves; too loose and
     * ACCELERATE gives up early. 0.5 m/s below 100 is half a percent, reached within a
     * few ticks of the ceiling and far below anything a driver could notice.
     *
     * <p>Penalty-aware: with the emergency cap in force the ceiling is 40 km/h, and
     * ACCELERATE has to hand over there rather than at the design speed. Handing over at
     * the wrong one would leave the lever in ACCELERATE above a cap it can never pull
     * past, which is the same oscillation the handover exists to remove.
     */
    public static boolean atDesignCeiling(double speedMs, int carriages, boolean penalty) {
        double ceiling = penalty ? Math.min(designMaxSpeed(), PENALTY_SPEED) : designMaxSpeed();
        return speedMs >= ceiling - CEILING_HANDOVER_BAND;
    }

    /** How far below the design ceiling still counts as having reached it [m/s]. */
    private static final double CEILING_HANDOVER_BAND = 0.5d;

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
        double v = Math.abs(speed);
        double needed = runningResistance(v, carriages)
                + gradeResistance(grade, carriages)
                + inertiaForce(acceleration, carriages);

        var res = CEEConfigs.server().resistanceValues;
        double margin = Math.max(res.electricTrainMarginFactor.get(), 1e-4d);
        double auxFactor = Math.max(res.electricTrainAuxiliaryLoadFactor.get(), 1e-4d);

        double tractionPower;
        if (needed <= 0d) {
            // Gravity alone is enough to keep rolling; the motors draw nothing
            // useful and only the auxiliaries load the supply.
            tractionPower = 0d;
        } else {
            double deliverable = availableEffort(v, carriages, powerScale);
            double effort = Math.min(needed, deliverable);
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
     * Speed cap left in force after the emergency brake is used [m/s], until the
     * next station call. This is the cost of using it.
     */
    public static final double PENALTY_SPEED = 40d / 3.6d;   // 40 km/h

    /**
     * Hysteresis band above the penalty cap before the brake is applied again [m/s].
     *
     * <p>Without it the penalty would enforce itself by braking, then stop braking the
     * instant the train dropped under the cap, then brake again - the same
     * accelerate/brake oscillation the ceiling used to cause, just at 40 km/h instead
     * of at top speed. One metre per second is well under the 0.8 m/s^2 the brake can
     * remove in a tick, so the band is closed within a tick or two and cannot be felt.
     */
    private static final double CAP_MARGIN = 1d;

    /**
     * How much more adhesion the emergency brake can find than the service brake.
     *
     * <p>Emergency braking is not a different mechanism, it is the same friction
     * brake run at the limit of adhesion with sanding, so the extra is a modest
     * factor rather than the order-of-magnitude jump a separate "emergency rate"
     * would imply. Real figures are around 1.3-1.5x the service rate.
     */
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

    /**
     * Total emergency braking rate at this speed [m/s²]: maximum adhesion plus the
     * electric brake.
     */
    public static double emergencyBrakeDeceleration(double speedMs) {
        return frictionBrake() * EMERGENCY_ADHESION_FACTOR
                + dynamicBrake() * dynamicBrakeFade(speedMs);
    }

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
     * @param penalty       whether the post-emergency speed cap is in force
     * @param curveLimitMs  fastest this train may take the curve it is on, or
     *                      {@code <= 0} for no limit. Supplied by the caller because
     *                      the law has no access to the track geometry, which lives on
     *                      the carriages.
     * @param out           filled with the acceleration and the motor-brake share;
     *                      pass {@code null} when only the acceleration is wanted.
     *                      The share is reported rather than recomputed by the
     *                      caller because it depends on the gear, the gradient and
     *                      what the supply could deliver, all decided here.
     */
    public static double gearAcceleration(double signedSpeedMs, double gradeToPlusX, int carriages,
                                          double powerScale, TrainGear gear, double cruiseSpeedMs,
                                          double brakeMs2, boolean emergency, boolean penalty,
                                          double curveLimitMs, GearStep out) {
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
        double ceiling = designMaxSpeed();
        // The emergency brake leaves a speed cap behind until the next station, which
        // is what makes using it a real decision rather than a free extra stop. The
        // cap lowers the ceiling rather than clamping train.speed: a clamp would be a
        // teleport the physics never agreed to, and it would also stop the train
        // dead the instant it crossed the cap. Lowering the ceiling instead means the
        // train runs out of traction there and, if it is already over the cap, is
        // braked down to it - see the ACCELERATE and CRUISE branches, which apply the
        // brake when they are above the ceiling. Without that second part a descent
        // simply ignored the penalty: the harness showed a train on a 1-in-3 grade
        // accelerating to 1762 km/h with the cap supposedly in force.
        if (penalty)
            ceiling = Math.min(ceiling, PENALTY_SPEED);
        // A curve lowers the ceiling exactly as the emergency penalty does, and for the
        // same reason it is done by lowering the ceiling rather than clamping the speed:
        // the train runs out of traction above it and is braked down to it, where a clamp
        // would be a teleport the physics never agreed to.
        //
        // This has to happen HERE, in the law, and not only in Train.maxTurnSpeed. That
        // method is consulted by Create's own control path and by Navigation, but the
        // lever does not go through either - it runs this law, whose ceiling was the flat
        // design figure. So the curve limit added for the lever's benefit was, for a
        // lever-driven train, dead code: a driver could take a 20 m curve at 100 m/s with
        // nothing objecting, which is precisely the case that throws people off the train.
        if (curveLimitMs > 0d)
            ceiling = Math.min(ceiling, curveLimitMs);
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
        double dynamicPart = dynamicBrake() * dynamicBrakeFade(speed);
        double brakeTotal = brakeMs2 + dynamicPart;
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
                    // The design ceiling is still enforced without the oscillation: drag
                    // alone holds the train there, because the ceiling is derived from
                    // the same drag. Overshoot is bounded by one tick of motion and
                    // nothing brakes a train that is merely at its design speed.
                    //
                    // That reasoning holds ONLY for the design ceiling, where drag is
                    // what stops the train running away. It does NOT hold for a ceiling
                    // imposed from outside - the emergency cap or a curve - because
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
                // The held speed is capped by the ceiling, which is how the emergency
                // penalty applies here: with the cap in force cruise holds 40 km/h
                // rather than braking only below whatever the driver set. Without
                // this the penalty would be honoured going uphill and quietly ignored
                // on a descent, where the set speed exceeds it.
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
                    double needed = Math.signum(v) * runningResistance(speed, carriages)
                            + mass * G * gradeToPlusX;
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

        // An externally imposed ceiling is enforced on its own terms rather than by
        // braking at the ceiling itself.
        //
        // Only a train that is ALREADY above such a limit is braked, and only until it is
        // back under it. That distinction is what separates "a limit is in force" from
        // "the train has reached its design speed": at the design ceiling the remaining
        // thrust is a fraction of a m/s^2, so a brake there fights a train doing nothing
        // wrong and produces the accelerate/brake oscillation described above. A train
        // genuinely over a limit needs slowing, and braking is the only way the limit is
        // honoured on a descent, where the motors are not what is pushing it.
        //
        // Two limits reach here, and both are limits nothing else enforces:
        //
        //   the emergency cap  - 40 km/h until the next station call
        //   a curve            - v = sqrt(g*r*lateral), which a train entering too fast
        //                        must be brought down to. Skipping this left the curve
        //                        limit with no effect on a lever-driven train at all,
        //                        because the ACCELERATE branch deliberately applies no
        //                        brake at or above a ceiling and nothing else did either.
        //
        // The margin is hysteresis, so a train sitting exactly on a limit is not braked
        // and released one tick at a time. Expressed as a fraction of the DESIGN ceiling
        // rather than a fixed m/s, so it stays proportionate on a curve whose limit may
        // be an order of magnitude below the design speed.
        double enforcedCeiling = designMaxSpeed();
        if (penalty)
            enforcedCeiling = Math.min(enforcedCeiling, PENALTY_SPEED);
        if (curveLimitMs > 0d)
            enforcedCeiling = Math.min(enforcedCeiling, curveLimitMs);

        // Only when the limit is genuinely below the design ceiling: at the design
        // ceiling itself there is nothing to enforce, which is the case the ACCELERATE
        // branch above deliberately leaves to drag.
        boolean limitInForce = enforcedCeiling < designMaxSpeed() - 1e-9d;
        boolean overLimit = speed > enforcedCeiling + Math.max(CAP_MARGIN, enforcedCeiling * 0.02d);

        // Skipped when the lever is already braking at least this hard: in that case
        // the driver's own brake is doing the work, and replacing it would throw away
        // the motor share and with it the regeneration.
        if (limitInForce && !emergency && overLimit) {
            double limitBrakeForce = brakeTotal * mass;
            if (Math.abs(leverBrakeForce) < limitBrakeForce) {
                force -= leverBrakeForce;                 // drop whatever the lever applied
                leverBrakeForce = -brakeHeading * limitBrakeForce;
                force += leverBrakeForce;
                dynamicApplied = dynamicPart;
            }
        }

        // Emergency braking REPLACES the lever's braking instead of adding to it.
        //
        // It is not a second brake bolted alongside the service one: it is the same
        // friction brake taken to the limit of adhesion with sanding. Adding them
        // gave 3.5 + 4.0 = 7.5 m/s^2, roughly twice what any real train can do and a
        // stopping distance to match. Whichever the lever was applying is undone
        // here and the emergency rate put in its place, so the position of the lever
        // does not change how hard the emergency brake bites.
        if (emergency && speed > 1e-6d) {
            force -= leverBrakeForce;
            force -= Math.signum(v) * emergencyBrakeDeceleration(speed) * mass;
            dynamicApplied = dynamicPart;
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
                hold = Math.max(hold, frictionBrake() * EMERGENCY_ADHESION_FACTOR);
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
                                          double brakeMs2, boolean emergency, boolean penalty) {
        return gearAcceleration(signedSpeedMs, gradeToPlusX, carriages, powerScale, gear,
                cruiseSpeedMs, brakeMs2, emergency, penalty, 0d, null);
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
