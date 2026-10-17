package com.george_vi.electroenergetics.content.railway_electrification.pantograph;

import com.george_vi.electroenergetics.CEEPackets;
import com.george_vi.electroenergetics.content.railway_electrification.TrainDriverState;
import com.george_vi.electroenergetics.mixin_interfaces.ICEETrainExtension;
import com.george_vi.electroenergetics.mixin_interfaces.IPantographList;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.Train;
import io.netty.buffer.ByteBuf;
import net.createmod.catnip.net.base.ServerboundPacketPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Driver to server: raise or lower one pantograph.
 *
 * <h2>The rule, and why it is enforced here</h2>
 *
 * <p>Raising is only permitted when the chosen pantograph is within reach of catenary; lowering is
 * always permitted. That asymmetry is the operator's design and it is physically right: a
 * pantograph can be dropped at any moment, but pressing one up against nothing is not something a
 * real one can do, and in game it would silently do nothing while appearing to be raised.
 *
 * <p>The check runs HERE, on the server, against the server's own geometry - not on the client
 * that asked. A client-side test would be both unenforceable and frequently wrong, since the
 * client's carriage data does not carry the pantograph list at all (client carriages are built
 * from {@code AddTrainPacket} and never go through {@code Carriage.read}). The server recomputes
 * {@code reachable} every solve in {@code CatenaryModule}, so it is the authority on it.
 *
 * <p>A refused raise is reported back to the driver rather than silently ignored, so the panel can
 * say why nothing happened.
 *
 * @param trainId the train the driver is at the controls of
 * @param carriageIndex index into {@code train.carriages}
 * @param localPos the pantograph's position in its carriage's local space, which is what
 *                 {@code IPantographList} keys on - not a list index, so it stays correct if the
 *                 contraption is reassembled
 * @param raise true to raise, false to lower
 */
public record SetPantographPacket(UUID trainId, int carriageIndex, BlockPos localPos, boolean raise)
        implements ServerboundPacketPayload {

    public static final StreamCodec<ByteBuf, SetPantographPacket> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, SetPantographPacket::trainId,
            ByteBufCodecs.VAR_INT, SetPantographPacket::carriageIndex,
            BlockPos.STREAM_CODEC, SetPantographPacket::localPos,
            ByteBufCodecs.BOOL, SetPantographPacket::raise,
            SetPantographPacket::new
    );

    @Override
    public void handle(ServerPlayer player) {
        Train train = Create.RAILWAYS.trains.get(trainId);
        if (train == null || train.carriages.isEmpty())
            return;
        if (carriageIndex < 0 || carriageIndex >= train.carriages.size())
            return;

        // Only the person actually at the controls may work the pantographs, checked against
        // Create's own record of who is holding them - the same authority the lever packet uses,
        // and not anything the client claims.
        //
        // Deliberately NOT gated on the train being under this mod's control: the whole point of
        // allowing this from both control schemes is that a driver who has been handed to Create
        // must still be able to raise a pantograph and get the electric controls back. Gating it
        // on the mode would strand them in Create with no way out.
        if (!((ICEETrainExtension) train).getElectricTrainData().driver.isDriver(player.getUUID()))
            return;

        Carriage carriage = train.carriages.get(carriageIndex);
        if (!(carriage instanceof IPantographList list))
            return;

        TrainPantographEntry entry = list.getPantographState(localPos);
        if (entry == null)
            return;

        if (!raise) {
            // Always allowed. A driver must be able to drop a pantograph at any moment, and
            // refusing to would be the one failure with no workaround.
            entry.active = false;
            return;
        }

        // Barred until the next station if the emergency brake has been used. Checked before the
        // reach test, because this is a rule about the driver rather than about the world: the wire
        // may well be right there, and the answer is still no.
        //
        // This is what makes "the emergency brake must stop the train whether or not there is
        // power" enforceable. Without it a driver whose supply failed mid-stop could simply raise a
        // pantograph and finish the stop on the motors, which is exactly what the rule forbids.
        //
        // BOTH flags, spelled exactly as the gear lock in SetTrainGearPacket spells them. Today the
        // two conditions happen to agree, because the flags only ever hold (false,false),
        // (false,true) or (true,true): arming sets both, and release clears emergencyArmed first. But
        // that is a property of the current release order, not something this check states - so
        // testing one flag here leaves a trap. Change the order and the two locks diverge silently,
        // with the gear refusing a move that a pantograph command still allows. Spelling both makes
        // the pair self-evidently identical rather than accidentally identical.
        TrainDriverState driver = ((ICEETrainExtension) train).getElectricTrainData().driver;
        if (driver.emergencyArmed || driver.controlsLocked) {
            refusal(player, "electroenergetics.train.panto.locked");
            return;
        }

        // Raising requires the wire to be in reach. `reachable` is recomputed from geometry every
        // solve on this side, so it cannot be spoofed and cannot be stale by more than one tick.
        //
        // Refusing is deliberate rather than a no-op: the alternative - raising against nothing -
        // would show the driver a raised pantograph collecting no current, which is exactly the
        // confusing state this rule exists to prevent.
        if (!entry.reachable) {
            refusal(player, "electroenergetics.train.panto.noWire");
            return;
        }

        entry.active = true;
    }

    /**
     * Tell the driver why a raise did nothing.
     *
     * <p>Necessary because the refusal would otherwise be completely silent. Once a train has been
     * handed to Create the electric panel is deliberately not drawn - Create's controls are in
     * charge and this mod's panel is not part of them - so a refused raise would leave the driver
     * pressing a key with no panel, no feedback, and no reason to think it was anything but broken.
     *
     * <p>An action-bar line rather than a chat message: this is transient feedback about a keypress,
     * not a record. Vanilla's own mechanism, so no new packet is needed and nothing is added to the
     * handover.
     */
    private static void refusal(ServerPlayer player, String langKey) {
        player.displayClientMessage(
                net.minecraft.network.chat.Component.translatable(langKey), true);
    }

    @Override
    public PacketTypeProvider getTypeProvider() {
        return CEEPackets.SET_PANTOGRAPH;
    }
}
