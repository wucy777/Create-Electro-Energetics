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

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || player.level() == null) {
            sent = false;
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
        if (Math.abs(train.speed) < RiderSeatGate.CREATE_CEILING_BPT) {
            sent = false;                                  // standing below the ceiling is legal
            return;
        }

        int best = nearestFreeSeat(carriage, contraption, player.position());
        int carriageIndex = RiderSeatGate.carriageIndexOf(train, player);

        if (sent && best == lastSeat && carriageIndex == lastCarriage)
            return;                                        // nothing changed: no traffic

        Vec3 local = carriage.toLocalVector(player.position(), 1.0f);
        CatnipServices.NETWORK.sendToServer(new ReportSeatIntentPacket(train.id, carriageIndex, best,
                local.x, local.y, local.z, true));
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
        var railways = com.simibubi.create.CreateClient.RAILWAYS;
        if (railways == null)
            return null;
        CarriageContraptionEntity found = null;
        double best = Double.MAX_VALUE;
        for (Train t : railways.trains.values()) {
            for (var car : t.carriages) {
                var e = car.anyAvailableEntity();
                if (e == null)
                    continue;
                if (!RiderSeatGate.isOnContraption(player, e))
                    continue;
                double d = e.distanceToSqr(player);
                if (d < best) {
                    best = d;
                    found = e;
                }
            }
        }
        return found;
    }

    private static int nearestFreeSeat(CarriageContraptionEntity carriage, Contraption contraption, Vec3 world) {
        java.util.Set<Integer> taken = new java.util.HashSet<>(contraption.getSeatMapping().values());
        int best = -1;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < contraption.getSeats().size(); i++) {
            if (taken.contains(i))
                continue;
            Vec3 seat = RiderSeatGate.seatWorld(carriage, i);
            if (seat == null)
                continue;
            double d = seat.distanceToSqr(world);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }
}
