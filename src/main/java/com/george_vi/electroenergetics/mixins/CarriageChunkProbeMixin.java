package com.george_vi.electroenergetics.mixins;

import com.simibubi.create.content.trains.entity.CarriageEntityHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Probes the carriage's own chunk instead of one 16 Blocks ahead of it, which is both cheaper and
 * the question that actually needs answering.
 *
 * <h2>What Create does</h2>
 *
 * <p>{@code alignEntity} refuses to place a carriage whose forward point is not in an entity-ticking
 * chunk, and instead flags {@code Train.carriageWaitingForChunks}. {@code Train.tick} then sets that
 * tick's advance distance to zero. The guard is server-only - the client skips the whole block with a
 * single {@code isClientSide} branch - so when it fires the server train stalls while the client train
 * keeps moving. The two positions diverge, and the client rider is later pulled to the server's
 * position by {@code handleMovePlayer}.
 *
 * <p>Measured on a live session at roughly 3.2 Blocks/tick: the dismount sent the player 9.60 Blocks,
 * which is 3.0 ticks of carriage travel. Four earlier builds measured 4.01, 2.99, 4.00 and 3.92 ticks
 * on the same quantity. A stable 3-4 tick offset, not jitter, is what a per-tick stall guards produce.
 *
 * <h2>Why the probe distance is the wrong question</h2>
 *
 * <p>The point being tested is
 *
 * <pre>positionAnchor + normalize(positionAnchor - entity.position()) * 16</pre>
 *
 * <p>that is, 16 Blocks ahead of the carriage along its direction of travel. That is a lookahead aimed
 * at preloading territory the train is about to enter. But {@code isActiveChunk} does not load anything -
 * it only asks whether a position is already entity-ticking - so the offset buys no loading. It only
 * makes the answer depend on something other than "is this carriage somewhere that can be placed".
 *
 * <p>At the speeds this mod permits the lookahead lands in territory nobody has ticked yet, the guard
 * fires, and the train stops. At Create's own ceiling of 1.4-2 Blocks/tick the same offset stays inside
 * loaded ground, which is why a fuel train never shows this.
 *
 * <h2>Cost</h2>
 *
 * <p>Lower than vanilla. The original expression allocates four {@link Vec3} objects per carriage per
 * tick (subtract, normalize, scale, add) purely to derive the probe point; this redirect reads the
 * anchor that {@code alignEntity} has already computed and allocates nothing. A ten-carriage train saves
 * 800 allocations per second.
 *
 * <p>Deliberately NOT a chunk ticket. Keeping the lookahead honest by force-loading what it points at
 * would cost sustained server work proportional to train speed, on a machine whose peak capacity is the
 * binding constraint. This change adds no work of any kind.
 */
@Mixin(targets = "com.simibubi.create.content.trains.entity.Carriage$DimensionalCarriageEntity")
public class CarriageChunkProbeMixin {

    /**
     * The carriage's authoritative position for this tick, already computed by {@code alignEntity}
     * before the guard runs.
     *
     * <p>{@code positionAnchor} is a PUBLIC field on the target. {@code @Shadow} on a public member of
     * a target this mixin does not otherwise touch would be a second declaration of it; the field is
     * read directly through the cast below instead.
     */
    @Unique
    private Vec3 electroEnergetics$anchor() {
        return ((com.simibubi.create.content.trains.entity.Carriage.DimensionalCarriageEntity)
                (Object) this).positionAnchor;
    }

    @Redirect(
            method = "alignEntity",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/simibubi/create/content/trains/entity/CarriageEntityHandler;"
                            + "isActiveChunk(Lnet/minecraft/world/level/Level;"
                            + "Lnet/minecraft/core/BlockPos;)Z"),
            remap = false)
    private boolean electroEnergetics$probeAtCarriage(Level level, BlockPos ignoredLookahead) {
        Vec3 anchor = electroEnergetics$anchor();
        // No anchor means alignEntity has nothing to place anyway; do not stall the train over it.
        if (anchor == null)
            return true;
        return CarriageEntityHandler.isActiveChunk(level, BlockPos.containing(anchor));
    }
}
