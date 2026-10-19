package com.george_vi.electroenergetics.mixins;

import com.simibubi.create.content.trains.entity.CarriageEntityHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Reports every chunk-activity decision for a carriage, with the distance to the nearest server player.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Moving the probe to the carriage's own position was supposed to stop the server stalling carriages.
 * It did not: the dismount still throws the rider, and the throw is still about three ticks of carriage
 * travel.
 *
 * <p>The remaining explanation on paper is circular. The server never carries a rider - the client
 * computes the seat position and reports it - so the server's copy of the player may lag behind the
 * carriage that player is sitting in. The entity-ticking distance is measured FROM PLAYERS, so a lagging
 * server player may not cover the carriage, the carriage is refused, the train stalls, and it lags
 * further.
 *
 * <p>That circle cannot be confirmed or dismissed by reading source. It needs two numbers on the same
 * line: what the decision was, and how far it was from the player the decision is measured against.
 *
 * <pre>
 *   CHUNK pos=.. chunk=.. inRange=.. result=.. playerDist=.. player=.. players=..
 * </pre>
 *
 * <p>If {@code playerDist} stays well inside the entity-ticking radius while {@code inRange} is false,
 * the player-lag theory is wrong and something else refuses the chunk. If {@code playerDist} tracks
 * speed, the theory holds and the fix is to stop measuring from a position that lags.
 *
 * <p>{@code inRange} is {@code DistanceManager.inEntityTickingRange} for the probe's chunk. The other
 * half of {@code isPositionEntityTicking} - the entity section manager - has no public accessor, so it is
 * not reported; {@code result} is the combined answer either way.
 *
 * <h2>Cost</h2>
 *
 * <p>Server side only, one line per 10 probes, and the work skipped when not sampled is a counter
 * increment and a type test. Removed as soon as the cause is known.
 */
@Mixin(value = CarriageEntityHandler.class, remap = false)
public class ChunkTickTraceMixin {

    @Unique
    private static final Logger electroEnergetics$LOG = LoggerFactory.getLogger("CEE-CHUNK");

    @Unique
    private static int electroEnergetics$n;

    @Inject(method = "isActiveChunk", at = @At("RETURN"), remap = false)
    private static void electroEnergetics$after(Level level, BlockPos pos,
                                                CallbackInfoReturnable<Boolean> cir) {
        try {
            if (!(level instanceof ServerLevel serverLevel))
                return;                     // the client branch returns false by design; nothing to learn
            if ((electroEnergetics$n++ % 10) != 0)
                return;

            boolean inRange = serverLevel.getChunkSource().chunkMap
                    .getDistanceManager().inEntityTickingRange(ChunkPos.asLong(pos));

            // Distance to the nearest server player: the entity-ticking window is centred on these, so
            // this number is what decides whether the carriage is covered.
            double nearest = -1;
            int count = 0;
            for (ServerPlayer player : serverLevel.players()) {
                count++;
                double d = player.position().distanceTo(
                        new net.minecraft.world.phys.Vec3(pos.getX() + 0.5, pos.getY() + 0.5,
                                pos.getZ() + 0.5));
                if (nearest < 0 || d < nearest)
                    nearest = d;
            }

            electroEnergetics$LOG.info(
                    "CHUNK pos={} chunk={} inRange={} result={} playerDist={} players={}",
                    pos.toShortString(), new ChunkPos(pos).toString(), inRange, cir.getReturnValue(),
                    String.format("%.1f", nearest), count);
        } catch (Throwable ignored) {
            // A diagnostic must never be the thing that breaks the server.
        }
    }
}
