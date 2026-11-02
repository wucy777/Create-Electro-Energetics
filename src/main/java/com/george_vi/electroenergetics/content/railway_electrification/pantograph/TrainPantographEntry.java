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

    /**
     * THE PER-ARM AUTOMATIC-WORKING SWITCH. On (default) the arm follows the wire; off the arm is
     * parked - forced down and kept down, and its manual raise is refused.
     *
     * <p>On: exactly the original mod's behaviour - raised while catenary is in reach, lowered when it
     * is lost. The geometry decides, so there is no manual raise to compete with it.
     *
     * <p>Off: "I do not want this arm working." {@code CatenaryModule} forces the arm down on every
     * solve, drops its wire cut, and leaves {@code reachable} false; {@code SetPantographPacket}
     * refuses a manual raise for it as well. This is not a manual-control mode: it is a park switch.
     *
     * <p>The state is saved per arm on the train (see {@code IPantographAutoState}) and re-applied in
     * {@code CatenaryModule.buildCircuit} before the first solve, so a parked arm stays parked across
     * a reload, a dimension change or a world restart - including when nobody is driving and the
     * driver-only sync never runs.
     */
    public boolean autoRaise = true;
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
