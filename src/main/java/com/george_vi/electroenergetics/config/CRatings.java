package com.george_vi.electroenergetics.config;

import net.createmod.catnip.config.ConfigBase;
import net.neoforged.neoforge.common.ModConfigSpec;

public class CRatings extends ConfigBase {
    public final ConfigFloat variacMaxPower = f(20_000, 1, "variacMaxPower", "[in Watts]");
    public final ConfigFloat potentiometerMaxPower = f(1_300, 1, "potentiometerMaxPower", "[in Watts]");
    public final ConfigFloat resistorMaxPower = f(1_300, 1, "resistorMaxPower", "[in Watts]");
    // No transformerMaxPower: the plain transformer has no power rating of its own
    // and never overheats, so nothing is limited here for it.
    public final ConfigFloat miniatureTransformerMaxPower = f(2_500, 1, "miniatureTransformerMaxPower", "[in Watts]");
    public final ConfigFloat electricFanMaxPower = f(750, 1, "electricFanMaxPower", "[in Watts]");

    @Override
    public String getName() {
        return "Ratings";
    }

    private ConfigDouble d(double current, double min, String name, String... comments) {
        return new ConfigDouble(name, current, min,  Double.MAX_VALUE, comments);
    }

    public class ConfigDouble extends CValue<Double, ModConfigSpec.DoubleValue> {

        public ConfigDouble(String name, double current, double min, double max, String... comment) {
            super(name, builder -> builder.defineInRange(name, current, min, max), comment);
        }
    }
}
