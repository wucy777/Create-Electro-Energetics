package com.george_vi.electroenergetics.content.railway_electrification.gauges;

import com.george_vi.electroenergetics.CEEPackets;
import com.george_vi.electroenergetics.content.railway_electrification.TrainHudData;
import io.netty.buffer.ByteBuf;
import net.createmod.catnip.net.base.ClientboundPacketPayload;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.StreamCodec;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.UUID;

/**
 * Server to client sync of a train's live electrical and traction state.
 *
 * <p>Feeds two things: the voltmeters and ammeters mounted on train
 * contraptions, and the driver's HUD (see {@link TrainHudData}).
 *
 * <p>The codec is written out by hand rather than composed, because this
 * carries more fields than {@code StreamCodec.composite} has an overload for.
 *
 * @param trainId          the train's UUID
 * @param voltage          catenary voltage at the train [V]
 * @param current          total current draw [A]
 * @param maxSpeed         modelled speed ceiling [Blocks/Second]
 * @param power            electrical power being drawn [W]
 * @param grade            track gradient along travel, positive uphill
 * @param powered          whether the traction is energised
 * @param carriages        carriage count of the consist
 * @param motorCars        how many carriages carry a traction motor
 * @param powerPerCarriage rated traction power of one carriage [W]
 * @param manualFullSpeed  whether the server waives Create's manual-driving speed
 *                         handicap for this train. Sent because it comes from a
 *                         server-side config the client cannot read, and the
 *                         experience-bar speed readout has to divide by the same
 *                         factor the train is driven with (see
 *                         {@code ElectricManualSpeed}).
 */
public record SyncTrainGaugeDataPacket(UUID trainId, double voltage, double current,
                                       float maxSpeed, float power, double grade,
                                       boolean powered, int carriages, int motorCars,
                                       float powerPerCarriage, boolean manualFullSpeed)
        implements ClientboundPacketPayload {

    public static final StreamCodec<ByteBuf, SyncTrainGaugeDataPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public SyncTrainGaugeDataPacket decode(ByteBuf buffer) {
            UUID trainId = UUIDUtil.STREAM_CODEC.decode(buffer);
            double voltage = buffer.readDouble();
            double current = buffer.readDouble();
            float maxSpeed = buffer.readFloat();
            float power = buffer.readFloat();
            double grade = buffer.readDouble();
            boolean powered = buffer.readBoolean();
            int carriages = buffer.readInt();
            int motorCars = buffer.readInt();
            float powerPerCarriage = buffer.readFloat();
            boolean manualFullSpeed = buffer.readBoolean();
            return new SyncTrainGaugeDataPacket(trainId, voltage, current, maxSpeed, power,
                    grade, powered, carriages, motorCars, powerPerCarriage, manualFullSpeed);
        }

        @Override
        public void encode(ByteBuf buffer, SyncTrainGaugeDataPacket p) {
            UUIDUtil.STREAM_CODEC.encode(buffer, p.trainId);
            buffer.writeDouble(p.voltage);
            buffer.writeDouble(p.current);
            buffer.writeFloat(p.maxSpeed);
            buffer.writeFloat(p.power);
            buffer.writeDouble(p.grade);
            buffer.writeBoolean(p.powered);
            buffer.writeInt(p.carriages);
            buffer.writeInt(p.motorCars);
            buffer.writeFloat(p.powerPerCarriage);
            buffer.writeBoolean(p.manualFullSpeed);
        }
    };

    @Override
    @OnlyIn(Dist.CLIENT)
    public void handle(LocalPlayer player) {
        ClientTrainGaugeData.update(trainId, voltage, current);
        TrainHudData.update(trainId, maxSpeed, power, (float) voltage, grade, powered,
                carriages, motorCars, powerPerCarriage, manualFullSpeed);
    }

    @Override
    public PacketTypeProvider getTypeProvider() {
        return CEEPackets.SYNC_TRAIN_GAUGE_DATA;
    }
}
