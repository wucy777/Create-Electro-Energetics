package com.george_vi.electroenergetics.mixins;

import com.george_vi.electroenergetics.CEERegistries;
import com.george_vi.electroenergetics.CEETags;
import com.george_vi.electroenergetics.content.railway_electrification.pantograph.IPantographBlock;
import com.george_vi.electroenergetics.content.railway_electrification.pantograph.PantographBlock;
import com.george_vi.electroenergetics.content.railway_electrification.pantograph.TrainPantographEntry;
import com.george_vi.electroenergetics.content.railway_electrification.sound_effects.TrainSoundModifier;
import com.george_vi.electroenergetics.content.railway_electrification.sound_effects.sound_types.ElectricTrainSoundType;
import com.george_vi.electroenergetics.mixin_interfaces.IPantographList;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.trains.entity.CarriageContraption;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Mixin(CarriageContraption.class)
public abstract class CarriageContraptionMixin extends Contraption implements IPantographList {
    @Unique
    public List<TrainPantographEntry> electroEnergetics$pantographs = new ArrayList<>();

    @Unique
    public Set<TrainSoundModifier> electroEnergetics$soundModifyingBlocks = new HashSet<>();

    @Unique
    public boolean electroEnergetics$sidewaysPantograph = false;

    @Unique
    public boolean electroEnergetics$hasMotor = false;

    @Unique
    public boolean electroEnergetics$hasCreativeSource = false;

    @Unique
    int electroenergetics$accumulators = 0;

    @Override
    public boolean electroEnergetics$hasCreativeElectricalSource() {
        return electroEnergetics$hasCreativeSource;
    }

    @Accessor("assemblyDirection")
    abstract Direction electroEnergetics$getAssemblyDirection();

    @Override
    public int electroEnergetics$getAccumulators() {
        return electroenergetics$accumulators;
    }

    @Override
    public void electroEnergetics$setAccumulators(int value) {
        electroenergetics$accumulators = value;
    }

    @Inject(method = "capture", at=@At("TAIL"), remap = false)
    public void electroEnergetics$capture(Level level, BlockPos pos, CallbackInfoReturnable<oshi.util.tuples.Pair<StructureTemplate.StructureBlockInfo, BlockEntity>> cir) {
        BlockState state = level.getBlockState(pos);

        if (state.getBlock() instanceof IPantographBlock pb) {

            Direction facing = state.getValue(PantographBlock.FACING);
            Direction assemblyDirection = this.electroEnergetics$getAssemblyDirection();
            if (pb.isSidewaysPantograph() == (facing.getAxis() == assemblyDirection.getAxis()))
                electroEnergetics$sidewaysPantograph = true;
            else {
                // Starts DOWN, because a train's pantographs only ever move when the driver moves them.
                //
                // The operator settled this twice, and the second answer supersedes the first. The
                // earlier question was answered "default up (=true, keep as is)" while thinking of a
                // pantograph as a BLOCK; the correction is that a train's pantograph is not that:
                //
                //   PLACED BLOCK        PantographBlockEntity.extended defaults to true and nothing
                //                       ever lowers it. The block has no controls of its own - an
                //                       unassembled train has no cab either - so it is always "up" and
                //                       draws no power, because a loose block is not on a circuit in
                //                       any way that matters. That behaviour is upstream's and is
                //                       deliberately untouched here.
                //
                //   ASSEMBLED, ON A TRAIN  the same block becomes a TrainPantographEntry, driven by
                //                       the cab's lever and by nothing else. Automatic raising was
                //                       removed at the operator's request, so the ONLY writer of
                //                       `active = true` in the whole mod is the driver's packet.
                //
                // A default of `true` would therefore be one remaining way for a pantograph to be up
                // without the driver having put it there - and it would be inconsistent with the very
                // mechanism the operator asked for: assembling a train under wire would start it
                // collecting current, assembling it a block away would not. "Whether or not the train
                // is being assembled" is the operator's own wording: the entity state has no automatic
                // raising, so it must not have an automatic initial raise either.
                //
                // Nothing is lost operationally: a train assembled under live wire starts with its
                // pantographs down and the driver presses the raise key once, which is exactly the
                // handle this feature exists to give them.
                //
                // This is only the ASSEMBLY default. A carriage loaded from NBT takes its value from
                // the saved "Active" tag (CarriageMixin.electroEnergetics$read), which is the
                // driver's own state and is deliberately left alone - a world reload must not fold
                // every raised pantograph.
                electroEnergetics$pantographs.add(new TrainPantographEntry(toLocalPos(pos), toLocalPos(pos).rotate(
                        assemblyDirection == Direction.NORTH ? Rotation.COUNTERCLOCKWISE_90 :
                        assemblyDirection == Direction.EAST ? Rotation.CLOCKWISE_180 :
                        assemblyDirection == Direction.SOUTH ? Rotation.CLOCKWISE_90 : Rotation.NONE
                ), pb.getPantographType(state), false, facing == assemblyDirection));
            }
        }
        if (state.is(CEETags.TRAIN_ELECTRIC_MOTOR))
            electroEnergetics$hasMotor = true;
        if (state.is(CEETags.TRAIN_ACCUMULATOR))
            electroenergetics$accumulators++;
        if (state.is(CEETags.TRAIN_CREATIVE_SOURCE))
            electroEnergetics$hasCreativeSource = true;
        electroEnergetics$addSMBlock(state.getBlock(), electroEnergetics$soundModifyingBlocks);
    }


    @Unique
    private static void electroEnergetics$addSMBlock(Block block, Set<TrainSoundModifier> smBlocks) {
        for (ElectricTrainSoundType soundType : CEERegistries.ELECTRIC_TRAIN_SOUND_TYPE) {
            if (soundType.validBlocks.get().containsKey(block)) {
                smBlocks.add(new TrainSoundModifier(block, soundType.validBlocks.get().getInt(block), soundType));
                return;
            }
        }
    }

    @Override
    public void electroEnergetics$setPantographList(List<TrainPantographEntry> newPantographList) {
        electroEnergetics$pantographs = newPantographList;
    }

    @Override
    public List<TrainPantographEntry> electroEnergetics$getPantographList() {
        return electroEnergetics$pantographs;
    }

    @Override
    public boolean electroEnergetics$hasElectricMotor() {
        return electroEnergetics$hasMotor;
    }

    @Override
    public void electroEnergetics$setElectricMotor(boolean v) {
        electroEnergetics$hasMotor = v;
    }

    @Override
    public Set<TrainSoundModifier> electroEnergetics$getSoundModifyingBlocks() {
        return electroEnergetics$soundModifyingBlocks;
    }
}
