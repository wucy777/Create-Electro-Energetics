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

        ElectricTrainData electricData = ((ICEETrainExtension) train).getElectricTrainData();

        if (emergency) {
            // The emergency brake is PULLED, not requested. It latches and runs until the train is
            // at a stand, and nothing releases it early - not a lever move and not a loss of supply.
            //
            // TRIGGERING it needs power, because the brake is a driven-motor one: it stops the train
            // by putting the motors into reverse against the direction of travel. That is also why
            // the driver has to be in REVERSE to fire it, and why REVERSE being unavailable without
            // supply already implies this.
            //
            // CONTINUING it does not need power, and that is the case that matters: if the supply
            // fails part-way through the stop, the train finishes it on friction alone. That happens
            // on its own because the motor share of the brake is scaled by the supply, so nothing
            // here has to handle it - and nothing here may CANCEL it either, which is why there is
            // no power test around the application itself.
            //
            // Only meaningful in reverse, which is where the panel offers it. The
            // main brake already gives the ordinary stop; this is the extra one
            // that costs the driver a speed cap for the rest of the leg.
            if (electricData.emergencyBrakeAvailable() && state.gear == TrainGear.REVERSE) {
                state.emergencyArmed = true;
                state.emergencyTicks = 0;   // the backstop counts up from the trigger
                state.emergencyPenalty = true;
                // A fresh application earns a fresh leg, so the "already reached a station"
                // latch restarts with it. Without this, a second pull would inherit the first
                // one's latch and the cap would clear the instant the driver next touched a
                // platform - the opposite of the rule that the cap lasts to the next station.
                state.penaltyStationReached = false;
                state.penaltyDistance = 0d;
                // No collecting current again until the next station. A driver whose supply fails
                // part-way through the stop therefore finishes on friction and STAYS without power,
                // which is the point: the emergency brake is not to be completed by the motors.
                state.controlsLocked = true;
                // Restart the lockout tail from zero, or a second application inherits whatever the
                // first one had already counted and the panel unlocks early.
                //
                // Concretely: the tail counts up while the panel is locked and the brake is over. Pull
                // the emergency brake again with 20 of the 40 ticks already counted and, without this
                // line, the second stop only gets 20 ticks of protection - half of what
                // LOCKOUT_TAIL_TICKS says. It never caused a lockout or a stuck panel, so it is a
                // fidelity bug rather than a safety one, but the constant's name and comment promise
                // 2 seconds after each stop and that is what it should deliver.
                state.lockoutTailTicks = 0;
            }
        }

        // LOCKOUT. Once the emergency brake is in force, every other control the driver has is
        // dead until the train has stopped.
        //
        // The operator's flow is: pull the handle, and nothing is accepted until the train stands
        // still, at which point the pantographs come down and control passes to Create. So a lever
        // move or an acknowledgement arriving during the stop is not a command to be stored for
        // later - it is refused outright, and the handle the driver had when they pulled the
        // emergency brake is the one that survives.
        //
        // Placed after the emergency trigger above so that PULLING it still works, and before the
        // confirm and gear blocks so neither can act once it is running. Without this a driver
        // could, mid-emergency, notch the lever somewhere and have that position be what the train
        // resumed in - which is exactly the "no input accepted" rule being broken.
        //
        // BOTH flags, because they no longer expire together. After the stop has finished,
        // GameEvents clears emergencyArmed immediately but controlsLocked stays set for
        // LOCKOUT_TAIL_TICKS (2 s). Testing only emergencyArmed therefore left a 40-tick hole where
        // the panel is still locked but this method accepted gear moves and acknowledgements.
        //
        // That hole is invisible to a normal client - TrainControlInput:204 refuses to send anything
        // while locked - so it only matters to a client that forms its own packets. The client-side
        // check is a convenience for well-behaved clients; the lockout itself belongs here, where the
        // server can enforce it regardless of what the client chose to send.
        //
        // Blocking acknowledge too is harmless: while the train stands, GameEvents already zeroes the
        // vigilance clock every tick, so there is nothing to acknowledge in that window, and the train
        // cannot move again until the lever is unlocked.
        if (state.emergencyArmed || state.controlsLocked)
            return;

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
            // A position that commands traction cannot be selected without a supply: it would
            // compute zero effort and leave the driver holding a handle that says "accelerate"
            // while the train coasts. The brake and the power-off position are always available -
            // the friction brake does not need the wire, which is exactly when it is most wanted.
            //
            // Checked here as well as on the client, because this is the authority: the client's
            // copy of the supply is a synced value that can be a few ticks stale, and a lever move
            // must not be able to slip through on a stale one.
            if (!electricData.gearAvailable(gear))
                return;

            // Reverse while already moving arms the emergency brake instead of
            // commanding reverse traction: a real handle cannot be thrown against
            // the direction of travel, and "stop hard" is what the driver means.
            if (gear == TrainGear.REVERSE && Math.abs(train.speed) > TrainTractionModel.CRUISE_MIN_SPEED) {
                state.gear = gear;
                // The SAME three flags the emergency button sets, because this is the same
                // emergency brake.
                //
                // It used to set only `emergencyArmed`. That left the panel live: the lockout for
                // the lever is keyed on emergencyArmed and so still held, but the lockout for the
                // PANTOGRAPHS is keyed on controlsLocked, and SetPantographPacket consults nothing
                // else. A driver who slammed the handle into reverse at speed could therefore raise a
                // pantograph in the middle of an emergency stop - the one thing the operator's rule
                // forbids, and the case that matters, since the point of the lock is that a driver
                // whose supply failed mid-stop must not bring the motors back to finish it.
                //
                // TrainDriverState.controlsLocked documents that the two flags "are set together, so
                // the brake cannot be running while the panel is live". This is the line that made
                // that untrue.
                state.emergencyTicks = 0;
                state.emergencyPenalty = true;
                // Same reason as the emergency button: a new application starts a new leg.
                state.penaltyStationReached = false;
                state.penaltyDistance = 0d;
                state.controlsLocked = true;
                state.emergencyArmed = true;
                // Same reason as the emergency button: the tail must restart, or a second application
                // inherits the first one's count and the panel unlocks before LOCKOUT_TAIL_TICKS have
                // passed since THIS stop.
                state.lockoutTailTicks = 0;
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
