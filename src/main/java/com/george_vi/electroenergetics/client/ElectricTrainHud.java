package com.george_vi.electroenergetics.client;

import com.george_vi.electroenergetics.content.railway_electrification.TrainHudData;
import com.simibubi.create.content.contraptions.actors.trainControls.ControlsHandler;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
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

        float partialTicks = deltaTracker.getGameTimeDeltaPartialTick(false);
        float speed = (float) Math.abs(train.speed) * 20f;
        lerp(speed, sample.maxSpeed(), sample.power(), partialTicks);

        var font = mc.font;
        int x = MARGIN;
        int y = graphics.guiHeight() - MARGIN - LINE_HEIGHT * 5 - 40;

        int label = 0x9AA0A6;
        int value = 0xFFFFFF;

        // With no supply the traction can sustain nothing, so the ceiling is
        // reported as zero. Showing "0 / 0" there would read as a broken readout,
        // so the ceiling is left blank and the catenary row explains why.
        String speedText = sample.powered()
                ? String.format("%.0f / %.0f m/s", shownSpeed, shownMaxSpeed)
                : String.format("%.0f m/s", shownSpeed);
        drawRow(graphics, font, x, y, 0, label, "Speed", value, speedText);
        drawRow(graphics, font, x, y, 1, label, "Throttle", value,
                String.format("%.0f%%", train.throttle * 100f));

        String gradeText = describeGrade(sample.grade());
        drawRow(graphics, font, x, y, 2, label, "Gradient", gradeColor(sample.grade()), gradeText);

        drawRow(graphics, font, x, y, 3, label, "Power", value,
                sample.powered() ? formatPower(shownPower) : "--");
        drawRow(graphics, font, x, y, 4, label, "Catenary", value,
                String.format("%.2f kV %s", sample.voltage() / 1000f,
                        sample.powered() ? "" : "(unpowered)"));
    }

    private void drawRow(GuiGraphics graphics, Font font,
                         int x, int y, int row, int labelColor, String label,
                         int valueColor, String value) {
        int rowY = y + row * LINE_HEIGHT;
        graphics.drawString(font, label, x, rowY, labelColor, true);
        graphics.drawString(font, value, x + 58, rowY, valueColor, true);
    }

    private static String formatPower(double watts) {
        double kw = watts / 1000d;
        if (Math.abs(kw) >= 1000d)
            return String.format("%.2f MW", kw / 1000d);
        return String.format("%.0f kW", kw);
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
