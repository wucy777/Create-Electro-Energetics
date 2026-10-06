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
    //   * curves impose NO limit and cost no power: an electric train takes a curve
    //     at the same speed as straight track. This is a deliberate design decision,
    //     not an omission - see §4.4 of the model documentation.
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
            "FRICTION (adhesion) service braking, the pads on the discs. 0.8 Blocks/Second² is the realistic figure for an adhesion railway. This is what the BRAKE position applies, and it is the only braking that exists at a standstill, so on its own it holds a grade up to this/g = 8.2%. The reverse position adds the motors to it; see electricTrainReverseMaxSpeed. [in Blocks / Second²]");

    public final ConfigFloat electricTrainDynamicBrakeDeceleration = f(0f, 0f, "electricTrainDynamicBrakeDeceleration",
            "OPTIONAL motor (regenerative) braking, added on top of the friction figure above while the lever is in the BRAKE position. Left at 0 so that the brake position means exactly the realistic friction rate above; raise it to something like 0.8-1.5 to get a real EMU's blended friction-plus-regenerative stop, at the cost of the brake no longer being a single realistic number. Whatever is set here is also what can be pushed back into the catenary, so this is the knob that turns regeneration on. A motor brake fades out as the train slows because its back-EMF collapses, so it never counts toward holding a train on a grade. [in Blocks / Second²]");

    public final ConfigFloat electricTrainDynamicBrakeMinSpeed = f(5f, 0f, "electricTrainDynamicBrakeMinSpeed",
            "Speed at which the motor brake reaches full effect, and below which it fades linearly to nothing. Real trains blend the friction brake in over roughly the last 20 km/h of a stop for this reason. [in Blocks / Second]");

    public final ConfigFloat electricTrainReverseMaxSpeed = f(10f, 1f, "electricTrainReverseMaxSpeed",
            "Fastest the train may be driven backwards from a standstill [in Blocks / Second]. This is a shunting speed, not a running speed: 10 Blocks/Second is 36 km/h, which is a brisk shunt. It does not apply when the train is still rolling forwards - in that case the reverse position is a brake, not a gear, and the lever does not command any backward motion at all. [in Blocks / Second]");

    public final ConfigBool electricTrainRegenerativeBraking = b(true, "electricTrainRegenerativeBraking",
            "Motors act as generators while braking and push power back into the catenary, the way a real EMU does. The recovered power shows up as a negative draw, so the amps fall and the line is fed rather than loaded. Turn off to make every stop purely friction, with no feed back.");

    public final ConfigFloat electricTrainRegenerativeFraction = f(0.7f, 0f, 1f, "electricTrainRegenerativeFraction",
            "Share of the motor brake's mechanical power the motors actually recover. The rest is motor, converter and gear loss. Real EMUs recover roughly 60-80% of the energy of a stop, and only from the motor brake: the friction pads make heat and recover nothing, which is why the low-speed fade of the motor brake directly sets how much of a stop can be recovered. [0-1]");

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
