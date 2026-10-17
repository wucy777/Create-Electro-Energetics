package com.george_vi.electroenergetics.client;

import com.george_vi.electroenergetics.content.railway_electrification.TrainControlMode;
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

    /** Height of the pantograph-only strip drawn while Create is driving the train. */
    private static final int STRIP_H = 30;

    /**
     * Widest a fitted line may be before it is scaled down, in GUI pixels.
     *
     * <p>The panel is {@code TrainControlLayout.PANEL_W} wide with a 5-pixel inset on each side.
     */
    private static final int FITTED_MAX_W = TrainControlLayout.PANEL_W - 10;

    /**
     * Where the pantograph detail starts, measured from the panel's left inset.
     *
     * <p>Wide enough for the glyph group with a few pantographs, which is what the two share a line.
     */
    private static final int PANTO_DETAIL_X = 40;

    /**
     * Smallest scale the fitting will shrink a line to.
     *
     * <p>Below roughly half of the already-reduced {@link #SMALL}, glyphs stop being legible; at that
     * point shrinking further would hide the message as effectively as overflowing it.
     */
    private static final float MIN_SCALE = 0.45f;

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

        // Handed to Create: no lever panel.
        //
        // The electric driving machinery is deliberately not imposed on Create's controls, which is
        // what the handover means. But the PANTAGRAPH controls have to stay usable and visible,
        // because raising one is the only way back out of CREATE - so a compact strip is drawn
        // instead of the lever panel.
        //
        // Without this the driver would be pressing the pantograph keys completely blind at exactly
        // the moment those keys are the only thing that can help them: no selection, no raised or
        // lowered state, and no explanation when a raise is refused.
        if (!TrainHudData.leverDriven(train.id)) {
            if (TrainControlInput.isAtElectricTrainControls())
                drawPantographStrip(graphics, mc.font, train, graphics.guiWidth(),
                        graphics.guiHeight());
            return;
        }

        if (gear == null)
            return;   // no server sample yet: draw nothing rather than a guess

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

        // The control mode, on the same header line, right-aligned.
        //
        // Without it the panel is silent about the one fact that explains its own behaviour. In
        // DEAD_COAST the traction positions refuse to engage and nothing says why; the driver sees a
        // lever that will not move and has no way to tell "no working pantograph" from "supply below
        // the minimum" from "the mod is broken". Both causes land in the same state and neither is
        // derivable on the client - reach is server-side geometry and the voltage is server-side
        // simulation - so the server's own verdict is the only honest source, and it was already
        // crossing the wire unused.
        //
        // Right-aligned and drawn last on the line so it cannot push the title aside, and fitted to
        // the space the title leaves rather than to the whole panel, because the two share this row.
        TrainControlMode mode = TrainHudData.controlMode(train.id);
        if (mode != null) {
            String modeText = modeLabel(mode);
            int titleEnd = x + 7 + font.width(header) + (pending ? font.width("*") : 0);
            int modeRight = x + pw - 5;
            drawSmallRight(graphics, font, modeText, modeRight, y + 4,
                    Math.max(0, modeRight - titleEnd - 3), modeColour(mode));
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
        //
        // Positioned through TrainControlLayout rather than by adding offsets to a hard-coded base.
        // The base used to be `ph - FOOTER_H + 4`, which put the pantograph row's last glyph 2.75 px
        // below the panel's bottom edge - the row was drawn outside the card. See FOOTER_INSET_TOP.
        drawSmall(graphics, font, hintText(), x + 5,
                TrainControlLayout.footerRowY(y, TrainControlLayout.FOOTER_ROW_HINTS), TEXT_DIM);

        // The lock outranks everything on the status line. While the emergency brake is running -
        // or until the next station after it - the whole panel is inert, and that is the single
        // most important thing to tell the driver: every key they press will do nothing, and
        // without being told why they will read the cab as broken.
        String status = TrainHudData.controlsLocked(train.id)
                ? tr("electroenergetics.train.status.locked")
                : statusText(train, gear);
        if (!status.isEmpty())
            drawSmall(graphics, font, status, x + 5,
                    TrainControlLayout.footerRowY(y, TrainControlLayout.FOOTER_ROW_STATUS),
                    TrainHudData.controlsLocked(train.id) ? DANGER : statusColor(train, gear));

        // Pantographs, over the status line: which one the keys will move, and whether each is up.
        //
        // The selected one is drawn in the accent colour so the driver can see what the toggle key
        // is about to act on BEFORE pressing it. That matters more here than anywhere else on the
        // panel: dropping the wrong pantograph takes the train's supply away and hands it to
        // Create, which is not something to discover afterwards.
        //
        // A pantograph that cannot reach the wire is drawn hollow - the same visual language the
        // lever uses for "selected but not in effect" - so a driver can see that raising it would
        // be refused without having to try.
        drawPantographs(graphics, font, train, x + 5,
                TrainControlLayout.footerRowY(y, TrainControlLayout.FOOTER_ROW_PANTO));
    }

    /** The pantograph row: one glyph per pantograph, the selected one accented. */
    private static void drawPantographs(GuiGraphics graphics, Font font, Train train,
                                        int x, int y) {
        java.util.List<com.george_vi.electroenergetics.content.railway_electrification.pantograph
                .SyncTrainPantographsPacket.Entry> pantographs = TrainHudData.pantographs(train.id);
        if (pantographs.isEmpty())
            return;

        // Measured against the 40 px the row actually owns, not against the whole panel.
        //
        // drawSmall forwards to drawSmallFitted with FITTED_MAX_W (94), which is the panel's own
        // width. But the detail text starts at x + PANTO_DETAIL_X (40), so anything wider than that
        // is drawn straight over it - and drawSmallFitted never shrinks it, because 94 has not been
        // exceeded yet. A three-carriage train with three pantographs is already 40.5 px wide at 75%
        // scale, so this was the ordinary case, not an extreme one.
        drawSmallFitted(graphics, font, pantographGlyphs(pantographs), x, y,
                PANTO_DETAIL_X - 4, TEXT);
        // The detail shares its line with the glyph group, so it only has what is left of the panel:
        // measured from its own left edge to the panel's right inset.
        drawSmallFitted(graphics, font, key(TrainControlKeys.PANTOGRAPH_TOGGLE) + " "
                        + pantographStateText(train, pantographs), x + PANTO_DETAIL_X, y,
                FITTED_MAX_W - PANTO_DETAIL_X, pantographStateColour(train, pantographs));
    }

    /**
     * The pantograph controls on their own, for a train Create is driving.
     *
     * <p>Drawn as a compact strip in the same corner the lever panel occupies, so the keys sit in
     * the same place on screen and the driver's hands do not have to learn a second layout. It shows
     * only the pantographs and the keys that work them - nothing of the electric driving machinery,
     * which is Create's business in this mode.
     */
    private static void drawPantographStrip(GuiGraphics graphics, Font font, Train train,
                                            int guiWidth, int guiHeight) {
        java.util.List<com.george_vi.electroenergetics.content.railway_electrification.pantograph
                .SyncTrainPantographsPacket.Entry> pantographs = TrainHudData.pantographs(train.id);

        // NOT an early return any more. It used to be, and that made the strip vanish exactly when
        // the train had no listed pantographs - which is the state where the driver most needs to be
        // told what happened. A consist whose pantographs are on carriages excluded from the list (the
        // sideways-mounted ones) reports an empty list while still being an electric train that Create
        // has taken over, and the driver would see no panel at all and no explanation.
        //
        // The card is always drawn; only the pantograph rows depend on there being any to show.
        boolean hasPantographs = !pantographs.isEmpty();

        int pw = TrainControlLayout.PANEL_W;
        int ph = STRIP_H;
        int x = TrainControlLayout.panelX(guiWidth);
        int y = TrainControlLayout.panelY(guiHeight) + TrainControlLayout.TOTAL_H - ph;

        graphics.fill(x, y, x + pw, y + ph, BACKDROP);
        graphics.renderOutline(x, y, pw, ph, BACKDROP_EDGE);
        graphics.fill(x + 1, y + 1, x + pw - 1, y + 2, INSET_HIGHLIGHT);

        String pantoTitle = tr("electroenergetics.train.panel.pantographs");
        drawSmall(graphics, font, pantoTitle, x + 5, y + 4, TEXT_DIM);
        // The mode, on the strip's title line, right-aligned - the same treatment the lever panel
        // gives it. Without it the handover is invisible: the lever panel simply disappears and the
        // driver is left to infer from the absent panel that Create now has the train. Saying so is
        // the whole point of the indicator, and this is the state where it matters most.
        //
        // The budget is what the title leaves, computed as an int: the title is drawn at SMALL, so
        // its rendered width is its font width scaled - mixing a scaled float into this arithmetic is
        // what made the first version of this line fail to compile against an int parameter.
        TrainControlMode stripMode = TrainHudData.controlMode(train.id);
        if (stripMode != null) {
            int titleEnd = x + 5 + Math.round(font.width(pantoTitle) * SMALL);
            int modeRight = x + pw - 5;
            drawSmallRight(graphics, font, modeLabel(stripMode), modeRight, y + 4,
                    Math.max(0, modeRight - titleEnd - 3), modeColour(stripMode));
        }
        // The pantograph rows exist only when there is something to list. Both helpers below index
        // the list (pantographStateText does `pantographs.get(min(selected, size - 1))`), so calling
        // them on an empty list would be an index error rather than a blank line - the early return
        // that used to guard this whole method was the only thing standing between them and that.
        if (hasPantographs) {
            // Same correction as drawPantographs: the glyph row starts at x + 5 and the detail text
            // at x + PANTO_DETAIL_X, so the row owns PANTO_DETAIL_X - 5 pixels and must be measured
            // against that. Using drawSmall here measured against the whole panel and let the glyphs
            // run over the detail from three pantographs upward.
            drawSmallFitted(graphics, font, pantographGlyphs(pantographs), x + 5, y + 15,
                    PANTO_DETAIL_X - 9, TEXT);
            drawSmallFitted(graphics, font, key(TrainControlKeys.PANTOGRAPH_TOGGLE) + " "
                            + pantographStateText(train, pantographs), x + PANTO_DETAIL_X, y + 15,
                    FITTED_MAX_W - PANTO_DETAIL_X, pantographStateColour(train, pantographs));
        } else {
            // Nothing to name, but the driver still needs the state: on an electric train there is
            // no other place this appears. The same line the detail row uses, in the same colour
            // language, saying why the key would do nothing.
            drawSmall(graphics, font, key(TrainControlKeys.PANTOGRAPH_TOGGLE) + " "
                    + tr("electroenergetics.train.panto.none"), x + 5, y + 15, WARN);
        }
    }

    /**
     * How many pantograph glyphs the row draws at most.
     *
     * <p>Three characters per pantograph runs out of room quickly, and drawSmallFitted only shrinks as
     * far as MIN_SCALE (0.45). Past that the row would be drawn straight over the detail text on the
     * same line - measured, not guessed: at the floor of 0.45 a character is 2.70 px, so the row's
     * real 31 px budget holds 11 characters, and three glyphs alone is nine.
     */
    private static final int MAX_GLYPHS = 2;

    /**
     * One glyph per pantograph: {@code P} raised, {@code p} lowered, the selected one bracketed.
     *
     * <p>Deliberately not coloured per pantograph: which one is raised is the driver's own doing and
     * the letters say it, so colour is spent on the one thing they cannot see from the panel - the
     * state of the pantograph they are about to act on.
     *
     * <p>No "3 of 12" counter. It was tried and removed: the counter needed two to five more
     * characters, which is 5 to 13 px at the floor, and the row does not have it. The bracketed
     * glyph already says which pantograph the keys are on, and cycling past the cap moves the window
     * so the bracket stays visible - so the counter cost width to say something the panel showed.
     *
     * <p>The window is kept centred on the selection so the row does not appear to jump as the
     * driver cycles, and the selected one is always inside it (checked exhaustively for every
     * (count, selection) pair up to 40).
     */
    private static String pantographGlyphs(java.util.List<
            com.george_vi.electroenergetics.content.railway_electrification.pantograph
                    .SyncTrainPantographsPacket.Entry> pantographs) {
        int n = pantographs.size();

        // Clamped exactly as pantographStateText and pantographStateColour clamp it.
        //
        // This is not belt-and-braces. TrainControlInput clamps the selection, but that clamp lives
        // inside `if (!locked && !pantographs.isEmpty())`, so it does not run while the emergency lock
        // is engaged. A carriage removed during a lockout therefore leaves the selection pointing past
        // the end of the list. With an unclamped selection here, no index ever equals it, so the row
        // drew no brackets at all while the detail line - which does clamp - described the last
        // pantograph. The panel would say two different things at once.
        //
        // Clamping here keeps the glyph row and the detail row telling the same story regardless of
        // what the selection field holds. It touches display only; the packet path has its own bounds
        // check on the server.
        int selected = Math.min(TrainControlInput.selectedPantograph(), n - 1);

        // Which indices to draw: everything when it fits, otherwise a window centred on the selection
        // and clamped to the list. Clamping is what keeps the LAST pantographs reachable - an
        // unclamped window would point past the end and draw nothing.
        int[] shown;
        if (n <= MAX_GLYPHS) {
            shown = new int[n];
            for (int i = 0; i < n; i++)
                shown[i] = i;
        } else {
            int half = MAX_GLYPHS / 2;
            int from = Math.max(0, Math.min(selected - half, n - MAX_GLYPHS));
            shown = new int[MAX_GLYPHS];
            for (int i = 0; i < MAX_GLYPHS; i++)
                shown[i] = from + i;
        }

        StringBuilder line = new StringBuilder();
        for (int i : shown) {
            line.append(i == selected ? '[' : ' ');
            line.append(pantographs.get(i).active() ? 'P' : 'p');
            line.append(i == selected ? ']' : ' ');
        }
        return line.toString();
    }

    /**
     * What the selected pantograph is doing, in words.
     *
     * <p>Words rather than colour alone, because the two refusals read very differently and a driver
     * needs to know which one they have hit: "no catenary in reach" is a fact about where they are,
     * while the emergency lock is a rule that will not lift until the next station.
     */
    private static String pantographStateText(Train train, java.util.List<
            com.george_vi.electroenergetics.content.railway_electrification.pantograph
                    .SyncTrainPantographsPacket.Entry> pantographs) {
        if (TrainHudData.controlsLocked(train.id))
            return tr("electroenergetics.train.panto.locked");
        var sel = pantographs.get(Math.min(TrainControlInput.selectedPantograph(),
                pantographs.size() - 1));
        if (sel.active())
            return tr("electroenergetics.train.panto.up");
        if (sel.reachable())
            return tr("electroenergetics.train.panto.canRaise");
        return tr("electroenergetics.train.panto.noWire");
    }

    private static int pantographStateColour(Train train, java.util.List<
            com.george_vi.electroenergetics.content.railway_electrification.pantograph
                    .SyncTrainPantographsPacket.Entry> pantographs) {
        if (TrainHudData.controlsLocked(train.id))
            return DANGER;
        var sel = pantographs.get(Math.min(TrainControlInput.selectedPantograph(),
                pantographs.size() - 1));
        if (sel.active())
            return REGEN;
        return sel.reachable() ? TEXT_DIM : WARN;
    }

    /** Draws at 75% scale, without touching the pose stack of the surrounding frame. */
    private static void drawSmall(GuiGraphics graphics, Font font, String text,
                                  int x, int y, int colour) {
        drawSmallFitted(graphics, font, text, x, y, FITTED_MAX_W, colour);
    }

    /**
     * Draws at 75% scale, shrinking further if the text would run past the panel.
     *
     * <p>This exists because the panel is a fixed 104 pixels wide and its text is translated, so a
     * string that fits in one language can overrun the screen in another. That is not a hypothetical:
     * the comment on {@link #hintText()} records the user reporting exactly this ("the up/down hint
     * at the bottom is cut off and I cannot see it"), and every string added since has had to be
     * measured by hand to avoid repeating it.
     *
     * <p>Measuring here removes the need for that. The text is drawn at {@code SMALL} unless its
     * width exceeds {@code maxWidth}, in which case the scale is reduced by exactly the ratio needed
     * and no further, down to {@link #MIN_SCALE}. A longer translation is then merely smaller rather
     * than invisible, which is the failure that actually matters: a driver who has just had the whole
     * panel locked needs to be told why, and text drawn off the edge of the screen tells them nothing.
     *
     * <p>The floor is deliberate. Beyond it the text would be unreadable at any scale, and silently
     * shrinking a paragraph to a grey smear would be worse than letting it overflow where it can at
     * least be seen. {@code _cache/check_hud_fit.py} reports every string that reaches this floor.
     */
    private static void drawSmallFitted(GuiGraphics graphics, Font font, String text,
                                        int x, int y, int maxWidth, int colour) {
        float scale = SMALL;
        float width = font.width(text) * SMALL;
        if (width > maxWidth && width > 0f)
            scale = Math.max(MIN_SCALE, SMALL * maxWidth / width);
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0);
        graphics.pose().scale(scale, scale, 1f);
        graphics.drawString(font, text, 0, 0, colour, false);
        graphics.pose().popPose();
    }

    /**
     * Draws at 75% scale ending exactly at {@code right}, shrinking further to fit {@code maxWidth}.
     *
     * <p>The mirror of {@link #drawSmallFitted}, for text that shares a line with something else and
     * must therefore be justified against a fixed right edge instead of starting at a fixed left one.
     * The control-mode label is the only user: it sits on the header row beside the panel title, and
     * the title's width varies with the language, so a left-anchored label would either collide with
     * it or leave a ragged gap.
     *
     * <p>Measured after scaling, so the right edge lands where it is asked to whatever the scale ends
     * up being. Without that the label would creep leftwards as it shrank.
     */
    private static void drawSmallRight(GuiGraphics graphics, Font font, String text, int right,
                                       int y, int maxWidth, int colour) {
        float scale = SMALL;
        float width = font.width(text) * SMALL;
        if (width > maxWidth && width > 0f)
            scale = Math.max(MIN_SCALE, SMALL * maxWidth / width);
        float drawn = font.width(text) * scale;
        graphics.pose().pushPose();
        graphics.pose().translate(right - drawn, y, 0);
        graphics.pose().scale(scale, scale, 1f);
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
    private static String statusText(Train train, TrainHudData.GearState gear) {
        if (gear.confirmDue())
            return Component.translatable("electroenergetics.train.status.confirm").getString();
        if (gear.emergencyPenalty())
            return Component.translatable("electroenergetics.train.status.penalty").getString();
        if (gear.emergencyArmed())
            return Component.translatable("electroenergetics.train.status.armed").getString();
        // Raised onto wire, but the wire is not live enough. This is the operator's "不够电压" case
        // and it is NOT the same as "no wire": the driver did the right thing, is still touching the
        // catenary, and the train still will not pull - because another train has taken the supply
        // or the section is sagging. Without this line the panel would say nothing at all in that
        // state, and "I raised it and nothing happened" is indistinguishable from a broken key.
        //
        // Ordered above regen and cruise because it explains why traction is unavailable, which is a
        // precondition for either of those readouts meaning anything.
        if (supplyStarved(train))
            return Component.translatable("electroenergetics.train.status.noSupply").getString();
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

    /**
     * A pantograph is raised and touching catenary, but the train has no supply.
     *
     * <p>Three facts, all of which the client already holds, and each of which rules out a different
     * explanation:
     *
     * <ul>
     *   <li>some pantograph is {@code active} - the driver has done their part;</li>
     *   <li>that pantograph is {@code reachable} - the wire is physically there, so this is not the
     *       "no catenary in reach" case the strip already names;</li>
     *   <li>the sample is not {@code powered} - and the server has said so, which is the only
     *       authority on the voltage.</li>
     * </ul>
     *
     * <p>Deliberately requires the raised pantograph to be a REACHABLE one, rather than any raised
     * pantograph. A driver who has a raised pantograph on a carriage that is nowhere near wire has a
     * different problem - the train is coasting because nothing is touching - and telling them the
     * voltage is insufficient would send them looking at the wrong thing.
     */
    private static boolean supplyStarved(Train train) {
        TrainHudData.GearState gear = TrainHudData.gear(train.id);
        if (gear == null)
            return false;
        var pantographs = TrainHudData.pantographs(train.id);
        boolean touching = false;
        for (var pe : pantographs) {
            if (pe.active() && pe.reachable()) {
                touching = true;
                break;
            }
        }
        if (!touching)
            return false;
        // Read `powered` directly rather than through emergencyBrakeAvailable. They answer the same
        // question today - both are `isPowered || hasCreativeSource` - but the sample already carries
        // the server's verdict and must be the one source of it here: asking a helper named after a
        // different feature would make this line look like it depends on that feature, and a future
        // change to it would silently change what this message says.
        return !TrainHudData.powered(train.id);
    }

    /** The name of a control mode, in the driver's language. */
    private static String modeLabel(TrainControlMode mode) {
        String key = switch (mode) {
            case ELECTRIC -> "electroenergetics.train.mode.electric";
            case DEAD_COAST -> "electroenergetics.train.mode.deadCoast";
            case CREATE -> "electroenergetics.train.mode.create";
        };
        return Component.translatable(key).getString();
    }

    /**
     * The colour of a control-mode label.
     *
     * <p>Dim for the normal state and a warning for the one where the driver has lost traction
     * without doing anything wrong. The header is an always-on element, so it is kept quiet: the
     * point is that a driver can find it when the lever will not engage, not that it draws the eye
     * during a normal run.
     */
    private static int modeColour(TrainControlMode mode) {
        return switch (mode) {
            case ELECTRIC -> TEXT_DIM;
            case DEAD_COAST -> WARN;
            case CREATE -> TEXT_DIM;
        };
    }

    private static int statusColor(Train train, TrainHudData.GearState gear) {
        if (gear.confirmDue())
            return DANGER;
        if (gear.emergencyPenalty())
            return WARN;
        if (supplyStarved(train))
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
