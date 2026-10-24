package com.george_vi.electroenergetics.mixins;

import com.george_vi.electroenergetics.content.railway_electrification.rider.CarriageRiderFrame;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import net.minecraft.world.entity.MoverType;
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
 * <h2>Three things this file has learned the hard way</h2>
 *
 * <p><b>1. The offset must be persistent.</b> Re-deriving it from the world position each tick and
 * converting back cancels the carriage's motion: the player stops moving, the carriage does not, the
 * world-to-local step absorbs the difference and converting back returns the same coordinate. Measured as a
 * player pinned at one point for two hundred ticks while the carriage left. The offset may only change by
 * the player's own movement, taken as a world-space VECTOR and rotated into the frame with
 * {@code reverseRotation} - rotation alone, no anchor. {@code toLocalVector} subtracts the anchor, which is
 * a point, so it is the wrong tool for a displacement and was the cause of that loop.
 *
 * <p><b>2. The old-position fields must not be written.</b> Setting {@code xo/yo/zo} to the new position
 * removes render interpolation. At 20 ticks per second the player advances three Blocks in one discrete
 * step, and the world jumps with the camera four times a second. That was the stuttering.
 *
 * <p><b>3. {@code setPos} bypasses collision.</b> Teleporting to the computed position each tick meant the
 * player passed through anything inside the carriage - a chest, a partition, a block - because nothing ever
 * asked whether the destination was free. The carriage's own floor was equally unprotected: the height
 * clamp only applied while the player was within half a Block of it, so once they sank past that the clamp
 * gave up and they fell through the bottom.
 *
 * <h2>How the position is applied now</h2>
 *
 * <p>The movement is handed to {@code Entity.move}, which is the game's own collision-resolving mover, with
 * the delta between the player's current position and the position the frame says they should occupy. So the
 * carry collides with everything a walking player collides with, and the result is whatever the game
 * allows - not whatever this class computed.
 *
 * <p>The authoritative offset is then re-read from wherever the player actually ended up. That way a
 * blocked movement stays blocked instead of being re-applied next tick, and the frame never accumulates a
 * correction the world refused to make.
 *
 * <p>The floor is a hard lower bound while the player is on the carriage, with no tolerance: a player
 * cannot be below the floor of the carriage they are standing in, so there is no depth from which they can
 * begin to fall.
 *
 * <h2>Cost</h2>
 *
 * <p>Two vector operations, one move and one map write per tick, for a player on a carriage. The move is the
 * same call the player would make anyway; it is not an extra collision pass, it is the one that replaces
 * the teleport. Players elsewhere pay one {@code instanceof} and one map miss.
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

            // The floor is a hard lower bound while they are on the carriage - no tolerance, because any
            // tolerance is a depth from which the player can start to fall. An upward excursion is a jump
            // and is left alone.
            boolean onFloor = local.y <= floor + 0.75;
            if (onFloor && local.y < floor)
                local = new Vec3(local.x, floor, local.z);

            Vec3 target = carriage.toGlobalVector(local, 1.0f);

            // Hand the movement to the game's own collision-resolving mover rather than teleporting. This is
            // what makes chests, partitions and other blocks inside the carriage actually stop the player.
            Vec3 applied = target.subtract(player.position());
            if (applied.lengthSqr() > 1.0e-8)
                player.move(MoverType.SELF, applied);

            // Re-read the offset from where collision actually left them, so a blocked move stays blocked
            // instead of being re-applied every tick.
            Vec3 actual = carriage.toLocalVector(player.position(), 1.0f);
            if (actual.y < floor)
                actual = new Vec3(actual.x, floor, actual.z);
            if (actual.y < floor + 0.75) {
                player.setOnGround(true);
                player.fallDistance = 0;
                Vec3 motion = player.getDeltaMovement();
                if (Math.abs(motion.y) < 0.5)
                    player.setDeltaMovement(motion.x, 0, motion.z);
            }

            CarriageRiderFrame.put(player, new CarriageRiderFrame.Local(
                    pending.carriageId(), actual, floor));

            if ((electroEnergetics$n++ % 40) == 0) {
                electroEnergetics$LOG.info("FRAME local={} moved={} onFloor={}",
                        String.format("(%.2f,%.2f,%.2f)", actual.x, actual.y, actual.z),
                        String.format("%.3f", worldDelta.length()), onFloor);
            }
        } catch (Throwable ignored) {
            // A frame fix must never be the thing that breaks movement.
        }
    }
}