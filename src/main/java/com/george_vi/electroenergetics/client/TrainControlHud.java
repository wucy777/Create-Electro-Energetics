package com.george_vi.electroenergetics.client;

import com.george_vi.electroenergetics.content.railway_electrification.TrainGear;
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
import net.minecraft.world.level.GameType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * The driver's lever and buttons, bottom-right.
 *
 * <p>A real EMU has a handle with a few detents; this is that, on screen. Clicking
 * a row notches the lever there. The two buttons sit underneath: the vigilance
 * acknowledgement, which is always present, and the emergency brake, which only
 * appears in reverse because that is the only position where it means anything.
 *
 * <p>Left-bottom is the readout; this corner is the controls. The centre and top of
 * the screen are left clear on purpose, since that is where the track ahead is.
 *
 * <p>Purely a view: every value shown comes from the server through
 * {@link TrainHudData}, and clicks are sent as packets. Nothing here decides
 * anything about the train.
 */
@OnlyIn(Dist.CLIENT)
public class TrainControlHud implements LayeredDraw.Layer {

    public static final TrainControlHud INSTANCE = new TrainControlHud();

    // Colour scheme: dark translucent panel with a light edge, so it reads against
    // both a bright sky and a dark tunnel.
    private static final int BACKDROP = 0xC8101216;
    private static final int BORDER = 0xFF3A3F46;
    private static final int TEXT = 0xFFE6E6E6;
    private static final int TEXT_DIM = 0xFF9AA0A6;
    private static final int ACTIVE_BG = 0xFF2D6A4F;
    private static final int ACTIVE_TEXT = 0xFFFFFFFF;
    private static final int HOVER_BG = 0xFF3A3F46;
    private static final int WARN = 0xFFFFB454;
    private static final int DANGER = 0xFFFF6B5E;

    private TrainControlHud() {}

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
        TrainHudData.GearState gear = TrainHudData.gear(train.id);
        if (gear == null)
            return;   // no server sample yet: draw nothing rather than a guess

        int w = graphics.guiWidth();
        int h = graphics.guiHeight();
        int x = TrainControlLayout.panelX(w);
        int y = TrainControlLayout.panelY(h);
        Font font = mc.font;

        graphics.fill(x, y, x + TrainControlLayout.PANEL_W, y + TrainControlLayout.TOTAL_H, BACKDROP);
        graphics.renderOutline(x, y, TrainControlLayout.PANEL_W, TrainControlLayout.TOTAL_H, BORDER);
        graphics.drawString(font, "Traction", x + 4, y + 3, TEXT_DIM, false);

        boolean reverseSelected = gear.gear() == TrainGear.REVERSE.ordinal();
        boolean emergencyVisible = reverseSelected;

        double mouseX = mc.mouseHandler.xpos() * w / mc.getWindow().getWidth();
        double mouseY = mc.mouseHandler.ypos() * h / mc.getWindow().getHeight();
        int hoveredGear = TrainControlLayout.gearAt(x, y, mouseX, mouseY);
        int hoveredButton = TrainControlLayout.buttonAt(x, y, mouseX, mouseY, emergencyVisible);

        TrainGear[] gears = TrainGear.values();
        for (int i = 0; i < gears.length; i++) {
            int rowY = TrainControlLayout.gearRowY(y, i);
            boolean active = i == gear.gear();
            boolean hover = i == hoveredGear;
            int bg = active ? ACTIVE_BG : (hover ? HOVER_BG : 0);
            if (bg != 0)
                graphics.fill(x + 2, rowY, x + TrainControlLayout.PANEL_W - 2,
                        rowY + TrainControlLayout.ROW_H - 1, bg);
            // The lever position itself: a solid bar on the left edge, so the
            // current notch is readable at a glance without reading the label.
            if (active)
                graphics.fill(x + 2, rowY, x + 4, rowY + TrainControlLayout.ROW_H - 1, ACTIVE_TEXT);
            graphics.drawString(font, gearLabel(gears[i]), x + 8, rowY + 4,
                    active ? ACTIVE_TEXT : TEXT, false);
        }

        int cy = TrainControlLayout.confirmY(y);
        boolean confirmDue = gear.confirmDue();
        drawButton(graphics, font, x, cy, "Acknowledge",
                confirmDue ? DANGER : TEXT,
                hoveredButton == TrainControlLayout.CONFIRM,
                confirmDue);

        if (emergencyVisible) {
            int ey = TrainControlLayout.emergencyY(y);
            drawButton(graphics, font, x, ey, "Emergency brake",
                    DANGER, hoveredButton == TrainControlLayout.EMERGENCY, false);
        }

        // A penalty that is still running is worth stating; otherwise the driver
        // only notices the train is slow and does not know why.
        if (gear.emergencyPenalty()) {
            graphics.drawString(font, "LIMITED to 40 km/h",
                    x + 4, y + TrainControlLayout.TOTAL_H - 9, WARN, false);
        } else if (gear.regen()) {
            graphics.drawString(font, "regenerating",
                    x + 4, y + TrainControlLayout.TOTAL_H - 9, 0xFF7EE787, false);
        }
    }

    private static void drawButton(GuiGraphics graphics, Font font, int x, int y,
                                   String label, int colour, boolean hover, boolean alert) {
        int bg = hover ? HOVER_BG : (alert ? 0x60FF6B5E : 0);
        if (bg != 0)
            graphics.fill(x + 2, y, x + TrainControlLayout.PANEL_W - 2, y + TrainControlLayout.BUTTON_H - 1, bg);
        graphics.renderOutline(x + 2, y, TrainControlLayout.PANEL_W - 4, TrainControlLayout.BUTTON_H - 1, BORDER);
        graphics.drawString(font, label, x + 8, y + 5, colour, false);
    }

    /** Short label per notch. Kept here rather than in the enum so the enum stays UI-free. */
    static String gearLabel(TrainGear gear) {
        return switch (gear) {
            case ACCELERATE -> "Accelerate";
            case CRUISE -> "Hold speed";
            case COAST -> "Power off";
            case BRAKE -> "Brake";
            case REVERSE -> "Reverse";
        };
    }

    /** The carriage whose controls the local player holds, if any. */
    private static Carriage drivenCarriage() {
        if (!(ControlsHandler.getContraption() instanceof CarriageContraptionEntity cce))
            return null;
        return cce.getCarriage();
    }
}
