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
    private static boolean pantoNextWasDown;
    private static boolean pantoToggleWasDown;

    /**
     * Which pantograph the pantograph keys act on, as an index into the server's list.
     *
     * <p>Reset to the first pantograph whenever the driver gets back in the cab, so the selection
     * is never inherited from a previous session and cannot point at a pantograph that no longer
     * exists.
     */
    private static int selectedPantograph;

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
     * Whether the player is at the controls of an electric train AT ALL, in either control scheme.
     *
     * <p>Deliberately wider than {@link #isDrivingElectricTrain()}, and the difference is what stops
     * the feature deadlocking.
     *
     * <p>The pantographs are worked from this test rather than from the lever's, because a driver
     * who drops every pantograph is handed to Create - at which point the lever keys must be dead
     * but the pantograph keys must very much still work. Gating them on the lever would mean that
     * the moment a train was handed over, the only control that could bring it back stopped
     * responding: the driver could lower a pantograph and then never raise one again.
     *
     * <p>The train is known to be an electric one on the client by the pantograph list having been
     * sent, which the server only ever sends to the driver of a consist that has them.
     */
    public static boolean isAtElectricTrainControls() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options == null || mc.options.hideGui || mc.player == null || mc.level == null)
            return false;
        if (mc.gameMode == null || mc.gameMode.getPlayerMode() == GameType.SPECTATOR)
            return false;
        if (mc.screen != null)
            return false;
        Train train = drivenTrain();
        if (train == null)
            return false;
        return TrainHudData.leverDriven(train.id)
                || !TrainHudData.pantographs(train.id).isEmpty();
    }

    /**
     * Called every client tick. Reads the keys, and sends anything that changed.
     *
     * <p>Nothing is sent while the lever is not moving, so a train standing at a
     * platform with a driver aboard generates no traffic at all.
     */
    public static void tick() {
        // The pantographs are serviced first and on their own test, so that they keep working after
        // the train has been handed to Create. That is not a detail: raising a pantograph is the only
        // way back out of CREATE, so if this were gated on the lever the handover would be a
        // one-way trip.
        tickPantographs();

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

        // Nothing is accepted while the controls are locked. Checked once, here, rather than inside
        // each key's handler: the lockout covers the entire panel, and a per-key test is a list
        // that would have to be kept complete every time a control is added.
        //
        // The edge states below are still updated, so a key held down through the lockout does not
        // fire the moment it lifts.
        if (TrainHudData.controlsLocked(train.id)) {
            upWasDown = up;
            downWasDown = down;
            confirmWasDown = confirm;
            engageWasDown = engage;
            emergencyWasDown = emergency;
            pantoNextWasDown = TrainControlKeys.isDown(TrainControlKeys.PANTOGRAPH_NEXT);
            pantoToggleWasDown = TrainControlKeys.isDown(TrainControlKeys.PANTOGRAPH_TOGGLE);
            return;
        }

        // Up notches towards ACCELERATE, down towards REVERSE. The lever is ordered
        // accelerate-first in the enum, so "up" is a lower index.
        //
        // Moving the handle sends NOTHING. This is the whole difference from the
        // previous version: the handle is the driver's intention, and the train only
        // learns about it when the handle is engaged. It also means a stray arrow key
        // - and the arrow keys are easy to catch while looking around - cannot change
        // what the train is doing.
        if (up && !upWasDown)
            handle = moveHandle(handle, -NOTCHES_PER_PRESS, engagedPosition(serverGear), train);
        if (down && !downWasDown)
            handle = moveHandle(handle, +NOTCHES_PER_PRESS, engagedPosition(serverGear), train);

        // Engage: commit the handle's position to the train.
        if (engage && !engageWasDown && handle != engagedPosition(serverGear))
            engage(train, handle);

        if (confirm && !confirmWasDown)
            send(SetTrainGearPacket.confirm(train.id));

        // Emergency is only offered by the server's own rule (reverse, while moving);
        // sending it otherwise is harmless because the handler ignores it, but this
        // avoids the packet entirely.
        if (emergency && !emergencyWasDown && handle == TrainGear.REVERSE.ordinal()
                && TrainHudData.emergencyBrakeAvailable(train.id))
            send(SetTrainGearPacket.emergency(train.id));

        upWasDown = up;
        downWasDown = down;
        confirmWasDown = confirm;
        engageWasDown = engage;
        emergencyWasDown = emergency;
    }

    /**
     * The pantograph keys, read on their own test so they survive the handover to Create.
     *
     * <p>Called from {@link #tick()} BEFORE the lever is considered, and deliberately not inside the
     * lever's branch. A driver who drops every pantograph is handed to Create, and raising one is
     * the only way back - so these keys must keep working precisely when the lever keys have stopped.
     *
     * <p>Both keys are edge-triggered, like the lever's: the select key steps once per press, and
     * the toggle acts once, rather than firing every tick they are held.
     */
    private static void tickPantographs() {
        boolean pantoNext = TrainControlKeys.isDown(TrainControlKeys.PANTOGRAPH_NEXT);
        boolean pantoToggle = TrainControlKeys.isDown(TrainControlKeys.PANTOGRAPH_TOGGLE);

        // Not at the controls of an electric consist: drop the edge state, so a key held while
        // walking up to the train does not fire the moment the panel appears.
        if (!isAtElectricTrainControls()) {
            pantoNextWasDown = pantoToggleWasDown = false;
            selectedPantograph = 0;
            return;
        }

        Train train = drivenTrain();
        if (train == null) {
            pantoNextWasDown = pantoToggleWasDown = false;
            return;
        }

        // Locked by the emergency brake: the whole panel is inert, so nothing is sent. The edge
        // state is still updated, so a key held down through the lockout does not fire when it lifts.
        boolean locked = TrainHudData.controlsLocked(train.id);

        java.util.List<com.george_vi.electroenergetics.content.railway_electrification.pantograph
                .SyncTrainPantographsPacket.Entry> pantographs = TrainHudData.pantographs(train.id);

        if (!locked && !pantographs.isEmpty()) {
            // Clamped rather than wrapped blindly: the server's list can shrink when a carriage is
            // removed, and a selection left pointing past the end would make the toggle key do
            // nothing at all.
            if (selectedPantograph >= pantographs.size())
                selectedPantograph = 0;

            if (pantoNext && !pantoNextWasDown)
                selectedPantograph = (selectedPantograph + 1) % pantographs.size();

            if (pantoToggle && !pantoToggleWasDown) {
                var target = pantographs.get(selectedPantograph);
                // Toggle means "the other of how it currently stands", so one key both raises and
                // lowers. The server refuses a raise it cannot justify and reports the outcome by
                // syncing back, so no local state is invented here - a refused raise simply does
                // not happen on screen either.
                CatnipServices.NETWORK.sendToServer(
                        new com.george_vi.electroenergetics.content.railway_electrification
                                .pantograph.SetPantographPacket(train.id, target.carriageIndex(),
                                target.localPos(), !target.active()));
            }
        }

        pantoNextWasDown = pantoNext;
        pantoToggleWasDown = pantoToggle;
    }

    /** Which pantograph the pantograph keys act on, for the panel to highlight. */
    public static int selectedPantograph() {
        return selectedPantograph;
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
    private static int moveHandle(int from, int delta, int engaged, Train train) {
        TrainGear[] values = TrainGear.values();
        int next = from + delta;
        // Step past positions that cannot be selected rather than stopping on them. A dead detent
        // that silently does nothing reads as a broken key; skipping means the handle always lands
        // somewhere the train can actually obey.
        while (next >= 0 && next < values.length && !available(train, next))
            next += delta;
        if (next < 0 || next >= values.length)
            return from;   // no selectable position that way; a detent does not wrap
        selectedGear = (next == engaged) ? null : values[next];
        return next;
    }

    /**
     * Whether a lever position may be selected right now.
     *
     * <p>Asks the same question the server asks, from the same synced supply state, so the handle
     * cannot be moved to a position the server would then refuse - which would show the driver
     * their handle moving and then snapping back on the next sync.
     *
     * <p>The three positions that command traction need power. The brake and the power-off position
     * do not, and must not be gated: the friction brake is what a driver without supply actually
     * has, and the emergency brake is a DRIVEN-MOTOR brake here, so it is unavailable with no
     * power for the same reason traction is.
     */
    private static boolean available(Train train, int gearIndex) {
        TrainGear[] values = TrainGear.values();
        if (gearIndex < 0 || gearIndex >= values.length)
            return false;
        // While the controls are locked nothing on the panel may be moved, so no position is
        // available - not even the brake. The server refuses every command in this state, and
        // offering one here would move the handle and then snap it back on the next sync.
        if (TrainHudData.controlsLocked(train.id))
            return false;
        return TrainHudData.gearAvailable(train.id, values[gearIndex]);
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
