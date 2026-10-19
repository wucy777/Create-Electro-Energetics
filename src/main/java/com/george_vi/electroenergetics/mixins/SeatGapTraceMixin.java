package com.george_vi.electroenergetics.mixins;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Measures the gap between where the seat says the player should be and where the player actually is,
 * on both sides, every tick.
 *
 * <h2>The measurement this replaces, and why it failed</h2>
 *
 * <p>Watching the move packet showed nothing: across 330 samples the server's position for the rider
 * agreed with the client's packet to within 0.152 Blocks, and the player was a passenger in every one.
 * So no correction packet is being sent, and the throw is not a server disagreement.
 *
 * <p>The throw is still there, and it is still about three ticks of carriage travel. Which means the
 * seat position and the player's position drift apart WITHIN the tick, and only the dismount reveals it -
 * the carry keeps overwriting the player to the seat, so the gap is invisible until the carry stops.
 *
 * <p>So this prints the gap itself, before and after the carry runs:
 *
 * <pre>
 *   SEATGAP side=CLIENT|SERVER seat=(x,y,z) player=(x,y,z) gap=.. anchor=(x,y,z) partial=..
 * </pre>
 *
 * <p>{@code seat} is {@code getPassengerPosition(player, 1)} - the destination the carry will use.
 * {@code gap} is the distance from that destination to where the player currently is. On a healthy tick
 * it is the ordinary carry distance; if it is several multiples of that, the seat is describing a
 * position several ticks away and the carry is being asked to teleport.
 *
 * <h2>Cost</h2>
 *
 * <p>One line per 20 ticks per rider, on both sides. Nothing is computed when there is no player
 * passenger. Removed once the cause is known.
 */
@Mixin(value = AbstractContraptionEntity.class, remap = false)
public class SeatGapTraceMixin {

    @Unique
    private static final Logger electroEnergetics$LOG = LoggerFactory.getLogger("CEE-SEATGAP");

    @Unique
    private static int electroEnergetics$n;

    @Inject(
            method = "positionRider(Lnet/minecraft/world/entity/Entity;"
                    + "Lnet/minecraft/world/entity/Entity$MoveFunction;)V",
            at = @At("HEAD"), remap = false)
    private void electroEnergetics$gap(Entity passenger, Entity.MoveFunction callback, CallbackInfo ci) {
        try {
            if (!(passenger instanceof Player))
                return;
            if (!((Object) this instanceof CarriageContraptionEntity cce))
                return;
            if ((electroEnergetics$n++ % 20) != 0)
                return;

            Vec3 seat = cce.getPassengerPosition(passenger, 1.0f);
            if (seat == null) {
                electroEnergetics$LOG.info("SEATGAP side={} seat=NULL player={} partial=1.0",
                        cce.level().isClientSide() ? "CLIENT" : "SERVER",
                        fmt(passenger.position()));
                return;
            }
            double gap = seat.distanceTo(passenger.position());

            electroEnergetics$LOG.info(
                    "SEATGAP side={} seat={} player={} gap={} anchor={}",
                    cce.level().isClientSide() ? "CLIENT" : "SERVER",
                    fmt(seat), fmt(passenger.position()), String.format("%.2f", gap),
                    fmt(cce.position()));
        } catch (Throwable ignored) {
            // A diagnostic must never be the thing that breaks the game.
        }
    }

    @Unique
    private static String fmt(Vec3 v) {
        return String.format("(%.2f,%.2f,%.2f)", v.x, v.y, v.z);
    }
}
