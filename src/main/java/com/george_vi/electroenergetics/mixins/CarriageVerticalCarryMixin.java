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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stops a rider sinking through the floor of a moving train carriage, by applying the vertical
 * component Create itself computes and then discards.
 *
 * <h2>The bug, measured rather than inferred</h2>
 *
 * <p>A diagnostic run of about six hundred ticks pinned it down:
 *
 * <pre>
 *   surfaceReg     = 100% in all 28 windows   the surface branch runs every tick
 *   snapOuterCalls = 0    in all 28 windows   the floor-snap ray never runs at all
 *   carriage Y constant at -59.000 while the rider travelled -60.000 to -57.560
 *   horizontal carry perfect: carried/motion = 0.993 to 1.000
 *   descent a steady 0.076 Blocks/tick, vy near -0.08, onGround true, fallDistance 0.0
 * </pre>
 *
 * <p>Horizontal carry is flawless; the entire defect is vertical. The descent is one gravity step
 * per tick that is never undone - a constant rate, where a real fall would accelerate.
 *
 * <h2>The cause, in two adjacent lines</h2>
 *
 * <p>Create supports a rider's weight in three places. The one a rider standing on a carriage
 * floor goes through is the only one that discards its vertical half:
 *
 * <pre>
 *   :283-285  hard       allowedMovement = collide(totalResponse, entity)
 *                        setPos(x + allowed.x, y + allowed.y, z + allowed.z)    Y APPLIED
 *   :308-310  surface    allowedMovement = collide(contactPointMotion, entity)
 *                        setPos(x + allowed.x, y,             z + allowed.z)    Y DISCARDED
 * </pre>
 *
 * <p>{@code :295 registerColliding} runs only inside {@code if (surfaceCollision)}, and the 100%
 * figure above confirms that is the branch in use, so it is the second of those that carries a
 * standing rider.
 *
 * <p>{@code collide} is the push-out solver - handed a penetration response, it returns the motion
 * needed to leave it. A rider resting on a carriage floor penetrates it slightly, so this returns
 * a POSITIVE {@code allowedMovement.y} whose whole purpose is to lift them clear. {@code :309}
 * passes {@code entityPosition.y} straight through instead, so that lift is thrown away, the rider
 * stays in penetration, and the next tick repeats with slightly more. That is the constant-rate
 * sink, and it is why the rate does not accelerate: each tick removes one gravity step and each
 * tick's computed correction is discarded again.
 *
 * <p>A carriage floor is not a world block, so vanilla's own collision cannot catch the rider
 * either, and nothing else supplies their weight.
 *
 * <h2>The fix</h2>
 *
 * <p>The surface branch is made to use the same expression as the hard-collision branch, so the
 * rider travels vertically with the contact point exactly as they already do horizontally. This
 * is not a new mechanism - it is the line Create already wrote two hundred lines earlier,
 * applied to the branch that omits it.
 *
 * <p>Because it applies the solver's own push-out rather than imposing a height, it cannot hold
 * anyone in the air and cannot fight a jump: leaving the carriage stops the push-out, and an
 * ascending rider gets no penetration to resolve. An earlier attempt of mine imposed a height
 * instead and would have floated a jumping rider at the apex of their arc; this does not.
 *
 * <h2>Measurement</h2>
 *
 * <p>The same run reports the vertical component it is applying and the rider's height relative
 * to the carriage, so the fix is verified against the numbers that found the bug rather than
 * against an expectation. Specifically {@code allowedYavg} should be small and positive - that is
 * the discarded lift being recovered - and the relative height should stop reaching its previous
 * floor of about -1.000.
 *
 * <h2>Scope and cost</h2>
 *
 * <p>CLIENT ONLY, where a rider's position is authoritative: the server skips players outright
 * ({@code ContraptionCollider:120-121}, {@code PlayerType.SERVER}) and learns the result from the
 * client's movement packets, as it already does for Create's own carry.
 *
 * <p>Confined to {@link CarriageContraptionEntity} by a per-pass guard, so no other contraption
 * changes - not Create's bearings, pistons or gantries. No packets, and no allocation unless the
 * summary line is due, which is once per hundred ticks and only while a rider is actually on a
 * moving carriage.
 */
@Mixin(ContraptionCollider.class)
public class CarriageVerticalCarryMixin {

    @Unique
    private static final Logger electroEnergetics$LOG = LoggerFactory.getLogger("CEE-CARRY");

    /** Below this carriage speed nothing is logged, so ordinary worlds stay silent. */
    @Unique
    private static final double MIN_INTERESTING_MOTION = 0.5d;

    /** Summary cadence and hard line cap, so a long session cannot fill the log. */
    @Unique
    private static final int SUMMARY_EVERY = 100;
    @Unique
    private static final int MAX_SUMMARY_LINES = 200;

    /** The carriage currently being processed, so the redirects can be scoped to it. */
    @Unique
    private static AbstractContraptionEntity electroEnergetics$current;

    /** What {@code collide(contactPointMotion, entity)} returned in the surface branch. */
    @Unique
    private static Vec3 electroEnergetics$surfaceAllowed = Vec3.ZERO;

    /**
     * What {@code collide(totalResponse, entity)} returned at {@code :283}, the push-out path.
     *
     * <p>Recorded because it distinguishes the two possible causes of the sink, which need
     * different fixes: if this carries a positive Y, the solver IS resolving penetration and the
     * surface branch's discarded term is the whole story. If it is zero, the rider is resting on
     * the carriage without penetrating it at all, so there is no push-out to recover and the
     * weight has to be supported rather than restored.
     */
    @Unique
    private static Vec3 electroEnergetics$hardAllowed = Vec3.ZERO;

    /** The rider's position before this carriage's pass, to confirm a real horizontal carry. */
    @Unique
    private static Vec3 electroEnergetics$beforePos;

    /** This carriage's own travel this tick. */
    @Unique
    private static double electroEnergetics$motion;

    // ---- verification ----

    @Unique
    private static int electroEnergetics$lines;
    @Unique
    private static long electroEnergetics$lastSummary = Long.MIN_VALUE;
    @Unique
    private static int electroEnergetics$carriedTicks;
    @Unique
    private static int electroEnergetics$positiveLifts;
    @Unique
    private static double electroEnergetics$allowedYSum;
    @Unique
    private static double electroEnergetics$relYMin = Double.MAX_VALUE;
    @Unique
    private static double electroEnergetics$relYMax = -Double.MAX_VALUE;

    /**
     * What the whole pass did to the rider's height, and how often it was upwards.
     *
     * <p>This is the decisive pair. If a carried rider is never once raised by the pass - if
     * {@code roseTicks} stays at zero - then nothing in Create supplies their weight at this
     * speed, whatever the individual terms say, and the support has to be provided rather than
     * recovered from a discarded value.
     */
    @Unique
    private static double electroEnergetics$passDySum;
    @Unique
    private static int electroEnergetics$roseTicks;
    @Unique
    private static int electroEnergetics$sankTicks;
    @Unique
    private static double electroEnergetics$sankSum;
    @Unique
    private static double electroEnergetics$hardYSum;

    /** Scopes the redirects to train carriages and a rider actually being carried. */
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

    /**
     * Captures what the surface branch's collision solve returned.
     *
     * <p>Wrapped rather than recomputed because {@code collide} is package-private and cannot be
     * called from here, and recording the real value keeps this exactly in step with whatever the
     * solver actually decided. {@code ordinal = 1} selects the call at {@code :308}; ordinal 0 is
     * {@code :283}.
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
     * Captures the push-out path's result at {@code :283}.
     *
     * <p>A positive Y here means the solver is resolving real penetration, in which case the
     * surface branch's discarded term is the cause of the sink and applying it is the fix. A zero
     * means the rider rests on the carriage without penetrating, so there is nothing to recover
     * and the weight must be supported instead.
     */
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
     * Applies the vertical component, matching the hard-collision branch at {@code :284-285}.
     *
     * <p>{@code ordinal = 1} selects the surface branch's call at {@code :309}; ordinal 0 is
     * {@code :284}, which already adds its own {@code .y} and must not be added to twice. Ordinals
     * were read from the shipped bytecode: {@code Entity.setPos} appears exactly twice inside
     * {@code collideEntities}, at 1546 and 1782.
     */
    @Redirect(method = "collideEntities",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;setPos(DDD)V",
                    ordinal = 1),
            remap = false)
    private static void electroEnergetics$applyVerticalCarry(Entity entity,
                                                            double x, double y, double z) {
        if (electroEnergetics$current == null) {
            entity.setPos(x, y, z);
            return;
        }
        entity.setPos(x, y + electroEnergetics$surfaceAllowed.y, z);
    }

    @Inject(method = "collideEntities", at = @At("RETURN"), remap = false)
    private static void electroEnergetics$afterCarry(AbstractContraptionEntity contraption,
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

            // Carried horizontally means genuinely in contact with this carriage - the same test
            // the horizontal carry itself satisfies, so this is exactly the set of riders whose
            // weight Create is failing to support. An airborne or departing rider fails it.
            double carriedHorizontally = Math.hypot(
                    player.getX() - before.x, player.getZ() - before.z);
            if (carriedHorizontally <= electroEnergetics$motion * 0.5d)
                return;

            electroEnergetics$carriedTicks++;
            double allowedY = electroEnergetics$surfaceAllowed.y;
            electroEnergetics$allowedYSum += allowedY;
            electroEnergetics$hardYSum += electroEnergetics$hardAllowed.y;
            if (allowedY > 1.0e-4d)
                electroEnergetics$positiveLifts++;

            // What the pass as a whole did to their height, which is the measurement that cannot
            // be argued with: if a carried rider is never raised, nothing here supports them.
            double passDy = player.getY() - before.y;
            electroEnergetics$passDySum += passDy;
            if (passDy > 1.0e-4d)
                electroEnergetics$roseTicks++;
            else if (passDy < -1.0e-4d) {
                electroEnergetics$sankTicks++;
                electroEnergetics$sankSum += -passDy;
            }

            double relY = player.getY() - contraption.getY();
            if (relY < electroEnergetics$relYMin) electroEnergetics$relYMin = relY;
            if (relY > electroEnergetics$relYMax) electroEnergetics$relYMax = relY;

            electroEnergetics$maybeSummary(contraption.level().getGameTime(), player, allowedY,
                    passDy);
        } catch (Throwable ignored) {
            // A carry correction must never be the thing that breaks the client.
            electroEnergetics$current = null;
            electroEnergetics$beforePos = null;
        }
    }

    @Unique
    private static void electroEnergetics$maybeSummary(long now, LocalPlayer player,
                                                       double allowedY, double passDy) {
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
                "CARRY FIX ticks={} surfaceYavg={} surfaceYnow={} positive={}/{} hardYavg={} "
                        + "| passDyavg={} rose={}/{} sank={}/{} avgSank={} "
                        + "| relY=[{},{}] nowRelY={} vy={} onGround={}",
                t,
                String.format("%.4f", electroEnergetics$allowedYSum / t),
                String.format("%.4f", allowedY),
                electroEnergetics$positiveLifts, t,
                String.format("%.4f", electroEnergetics$hardYSum / t),
                String.format("%.4f", electroEnergetics$passDySum / t),
                electroEnergetics$roseTicks, t,
                electroEnergetics$sankTicks, t,
                String.format("%.4f", electroEnergetics$sankTicks == 0 ? 0d
                        : electroEnergetics$sankSum / electroEnergetics$sankTicks),
                String.format("%.3f", electroEnergetics$relYMin),
                String.format("%.3f", electroEnergetics$relYMax),
                String.format("%.3f", player.getY()),
                String.format("%.3f", player.getDeltaMovement().y),
                player.onGround());

        electroEnergetics$carriedTicks = 0;
        electroEnergetics$positiveLifts = 0;
        electroEnergetics$allowedYSum = 0;
        electroEnergetics$hardYSum = 0;
        electroEnergetics$passDySum = 0;
        electroEnergetics$roseTicks = 0;
        electroEnergetics$sankTicks = 0;
        electroEnergetics$sankSum = 0;
        electroEnergetics$relYMin = Double.MAX_VALUE;
        electroEnergetics$relYMax = -Double.MAX_VALUE;
    }
}
