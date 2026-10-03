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

    public final ConfigFloat electricTrainMaxSpeed = f(60f, 1f, "electricTrainMaxSpeed",
            "Design top speed of the rolling stock. This is a hard ceiling; on a gradient or in a curve the train is limited below it. [in Blocks / Second]");

    public final ConfigFloat electricTrainPowerPerCarriage = f(500_000f, 1_000f, "electricTrainPowerPerCarriage",
            "Rated traction power per carriage; a consist's rating is this times its carriage count. Lower it if several trains share one catenary. [in Watts]");

    public final ConfigFloat electricTrainMaxAcceleration = f(1.0f, 0.05f, "electricTrainMaxAcceleration",
            "Tractive acceleration of the consist. Create uses this same number for stopping distance, so it must stay realistic (roughly 0.5 - 1.0 for an EMU); do not set it near zero or trains will think they need a huge distance to brake. [in Blocks / Second²]");

    public final ConfigFloat electricTrainLateralAccelLimit = f(0.65f, 0.05f, "electricTrainLateralAccelLimit",
            "Maximum comfortable lateral acceleration in curves. Limits curve speed to sqrt(a * radius). [in Blocks / Second²]");

    public final ConfigBool electricTrainGradeResistance = b(true, "electricTrainGradeResistance",
            "Apply gradient resistance. Uphill raises the force needed and lowers the sustainable speed; downhill lets gravity assist so less power is drawn.");

    public final ConfigBool electricTrainCurveSpeedLimit = b(true, "electricTrainCurveSpeedLimit",
            "Limit speed in curves by the lateral acceleration limit. Speed is only reduced while actually on a curve.");

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
