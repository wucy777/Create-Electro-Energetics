package com.george_vi.electroenergetics.mixins;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.ContraptionCollider;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Second-generation diagnostic for the rider ejection. Read-only.
 *
 * <h2>What the first generation settled, including where it was wrong</h2>
 *
 * <p>The first version tested three hypotheses and settled all three, which is why this is worth
 * recording rather than simply replacing:
 *
 * <ul>
 *   <li><b>H1, the capture window is too small - DISPROVED.</b> {@code BEYOND WINDOW} appeared
 *       zero times, and 153 of 161 {@code LOST} lines carried {@code inWindow=true}. A rider
 *       Create can see, inside the window Create uses, is not being lost by that window.</li>
 *   <li><b>H2, the damage path launches them - NOT OBSERVED.</b> The rider's own delta movement
 *       stayed at gravity, about -0.04, throughout.</li>
 *   <li><b>H3, two carriages carry one rider - real but rare.</b> Two occurrences. It exists and
 *       may still matter, but it cannot explain a repeated ejection.</li>
 * </ul>
 *
 * <p>It also had the flaw that produced those 153 lines: it counted any carriage that did not
 * move the rider as having LOST them. Adjacent carriages' boxes overlap by construction, so a
 * rider standing on carriage A is inside carriage B's box too, and B is CORRECT not to carry
 * them because A already did. This version only reports a loss for the carriage that actually
 * carried the rider, so what remains is real.
 *
 * <h2>What it measures instead</h2>
 *
 * <p>The surviving evidence is the rider's height. In the session that prompted this, they were
 * lifted 4.25 Blocks over about a second and then dropped:
 *
 * <pre>
 *   05.681  Y=-59.25   still carried
 *   05.732  Y=-59.15   carrying stops
 *   06.231  Y=-58.80
 *   06.879  Y=-56.29   sustained climb, about 0.21 Blocks per tick
 *   07.182  Y=-54.55   apex
 *   07.429  Y=-55.30   released, falling
 * </pre>
 *
 * <p>A sustained lift is not a launch, and two details point at one specific function.
 * {@code ContraptionCollider.savePlayerFromClipping} snaps the rider's height every tick to the
 * first carriage surface its ray finds:
 *
 * <pre>
 *   ContraptionCollider.java:406   rayLength = max(5, |entityY - yStart|)     a 5 Block ray
 *   ContraptionCollider.java:424   entity.setPos(entity.getX(), yStart - shortestDistance, ...)
 * </pre>
 *
 * <p>The climb stopped after 4.25 Blocks, which is what a 5 Block ray running out looks like. And
 * the snap is self-sustaining: it is armed at {@code :325} when {@code entity.onGround()}, while
 * {@code :303} sets {@code onGround} true every tick in the very surface branch that carries the
 * rider.
 *
 * <p>So this records that function directly - when it runs, and how far it moved the rider -
 * beside the carry itself. A run that lifts the rider will show the lift and the snap in the same
 * ticks, or it will not; either way H4 is settled rather than argued.
 *
 * <h2>Cost</h2>
 *
 * <p>Silent below {@link #MIN_INTERESTING_MOTION}, so an ordinary world logs nothing at all. Only
 * the owning carriage is sampled, once every {@link #SAMPLE_EVERY} ticks, plus every snap that
 * moved the rider and every real loss. Hard line cap.
 */
@Mixin(ContraptionCollider.class)
public class ContraptionCarryDiagnosticMixin {

    @Unique
    private static final Logger electroEnergetics$LOG = LoggerFactory.getLogger("CEE-CARRY");

    /** Below this carriage speed, in Blocks/tick, nothing is logged. */
    @Unique
    private static final double MIN_INTERESTING_MOTION = 0.5d;

    /** Hard stop, so a long session cannot grow the log without bound. */
    @Unique
    private static final int MAX_LINES = 3000;

    /** Per-category caps, so one anomaly cannot drown out the others. */
    @Unique
    private static final int MAX_PER_KIND = 800;

    /** Sample the owning carriage every this many ticks. */
    @Unique
    private static final int SAMPLE_EVERY = 10;

    @Unique
    private static final Vec3 electroEnergetics$NONE = new Vec3(Double.NaN, Double.NaN, Double.NaN);

    // ---- per-call carry state ----

    @Unique
    private static Vec3 electroEnergetics$beforePos = electroEnergetics$NONE;
    @Unique
    private static double electroEnergetics$motion;

    // ---- which carriage actually carries the rider ----

    @Unique
    private static int electroEnergetics$ownerId = -1;
    @Unique
    private static long electroEnergetics$ownerTick = Long.MIN_VALUE;

    // ---- snap measurement ----

    @Unique
    private static double electroEnergetics$snapYBefore;
    @Unique
    private static Vec3 electroEnergetics$snapPosBefore = electroEnergetics$NONE;

    // ---- counters ----

    @Unique
    private static int electroEnergetics$lines;
    @Unique
    private static int electroEnergetics$lost;
    @Unique
    private static int electroEnergetics$lostLogged;
    @Unique
    private static int electroEnergetics$snapSeen;
    @Unique
    private static int electroEnergetics$snapLogged;
    @Unique
    private static double electroEnergetics$snapClimbTotal;
    @Unique
    private static double electroEnergetics$snapClimbMax;
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

            electroEnergetics$motion = contraption.position()
                    .subtract(contraption.getPrevPositionVec())
                    .length();
            if (electroEnergetics$motion < MIN_INTERESTING_MOTION)
                return;

            // Exactly Create's own selection box, ContraptionCollider:100-101, so "inside"
            // means what Create means by it.
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
            }

            if (electroEnergetics$lines >= MAX_LINES) {
                if (!electroEnergetics$cappedLogged) {
                    electroEnergetics$cappedLogged = true;
                    electroEnergetics$LOG.info("CARRY diagnostic hit its {} line cap", MAX_LINES);
                }
                return;
            }

            // A loss is only real when the carriage that WAS carrying stops carrying. A
            // neighbouring carriage declining to carry a rider another is already carrying is
            // correct, and reporting it was the first version's flaw.
            if (!movedIt && isOwner) {
                electroEnergetics$lost++;
                if (electroEnergetics$lostLogged++ < MAX_PER_KIND)
                    log("CARRY LOST motion={} carried={} carriage={} ownDelta={} onGround={} at={}",
                            f(electroEnergetics$motion), f(carried), contraption.getId(),
                            v(player.getDeltaMovement()), player.onGround(), v(before));
                return;
            }

            if (isOwner && now % SAMPLE_EVERY == 0)
                log("CARRY owner motion={} carried={} ownDelta={} onGround={} fallDist={} "
                                + "at={} carriageAt={}",
                        f(electroEnergetics$motion), f(carried), v(player.getDeltaMovement()),
                        player.onGround(), f(player.fallDistance), v(player.position()),
                        v(contraption.position()));
        } catch (Throwable ignored) {
            // A diagnostic must never be the thing that breaks the game.
        }
    }

    // ------------------------------------------------------------------- snap

    /** Records every run of the floor snap, which is H4. */
    @Inject(method = "saveClientPlayerFromClipping", at = @At("HEAD"), remap = false)
    private static void electroEnergetics$beforeSnap(AbstractContraptionEntity contraption,
                                                     Vec3 contraptionMotion, CallbackInfo ci) {
        electroEnergetics$snapPosBefore = electroEnergetics$NONE;
        try {
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
    private static void electroEnergetics$afterSnap(AbstractContraptionEntity contraption,
                                                    Vec3 contraptionMotion, CallbackInfo ci) {
        try {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player == null || Double.isNaN(electroEnergetics$snapPosBefore.x))
                return;

            double dy = player.getY() - electroEnergetics$snapYBefore;
            boolean moved = Math.abs(dy) > 1.0e-4d;
            if (moved) {
                electroEnergetics$snapSeen++;
                electroEnergetics$snapClimbTotal += dy;
                if (dy > electroEnergetics$snapClimbMax)
                    electroEnergetics$snapClimbMax = dy;
            }

            if (electroEnergetics$lines >= MAX_LINES)
                return;

            if (moved && electroEnergetics$snapLogged++ < MAX_PER_KIND)
                log("SNAP dy={} (climb total={} max={}) carriage={} motion={} onGround={} "
                                + "fallDist={} before={} after={}",
                        f(dy), f(electroEnergetics$snapClimbTotal),
                        f(electroEnergetics$snapClimbMax), contraption.getId(),
                        f(contraptionMotion.length()), player.onGround(),
                        f(player.fallDistance), v(electroEnergetics$snapPosBefore),
                        v(player.position()));
        } catch (Throwable ignored) {
            // A diagnostic must never be the thing that breaks the game.
        }
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
