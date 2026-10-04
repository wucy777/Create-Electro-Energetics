package com.george_vi.electroenergetics.content.railway_electrification;

import com.george_vi.electroenergetics.content.railway_electrification.pantograph.TrainPantographEntry;
import com.george_vi.electroenergetics.foundation.nodes.AttachedNode;
import com.george_vi.electroenergetics.simulation.infrastructure.WireSimulationState;

import java.util.ArrayList;
import java.util.List;

public class ElectricTrainData {
    public List<TrainPantographEntry> pantographs = new ArrayList<>();
    public int accumulators = 0;
    public double accumulatorCharge = 0d;
    public double accumulatorChargeVoltage = 0d;
    public double accumulatorActualVoltage = 0d;
    public boolean hasCreativeSource = false;
    public boolean isPowered = false;
    public double lastVoltage = 0d;

    public double lastSpeed;

    // Speed the traction model can currently sustain, in Blocks/Second.
    // Already accounts for running resistance, gradient and the supply.
    public float maxSpeed;

    // Acceleration the traction can currently deliver, in Blocks/Second².
    // Tapers with speed because the effort is power-limited.
    public float availableAcceleration;

    // Gradient along the direction of travel (rise / run). Positive = uphill.
    public double trackGrade = 0d;

    // Electrical power actually being drawn by the traction [W]. Reported on
    // the driver's HUD.
    public double displayPower = 0d;

    public AttachedNode trainNode;
    public AttachedNode groundNode;
    public WireSimulationState connectedWireState;
    public WireSimulationState.WireCutHandle wireCutHandle;

    // Total current draw for display on ammeters attached to train contraptions
    public double displayCurrent = 0;

    // Power the motors pushed back into the line while braking [W]. Zero unless
    // the train is actually slowing under the brake, and reported on the HUD.
    public double regenPower = 0d;

    // Whether the brake is being held this tick. Set by the drive path's braking
    // branch and read by the next circuit build, which is the same one-tick lag
    // the load resistance already has.
    public boolean braking = false;

    // Packet throttling for gauge data sync
    public double lastSyncedVoltage = 0;
    public double lastSyncedCurrent = 0;
    public int ticksSinceGaugeSync = 0;
}
