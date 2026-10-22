package com.george_vi.electroenergetics.mixins;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Turns the forced post-dismount teleport into a short interpolation instead of a snap.
 *
 * <h2>The chain, established over six builds</h2>
 *
 * <p>The dismount position is written twice into NBT and read twice. Four builds tried to change it there;
 * the consumer probe showed why none could work - it logged
 * {@code seat=(336.50,-443.19) rider=(336.50,-443.19)}, identical, so the player had ALREADY arrived and
 * the tag is downstream of the move.
 *
 * <p>A stack trace from the single method every server teleport funnels through named the sender:
 *
 * <pre>
 *   TELEPORT target=(336.50,-57.98,-463.93) pax=false vehicle=-
 *   caller = ... teleport &lt;- ServerEntity:95(lambda$sendChanges$0)
 * </pre>
 *
 * <p>{@code ServerEntity.sendChanges} contains:
 *
 * <pre>
 *   if (teleportDelay &gt; 400 || wasRiding || wasOnGround != entity.onGround())
 *       packet = new ClientboundTeleportEntityPacket(entity);   // absolute, applied with absMoveTo
 * </pre>
 *
 * <p>{@code wasRiding} clears on the first tick without a passenger, so leaving a seat forces exactly one
 * absolute-position packet. It carries the SERVER's entity position; the client draws the rider from
 * {@code positionRider} against an interpolated carriage, and at speed the two differ by several ticks of
 * travel. Measured: 15.33 Blocks at 3.91 Blocks/tick = 3.92 ticks, matching every earlier build.
 *
 * <h2>Why interpolation rather than suppression</h2>
 *
 * <p>Suppressing the packet would desynchronise the client whenever the server genuinely needs to correct
 * it. What is wrong is not that the correction arrives, but that it is applied instantly. Vanilla already
 * has the right mechanism for a small correction: {@code Entity.lerpTo}, which the client uses for
 * ordinary entity movement and which eases the entity across the gap over a few ticks.
 *
 * <p>So: for the local player, when the correction is close enough to be this artefact, apply it through
 * {@code lerpTo} instead of {@code absMoveTo}. The player slides the remaining distance over a fraction of
 * a second instead of being thrown across it.
 *
 * <h2>How the harmful case is told apart</h2>
 *
 * <p>By distance only, with a bound well above the artefact and well below any intentional relocation. The
 * artefact is the carriage's per-tick travel times a few ticks - under 30 Blocks even at this mod's
 * maximum. A dimension change or a command teleport moves the player to a structurally different place.
 * The threshold used is 64 Blocks, which cannot catch a command teleport and cannot miss a dismount.
 *
 * <h2>Cost</h2>
 *
 * <p>Only on a teleport packet for the local player - a handful of times per session. One distance
 * calculation.
 */
@Mixin(value = ClientPacketListener.class, remap = false)
public class DismountTeleportMixin {

    @Unique
    private static final Logger electroEnergetics$LOG = LoggerFactory.getLogger("CEE-FORCED");

    @Inject(method = "handleTeleportEntity", at = @At("HEAD"), cancellable = true, remap = false)
    private void electroEnergetics$easeDismount(ClientboundTeleportEntityPacket packet, CallbackInfo ci) {
        try {
            ClientPacketListener self = (ClientPacketListener) (Object) this;
            var level = self.getLevel();
            if (level == null)
                return;

            Entity target = level.getEntity(packet.getId());
            if (!(target instanceof LocalPlayer player))
                return;                     // only the local player is ever "thrown" in the player's view

            double dx = packet.getX() - player.getX();
            double dy = packet.getY() - player.getY();
            double dz = packet.getZ() - player.getZ();
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

            // Beyond this it is a real relocation; let vanilla apply it instantly.
            if (dist > 64)
                return;

            // Ordinary entity interpolation, over the same number of steps vanilla uses for a moved
            // entity. The player keeps control the whole time; this only smooths the position change.
            player.lerpTo(packet.getX(), packet.getY(), packet.getZ(),
                    packet.getyRot() * 360.0f / 256.0f, packet.getxRot() * 360.0f / 256.0f, 3);
            player.setDeltaMovement(player.getDeltaMovement().scale(0.5));

            electroEnergetics$LOG.info("FORCED eased dist={} from={} to={}",
                    String.format("%.2f", dist),
                    String.format("(%.2f,%.2f,%.2f)", player.getX(), player.getY(), player.getZ()),
                    String.format("(%.2f,%.2f,%.2f)", packet.getX(), packet.getY(), packet.getZ()));

            ci.cancel();
        } catch (Throwable ignored) {
            // If anything at all is unexpected, let vanilla handle the packet.
        }
    }
}
