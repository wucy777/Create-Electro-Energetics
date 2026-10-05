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
    private static boolean emergencyWasDown;

    /**
     * The last lever position this client SENT, or {@code null} when it is not
     * predicting.
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
            upWasDown = downWasDown = confirmWasDown = emergencyWasDown = false;
            predictedGear = null;
            predictedTicks = 0;
            return;
        }

        Train train = drivenTrain();
        TrainHudData.GearState gear = TrainHudData.gear(train.id);
        if (gear == null)
            return;

        // Reconcile the local prediction with what the server reports.
        int serverGear = gear.gear();
        if (predictedGear != null) {
            if (predictedGear.ordinal() == serverGear) {
                predictedGear = null;      // confirmed; stop guessing
                predictedTicks = 0;
            } else if (++predictedTicks > PREDICTION_TIMEOUT) {
                // The command was refused or overtaken (a vigilance fail-safe drops
                // the lever to the brake, for instance). Believe the server rather
                // than fighting it for the rest of the journey.
                predictedGear = null;
                predictedTicks = 0;
            }
        }

        // What the next press moves from: the driver's own last command if there is
        // one, otherwise the server's value.
        int current = predictedGear != null ? predictedGear.ordinal() : serverGear;

        boolean up = TrainControlKeys.isDown(TrainControlKeys.LEVER_UP);
        boolean down = TrainControlKeys.isDown(TrainControlKeys.LEVER_DOWN);
        boolean confirm = TrainControlKeys.isDown(TrainControlKeys.CONFIRM);
        boolean emergency = TrainControlKeys.isDown(TrainControlKeys.EMERGENCY);

        // Up notches towards ACCELERATE, down towards REVERSE. The lever is ordered
        // accelerate-first in the enum, so "up" is a lower index.
        if (up && !upWasDown)
            current = notch(train, current, -NOTCHES_PER_PRESS);
        if (down && !downWasDown)
            current = notch(train, current, +NOTCHES_PER_PRESS);

        if (confirm && !confirmWasDown)
            send(SetTrainGearPacket.confirm(train.id));

        // Emergency is only offered by the server's own rule (reverse, while moving);
        // sending it otherwise is harmless because the handler ignores it, but this
        // avoids the packet entirely.
        if (emergency && !emergencyWasDown && current == TrainGear.REVERSE.ordinal())
            send(SetTrainGearPacket.emergency(train.id));

        upWasDown = up;
        downWasDown = down;
        confirmWasDown = confirm;
        emergencyWasDown = emergency;
    }

    /**
     * Move the lever by {@code delta} positions from {@code from}, clamped, and tell
     * the server. Returns the position now selected, which becomes the prediction.
     */
    private static int notch(Train train, int from, int delta) {
        TrainGear[] values = TrainGear.values();
        int next = from + delta;
        if (next < 0 || next >= values.length)
            return from;   // already at an end of the lever; a detent does not wrap
        if (next == from)
            return from;
        send(SetTrainGearPacket.gear(train.id, values[next]));
        predictedGear = values[next];
        predictedTicks = 0;
        return next;
    }

    /**
     * The lever position to display, which is the driver's own last command while it
     * is still waiting for the server to confirm it.
     *
     * <p>The renderer uses this so the handle moves on the key press rather than a few
     * ticks later. Without it the lever would feel laggy and, when several presses
     * were queued, would appear to jump: the handle stayed still through the presses
     * and then travelled the whole distance at once when the packets landed.
     *
     * @param serverGear what the server last reported
     * @return the position to draw
     */
    public static int displayGear(int serverGear) {
        return predictedGear != null ? predictedGear.ordinal() : serverGear;
    }

    /** Move the lever by {@code delta} positions, clamped, and tell the server. */
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
