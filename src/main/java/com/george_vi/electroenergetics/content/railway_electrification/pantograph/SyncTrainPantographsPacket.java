package com.george_vi.electroenergetics.content.railway_electrification.pantograph;

import com.george_vi.electroenergetics.CEEPackets;
import com.george_vi.electroenergetics.content.railway_electrification.TrainHudData;
import io.netty.buffer.ByteBuf;
import net.createmod.catnip.net.base.ClientboundPacketPayload;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Server to client sync of a train's pantographs, so the cab can list them and work them.
 *
 * <h2>Why the client cannot work this out itself</h2>
 *
 * <p>A client-side carriage has an EMPTY pantograph list. Carriages are populated from
 * {@code Carriage.read} / {@code setContraption}, which only ever run on the server; the client
 * receives its carriages through {@code AddTrainPacket}, which does not carry them. So without
 * this packet there is nothing on the client to name or operate - the driver would have no way to
 * say which pantograph they meant.
 *
 * <h2>Why it is addressed to drivers rather than broadcast</h2>
 *
 * <p>The data includes {@code reachable}, which is a property of where the train is standing and
 * therefore changes as it moves - so it cannot be synced only on change without either going stale
 * or being sent every tick. The two costs are avoided by sending it only to the player at the
 * controls, throttled, and by keeping it out of the per-train gauge broadcast that every player
 * near the train receives. A driver is one player, so the total traffic is a small packet a few
 * times a second for the only person who can act on it.
 *
 * <p>No keep-alive is needed while nobody is driving: nothing is sent at all, and the client's copy
 * is rebuilt the moment a driver takes the controls.
 *
 * <h2>Fields per pantograph</h2>
 *
 * <ul>
 *   <li>{@code carriageIndex} and {@code localPos} together identify it. The position is what
 *       {@code IPantographList} keys on, not a list index, so it survives a reassembly.</li>
 *   <li>{@code active} is the driver's handle - whether they have it raised.</li>
 *   <li>{@code reachable} is whether catenary is within reach, which is what decides whether
 *       raising is allowed. It is only ever an affordance here; the server checks it again in
 *       {@link SetPantographPacket} before acting, so a stale copy cannot authorise anything.</li>
 * </ul>
 *
 * @param trainId the train these belong to
 * @param locked whether raising is barred until the next station, because the emergency brake has
 *               been used. Sent separately from each entry's state because it is a rule about the
 *               driver rather than about any one pantograph, and the panel has to be able to say
 *               WHY a raise did nothing - otherwise the key appears broken.
 * @param autoRaise the driver's automatic-raise switch. Sent because the panel has to show whether it
 *                  is on: with it on, a manual lowering does not stick, and a driver who saw the
 *                  switch state nowhere would read that as the key being broken.
 * @param entries one per pantograph, in carriage order
 */
public record SyncTrainPantographsPacket(UUID trainId, boolean locked, boolean autoRaise,
                                         List<Entry> entries)
        implements ClientboundPacketPayload {

    /**
     * One pantograph's identity and state.
     *
     * @param carriageIndex index into {@code train.carriages}
     * @param localPos the pantograph's position in its carriage's local space
     * @param active whether the driver has it raised
     * @param reachable whether catenary is within reach of it right now
     * @param railShoe whether this is a rail contact shoe rather than an overhead pantograph
     * @param autoRaise whether this arm is on automatic raise/lower, which disables its manual key
     */
    public record Entry(int carriageIndex, BlockPos localPos, boolean active, boolean reachable,
                        boolean railShoe, boolean autoRaise) {}

    /**
     * Written by hand rather than composed: the payload is a variable-length list of records, which
     * {@code StreamCodec.composite} cannot express.
     */
    public static final net.minecraft.network.codec.StreamCodec<ByteBuf, SyncTrainPantographsPacket>
            STREAM_CODEC = new net.minecraft.network.codec.StreamCodec<>() {
        @Override
        public SyncTrainPantographsPacket decode(ByteBuf buffer) {
            UUID trainId = UUIDUtil.STREAM_CODEC.decode(buffer);
            boolean locked = buffer.readBoolean();
            // Read in the same order it is written. Placed next to `locked` rather than folded into
            // the per-entry flags, because it is a property of the TRAIN (one switch for the whole
            // consist) exactly as `locked` is - putting a train-level flag inside the per-pantograph
            // byte would mean repeating it on every entry and inviting the copies to disagree.
            boolean autoRaise = buffer.readBoolean();
            int count = buffer.readUnsignedByte();
            List<Entry> entries = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int carriageIndex = buffer.readUnsignedByte();
                BlockPos localPos = BlockPos.STREAM_CODEC.decode(buffer);
                int flags = buffer.readUnsignedByte();
                entries.add(new Entry(carriageIndex, localPos, (flags & 1) != 0,
                        (flags & 2) != 0, (flags & 4) != 0, (flags & 8) != 0));
            }
            return new SyncTrainPantographsPacket(trainId, locked, autoRaise, entries);
        }

        @Override
        public void encode(ByteBuf buffer, SyncTrainPantographsPacket p) {
            UUIDUtil.STREAM_CODEC.encode(buffer, p.trainId);
            buffer.writeBoolean(p.locked);
            buffer.writeBoolean(p.autoRaise);
            // A consist with more than 255 pantographs does not exist; clamped rather than
            // growing the field, since the count is what every other field's size depends on.
            buffer.writeByte(Math.min(255, p.entries.size()));
            for (Entry e : p.entries) {
                buffer.writeByte(e.carriageIndex & 0xFF);
                BlockPos.STREAM_CODEC.encode(buffer, e.localPos);
                buffer.writeByte((e.active ? 1 : 0) | (e.reachable ? 2 : 0) | (e.railShoe ? 4 : 0)
                        | (e.autoRaise ? 8 : 0));
            }
        }
    };

    @Override
    @OnlyIn(Dist.CLIENT)
    public void handle(LocalPlayer player) {
        TrainHudData.updatePantographs(trainId, locked, autoRaise, entries);
    }

    @Override
    public PacketTypeProvider getTypeProvider() {
        return CEEPackets.SYNC_TRAIN_PANTOGRAPHS;
    }
}
