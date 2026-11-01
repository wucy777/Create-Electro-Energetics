package com.george_vi.electroenergetics.mixins;

import com.george_vi.electroenergetics.mixin_interfaces.IPantographAutoState;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.DimensionPalette;
import com.simibubi.create.content.trains.graph.TrackGraph;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Saves the per-arm automatic raise switches with the train.
 *
 * <p>The switch used to live only on the entry objects built from the carriage when the train loads, so every
 * restart reset it. It is kept in the train's own tag instead: a train copied, migrated or moved between
 * dimensions takes its settings with it, and deleting the train deletes them.
 */
@Mixin(value = Train.class, remap = false)
public class TrainPantographAutoStateMixin implements IPantographAutoState {

    @Unique
    private static final String electroEnergetics$TAG = "CEEPantographAutoRaise";

    @Unique
    private Map<String, Boolean> electroEnergetics$pantoAuto = new HashMap<>();

    @Override
    public Map<String, Boolean> electroEnergetics$pantoAutoState() {
        return electroEnergetics$pantoAuto;
    }

    @Override
    public void electroEnergetics$setPantoAutoState(Map<String, Boolean> state) {
        electroEnergetics$pantoAuto = state == null ? new HashMap<>() : state;
    }

    @Inject(method = "write", at = @At("RETURN"), remap = false)
    private void electroEnergetics$savePantoAuto(CallbackInfoReturnable<CompoundTag> cir) {
        try {
            CompoundTag out = cir.getReturnValue();
            if (out == null || electroEnergetics$pantoAuto.isEmpty())
                return;
            ListTag list = new ListTag();
            for (var entry : electroEnergetics$pantoAuto.entrySet()) {
                CompoundTag one = new CompoundTag();
                one.putString("K", entry.getKey());
                one.putBoolean("V", entry.getValue());
                list.add(one);
            }
            out.put(electroEnergetics$TAG, list);
        } catch (Throwable ignored) {
            // Losing the switches is better than losing the train.
        }
    }

    @Inject(method = "read", at = @At("RETURN"), remap = false)
    private static void electroEnergetics$loadPantoAuto(CompoundTag tag, HolderLookup.Provider registries,
                                                        Map<UUID, TrackGraph> graphs, DimensionPalette palette,
                                                        CallbackInfoReturnable<Train> cir) {
        try {
            Train train = cir.getReturnValue();
            if (train == null || tag == null || !tag.contains(electroEnergetics$TAG))
                return;
            Map<String, Boolean> loaded = new HashMap<>();
            ListTag list = tag.getList(electroEnergetics$TAG, 10);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag one = list.getCompound(i);
                loaded.put(one.getString("K"), one.getBoolean("V"));
            }
            ((IPantographAutoState) train).electroEnergetics$setPantoAutoState(loaded);
        } catch (Throwable ignored) {
        }
    }
}