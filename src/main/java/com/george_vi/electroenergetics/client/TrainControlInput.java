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
     * Notches to move per press. One click per press is what a detent does, and the
     * whole point of a lever is that you can count the positions you moved.
     */
    private static final int NOTCHES_PER_PRESS = 1;

    private TrainControlInput() {}

    /**
     * Called every client tick. Reads the keys, and sends anything that changed.
     *
     * <p>Nothing is sent while the lever is not moving, so a train standing at a
     * platform with a driver aboard generates no traffic at all.
     */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        boolean driving = canDrive(mc);
        if (!driving) {
            // Drop the edge state so the first press after getting back in the cab is
            // treated as a fresh press even if the key was down the whole time.
            upWasDown = downWasDown = confirmWasDown = emergencyWasDown = false;
            return;
        }

        Train train = drivenTrain();
        TrainHudData.GearState gear = TrainHudData.gear(train.id);
        if (gear == null)
            return;

        boolean up = TrainControlKeys.isDown(TrainControlKeys.LEVER_UP);
        boolean down = TrainControlKeys.isDown(TrainControlKeys.LEVER_DOWN);
        boolean confirm = TrainControlKeys.isDown(TrainControlKeys.CONFIRM);
        boolean emergency = TrainControlKeys.isDown(TrainControlKeys.EMERGENCY);

        // Up notches towards ACCELERATE, down towards REVERSE. The lever is ordered
        // accelerate-first in the enum, so "up" is a lower index.
        if (up && !upWasDown)
            notch(train, gear, -NOTCHES_PER_PRESS);
        if (down && !downWasDown)
            notch(train, gear, +NOTCHES_PER_PRESS);

        if (confirm && !confirmWasDown)
            send(SetTrainGearPacket.confirm(train.id));

        // Emergency is only offered by the server's own rule (reverse, while moving);
        // sending it otherwise is harmless because the handler ignores it, but this
        // avoids the packet entirely.
        if (emergency && !emergencyWasDown && gear.gear() == TrainGear.REVERSE.ordinal())
            send(SetTrainGearPacket.emergency(train.id));

        upWasDown = up;
        downWasDown = down;
        confirmWasDown = confirm;
        emergencyWasDown = emergency;
    }

    /** Move the lever by {@code delta} positions, clamped, and tell the server. */
    private static void notch(Train train, TrainHudData.GearState gear, int delta) {
        TrainGear[] values = TrainGear.values();
        int next = gear.gear() + delta;
        if (next < 0 || next >= values.length)
            return;   // already at an end of the lever; a detent does not wrap
        if (next == gear.gear())
            return;
        send(SetTrainGearPacket.gear(train.id, values[next]));
    }

    private static boolean canDrive(Minecraft mc) {
        if (mc.options.hideGui || mc.player == null || mc.level == null)
            return false;
        if (mc.gameMode == null || mc.gameMode.getPlayerMode() == GameType.SPECTATOR)
            return false;
        if (mc.screen != null)
            return false;   // a menu is open: the arrow keys belong to it
        Train train = drivenTrain();
        return train != null && TrainHudData.leverDriven(train.id);
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
