package com.george_vi.electroenergetics.content.railway_electrification;

import com.simibubi.create.content.contraptions.OrientedContraptionEntity;
import com.simibubi.create.content.contraptions.actors.trainControls.ControlsBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;

/**
 * Which way the cab the driver is standing at faces along the consist.
 *
 * <p>A train can have a cab at each end, and the two cabs command opposite
 * directions: at the far cab, "accelerate" means moving the other way in the world.
 * Create handles this for its own controls - {@code CarriageContraptionEntity.control}
 * computes {@code inverted} and then does {@code if (inverted) targetSpeed *= -1} -
 * but the lever never passes through that method, so the lever has to work it out
 * itself or it will be mirrored at one end of the train.
 *
 * <p>Reproduced here rather than reimplemented in each place, because the client needs
 * the same answer for the direction arrow that the server needs for the physics, and
 * two copies of this test could drift apart. The logic is Create's, deliberately
 * verbatim: the controls block's FACING compared against the contraption's initial
 * orientation taken counter-clockwise.
 *
 * <p>Takes {@link OrientedContraptionEntity} rather than the more general
 * {@code AbstractContraptionEntity} because {@code getInitialOrientation} is declared
 * there - a carriage entity always is one, and narrowing the parameter is what keeps
 * this honest about needing an orientation to exist at all.
 *
 * <p>Safe on both sides - it only reads common contraption state.
 */
public final class CabOrientation {

    private CabOrientation() {}

    /**
     * Whether the cab at {@code controlsLocalPos} faces against the contraption, in
     * which case everything it commands is mirrored in the world.
     *
     * <p>False for anything unexpected, which is the safe direction: false is the
     * single-cab case, so a cab whose orientation cannot be read behaves exactly as
     * every train did before this existed.
     */
    public static boolean isInverted(OrientedContraptionEntity entity, BlockPos controlsLocalPos) {
        if (entity == null || controlsLocalPos == null || entity.getContraption() == null)
            return false;
        try {
            StructureBlockInfo info = entity.getContraption().getBlocks().get(controlsLocalPos);
            if (info == null || !info.state().hasProperty(ControlsBlock.FACING))
                return false;
            Direction initialOrientation = entity.getInitialOrientation().getCounterClockWise();
            return !info.state().getValue(ControlsBlock.FACING).equals(initialOrientation);
        } catch (Throwable ignored) {
            return false;
        }
    }
}
