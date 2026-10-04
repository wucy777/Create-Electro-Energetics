package com.george_vi.electroenergetics.client;

import com.simibubi.create.foundation.block.connected.CTSpriteShiftEntry;
import com.simibubi.create.foundation.block.connected.SimpleCTBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Draws a window frame wherever a pane of clear glass ends.
 *
 * <p>The frame is a ring drawn on the border of each face's texture, so a border
 * segment is drawn on a side unless the neighbour on that side is more clear
 * glass. The connection test therefore has to answer exactly one question - "is
 * the neighbour more of the same glass?" - and treat everything else as
 * unconnected, because everything else is where the glass stops and a frame
 * belongs.
 *
 * <p>So the rule is {@code other.getBlock() == state.getBlock()}, and not
 * "connect to anything that is not air". The latter reads as the friendlier rule
 * but it suppresses the frame along every boundary the glass shares with an
 * occupied block, and those boundaries are real edges of the glass:
 *
 * <ul>
 *   <li>A pane resting on the ground has ground under it, so the bottom border of
 *       its four side faces was dropped - the block looked framed along the top
 *       and the vertical edges but bare along the bottom.</li>
 *   <li>Wherever a sheet of glass meets a wall, a pillar or a different copycat
 *       material the frame stopped dead at the seam, leaving a stepped notch in
 *       what should be one continuous outline.</li>
 * </ul>
 *
 * <p>{@code other} is whatever {@code BlockState.getAppearance} resolves the
 * neighbour to, and for a copycat panel that is the panel's <em>material</em>. A
 * copycat carrying clear glass therefore reports the clear glass block and joins
 * seamlessly to a real clear glass block, while a copycat carrying stone reports
 * stone and correctly gets a frame.
 *
 * <p>The shape test in {@code isBeingBlocked} is deliberately not wanted here: a
 * wire or a sign hanging beside the pane does not change where the glass ends, so
 * the override answers directly instead of asking a full block behind it for
 * permission.
 */
public class ClearGlassCTBehaviour extends SimpleCTBehaviour {

    public ClearGlassCTBehaviour(CTSpriteShiftEntry shift) {
        super(shift);
    }

    /**
     * A face pressed against a solid block is not visible, and connected-texture
     * data is normally not gathered for such a face. The ring on that face cannot
     * be seen either way, so this changes nothing on its own; it is here for
     * parity with Create's own glass panes ({@code GlassPaneCTBehaviour}), which
     * ask for the same thing. It keeps the frame correct if a resource pack or a
     * future render type ever makes an occluded face visible, and the cost is one
     * extra neighbour probe per occluded face, gathered with the rest of the model
     * data and cached until a neighbouring block changes.
     */
    @Override
    public boolean buildContextForOccludedDirections() {
        return true;
    }

    /**
     * Only the 6-argument overload is overridden. The 8-argument form delegates to
     * this one, and {@code ConnectedTextureBehaviour.testConnection} samples
     * through the 8-argument form, so this single override covers every direction
     * the context builder probes - including the diagonal ones.
     */
    @Override
    public boolean connectsTo(BlockState state, BlockState other, BlockAndTintGetter reader,
                              BlockPos pos, BlockPos otherPos, Direction face) {
        return other.getBlock() == state.getBlock();
    }
}
