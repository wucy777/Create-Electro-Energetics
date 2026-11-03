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
        return train != null && Math.abs(train.speed) >= createCeilingBpt() - 1.0e-4d;
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
        entity.addSittingPassenger(player, seat);
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
        chosenEntity.addSittingPassenger(player, bestIndex);
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
        List<BlockPos> seats = entity.getContraption().getSeats();
        int wanted = nearestSeat(seats, driver.driverDesk);
        if (wanted < 0)
            return false;
        return !Integer.valueOf(wanted).equals(entity.getContraption().getSeatMapping()
                .get(driver.claimOwner));
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
        if (held.isEmpty())
            return 0;
        TrainDriverState driverState = ((com.george_vi.electroenergetics.mixin_interfaces.ICEETrainExtension) train)
                .getElectricTrainData().driver;
        UUID claimed = driverState.claimOwner;
        if (claimed == null)
            return 0;

        CarriageContraptionEntity deskCarriage = carriageEntity(train, driverState.driverCarriage);
        if (deskCarriage == null || deskCarriage.level().isClientSide())
            return 0;
        Player driver = null;
        for (Player candidate : deskCarriage.level().players()) {
            if (candidate.getUUID().equals(claimed)) {
                driver = candidate;
                break;
            }
        }
        if (driver == null)
            return 0;

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
        if (!aboard)
            return 0;

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
        if (current != null && held.contains(current))
            return 0;
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

            entity.addSittingPassenger(driver, seat.seatIndex());
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
    private static final Set<UUID> FORCED_DISMOUNTS = new HashSet<>();    /**
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
            best.entity().addSittingPassenger(player, best.index());
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
        return overloaded ? (float) createCeilingBpt() : blocksPerTick;
    }
}
