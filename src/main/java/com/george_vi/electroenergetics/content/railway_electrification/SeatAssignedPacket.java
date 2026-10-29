package com.george_vi.electroenergetics.content.railway_electrification;

import com.george_vi.electroenergetics.CEEPackets;
import com.george_vi.electroenergetics.client.SeatIntentTracker;
import io.netty.buffer.ByteBuf;
import net.createmod.catnip.net.base.ClientboundPacketPayload;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Server to client: this is the seat you actually got.
 *
 * <p>Sent after the server seats a player, whether it used the seat the client asked for or had to choose
 * another because that one had been taken. The client's prediction is therefore only a prediction - the
 * screen is told the truth rather than being left to assume its request succeeded.
 */
public record SeatAssignedPacket(int carriageIndex, int seatIndex) implements ClientboundPacketPayload {

    public static final StreamCodec<ByteBuf, SeatAssignedPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SeatAssignedPacket::carriageIndex,
            ByteBufCodecs.VAR_INT, SeatAssignedPacket::seatIndex,
            SeatAssignedPacket::new
    );

    @Override
    @OnlyIn(Dist.CLIENT)
    public void handle(LocalPlayer player) {
        SeatIntentTracker.acceptAssignment(carriageIndex, seatIndex);
    }

    @Override
    public PacketTypeProvider getTypeProvider() {
        return CEEPackets.SEAT_ASSIGNED;
    }
}
