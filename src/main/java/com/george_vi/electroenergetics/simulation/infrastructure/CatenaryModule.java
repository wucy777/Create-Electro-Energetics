package com.george_vi.electroenergetics.simulation.infrastructure;

import com.george_vi.electroenergetics.CEERegistries;
import com.george_vi.electroenergetics.config.CEEConfigs;
import com.george_vi.electroenergetics.content.railway_electrification.ElectricTrainData;
import com.george_vi.electroenergetics.content.railway_electrification.TrainTractionModel;
import com.george_vi.electroenergetics.content.railway_electrification.gauges.SyncTrainGaugeDataPacket;
import com.george_vi.electroenergetics.content.railway_electrification.pantograph.TrainPantographEntry;
import com.george_vi.electroenergetics.content.railway_electrification.sound_effects.UpdateElectricTrainSoundPacket;
import com.george_vi.electroenergetics.foundation.SendSparkPacket;
import com.george_vi.electroenergetics.foundation.nodes.AttachedNode;
import com.george_vi.electroenergetics.mixin_interfaces.ICEETrainExtension;
import com.george_vi.electroenergetics.mixin_interfaces.IPantographList;
import com.george_vi.electroenergetics.simulation.CircuitBuilder;
import com.george_vi.electroenergetics.simulation.SimulationResults;
import com.george_vi.electroenergetics.simulation.electrical_properties.ElectricalProperties;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import net.createmod.catnip.math.VecHelper;
import net.createmod.catnip.platform.CatnipServices;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.*;

public class CatenaryModule {
    private static final double GAUGE_SYNC_THRESHOLD = 0.05; // 5% change threshold for network sync

    final InfrastructureSavedData sd;
    final ServerLevel level;
    final WireSimulationState levelWireSimulationState;
    private final Set<Train> allTrains = new HashSet<>();

    public CatenaryModule(InfrastructureSavedData sd, ServerLevel level, WireSimulationState wireSimulationState) {
        this.sd = sd;
        this.level = level;
        this.levelWireSimulationState = wireSimulationState;
    }

    public void buildCircuit(CircuitBuilder builder) {
        int id = 0;
        for (Train train : Create.RAILWAYS.trains.values()) {
            allTrains.add(train);
            ICEETrainExtension trainExtension = (ICEETrainExtension)train;
            ElectricTrainData trainData = trainExtension.getElectricTrainData();
            boolean connected = false;
            for (Carriage carriage : train.carriages) {
                List<TrainPantographEntry> pantographs = ((IPantographList)carriage).electroEnergetics$getPantographList();
                if (carriage.presentInMultipleDimensions() ||
                        !carriage.getPresentDimensions().getFirst().equals(level.dimension()) ||
                        !((IPantographList)carriage).electroEnergetics$hasElectricMotor())
                    continue;

                Carriage.DimensionalCarriageEntity dce = carriage.getDimensional(carriage.getPresentDimensions().getFirst());
                Vec3 positionVec = dce.rotationAnchors.getFirst();
                Vec3 coupledVec = dce.rotationAnchors.getSecond();
                //noinspection ConstantValue
                if (positionVec == null || coupledVec == null)
                    continue;

                double diffX = positionVec.x - coupledVec.x;
                double diffY = positionVec.y - coupledVec.y;
                double diffZ = positionVec.z - coupledVec.z;

                float yaw = (float) (Mth.atan2(diffZ, diffX) * Mth.RAD_TO_DEG) + 180;
                float pitch = (float) (Mth.atan2(diffY, Math.sqrt(diffX * diffX + diffZ * diffZ)) * Mth.RAD_TO_DEG) * -1;

                Vec3 pivotPosition = carriage.isOnTwoBogeys() ? positionVec : VecHelper.lerp(0.5f, positionVec, coupledVec);

                PantographLoop:
                for (TrainPantographEntry pantograph : pantographs) {
                    if (!pantograph.active)
                        continue;

                    Vec3 pantographPos = Vec3.atLowerCornerOf(pantograph.rotatedPos).add((pantograph.facingForward ? 1 : -1) * pantograph.type.backOffset, pantograph.type.reach / 2 + pantograph.type.topOffset, 0);

                    pantographPos = VecHelper.rotate(pantographPos, -pitch, Direction.Axis.Z);
                    pantographPos = VecHelper.rotate(pantographPos, -yaw + 180, Direction.Axis.Y);

                    pantographPos = pivotPosition.add(pantographPos);
//                    level.sendParticles(ParticleTypes.ELECTRIC_SPARK, pantographPos.x, pantographPos.y, pantographPos.z, 3, 0, 0, 0, 0);

                    Set<ConnectionEntry> toCheck = new HashSet<>();

                    long sectionPos = SectionPos.asLong(Mth.floor(pantographPos.x) >> 4, Mth.floor(pantographPos.y) >> 4, Mth.floor(pantographPos.z) >> 4);
                    sd.wireSimulationState.getConnectionsInSection(sectionPos, toCheck::add);
                    for (Direction direction : Direction.values()) {
                        double positionAlongAxis = (pantographPos.get(direction.getAxis()) % 16 + 16) % 16 - 8;
                        if (positionAlongAxis < direction.getAxisDirection().getStep() * 6)
                            continue;
                        sd.wireSimulationState.getConnectionsInSection(
                                SectionPos.asLong(
                                        SectionPos.x(sectionPos) + direction.getNormal().getX(),
                                        SectionPos.y(sectionPos) + direction.getNormal().getY(),
                                        SectionPos.z(sectionPos) + direction.getNormal().getZ()), toCheck::add);
                    }

                    for (ConnectionEntry connectionEntry : toCheck) {
                        if (connectionEntry.wireData.wireType().getSag() != 0 && !(connectionEntry.wireData instanceof CatenaryConnectionData))
                            continue;
                        Vec3 start = connectionEntry.pos1;
                        Vec3 end = connectionEntry.pos2;

                        float t = 0;

                        Vec3 ab = end.subtract(start);
                        Vec3 ap = pantographPos.subtract(start);
                        double denom = ab.lengthSqr();
                        if (denom != 0) {
                            t = (float) (ap.dot(ab) / denom);
                            t = Mth.clamp(t, 0, 1);
                        }

                        Vec3 closest = VecHelper.lerp(t, start, end);

                        Vec3 distance = pantographPos.subtract(closest);
                        distance = VecHelper.rotate(distance, yaw + 180, Direction.Axis.Y);
                        distance = VecHelper.rotate(distance, -pitch, Direction.Axis.X);
                        float xTol = (float) ((distance.y + pantograph.type.reach / 2) * 0.2f + 0.125f);
                        if (Math.abs(distance.z()) < pantograph.type.sidewaysReach && Math.abs(distance.x()) < xTol && Math.abs(distance.y()) < pantograph.type.reach / 2) {

                            // If we have a handle, but it's from a different dimension, then invalidate it
                            // (we'll create a new one)
                            if (trainData.wireCutHandle != null && trainData.connectedWireState != levelWireSimulationState) {
                                trainData.connectedWireState.invalidateHandle(trainData.wireCutHandle);
                                trainData.wireCutHandle = null;
                            }

                            if (trainData.wireCutHandle == null) {
                                trainData.connectedWireState = levelWireSimulationState;
                                trainData.wireCutHandle = trainData.connectedWireState.createHandle("ElectricTrain");
                            }

                            pantograph.prevPos = pantograph.pos;
                            pantograph.pos = closest;

                            // If not connected, connect
                            if (pantograph.node == null) {
                                pantograph.node = trainData.connectedWireState.createCut(trainData.wireCutHandle, connectionEntry, t);
                                pantograph.onConnection = connectionEntry;
                            }
                            // If connected to another within this dimension
                            else if (trainData.connectedWireState == levelWireSimulationState) {
                                if (pantograph.onConnection != connectionEntry ||
                                        !trainData.connectedWireState.cutExists(trainData.wireCutHandle, pantograph.node)) {
                                    trainData.connectedWireState.removeCut(trainData.wireCutHandle, pantograph.node);
                                    pantograph.node = levelWireSimulationState.createCut(trainData.wireCutHandle, connectionEntry, t);

                                    pantograph.onConnection = connectionEntry;
                                    trainData.connectedWireState = levelWireSimulationState;
                                }
                                // Continue being connected
                                else {
                                    trainData.connectedWireState.relocateCut(trainData.wireCutHandle, pantograph.node, t);
                                }
                            }
                            connected = true;
//                            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, closest.x, closest.y, closest.z, 3, 0, 0, 0, 0);
                            continue PantographLoop;
                        }
                    }
                    pantograph.prevPos = pantograph.pos;
                    pantograph.pos = null;
                    if (trainData.wireCutHandle != null && pantograph.node != null)
                        trainData.connectedWireState.removeCut(trainData.wireCutHandle, pantograph.node);

                }
            }

            // Only modify properties if the train is being powered by a source in this dimension, otherwise we end up
            // resetting the train properties even if it's being powered in a different dimension.
            if(trainData.connectedWireState != levelWireSimulationState)
                continue;

            if (!connected || (trainData.pantographs.isEmpty() && trainData.accumulatorCharge <= 0.001)) {
                trainData.groundNode = null;
                trainData.trainNode = null;

                continue;
            }

            double trainSpeed = Math.abs(train.speed);
            float acceleration = (float) (trainSpeed - trainData.lastSpeed);

            // This needs to happen in this method as the train speed is updated after it.
            trainData.lastSpeed = trainSpeed;

            AttachedNode groundNode = trainData.groundNode = new AttachedNode(id, "CEEPantographGroundNode");
            AttachedNode trainNode = trainData.trainNode = new AttachedNode(id, "CEETrainNode");
            builder.addNode(groundNode);
            builder.addNode(trainNode);
            builder.ground(groundNode, 10);

            double trainSpeedMs = Math.abs(train.speed) * 20d;
            double accelerationMs2 = acceleration * 400d;
            int carriages = Math.max(1, train.carriages.size());

            // How much of its rating this train may draw. A stiff supply gives a
            // full 1.0; when several trains share one catenary the terminal
            // voltage sags and each of them gets less, so they share the power
            // instead of melting the wire together. With no catenary contact
            // the accumulator voltage is what limits the traction.
            double lastVoltage = Math.abs(trainData.lastVoltage) < 1 ? 3000 : trainData.lastVoltage;
            double tractionVoltage = trainData.hasCreativeSource
                    ? maxVoltageForTraction()
                    : (Math.abs(trainData.lastVoltage) < 1
                            ? Math.max(trainData.accumulatorActualVoltage, trainData.accumulatorChargeVoltage)
                            : trainData.lastVoltage);
            double powerScale = TrainTractionModel.powerScaleForVoltage(tractionVoltage);

            // Gradient along the direction of travel, taken from the consist's
            // own geometry: the rise between its leading and trailing anchors
            // divided by their horizontal run.
            double grade = trainGrade(train);

            // Electrical demand for the current mechanical situation.
            double electricalPower = TrainTractionModel.electricalDemand(
                    trainSpeedMs, accelerationMs2, grade, carriages, powerScale);

            // Guard against a non-finite demand producing a broken resistance.
            if (!(electricalPower > 0d) || Double.isNaN(electricalPower))
                electricalPower = TrainTractionModel.MIN_DEMAND;
            trainData.displayPower = electricalPower;
            double trainResistance = lastVoltage * lastVoltage / electricalPower;

            builder.connect(groundNode, trainNode, ElectricalProperties.resistor(trainResistance));

            for (TrainPantographEntry pe : trainExtension.getElectricTrainData().pantographs)
                if (pe.active && pe.node != null)
                    builder.connect(pe.node, trainNode, ElectricalProperties.resistor(CEEConfigs.server().resistanceValues.wireResistance.get()));
            id++;
        }

        Iterator<Train> trainIterator = allTrains.iterator();
        while (trainIterator.hasNext()) {
            Train train = trainIterator.next();
            if (!Create.RAILWAYS.trains.containsKey(train.id) && ((ICEETrainExtension)train).getElectricTrainData().wireCutHandle != null) {
                var trainData = ((ICEETrainExtension) train).getElectricTrainData();

                trainData.connectedWireState.invalidateHandle(trainData.wireCutHandle);
                trainData.wireCutHandle = null;
                trainIterator.remove();
            }
        }
    }

    public void finishSimulation(SimulationResults results) {
        for (Train train : Create.RAILWAYS.trains.values()) {

            ICEETrainExtension trainExtension = (ICEETrainExtension)train;
            ElectricTrainData trainData = trainExtension.getElectricTrainData();

            AttachedNode groundNode = trainData.groundNode;
            AttachedNode trainNode = trainData.trainNode;
            double voltage;
            if (groundNode != null && trainNode != null)
                voltage = Math.abs(results.getVoltageAt(groundNode, trainNode));
            else
                voltage = 0;

            // Store voltage for gauge displays on train contraptions
            trainData.lastVoltage = voltage;

            boolean minimumVoltageReached = voltage >= CEEConfigs.server().voltageValues.trainMinVoltage.get();
            boolean active = trainData.hasCreativeSource || minimumVoltageReached;
            double trainSpeed = train.derailed ? 0 : Math.abs(train.speed);

            // Calculate total current draw for ammeter displays
            double totalCurrent = 0;

            // Sparks
            for (TrainPantographEntry pantograph : trainData.pantographs) {
                if (pantograph.node == null || trainNode == null)
                    continue;
                double current = Math.abs(results.getCurrentThrough(trainNode, pantograph.node));
                totalCurrent += current;

                Vec3 sparkPos = pantographSparkPosition(level, train, pantograph, current);
                if (sparkPos != null)
                    CatnipServices.NETWORK.sendToClientsAround(level, sparkPos,
                            40, new SendSparkPacket(sparkPos, SendSparkPacket.SparkSize.MEDIUM));
                pantograph.lastCurrent = current;
            }

            // Store total current for ammeter displays on train contraptions
            trainData.displayCurrent = totalCurrent;

            // The gauge/HUD sync happens further down, once the traction values
            // for this tick have been worked out -- the driver's HUD needs the
            // modelled speed ceiling, power and gradient as well, and those are
            // only known after the solve.

            if (!active) {
                if (trainData.accumulatorCharge > 0) {
                    if (trainSpeed > 0.001) {
                        trainData.accumulatorCharge = Math.max(
                                0d,
                                trainData.accumulatorCharge - 1d / CEEConfigs.server().trainValues.ticksPerAccumulatorOnTrain.get()
                        );
                        trainData.accumulatorActualVoltage = trainData.accumulatorChargeVoltage * trainData.accumulatorCharge / trainData.accumulators;
                    }
                    active = true;
                }
            } else if (minimumVoltageReached) {

                if (trainData.accumulatorCharge < trainData.accumulators) {
                    trainData.accumulatorCharge = Math.min(
                            trainData.accumulators,
                            trainData.accumulatorCharge + 1d / CEEConfigs.server().trainValues.ticksPerAccumulatorChargeOnTrain.get()
                    );

                    trainData.accumulatorChargeVoltage = voltage * trainData.accumulatorCharge / trainData.accumulators;
                } else if (trainData.accumulatorCharge == trainData.accumulators) {
                    trainData.accumulatorChargeVoltage = voltage;
                }
                trainData.accumulatorActualVoltage = trainData.accumulatorChargeVoltage;
            }

            Map<Integer, Vec3> positions = new HashMap<>();
            int motorCars = 0;
            for (Carriage carriage : train.carriages) {
                if (((IPantographList)carriage).electroEnergetics$hasElectricMotor()) {
                    // Counted here rather than from positions.size(), which holds
                    // two entries per two-bogey carriage and skips ones whose
                    // dimensional entity is not loaded.
                    motorCars++;
                    Carriage.DimensionalCarriageEntity dce = carriage.getDimensionalIfPresent(level.dimension());
                    if (dce == null)
                        continue;

                    int carriageIndex = train.carriages.indexOf(carriage);
                    if (carriage.isOnTwoBogeys()) {
                        positions.put(carriageIndex + 1, dce.trailingAnchor());
                        positions.put(-carriageIndex - 1, dce.leadingAnchor());
                    } else
                        positions.put(carriageIndex + 1, dce.trailingAnchor());
                }
            }
            float acceleration = (float) (trainSpeed - trainData.lastSpeed);

            for (Map.Entry<Integer, Vec3> ce : positions.entrySet()) {
                Integer carriageID = ce.getKey();
                Vec3 pos = ce.getValue();

                if (pos == null)
                    continue;

                CatnipServices.NETWORK.sendToClientsAround(level, pos,
                        100, new UpdateElectricTrainSoundPacket(train.id, carriageID, (float) trainSpeed, acceleration, active, CEERegistries.ELECTRIC_TRAIN_SOUND_TYPE.getId(trainExtension.getSoundType())));
            }
            trainData.isPowered = active;

            // Gradient and supply share for this tick. These are recomputed here
            // rather than reused from buildCircuit because the terminal voltage
            // is only known now, after the solve.
            int carriages = Math.max(1, train.carriages.size());
            double trainSpeedMs = trainSpeed * 20d;
            double grade = trainGrade(train);
            double tractionVoltage = trainData.hasCreativeSource
                    ? maxVoltageForTraction()
                    : (voltage > 0 ? voltage : trainData.accumulatorActualVoltage);
            double powerScale = TrainTractionModel.powerScaleForVoltage(tractionVoltage);

            trainData.trackGrade = grade;

            if (active) {
                // Speed the traction can sustain against resistance and gradient.
                trainData.maxSpeed = (float) TrainTractionModel.maxSustainableSpeed(
                        grade, carriages, powerScale);

                // Acceleration the motors can currently deliver; tapers with speed.
                trainData.availableAcceleration = (float) TrainTractionModel.availableAcceleration(
                        trainSpeedMs, grade, carriages, powerScale);
            } else {
                // No traction with no supply, so there is no speed it can
                // sustain. Zero rather than the design ceiling on purpose: the
                // design figure would be picked up by both the HUD and
                // maxSpeed() and would let a train with no power at all run at
                // full speed. TrainHudData.maxSpeed() ignores a sample the
                // server marked unpowered, so a zero here cannot divide by it.
                trainData.maxSpeed = 0f;
                trainData.availableAcceleration = 0f;
            }

            // Sync to clients for the gauges and the driver's HUD, now that this
            // tick's traction values are known. Throttled: only when something
            // moved appreciably, or every few ticks as a keep-alive.
            trainData.ticksSinceGaugeSync++;
            boolean significantVoltageChange = Math.abs(voltage - trainData.lastSyncedVoltage)
                    > Math.max(voltage, trainData.lastSyncedVoltage) * GAUGE_SYNC_THRESHOLD;
            boolean significantCurrentChange = Math.abs(totalCurrent - trainData.lastSyncedCurrent)
                    > Math.max(totalCurrent, trainData.lastSyncedCurrent) * GAUGE_SYNC_THRESHOLD;
            boolean shouldSync = trainData.ticksSinceGaugeSync >= 5
                    || significantVoltageChange || significantCurrentChange;

            if (shouldSync && !train.carriages.isEmpty()) {
                Carriage.DimensionalCarriageEntity firstCarriage =
                        train.carriages.getFirst().getDimensionalIfPresent(level.dimension());
                if (firstCarriage != null && firstCarriage.entity != null) {
                    CarriageContraptionEntity entity = firstCarriage.entity.get();
                    if (entity != null) {
                        CatnipServices.NETWORK.sendToClientsAround(
                                level,
                                entity.position(),
                                100,
                                new SyncTrainGaugeDataPacket(train.id, voltage, totalCurrent,
                                        trainData.maxSpeed, (float) trainData.displayPower,
                                        grade, active, carriages, motorCars,
                                        CEEConfigs.server().trainValues
                                                .electricTrainPowerPerCarriage.getF(),
                                        CEEConfigs.server().trainValues
                                                .electricTrainManualFullSpeed.get())
                        );
                        trainData.lastSyncedVoltage = voltage;
                        trainData.lastSyncedCurrent = totalCurrent;
                        trainData.ticksSinceGaugeSync = 0;
                    }
                }
            }

        }
    }

    /** Voltage the catenary is nominally at; used for creative-supply trains. */
    private static double maxVoltageForTraction() {
        return CEEConfigs.server().voltageValues.trainMaxVoltage.get();
    }

    /**
     * Rise over run along the consist, in the direction the train is travelling.
     * Positive means the train is climbing, whichever way it is facing.
     *
     * <p>The anchors are fixed to the consist, not to the direction of travel:
     * {@code rotationAnchors.getFirst()} is the carriage's leading end, which is
     * the front only while {@code speed > 0}. Running in reverse, that end is
     * behind, so the raw rise/run comes out negated - the train would read a
     * climb as a descent, gaining speed and shedding power on the way up. The
     * sign of travel reverses it back.
     */
    private double trainGrade(Train train) {
        double rise = 0d;
        double run = 0d;
        for (Carriage carriage : train.carriages) {
            Carriage.DimensionalCarriageEntity dce = carriage.getDimensionalIfPresent(level.dimension());
            if (dce == null || dce.rotationAnchors == null)
                continue;
            Vec3 leading = dce.rotationAnchors.getFirst();
            Vec3 trailing = dce.rotationAnchors.getSecond();
            if (leading == null || trailing == null)
                continue;
            rise += leading.y - trailing.y;
            double dx = leading.x - trailing.x;
            double dz = leading.z - trailing.z;
            run += Math.sqrt(dx * dx + dz * dz);
        }
        if (run < 1e-4d)
            return 0d;
        // Reversing makes the consist's "leading" end the trailing one.
        double direction = train.speed < 0 ? -1d : 1d;
        return direction * rise / run;
    }

    private static Vec3 pantographSparkPosition(ServerLevel level, Train train, TrainPantographEntry pantograph, double current) {
        if (pantograph.pos == null && pantograph.prevPos != null && pantograph.lastCurrent > 1e-2d) {
            return pantograph.prevPos;
        } else if (pantograph.pos != null && pantograph.prevPos == null && current > 1e-2d) {
            return pantograph.pos;
        } else if (pantograph.pos != null && current > 7) {
            if (CEEConfigs.server().trainValues.highSpeedPantographSparks.get() != 0 &&
                    Math.abs(train.speed) > CEEConfigs.server().trainValues.highSpeedPantographSparks.get() &&
                    level.random.nextFloat() < CEEConfigs.server().trainValues.highSpeedPantographSparkChance.get()) {
                return pantograph.pos;
            } else if (Math.abs(train.speed) > 0.1) {
                BlockPos pos = BlockPos.containing(pantograph.pos);
                if (CEEConfigs.server().trainValues.winterPantographSparks.get() &&
                        level.random.nextFloat() < CEEConfigs.server().trainValues.winterPantographSparkChance.get() &&
                        level.isLoaded(pos) && level.getBiome(pos).value().getBaseTemperature() < 0.15f) {
                    return pantograph.pos;
                } else if (CEEConfigs.server().trainValues.rainPantographSparks.get() &&
                        level.random.nextFloat() < CEEConfigs.server().trainValues.rainPantographSparkChance.get() &&
                        level.isLoaded(pos) && level.isRainingAt(pos)) {
                    return pantograph.pos;
                }
            }
        }
        return null;
    }
}
