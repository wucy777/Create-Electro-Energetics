package com.george_vi.electroenergetics.content.railway_electrification;

import com.george_vi.electroenergetics.CEEPackets;
import com.george_vi.electroenergetics.client.SeatIntentTracker;
import io.netty.buffer.ByteBuf;
import net.createmod.catnip.net.base.ClientboundPacketPayload;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.codec.StreamCodec;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Server to client: I am about to seat you and I have no report from you - send one.
 *
 * <p>Only needed when the server wants to seat a player who has never reported, which in practice means
 * somebody who has just joined or just boarded. Its cost is one empty packet in that case and nothing at any
 * other time.
 */
public record RequestSeatIntentPacket() implements ClientboundPacketPayload {

    public static final StreamCodec<ByteBuf, RequestSeatIntentPacket> STREAM_CODEC =
            StreamCodec.unit(new RequestSeatIntentPacket());

    @Override
    @OnlyIn(Dist.CLIENT)
    public void handle(LocalPlayer player) {
        SeatIntentTracker.requestReport();
    }

    @Override
    public PacketTypeProvider getTypeProvider() {
        return CEEPackets.REQUEST_SEAT_INTENT;
    }
}
