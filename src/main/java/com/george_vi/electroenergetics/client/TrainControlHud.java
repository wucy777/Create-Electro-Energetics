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
 * The driver's lever, bottom-right: one slot with a handle that slides in it.
 *
 * <p>A handle on a scale, not a row of buttons. The five positions are labelled down
 * the side and the handle sits at the one that is selected, so the driver reads the
 * lever's position at a glance rather than working out which button was last pressed.
 *
 * <p>Driven by the raw arrow keys and J/K, described in {@link TrainControlKeys}. Not
 * by the mouse: while driving, the cursor is captured for looking around and is not
 * on screen to point at anything.
 *
 * <p>A view only. Every value comes from the server through {@link TrainHudData}, and
 * the keys are read in {@link TrainControlInput}; nothing here decides anything.
 */
@OnlyIn(Dist.CLIENT)
public class TrainControlHud implements LayeredDraw.Layer {

    public static final TrainControlHud INSTANCE = new TrainControlHud();

    private static final int BACKDROP = 0xC8101216;
    private static final int BORDER = 0xFF3A3F46;
    private static final int TEXT = 0xFFE6E6E6;
    private static final int TEXT_DIM = 0xFF9AA0A6;
    private static final int ACTIVE_TEXT = 0xFFFFFFFF;
    private static final int WARN = 0xFFFFB454;
    private static final int DANGER = 0xFFFF6B5E;
    private static final int REGEN = 0xFF7EE787;

    /** Slot rail and the handle riding in it. */
    private static final int RAIL = 0xFF2A2E34;
    private static final int DETENT_MARK = 0xFF4A5058;
    private static final int HANDLE = 0xFFD8DEE6;
    private static final int HANDLE_EDGE = 0xFF6E767F;
    private static final int HANDLE_GRIP = 0xFF8A929B;

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
        if (!TrainHudData.leverDriven(train.id))
            return;

        int w = graphics.guiWidth();
        int h = graphics.guiHeight();
        int x = TrainControlLayout.panelX(w);
        int y = TrainControlLayout.panelY(h);
        Font font = mc.font;

        graphics.fill(x, y, x + TrainControlLayout.PANEL_W, y + TrainControlLayout.TOTAL_H, BACKDROP);
        graphics.renderOutline(x, y, TrainControlLayout.PANEL_W, TrainControlLayout.TOTAL_H, BORDER);

        int current = gear.gear();
        TrainGear[] gears = TrainGear.values();
        boolean valid = current >= 0 && current < gears.length;
        TrainGear selected = valid ? gears[current] : TrainGear.BRAKE;

        // Header: the position's name, which is what the driver reads first.
        graphics.drawString(font, shortLabel(selected), x + 4, y + 3, ACTIVE_TEXT, false);

        int cx = TrainControlLayout.slotCenterX(x);
        int top = TrainControlLayout.slotTopY(y);
        int railTop = top;
        int railBottom = top + TrainControlLayout.SLOT_H + TrainControlLayout.HANDLE_H;

        // The rail the handle slides in.
        graphics.fill(cx - 1, railTop, cx + 1, railBottom, RAIL);

        // A tick at every detent, so the positions are countable, and the label for
        // each one beside it. The selected label is bright, the rest dim.
        for (int i = 0; i < gears.length; i++) {
            int my = TrainControlLayout.handleCenterY(y, i);
            boolean isCurrent = i == current;
            graphics.fill(cx - 4, my - 0, cx + 4, my + 1,
                    isCurrent ? ACTIVE_TEXT : DETENT_MARK);
            graphics.drawString(font, shortLabel(gears[i]), x + 4,
                    TrainControlLayout.detentLabelY(y, i),
                    isCurrent ? ACTIVE_TEXT : TEXT_DIM, false);
        }

        // The handle itself: a bar across the rail with a grip line, so it reads as a
        // handle rather than a blip on the scale.
        int hy = TrainControlLayout.handleCenterY(y, valid ? current : 0);
        int hx = cx - TrainControlLayout.HANDLE_W / 2;
        graphics.fill(hx, hy - TrainControlLayout.HANDLE_H / 2,
                hx + TrainControlLayout.HANDLE_W, hy + TrainControlLayout.HANDLE_H / 2, HANDLE);
        graphics.renderOutline(hx, hy - TrainControlLayout.HANDLE_H / 2,
                TrainControlLayout.HANDLE_W, TrainControlLayout.HANDLE_H, HANDLE_EDGE);
        graphics.fill(hx + 4, hy - 1, hx + TrainControlLayout.HANDLE_W - 4, hy + 1, HANDLE_GRIP);

        // Footer: the key hints, then the one thing worth saying.
        int fy = y + TrainControlLayout.TOTAL_H - TrainControlLayout.FOOTER_H + 2;
        graphics.drawString(font, "\u2191\u2193 lever   J ack", x + 4, fy, TEXT_DIM, false);

        String status = statusLine(gear);
        if (!status.isEmpty())
            graphics.drawString(font, status, x + 4, fy + 10, statusColor(gear), false);
    }

    /** The one thing to tell the driver right now, in priority order. */
    private static String statusLine(TrainHudData.GearState gear) {
        if (gear.confirmDue())
            return "PRESS J";
        if (gear.emergencyPenalty())
            return "LIMIT 40 km/h";
        if (gear.emergencyArmed())
            return "K = emergency";
        if (gear.regen())
            return "regenerating";
        if (gear.gear() == TrainGear.CRUISE.ordinal()) {
            return switch (gear.cruiseState()) {
                case 0 -> "holding speed";
                case 1 -> "power limited";
                case 2 -> "braking downhill";
                default -> "stopped";
            };
        }
        return "";
    }

    private static int statusColor(TrainHudData.GearState gear) {
        if (gear.confirmDue())
            return DANGER;
        if (gear.emergencyPenalty())
            return WARN;
        if (gear.regen())
            return REGEN;
        return TEXT;
    }

    /** Compact label, since the panel is narrow and the scale is read vertically. */
    static String shortLabel(TrainGear gear) {
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
