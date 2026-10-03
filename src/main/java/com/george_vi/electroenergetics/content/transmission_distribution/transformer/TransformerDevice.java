package com.george_vi.electroenergetics.content.transmission_distribution.transformer;

import com.george_vi.electroenergetics.devices.device.DevicesSavedData;
import com.george_vi.electroenergetics.devices.device.SimulatedDeviceType;
import com.george_vi.electroenergetics.foundation.device.SimpleElectricalDevice;
import com.george_vi.electroenergetics.simulation.BridgeCollector;
import com.george_vi.electroenergetics.simulation.SimulationResults;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;

/**
 * The plain transformer. It has no power rating and cannot overheat: the winding
 * ratio alone decides what it does, and how much power it passes is left entirely
 * to the circuit around it. (The panel-mounted miniature transformer and the
 * transformer core multiblock separately keep their own thermal ratings.)
 */
public class TransformerDevice extends SimpleElectricalDevice {
    public TransformerDevice(Level level, BlockPos pos, DevicesSavedData deviceSD, SimulatedDeviceType<?> type) {
        super(level, pos, deviceSD, type);
    }

    public TransformerBehaviour.TransformerBehaviourDataHolder transformerData;
    public double ratio;
    public TransformerBlockEntity be;

    @Override
    public void preTick(BridgeCollector bridges) {
        double ratio = this.ratio;
        if (ratio == 0)
            ratio = 1;

        TransformerBehaviour.preTick(TransformerBehaviour.setupStandardNodes(pos), ratio, pos, bridges, this.transformerData);
    }

    @Override
    public void postTick(SimulationResults results) {
        double power = TransformerBehaviour.postTick(TransformerBehaviour.setupStandardNodes(pos), results, this.transformerData);

        if (this.be == null && level.isLoaded(pos) && level.getBlockEntity(pos) instanceof TransformerBlockEntity b)
            this.be = b;

        if (this.be != null) {
            if (this.be.isRemoved())
                this.be = null;
            else {
                this.be.power = Math.abs(power);
                this.be.primaryVoltage = this.transformerData.lastPrimaryVoltage;
                this.be.secondaryVoltage = this.transformerData.lastSecondaryVoltage;
            }
        }
    }

    @Override
    public void read(CompoundTag tag) {
        this.ratio = tag.getDouble("Ratio");
        this.transformerData = new TransformerBehaviour.TransformerBehaviourDataHolder(tag.getCompound("TransformerData"));
    }

    @Override
    public void write(CompoundTag tag) {
        tag.putDouble("Ratio", this.ratio);
        tag.put("TransformerData", this.transformerData.write());
    }
}


