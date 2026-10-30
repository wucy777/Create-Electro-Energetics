package com.george_vi.electroenergetics.content.railway_electrification;

import com.george_vi.electroenergetics.CEEPackets;
import com.simibubi.create.Create;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import io.netty.buffer.ByteBuf;
import net.createmod.catnip.net.base.ServerboundPacketPayload;
import net.createmod.catnip.platform.CatnipServices;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * Client to server: where this player is standing, and which seat its own client thinks is nearest.
 *
 * <h2>Why the client computes it</h2>
 *
 * <p>The client is the side that draws the player, so its idea of where the player is standing is the one the
 * player can see. The server's copy of a passenger's position on a moving contraption is written from movement
 * packets and lags the carriage, which is how a player standing on the driver's seat was repeatedly seated in
 * a passenger seat several Blocks behind it.
 *
 * <h2>What is trusted</h2>
 *
 * <p>The train id and nothing else. The carriage is derived from the player's own vehicle, the seat index is
 * validated against that carriage's seat list and mapping, and the seating call only happens if the player is
 * genuinely unseated and aboard. A hand-made packet cannot seat a player on a train they are not riding, into
 * a seat that does not exist, or into an occupied one.
 *
 * <h2>Traffic</h2>
 *
 * <p>Edge triggered by the client: one packet when the nearest seat changes, none while it stays the same, and
 * none at all while the player is seated or the train is below the ceiling.
 */
public record ReportSeatIntentPacket(UUID trainId, int carriageIndex, int seatIndex,
                                     double localX, double localY, double localZ, boolean standing)
        implements ServerboundPacketPayload {

    /**
     * Hand-written rather than {@code StreamCodec.composite}: seven components is past the largest overload
     * that exists, so the builder form does not compile. The field order here is the wire format, and it must
     * stay in step with the record.
     */
    public static final StreamCodec<ByteBuf, ReportSeatIntentPacket> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                UUIDUtil.STREAM_CODEC.encode(buf, p.trainId());
                ByteBufCodecs.VAR_INT.encode(buf, p.carriageIndex());
                ByteBufCodecs.VAR_INT.encode(buf, p.seatIndex());
                ByteBufCodecs.DOUBLE.encode(buf, p.localX());
                ByteBufCodecs.DOUBLE.encode(buf, p.localY());
                ByteBufCodecs.DOUBLE.encode(buf, p.localZ());
                ByteBufCodecs.BOOL.encode(buf, p.standing());
            },
            buf -> new ReportSeatIntentPacket(
                    UUIDUtil.STREAM_CODEC.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.DOUBLE.decode(buf),
                    ByteBufCodecs.DOUBLE.decode(buf),
                    ByteBufCodecs.DOUBLE.decode(buf),
                    ByteBufCodecs.BOOL.decode(buf)));

    @Override
    public void handle(ServerPlayer player) {
        Train train = Create.RAILWAYS.trains.get(trainId);
        if (train == null || train.carriages.isEmpty()) {
            SeatIntentStore.clear(player.getUUID());
            return;
        }

        // The carriage comes from the world, never from the packet. A player standing on the platform is not
        // riding anything and gets nothing.
        int actual = RiderSeatGate.carriageIndexOf(train, player);
        if (actual < 0) {
            // The server's copy of a standing player's position lags at speed, so the exact block test can
            // miss the carriage they are visibly on. The client said which one, and it is the authority on
            // where it is standing - accepted here only if that carriage really is within reach, so a
            // packet cannot place a player on a train across the world.
            if (carriageIndex >= 0 && carriageIndex < train.carriages.size()) {
                var claimed = train.carriages.get(carriageIndex).anyAvailableEntity();
                if (claimed != null && claimed.distanceToSqr(player) < 16d * 16d)
                    actual = carriageIndex;
            }
            if (actual < 0) {
                SeatIntentStore.clear(player.getUUID());
                return;
            }
        }
        if (!standing) {
            SeatIntentStore.clear(player.getUUID());
            return;
        }

        CarriageContraptionEntity entity = train.carriages.get(actual).anyAvailableEntity();
        if (entity == null)
            return;
        Contraption contraption = entity.getContraption();
        if (contraption == null)
            return;

        // Remember the report whichever way the seating goes: the server may need it later, when the train
        // reaches the ceiling and this player still has no seat.
        SeatIntentStore.put(player.getUUID(), new SeatIntentStore.Intent(
                train.id, actual, seatIndex, localX, localY, localZ));

        if (contraption.getSeatMapping().containsKey(player.getUUID()))
            return;                                     // already seated; nothing to do

        boolean legal = seatIndex >= 0
                && seatIndex < contraption.getSeats().size()
                && !contraption.getSeatMapping().containsValue(seatIndex);

        int target = legal ? seatIndex : RiderSeatGate.nearestFreeSeat(entity, new Vec3(localX, localY, localZ));
        if (target < 0)
            return;                                     // no seat anywhere: the capacity rule takes over

        org.slf4j.LoggerFactory.getLogger("electroenergetics/seatintent").info(
                "SEATINTENT recv reportedCarriage={} reportedSeat={} actualCarriage={} legal={} target={} "
                        + "local={},{},{} occupiedBefore={}",
                carriageIndex, seatIndex, actual, legal, target,
                String.format("%.2f", localX), String.format("%.2f", localY), String.format("%.2f", localZ),
                contraption.getSeatMapping().values());

        entity.addSittingPassenger(player, target);

        org.slf4j.LoggerFactory.getLogger("electroenergetics/seatintent").info(
                "SEATINTENT done playerSeatAfter={} passengers={}",
                contraption.getSeatMapping().get(player.getUUID()),
                entity.getPassengers().size());

        CatnipServices.NETWORK.sendToClient(player, new SeatAssignedPacket(actual, target));
    }

    @Override
    public PacketTypeProvider getTypeProvider() {
        return CEEPackets.REPORT_SEAT_INTENT;
    }
}
