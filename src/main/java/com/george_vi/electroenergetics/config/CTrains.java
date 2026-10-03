package com.george_vi.electroenergetics.config;

import net.createmod.catnip.config.ConfigBase;

public class CTrains extends ConfigBase {

    // ------------------------------------------------------------------
    // Realistic traction model.
    // 1 block = 1 metre, so Blocks/Second is m/s and Blocks/Second² is m/s².
    //
    // The train is modelled as a real electric locomotive:
    //   * constant tractive EFFORT below the base speed,
    //   * constant tractive POWER above it,
    //   * speed on a gradient is whatever the rating can sustain against
    //     both running resistance and the gravity component,
    //   * curves are limited by a comfortable lateral acceleration.
    // ------------------------------------------------------------------

    public final ConfigFloat electricTrainMaxSpeed = f(97.222f, 1f, "electricTrainMaxSpeed",
            "Operating top speed of the rolling stock. A hard ceiling: on a gradient or in a curve the train is limited below it. 97.222 Blocks/Second = 350 km/h. [in Blocks / Second]");

    public final ConfigFloat electricTrainPowerPerCarriage = f(1_375_000f, 1_000f, "electricTrainPowerPerCarriage",
            "Rated traction power per carriage; a consist's rating is this times its carriage count. 1375 kW per carriage is a high-speed EMU figure. Lower it if several trains share one catenary. [in Watts]");

    public final ConfigFloat electricTrainMaxAcceleration = f(0.5f, 0.05f, "electricTrainMaxAcceleration",
            "Tractive acceleration in the constant-effort region. This is the acceleration at low speed; above the base speed it tapers as power becomes the limit. [in Blocks / Second²]");

    public final ConfigFloat electricTrainBrakeDeceleration = f(0.8f, 0.05f, "electricTrainBrakeDeceleration",
            "Service braking deceleration, used for stopping and braking distances only. Kept separate from traction so that Create's braking distance stays realistic, and so that a power-limited climb does not make the train think it needs forever to stop. [in Blocks / Second²]");

    public final ConfigBool electricTrainGradeResistance = b(true, "electricTrainGradeResistance",
            "Apply gradient resistance. Uphill raises the force needed and lowers the sustainable speed; downhill lets gravity assist so less power is drawn.");

    public final ConfigFloat electricTrainCurveSpeedPercent = f(60f, 1f, "electricTrainCurveSpeedPercent",
            "Speed allowed in a curve, as a percentage of electricTrainMaxSpeed. While any carriage is on a curve the train is held to this and only speeds up again on leaving it. [in %]");

    public final ConfigFloat electricTrainCurvePowerPercent = f(50f, 1f, "electricTrainCurvePowerPercent",
            "Traction power allowed in a curve, as a percentage of the consist's rating. Easing off through the curve lowers the electrical demand as well as the speed. [in %]");

    public final ConfigBool electricTrainCurveSpeedLimit = b(true, "electricTrainCurveSpeedLimit",
            "Limit speed and power in curves. Off means the train runs at full speed and power through curves.");

    // ------------------------------------------------------------------
    // Accumulator and pantograph settings
    // ------------------------------------------------------------------

    public final ConfigInt ticksPerAccumulatorOnTrain = i(1200, 1, "trainAccumulatorTime", "[in Ticks / one Accumulator]");
    public final ConfigInt ticksPerAccumulatorChargeOnTrain = i(1200, 1, "trainAccumulatorChargeTime", "[in Ticks / one Accumulator]");
    public final ConfigBool winterPantographSparks = b(true, "winterPantographSparks", "Sparks appear on electric trains in cold conditions.");
    public final ConfigFloat winterPantographSparkChance = f(0.01f, 0f, 1f, "winterPantographSparkChance", "Chance of a spark appearing in cold conditions.");
    public final ConfigFloat highSpeedPantographSparks = f(1.44f, 0f, "speedPantographSparkThreshold", "Trains faster than this value will have sparks on pantographs. Set to 0 to disable. [in Blocks / Tick]");
    public final ConfigFloat highSpeedPantographSparkChance = f(0.01f, 0f, 1f, "highSpeedPantographSparkChance", "Chance of a spark appearing at high speeds.");
    public final ConfigBool rainPantographSparks = b(true, "rainPantographSparks", "Sparks appear on electric trains in rainy conditions.");
    public final ConfigFloat rainPantographSparkChance = f(0.01f, 0f, 1f, "rainPantographSparkChance", "Chance of a spark appearing in raniy conditions.");

    @Override
    public String getName() {
        return "trains";
    }
}
