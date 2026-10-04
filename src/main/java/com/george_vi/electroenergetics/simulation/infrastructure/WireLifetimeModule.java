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
                    current = Math.max(current, vd / (wholeWireResistance * dist));
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
            float rawTemp = (float) Math.min(current, HEATING_ARITHMETIC_GUARD);
            rawTemp *= Math.min(temp < 0 ? 0 : 1 / (1 + (temp / 1000)), 1);
            rawTemp = Math.max(temp - 33.3f + rawTemp, 0);
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
            // uncapped rawTemp, so every burn time is decided by the real current. It
            // must read rawTemp for a second reason too - a value pinned at the cap
            // would compare equal to itself, so 'increase' would read false forever and
            // a hard short could never break its wire at all.
            //
            // An infinite melting point (glass insulators, bundle conductors) makes
            // Math.min a no-op, leaving those wires exactly as they were.
            float newTemp = (float) Math.min(rawTemp, meltingPoint * 1.05);
            boolean increase = rawTemp > temp;

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
            // The tolerance matters, and it must round the rating UP. maxTemperature
            // lives in a float field, and the round trip through it loses enough
            // precision that a 1000 A wire reads back as 999.999976 A; comparing
            // strictly would then make every wire smoke at exactly its rated current,
            // which is the very bug being fixed. Rounding up by one part in 10^5 costs
            // nothing measurable - it is far below any threshold a player could aim at
            // and far below the gaps between the ratings (415 to 1000 A).
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

            if (rawTemp > meltingPoint && increase) {
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
