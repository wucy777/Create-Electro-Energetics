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
import net.minecraft.network.chat.Component;
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
 * <p>Everything shown is a translated string, so the panel is in the player's own
 * language rather than English regardless of what language they run the game in.
 *
 * <p>The handle follows {@link TrainControlInput#displayGear}, which is the driver's
 * own last command while the server has yet to confirm it. Drawing the server's
 * value instead made the handle lag the key and then jump several positions at once
 * when a burst of presses all landed together.
 *
 * <p>Drawn with {@code fill} and {@code renderOutline} only - no textures, no
 * blitting, no per-frame allocation - so it costs a few dozen quads and does not
 * touch the frame budget. The rounded look comes from layering rectangles rather
 * than from a texture, which is what keeps it free.
 *
 * <p>A view only. Every value comes from the server through {@link TrainHudData} and
 * the keys are read in {@link TrainControlInput}; nothing here decides anything.
 */
@OnlyIn(Dist.CLIENT)
public class TrainControlHud implements LayeredDraw.Layer {

    public static final TrainControlHud INSTANCE = new TrainControlHud();

    // Panel: a dark card with a soft edge, plus a one-pixel lighter inset along the
    // top so it reads as raised rather than flat.
    private static final int BACKDROP = 0xD00E1116;
    private static final int BACKDROP_EDGE = 0xFF2A3038;
    private static final int INSET_HIGHLIGHT = 0x18FFFFFF;
    private static final int INSET_SHADOW = 0x20000000;

    private static final int TEXT = 0xFFE8ECF1;
    private static final int TEXT_DIM = 0xFF8A939F;
    private static final int ACTIVE_TEXT = 0xFFFFFFFF;

    private static final int ACCENT = 0xFF4FB3FF;      // selection
    /** A handle moved but not yet engaged, and the row the train is really in. */
    private static final int PENDING = 0xFFFFC24B;
    private static final int ENGAGED_MARK = 0xFF8FB6D9;
    private static final int ENGAGED_MARK_TEXT = 0xFFAFC2D4;
    private static final int WARN = 0xFFFFB454;
    private static final int DANGER = 0xFFFF6B5E;
    private static final int REGEN = 0xFF7EE787;

    // Lever track: a recessed groove, ticks at each detent, and the handle.
    private static final int RAIL = 0xFF191D23;
    private static final int RAIL_EDGE = 0xFF343B45;
    private static final int DETENT_MARK = 0xFF4A525C;
    private static final int HANDLE_BODY = 0xFFE4E9EF;
    private static final int HANDLE_EDGE = 0xFF6E7781;
    private static final int HANDLE_GRIP = 0xFF98A1AB;

    /** Scale the header and key hints by this much, so they sit back from the panel. */
    private static final float SMALL = 0.75f;

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
        int pw = TrainControlLayout.PANEL_W;
        int ph = TrainControlLayout.TOTAL_H;
        Font font = mc.font;

        // Card.
        graphics.fill(x, y, x + pw, y + ph, BACKDROP);
        graphics.renderOutline(x, y, pw, ph, BACKDROP_EDGE);
        graphics.fill(x + 1, y + 1, x + pw - 1, y + 2, INSET_HIGHLIGHT);
        graphics.fill(x + 1, y + ph - 2, x + pw - 1, y + ph - 1, INSET_SHADOW);

        TrainGear[] gears = TrainGear.values();
        int serverGear = gear.gear();
        int current = TrainControlInput.displayGear(serverGear);
        boolean valid = current >= 0 && current < gears.length;
        TrainGear selected = valid ? gears[current] : TrainGear.BRAKE;

        // Where the train actually is, which differs from the handle while a selection
        // is pending. Both are marked on the scale so the driver can see the lever has
        // been moved somewhere and the train has not followed yet.
        int engaged = TrainControlInput.engagedGear(serverGear);
        boolean pending = TrainControlInput.hasPendingSelection();

        // Header: the handle's position, with a cue when it is not yet engaged. This
        // is the one thing that must not be ambiguous, because moving the handle
        // changes nothing on its own.
        String header = label(selected);
        graphics.drawString(font, header, x + 5, y + 4, ACTIVE_TEXT, false);
        if (pending) {
            // An asterisk rather than a word: it fits the 12px header, is understood
            // in every language, and matches the hollow handle below, which is the
            // same statement made spatially.
            int hw2 = font.width(header);
            graphics.drawString(font, "*", x + 7 + hw2, y + 4, PENDING, false);
        }

        int cx = TrainControlLayout.slotCenterX(x);
        int railTop = TrainControlLayout.slotTopY(y);
        int railBottom = railTop + TrainControlLayout.SLOT_H + TrainControlLayout.HANDLE_H;

        // Recessed groove: a dark bar with a darker line each side, which reads as
        // inset without needing a texture.
        graphics.fill(cx - 2, railTop, cx + 2, railBottom, RAIL);
        graphics.fill(cx - 2, railTop, cx - 1, railBottom, RAIL_EDGE);
        graphics.fill(cx + 1, railTop, cx + 2, railBottom, RAIL_EDGE);

        // Detents and their labels. Three states, which is what the two-step lever
        // needs to be readable: the handle's position (accent), the position the train
        // is actually in (a bright tick, hollow ring when it is the handle's row too),
        // and everything else (dim).
        for (int i = 0; i < gears.length; i++) {
            int my = TrainControlLayout.handleCenterY(y, i);
            boolean isCurrent = i == current;
            boolean isEngaged = i == engaged;
            int tick = isCurrent ? ACCENT : (isEngaged ? ENGAGED_MARK : DETENT_MARK);
            graphics.fill(cx - 4, my, cx + 4, my + 1, tick);
            // A second tick just under the engaged row, so "the train is here" stays
            // visible even when the handle has been moved away from it.
            if (isEngaged && !isCurrent)
                graphics.fill(cx + 2, my + 2, cx + 5, my + 3, ENGAGED_MARK);
            int textColour = isCurrent ? ACTIVE_TEXT
                    : (isEngaged ? ENGAGED_MARK_TEXT : TEXT_DIM);
            graphics.drawString(font, label(gears[i]), x + 5,
                    TrainControlLayout.detentLabelY(y, i), textColour, false);
        }

        // The handle: a rounded bar with an edge and a grip line, so it reads as a
        // physical handle rather than a blip on the scale.
        //
        // Drawn HOLLOW when the position is selected but not engaged. That is the
        // clearest possible statement of the two-step model - a solid handle is a
        // lever that has taken effect, an outline is one that has not - and it needs
        // no text at all.
        int hy = TrainControlLayout.handleCenterY(y, valid ? current : 0);
        int hw = TrainControlLayout.HANDLE_W;
        int hh = TrainControlLayout.HANDLE_H;
        int hx = cx - hw / 2;
        int hTop = hy - hh / 2;
        if (pending) {
            graphics.renderOutline(hx, hTop, hw, hh, PENDING);
            graphics.fill(hx + 4, hy - 1, hx + hw - 4, hy + 1, PENDING);
        } else {
            graphics.fill(hx, hTop, hx + hw, hTop + hh, HANDLE_BODY);
            graphics.fill(hx + 1, hTop, hx + hw - 1, hTop + 1, HANDLE_GRIP);
            graphics.fill(hx + 1, hTop + hh - 1, hx + hw - 1, hTop + hh, HANDLE_GRIP);
            graphics.fill(hx + 4, hy - 1, hx + hw - 4, hy + 1, HANDLE_GRIP);
            graphics.renderOutline(hx, hTop, hw, hh, HANDLE_EDGE);
        }
        // Accent cap on the handle's side, tying it to the highlighted row.
        graphics.fill(hx - 2, hTop + 2, hx, hTop + hh - 2, pending ? PENDING : ACCENT);

        // Footer: key hints, then the one thing worth saying.
        int fy = y + ph - TrainControlLayout.FOOTER_H + 3;
        drawSmall(graphics, font, hintText(), x + 5, fy, TEXT_DIM);

        String status = statusText(gear);
        if (!status.isEmpty())
            drawSmall(graphics, font, status, x + 5, fy + 9, statusColor(gear));
    }

    /** Draws at 75% scale, without touching the pose stack of the surrounding frame. */
    private static void drawSmall(GuiGraphics graphics, Font font, String text,
                                  int x, int y, int colour) {
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0);
        graphics.pose().scale(SMALL, SMALL, 1f);
        graphics.drawString(font, text, 0, 0, colour, false);
        graphics.pose().popPose();
    }

    /**
     * The key hints, built from the live bindings.
     *
     * <p>Read from the KeyMappings rather than written out, so a player who rebinds
     * sees their own keys here instead of the defaults. The gap between the lever
     * keys and the engage key is wider than the rest, because those two are one
     * gesture - move the handle, then engage it - and running all five together would
     * read as five unrelated keys.
     */
    private static String hintText() {
        return key(TrainControlKeys.LEVER_UP) + "\u2191" + key(TrainControlKeys.LEVER_DOWN)
                + "  " + key(TrainControlKeys.ENGAGE)
                + tr("electroenergetics.train.hint.engage")
                + "   " + key(TrainControlKeys.CONFIRM)
                + "\u786e\u8ba4";
    }

    /** A binding's key name, in the player's language. */
    private static String key(net.minecraft.client.KeyMapping mapping) {
        return mapping.getTranslatedKeyMessage().getString();
    }

    /** The one thing to tell the driver right now, in priority order. */
    private static String statusText(TrainHudData.GearState gear) {
        if (gear.confirmDue())
            return Component.translatable("electroenergetics.train.status.confirm").getString();
        if (gear.emergencyPenalty())
            return Component.translatable("electroenergetics.train.status.penalty").getString();
        if (gear.emergencyArmed())
            return Component.translatable("electroenergetics.train.status.armed").getString();
        if (gear.regen())
            return Component.translatable("electroenergetics.train.status.regen").getString();
        if (gear.gear() == TrainGear.CRUISE.ordinal()) {
            return switch (gear.cruiseState()) {
                case 0 -> Component.translatable("electroenergetics.train.status.holding").getString();
                case 1 -> Component.translatable("electroenergetics.train.status.limited").getString();
                case 2 -> Component.translatable("electroenergetics.train.status.braking").getString();
                default -> Component.translatable("electroenergetics.train.status.stopped").getString();
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

    /**
     * The translated name of a lever position.
     *
     * <p>Translated rather than a literal, so the panel is in the player's language.
     */
    static String label(TrainGear gear) {
        String key = switch (gear) {
            case ACCELERATE -> "electroenergetics.train.gear.accelerate";
            case CRUISE -> "electroenergetics.train.gear.cruise";
            case COAST -> "electroenergetics.train.gear.coast";
            case BRAKE -> "electroenergetics.train.gear.brake";
            case REVERSE -> "electroenergetics.train.gear.reverse";
        };
        return Component.translatable(key).getString();
    }

    /** A translated string, so both panels read in the player's own language. */
    private static String tr(String key) {
        return Component.translatable(key).getString();
    }

    /** The carriage whose controls the local player holds, if any. */
    private static Carriage drivenCarriage() {
        if (!(ControlsHandler.getContraption() instanceof CarriageContraptionEntity cce))
            return null;
        return cce.getCarriage();
    }
}
