package com.george_vi.electroenergetics.client;

import com.george_vi.electroenergetics.content.railway_electrification.TrainGear;

/**
 * Geometry of the lever panel: one vertical slot with a handle that slides in it.
 *
 * <p>Shared by the renderer and the key handling so the panel that is drawn and the
 * panel that responds cannot drift apart. The layout is a single scale with five
 * detents, not five separate buttons - the handle sits at one position and moves, the
 * way a real driving handle does.
 *
 * <p>Anchored bottom-right, and sized so the middle and top of the screen stay clear:
 * that is where the track ahead is.
 */
final class TrainControlLayout {

    /** Panel width in GUI pixels. Wide enough for the label and the handle. */
    static final int PANEL_W = 104;

    /** The vertical travel of the handle: one detent per lever position. */
    static final int DETENT_H = 18;
    static final int GEAR_COUNT = TrainGear.values().length;
    static final int SLOT_H = (GEAR_COUNT - 1) * DETENT_H;

    /** Handle dimensions. */
    static final int HANDLE_W = 22;
    static final int HANDLE_H = 11;

    static final int MARGIN = 4;

    /**
     * Header row: the panel's TITLE, not a lever position.
     *
     * <p>It used to show the name of the position the handle was in, which is the same
     * text as one of the five detent labels below it - so the header read as a sixth
     * position sitting immediately above the first. Reported exactly that way: "the
     * current gear at the top is so close to the first position that I thought it was
     * another position". The handle already marks the position on the scale, so the
     * title says what the panel IS and the scale says where the lever is.
     */
    static final int HEADER_H = 14;

    /** Rule between the title and the scale, so the two cannot be read as one list. */
    static final int DIVIDER_H = 1;

    /**
     * Footer rows: two lines of key hints and the status line.
     *
     * <p>Two lines rather than one because the single line was wider than the panel and
     * ran off the edge of the screen - the reported "the up/down hint at the bottom
     * right is cut off and I cannot see it". The cause was printing the arrow keys'
     * translated names ("up arrow key", "down arrow key"), which are long in every
     * language; they are drawn as arrow glyphs now, which is both shorter and clearer.
     */
    static final int FOOTER_H = 34;

    /**
     * Where the footer's three rows sit, relative to the footer's top.
     *
     * <p>Measured, not guessed. The text is drawn at {@code SMALL} (0.75), and Minecraft's font is
     * nine pixels tall, so a row occupies 6.75 px. The rows are 11 px apart, which leaves 4.25 px of
     * gap between them - enough to read as separate lines rather than one block.
     *
     * <p>The footer used to be 30 px with a 4 px top inset, and that put the last row's glyph 2.75 px
     * BELOW the panel's bottom edge: not a crash, and not easily noticed against a dark background,
     * which is why it survived. The bottom of the last glyph row was simply drawn outside the card.
     *
     * <p>Fitting it by moving the rows up does not work. The numbers leave no room: three rows need
     * 22 + 6.75 = 28.75 px from the first row's top, so a 30 px footer only has 1.25 px for the inset
     * - and an inset of 1 leaves 0.25 px to spare, which is nothing once a language's font metrics
     * differ slightly. An inset of 2 overflows by 0.75 px, which is what the geometry check reports.
     *
     * <p>So the footer is made 4 px taller instead, which keeps a 2 px inset at the top, 2.25 px
     * below the last row, and the row spacing unchanged. TOTAL_H grows by the same 4 px: the panel is
     * anchored to the bottom-right corner already, so it extends upward into empty space rather than
     * moving anything else.
     */
    static final int FOOTER_INSET_TOP = 2;

    /** Y offsets of the three footer rows, from the footer's top. */
    static final int FOOTER_ROW_HINTS = 0;
    static final int FOOTER_ROW_STATUS = 11;
    static final int FOOTER_ROW_PANTO = 22;

    /** Y of a footer row, given the panel's top. */
    static int footerRowY(int panelY, int rowOffset) {
        return panelY + TOTAL_H - FOOTER_H + FOOTER_INSET_TOP + rowOffset;
    }

    static final int TOTAL_H = HEADER_H + DIVIDER_H + SLOT_H + HANDLE_H + FOOTER_H;

    private TrainControlLayout() {}

    static int panelX(int guiWidth) {
        return guiWidth - PANEL_W - MARGIN;
    }

    static int panelY(int guiHeight) {
        return guiHeight - TOTAL_H - MARGIN;
    }

    /** Centre of the slot, where the handle's track is. */
    static int slotCenterX(int panelX) {
        return panelX + PANEL_W - 22;
    }

    /** Top of the slot: the ACCELERATE end. */
    static int slotTopY(int panelY) {
        return panelY + HEADER_H + DIVIDER_H;
    }

    /** Y of the rule that separates the title from the scale. */
    static int dividerY(int panelY) {
        return panelY + HEADER_H;
    }

    /**
     * Centre Y of the handle for a given lever position.
     *
     * <p>Index 0 (ACCELERATE) is at the top and the last index (REVERSE) at the
     * bottom, which is the order the enum is declared in and the order the driver
     * sees: pushing the lever away accelerates, pulling it back reverses.
     */
    static int handleCenterY(int panelY, int gearIndex) {
        int clamped = Math.max(0, Math.min(GEAR_COUNT - 1, gearIndex));
        return slotTopY(panelY) + clamped * DETENT_H + HANDLE_H / 2;
    }

    /** Y of the label for a detent, aligned with that detent on the slot. */
    static int detentLabelY(int panelY, int gearIndex) {
        return handleCenterY(panelY, gearIndex) - 4;
    }
}
