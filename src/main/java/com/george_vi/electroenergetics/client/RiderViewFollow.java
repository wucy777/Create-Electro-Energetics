package com.george_vi.electroenergetics.client;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;

/**
 * Keeps a rider's view turning with the carriage.
 *
 * <p>Riding through a curve should turn the view with the train. Without this the camera stays fixed in world
 * orientation and the carriage swings around it, which reads as the train rotating under a stationary camera.
 * The rider position code that used to provide this was removed when riding went back to vanilla Create, and
 * the view went with it.
 *
 * <h2>Which angle</h2>
 *
 * <p>Not the entity's yaw: a carriage entity keeps its own yaw at zero and carries the orientation in its
 * contraption rotation state, which is what the renderer turns the carriage by. The delta of that state is
 * therefore the motion the eye should follow.
 *
 * <p>The previous value is tracked here rather than read from the entity, because the state exposes the
 * current rotation only. It is reset whenever the player changes carriage, so a new ride never inherits the
 * last one's angle.
 */
public final class RiderViewFollow {

    private RiderViewFollow() {}

    private static int followedEntity = -1;
    private static float lastYaw = Float.NaN;

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) {
            reset();
            return;
        }
        if (!(player.getVehicle() instanceof CarriageContraptionEntity carriage)) {
            reset();
            return;
        }

        AbstractContraptionEntity.ContraptionRotationState state = carriage.getRotationState();
        float yaw = state == null ? 0f : state.yRotation + state.getYawOffset();

        if (carriage.getId() != followedEntity || Float.isNaN(lastYaw)) {
            followedEntity = carriage.getId();
            lastYaw = yaw;
            return;
        }

        float delta = Mth.wrapDegrees(yaw - lastYaw);
        lastYaw = yaw;
        if (Math.abs(delta) < 1.0e-4f)
            return;

        player.setYRot(player.getYRot() + delta);
        player.setYHeadRot(player.getYHeadRot() + delta);
        player.yRotO += delta;
        player.yHeadRotO += delta;
    }

    private static void reset() {
        followedEntity = -1;
        lastYaw = Float.NaN;
    }
}