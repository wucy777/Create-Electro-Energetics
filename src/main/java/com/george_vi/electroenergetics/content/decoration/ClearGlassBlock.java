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
 * only thing the class has to get right is the render type: the model declares
 * {@code minecraft:translucent}, because {@code cutout} - which vanilla glass
 * uses - discards the alpha channel outright. Under cutout this texture would be
 * drawn as a fully opaque sheet, or not at all.
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
