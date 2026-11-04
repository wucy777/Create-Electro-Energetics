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
        drawSmall(graphics, font, hintText(TrainHudData.pantographs(train.id).size()), x + 5,
                TrainControlLayout.footerRowY(y, TrainControlLayout.FOOTER_ROW_HINTS), TEXT_DIM);

        // The one-driver rule comes before everything on the status line, including the lock.
        // A player who is not this train's driver has the panel open but no controls at all:
        // their keys are not sent, and the server would refuse them if they were. Without
        // this line the panel looks exactly like a driver's, so they press keys, nothing
        // happens, and the cab reads as broken. Read-only outranks the lock because it is
        // the state that stays true - the lock lifts by itself, the missing controls do not.
        String readOnly = DriverPanelClient.readOnlyStatus(train.id);

        // The lock outranks the ordinary status text. While the emergency brake is running -
        // or until the next station after it - the whole panel is inert, and that is the single
        // most important thing to tell the driver: every key they press will do nothing, and
        // without being told why they will read the cab as broken.
        String status = readOnly != null
                ? readOnly
                : (TrainHudData.controlsLocked(train.id)
                        ? tr("electroenergetics.train.status.locked")
                        : statusText(train, gear));
        if (!status.isEmpty())
            drawSmall(graphics, font, status, x + 5,
                    TrainControlLayout.footerRowY(y, TrainControlLayout.FOOTER_ROW_STATUS),
                    readOnly != null ? WARN
                            : (TrainHudData.controlsLocked(train.id) ? DANGER : statusColor(train, gear)));

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

    /**
     * The pantograph row: the consist drawn as carriages, each arm marked above the carriage it is on,
     * the selected one accented.
     */
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
        drawPantographConsist(graphics, train, pantographs, x, y);
        // The detail shares its line with the glyph group, so it only has what is left of the panel:
        // measured from its own left edge to the panel's right inset.
        //
        // Drawn only while some arm is in manual mode. The operator asked for the original mod's
        // automatic raise/lower back, and the original had no such switch at all: with every arm on
        // automatic this line announced a key that does nothing, which reads as a broken control.
        // The row is left blank instead, and the panel's height does not change.
        if (anyManualPantograph(pantographs)) {
            drawSmallFitted(graphics, font, key(TrainControlKeys.PANTOGRAPH_TOGGLE) + " "
                            + pantographStateText(train, pantographs), x + PANTO_DETAIL_X, y,
                    FITTED_MAX_W - PANTO_DETAIL_X, pantographStateColour(train, pantographs));
        }
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
            // The same consist strip as the electric panel. Two panels, one picture: the marks above
            // the carriages mean the same thing whichever scheme is driving, and a driver handed to
            // Create should not lose the only place that says which arm is on which carriage.
            drawPantographConsist(graphics, train, pantographs, x + 5, y + 15);
            // Same rule as the lever panel: the manual-mode line is blank while every arm is on
            // automatic, so a driver handed to Create sees the original mod's appearance by default.
            if (anyManualPantograph(pantographs)) {
                drawSmallFitted(graphics, font, key(TrainControlKeys.PANTOGRAPH_TOGGLE) + " "
                                + pantographStateText(train, pantographs), x + PANTO_DETAIL_X, y + 15,
                        FITTED_MAX_W - PANTO_DETAIL_X, pantographStateColour(train, pantographs));
            }
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
     * Carriages the strip draws at most.
     *
     * <p>Twenty was the old cap, which a coupled pair of units (重联) exceeds. Two rows of up to
     * twenty is the answer to "what happens with a double unit", so the strip carries forty before
     * it starts dropping carriages off the end.
     */
    private static final int MAX_CARRIAGES = 40;

    /**
     * Longest consist still drawn on a single row, counted in SLOTS after merging.
     *
     * <p>Slots rather than carriages: a slot is one drawn unit, so it is what decides the width of
     * the row. Merging a long run of pantograph-less carriages into one slot can bring a forty-car
     * train back onto a single row, and that is the point of the merge.
     */
    // Eight slots per row, not twelve. Every slot is a carriage and the strip only has a fixed budget of
    // pixels, so twelve made each carriage about two pixels wide - wide enough to show that a carriage exists
    // and nothing else. The operator asked for longer carriages, and the cost of it is an earlier wrap onto
    // the second row.
    private static final int SINGLE_ROW_MAX = 8;

    /**
     * Height of a carriage body, in pixels - five rows, in BOTH layouts.
     *
     * <p>Five, not the four (single row) and three (two-row) this had. The owner asked for the body to
     * be five rows and for the cab to be drawn as a pixel arc instead of the old step ("加高面板后直接
     * 五行精画车头弧线"), and five is also the first height at which the arc can have a gradient at
     * all: at four rows the nose gets two distinct reaches, at five it gets three and reads as a
     * streamlined head rather than a corner.
     *
     * <p>The two-row layout uses the same five rows now, which is why the panel's band grew from 12
     * rows to 14 (see {@code TrainControlLayout.FOOTER_H}): two bands of (mark row + five body rows)
     * plus the one-row link between them is 13 rows, and the band's rows are counted down to the
     * panel's own outline, so the band needs 14. Nothing else in the panel moved.
     */
    private static final int CARRIAGE_H = 5;

    /**
     * The five-row cab silhouette, expressed as the number of pixels extending past the body on each row.
     *
     * <p>The operator's final pattern is 00011 / 00111 / 11111 / 00111 / 01111, so the row widths are
     * {@code [2, 3, 5, 3, 4]}. It is intentionally not a monotone taper: the bottom row is one pixel wider
     * than the row above, matching the supplied front profile rather than a generic arrowhead.
     *
     * <p>The trailing cab mirrors the same data, so both ends remain symmetric when the train reverses.
     * The array length must equal {@link #CARRIAGE_H}; its maximum must fit {@link #CARRIAGE_NOSE_W}.
     */
    private static final int[] CARRIAGE_NOSE_ARC = {2, 3, 5, 3, 4};

    /**
     * How far the nose reaches past the body at its widest row - the arc's maximum.
     *
     * <p>A named constant because the width solver needs it before any arc is drawn, and the two have
     * to agree: the strip reserves {@code 2 * CARRIAGE_NOSE_W} pixels for the two noses, and the body
     * starts that far inside the strip's left edge. An arc wider than this would overrun the reserve.
     */
    private static final int CARRIAGE_NOSE_W = 5;

    /**
     * Rows the strip may use inside the panel, counted down from the y the caller passes in.
     *
     * <p>Read out of the layout rather than restated: the panel's pantograph row is the last of its
     * three footer rows, so the band is everything from that row down to the panel's bottom edge.
     * Its last row is the panel's own one-pixel outline, which nothing here paints over.
     */
    private static final int PANTO_BAND_ROWS = TrainControlLayout.TOTAL_H
            - TrainControlLayout.footerRowY(0, TrainControlLayout.FOOTER_ROW_PANTO);

    /**
     * A merged segment's block, deliberately darker than a carriage.
     *
     * <p>It has to be unmistakable that this is not one carriage but a compressed stretch of them,
     * and the count drawn with it is what says how many. Insetting the block's ends would not be
     * enough on its own at this size.
     */
    private static final int MERGED_BLOCK = 0xFF3A424C;

    /** Width of a count digit, and the gap between two of them. */
    private static final int COUNT_DIGIT_W = 3;
    private static final int COUNT_DIGIT_GAP = 1;

    /** Rows of a count digit. */
    private static final int COUNT_ROWS = 4;

    /**
     * Extra width a two-row merged block needs around its count.
     *
     * <p>In the single-row layout the count sits under the block, so the block only has to be wide
     * enough to look like a block. In the two-row layout there is no row below the block to put the
     * count on - the row under the top row is the bottom row - so the count is drawn inside the
     * block, and without a margin around it the block reads as a bright blob rather than a number.
     */
    private static final int COUNT_PAD = 4;

    /**
     * The count glyphs: ten digits, four rows of three columns each, {@code #} = ink.
     *
     * <p>Static strings and {@code charAt} only. This is the one place the strip draws a number, and
     * it must not allocate on the render path; the whole table is a few bytes in the constant pool
     * and the runtime never touches it. Three pixels wide is the widest a count can afford - a
     * two-carriage block on a busy line is only a few pixels wide - and four rows is the shortest
     * shape that still tells 2 from 3 and 5 from 6.
     */
    private static final String[] COUNT_GLYPHS = {
            "###" + "#.#" + "#.#" + "###",   // 0
            "..#" + "..#" + "..#" + "..#",   // 1
            "###" + "..#" + "#.." + "###",   // 2
            "###" + "..#" + "..#" + "###",   // 3
            "#.#" + "#.#" + "###" + "..#",   // 4
            "###" + "#.." + "..#" + "###",   // 5
            "###" + "#.." + "#.#" + "###",   // 6
            "###" + "..#" + "..#" + "..#",   // 7
            "###" + "#.#" + "###" + "###",   // 8
            "###" + "#.#" + "###" + "..#",   // 9
    };

    /**
     * The consist, drawn as one or two rows of carriage silhouettes, with each pantograph above the
     * carriage it is fitted to, the runs of pantograph-less carriages merged into counted blocks, and
     * a left arrow for the direction of travel.
     *
     * <h2>Why a drawing rather than a glyph string</h2>
     *
     * <p>The row used to be "pPp" - one character per pantograph. It told the driver how many there
     * were and nothing else: not which carriage any of them was on, not how long the train was, and
     * not which end was leading. A strip of carriages carries all three in the same space, because
     * the position of a mark above a carriage IS which carriage it is on.
     *
     * <h2>Why the client needs the carriage count</h2>
     *
     * <p>The list only contains carriages that have a pantograph. A rake with one power car would draw
     * as a single carriage, which is worse than useless - it would misrepresent the train's length. The
     * count comes from the server in the sync packet because {@code train.carriages} is empty on the
     * client: Carriage is server state there, the same reason the control gate cannot read it.
     *
     * <h2>Merged segments</h2>
     *
     * <p>A train can carry its arms on very few carriages, and drawing every one of the carriages in
     * between as its own rectangle leaves no width for any of them. So a maximal run of consecutive
     * pantograph-less carriages is drawn as ONE merged block with its length written next to it.
     * The two end carriages are never merged: they are the cabs and they keep their arcs, so only
     * display positions 1..n-2 take part.
     *
     * <p>The count is the part that must not be lost. It is drawn as pixel digits from
     * {@link #COUNT_GLYPHS}: three by four pixels per digit, two digits at most, under the block in
     * the single-row layout and inside it when two rows leave no row beneath.
     *
     * <h2>Two rows for a coupled unit</h2>
     *
     * <p>Up to {@link #SINGLE_ROW_MAX} SLOTS the strip is one row; beyond that it wraps onto two, the
     * top row carrying the leading half and the bottom row the trailing half. Slots, not carriages:
     * the drawn width is per slot. Both rows read from the leading end, so the reading direction
     * never reverses, and the break is drawn as a link line from the end of the top row back to above
     * the start of the bottom row. No caption: it would not fit on the row.
     *
     * <h2>Shape</h2>
     *
     * <p>Carriages are plain rectangles five rows tall in BOTH layouts (see {@link #CARRIAGE_H}: the
     * owner asked for five rows once three and four both read as bars). Only the two end cars carry a
     * nose drawn with the five-row pixel profile [2, 3, 5, 3, 4].
     * The trailing cab mirrors this profile to the right.
     *
     * Interior carriages stay flush rectangles: they are the coupled cars, not more noses.
     *
     * <p>The selected carriage is marked by its body colour alone - bright against the dim ones,
     * the same cue the lever's handle row uses. Merged blocks never are: they carry no arm, so the
     * selected one cannot be in them.
     *
     * <h2>Where the arrow and the count go</h2>
     *
     * <p>Inside the panel, and inside its band. The band is 14 rows ({@link #PANTO_BAND_ROWS}) and its
     * last row is the panel's own one-pixel outline, so 13 rows of it may take ink. The count needs
     * four rows below the block, so a single row WITH merged segments puts the arrow in the four rows
     * above the strip and the counts in the four rows at the bottom of the band (rows 9..12). A plain
     * single row has no counts, so the arrow keeps the place the driver already knows it in: under the
     * strip, in those same four rows. The two-row layout has no row below either band - its two bands
     * and the link use rows 0..12, all 13 ink rows - so a merged block carries its count inside itself
     * and the arrow takes the four rows above. Every one of those placements is measured, not
     * estimated: the status line's ink ends at y-4.25 and the arrow above starts at y-4, while the
     * arrow below ends on the band's second-to-last row because the band's last row is the panel's
     * own one-pixel outline. None of it depends on the GUI scale: the panel is anchored to the
     * bottom-right corner and none of these numbers is scaled.
     *
     * <h2>Cost</h2>
     *
     * <p>Fills only, and nothing is allocated: the pantograph list is walked by index, the arms are
     * a bit mask in a {@code long}, and the count digits come from a static table. This runs every
     * frame while the driver holds the cab.
     */
    private static void drawPantographConsist(GuiGraphics graphics, Train train,
                                              java.util.List<
                                                      com.george_vi.electroenergetics.content
                                                              .railway_electrification.pantograph
                                                              .SyncTrainPantographsPacket.Entry> pantographs,
                                              int x, int y) {
        int n = Math.max(1, Math.min(MAX_CARRIAGES, TrainHudData.carriageCount(train.id)));

        // The leading end is ALWAYS on the left, whatever the train is doing - the owner's rule. A
        // multiple unit has a cab at both ends, so which end leads changes with the direction of
        // travel and cannot be inferred from the model; it has to be drawn. When the train is running
        // the other way the whole strip is mirrored, carriages, marks and both arcs together.
        // Which end of the strip is the leading one. With a driver aboard it is the cab they took, and that
        // has to come from the claim rather than from the speed: on a stationary train the speed still holds
        // whatever the last movement was, so switching ends left the strip - and every pantograph on it -
        // describing the previous direction until the train rolled forward a little.
        int claimCarriage = com.george_vi.electroenergetics.content.railway_electrification.TrainHudData
                .driverClaimCarriage(train.id);
        boolean leadingEndIsFirst = claimCarriage >= 0
                ? claimCarriage * 2 < n
                : train.speed >= 0;

        // Where the arms are, one bit per carriage in DISPLAY order (bit 0 = leading end). A long
        // rather than an array or a list of per-carriage flags: the merge rule and the icon placement
        // only ever ask "has this carriage got an arm", and a bit test answers that with no
        // allocation at all.
        long arms = displayPantographMask(pantographs, n, leadingEndIsFirst);

        // Slots: a maximal run of pantograph-less interior carriages is one slot, EVERYTHING ELSE -
        // an arm carriage, or either end carriage, which must keep its arc - is one slot per
        // carriage.
        int slots = 0;
        for (int pos = 0; pos < n; pos += consistUnitLength(pos, n, arms))
            slots++;

        boolean singleRow = slots <= SINGLE_ROW_MAX;
        int topSlots = singleRow ? slots : (slots + 1) / 2;
        int bottomSlots = slots - topSlots;
        // Both layouts use the same five-row body. The two-row layout used to give one row back so
        // that its two bands, their mark rows, the link and the arrow all fit a 12-row band; the band
        // is 14 rows now (TrainControlLayout.FOOTER_H), so it no longer has to, and the two layouts
        // cannot drift apart.
        int carH = CARRIAGE_H;

        // Width. The row shares its line with the pantograph detail text, which starts PANTO_DETAIL_X
        // from the panel's inset, so the strip owns PANTO_DETAIL_X - 8 pixels; an end carriage's nose
        // and tail reach CARRIAGE_NOSE_W pixels past their body, and the budget keeps that much
        // clear at each end. Every slot gets the same base width, except a merged slot, which is
        // widened when it needs the room for its count. The base width is the largest one both rows
        // can afford; if even one-pixel slots with a gap do not fit, the gap goes first - that is the
        // "past forty it still compresses" rule.
        int avail = PANTO_DETAIL_X - 8;
        int budget = avail - 2 * CARRIAGE_NOSE_W;
        int topOneDigit = 0;
        int topTwoDigit = 0;
        int bottomOneDigit = 0;
        int bottomTwoDigit = 0;
        int slot = 0;
        for (int pos = 0; pos < n; ) {
            int len = consistUnitLength(pos, n, arms);
            if (len >= 2) {
                boolean top = slot < topSlots;
                if (len >= 10) {
                    if (top)
                        topTwoDigit++;
                    else
                        bottomTwoDigit++;
                } else {
                    if (top)
                        topOneDigit++;
                    else
                        bottomOneDigit++;
                }
            }
            slot++;
            pos += len;
        }
        int gap = 1;
        int baseW = 0;
        for (int attempt = 0; attempt < 2 && baseW < 1; attempt++) {
            gap = 1 - attempt;   // one-pixel gap first, none if that cannot fit
            for (int candidate = budget; candidate >= 1; candidate--) {
                if (consistRowWidth(candidate, gap, topSlots, topOneDigit, topTwoDigit, !singleRow) <= budget
                        && consistRowWidth(candidate, gap, bottomSlots, bottomOneDigit, bottomTwoDigit,
                                true) <= budget) {
                    baseW = candidate;
                    break;
                }
            }
        }
        if (baseW < 1)
            baseW = 1;
        boolean hasMerged = topOneDigit + topTwoDigit + bottomOneDigit + bottomTwoDigit > 0;

        // The nose reaches CARRIAGE_NOSE_W pixels left of the body, so the bodies start that far
        // in. Both rows start at that same column, which is what makes the two halves read as one
        // train.
        int bodyX = x + CARRIAGE_NOSE_W;
        int topBand = y;                     // the first row's pantograph-mark row
        int bottomBand = y + carH + 3;       // the second row's, one link row below the first band

        int selected = Math.min(TrainControlInput.selectedPantograph(),
                Math.max(0, pantographs.size() - 1));
        int selectedCarriage = pantographs.isEmpty() ? -1 : pantographs.get(selected).carriageIndex();

        int topX = bodyX;
        int bottomX = bodyX;
        slot = 0;
        for (int pos = 0; pos < n; ) {
            int len = consistUnitLength(pos, n, arms);
            int row = slot < topSlots ? 0 : 1;
            int band = row == 0 ? topBand : bottomBand;
            int bodyY = band + 2;
            int sx = row == 0 ? topX : bottomX;
            int width = len >= 2
                    ? Math.max(baseW, consistCountWidth(len) + (singleRow ? 0 : COUNT_PAD))
                    : baseW;

            if (len >= 2) {
                // A compressed stretch: a block darker than a carriage, inset at both ends, with the
                // number of carriages it stands for. The count cannot be inferred from the block's
                // width, and it is not meant to be - the number is the honest part.
                int inset = width >= 4 ? 1 : 0;
                int blockTop = singleRow ? bodyY : bodyY - 1;
                graphics.fill(sx + inset, blockTop, sx + width - inset, bodyY + carH, MERGED_BLOCK);
                int countX = sx + (width - consistCountWidth(len)) / 2;
                int countY = singleRow ? y + PANTO_BAND_ROWS - COUNT_ROWS - 1 : blockTop;
                drawConsistCount(graphics, countX, countY, len, TEXT);
            } else {
                // One carriage. slot counts from the leading end; pos is its display position, and
                // i = the carriage's index in the train, which is what the selection is keyed by.
                int i = leadingEndIsFirst ? pos : n - 1 - pos;
                int colour = i == selectedCarriage ? TEXT : TEXT_DIM;

                graphics.fill(sx, bodyY, sx + width, bodyY + carH, colour);

                // The nose arcs on the two end cars only. Every other carriage stays a plain
                // rectangle with the one-pixel gap the row leaves, which is what keeps the strip
                // reading as a consist: a row of noses would be a row of arrows. The arc is drawn one
                // body row at a time from CARRIAGE_NOSE_ARC, so the reach changes with the row rather
                // than stepping once at a fixed height.
                if (slot == 0)
                    drawCarriageNose(graphics, sx, bodyY, colour, false);
                if (slot == slots - 1)
                    drawCarriageNose(graphics, sx + width, bodyY, colour, true);

                // The carriage's arms, above it and in its own column. Two arms on one carriage
                // overdraw each other at the same spot, exactly as they always have.
                if ((arms & (1L << pos)) != 0L) {
                    int mx = sx + (width - 1) / 2;
                    int barW = Math.min(5, width);
                    int barX = mx - (barW - 1) / 2;
                    for (int k = 0; k < pantographs.size(); k++) {
                        var entry = pantographs.get(k);
                        int carriageIndex = Math.max(0, Math.min(n - 1, entry.carriageIndex()));
                        int displayPos = leadingEndIsFirst ? carriageIndex : n - 1 - carriageIndex;
                        if (displayPos != pos)
                            continue;
                        // Blue while the arm is working (the switch is on), red once it is parked.
                        int iconColour = entry.autoRaise() ? 0x5AB0FF : DANGER;
                        // Five columns, two rows, the operator's own art:
                        //   lowered   11111        raised   00001
                        //             10101                 00010
                        // Lowered is the collector bar seen from the side, lying flat on three feet; raised is
                        // the arm raked up, the same stroke the side view shows. Five wide rather than eight:
                        // at eight the last column fell into the one-pixel gap between carriages and left a
                        // stray mark on the black divider, and the origin is clamped inside the carriage so it
                        // cannot happen again at any width.
                        //
                        // Two rows CLEAR of the carriage body: band is the mark row and the body starts at
                        // band + 1, so drawing at band - 2 leaves one empty row between the arm and its roof.
                        int gy = band;
                        int gx = sx + Math.max(0, Math.min((width - 5) / 2, width - 5));
                        if (entry.active()) {
                            graphics.fill(gx + 4, gy, gx + 5, gy + 1, iconColour);
                            graphics.fill(gx + 3, gy + 1, gx + 4, gy + 2, iconColour);
                        } else {
                            graphics.fill(gx, gy, gx + 5, gy + 1, iconColour);
                            graphics.fill(gx, gy + 1, gx + 1, gy + 2, iconColour);
                            graphics.fill(gx + 2, gy + 1, gx + 3, gy + 2, iconColour);
                            graphics.fill(gx + 4, gy + 1, gx + 5, gy + 2, iconColour);
                        }
                    }
                }
            }

            if (row == 0)
                topX = sx + width + gap;
            else
                bottomX = sx + width + gap;
            slot++;
            pos += len;
        }

        // The link between the two rows: off the end of the top row, one row down, back along the gap
        // to above the start of the bottom row. Both hooks are one pixel because the gap between the
        // bands is one pixel. This is the only thing that says the two lines are one train.
        int topSpan = topX - bodyX - gap;
        if (!singleRow) {
            int linkY = topBand + carH + 1;
            int topEnd = bodyX + topSpan;
            graphics.fill(topEnd, linkY - 1, topEnd + 1, linkY + 1, TEXT_DIM);
            graphics.fill(bodyX - 1, linkY, topEnd + 1, linkY + 1, TEXT_DIM);
            graphics.fill(bodyX - 1, linkY + 1, bodyX, linkY + 2, TEXT_DIM);
        }

        // Direction. Forward is left, so the arrow is always a left arrow and always inside the
        // panel. It goes above the strip whenever the strip needs the rows below it: under a single
        // row with merged segments that is where the counts are, and below the two-row layout there
        // is no room at all. A plain single row has nothing to put down there, so the arrow keeps the
        // place the driver already knows it in - under the strip, and lower now that the body is
        // five rows: the band's bottom four rows (9..12) are measured down from the band's last row,
        // which is the panel's outline.
        //
        // Both positions are measured, not estimated: the status line's ink ends a quarter pixel
        // above the upper one, and the lower one ends on the band's second-to-last row because the
        // band's last row is the panel's own one-pixel outline. Moving the strip up to keep a single
        // placement was rejected - it would put the strip's top row against the status line and pull
        // the detail text out of line, for no gain. One four-row shape serves both positions.
        int arrowLen = Math.max(10, Math.min(avail - 2, topSpan + 2));
        int arrowX = x;
        int arrowY = singleRow && !hasMerged ? y + PANTO_BAND_ROWS - 5 : y - 4;
        // The operator's own pixel art, drawn literally: a one-pixel shaft on the middle row with a
        // two-step chevron head on the rows above and below it. Anything smoother reads as a blob at this
        // size, and anything with a filled head reads as an arrowhead pointing the other way.
        //     ....#....
        //     ..#......
        //     #########
        //     ..#......
        //     ....#....
        //   ...#...
        //   ..#....
        //   #######
        //   ..#....
        //   ...#...
        graphics.fill(arrowX + 3, arrowY, arrowX + 4, arrowY + 1, TEXT_DIM);
        graphics.fill(arrowX + 2, arrowY + 1, arrowX + 3, arrowY + 2, TEXT_DIM);
        graphics.fill(arrowX, arrowY + 2, arrowX + arrowLen, arrowY + 3, TEXT_DIM);
        graphics.fill(arrowX + 2, arrowY + 3, arrowX + 3, arrowY + 4, TEXT_DIM);
        graphics.fill(arrowX + 3, arrowY + 4, arrowX + 4, arrowY + 5, TEXT_DIM);
    }

    /**
     * One cab's nose: the body rows stepping out along {@link #CARRIAGE_NOSE_ARC}.
     *
     * <p>One fill per row, because neighbouring rows differ and that difference IS the nose - the
     * profile is data here, not a shape baked into two rectangles as it used to be. {@code edge} is
     * the body's leading (or trailing) column: the leading cab passes its left edge and the trailing
     * cab its right edge, and {@code trailing} says which side the pixels go on, so a train running
     * the other way gets the mirror image for free.
     *
     * <p>Fills only, and nothing is allocated: a static array of ints walked by index. This runs once
     * per cab per frame, five fills at most.
     */
    private static void drawCarriageNose(GuiGraphics graphics, int edge, int bodyY, int colour,
                                         boolean trailing) {
        for (int row = 0; row < CARRIAGE_NOSE_ARC.length; row++) {
            int reach = CARRIAGE_NOSE_ARC[row];
            if (reach <= 0)
                continue;
            if (trailing)
                graphics.fill(edge, bodyY + row, edge + reach, bodyY + row + 1, colour);
            else
                graphics.fill(edge - reach, bodyY + row, edge, bodyY + row + 1, colour);
        }
    }

    /**
     * Where the arms are, one bit per carriage, in display order.
     *
     * <p>A long, not an array: the merge rule and the icon placement both only ask "has this carriage
     * got an arm", and a bit test answers that without allocating. The mask is built once per frame -
     * at most forty shifts - and the carriage index is clamped first with the same clamp the icon
     * placement always used, so a stale index from the server draws somewhere rather than nowhere.
     */
    private static long displayPantographMask(java.util.List<
            com.george_vi.electroenergetics.content.railway_electrification.pantograph
                    .SyncTrainPantographsPacket.Entry> pantographs, int n,
                                              boolean leadingEndIsFirst) {
        long arms = 0L;
        for (int k = 0; k < pantographs.size(); k++) {
            int i = Math.max(0, Math.min(n - 1, pantographs.get(k).carriageIndex()));
            int displayPos = leadingEndIsFirst ? i : n - 1 - i;
            arms |= 1L << displayPos;
        }
        return arms;
    }

    /**
     * Whether at least one arm is out of automatic mode.
     *
     * <p>The "M + state" line exists only for the manual switch, which the original mod did not
     * have: there, every arm simply rose and fell with the wire. The operator asked for that
     * behaviour back, so while every arm is automatic the line is not drawn at all - it otherwise
     * advertises a key that does nothing (the server refuses a manual raise while auto is on), which
     * reads as a broken control. The row itself stays part of the panel, so nothing jumps; only the
     * text is skipped.
     *
     * <p>Allocation-free: an indexed walk over a list that is forty entries long at most.
     */
    private static boolean anyManualPantograph(java.util.List<
            com.george_vi.electroenergetics.content.railway_electrification.pantograph
                    .SyncTrainPantographsPacket.Entry> pantographs) {
        for (int k = 0; k < pantographs.size(); k++)
            if (!pantographs.get(k).autoRaise())
                return true;
        return false;
    }

    /**
     * How many carriages the slot starting at display position {@code pos} stands for.
     *
     * <p>1 for an arm carriage and for either end carriage - those are the cabs and keep their
     * arcs - and the length of the maximal run of pantograph-less carriages in between. A run of
     * two or more is drawn as one merged block; a run of one is a normal carriage.
     */
    private static int consistUnitLength(int pos, int n, long arms) {
        if (pos == 0 || pos == n - 1)
            return 1;
        if ((arms & (1L << pos)) != 0L)
            return 1;
        int run = 1;
        while (pos + run < n - 1 && (arms & (1L << (pos + run))) == 0L)
            run++;
        return run;
    }

    /** Width of a merged segment's count: three pixels per digit, one between them. */
    private static int consistCountWidth(int carriages) {
        return carriages >= 10 ? COUNT_DIGIT_W * 2 + COUNT_DIGIT_GAP : COUNT_DIGIT_W;
    }

    /**
     * Pixels a row of slots needs: a base width each, a gap between them, and whatever a merged slot
     * needs beyond the base width to hold its count.
     *
     * <p>{@code countInside} adds the margin a block needs when its count is drawn inside it rather
     * than under it.
     */
    private static int consistRowWidth(int baseW, int gap, int slotCount, int oneDigit, int twoDigit,
                                       boolean countInside) {
        if (slotCount <= 0)
            return 0;
        int pad = countInside ? COUNT_PAD : 0;
        int extra = oneDigit * Math.max(0, COUNT_DIGIT_W + pad - baseW)
                + twoDigit * Math.max(0, COUNT_DIGIT_W * 2 + COUNT_DIGIT_GAP + pad - baseW);
        return baseW * slotCount + gap * (slotCount - 1) + extra;
    }

    /** A merged segment's count, one or two digits (a run is two to thirty-eight carriages). */
    private static void drawConsistCount(GuiGraphics graphics, int x, int y, int carriages, int colour) {
        if (carriages >= 10) {
            drawCountDigit(graphics, x, y, carriages / 10, colour);
            drawCountDigit(graphics, x + COUNT_DIGIT_W + COUNT_DIGIT_GAP, y, carriages % 10, colour);
        } else {
            drawCountDigit(graphics, x, y, carriages, colour);
        }
    }

    /**
     * One count digit: four rows of three pixels, read out of the static table.
     *
     * <p>Only {@code charAt} and {@code fill}: no string is built and no boxed value is created, so
     * the count costs nothing per frame beyond its pixels.
     */
    private static void drawCountDigit(GuiGraphics graphics, int x, int y, int digit, int colour) {
        String glyph = COUNT_GLYPHS[digit];
        for (int row = 0; row < COUNT_ROWS; row++) {
            int col = 0;
            while (col < COUNT_DIGIT_W) {
                if (glyph.charAt(row * COUNT_DIGIT_W + col) != '#') {
                    col++;
                    continue;
                }
                int start = col;
                while (col < COUNT_DIGIT_W && glyph.charAt(row * COUNT_DIGIT_W + col) == '#')
                    col++;
                graphics.fill(x + start, y + row, x + col, y + row + 1, colour);
            }
        }
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
        // The switch outranks the per-arm state on this line when it is ON, because it changes what
        // the per-arm state MEANS: with it on, a lowered arm will be raised again by the next solve,
        // so "this one is down" is not something the driver can act on. Saying AUTO makes that
        // obvious instead of leaving them pressing raise and watching it revert.
        var chosen = pantographs.get(Math.min(TrainControlInput.selectedPantograph(),
                pantographs.size() - 1));
        if (chosen.autoRaise())
            return tr("electroenergetics.train.panto.autoOn");
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
        if (TrainHudData.autoRaise(train.id))
            return ACCENT;
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
     *
     * <p>The pantograph half appears ONLY when there are two or more arms to choose between. With
     * one arm the arrows do nothing, and advertising a control that cannot act is worse than saying
     * nothing: the driver presses it, nothing happens, and they conclude the cab is broken.
     *
     * <p>Two rows' worth of text does not fit on one 94-pixel line - measured, not assumed: the lever
     * half alone is 73.5 px in Chinese and 93.0 px in English before the pantograph half starts. So
     * when there is something to select, the lever half gives up its engage hint and the row becomes
     * "↑↓ 换挡   ←→ 选弓   M升降". The engage key is named on its own row whenever the train has one
     * arm, and it is the one piece of this line the driver can also discover from the lever moving.
     */
    private static String hintText(int pantographCount) {
        String lever = "\u2191\u2193 " + tr("electroenergetics.train.hint.move");
        if (pantographCount < 2) {
            // No selection to make: keep the original line, including the engage key.
            return lever + "   " + key(TrainControlKeys.ENGAGE)
                    + tr("electroenergetics.train.hint.engage");
        }
        // Selection is available, so it takes the room. The toggle key is named because raising the
        // wrong arm drops the supply, which is the one thing on this panel worth being explicit about.
        return lever
                + "  \u2190\u2192 " + tr("electroenergetics.train.hint.selectPanto")
                + "  " + key(TrainControlKeys.PANTOGRAPH_TOGGLE)
                + tr("electroenergetics.train.hint.raiseLower");
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
