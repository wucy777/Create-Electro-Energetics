package com.george_vi.electroenergetics.content.railway_electrification;

import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.actors.trainControls.ControlsBlock;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Answers one question - is anybody aboard this train standing up - and puts them in a seat when there is
 * one to put them in.
 *
 * <h2>What "seated" means, and the mistake this class made first</h2>
 *
 * <p>A player sitting on a train does NOT ride a seat entity. They ride the carriage itself, and Create
 * records which seat index they occupy in {@code Contraption.seatMapping}. That mapping is the only
 * authority, and it is what {@code AbstractContraptionEntity.addSittingPassenger} writes and what
 * {@code getSeatOf(uuid)} reads.
 *
 * <p>This class originally tested {@code getVehicle() instanceof SeatEntity}. That is how a seat block
 * standing in the world works, and it is wrong for a train: every seated passenger rides the carriage, so
 * every one of them counted as standing. With the ceiling gated on that answer the train would have been
 * capped whenever anybody was aboard, and the seating pass would have tried to re-seat people who were
 * already sitting.
 *
 * <h2>Who counts as aboard</h2>
 *
 * <p>Already-seated riders come from Create's passenger chain and seat mapping. A standing player must have
 * a supporting block in the carriage's own local block map below their feet (a short jump above that surface
 * is tolerated). Create's collision set alone is not evidence of boarding: it also records side impacts when
 * a moving train hits somebody on the platform.
 *
 * <h2>Free seats come from the carriage, not the world</h2>
 *
 * <p>{@code Contraption.getSeats()} lists every seat position in the carriage and the seat mapping says
 * which are taken, so a free seat is an index in the former that is absent from the latter. Only THIS
 * train's carriages are read, so a nearer seat on another train, or a seat block standing in the world, can
 * never be used.
 */
public final class RiderSeatGate {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("electroenergetics/seatrestore");
    private RiderSeatGate() {}

    /**
     * Create's own top speed for a player-driven train, in Blocks per second.
     *
     * <p>Read from Create's own config rather than hardcoded: 28 was simply whatever that config happened to
     * say when this was written, and hardcoding it meant the two could disagree the moment the pack changed
     * its train speed - the mod would cap a train below what Create itself allows, or fail to cap one above.
     */
    public static double createCeilingBps() {
        // Hardcoded at the operator's request. Reading Create's own config was the right instinct but makes
        // the limit move under a pack update, and this figure is part of the train's behaviour here, not a
        // server preference.
        return 28d;
    }

    /** The same figure in Blocks per tick, which is what {@code maxSpeed()} returns. */
    public static double createCeilingBpt() {
        return createCeilingBps() / 20d;
    }

    /** The same figure in Blocks per tick, which is what {@code maxSpeed()} returns. */

    private static final Map<UUID, Boolean> CACHE = new HashMap<>();
    private static long cacheStamp = -1L;

    /** Whether every player aboard is sitting in a seat. */
    public static boolean allSeated(Train train) {
        if (train == null || train.carriages == null || train.carriages.isEmpty())
            return true;

        // Stamped on the level's own tick counter, not on the wall clock. A 50 ms wall-clock window drifts
        // against the game tick when the server is lagging: it can straddle two ticks (a stale answer
        // survives longer than a tick) or collapse two ticks into one. The game time cannot.
        long stamp = -1L;
        for (Carriage carriage : train.carriages) {
            CarriageContraptionEntity entity = carriage.anyAvailableEntity();
            if (entity != null) {
                stamp = entity.level().getGameTime();
                break;
            }
        }
        if (stamp < 0L)
            return true;                                  // nothing loaded: nobody aboard to be standing
        if (stamp != cacheStamp) {
            cacheStamp = stamp;
            CACHE.clear();
        }
        Boolean cached = CACHE.get(train.id);
        if (cached != null)
            return cached;

        boolean result = unseatedPlayers(train).isEmpty();
        CACHE.put(train.id, result);
        return result;
    }

    /**
     * Whether the train is at or above Create's ceiling.
     *
     * <p>{@code >=} and not {@code >}, because the ceiling is a place the train can actually sit: 28 is the
     * configured limit, so a train running at its limit is exactly 1.4 Blocks/tick and a strict comparison
     * is never true. The seating rule and the dismount lock both hung off that comparison and were
     * therefore unreachable at the speed they exist for.
     *
     * <p>The epsilon absorbs the float division; without it 28/20 does not round-trip and the train would
     * have to exceed its own ceiling to arm anything.
     */
    public static boolean atOrAboveCeiling(Train train) {
        // Deliberately still Blocks per tick, the way it always was.
        //
        // It was briefly changed to convert by the live tick rate, so that the gate would work under
        // /tick rate 500. That made the gate ACTIVE in a configuration nothing here was ever validated in, and
        // the paths behind it - the seating pass, the dismount lock - promptly threw the operator out of their
        // seat at 314 km/h. A gate that stays shut in a configuration that was never tested is the safer of the
        // two failures: at 500 tps this comparison reads the train as slow, the ceiling rule does not arm, and
        // the train behaves exactly as it did before any of this existed, which the operator confirms was fine.
        //
        // Making 500 honest is a whole-model change: twenty-eight conversions across TrainMixin, CatenaryModule
        // and GameEvents, on top of Create's own tick-rate assumptions, which this mod cannot fix.
        return train != null && Math.abs(train.speed) >= createCeilingBpt() - 1.0e-4d;
    }

    /**
     * Ticks per second, live, as a double.
     *
     * <p>Everything that converts between Blocks-per-tick and metres-per-second has to agree on this figure, or the
     * train answers three different speeds to three different subsystems. It is the same accessor {@link
     * #currentTickRate()} reads, returned as a double so the conversions do not lose precision.
     */
    public static double ticksPerSecond() {
        return currentTickRate();
    }

    /**
     * Blocks per tick to metres per second.
     *
     * <p>The conversion was a flat `* 20` in every caller, which is only correct at the default tick rate. Under
     * `/tick rate 200` a train whose speed field said 0.16 moved 32 Blocks a second while every reader was told it
     * was doing 3.2 - a factor of ten, in the direction that hides overspeed.
     */
    public static double bptToMs(double bpt) {
        return bpt * ticksPerSecond();
    }

    /** Metres per second to Blocks per tick. */
    public static double msToBpt(double ms) {
        double tps = ticksPerSecond();
        return tps > 0d ? ms / tps : ms / 20d;
    }

    /**
     * The divisor that turns m/s^2 into Blocks per tick squared: {@code 1 / (secondsPerTick)^2}.
     *
     * <p>Kept as a divisor because that is how the integrator reads: {@code speed += a / divisor}. At twenty ticks a
     * second it is 400, which is the literal that used to be in both call sites - so the default-rate behaviour is
     * bit-for-bit what it was.
     */
    public static double accelerationDivisor() {
        double spt = secondsPerTick();
        return spt > 0d ? 1d / (spt * spt) : 400d;
    }

    /**
     * The train's speed in METRES PER SECOND, converted through the live tick rate.
     *
     * <p>{@code train.speed} is Blocks per TICK, and the tick rate is not always 20. Under {@code /tick rate 500}
     * a train doing 80 m/s advances 0.16 Blocks per tick, so every Blocks-per-tick threshold in this file reads
     * it as nearly stopped. That is fine for the per-tick gates, which have always behaved that way and which the
     * operator has tested around - but it is NOT fine for a decision taken once, at the moment a claim ends,
     * about whether the driver can safely be stood up. Getting that one wrong throws them off a moving train.
     *
     * <p>So this exists for the release path only. It is not used by atOrAboveCeiling, which stays in Blocks per
     * tick deliberately.
     */
    public static double speedMs(Train train) {
        if (train == null)
            return 0d;
        return Math.abs(train.speed) * currentTickRate();
    }

    /**
     * The server's tick rate, defaulting to 20.
     *
     * <p>Read reflectively and cached: the accessor is not stable across the mappings this mod builds against,
     * and this is asked on a release, not per tick - so a stale-by-a-second figure is harmless and a compile
     * failure is not.
     */
    private static float cachedTickRate = 20f;

    /**
     * Ticks per second, MEASURED rather than asked for.
     *
     * <p>The previous implementation read it from {@code MinecraftServer.tickRateManager()}. That method does not
     * exist in this version - the class it belongs to is absent from every jar on the classpath - so the reflective
     * lookup threw on every call, the catch swallowed it, and the answer was the hard-coded default twenty forever.
     * Everything built on it therefore did nothing: the acceleration divisor stayed 400 and the train kept
     * accelerating at the same rate whatever {@code /tick rate} said, which is exactly what the operator reported
     * after the last build.
     *
     * <p>So it is measured instead. Each call to this method during a server tick advances a counter; a wall-clock
     * window decides how many ticks happened in the last second. Measured from {@code System.nanoTime} rather than
     * the level's game time, because game time is what is being sped up and cannot time itself.
     *
     * <p>Falls back to twenty until a full second has been observed, and never returns anything but a sane positive
     * figure - a zero here would divide the physics by zero and stop every train dead.
     */
    private static long lastServerTickNanos = 0L;

    /**
     * Instantaneous ticks per second, from the gap between the last two server ticks.
     *
     * <p>The operator's objection to averaging over a window is correct and it decided the design: a tick rate is
     * capped by the machine, so asking for ten thousand and getting three hundred is normal, and a one-second
     * average would spend that second reporting a figure the server had already stopped running at. Worse, the
     * average would be wrong in the direction that matters - it would lag a train that had just sped up.
     *
     * <p>So the gap between consecutive ticks is measured instead. Raw, it is far too noisy to integrate against:
     * one garbage-collection pause and the next tick claims 3 ticks per second, which would make every train on the
     * server lurch. What is kept is the PREVIOUS smoothed figure moved toward the new one, so a single bad gap can
     * move it by at most a quarter and a real rate change settles within a handful of ticks.
     *
     * <p>Clamped to a sane band. Below twenty the physics is already in a regime the design does not cover, and
     * above a few thousand the gap is mostly measurement noise rather than the server's real speed.
     */
    private static final float TICK_RATE_SMOOTHING = 0.25f;
    private static final float TICK_RATE_MIN = 1f;
    private static final float TICK_RATE_MAX = 100000f;

    /**
     * Called once per server tick, from the server's own tick hook - not from train ticks, which would call it once
     * per train and inflate the count by the size of the fleet.
     */
    public static void noteServerTick() {
        long now = System.nanoTime();
        long previous = lastServerTickNanos;
        lastServerTickNanos = now;
        if (previous == 0L)
            return;
        long gap = now - previous;
        if (gap <= 0L)
            return;
        float instant = 1_000_000_000f / gap;
        if (instant < TICK_RATE_MIN || instant > TICK_RATE_MAX)
            return;
        // One bad gap moves the answer by a quarter, so a stall cannot throw the whole fleet's physics for a tick.
        cachedTickRate += (instant - cachedTickRate) * TICK_RATE_SMOOTHING;
    }

    /** Ticks per second, smoothed from the last few server ticks. Twenty until the first one is observed. */
    public static float currentTickRate() {
        return cachedTickRate;
    }

    /**
     * The reciprocal of the tick rate - SECONDS PER TICK - which is what the integrator actually wants.
     *
     * <p>Speed is in Blocks per tick and acceleration in metres per second squared, so a step is
     * {@code a * secondsPerTick * secondsPerTick}. Written as a named quantity because the two places that need it
     * were both written as a literal 400 and both were wrong at any rate but twenty.
     */
    public static double secondsPerTick() {
        float tps = cachedTickRate;
        return tps > 0.1f ? 1d / tps : 1d / 20d;
    }

    /** Set from the synced payload on the client. Ignored on the server, which measures its own. */
    public static void acceptSyncedTickRate(float rate) {
        if (rate > 0.1f && rate < 100000f)
            cachedTickRate = rate;
    }

    /** Whether anybody aboard is standing, on the roof included. */
    public static boolean anyUnseated(Train train) {
        return !allSeated(train);
    }

    /**
     * The players aboard who are not sitting, in a deterministic order.
     *
     * <p>Sorted by UUID, so two players contending for the same seat resolve the same way every time rather
     * than in whatever order the level happened to hand them out.
     */
    public static List<Player> unseatedPlayers(Train train) {
        // Seated is a property of the TRAIN, not of one carriage. Built once from every carriage before any
        // player is judged, because a player seated in the first carriage is still inside the second
        // carriage's bounding box - the two touch - and judging carriages one at a time counted that player
        // as standing in every carriage but their own. On a multi-carriage train that is a permanent
        // "somebody is standing", which is one player on a train with forty-six empty seats reporting itself
        // as over capacity, forever.
        java.util.Set<UUID> seated = new java.util.HashSet<>();
        for (Carriage carriage : train.carriages) {
            CarriageContraptionEntity entity = carriage.anyAvailableEntity();
            if (entity == null || entity.level().isClientSide())
                continue;
            for (Entity passenger : entity.getIndirectPassengers())
                seated.add(passenger.getUUID());
            Contraption contraption = entity.getContraption();
            if (contraption != null)
                seated.addAll(contraption.getSeatMapping().keySet());
        }

        // The driver standing at the controls is NOT an unseated passenger. They are doing their job at a
        // desk that has no seat of its own, and counting them as standing made a one-man train report itself
        // as over capacity: the gate would try to seat the driver, fail while they kept driving, and after the
        // grace the train forced itself to cruise - reported as "above 50 m/s I hold J and every so often it
        // drops back to cruise by itself".
        var driverData = ((com.george_vi.electroenergetics.mixin_interfaces.ICEETrainExtension) train)
                .getElectricTrainData();
        java.util.UUID driving = driverData != null && driverData.driver != null
                ? driverData.driver.claimOwner : null;

        // Use the level's player list and the exact contraption block map as the authority. Create's
        // collidingEntities also contains side impacts; treating that set as boarding evidence was what
        // seated a platform player when a fast train merely hit them.
        List<Player> out = new ArrayList<>();
        for (Carriage carriage : train.carriages) {
            CarriageContraptionEntity entity = carriage.anyAvailableEntity();
            if (entity == null || entity.level().isClientSide())
                continue;
            Contraption contraption = entity.getContraption();
            if (contraption == null)
                continue;

            for (Player nearby : entity.level().players())
                if (isOnContraption(nearby, entity))
                    collect(nearby, seated, out);
        }
        if (driving != null)
            out.removeIf(p -> driving.equals(p.getUUID()));
        out.sort(Comparator.comparing(Player::getUUID));
        return out;
    }

    private static void collect(Entity entity, java.util.Set<UUID> seated, List<Player> out) {
        if (!(entity instanceof Player player))
            return;
        if (player.isSpectator())
            return;
        if (seated.contains(player.getUUID()))
            return;
        if (hasTeleportInFlight(player))
            return;
        if (!out.contains(player))
            out.add(player);
    }

    /**
     * Whether this player has a teleport that has been sent but not yet acknowledged.
     *
     * <p>Not because the position is stale - it is not. {@code teleport} applies the destination with
     * {@code absMoveTo} before it returns. The deferral is about the SECOND application: the acknowledgement
     * makes the server call {@code absMoveTo} with the same stored destination again, overwriting anything
     * done in between. Seating somebody inside that window puts them in a seat on a moving train and then
     * pulls them back to the static destination, which is two to five Blocks behind at line speed.
     */
    private static boolean hasTeleportInFlight(Player player) {
        if (!(player instanceof net.minecraft.server.level.ServerPlayer serverPlayer))
            return false;
        try {
            return ((com.george_vi.electroenergetics.mixins.TeleportPendingAccessor) serverPlayer.connection)
                    .electroEnergetics$awaitingPositionFromClient() != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Puts a returning player back in the seat they left.
     *
     * <p>Rejoining a train that is still moving otherwise lands the player at their saved world position with
     * no seat: the train has moved on, and whatever seating happens next is decided from that stale position
     * - a different seat. The seat map still has them, because an offline player keeps their entry, so the
     * seat index is read from there and applied directly instead of being recomputed.
     */

    /**
     * The carriage whose seat map still claims this player, nearest to where they are, or null.
     *
     * <p>"Nearest" rather than "first found": a player can hold a seat entry on more than one carriage -
     * an old claim that was never released, plus the one they actually sit in - and the offline-preserving
     * prune keeps both. Taking the first match put returning players in whichever carriage happened to be
     * earlier in the consist, which is how a driver came back from a relogin sitting in the second car.
     */
    private static CarriageContraptionEntity nearestClaimed(Entity player) {
        CarriageContraptionEntity result = nearestClaimed0(player);
        LOG.info("SEATRESTORE nearestClaimed={} claims={}", result == null ? "none" : "found",
                claimCount(player));
        return result;
    }

    private static int claimCount(Entity player) {
        int n = 0;
        for (com.simibubi.create.content.trains.entity.Train train
                : com.simibubi.create.Create.RAILWAYS.trains.values()) {
            for (var carriage : train.carriages) {
                CarriageContraptionEntity e = carriage.anyAvailableEntity();
                if (e == null || e.getContraption() == null)
                    continue;
                if (e.getContraption().getSeatMapping().containsKey(player.getUUID()))
                    n++;
            }
        }
        return n;
    }

    private static CarriageContraptionEntity nearestClaimed0(Entity player) {
        CarriageContraptionEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (com.simibubi.create.content.trains.entity.Train train
                : com.simibubi.create.Create.RAILWAYS.trains.values()) {
            for (var carriage : train.carriages) {
                CarriageContraptionEntity entity = carriage.anyAvailableEntity();
                if (entity == null || entity.level().isClientSide())
                    continue;
                Contraption contraption = entity.getContraption();
                if (contraption == null || !contraption.getSeatMapping().containsKey(player.getUUID()))
                    continue;
                double d = entity.distanceToSqr(player);
                if (d < bestDistance) {
                    bestDistance = d;
                    best = entity;
                }
            }
        }
        return best;
    }

    /**
     * Seats the player back in their claimed seat, without moving them.
     *
     * <p>The teleport that used to run here was the problem: it moved the player to the seat's world
     * position during login, before their client had finished joining, and it did so on a train that was
     * still moving - reported as the world refusing to load normally afterwards. Create's own
     * addSittingPassenger already carries a rider to the seat; nothing here has to place them by hand.
     *
     * <p>If the train is further away than a rider could plausibly have logged off from, the claim is stale
     * and is left alone - the capacity rules will seat them normally.
     */
    private static boolean restoreClaim(net.minecraft.server.level.ServerPlayer player) {
        CarriageContraptionEntity entity = nearestClaimed(player);
        if (entity == null)
            return false;
        Contraption contraption = entity.getContraption();
        if (contraption == null)
            return false;
        Integer seat = contraption.getSeatMapping().get(player.getUUID());
        if (seat == null || seat < 0 || seat >= contraption.getSeats().size())
            return false;
        if (player.getVehicle() == entity)
            return true;

        // The seat is a binding, not a proximity test. A player who logs out seated keeps that seat no
        // matter how far the train travels while they are gone, and rejoining must put them back in it -
        // twenty million Blocks away included. Create already knows where the carriage is, so it is
        // asked, and the player is moved to the seat before being attached; attaching alone would leave
        // their client outside the loaded area.
        if (entity.level() instanceof net.minecraft.server.level.ServerLevel level) {
            Vec3 seatWorld = entity.toGlobalVector(
                    Vec3.atCenterOf(contraption.getSeats().get(seat)), 1.0f);
            player.teleportTo(level, seatWorld.x, seatWorld.y, seatWorld.z,
                    player.getYRot(), player.getXRot());
        }
        SEATING.add(player.getUUID());
        try {
            entity.addSittingPassenger(player, seat);
        } finally {
            SEATING.remove(player.getUUID());
        }
        boolean riding = player.getVehicle() == entity;
        LOG.info("SEATRESTORE claim seat={} seats={} dist={} ridingBefore={} ridingAfter={}",
                seat, contraption.getSeats().size(),
                String.format("%.1f", Math.sqrt(entity.distanceToSqr(player))),
                false, riding);
        return riding;
    }

    /**
     * Whether a dismount by this player must be refused: they are in a seat on a train carriage, and the
     * train is at or above the ceiling.
     *
     * <p>Shared by the two mixins that guard the dismount, so the rule cannot differ between them.
     */
    /**
     * Stands a player up, bypassing the speed dismount lock for exactly this call.
     *
     * <p>The lock exists to stop a rider CHOOSING to step off a fast train. It must not stop the mod from
     * taking the driver's seat away when their claim ends - that is not a choice, and leaving them seated in a
     * seat that is reserved for the next driver is the inconsistent state this is here to prevent.
     */
    public static void forceDismount(net.minecraft.world.entity.player.Player player) {
        if (player == null || !player.isPassenger())
            return;
        if (true)
            System.out.println("[forceDismount] " + player.getName().getString()
                    + " vehicle=" + (player.getVehicle() == null ? "null"
                            : player.getVehicle().getClass().getSimpleName()));
        FORCED_DISMOUNTS.add(player.getUUID());
        try {
            player.stopRiding();
        } catch (Throwable ignored) {
            // A failed stand-up must not break the tick; the claim is released either way.
        } finally {
            FORCED_DISMOUNTS.remove(player.getUUID());
        }
    }

    /**
     * Whether this player is on the train in any sense - riding it, or standing inside one of its carriages.
     *
     * <p>Separate from {@link #isRidingTrain} on purpose. "Is this player at the controls" and "is this player
     * seated" are different questions, and the pause menu is exactly the case that separates them: the seat is
     * dropped for a tick on return while the player never left the carriage.
     */
    public static boolean isOnTrainNear(Train train, net.minecraft.world.entity.Entity player, double reach) {
        if (train == null || player == null)
            return false;
        if (isRidingTrain(train, player))
            return true;
        for (Carriage carriage : train.carriages) {
            final boolean[] found = {false};
            carriage.forEachPresentEntity(cce -> {
                if (found[0] || cce.level() != player.level())
                    return;
                if (cce.distanceToSqr(player) <= reach * reach
                        && isStandingOnCarriageFloor(player, cce))
                    found[0] = true;
            });
            if (found[0])
                return true;
        }
        return false;
    }

    /**
     * Whether this player is SITTING on this train - a passenger of one of its carriages, not merely aboard.
     *
     * <p>O(1): a vehicle check and one carriage comparison. This is asked once per tick for the one train that has
     * a driver, so it must not walk the consist.
     *
     * <p>Distinct from {@link #isRidingTrain}, which is about being carried at all. Standing in the aisle is
     * "riding the train" and is not "seated", and the difference is exactly what the ceiling rule turns on.
     */
    public static boolean isSeatedInTrain(Train train, net.minecraft.world.entity.Entity player) {
        if (train == null || player == null || !player.isPassenger())
            return false;
        net.minecraft.world.entity.Entity root = player.getRootVehicle();
        if (!(root instanceof CarriageContraptionEntity cce))
            return false;
        Carriage carriage = cce.getCarriage();
        return carriage != null && carriage.train == train;
    }

    /**
     * Whether this player is standing on a carriage floor of this train.
     *
     * <p>Floor support, not proximity. A driver thrown clear of the train stands on the world and fails this;
     * a driver standing in the cab passes it even on the tick the passenger list has not caught up.
     */
    public static boolean isStandingOnAnyFloor(Train train, net.minecraft.world.entity.Entity player) {
        if (train == null || player == null)
            return false;
        for (Carriage carriage : train.carriages) {
            final boolean[] found = {false};
            carriage.forEachPresentEntity(cce -> {
                if (found[0] || cce.level() != player.level())
                    return;
                if (isStandingOnCarriageFloor(player, cce))
                    found[0] = true;
            });
            if (found[0])
                return true;
        }
        return false;
    }

    /** Whether this player is riding a carriage of this train. */
    public static boolean isRidingTrain(Train train, net.minecraft.world.entity.Entity player) {
        if (train == null || player == null)
            return false;
        // getRootVehicle, not getVehicle: a player sitting on a seat rides a SeatEntity whose own vehicle is
        // the carriage. Testing the direct vehicle would report a seated driver as NOT riding and the claim
        // would be released the tick it was taken.
        net.minecraft.world.entity.Entity root = player.getRootVehicle();
        if (!(root instanceof CarriageContraptionEntity cce))
            return false;
        Carriage carriage = cce.getCarriage();
        return carriage != null && carriage.train == train;
    }

    public static boolean shouldLockDismount(net.minecraft.world.entity.player.Player player) {
        if (!(player instanceof net.minecraft.server.level.ServerPlayer))
            return false;
        if (player.level().isClientSide())
            return false;
        // This dismount is the gate's own, moving a player out of the driver's seat. See
        // FORCED_DISMOUNTS: the lock is about a player choosing to leave a fast train, not about a
        // forced change of seat inside it.
        if (FORCED_DISMOUNTS.contains(player.getUUID()))
            return false;
        // SEATING IN PROGRESS - and this is the one that mattered.
        //
        // AbstractContraptionEntity.addSittingPassenger calls stopRiding on the entity BEFORE seating it: the
        // passenger is taken off whatever they were riding, then put on the seat. Our lock saw that inner
        // stopRiding, refused it because the train was fast, and the seating therefore never completed - after
        // which Create retried on the next tick, and the next. The log shows 262 refusals across three minutes,
        // and by then the reservation had been re-evaluated enough times to lose track of the driver.
        //
        // A call that is part of seating somebody is not somebody choosing to get off.
        if (SEATING.contains(player.getUUID()))
            return false;

        // Diagnostic: the operator reports the lock works on an ordinary seat and not on the driver's. The two
        // differ in what the player is riding, so the vehicle is named in the log before anything else is
        // decided. Temporary, and removed once the answer is known.
        if (true) {
            net.minecraft.world.entity.Entity v = player.getVehicle();
            System.out.println(String.format(
                    "[seatlock] %s vehicle=%s root=%s",
                    player.getName().getString(),
                    v == null ? "null" : v.getClass().getSimpleName(),
                    player.getRootVehicle() == null ? "null"
                            : player.getRootVehicle().getClass().getSimpleName()));
        }

        com.simibubi.create.content.trains.entity.CarriageContraptionEntity carriage = null;
        if (player.getVehicle()
                instanceof com.simibubi.create.content.trains.entity.CarriageContraptionEntity direct)
            carriage = direct;
        else if (player.getVehicle()
                instanceof com.simibubi.create.content.contraptions.actors.seat.SeatEntity seat
                && seat.getRootVehicle()
                instanceof com.simibubi.create.content.trains.entity.CarriageContraptionEntity root)
            carriage = root;
        if (carriage == null) {
            // No vehicle. That is NOT "not on a train": stopRiding clears the vehicle before the ride is torn
            // down, so this branch is reached by exactly the player who is trying to leave. A driver is caught
            // here by asking the claim instead - which is why the driver's seat could be left while an ordinary
            // seat could not: the ordinary one is stopped earlier, in wantsToStopRiding, where the vehicle is
            // still set.
            return driverOfLockedTrain(player);
        }

        Contraption contraption = carriage.getContraption();
        if (contraption == null)
            return driverOfLockedTrain(player);

        var c = carriage.getCarriage();
        if (c == null || c.train == null || !atOrAboveCeiling(c.train))
            return false;

        // Above the ceiling nobody leaves a seat on an electric train. The operator's rule, and the reason it
        // is not conditioned on the seat mapping:
        //
        // The mapping was the test and it was wrong in the case that matters. A driver seated by the claim can
        // be a tick away from having their entry written, and the operator reported pressing shift at speed and
        // stepping off - the mapping simply did not contain them yet. Asking "is this player riding a carriage
        // of a fast electric train" needs no mapping, cannot be a tick stale, and is exactly the condition the
        // lock is for. A passenger is in the same position: if they are on the carriage, they can be held on it.
        var data = ((com.george_vi.electroenergetics.mixin_interfaces.ICEETrainExtension) c.train)
                .getElectricTrainData();
        return data != null && data.hasTractionMotors;
    }

    /**
     * Puts a driver back in their seat when the lock has been bypassed.
     *
     * <p>The lock is two injections and the operator found a path past both: the refusal message appeared and the
     * player stepped off anyway, then was thrown forward down the train and re-seated as an ordinary passenger.
     * That is not the lock failing to decide - it decided - it is a second exit that does not go through either
     * of the two methods the lock knows about. Create's own seat handling removes a passenger directly on some
     * paths, so no amount of guarding {@code stopRiding} and {@code wantsToStopRiding} can cover it.
     *
     * <p>So the rule is enforced where it cannot be bypassed: a driver of a train above the ceiling is seated
     * again every tick. The window in which they are loose is one tick, which is far too short to be thrown.
     *
     * <p>Nobody else is touched. A passenger who stands up at speed is the gate's business, not this method's,
     * and re-seating them here would fight {@code trySeatAll} for no reason.
     */

    public static void keepDriverSeated(Train train, net.minecraft.server.MinecraftServer server) {
        if (train == null || server == null)
            return;
        var data = ((com.george_vi.electroenergetics.mixin_interfaces.ICEETrainExtension) train)
                .getElectricTrainData();
        if (data == null || data.driver == null || data.driver.claimOwner == null)
            return;
        if (!atOrAboveCeiling(train))
            return;
        UUID owner = data.driver.claimOwner;

        // Cheapest possible test first. A seated driver is a passenger, and the entity API answers what it is
        // riding without touching a carriage, a contraption or its seat map. This is the ordinary case on every
        // tick of every driven train, so it has to cost a field read and nothing else.
        net.minecraft.server.level.ServerPlayer driver = server.getPlayerList().getPlayer(owner);
        if (driver == null)
            return;
        if (driver.isPassenger() && driver.getRootVehicle() instanceof CarriageContraptionEntity cce
                && cce.getCarriage() != null && cce.getCarriage().train == train)
            return;                              // aboard and seated: nothing to repair

        // Only now, for a driver who is loose, walk the carriages. That is one player at most and only in the
        // tick after something went wrong, not a per-tick cost.
        for (Carriage carriage : train.carriages) {
            final boolean[] aboard = {false};
            carriage.forEachPresentEntity(cce -> {
                if (aboard[0] || cce.level().isClientSide())
                    return;
                for (Entity passenger : cce.getIndirectPassengers()) {
                    if (passenger instanceof net.minecraft.server.level.ServerPlayer sp
                            && owner.equals(sp.getUUID()))
                        aboard[0] = true;        // still aboard, just not seated
                }
            });
            if (aboard[0])
                return;
        }
        // Not riding any carriage of this train. Seat them again, without the eviction the claim path uses - a
        // driver who is not in a seat is a driver who is not in anybody's way.
        seatClaimedDriver(train, reservedDriverSeats(train));
    }


    /**
     * Whether this player is the driver of an electric train above the ceiling.
     *
     * <p>Asked without any reference to what the player is riding, because the moment that matters is the one
     * where the ride is already being torn down. The claim is the authority and it is still set at that point.
     */
    private static boolean driverOfLockedTrain(net.minecraft.world.entity.player.Player player) {
        if (!(player.level() instanceof net.minecraft.server.level.ServerLevel))
            return false;
        // Only trains that are actually moving can lock anybody, and only a moving train has a speed to test.
        // A stopped train - the overwhelming majority at any moment, and every parked one - is skipped by two
        // field reads. This runs on a dismount request, not per tick, so even the full scan would be rare; the
        // gate is here so that a hundred parked trains cost a hundred comparisons rather than a hundred
        // electric-data lookups.
        for (var train : com.simibubi.create.Create.RAILWAYS.trains.values()) {
            if (!atOrAboveCeiling(train))
                continue;
            var data = ((com.george_vi.electroenergetics.mixin_interfaces.ICEETrainExtension) train)
                    .getElectricTrainData();
            if (data != null && data.driver != null && player.getUUID().equals(data.driver.claimOwner))
                return true;
        }
        return false;
    }

    /** Tells the player their dismount was refused. */
    public static void refuseDismount(net.minecraft.world.entity.player.Player player) {
        player.displayClientMessage(
                net.minecraft.network.chat.Component.translatable(
                        "electroenergetics.train.riders.seatLocked"), true);
    }

    /**
     * Seats ONE named player, without asking whether the gate thinks they are aboard.
     *
     * <p>Exists so seat selection can be tested on its own. The automatic pass first has to decide who is
     * aboard, and if that decision is wrong the seat it picks is wrong for a reason that has nothing to do
     * with the distance arithmetic. This runs the selection directly on a player the caller names.
     */
    public static boolean seatPlayer(Train train, Player player) {
        int bestIndex = -1;
        double bestDistance = Double.MAX_VALUE;
        CarriageContraptionEntity chosenEntity = null;

        for (Carriage carriage : train.carriages) {
            CarriageContraptionEntity entity = carriage.anyAvailableEntity();
            if (entity == null || entity.level().isClientSide())
                continue;
            Contraption contraption = entity.getContraption();
            if (contraption == null)
                continue;

            List<BlockPos> seats = contraption.getSeats();
            Set<Integer> taken = new HashSet<>(contraption.getSeatMapping().values());
            Vec3 localPlayer = entity.toLocalVector(player.position(), 1.0f);
            for (int i = 0; i < seats.size(); i++) {
                if (taken.contains(i))
                    continue;
                double d = Vec3.atCenterOf(seats.get(i)).distanceToSqr(localPlayer);
                if (d < bestDistance) {
                    bestDistance = d;
                    bestIndex = i;
                    chosenEntity = entity;
                }
            }
        }
        if (chosenEntity == null)
            return false;
        SEATING.add(player.getUUID());
        try {
            chosenEntity.addSittingPassenger(player, bestIndex);
        } finally {
            SEATING.remove(player.getUUID());
        }
        return true;
    }

    /** The index of the carriage this entity is riding, or -1 if it is not riding any of them. */
    /** Whether the player's feet are supported by a block in this carriage. */
    public static boolean isStandingOnCarriageFloor(Entity entity,
                                                     com.simibubi.create.content.contraptions.AbstractContraptionEntity carriage) {
        return hasCarriageSupport(entity, carriage, 0.18d);
    }

    /**
     * Whether the player is on a support surface belonging to this carriage.
     *
     * <p>The check follows collision shapes in carriage-local coordinates. A nearby world AABB or Create's
     * side-collision bookkeeping cannot by itself make a platform player count as aboard. A short jump above
     * an actual support surface remains aboard for the seating gate.
     */
    public static boolean isOnContraption(net.minecraft.world.entity.Entity entity,
                                          com.simibubi.create.content.contraptions.AbstractContraptionEntity carriage) {
        return hasCarriageSupport(entity, carriage, 3.0d);
    }

    private static boolean hasCarriageSupport(Entity entity,
            com.simibubi.create.content.contraptions.AbstractContraptionEntity carriage, double maxGap) {
        if (entity == null || carriage == null)
            return false;
        Contraption contraption = carriage.getContraption();
        if (contraption == null || contraption.getBlocks() == null || contraption.getBlocks().isEmpty())
            return false;

        Vec3 localFeet = carriage.toLocalVector(
                new Vec3(entity.getX(), entity.getBoundingBox().minY, entity.getZ()), 1.0f);
        BlockPos feetCell = BlockPos.containing(localFeet);
        for (int depth = 1; depth <= 4; depth++) {
            BlockPos supportPos = feetCell.below(depth);
            var support = contraption.getBlocks().get(supportPos);
            if (support == null)
                continue;
            var shape = support.state().getCollisionShape(
                    contraption.getContraptionWorld(), supportPos,
                    net.minecraft.world.phys.shapes.CollisionContext.of(entity));
            if (shape.isEmpty())
                continue;
            var bounds = shape.bounds();
            double x = localFeet.x - supportPos.getX();
            double z = localFeet.z - supportPos.getZ();
            if (x < bounds.minX || x > bounds.maxX || z < bounds.minZ || z > bounds.maxZ)
                continue;
            double top = supportPos.getY() + shape.max(net.minecraft.core.Direction.Axis.Y);
            double gap = localFeet.y - top;
            if (gap >= -0.12d && gap <= maxGap)
                return true;
        }
        return false;
    }

    public static int carriageIndexOf(Train train, Entity entity) {
        if (train == null || entity == null)
            return -1;
        Entity root = entity.getRootVehicle();
        for (int i = 0; i < train.carriages.size(); i++) {
            CarriageContraptionEntity carriage = train.carriages.get(i).anyAvailableEntity();
            if (carriage == null)
                continue;
            if (root == carriage || entity.getVehicle() == carriage)
                return i;
        }

        // Standing players have no vehicle: their root vehicle is themselves. A local support-surface
        // check identifies the carriage without treating a side impact as boarding.
        for (int i = 0; i < train.carriages.size(); i++) {
            CarriageContraptionEntity carriage = train.carriages.get(i).anyAvailableEntity();
            if (carriage == null)
                continue;
            if (isOnContraption(entity, carriage))
                return i;
        }
        return -1;
    }

    /**
     * The free seat nearest to a point given in the CARRIAGE's own coordinates, or -1 if none is free.
     *
     * <p>Takes local coordinates deliberately. The caller that knows where the player is drawn is the client,
     * and it can express that in the carriage's frame; converting a world position on the server is what
     * produced two rounds of wrong answers.
     */
    public static int nearestFreeSeat(CarriageContraptionEntity entity, Vec3 local) {
        Contraption contraption = entity.getContraption();
        if (contraption == null || local == null)
            return -1;
        java.util.List<BlockPos> seats = contraption.getSeats();
        java.util.Set<Integer> taken = new java.util.HashSet<>(contraption.getSeatMapping().values());
        int best = -1;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < seats.size(); i++) {
            if (taken.contains(i))
                continue;
            double d = Vec3.atCenterOf(seats.get(i)).distanceToSqr(local);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    /**
     * One seat, named by the carriage it is in and its index in that carriage's seat list.
     *
     * <p>The pair rather than a block position, because a seat index is only meaningful inside the
     * contraption that owns it: the same index in two carriages is two different seats, and the
     * contraption that carries the seat is also the one that has to be asked to seat somebody in it.
     */
    public record SeatRef(int carriageIndex, int seatIndex) {}

    /**
     * Whether this player is seated in THE driver's seat that belongs to their claim - not merely
     * anywhere on the train. The outcome check that keeps a driver aboard above the ceiling must not
     * be satisfied by some other seat: between a dismount and the re-seat one tick later, a loose
     * driver who ended up in a passenger seat would otherwise read as "seated" and stay there.
     *
     * <p>Read from the seat mapping, like {@link #isSeatedInTrain}: the mapping is what Create's own
     * dismount clears, so "mapping says the reserved seat" is exactly "riding in the reserved seat".
     */
    public static boolean isInClaimedDriverSeat(Train train, net.minecraft.server.level.ServerPlayer player) {
        if (player == null)
            return false;
        Set<SeatRef> reserved = reservedDriverSeats(train);
        if (reserved.isEmpty())
            return false;
        for (Carriage carriage : train.carriages) {
            CarriageContraptionEntity entity = carriage.anyAvailableEntity();
            if (entity == null || entity.level().isClientSide())
                continue;
            Contraption contraption = entity.getContraption();
            if (contraption == null)
                continue;
            Integer seat = contraption.getSeatMapping().get(player.getUUID());
            if (seat == null)
                continue;
            int carriageIndex = train.carriages.indexOf(carriage);
            return reserved.contains(new SeatRef(carriageIndex, seat));
        }
        return false;
    }

    /**
     * The seats held for a driver right now, as (carriage, seat) pairs.
     *
     * <h2>The rule</h2>
     *
     * <p>Every controls block in a carriage has a DRIVER'S SEAT: the seat nearest to it, measured in
     * the carriage's own local coordinates. Local deliberately - the desk and the seats both come out
     * of the contraption's own block map, so they share one origin and one rotation, and this file
     * already does every other comparison that way. A world-space distance would be wrong for any
     * carriage that is not axis-aligned.
     *
     * <p>With nobody driving, EVERY desk's seat is held empty: the next driver will need one, and the
     * operator's ruling for a unit with a cab at each end is that both ends keep a driver's seat.
     * With a driver, only the seat of the desk they actually claimed is held; the other cab's seat is
     * an ordinary seat again, which is the same ruling read the other way.
     *
     * <p>Held seats are not offered to anybody in {@link #trySeatAll}: they are not legal free
     * positions, so a train whose only empty seats are driver's seats is over capacity exactly as if
     * those seats did not exist. That has a cost - a train with nobody at the controls carries fewer
     * passengers than it has seats - and it is the intended one: the seat has to be there when
     * somebody takes the controls, including at speed.
     *
     * <p>Only a train this mod's driving rules own has driver's seats. A fuel train, a motors-only
     * set and a rail-shoe metro keep upstream's seating, where every seat is an ordinary seat.
     */
    public static Set<SeatRef> reservedDriverSeats(Train train) {
        Set<SeatRef> held = new HashSet<>();
        if (train == null || train.carriages == null || !DriverAuthority.appliesTo(train))
            return held;

        com.george_vi.electroenergetics.content.railway_electrification.TrainDriverState driverState =
                ((com.george_vi.electroenergetics.mixin_interfaces.ICEETrainExtension) train)
                        .getElectricTrainData().driver;
        UUID claimed = driverState.claimOwner;
        int claimedCarriage = driverState.driverCarriage;
        BlockPos claimedDesk = driverState.driverDesk;

        // With a driver, the ONLY held seat is the one at the desk they claimed; the other cab's seat goes
        // back to being an ordinary seat.
        //
        // Which carriage that desk is in is REMEMBERED on the claim, and memory can be wrong: a double-ended
        // unit has a controls block at each end, and if the recorded index names the wrong end the reservation
        // lands in the cab nobody is sitting in - the driver's own seat is then free for a passenger while the
        // seat they are actually in counts as ordinary. That is one of the ways one end of a twin-cab train
        // behaved differently from the other.
        //
        // So the recorded carriage is preferred, and the rest of the consist is searched when it does not
        // contain that desk. The search is a handful of BlockPos comparisons per carriage and only runs while
        // somebody holds a claim.
        boolean foundClaimedDesk = false;
        for (int carriageIndex = 0; carriageIndex < train.carriages.size(); carriageIndex++) {
            Carriage carriage = train.carriages.get(carriageIndex);
            CarriageContraptionEntity entity = carriage.anyAvailableEntity();
            if (entity == null || entity.level().isClientSide())
                continue;
            Contraption contraption = entity.getContraption();
            if (contraption == null)
                continue;
            List<BlockPos> seats = contraption.getSeats();
            if (seats.isEmpty())
                continue;
            for (BlockPos desk : controlDesks(contraption)) {
                if (claimed != null && !desk.equals(claimedDesk))
                    continue;
                int seat = nearestSeat(seats, desk);
                if (seat < 0)
                    continue;
                if (claimed != null && carriageIndex == claimedCarriage)
                    foundClaimedDesk = true;
                if (claimed != null)
                    System.out.println("[held] desk=" + desk + " carriage=" + carriageIndex
                            + " seat=" + seat + " claimedCarriage=" + claimedCarriage
                            + " claimedDesk=" + claimedDesk);
                held.add(new SeatRef(carriageIndex, seat));
            }
        }
        if (claimed != null && !foundClaimedDesk) {
            // The remembered carriage did not hold the claimed desk. Keep whatever the search found rather
            // than reserving nothing: no reservation would let a passenger take the driver's seat while the
            // driver is in it.
            return held;
        }
        return held;
    }

    /** Whether the claimed driver is not yet occupying the seat reserved for their control desk. */
    public static boolean claimedDriverNeedsSeat(Train train) {
        if (train == null || !DriverAuthority.appliesTo(train))
            return false;
        TrainDriverState driver = ((com.george_vi.electroenergetics.mixin_interfaces.ICEETrainExtension) train)
                .getElectricTrainData().driver;
        if (driver.claimOwner == null || driver.driverDesk == null)
            return false;
        CarriageContraptionEntity entity = carriageEntity(train, driver.driverCarriage);
        if (entity == null || entity.getContraption() == null)
            return false;
        // ONLY when the driver has no seat at all. It used to ask whether they were in the seat NEAREST the desk,
        // and that is a different question with a destructive answer: seatClaimedDriver evicts an occupant before
        // seating, so a driver who was sitting anywhere else - already seated, already riding - was pulled off and
        // re-seated. Once per tick, because a seating pass runs every tick at the ceiling. At tick rate 500 that
        // is the operator being flung down the train at 314 km/h without touching anything.
        //
        // "Has no seat" is the only condition worth acting on: a standing driver at the ceiling needs putting in
        // the cab. A seated one does not, wherever they happen to be sitting.
        return entity.getContraption().getSeatMapping().get(driver.claimOwner) == null;
    }

    /** Every controls block in this contraption, in its own local coordinates. */
    private static List<BlockPos> controlDesks(Contraption contraption) {
        List<BlockPos> desks = new ArrayList<>();
        var blocks = contraption.getBlocks();
        if (blocks == null)
            return desks;
        for (var entry : blocks.entrySet()) {
            if (entry.getValue() != null && entry.getValue().state().getBlock() instanceof ControlsBlock)
                desks.add(entry.getKey());
        }
        return desks;
    }

    /**
     * The index of the seat nearest to a control desk, in the carriage's own coordinates, or -1.
     *
     * <p>Local, for the same reason {@link #isOnContraption} is: the contraption's block map and its
     * seat list share a frame, and mixing that frame with world coordinates is how this file used to
     * pick seats that were nowhere near the player.
     */
    private static int nearestSeat(List<BlockPos> seats, BlockPos desk) {
        Vec3 centre = Vec3.atCenterOf(desk);
        int best = -1;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < seats.size(); i++) {
            double d = Vec3.atCenterOf(seats.get(i)).distanceToSqr(centre);
            if (best < 0 || d < bestDistance - 1.0e-9d
                    || (Math.abs(d - bestDistance) <= 1.0e-9d
                        && seats.get(i).compareTo(seats.get(best)) < 0)) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    /**
     * Seats the live claimed driver in the reserved seat nearest to their control desk.
     *
     * <p>This is independent of the overload passenger list: that list deliberately excludes the driver, but
     * the driver still must not be left at a passenger seat or standing at the desk when the train reaches
     * the ceiling. If another player occupies the reserved seat, Create's passenger removal path is used and
     * the evicted player participates in the ordinary allocation pass below.
     *
     * @return how many players this seated (0 or 1)
     */
    public static int seatClaimedDriver(Train train, Set<SeatRef> held) {
        if (true)
            System.out.println("[seatlock] seatClaimedDriver held=" + held.size());
        if (held.isEmpty()) {
            System.out.println("[seat] path=no-held-seats");
            return 0;
        }
        TrainDriverState driverState = ((com.george_vi.electroenergetics.mixin_interfaces.ICEETrainExtension) train)
                .getElectricTrainData().driver;
        UUID claimed = driverState.claimOwner;
        if (claimed == null) {
            System.out.println("[seat] path=no-claim");
            return 0;
        }

        CarriageContraptionEntity deskCarriage = carriageEntity(train, driverState.driverCarriage);
        if (deskCarriage == null || deskCarriage.level().isClientSide()) {
            System.out.println("[seat] path=no-desk-carriage carriage=" + driverState.driverCarriage);
            return 0;
        }
        Player driver = null;
        for (Player candidate : deskCarriage.level().players()) {
            if (candidate.getUUID().equals(claimed)) {
                driver = candidate;
                break;
            }
        }
        if (driver == null) {
            System.out.println("[seat] path=driver-not-online");
            return 0;
        }

        boolean aboard = carriageIndexOf(train, driver) >= 0;
        if (!aboard) {
            for (Carriage carriage : train.carriages) {
                CarriageContraptionEntity entity = carriage.anyAvailableEntity();
                if (entity != null && !entity.level().isClientSide() && isOnContraption(driver, entity)) {
                    aboard = true;
                    break;
                }
            }
        }
        // A live cab claim is not permission to pull somebody aboard from the platform.
        if (!aboard) {
            System.out.println("[seat] path=not-aboard");
            return 0;
        }

        SeatRef current = null;
        for (int i = 0; i < train.carriages.size(); i++) {
            CarriageContraptionEntity entity = carriageEntity(train, i);
            if (entity == null || entity.getContraption() == null)
                continue;
            Integer seat = entity.getContraption().getSeatMapping().get(claimed);
            if (seat != null) {
                current = new SeatRef(i, seat);
                break;
            }
        }
        if (current != null && held.contains(current)) {
            System.out.println("[seat] path=already-in-reserved seat=" + current.seatIndex());
            return 0;
        }
        if (current != null) {
            CarriageContraptionEntity entity = carriageEntity(train, current.carriageIndex());
            if (entity == null || entity.getContraption() == null
                    || !evict(entity, entity.getContraption(), claimed))
                return 0;
        }

        for (SeatRef seat : held) {
            CarriageContraptionEntity entity = carriageEntity(train, seat.carriageIndex());
            if (entity == null || entity.level().isClientSide())
                continue;
            Contraption contraption = entity.getContraption();
            if (contraption == null)
                continue;
            List<BlockPos> seats = contraption.getSeats();
            if (seat.seatIndex() < 0 || seat.seatIndex() >= seats.size())
                continue;

            UUID occupant = occupantOf(contraption, seat.seatIndex());
            if (occupant != null && !occupant.equals(claimed) && !evict(entity, contraption, occupant))
                continue;

            if (true)
            System.out.println("[seatlock] seating driver into carriage " + seat.carriageIndex()
                    + " seat " + seat.seatIndex());
        System.out.println("[seat] path=seating carriage=" + seat.carriageIndex()
                + " seat=" + seat.seatIndex() + " from=" + (current == null ? "none" : current.seatIndex()));
        SEATING.add(driver.getUUID());
        try {
            entity.addSittingPassenger(driver, seat.seatIndex());
        } finally {
            SEATING.remove(driver.getUUID());
        }
        // The re-seat lands one tick after a successful dismount attempt; at ceiling speed that tick
        // has carried the driver up to a block and a half forward, which is what reads as "thrown
        // out of the cab". Landing in the seat with the drift still applied would seat a driver
        // floating ahead of their own carriage - so the leftover motion dies here, with the seat.
        driver.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        driver.fallDistance = 0f;
            if (!contraption.getSeatMapping().containsKey(claimed))
                continue;

            if (driver instanceof net.minecraft.server.level.ServerPlayer serverPlayer)
                net.createmod.catnip.platform.CatnipServices.NETWORK.sendToClient(
                        serverPlayer, new SeatAssignedPacket(seat.carriageIndex(), seat.seatIndex()));
            SeatIntentStore.clear(claimed);
            return 1;
        }
        return 0;
    }

    /** The player sitting in this seat, or null when it is free. */
    private static UUID occupantOf(Contraption contraption, int seatIndex) {
        for (var entry : contraption.getSeatMapping().entrySet()) {
            if (entry.getValue() != null && entry.getValue() == seatIndex)
                return entry.getKey();
        }
        return null;
    }

    /**
     * Force a seated entity out of this carriage, and make sure the seat is usable afterwards.
     *
     * <p>{@code stopRiding()} rather than editing the seat mapping directly: Create's
     * {@code removePassenger} override removes the mapping, sends the seat sync and drops the entity
     * where the seat is. The direct write is only the fallback for a mapping entry that names
     * somebody who is no longer a passenger at all - the stale-claim case {@link #pruneStaleSeats}
     * exists for, which would otherwise stop the driver being seated while the seat looked free.
     *
     * @return whether the seat is now free
     */
    private static boolean evict(CarriageContraptionEntity entity, Contraption contraption,
                                 UUID occupantId) {
        Entity occupant = null;
        for (Entity passenger : entity.getPassengers()) {
            if (passenger.getUUID().equals(occupantId)) {
                occupant = passenger;
                break;
            }
        }
        if (occupant == null) {
            for (Entity passenger : entity.getIndirectPassengers()) {
                if (passenger.getUUID().equals(occupantId)) {
                    occupant = passenger;
                    break;
                }
            }
        }
        if (occupant != null) {
            FORCED_DISMOUNTS.add(occupantId);
            try {
                if (true)
                    System.out.println("[evict] occupant=" + occupantId);
                occupant.stopRiding();
            } finally {
                FORCED_DISMOUNTS.remove(occupantId);
            }
        }

        var mapping = contraption.getSeatMapping();
        if (mapping.containsKey(occupantId)) {
            mapping.remove(occupantId);
            try {
                contraption.setSeatMapping(mapping);
            } catch (Throwable ignored) {
                // A copy we cannot write back is not worth a crash; report the failure instead of
                // seating the driver into a seat somebody else still holds.
            }
        }
        return !contraption.getSeatMapping().containsKey(occupantId);
    }

    /** The present entity of one carriage, or null when it is not loaded. */
    private static CarriageContraptionEntity carriageEntity(Train train, int carriageIndex) {
        if (carriageIndex < 0 || carriageIndex >= train.carriages.size())
            return null;
        return train.carriages.get(carriageIndex).anyAvailableEntity();
    }

    /** Ticks the gate waits for a client's seat answer before using its own frame. */
    public static final int SEAT_INTENT_GRACE_TICKS = 10;

    /**
     * Players the gate itself is moving out of a seat, so the speed dismount lock must let them go.
     *
     * <p>The lock exists to stop a player CHOOSING to get off a fast train. Evicting somebody from the
     * driver's seat is not that: it is a forced change of seat inside the train, and the whole point
     * of the ceiling rule is that they are then reseated (or counted as over capacity) in the same
     * pass. Leaving the lock in the way would make the rule unreachable at exactly the speed it
     * exists for - 28 Blocks/second and above - so the eviction marks the player for the duration of
     * the one {@code stopRiding()} call that does it. Server thread only, and always removed.
     */
    private static final Set<UUID> FORCED_DISMOUNTS = new HashSet<>();

    /** Players who are being seated right now: their inner stopRiding is part of seating, not a dismount. */
    private static final Set<UUID> SEATING = new HashSet<>();    /**
     * Players whose seat is being restored after a relogin, with a deadline tick. A seated
     * player who logs out keeps their claim, and the restore has to survive the gap between
     * PlayerLoggedInEvent (too early: Create has not loaded its trains yet) and the first tick
     * where the train actually exists.
     */
    private static final java.util.Map<java.util.UUID, Long> PENDING_RESTORE = new java.util.HashMap<>();

    /** Register a retrying restore for a player who logged out while seated. */
    public static void registerPendingRestore(net.minecraft.server.level.ServerPlayer player, long deadlineTick) {
        // Disabled on the owner's ruling: a rejoin keeps the player exactly where they logged out, standing.
        // If the train has moved less than its own length they are still inside it and the normal seating
        // rules pick them up from there - which is the seat they left. Any further and they stand where they
        // are, which was accepted.
        //
        // Nothing registers, so tickPendingRestores() finds an empty table and returns immediately, and
        // hasPendingRestore() is always false. The machinery is left in place rather than deleted: it is
        // inert, and unwinding it by hand has already destroyed this file's other methods once today.
    }

    /** Whether this player's seat claim must not be pruned yet (a restore is pending). */
    public static boolean hasPendingRestore(java.util.UUID playerId) {
        return PENDING_RESTORE.containsKey(playerId);
    }

    /**
     * One retry per tick for every pending restore: when the train exists again, snap the
     * player back into their own claim. Deadline or missing train drops the pending state -
     * the player is then online and standing, and the normal prune releases the seat.
     */
    public static void tickPendingRestores(net.minecraft.server.MinecraftServer server) {
        if (PENDING_RESTORE.isEmpty())
            return;
        long now = server.getTickCount();
        for (var entry : java.util.Map.copyOf(PENDING_RESTORE).entrySet()) {
            net.minecraft.server.level.ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null)
                continue;                               // dropped again before the train loaded
            if (now > entry.getValue() || restoreClaim(player)) {
                PENDING_RESTORE.remove(entry.getKey());
            }
            // Otherwise the carriage is not loaded yet or the restore did not take: keep retrying until
            // the deadline. The claim itself is never dropped early - it is a binding.
        }
    }

    /**
     * The reported standing point, expressed in world coordinates through the carriage that named it.
     *
     * <p>Null when that carriage is not loaded: the client's point is only meaningful in a frame the server
     * can currently place, and guessing one would be worse than falling back to the server's own copy.
     */
    private static Vec3 intentWorld(Train train, SeatIntentStore.Intent intent) {
        if (intent == null)
            return null;
        CarriageContraptionEntity source = carriageEntity(train, intent.carriageIndex());
        if (source == null || source.getContraption() == null)
            return null;
        return source.toGlobalVector(new Vec3(intent.localX(), intent.localY(), intent.localZ()), 1.0f);
    }

    /** World position of a seat, for the client's own distance comparison. */
    public static Vec3 seatWorld(CarriageContraptionEntity entity, int index) {
        Contraption contraption = entity == null ? null : entity.getContraption();
        if (contraption == null || index < 0 || index >= contraption.getSeats().size())
            return null;
        return entity.toGlobalVector(Vec3.atCenterOf(contraption.getSeats().get(index)), 1.0f);
    }

    /**
     * Drops seat-mapping entries whose entity is no longer a passenger of that carriage.
     *
     * <p>A mapping entry is what makes a seat "taken". If one outlives the ride - a teleport, a death, a
     * chunk unload, a train edit - that seat is permanently unusable: right-click reaches Create's
     * addSittingPassenger, which sees an occupant at that index and returns without doing anything, while
     * the neighbouring seats keep working.
     *
     * <p>Every passenger counts, not just players. A mob in a seat has an entry and no Player behind it, so
     * a player-only sweep would delete it and hand that seat to somebody else.
     */
    public static void pruneStaleSeats(Train train) {
        for (Carriage carriage : train.carriages) {
            CarriageContraptionEntity entity = carriage.anyAvailableEntity();
            if (entity == null || entity.level().isClientSide())
                continue;
            Contraption contraption = entity.getContraption();
            if (contraption == null)
                continue;

            java.util.Set<UUID> aboard = new java.util.HashSet<>();
            for (Entity passenger : entity.getIndirectPassengers())
                aboard.add(passenger.getUUID());
            // A seat is held only by somebody actually in it - offline claims included. Since rejoining no
            // longer restores a seat (the player stands where they logged out and the normal rules seat them
            // from there), a claim left by an absent player does nothing except make that seat illegal for
            // everyone else, and for the returning player themselves when they stand next to it.

            Map<UUID, Integer> mapping;
            try {
                mapping = contraption.getSeatMapping();
            } catch (Throwable ignored) {
                continue;
            }
            if (mapping == null || mapping.isEmpty())
                continue;

            // Who is actually riding THIS carriage, asked of the entity rather than derived from the passenger
            // list. getIndirectPassengers is rebuilt by Create and is briefly empty for a rider at speed - the
            // list is the thing that lags, not the ride. Pruning on the list alone therefore deleted the seat of
            // a player who was sitting in it, at which point the driver read as "not aboard", the claim was
            // released, and the train went on without them while still holding their reserved seat.
            //
            // That is the operator being thrown out while holding J and never touching the dismount key.
            java.util.Set<UUID> ridingHere = new java.util.HashSet<>();
            for (Entity rider : entity.getPassengers())
                ridingHere.add(rider.getUUID());
            for (Entity rider : entity.getIndirectPassengers())
                ridingHere.add(rider.getUUID());

            boolean changed = false;
            java.util.Iterator<Map.Entry<UUID, Integer>> it = mapping.entrySet().iterator();
            while (it.hasNext()) {
                UUID seated = it.next().getKey();
                if (aboard.contains(seated) || ridingHere.contains(seated))
                    continue;
                it.remove();
                changed = true;
            }
            if (changed) {
                try {
                    contraption.setSeatMapping(mapping);
                } catch (Throwable ignored) {
                    // A copy we cannot write back is not worth a crash; the next tick tries again.
                }
                // The seat answer changed inside this tick, so any cached verdict is now wrong.
                CACHE.remove(train.id);
            }
        }
    }

    /**
     * Seats every standing player that a free seat can be found for, and returns how many were seated.
     *
     * <p>Each assignment claims the seat before the next player is considered, so two players can never be
     * given the same one. Ties on distance are broken by the seat's own position, which makes the outcome
     * independent of iteration order.
     */
    public static int trySeatAll(Train train) {
        // Debug entry: the grace window is pre-expired, so the pass seats immediately
        // from the server's own frame - which is what a standstill test wants anyway.
        return trySeatAll(train, Long.MAX_VALUE);
    }

    public static int trySeatAll(Train train, long serverTick) {
        Set<SeatRef> held = reservedDriverSeats(train);
        int seatedDriver = held.isEmpty() ? 0 : seatClaimedDriver(train, held);
        if (seatedDriver > 0)
            CACHE.remove(train.id);       // the seat mapping changed inside this tick

        List<Player> unseated = unseatedPlayers(train);
        if (unseated.isEmpty())
            return seatedDriver;

        record Candidate(int carriage, CarriageContraptionEntity entity, int index, BlockPos local) {}

        List<Candidate> free = new ArrayList<>();
        for (int carriageIndex = 0; carriageIndex < train.carriages.size(); carriageIndex++) {
            Carriage carriage = train.carriages.get(carriageIndex);
            CarriageContraptionEntity entity = carriage.anyAvailableEntity();
            if (entity == null || entity.level().isClientSide())
                continue;
            Contraption contraption = entity.getContraption();
            if (contraption == null)
                continue;

            List<BlockPos> seats = contraption.getSeats();
            Set<Integer> taken = new HashSet<>(contraption.getSeatMapping().values());
            for (int i = 0; i < seats.size(); i++) {
                if (taken.contains(i))
                    continue;
                // Held for a driver. Not a legal free position for anybody else: it reduces the
                // train's capacity rather than being handed out - see reservedDriverSeats.
                if (held.contains(new SeatRef(carriageIndex, i)))
                    continue;
                free.add(new Candidate(carriageIndex, entity, i, seats.get(i)));
            }
        }

        // The cache is what makes maxSpeed() cheap, and it is exactly what would lie here: the caller asks
        // anyUnseated() again immediately after this method, inside the same tick, and a cached "true" from
        // before the seating would report the train as overloaded after everybody had been seated - which
        // put the lever into BRAKE on a train that had just been fixed.
        CACHE.remove(train.id);

        int seatedHere = 0;
        for (Player player : unseated) {
            Candidate best = null;
            double bestDistance = Double.MAX_VALUE;

            // The seat the player's own client said it was standing nearest to, if that seat is still free on
            // the carriage it named. This is the whole point of the report: the client knows where it drew the
            // player, the server only knows where the movement packets last put them.
            SeatIntentStore.Intent intent = SeatIntentStore.get(player.getUUID());
            boolean hasIntent = intent != null && intent.trainId().equals(train.id);
            if (hasIntent) {
                for (Candidate candidate : free) {
                    if (candidate.carriage() == intent.carriageIndex()
                            && candidate.index() == intent.seatIndex()) {
                        best = candidate;
                        break;
                    }
                }
            }

            // The requested seat has just gone: resolve the substitute HERE, from the point the client
            // reported. Asking that client for a second report would put an extra round trip inside the one
            // path the owner asked to keep local - every contested seating, not just the uncontested ones.
            if (best == null && hasIntent) {
                Vec3 clientPoint = intentWorld(train, intent);
                if (clientPoint != null) {
                    int standingCarriage = intent.standingCarriageIndex() >= 0
                            ? intent.standingCarriageIndex() : intent.carriageIndex();
                    for (int pass = 0; pass < 2 && best == null; pass++) {
                        for (Candidate candidate : free) {
                            // Same carriage first; other carriages only when it has nothing left.
                            if (pass == 0 && candidate.carriage() != standingCarriage)
                                continue;
                            if (pass == 1 && candidate.carriage() == standingCarriage)
                                continue;
                            double d = candidate.entity().toLocalVector(clientPoint, 1.0f)
                                    .distanceToSqr(Vec3.atCenterOf(candidate.local()));
                            if (best == null
                                    || d < bestDistance - 1.0e-9d
                                    || (Math.abs(d - bestDistance) <= 1.0e-9d
                                        && candidate.local().compareTo(best.local()) < 0)) {
                                best = candidate;
                                bestDistance = d;
                            }
                        }
                    }
                }
            }

            // No report at all yet (a player who has only just boarded): ask once and give a silent client
            // the grace window. This is the only case that costs a round trip.
            if (best == null && !hasIntent) {
                Long requested = SeatIntentStore.requestedTick(player.getUUID());
                if (requested == null) {
                    SeatIntentStore.markRequested(player.getUUID(), serverTick);
                    if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer)
                        net.createmod.catnip.platform.CatnipServices.NETWORK.sendToClient(
                                serverPlayer, new RequestSeatIntentPacket());
                    continue;
                }
                if (serverTick - requested < SEAT_INTENT_GRACE_TICKS)
                    continue;
            }

            int ownCarriage = RiderSeatGate.carriageIndexOf(train, player);
            for (Candidate candidate : best == null ? free : java.util.List.<Candidate>of()) {
                // Same carriage first (the operator's refinement): the nearest free seat on the
                // carriage the player stands on beats a nearer-looking one across a coupling.
                // Cross-carriage candidates are only considered when the own carriage has none.
                if (ownCarriage >= 0 && candidate.carriage() != ownCarriage)
                    continue;
                // Compared in the carriage's own frame. Converting the seat to world coordinates and the
                // player to world coordinates as well looked equivalent but was not: the transform is
                // anchored on the contraption, so mixing the two put the seats in the wrong places and the
                // "nearest" seat was whichever arithmetic error won. In local space both sides share one
                // origin and the answer is the seat that is actually closest to the player.
                Vec3 localPlayer = candidate.entity().toLocalVector(player.position(), 1.0f);
                double d = Vec3.atCenterOf(candidate.local()).distanceToSqr(localPlayer);
                if (best == null
                        || d < bestDistance - 1.0e-9d
                        || (Math.abs(d - bestDistance) <= 1.0e-9d
                            && candidate.local().compareTo(best.local()) < 0)) {
                    best = candidate;
                    bestDistance = d;
                }
            }
            if (best == null && ownCarriage >= 0) {
                // Own carriage full: nearest free seat across the other carriages.
                for (Candidate candidate : free) {
                    double d = candidate.entity().toLocalVector(player.position(), 1.0f)
                            .distanceToSqr(Vec3.atCenterOf(candidate.local()));
                    if (best == null || d < bestDistance - 1.0e-9d) {
                        best = candidate;
                        bestDistance = d;
                    }
                }
            }

            // continue, not break. One player with no seat within reach must not stop the others from being
            // seated - that made the outcome depend on the order players happened to be listed in.
            if (best == null)
                continue;
            free.remove(best);

            // Create's own entry point for sitting somebody on a moving contraption. It writes the seat
            // mapping and starts the ride, so nothing here has to know how either works.
            SEATING.add(player.getUUID());
            try {
                best.entity().addSittingPassenger(player, best.index());
            } finally {
                SEATING.remove(player.getUUID());
            }
            // Tell the client which seat it landed in, so its intent state resets against
            // the authoritative choice instead of guessing from the render.
            if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer)
                net.createmod.catnip.platform.CatnipServices.NETWORK.sendToClient(
                        serverPlayer, new SeatAssignedPacket(best.carriage(), best.index()));
            SeatIntentStore.clear(player.getUUID());
            seatedHere++;
        }
        CACHE.remove(train.id);
        return seatedDriver + seatedHere;
    }

    /**
     * Applies the ceiling to a value that is already in Blocks per tick.
     *
     * <p>Gated on OVERLOAD, not on "somebody is standing". A player on their feet below the ceiling is the
     * intended way to move around a train; only when there are more people than seats does the ceiling
     * apply.
     */
    public static float clamp(Train train, float blocksPerTick) {
        if (blocksPerTick <= (float) createCeilingBpt())
            return blocksPerTick;

        // Which side is asking matters here. The server owns the flag; the client is told it through the
        // gauge packet. Reading the server field on the client returns false always, which left the HUD's
        // speed-bar denominator uncapped while the server was actually holding the train to the ceiling -
        // the bar then read low for the whole overload.
        var carriage = train.carriages.isEmpty() ? null : train.carriages.get(0).anyAvailableEntity();
        boolean overloaded;
        if (carriage != null && carriage.level().isClientSide()) {
            overloaded = TrainHudData.overloaded(train.id);
        } else {
            var data = ((com.george_vi.electroenergetics.mixin_interfaces.ICEETrainExtension) train)
                    .getElectricTrainData();
            overloaded = data.driver.overloaded;
        }
        // The cap is 28 m/s, and this method's answer is Blocks per TICK - so the ceiling has to be divided by the
        // tick rate, not by a hardcoded 20. At /tick rate 500 the old form returned 1.4 Blocks/tick, which is
        // 700 m/s there: the overload cap did nothing at all.
        return overloaded ? (float) createCeilingBpt() : blocksPerTick;
    }
}
