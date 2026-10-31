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
            // Offline players keep their seat. A player who logs off while sitting is removed from the
            // passenger list, so "not aboard" would delete their entry - and on rejoin they would no longer
            // be seated, landing wherever their saved position happened to be and then being force-seated
            // into whatever seat was nearest at that moment. That is the seat that "changed after rejoining".
            // A seat is only released when its player is online and demonstrably not in it.

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
                UUID id = it.next().getKey();
                if (aboard.contains(id))
                    continue;
                // Online and not aboard: they really did leave the seat.
                if (entity.level().getPlayerByUUID(id) != null) {
                    it.remove();
                    changed = true;
                }
                // Offline: keep it. They are expected back, in that seat.
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
        List<Player> unseated = unseatedPlayers(train);
        if (unseated.isEmpty())
            return 0;

        record Candidate(int carriage, CarriageContraptionEntity entity, int index, BlockPos local) {}

        List<Candidate> free = new ArrayList<>();
        int totalSeats = 0;   // SEATDIAG
        for (int carriageIndex = 0; carriageIndex < train.carriages.size(); carriageIndex++) {
            Carriage carriage = train.carriages.get(carriageIndex);
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
                free.add(new Candidate(carriageIndex, entity, i, seats.get(i)));
            }
        }

        // The cache is what makes maxSpeed() cheap, and it is exactly what would lie here: the caller asks
        // anyUnseated() again immediately after this method, inside the same tick, and a cached "true" from
        // before the seating would report the train as overloaded after everybody had been seated - which
        // put the lever into BRAKE on a train that had just been fixed.
        CACHE.remove(train.id);

        String seatedMapHint = "";   // SEATDIAG
        String aboardHint = "";      // SEATDIAG
        int seated = 0;
        for (Player player : unseated) {
            Candidate best = null;
            double bestDistance = Double.MAX_VALUE;

            // The seat the player's own client said it was standing nearest to, if that seat is still free on
            // the carriage it named. This is the whole point of the report: the client knows where it drew the
            // player, the server only knows where the movement packets last put them.
            SeatIntentStore.Intent intent = SeatIntentStore.get(player.getUUID());
            if (intent != null && intent.trainId().equals(train.id)) {
                for (Candidate candidate : free) {
                    if (candidate.carriage() == intent.carriageIndex()
                            && candidate.index() == intent.seatIndex()) {
                        best = candidate;
                        break;
                    }
                }
            }
            // No usable intent: ask the client which seat it sees, and WAIT for the answer
            // before falling back to the server's own frame. The server's copy of a standing
            // player's position lags at speed, so seating from it right away is exactly what
            // produced the "not the seat I saw" reports. The wait is one to two ticks - the
            // client answers on its next tick - with a grace cap so a silent client still gets
            // seated. The capacity rules keep running; only the CHOICE waits.
            if (best == null) {
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

            StringBuilder diag = null;   // SEATDIAG
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
                // Compared in the carriage's own frame, with no correction applied. An earlier version
                // multiplied the carriage's per-tick movement by a measured lag and added it to the player's
                // position; without a way to prove that factor it was a guess dressed as a measurement, and
                // a guess inside the selection is worse than a known-wrong frame. The authoritative position
                // now comes from the client, which is the side that draws the player.
                Vec3 localPlayer = candidate.entity().toLocalVector(player.position(), 1.0f);
                double d = Vec3.atCenterOf(candidate.local()).distanceToSqr(localPlayer);
                // A seat has to be somewhere the player could have walked to. Handing them one at the far
                // end of the train is not seating them, it is teleporting them: they lose their bearings and
                // whatever they were doing. If nothing is close, this is a capacity problem and is treated
                // as one rather than solved by moving a player five Blocks.
                if (diag == null) {
                    StringBuilder mh = new StringBuilder();
                    StringBuilder ah = new StringBuilder();
                    for (Carriage cc : train.carriages) {
                        CarriageContraptionEntity ce = cc.anyAvailableEntity();
                        if (ce == null || ce.getContraption() == null) continue;
                        mh.append("{");
                        ce.getContraption().getSeatMapping().forEach(
                                (u, i) -> mh.append(u.toString(), 0, 8).append("=").append(i).append(","));
                        mh.append("}");
                        ah.append("[");
                        for (Entity p : ce.getIndirectPassengers())
                            ah.append(p.getUUID().toString(), 0, 8).append(",");
                        ah.append("]");
                    }
                    seatedMapHint = mh.toString();
                    aboardHint = ah.toString();
                    var v = player.position();
                    diag = new StringBuilder("SEATDIAG playerWorld=")
                            .append(String.format("%.2f,%.2f,%.2f", v.x, v.y, v.z))
                            .append(" local=")
                            .append(String.format("%.2f,%.2f,%.2f", localPlayer.x, localPlayer.y, localPlayer.z))
                            .append(" seats=").append(totalSeats)
                            .append(" free=").append(free.size())
                            .append(" mapping=").append(seatedMapHint)
                            .append(" aboard=").append(aboardHint);
                }
                var seatWorld = candidate.entity().toGlobalVector(Vec3.atCenterOf(candidate.local()), 1.0f);
                diag.append(" #").append(candidate.index()).append(candidate.local())
                        .append(String.format("@%.2f", Math.sqrt(d)))
                        .append("[w").append(String.format("%.1f,%.1f,%.1f",
                                seatWorld.x, seatWorld.y, seatWorld.z)).append("]");
                // No distance cap. The owner's ruling: seat the nearest free seat on THIS train,
                // however far down the carriage it is. Being picked out of another train or off the
                // platform is prevented upstream by isOnContraption - only players actually standing
                // on this carriage's own blocks are ever considered - so a cap on top of that only
                // produced false "over capacity" reports with empty seats in sight.
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
                    if (best == null || d < bestDistance) {
                        best = candidate;
                        bestDistance = d;
                    }
                }
            }
            if (diag != null)
                LOGGER.info(diag.append(best == null ? " -> none" : " -> chosen #" + best.index()).toString());
            // continue, not break. One player with no seat within reach must not stop the others from being
            // seated - that made the outcome depend on the order players happened to be listed in.
            if (best == null)
                continue;
            free.remove(best);

            // Create's own entry point for sitting somebody on a moving contraption. It writes the seat
            // mapping and starts the ride, so nothing here has to know how either works.
            best.entity().addSittingPassenger(player, best.index());
            // Tell the client which seat it landed in, so its intent state resets against
            // the authoritative choice instead of guessing from the render.
            if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer)
                net.createmod.catnip.platform.CatnipServices.NETWORK.sendToClient(
                        serverPlayer, new SeatAssignedPacket(best.carriage(), best.index()));
            SeatIntentStore.clear(player.getUUID());
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