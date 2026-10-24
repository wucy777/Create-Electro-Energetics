package com.george_vi.electroenergetics.mixins;

import com.george_vi.electroenergetics.content.railway_electrification.rider.CarriageRiderFrame;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
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
 * Owns a player's position on a carriage: the offset lives in the carriage frame, the world follows.
 *
 * <h2>Why the previous version froze the player</h2>
 *
 * <p>It re-derived the offset from the player's world position and then converted it back. With the carry
 * suppressed, the player stopped moving while the carriage kept going, so the world-to-local conversion
 * absorbed the carriage's motion into the offset, and converting back returned the same world position.
 * A stable loop that never carried anyone - measured as a player pinned at z=-371.78 for hundreds of ticks
 * while veh=175 moved away.
 *
 * <h2>The correction</h2>
 *
 * <p>The offset must be persistent, and it may only change by the player's OWN movement. So the movement is
 * taken as a world-space VECTOR between the start and end of the tick, rotated into the carriage frame by
 * rotation alone, and added to the stored offset. The carriage's motion never enters the arithmetic, which
 * is precisely why the player is then carried by it.
 *
 * <p>{@code toLocalVector} is the wrong tool for that: it subtracts the anchor, which is a point, not a
 * direction. Using it on a displacement is what produced the no-op loop. {@code reverseRotation} is the
 * correct one because a displacement has no origin.
 *
 * <h2>Why the old position fields are written too</h2>
 *
 * <p>The renderer interpolates between {@code xo/yo/zo} and the current position. Writing the position alone
 * leaves the old value behind, so the body is drawn partway between where it was and where it is - the
 * camera shows the new place while the model shows the old one. That is the "out of body" symptom. Setting
 * both makes the tick look like no movement, which is what a teleport should look like.
 *
 * <h2>Cost</h2>
 *
 * <p>Two vector operations and a map write per tick for a player on a carriage. Players elsewhere pay one
 * instanceof and one map miss.
 */
@Mixin(value = net.minecraft.world.entity.player.Player.class, remap = false)
public class CarriageRiderFrameMixin {

    @Unique
    private static final Logger electroEnergetics$LOG = LoggerFactory.getLogger("CEE-FRAME");

    @Unique
    private static int electroEnergetics$n;

    /** The stored offset at the start of this tick, or null when the player is not on a carriage. */
    @Unique
    private CarriageRiderFrame.Local electroEnergetics$pending;

    /** Where the player was at the start of the tick, before their own input and gravity. */
    @Unique
    private Vec3 electroEnergetics$startWorld;

    @Inject(method = "tick", at = @At("HEAD"), remap = false)
    private void electroEnergetics$capture(CallbackInfo ci) {
        electroEnergetics$pending = null;
        electroEnergetics$startWorld = null;
        try {
            if (!((Object) this instanceof Player player))
                return;
            if (!player.level().isClientSide())
                return;

            CarriageContraptionEntity carriage = CarriageRiderFrame.carriageOf(player);
            if (carriage == null) {
                CarriageRiderFrame.forget(player);
                return;
            }

            var stored = CarriageRiderFrame.get(player);
            electroEnergetics$pending = CarriageRiderFrame.belongsTo(stored, carriage)
                    ? stored
                    : CarriageRiderFrame.record(player, carriage);
            electroEnergetics$startWorld = player.position();
        } catch (Throwable ignored) {
            // A frame fix must never be the thing that breaks movement.
        }
    }

    @Inject(method = "tick", at = @At("TAIL"), remap = false)
    private void electroEnergetics$resolve(CallbackInfo ci) {
        try {
            var pending = electroEnergetics$pending;
            var start = electroEnergetics$startWorld;
            electroEnergetics$pending = null;
            electroEnergetics$startWorld = null;

            if (pending == null || start == null)
                return;
            if (!((Object) this instanceof Player player))
                return;
            if (!player.level().isClientSide())
                return;

            CarriageContraptionEntity carriage = CarriageRiderFrame.carriageOf(player);
            if (carriage == null || !CarriageRiderFrame.belongsTo(pending, carriage)) {
                CarriageRiderFrame.forget(player);
                return;
            }

            // The player's own movement this tick, as a VECTOR, rotated into the carriage frame.
            // Rotation only: a displacement has no origin, so the anchor must not be subtracted.
            Vec3 worldDelta = player.position().subtract(start);
            Vec3 localDelta = carriage.reverseRotation(worldDelta, 1.0f);

            Vec3 local = pending.local().add(localDelta);
            double floor = pending.floorY();

            boolean standing = local.y - floor >= -0.5 && local.y - floor <= 0.75;
            if (standing)
                local = new Vec3(local.x, floor, local.z);

            Vec3 world = carriage.toGlobalVector(local, 1.0f);
            player.setPos(world.x, world.y, world.z);

            // The renderer interpolates between the old and current position. Leaving the old value behind
            // draws the body partway back - the camera moves, the model does not. Writing both makes this
            // look like a teleport rather than a lag.
            player.xo = world.x;
            player.yo = world.y;
            player.zo = world.z;
            player.xOld = world.x;
            player.yOld = world.y;
            player.zOld = world.z;

            if (standing) {
                player.setOnGround(true);
                player.fallDistance = 0;
                Vec3 motion = player.getDeltaMovement();
                if (Math.abs(motion.y) < 0.5)
                    player.setDeltaMovement(motion.x, 0, motion.z);
            }

            CarriageRiderFrame.put(player, new CarriageRiderFrame.Local(
                    pending.carriageId(), local, floor));

            if ((electroEnergetics$n++ % 20) == 0) {
                electroEnergetics$LOG.info("FRAME local={} world={} moved={} standing={}",
                        String.format("(%.2f,%.2f,%.2f)", local.x, local.y, local.z),
                        String.format("(%.2f,%.2f,%.2f)", world.x, world.y, world.z),
                        String.format("%.3f", worldDelta.length()), standing);
            }
        } catch (Throwable ignored) {
            // A frame fix must never be the thing that breaks movement.
        }
    }
}