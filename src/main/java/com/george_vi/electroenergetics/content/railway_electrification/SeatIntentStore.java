package com.george_vi.electroenergetics.content.railway_electrification;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The last seat a player's own client said it was standing nearest to.
 *
 * <p>Server side only. The client is the side that draws the player, so its answer is the one that matches
 * what the player sees; this keeps it until the server needs it. Cleared when the player stops reporting, logs
 * out, or the intent no longer belongs to the train being seated.
 *
 * <p>Holds one small record per player who has ever reported - a few dozen bytes, and nothing is sent or
 * stored while a player is seated or moving about below the ceiling.
 */
public final class SeatIntentStore {

    private SeatIntentStore() {}

    /** What the client reported: which seat it is nearest to, and where it is in the carriage. */
    public record Intent(UUID trainId, int carriageIndex, int seatIndex,
                         double localX, double localY, double localZ) {}

    private static final Map<UUID, Intent> INTENTS = new HashMap<>();

    /** Server tick of the first unanswered seat-intent request, per player. */
    private static final Map<UUID, Long> REQUESTED = new HashMap<>();

    public static void put(UUID player, Intent intent) {
        INTENTS.put(player, intent);
        REQUESTED.remove(player);   // the answer arrived; any wait deadline is moot
    }

    public static Intent get(UUID player) {
        return INTENTS.get(player);
    }

    public static void markRequested(UUID player, long tick) {
        REQUESTED.putIfAbsent(player, tick);
    }

    /** Server tick of the first unanswered request, or null when none is in flight. */
    public static Long requestedTick(UUID player) {
        return REQUESTED.get(player);
    }

    public static void clear(UUID player) {
        INTENTS.remove(player);
        REQUESTED.remove(player);
    }
}
