package com.george_vi.electroenergetics.mixins;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.ContraptionCollider;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Diagnostic for why a rider is thrown off a train at this mod's speeds.
 *
 * <p>Read-only. It moves no entity and sets no velocity; it observes the carry and writes a
 * line when something anomalous happened. It exists because four previous attempts of mine to
 * fix the ejection reasoned from reading the code and all four were wrong, whereas the single
 * diagnostic ever installed produced the one piece of evidence that settled the question. So
 * this is that method used deliberately instead of as a last resort.
 *
 * <h2>The three candidates it discriminates</h2>
 *
 * <p>All three live in {@code ContraptionCollider}, and all three are constants calibrated for
 * Create's own 0.1-0.5 Blocks/tick, which at this mod's top speed is 4.86.
 *
 * <p><b>H1 - the capture window cannot keep up.</b> {@code :100} selects candidate riders with
 * {@code bounds.inflate(2)}, two Blocks. At 4.86 Blocks/tick that margin is SMALLER THAN ONE
 * TICK OF TRAVEL, so a rider who loses a single tick - because {@code :308}'s
 * {@code collide(contactPointMotion, entity)} was partly blocked by a world block, a tunnel
 * wall, or a platform edge - ends up 4.86 Blocks behind, outside the window, and can never be
 * caught again. The next catch corrects several Blocks at once, which is the throw.
 * Discriminated by testing the rider against exactly the box Create uses and against the same
 * box widened by one tick of travel.
 *
 * <p><b>H2 - the damage path launches them.</b> {@code handleDamageFromTrain} at {@code :451}
 * computes {@code diffMotion = contraptionMotion - entity.getDeltaMovement()}, and a standing
 * rider's own delta movement is gravity alone, so at this mod's speeds {@code diffMotion} is
 * about 4.86 instead of about 0. That passes the {@code :453} gate, and {@code :477-480} then
 * adds {@code damage * 4} Blocks/tick to their motion - clamped at 3, which is 60 m/s. Only two
 * gates stand in front of it, {@code :433 onGround} and {@code :442 already-colliding}, and
 * both are exactly what fails at the moment a rider is being re-caught. Discriminated by
 * logging the rider's own delta movement beside the carriage's motion.
 *
 * <p><b>H3 - two carriages carry the same rider.</b> {@code :96 skipClientPlayer} is a local of
 * one {@code collideEntities} call and each carriage is a separate call, so one player can be
 * carried once per overlapping carriage, and {@code inflate(2)} makes adjacent carriages'
 * boxes overlap by construction. Doubling 0.2 Blocks/tick is invisible; doubling 4.86 is not.
 * Discriminated by counting, per game tick, how many contraptions actually moved the rider.
 *
 * <h2>Only real riders are tracked, never bystanders</h2>
 *
 * <p>A player standing beside the track is a perfectly ordinary player who happens not to move
 * while the carriage does, so a naive "the carriage travelled and they did not" test would
 * report them as a lost rider every tick and bury the real signal. A player is therefore
 * tracked only when this carriage owns them: either they are inside its own box right now, or
 * this same carriage carried them within the last few ticks. A bystander is neither, so the
 * only LOST lines that can appear are real ones - a rider this carriage was carrying and then
 * failed to.
 *
 * <h2>Cost</h2>
 *
 * <p>Silent below {@link #MIN_INTERESTING_MOTION}, which is above anything Create's own
 * contraptions reach, so an ordinary world logs nothing at all. Above it, only anomalies are
 * logged plus an occasional healthy sample, per-category caps stop any one kind of anomaly
 * from dominating, and {@link #MAX_LINES} is a hard stop so a long session cannot fill a log.
 * Two box tests and a little arithmetic per contraption per tick, and no allocation unless a
 * line is actually written.
 */
@Mixin(ContraptionCollider.class)
public class ContraptionCarryDiagnosticMixin {

    @Unique
    private static final Logger electroEnergetics$LOG = LoggerFactory.getLogger("CEE-CARRY");

    /**
     * Below this carriage speed the carry is not what is under investigation, in Blocks/tick.
     * Create's own contraptions sit at 0.1-0.5, so this keeps the diagnostic out of every
     * ordinary world and out of the log of anyone not driving one of these trains.
     */
    @Unique
    private static final double MIN_INTERESTING_MOTION = 0.5d;

    /** Hard stop, so a long session cannot grow the log without bound. */
    @Unique
    private static final int MAX_LINES = 3000;

    /** Per-category caps, so one kind of anomaly cannot drown out the others. */
    @Unique
    private static final int MAX_PER_KIND = 600;

    /** Log every Nth healthy carry, so the log shows what normal looks like too. */
    @Unique
    private static final int OK_SAMPLE_EVERY = 20;

    /** Ticks a carriage stays the tracked owner after it last carried the rider. */
    @Unique
    private static final int OWNER_GRACE_TICKS = 10;

    @Unique
    private static final Vec3 electroEnergetics$NONE = new Vec3(Double.NaN, Double.NaN, Double.NaN);

    // ---- per-call state, written at HEAD and consumed at RETURN ----

    @Unique
    private static Vec3 electroEnergetics$beforePos = electroEnergetics$NONE;
    @Unique
    private static Vec3 electroEnergetics$beforeDelta = Vec3.ZERO;
    @Unique
    private static double electroEnergetics$motion;
    @Unique
    private static boolean electroEnergetics$inBox;
    @Unique
    private static boolean electroEnergetics$inWindow;
    @Unique
    private static boolean electroEnergetics$inWide;

    // ---- carry ownership ----

    @Unique
    private static int electroEnergetics$ownerId = -1;
    @Unique
    private static long electroEnergetics$ownerTick = Long.MIN_VALUE;

    // ---- per-tick tally for H3 ----

    @Unique
    private static long electroEnergetics$tallyTick = Long.MIN_VALUE;
    @Unique
    private static int electroEnergetics$carriedThisTick;

    // ---- bookkeeping and counters ----

    @Unique
    private static int electroEnergetics$lines;
    @Unique
    private static int electroEnergetics$lost;
    @Unique
    private static int electroEnergetics$lostInsideWindow;
    /** Occurrences, which the summary reports, kept apart from the lines actually written. */
    @Unique
    private static int electroEnergetics$doubleSeen;
    @Unique
    private static int electroEnergetics$beyondSeen;
    @Unique
    private static int electroEnergetics$lostLogged;
    @Unique
    private static int electroEnergetics$doubleLogged;
    @Unique
    private static int electroEnergetics$beyondLogged;
    @Unique
    private static int electroEnergetics$okSeen;
    @Unique
    private static int electroEnergetics$okLogged;
    @Unique
    private static long electroEnergetics$lastSummaryTick = Long.MIN_VALUE;
    @Unique
    private static boolean electroEnergetics$cappedLogged;

    @Inject(method = "collideEntities", at = @At("HEAD"), remap = false)
    private static void electroEnergetics$beforeCarry(AbstractContraptionEntity contraption,
                                                      CallbackInfo ci) {
        electroEnergetics$beforePos = electroEnergetics$NONE;
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

            Vec3 where = player.position();
            AABB box = contraption.getBoundingBox();

            // Mirrors ContraptionCollider:100-101 exactly - inflate(2) plus
            // expandTowards(0, 32, 0) - so inWindow means what Create's own query means
            // rather than an approximation of it.
            electroEnergetics$inBox = box.contains(where);
            electroEnergetics$inWindow = box.inflate(2d).expandTowards(0, 32, 0).contains(where);
            // And what that window WOULD have to be to still see them. The measurement that
            // settles H1.
            electroEnergetics$inWide = box.inflate(2d + electroEnergetics$motion)
                    .expandTowards(0, 32, 0).contains(where);

            // Track only a player this carriage owns, so a bystander beside the track can
            // never be reported as a rider who was dropped.
            boolean owned = electroEnergetics$inBox
                    || (contraption.getId() == electroEnergetics$ownerId
                        && Math.abs(contraption.level().getGameTime()
                                    - electroEnergetics$ownerTick) <= OWNER_GRACE_TICKS);
            if (!owned)
                return;

            electroEnergetics$beforePos = where;
            electroEnergetics$beforeDelta = player.getDeltaMovement();
        } catch (Throwable ignored) {
            electroEnergetics$beforePos = electroEnergetics$NONE;
        }
    }

    @Inject(method = "collideEntities", at = @At("RETURN"), remap = false)
    private static void electroEnergetics$afterCarry(AbstractContraptionEntity contraption,
                                                     CallbackInfo ci) {
        try {
            Vec3 before = electroEnergetics$beforePos;
            electroEnergetics$beforePos = electroEnergetics$NONE;
            if (Double.isNaN(before.x))
                return;

            LocalPlayer player = Minecraft.getInstance().player;
            if (player == null)
                return;

            // Between HEAD and RETURN of this call only this collider runs, so the whole of
            // this difference is what this carriage did to the rider.
            double carried = player.position().distanceTo(before);
            long tick = contraption.level().getGameTime();
            boolean moved = carried > electroEnergetics$motion * 0.5d;

            if (tick != electroEnergetics$tallyTick) {
                electroEnergetics$tallyTick = tick;
                electroEnergetics$carriedThisTick = 0;
            }
            if (moved) {
                electroEnergetics$carriedThisTick++;
                electroEnergetics$ownerId = contraption.getId();
                electroEnergetics$ownerTick = tick;
            }

            electroEnergetics$maybeSummary(tick);

            if (electroEnergetics$lines >= MAX_LINES) {
                if (!electroEnergetics$cappedLogged) {
                    electroEnergetics$cappedLogged = true;
                    electroEnergetics$LOG.info(
                            "CARRY diagnostic hit its {} line cap; further lines suppressed",
                            MAX_LINES);
                }
                return;
            }

            if (!moved) {
                // H1's signature: the carriage travelled and the rider did not go with it.
                electroEnergetics$lost++;
                if (electroEnergetics$inWindow)
                    electroEnergetics$lostInsideWindow++;
                if (electroEnergetics$lostLogged++ < MAX_PER_KIND)
                    log("CARRY LOST motion={} carried={} inBox={} inWindow={} inWindowWidened={} "
                                    + "carriage={} ownDelta={} at={}",
                            f(electroEnergetics$motion), f(carried),
                            electroEnergetics$inBox, electroEnergetics$inWindow,
                            electroEnergetics$inWide, contraption.getId(),
                            v(electroEnergetics$beforeDelta), v(before));
            } else if (electroEnergetics$carriedThisTick > 1) {
                // H3's signature: more than one carriage moved the same rider this tick.
                electroEnergetics$doubleSeen++;
                if (electroEnergetics$doubleLogged++ < MAX_PER_KIND)
                    log("CARRY DOUBLE count={} motion={} carried={} carriage={} ownDelta={} at={}",
                            electroEnergetics$carriedThisTick, f(electroEnergetics$motion),
                            f(carried), contraption.getId(),
                            v(electroEnergetics$beforeDelta), v(before));
            } else if (electroEnergetics$inWide && !electroEnergetics$inWindow) {
                // A rider Create could NOT see being carried anyway. If this appears, the
                // window is already too small and only positioning saved it.
                electroEnergetics$beyondSeen++;
                if (electroEnergetics$beyondLogged++ < MAX_PER_KIND)
                    log("CARRY BEYOND WINDOW motion={} carried={} carriage={} ownDelta={} at={}",
                            f(electroEnergetics$motion), f(carried), contraption.getId(),
                            v(electroEnergetics$beforeDelta), v(before));
            } else if (++electroEnergetics$okSeen % OK_SAMPLE_EVERY == 0
                    && electroEnergetics$okLogged++ < MAX_PER_KIND) {
                log("CARRY ok motion={} carried={} inWindow={} carriage={} at={}",
                        f(electroEnergetics$motion), f(carried),
                        electroEnergetics$inWindow, contraption.getId(), v(before));
            }
        } catch (Throwable ignored) {
            // A diagnostic must never be the thing that breaks the game.
        }
    }

    /**
     * A periodic one-line verdict, so the outcome can be read at a glance instead of inferred
     * from hundreds of detail lines.
     */
    @Unique
    private static void electroEnergetics$maybeSummary(long tick) {
        if (tick - electroEnergetics$lastSummaryTick < 200L)
            return;
        if (electroEnergetics$lastSummaryTick == Long.MIN_VALUE) {
            electroEnergetics$lastSummaryTick = tick;
            return;
        }
        electroEnergetics$lastSummaryTick = tick;
        if (electroEnergetics$lost == 0 && electroEnergetics$doubleSeen == 0
                && electroEnergetics$beyondSeen == 0 && electroEnergetics$okSeen == 0)
            return;
        electroEnergetics$LOG.info(
                "CARRY SUMMARY lost={} (of which inside the window={}) doubleCarry={} "
                        + "beyondWindow={} healthySampled={}",
                electroEnergetics$lost, electroEnergetics$lostInsideWindow,
                electroEnergetics$doubleSeen, electroEnergetics$beyondSeen,
                electroEnergetics$okSeen);
    }

    @Unique
    private static void log(String format, Object... args) {
        electroEnergetics$lines++;
        electroEnergetics$LOG.info(format, args);
    }

    @Unique
    private static String f(double value) {
        return String.format("%.3f", value);
    }

    @Unique
    private static String v(Vec3 vec) {
        return String.format("(%.2f,%.2f,%.2f)", vec.x, vec.y, vec.z);
    }
}
