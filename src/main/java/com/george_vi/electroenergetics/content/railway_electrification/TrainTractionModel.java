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

    /**
     * Retarding acceleration with the motors off and no brakes applied [m/s²].
     *
     * <p>This is what a train coasts down at: running resistance alone, divided
     * by the effective mass. It is what makes coasting and braking different
     * things. At 100 m/s a single carriage sheds about 0.16 m/s² and needs
     * roughly ten minutes to coast to a stand, which is why a real train coasts
     * for kilometres and why the driver uses the brake to stop at all.
     */
    public static double coastDeceleration(double speedMs, double grade, int carriages) {
        double v = Math.abs(speedMs);
        double resisting = runningResistance(v, carriages) + gradeResistance(grade, carriages);
        if (resisting <= 0d)
            return 0d;
        double gamma = CEEConfigs.server().resistanceValues.electricTrainRotatingMassFactor.get();
        double a = resisting / ((1d + gamma) * totalMass(carriages));
        return Double.isFinite(a) && a > 0d ? a : 0d;
    }

    // ------------------------------------------------------------------
    // Regenerative braking
    // ------------------------------------------------------------------

    /**
     * Electrical power the motors can push back into the line while braking [W].
     *
     * <p>The retarding force is the same inertia term the train accelerates
     * against, {@code (1+γ)·m·a}, and the mechanical power that has to go
     * somewhere is {@code F·v}. A real EMU converts a share of that back to
     * electricity through the motors and the converter; the rest is friction
     * (brake pads blending in) and losses. {@code regenFraction} is that share.
     *
     * <p>It fades out below {@code regenMinSpeed}. This is not a convenience: a
     * motor's back-EMF is proportional to speed, so as the train slows there is
     * progressively less voltage to push current against, and at a standstill
     * there is none at all - a real train finishes the stop on friction alone.
     * Modelling that also stops this from claiming recovery during the last few
     * metres of every stop, where the power would be significant while the
     * physics says it cannot happen.
     *
     * @param speedMs        current speed [m/s]; sign is ignored
     * @param decelerationMs2 braking rate actually being applied [m/s²], positive
     * @param regenFraction  share of the braking power the motors recover, 0-1
     * @param regenMinSpeed  speed below which recovery fades to nothing [m/s]
     * @return recoverable power [W], zero when not braking meaningfully
     */
    public static double regenerativePower(double speedMs, double decelerationMs2, int carriages,
                                           double regenFraction, double regenMinSpeed) {
        double v = Math.abs(speedMs);
        if (v <= 0d || decelerationMs2 <= 0d || regenFraction <= 0d)
            return 0d;

        // Linear fade over the last stretch before regenMinSpeed, so the power
        // falls away smoothly instead of switching off at a threshold.
        double fade = regenMinSpeed <= 0d ? 1d : Math.min(v / regenMinSpeed, 1d);

        double retardingForce = inertiaForce(decelerationMs2, carriages);
        double mechanical = retardingForce * v;

        // The motors cannot absorb more than they are rated for. Without this cap
        // a single carriage braking from 100 m/s would report ~18 MW against a
        // 1.375 MW rating, because the retarding force needed for a 3.5 m/s^2 stop
        // at that speed is far beyond what the motors can convert. Real trains
        // blend in the friction brakes for exactly this reason: the motors take
        // what they can and the pads dissipate the rest, which is also why
        // recovery is poor during hard stops from high speed.
        double rated = ratedElectricalPower(carriages);

        double power = Math.min(mechanical * Math.min(regenFraction, 1d), rated) * fade;
        return Double.isFinite(power) && power > 0d ? power : 0d;
    }
}
