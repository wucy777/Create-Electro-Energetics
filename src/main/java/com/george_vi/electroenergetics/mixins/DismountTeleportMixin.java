package com.george_vi.electroenergetics.mixins;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Applies a small server position correction by interpolation instead of a hard set, so leaving a train
 * at speed eases the player across the gap instead of throwing them.
 *
 * <h2>How the real site was found</h2>
 *
 * <p>Six builds aimed at the dismount location stored in NBT. A consumer probe finally showed that tag sits
 * downstream of the move - it logged {@code seat=(336.50,-443.19) rider=(336.50,-443.19)}, identical.
 *
 * <p>A stack trace from the one server method every teleport funnels through named the sender as
 * {@code ServerEntity.sendChanges}, which forces one absolute-position packet on the tick a passenger is
 * released. That packet is a {@code ClientboundPlayerPositionPacket}: a hook on
 * {@code handleTeleportEntity}, written for the sibling packet class, never fired, while the write tracer
 * kept reporting {@code ClientPacketListener.handleMovePlayer} as the writer.
 *
 * <p>Inside {@code handleMovePlayer} there is exactly ONE position write, at bytecode offset 287:
 *
 * <pre>
 *   player.setPos(x, y, z);
 *   player.setDeltaMovement(...);
 * </pre>
 *
 * <p>The previous-position fields are updated just before it, in both the relative and absolute branches,
 * so nothing downstream treats the change as motion - it is a hard set, and the player visibly jumps by
 * the entire correction.
 *
 * <h2>What is changed</h2>
 *
 * <p>For the local player, when the correction is small, that {@code setPos} is replaced by
 * {@link Player#lerpTo} over three ticks. Vanilla already uses {@code lerpTo} for ordinary entity
 * movement; it eases an entity across a small position change and keeps input responsive, because the
 * client still owns the player's motion while the interpolation runs.
 *
 * <p>The correction being eased is the gap between the server's carriage position and the client's
 * interpolated one - measured at 3.9 ticks of travel, 15.33 Blocks at 3.91 Blocks/tick on the previous
 * build. A genuinely large relocation is far outside the bound and is applied exactly as vanilla does it.
 *
 * <h2>Bound</h2>
 *
 * <p>64 Blocks. The artefact is under 30 Blocks even at this mod's ceiling; an intentional teleport is
 * structurally elsewhere. The bound cannot catch the latter and cannot miss the former.
 *
 * <h2>Cost</h2>
 *
 * <p>One distance calculation per position packet received for the local player. Such packets arrive on
 * dismounts, dimension changes and periodic resyncs - not per tick.
 */
@Mixin(value = ClientPacketListener.class, remap = false)
public class DismountTeleportMixin {

    @Unique
    private static final Logger electroEnergetics$LOG = LoggerFactory.getLogger("CEE-FORCED");

    @Redirect(
            method = "handleMovePlayer",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/player/Player;setPos(DDD)V"),
            remap = false)
    private void electroEnergetics$easeInsteadOfSnap(Player player, double x, double y, double z) {
        try {
            if (player instanceof LocalPlayer local) {
                double dx = x - player.getX();
                double dy = y - player.getY();
                double dz = z - player.getZ();
                double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

                if (dist > 0.05 && dist <= 64) {
                    // Ease it. 3 ticks is what vanilla uses for an entity that moved; the player keeps
                    // full control while the position catches up.
                    local.lerpTo(x, y, z, local.getYRot(), local.getXRot(), 3);
                    electroEnergetics$LOG.info("FORCED eased dist={} to=({},{},{})",
                            String.format("%.2f", dist),
                            String.format("%.2f", x), String.format("%.2f", y),
                            String.format("%.2f", z));
                    return;
                }
            }
            // Out of range, or not the local player: apply it exactly as vanilla does.
            player.setPos(x, y, z);
        } catch (Throwable ignored) {
            // If anything is unexpected, fall back to vanilla behaviour.
            player.setPos(x, y, z);
        }
    }
}
