package com.george_vi.electroenergetics.mixins;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.ContraptionCollider;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang3.mutable.MutableInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps a rider standing on a moving train carriage from sinking through its floor.
 *
 * <h2>Six hypotheses, five disproved by measurement</h2>
 *
 * <p>Each of these looked obvious in the source, and each was wrong. They are recorded because
 * the reasoning is what misled me, not the code:
 *
 * <ul>
 *   <li><b>The capture window is too small - DISPROVED.</b> {@code BEYOND WINDOW} appeared zero
 *       times in every log.</li>
 *   <li><b>The damage path launches them - NOT OBSERVED.</b> The rider's delta movement stayed at
 *       gravity throughout.</li>
 *   <li><b>Two carriages carry one rider - real but rare.</b> Two occurrences.</li>
 *   <li><b>The floor snap lifts them - DISPROVED.</b> It never ran: {@code snapOuterCalls = 0}.</li>
 *   <li><b>Restoring the vertical term :309-310 discards is the fix - DISPROVED.</b> That term
 *       measured {@code surfaceYavg = 0.0000} across 763 carried ticks, so the redirect was
 *       adding zero. It is still applied below because it is correct on a gradient, but it is
 *       not the sink.</li>
 *   <li><b>The collider pass lowers the rider - DISPROVED.</b> {@code sank = 1 / 763 ticks},
 *       {@code rose = 14 / 763}: the pass does essentially nothing vertically.</li>
 * </ul>
 *
 * <p>What the same run established:
 *
 * <pre>
 *   surfaceReg = 100%        the carriage's surface branch runs every tick
 *   relY minimum drifted +1.025 to -1.000 and stayed there
 *   player Y settled at exactly -60.000 - an integer, a world block face
 * </pre>
 *
 * <p>So nothing in the collider causes the sink; it is what the collider fails to do. The rider's
 * own gravity displaces them downward each tick and nothing restores them, because a carriage
 * floor is not a world block and vanilla's collision therefore never supplies the support that
 * would normally stop it.
 *
 * <h2>The fix</h2>
 *
 * <p>While the carriage is genuinely supporting the rider, their expected height is advanced by
 * the contact point's vertical motion each tick and restored whenever they have dropped below it.
 *
 * <p>The discriminator is exact, and it is what two earlier attempts got wrong. {@code
 * ContraptionCollider:295} calls {@code registerColliding}, which stores a fresh
 * {@code MutableInt} at zero, and {@code AbstractContraptionEntity} increments those counters on
 * its own tick and removes entries above three. Since entity ticks run before
 * {@code ClientTickEvent.Post}, a value of zero AFTER the pass means this carriage registered the
 * rider DURING this pass - they are standing on it right now. A rider who is jumping, falling,
 * or in the aisle reads as not in contact and is left alone.
 *
 * <p>The first attempt used "the carriage carried them horizontally" instead, which remains true
 * throughout a jump and would have held the rider floating at the apex of their arc. This cannot,
 * because a jumping rider is not in contact.
 *
 * <p>Ownership is tracked so sibling carriages do not fight: a carriage that is not the current
 * holder ignores its own lack of contact rather than clearing the hold, and a carriage reporting
 * contact while another already holds does not steal it. Only the holding carriage releasing
 * contact ends the hold.
 *
 * <h2>Why the restore is bounded</h2>
 *
 * <p>{@link #MAX_RESTORE} is far above the measured per-tick sink (about 0.03) and far below any
 * genuine change of level. Beyond it the rider is taken to have moved to a different height -
 * walked down a step, crossed to a lower deck - and their position becomes the new baseline
 * instead of being hauled back. Only a RISE is ever corrected, never a fall below, so nothing
 * here can push a rider down or hold up one who should be falling.
 *
 * <h2>Also kept</h2>
 *
 * <p>{@code :309-310} writes X and Z but passes {@code entityPosition.y} through, dropping the
 * {@code allowedMovement.y} computed one line earlier at {@code :308}, whereas the hard-collision
 * branch at {@code :284-285} applies its own. That omission is real and is restored here, matching
 * Create's own line, so a rider on a gradient travels with the floor.
 *
 * <h2>Scope and cost</h2>
 *
 * <p>CLIENT ONLY, where a rider's position is authoritative: the server skips players outright
 * ({@code ContraptionCollider:120-121}, {@code PlayerType.SERVER}) and learns the result from the
 * client's movement packets, as it already does for Create's own carry.
 *
 * <p>Confined to {@link CarriageContraptionEntity}, so no other contraption changes. No packets,
 * no allocation unless the summary line is due.
 */
@Mixin(ContraptionCollider.class)
public class CarriageVerticalCarryMixin {

    @Unique
    private static final Logger electroEnergetics$LOG = LoggerFactory.getLogger("CEE-CARRY");

    /** Below this carriage speed nothing is logged, so ordinary worlds stay silent. */
    @Unique
    private static final double MIN_INTERESTING_MOTION = 0.5d;

    /**
     * Largest downward deficit treated as missed support and restored, in Blocks.
     *
     * <p>The measured sink is about 0.03 per tick, so this covers several ticks of it while
     * staying far below any real change of level.
     */
    @Unique
    private static final double MAX_RESTORE = 0.30d;

    /** Below this, the position is left completely alone. */
    @Unique
    private static final double EPSILON = 1.0e-4d;

    /** Summary cadence and hard line cap, so a long session cannot fill the log. */
    @Unique
    private static final int SUMMARY_EVERY = 100;
    @Unique
    private static final int MAX_SUMMARY_LINES = 200;

    /** Ticks before a remembered height is discarded rather than trusted. */
    @Unique
    private static final int HOLD_EXPIRY_TICKS = 5;

    /** The carriage currently being processed, so the redirects can be scoped to it. */
    @Unique
    private static AbstractContraptionEntity electroEnergetics$current;

    /** What {@code collide(contactPointMotion, entity)} returned in the surface branch. */
    @Unique
    private static Vec3 electroEnergetics$surfaceAllowed = Vec3.ZERO;

    /** What {@code collide(totalResponse, entity)} returned at {@code :283}. */
    @Unique
    private static Vec3 electroEnergetics$hardAllowed = Vec3.ZERO;

    /** The rider's position before this carriage's pass. */
    @Unique
    private static Vec3 electroEnergetics$beforePos;

    /** This carriage's own travel this tick. */
    @Unique
    private static double electroEnergetics$motion;

    // ---- the height held across ticks ----

    /** The rider's expected height while the holding carriage supports them, else NaN. */
    @Unique
    private static double electroEnergetics$holdY = Double.NaN;

    /** The carriage doing the holding, and the tick that ended. */
    @Unique
    private static int electroEnergetics$holdOwner = -1;
    @Unique
    private static long electroEnergetics$holdTick = Long.MIN_VALUE;

    // ---- verification ----

    @Unique
    private static int electroEnergetics$lines;
    @Unique
    private static long electroEnergetics$lastSummary = Long.MIN_VALUE;
    @Unique
    private static int electroEnergetics$redirectCalls;
    @Unique
    private static int electroEnergetics$appliedCalls;
    @Unique
    private static int electroEnergetics$capturedCalls;
    @Unique
    private static int electroEnergetics$onSurfaceTicks;
    @Unique
    private static int electroEnergetics$restoredTicks;
    @Unique
    private static double electroEnergetics$restoreSum;
    @Unique
    private static int electroEnergetics$rebasedTicks;
    @Unique
    private static int electroEnergetics$releasedTicks;
    @Unique
    private static double electroEnergetics$surfaceYSum;
    @Unique
    private static int electroEnergetics$positiveSurfaceY;
    @Unique
    private static double electroEnergetics$hardYSum;
    @Unique
    private static double electroEnergetics$relYMin = Double.MAX_VALUE;
    @Unique
    private static double electroEnergetics$relYMax = -Double.MAX_VALUE;
    @Unique
    private static double electroEnergetics$lastCarriageY;

    // ---- fallback carry: supplied when Create's own detection misses the rider ----

    /** Game tick of the last fallback supply, so only one carriage supplies per tick. */
    @Unique
    private static long electroEnergetics$suppliedTick = Long.MIN_VALUE;
    @Unique
    private static int electroEnergetics$suppliedTicks;
    @Unique
    private static double electroEnergetics$suppliedHSum;
    /** Ticks Create carried them itself, for comparison against the fallback. */
    @Unique
    private static int electroEnergetics$carryByCreateTicks;

    /** Scopes the redirects to train carriages and records the rider's starting position. */
    @Inject(method = "collideEntities", at = @At("HEAD"), remap = false)
    private static void electroEnergetics$enter(AbstractContraptionEntity contraption,
                                                CallbackInfo ci) {
        electroEnergetics$beforePos = null;
        electroEnergetics$current = null;
        try {
            if (!contraption.level().isClientSide())
                return;
            if (!(contraption instanceof CarriageContraptionEntity))
                return;

            LocalPlayer player = Minecraft.getInstance().player;
            if (player == null || player.isPassenger() || player.isSpectator())
                return;

            Vec3 motion = contraption.position().subtract(contraption.getPrevPositionVec());
            electroEnergetics$motion = motion.length();
            if (electroEnergetics$motion < MIN_INTERESTING_MOTION)
                return;

            // Exactly Create's own selection box, ContraptionCollider:100-101.
            if (!contraption.getBoundingBox().inflate(2d).expandTowards(0, 32, 0)
                    .contains(player.position()))
                return;

            electroEnergetics$current = contraption;
            electroEnergetics$beforePos = player.position();
        } catch (Throwable ignored) {
            electroEnergetics$current = null;
            electroEnergetics$beforePos = null;
        }
    }

    /** Captures the surface branch's collision result at {@code :308}. Ordinal 1 of 2. */
    @WrapOperation(method = "collideEntities",
            at = @At(value = "INVOKE",
                    target = "Lcom/simibubi/create/content/contraptions/ContraptionCollider;"
                            + "collide(Lnet/minecraft/world/phys/Vec3;"
                            + "Lnet/minecraft/world/entity/Entity;)"
                            + "Lnet/minecraft/world/phys/Vec3;",
                    ordinal = 1),
            remap = false)
    private static Vec3 electroEnergetics$captureSurfaceAllowed(Vec3 motion, Entity entity,
                                                               Operation<Vec3> original) {
        electroEnergetics$capturedCalls++;
        Vec3 allowed = original.call(motion, entity);
        electroEnergetics$surfaceAllowed = allowed;
        return allowed;
    }

    /** Captures the hard-collision branch's result at {@code :283}. Ordinal 0 of 2. */
    @WrapOperation(method = "collideEntities",
            at = @At(value = "INVOKE",
                    target = "Lcom/simibubi/create/content/contraptions/ContraptionCollider;"
                            + "collide(Lnet/minecraft/world/phys/Vec3;"
                            + "Lnet/minecraft/world/entity/Entity;)"
                            + "Lnet/minecraft/world/phys/Vec3;",
                    ordinal = 0),
            remap = false)
    private static Vec3 electroEnergetics$captureHardAllowed(Vec3 motion, Entity entity,
                                                             Operation<Vec3> original) {
        Vec3 allowed = original.call(motion, entity);
        electroEnergetics$hardAllowed = allowed;
        return allowed;
    }

    /**
     * Applies the vertical component the surface branch drops, matching {@code :284-285}.
     *
     * <p>Ordinal 1 of {@code Entity.setPos}: the shipped bytecode has exactly two, at 1546 (the
     * hard branch, which already adds its own {@code .y}) and 1782 (the surface branch).
     */
    @Redirect(method = "collideEntities",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;setPos(DDD)V",
                    ordinal = 1),
            remap = false)
    private static void electroEnergetics$applyVerticalCarry(Entity entity,
                                                            double x, double y, double z) {
        electroEnergetics$redirectCalls++;
        if (electroEnergetics$current == null) {
            entity.setPos(x, y, z);
            return;
        }
        electroEnergetics$appliedCalls++;
        entity.setPos(x, y + electroEnergetics$surfaceAllowed.y, z);
    }

    @Inject(method = "collideEntities", at = @At("RETURN"), remap = false)
    private static void electroEnergetics$holdHeight(AbstractContraptionEntity contraption,
                                                     CallbackInfo ci) {
        try {
            AbstractContraptionEntity scoped = electroEnergetics$current;
            Vec3 before = electroEnergetics$beforePos;
            electroEnergetics$current = null;
            electroEnergetics$beforePos = null;
            if (scoped == null || before == null)
                return;

            LocalPlayer player = Minecraft.getInstance().player;
            if (player == null || player.isPassenger() || player.isSpectator())
                return;

            long now = contraption.level().getGameTime();
            double carriageY = contraption.getY();

            // Contact registered DURING THIS PASS. registerColliding stores a fresh counter at
            // zero and the entity's own tick increments these, so zero now means the carriage
            // just registered them - i.e. they are standing on it. Not membership, which would
            // still match a rider who jumped a tick ago.
            MutableInt contact = contraption.collidingEntities.get(player);
            boolean onSurface = contact != null && contact.intValue() == 0;
            boolean isHolder = contraption.getId() == electroEnergetics$holdOwner;

            if (!onSurface) {
                // Create's carry lives inside `if (surfaceCollision)` at :294, and the diagnostic
                // shows that detection collapsing as speed rises: it fired on 22% of passes at
                // 16 m/s but only 2% at 71 m/s, with `hardCollision` never true at all. So :280
                // bails and the rider is carried by nobody - which is the sideways ejection.
                // The carry Create would have applied is therefore applied here.
                electroEnergetics$supplyMissedCarry(contraption, player, before);

                // Only the carriage that was holding may end the hold. A sibling carriage's
                // report of no contact is meaningless - the rider is simply standing on the
                // other one - and letting it clear the hold would break the state every tick.
                if (isHolder && !Double.isNaN(electroEnergetics$holdY)) {
                    electroEnergetics$holdY = Double.NaN;
                    electroEnergetics$holdOwner = -1;
                    electroEnergetics$releasedTicks++;
                }
                electroEnergetics$recordRel(contraption, player, carriageY);
                electroEnergetics$maybeSummary(now, player, carriageY);
                return;
            }

            // Create carried them, so no fallback is needed this tick.
            electroEnergetics$carryByCreateTicks++;

            // Another carriage is already holding and its hold is still fresh. Leave it alone
            // rather than fighting over the same rider.
            if (!isHolder && !Double.isNaN(electroEnergetics$holdY)
                    && now - electroEnergetics$holdTick <= HOLD_EXPIRY_TICKS) {
                electroEnergetics$recordRel(contraption, player, carriageY);
                electroEnergetics$maybeSummary(now, player, carriageY);
                return;
            }

            electroEnergetics$onSurfaceTicks++;

            // The contact point is where the floor went this tick, so the held height advances by
            // it and tracks the carriage rather than a fixed world Y.
            Vec3 carry = contraption.getContactPointMotion(player.position());

            double expected;
            if (isHolder && !Double.isNaN(electroEnergetics$holdY)
                    && now - electroEnergetics$holdTick <= HOLD_EXPIRY_TICKS) {
                expected = electroEnergetics$holdY + carry.y;
            } else {
                // Freshly stepped onto this carriage: adopt where they are, so this needs to know
                // nothing about the carriage's geometry.
                expected = player.getY();
            }

            double deficit = expected - player.getY();

            if (deficit > MAX_RESTORE) {
                // Far below the held height: a real change of level. Take their position as the
                // truth instead of hauling them back up.
                expected = player.getY();
                electroEnergetics$rebasedTicks++;
            } else if (deficit > EPSILON) {
                player.setPos(player.getX(), expected, player.getZ());
                electroEnergetics$restoredTicks++;
                electroEnergetics$restoreSum += deficit;
            } else if (deficit < -EPSILON) {
                // Above the held height: stepping up, or being lifted by the carriage. Follow
                // them - this is what keeps a step up working and never pulls anyone down.
                expected = player.getY();
            }

            electroEnergetics$holdY = expected;
            electroEnergetics$holdOwner = contraption.getId();
            electroEnergetics$holdTick = now;

            // Travelling with the floor means no downward speed accumulates, so a supported rider
            // is not falling as far as vanilla is concerned.
            Vec3 own = player.getDeltaMovement();
            if (own.y < carry.y)
                player.setDeltaMovement(own.x, carry.y, own.z);
            player.fallDistance = 0;

            electroEnergetics$surfaceYSum += electroEnergetics$surfaceAllowed.y;
            if (electroEnergetics$surfaceAllowed.y > EPSILON)
                electroEnergetics$positiveSurfaceY++;
            electroEnergetics$hardYSum += electroEnergetics$hardAllowed.y;

            electroEnergetics$recordRel(contraption, player, carriageY);
            electroEnergetics$maybeSummary(now, player, carriageY);
        } catch (Throwable ignored) {
            // A carry correction must never be the thing that breaks the client.
            electroEnergetics$current = null;
            electroEnergetics$beforePos = null;
        }
    }

    /**
     * Applies the carry Create would have applied, when its own detection missed the rider.
     *
     * <p>Measured: {@code surfaceCollision} - the only branch that carries a standing rider, since
     * {@code hardCollision} is never true for one - fired on 22% of passes at 16 m/s but only 2%
     * at 71 m/s. {@code :280} then bails, and the rider is carried by nobody, which is what throws
     * them out of the side of the carriage at speed.
     *
     * <p>The motion used is {@code getContactPointMotion}, the same call Create makes at
     * {@code :307}. It is exactly the displacement of the material point under the rider's feet,
     * so applying it puts them where Create would have put them - no new mechanism, and nothing
     * invented about the carriage's geometry.
     *
     * <p>Three guards, each preventing a specific way this could do harm:
     *
     * <ol>
     *   <li><b>The rider must be inside the carriage's OWN box</b>, not the inflated selection
     *       window. A bystander beside the track is inside the window, which is why the window
     *       exists, but is not inside the carriage and must never be carried by it.</li>
     *   <li><b>Only one carriage may supply per tick.</b> Adjacent carriages' boxes overlap at a
     *       coupling, so without this the rider could be carried twice in one tick and move at
     *       double speed - which is a worse bug than the one being fixed.</li>
     *   <li><b>Nothing is supplied while Create is carrying them.</b> This runs only from the
     *       branch where Create reported no contact, so the two can never both apply.</li>
     * </ol>
     *
     * <p>It also refuses when the rider is already moving faster horizontally than the carriage,
     * so a player sprinting along a carriage or jumping off it is not held back.
     */
    @Unique
    private static void electroEnergetics$supplyMissedCarry(AbstractContraptionEntity contraption,
                                                            LocalPlayer player, Vec3 before) {
        try {
            if (electroEnergetics$motion < MIN_INTERESTING_MOTION)
                return;

            // Guard 1: inside the carriage itself, not merely inside the selection window.
            if (!contraption.getBoundingBox().inflate(0.3d).contains(player.position()))
                return;

            long now = contraption.level().getGameTime();

            // Guard 2: at most one carriage supplies per tick.
            if (electroEnergetics$suppliedTick == now)
                return;

            Vec3 carry = contraption.getContactPointMotion(player.position());
            double carryH = Math.hypot(carry.x, carry.z);
            if (carryH < 1.0e-4d)
                return;   // carriage is not moving horizontally; nothing to carry

            // Do not fight a rider who is already moving horizontally as fast as the carriage -
            // that is someone walking, sprinting, or stepping off, not someone being left behind.
            Vec3 own = player.getDeltaMovement();
            if (Math.hypot(own.x, own.z) >= carryH)
                return;

            electroEnergetics$suppliedTick = now;

            // The carry, as Create applies it: displace by the contact point's motion.
            player.setPos(player.getX() + carry.x, player.getY() + carry.y, player.getZ() + carry.z);
            // Carried, so not falling.
            if (own.y < carry.y)
                player.setDeltaMovement(own.x, carry.y, own.z);
            player.fallDistance = 0;

            electroEnergetics$suppliedTicks++;
            electroEnergetics$suppliedHSum += carryH;
        } catch (Throwable ignored) {
            // A carry correction must never be the thing that breaks the client.
        }
    }

    @Unique
    private static void electroEnergetics$recordRel(AbstractContraptionEntity contraption,
                                                   LocalPlayer player, double carriageY) {
        electroEnergetics$lastCarriageY = carriageY;
        double relY = player.getY() - carriageY;
        if (relY < electroEnergetics$relYMin) electroEnergetics$relYMin = relY;
        if (relY > electroEnergetics$relYMax) electroEnergetics$relYMax = relY;
    }

    @Unique
    private static void electroEnergetics$maybeSummary(long now, LocalPlayer player,
                                                       double carriageY) {
        // The sentinel is tested FIRST. As `now - Long.MIN_VALUE` this overflows to a large
        // negative number that is always below the interval, so the test below would return on
        // every tick and the summary could never be emitted at all - which is exactly what
        // silenced the first build of this class.
        if (electroEnergetics$lastSummary == Long.MIN_VALUE) {
            electroEnergetics$lastSummary = now;
            return;
        }
        if (now - electroEnergetics$lastSummary < SUMMARY_EVERY)
            return;
        electroEnergetics$lastSummary = now;
        if (electroEnergetics$lines++ >= MAX_SUMMARY_LINES)
            return;

        int t = electroEnergetics$onSurfaceTicks;
        electroEnergetics$LOG.info(
                "CARRY FIX onCarriage={} byCreate={} supplied={} suppliedHavg={} "
                        + "| restored={}/{} avgRestore={} rebased={} released={} "
                        + "| surfaceYavg={} positiveY={} hardYavg={} "
                        + "| redirect={} applied={} captured={} "
                        + "| relY=[{},{}] nowRelY={} carriageY={} playerY={} vy={} onGround={}",
                t, electroEnergetics$carryByCreateTicks, electroEnergetics$suppliedTicks,
                String.format("%.3f", electroEnergetics$suppliedTicks == 0 ? 0d
                        : electroEnergetics$suppliedHSum / electroEnergetics$suppliedTicks),
                electroEnergetics$restoredTicks, t,
                String.format("%.4f", electroEnergetics$restoredTicks == 0 ? 0d
                        : electroEnergetics$restoreSum / electroEnergetics$restoredTicks),
                electroEnergetics$rebasedTicks, electroEnergetics$releasedTicks,
                String.format("%.4f", t == 0 ? 0d : electroEnergetics$surfaceYSum / t),
                electroEnergetics$positiveSurfaceY,
                String.format("%.4f", t == 0 ? 0d : electroEnergetics$hardYSum / t),
                electroEnergetics$redirectCalls, electroEnergetics$appliedCalls,
                electroEnergetics$capturedCalls,
                String.format("%.3f", electroEnergetics$relYMin),
                String.format("%.3f", electroEnergetics$relYMax),
                String.format("%.3f", player.getY() - carriageY),
                String.format("%.3f", carriageY),
                String.format("%.3f", player.getY()),
                String.format("%.3f", player.getDeltaMovement().y),
                player.onGround());

        electroEnergetics$onSurfaceTicks = 0;
        electroEnergetics$carryByCreateTicks = 0;
        electroEnergetics$suppliedTicks = 0;
        electroEnergetics$suppliedHSum = 0;
        electroEnergetics$restoredTicks = 0;
        electroEnergetics$restoreSum = 0;
        electroEnergetics$rebasedTicks = 0;
        electroEnergetics$releasedTicks = 0;
        electroEnergetics$surfaceYSum = 0;
        electroEnergetics$positiveSurfaceY = 0;
        electroEnergetics$hardYSum = 0;
        electroEnergetics$redirectCalls = 0;
        electroEnergetics$appliedCalls = 0;
        electroEnergetics$capturedCalls = 0;
        electroEnergetics$relYMin = Double.MAX_VALUE;
        electroEnergetics$relYMax = -Double.MAX_VALUE;
    }
}
