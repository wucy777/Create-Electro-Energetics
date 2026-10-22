package com.george_vi.electroenergetics.mixins;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Ignores a small server position correction for the local player, keeping the position the carry
 * already gave them.
 *
 * <h2>Why easing was not enough</h2>
 *
 * <p>The previous build routed the correction through {@code lerpTo}. The measured log showed it working
 * as designed - {@code FORCED eased dist=14.87} - and the player still reported being thrown. That is the
 * answer: smoothing changes how the 14.87 Blocks are traversed, not the fact that the client is being told
 * to traverse them. The displacement itself is the problem.
 *
 * <h2>Where the displacement comes from</h2>
 *
 * <p>The server forces one absolute-position packet on the tick a passenger is released
 * ({@code ServerEntity.sendChanges}, the {@code wasRiding} branch). Its payload is the SERVER's entity
 * position. The client is drawing the rider from {@code positionRider} against an interpolated carriage,
 * which trails the server by three to four ticks of travel - the same multiple measured on every build.
 *
 * <p>Both sides compute the ride correctly; they simply describe the carriage at different instants. The
 * client is right about where the player appears, the server is right about where the player is, and the
 * packet forces the client to jump between the two.
 *
 * <h2>Why ignoring it is correct here</h2>
 *
 * <p>When the correction is small, the client is not wrong - it is merely behind. The carry has already
 * placed the player on the seat, and the client's own carriage continues forward, so the player stays with
 * the train without any help. Applying the packet moves them to a position the client will reach on its
 * own a few ticks later, and it is exactly that forced move the player perceives as being thrown.
 *
 * <p>When the correction is large, the client genuinely has to be relocated - a dimension change, a
 * command, a teleport out of a stuck position. Those are far outside the bound and are applied normally.
 *
 * <h2>Bound</h2>
 *
 * <p>64 Blocks, matched to the artefact: it is the carriage's per-tick travel times a few ticks, under 30
 * Blocks even at this mod's ceiling. An intentional relocation is structurally elsewhere.
 *
 * <h2>Cost</h2>
 *
 * <p>One distance calculation per position packet for the local player. Such packets arrive on dismounts,
 * dimension changes and periodic resyncs - not per tick.
 */
@Mixin(value = ClientPacketListener.class, remap = false)
public class DismountTeleportMixin {

    @Unique
    private static final Logger electroEnergetics$LOG = LoggerFactory.getLogger("CEE-FORCED");

    @Inject(method = "handleMovePlayer", at = @At("HEAD"), cancellable = true, remap = false)
    private void electroEnergetics$ignoreSmallCorrection(
            net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        try {
            LocalPlayer player = net.minecraft.client.Minecraft.getInstance().player;
            if (player == null)
                return;

            // The packet's target, resolved the same way vanilla does: relatives are added to the
            // current position, absolutes are taken as-is.
            var relatives = packet.getRelativeArguments();
            double x = relatives.contains(net.minecraft.world.entity.RelativeMovement.X)
                    ? player.getX() + packet.getX() : packet.getX();
            double y = relatives.contains(net.minecraft.world.entity.RelativeMovement.Y)
                    ? player.getY() + packet.getY() : packet.getY();
            double z = relatives.contains(net.minecraft.world.entity.RelativeMovement.Z)
                    ? player.getZ() + packet.getZ() : packet.getZ();

            double dx = x - player.getX();
            double dy = y - player.getY();
            double dz = z - player.getZ();
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

            if (dist > 64)
                return;                     // a real relocation: let vanilla apply it

            electroEnergetics$LOG.info("FORCED ignored dist={} kept={}",
                    String.format("%.2f", dist),
                    String.format("(%.2f,%.2f,%.2f)", player.getX(), player.getY(), player.getZ()));
            ci.cancel();
        } catch (Throwable ignored) {
            // If anything is unexpected, let vanilla handle the packet.
        }
    }

}
