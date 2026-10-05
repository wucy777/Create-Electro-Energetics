package com.george_vi.electroenergetics.content.railway_electrification;

import com.george_vi.electroenergetics.CEEPackets;
import com.george_vi.electroenergetics.mixin_interfaces.ICEETrainExtension;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.Train;
import io.netty.buffer.ByteBuf;
import net.createmod.catnip.net.base.ServerboundPacketPayload;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Driver to server: move the lever, use the emergency brake, or confirm.
 *
 * <p>Sent only when the driver actually does something, plus a slow keep-alive
 * while a prompt is up. Nothing here is periodic while the train is running
 * normally, so an idle train on a driver's screen costs no traffic.
 *
 * @param trainId the train the driver is at the controls of
 * @param gearOrdinal the lever position, or -1 for "not changing the lever"
 * @param confirm     the driver pressed the vigilance button
 * @param emergency   the driver pressed the emergency brake
 */
public record SetTrainGearPacket(UUID trainId, int gearOrdinal, boolean confirm, boolean emergency)
        implements ServerboundPacketPayload {

    /**
     * How long the emergency brake is applied for once fired. Long enough to
     * bring a train down from line speed at the emergency rate; the penalty then
     * outlives it until the next station.
     */
    public static final int EMERGENCY_APPLY_TICKS = 100;

    public static final StreamCodec<ByteBuf, SetTrainGearPacket> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, SetTrainGearPacket::trainId,
            ByteBufCodecs.VAR_INT, SetTrainGearPacket::gearOrdinal,
            ByteBufCodecs.BOOL, SetTrainGearPacket::confirm,
            ByteBufCodecs.BOOL, SetTrainGearPacket::emergency,
            SetTrainGearPacket::new
    );

    /** Lever move with no other action. */
    public static SetTrainGearPacket gear(UUID trainId, TrainGear gear) {
        return new SetTrainGearPacket(trainId, gear.ordinal(), false, false);
    }

    /** Vigilance acknowledgement. */
    public static SetTrainGearPacket confirm(UUID trainId) {
        return new SetTrainGearPacket(trainId, -1, true, false);
    }

    /** Emergency brake. */
    public static SetTrainGearPacket emergency(UUID trainId) {
        return new SetTrainGearPacket(trainId, -1, false, true);
    }

    @Override
    public void handle(ServerPlayer player) {
        Train train = Create.RAILWAYS.trains.get(trainId);
        if (train == null || train.carriages.isEmpty())
            return;

        // Only the person actually at the controls may command the train. This is
        // checked against Create's own record of who is holding them, which the
        // server ticks from the contraption entity - not against anything the client
        // claims, and not against proximity. Proximity was the earlier test and it
        // was too weak: standing next to a train is not driving it, and a passenger
        // riding it would have passed. This cannot be spoofed by a hand-made packet,
        // because driverId is written only from the world state.
        TrainDriverState state = ((ICEETrainExtension) train).getElectricTrainData().driver;
        if (!state.isDriver(player.getUUID()))
            return;

        if (emergency) {
            // Only meaningful in reverse, which is where the panel offers it. The
            // main brake already gives the ordinary stop; this is the extra one
            // that costs the driver a speed cap for the rest of the leg.
            if (state.gear == TrainGear.REVERSE) {
                state.emergencyArmed = true;
                state.emergencyTicks = EMERGENCY_APPLY_TICKS;
                state.emergencyPenalty = true;
            }
        }

        if (confirm) {
            // Acknowledge: reset the vigilance clock to zero. Pressing early therefore
            // resets the full 30 s, not just the warning - there is only one clock.
            state.confirmWaiting = 0;
            // Acknowledging also releases a trip, so the driver can carry on rather than
            // being stuck with a braked train. Recovering from a trip is the documented
            // behaviour: the device stops the train, it does not end the journey.
            if (state.vigilanceTripped && Math.abs(train.speed) < 1e-3d) {
                state.clearTrip();
                state.tripTicks = 0;
            }
        }

        if (gearOrdinal >= 0 && gearOrdinal < TrainGear.values().length) {
            TrainGear gear = TrainGear.values()[gearOrdinal];
            // Reverse while already moving arms the emergency brake instead of
            // commanding reverse traction: a real handle cannot be thrown against
            // the direction of travel, and "stop hard" is what the driver means.
            if (gear == TrainGear.REVERSE && Math.abs(train.speed) > TrainTractionModel.CRUISE_MIN_SPEED) {
                state.gear = gear;
                state.emergencyArmed = true;
            } else {
                state.gear = gear;
            }
            // Cruise captures the speed it is to hold at the moment it is engaged.
            if (gear == TrainGear.CRUISE)
                state.cruiseSpeed = Math.abs(train.speed) * 20d;
        }
    }

    @Override
    public PacketTypeProvider getTypeProvider() {
        return CEEPackets.SET_TRAIN_GEAR;
    }
}
