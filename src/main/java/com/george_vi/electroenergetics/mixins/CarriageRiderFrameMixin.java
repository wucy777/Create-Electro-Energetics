package com.george_vi.electroenergetics.mixins;

import com.george_vi.electroenergetics.content.railway_electrification.ElectricTrainData;
import com.george_vi.electroenergetics.mixin_interfaces.ICEETrainExtension;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Places a rider on an electric carriage BEFORE gravity is applied, so the floor is real for that tick.
 *
 * <h2>The ordering that made every previous attempt fail</h2>
 *
 * <p>Inside one client tick the relevant calls run in this order:
 *
 * <pre>
 *   Entity.tick
 *     -&gt; LivingEntity.aiStep
 *        -&gt; travel              applies gravity, reading onGround
 *     -&gt; move
 *   ClientEvents.onTickPost
 *     -&gt; ContraptionHandler.tick
 *        -&gt; ContraptionCollider  moves the rider onto the carriage, sets onGround
 * </pre>
 *
 * <p>The collider runs AFTER gravity. So a rider on a fast carriage goes through this every tick: gravity
 * pulls them down because the floor is not recognised yet, then the collider lifts them back onto the
 * carriage and marks them grounded, then gravity pulls them down again. Measured:
 *
 * <pre>
 *   t=871034  y=-59.07  d= 0.00  dY= 0.00  sp=0
 *   t=871034  y=-59.07  d= 3.48  dY= 0.00  sp=1
 *   t=871034  y=-59.17  d= 0.00  dY=-0.10  sp=1
 * </pre>
 *
 * <p>and periodically the accumulated deficit is paid off in one go - {@code d=10.44} against a carriage
 * speed of 3.48, which is the "thrown" the operator keeps reporting.
 *
 * <p>Setting {@code onGround} anywhere later in that cycle cannot work: the sink has already been applied
 * by the time the flag is set, and the flag is cleared again by the next tick's grounding test.
 *
 * <h2>What this does</h2>
 *
 * <p>At the head of {@code travel} - before gravity is computed - for a player standing on an electric
 * carriage: move them onto the carriage's floor and ground them. Gravity then integrates against a floor
 * that exists, so there is nothing to sink through and nothing for the collider to catch up to.
 *
 * <p>The position is taken from the carriage's own frame, not invented. It is the same value the collider
 * would apply a moment later; the only change is that it is applied early enough to matter.
 *
 * <h2>Why moving the player here does not forbid walking or jumping</h2>
 *
 * <p>This runs before the player's movement for the tick, not after it. Input processed later in the same
 * tick - walking, sprinting, a jump - is added on top of a correct starting position, so it is neither
 * overwritten nor amplified. Vertical velocity is preserved: only the downward sink that built up while the
 * floor was missing is cleared, and only when no jump is in progress.
 *
 * <h2>Scope and cost</h2>
 *
 * <p>Electric trains only. Per tick per player on one: two coordinate transforms and a comparison. Everyone
 * else pays one {@code instanceof}.
 */
@Mixin(value = LivingEntity.class, remap = false)
public class CarriageRiderFrameMixin {

    /** Height band above the carriage floor that still counts as standing on it. */
    @Unique
    private static final double electroEnergetics$STEP_BAND = 0.75d;

    /**
     * Runs at the head of the gravity step, which is the only place a grounding correction can take effect.
     */
    @Inject(method = "travel", at = @At("HEAD"), remap = false)
    private void electroEnergetics$standOnCarriageBeforeGravity(Vec3 travelVector, CallbackInfo ci) {
        try {
            if (!((Object) this instanceof Player player))
                return;

            CarriageContraptionEntity carriage = electroEnergetics$carriageAround(player);
            if (carriage == null)
                return;

            Vec3 local = carriage.toLocalVector(player.position(), 1.0f);
            if (!electroEnergetics$standingOnFloor(carriage, player, local))
                return;                     // mid-jump or outside the footprint: leave the tick alone

            // Snap the vertical only. Horizontal is owned by the carry and is already correct; writing it
            // here would fight the carriage's own motion on every tick, which is the mistake the discarded
            // full-frame draft made.
            Vec3 seat = carriage.getPassengerPosition(player, 1.0f);
            if (seat == null)
                return;
            player.setPos(player.getX(), seat.y, player.getZ());

            player.setOnGround(true);
            player.fallDistance = 0;
            Vec3 motion = player.getDeltaMovement();
            if (motion.y < 0)
                player.setDeltaMovement(motion.x, 0, motion.z);
        } catch (Throwable ignored) {
            // A footing fix must never be the thing that breaks movement.
        }
    }

    /**
     * Whether the player's feet are within one step of the carriage floor.
     *
     * <p>The floor line is taken from the carriage's own seat position, which already accounts for the
     * passenger's size and the carriage's build, rather than a fixed offset guessed from the sky.
     */
    @Unique
    private boolean electroEnergetics$standingOnFloor(CarriageContraptionEntity carriage, Player player,
                                                      Vec3 local) {
        Vec3 seat = carriage.getPassengerPosition(player, 1.0f);
        if (seat == null)
            return false;

        Vec3 seatLocal = carriage.toLocalVector(seat, 1.0f);
        double aboveFloor = local.y - seatLocal.y;

        // A jump leaves the band immediately, which is why a band is used rather than an equality test.
        return aboveFloor >= -0.5 && aboveFloor <= electroEnergetics$STEP_BAND;
    }

    /**
     * The electric carriage the player is riding or standing in, or null.
     *
     * <p>Riding is checked first because it is the common case. A player who has stood up from the seat is
     * still inside the carriage, so proximity is checked as a fallback - before anything is written, so a
     * player on open ground never has their position touched.
     */
    @Unique
    private static CarriageContraptionEntity electroEnergetics$carriageAround(Player player) {
        if (player.getVehicle() instanceof CarriageContraptionEntity carriage
                && electroEnergetics$isElectric(carriage))
            return carriage;

        CarriageContraptionEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity e : player.level().getEntities(player, player.getBoundingBox().inflate(6))) {
            if (!(e instanceof CarriageContraptionEntity carriage))
                continue;
            if (!electroEnergetics$isElectric(carriage))
                continue;
            double d = carriage.position().distanceTo(player.position());
            if (d < bestDistance) {
                bestDistance = d;
                best = carriage;
            }
        }
        // 6 Blocks covers a carriage length; beyond that the player is not inside that train.
        return bestDistance <= 6 ? best : null;
    }

    @Unique
    private static boolean electroEnergetics$isElectric(CarriageContraptionEntity carriage) {
        var c = carriage.getCarriage();
        if (c == null || c.train == null)
            return false;
        ElectricTrainData data = ((ICEETrainExtension) c.train).getElectricTrainData();
        return data != null && data.hasTractionMotors;
    }
}
