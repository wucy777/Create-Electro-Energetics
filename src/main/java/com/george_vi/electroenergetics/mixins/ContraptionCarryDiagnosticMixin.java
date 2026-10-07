package com.george_vi.electroenergetics.mixins;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.ContraptionCollider;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Third-generation diagnostic for the rider sinking through the carriage floor. Read-only.
 *
 * <h2>What is established, and what each generation got wrong</h2>
 *
 * <p>The measured facts, which no longer need re-deriving. The rider is carried horizontally
 * perfectly - {@code carried/motion} is 0.993 to 1.000 - and every defect is VERTICAL. Against a
 * carriage sitting at a constant Y of -59.000, the rider oscillated between -60.000 and -57.560,
 * a relative height swinging from +1.44 to -1.00 Blocks, and settled at -60.000, which is the
 * rail surface. The descent is a steady 0.076 Blocks per tick with {@code vy} pinned at -0.08
 * for as long as it lasts, so it is not an accelerating fall: it is one gravity step per tick
 * that never accumulates and never gets undone. All the while {@code onGround} reads true and
 * {@code fallDistance} reads 0.0, which is a state Create forces rather than one the game
 * arrived at.
 *
 * <ul>
 *   <li><b>H1, the capture window is too small - DISPROVED.</b> {@code BEYOND WINDOW} appeared
 *       zero times.</li>
 *   <li><b>H2, the damage path launches them - NOT OBSERVED.</b> {@code ownDelta} stayed at
 *       gravity throughout.</li>
 *   <li><b>H3, two carriages carry one rider - real but rare.</b> Two occurrences.</li>
 *   <li><b>H4, savePlayerFromClipping lifts them - DISPROVED, and the probe that "proved" it was
 *       faulty.</b> {@code SNAP} appeared zero times in three logs, but that is not evidence the
 *       function never ran: the probe only logged when the rider actually MOVED, and that
 *       function returns early in several places without moving anyone. It also turns out to be
 *       gated on a condition this situation may never satisfy - see below. This version
 *       therefore records every CALL, not only every effect.</li>
 * </ul>
 *
 * <h2>The candidate this version tests</h2>
 *
 * <p>Create holds a rider on a carriage up through two paths, and this situation may satisfy
 * neither.
 *
 * <p>Path one is the hard-collision branch, {@code :283-285}, which only adjusts Y when
 * {@code totalResponse} is non-zero - i.e. when the rider is actually penetrating something.
 * Resting on a floor is not penetrating, so a resting rider gets {@code allowedMovement.y = 0}
 * from it.
 *
 * <p>Path two is the floor snap, and it is armed and fired under conditions that a level track
 * never meets:
 *
 * <pre>
 *   ContraptionCollider.java:363   speed = contraptionMotion.multiply(0, 1, 0).lengthSqr()
 *   ContraptionCollider.java:367   if (speed &lt; 0.05) return;      VERTICAL motion only
 * </pre>
 *
 * <p>On level track the carriage's vertical motion is about zero, so {@code :367} returns every
 * tick and the snap never reaches the ray that would find the floor. Meanwhile {@code :309-310},
 * the surface branch that actually carries a standing rider, writes X and Z and passes
 * {@code entityPosition.y} straight through - the vertical component of the contact-point motion
 * is discarded there.
 *
 * <p>That combination would produce exactly what was measured: horizontal carry perfect,
 * vertical support absent, one gravity step per tick, and {@code onGround} asserted true
 * anyway by {@code :303}. It would also explain why Create's own contraptions do not show it -
 * they are slow enough that a tenth of a Block per tick is not noticed, and the same missing
 * support is present in both cases.
 *
 * <h2>How it is tested rather than argued</h2>
 *
 * <p>Three questions, each answered by an existing public field or a return value, so nothing
 * has to be inferred:
 *
 * <ol>
 *   <li><b>Is the rider registering as touching the carriage at all?</b>
 *       {@code :295 registerColliding} runs ONLY inside {@code if (surfaceCollision)}, and
 *       {@code collidingEntities} is public. So membership of that map IS the value of
 *       surfaceCollision, read without touching the local variable.</li>
 *   <li><b>Do the two snap functions run, and what do they decide?</b> Recorded on entry and on
 *       exit including early returns, which is what the previous version failed to do.</li>
 *   <li><b>What is the carriage's VERTICAL motion?</b> Recorded as its own field, since that is
 *       the quantity {@code :367} gates on and the one number the previous logs never
 *       captured.</li>
 * </ol>
 *
 * <h2>Cost</h2>
 *
 * <p>Silent below {@link #MIN_INTERESTING_MOTION}. Detail lines are capped hard; the main output
 * is a periodic summary of counts, which is what settles the question. No allocation unless a
 * line is written, and nothing is written below the speed gate.
 */
@Mixin(ContraptionCollider.class)
public class ContraptionCarryDiagnosticMixin {

    @Unique
    private static final Logger electroEnergetics$LOG = LoggerFactory.getLogger("CEE-CARRY");

    /** Below this carriage speed, in Blocks/tick, nothing is logged. */
    @Unique
    private static final double MIN_INTERESTING_MOTION = 0.5d;

    /** Hard stop so a long session cannot grow the log without bound. */
    @Unique
    private static final int MAX_LINES = 2500;

    /** Per-category caps. */
    @Unique
    private static final int MAX_PER_KIND = 400;

    /** Emit the summary every this many ticks. */
    @Unique
    private static final int SUMMARY_EVERY = 40;

    @Unique
    private static final Vec3 electroEnergetics$NONE = new Vec3(Double.NaN, Double.NaN, Double.NaN);

    // ---- per-call carry state ----

    @Unique
    private static Vec3 electroEnergetics$beforePos = electroEnergetics$NONE;
    @Unique
    private static double electroEnergetics$motion;
    @Unique
    private static double electroEnergetics$motionY;

    // ---- ownership ----

    @Unique
    private static int electroEnergetics$ownerId = -1;
    @Unique
    private static long electroEnergetics$ownerTick = Long.MIN_VALUE;

    // ---- snap call state ----

    @Unique
    private static double electroEnergetics$snapYBefore;
    @Unique
    private static Vec3 electroEnergetics$snapPosBefore = electroEnergetics$NONE;

    // ---- counters, reset each summary ----

    @Unique
    private static int electroEnergetics$ticks;
    @Unique
    private static int electroEnergetics$surfaceTicks;
    @Unique
    private static int electroEnergetics$snapClientCalls;
    @Unique
    private static int electroEnergetics$snapInnerCalls;
    @Unique
    private static int electroEnergetics$snapInnerTrue;
    @Unique
    private static int electroEnergetics$snapMovedRider;
    @Unique
    private static double electroEnergetics$vySum;
    @Unique
    private static int electroEnergetics$vyCount;
    @Unique
    private static double electroEnergetics$motionYSum;
    @Unique
    private static double electroEnergetics$relYMin = Double.MAX_VALUE;
    @Unique
    private static double electroEnergetics$relYMax = -Double.MAX_VALUE;
    @Unique
    private static double electroEnergetics$sinkPerTickSum;
    @Unique
    private static int electroEnergetics$sinkTicks;

    // ---- lifetime counters ----

    @Unique
    private static int electroEnergetics$lines;
    @Unique
    private static int electroEnergetics$details;
    @Unique
    private static long electroEnergetics$lastSummary;
    @Unique
    private static boolean electroEnergetics$cappedLogged;

    // ------------------------------------------------------------------ carry

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

            Vec3 motion = contraption.position().subtract(contraption.getPrevPositionVec());
            electroEnergetics$motion = motion.length();
            electroEnergetics$motionY = motion.y;

            if (electroEnergetics$motion < MIN_INTERESTING_MOTION)
                return;

            if (!contraption.getBoundingBox().inflate(2d).expandTowards(0, 32, 0)
                    .contains(player.position()))
                return;

            electroEnergetics$beforePos = player.position();
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

            double carried = player.position().distanceTo(before);
            boolean movedIt = carried > electroEnergetics$motion * 0.5d;
            long now = contraption.level().getGameTime();
            boolean isOwner = contraption.getId() == electroEnergetics$ownerId
                    && now - electroEnergetics$ownerTick <= 1;

            if (movedIt) {
                electroEnergetics$ownerId = contraption.getId();
                electroEnergetics$ownerTick = now;
                isOwner = true;
            }

            // Question 1, answered without touching a local: registerColliding is called ONLY
            // inside `if (surfaceCollision)`, and the map is public.
            boolean surface = contraption.collidingEntities.containsKey(player);

            if (isOwner) {
                electroEnergetics$ticks++;
                if (surface)
                    electroEnergetics$surfaceTicks++;
                electroEnergetics$motionYSum += electroEnergetics$motionY;

                double vy = player.getDeltaMovement().y;
                electroEnergetics$vySum += vy;
                electroEnergetics$vyCount++;

                double relY = player.getY() - contraption.getY();
                if (relY < electroEnergetics$relYMin) electroEnergetics$relYMin = relY;
                if (relY > electroEnergetics$relYMax) electroEnergetics$relYMax = relY;

                if (vy < -0.01d) {
                    electroEnergetics$sinkTicks++;
                    electroEnergetics$sinkPerTickSum += -vy;
                }

                electroEnergetics$maybeSummary(now, player, relY, surface);
            }
        } catch (Throwable ignored) {
            // A diagnostic must never be the thing that breaks the game.
        }
    }

    // ------------------------------------------------------------------- snap

    /** Outer gate: armed by safetyLock and the vertical-motion test at :363-368. */
    @Inject(method = "saveClientPlayerFromClipping", at = @At("HEAD"), remap = false)
    private static void electroEnergetics$outerSnapHead(AbstractContraptionEntity contraption,
                                                        Vec3 contraptionMotion, CallbackInfo ci) {
        electroEnergetics$snapPosBefore = electroEnergetics$NONE;
        try {
            electroEnergetics$snapClientCalls++;
            LocalPlayer player = Minecraft.getInstance().player;
            if (player == null)
                return;
            electroEnergetics$snapYBefore = player.getY();
            electroEnergetics$snapPosBefore = player.position();
        } catch (Throwable ignored) {
            electroEnergetics$snapPosBefore = electroEnergetics$NONE;
        }
    }

    @Inject(method = "saveClientPlayerFromClipping", at = @At("RETURN"), remap = false)
    private static void electroEnergetics$outerSnapReturn(AbstractContraptionEntity contraption,
                                                          Vec3 contraptionMotion, CallbackInfo ci) {
        try {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player == null || Double.isNaN(electroEnergetics$snapPosBefore.x))
                return;
            double dy = player.getY() - electroEnergetics$snapYBefore;
            if (Math.abs(dy) > 1.0e-4d) {
                electroEnergetics$snapMovedRider++;
                electroEnergetics$detail("SNAP moved dy={} motionY={} motion={} carriage={} at={}",
                        f(dy), f(contraptionMotion.y), f(contraptionMotion.length()),
                        contraption.getId(), v(player.position()));
            }
        } catch (Throwable ignored) {
            // A diagnostic must never be the thing that breaks the game.
        }
    }

    /** The ray itself, and its boolean: false means it found no floor. */
    @Inject(method = "savePlayerFromClipping", at = @At("HEAD"), remap = false)
    private static void electroEnergetics$innerSnapHead(Player entity,
                                                        AbstractContraptionEntity contraption,
                                                        Vec3 contraptionMotion, double yStartOffset,
                                                        CallbackInfoReturnable<Boolean> cir) {
        try {
            electroEnergetics$snapInnerCalls++;
        } catch (Throwable ignored) {
            // read-only
        }
    }

    @Inject(method = "savePlayerFromClipping", at = @At("RETURN"), remap = false)
    private static void electroEnergetics$innerSnapReturn(Player entity,
                                                          AbstractContraptionEntity contraption,
                                                          Vec3 contraptionMotion, double yStartOffset,
                                                          CallbackInfoReturnable<Boolean> cir) {
        try {
            boolean found = Boolean.TRUE.equals(cir.getReturnValue());
            if (found)
                electroEnergetics$snapInnerTrue++;
            electroEnergetics$detail("SNAP inner foundFloor={} motionY={} motion={} carriage={} "
                            + "yStartOffset={} at={}",
                    found, f(contraptionMotion.y), f(contraptionMotion.length()),
                    contraption.getId(), f(yStartOffset), v(entity.position()));
        } catch (Throwable ignored) {
            // read-only
        }
    }

    // ---------------------------------------------------------------- summary

    @Unique
    private static void electroEnergetics$maybeSummary(long now, LocalPlayer player, double relY,
                                                       boolean surface) {
        if (now - electroEnergetics$lastSummary < SUMMARY_EVERY)
            return;
        if (electroEnergetics$lastSummary == Long.MIN_VALUE) {
            electroEnergetics$lastSummary = now;
            return;
        }
        electroEnergetics$lastSummary = now;
        if (electroEnergetics$ticks == 0)
            return;

        int t = electroEnergetics$ticks;
        electroEnergetics$LOG.info(
                "CARRY SUM ticks={} surfaceReg={}/{} ({}%) snapOuterCalls={} snapInnerCalls={} "
                        + "snapInnerFoundFloor={} snapMovedRider={} motionYavg={} vyAvg={} "
                        + "relY=[{},{}] nowRelY={} sinkTicks={}/{} avgSinkStep={} onGround={} "
                        + "fallDist={}",
                t, electroEnergetics$surfaceTicks, t,
                String.format("%.0f", 100.0 * electroEnergetics$surfaceTicks / t),
                electroEnergetics$snapClientCalls, electroEnergetics$snapInnerCalls,
                electroEnergetics$snapInnerTrue, electroEnergetics$snapMovedRider,
                f(electroEnergetics$motionYSum / t),
                f(electroEnergetics$vyCount == 0 ? 0 : electroEnergetics$vySum / electroEnergetics$vyCount),
                f(electroEnergetics$relYMin), f(electroEnergetics$relYMax), f(relY),
                electroEnergetics$sinkTicks, t,
                f(electroEnergetics$sinkTicks == 0 ? 0
                        : electroEnergetics$sinkPerTickSum / electroEnergetics$sinkTicks),
                player.onGround(), f(player.fallDistance));

        // Reset the window so each summary describes its own interval.
        electroEnergetics$ticks = 0;
        electroEnergetics$surfaceTicks = 0;
        electroEnergetics$snapClientCalls = 0;
        electroEnergetics$snapInnerCalls = 0;
        electroEnergetics$snapInnerTrue = 0;
        electroEnergetics$snapMovedRider = 0;
        electroEnergetics$vySum = 0;
        electroEnergetics$vyCount = 0;
        electroEnergetics$motionYSum = 0;
        electroEnergetics$relYMin = Double.MAX_VALUE;
        electroEnergetics$relYMax = -Double.MAX_VALUE;
        electroEnergetics$sinkPerTickSum = 0;
        electroEnergetics$sinkTicks = 0;
    }

    @Unique
    private static void electroEnergetics$detail(String format, Object... args) {
        if (electroEnergetics$lines >= MAX_LINES) {
            if (!electroEnergetics$cappedLogged) {
                electroEnergetics$cappedLogged = true;
                electroEnergetics$LOG.info("CARRY diagnostic hit its {} line cap", MAX_LINES);
            }
            return;
        }
        if (electroEnergetics$details++ >= MAX_PER_KIND)
            return;
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
