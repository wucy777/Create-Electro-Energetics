package com.george_vi.electroenergetics.content.railway_electrification;

import com.simibubi.create.content.contraptions.actors.seat.SeatEntity;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.HashMap;
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

    /** Applies the ceiling to a value that is already in Blocks per tick. */
    public static float clamp(Train train, float blocksPerTick) {
        if (blocksPerTick <= (float) CREATE_CEILING_BPT)
            return blocksPerTick;
        return allSeated(train) ? blocksPerTick : (float) CREATE_CEILING_BPT;
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