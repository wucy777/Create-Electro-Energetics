package com.george_vi.electroenergetics.simulation;

import com.george_vi.electroenergetics.CEETags;
import com.george_vi.electroenergetics.config.CEEConfigs;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.registries.DeferredHolder;

import javax.annotation.Nullable;
import java.util.function.DoubleSupplier;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

public class WireType {

    /**
     * Heat lost per tick, in temperature units, at zero current.
     *
     * <p>Public because the wire heater subtracts it every tick, and the rating
     * functions below divide by it. Those two must be the same number: the heater
     * works in float while this is a double, so if the heater wrote its own
     * {@code 33.3f} literal the two would not cancel exactly and a wire sitting on
     * its rating could drift a hair either side of it. The heater casts this value
     * instead, so there is only one constant.
     */
    public static final double HEATING_COOLING_PER_TICK = 33.3d;

    /** Temperature scale at which the heater's self-limiting term halves the input. */
    private static final double HEATING_HALF_POINT = 1000d;

    /**
     * The highest ampacity any wire in the mod is given, in Amps. This is a design
     * limit on the ratings, not on the physics: no conductor is defined above it, so
     * the whole set stays comparable and the tooltips stay meaningful.
     */
    public static final double WIRE_RATING_LIMIT = 1000d;

    /**
     * The trip temperature for a wire rated for the given current, in the heater's
     * abstract units.
     *
     * <p>The heater settles at {@code T = 1000 * (I / 33.3 - 1)} for a current
     * {@code I}, and a wire breaks once its temperature passes this value. So a wire
     * rated {@code R} gets exactly the settling temperature of {@code R}: it holds
     * steady at its rating and breaks for any current above it, sooner the further
     * over it goes.
     *
     * <p>Ratings are clamped to {@link #WIRE_RATING_LIMIT}: a heavy conductor whose
     * cross-section would allow more is still rated at the limit. Such a conductor is
     * not pointless - its lower resistance still means lower losses.
     */
    public static double temperatureForAmpacity(double amps) {
        double rated = Math.min(amps, WIRE_RATING_LIMIT);
        return HEATING_HALF_POINT * (rated / HEATING_COOLING_PER_TICK - 1d);
    }

    /**
     * The current a wire with the given {@code maxTemperature} is rated for, in amps -
     * the number the item tooltips show. The exact inverse of
     * {@link #temperatureForAmpacity}, so a tooltip and the heater cannot disagree.
     */
    public static double ampacityForTemperature(double maxTemperature) {
        return HEATING_COOLING_PER_TICK * (1d + maxTemperature / HEATING_HALF_POINT);
    }

    final DoubleSupplier resistance;
    final PartialModel model;
    final Supplier<Item> droppedItem;
    final Supplier<Item> spoolItem;
    final double insulationResistance;
    final DoubleSupplier maxInsulationVoltage;
    final Supplier<WireType> overheatedReplacement;
    final TagKey<Item> droppedTag;

    /**
     * This is the temperature at which the wire burns. It is in abstract units.
     * When a current is going through a wire, it raises temperature. It eventually settles into a temperature,
     * the temperature that it stops heating up is dependent on the current.
     * these are temperature values, that the wire settles into, and the currents needed for that.
     * The heater sees the real current - there is no clamp on it - so an overload
     * burns faster the further over the rating it is. Here are a few samples from the
     * settling function.
     * 14000 -> ~ 500A
     * 10000 -> ~ 366A
     * 7500  -> ~ 280A
     * 5000  -> ~ 200A
     * 2000  -> ~ 100A
     * 1000  -> ~ 66.6A
     * 500   -> ~ 50A
     * 0     -> I <= 33.3A
     *
     * <p>Use {@link #temperatureForAmpacity} and {@link #ampacityForTemperature}
     * rather than repeating the algebra: the relationship is used by the heating
     * model, by the wire definitions and by the item tooltips, and those drifting
     * apart is what makes a tooltip disagree with when a wire actually burns.
     */
    final DoubleSupplier maxTemperature;
    final float sag;
    final IntSupplier maxLength;
    final float thickness;
    final boolean isDecorative;
    final boolean isInvulnerable;
    final WireRenderType renderType;
    final PartialModel endPointModel;

    private WireType(DoubleSupplier resistance, PartialModel model, Supplier<Item> droppedItem,
                     Supplier<Item> spoolItem, double insulationResistance, DoubleSupplier maxInsulationVoltage,
                     Supplier<WireType> overheatedReplacement, TagKey<Item> droppedTag, DoubleSupplier maxTemperature,
                     float sag, IntSupplier maxLength, float thickness, boolean isDecorative, boolean isInvulnerable, WireRenderType renderType, PartialModel endPointModel) {
        this.renderType = renderType;
        this.endPointModel = endPointModel;
        if (droppedItem == null && droppedTag == null)
            droppedItem = () -> Items.AIR;

        this.resistance = resistance;
        this.model = model;
        this.droppedItem = droppedItem;
        this.spoolItem = spoolItem;
        this.insulationResistance = insulationResistance;
        this.maxInsulationVoltage = maxInsulationVoltage;
        this.overheatedReplacement = overheatedReplacement;
        this.droppedTag = droppedTag;
        this.maxTemperature = maxTemperature;
        this.sag = sag;
        this.maxLength = maxLength;
        this.thickness = thickness;
        this.isDecorative = isDecorative;
        this.isInvulnerable = isInvulnerable;
    }

    public Item getDrops() {
        return droppedItem == null ? CEETags.itemFromTag(droppedTag) : droppedItem.get();
    }

    public @Nullable TagKey<Item> getDroppedTag() {
        return droppedTag;
    }

    public Item getSpooledItem() {
        return spoolItem.get();
    }

    public double getMaxTemperature() {
        return maxTemperature.getAsDouble();
    }

    public WireType overheatedReplacement() {
        return overheatedReplacement.get();
    }

    public double getResistance() {
        return resistance.getAsDouble();
    }

    public double insulationResistance() {
        return insulationResistance;
    }

    public double maxInsulationVoltage() {
        return maxInsulationVoltage.getAsDouble();
    }

    public PartialModel getModel() {
        return model;
    }

    @Nullable
    public PartialModel getEndPointModel() {
        return endPointModel;
    }

    public float getSag() {
        return sag;
    }

    public float getMaxLength() {
        return maxLength.getAsInt();
    }

    public boolean insulated() {
        return insulationResistance > 1;
    }

    public float getThickness() {
        return thickness;
    }

    @SuppressWarnings("unused")
    public boolean isInvulnerable() {
        return isInvulnerable;
    }

    @SuppressWarnings("unused")
    public boolean isDecorative() {
        return isDecorative;
    }

    @OnlyIn(Dist.CLIENT)
    public RenderType renderType() {
        return switch (renderType) {
            case SOLID, SOLID_NOT_SCALED -> RenderType.solid();
            case CUTOUT, CUTOUT_NOT_SCALED -> RenderType.cutout();
            case TRANSLUCENT, TRANSLUCENT_NOT_SCALED -> RenderType.translucent();
        };
    }

    public boolean shouldScaleLast() {
        return switch (renderType) {
            case SOLID, CUTOUT, TRANSLUCENT -> true;
            default -> false;
        };
    }


    public static class Builder {

        DoubleSupplier resistance = () -> CEEConfigs.server().resistanceValues.wireResistance.get();
        PartialModel model;
        PartialModel endPointModel;
        Supplier<Item> droppedItem = null;
        Supplier<Item> spoolItem = () -> Items.AIR;
        double insulationResistance = 0;
        DoubleSupplier maxInsulationVoltage = () -> 0;
        Supplier<WireType> overheatedReplacement = () -> null;
        DoubleSupplier maxTemperature = () -> 1000;
        float sag = 1;
        IntSupplier maxLength = () -> CEEConfigs.server().maxWireLength.get();
        float thickness = 1/16f;

        Function<DyeColor, WireType> dyeTypeFunction;
        DyeColor dyeColor;
        boolean decorative = false;
        boolean invulnerable = false;
        TagKey<Item> droppedTag = null;

        WireRenderType renderType = WireRenderType.SOLID;

        public WireType build() {
            if (dyeTypeFunction != null)
                return new Dyeable(resistance, model, droppedItem, spoolItem, insulationResistance,
                        maxInsulationVoltage, overheatedReplacement, droppedTag, maxTemperature, sag, maxLength,
                        thickness, dyeTypeFunction, dyeColor, renderType, endPointModel);
            return new WireType(resistance, model, droppedItem, spoolItem, insulationResistance, maxInsulationVoltage,
                    overheatedReplacement, droppedTag, maxTemperature, sag, maxLength, thickness, decorative,
                    invulnerable, renderType, endPointModel);
        }

        public Builder(PartialModel model) {
            this.model = model;
        }

        public Builder resistance(DoubleSupplier v) {
            resistance = v;
            return this;
        }

        public Builder droppedItem(Supplier<Item> v) {
            droppedItem = v;
            return this;
        }

        @SuppressWarnings("unused")
        public Builder endPointItem(PartialModel model) {
            endPointModel = model;
            return this;
        }

        public Builder droppedTag(TagKey<Item> v) {
            droppedTag = v;
            return this;
        }

        public Builder spoolItem(Supplier<Item> v) {
            spoolItem = v;
            return this;
        }

        public Builder insulationResistance(double v) {
            insulationResistance = v;
            return this;
        }

        public Builder sag(float v) {
            sag = v;
            return this;
        }

        public Builder thickness(float v) {
            thickness = v;
            return this;
        }

        public Builder maxTemperature(DoubleSupplier v) {
            maxTemperature = v;
            return this;
        }

        public Builder maxInsulationVoltage(DoubleSupplier v) {
            maxInsulationVoltage = v;
            return this;
        }

        public Builder replaceOnOverheated(Supplier<WireType> v) {
            overheatedReplacement = v;
            return this;
        }

        public Builder maxLength(IntSupplier v) {
            maxLength = v;
            return this;
        }

        public Builder dyeable(Function<DyeColor, WireType> dyeTypeFunction) {
            this.dyeTypeFunction = dyeTypeFunction;
            return this;
        }

        @SuppressWarnings("unused")
        public Builder dyeable(Function<DyeColor, WireType> dyeTypeFunction, DyeColor color) {
            this.dyeTypeFunction = dyeTypeFunction;
            dyeColor = color;
            return this;
        }

        public Builder dyeable(DeferredHolder<WireType, WireType>[] allDyes) {
            return dyeable((dye) -> allDyes[dye.ordinal()].get());
        }

        public Builder dyeable(DeferredHolder<WireType, WireType>[] allDyes, DyeColor color) {
            dyeColor = color;
            return dyeable(allDyes);
        }

        public Builder invulnerable() {
            invulnerable = true;
            return this;
        }

        public Builder decorative() {
            decorative = true;
            return this;
        }

        @SuppressWarnings("unused")
        public Builder renderType(WireRenderType renderType) {
            this.renderType = renderType;
            return this;
        }
    }

    public static class Dyeable extends WireType {
        private final Function<DyeColor, WireType> typeFunction;
        private final DyeColor dyeColor;

        private Dyeable(DoubleSupplier resistance, PartialModel model, Supplier<Item> droppedItem,
                        Supplier<Item> spoolItem, double insulationResistance, DoubleSupplier maxInsulationVoltage,
                        Supplier<WireType> overheatedReplacement, TagKey<Item> droppedTag,
                        DoubleSupplier maxTemperature, float sag, IntSupplier maxLength, float thickness,
                        Function<DyeColor, WireType> typeFunction, @Nullable DyeColor dyeColor,
                        WireRenderType renderType, PartialModel endpointModel) {
            super(resistance, model, droppedItem, spoolItem, insulationResistance, maxInsulationVoltage,
                    overheatedReplacement, droppedTag, maxTemperature, sag, maxLength, thickness, false,
                    false, renderType, endpointModel);

            this.typeFunction = typeFunction;
            this.dyeColor = dyeColor;
        }

        public WireType getDyed(DyeColor color) {
            return typeFunction.apply(color);
        }

        public DyeColor getColor() {
            return dyeColor;
        }

        @SuppressWarnings("unused")
        public boolean isDyed() {
            return dyeColor != null;
        }

    }

    /**
     * For render types, this is used instead of {@link RenderType} to not cause issues with dedicated servers
     * <br>
     * For not scaled wire types, the first and last segment aren't rendered
     */
    public enum WireRenderType {
        SOLID,
        SOLID_NOT_SCALED,
        CUTOUT,
        CUTOUT_NOT_SCALED,
        TRANSLUCENT,
        TRANSLUCENT_NOT_SCALED
    }
}
