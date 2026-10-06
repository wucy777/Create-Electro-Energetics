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

        // Header: the panel's TITLE, deliberately not the gear's name.
        //
        // It used to print the selected position, which is the same text as one of the
        // five labels below - so it read as a sixth detent sitting right above the first.
        // Reported as "the current gear is so close to the first position that I thought
        // it was another position". The handle already shows the position on the scale,
        // so the title says what the panel is. The pending cue is an asterisk after the
        // title now, which cannot be mistaken for a position either.
        String header = tr("electroenergetics.train.panel.lever");
        graphics.drawString(font, header, x + 5, y + 3, TEXT_DIM, false);
        if (pending) {
            int hw2 = font.width(header);
            graphics.drawString(font, "*", x + 7 + hw2, y + 3, PENDING, false);
        }

        // Rule under the title, so the title and the scale cannot be read as one list of
        // positions. Without it the title sits at the same indent and spacing as the
        // labels, which is what made them look like one column.
        graphics.fill(x + 4, TrainControlLayout.dividerY(y), x + pw - 4,
                TrainControlLayout.dividerY(y) + 1, BACKDROP_EDGE);

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

        // Footer: key hints on one line, the one thing worth saying on the next.
        int fy = y + ph - TrainControlLayout.FOOTER_H + 4;
        drawSmall(graphics, font, hintText(), x + 5, fy, TEXT_DIM);

        String status = statusText(gear);
        if (!status.isEmpty())
            drawSmall(graphics, font, status, x + 5, fy + 11, statusColor(gear));
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
     * <p>Read from the KeyMappings rather than written out, so a player who rebinds sees
     * their own keys here instead of the defaults.
     *
     * <p>Drawn as GLYPHS - arrows and the word for the engage key - rather than as the
     * bindings' translated names. That was the overflow: {@code getTranslatedKeyMessage}
     * for the arrow keys is "up arrow key" / "down arrow key" (and four-character
     * equivalents in Chinese), so the hint line was far wider than the 104-pixel panel and
     * ran off the bottom-right of the screen. Reported as "the up/down hint at the bottom
     * is cut off and I cannot see it". An arrow is also the clearer thing to show for a
     * lever, since it says which WAY the handle moves rather than which key it is.
     *
     * <p>Only the engage key is named, because that one is not obvious from a glyph - and
     * it is the key that actually does something, the arrows only move the handle.
     */
    private static String hintText() {
        return "\u2191\u2193 " + tr("electroenergetics.train.hint.move")
                + "   " + key(TrainControlKeys.ENGAGE)
                + tr("electroenergetics.train.hint.engage");
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
