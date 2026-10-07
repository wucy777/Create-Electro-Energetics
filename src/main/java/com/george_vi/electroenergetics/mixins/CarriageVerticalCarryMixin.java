package com.george_vi.electroenergetics.mixins;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.ContraptionCollider;
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
 * Gives a rider on a moving train the vertical support Create's surface branch leaves out.
 *
 * <h2>The bug, measured rather than inferred</h2>
 *
 * <p>Standing on a moving train, the rider sinks through the carriage floor at a steady rate and
 * keeps going until they reach the ground below. A diagnostic run of about six hundred ticks
 * pinned it down exactly:
 *
 * <pre>
 *   surfaceReg     = 100% in all 28 sample windows   the surface branch runs every tick
 *   snapOuterCalls = 0    in all 28 sample windows   the floor-snap ray never runs at all
 *   motionYavg     = 0.000 in all 28 sample windows  the carriage has no vertical motion
 *   carriage Y held constant at -59.000 while the rider travelled -60.000 to -57.560
 *   horizontal carry perfect: carried/motion = 0.993 to 1.000
 *   descent a steady 0.076 Blocks/tick, vy pinned near -0.08, onGround true, fallDistance 0.0
 * </pre>
 *
 * <p>So the horizontal carry is flawless and the entire defect is vertical. The rider descends by
 * exactly one gravity step per tick, never faster, which is not a fall - a fall accelerates. It
 * is one gravity step that is applied every tick and never undone.
 *
 * <h2>Why, and why Create's own contraptions do not show it</h2>
 *
 * <p>{@code ContraptionCollider} supports a rider's weight in three separate places, and the one
 * a rider standing on a carriage floor goes through is the only one missing its vertical half.
 *
 * <pre>
 *   :284-285  hard collision   setPos(x + allowed.x, y + allowed.y, z + allowed.z)   Y APPLIED
 *   :241-243  temporal         setDeltaMovement(... add(0, idealVerticalMotion, 0))   Y SUPPORTED
 *   :258-260  hard collision   entityMotion = ... add(0, contraptionMotion.y, 0)     Y SUPPORTED
 *   :309-310  surface          setPos(x + allowed.x, y,             z + allowed.z)   Y DISCARDED
 * </pre>
 *
 * <p>That last line carries a standing rider - :295 {@code registerColliding} runs only inside
 * {@code if (surfaceCollision)}, which is exactly what the 100% measurement above shows - and it
 * passes {@code entityPosition.y} straight through, throwing away the {@code allowedMovement.y}
 * that was computed one line earlier at :308. It also has no counterpart to :241 or :258. A
 * carriage floor is not a world block, so vanilla's own collision cannot catch the rider either:
 * nothing at all supplies their weight, and gravity wins one step at a time.
 *
 * <p>Create does not notice because its own contraptions move at a tenth to half a Block per
 * tick, where a missing gravity step is a sub-pixel drift that wall collision and seats hide. At
 * several Blocks per tick the same omission becomes a visible descent.
 *
 * <p>The floor snap cannot cover for it here, and that is measured too: {@code snapOuterCalls}
 * is zero, because {@code :74} {@code safetyLock} is a single static field that each carriage
 * overwrites in turn, and {@code :363-367} would in any case return early whenever the carriage
 * has no vertical motion - which is precisely the level-track case.
 *
 * <h2>The two changes</h2>
 *
 * <p>Both restore behaviour Create already has elsewhere; neither invents a mechanism.
 *
 * <p><b>1. Apply the vertical component that :309-310 discards.</b> The surface branch is made to
 * use the same expression as the hard-collision branch at :284-285, so the rider travels with the
 * carriage's contact point in Y exactly as they already do in X and Z. On level track
 * {@code allowedMovement.y} is about zero and this changes nothing, which is why it cannot be the
 * whole fix; on a gradient it carries the rider up and down with the floor instead of leaving
 * them behind.
 *
 * <p><b>2. Add the vertical support :241-243 provides elsewhere.</b> When the carriage is
 * carrying the rider across its surface and the rider's own downward velocity would take them
 * below the contact point they are standing on, that velocity is raised to the contact point's.
 * This is the direct counterpart of Create's own {@code idealVerticalMotion} correction, applied
 * to the branch that lacks one.
 *
 * <p>It only ever raises a downward velocity, never lowers an upward one, so a jump is untouched:
 * jumping gives a velocity well above the contact point's, and the comparison fails. Walking
 * across a gradient keeps working for the same reason. And because the correction is a velocity
 * rather than a position, a rider who leaves the carriage is not held by it - the moment this
 * carriage stops carrying them the check stops matching and gravity resumes untouched.
 *
 * <h2>Scope</h2>
 *
 * <p>CLIENT ONLY, which is where a rider's position is authoritative: the server skips players
 * outright ({@code ContraptionCollider:120-121}, {@code PlayerType.SERVER}) and learns the result
 * from the client's movement packets, as it already does for Create's own carry. Nothing runs at
 * all unless a client player is being carried by the carriage in question, so an ordinary world,
 * a standing player and every non-train contraption are unaffected.
 */
@Mixin(ContraptionCollider.class)
public class CarriageVerticalCarryMixin {

    @Unique
    private static final Logger electroEnergetics$LOG = LoggerFactory.getLogger("CEE-CARRY");

    /** Summary cadence in ticks, and hard line cap so a long session cannot fill the log. */
    @Unique
    private static final int SUMMARY_EVERY = 100;
    @Unique
    private static final int MAX_SUMMARY_LINES = 200;

    /** Below this carriage speed nothing is logged, so ordinary worlds stay silent. */
    @Unique
    private static final double MIN_INTERESTING_MOTION = 0.5d;

    /** What {@code collide(contactPointMotion, entity)} returned in the surface branch. */
    @Unique
    private static Vec3 electroEnergetics$surfaceAllowed = Vec3.ZERO;

    /** The client player's position before this carriage's pass, to confirm a real carry. */
    @Unique
    private static Vec3 electroEnergetics$beforePos;

    /** This carriage's own travel this tick. */
    @Unique
    private static double electroEnergetics$motion;

    // ---- verification counters ----

    @Unique
    private static int electroEnergetics$lines;
    @Unique
    private static long electroEnergetics$lastSummary = Long.MIN_VALUE;
    @Unique
    private static int electroEnergetics$carriedTicks;
    @Unique
    private static int electroEnergetics$liftedTicks;
    @Unique
    private static int electroEnergetics$stillSinkingTicks;
    @Unique
    private static double electroEnergetics$sinkSum;
    @Unique
    private static double electroEnergetics$relYMin = Double.MAX_VALUE;
    @Unique
    private static double electroEnergetics$relYMax = -Double.MAX_VALUE;

    // ------------------------------------------------------- 1. the discarded Y

    /**
     * Captures what the surface branch's collision solve returned.
     *
     * <p>Wrapped rather than recomputed because {@code collide} is package-private and cannot be
     * called from here, and recording the real value keeps this exactly in step with whatever
     * the solver actually decided.
     *
     * <p>{@code ordinal = 1} selects the call at {@code :308}; ordinal 0 is the hard-collision
     * call at {@code :283}.
     */
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
        Vec3 allowed = original.call(motion, entity);
        electroEnergetics$surfaceAllowed = allowed;
        return allowed;
    }

    /**
     * Applies that vertical component, matching the hard-collision branch at {@code :284-285}.
     *
     * <p>{@code ordinal = 1} selects the surface branch's call at {@code :309}; ordinal 0 is
     * {@code :284}, which already adds its {@code .y} and must not be touched.
     */
    @Redirect(method = "collideEntities",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;setPos(DDD)V",
                    ordinal = 1),
            remap = false)
    private static void electroEnergetics$applyVerticalCarry(Entity entity,
                                                             double x, double y, double z) {
        entity.setPos(x, y + electroEnergetics$surfaceAllowed.y, z);
    }

    // ------------------------------------------------ 2. the missing vertical support

    @Inject(method = "collideEntities", at = @At("HEAD"), remap = false)
    private static void electroEnergetics$beforeCarry(AbstractContraptionEntity contraption,
                                                      CallbackInfo ci) {
        electroEnergetics$beforePos = null;
        try {
            if (!contraption.level().isClientSide())
                return;
            LocalPlayer player = Minecraft.getInstance().player;
            if (player == null || player.isPassenger() || player.isSpectator())
                return;

            electroEnergetics$motion = contraption.position()
                    .subtract(contraption.getPrevPositionVec())
                    .length();
            if (electroEnergetics$motion < MIN_INTERESTING_MOTION)
                return;
            if (!contraption.getBoundingBox().inflate(2d).expandTowards(0, 32, 0)
                    .contains(player.position()))
                return;

            electroEnergetics$beforePos = player.position();
        } catch (Throwable ignored) {
            electroEnergetics$beforePos = null;
        }
    }

    @Inject(method = "collideEntities", at = @At("RETURN"), remap = false)
    private static void electroEnergetics$holdVertically(AbstractContraptionEntity contraption,
                                                         CallbackInfo ci) {
        try {
            Vec3 before = electroEnergetics$beforePos;
            electroEnergetics$beforePos = null;
            if (before == null)
                return;

            LocalPlayer player = Minecraft.getInstance().player;
            if (player == null || player.isPassenger() || player.isSpectator())
                return;

            // Only for a carriage that actually carried them during this pass. That is what
            // keeps this from reaching a rider who is merely near the track, and from lingering
            // after they step off: collidingEntities holds an entry for up to three ticks
            // (:374-376), so membership alone would be too loose.
            double carried = player.position().distanceTo(before);
            if (carried <= electroEnergetics$motion * 0.5d)
                return;

            // The motion of the material point under their feet: exactly what they should be
            // travelling with, and the same quantity Create's own :241-243 correction uses.
            Vec3 carry = contraption.getContactPointMotion(player.position());
            Vec3 own = player.getDeltaMovement();

            if (own.y < carry.y) {
                player.setDeltaMovement(own.x, carry.y, own.z);
                // They are standing on the carriage, so the corrected height must not later be
                // charged as a fall.
                player.fallDistance = 0;
                if (carry.y > 0d)
                    electroEnergetics$liftedTicks++;
            }

            // Verification: what is left of the sink, measured the same way the diagnostic
            // measured it so the two are comparable.
            double vy = player.getDeltaMovement().y;
            if (vy < -0.01d) {
                electroEnergetics$stillSinkingTicks++;
                electroEnergetics$sinkSum += -vy;
            }
            double relY = player.getY() - contraption.getY();
            if (relY < electroEnergetics$relYMin) electroEnergetics$relYMin = relY;
            if (relY > electroEnergetics$relYMax) electroEnergetics$relYMax = relY;
            electroEnergetics$carriedTicks++;

            electroEnergetics$maybeSummary(contraption.level().getGameTime(), player);
        } catch (Throwable ignored) {
            // A carry correction must never be the thing that breaks the client.
        }
    }

    @Unique
    private static void electroEnergetics$maybeSummary(long now, LocalPlayer player) {
        if (now - electroEnergetics$lastSummary < SUMMARY_EVERY)
            return;
        if (electroEnergetics$lastSummary == Long.MIN_VALUE) {
            electroEnergetics$lastSummary = now;
            return;
        }
        electroEnergetics$lastSummary = now;
        if (electroEnergetics$carriedTicks == 0)
            return;
        if (electroEnergetics$lines++ >= MAX_SUMMARY_LINES)
            return;

        int t = electroEnergetics$carriedTicks;
        electroEnergetics$LOG.info(
                "CARRY FIX carried={} lifted={} stillSinking={}/{} avgSink={} relY=[{},{}] "
                        + "nowRelY={} vy={} onGround={}",
                t, electroEnergetics$liftedTicks,
                electroEnergetics$stillSinkingTicks, t,
                String.format("%.3f", electroEnergetics$stillSinkingTicks == 0 ? 0d
                        : electroEnergetics$sinkSum / electroEnergetics$stillSinkingTicks),
                String.format("%.3f", electroEnergetics$relYMin),
                String.format("%.3f", electroEnergetics$relYMax),
                String.format("%.3f", player.getY()),
                String.format("%.3f", player.getDeltaMovement().y),
                player.onGround());

        electroEnergetics$carriedTicks = 0;
        electroEnergetics$liftedTicks = 0;
        electroEnergetics$stillSinkingTicks = 0;
        electroEnergetics$sinkSum = 0;
        electroEnergetics$relYMin = Double.MAX_VALUE;
        electroEnergetics$relYMax = -Double.MAX_VALUE;
    }
}
