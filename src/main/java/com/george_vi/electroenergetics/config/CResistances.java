package com.george_vi.electroenergetics.config;

import net.createmod.catnip.config.ConfigBase;
import net.neoforged.neoforge.common.ModConfigSpec;

public class CResistances extends ConfigBase {
    public final ConfigDouble motorResistance = d(30d, 0.1d, "motorResistance", "[in Ohms]");
    public final ConfigDouble pumpResistance = d(200d, 0.1d, "pumpResistance", "[in Ohms]");
    public final ConfigDouble bulbResistance = d(1000d, 0.1d, "bulbResistance", "[in Ohms]");
    public final ConfigDouble resistiveHeaterResistance = d(45d, 0.1d, "resistiveHeaterResistance", "[in Ohms]");
    public final ConfigDouble electricTrainDriveEfficiency = d(0.9d, 0.01d, "electricTrainDriveEfficiency", "Drive efficiency η used by the train traction model.");
    public final ConfigDouble electricTrainRotatingMassFactor = d(0.06d, 0d, "electricTrainRotatingMassFactor", "Rotating-mass factor γ; adds to the effective mass during acceleration. Typical 0.06 for a multiple unit.");
    public final ConfigDouble electricTrainBasicResistanceA = d(1.0d, 0d, "electricTrainBasicResistanceA", "Davis running-resistance coefficient A, the constant term.");
    public final ConfigDouble electricTrainBasicResistanceB = d(0.02d, 0d, "electricTrainBasicResistanceB", "Davis running-resistance coefficient B, linear in speed [per m/s].");
    public final ConfigDouble electricTrainBasicResistanceC = d(0.0005d, 0d, "electricTrainBasicResistanceC", "Davis running-resistance coefficient C, quadratic in speed (aerodynamic) [per (m/s)^2].");
    public final ConfigDouble electricTrainAuxiliaryLoadFactor = d(1.0d, 0.01d, "electricTrainAuxiliaryLoadFactor", "Auxiliary load factor K_aux for lights, HVAC and controls; divides the useful traction force.");
    public final ConfigDouble electricTrainMarginFactor = d(1.0d, 0.01d, "electricTrainMarginFactor", "Design margin factor K_margin applied on top of the demanded power.");
    public final ConfigDouble electricTrainMassPerCarriage = d(50_000d, 1d, "electricTrainMassPerCarriage", "Train mass per carriage, loaded [in kg].");
    public final ConfigDouble wireResistance = d(0.005d, 0.0001d, "wireResistance", "[in Ohms / Meter]");
    public final ConfigDouble electrumWireResistance = d(0.005d, 0.0001d, "electrumWireResistance", "[in Ohms / Meter]");
    public final ConfigDouble ironWireResistance = d(0.01d, 0.0001d, "ironWireResistance", "[in Ohms / Meter]");
    public final ConfigDouble ironRailResistance = d(0.003d, 0.0001d, "ironRailResistance", "[in Ohms / Meter]");
    public final ConfigDouble indicatorBulbResistance = d(1000, 0.0001d, "indicatorBulbResistance", "[in Ohms]");
    public final ConfigDouble electricFanResistance = d(200, 0.0001d, "electricFanResistance", "[in Ohms]");
    public final ConfigDouble converterMinResistance = d(20, 0.0001d, "converterMinResistance", "[in Ohms]");

    @Override
    public String getName() {
        return "Resistances";
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
