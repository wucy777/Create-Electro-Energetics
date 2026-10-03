package com.george_vi.electroenergetics.client;

import com.george_vi.electroenergetics.content.railway_electrification.ElectricManualSpeed;
import com.george_vi.electroenergetics.content.railway_electrification.TrainHudData;
import com.simibubi.create.content.contraptions.actors.trainControls.ControlsHandler;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.infrastructure.config.AllConfigs;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.util.Mth;
import net.minecraft.world.level.GameType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Driver's HUD: a compact readout of the traction state while you are at the
 * controls of an electric train.
 *
 * <p>Shows the modelled speed ceiling, the gradient the train is on, the
 * electrical power being drawn and the catenary voltage, plus the current
 * speed. Drawn just above the hotbar so it does not fight Create's own train
 * HUD, which sits on the experience bar.
 *
 * <p>The speed row deliberately uses Create's manual-driving cap
 * ({@code maxSpeed() * manualTrainSpeedModifier}) as its denominator, the same
 * figure Create's experience-bar speed display uses. Using the raw modelled
 * ceiling instead made the two disagree: the bar would sit full while this
 * panel still read two thirds.
 *
 * <p>Deliberately read-only: everything shown comes from the server through
 * {@link TrainHudData}, so the numbers agree with what the simulation is
 * actually doing rather than with a client-side guess.
 */
@OnlyIn(Dist.CLIENT)
public class ElectricTrainHud implements LayeredDraw.Layer {

    public static final ElectricTrainHud INSTANCE = new ElectricTrainHud();

    private static final int MARGIN = 4;
    private static final int LINE_HEIGHT = 10;

    /** Smoothed display values so the readout does not flicker tick to tick. */
    private float shownSpeed;
    private float shownMaxSpeed;
    private float shownPower;
    private boolean initialised;

    // Row text plus the quantised values it was built from. Rebuilt only when a
    // displayed digit actually changes, since this renders every frame.
    private String textSpeed = "";
    private String textThrottle = "";
    private String textGrade = "";
    private String textPower = "";
    private String textCatenary = "";
    private String textCars = "";
    private int cachedSpeed = Integer.MIN_VALUE;
    private int cachedCap = Integer.MIN_VALUE;
    private int cachedThrottle = Integer.MIN_VALUE;
    private int cachedPowerKw = Integer.MIN_VALUE;
    private int cachedDeciVolt = Integer.MIN_VALUE;
    private int cachedCars = Integer.MIN_VALUE;
    private int cachedMotors = Integer.MIN_VALUE;
    private boolean cachedPowered;
    private double cachedGrade = Double.NaN;
    private float cachedPerCarriage = Float.NaN;

    private ElectricTrainHud() {}

    @Override
    public void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.player == null || mc.level == null)
            return;
        if (mc.gameMode == null || mc.gameMode.getPlayerMode() == GameType.SPECTATOR)
            return;

        Carriage carriage = drivenCarriage();
        if (carriage == null)
            return;
        Train train = carriage.train;
        if (train == null)
            return;

        TrainHudData.Sample sample = TrainHudData.get(train.id);
        if (sample == null)
            return;

        // The row count is fixed so the panel does not jump around as trains
        // gain or lose carriages.
        int rows = 6;

        // What the driver can actually reach right now. Create caps manual
        // driving at maxSpeed() * manualTrainSpeedModifier (see
        // CarriageContraptionEntity.control), and it sizes the experience-bar
        // speed bar from that same product. For an electric train that factor is
        // overridden on both sides (ElectricManualSpeed), so this resolves the
        // same value here rather than assuming Create's raw setting, otherwise
        // the panel would read two thirds while the train is at its ceiling.
        float manualCap = sample.maxSpeed() * ElectricManualSpeed.modifierFor(train, manualSpeedModifier());

        float partialTicks = deltaTracker.getGameTimeDeltaPartialTick(false);
        float speed = (float) Math.abs(train.speed) * 20f;
        lerp(speed, manualCap, sample.power(), partialTicks);

        var font = mc.font;
        int x = MARGIN;
        int y = graphics.guiHeight() - MARGIN - LINE_HEIGHT * rows - 40;

        int label = 0x9AA0A6;
        int value = 0xFFFFFF;

        // Values are quantised to what is actually displayed before they are
        // formatted. This renderer runs every frame, and String.format is not
        // cheap, so rebuilding only when a visible digit changes keeps the panel
        // off the frame budget.
        // Math.round(float) widens to double and returns a long, so each of these
        // is narrowed back explicitly.
        int speedI = (int) Math.round(shownSpeed);
        int capI = (int) Math.round(shownMaxSpeed);
        int throttleI = (int) Math.round(train.throttle * 100f);
        int powerKwI = (int) Math.round(shownPower / 1000f);
        int deciVoltI = (int) Math.round(sample.voltage() / 100f);   // 0.1 kV steps
        int cars = sample.carriages() > 0 ? sample.carriages()
                : Math.max(1, train.carriages.size());
        int motorsI = sample.motorCars();
        boolean powered = sample.powered();

        if (speedI != cachedSpeed || capI != cachedCap || throttleI != cachedThrottle
                || powerKwI != cachedPowerKw || deciVoltI != cachedDeciVolt
                || cars != cachedCars || motorsI != cachedMotors
                || powered != cachedPowered || sample.grade() != cachedGrade
                || sample.powerPerCarriage() != cachedPerCarriage) {
            cachedSpeed = speedI;
            cachedCap = capI;
            cachedThrottle = throttleI;
            cachedPowerKw = powerKwI;
            cachedDeciVolt = deciVoltI;
            cachedCars = cars;
            cachedMotors = motorsI;
            cachedPowered = powered;
            cachedGrade = sample.grade();
            cachedPerCarriage = sample.powerPerCarriage();
            rebuildText(speedI, capI, throttleI, powerKwI, deciVoltI,
                    cars, motorsI, powered, sample.grade(),
                    sample.powerPerCarriage());
        }

        drawRow(graphics, font, x, y, 0, label, "Speed", value, textSpeed);
        drawRow(graphics, font, x, y, 1, label, "Throttle", value, textThrottle);
        drawRow(graphics, font, x, y, 2, label, "Gradient",
                gradeColor(sample.grade()), textGrade);
        drawRow(graphics, font, x, y, 3, label, "Power", value, textPower);
        drawRow(graphics, font, x, y, 4, label, "Catenary", value, textCatenary);
        // Carriage count and how many of them actually pull, so the rating above
        // can be sanity-checked at a glance.
        drawRow(graphics, font, x, y, 5, label, "Cars", value, textCars);
    }

    /** Rebuilds the cached row text; only called when a displayed value changed. */
    private void rebuildText(int speed, int cap, int throttle, int powerKw, int deciVolt,
                             int cars, int motors, boolean powered, double grade,
                             float perCarriage) {
        // With no supply the traction can sustain nothing, so the ceiling is
        // reported as zero. Showing "0 / 0" there would read as a broken readout,
        // so the ceiling is left blank and the catenary row explains why.
        textSpeed = powered
                ? speed + " / " + cap + " m/s"
                : speed + " m/s";
        textThrottle = throttle + "%";
        textGrade = describeGrade(grade);
        // Rating is per carriage, so show the consist's rating against what it is
        // actually drawing; that is the comparison that shows whether the set is
        // near its limit or cruising well below it.
        textPower = powered
                ? formatPowerKw(powerKw) + " / "
                        + String.format("%.2f", cars * perCarriage / 1e6) + " MW"
                : "--";
        textCatenary = String.format("%.1f", deciVolt / 10d) + " kV"
                + (powered ? "" : " (unpowered)");
        textCars = cars + "  (" + motors + " motorised)";
    }

    /**
     * Create's manual-driving speed factor. Read live rather than hard-coded so
     * the panel follows the player's create-server.toml.
     */
    private static float manualSpeedModifier() {
        return AllConfigs.server().trains.manualTrainSpeedModifier.getF();
    }

    private void drawRow(GuiGraphics graphics, Font font,
                         int x, int y, int row, int labelColor, String label,
                         int valueColor, String value) {
        int rowY = y + row * LINE_HEIGHT;
        graphics.drawString(font, label, x, rowY, labelColor, true);
        graphics.drawString(font, value, x + 58, rowY, valueColor, true);
    }

    private static String formatPowerKw(int kw) {
        if (Math.abs(kw) >= 1000)
            return String.format("%.2f MW", kw / 1000d);
        return kw + " kW";
    }

    /** Uphill reads as a positive grade, which is what the model uses. */
    private static String describeGrade(double grade) {
        if (Math.abs(grade) < 0.0005d)
            return "level";
        double percent = grade * 100d;
        return String.format("%+.2f%% %s", percent, grade > 0 ? "uphill" : "downhill");
    }

    private static int gradeColor(double grade) {
        if (grade > 0.005d)
            return 0xFF7B72;   // climbing costs power
        if (grade < -0.005d)
            return 0x7EE787;   // gravity assists
        return 0xFFFFFF;
    }

    private void lerp(float speed, float maxSpeed, float power, float partialTicks) {
        float factor = Mth.clamp(0.25f * Math.max(partialTicks, 0.05f), 0f, 1f);
        if (!initialised) {
            shownSpeed = speed;
            shownMaxSpeed = maxSpeed;
            shownPower = power;
            initialised = true;
            return;
        }
        shownSpeed += (speed - shownSpeed) * factor;
        shownMaxSpeed += (maxSpeed - shownMaxSpeed) * factor;
        shownPower += (power - shownPower) * factor;
    }

    /** The carriage whose controls the local player currently holds, if any. */
    private static Carriage drivenCarriage() {
        if (!(ControlsHandler.getContraption() instanceof CarriageContraptionEntity cce))
            return null;
        return cce.getCarriage();
    }
}
