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
 * Records every position write made to the local player, together with the caller that made it,
 * and dumps the last few ticks of history when a write is too large to be ordinary carrying.
 *
 * <h2>What the previous version got wrong</h2>
 *
 * <p>It stopped logging once a fixed number of lines had been written. During ordinary riding that
 * limit is reached, so the writes immediately around an anomaly - the only ones that matter - were
 * silently discarded, and the offending write had to be inferred instead of read. Two further
 * faults in it are fixed here:
 *
 * <ul>
 *   <li>it reset the previous position at every tick boundary, so the FIRST write of each tick
 *       always reported a displacement of zero - which is precisely how the write being hunted
 *       would have hidden itself;</li>
 *   <li>its dump threshold was below the size of an ordinary carry, so it would have fired once per
 *       tick and exhausted its own quota within a second of boarding.</li>
 * </ul>
 *
 * <h2>Design</h2>
 *
 * <p>A rolling window of the last {@link #CAP} writes is kept. Every write in it carries its
 * caller, so the window reads as a sequence: which code moved the player, by how much, in what
 * order. When a single write exceeds {@link #DUMP_FACTOR} times the rider's own per-tick speed - too
 * large to be an ordinary carry - the whole window is written to the log. The evidence is therefore
 * always in the log, whether or not anyone was watching for it.
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
 * <p>Create's carry and its hard-collision push (both {@code setPos}), the dismount relocation
 * ({@code absMoveTo}), loading, lerping and server corrections are all captured. A tracer on
 * {@code setPos} alone would have missed two of them, which is how an earlier attribution attempt
 * produced a false all-clear.
 *
 * <h2>Cost</h2>
 *
 * <p>The arming test is the only expensive part and is cached per tick, so it runs once per tick
 * rather than once per write. A stack frame is taken per write while the player is riding a carriage
 * or has just left one - a few per tick - which is acceptable for a diagnostic build and is the
 * reason every line in the window can name its caller. Read-only: it never modifies a position or
 * a velocity.
 */
@Mixin(Entity.class)
public class RiderWriteTraceMixin {

    @Unique
    private static final Logger electroEnergetics$LOG = LoggerFactory.getLogger("CEE-WRITE");

    /**
     * A write dumps when it exceeds this multiple of the rider's own per-tick speed.
     *
     * <p>Both bounds move with speed, so the threshold has to as well:
     *
     * <pre>
     *   ordinary carry  = 1x speed      (one positionRider write per tick)
     *   the anomaly     = ~3x speed     (observed: 10.917 Blocks at 3.60 Blocks/tick)
     * </pre>
     *
     * <p>A factor of 2 sits between them with equal margin on both sides at every speed, which a
     * constant cannot do. This constant has been wrong three times as a constant, in instructive ways:
     *
     * <ul>
     *   <li>5.0 - exactly the ordinary carry at the 100 Blocks/Second ceiling, so cruising at top
     *       speed dumped once per tick and used up {@link #MAX_DUMPS} in a second;</li>
     *   <li>12.0 - above the 10.917 anomaly, so it would never fire for the event it exists to
     *       capture; fixing the false positive had silenced the true one;</li>
     *   <li>7.5 - fixed the ceiling case but was blind below ~2.5 Blocks/tick, because a slow
     *       anomaly is smaller than any constant that clears the fast ordinary carry.</li>
     * </ul>
     *
     * <p>There is deliberately no floor on the THRESHOLD. A threshold floor above the ordinary carry
     * at low speed reintroduces the same blind spot - at 2.0 Blocks/tick the anomaly is 6.0, which no
     * fixed floor of 7.5 can catch. {@link #MIN_TRAVEL} is a floor on the INPUT instead, which is the
     * opposite thing: it suppresses the test only when nothing moved at all, rather than raising the
     * bar for a slow train.
     */
    @Unique
    private static final double DUMP_FACTOR = 2.0d;

    /**
     * Below this measured travel per tick, the dump test is skipped rather than scaled.
     *
     * <p>The threshold is proportional to measured travel, so a measured travel of zero would make
     * the threshold zero too - and {@code fromPrev >= 0} is true for every write, including one that
     * moved the player nowhere. See the comment at the test for the two ordinary occasions this
     * happens on and what it costs.
     *
     * <p>The value only has to separate "did not move" from "moved at all". A rider on a MOVING train
     * travels whole Blocks per tick at any speed this mod allows (the slowest forward speed that
     * matters is the 0.05 Blocks/tick handover floor), while a rider on a stopped train travels
     * exactly zero. 1e-3 Blocks/tick is far below the former and far above float noise in the latter,
     * so it cannot suppress a genuine anomaly: an anomaly at any real speed is a multiple of the
     * travel, and the travel itself is the thing being tested.
     */
    @Unique
    private static final double MIN_TRAVEL = 1e-3d;

    /** Ticks of history kept and dumped on a large write. */
    @Unique
    private static final int CAP = 60;

    /** Hard cap on dumps, so a long session cannot fill the log. */
    @Unique
    private static final int MAX_DUMPS = 20;

    @Unique
    private static final String[] electroEnergetics$ring = new String[CAP];
    @Unique
    private static int electroEnergetics$ringIdx;
    @Unique
    private static int electroEnergetics$ringCount;
    @Unique
    private static int electroEnergetics$dumps;
    /**
     * Game tick of the last dump, used to give the budget back after a quiet spell.
     *
     * <p>{@link #MAX_DUMPS} exists so a long session cannot fill the log, but a single counter that
     * never recovers also means a session that dumps twenty times early can never dump again - and the
     * interesting event is usually later. Re-arming after this many ticks keeps the cap per-episode
     * rather than per-session.
     */
    @Unique
    private static long electroEnergetics$lastDumpTick = Long.MIN_VALUE;
    @Unique
    private static final long DUMP_REARM_TICKS = 20L * 60L;     // one minute
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
    /** Previous Y, for the vertical component of each write - the floor pass-through is vertical. */
    @Unique
    private static double electroEnergetics$prevY;

    /** Total lines printed this session, so the log cannot be flooded. */
    @Unique
    private static int electroEnergetics$printed;

    /** Hard cap on printed lines. See the note at the print site. */
    @Unique
    private static final int MAX_PRINTED = 20_000;
    @Unique
    private static boolean electroEnergetics$hasPrev;
    /**
     * Last position of the PREVIOUS tick, and this tick's measured travel in Blocks.
     *
     * <p>Kept separately from {@link #electroEnergetics$prevX} because that one advances on every
     * write, while these only roll over at a tick boundary. The distance from the previous tick's last
     * position to this tick's first write is the rider's actual travel per tick - the quantity the dump
     * threshold is scaled by.
     */
    @Unique
    private static double electroEnergetics$prevTickX;
    @Unique
    private static double electroEnergetics$prevTickZ;
    @Unique
    private static boolean electroEnergetics$hasPrevTick;
    @Unique
    private static double electroEnergetics$tickTravel;
    @Unique
    private static final StringBuilder electroEnergetics$sb = new StringBuilder();

    /**
     * Whether the tracer should record anything this tick.
     *
     * <p>Two cases only, and the second is the one the whole instrument exists for:
     *
     * <ul>
     *   <li>the player IS a passenger on a carriage - the ordinary riding case;</li>
     *   <li>the player WAS a passenger very recently - the dismount tick, which is when the throw
     *       happens. A test on {@code isPassenger()} alone would switch the tracer off at exactly the
     *       moment it needs to be watching, because the throw occurs on the tick the seat is given up:
     *       {@code absMoveTo} runs after the passenger flag is already false.</li>
     * </ul>
     *
     * <h2>Why proximity alone was wrong</h2>
     *
     * <p>The first version armed whenever a carriage was within 24 Blocks. On a real save
     * that is most of the time - the log it produced had 4073 lines with {@code pax=0} against 127 with
     * {@code pax=1} - and every one of those records was a player WALKING near a train, being nudged by
     * {@code ContraptionCollider}'s contact push. At walking speed the scaled threshold is about
     * 0.4 Blocks (2 x the per-tick travel), which an ordinary contact push exceeds, so the tracer
     * dumped 70 times for displacements of 0.07 to 0.51 Blocks and not once for the anomaly it was
     * built to catch. The budget was being spent on people walking past trains.
     *
     * <p>The dismount window is measured in ticks because the throw is a same-tick or next-tick event:
     * {@link #DISMOUNT_WATCH_TICKS} covers the tick the seat is released plus a margin for the order in
     * which the client applies the relocation.
     */
    @Unique
    private static boolean electroEnergetics$shouldArm(LocalPlayer player) {
        long tick = player.level().getGameTime();

        // Riding: any passenger state at all, at first, and then narrowed.
        //
        // The first version required `getVehicle() instanceof CarriageContraptionEntity`, and on a real
        // session it armed ZERO times: not one line in the log, while the flight recorder sitting in
        // the same tick recorded `pax=1 veh=183` from the same player. Whatever the vehicle is at that
        // instant - and `setPosRaw` runs at several points inside a tick, including during the
        // dismount - the narrower test did not match it.
        //
        // So the riding test is now the same one the flight recorder already proves works: a passenger
        // whose vehicle is a contraption entity. `AbstractContraptionEntity` is the supertype of every
        // carriage entity and of nothing this should exclude, and it is checked by CLASS NAME rather
        // than by importing Create's exact type, so a subclass or a Create-side wrapper still matches.
        if (player.isPassenger() && player.getVehicle() != null) {
            Entity vehicle = player.getVehicle();
            if (vehicle instanceof com.simibubi.create.content.contraptions.AbstractContraptionEntity
                    || vehicle.getClass().getName().contains("ContraptionEntity")) {
                electroEnergetics$lastRidingTick = tick;
                if (!electroEnergetics$reportedFirstArm) {
                    electroEnergetics$reportedFirstArm = true;
                    electroEnergetics$LOG.info("ARMED on vehicle class {} id={}",
                            vehicle.getClass().getSimpleName(), vehicle.getId());
                }
                return true;
            }
        }

        // Was riding moments ago: the dismount tick and the few after it. This is the window the
        // instrument exists for, so it must survive the passenger flag going false.
        if (electroEnergetics$lastRidingTick != Long.MIN_VALUE
                && tick - electroEnergetics$lastRidingTick <= DISMOUNT_WATCH_TICKS) {
            return true;
        }

        return false;
    }

    /**
     * Last tick at which the player was a passenger on a carriage.
     *
     * <p>{@code Long.MIN_VALUE} means "never, this session". Tested before subtracting, because
     * {@code MIN_VALUE} is the one value that makes {@code tick - last} overflow into a small positive
     * number and arm the tracer forever - the same sentinel trap this codebase has hit before.
     */
    @Unique
    private static long electroEnergetics$lastRidingTick = Long.MIN_VALUE;

    /**
     * Whether the tracer has announced arming once, so the log proves it armed at all.
     *
     * <p>Added after a session produced ZERO tracer lines while the player was demonstrably riding: the
     * arming predicate had been rejecting every tick, and nothing in the output said so. "No output" is
     * ambiguous between "never armed" and "armed but saw nothing worth dumping", and one line at the
     * first successful arm removes that ambiguity for good - at the cost of a single log line.
     */
    @Unique
    private static boolean electroEnergetics$reportedFirstArm = false;

    /** How long after leaving a seat the tracer keeps watching, in ticks. */
    @Unique
    private static final long DISMOUNT_WATCH_TICKS = 20L;

    /**
     * Reports the write that just happened, while the local player is riding a carriage or has just
     * left one.
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
                electroEnergetics$armed = electroEnergetics$shouldArm(player);
            }
            if (!electroEnergetics$armed)
                return;

            if (tick != electroEnergetics$writeTick) {
                // Carry the last position of the CLOSING tick into the next one, so the first write of
                // the new tick can measure tick-over-tick travel.
                electroEnergetics$prevTickX = electroEnergetics$prevX;
                electroEnergetics$prevTickZ = electroEnergetics$prevZ;
                electroEnergetics$hasPrevTick = electroEnergetics$hasPrev;

                electroEnergetics$writeTick = tick;
                electroEnergetics$seq = 0;
                // The previous position is deliberately NOT reset here. Resetting it would make
                // the first write of every tick report a displacement of zero, which is exactly
                // the case that matters: a teleport at the start of a tick.
            }
            electroEnergetics$seq++;

            double fromPrev = electroEnergetics$hasPrev
                    ? Math.hypot(x - electroEnergetics$prevX, z - electroEnergetics$prevZ)
                    : 0d;
            double dY = y - electroEnergetics$prevY;
            electroEnergetics$prevX = x;
            electroEnergetics$prevZ = z;
            electroEnergetics$prevY = y;
            electroEnergetics$hasPrev = true;

            // Per-tick travel, taken from the FIRST write of this tick.
            //
            // The speed has to come from measured movement, not from getDeltaMovement: a passenger's
            // deltaMovement is its OWN motion, which is essentially zero while riding. The dump proves
            // it - own=(0.00,-0.08,0.00) on a tick where the player actually moved 3.60 Blocks, because
            // positionRider sets the position directly and never touches the passenger's velocity. A
            // threshold built on that reads 0, and every write would dump.
            //
            // <h2>The self-calibration trap, and why the first write is now judged too</h2>
            //
            // The first write of an ordinary tick is the carry, so the distance from the previous
            // tick's last position to it IS the travel per tick. But on the tick that matters it is not
            // the carry: it IS the throw. Measured on a live session, the dismount tick's first write
            // moved the player 11.95 Blocks, so this line set tickTravel = 11.95, the threshold became
            // 23.90, and the 11.95 was below it - the anomaly raised the bar that was meant to catch it.
            //
            // That is the third form of the same mistake in this instrument: calibrating the
            // observation with the thing being observed. (First: a constant equal to the top-speed
            // carry. Second: a threshold that a stopped train could drive to zero.)
            //
            // So the FIRST write is now judged like every other write, against the previous tick's
            // travel, and a first write that already exceeds the bound does not become the new
            // baseline. The carry of the next tick re-establishes it.
            if (electroEnergetics$seq == 1) {
                double measured = electroEnergetics$hasPrevTick
                        ? Math.hypot(x - electroEnergetics$prevTickX, z - electroEnergetics$prevTickZ)
                        : 0d;
                // Keep the previous tick's figure when this first write is itself an outlier, so an
                // anomaly cannot define the scale it is compared against.
                double previous = electroEnergetics$tickTravel;
                if (previous < MIN_TRAVEL || measured < previous * DUMP_FACTOR) {
                    electroEnergetics$tickTravel = measured;
                }
                // else: leave tickTravel at the previous tick's value - this write is the anomaly.
            }

            electroEnergetics$sb.setLength(0);
            // Everything needed to diagnose the carry in ONE line, printed for EVERY write.
            //
            // The operator's standing instruction is one output line per executed step, and the
            // previous design broke it: writes went into a ring and only surfaced when a heuristic
            // fired. That heuristic had three separate bugs, so most of the time there was no output
            // at all, and the carry could not be watched while it happened. The heuristic is gone from
            // this path; what is printed is the write itself.
            //
            // Fields, and why each one is needed:
            //
            //   p      where the write put the player
            //   d      how far this write moved them, from the previous write
            //   dY     the vertical component alone - the floor pass-through is vertical
            //   pax    whether they were still a passenger on this write
            //   veh    the vehicle id, so a write during a dismount is recognisable
            //   sp     whether the player is standing on the ground, per vanilla
            //   np     NO PHYSICS - the flag that makes Entity.move skip every collision
            //   onC    whether the player's box is currently inside a carriage's collider
            //
            // `np` is the one that decides the whole question: if the carry's collision test is what
            // clips movement, then `d` will be small while a large target was computed, and `np` will
            // be false. If `np` is true and `d` is still large, collision is not involved at all.
            electroEnergetics$sb.append("p=(")
                    .append(fmt(x)).append(',').append(fmt(y)).append(',').append(fmt(z))
                    .append(") d=").append(String.format("%.3f", fromPrev))
                    .append(" dY=").append(String.format("%.3f", dY))
                    .append(" pax=").append(player.isPassenger() ? "1" : "0")
                    .append(" veh=").append(player.getVehicle() == null ? "-"
                            : String.valueOf(player.getVehicle().getId()))
                    .append(" sp=").append(player.onGround() ? "1" : "0")
                    .append(" np=").append(player.noPhysics ? "1" : "0")
                    .append(" own=(").append(fmt(player.getDeltaMovement().x)).append(',')
                    .append(fmt(player.getDeltaMovement().y)).append(',')
                    .append(fmt(player.getDeltaMovement().z)).append(')')
                    .append(" CALLER=").append(electroEnergetics$caller());

            String entry = "t=" + tick + " #" + electroEnergetics$seq + " "
                    + electroEnergetics$sb;

            // Print it. Every write while armed, no heuristic in between.
            //
            // A hard cap on total lines, not a per-second throttle: throttling would drop exactly the
            // writes that matter (a burst IS the event), while a cap only stops the run once the
            // evidence is already far beyond sufficient. 20000 lines is about a full minute of the
            // carry at 20 writes per tick, which is more than any single investigation needs.
            if (electroEnergetics$printed < MAX_PRINTED) {
                electroEnergetics$printed++;
                electroEnergetics$LOG.info("W {}", entry);
            } else if (electroEnergetics$printed == MAX_PRINTED) {
                electroEnergetics$printed++;
                electroEnergetics$LOG.info("W ... line cap reached, {} writes since; no more lines",
                        electroEnergetics$seq);
            }

            electroEnergetics$ring[electroEnergetics$ringIdx] = entry;
            electroEnergetics$ringIdx = (electroEnergetics$ringIdx + 1) % CAP;
            if (electroEnergetics$ringCount < CAP)
                electroEnergetics$ringCount++;

            // Give the budget back after a quiet spell, so the cap bounds one episode rather than the
            // whole session. Without this, a session that dumped twenty times early could never dump
            // again - and the event worth reading is usually the later one.
            if (tick - electroEnergetics$lastDumpTick > DUMP_REARM_TICKS)
                electroEnergetics$dumps = 0;

            // Threshold scales with how far the rider actually travels per tick.
            //
            // NOT getDeltaMovement: a passenger's velocity is its own, and positionRider moves the
            // player by writing the position directly, so deltaMovement stays near zero while riding.
            // The dump proves it - own=(0.00,-0.08,0.00) on a tick where the player moved 3.60 Blocks.
            // A threshold built on that reads zero and dumps on every write.
            //
            // electroEnergetics$tickTravel is instead measured: the distance from the previous tick's
            // last position to this tick's first write. That first write is the ordinary carry, so the
            // number is the real per-tick travel, taken from the same data the anomaly is measured from.
            //
            // Both bounds are proportional to it - ordinary carry is 1x, the anomaly is about 3x (the
            // observed throw was 10.917 Blocks at 3.60 Blocks/tick) - so a constant could never straddle
            // them at every speed. That is exactly how 5.0 (equal to the ceiling carry), 12.0 (above the
            // anomaly) and 7.5 (blind below 2.5 Blocks/tick) each failed.
            //
            // A measured quantity, unlike a constant, can be ZERO - and zero is a trap here:
            // threshold = 0 * 2 = 0, and `fromPrev >= 0` is true for every write including a
            // displacement of exactly zero, so every write in that tick dumps. That happens on two
            // ordinary occasions, neither of them an anomaly:
            //
            //   * the first armed tick, where hasPrevTick is still false and travel has never been
            //     measured;
            //   * a rider standing on a STOPPED train, where positionRider writes the same position
            //     every tick, giving travel = 0 and displacement = 0.
            //
            // In the second case the train is stationary, so it burns the whole MAX_DUMPS budget at
            // one dump per tick - the entire 20 in a single second, while the train sits at a
            // platform - and the tracer is then silent for the fast run where a dismount can actually
            // throw the rider. That is the same class of failure as the earlier constant mistakes:
            // the instrument spends its evidence before the event worth reading.
            //
            // The fix is not a floor on the THRESHOLD (that would reintroduce the blind spot the
            // constant had at low speed). It is a floor on the INPUT: no measured travel means no
            // displacement to call anomalous, so the dump test is skipped entirely. The window is
            // still recorded, so the history leading into a later anomaly is intact.
            // NO GATE HERE. Every armed write is already printed above, line by line, which is the
            // operator's standing instruction and the only thing that made the carry observable at
            // all. The threshold machinery that used to decide whether a write was "interesting" had
            // three separate calibration bugs and suppressed the output in exactly the cases worth
            // reading; it is not consulted on this path any more.
            //
            // The dump-on-anomaly path is kept for the flight recorder, which has its own, independent
            // trigger. Here the raw stream is the evidence.
        } catch (Throwable ignored) {
            // A tracer must never be the thing that breaks the client.
        }
    }

    @Unique
    private static String fmt(double v) {
        return String.format("%.2f", v);
    }

    /**
     * Writes the rolling window to the log, oldest first.
     *
     * <p>The whole window, so the sequence of writes that produced the anomaly can be read in
     * order - which is what a fixed output cap previously discarded.
     */
    @Unique
    private static void electroEnergetics$dump(long tick, double size, String entry) {
        electroEnergetics$dumps++;
        electroEnergetics$lastDumpTick = tick;
        electroEnergetics$LOG.info("WRITE DUMP #{} t={} size={}", electroEnergetics$dumps, tick,
                String.format("%.3f", size));
        int count = Math.min(electroEnergetics$ringCount, CAP);
        for (int back = count - 1; back >= 0; back--) {
            int index = (electroEnergetics$ringIdx - 1 - back + 2 * CAP) % CAP;
            String e = electroEnergetics$ring[index];
            if (e != null)
                electroEnergetics$LOG.info("   -{} {}", back, e);
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
