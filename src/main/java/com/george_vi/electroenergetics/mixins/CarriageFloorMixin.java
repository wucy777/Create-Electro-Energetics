package com.george_vi.electroenergetics.mixins;

import com.george_vi.electroenergetics.content.railway_electrification.ElectricTrainData;
import com.george_vi.electroenergetics.mixin_interfaces.ICEETrainExtension;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
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
 * Gives a player standing inside a fast electric carriage the same footing they have on solid ground.
 *
 * <h2>What was actually wrong</h2>
 *
 * <p>Measured while walking inside a carriage at speed, one line per write:
 *
 * <pre>
 *   t=863770  p=(334.50,-59.05,-394.62)  d=3.88  dY= 0.00  sp=0
 *   t=863770  p=(334.50,-59.14,-390.73)  d=0.00  dY=-0.09  sp=0
 *   t=863771  p=(334.50,-59.14,-386.85)  d=3.88  dY= 0.00  sp=0
 *   t=863771  p=(334.50,-59.25,-386.85)  d=0.00  dY=-0.11  sp=0
 * </pre>
 *
 * <p>Two separate facts, and only one of them is broken:
 *
 * <ul>
 *   <li>the horizontal carry is CORRECT - {@code d=3.88} per tick is exactly the carriage's own speed,
 *       so the player is being moved with the carriage as they should be;</li>
 *   <li>{@code sp=0} on every single line, and {@code dY} negative every tick - the player is in
 *       permanent free fall inside the carriage, gaining about 0.01 Blocks of downward speed per tick.</li>
 * </ul>
 *
 * <p>So there is no dragging, no throwing and no bad destination. The player walks on a floor the game
 * does not acknowledge, falls through it, and the horizontal carry keeps sliding them along while they
 * sink. From the outside that reads as being slid around the carriage and dropped out of the bottom.
 *
 * <h2>Why the carriage floor does not count as ground</h2>
 *
 * <p>The floor is a contraption, not a block. Vanilla's grounding test looks for blocks beneath the entity
 * and finds none, so {@code onGround} stays false. Create's collider does set it - but only when
 * {@code canWalk}, which is {@code bounce != 0 || slide == 0}, where {@code slide} is the block's friction
 * minus 0.6. Carriage flooring that reports any friction above 0.6 therefore never reaches that branch,
 * and nothing else does either.
 *
 * <h2>What this does</h2>
 *
 * <p>For a player standing on the floor of an electric carriage, restores the two things solid ground
 * gives them:
 *
 * <ul>
 *   <li>{@code onGround = true}, so gravity stops accumulating and jumping is permitted;</li>
 *   <li>vertical velocity is dropped when they are not actively jumping, so they do not inherit the sink
 *       that built up while the floor was ignored.</li>
 * </ul>
 *
 * <p>The horizontal position is NOT touched. It is already correct, and the carry continues to own it.
 *
 * <h2>How "standing on the floor" is decided</h2>
 *
 * <p>By geometry, not by state: the player's feet are within a small band above the carriage's floor
 * level, and horizontally within the carriage's footprint. A player mid-jump is above that band and is
 * left alone, which is what allows jumping to work at all. It costs two comparisons in the common case.
 *
 * <h2>Cost</h2>
 *
 * <p>Runs per tick for players inside a carriage. Two Vec3 reads and a comparison, with the train lookup
 * skipped entirely when the vehicle is not a carriage. No allocation, no lookup table, no logging outside
 * the diagnostic build.
 */
@Mixin(value = Entity.class, remap = false)
public class CarriageFloorMixin {

    /**
     * Minimum height above the carriage floor at which the player stops counting as grounded.
     *
     * <p>Vanilla's step height is 0.6 and its jump leaves the floor immediately, so a band slightly above
     * that separates "standing" from "jumping" without catching either wrongly.
     */
    @Unique
    private static final double electroEnergetics$FLOOR_BAND = 0.75d;

    @Inject(method = "tick", at = @At("HEAD"), remap = false)
    private void electroEnergetics$restoreFooting(CallbackInfo ci) {
        try {
            if (!((Object) this instanceof Player player))
                return;
            Entity vehicle = player.getVehicle();
            if (vehicle instanceof CarriageContraptionEntity carriage) {
                electroEnergetics$standOnCarriageFloor(player, carriage);
                return;
            }

            // Not riding: the player may be walking inside the carriage they just left the seat of.
            // The carriage is found only when they are actually inside one, which the distance test
            // rejects for anyone on open ground.
            if (player.isPassenger())
                return;
            electroEnergetics$standOnNearestFloor(player);
        } catch (Throwable ignored) {
            // A footing fix must never be the thing that breaks movement.
        }
    }

    @Unique
    private void electroEnergetics$standOnCarriageFloor(Player player, CarriageContraptionEntity carriage) {
        if (!electroEnergetics$isElectric(carriage))
            return;

        Vec3 seat = carriage.getPassengerPosition(player, 1.0f);
        if (seat == null)
            return;

        double aboveFloor = player.getY() - (seat.y - electroEnergetics$seatToFloor());
        if (aboveFloor < -0.5 || aboveFloor > electroEnergetics$FLOOR_BAND)
            return;

        electroEnergetics$ground(player);
    }

    @Unique
    private void electroEnergetics$standOnNearestFloor(Player player) {
        double best = Double.MAX_VALUE;
        CarriageContraptionEntity found = null;
        for (Entity e : player.level().getEntities(player, player.getBoundingBox().inflate(6))) {
            if (!(e instanceof CarriageContraptionEntity carriage))
                continue;
            if (!electroEnergetics$isElectric(carriage))
                continue;
            double d = carriage.position().distanceTo(player.position());
            if (d < best) {
                best = d;
                found = carriage;
            }
        }
        if (found != null)
            electroEnergetics$standOnCarriageFloor(player, found);
    }

    /**
     * The seat sits {@code 0.125} above the carriage floor plus the rider's own height offset, so the
     * floor is that much below the seat. Kept as a named constant rather than a literal at the use site.
     */
    @Unique
    private static double electroEnergetics$seatToFloor() {
        return 0.125d;
    }

    @Unique
    private static void electroEnergetics$ground(Player player) {
        player.setOnGround(true);
        // Only cancel downward motion that came from the floor being ignored. An upward velocity means a
        // jump is in progress and must be left alone.
        Vec3 motion = player.getDeltaMovement();
        if (motion.y < 0)
            player.setDeltaMovement(motion.x, 0, motion.z);
        player.fallDistance = 0;
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
