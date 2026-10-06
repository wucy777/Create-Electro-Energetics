package com.george_vi.electroenergetics.client;

import com.george_vi.electroenergetics.content.railway_electrification.SetTrainGearPacket;
import com.george_vi.electroenergetics.content.railway_electrification.TrainGear;
import com.george_vi.electroenergetics.content.railway_electrification.TrainHudData;
import com.simibubi.create.content.contraptions.actors.trainControls.ControlsHandler;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import net.createmod.catnip.platform.CatnipServices;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.GameType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Turns key presses into lever commands, once per press.
 *
 * <p>Reads the raw keys in {@link TrainControlKeys} rather than KeyMappings, so the
 * cab works in a pack where every convenient key is already bound by another mod.
 * The keys are only touched while a player drives an electric train.
 *
 * <p>Edge-triggered, not level-triggered: holding an arrow must notch the lever once,
 * the way a handle detent clicks, not run up and down the whole range while held. The
 * previous state of each key is kept here so a press can be told from a hold.
 */
@OnlyIn(Dist.CLIENT)
public final class TrainControlInput {

    // Previous frame's key states, for edge detection.
    private static boolean upWasDown;
    private static boolean downWasDown;
    private static boolean confirmWasDown;
    private static boolean engageWasDown;
    private static boolean emergencyWasDown;

    /**
     * The position the driver has moved the handle to but NOT yet engaged.
     *
     * <p>The lever is two-step, like a real one: the arrow keys move the handle over
     * the detents, and the train does nothing until the handle is engaged. So this is
     * not a prediction of the server's state - it is the handle's own position, which
     * the server knows nothing about until {@link #ENGAGE} is pressed.
     *
     * <p>{@code null} means the handle is resting on the engaged position, i.e. there
     * is nothing pending to show.
     */
    private static TrainGear selectedGear;

    /**
     * The engaged lever position as last commanded by this client, or {@code null}
     * when it is not waiting on a command.
     *
     * <p>This is the fix for the lever appearing to jump positions. The next position
     * used to be computed from {@link TrainHudData}, which is the server's last synced
     * value and therefore up to a few ticks stale. Pressing the key twice inside that
     * window computed the same "next" value both times, so the second press did
     * nothing - the lever moved once for two presses. Worse, the two packets both
     * carried the same target, so the lever could land somewhere the driver never
     * asked for and then visibly snap when the server's state caught up.
     *
     * <p>Held here instead, and reset whenever the server's value agrees with it (or
     * disagrees for long enough to mean the command was rejected), so the driver's
     * presses accumulate locally and the display follows their own input immediately.
     */
    private static TrainGear predictedGear;
    private static int predictedTicks;   // how long the guess has gone unconfirmed

    /**
     * Notches to move per press. One click per press is what a detent does, and the
     * whole point of a lever is that you can count the positions you moved.
     */
    private static final int NOTCHES_PER_PRESS = 1;

    /** Ticks a prediction may stand unconfirmed before the server is believed. */
    private static final int PREDICTION_TIMEOUT = 40;

    private TrainControlInput() {}

    /**
     * Whether the player is currently driving an electric train, which is the only
     * situation in which the cab takes its keys away from everything else.
     *
     * <p>Public and separate from {@link #tick()} because the key suppression runs on
     * the input thread's event, not on the client tick, and needs the same answer.
     */
    public static boolean isDrivingElectricTrain() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options == null || mc.options.hideGui || mc.player == null || mc.level == null)
            return false;
        if (mc.gameMode == null || mc.gameMode.getPlayerMode() == GameType.SPECTATOR)
            return false;
        if (mc.screen != null)
            return false;   // a menu is open: its keys are not ours to take
        Train train = drivenTrain();
        return train != null && TrainHudData.leverDriven(train.id);
    }

    /**
     * Called every client tick. Reads the keys, and sends anything that changed.
     *
     * <p>Nothing is sent while the lever is not moving, so a train standing at a
     * platform with a driver aboard generates no traffic at all.
     */
    public static void tick() {
        if (!isDrivingElectricTrain()) {
            // Drop the edge state so the first press after getting back in the cab is
            // treated as a fresh press even if the key was down the whole time.
            upWasDown = downWasDown = confirmWasDown = engageWasDown = emergencyWasDown = false;
            selectedGear = null;
            predictedGear = null;
            predictedTicks = 0;
            return;
        }

        Train train = drivenTrain();
        TrainHudData.GearState gear = TrainHudData.gear(train.id);
        if (gear == null)
            return;

        // Reconcile the engaged position with what the server reports.
        int serverGear = gear.gear();
        if (predictedGear != null) {
            if (predictedGear.ordinal() == serverGear) {
                predictedGear = null;      // confirmed; stop guessing
                predictedTicks = 0;
            } else if (++predictedTicks > PREDICTION_TIMEOUT) {
                // The command was refused or overtaken (a vigilance fail-safe drops
                // the lever to the brake, for instance). Believe the server rather
                // than fighting it for the rest of the journey. A pending selection
                // is dropped with it, so the handle cannot be left showing a position
                // the train has since been forced away from - the driver selects
                // again from wherever the lever actually is now.
                predictedGear = null;
                predictedTicks = 0;
                selectedGear = null;
            }
        }

        // Where the handle currently rests: a pending selection if the driver has
        // moved it, otherwise the engaged position.
        int handle = selectedGear != null ? selectedGear.ordinal()
                : (predictedGear != null ? predictedGear.ordinal() : serverGear);

        boolean up = TrainControlKeys.isDown(TrainControlKeys.LEVER_UP);
        boolean down = TrainControlKeys.isDown(TrainControlKeys.LEVER_DOWN);
        boolean confirm = TrainControlKeys.isDown(TrainControlKeys.CONFIRM);
        boolean engage = TrainControlKeys.isDown(TrainControlKeys.ENGAGE);
        boolean emergency = TrainControlKeys.isDown(TrainControlKeys.EMERGENCY);

        // Up notches towards ACCELERATE, down towards REVERSE. The lever is ordered
        // accelerate-first in the enum, so "up" is a lower index.
        //
        // Moving the handle sends NOTHING. This is the whole difference from the
        // previous version: the handle is the driver's intention, and the train only
        // learns about it when the handle is engaged. It also means a stray arrow key
        // - and the arrow keys are easy to catch while looking around - cannot change
        // what the train is doing.
        if (up && !upWasDown)
            handle = moveHandle(handle, -NOTCHES_PER_PRESS, engagedPosition(serverGear));
        if (down && !downWasDown)
            handle = moveHandle(handle, +NOTCHES_PER_PRESS, engagedPosition(serverGear));

        // Engage: commit the handle's position to the train.
        if (engage && !engageWasDown && handle != engagedPosition(serverGear))
            engage(train, handle);

        if (confirm && !confirmWasDown)
            send(SetTrainGearPacket.confirm(train.id));

        // Emergency is only offered by the server's own rule (reverse, while moving);
        // sending it otherwise is harmless because the handler ignores it, but this
        // avoids the packet entirely.
        if (emergency && !emergencyWasDown && handle == TrainGear.REVERSE.ordinal())
            send(SetTrainGearPacket.emergency(train.id));

        upWasDown = up;
        downWasDown = down;
        confirmWasDown = confirm;
        engageWasDown = engage;
        emergencyWasDown = emergency;
    }

    /** The position the train is believed to be actually in. */
    private static int engagedPosition(int serverGear) {
        return predictedGear != null ? predictedGear.ordinal() : serverGear;
    }

    /**
     * Move the handle by {@code delta} detents, clamped, recording it as a pending
     * selection. Deliberately does not talk to the server.
     *
     * <p>If the handle comes back to rest on the engaged position the selection is
     * cleared, so there is no pending state to show and the display stops hinting at
     * anything.
     *
     * <p>That last part was DOCUMENTED here but not implemented, which was a real bug
     * with a visible symptom: nudging the handle up and back down left {@code selectedGear}
     * set to the engaged position. Nothing was pending - the handle was exactly where the
     * train was - yet every test for "is a selection pending" only asked whether the field
     * was non-null, so the panel drew its pending asterisk and its hollow handle forever.
     * Reported as "after engaging a gear, pressing up/down and returning to the same gear
     * leaves a yellow asterisk on it permanently".
     *
     * <p>The comparison has to be against the ENGAGED position rather than the handle's
     * previous position: from two detents away, arriving back at the engaged gear must
     * clear the selection in one press, and it passes through no intermediate state that
     * could be mistaken for "still pending".
     */
    private static int moveHandle(int from, int delta, int engaged) {
        TrainGear[] values = TrainGear.values();
        int next = from + delta;
        if (next < 0 || next >= values.length)
            return from;   // already at an end of the lever; a detent does not wrap
        selectedGear = (next == engaged) ? null : values[next];
        return next;
    }

    /** Commit the handle's position: this is what moves the train. */
    private static void engage(Train train, int handle) {
        TrainGear[] values = TrainGear.values();
        if (handle < 0 || handle >= values.length)
            return;
        send(SetTrainGearPacket.gear(train.id, values[handle]));
        predictedGear = values[handle];
        predictedTicks = 0;
        // The handle now rests on what was just engaged, so there is nothing pending.
        selectedGear = null;
    }

    /**
     * The position the HANDLE is drawn at.
     *
     * <p>This is the driver's own pending selection if they have moved the handle,
     * otherwise the engaged position - with the local command preferred over the
     * server's value for the few ticks it takes to be confirmed. Without the last
     * part the handle would lag the engage key and, when several commands were
     * outstanding, would appear to jump.
     *
     * @param serverGear what the server last reported
     * @return the position to draw the handle at
     */
    public static int displayGear(int serverGear) {
        if (selectedGear != null)
            return selectedGear.ordinal();
        return engagedPosition(serverGear);
    }

    /**
     * The position the train is actually in, for the readout that reports state rather
     * than the handle.
     *
     * <p>Kept separate from {@link #displayGear} so the two questions - "where is the
     * handle" and "what is the train doing" - can be answered differently, which is the
     * whole point of a two-step lever: they are genuinely different while a selection
     * is pending.
     */
    public static int engagedGear(int serverGear) {
        return engagedPosition(serverGear);
    }

    /** Whether a selection is pending, i.e. the handle is off the engaged position. */
    public static boolean hasPendingSelection() {
        return selectedGear != null;
    }

    private static Train drivenTrain() {
        if (!(ControlsHandler.getContraption() instanceof CarriageContraptionEntity cce))
            return null;
        Carriage carriage = cce.getCarriage();
        return carriage == null ? null : carriage.train;
    }

    private static void send(SetTrainGearPacket packet) {
        CatnipServices.NETWORK.sendToServer(packet);
    }
}
