package com.george_vi.electroenergetics.client;

import com.simibubi.create.foundation.block.connected.CTSpriteShiftEntry;
import com.simibubi.create.foundation.block.connected.SimpleCTBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Draws the clear-glass frame only where the glass faces open space.
 *
 * <p>Create's default rule is {@code state.getBlock() == other.getBlock()}, where
 * {@code other} comes from {@code BlockState.getAppearance}. For a copycat that
 * resolves to the <em>material</em>, so the default rule means "connect only to
 * another pane of the same material". Every other neighbour - a different
 * copycat material, a stone block, a wall - breaks the connection and therefore
 * gets a frame segment drawn on that side.
 *
 * <p>That is the right rule for a texture whose pattern should tile across a
 * single material, but wrong for a window frame: a glass sheet set into a wall,
 * or a run of glass broken by a pillar, ends up with a border line around every
 * seam and crossing right through the middle of the assembly. The frame the
 * player actually wants traces the <em>silhouette</em> of the glass, so the only
 * thing that should break it is open space.
 *
 * <p>Hence the override: any occupied neighbour counts as connected. Air is what
 * produces the outer perimeter, and everything else is swallowed, leaving one
 * continuous outline around the outside of the whole glass structure.
 *
 * <p>Note this deliberately does NOT connect to everything - air must still
 * count as unconnected, or every cell would select the fully-surrounded tile and
 * no frame would be drawn at all.
 *
 * <p>The neighbour test uses the material's own shape, because {@code other} is
 * the material state rather than the copycat's. A thin copycat panel with a
 * stone material therefore reads as a full cube for this test, which is what we
 * want: it should hide the seam behind it.
 */
public class ClearGlassCTBehaviour extends SimpleCTBehaviour {

    public ClearGlassCTBehaviour(CTSpriteShiftEntry shift) {
        super(shift);
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
        return !other.isAir();
    }
}
