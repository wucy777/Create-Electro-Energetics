package com.george_vi.electroenergetics.mixins;

import com.simibubi.create.content.contraptions.ContraptionWorld;
import com.simibubi.create.content.contraptions.actors.seat.SeatBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Gives a seat the whole block as its click target while it is part of a moving contraption.
 *
 * <h2>Why the target is {@code SeatBlock.getShape}</h2>
 *
 * <p>The first version of this patch changed the shape expression inside
 * {@code ContraptionHandlerClient.rayTraceContraption}. That call is not in that method at all - it is in the
 * private lambda {@code lambda$rayTraceContraption$0} the method passes to {@code RaycastHelper}, so with
 * {@code injectors.defaultRequire = 1} the injection would have found nothing and Mixin would have refused to
 * start the client. {@code SeatBlock.getShape} is declared on the seat itself, which is a target that exists
 * by definition and does not depend on how Create happens to structure a helper method.
 *
 * <h2>Why only inside a contraption</h2>
 *
 * <p>A seat's own shape is a low chair, and in the real world that is the right target: the player can see the
 * chair and click it. On a moving train the ray is traced against the contraption's blocks, and the same low
 * shape means a click aimed at the seat - especially the driver's seat, which is often behind a controls block
 * and under the player's own feet - can pass just over the chair and land on nothing, so nothing happens.
 * Returning the full block there makes the seat behave like the block it occupies.
 *
 * <p>The real world is deliberately untouched: {@link ContraptionWorld} is the wrapper Create hands to block
 * queries for an assembled contraption, so a seat placed on the ground keeps the shape it has always had, and
 * its selection outline does not grow into a cube.
 *
 * <p>Collision is NOT widened. {@code getCollisionShape} stays as Create wrote it, so players can still walk
 * over seats; only what a click can hit changed.
 */
@Mixin(value = SeatBlock.class, remap = false)
public class ContraptionSeatHitboxMixin {

    @Inject(method = "getShape", at = @At("HEAD"), cancellable = true, remap = false)
    private void electroEnergetics$fullBlockClickTarget(BlockState state, BlockGetter level, BlockPos pos,
                                                        CollisionContext context,
                                                        CallbackInfoReturnable<VoxelShape> cir) {
        // ONLY the empty context, which is the one a ray trace and an outline use. An entity's collision
        // query carries its own context, and answering a full block there makes every seat a solid cube that
        // pushes entities out of it - reported as "standing anywhere on the train other than the roof, and
        // even just touching the side, the player drifts slowly" while the train stood still. The click target
        // and the collision shape are separate questions and this must answer only the first.
        if (level instanceof ContraptionWorld && context.isEmpty())
            cir.setReturnValue(Shapes.block());
    }
}
