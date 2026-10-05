package com.george_vi.electroenergetics.client;

import com.george_vi.electroenergetics.content.railway_electrification.ElectricManualSpeed;
import com.george_vi.electroenergetics.content.railway_electrification.TrainGear;
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
 * <p>The speed row deliberately uses the same ceiling the train is actually
 * obeying - {@code maxSpeed() * manualTrainSpeedModifier * throttle}, with
 * Create's handicap resolved by {@code ElectricManualSpeed} - which is also what
 * Create's experience-bar speed display is sized from. Using the raw modelled
 * ceiling instead made the two disagree: the bar would sit full while this panel
 * still read two thirds.
 *
 * <p>Deliberately read-only: everything shown comes from the server through
 * {@link TrainHudData}, so the numbers agree with what the simulation is
 * actually doing rather than with a client-side guess. The throttle is the one
 * exception, because a client-side scroll has not reached the server yet when the
 * row is drawn; it is read from the train directly.
 */
@OnlyIn(Dist.CLIENT)
public class ElectricTrainHud implements LayeredDraw.Layer {

    public static final ElectricTrainHud INSTANCE = new ElectricTrainHud();

    private static final int MARGIN = 4;
    private static final int LINE_HEIGHT = 10;

    /** Row label colour, shared by the status line's fallback. */
    private static final int LABEL = 0x9AA0A6;
    private static final int VALUE = 0xFFFFFF;

    /** Smoothed display values so the readout does not flicker tick to tick. */
    private float shownSpeed;
    private float shownMaxSpeed;
    private float shownPower;
    private boolean initialised;

    // Row text plus the quantised values it was built from. Rebuilt only when a
    // displayed digit actually changes, since this renders every frame.
    private String textSpeed = "";
    private String textGear = "";
    private String textGrade = "";
    private String textPower = "";
    private String textCatenary = "";
    private String textCars = "";
    private int cachedSpeed = Integer.MIN_VALUE;
    private int cachedCap = Integer.MIN_VALUE;
    private int cachedGear = Integer.MIN_VALUE;
    private int cachedPowerKw = Integer.MIN_VALUE;
    private int cachedDeciVolt = Integer.MIN_VALUE;
    private int cachedCars = Integer.MIN_VALUE;
    private int cachedMotors = Integer.MIN_VALUE;
    private boolean cachedPowered;
    private double cachedGrade = Double.NaN;
    private float cachedPerCarriage = Float.NaN;

    /**
     * The lever sample this frame, held for the status line and the gear row. Not
     * part of the cached-value comparison because it is read directly while
     * drawing rather than formatted into a cached string.
     */
    private TrainHudData.GearState gearState;

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
        // The readout belongs to the electric train too: on a fuel train the ceiling,
        // catenary and gear rows would all be meaningless.
        if (!TrainHudData.leverDriven(train.id))
            return;

        // The row count is fixed so the panel does not jump around as trains
        // gain or lose carriages.
        int rows = 6;

        // The train's own ceiling, with no throttle term: this is what fills the
        // readout. Create's experience bar divides by the same figure, so the two
        // agree. The throttle no longer caps an electric train's speed at all -
        // the lever does - so this is simply the modelled ceiling.
        float manualCap = sample.maxSpeed()
                * ElectricManualSpeed.speedBarFactor(train, manualSpeedModifier());

        float partialTicks = deltaTracker.getGameTimeDeltaPartialTick(false);
        float speed = (float) Math.abs(train.speed) * 20f;
        lerp(speed, manualCap, sample.power(), partialTicks);

        // Bottom-left, with the rows stacked upwards from the bottom margin, so
        // the readout sits under the eye instead of across the middle of the view.
        gearState = TrainHudData.gear(train.id);

        var font = mc.font;
        int x = MARGIN;
        int y = graphics.guiHeight() - MARGIN - LINE_HEIGHT * 7;

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
        int gearI = gearState == null ? -1 : gearState.gear();
        int powerKwI = (int) Math.round(shownPower / 1000f);
        int deciVoltI = (int) Math.round(sample.voltage() / 100f);   // 0.1 kV steps
        int cars = sample.carriages() > 0 ? sample.carriages()
                : Math.max(1, train.carriages.size());
        int motorsI = sample.motorCars();
        boolean powered = sample.powered();

        if (speedI != cachedSpeed || capI != cachedCap || gearI != cachedGear
                || powerKwI != cachedPowerKw || deciVoltI != cachedDeciVolt
                || cars != cachedCars || motorsI != cachedMotors
                || powered != cachedPowered || sample.grade() != cachedGrade
                || sample.powerPerCarriage() != cachedPerCarriage) {
            cachedSpeed = speedI;
            cachedCap = capI;
            cachedGear = gearI;
            cachedPowerKw = powerKwI;
            cachedDeciVolt = deciVoltI;
            cachedCars = cars;
            cachedMotors = motorsI;
            cachedPowered = powered;
            cachedGrade = sample.grade();
            cachedPerCarriage = sample.powerPerCarriage();
            rebuildText(speedI, capI, gearI, powerKwI, deciVoltI,
                    cars, motorsI, powered, sample.grade(),
                    sample.powerPerCarriage());
        }

        drawRow(graphics, font, x, y, 0, label, tr("electroenergetics.train.row.speed"), value, textSpeed);
        drawRow(graphics, font, x, y, 1, label, tr("electroenergetics.train.row.gear"), value, textGear);
        drawRow(graphics, font, x, y, 2, label, tr("electroenergetics.train.row.gradient"),
                gradeColor(sample.grade()), textGrade);
        drawRow(graphics, font, x, y, 3, label, tr("electroenergetics.train.row.power"), value, textPower);
        drawRow(graphics, font, x, y, 4, label, tr("electroenergetics.train.row.catenary"), value, textCatenary);
        // Carriage count and how many of them actually pull, so the rating above
        // can be sanity-checked at a glance.
        drawRow(graphics, font, x, y, 5, label, tr("electroenergetics.train.row.cars"), value, textCars);

        // Warning lamps, as a strip above the rows.
        //
        // Above rather than beside, because the values column is at a fixed offset and
        // its text is the widest thing here ("350 / 350 m/s"), so a side strip would
        // sit on top of it. Above is empty space and always will be, since the panel
        // grows downwards from a fixed top edge.
        //
        // The vigilance lamp is the one that matters: the prompt is the only thing that
        // will drop the lever on its own, it is easy to miss as a line of text, and
        // missing it costs the driver the train's speed. So it BLINKS, which is what an
        // attention lamp is for and what colour alone cannot achieve. All four are
        // always drawn, lit or not, so a driver can see the panel is alive.
        //
        // Laid out left to right from each caption's measured width, rather than at
        // fixed offsets, because the captions are translated and a fixed pitch would
        // overlap in one language and gap in another. fill/outline only.
        drawLamps(graphics, font, x, y - LAMP_STRIP_GAP - LAMP_H);

        // Status line under the block. This is where the things a driver has to act
        // on go, in priority order: the vigilance prompt first, because ignoring it
        // drops the lever to the brake; then the emergency-brake penalty, so a
        // mysteriously slow train is explained; then what cruise is doing, which is
        // the one readout that says whether the set is holding its speed.
        String status = statusLine();
        if (!status.isEmpty())
            graphics.drawString(font, status, x, y + LINE_HEIGHT * 6 + 2, statusColor(), true);
    }

    /**
     * The four warning lamps, left to right.
     *
     * <p>A dark lamp is an outline rather than a dim fill, so an unlit lamp reads as a
     * lamp that exists and is off - which is what tells a driver the panel is working
     * - instead of as a smudge.
     */
    private void drawLamps(GuiGraphics graphics, Font font, int x, int y) {
        TrainHudData.GearState gear = gearState;
        boolean blink = blinkOn();
        int lx = x;

        // The vigilance lamp ESCALATES, because the three stages mean different things:
        //   1  amber  - the prompt is coming; the driver has time
        //   2  red    - it is overdue; act now or the train stops itself
        // Colour alone would not be enough to convey that reliably, so the red stage
        // also blinks - which is what an attention lamp is for.
        int stage = gear == null ? 0 : gear.vigilanceStage();
        lx = drawLamp(graphics, font, lx, y,
                tr("electroenergetics.train.lamp.vigilance"),
                stage >= 2 ? LAMP_RED : LAMP_AMBER,
                stage >= 1, stage >= 2 ? blink : false);
        lx = drawLamp(graphics, font, lx, y,
                tr("electroenergetics.train.lamp.emergency"), LAMP_AMBER,
                gear != null && gear.emergencyArmed(), false);
        lx = drawLamp(graphics, font, lx, y,
                tr("electroenergetics.train.lamp.limit"), LAMP_AMBER,
                gear != null && gear.emergencyPenalty(), false);
        drawLamp(graphics, font, lx, y,
                tr("electroenergetics.train.lamp.regen"), LAMP_GREEN,
                gear != null && gear.regen(), false);
    }

    /**
     * One lamp and its caption. Returns the x for the next lamp.
     *
     * <p>{@code blink} is the current blink phase; the lamp lights only when lit AND
     * that phase is on, so the caller advances the phase once per frame rather than
     * each lamp computing its own.
     */
    private int drawLamp(GuiGraphics graphics, Font font, int x, int y,
                         String caption, int colour, boolean lit, boolean blink) {
        if (lit && blink) {
            graphics.fill(x, y, x + LAMP_W, y + LAMP_H, colour);
        } else {
            graphics.renderOutline(x, y, LAMP_W, LAMP_H, lit ? colour : LAMP_OFF);
        }
        int textColour = lit ? colour : LAMP_OFF_TEXT;
        graphics.drawString(font, caption, x + LAMP_W + 2, y - 1, textColour, false);
        return x + LAMP_W + 2 + font.width(caption) + LAMP_GAP;
    }

    /**
     * Blink phase, from the world clock so there is no per-HUD timer to keep and the
     * two panels cannot drift apart.
     */
    private static boolean blinkOn() {
        var level = Minecraft.getInstance().level;
        return level != null && (level.getGameTime() / BLINK_TICKS) % 2L == 0L;
    }

    /** Half a second on, half a second off. */
    private static final int BLINK_TICKS = 10;

    private static final int LAMP_W = 5;
    private static final int LAMP_H = 5;
    /** Gap between one lamp's caption and the next lamp. */
    private static final int LAMP_GAP = 6;
    /** Distance from the strip to the first row of figures. */
    private static final int LAMP_STRIP_GAP = 3;

    private static final int LAMP_RED = 0xFFFF4C4C;
    private static final int LAMP_AMBER = 0xFFFFB454;
    private static final int LAMP_GREEN = 0xFF5CD65C;
    private static final int LAMP_OFF = 0xFF3A4048;
    private static final int LAMP_OFF_TEXT = 0xFF6B7280;

    /** The single most important thing to tell the driver right now, or "". */
    private String statusLine() {
        TrainHudData.GearState gear = gearState;
        if (gear == null)
            return "";
        // Two distinct messages, because the two stages ask for different things: amber
        // says the prompt is coming, red says the train is about to stop itself.
        if (gear.vigilanceStage() >= 2)
            return tr("electroenergetics.train.status.overdue");
        if (gear.vigilanceStage() == 1)
            return tr("electroenergetics.train.status.warn");
        if (gear.confirmDue())
            return tr("electroenergetics.train.status.confirm");
        // Automatic arrival outranks the lever readout: while it is running, the lever is
        // not what is commanding the train, and saying "holding 100" would be a lie about
        // a train that is braking for a platform.
        if (gear.autoArrive())
            return tr("electroenergetics.train.status.arriving");
        // Unmanned is shown even while the clock is still only amber, because it changes
        // what the driver has to do about it - there is nobody at the desk to press the
        // button, so the warning cannot be answered where it is being displayed.
        if (gear.unmanned() && gear.vigilanceStage() == 0 && !gear.confirmDue())
            return tr("electroenergetics.train.status.unmanned");
        if (gear.emergencyPenalty())
            return tr("electroenergetics.train.status.penalty");
        if (gear.emergencyArmed())
            return tr("electroenergetics.train.status.armed");
        if (gear.regen())
            return tr("electroenergetics.train.status.regen");
        if (gear.gear() == TrainGear.CRUISE.ordinal()) {
            return switch (gear.cruiseState()) {
                case 0 -> tr("electroenergetics.train.status.holding");
                case 1 -> tr("electroenergetics.train.status.limited");
                case 2 -> tr("electroenergetics.train.status.braking");
                default -> tr("electroenergetics.train.status.stopped");
            };
        }
        return "";
    }

    /** A translated string, so the readout is in the player's own language. */
    private static String tr(String key) {
        return net.minecraft.network.chat.Component.translatable(key).getString();
    }

    private int statusColor() {
        TrainHudData.GearState gear = gearState;
        if (gear == null)
            return VALUE;
        if (gear.vigilanceStage() >= 2)
            return 0xFFFF6B5E;   // red: about to trip
        if (gear.vigilanceStage() == 1)
            return 0xFFFFB454;   // amber: the prompt is coming
        if (gear.confirmDue())
            return 0xFFFF6B5E;
        if (gear.emergencyPenalty())
            return 0xFFFFB454;
        if (gear.regen())
            return 0xFF7EE787;
        return VALUE;
    }

    /** Rebuilds the cached row text; only called when a displayed value changed. */
    private void rebuildText(int speed, int cap, int gear, int powerKw, int deciVolt,
                             int cars, int motors, boolean powered, double grade,
                             float perCarriage) {
        // With no supply the traction can sustain nothing, so the ceiling is
        // reported as zero. Showing "0 / 0" there would read as a broken readout,
        // so the ceiling is left blank and the catenary row explains why.
        textSpeed = powered
                ? speed + " / " + cap + " m/s"
                : speed + " m/s";
        textGear = gear < 0 || gear >= TrainGear.values().length
                ? "--"
                : TrainControlHud.label(TrainGear.values()[gear]);
        textGrade = describeGrade(grade);
        // Rating is per carriage, so show the consist's rating against what it is
        // actually drawing; that is the comparison that shows whether the set is
        // near its limit or cruising well below it. A negative figure is the
        // motors regenerating, so it is labelled rather than printed as a bare
        // minus, which would read as a broken reading.
        if (!powered)
            textPower = "--";
        else if (powerKw < 0)
            textPower = tr("electroenergetics.train.value.regen") + " " + formatPowerKw(-powerKw);
        else
            textPower = formatPowerKw(powerKw) + " / "
                    + String.format("%.2f", cars * perCarriage / 1e6) + " MW";
        textCatenary = String.format("%.1f", deciVolt / 10d) + " kV"
                + (powered ? "" : " " + tr("electroenergetics.train.value.unpowered"));
        textCars = cars + "  (" + motors + " " + tr("electroenergetics.train.value.motorised") + ")";
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
            return tr("electroenergetics.train.grade.level");
        double percent = grade * 100d;
        return String.format("%+.2f%% %s", percent,
                tr(grade > 0 ? "electroenergetics.train.grade.uphill"
                             : "electroenergetics.train.grade.downhill"));
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
