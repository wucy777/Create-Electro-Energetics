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
    // Curve-limited speed from the lateral acceleration limit, in Blocks/Second.
    public float curveSpeed = Float.MAX_VALUE;
    // Yaw of the leading carriage last tick, used to measure the yaw rate in curves.
    public float lastYaw = Float.NaN;

    public AttachedNode trainNode;
    public AttachedNode groundNode;
    public WireSimulationState connectedWireState;
    public WireSimulationState.WireCutHandle wireCutHandle;

    // Total current draw for display on ammeters attached to train contraptions
    public double displayCurrent = 0;

    // Packet throttling for gauge data sync
    public double lastSyncedVoltage = 0;
    public double lastSyncedCurrent = 0;
    public int ticksSinceGaugeSync = 0;
}
