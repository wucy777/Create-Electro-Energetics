package com.george_vi.electroenergetics.content.decoration;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.block.TransparentBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * Glass with the frame removed, leaving only the sparkle marks.
 *
 * <p>Extending {@link TransparentBlock} (vanilla glass' own behaviour class)
 * inherits the two things that matter: shared faces between two of the same block
 * are not drawn, so a wall of this has no internal seams, and light propagates
 * down through it.
 *
 * <p>The frame is painted into vanilla glass' texture, so removing it is a
 * texture property rather than a code one - this class simply ships a texture
 * that is transparent everywhere except the interior sparkles. The model declares
 * the cutout render type, matching vanilla glass; see
 * {@code models/block/clear_glass.json}.
 */
public class ClearGlassBlock extends TransparentBlock {

    public static final MapCodec<ClearGlassBlock> CODEC = simpleCodec(ClearGlassBlock::new);

    public ClearGlassBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends TransparentBlock> codec() {
        return CODEC;
    }
}
