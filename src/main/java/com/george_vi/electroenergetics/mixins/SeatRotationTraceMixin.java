package com.george_vi.electroenergetics.mixins;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Observes Create's own seat rotation, to find out why a player on an ELECTRIC train is not turned
 * with the carriage while a player on a fuel train is.
 *
 * <h2>The question this answers</h2>
 *
 * <p>Create rotates a seated player with the contraption through
 * {@code ContraptionPlayerPassengerRotation.frame()}, which adds the carriage's per-frame yaw change to
 * the player's own yaw. It is gated on {@code rotateWhenSeated} (true in the operator's config) and on
 * {@code player.isPassenger() && vehicle instanceof AbstractContraptionEntity}. The operator reports
 * that a FUEL train turns him correctly and an ELECTRIC one does not, from the same seat, so one of
 * those inputs differs between the two cases.
 *
 * <p>Reading the code has produced four wrong attributions for this class of bug already, so this
 * records the inputs instead: which vehicle, its class, whether it is a CarriageContraptionEntity, the
 * yaw that Create will use, and the per-frame delta.
 *
 * <h2>Cost</h2>
 *
 * <p>One line per 20 frames while seated. Nothing is allocated when the player is not a passenger, and
 * the whole body is inside a single boolean test in that case. Removed once the cause is known.
 */
@Mixin(value = com.simibubi.create.content.contraptions.actors.seat
        .ContraptionPlayerPassengerRotation.class, remap = false)
public class SeatRotationTraceMixin {

    @Unique
    private static final Logger electroEnergetics$LOG = LoggerFactory.getLogger("CEE-SEAT");

    @Unique
    private static int electroEnergetics$n;

    @Inject(method = "frame", at = @At("HEAD"), remap = false)
    private static void electroEnergetics$beforeFrame(CallbackInfo ci) {
        try {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player == null || !player.isPassenger())
                return;
            Entity vehicle = player.getVehicle();
            if (!(vehicle instanceof AbstractContraptionEntity ace))
                return;
            if ((electroEnergetics$n++ % 20) != 0)
                return;

            String kind = vehicle instanceof CarriageContraptionEntity ? "CARRIAGE" : "OTHER";
            electroEnergetics$LOG.info(
                    "SEAT vehicle={} kind={} id={} playerYaw={} viewYaw={} xRot={}",
                    vehicle.getClass().getSimpleName(), kind, vehicle.getId(),
                    String.format("%.2f", player.getYRot()),
                    String.format("%.2f", ace.getViewYRot(1.0f)),
                    String.format("%.2f", ace.getViewXRot(1.0f)));
        } catch (Throwable ignored) {
            // A diagnostic must never be the thing that breaks the client.
        }
    }

    @Inject(method = "frame", at = @At("RETURN"), remap = false)
    private static void electroEnergetics$afterFrame(CallbackInfo ci) {
        try {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player == null || !player.isPassenger())
                return;
            if ((electroEnergetics$n % 20) != 1)
                return;
            electroEnergetics$LOG.info("SEAT after playerYaw={}",
                    String.format("%.2f", player.getYRot()));
        } catch (Throwable ignored) {
        }
    }
}
