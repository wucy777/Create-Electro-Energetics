package com.george_vi.electroenergetics.content.railway_electrification;

import com.simibubi.create.content.contraptions.actors.seat.SeatEntity;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Holds a train to Create's own speed ceiling while anyone aboard is free to move around.
 *
 * <h2>The rule</h2>
 *
 * <p>Create's rider physics are designed for a train that advances well under a Block per tick. Above that,
 * a passenger who is not in a seat has no stable frame to stand in: they sink through a floor the game does
 * not recognise, slide along at the carriage's speed while doing it, and every few ticks the accumulated
 * deficit is settled in one step. That is the entire history of this bug, and it is unfixable by adjusting
 * where the rider is put, because the state itself is outside what the engine supports.
 *
 * <p>So the state is made unreachable instead. While any player aboard is not in a seat, the train's ceiling
 * is Create's own - 28 Blocks per second - and Create's own rider code is correct in that range, unchanged
 * and never overridden. When everyone is seated the electric ceiling applies again and the train is free to
 * run at the speeds this mod exists to allow.
 *
 * <h2>Why the ceiling and not an override</h2>
 *
 * <p>{@code Train.maxSpeed()} is the single figure both control schemes consult: Create computes its target
 * speed from it, and this mod's gear law reads it for its own ceiling. Clamping there means the two cannot
 * disagree, and no code path needs to know which scheme is in charge. Nothing is forced, nothing is
 * re-implemented; the train simply is not allowed to ask for a speed at which the problem exists.
 *
 * <h2>Cost</h2>
 *
 * <p>One bounding-box query per carriage per train per tick, cached for the tick so the several callers of
 * {@code maxSpeed()} pay for it once. A train with nobody inside is one empty query. This is the minimum
 * that can answer "is anyone standing up on this train" at all.
 */
public final class RiderSeatGate {

    private RiderSeatGate() {}

    /** Create's own ceiling, in Blocks per second. Above this its rider model does not hold. */
    public static final double CREATE_CEILING_BPS = 28d;

    /** The same figure in Blocks per tick, which is what {@code maxSpeed()} returns. */
    public static final double CREATE_CEILING_BPT = CREATE_CEILING_BPS / 20d;

    /** Per-tick cache: several callers ask the same question inside one tick. */
    private static final Map<UUID, Boolean> CACHE = new HashMap<>();
    private static long cacheStamp = -1L;

    /**
     * Whether every player aboard this train is sitting in a seat.
     *
     * <p>A player standing in a carriage is a passenger of the contraption, or not a passenger at all - in
     * both cases their vehicle is something other than a seat, which is what this tests.
     */
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
            AABB box = entity.getBoundingBox();
            if (box == null)
                continue;
            // Generous by two Blocks: the contraption's own bounds are the carriages' build volume, and a
            // player pressed against a wall or mid-jump is still aboard.
            for (Player player : entity.level().getEntitiesOfClass(Player.class, box.inflate(2d))) {
                if (!(player.getVehicle() instanceof SeatEntity))
                    return false;
            }
        }
        return true;
    }
}