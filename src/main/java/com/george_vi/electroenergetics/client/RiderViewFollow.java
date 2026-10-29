package com.george_vi.electroenergetics.client;

import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;

/**
 * Keeps a rider's view turning with the carriage.
 *
 * <p>Riding a train that turns should turn the view with it - otherwise the camera stays fixed in world
 * orientation and the carriage swings around the player, which reads as the train rotating under a
 * stationary camera. The rider position code that used to provide this was removed when riding went back to
 * vanilla Create, and the view went with it.
 *
 * <p>Applied as the carriage's own yaw delta each tick, so the player's look direction stays fixed relative
 * to the train while their own mouse movement keeps working on top of it.
 */
public final class RiderViewFollow {

    private RiderViewFollow() {}

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null)
            return;
        if (!(player.getVehicle() instanceof CarriageContraptionEntity carriage))
            return;

        float delta = Mth.wrapDegrees(carriage.getYRot() - carriage.yRotO);
        if (Math.abs(delta) < 1.0e-4f)
            return;

        player.setYRot(player.getYRot() + delta);
        player.setYHeadRot(player.getYHeadRot() + delta);
        player.yRotO += delta;
        player.yHeadRotO += delta;
    }
}