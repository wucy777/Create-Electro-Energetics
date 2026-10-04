package com.george_vi.electroenergetics.content.decoration;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.block.TransparentBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * Frameless glass: one faint tinted sheet, no border, so a wall of it reads as a
 * single pane instead of a grid of tiles.
 *
 * <p>Extending {@link TransparentBlock} (vanilla glass' own behaviour class)
 * inherits the two things that matter: shared faces between two of the same block
 * are not drawn, so a wall of this has no internal seams, and light propagates
 * down through it.
 *
 * <p>Both "frameless" and "see-through" are texture properties rather than code
 * ones. The block ships a texture that is a single flat fill at low alpha, so the
 * only thing the class has to get right is the render type. That must be
 * {@code minecraft:translucent}, and it must be registered in
 * {@code ItemBlockRenderTypes} as well as declared in the model:
 * {@code getChunkRenderType} reads its own per-block map and falls back to
 * {@code solid()} when a block is absent, so a model's {@code render_type} alone
 * is not enough. The cutout passes are wrong for this texture - they do not blend
 * at all and merely drop texels below alpha 0.1, so a faint sheet would be drawn
 * fully opaque.
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
