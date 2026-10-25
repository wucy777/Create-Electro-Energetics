package com.george_vi.electroenergetics.content.railway_electrification;

import com.simibubi.create.content.contraptions.actors.seat.SeatEntity;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Answers one question: is anybody aboard this train standing up?
 *
 * <h2>The two sources, and why not a bounding box</h2>
 *
 * <p>This used to scan a world AABB two Blocks around each carriage. That counted players on the platform,
 * spectators flying past, and both trains in a passing loop - so an ordinary station stop pinned the train
 * at Create's ceiling, and two trains meeting did it to each other. The box was my invention; nothing in
 * Create works that way.
 *
 * <p>Create already tracks both populations this needs, and the answer is read from them instead:
 *
 * <ul>
 *   <li>{@code AbstractContraptionEntity.collidingEntities} - the map Create maintains of entities resting
 *       on a contraption. A player standing in the carriage, or on its roof, is in it. A player on the
 *       platform is not.</li>
 *   <li>{@code getIndirectPassengers()} - riders, including a player sitting on a seat, which is itself a
 *       passenger of the contraption.</li>
 * </ul>
 *
 * <p>Both are per-contraption, so a passing train cannot see the other's occupants, and neither query looks
 * at anything outside the train.
 *
 * <h2>Spectators</h2>
 *
 * <p>Excluded outright. A spectator is not part of the world: they are not carried, they are not counted,
 * and their camera follows whatever they are spectating. Leaving them in meant a spectator drifting through
 * a carriage capped the train.
 *
 * <h2>Roof</h2>
 *
 * <p>A player on the roof is in {@code collidingEntities} and is not on a seat, so they count as standing -
 * which is what the owner asked for. Riding on the outside of a train at line speed is exactly the case the
 * ceiling exists to prevent.
 *
 * <h2>Cost</h2>
 *
 * <p>Two small collections per carriage, cached for the tick because several callers of {@code maxSpeed()}
 * ask inside one. Nothing is allocated and no bounding-box query touches the level.
 */
public final class RiderSeatGate {

    private RiderSeatGate() {}

    /** Create's own ceiling, in Blocks per second. Above this its rider model does not hold. */
    public static final double CREATE_CEILING_BPS = 28d;

    /** The same figure in Blocks per tick, which is what {@code maxSpeed()} returns. */
    public static final double CREATE_CEILING_BPT = CREATE_CEILING_BPS / 20d;

    private static final Map<UUID, Boolean> CACHE = new HashMap<>();
    private static long cacheStamp = -1L;

    /** Whether every player aboard is sitting in a seat. */
    public static boolean allSeated(Train train) {
        if (train == null || train.carriages == null || train.carriages.isEmpty())
            return true;

        long stamp = System.currentTimeMillis() / 50L;
        if (stamp != cacheStamp) {
            cacheStamp = stamp;
            CACHE.clear();
        }
        Boolean cached = CACHE.get(train.id);
        if (cached != null)
            return cached;

        boolean result = compute(train);
        CACHE.put(train.id, result);
        return result;
    }

    /** Whether anybody aboard is standing, on the roof included. */
    public static boolean anyUnseated(Train train) {
        return !allSeated(train);
    }

    /**
     * The players aboard who are not sitting, in a deterministic order.
     *
     * <p>Sorted by UUID rather than by whatever order the level hands them out. Two players contending for
     * the same seat resolve in a fixed order because of this, so the same situation always produces the same
     * seating - not a different one after a reload.
     */
    public static List<Player> unseatedPlayers(Train train) {
        List<Player> out = new ArrayList<>();
        for (Carriage carriage : train.carriages) {
            CarriageContraptionEntity entity = carriage.anyAvailableEntity();
            if (entity == null || entity.level().isClientSide())
                continue;
            for (Entity colliding : entity.collidingEntities.keySet())
                collect(colliding, out);
            for (Entity passenger : entity.getIndirectPassengers())
                collect(passenger, out);
        }
        out.sort(Comparator.comparing(Player::getUUID));
        return out;
    }

    private static void collect(Entity entity, List<Player> out) {
        if (entity instanceof Player player && !player.isSpectator() && !isSeated(player) && !out.contains(player))
            out.add(player);
    }

    /**
     * Seats every standing player that a free seat can be found for, and returns how many were seated.
     *
     * <p>Only seats belonging to THIS train are considered: the free seats are read from the carriages'
     * own passengers, so a nearer seat on another train, or a seat block standing in the world, is not a
     * candidate. That was an explicit instruction - a player must not be moved onto a different train.
     *
     * <p>Each assignment claims the seat before the next player is considered, so two players can never be
     * given the same one. Ties on distance are broken by the seat's own block position, which makes the
     * outcome independent of iteration order.
     */
    public static int trySeatAll(Train train) {
        List<Player> unseated = unseatedPlayers(train);
        if (unseated.isEmpty())
            return 0;

        List<SeatEntity> free = new ArrayList<>();
        for (Carriage carriage : train.carriages) {
            CarriageContraptionEntity entity = carriage.anyAvailableEntity();
            if (entity == null)
                continue;
            for (Entity passenger : entity.getPassengers())
                if (passenger instanceof SeatEntity seat && seat.getPassengers().isEmpty() && !free.contains(seat))
                    free.add(seat);
        }

        int seated = 0;
        for (Player player : unseated) {
            SeatEntity best = null;
            double bestDistance = Double.MAX_VALUE;
            for (SeatEntity seat : free) {
                double d = seat.distanceToSqr(player);
                if (best == null
                        || d < bestDistance - 1.0e-9d
                        || (Math.abs(d - bestDistance) <= 1.0e-9d && seat.blockPosition().compareTo(best.blockPosition()) < 0)) {
                    best = seat;
                    bestDistance = d;
                }
            }
            if (best == null)
                break;
            free.remove(best);
            if (player.startRiding(best, true))
                seated++;
        }
        return seated;
    }

    /**
     * Applies the ceiling to a value that is already in Blocks per tick.
     *
     * <p>Gated on OVERLOAD, not on "somebody is standing". A player on their feet below the ceiling is the
     * intended way to move around a train; only when there are more people than seats does the ceiling
     * apply.
     */
    public static float clamp(Train train, float blocksPerTick) {
        if (blocksPerTick <= (float) CREATE_CEILING_BPT)
            return blocksPerTick;

        var data = ((com.george_vi.electroenergetics.mixin_interfaces.ICEETrainExtension) train)
                .getElectricTrainData();
        return data.driver.overloaded ? (float) CREATE_CEILING_BPT : blocksPerTick;
    }

    private static boolean compute(Train train) {
        for (Carriage carriage : train.carriages) {
            CarriageContraptionEntity entity = carriage.anyAvailableEntity();
            if (entity == null)
                continue;

            // The client has neither of the two collections below - collidingEntities is filled by the
            // collider on the side that owns the world - so it must not decide this for itself. Its ceiling
            // arrives already capped through the synced sample.
            if (entity.level().isClientSide())
                return true;

            for (Entity colliding : entity.collidingEntities.keySet())
                if (colliding instanceof Player player && !player.isSpectator() && !isSeated(player))
                    return false;

            for (Entity passenger : entity.getIndirectPassengers())
                if (passenger instanceof Player player && !player.isSpectator() && !isSeated(player))
                    return false;
        }
        return true;
    }

    private static boolean isSeated(Player player) {
        return player.getVehicle() instanceof SeatEntity;
    }
}