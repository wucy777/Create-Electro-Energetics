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

        // The SEAT's carriage is the client's call, and it travels in the packet: the client is the side
        // that sees which seat is nearest, and that seat may be on a neighbouring carriage. Overwriting it
        // with the server's own standing-carriage guess put the client's seat index into the wrong
        // carriage's seat table - the reported "nearest seat" that never was. Only range and reachability
        // are server-checked here, so a packet cannot name a carriage across the world.
        if (carriageIndex < 0 || carriageIndex >= train.carriages.size()) {
            SeatIntentStore.clear(player.getUUID());
            return;
        }
        CarriageContraptionEntity seatEntity = train.carriages.get(carriageIndex).anyAvailableEntity();
        if (seatEntity == null || seatEntity.distanceToSqr(player) > 16d * 16d) {
            SeatIntentStore.clear(player.getUUID());
            return;
        }
        Contraption seatContraption = seatEntity.getContraption();
        if (seatContraption == null || seatIndex < 0 || seatIndex >= seatContraption.getSeats().size()) {
            SeatIntentStore.clear(player.getUUID());
            return;
        }

        // Store ONLY. Seating happens in RiderSeatGate.trySeatAll, the single decision point - a second
        // seat-er here raced the gate and assigned seats the gate's own rules never chose.
        SeatIntentStore.put(player.getUUID(), new SeatIntentStore.Intent(
                train.id, carriageIndex, seatIndex, localX, localY, localZ));

        org.slf4j.LoggerFactory.getLogger("electroenergetics/seatintent").info(
                "SEATINTENT recv seatCarriage={} seat={} standingCarriage={} local={},{},{}",
                carriageIndex, seatIndex, actual,
                String.format("%.2f", localX), String.format("%.2f", localY), String.format("%.2f", localZ));

    }

    @Override
    public PacketTypeProvider getTypeProvider() {
        return CEEPackets.REPORT_SEAT_INTENT;
    }
}
