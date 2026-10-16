package com.george_vi.electroenergetics.mixins;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Records every position write made to the local player, together with the caller that made it.
 *
 * <h2>Why this, and not another hypothesis</h2>
 *
 * <p>Seven explanations have been proposed for the rider being thrown from a moving train and six
 * have been disproved by running them. The last failure was conclusive, because its own
 * instrumentation reported both the outcome and the reason it could never have worked:
 *
 * <pre>
 *   CARRY CLEAR cleared=174  residualAvg=0.0699  residualMax=0.2269
 *   RIDER DUMP  STEP(step=12.37, car=3.09) DISMOUNT
 * </pre>
 *
 * <p>The clear ran hundreds of times, so it was definitely live - and the velocity it removed was
 * 0.07 Blocks per tick, three orders of magnitude too small to account for a twelve Block jump.
 * More decisively, on the recorded trigger tick the player's own horizontal velocity was EXACTLY
 * ZERO and they still moved 12.37 Blocks:
 *
 * <pre>
 *   t=741661  p=(336.50,-58.10,-411.95)  pax=1  step= 0.00   (passenger did not move at all)
 *   t=741662  p=(336.50,-57.98,-399.58)  pax=0  step=12.37   (12.37 = 4.00 x 3.09)
 * </pre>
 *
 * <p>Zero velocity cannot integrate into twelve Blocks of displacement. Whatever moves the rider
 * writes the position DIRECTLY, and no amount of reasoning about velocity, reference frames or
 * carriage counts will say which line does it. This records that line.
 *
 * <h2>Why setPosRaw is the one place that catches everything</h2>
 *
 * <p>Disassembling the shipped client jar shows every position write funnels into it:
 *
 * <pre>
 *   setPos(DDD)V        -> setPosRaw(DDD)V     (delegates at bytecode offset 5)
 *   absMoveTo(DDD)V     -> setPos(DDD)V
 *   absMoveTo(DDD,FF)V  -> absMoveTo(DDD)V
 *   moveTo(DDD,FF)V     -> setPosRaw(DDD)V
 * </pre>
 *
 * <p>Create's carry and its hard-collision push both call {@code setPos}; the dismount relocation
 * calls {@code absMoveTo}; loading, lerping and server corrections arrive through the others. All
 * of them are visible here. A tracer on {@code setPos} alone would have missed two of them - which
 * is precisely how a previous attempt at attribution produced a false all-clear.
 *
 * <h2>What each line means</h2>
 *
 * <p>The tick, a sequence number within that tick, how far the write moved the player from the
 * previous write, the position written, whether they were a passenger, and the caller. Several
 * writes in one tick with a large final distance is the signature being looked for; the caller
 * column names the code responsible for each one, with line numbers.
 *
 * <h2>Guaranteed capture</h2>
 *
 * <p>Ordinary logging is capped so a long session cannot fill the log, but a write that moves the
 * player more than {@link #BIG} Blocks is logged even once that cap is reached, under a separate
 * cap. The event being hunted is a large jump, so it cannot be lost to log exhaustion - the failure
 * mode that hid the previous experiment's result behind a cap.
 *
 * <h2>Cost</h2>
 *
 * <p>The proximity test is the only expensive part and is cached per tick, so it runs once per tick
 * rather than once per write. Nothing is formatted and no stack is walked unless a carriage is
 * within {@link #NEAR} Blocks. Read-only: it never modifies a position or a velocity.
 */
@Mixin(Entity.class)
public class RiderWriteTraceMixin {

    @Unique
    private static final Logger electroEnergetics$LOG = LoggerFactory.getLogger("CEE-WRITE");

    /** Recording radius in Blocks. Outside this the tracer does nothing at all. */
    @Unique
    private static final double NEAR = 24d;

    /** Ordinary log cap. */
    @Unique
    private static final int MAX_LINES = 1500;

    /** A write that moves the player at least this far is always logged. */
    @Unique
    private static final double BIG = 5d;

    /** Separate cap for those, so log exhaustion cannot hide the event being hunted. */
    @Unique
    private static final int MAX_BIG = 200;

    @Unique
    private static int electroEnergetics$lines;
    @Unique
    private static int electroEnergetics$bigs;
    @Unique
    private static long electroEnergetics$cachedTick = Long.MIN_VALUE;
    @Unique
    private static boolean electroEnergetics$armed;
    @Unique
    private static long electroEnergetics$writeTick = Long.MIN_VALUE;
    @Unique
    private static int electroEnergetics$seq;
    @Unique
    private static double electroEnergetics$prevX;
    @Unique
    private static double electroEnergetics$prevZ;
    @Unique
    private static int electroEnergetics$writesThisTick;
    @Unique
    private static double electroEnergetics$tickTravel;

    /**
     * Reports the write that just happened, if the local player is near a train carriage.
     *
     * <p>At RETURN, so the values reported are the ones actually applied.
     */
    @Inject(method = "setPosRaw", at = @At("RETURN"), remap = false)
    private void electroEnergetics$trace(double x, double y, double z, CallbackInfo ci) {
        try {
            if (!((Object) this instanceof LocalPlayer player))
                return;
            if (!player.level().isClientSide())
                return;

            long tick = player.level().getGameTime();

            // Proximity is the only costly part, so it is resolved once per tick and remembered.
            if (tick != electroEnergetics$cachedTick) {
                electroEnergetics$cachedTick = tick;
                electroEnergetics$armed = !player.level().getEntitiesOfClass(
                        com.simibubi.create.content.trains.entity.CarriageContraptionEntity.class,
                        player.getBoundingBox().inflate(NEAR)).isEmpty();
            }
            if (!electroEnergetics$armed)
                return;

            if (tick != electroEnergetics$writeTick) {
                // Report the tick that just ended if its total travel was larger than any single
                // carriage could account for. That is the signature, and it survives the cap.
                if (electroEnergetics$writeTick != Long.MIN_VALUE
                        && electroEnergetics$writesThisTick > 0
                        && electroEnergetics$tickTravel > BIG * 0.6d
                        && electroEnergetics$bigs++ < MAX_BIG) {
                    electroEnergetics$LOG.info(
                            "TICK t={} writes={} travel={} << suspicious",
                            electroEnergetics$writeTick, electroEnergetics$writesThisTick,
                            String.format("%.3f", electroEnergetics$tickTravel));
                }
                electroEnergetics$writeTick = tick;
                electroEnergetics$seq = 0;
                electroEnergetics$writesThisTick = 0;
                electroEnergetics$tickTravel = 0;
                electroEnergetics$prevX = x;
                electroEnergetics$prevZ = z;
            }
            electroEnergetics$seq++;
            electroEnergetics$writesThisTick++;

            double fromPrev = Math.hypot(x - electroEnergetics$prevX, z - electroEnergetics$prevZ);
            electroEnergetics$tickTravel += fromPrev;
            electroEnergetics$prevX = x;
            electroEnergetics$prevZ = z;

            boolean big = fromPrev >= BIG;
            if (big) {
                if (electroEnergetics$bigs++ >= MAX_BIG)
                    return;
            } else if (electroEnergetics$lines++ >= MAX_LINES) {
                return;
            }

            electroEnergetics$LOG.info(
                    "WRITE t={} #{} d={} -> ({},{},{}) pax={} veh={} caller={}",
                    tick, electroEnergetics$seq, String.format("%.3f", fromPrev),
                    String.format("%.2f", x), String.format("%.2f", y), String.format("%.2f", z),
                    player.isPassenger() ? "1" : "0",
                    player.getVehicle() == null ? "-" : player.getVehicle().getId(),
                    electroEnergetics$caller());
        } catch (Throwable ignored) {
            // A tracer must never be the thing that breaks the client.
        }
    }

    /**
     * The first frames outside this tracer and outside Entity - the code that chose to move them.
     *
     * <p>Three frames, because a Create frame on its own is often a helper whose caller is the
     * interesting one. Line numbers are included, which is the whole point: they name the statement.
     */
    @Unique
    private static String electroEnergetics$caller() {
        StackTraceElement[] stack = new Throwable().getStackTrace();
        StringBuilder sb = new StringBuilder();
        int taken = 0;
        for (StackTraceElement e : stack) {
            String cls = e.getClassName();
            if (cls.startsWith("com.george_vi.electroenergetics.mixins.RiderWriteTraceMixin"))
                continue;
            if (cls.equals("net.minecraft.world.entity.Entity"))
                continue;
            if (taken > 0)
                sb.append(" <- ");
            sb.append(cls.substring(cls.lastIndexOf('.') + 1))
              .append(':').append(e.getLineNumber());
            if (++taken >= 3)
                break;
        }
        return sb.length() == 0 ? "?" : sb.toString();
    }
}
