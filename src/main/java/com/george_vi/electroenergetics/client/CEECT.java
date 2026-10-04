package com.george_vi.electroenergetics.client;

import com.george_vi.electroenergetics.CreateElectroEnergetics;
import com.simibubi.create.foundation.block.connected.AllCTTypes;
import com.simibubi.create.foundation.block.connected.CTSpriteShiftEntry;
import com.simibubi.create.foundation.block.connected.CTSpriteShifter;

/**
 * Create-style connected textures for our own blocks.
 *
 * <p>Why Create CT rather than an OptiFine/Continuity {@code optifine/ctm} set:
 * a copycat panel (Create's own, and Copycats+' range) never renders the material
 * block's model through the normal block-render pipeline. It looks the material's
 * {@code BakedModel} up directly and replays its quads, harvesting the model's
 * {@code ModelData} first and handing it back on the {@code getQuads} call
 * (Create's {@code CopycatModel.gatherModelData} and Copycats+'
 * {@code CopycatModelNeoForge.gatherModelData} both do this). Continuity applies
 * CTM from inside its own model wrapper's {@code emitBlockQuads}, which that route
 * bypasses entirely, so an OptiFine CTM set can frame the real block but never a
 * copycat. Create's {@code CTModel} instead shifts the quad UVs inside
 * {@code getQuads} based on that same {@code ModelData}, so a CT type registered on
 * the material block drives the copycat's appearance too.
 *
 * <p>The shift is looked up by sprite, and the copycat quad keeps the material's
 * original sprite, so this one registration covers the plain block and every
 * copycat using it as a material.
 *
 * <p>{@link CTSpriteShifter#getCT} is safe to call from common code: it only
 * touches the client atlas when the environment is client, and the shift is
 * resolved lazily from the sprite sheet.
 */
public final class CEECT {

    /**
     * Outer-perimeter frame for {@code clear_glass}.
     *
     * <p>{@link AllCTTypes#RECTANGLE} is a 4x4 sheet and only needs the four
     * axis-aligned neighbours, which is exactly the "draw an edge where the
     * neighbour is missing" rule behind a perimeter frame. Each 16x16 cell holds
     * the border segments for one combination of present/absent neighbours, so a
     * flat sheet of the block shows a frame around its outside edge only.
     */
    public static final CTSpriteShiftEntry CLEAR_GLASS = CTSpriteShifter.getCT(
            AllCTTypes.RECTANGLE,
            CreateElectroEnergetics.rl("block/clear_glass"),
            CreateElectroEnergetics.rl("block/clear_glass_connected"));

    private CEECT() {
    }
}
