package com.george_vi.electroenergetics.simulation.infrastructure;

import com.george_vi.electroenergetics.CEEPantographTypes;
import com.george_vi.electroenergetics.CEERegistries;
import com.george_vi.electroenergetics.config.CEEConfigs;
import com.george_vi.electroenergetics.content.railway_electrification.ElectricTrainData;
import com.george_vi.electroenergetics.content.railway_electrification.TrainControlMode;
import com.george_vi.electroenergetics.content.railway_electrification.TrainControlModeLogic;
import com.george_vi.electroenergetics.content.railway_electrification.TrainDriverState;
import com.george_vi.electroenergetics.content.railway_electrification.TrainGear;
import com.george_vi.electroenergetics.content.railway_electrification.TrainTractionModel;
import com.george_vi.electroenergetics.content.railway_electrification.gauges.SyncTrainGaugeDataPacket;
import com.george_vi.electroenergetics.content.railway_electrification.pantograph.SyncTrainPantographsPacket;
import com.george_vi.electroenergetics.content.railway_electrification.pantograph.TrainPantographEntry;
import com.george_vi.electroenergetics.content.railway_electrification.sound_effects.UpdateElectricTrainSoundPacket;
import com.george_vi.electroenergetics.foundation.SendSparkPacket;
import com.george_vi.electroenergetics.foundation.nodes.AttachedNode;
import com.george_vi.electroenergetics.mixin_interfaces.ICEETrainExtension;
import com.george_vi.electroenergetics.mixin_interfaces.IPantographList;
import com.george_vi.electroenergetics.simulation.CircuitBuilder;
import com.george_vi.electroenergetics.simulation.SimulationResults;
import com.george_vi.electroenergetics.simulation.electrical_properties.ElectricalProperties;
import com.george_vi.electroenergetics.simulation.electrical_properties.NortonProperties;
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
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.*;

public class CatenaryModule {
    private static final double GAUGE_SYNC_THRESHOLD = 0.05; // 5% change threshold for network sync

    /**
     * Ticks between keep-alive pantograph and control-lock syncs to the driver.
     *
     * <p>Five ticks, matching the gauge sync, because both feed the same panel and refreshing them
     * at different rates would only make the two disagree on screen. A lock state CHANGE bypasses
     * this interval entirely - see {@code syncPantographs}.
     */
    private static final int PANTOGRAPH_SYNC_INTERVAL = 5;

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

            // STRUCTURAL FACTS FIRST, before any early return in this method.
            //
            // Whether this consist IS an electric train - motors and pantographs - is a property of
            // how it was built, not of whether it is collecting current. Deciding it further down,
            // after the "nothing is connected" early return, deadlocked the whole feature: a train
            // assembled with every pantograph DOWN has nothing connected, so it would never be
            // marked electric, so the driver's raise command would be refused as coming from
            // someone not driving an electric train, so the pantograph could never go up. Raising a
            // pantograph required being electric, and being electric required a raised pantograph.
            //
            // Computing it here breaks that: the train is electric from the moment it is built, and
            // the pantographs are simply all down.
            boolean trainHasMotor = false;
            for (Carriage carriage : train.carriages) {
                if (((IPantographList) carriage).electroEnergetics$hasElectricMotor()) {
                    trainHasMotor = true;
                    break;
                }
            }
            // BOTH are required, and they may sit on different carriages. Motors without a
            // pantograph can never be energised, and pantographs without a motor can never move the
            // train; neither is an electric train in any useful sense, so neither gets the lever.
            //
            // A train that collects ONLY through rail contact shoes is still an electric train in the
            // sense that it runs on this mod's electricity, but the operator's ruling is that it keeps
            // UPSTREAM's behaviour: Create's W/S, Create's speed bar, none of this mod's lever,
            // vigilance clock, traction model or ceiling - "弓就是动车，靴就是地铁". So it is recorded as
            // an electric train for the electrical solve, and `underElectricControl()` refuses it,
            // which is the single gate every driving path asks.
            trainData.hasTractionMotors = trainHasMotor && !trainData.pantographs.isEmpty();

            // The reach flags are cleared per carriage below, NOT for the whole consist here.
            //
            // A carriage in another level belongs to that level's solve: clearing it from here would
            // be undone and redone in an order nobody controls, because every level's module sees
            // every train. Each carriage therefore clears its own, once it is known to be this
            // level's to evaluate.

            boolean connected = false;
            for (Carriage carriage : train.carriages) {
                List<TrainPantographEntry> pantographs = ((IPantographList)carriage).electroEnergetics$getPantographList();
                // A carriage is skipped when it is not in this dimension, or when it is neither a
                // motor car nor carries a pantograph - in which case there is nothing here to do.
                //
                // The motor test is deliberately paired with the pantograph test rather than used
                // alone. Using it alone skipped every carriage that carries a pantograph but no
                // motor, which meant such a pantograph's geometry was never evaluated: it could
                // never be found within reach of catenary, so it could never be raised, and its
                // automatic lowering never fired. Motors and pantographs are allowed to sit on
                // different carriages, so that combination is a normal consist, not an edge case.
                boolean carriageHasMotor =
                        ((IPantographList)carriage).electroEnergetics$hasElectricMotor();

                // A carriage spanning a dimension boundary is skipped by EVERY level's module, so
                // nothing anywhere will evaluate its geometry. Its flags must therefore be cleared
                // rather than left: whatever the last evaluated tick left behind would stand for
                // ever, and `active && reachable` is exactly the test for a working pantograph. A
                // stale true here would let a spanning carriage count as collecting current, and
                // would authorise a driver's raise, for a pantograph that nothing ever looks at.
                if (carriage.presentInMultipleDimensions()) {
                    for (TrainPantographEntry pe : pantographs)
                        pe.reachable = false;
                    continue;
                }

                // A carriage in ANOTHER level is a different case and is deliberately left alone:
                // that level's own module evaluates it, and clearing it from here would be undone
                // and redone in an order nobody controls.
                if (!carriage.getPresentDimensions().getFirst().equals(level.dimension()))
                    continue;

                // Nothing on this carriage to evaluate. The list is empty, so there is no flag to
                // strand - this is the only skip that cannot leave a stale value behind.
                if (!carriageHasMotor && pantographs.isEmpty())
                    continue;

                Carriage.DimensionalCarriageEntity dce = carriage.getDimensional(carriage.getPresentDimensions().getFirst());
                Vec3 positionVec = dce.rotationAnchors.getFirst();
                Vec3 coupledVec = dce.rotationAnchors.getSecond();
                //noinspection ConstantValue
                if (positionVec == null || coupledVec == null) {
                    // The anchors are not loaded, so no geometry can be evaluated for this carriage
                    // and the loop below will not run. Its pantographs must still be brought to a
                    // known state, or they keep whatever the last solvable tick left - and a stale
                    // `reachable = true` is what authorises a driver's raise (SetPantographPacket)
                    // and what the automatic lowering tests. Left alone, a pantograph whose carriage
                    // had been unloaded would stay permanently "in reach of wire", so the driver
                    // could raise it onto nothing and it would never come down again.
                    for (TrainPantographEntry pe : pantographs)
                        pe.reachable = false;
                    continue;
                }

                double diffX = positionVec.x - coupledVec.x;
                double diffY = positionVec.y - coupledVec.y;
                double diffZ = positionVec.z - coupledVec.z;

                float yaw = (float) (Mth.atan2(diffZ, diffX) * Mth.RAD_TO_DEG) + 180;
                float pitch = (float) (Mth.atan2(diffY, Math.sqrt(diffX * diffX + diffZ * diffZ)) * Mth.RAD_TO_DEG) * -1;

                Vec3 pivotPosition = carriage.isOnTwoBogeys() ? positionVec : VecHelper.lerp(0.5f, positionVec, coupledVec);

                // Recomputed from scratch for THIS carriage's pantographs, immediately before the
                // geometry that decides it. A stale true would let a driver raise a pantograph onto
                // nothing, and would stop the automatic lowering from ever firing.
                //
                // Done here rather than at the top of the method so that a carriage in another level
                // is left to that level's solve: every level's module sees every train, so clearing
                // the whole consist from each of them would be an order nobody controls.
                for (TrainPantographEntry pe : pantographs)
                    pe.reachable = false;

                PantographLoop:
                for (TrainPantographEntry pantograph : pantographs) {
                    // NOTE: the geometry below is evaluated whether or not the driver has this
                    // pantograph raised, because the answer is needed either way. "Is this
                    // pantograph within reach of catenary" is what decides whether the driver is
                    // ALLOWED to raise it, and what triggers the automatic lowering when the train
                    // runs out from under the wire. Only the electrical connection step is gated
                    // on `active`.
                    boolean raised = pantograph.active;

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

                            // Catenary is within reach of this pantograph. Recorded regardless of
                            // whether it is raised, because this is the fact that decides whether
                            // the driver is ALLOWED to raise it.
                            pantograph.reachable = true;

                            // AUTOMATIC RAISING, behind the driver's switch - and it is the mirror
                            // image of the automatic lowering further down.
                            //
                            // Off by default, because "how many pantographs are up is the driver's
                            // call". On, it restores upstream's behaviour exactly: wire in reach,
                            // therefore up, with no further test.
                            //
                            // The reason the switch exists at all is a rigid carriage on a curve. The
                            // arm is fixed in the carriage's frame while the catenary follows the
                            // arc, so on a tight enough bend the contact leaves reach laterally; the
                            // lowering below then drops it, and by the time the track straightens the
                            // train has already lost speed. That is not something the driver can fix
                            // by pressing raise at the right moment - it would need pressing on every
                            // bend - so it is a switch rather than a key.
                            //
                            // Setting `active` here makes this the second writer of a raise in the
                            // mod, which is exactly what the single-writer invariant was protecting.
                            // It is allowed because it is not a silent raise: the driver turned the
                            // switch on. The invariant is now "no raise without either the driver's
                            // packet or the driver's switch", and both are the driver.
                            // Per arm: this one's own switch, not the consist's.
                            if (!raised && pantograph.autoRaise)
                                pantograph.active = true;

                            // A raised this tick (by the driver earlier, or by the switch just above)
                            // is what the electrical work below requires.
                            raised = pantograph.active;

                            // The electrical work below only applies to a raised pantograph. A
                            // lowered one that happens to be under wire must NOT collect current,
                            // which is the whole point of the driver's handle - and it must not set
                            // `connected` either, or the train would be treated as energised with
                            // nothing actually touching the wire.
                            if (!raised)
                                continue PantographLoop;

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

                    // AUTOMATIC LOWERING. Nothing within reach and the driver still has this one
                    // raised, so it comes down - the case of a driver approaching a neutral section
                    // or the end of the electrified line who did not lower it in time. A real
                    // pantograph cannot be left pressing against nothing, and the driver has
                    // plainly lost the wire either way.
                    //
                    // Its counterpart, automatic RAISING, is not here: it lives where `reachable` is
                    // set true, and only runs behind the driver's own switch. See that site.
                    // (reachable has just been left false for this pantograph: it is cleared per
                    // carriage before the loop and is set true only on a geometry match, which did
                    // not happen for this one.)
                    if (raised)
                        pantograph.active = false;

                }
            }

            // Only modify properties if the train is being powered by a source in this dimension, otherwise we end up
            // resetting the train properties even if it's being powered in a different dimension.
            if(trainData.connectedWireState != levelWireSimulationState)
                continue;

            if (!connected || (trainData.pantographs.isEmpty() && trainData.accumulatorCharge <= 0.001)) {
                trainData.groundNode = null;
                trainData.trainNode = null;

                // The control mode is still decided HERE, and this is not optional.
                //
                // "Nothing is connected" is exactly the case the handover exists for - a train with
                // no working pantograph - so returning without deciding would leave such a train
                // permanently in ELECTRIC, and neither the coast nor the handover to Create would
                // ever happen. The rest of this method is about traction this train does not have,
                // which is why it can be skipped; the mode is not.
                trainData.hasWorkingPantograph = false;
                // Same rule on this path: a rail-shoe-only train is Create's, unconditionally. Deciding
                // it here as well as on the connected path is not redundancy - this is the path a
                // shoe train takes whenever it is between rails, and leaving it out would put the
                // train back under this mod's controls for the length of every gap.
                if (trainData.railShoeOnly())
                    trainData.controlMode = TrainControlMode.CREATE;
                else
                    TrainControlModeLogic.update(trainData, train.speed, false);

                // The pantograph and lock state still has to reach the driver from here. This is the
                // path taken when nothing is collecting current, which is exactly when the lock
                // matters - see syncPantographs.
                if (trainData.driver.isDriven())
                    syncPantographs(train, trainData, trainData.driver);

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
            // The motors draw current whenever they are being driven against the train: an emergency
            // application, or REVERSE used as a brake while still moving. Both are motor work at rating,
            // and both must be billed to the catenary - the emergency brake is explicitly not a
            // regenerative brake.
            boolean motorBraking = trainData.driver.emergencyArmed
                    || (trainData.driver.gear == TrainGear.REVERSE && Math.abs(trainSpeedMs) > 1e-3d);
            double electricalPower = TrainTractionModel.electricalDemand(
                    trainSpeedMs, accelerationMs2, grade, carriages, powerScale, motorBraking);

            // Guard against a non-finite demand producing a broken resistance.
            if (!(electricalPower > 0d) || Double.isNaN(electricalPower))
                electricalPower = TrainTractionModel.MIN_DEMAND;

            // Regenerative braking. Set by the braking branch of the drive path on
            // the previous tick, so this is the same one-tick lag the load itself
            // already has - the demand above is likewise built from lastSpeed and
            // lastVoltage. Only a train actually slowing under the brake is
            // regenerating; coasting does not, because the motors are not being
            // driven against anything.
            //
            // Gated on the supply actually being able to deliver, which is what
            // `powerScale` already answers locally: it is 0 at or below the minimum
            // traction voltage and rises to 1 as the terminal voltage recovers. That
            // gate is a bug fix. The traction demand above is scaled by the supply, so
            // a train with no catenary draws nothing - but regeneration was not, so a
            // train braking through a neutral section, or braking into a station on an
            // unelectrified stretch, still reported feeding power back into a wire it
            // was not connected to. Reported exactly that way: wire live mid-route,
            // dead at the platform, and braking for the stop still showed regen. A
            // generator with no circuit recovers nothing.
            var trainCfg = CEEConfigs.server().trainValues;
            double regenPower = 0d;
            if (powerScale > 0d && trainData.braking
                    && trainCfg.electricTrainRegenerativeBraking.get()) {
                // Only the MOTOR brake's share can come back. The pads dissipate
                // their energy as heat and recover nothing, so using the total
                // braking rate here would claim recovery from friction. The gear law
                // reports the share it actually applied, which is the only place
                // that knows: it depends on the gear, the gradient and what the
                // supply could deliver. The electric brake's own low-speed fade is
                // already inside that figure; it is not applied again here, or the
                // two would multiply and recovery would collapse far sooner than a
                // real train's.
                regenPower = TrainTractionModel.regenerativePower(
                        trainSpeedMs, trainData.gearStep.dynamicBrake, carriages,
                        trainCfg.electricTrainRegenerativeFraction.get());
            }
            trainData.regenPower = regenPower;

            if (regenPower > 0d) {
                // A generator, not a load: the motors push power back up the
                // pantograph. Modelled the same way the mod's own alternator is -
                // a Norton source whose internal resistance is the max-power-
                // transfer value V^2/(4P) and whose source current is V/internal -
                // so the line sees a source behind an impedance rather than an
                // ideal one, and it cannot deliver more than the motors recover.
                double lineVoltage = Math.abs(lastVoltage);
                double internal = (lineVoltage * lineVoltage) / (4d * regenPower);
                if (Double.isFinite(internal) && internal > 0d) {
                    builder.connect(trainNode, groundNode,
                            new NortonProperties(internal, lineVoltage / internal));
                    // The auxiliaries still draw while braking, so they go back in
                    // as an ordinary load; only the traction side is a source.
                    double aux = TrainTractionModel.auxiliaryPower(carriages);
                    double auxResistance = lineVoltage * lineVoltage / aux;
                    if (Double.isFinite(auxResistance) && auxResistance > 0d)
                        builder.connect(groundNode, trainNode,
                                ElectricalProperties.resistor(auxResistance));
                } else {
                    regenPower = 0d;
                }
                // Reported as a negative draw, which is what the HUD and the
                // on-train ammeters should show while the line is being fed.
                trainData.displayPower = -regenPower;
            } else {
                trainData.regenPower = 0d;
                trainData.displayPower = electricalPower;
                double trainResistance = lastVoltage * lastVoltage / electricalPower;
                builder.connect(groundNode, trainNode, ElectricalProperties.resistor(trainResistance));
            }

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

            // Only finish a train whose nodes this level's solve actually contains.
            // The results passed in belong to one level, so a train last connected in
            // another dimension has no nodes in them: getVoltageAt returns 0, the
            // train is written as unpowered with maxSpeed 0 and zero available
            // acceleration, and its gradient is reset. The server ticks every level
            // in turn, so the last level decides the answer for every train in the
            // world - a single dangling wire in a second dimension was enough to
            // stop every electric train in the overworld.
            //
            // The test is deliberately "connected somewhere else", not "not connected
            // here": connectedWireState is only ever assigned on pantograph contact,
            // so it stays null for an accumulator-only train. Skipping on null would
            // stop such a train from ever discharging, which is a regression the
            // guard must not introduce.
            if (trainData.connectedWireState != null
                    && trainData.connectedWireState != levelWireSimulationState)
                continue;

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

            // (hasTractionMotors was decided at the TOP of this method, before the early returns -
            // see the comment there. It is a structural fact about the consist and must not depend
            // on whether anything is currently connected.)

            // Whether anything is actually collecting current right now: a pantograph the driver has
            // raised AND which is within reach of catenary. Recomputed every solve, because entering
            // a neutral section makes it false without the driver doing anything.
            //
            // Deliberately not also requiring `node != null`: reaching that point in the loop above
            // is what creates the node, so a raised pantograph over live wire always has one, and
            // adding the test here would only introduce a way for the two to disagree.
            boolean workingPantograph = false;
            for (TrainPantographEntry pe : trainData.pantographs) {
                if (pe.active && pe.reachable) {
                    workingPantograph = true;
                    break;
                }
            }
            trainData.hasWorkingPantograph = workingPantograph;

            // Which control scheme drives this train, and the handover between them. See
            // TrainControlModeLogic for why the change is not immediate at speed. That method also
            // owns the driver-state edges, so the two records of this fact cannot disagree.
            //
            // `active` is passed rather than read back from trainData.isPowered, because that field
            // is only assigned further down this method - using it here would decide the mode from
            // the previous tick's supply.
            //
            // A rail-shoe-only train is reported as CREATE, unconditionally. That is not a hack to
            // suppress the panel: CREATE is exactly the state "Create's controls are in charge and
            // nothing of this mod's driving machinery applies", which is the operator's ruling for
            // these trains. Reporting it here means every consumer - the server's gear law and
            // maxSpeed, and the client's panel and key handling - agrees without each having to learn
            // a new special case, and it keeps the two sides from ever disagreeing about it.
            //
            // Asks the predicate rather than a local variable, for a reason found the hard way: this
            // block is OUTSIDE the per-train loop, so a variable declared inside that loop is not in
            // scope here, and the first version of this code did not compile on CI. The predicate is
            // a pure function of the train's own data, so calling it is both simpler and immune to
            // that mistake.
            if (trainData.railShoeOnly())
                trainData.controlMode = TrainControlMode.CREATE;
            else
                TrainControlModeLogic.update(trainData, train.speed, active);

            for (Map.Entry<Integer, Vec3> ce : positions.entrySet()) {
                Integer carriageID = ce.getKey();
                Vec3 pos = ce.getValue();

                if (pos == null)
                    continue;

                CatnipServices.NETWORK.sendToClientsAround(level, pos,
                        100, new UpdateElectricTrainSoundPacket(train.id, carriageID, (float) trainSpeed, acceleration, active, CEERegistries.ELECTRIC_TRAIN_SOUND_TYPE.getId(trainExtension.getSoundType())));
            }
            trainData.isPowered = active;

            // An electric train does not burn fuel, and this is the only thing that
            // says so. Create's burnFuel() consumes from carriage storage whenever
            // fuelTicks reaches zero, and its fuel storage is every exposed inventory
            // on the consist - MountedStorageManager.canUseForFuel accepts any
            // storage that is not on the blacklist - so merely not being a fuel
            // train is not enough: coal sitting in an on-board chest would be eaten
            // one item at a time, silently, and other mods' fuel handlers would run
            // with it. Keeping the counter above zero makes burnFuel take its early
            // return.
            //
            // Gated on the consist carrying electric motors rather than on being
            // powered. The baseline pinned it only while active, which still let a
            // stationary electric train with a flat accumulator and no catenary eat
            // its cargo; an electric train has no business consuming fuel in any
            // state, and this can only ever destroy fewer items than before.
            if (motorCars > 0 && train.fuelTicks <= 1)
                train.fuelTicks = 10;

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
            // Published for the gear law, which runs on the train's own tick. This
            // is necessarily last tick's terminal voltage, the same one-tick lag
            // the load resistance itself already has.
            trainData.powerScale = powerScale;

            // Cruise's own verdict for this tick, for the display: whether it is
            // holding, running out of power on a climb, or braking down a descent.
            trainData.cruiseState = TrainTractionModel.cruiseState(
                    trainSpeedMs, grade, carriages, powerScale);

            // NOTE: the driver state timers (vigilance countdown, emergency brake
            // release, penalty clearing) are deliberately NOT advanced here. This
            // method runs once per LEVEL per tick, so a train whose
            // connectedWireState is null - an accumulator-only set - passes the
            // dimension guard above in every level and would have its timers
            // stepped once per dimension. A 30-second vigilance interval would then
            // fire in 10 seconds in a three-dimension world. They live in
            // GameEvents.serverTickEvent instead, which runs exactly once per tick.
            TrainDriverState driver = trainData.driver;

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

            // Sent for every train, fuel ones included, because this packet is also
            // what feeds the train-mounted voltmeters and ammeters - a gauge on a
            // carriage should read the line whether or not that consist has traction
            // motors. The lever fields it now carries are simply ignored by the
            // client for a train without motors; see TrainControlHud, which gates on
            // the motor count rather than on this being an electric train.
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
                                                .electricTrainManualFullSpeed.get(),
                                        driver.gear.ordinal(), driver.warningStage() > 0,
                                        driver.warningStage(),
                                        driver.emergencyArmed, driver.emergencyPenalty,
                                        trainData.cruiseState.ordinal(),
                                        trainData.regenPower > 0d,
                                        driver.autoArrive, driver.unmanned,
                                        (float) TrainTractionModel.designMaxSpeed(),
                                        trainData.controlMode.ordinal())
                        );
                        trainData.lastSyncedVoltage = voltage;
                        trainData.lastSyncedCurrent = totalCurrent;
                        trainData.ticksSinceGaugeSync = 0;
                    }
                }
            }

            // Pantograph list, to the driver only.
            //
            // Kept out of the broadcast above for two reasons: the list is only actionable by the
            // person at the controls, and it carries `reachable`, which changes as the train moves
            // and therefore cannot be sent on change alone without going stale. Addressing one
            // player keeps the traffic a small packet every few ticks for the only person who can
            // act on it, and nothing at all is sent while nobody is driving.
            //
            // Ride on the same throttle as the gauge sync: the driver's panel reads both together,
            // so refreshing them at different rates would only make the two disagree on screen.
            if (shouldSync && driver.isDriven())
                syncPantographs(train, trainData, driver);

        }
    }

    /**
     * Send the driver the pantograph list and the control-lock state, if it is worth sending.
     *
     * <h2>Why this is its own method, called from two places</h2>
     *
     * <p>This has to run on the "nothing is connected" path as well as the powered one. That path
     * returns early because there is no traction to compute, but a train with every pantograph down
     * is exactly the case the lock message exists for: the driver has just finished an emergency
     * stop on friction, their whole panel is inert, and without this packet they would be shown an
     * unlocked cab and press keys that do nothing.
     *
     * <h2>Throttling</h2>
     *
     * <p>A lock state CHANGE is sent immediately, because the driver is about to press something and
     * a five-tick delay is long enough to press it and conclude the cab is broken. Otherwise it is
     * a keep-alive every {@link #PANTOGRAPH_SYNC_INTERVAL} ticks, which is what keeps the reach
     * flags current as the train moves under the wire.
     *
     * <p>Sent to the DRIVER ONLY, never broadcast: it is actionable by one person, and the reach
     * flags change as the train moves, so a per-player packet a few times a second is far cheaper
     * than keeping every nearby client's copy fresh. Nothing is sent while nobody is driving.
     */
    private void syncPantographs(Train train, ElectricTrainData trainData, TrainDriverState driver) {
        trainData.ticksSincePantographSync++;
        boolean lockChanged = trainData.lastSyncedControlsLocked != driver.controlsLocked;
        // The header flag is now derived from the arms rather than stored on the train: automatic
        // raise/lower is per arm, and a single train-wide boolean could only ever be a summary of them.
        boolean anyAuto = false;
        for (Carriage c : train.carriages) {
            if (!(c instanceof IPantographList l))
                continue;
            for (TrainPantographEntry pe : l.electroEnergetics$getPantographList()) {
                if (pe.autoRaise) { anyAuto = true; break; }
            }
            if (anyAuto) break;
        }
        // The switch state is sent on change for the same reason the lock is: the driver pressed a key
        // and is looking at the panel. Waiting for the five-tick keep-alive would leave the indicator
        // disagreeing with the key for a quarter of a second, which reads as the key not having worked.
        boolean autoRaiseChanged =
                trainData.lastSyncedAutoRaisePantographs != anyAuto;
        if (!lockChanged && !autoRaiseChanged
                && trainData.ticksSincePantographSync < PANTOGRAPH_SYNC_INTERVAL)
            return;

        // Walked from the level rather than the server's player list: the driver is by definition
        // in the level this solve belongs to, and it avoids assuming anything about the server
        // object here.
        ServerPlayer driverPlayer = null;
        for (ServerPlayer candidate : level.players()) {
            if (candidate.getUUID().equals(driver.driverId)) {
                driverPlayer = candidate;
                break;
            }
        }
        if (driverPlayer == null)
            return;

        List<SyncTrainPantographsPacket.Entry> entries = new ArrayList<>();
        for (int i = 0; i < train.carriages.size(); i++) {
            Carriage carriage = train.carriages.get(i);
            if (!(carriage instanceof IPantographList list))
                continue;
            for (TrainPantographEntry pe : list.electroEnergetics$getPantographList()) {
                entries.add(new SyncTrainPantographsPacket.Entry(
                        i, pe.originalPos, pe.active, pe.reachable,
                        pe.type == CEEPantographTypes.RAIL_CONTACT_SHOE.get(), pe.autoRaise));
            }
        }

        CatnipServices.NETWORK.sendToClient(driverPlayer,
                new SyncTrainPantographsPacket(train.id, driver.controlsLocked, anyAuto, entries));

        trainData.ticksSincePantographSync = 0;
        trainData.lastSyncedControlsLocked = driver.controlsLocked;
        trainData.lastSyncedAutoRaisePantographs = anyAuto;
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
