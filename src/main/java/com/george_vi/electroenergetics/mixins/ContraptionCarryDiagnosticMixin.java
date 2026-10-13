package com.george_vi.electroenergetics.mixins;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.ContraptionCollider;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.foundation.collision.CollisionList;
import com.simibubi.create.foundation.collision.ContinuousOBBCollider;
import com.simibubi.create.foundation.collision.OrientedBB;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang3.mutable.MutableInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ONE comprehensive instrumentation pass over the whole vertical path of the rider carry, so a
 * single play session answers every remaining question instead of one build per hypothesis.
 *
 * <h2>Why this class exists</h2>
 *
 * <p>I changed one thing, waited for CI, asked for a manual play session, read a log, changed one
 * thing - five times over, and was wrong four of them. Every iteration costs a full build plus a
 * human test run, which is an absurd price for one bit of information. The user's objection is
 * correct and this is the correction: instrument every decision point at once, so that whatever
 * the answer turns out to be, the log already contains it.
 *
 * <h2>Already settled - not instrumented again</h2>
 *
 * <pre>
 *   horizontal carry is exact                     carried/motion = 0.993 - 1.000
 *   the collider barely moves the rider in Y      sank = 1/763, rose = 14/763
 *   the term :309-310 discards is always zero     surfaceYavg = 0.0000, positiveY = 0/763
 *   the floor snap never runs                     snapOuterCalls = 0
 * </pre>
 *
 * <h2>What this records, and the question each field answers</h2>
 *
 * <p><b>1. The solver's raw verdict.</b> {@code ContinuousOBBCollider.collideMany} returns a
 * {@code CollisionResponse} holding {@code surfaceCollision}, {@code temporalResponse},
 * {@code collisionResponse} and {@code normal}. {@code ContraptionCollider} derives
 * {@code hardCollision} from whether the response is non-zero and {@code temporalCollision} from
 * whether {@code temporalResponse} differs from 1, so capturing the raw response gives all three
 * branch flags without touching a local variable. This is the ground truth, taken before any of
 * {@code ContraptionCollider}'s own transformations.
 *
 * <p>The header comment on {@code collideEntities} states plainly that surface collision
 * detection "isn't very accurate", so the first question is whether a standing rider is being
 * classified as a surface contact at all - and if not, which classification they do get.
 *
 * <p><b>2. The motion the solver was given.</b> That argument is the entity's delta movement minus
 * the carriage's, rotated into carriage space. A standing rider's own gravity displacement is
 * therefore inside it, which makes this the exact per-tick sink the solver is being asked to
 * resolve. Whether it does is the whole question, and this records the number rather than my
 * assumption about it.
 *
 * <p><b>3. Which branches actually ran.</b> hard, temporal, surface and "bailed before applying
 * anything" are each counted per tick, so the combination a standing rider produces is visible
 * rather than deduced. That bail-out is {@code :280 if (!hardCollision && !surfaceCollision)
 * continue;}.
 *
 * <p><b>4. The outcome of the whole pass.</b> The rider's Y before and after, which measures
 * directly whether this pass sank them, held them, or did nothing - the one result that no
 * interpretation can argue with.
 *
 * <p><b>5. Contact and height.</b> Membership of {@code collidingEntities} at value zero means the
 * carriage registered the rider during this very pass, and the rider's height relative to the
 * carriage shows whether the floor is being held.
 *
 * <h2>Cost</h2>
 *
 * <p>Completely silent unless a client player is being carried by a train carriage faster than
 * 0.5 Blocks/tick, so ordinary worlds, standing players and every other contraption log nothing.
 * Output is one summary line per hundred ticks, hard capped. Two box tests and a few dozen
 * arithmetic operations per carriage per tick otherwise, with no allocation unless a line is due.
 */
@Mixin(ContraptionCollider.class)
public class ContraptionCarryDiagnosticMixin {

    @Unique
    private static final Logger electroEnergetics$LOG = LoggerFactory.getLogger("CEE-CARRY");

    @Unique
    private static final double MIN_INTERESTING_MOTION = 0.5d;
    @Unique
    private static final int SUMMARY_EVERY = 100;
    @Unique
    private static final int MAX_SUMMARY_LINES = 300;

    @Unique
    private static final Vec3 electroEnergetics$NONE = new Vec3(Double.NaN, Double.NaN, Double.NaN);

    // ---- per-pass state, written at HEAD, consumed at RETURN ----

    @Unique
    private static AbstractContraptionEntity electroEnergetics$current;
    @Unique
    private static Vec3 electroEnergetics$beforePos = electroEnergetics$NONE;
    @Unique
    private static double electroEnergetics$motion;
    @Unique
    private static double electroEnergetics$motionY;
    @Unique
    private static boolean electroEnergetics$sawResponse;
    @Unique
    private static boolean electroEnergetics$rSurface;
    @Unique
    private static double electroEnergetics$rTemporal = 1d;
    @Unique
    private static Vec3 electroEnergetics$rResponse = Vec3.ZERO;
    @Unique
    private static Vec3 electroEnergetics$rNormal = Vec3.ZERO;
    @Unique
    private static double electroEnergetics$rResponseY;
    @Unique
    private static Vec3 electroEnergetics$solverMotion = Vec3.ZERO;

    // ---- counters for the current window ----

    @Unique
    private static int electroEnergetics$lines;
    @Unique
    private static long electroEnergetics$lastSummary = Long.MIN_VALUE;
    @Unique
    private static int electroEnergetics$ticks;
    @Unique
    private static int electroEnergetics$solverCalls;
    @Unique
    private static int electroEnergetics$surfaceYes;
    @Unique
    private static int electroEnergetics$temporalYes;
    @Unique
    private static int electroEnergetics$hardYes;
    @Unique
    private static int electroEnergetics$bailed;
    @Unique
    private static int electroEnergetics$roseTicks;
    @Unique
    private static int electroEnergetics$sankTicks;
    @Unique
    private static double electroEnergetics$sankSum;
    @Unique
    private static double electroEnergetics$passDySum;
    @Unique
    private static double electroEnergetics$solverMotionYSum;
    @Unique
    private static double electroEnergetics$responseYSum;
    @Unique
    private static int electroEnergetics$inContactTicks;
    @Unique
    private static double electroEnergetics$relYMin = Double.MAX_VALUE;
    @Unique
    private static double electroEnergetics$relYMax = -Double.MAX_VALUE;

    // ---- HORIZONTAL / LATERAL, added because a rider was thrown out sideways at 60 m/s ----
    //
    // The rider sinking was fixed, but at higher speed they are ejected out of the SIDE, which
    // is a different axis entirely and was never instrumented. The quantities below are the ones
    // that govern lateral motion: whether the pass carried them in X/Z at all, whether the
    // contact point they were carried by lagged the carriage, and whether the lateral push-out
    // (:256-257, :261-262) fired.

    /** Lateral, i.e. horizontal, displacement the pass actually applied to the rider. */
    @Unique
    private static double electroEnergetics$carriedHorizSum;
    /** The rider's horizontal distance from the carriage centre after the pass. */
    @Unique
    private static double electroEnergetics$radialMax;
    /** Ticks where the pass applied essentially no horizontal carry. */
    @Unique
    private static int electroEnergetics$horizLostTicks;
    /** Ticks where the lateral push-out zeroed an axis of the rider's own motion. */
    @Unique
    private static int electroEnergetics$lateralZeroedTicks;
    /** Largest single-pass horizontal gap between where the rider was and where they ended up. */
    @Unique
    private static double electroEnergetics$passDxzMax;
    /** The contact-point motion's horizontal magnitude, which is what carries them. */
    @Unique
    private static double electroEnergetics$contactHSum;
    /** Whether the carriage still considered them a contact at all. */
    @Unique
    private static int electroEnergetics$contactZeroTicks;
    /** The rider's own horizontal speed, which should stay near zero when standing still. */
    @Unique
    private static double electroEnergetics$ownHSum;

    // ---------------------------------------------------------------- pass entry

    @Inject(method = "collideEntities", at = @At("HEAD"), remap = false)
    private static void electroEnergetics$enter(AbstractContraptionEntity contraption,
                                                CallbackInfo ci) {
        electroEnergetics$beforePos = electroEnergetics$NONE;
        electroEnergetics$current = null;
        electroEnergetics$sawResponse = false;
        electroEnergetics$rSurface = false;
        electroEnergetics$rTemporal = 1d;
        electroEnergetics$rResponse = Vec3.ZERO;
        electroEnergetics$rNormal = Vec3.ZERO;
        electroEnergetics$rResponseY = 0d;
        electroEnergetics$solverMotion = Vec3.ZERO;
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
            electroEnergetics$motionY = motion.y;
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
            electroEnergetics$beforePos = electroEnergetics$NONE;
        }
    }

    // ------------------------------------------------- 1 & 2. the solver's verdict

    /**
     * Captures {@code CollisionResponse} and the motion handed to the solver.
     *
     * <p>One call site in {@code collideEntities}, so no ordinal is needed and there is nothing to
     * get wrong about which invocation this is.
     */
    @WrapOperation(method = "collideEntities",
            at = @At(value = "INVOKE",
                    target = "Lcom/simibubi/create/foundation/collision/ContinuousOBBCollider;"
                            + "collideMany(Lcom/simibubi/create/foundation/collision/CollisionList;"
                            + "Lcom/simibubi/create/foundation/collision/CollisionList;"
                            + "Lcom/simibubi/create/foundation/collision/OrientedBB;"
                            + "Lnet/minecraft/world/phys/Vec3;FZ)"
                            + "Lcom/simibubi/create/foundation/collision/ContinuousOBBCollider"
                            + "$CollisionResponse;"),
            remap = false)
    private static ContinuousOBBCollider.CollisionResponse electroEnergetics$captureSolver(
            CollisionList collidable, CollisionList dense, OrientedBB obb,
            Vec3 motion, float maxStep, boolean horizontalPass,
            Operation<ContinuousOBBCollider.CollisionResponse> original) {

        electroEnergetics$solverMotion = motion;
        ContinuousOBBCollider.CollisionResponse response =
                original.call(collidable, dense, obb, motion, maxStep, horizontalPass);
        if (response != null) {
            electroEnergetics$sawResponse = true;
            electroEnergetics$rSurface = response.surfaceCollision;
            electroEnergetics$rTemporal = response.temporalResponse;
            electroEnergetics$rResponse = response.collisionResponse == null
                    ? Vec3.ZERO : response.collisionResponse;
            electroEnergetics$rNormal = response.normal == null ? Vec3.ZERO : response.normal;
            electroEnergetics$rResponseY = electroEnergetics$rResponse.y;
        }
        return response;
    }

    // ---------------------------------------------------------------- pass exit

    @Inject(method = "collideEntities", at = @At("RETURN"), remap = false)
    private static void electroEnergetics$exit(AbstractContraptionEntity contraption,
                                               CallbackInfo ci) {
        try {
            AbstractContraptionEntity scoped = electroEnergetics$current;
            Vec3 before = electroEnergetics$beforePos;
            electroEnergetics$current = null;
            electroEnergetics$beforePos = electroEnergetics$NONE;
            if (scoped == null || before == null || Double.isNaN(before.x))
                return;

            LocalPlayer player = Minecraft.getInstance().player;
            if (player == null)
                return;

            long now = contraption.level().getGameTime();
            double carriageY = contraption.getY();
            double passDy = player.getY() - before.y;

            electroEnergetics$ticks++;
            if (electroEnergetics$sawResponse)
                electroEnergetics$solverCalls++;

            boolean temporal = Math.abs(electroEnergetics$rTemporal - 1d) > 1.0e-9;
            boolean hard = !electroEnergetics$rResponse.equals(Vec3.ZERO);

            if (electroEnergetics$rSurface)
                electroEnergetics$surfaceYes++;
            if (temporal)
                electroEnergetics$temporalYes++;
            if (hard)
                electroEnergetics$hardYes++;
            // ContraptionCollider:280 - bails before applying anything at all.
            if (!hard && !electroEnergetics$rSurface)
                electroEnergetics$bailed++;

            electroEnergetics$passDySum += passDy;
            if (passDy > 1.0e-4d)
                electroEnergetics$roseTicks++;
            else if (passDy < -1.0e-4d) {
                electroEnergetics$sankTicks++;
                electroEnergetics$sankSum += -passDy;
            }

            electroEnergetics$solverMotionYSum += electroEnergetics$solverMotion.y;
            electroEnergetics$responseYSum += electroEnergetics$rResponseY;

            // A value of zero here means the carriage registered the rider during THIS pass.
            MutableInt contact = contraption.collidingEntities.get(player);
            if (contact != null && contact.intValue() == 0)
                electroEnergetics$inContactTicks++;

            double relY = player.getY() - carriageY;
            if (relY < electroEnergetics$relYMin) electroEnergetics$relYMin = relY;
            if (relY > electroEnergetics$relYMax) electroEnergetics$relYMax = relY;

            // ---- HORIZONTAL ----
            // The axis that was never instrumented, and the one the rider was ejected along.
            double passDxz = Math.hypot(player.getX() - before.x, player.getZ() - before.z);
            electroEnergetics$carriedHorizSum += passDxz;
            if (passDxz > electroEnergetics$passDxzMax) electroEnergetics$passDxzMax = passDxz;
            // A pass that moved them essentially not at all horizontally while the carriage
            // travelled: they were dropped by the carry.
            if (passDxz < electroEnergetics$motion * 0.5d)
                electroEnergetics$horizLostTicks++;

            // How far out to the side of the carriage centre they are. Growth here is the rider
            // sliding toward the edge, which is the precursor to being thrown off it.
            double radial = Math.hypot(player.getX() - contraption.getX(),
                                       player.getZ() - contraption.getZ());
            if (radial > electroEnergetics$radialMax) electroEnergetics$radialMax = radial;

            // What actually carries them horizontally, and their own horizontal speed, which
            // should stay near zero while standing.
            Vec3 contactMotion = contraption.getContactPointMotion(player.position());
            electroEnergetics$contactHSum += Math.hypot(contactMotion.x, contactMotion.z);
            Vec3 own = player.getDeltaMovement();
            electroEnergetics$ownHSum += Math.hypot(own.x, own.z);
            if (contact != null && contact.intValue() != 0)
                electroEnergetics$contactZeroTicks++;

            electroEnergetics$maybeSummary(now, player, carriageY);
        } catch (Throwable ignored) {
            electroEnergetics$current = null;
            electroEnergetics$beforePos = electroEnergetics$NONE;
        }
    }

    // ---------------------------------------------------------------- summary

    @Unique
    private static void electroEnergetics$maybeSummary(long now, LocalPlayer player,
                                                       double carriageY) {
        // The sentinel is tested FIRST. Writing this as `now - Long.MIN_VALUE` overflows to a
        // large negative number that is always below the interval, so the guard would return on
        // every tick and the summary could never print - which is exactly what silenced an
        // earlier build of this diagnostic, and cost a whole test run to find.
        if (electroEnergetics$lastSummary == Long.MIN_VALUE) {
            electroEnergetics$lastSummary = now;
            return;
        }
        if (now - electroEnergetics$lastSummary < SUMMARY_EVERY)
            return;
        electroEnergetics$lastSummary = now;
        if (electroEnergetics$lines++ >= MAX_SUMMARY_LINES)
            return;

        int t = electroEnergetics$ticks;
        if (t == 0) {
            electroEnergetics$LOG.info("CARRY SUM no carriage passes this window");
            return;
        }

        electroEnergetics$LOG.info(
                "CARRY SUM ticks={} solverCalls={} | surface={}/{} temporal={}/{} hard={}/{} "
                        + "bailed280={}/{} | solverMotionYavg={} responseYavg={} "
                        + "inContact={}/{} | passDyavg={} rose={} sank={} avgSank={} "
                        + "| relY=[{},{}] nowRelY={} carriageY={} playerY={} vy={} onGround={}",
                t, electroEnergetics$solverCalls,
                electroEnergetics$surfaceYes, t,
                electroEnergetics$temporalYes, t,
                electroEnergetics$hardYes, t,
                electroEnergetics$bailed, t,
                String.format("%.4f", electroEnergetics$solverMotionYSum / t),
                String.format("%.4f", electroEnergetics$responseYSum / t),
                electroEnergetics$inContactTicks, t,
                String.format("%.4f", electroEnergetics$passDySum / t),
                electroEnergetics$roseTicks, electroEnergetics$sankTicks,
                String.format("%.4f", electroEnergetics$sankTicks == 0 ? 0d
                        : electroEnergetics$sankSum / electroEnergetics$sankTicks),
                String.format("%.3f", electroEnergetics$relYMin),
                String.format("%.3f", electroEnergetics$relYMax),
                String.format("%.3f", player.getY() - carriageY),
                String.format("%.3f", carriageY),
                String.format("%.3f", player.getY()),
                String.format("%.3f", player.getDeltaMovement().y),
                player.onGround());

        // The horizontal half, reported separately because the ejection is a lateral event and
        // mixing it into the vertical line made neither readable.
        electroEnergetics$LOG.info(
                "CARRY LAT ticks={} carriedHavg={} passDxzMax={} motion={} motionY={} "
                        + "contactHavg={} ownHavg={} radialMax={} "
                        + "horizLost={}/{} contactLost={}/{}",
                t,
                String.format("%.3f", electroEnergetics$carriedHorizSum / t),
                String.format("%.3f", electroEnergetics$passDxzMax),
                String.format("%.3f", electroEnergetics$motion),
                String.format("%.4f", electroEnergetics$motionY),
                String.format("%.3f", electroEnergetics$contactHSum / t),
                String.format("%.3f", electroEnergetics$ownHSum / t),
                String.format("%.3f", electroEnergetics$radialMax),
                electroEnergetics$horizLostTicks, t,
                electroEnergetics$contactZeroTicks, t);

        electroEnergetics$ticks = 0;
        electroEnergetics$solverCalls = 0;
        electroEnergetics$surfaceYes = 0;
        electroEnergetics$temporalYes = 0;
        electroEnergetics$hardYes = 0;
        electroEnergetics$bailed = 0;
        electroEnergetics$roseTicks = 0;
        electroEnergetics$sankTicks = 0;
        electroEnergetics$sankSum = 0;
        electroEnergetics$passDySum = 0;
        electroEnergetics$solverMotionYSum = 0;
        electroEnergetics$responseYSum = 0;
        electroEnergetics$inContactTicks = 0;
        electroEnergetics$relYMin = Double.MAX_VALUE;
        electroEnergetics$relYMax = -Double.MAX_VALUE;
        electroEnergetics$carriedHorizSum = 0;
        electroEnergetics$radialMax = 0;
        electroEnergetics$horizLostTicks = 0;
        electroEnergetics$passDxzMax = 0;
        electroEnergetics$contactHSum = 0;
        electroEnergetics$contactZeroTicks = 0;
        electroEnergetics$ownHSum = 0;
    }
}
