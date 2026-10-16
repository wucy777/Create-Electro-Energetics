package com.george_vi.electroenergetics.mixins;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.ContraptionCollider;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Carries the client player at most ONCE per tick, however many carriages are touching them.
 *
 * <h2>The measurement this comes from</h2>
 *
 * <p>A flight recorder kept the rider's position every tick and dumped the last sixty ticks when a
 * single tick moved them much further than the carriage did. Both dumps agree, and both are
 * multiples of the carriage's own motion:
 *
 * <pre>
 *   dump 1   carriage moved 3.09 Blocks   rider moved 12.36   = 4.00 x
 *   dump 2   carriage moved 3.17 Blocks   rider moved  6.29   = 1.98 x
 * </pre>
 *
 * <p>The contact point motion recorded in those same samples was entirely normal - it equalled the
 * carriage's own motion. So nothing was miscomputed. The carry was simply applied more than once
 * in a tick, and the displacements added up.
 *
 * <h2>Why it is applied more than once</h2>
 *
 * <p>{@code collideEntities} guards its client-player carry with {@code skipClientPlayer}, but the
 * LocalVariableTable of the shipped jar shows that is a LOCAL VARIABLE of a single call:
 *
 * <pre>
 *   102    1842     8 skipClientPlayer   Z
 * </pre>
 *
 * <p>It can therefore only deduplicate within one carriage's pass, while
 * {@code ContraptionHandler.tick} calls {@code collideEntities} once per loaded contraption. A
 * rider inside the boxes of three carriages is carried by all three in the same tick.
 *
 * <p>What makes this bite hardest is the exact moment reported: a passenger is skipped outright by
 * {@code canCollideWith} ({@code AbstractContraptionEntity:849}, {@code e.getVehicle() != null}),
 * so the instant they stand up they become collidable to every carriage at once, and all of them
 * carry them in that single tick.
 *
 * <h2>Why a stock Create train is fine and ours is not</h2>
 *
 * <p>The error is {@code (n - 1) x carriageMotion}, so it scales with speed:
 *
 * <pre>
 *   Create's train ceiling, 28 m/s   1.40 Blocks per extra carriage per tick
 *   this mod,              60 m/s    3.00 Blocks per extra carriage per tick
 * </pre>
 *
 * <p>At the low figure the surplus is small enough to be absorbed by ordinary movement, which is
 * why a stock Create train does not obviously break. At the high figure it is three Blocks of
 * unwanted displacement per extra carriage, every tick - the rider thrown ahead of the train.
 *
 * <h2>The fix</h2>
 *
 * <p>Widen the guard from one call to one tick. The first carriage that reaches the carry in a
 * given tick applies exactly what Create computed for it; any later carriage in that same tick
 * keeps the rider's existing horizontal position and takes only the vertical term.
 *
 * <p>First-wins is chosen over picking a specific carriage deliberately. Any carriage that reaches
 * this call is one the rider is genuinely in contact with, and they all share the train's
 * translation - so whichever wins carries them correctly. Trying instead to pre-select "the
 * carriage under their feet" would risk selecting one whose surface branch does not fire on a
 * given tick, which would leave the rider with NO carry at all rather than one.
 *
 * <h2>Why this is the safe direction</h2>
 *
 * <p>It can only ever REMOVE duplicated displacement, never add any. The winning carriage still
 * applies exactly Create's own value. So the worst case from a mistake here is a rider sitting
 * slightly still for a tick, not one being launched - the failure is bounded by the bug it fixes.
 *
 * <h2>Scope</h2>
 *
 * <p>CLIENT ONLY, and only while a {@link CarriageContraptionEntity} is the contraption being
 * processed. Other contraptions - elevators, bearings, pistons - take the unmodified path, so
 * nothing outside trains changes. The server never reaches this code, as it skips players outright
 * ({@code ContraptionCollider:120-121}), so the client stays authoritative for its own position
 * exactly as it already is for Create's carry, and there is no desync risk.
 *
 * <p>Only X and Z are affected. The vertical term is passed through untouched, because the
 * duplication measured is a multiple of the carriage's horizontal motion.
 */
@Mixin(ContraptionCollider.class)
public class CarriageCarryOncePerTickMixin {

    @Unique
    private static final Logger electroEnergetics$LOG = LoggerFactory.getLogger("CEE-CARRY");

    @Unique
    private static final int SUMMARY_EVERY = 200;
    @Unique
    private static final int MAX_LINES = 60;

    /** Below this the mixin is entirely inert, so a stationary train costs nothing. */
    @Unique
    private static final double MIN_MOTION = 0.5d;

    /**
     * Whether this pass is one this mixin is responsible for.
     *
     * <p>Kept separate from {@link #electroEnergetics$allowed} so that every other kind of
     * contraption takes Create's unmodified path: when this is false the redirect simply performs
     * Create's own call.
     */
    @Unique
    private static boolean electroEnergetics$active;

    /** Whether THIS pass may apply the horizontal carry, or must defer to one that already did. */
    @Unique
    private static boolean electroEnergetics$allowed;

    /** The tick on which a carriage last applied the carry. */
    @Unique
    private static long electroEnergetics$carriedTick = Long.MIN_VALUE;

    // ---- verification ----

    @Unique
    private static int electroEnergetics$lines;
    @Unique
    private static long electroEnergetics$lastSummary = Long.MIN_VALUE;
    @Unique
    private static int electroEnergetics$carried;
    @Unique
    private static int electroEnergetics$suppressed;
    @Unique
    private static double electroEnergetics$suppressedSum;

    /**
     * Decides, once per carriage pass, whether this carriage may carry, and marks the mixin active.
     */
    @Inject(method = "collideEntities", at = @At("HEAD"), remap = false)
    private static void electroEnergetics$enter(AbstractContraptionEntity contraption,
                                                CallbackInfo ci) {
        electroEnergetics$active = false;
        electroEnergetics$allowed = false;
        try {
            if (!contraption.level().isClientSide())
                return;
            if (!(contraption instanceof CarriageContraptionEntity))
                return;

            LocalPlayer player = Minecraft.getInstance().player;
            if (player == null || player.isPassenger() || player.isSpectator())
                return;

            Vec3 motion = contraption.position().subtract(contraption.getPrevPositionVec());
            if (motion.length() < MIN_MOTION)
                return;

            // From here on this mixin owns the decision for this pass.
            electroEnergetics$active = true;

            // Only the first carriage to carry them in this tick does so; the rest defer. Without
            // this, every carriage whose surface branch fires adds another full carriage-length of
            // displacement in the same tick, which is what threw the rider ahead of the train.
            long tick = contraption.level().getGameTime();
            electroEnergetics$allowed = electroEnergetics$carriedTick != tick;
        } catch (Throwable ignored) {
            electroEnergetics$active = false;
            electroEnergetics$allowed = false;
        }
    }

    /**
     * The surface branch's position write, {@code ContraptionCollider:309}.
     *
     * <p>Ordinal 1 of {@code Entity.setPos}. The shipped jar contains exactly two in this method,
     * at bytecode offsets 1546 (the hard branch, which applies its own vertical term) and 1782
     * (the surface branch, which is the carry). Confirmed with javap against the installed jar
     * rather than inferred from line numbers.
     */
    @Redirect(method = "collideEntities",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;setPos(DDD)V",
                    ordinal = 1),
            remap = false)
    private static void electroEnergetics$carryOncePerTick(Entity entity,
                                                          double x, double y, double z) {
        try {
            if (!electroEnergetics$active || electroEnergetics$allowed) {
                // Either not our business, or this is the one carriage allowed to carry: apply
                // Create's own value unchanged.
                if (electroEnergetics$active) {
                    electroEnergetics$carriedTick = entity.level().getGameTime();
                    electroEnergetics$carried++;
                    electroEnergetics$maybeSummary(electroEnergetics$carriedTick);
                }
                entity.setPos(x, y, z);
                return;
            }

            // A carriage already carried them this tick. Adding this carriage's motion as well is
            // exactly the accumulation that threw them ahead of the train, so keep X and Z where
            // the first carriage left them and take only the vertical term.
            double surplus = Math.hypot(x - entity.getX(), z - entity.getZ());
            if (surplus > 1.0e-4d) {
                electroEnergetics$suppressed++;
                electroEnergetics$suppressedSum += surplus;
            }
            entity.setPos(entity.getX(), y, entity.getZ());
            electroEnergetics$maybeSummary(entity.level().getGameTime());
        } catch (Throwable ignored) {
            // A carry correction must never be the thing that breaks the client.
            entity.setPos(x, y, z);
        }
    }

    @Unique
    private static void electroEnergetics$maybeSummary(long tick) {
        // The sentinel is tested FIRST. Written as `tick - Long.MIN_VALUE` this overflows to a
        // negative number that is always below the interval, so the guard would return forever and
        // the summary could never print - a mistake that already cost a test run to find.
        if (electroEnergetics$lastSummary == Long.MIN_VALUE) {
            electroEnergetics$lastSummary = tick;
            return;
        }
        if (tick - electroEnergetics$lastSummary < SUMMARY_EVERY)
            return;
        electroEnergetics$lastSummary = tick;
        if (electroEnergetics$lines++ >= MAX_LINES)
            return;

        electroEnergetics$LOG.info(
                "CARRY ONCE carried={} suppressed={} suppressedAvg={}",
                electroEnergetics$carried, electroEnergetics$suppressed,
                String.format("%.3f", electroEnergetics$suppressed == 0 ? 0d
                        : electroEnergetics$suppressedSum / electroEnergetics$suppressed));

        electroEnergetics$carried = 0;
        electroEnergetics$suppressed = 0;
        electroEnergetics$suppressedSum = 0;
    }
}
