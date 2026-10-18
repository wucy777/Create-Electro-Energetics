package com.george_vi.electroenergetics.content.railway_electrification.pantograph;

import com.george_vi.electroenergetics.CEEPackets;
import com.george_vi.electroenergetics.content.railway_electrification.ElectricTrainData;
import com.george_vi.electroenergetics.mixin_interfaces.ICEETrainExtension;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.Train;
import io.netty.buffer.ByteBuf;
import net.createmod.catnip.net.base.ServerboundPacketPayload;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Driver to server: turn the automatic-raise switch on or off.
 *
 * <h2>What the switch does</h2>
 *
 * <p>On, it restores upstream's behaviour: a pantograph with catenary in reach is raised, with no
 * further test. Off - the default - a pantograph only goes up because the driver's packet put it
 * there.
 *
 * <h2>Why it is a switch and not a key</h2>
 *
 * <p>The case that prompted it is a rigid carriage on a curve. The arm is fixed in the carriage's
 * frame while the catenary follows the arc, so on a tight enough bend the contact leaves reach
 * sideways; the automatic lowering that is deliberately kept then drops the arm, and by the time the
 * track straightens the train has already lost speed. Pressing raise at the right moment on every
 * bend is not a driving skill, it is a chore, and the operator's own track is a compact loop where it
 * would happen constantly. So it is a mode, held on, not a key pressed repeatedly.
 *
 * <p>Automatic lowering still applies while the switch is on, which is what makes the pair work:
 * "raise whenever there is wire, drop it when there is none" is exactly upstream's behaviour, and
 * that is what the switch restores.
 *
 * <p>Deliberately NOT blocked by the emergency lockout, and this is a considered exception. The
 * lockout exists so an emergency stop cannot be finished on the motors; it is enforced on the
 * individual raise command, which is what could do that. This switch does not raise anything by
 * itself - it sets a policy, and the policy only takes effect through geometry that is re-evaluated
 * every solve. But the panel is inert during a lockout, so the key is unreachable anyway; the check
 * here is therefore refused rather than allowed, to keep the panel's rule "everything is inert"
 * literally true rather than approximately true.
 *
 * @param trainId the train the driver is at the controls of
 * @param autoRaise the switch's new position
 */
public record SetAutoRaisePacket(UUID trainId, boolean autoRaise) implements ServerboundPacketPayload {

    public static final StreamCodec<ByteBuf, SetAutoRaisePacket> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, SetAutoRaisePacket::trainId,
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
        // turn the switch on, because that is how they get the electric controls back.
        if (!data.driver.isDriver(player.getUUID()))
            return;

        // The whole panel is inert during an emergency lockout - see the class comment for why this
        // is refused even though it could not raise anything on its own. The client also blocks the
        // key, so this is the authoritative half of the same rule.
        if (data.driver.emergencyArmed || data.driver.controlsLocked) {
            player.displayClientMessage(
                    net.minecraft.network.chat.Component.translatable(
                            "electroenergetics.train.panto.locked"), true);
            return;
        }

        data.autoRaisePantographs = autoRaise;

        // Turning the switch OFF must lower the arms it raised, or the driver turns it off and nothing
        // observable happens until the next bend - the arms would simply stay wherever the automatic
        // raising last put them, still collecting current, with the panel claiming manual mode. That
        // is the one state this whole feature exists to prevent: a pantograph up that the driver did
        // not raise.
        //
        // Turning it ON does not raise anything here. Raising is geometry's job (the site in
        // CatenaryModule where `reachable` is set), and doing it from the packet would raise an arm
        // with no check on whether there is wire - precisely the failure the manual path refuses.
        if (!autoRaise) {
            for (var carriage : train.carriages) {
                if (!(carriage instanceof com.george_vi.electroenergetics.mixin_interfaces
                        .IPantographList list))
                    continue;
                for (TrainPantographEntry entry : list.electroEnergetics$getPantographList())
                    entry.active = false;
            }
        }
    }

    @Override
    public PacketTypeProvider getTypeProvider() {
        return CEEPackets.SET_AUTO_RAISE;
    }
}
