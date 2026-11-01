package com.george_vi.electroenergetics.content.railway_electrification;

import com.george_vi.electroenergetics.CEEPackets;
import io.netty.buffer.ByteBuf;
import net.createmod.catnip.net.base.ClientboundPacketPayload;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.UUID;

/**
 * Server to client: who owns this train's controls right now, or nobody.
 *
 * <h2>Why this cannot be derived on the client</h2>
 *
 * <p>Create does sync a "controlling player" on each carriage contraption entity, and on
 * a server that only runs this mod the two would agree. But a client is not allowed to
 * infer a permission from it: the client's own right-click prediction can write that
 * value locally before the server has answered, which is exactly the state a denied
 * viewer is left in. The mod's claim is the authority, so the client is told the
 * authority's answer directly and never guesses.
 *
 * <h2>Traffic</h2>
 *
 * <p>Sent only when the claim CHANGES - a right-click that takes or releases the
 * controls, or an automatic release - to players within {@link DriverAuthority#SYNC_RANGE}
 * blocks of the train. That is a handful of ~20-byte packets per journey leg, not a
 * periodic sync. It deliberately does not ride the gauge broadcast: that runs every five
 * ticks and carries sixteen more bytes of UUID, which would be a permanent cost for a
 * value that almost never changes.
 *
 * @param trainId  the train the claim belongs to
 * @param driverId the player who owns the controls, or {@code null} when the train is free
 */
public record DriverClaimPacket(UUID trainId, UUID driverId) implements ClientboundPacketPayload {

    public static final StreamCodec<ByteBuf, DriverClaimPacket> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                UUIDUtil.STREAM_CODEC.encode(buf, p.trainId());
                // Present-flag rather than a sentinel UUID: "nobody drives this train" is an
                // ordinary state of the claim, not an error, and a sentinel would be one
                // more thing every reader has to remember to test.
                ByteBufCodecs.BOOL.encode(buf, p.driverId() != null);
                if (p.driverId() != null)
                    UUIDUtil.STREAM_CODEC.encode(buf, p.driverId());
            },
            buf -> new DriverClaimPacket(
                    UUIDUtil.STREAM_CODEC.decode(buf),
                    ByteBufCodecs.BOOL.decode(buf) ? UUIDUtil.STREAM_CODEC.decode(buf) : null));

    @Override
    @OnlyIn(Dist.CLIENT)
    public void handle(LocalPlayer player) {
        TrainHudData.updateDriverClaim(trainId, driverId);
    }

    @Override
    public PacketTypeProvider getTypeProvider() {
        return CEEPackets.SYNC_DRIVER_CLAIM;
    }
}
