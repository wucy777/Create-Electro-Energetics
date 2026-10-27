package com.george_vi.electroenergetics.content.railway_electrification;

import com.simibubi.create.content.contraptions.Contraption;
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
 * <p>Two collections Create already maintains, per carriage:
 *
 * <ul>
 *   <li>{@code collidingEntities} - entities resting on the contraption. A player standing in the carriage,
 *       or on its roof, is in it. A player on the platform is not.</li>
 *   <li>{@code getIndirectPassengers()} - riders, including seated passengers.</li>
 * </ul>
 *
 * <p>Spectators are excluded: not counted, not carried, not followed.
 *
 * <h2>Free seats come from the carriage, not the world</h2>
 *
 * <p>{@code Contraption.getSeats()} lists every seat position in the carriage and the seat mapping says
 * which are taken, so a free seat is an index in the former that is absent from the latter. Only THIS
 * train's carriages are read, so a nearer seat on another train, or a seat block standing in the world, can
 * never be used.
 */
public final class RiderSeatGate {

    // Temporary: one line per seating event, so a wrong seat can be diagnosed from the log instead of
    // guessed at. Costs nothing when nobody is being seated.
    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("electroenergetics/seatdiag");

    private RiderSeatGate() {}

    /**
     * How far a player may be moved to a seat, in Blocks.
     *
     * <p>Generous enough for the length of a carriage bay, small enough that the player can see where they
     * ended up. Beyond this the situation is treated as "no seat for you", which is the honest answer.
     */
    public static final double MAX_SEAT_TRANSFER_BLOCKS = 4d;

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
        return train != null && Math.abs(train.speed) >= CREATE_CEILING_BPT - 1.0e-4d;
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
        List<Player> out = new ArrayList<>();
        for (Carriage carriage : train.carriages) {
            CarriageContraptionEntity entity = carriage.anyAvailableEntity();
            if (entity == null || entity.level().isClientSide())
                continue;
            Contraption contraption = entity.getContraption();
            if (contraption == null)
                continue;

            // Seated means "is a passenger of this carriage". Create seats a player by starting the ride on
            // the carriage, so a seated player is always in this set - and unlike the seat mapping it is a
            // property of this entity instance, which is the instance the seating call was made on.
            //
            // The mapping alone was not reliable enough to answer this: the player was seated into a seat,
            // the mapping did not report them, and every tick repeated "one player is standing" - over
            // capacity, the message, and the brake at the limit, forever, on a train full of empty seats.
            java.util.Set<UUID> seated = new java.util.HashSet<>();
            for (Entity passenger : entity.getIndirectPassengers())
                seated.add(passenger.getUUID());
            seated.addAll(contraption.getSeatMapping().keySet());

            for (Entity colliding : entity.collidingEntities.keySet())
                collect(colliding, seated, out);
            for (Entity passenger : entity.getIndirectPassengers())
                collect(passenger, seated, out);
            // collidingEntities is filled by the collision pass, so a player who walked in and stopped may
            // never appear in it. Their own box against the carriage's is the cheap exact test.
            for (var nearby : entity.level().players())
                if (entity.getBoundingBox().intersects(nearby.getBoundingBox()))
                    collect(nearby, seated, out);
        }
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
     * Whether a dismount by this player must be refused: they are in a seat on a train carriage, and the
     * train is at or above the ceiling.
     *
     * <p>Shared by the two mixins that guard the dismount, so the rule cannot differ between them.
     */
    public static boolean shouldLockDismount(net.minecraft.world.entity.player.Player player) {
        if (!(player instanceof net.minecraft.server.level.ServerPlayer))
            return false;
        if (player.level().isClientSide())
            return false;

        com.simibubi.create.content.trains.entity.CarriageContraptionEntity carriage = null;
        if (player.getVehicle()
                instanceof com.simibubi.create.content.trains.entity.CarriageContraptionEntity direct)
            carriage = direct;
        else if (player.getVehicle()
                instanceof com.simibubi.create.content.contraptions.actors.seat.SeatEntity seat
                && seat.getRootVehicle()
                instanceof com.simibubi.create.content.trains.entity.CarriageContraptionEntity root)
            carriage = root;
        if (carriage == null)
            return false;

        Contraption contraption = carriage.getContraption();
        if (contraption == null || !contraption.getSeatMapping().containsKey(player.getUUID()))
            return false;

        var c = carriage.getCarriage();
        return c != null && c.train != null && atOrAboveCeiling(c.train);
    }

    /** Tells the player their dismount was refused. */
    public static void refuseDismount(net.minecraft.world.entity.player.Player player) {
        player.displayClientMessage(
                net.minecraft.network.chat.Component.translatable(
                        "electroenergetics.train.riders.seatLocked"), true);
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

            Map<UUID, Integer> mapping;
            try {
                mapping = contraption.getSeatMapping();
            } catch (Throwable ignored) {
                continue;
            }
            if (mapping == null || mapping.isEmpty())
                continue;

            boolean changed = false;
            java.util.Iterator<Map.Entry<UUID, Integer>> it = mapping.entrySet().iterator();
            while (it.hasNext()) {
                if (!aboard.contains(it.next().getKey())) {
                    it.remove();
                    changed = true;
                }
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
        List<Player> unseated = unseatedPlayers(train);
        if (unseated.isEmpty())
            return 0;

        record Candidate(CarriageContraptionEntity entity, int index, BlockPos local) {}

        List<Candidate> free = new ArrayList<>();
        int totalSeats = 0;   // SEATDIAG
        for (Carriage carriage : train.carriages) {
            CarriageContraptionEntity entity = carriage.anyAvailableEntity();
            if (entity == null || entity.level().isClientSide())
                continue;
            Contraption contraption = entity.getContraption();
            if (contraption == null)
                continue;

            List<BlockPos> seats = contraption.getSeats();
            totalSeats += seats.size();   // SEATDIAG
            Set<Integer> taken = new HashSet<>(contraption.getSeatMapping().values());
            for (int i = 0; i < seats.size(); i++) {
                if (taken.contains(i))
                    continue;
                free.add(new Candidate(entity, i, seats.get(i)));
            }
        }

        // The cache is what makes maxSpeed() cheap, and it is exactly what would lie here: the caller asks
        // anyUnseated() again immediately after this method, inside the same tick, and a cached "true" from
        // before the seating would report the train as overloaded after everybody had been seated - which
        // put the lever into BRAKE on a train that had just been fixed.
        CACHE.remove(train.id);

        int seated = 0;
        for (Player player : unseated) {
            Candidate best = null;
            double bestDistance = Double.MAX_VALUE;
            StringBuilder diag = null;   // SEATDIAG
            for (Candidate candidate : free) {
                // Compared in the carriage's own frame. Converting the seat to world coordinates and the
                // player to world coordinates as well looked equivalent but was not: the transform is
                // anchored on the contraption, so mixing the two put the seats in the wrong places and the
                // "nearest" seat was whichever arithmetic error won. In local space both sides share one
                // origin and the answer is the seat that is actually closest to the player.
                Vec3 localPlayer = candidate.entity().toLocalVector(player.position(), 1.0f);
                double d = Vec3.atCenterOf(candidate.local()).distanceToSqr(localPlayer);
                // A seat has to be somewhere the player could have walked to. Handing them one at the far
                // end of the train is not seating them, it is teleporting them: they lose their bearings and
                // whatever they were doing. If nothing is close, this is a capacity problem and is treated
                // as one rather than solved by moving a player five Blocks.
                if (diag == null) {
                    diag = new StringBuilder("SEATDIAG playerLocal=")
                            .append(String.format("%.2f,%.2f,%.2f", localPlayer.x, localPlayer.y, localPlayer.z))
                            .append(" seats=").append(totalSeats)
                            .append(" free=").append(free.size());
                }
                diag.append(" #").append(candidate.index()).append(candidate.local())
                        .append(String.format("@%.2f", Math.sqrt(d)));
                if (d > MAX_SEAT_TRANSFER_BLOCKS * MAX_SEAT_TRANSFER_BLOCKS)
                    continue;
                if (best == null
                        || d < bestDistance - 1.0e-9d
                        || (Math.abs(d - bestDistance) <= 1.0e-9d
                            && candidate.local().compareTo(best.local()) < 0)) {
                    best = candidate;
                    bestDistance = d;
                }
            }
            if (diag != null)
                LOGGER.info(diag.append(best == null ? " -> none" : " -> chosen #" + best.index()).toString());
            if (best == null)
                break;
            free.remove(best);

            // Create's own entry point for sitting somebody on a moving contraption. It writes the seat
            // mapping and starts the ride, so nothing here has to know how either works.
            best.entity().addSittingPassenger(player, best.index());
            seated++;
        }
        CACHE.remove(train.id);
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
        return overloaded ? (float) CREATE_CEILING_BPT : blocksPerTick;
    }
}