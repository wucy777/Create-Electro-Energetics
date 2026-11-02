package com.george_vi.electroenergetics.content.railway_electrification.pantograph;

import com.george_vi.electroenergetics.CEEPackets;
import com.george_vi.electroenergetics.content.railway_electrification.TrainDriverState;
import com.george_vi.electroenergetics.content.railway_electrification.TrainGear;
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
 * Driver to server: a manual raise/lower request for one pantograph.
 *
 * <h2>The per-arm switch is an AUTOMATIC-WORKING switch</h2>
 *
 * <p>The switch does not select "automatic versus manual" any more. It selects whether the arm works
 * at all:
 *
 * <ul>
 *   <li>ON (default) - the arm follows the geometry: CatenaryModule raises it when catenary is in
 *       reach and lowers it when the wire is lost. This is the original mod's behaviour.</li>
 *   <li>OFF - the arm is parked. CatenaryModule forces it down every solve and drops its wire cut,
 *       because "auto off" means "I do not want this arm working".</li>
 * </ul>
 *
 * <p>Consequently a manual RAISE is refused for every arm: an automatic arm raises itself, and a
 * switched-off arm is meant to stay down. A manual LOWER is still accepted for a parked arm because
 * it is a harmless no-op (the arm is down already); for an automatic arm it is refused too, since the
 * next solve would put the arm straight back up and the key would appear broken.
 *
 * <p>The refusals are reported back to the driver rather than silently ignored, so the panel can say
 * why nothing happened. (The wording of that message, and which state the panel shows, belongs to the
 * panel side; this class only decides what the server accepts.)
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

        // THE SWITCH IS NOW AN AUTOMATIC-WORKING SWITCH, NOT A MANUAL ONE.
        //
        // Switch ON means the arm follows the geometry (CatenaryModule raises it when the wire is in
        // reach and lowers it when the wire is lost). Accepting a manual command here would be undone
        // by the very next solve, so refusing is the honest answer: the key would appear to work for
        // one tick and then snap back, which reads as a broken control.
        if (entry.autoRaise) {
            refusal(player, "electroenergetics.train.panto.autoLocked");
            return;
        }

        if (!raise) {
            // Lowering a parked arm. The CatenaryModule forces these arms down every solve, so this is
            // a no-op in practice - accepted anyway, because it has no side effect and a hard "no" to
            // a key that already matches reality would be noise.
            //
            // The lever is dropped to COAST first (only if this was the last live arm), and the order
            // matters. Cutting the supply while the gear still commands traction leaves the motors
            // connected to a dead line, and an unexcited motor being turned by a moving train is a
            // generator with a short across it: it brakes, hard enough to be felt through the train. A
            // road vehicle has the same problem and the same answer - declutch before killing the
            // engine - so the lever goes to neutral and only then does the pantograph come down.
            //
            // It is also the only order that is legal. COAST and BRAKE are the only positions selectable
            // without a supply (ElectricTrainData.gearAvailable), so lowering first would leave the lever
            // in a position the panel itself refuses to offer.
            TrainDriverState driver = ((ICEETrainExtension) train).getElectricTrainData().driver;
            entry.active = false;

            // Only declutch if this was the LAST live arm. On a twin-arm consist, dropping one while the
            // other still collects leaves the train powered, and cutting traction there is a step the
            // driver never asked for. The declutch exists to keep the motors off a dead line - with an arm
            // still live, the line is not dead.
            if (!electroEnergetics$otherArmLive(train, entry) && driver.gear.appliesTraction())
                driver.gear = TrainGear.COAST;

            return;
        }

        // RAISING IS REFUSED FOR EVERY ARM. That is the switch semantics, not an oversight:
        //
        //   automatic arm (switch on)   raises itself when the geometry says there is wire. A manual
        //                               raise would at best duplicate what the next solve does, and at
        //                               worst fight the geometry that already refused to raise it.
        //   parked arm    (switch off)  the driver said "I do not want this arm working", and
        //                               CatenaryModule forces it down on every solve. Accepting a raise
        //                               here would be undone immediately, so refusing is the only
        //                               answer that keeps the server state and the switch consistent.
        //
        // Reported rather than silently ignored, so the driver knows the key was understood and the
        // answer is no. The wording of the prompt is the panel's business; this class owns the rule.
        refusal(player, "electroenergetics.train.panto.autoLocked");
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

    /** Whether any arm other than this one is still raised, on any carriage of the train. */
    private static boolean electroEnergetics$otherArmLive(Train train, TrainPantographEntry except) {
        for (var carriage : train.carriages) {
            if (!(carriage instanceof IPantographList list))
                continue;
            for (TrainPantographEntry other : list.electroEnergetics$getPantographList()) {
                if (other != except && other.active)
                    return true;
            }
        }
        return false;
    }

    @Override
    public PacketTypeProvider getTypeProvider() {
        return CEEPackets.SET_PANTOGRAPH;
    }
}
