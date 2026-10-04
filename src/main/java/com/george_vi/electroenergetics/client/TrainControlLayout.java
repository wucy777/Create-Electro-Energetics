package com.george_vi.electroenergetics.client;

import com.george_vi.electroenergetics.content.railway_electrification.TrainGear;

/**
 * Screen-space layout and hit-testing for the driver's control panel.
 *
 * <p>Kept apart from the renderer because the geometry has to be shared by three
 * things that must agree exactly: the renderer, the click test, and the hover
 * highlight. Scattering the rectangles across the drawing code is how a button
 * ends up a few pixels away from where it can be clicked.
 *
 * <p>Laid out against a nominal width and height and then anchored to the actual
 * window, so the panel sits in the bottom-right corner at any GUI scale. The
 * centre and top of the screen are deliberately left clear - that is where the
 * track ahead is.
 */
final class TrainControlLayout {

    /** Panel width, in GUI pixels. Five gear rows plus the two buttons. */
    static final int PANEL_W = 118;
    static final int ROW_H = 16;
    static final int MARGIN = 4;

    /** Height of the lever column: one row per gear. */
    static final int GEAR_COUNT = TrainGear.values().length;
    static final int LEVER_H = GEAR_COUNT * ROW_H;

    /** The two buttons under the lever. */
    static final int BUTTON_H = 18;

    static final int TOTAL_H = 14 + LEVER_H + 4 + BUTTON_H + BUTTON_H + MARGIN + 10;

    private TrainControlLayout() {}

    static int panelX(int guiWidth) {
        return guiWidth - PANEL_W - MARGIN;
    }

    static int panelY(int guiHeight) {
        return guiHeight - TOTAL_H - MARGIN;
    }

    /** Top edge of gear row {@code i}. */
    static int gearRowY(int panelY, int i) {
        return panelY + 14 + i * ROW_H;
    }

    /** Index of the gear under the cursor, or -1. */
    static int gearAt(int panelX, int panelY, double mouseX, double mouseY) {
        if (mouseX < panelX || mouseX > panelX + PANEL_W)
            return -1;
        for (int i = 0; i < GEAR_COUNT; i++) {
            int y = gearRowY(panelY, i);
            if (mouseY >= y && mouseY < y + ROW_H)
                return i;
        }
        return -1;
    }

    /** Whether the point is inside the panel at all, so clicks can be swallowed. */
    static boolean inside(int panelX, int panelY, double mouseX, double mouseY) {
        return mouseX >= panelX && mouseX <= panelX + PANEL_W
                && mouseY >= panelY && mouseY <= panelY + TOTAL_H;
    }

    // The two buttons. Named zones rather than indices, because they are not a
    // list: the emergency button only exists in reverse, and the two must not be
    // confused if one is hidden.

    static final int NONE = 0;
    static final int CONFIRM = 1;
    static final int EMERGENCY = 2;

    static int confirmY(int panelY) {
        return panelY + 14 + LEVER_H + 4;
    }

    static int emergencyY(int panelY) {
        return confirmY(panelY) + BUTTON_H;
    }

    /**
     * Which button is under the cursor. The emergency button is only testable when
     * {@code emergencyVisible}, so a click where it would have been cannot fire it
     * while the lever is not in reverse.
     */
    static int buttonAt(int panelX, int panelY, double mouseX, double mouseY,
                        boolean emergencyVisible) {
        if (mouseX < panelX || mouseX > panelX + PANEL_W)
            return NONE;
        int cy = confirmY(panelY);
        if (mouseY >= cy && mouseY < cy + BUTTON_H)
            return CONFIRM;
        if (emergencyVisible) {
            int ey = emergencyY(panelY);
            if (mouseY >= ey && mouseY < ey + BUTTON_H)
                return EMERGENCY;
        }
        return NONE;
    }
}
