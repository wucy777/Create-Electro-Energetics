package com.george_vi.electroenergetics.mixins;

import com.george_vi.electroenergetics.content.railway_electrification.ElectricTrainData;
import com.george_vi.electroenergetics.mixin_interfaces.ICEETrainExtension;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gives a player standing on an electric carriage the footing and the carry that a solid floor would.
 *
 * <h2>The one thing that is actually wrong</h2>
 *
 * <p>Measured per position write, player standing in a carriage at 3.9 Blocks/tick:
 *
 * <pre>
 *   t=867607  p=(336.50,-58.05,-464.51)  d=0.00  dY=-0.08  sp=0
 *   t=867608  p=(336.50,-58.21,-464.51)  d=0.00  dY=-0.15  sp=0
 *   t=867609  p=(336.50,-58.44,-464.51)  d=0.00  dY=-0.23  sp=0
 * </pre>
 *
 * <p>The horizontal carry is not broken - elsewhere the same log shows {@code d=3.63} to {@code d=3.92},
 * exactly the carriage's speed. What is broken is that {@code sp} is zero and {@code dY} is negative every
 * tick: the player is in free fall inside a carriage, sinking about 0.15 Blocks per tick, while whatever
 * horizontal carry is left slides them along.
 *
 * <p>That is the entire defect. Not a bad dismount destination, not a teleport, not a stale seat - a floor
 * the game does not recognise.
 *
 * <h2>Why the floor is not recognised</h2>
 *
 * <p>The carriage floor is a contraption, not blocks. Vanilla's grounding test looks for blocks beneath the
 * entity and finds none. Create's collider does set {@code onGround}, but only under
 * {@code canWalk = bounce != 0 || slide == 0}, where {@code slide} is the block's friction minus 0.6 -
 * flooring that reports friction above 0.6 never reaches that branch, and nothing else sets the flag.
 *
 * <h2>What this does instead of reimplementing the carry</h2>
 *
 * <p>It supplies only what the missing floor would have supplied, and leaves position, input, collision and
 * jumping entirely to the game:
 *
 * <ul>
 *   <li>{@code onGround = true} while the player's feet are within a step of the carriage floor;</li>
 *   <li>the accumulated downward velocity is dropped, so they stop sinking;</li>
 *   <li>fall distance is cleared, so they take no fall damage from standing still.</li>
 * </ul>
 *
 * <p>Nothing sets the player's position. Every previous attempt set it, which is why every one of them
 * produced a new symptom: a player whose position is written each tick cannot walk, cannot jump, and cannot
 * be carried by anything else. Here the carry keeps doing the carrying and the game keeps doing the moving.
 *
 * <h2>How "on the floor" is decided</h2>
 *
 * <p>By geometry against the carriage's own frame, so turning does not matter: the player's feet are within
 * a step height of the floor line, and horizontally inside the carriage. A player mid-jump is above that
 * band and is left alone, which is what allows jumping to work.
 *
 * <h2>Scope and cost</h2>
 *
 * <p>Electric trains only. Per tick per player on one: two coordinate transforms and a comparison, no
 * allocation beyond the Vec3 the transform already returns. Players elsewhere pay one {@code instanceof}
 * and one bounding-box query.
 */
@Mixin(value = net.minecraft.world.entity.LivingEntity.class, remap = false)
public class CarriageRiderFrameMixin {

    /**
     * How far above the carriage floor a player can be and still count as standing.
     *
     * <p>Vanilla's step height is 0.6; a band slightly above that separates standing from jumping without
     * catching either wrongly.
     */
    @Unique
    private static final double electroEnergetics$STEP_BAND = 0.75d;

    /**
     * Runs immediately before the game applies gravity, which is the only moment where setting
     * {@code onGround} changes the outcome.
     *
     * <p>The previous build hooked {@code Entity.tick} at TAIL - after gravity had already been applied -
     * and the log showed what that costs: {@code sp=1} on 34 writes but {@code sp=0} on 801, with 11 writes
     * where {@code onGround} was true and the player was still sinking. Setting the flag after the
     * decision it gates is the same as not setting it.
     */
    @Inject(method = "travel", at = @At("HEAD"), remap = false)
    private void electroEnergetics$restoreFooting(Vec3 travelVector, CallbackInfo ci) {
        try {
            if (!((Object) this instanceof Player player))
                return;

            CarriageContraptionEntity carriage = electroEnergetics$carriageAround(player);
            if (carriage == null)
                return;

            Vec3 local = carriage.toLocalVector(player.position(), 1.0f);
            if (!electroEnergetics$standingOnFloor(carriage, player, local))
                return;

            player.setOnGround(true);
            player.fallDistance = 0;
            Vec3 motion = player.getDeltaMovement();
            // Only the sink that built up while the floor was ignored is cancelled. An upward velocity is a
            // jump in progress and must be left alone, or jumping would not work at all.
            if (motion.y < 0)
                player.setDeltaMovement(motion.x, 0, motion.z);
        } catch (Throwable ignored) {
            // A footing fix must never be the thing that breaks movement.
        }
    }

    /**
     * Whether the player's feet are within one step of the carriage floor and inside its footprint.
     *
     * <p>The floor line is taken from the carriage's own seat position rather than a fixed offset, because
     * the seat height already accounts for the passenger's size and the carriage's build.
     */
    @Unique
    private boolean electroEnergetics$standingOnFloor(CarriageContraptionEntity carriage, Player player,
                                                      Vec3 local) {
        Vec3 seat = carriage.getPassengerPosition(player, 1.0f);
        if (seat == null)
            return false;

        Vec3 seatLocal = carriage.toLocalVector(seat, 1.0f);
        double aboveFloor = local.y - seatLocal.y;

        // Below the band: standing, or crouching. Slightly negative covers the sub-block settle the game
        // applies when an entity is placed onto a surface.
        return aboveFloor >= -0.5 && aboveFloor <= electroEnergetics$STEP_BAND;
    }

    /**
     * The electric carriage the player is riding or standing in, or null.
     *
     * <p>Riding is checked first because it is the common case and costs nothing. A player who has stood up
     * from the seat is still inside the carriage, so proximity is checked as a fallback - deliberately
     * before anything is written, so a player on open ground never has grounding forced on them.
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
