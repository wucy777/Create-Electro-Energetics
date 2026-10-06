package com.george_vi.electroenergetics.mixins;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Stops vanilla rubber-banding a player who is standing on a fast train, by accepting the
 * displacement Create's carriage carrying legitimately produces.
 *
 * <h2>The bug</h2>
 *
 * <p>Reported: at high speed a player standing on an electric train is repeatedly yanked
 * backwards out of the carriage. Sitting down is unaffected, and neither is Create's own
 * slower traffic.
 *
 * <h2>Why it happens</h2>
 *
 * <p>Vanilla validates a free entity's reported movement every tick and snaps the player
 * back when the report disagrees with what the server simulated. The condition is
 * {@code distSq > 0.0625} against the position AFTER the server's own {@code move()} -
 * that is, a residual of more than a quarter of a block - and it is followed by a
 * {@code teleport(...)} to the pre-packet position. That is the rubber-band.
 *
 * <p>Two things make a rider on a train trip it, and neither is a mistake on Create's
 * part:
 *
 * <ul>
 *   <li>Create carries a standing rider CLIENT-SIDE and forwards the result, because
 *       {@code ContraptionCollider} deliberately skips players on the server
 *       ({@code if (playerType == PlayerType.SERVER) continue;}). So the client's position
 *       is authoritative and the server's own simulation of it is not merely stale, it is
 *       never computed at all - which means the residual is large by construction.</li>
 *   <li>The carriage is not made of world blocks, so from the block collision system's
 *       point of view a rider is standing in mid-air. The check that is supposed to
 *       suppress the correction for someone legitimately unable to move freely therefore
 *       does not fire.</li>
 * </ul>
 *
 * <p>Vanilla's own answer to a moving platform is genuine passengerhood: the whole
 * validation block is skipped for {@code isPassenger()}, and the platform is driven
 * through {@code handleMoveVehicle}, which snaps the VEHICLE and never the player. Create
 * routes a standing rider through the validated path instead, so the two are structurally
 * at odds - and the disagreement only becomes visible once the per-tick displacement is
 * large, which is what this mod's speed does. Create already works around the adjacent
 * problem (it zeroes the "floating too long" counters in both {@code ContraptionCollider}
 * and {@code ClientMotionPacket}) but has no equivalent bypass for this one.
 *
 * <h2>The fix</h2>
 *
 * <p>The correction is simply not applied to a player who is inside a contraption's own
 * bounding box. That is the honest test for "this player's motion is the contraption's to
 * decide": they are standing on it, they cannot be somewhere else, and the server has no
 * independent simulation of them to prefer.
 *
 * <p>Deliberately narrow in three ways. It is gated on the player being inside a carriage
 * rather than on any train existing, so a player on foot beside the track is validated
 * exactly as before. It only removes the correction, never the server's own position
 * update, so normal movement is untouched. And it does not touch the other two teleports
 * in this method - the "moved too quickly" one compares against the player's own delta and
 * needs 10 blocks per tick to fire, which this mod never reaches.
 *
 * <h2>Why an ordinal, and the risk it carries</h2>
 *
 * <p>The correction is targeted by {@code ordinal = 2} because all three teleports in
 * {@code handleMovePlayer} share an identical descriptor. This was verified against the
 * jars that actually run: both the 1.21.1 client jar and {@code neoforge-21.1.244-client}
 * have exactly three {@code teleport(DDDFF)V} calls in this method, and the third follows
 * the "moved wrongly" branch in both.
 *
 * <p>Stated plainly because it is a real risk: if a future NeoForge or Create adds another
 * {@code teleport} call earlier in this method, the ordinal would select a different call
 * site. The injection would still apply, so it would not be caught at load - but the
 * behaviour it modifies would be a different one. A failed lookup DOES fail loudly: the
 * injector requires one match, so the game refuses to start rather than silently doing
 * nothing, which is the failure mode to prefer.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public class ServerGamePacketListenerImplMixin {

    /**
     * Skip the rubber-band correction for a rider standing on a contraption.
     *
     * <p>Identified by ordinal because all three {@code teleport} calls in the target
     * method share one descriptor; see the class comment for the verification and for the
     * risk this carries.
     *
     * <p>The receiver comes first in the signature because the wrapped call is an instance
     * method - {@code this.teleport(...)} - which is also how the other WrapOperation in
     * this package reads. The player is taken from that receiver rather than shadowed,
     * because the field is public and not final.
     */
    @WrapOperation(
            method = "handleMovePlayer",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;teleport(DDDFF)V",
                    ordinal = 2),
            remap = false)
    private void electroEnergetics$acceptCarriageCarry(ServerGamePacketListenerImpl self,
                                                       double x, double y, double z,
                                                       float yRot, float xRot,
                                                       Operation<Void> original) {
        ServerPlayer player = self.player;
        if (player != null && electroEnergetics$aboardContraption(player)) {
            // The motion came from the carriage. Leaving the position alone is the whole
            // point: applying the correction is what yanks the rider out of the train.
            return;
        }
        original.call(self, x, y, z, yRot, xRot);
    }

    /**
     * Whether the player is standing on a contraption, tested against its own footprint.
     *
     * <p>Checked geometrically rather than from Create's collision bookkeeping, because
     * that bookkeeping is not populated for a player on the server - the very skip
     * ({@code PlayerType.SERVER}) that makes the client authoritative. The bounding box is
     * the same signal {@code ContraptionCollider} carries riders by, so it is the honest
     * one to test.
     *
     * <p>Inflated slightly, to absorb the player's own width and the sub-tick jitter of
     * being moved by the carriage. Kept small: a player walking past a train on a platform
     * must NOT be inside it, or they would lose validation while merely standing nearby.
     *
     * <p>Cost is one bounded entity query, and only on the ticks where vanilla had already
     * decided to rubber-band - that is, only while a rider is actually being carried at a
     * speed the client and server disagree about. Create runs a comparable query every
     * tick anyway.
     *
     * <p>Fails to {@code false} on anything unexpected, which restores vanilla behaviour
     * exactly.
     */
    private static boolean electroEnergetics$aboardContraption(ServerPlayer player) {
        try {
            return !player.level()
                    .getEntitiesOfClass(AbstractContraptionEntity.class,
                            player.getBoundingBox().inflate(1.0d),
                            e -> e.getBoundingBox().inflate(0.5d).contains(player.position()))
                    .isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }
}
