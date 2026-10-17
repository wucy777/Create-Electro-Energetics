package com.george_vi.electroenergetics.content.railway_electrification.pantograph;

import com.george_vi.electroenergetics.foundation.nodes.AttachedNode;
import com.george_vi.electroenergetics.simulation.infrastructure.ConnectionEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

public final class TrainPantographEntry {
    public final BlockPos originalPos;
    public final PantographType type;
    public final BlockPos rotatedPos;
    public boolean active;
    public boolean facingForward;

    /**
     * Whether this pantograph is within reach of catenary right now.
     *
     * <p>Recomputed from geometry every solve, and deliberately kept apart from {@link #active}:
     * that is the driver's handle, this is a fact about the world. The two must not be conflated,
     * because they answer different questions and one is used to validate the other.
     *
     * <ul>
     *   <li>Raising is only allowed while this is true - a driver cannot put a pantograph up onto
     *       nothing.</li>
     *   <li>Lowering is automatic as soon as this goes false while {@link #active} is true, which
     *       is what happens when the train runs out from under the wire.</li>
     * </ul>
     *
     * <p>Server-side and not persisted: it describes the world at this instant, so a saved value
     * would be meaningless on load.
     */
    public boolean reachable;
    public AttachedNode node;
    public Vec3 pos = null;
    public Vec3 prevPos = null;
    public double lastCurrent;
    public ConnectionEntry onConnection;

    public TrainPantographEntry(BlockPos originalPos, BlockPos rotatedPos, PantographType type, boolean active, boolean facingForward) {
        this.originalPos = originalPos;
        this.rotatedPos = rotatedPos;
        this.type = type;
        this.active = active;
        this.facingForward = facingForward;
    }
}
