package com.george_vi.electroenergetics.client;

import com.george_vi.electroenergetics.CEEPackets;
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
 * Turns a click on the lever panel into a command, and decides whether the click
 * should be swallowed before it reaches the world.
 *
 * <p>Swallowing matters: without it, clicking the panel would also swing at
 * whatever is behind it or place a block. The test is deliberately limited to the
 * panel's own rectangle while a driver is at the controls of an electric train, so
 * ordinary play is untouched everywhere else on screen.
 */
@OnlyIn(Dist.CLIENT)
public final class TrainControlInput {

    private TrainControlInput() {}

    /** Latest lever sample for the train the player drives, or {@code null}. */
    private static TrainHudData.GearState gearState(Train train) {
        return train == null ? null : TrainHudData.gear(train.id);
    }

    private static Train drivenTrain() {
        if (!(ControlsHandler.getContraption() instanceof CarriageContraptionEntity cce))
            return null;
        Carriage carriage = cce.getCarriage();
        return carriage == null ? null : carriage.train;
    }

    /**
     * Handle a click at the current cursor position.
     *
     * @return true when the panel consumed the click and the caller must not pass it
     *         on to the world
     */
    public static boolean onClick(int button) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.player == null || mc.level == null)
            return false;
        if (mc.gameMode == null || mc.gameMode.getPlayerMode() == GameType.SPECTATOR)
            return false;
        if (button != 0)
            return false;   // left click only; right click stays with the world

        Train train = drivenTrain();
        if (train == null)
            return false;
        TrainHudData.GearState gear = gearState(train);
        if (gear == null)
            return false;   // no server sample: the panel is not drawn either
        // The panel is not drawn on a fuel train, so it must not swallow clicks there
        // either; the two use the same test so they cannot disagree.
        if (!TrainHudData.leverDriven(train.id))
            return false;

        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();
        int px = TrainControlLayout.panelX(w);
        int py = TrainControlLayout.panelY(h);

        // Cursor position, converted the same way the renderer does.
        double mouseX = mc.mouseHandler.xpos() * w / mc.getWindow().getGuiScaledWidth();
        double mouseY = mc.mouseHandler.ypos() * h / mc.getWindow().getGuiScaledHeight();

        if (!TrainControlLayout.inside(px, py, mouseX, mouseY))
            return false;

        // Inside the panel it is always consumed, even on empty space, so a click
        // between the rows cannot fall through and hit the world.
        boolean emergencyVisible = gear.gear() == TrainGear.REVERSE.ordinal();
        int buttonHit = TrainControlLayout.buttonAt(px, py, mouseX, mouseY, emergencyVisible);
        if (buttonHit == TrainControlLayout.CONFIRM) {
            send(SetTrainGearPacket.confirm(train.id));
            return true;
        }
        if (buttonHit == TrainControlLayout.EMERGENCY) {
            send(SetTrainGearPacket.emergency(train.id));
            return true;
        }

        int gearHit = TrainControlLayout.gearAt(px, py, mouseX, mouseY);
        if (gearHit >= 0) {
            TrainGear selected = TrainGear.values()[gearHit];
            // No redundant packet when the lever is already there.
            if (gearHit != gear.gear())
                send(SetTrainGearPacket.gear(train.id, selected));
        }
        return true;
    }

    private static void send(SetTrainGearPacket packet) {
        CatnipServices.NETWORK.sendToServer(packet);
    }
}
