package com.george_vi.electroenergetics.content.decoration;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.block.TransparentBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * Glass with the frame removed and the tint turned right down.
 *
 * <p>Extending {@link TransparentBlock} (Creative's glass behaviour, in 1.21
 * vanilla's own class) inherits the two things that matter: shared faces between
 * two of the same block are not drawn, so a wall of this has no internal seams,
 * and light propagates down through it.
 *
 * <p>The visible frame on vanilla glass is painted into its texture, so the frame
 * being gone is a texture property, not a code one. See
 * {@code models/block/clear_glass.json}, which also declares the translucent
 * render type - without that a texture this faint would be dropped by the cutout
 * pass and the block would render as nothing at all.
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
