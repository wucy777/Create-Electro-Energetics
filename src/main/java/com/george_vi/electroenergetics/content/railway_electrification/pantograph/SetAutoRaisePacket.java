package com.george_vi.electroenergetics.content.railway_electrification.pantograph;

import com.george_vi.electroenergetics.CEEPackets;
import com.george_vi.electroenergetics.content.railway_electrification.ElectricTrainData;
import com.george_vi.electroenergetics.mixin_interfaces.ICEETrainExtension;
import com.george_vi.electroenergetics.mixin_interfaces.IPantographList;
import com.simibubi.create.Create;
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
 * Turns one arm's automatic raise/lower on or off.
 *
 * <p>Per arm rather than per train. A consist on a winding line may want the leading arm to look after
 * itself and the trailing one left alone, and a train-wide switch cannot express that. It also makes the
 * rule "auto on disables that arm's manual key" expressible at all - with one switch for the whole train,
 * turning it off to regain manual control of one arm would have freed every arm.
 */
public record SetAutoRaisePacket(UUID trainId, int carriageIndex, BlockPos localPos, boolean autoRaise)
        implements ServerboundPacketPayload {

    public static final StreamCodec<ByteBuf, SetAutoRaisePacket> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, SetAutoRaisePacket::trainId,
            ByteBufCodecs.VAR_INT, SetAutoRaisePacket::carriageIndex,
            BlockPos.STREAM_CODEC, SetAutoRaisePacket::localPos,
            ByteBufCodecs.BOOL, SetAutoRaisePacket::autoRaise,
            SetAutoRaisePacket::new
    );

    @Override
    public void handle(ServerPlayer player) {
        Train train = Create.RAILWAYS.trains.get(trainId);
        if (train == null)
            return;

        ElectricTrainData data = ((ICEETrainExtension) train).getElectricTrainData();

        // Only the person at the controls. Checked against Create's own record, like every other cab
        // packet, and not gated on the control mode: a driver handed to Create must still be able to
        // turn it on, because that is how they get the electric controls back.
        if (!data.driver.isDriver(player.getUUID()))
            return;

        // The whole panel is inert during an emergency lockout - see the class comment for why this
        // is refused even though it could not raise anything on its own. The client also blocks the
        // key, so this is the authoritative half of the same rule.
        // Same three flags as the manual raise check: see the note there for why the penalty is the one
        // that matters.
        if (data.driver.emergencyArmed || data.driver.controlsLocked || data.driver.emergencyPenalty) {
            player.displayClientMessage(
                    net.minecraft.network.chat.Component.translatable(
                            "electroenergetics.train.panto.locked"), true);
            return;
        }

        if (carriageIndex < 0 || carriageIndex >= train.carriages.size())
            return;
        if (!(train.carriages.get(carriageIndex) instanceof IPantographList list))
            return;
        TrainPantographEntry entry = list.getPantographState(localPos);
        if (entry == null)
            return;

        entry.autoRaise = autoRaise;

        // Turning the switch OFF must lower the arms it raised, or the driver turns it off and nothing
        // observable happens until the next bend - the arms would simply stay wherever the automatic
        // raising last put them, still collecting current, with the panel claiming manual mode. That
        // is the one state this whole feature exists to prevent: a pantograph up that the driver did
        // not raise.
        //
        // Turning it ON does not raise anything here. Raising is geometry's job (the site in
        // CatenaryModule where `reachable` is set), and doing it from the packet would raise an arm
        // with no check on whether there is wire - precisely the failure the manual path refuses.
        // Turning it off lowers the arm it was looking after, and only that one: the others were never
        // this switch's business.
        if (!autoRaise)
            entry.active = false;
    }

    @Override
    public PacketTypeProvider getTypeProvider() {
        return CEEPackets.SET_AUTO_RAISE;
    }
}
