package com.george_vi.electroenergetics.content.railway_electrification.sound_effects;

import com.george_vi.electroenergetics.content.railway_electrification.sound_effects.sound_types.ElectricTrainSoundBehaviour;
import com.simibubi.create.CreateClient;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.Train;
import net.createmod.catnip.data.Pair;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class ElectricTrainSounds {
    public static Map<Pair<UUID, Integer>, ElectricTrainSoundEntry> soundProperties = new HashMap<>();

    @OnlyIn(Dist.CLIENT)
    static Map<Pair<UUID, Integer>, ElectricTrainSoundBehaviour> sounds = new HashMap<>();

    @OnlyIn(Dist.CLIENT)
    public static void tick() {
        for (Map.Entry<Pair<UUID, Integer>, ElectricTrainSoundEntry> e : Set.copyOf(soundProperties.entrySet())) {
            Pair<UUID, Integer> carriagePair = e.getKey();
            UUID trainID = carriagePair.getFirst();
            int carriageID = carriagePair.getSecond();
            Train train = CreateClient.RAILWAYS.trains.get(trainID);
            if (train == null) {
                sounds.remove(carriagePair);
                soundProperties.remove(carriagePair);
                continue;
            }
            ElectricTrainSoundBehaviour soundBehaviour = sounds.get(carriagePair);
            if (soundBehaviour == null)
                sounds.put(carriagePair, soundBehaviour = e.getValue().type().soundBehaviour.get());

            // The sound engine was written against CREATE's speed range (top speed 1.5 Blocks/tick):
            // the phase table, pitch lerps and wind curves all normalize against it. An electric consist
            // runs to its own design ceiling (up to 5 Blocks/tick), and feeding that raw put every curve
            // outside its domain - the phase table froze at P1 past ~19 m/s while the wind and ASYNC
            // branches extrapolated past their stoppers, which is the "sound varies abnormally with
            // speed" report. Scaling the INPUT to the engine's design domain restores the original
            // author's curves across the whole electric range: the phases now traverse 0 -> top speed
            // exactly as the diagram in the phase enum says, and every sound type (Modern/DC/IGBT)
            // inherits the fix because they all consume these two fields.
            float createTopBpt = com.simibubi.create.infrastructure.config.AllConfigs.server()
                    .trains.poweredTrainTopSpeed.getF() / 20f;
            float electricTopBpt = com.george_vi.electroenergetics.content.railway_electrification.TrainHudData
                    .maxSpeedFor(trainID) / 20f;
            float soundScale = 1f;
            if (electricTopBpt > createTopBpt)
                soundScale = createTopBpt / electricTopBpt;
            soundBehaviour.trainSpeed = e.getValue().speed() * soundScale;
            soundBehaviour.acceleration = e.getValue().acceleration() * soundScale;
            soundBehaviour.pos = null;
            if (train.carriages.size() >= Math.abs(carriageID)) {
                Carriage.DimensionalCarriageEntity dimensional = train.carriages.get(Math.abs(carriageID) - 1).getDimensional(Minecraft.getInstance().level);
                Vec3 leading = dimensional.leadingAnchor();
                Vec3 trailing = dimensional.trailingAnchor();
                if (carriageID > 0) {
                    if (leading != null)
                        soundBehaviour.pos = leading;
                } else if (trailing != null)
                    soundBehaviour.pos = trailing;
            }


            if (e.getValue().ticks() > 0) {
                if (soundBehaviour.pos != null)
                    soundBehaviour.tick();
                soundProperties.replace(carriagePair, new ElectricTrainSoundEntry(e.getValue().speed(), e.getValue().acceleration(), e.getValue().active(), e.getValue().ticks() - 1, e.getValue().type()));
            } else
                soundProperties.remove(carriagePair);
        }
    }
}
