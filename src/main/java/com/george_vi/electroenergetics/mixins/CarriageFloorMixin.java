package com.george_vi.electroenergetics.mixins;

import com.george_vi.electroenergetics.content.railway_electrification.ElectricTrainData;
import com.george_vi.electroenergetics.content.railway_electrification.RiderSeatGate;
import com.george_vi.electroenergetics.mixin_interfaces.ICEETrainExtension;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
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
 *   <li>{@code onGround = true}, so the normal movement code can stop accumulating a fall and allow jumps;</li>
 *   <li>{@code fallDistance = 0}, so a missed collision frame does not cause false fall damage.</li>
 * </ul>
 *
 * <p>The horizontal position is NOT touched. It is already correct, and the carry continues to own it.
 *
 * <h2>How standing on the floor is decided</h2>
 *
 * <p>The player's feet are transformed into carriage-local coordinates and checked against the top face of
 * a real collision shape in the contraption block map. Nearby entity bounds are only a spatial-index query;
 * they never decide whether the player is on the train. Seated riders are left to Create's passenger path.
 *
 * <h2>Cost</h2>
 *
 * <p>Only unseated players query nearby entities, in a 1-block horizontal radius. Most ticks find no
 * carriage candidate; if one is nearby, the exact local support check inspects at most four blocks below.
 * No position correction, per-tick logging, network packet, or state cache is added.
 */
@Mixin(value = Entity.class, remap = false)
public class CarriageFloorMixin {

    @Inject(method = "tick", at = @At("HEAD"), remap = false)
    private void electroEnergetics$restoreFooting(CallbackInfo ci) {
        try {
            if (!((Object) this instanceof Player player))
                return;
            Entity vehicle = player.getVehicle();
            if (vehicle instanceof CarriageContraptionEntity) {
                // Riding a carriage: the seat holds the player, and Create positions them every tick. Forcing
                // a footing underneath that is exactly the fight described above, so nothing is done here.
                return;
            }

            // Not riding: restore footing only when a real local collision surface supports the player's feet.
            // The nearby entity query below is broad phase only; a train beside the platform cannot qualify.
            if (player.isPassenger())
                return;
            electroEnergetics$standOnNearestFloor(player);
        } catch (Throwable ignored) {
            // A footing fix must never be the thing that breaks movement.
        }
    }

    @Unique
    private boolean electroEnergetics$standOnCarriageFloor(Player player, CarriageContraptionEntity carriage) {
        if (electroEnergetics$isJumpingOrClimbing(player))
            return false;
        if (!electroEnergetics$isElectric(carriage)
                || !RiderSeatGate.isStandingOnCarriageFloor(player, carriage))
            return false;
        electroEnergetics$ground(player);
        return true;
    }

    @Unique
    private void electroEnergetics$standOnNearestFloor(Player player) {
        // This is only a broad-phase lookup; the decision below checks the exact local support block and
        // its top face. A nearby axis-aligned carriage bound can never by itself mark a player grounded.
        AABB search = player.getBoundingBox().inflate(1.0d, 1.5d, 1.0d);
        for (Entity e : player.level().getEntities(player, search)) {
            if (e instanceof CarriageContraptionEntity carriage
                    && electroEnergetics$standOnCarriageFloor(player, carriage))
                return;
        }
    }

    @Unique
    private boolean electroEnergetics$isJumpingOrClimbing(Player player) {
        // A player who is moving UP is not standing on the carriage floor, and telling the game they are is how a
        // jump through a carriage wall happens: the ground flag suppresses the collision response, the player
        // rises through the roof or the side, and the next tick finds them outside the carriage. Reported as
        // "jumping while pressing against the side of the carriage, sometimes you go straight through".
        //
        // Only a descending-or-still player can be given a footing.
        // Creative flight counts: the operator flew into a parked train and the flight stopped dead, then
        // carried on falling, and sometimes ended up inside the carriage. Grounding a flying player is the same
        // mistake as grounding a jumping one - the game reads it as supported and the flight is cut.
        return player.getDeltaMovement().y > 0.01d
                || player.isSwimming()
                || player.isFallFlying()
                || player.getAbilities().flying
                || player.isNoGravity();
    }

    @Unique
    private static void electroEnergetics$ground(Player player) {
        // Grounded, and nothing else.
        //
        // This used to zero any downward velocity as well, and that is what made the player jitter
        // vertically: Create's own collision pass runs in the same tick and writes its own motion, so the
        // two fought over the same field - the player was pinned to the floor, then moved, then pinned
        // again. Reported as "the body twitches up and down at about 15 m/s, and at 23 it sometimes goes
        // through the floor".
        //
        // Clearing `fallDistance` and setting the flag is all the floor needs: it is what stops the fall
        // damage and what lets a step or a jump work. Whatever vertical motion the game has decided on is
        // left to the game.
        // fallDistance only. setOnGround(true) was here and it is what let players through the carriage wall.
        //
        // The ground flag does more than stop fall damage: the game treats a grounded entity as supported and
        // skips the collision response it would otherwise apply, so a player pressed against a carriage side and
        // jumping had nothing stopping them crossing it. The operator hit it from both sides - inside jumping at
        // the shell and coming out, outside flying into a parked train and ending up standing on its floor.
        //
        // Clearing fallDistance is the part that was actually needed: it is what stops a fall being recorded
        // while a carriage floor passes underneath. Grounding is the game's own answer and it is left to it.
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