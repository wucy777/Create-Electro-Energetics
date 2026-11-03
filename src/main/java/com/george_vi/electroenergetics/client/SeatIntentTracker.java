package com.george_vi.electroenergetics.client;

import com.george_vi.electroenergetics.content.railway_electrification.ReportSeatIntentPacket;
import com.george_vi.electroenergetics.content.railway_electrification.RiderSeatGate;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import net.createmod.catnip.platform.CatnipServices;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Reports, from the client, which seat the player is standing nearest to.
 *
 * <h2>Why here</h2>
 *
 * <p>This is the side that draws the player. The server's copy of a passenger's position on a moving
 * contraption is rebuilt from movement packets and lags the carriage, which is how a player standing on the
 * driver's seat kept being seated several Blocks behind it. Asking the client what it is standing on removes
 * the guess instead of compensating for it.
 *
 * <h2>Cost</h2>
 *
 * <p>Edge triggered. One packet when the nearest seat changes; nothing while it stays the same, nothing while
 * the player is seated, nothing below the ceiling, nothing when not on a train. Standing still costs no
 * traffic at all.
 */
public final class SeatIntentTracker {

    private SeatIntentTracker() {}

    private static boolean sent;
    private static int lastSeat = Integer.MIN_VALUE;
    private static int lastCarriage = Integer.MIN_VALUE;

    /** The seat the server said we actually got; null until it says something. */
    private static int assignedCarriage = Integer.MIN_VALUE;
    private static int assignedSeat = Integer.MIN_VALUE;

    /** The server has asked for a report: forget the edge state so the next tick sends. */
    public static void requestReport() {
        sent = false;
    }

    public static void acceptAssignment(int carriageIndex, int seatIndex) {
        assignedCarriage = carriageIndex;
        assignedSeat = seatIndex;
    }

    public static int assignedSeat() {
        return assignedSeat;
    }

    public static int assignedCarriage() {
        return assignedCarriage;
    }

    /** Reset edge state when the local player or world is replaced. */
    public static void reset() {
        sent = false;
        lastSeat = Integer.MIN_VALUE;
        lastCarriage = Integer.MIN_VALUE;
        assignedCarriage = Integer.MIN_VALUE;
        assignedSeat = Integer.MIN_VALUE;
    }

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || player.level() == null) {
            reset();
            return;
        }

        // A player who NEEDS a seat is standing: their root vehicle is themselves, so testing only
        // getRootVehicle() meant this never reported for the one case it exists for - and the server fell
        // back to its own position, which is the lagging one. Standing players find the carriage by
        // intersection instead.
        Entity root = player.getRootVehicle();
        CarriageContraptionEntity carriage;
        if (root instanceof CarriageContraptionEntity riding) {
            carriage = riding;
        } else {
            carriage = standingCarriage(player);
        }
        if (carriage == null) {
            sent = false;
            return;
        }
        var cc = carriage.getCarriage();
        if (cc == null || cc.train == null) {
            sent = false;
            return;
        }
        Train train = cc.train;
        Contraption contraption = carriage.getContraption();
        if (contraption == null) {
            sent = false;
            return;
        }
        if (contraption.getSeatMapping().containsKey(player.getUUID())) {
            sent = false;                                  // seated: nothing to report
            return;
        }
        // Report from well below the ceiling, not only at it. The server needs the answer BEFORE the train
        // arrives at 28; waiting until 28 meant the first tick of the rule had nothing to use and fell back
        // to its own lagging position. Edge-triggered, so a steady speed costs no packets.
        if (Math.abs(train.speed) < 1.0d) {
            sent = false;                                  // standing on a stopped train is not a case
            return;
        }

        // The carriage the client actually found, by index in the train. Using this rather than asking the
        // gate again keeps the report and the pick in one frame: a seat may be on a neighbouring carriage, but
        // the "own carriage first" pass and the server's conflict fallback must agree with what is on screen.
        int ownCarriage = train.carriages.indexOf(cc);
        if (ownCarriage < 0)
            ownCarriage = RiderSeatGate.carriageIndexOf(train, player);
        int best = -1;
        int bestCarriage = -1;
        double bestDistance = Double.MAX_VALUE;
        // Two passes with the operator's priority: nearest on the player's OWN carriage
        // first; the other carriages only when the own one has no free seat.
        for (int pass = 0; pass < 2 && best < 0; pass++) {
        for (int ci = 0; ci < train.carriages.size(); ci++) {
            if (pass == 0 && ci != ownCarriage)
                continue;
            if (pass == 1 && ci == ownCarriage)
                continue;
            CarriageContraptionEntity e = train.carriages.get(ci).anyAvailableEntity();
            if (e == null)
                continue;
            Contraption c2 = e.getContraption();
            if (c2 == null)
                continue;
            java.util.Set<Integer> taken =
                    new java.util.HashSet<>(c2.getSeatMapping().values());
            java.util.List<net.minecraft.core.BlockPos> seats = c2.getSeats();
            for (int i = 0; i < seats.size(); i++) {
                if (taken.contains(i))
                    continue;
                Vec3 seat = RiderSeatGate.seatWorld(e, i);
                if (seat == null)
                    continue;
                double d = seat.distanceToSqr(player.position());
                if (d < bestDistance) {
                    bestDistance = d;
                    best = i;
                    bestCarriage = ci;
                }
            }
        }
        }
        if (best < 0 || bestCarriage < 0) {
            sent = false;
            return;
        }
        int carriageIndex = bestCarriage;

        if (sent && best == lastSeat && carriageIndex == lastCarriage)
            return;                                        // nothing changed: no traffic

        CarriageContraptionEntity targetCarriage = train.carriages.get(carriageIndex).anyAvailableEntity();
        if (targetCarriage == null) {
            sent = false;
            return;
        }
        Vec3 local = targetCarriage.toLocalVector(player.position(), 1.0f);
        CatnipServices.NETWORK.sendToServer(new ReportSeatIntentPacket(train.id, carriageIndex, best,
                ownCarriage, local.x, local.y, local.z, true));
        sent = true;
        lastSeat = best;
        lastCarriage = carriageIndex;
    }

    /**
     * The carriage this player is standing on, found by intersection, or null.
     *
     * <p>Client side, so the position used is the one the player sees themselves at - which is the entire
     * point of reporting from here rather than letting the server guess.
     */
    private static CarriageContraptionEntity standingCarriage(LocalPlayer player) {
        CarriageContraptionEntity found = null;
        double best = Double.MAX_VALUE;
        var nearby = player.level().getEntitiesOfClass(
                CarriageContraptionEntity.class, player.getBoundingBox().inflate(2.0d));
        for (CarriageContraptionEntity entity : nearby) {
            if (!RiderSeatGate.isOnContraption(player, entity))
                continue;
            double d = entity.distanceToSqr(player);
            if (d < best) {
                best = d;
                found = entity;
            }
        }
        return found;
    }

}
