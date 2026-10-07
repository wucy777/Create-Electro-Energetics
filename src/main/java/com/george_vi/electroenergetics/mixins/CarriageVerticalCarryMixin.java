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
 * Gives a rider standing on a moving train carriage the vertical support it lacks.
 *
 * <h2>The bug, measured rather than inferred</h2>
 *
 * <p>A diagnostic run of about six hundred ticks pinned this down exactly:
 *
 * <pre>
 *   surfaceReg     = 100% in all 28 windows   the surface branch runs every tick
 *   snapOuterCalls = 0    in all 28 windows   the floor-snap ray never runs at all
 *   motionYavg     = 0.000 in all 28 windows  the carriage has no vertical motion
 *   carriage Y constant at -59.000 while the rider travelled -60.000 to -57.560
 *   horizontal carry perfect: carried/motion = 0.993 to 1.000
 *   descent a steady 0.076 Blocks/tick, vy pinned near -0.08, onGround true, fallDistance 0.0
 * </pre>
 *
 * <p>The horizontal carry is therefore flawless and the whole defect is vertical. The descent is
 * one gravity step per tick that is never undone - a constant {@code vy}, which a real fall
 * would not produce, because a fall accelerates.
 *
 * <h2>Why</h2>
 *
 * <p>Create supports a rider's weight in three places and the one a rider standing on a carriage
 * floor goes through is the only one missing its vertical half:
 *
 * <pre>
 *   :284-285  hard       setPos(x + allowed.x, y + allowed.y, z + allowed.z)   Y applied
 *   :241-243  temporal   setDeltaMovement(... add(0, idealVerticalMotion, 0))  Y supported
 *   :258-260  hard       entityMotion = ... add(0, contraptionMotion.y, 0)     Y supported
 *   :309-310  surface    setPos(x + allowed.x, y,             z + allowed.z)   Y DISCARDED
 * </pre>
 *
 * <p>{@code :295 registerColliding} runs only inside {@code if (surfaceCollision)}, which the
 * 100% figure above confirms is the branch in use, and {@code :309} passes
 * {@code entityPosition.y} straight through, discarding the {@code allowedMovement.y} computed
 * one line earlier at {@code :308}. There is no counterpart to {@code :241} or {@code :258} in
 * that branch either. A carriage floor is not a world block, so vanilla's own collision cannot
 * catch the rider: nothing supplies their weight and gravity wins a step at a time.
 *
 * <p>Create does not show it because its contraptions move at a tenth to half a Block per tick,
 * where a missing gravity step is sub-pixel drift that wall collision and seats hide.
 *
 * <p>The floor snap cannot cover for it, which is also measured: {@code snapOuterCalls} is zero
 * because {@code :74} {@code safetyLock} is a single static field that each carriage overwrites
 * in turn, so in a multi-carriage consist the check at {@code :92} never matches what
 * {@code :326} wrote; and {@code :363-367} would return early anyway whenever the carriage has
 * no vertical motion, which is exactly the level-track case.
 *
 * <h2>Why the obvious correction does not work, which cost a round to learn</h2>
 *
 * <p>Zeroing the rider's downward velocity does nothing, and the probe's own timing shows why.
 * {@code collideEntities} runs from {@code ContraptionHandler.tick} in {@code ClientTickEvent.Post},
 * i.e. AFTER the client has already applied gravity and already moved the rider down. Clearing a
 * velocity at that point cannot undo a displacement that has happened; the next tick simply
 * repeats the same step, which is precisely the constant 0.076 per tick that was measured.
 *
 * <p>Nor can the target height be taken from the rider's position during the same pass: it is
 * captured at {@code HEAD}, already sunk, so the deficit computes as zero and any correction is
 * inert. The height has to be carried ACROSS ticks, advanced by the contact point as the
 * carriage moves, and it is held here in {@link #electroEnergetics$holdY} for exactly that
 * reason.
 *
 * <h2>The two changes</h2>
 *
 * <p>Both restore behaviour Create already has elsewhere. Neither invents a mechanism, and both
 * are confined to train carriages, so no other contraption in the game changes.
 *
 * <p><b>1. Apply the vertical component {@code :309-310} discards</b>, using the same expression
 * as {@code :284-285}, so the rider travels with the contact point in Y exactly as they already
 * do in X and Z. On level track this is about zero and so it cannot be the whole fix; on a
 * gradient it is what keeps the rider with the floor instead of leaving them behind.
 *
 * <p><b>2. Hold the height across ticks.</b> The rider's expected height is advanced by the
 * contact point's vertical motion each tick and restored if they have dropped below it.
 *
 * <p>The restore is bounded by {@link #MAX_RESTORE}, and that bound is what keeps it honest:
 * only a small deficit - the kind a tick or two of missed gravity produces - is corrected, while
 * anything larger means the rider genuinely moved to a different level, and their new position is
 * adopted as the baseline instead. That is what stops the two failure modes this could otherwise
 * have: yanking a rider back up after they legitimately stepped down, and floating them at the
 * apex of a jump.
 *
 * <p>Only a rise is ever corrected, never a fall below, so nothing here can push a rider down.
 * And a rider who is airborne is not corrected at all, because a carriage only carries what is
 * in contact with it - the same test that carries them horizontally is what enables this, so
 * jumping is untouched and stepping off is not resisted.
 *
 * <h2>Scope and cost</h2>
 *
 * <p>CLIENT ONLY, which is where a rider's position is authoritative: the server skips players
 * outright ({@code ContraptionCollider:120-121}, {@code PlayerType.SERVER}) and learns the result
 * from the client's movement packets, as it already does for Create's own carry.
 *
 * <p>Confined to {@link CarriageContraptionEntity}, and nothing runs unless a client player is
 * actually being carried by that carriage, so a standing player, a platform, and every other
 * contraption are untouched. No packets, and no allocation unless the summary line is due.
 */
@Mixin(ContraptionCollider.class)
public class CarriageVerticalCarryMixin {

    @Unique
    private static final Logger electroEnergetics$LOG = LoggerFactory.getLogger("CEE-CARRY");

    /** Below this carriage speed nothing is logged, so ordinary worlds stay silent. */
    @Unique
    private static final double MIN_INTERESTING_MOTION = 0.5d;

    /**
     * Largest downward deficit that is treated as missed support and restored, in Blocks.
     *
     * <p>Comfortably more than the roughly 0.08 a single tick of gravity produces, and well under
     * any real change of level, so the two cannot be confused.
     */
    @Unique
    private static final double MAX_RESTORE = 0.35d;

    /** Summary cadence and hard line cap, so a long session cannot fill the log. */
    @Unique
    private static final int SUMMARY_EVERY = 100;
    @Unique
    private static final int MAX_SUMMARY_LINES = 200;

    // ---- the contraption currently being processed, so the redirects can be scoped ----

    @Unique
    private static AbstractContraptionEntity electroEnergetics$current;

    /** What {@code collide(contactPointMotion, entity)} returned in the surface branch. */
    @Unique
    private static Vec3 electroEnergetics$surfaceAllowed = Vec3.ZERO;

    /** The rider's position before this carriage's pass. */
    @Unique
    private static Vec3 electroEnergetics$beforePos;

    /** This carriage's travel this tick. */
    @Unique
    private static double electroEnergetics$motion;

    // ---- the height held across ticks ----

    /** The rider's expected height while carried, or NaN when there is none to hold. */
    @Unique
    private static double electroEnergetics$holdY = Double.NaN;

    /** The carriage that height belongs to, and the tick it was last valid. */
    @Unique
    private static int electroEnergetics$holdOwner = -1;
    @Unique
    private static long electroEnergetics$holdTick = Long.MIN_VALUE;

    /** Ticks beyond which a remembered height is discarded rather than trusted. */
    @Unique
    private static final int HOLD_EXPIRY_TICKS = 5;

    // ---- verification counters ----

    @Unique
    private static int electroEnergetics$lines;
    @Unique
    private static long electroEnergetics$lastSummary = Long.MIN_VALUE;
    @Unique
    private static int electroEnergetics$carriedTicks;
    @Unique
    private static int electroEnergetics$restoredTicks;
    @Unique
    private static int electroEnergetics$rebasedTicks;
    @Unique
    private static double electroEnergetics$restoreSum;
    @Unique
    private static double electroEnergetics$relYMin = Double.MAX_VALUE;
    @Unique
    private static double electroEnergetics$relYMax = -Double.MAX_VALUE;

    // ------------------------------------------------- 1. the discarded vertical component

    /** Scopes the two redirects below to train carriages, and clears state on the way out. */
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
     * <p>Wrapped rather than recomputed, because {@code collide} is package-private and cannot be
     * called from here, and recording the real value keeps this in step with whatever the solver
     * decided. {@code ordinal = 1} selects the call at {@code :308}; ordinal 0 is {@code :283}.
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
     * {@code :284}, which already adds its {@code .y} and must not be touched twice. Ordinals
     * were read from the shipped bytecode: {@code Entity.setPos} appears exactly twice in
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

    // ------------------------------------------------- 2. the height held across ticks

    @Inject(method = "collideEntities", at = @At("RETURN"), remap = false)
    private static void electroEnergetics$restoreHeight(AbstractContraptionEntity contraption,
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

            // Carried horizontally means genuinely in contact with this carriage. This is the
            // same test the horizontal carry itself satisfies, so it is exactly the set of
            // riders whose weight Create is failing to support - and an airborne or departing
            // rider fails it and is left alone.
            double carriedHorizontally = Math.hypot(
                    player.getX() - before.x, player.getZ() - before.z);
            if (carriedHorizontally <= electroEnergetics$motion * 0.5d)
                return;

            long now = contraption.level().getGameTime();

            // The contact point's motion this tick is where the floor went; the held height
            // advances by it, so the target tracks the carriage rather than a fixed world Y.
            Vec3 carry = contraption.getContactPointMotion(player.position());

            double expected;
            if (contraption.getId() == electroEnergetics$holdOwner
                    && now - electroEnergetics$holdTick <= HOLD_EXPIRY_TICKS
                    && !Double.isNaN(electroEnergetics$holdY)) {
                expected = electroEnergetics$holdY + carry.y;
            } else {
                // Nothing to continue from: adopt where they are. This is the re-acquisition
                // path, and it is why the hold cannot survive a carriage change or a long
                // absence and start applying a stale height.
                expected = player.getY();
            }

            double deficit = expected - player.getY();

            if (deficit > MAX_RESTORE) {
                // Far below where they should be: a real change of level, not missed support.
                // Take their position as the truth rather than yanking them up.
                expected = player.getY();
                electroEnergetics$rebasedTicks++;
            } else if (deficit > 1.0e-4d) {
                player.setPos(player.getX(), expected, player.getZ());
                electroEnergetics$restoredTicks++;
                electroEnergetics$restoreSum += deficit;
            } else if (deficit < -1.0e-4d) {
                // Above the held height: a step up, or the first frame of a jump. Follow them,
                // which is what makes a step up work and keeps a jump from being pulled down.
                expected = player.getY();
            }

            electroEnergetics$holdY = expected;
            electroEnergetics$holdOwner = contraption.getId();
            electroEnergetics$holdTick = now;

            // Travelling with the floor means no downward speed accumulates, so the carried
            // rider is not falling as far as vanilla is concerned.
            Vec3 own = player.getDeltaMovement();
            if (own.y < carry.y)
                player.setDeltaMovement(own.x, carry.y, own.z);
            player.fallDistance = 0;

            double relY = player.getY() - contraption.getY();
            if (relY < electroEnergetics$relYMin) electroEnergetics$relYMin = relY;
            if (relY > electroEnergetics$relYMax) electroEnergetics$relYMax = relY;
            electroEnergetics$carriedTicks++;

            electroEnergetics$maybeSummary(now, player);
        } catch (Throwable ignored) {
            // A carry correction must never be the thing that breaks the client.
            electroEnergetics$current = null;
            electroEnergetics$beforePos = null;
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
                "CARRY FIX carried={} restored={}/{} avgRestore={} rebased={} relY=[{},{}] "
                        + "nowRelY={} vy={} onGround={}",
                t, electroEnergetics$restoredTicks, t,
                String.format("%.3f", electroEnergetics$restoredTicks == 0 ? 0d
                        : electroEnergetics$restoreSum / electroEnergetics$restoredTicks),
                electroEnergetics$rebasedTicks,
                String.format("%.3f", electroEnergetics$relYMin),
                String.format("%.3f", electroEnergetics$relYMax),
                String.format("%.3f", player.getY()),
                String.format("%.3f", player.getDeltaMovement().y),
                player.onGround());

        electroEnergetics$carriedTicks = 0;
        electroEnergetics$restoredTicks = 0;
        electroEnergetics$rebasedTicks = 0;
        electroEnergetics$restoreSum = 0;
        electroEnergetics$relYMin = Double.MAX_VALUE;
        electroEnergetics$relYMax = -Double.MAX_VALUE;
    }
}
