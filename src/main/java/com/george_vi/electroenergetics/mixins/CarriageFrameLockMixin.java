package com.george_vi.electroenergetics.mixins;

import com.simibubi.create.content.contraptions.ContraptionHandler;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Stops a player from sinking through the floor of a moving train carriage.
 *
 * <h2>The bug, and the line that causes it</h2>
 *
 * <p>Standing on a moving train, the player slowly descends, passes through the carriage
 * floor, reaches the rails and drops off the train. Reported as "脱离座位站在列车没被甩也会缓慢
 * 下降高度（无视并穿过列车地板）直到碰到铁轨掉出列车", alongside walking on a train being
 * unusable - and the sinking is why walking is unusable, because a player who cannot stay on
 * the floor cannot walk on it either.
 *
 * <p>Create carries a standing rider by displacing them by the motion of the material point
 * under their feet. In the collider that displacement is applied here:
 *
 * <pre>
 *   ContraptionCollider.java:309-310
 *     entity.setPos(entityPosition.x + allowedMovement.x, entityPosition.y,
 *                   entityPosition.z + allowedMovement.z);
 * </pre>
 *
 * <p><b>X and Z are written. Y is not.</b> The vertical component of the carry is discarded,
 * so the carriage lifts the player horizontally but never holds them up. Gravity pulls them
 * down every tick and nothing ever puts them back, so the descent is monotonic and ends at the
 * rails. This is not a speed effect and not a stale-position effect: it happens on any moving
 * carriage, at any speed, and it is why the player sinks rather than being thrown.
 *
 * <h2>Why three earlier attempts of mine failed</h2>
 *
 * <p>They all tried to police the individual places a stale position gets written - first
 * through the damage path, then through the two readers of the dismount tag - and a log
 * eventually disproved all of them. They were looking in the wrong place: nothing was writing
 * a wrong vertical position, and the reason is that nothing was writing a vertical position
 * at all.
 *
 * <h2>What this does</h2>
 *
 * <p>Corrects Y, and only Y. The player's own height within the carriage is tracked in the
 * carriage's LOCAL frame - the frame's Y axis is normal to the carriage floor, since Create
 * rotates the contraption to follow the track - and the world position is corrected along that
 * axis whenever they have dropped below the floor they were standing on.
 *
 * <h2>Why horizontal is deliberately left alone</h2>
 *
 * <p>Because it already works, and the reason is the same line quoted above: X and Z ARE
 * written, by the collider, every tick, from {@code getContactPointMotion} - which is the
 * carriage's own motion at the contact point, so a rider who is not pressing anything is
 * already held at rest relative to their carriage. Nothing else moves a client player
 * horizontally when there is no input, because gravity is vertical and air drag has no
 * horizontal velocity to act on. Adding a second writer here would fight Create's carry
 * rather than help it, so the horizontal component of the player's position is passed through
 * untouched and this class can only ever change their height.
 *
 * <p>That is also what makes this safe: the correction is purely vertical, so it cannot
 * conflict with Create's horizontal carry, cannot disagree with the server about where the
 * player is horizontally, and cannot interfere with walking.
 *
 * <h2>Why a jump is not mistaken for a step</h2>
 *
 * <p>The floor is only re-learnt when the player is genuinely standing on something new, and a
 * jump is excluded three ways, because re-learning the floor at the top of a jump arc would
 * leave the player standing on air when they came down:
 *
 * <ul>
 *   <li>while the jump key is held, the floor is left alone entirely;</li>
 *   <li>a rise above {@link #STEP_UP} latches {@code airborne}, and while latched the floor is
 *       never re-learnt - which covers a jump whose key was released early, and any fall;</li>
 *   <li>the latch clears only when they are back down at the floor.</li>
 * </ul>
 *
 * <p>A step up is at most vanilla's 0.6 and a jump's first tick is 0.42, so height alone
 * cannot separate them - which is exactly why the jump key and the airborne latch are needed
 * rather than a bare threshold.
 *
 * <h2>Scope and cost</h2>
 *
 * <p>CLIENT ONLY, and in the client section, because that is where a rider's position is
 * authoritative: the server never carries a player at all ({@code ContraptionCollider} skips
 * {@code PlayerType.SERVER}) and learns the result from the client's movement packets, exactly
 * as it already does for Create's own carry.
 *
 * <p>Limited to {@link CarriageContraptionEntity} - train carriages - so nothing about
 * Create's other contraptions changes.
 *
 * <p>It does nothing unless the local player is standing inside a carriage's footprint, so a
 * player on foot or on a platform is untouched. No packets, no allocation in the steady state,
 * and a handful of arithmetic per tick.
 */
@Mixin(ContraptionHandler.class)
public class CarriageFrameLockMixin {

    @Unique
    private static final Logger electroEnergetics$LOG = LoggerFactory.getLogger("CEE-DIAG");

    /** How far below the floor the player may drift before the height is restored. */
    @Unique
    private static final double SINK_TOLERANCE = 0.05d;

    /**
     * Rise within one tick above which the player counts as airborne rather than stepping up.
     *
     * <p>Vanilla's largest step is 0.6 blocks and a jump rises about 0.42 in its first tick,
     * so this sits above a step and the airborne latch below catches the jump. See the class
     * comment for why this alone is not enough and the jump key is consulted as well.
     */
    @Unique
    private static final double STEP_UP = 0.7d;

    /**
     * How far outside a carriage's own box the player may be and still be held to it.
     *
     * <p>More generous than the two blocks Create's collider uses, so a rider who has already
     * been let go of can be caught again rather than being lost for good.
     */
    @Unique
    private static final double REACH = 2.5d;

    /**
     * Ticks the player may be outside every carriage's box before the hold is dropped.
     *
     * <p>Hysteresis, and it is load-bearing rather than tidiness: at a box edge the containment
     * test can alternate between ticks, and acquiring and releasing each time would make the
     * corrected height jitter - turning this fix into a new bug of the kind it removes.
     */
    @Unique
    private static final int RELEASE_GRACE_TICKS = 10;

    /** The carriage whose frame the local player is being held in, or {@code null}. */
    @Unique
    private static CarriageContraptionEntity electroEnergetics$frame;

    /** The frame-local height of the floor the player is standing on. */
    @Unique
    private static double electroEnergetics$floorY;

    /**
     * Set when the player has risen further than a step; cleared when they are back at the
     * floor. While set, the floor is never re-learnt, so a jump cannot move it.
     */
    @Unique
    private static boolean electroEnergetics$airborne;

    /** Ticks the hold has lasted, for the diagnostics and the release grace period. */
    @Unique
    private static int electroEnergetics$heldTicks;

    /** Ticks the player has been outside every carriage while still being held. */
    @Unique
    private static int electroEnergetics$outsideTicks;

    @Inject(method = "tick", at = @At("TAIL"), remap = false)
    private static void electroEnergetics$holdAboveFloor(Level world, CallbackInfo ci) {
        try {
            if (!world.isClientSide())
                return;

            LocalPlayer player = Minecraft.getInstance().player;
            if (player == null || player.isPassenger() || player.isSpectator()) {
                electroEnergetics$release();
                return;
            }

            CarriageContraptionEntity carriage = electroEnergetics$carriageUnder(world, player);
            if (carriage == null) {
                // Held over a grace period so an edge-of-box flicker cannot make the height
                // jitter between corrected and uncorrected.
                if (electroEnergetics$frame != null
                        && ++electroEnergetics$outsideTicks > RELEASE_GRACE_TICKS)
                    electroEnergetics$release();
                return;
            }
            electroEnergetics$outsideTicks = 0;

            Vec3 nowLocal = carriage.toLocalVector(player.position(), 0);

            if (electroEnergetics$frame != carriage) {
                // First tick on this carriage: adopt the height they are already standing at
                // rather than assuming one, so this works on any carriage, at any point on it,
                // without knowing its geometry.
                electroEnergetics$frame = carriage;
                electroEnergetics$floorY = nowLocal.y;
                electroEnergetics$airborne = false;
                electroEnergetics$heldTicks = 0;
                electroEnergetics$outsideTicks = 0;
                electroEnergetics$LOG.info("carriage floor acquired at localY={}",
                        String.format("%.2f", nowLocal.y));
                return;
            }

            electroEnergetics$heldTicks++;

            boolean jumping = player.input != null && player.input.jumping;
            double rise = nowLocal.y - electroEnergetics$floorY;

            // Decide the height to hold.
            double targetLocalY;
            if (rise < -SINK_TOLERANCE) {
                targetLocalY = electroEnergetics$floorY;   // sank: restore the floor
                electroEnergetics$airborne = false;
                if (electroEnergetics$heldTicks % 20 == 0)
                    electroEnergetics$LOG.info("floor restored, {} below it",
                            String.format("%.2f", -rise));
            } else if (rise > STEP_UP) {
                targetLocalY = nowLocal.y;                 // clearly airborne
                electroEnergetics$airborne = true;
            } else if (jumping || electroEnergetics$airborne) {
                targetLocalY = nowLocal.y;                 // ascending, or coming back down
            } else {
                electroEnergetics$floorY = nowLocal.y;     // a step, or standing level
                targetLocalY = electroEnergetics$floorY;
            }

            if (Math.abs(targetLocalY - nowLocal.y) < 1.0e-4d)
                return;   // already where they should be: touch nothing

            // Rebuilt from the ORIGINAL local vector with only Y replaced, so the correction is
            // purely along the carriage's own vertical axis and the horizontal position is
            // carried through exactly as Create left it.
            Vec3 corrected = carriage.toGlobalVector(
                    new Vec3(nowLocal.x, targetLocalY, nowLocal.z), 0);
            player.setPos(corrected.x, corrected.y, corrected.z);

            // On the floor as far as vanilla is concerned, so a corrected height cannot accrue
            // fall damage on the way back up.
            player.fallDistance = 0;
        } catch (Throwable ignored) {
            // A carry must never be the thing that breaks the client.
            electroEnergetics$release();
        }
    }

    /**
     * The carriage whose floor the player is standing on, or {@code null}.
     *
     * <p>Tested against each carriage's own box so the answer follows the geometry rather than
     * any stored link - which matters because a player who has already slipped has no link left
     * to test. The nearest is chosen when boxes overlap at a coupling, so the answer stays
     * stable instead of flickering between two carriages.
     */
    @Unique
    private static CarriageContraptionEntity electroEnergetics$carriageUnder(Level world,
                                                                            LocalPlayer player) {
        List<CarriageContraptionEntity> nearby = world.getEntitiesOfClass(
                CarriageContraptionEntity.class,
                player.getBoundingBox().inflate(REACH, 3d, REACH));

        CarriageContraptionEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (CarriageContraptionEntity candidate : nearby) {
            if (!candidate.getBoundingBox().inflate(0.5d).contains(player.position()))
                continue;
            double distance = candidate.position().distanceToSqr(player.position());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    @Unique
    private static void electroEnergetics$release() {
        if (electroEnergetics$frame != null && electroEnergetics$heldTicks > 20)
            electroEnergetics$LOG.info("carriage floor released after {} ticks",
                    electroEnergetics$heldTicks);
        electroEnergetics$frame = null;
        electroEnergetics$airborne = false;
        electroEnergetics$outsideTicks = 0;
    }
}
