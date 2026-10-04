package com.george_vi.electroenergetics;

import com.george_vi.electroenergetics.config.CEEConfigs;
import com.george_vi.electroenergetics.simulation.WireType;
import net.minecraft.world.item.DyeColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class CEEWireTypes {

    /**
     * Nominal ampacity of the copper conductor every copper wire shares.
     *
     * <p>This is the full {@link WireType#WIRE_RATING_LIMIT}: copper is the reference
     * metal and the highest-rated conductor in the mod. The trip temperature is
     * derived from this figure, so the wire breaks at its rating and the item tooltip
     * reads exactly 1000 A.
     */
    private static final double COPPER_AMPACITY = WireType.WIRE_RATING_LIMIT;

    private static final double COPPER_AMPACITY_TEMPERATURE =
            WireType.temperatureForAmpacity(COPPER_AMPACITY);

    /**
     * Ampacity of a thin iron strand of the same gauge as the copper wire.
     *
     * <p>Iron is 5.78x more resistive than copper (9.71e-8 against 1.68e-8 ohm*m),
     * and because heating goes as {@code I^2 R} while the surface shedding that
     * heat does not change, the current that reaches the same temperature falls
     * with the square root of the resistivity ratio: 1000 A over sqrt(5.78) is
     * about 415 A.
     */
    private static final double IRON_STRAND_AMPACITY = 415d;

    /** Trip temperature for {@link #IRON_STRAND_AMPACITY}, i.e. what IRON uses. */
    private static final double IRON_STRAND_TRIP_TEMPERATURE =
            WireType.temperatureForAmpacity(IRON_STRAND_AMPACITY);

    private static final DeferredRegister<WireType> WIRE_TYPES =
            DeferredRegister.create(CEERegistries.WIRE_TYPE, CreateElectroEnergetics.ID);

    public static final DeferredHolder<WireType, WireType> COPPER = WIRE_TYPES.register("copper", () -> new WireType.Builder(CEEPartialModels.COPPER_WIRE_SEGMENT)
            .resistance(CEEConfigs.server().resistanceValues.copperWireResistance::get)
            .droppedTag(CEETags.COPPER_WIRE)
            .spoolItem(CEEItems.COPPER_WIRE_SPOOL::get)
            .maxTemperature(() -> COPPER_AMPACITY_TEMPERATURE)
            .maxLength(CEEConfigs.server().maxWireLength::get)
            .build());

    @SuppressWarnings("unchecked")
    public static final DeferredHolder<WireType, WireType>[] COLORED_WIRES = new DeferredHolder[DyeColor.values().length];

    public static final DeferredHolder<WireType, WireType> STANDARD = WIRE_TYPES.register("standard", () -> new WireType.Builder(CEEPartialModels.WIRE_SEGMENT)
            .resistance(CEEConfigs.server().resistanceValues.copperWireResistance::get)
            .droppedItem(CEEItems.INSULATED_WIRE)
            .spoolItem(CEEItems.WIRE_SPOOL::get)
            .maxInsulationVoltage(CEEConfigs.server().voltageValues.wireMaxVoltage::get)
            // Copper conductor inside an insulation sleeve, so the electrical
            // figures are copper's (see COPPER) and only the insulation is
            // different: this is the 1500 V sleeve, with half the leakage
            // resistance of the heavily insulated one.
            .maxTemperature(() -> COPPER_AMPACITY_TEMPERATURE)
            .replaceOnOverheated(COPPER)
            .insulationResistance(330_000)
            .maxLength(CEEConfigs.server().maxWireLength::get)
            .dyeable(COLORED_WIRES)
            .build());

    public static final DeferredHolder<WireType, WireType> DUPLEX = WIRE_TYPES.register("duplex", () -> new WireType.Builder(CEEPartialModels.DUPLEX_WIRE_SEGMENT)
            .resistance(() -> 1e+11d)
            .maxLength(CEEConfigs.server().maxBundledWireLength::get)
            .decorative()
            .build());

    public static final DeferredHolder<WireType, WireType> BUNDLE_CONDUCTOR = WIRE_TYPES.register("bundle_conductor", () -> new WireType.Builder(CEEPartialModels.DUPLEX_WIRE_SEGMENT)
            .resistance(CEEConfigs.server().resistanceValues.wireResistance::get)
            .droppedItem(CEEItems.INSULATED_WIRE)
            .spoolItem(CEEItems.DUPLEX_WIRE_SPOOL::get)
            .maxInsulationVoltage(CEEConfigs.server().voltageValues.wireMaxVoltage::get)
            .maxTemperature(() -> Double.MAX_VALUE)
            .maxLength(() -> Integer.MAX_VALUE)
            .invulnerable()
            .build());

    @SuppressWarnings("unchecked")
    public static final DeferredHolder<WireType, WireType>[] COLORED_HEAVILY_INSULATED_WIRES = new DeferredHolder[DyeColor.values().length];

    public static final DeferredHolder<WireType, WireType> HEAVILY_INSULATED = WIRE_TYPES.register("heavily_insulated", () -> new WireType.Builder(CEEPartialModels.HEAVILY_INSULATED_WIRE_SEGMENT)
            .resistance(CEEConfigs.server().resistanceValues.copperWireResistance::get)
            .droppedItem(CEEItems.HEAVILY_INSULATED_WIRE)
            .spoolItem(CEEItems.HEAVILY_INSULATED_WIRE_SPOOL::get)
            .maxInsulationVoltage(CEEConfigs.server().voltageValues.heavilyInsulatedWireMaxVoltage::get)
            // Same copper conductor as STANDARD, in a thicker sleeve: identical
            // resistance and ampacity, but it holds off 20 kV instead of 1500 V
            // and leaks half as much along the shock path.
            .maxTemperature(() -> COPPER_AMPACITY_TEMPERATURE)
            .replaceOnOverheated(COPPER)
            .insulationResistance(660_000)
            .maxLength(CEEConfigs.server().maxHeavilyInsulatedWireLength::get)
            .sag(0.7f)
            .thickness(3/16f)
            .dyeable(COLORED_HEAVILY_INSULATED_WIRES)
            .build());

    public static final DeferredHolder<WireType, WireType> CREATIVE = WIRE_TYPES.register("creative", () -> new WireType.Builder(CEEPartialModels.CREATIVE_WIRE_SEGMENT)
            .resistance(() -> 0.00001d)
            .spoolItem(CEEItems.CREATIVE_WIRE_SPOOL::get)
            .maxInsulationVoltage(() -> 1e+11d)
            .maxTemperature(() -> 1e+11d)
            .insulationResistance(1e+11d)
            .maxLength(CEEConfigs.server().maxWireLength::get)
            .build());

    public static final DeferredHolder<WireType, WireType> IRON = WIRE_TYPES.register("iron", () -> new WireType.Builder(CEEPartialModels.IRON_WIRE_SEGMENT)
            .resistance(CEEConfigs.server().resistanceValues.ironWireResistance::get)
            .droppedItem(CEEItems.IRON_WIRE_STRAND)
            .spoolItem(CEEItems.IRON_WIRE_SPOOL::get)
            // Same gauge as the copper wire, so the only difference is the metal:
            // 5.78x the resistivity, and a rating scaled by 1/sqrt(5.78) because
            // P = I^2 R means the current that reaches the same temperature falls
            // with the square root of the resistance. See IRON_BUS / IRON_RAIL for
            // the thicker conductors, which trade resistance for ampacity.
            .maxTemperature(() -> IRON_STRAND_TRIP_TEMPERATURE)
            .maxLength(CEEConfigs.server().maxWireLength::get)
            .build());

    public static final DeferredHolder<WireType, WireType> IRON_BUS = WIRE_TYPES.register("iron_bus", () -> new WireType.Builder(CEEPartialModels.IRON_BUS_SEGMENT)
            .resistance(CEEConfigs.server().resistanceValues.ironBusWireResistance::get)
            .droppedTag(CEETags.IRON_BUS_COMPONENT)
            .spoolItem(CEEItems.IRON_BUS_SPOOL::get)
            // Twice the linear gauge of the strand, i.e. four times the
            // cross-section: a quarter of the resistance and, for the same
            // temperature rise, 4^0.75 times the current (heat scales with I^2 R
            // but the surface that sheds it only scales with the perimeter). That
            // works out to ~1174 A, above the 1000 A ceiling, so the rating is
            // capped there and the extra cross-section pays off as lower losses
            // instead of a higher rating.
            .maxTemperature(() -> WireType.temperatureForAmpacity(
                    IRON_STRAND_AMPACITY * Math.pow(4d, 0.75d)))
            .maxLength(CEEConfigs.server().maxBusWireLength::get)
            .sag(0f)
            .thickness(2/16f)
            .build());

    @SuppressWarnings("unchecked")
    public static final DeferredHolder<WireType, WireType>[] COLORED_GLASS_INSULATOR = new DeferredHolder[DyeColor.values().length];

    public static final DeferredHolder<WireType, WireType> GLASS_INSULATOR = WIRE_TYPES.register("glass_insulator", () -> new WireType.Builder(CEEPartialModels.HANGING_INSULATOR)
            .resistance(() -> 1e+11d)
            .endPointItem(CEEPartialModels.HANGING_INSULATOR_ENDPOINT)
            .droppedItem(CEEItems.GLASS_INSULATOR_SEGMENT)
            .spoolItem(CEEItems.GLASS_INSULATOR_SPOOL::get)
            .maxLength(CEEConfigs.server().maxBusWireLength::get)
            .maxInsulationVoltage(() -> Double.MAX_VALUE)
            .decorative()
            .sag(0.1f)
            .thickness(4/16f)
            .renderType(WireType.WireRenderType.TRANSLUCENT_NOT_SCALED)
            .dyeable(COLORED_GLASS_INSULATOR)
            .build());

    public static final DeferredHolder<WireType, WireType> IRON_RAIL = WIRE_TYPES.register("iron_rail", () -> new WireType.Builder(CEEPartialModels.IRON_RAIL_SEGMENT)
            .resistance(CEEConfigs.server().resistanceValues.ironRailResistance::get)
            .droppedTag(CEETags.IRON_RAIL_COMPONENT)
            .spoolItem(CEEItems.IRON_RAIL_SPOOL::get)
            // Four times the linear gauge of the strand, i.e. sixteen times the
            // cross-section - a contact rail. Sixteenth of the resistance, and
            // 16^0.75 times the current for the same temperature rise, which would
            // be ~3320 A. That is far above the 1000 A ceiling, so like the bus it
            // is capped there and its size shows up as lower losses rather than a
            // higher rating.
            .maxTemperature(() -> WireType.temperatureForAmpacity(
                    IRON_STRAND_AMPACITY * Math.pow(16d, 0.75d)))
            .maxLength(CEEConfigs.server().maxBusWireLength::get)
            .sag(0f)
            .thickness(4/16f)
            .build());

    public static final DeferredHolder<WireType, WireType> ELECTRUM = WIRE_TYPES.register("electrum", () -> new WireType.Builder(CEEPartialModels.ELECTRUM_WIRE_SEGMENT)
            .resistance(CEEConfigs.server().resistanceValues.electrumWireResistance::get)
            .droppedTag(CEETags.ELECTRUM_WIRE)
            .spoolItem(CEEItems.ELECTRUM_WIRE_SPOOL::get)
            .maxTemperature(() -> 3540)
            .maxLength(CEEConfigs.server().maxWireLength::get)
            .build());


    static {
        for (DyeColor color : DyeColor.values()) {
            COLORED_WIRES[color.ordinal()] =
                    WIRE_TYPES.register("colored_" + color.getSerializedName(),
                    () -> new WireType.Builder(CEEPartialModels.COLORED_WIRE_SEGMENTS[color.ordinal()])
                    .resistance(CEEConfigs.server().resistanceValues.copperWireResistance::get)
                    .droppedItem(CEEItems.INSULATED_WIRE)
                    .spoolItem(CEEItems.WIRE_SPOOL::get)
                    .maxInsulationVoltage(CEEConfigs.server().voltageValues.wireMaxVoltage::get)
                    .maxTemperature(() -> COPPER_AMPACITY_TEMPERATURE)
                    .replaceOnOverheated(COPPER)
                    .insulationResistance(330_000)
                    .maxLength(CEEConfigs.server().maxWireLength::get)
                    .dyeable(COLORED_WIRES, color)
                    .build());

            COLORED_HEAVILY_INSULATED_WIRES[color.ordinal()] =
                    WIRE_TYPES.register(color.getSerializedName() + "_heavily_insulated",
                    () -> new WireType.Builder(CEEPartialModels.COLORED_HEAVILY_INSULATED_WIRE_SEGMENTS[color.ordinal()])
                    .resistance(CEEConfigs.server().resistanceValues.copperWireResistance::get)
                    .droppedItem(CEEItems.HEAVILY_INSULATED_WIRE)
                    .spoolItem(CEEItems.HEAVILY_INSULATED_WIRE_SPOOL::get)
                    .maxInsulationVoltage(CEEConfigs.server().voltageValues.heavilyInsulatedWireMaxVoltage::get)
                    .maxTemperature(() -> COPPER_AMPACITY_TEMPERATURE)
                    .replaceOnOverheated(COPPER)
                    .insulationResistance(660_000)
                    .maxLength(CEEConfigs.server().maxHeavilyInsulatedWireLength::get)
                    .sag(0.7f)
                    .thickness(3/16f)
                    .dyeable(COLORED_HEAVILY_INSULATED_WIRES, color)
                    .build());

            COLORED_GLASS_INSULATOR[color.ordinal()] =
                    WIRE_TYPES.register(color.getSerializedName() + "_hanging_insulator",
                            () -> new WireType.Builder(CEEPartialModels.COLORED_HANGING_INSULATOR[color.ordinal()])
                                    .resistance(() -> 1e+11d)
                                    .endPointItem(CEEPartialModels.HANGING_INSULATOR_ENDPOINT)
                                    .droppedItem(CEEItems.GLASS_INSULATOR_SEGMENT)
                                    .spoolItem(CEEItems.GLASS_INSULATOR_SPOOL::get)
                                    .maxLength(CEEConfigs.server().maxBusWireLength::get)
                                    .maxInsulationVoltage(() -> Double.MAX_VALUE)
                                    .decorative()
                                    .sag(0.1f)
                                    .thickness(4/16f)
                                    .renderType(WireType.WireRenderType.TRANSLUCENT_NOT_SCALED)
                                    .dyeable(COLORED_GLASS_INSULATOR)
                                    .build());
        }
    }

    public static void register(IEventBus bus) {
        WIRE_TYPES.register(bus);
    }

}
