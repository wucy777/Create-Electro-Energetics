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
    /** Header row showing which position the lever is in. */
    static final int HEADER_H = 12;
    /** Footer rows: the key hints, and the status line. */
    static final int FOOTER_H = 22;

    static final int TOTAL_H = HEADER_H + SLOT_H + HANDLE_H + FOOTER_H;

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
