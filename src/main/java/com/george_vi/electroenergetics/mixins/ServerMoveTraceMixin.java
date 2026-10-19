package com.george_vi.electroenergetics.mixins;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reports what the server believes about a player's position when a move packet arrives, and whether
 * the server considers that player a passenger.
 *
 * <h2>The two facts that decide the throw</h2>
 *
 * <p>{@code handleMovePlayer} branches on {@code player.isPassenger()}. The passenger branch applies the
 * server's own coordinates and ignores the packet; the other branch measures the difference and, past a
 * threshold, sends a correction. Whichever branch runs, the server's coordinates win.
 *
 * <p>So a throw means the server's idea of where the player is differs from the client's. This prints
 * both sides before the branch, so the difference and the branch are visible together:
 *
 * <pre>
 *   MOVE pax=.. serverPos=(..) packetPos=(..) delta=.. accepted=..
 * </pre>
 *
 * <p>{@code delta} is the distance the server is about to move the player, which is the size of the
 * throw if a correction is sent. {@code pax} says whether the passenger branch was taken - if it was and
 * a correction still went out, the two positions differ even though both sides claim to be carrying the
 * player, which is a different problem from a passenger-state mismatch.
 *
 * <h2>Why not the write tracer</h2>
 *
 * <p>The write tracer names the method that wrote the position, and it named
 * {@code ClientPacketListener.handleMovePlayer} - which is correct and already known. What it cannot show
 * is why the server's number differs from the client's, because at that point the client's number has
 * already been replaced. This records both before anything is overwritten.
 *
 * <h2>Cost</h2>
 *
 * <p>One type check when the player is not an electric-train rider; a distance and a line (rate limited
 * to one per 5 packets) when they are. Server side only.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public class ServerMoveTraceMixin {

    @Unique
    private static final Logger electroEnergetics$LOG = LoggerFactory.getLogger("CEE-MOVE");

    @Unique
    private static int electroEnergetics$n;

    @Inject(method = "handleMovePlayer", at = @At("HEAD"), remap = false)
    private void electroEnergetics$beforeMove(
            net.minecraft.network.protocol.game.ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        try {
            ServerPlayer player = ((ServerGamePacketListenerImpl) (Object) this).player;
            if (player == null || !player.isPassenger())
                return;                     // only the riding case is interesting
            if ((electroEnergetics$n++ % 5) != 0)
                return;

            double px = player.getX(), py = player.getY(), pz = player.getZ();
            double dx = packet.getX(player.getX()) - px;
            double dy = packet.getY(player.getY()) - py;
            double dz = packet.getZ(player.getZ()) - pz;
            double delta = Math.sqrt(dx * dx + dy * dy + dz * dz);

            electroEnergetics$LOG.info(
                    "MOVE pax=1 serverPos=({},{},{}) delta={} vehicle={} onGround={}",
                    String.format("%.2f", px), String.format("%.2f", py), String.format("%.2f", pz),
                    String.format("%.3f", delta),
                    player.getVehicle() == null ? "-" : player.getVehicle().getId(),
                    packet.isOnGround());
        } catch (Throwable ignored) {
            // A diagnostic must never be the thing that breaks the server.
        }
    }
}
