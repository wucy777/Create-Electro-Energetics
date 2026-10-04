package com.george_vi.electroenergetics.simulation.infrastructure;

import com.george_vi.electroenergetics.config.CEEConfigs;
import com.george_vi.electroenergetics.content.wire.SendPositionedWireParticlesPacket;
import com.george_vi.electroenergetics.content.wire.SendWireParticlesPacket;
import com.george_vi.electroenergetics.foundation.nodes.AttachedNode;
import com.george_vi.electroenergetics.foundation.nodes.InWorldNodeConnection;
import com.george_vi.electroenergetics.foundation.nodes.Node;
import com.george_vi.electroenergetics.simulation.SimulationResults;
import com.george_vi.electroenergetics.simulation.WireType;
import net.createmod.catnip.math.VecHelper;
import net.createmod.catnip.platform.CatnipServices;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;

public class WireLifetimeModule {

    /**
     * Purely numeric ceiling on the current fed to the temperature integrator, to
     * keep the float arithmetic finite. It is not an electrical rating and no wire
     * is defined in terms of it; it is far above any current a circuit here
     * produces, so it never affects real behaviour.
     */
    private static final double HEATING_ARITHMETIC_GUARD = 1e6;

    final InfrastructureSavedData sd;
    final ServerLevel level;
    final WireSimulationState wireSimulationState;

    public WireLifetimeModule(InfrastructureSavedData sd, ServerLevel level, WireSimulationState wireSimulationState) {
        this.sd = sd;
        this.level = level;
        this.wireSimulationState = wireSimulationState;
    }

    public void finishSimulation(SimulationResults results) {
        if (!CEEConfigs.server().wiresBreak.get())
            return;

        InWorldNodeConnection longestWireToBreak = null;
        WireData longestWireDataToBreak = null;
        boolean isCatenary = false;
        for (Map.Entry<InWorldNodeConnection, ConnectionEntry> e : wireSimulationState.getAllConnections()) {
            InWorldNodeConnection connection = e.getKey();
            ConnectionEntry connectionData = e.getValue();
            WireType wireType = connectionData.wireData.wireType();
            List<WireSimulationState.CutWireEntry> cuts = connectionData.cuts;

            double current = 0;
            double wholeWireResistance = connectionData.resistance * connectionData.wireData.length;
            if (cuts == null || cuts.isEmpty()) {
                double vd = connectionData.getVoltageOnWire(results, connection.node1(), connection.node2());
                current = wholeWireResistance <= 0 ? 0 : Math.abs(vd) / wholeWireResistance;
            } else {
                float prevPoint = 0;
                Node prevNode = connection.node1();
                for (WireSimulationState.CutWireEntry cut : cuts) {
                    float point = cut.point();
                    if (point - prevPoint < 0.01)
                        continue;
                    AttachedNode node = cut.node();
                    float dist = point - prevPoint;
                    prevPoint = point;
                    double vd = Math.abs(results.getVoltageAt(prevNode, node));
                    prevNode = node;
                    // The floor must match the one the solver applied. Cut spans are
                    // built by WireAssemblerModule, which clamps each segment to at
                    // least CUT_SEGMENT_MIN_RESISTANCE ohm; dividing by the raw
                    // R*length*dist instead over-reads the current by
                    // floor/(R*length*dist) wherever the clamp engaged. With the
                    // current wire figures that is not a corner case: an iron rail
                    // span is R*length = 7.23e-5 * 8 = 5.8e-4 ohm, below the floor
                    // for its whole length, so the heater read up to 1.7x the real
                    // current at full span and far more on short segments - a rail
                    // rated 1000 A fused at a few hundred, and smoked for it.
                    //
                    // For a single cut this makes the heater exactly equal to the
                    // solver: progress and dist are both the distance from node1.
                    // With several cuts the assembler's own accounting differs (it
                    // measures every segment from node1 rather than from the
                    // previous cut), which is upstream behaviour and not something
                    // this floor can correct.
                    //
                    // It also removes the division-by-zero that the uncut branch
                    // above already guards against: the clamped divisor is >= 1e-3.
                    current = Math.max(current, vd / Math.max(
                            WireAssemblerModule.CUT_SEGMENT_MIN_RESISTANCE,
                            wholeWireResistance * dist));
                }
            }

            // Fetched once: getMaxTemperature() goes through a DoubleSupplier, and it
            // is needed three times below (cap, smoke threshold, break test).
            double meltingPoint = wireType.getMaxTemperature();

            float temp = connectionData.wireData.temperature;
            // The heater sees the current as it really is, so an overload burns in
            // proportion to how far over the rating it is. Clamping this to the
            // rating would make the model blind above it, and every short circuit -
            // however violent - would take exactly as long to melt a wire as a
            // current sitting right on the limit.
            //
            // The guard below is only numeric: at absurd currents the heat addition
            // would overflow float and turn the temperature into NaN/infinity, which
            // would then persist forever. 1e6 A is far beyond anything a circuit in
            // this mod produces, so it never changes real behaviour.
            // Cooling and the self-limiting term, plus the heat the current adds. The
            // cooling constant is taken from WireType rather than written as its own
            // 33.3f literal, so that this integrator and the rating functions in
            // WireType - which divide by the same number to derive a rating from a
            // melting point - cannot disagree about it. They are float and double
            // respectively, so a duplicated literal would not cancel exactly.
            float coolingPerTick = (float) WireType.HEATING_COOLING_PER_TICK;
            float rawTemp = (float) Math.min(current, HEATING_ARITHMETIC_GUARD);
            rawTemp *= Math.min(temp < 0 ? 0 : 1 / (1 + (temp / 1000)), 1);
            rawTemp = Math.max(temp - coolingPerTick + rawTemp, 0);
            // A non-finite temperature would latch: every later tick would recompute
            // it as non-finite again and the wire would neither cool nor break.
            if (!Float.isFinite(rawTemp))
                rawTemp = 0;

            // A wire cannot get hotter than its melting point, so there is nothing to
            // gain by storing more - but the integrator will, and by orders of
            // magnitude. It settles at 1000*(I/33.3 - 1), so a dead short carrying tens
            // of kA drives the stored value into the hundreds of thousands in one tick.
            //
            // Storing that is what made smoke linger. Cooling is a flat 33.3/tick no
            // matter how hot the wire is, so one that fused while sitting at 100k kept
            // smoking for another ~113 s, and at the 1e6 A guard for ~24 minutes. It
            // also spread: only one wire is cut per break roll, so every other wire on
            // the faulted path held the same absurd temperature and smoked along with
            // it - which is why smoke appeared on spans far from the fault.
            //
            // Capping the stored value bounds the tail to a couple of seconds whatever
            // the fault. The extra 5% keeps the wire visibly over the line as it fails,
            // so it still smokes on the way out rather than going quiet the instant it
            // reaches the melting point.
            //
            // The cap cannot change WHEN a wire breaks: the break test below reads the
            // uncapped rawTemp, so every burn time is decided by the real current.
            //
            // An infinite melting point (glass insulators, bundle conductors) makes
            // Math.min a no-op, leaving those wires exactly as they were.
            float newTemp = (float) Math.min(rawTemp, meltingPoint * 1.05);

            connectionData.wireData.temperature = newTemp;

            // Smoke means "this wire is carrying more than it is rated for, and will
            // fail if that continues", so the test is an overload test on the current
            // itself rather than a temperature threshold. Going through the rating
            // keeps it exactly consistent with the tooltip and the break rule: all
            // three derive from the same maxTemperature.
            //
            // It used to be 0.85 * maxTemperature. That is not a fraction of the
            // rating but of the melting temperature, and the two are different scales:
            // the equilibrium 1000*(I/33.3 - 1) puts 0.85 * maxT at 855 A on a 1000 A
            // wire. Any line loaded from 855 A up therefore sat above the smoke
            // threshold forever without ever getting hot enough to break, so a wire
            // merely working near its rating streamed smoke indefinitely with nothing
            // wrong with it.
            //
            // Testing the current also means smoke starts the moment a wire is
            // overloaded (a useful warning while it heats up over the next minutes)
            // and stops as soon as it is not, instead of trailing off with the stored
            // heat.
            //
            // The tolerance rounds the rating up rather than comparing exactly. The
            // margin it covers is tiny: the integrator subtracts 33.3f while the
            // rating is derived from 33.3d, so the two do not cancel exactly and a
            // wire sitting exactly on its rating can land a hair either side of it.
            // Rounding up keeps a wire from smoking at precisely its rated current,
            // which is the bug being fixed here; the cost is that it fails at
            // 1.00001x its rating instead of 1x, far below any gap a player could
            // aim at (the ratings are 415 and 1000 A).
            double ratedCurrent = WireType.ampacityForTemperature(meltingPoint) * (1d + 1e-5);
            if (current > ratedCurrent && level.isLoaded(connection.node1().sourcePos())) {
                // Smoke particles

                Vec3 wireCenter = VecHelper.lerp(0.5f, connection.node1().sourcePos().getCenter(), connection.node2().sourcePos().getCenter());
                if (connectionData.isCatenary) {
                    Vec3 pos1 = connection.node1().sourcePos().getBottomCenter();
                    Vec3 pos2 = connection.node2().sourcePos().getBottomCenter();
                    CatnipServices.NETWORK.sendToClientsAround(level, wireCenter,
                            connection.node1().sourcePos().getCenter().distanceTo(connection.node2().sourcePos().getCenter()) + 20, new SendPositionedWireParticlesPacket(pos1, pos2, ParticleTypes.SMOKE, 0f, 0.2f));
                    Vec3 topPos1 = pos1.add(0, 1.5, 0);
                    Vec3 topPos2 = pos2.add(0, 1.5, 0);
                    float distance = (float) topPos1.distanceTo(topPos2);
                    CatnipServices.NETWORK.sendToClientsAround(level, wireCenter,
                            connection.node1().sourcePos().getCenter().distanceTo(connection.node2().sourcePos().getCenter()) + 20, new SendPositionedWireParticlesPacket(topPos1, topPos2, ParticleTypes.SMOKE, 350f * (0.05f / distance), 0.2f));
                } else {
                    Vec3 pos1 = sd.getNodePosition(connection.node1());
                    Vec3 pos2 = sd.getNodePosition(connection.node2());
                    if (pos1 != null && pos2 != null) {
                        double distance = pos1.distanceTo(pos2);
                        CatnipServices.NETWORK.sendToClientsAround(level, wireCenter,
                                distance + 20, new SendWireParticlesPacket(connection.node1(), connection.node2(), ParticleTypes.SMOKE, connectionData.wireData.getSag(distance), 0.2f));
                    }
                }
            }

            // A wire fails when it is above its melting point AND still being
            // overloaded - not merely when its temperature is rising. The rising
            // test that used to be here left a wire permanently unfailable: the
            // stored value is capped at 1.05 * meltingPoint, and a replacement wire
            // inherits that temperature from the one that fused. From the cap, a
            // current only slightly over the rating produces an equilibrium that is
            // still above the melting point, so the wire *cools* towards it - the
            // temperature never rises, the old test never fired, and because smoke
            // only needs the current to be over the rating the wire streamed smoke
            // forever without ever failing. That is the "inexplicable smoke" this
            // change set exists to remove, so the test is now on the cause.
            //
            // Burn times are unchanged by this: a wire heated from cold crosses its
            // melting point on a rising tick, when both tests agree, and the
            // overload test is also what decides whether it can recover.
            if (rawTemp > meltingPoint && current > ratedCurrent) {
                if (longestWireToBreak == null) {
                    longestWireToBreak = connection;
                    longestWireDataToBreak = connectionData.wireData;
                    isCatenary = connectionData.isCatenary;

                }
                else if (longestWireDataToBreak.length < connectionData.wireData.length) {
                    longestWireToBreak = connection;
                    longestWireDataToBreak = connectionData.wireData;
                    isCatenary = connectionData.isCatenary;
                }
            }

        }

        if (longestWireToBreak != null && sd.level.random.nextFloat() > 0.96f) {
            if (isCatenary) {
                sd.removeCatenary(longestWireToBreak.node1().sourcePos(), longestWireToBreak.node2().sourcePos());
                return;
            }
            WireType replaceWith = longestWireDataToBreak.wireType().overheatedReplacement();
            WireData wireConnectionData = sd.removeConnection(longestWireToBreak);
            Vec3 pos1 = longestWireToBreak.node1().getPosition(level);
            Vec3 pos2 = longestWireToBreak.node2().getPosition(level);
            if (pos1 == null || pos2 == null)
                return;

            if (replaceWith == null) {
                CatnipServices.NETWORK.sendToClientsAround(level, VecHelper.lerp(0.5f,
                                longestWireToBreak.node1().sourcePos().getCenter(),
                                longestWireToBreak.node2().sourcePos().getCenter()),
                        longestWireToBreak.node1().sourcePos().getCenter().distanceTo(longestWireToBreak.node2().sourcePos().getCenter()) + 20,
                        new SendWireParticlesPacket(longestWireToBreak.node1(), longestWireToBreak.node2(),
                                ParticleTypes.BUBBLE_POP, longestWireDataToBreak.getSag(pos1.distanceTo(pos2)), 4));
            } else {
                sd.connect(longestWireToBreak.node1(), longestWireToBreak.node2(), new WireData(replaceWith, wireConnectionData.temperature(), wireConnectionData.attachments(), wireConnectionData.length));
                CatnipServices.NETWORK.sendToClientsAround(level, VecHelper.lerp(0.5f,
                                longestWireToBreak.node1().sourcePos().getCenter(),
                                longestWireToBreak.node2().sourcePos().getCenter()),
                        longestWireToBreak.node1().sourcePos().getCenter().distanceTo(longestWireToBreak.node2().sourcePos().getCenter()) + 20,
                        new SendWireParticlesPacket(longestWireToBreak.node1(), longestWireToBreak.node2(),
                                ParticleTypes.LARGE_SMOKE, longestWireDataToBreak.getSag(pos1.distanceTo(pos2)), 4));
            }
        }
    }
}
