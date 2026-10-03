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
    //   * curves impose no limit at all.
    // ------------------------------------------------------------------

    public final ConfigFloat electricTrainMaxSpeed = f(100f, 1f, "electricTrainMaxSpeed",
            "Maximum speed on level track. A hard ceiling: uphill the train settles below it because the gradient eats into the available traction. 100 Blocks/Second. [in Blocks / Second]");

    public final ConfigFloat electricTrainPowerPerCarriage = f(1_375_000f, 1_000f, "electricTrainPowerPerCarriage",
            "Rated traction power per carriage; a consist's rating is this times its carriage count. 1375 kW per carriage is a high-speed EMU figure. Lower it if several trains share one catenary. [in Watts]");

    public final ConfigFloat electricTrainMaxAcceleration = f(2.0f, 0.05f, "electricTrainMaxAcceleration",
            "Tractive acceleration while the motors are torque-limited, i.e. below the base speed. Above it the power rating caps the effort instead, so this value decides how soon the consist pulls its full rating. The base speed is (powerPerCarriage * driveEfficiency) / (massPerCarriage * (1+rotatingMassFactor) * this), which cancels the carriage count: at the defaults that is about 11.7 Blocks/Second (42 km/h), so the set is at full power from 42 km/h upward. Lower this for a gentler, longer start. [in Blocks / Second²]");

    public final ConfigBool electricTrainManualFullSpeed = b(true, "electricTrainManualFullSpeed",
            "Let a manually driven electric train reach the speed its own traction model allows. Create drives manual trains to maxSpeed() * manualTrainSpeedModifier (0.75 by default), a handicap meant for fuel trains; with this on, an electric train ignores that factor so the configured electricTrainMaxSpeed is actually reachable. Scheduled trains are unaffected.");

    public final ConfigFloat electricTrainBrakeDeceleration = f(0.8f, 0.05f, "electricTrainBrakeDeceleration",
            "Service braking deceleration, used for stopping and braking distances only. Kept separate from traction so that Create's braking distance stays realistic, and so that a power-limited climb does not make the train think it needs forever to stop. [in Blocks / Second²]");

    public final ConfigBool electricTrainGradeResistance = b(true, "electricTrainGradeResistance",
            "Apply gradient resistance. Uphill raises the force needed and lowers the sustainable speed; downhill lets gravity assist so less power is drawn.");

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
