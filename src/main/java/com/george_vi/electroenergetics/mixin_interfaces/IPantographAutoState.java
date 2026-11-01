package com.george_vi.electroenergetics.mixin_interfaces;

import java.util.Map;

/**
 * The per-arm automatic raise switches, carried on the train so they survive a world reload.
 *
 * <p>The switch used to live only on {@code TrainPantographEntry}, which is built from the carriage when the
 * train loads - so every restart reset it. This is where it is kept instead, keyed by carriage and seat
 * position, and written to the train's own saved data.
 */
public interface IPantographAutoState {

    Map<String, Boolean> electroEnergetics$pantoAutoState();

    void electroEnergetics$setPantoAutoState(Map<String, Boolean> state);

    /** Key for one arm: the carriage index and the seat's local position. */
    static String key(int carriageIndex, net.minecraft.core.BlockPos localPos) {
        return carriageIndex + ":" + localPos.asLong();
    }
}