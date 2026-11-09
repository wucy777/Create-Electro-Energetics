package com.george_vi.electroenergetics.simulation.simulator;

import com.george_vi.electroenergetics.CEESimulatedDeviceFeatureTypes;
import com.george_vi.electroenergetics.config.CEEConfigs;
import com.george_vi.electroenergetics.devices.device.DevicesSavedData;
import com.george_vi.electroenergetics.devices.device.SimulatedDevice;
import com.george_vi.electroenergetics.events.AddToElectricGraphEvent;
import com.george_vi.electroenergetics.events.FinishElectricSimulationEvent;
import com.george_vi.electroenergetics.foundation.device.TickingElectricalDevice;
import com.george_vi.electroenergetics.foundation.nodes.DirectionalNodeConnection;
import com.george_vi.electroenergetics.foundation.nodes.InWorldNode;
import com.george_vi.electroenergetics.simulation.*;
import com.george_vi.electroenergetics.simulation.electrical_properties.ElectricalProperties;
import com.george_vi.electroenergetics.simulation.electrical_properties.MicroTickingElectricalProperties;
import com.george_vi.electroenergetics.simulation.infrastructure.InfrastructureSavedData;
import com.george_vi.electroenergetics.simulation.util.DataPacker;
import com.george_vi.electroenergetics.simulation.util.SimulatorProfiler;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2DoubleMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import it.unimi.dsi.fastutil.objects.ObjectDoublePair;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.NeoForge;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class SimulationTicker {

    public static SimulatorProfiler profiler = new SimulatorProfiler();
    public static Map<Level, SimulationStats> allStats = new Object2ObjectArrayMap<>();

    public final ServerLevel level;
    public final InfrastructureSavedData sd;

    public SimulationResults lastResults;
    private CircuitBuilder circuitBuilder;
    private SimulationStats stats;
    public List<SimulatorProfiler.ResultEntry> lastProfilerResults;
    public SimulationStats lastStats;

    public int microTicks = 1;

    public Future<SimulationResults> future = null;

    public SimulationTicker(ServerLevel level, InfrastructureSavedData sd) {
        this.level = level;
        this.sd = sd;
    }

    private static ExecutorService electricalExecutorService;

    public static void runServer() {
        electricalExecutorService = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r);
            t.setName("CEE-Electrical-Simulator");
            t.setDaemon(true);
            return t;
        });
    }

    public static void stopServer() {
        electricalExecutorService.shutdownNow();
    }

    /**
     * Ticks skipped so the simulation runs at WALL-CLOCK rate rather than once per server tick.
     *
     * <p>The electrical solve advances by {@link #microTicks} fixed steps per call, and those steps are sized for
     * twenty ticks a second. Under {@code /tick rate 500} the server therefore ran the solve twenty-five times as
     * often, which does not make the grid simulate better - it makes every device in the world consume twenty-five
     * times the energy per second, because each solve still bills a full tick's worth. The operator predicted
     * exactly this: raise the rate and the grid collapses.
     *
     * <p>The solve is skipped instead, at the ratio of the live rate to twenty. At twenty the divisor is one and
     * nothing changes; at five hundred the solve runs every twenty-fifth tick and the grid sees the same number of
     * solves per second it always did.
     *
     * <p>Measured through the train tick's own clock ({@code RiderSeatGate.currentTickRate}) rather than asked of
     * the server, because this version has no server-side accessor for it - see that method.
     */
    private int skipCounter = 0;

    public void tick() {
        if (level.tickRateManager().isFrozen())
            return;

        // Wall-clock pacing. The remainder is carried in the counter rather than recomputed, so twenty-five skips
        // do not accumulate into a drift against the rate they were derived from.
        float liveRate = com.george_vi.electroenergetics.content.railway_electrification.RiderSeatGate
                .currentTickRate();
        if (liveRate > 21f) {
            int stride = Math.max(1, Math.round(liveRate / 20f));
            if (++skipCounter < stride)
                return;
            skipCounter = 0;
        } else {
            skipCounter = 0;
        }
        microTicks = CEEConfigs.server().simulationConfig.microTicks.get();

        profiler.push(level.dimension().location().toString());
        // Setup
        profiler.push("setupNodes");

        Set<InWorldNode> inWorldNodes = sd.getNodes();
        DevicesSavedData deviceSD = DevicesSavedData.load(level);
        Collection<SimulatedDevice> devices = deviceSD.getDevices(CEESimulatedDeviceFeatureTypes.TICKING_ELECTRICAL.get());

        if (inWorldNodes.isEmpty() && devices.isEmpty()) {
            profiler.pop();
            profiler.pop();
            return;
        }

        circuitBuilder = sd.wireSimulationState.createCircuitBuilder();

        profiler.popPush("preTick");

        // PreTick
        BridgeCollector bridgeCollector = new BridgeCollector(circuitBuilder, sd, microTicks);
        for (SimulatedDevice device : devices)
            ((TickingElectricalDevice)device).preTick(bridgeCollector);

        if (inWorldNodes.isEmpty()) {
            profiler.pop();
            profiler.pop();
            return;
        }

        profiler.popPush("addToGraphEvent");

        NeoForge.EVENT_BUS.post(new AddToElectricGraphEvent(circuitBuilder, level, sd));

        profiler.pop();
        profiler.pop();

        long thrStart = System.nanoTime();

        // This is done to reduce performance overhead from creating connections on the main thread.
        // Some systems can define which connections to add, which will later be connected.
        // This moves wire creation away from the main thread and into the electrical thread.
        List<ObjectDoublePair<DirectionalNodeConnection>> wiresToJoin = new ArrayList<>(sd.wireSimulationState.getLazyConnections());

        stats = new SimulationStats();
        future = electricalExecutorService.submit(() -> {
            circuitBuilder.connectAll(wiresToJoin);
            List<List<SimulationNode>> networks = circuitBuilder.dfsAndGround();
            stats.totalNodes = circuitBuilder.allNodes().size();
            stats.totalSeparatedNodes = new int[networks.size()];
            stats.totalOptimizedNodes = new int[networks.size()];
            stats.totalDevices = devices.size();
            // Solve
            double[] allVoltages = new double[circuitBuilder.allNodes().size() * microTicks];
            Map<BlockPos, Object2DoubleMap<DirectionalNodeConnection>> sourceAmps = new HashMap<>();

            List<Network> allNetworks = new ArrayList<>(networks.size());
            int l = 0;
            for (List<SimulationNode> networkNodes : networks) {
                if (networkNodes.size() == 1)
                    continue;
                if (networkNodes.size() == 2) {
                    Iterator<SimulationNode> iterator = networkNodes.iterator();
                    SimulationNode node1 = iterator.next();
                    SimulationNode node2 = iterator.next();
                    ElectricalProperties properties = node1.adjacency.get(node2.ordinal);
                    if (properties.isSimpleResistor())
                        continue;
                }

                boolean foundSource = false;
                NodeLoop:
                for (SimulationNode node : networkNodes) {
                    for (ElectricalProperties properties : node.adjacency.values()) {
                        if (!properties.isSimpleResistor()) {
                            foundSource = true;
                            break NodeLoop;
                        }
                    }
                }

                if (!foundSource)
                    continue;

                Network network = new Network(networkNodes, circuitBuilder, sd);

                if (CEEConfigs.server().simulationConfig.optimizeGraph.get())
                    network.optimize();
                stats.totalSeparatedNodes[l] = networkNodes.size();
                stats.totalOptimizedNodes[l] = network.allNodes.size();
                l++;
                network.mapToSimNodes();
                allNetworks.add(network);
                stats.totalMicroTickers += network.simulationMicroTicked.size();
            }
            long solveStart = System.nanoTime();

            for (int i = 0; i < microTicks; i++) {
                for (Network network : allNetworks) {
                    for (Long2ObjectMap.Entry<ElectricalProperties> entry : network.originalMicroTicked.long2ObjectEntrySet()) {
                        int first = DataPacker.unpackFirstI(entry.getLongKey());
                        int second = DataPacker.unpackSecondI(entry.getLongKey());
                        if (entry.getValue() instanceof MicroTickingElectricalProperties properties) {
                            properties.tick(allVoltages, i, microTicks, first, second);
                        }
                    }
                }
                for (Network network : allNetworks) {
                    network.runSolver(allVoltages, i, microTicks);
                }
                for (Network network : allNetworks) {
                    for (Long2ObjectMap.Entry<ElectricalProperties> entry : network.originalMicroTicked.long2ObjectEntrySet()) {
                        int first = DataPacker.unpackFirstI(entry.getLongKey());
                        int second = DataPacker.unpackSecondI(entry.getLongKey());
                        if (entry.getValue() instanceof MicroTickingElectricalProperties properties) {
                            properties.afterTick(allVoltages, first, second, i, microTicks);
                        }
                    }
                }
            }

            profiler.addThreadedNanos(System.nanoTime() - thrStart);
            profiler.addSolverNanos(System.nanoTime() - solveStart);
            return new SimulationResults(allVoltages, microTicks, circuitBuilder, sd);
        });
    }

    public void endTick() {
        SimulationResults simulationResults = null;

        if (future != null) {
            try {
                simulationResults = future.get();
            } catch (InterruptedException | ExecutionException e) {
                InfrastructureSavedData.LOGGER.warn("Error while waiting for the electrical simulation to finish!", e);
                return;
            }
        }

        if (simulationResults == null)
            return;

        profiler.push(level.dimension().location().toString());

        profiler.push("postTick");
        DevicesSavedData deviceSD = DevicesSavedData.load(level);
        Collection<SimulatedDevice> devices = deviceSD.getDevices(CEESimulatedDeviceFeatureTypes.TICKING_ELECTRICAL.get());

        for (SimulatedDevice device : devices)
            ((TickingElectricalDevice)device).postTick(simulationResults);

        profiler.popPush("finish");
        
        sd.wireLifetimeModule.finishSimulation(simulationResults);

        NeoForge.EVENT_BUS.post(new FinishElectricSimulationEvent(simulationResults, level, sd));

        if (!circuitBuilder.allNodes().isEmpty())
            sd.setDirty();

        profiler.push("syncVoltages");
        VoltageSync.finishSimulation(sd, level, simulationResults);
        lastResults = simulationResults;
        lastStats = stats;
        lastProfilerResults = profiler.getResults();

        profiler.pop();
        profiler.pop();
        profiler.pop();

        SimulationTicker.allStats.put(level, stats);
        deviceSD.setDirty();
    }
}
