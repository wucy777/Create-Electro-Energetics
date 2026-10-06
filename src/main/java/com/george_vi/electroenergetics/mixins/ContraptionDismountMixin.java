package com.george_vi.electroenergetics.mixins;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.ContraptionHandler;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Keeps a rider with the carriage when they stand up from a seat at speed.
 *
 * <h2>Why the first three attempts failed</h2>
 *
 * <p>All of them compared the stored dismount position against the RIDER'S POSITION, and
 * on a moving train that second figure is worthless. {@code ContraptionCollider} skips
 * players on the server outright -
 *
 * <pre>
 *   ContraptionCollider.java:120-121
 *     if (playerType == PlayerType.SERVER)
 *         continue;
 * </pre>
 *
 * <p>- so the server never carries them and its copy of a rider lags the client by
 * whatever the speed costs. Measured, from the session that ran jar 47:
 *
 * <pre>
 *   server thought the rider was at Z = -382.78
 *   the rider's client entity was at Z = -391.07     (8.29 Blocks apart)
 *   the seat the server had recorded was at Z = -378.64
 * </pre>
 *
 * <p>So a "how far is this move?" test was measuring the distance between two positions
 * that were BOTH wrong, and it refused a correct relocation while reporting a
 * 4.15-Block distance - the wrong number entirely.
 *
 * <h2>What it does instead</h2>
 *
 * <p>The question is not "how far is the move" but "is this contraption moving". The
 * relocation is a snapshot taken server-side and applied client-side a network round trip
 * later, so on a moving contraption it is stale BY CONSTRUCTION - the error is the train's
 * speed times the latency, and it grows without bound as the speed does. On a stationary
 * one the snapshot is still accurate and Create's relocation is both correct and useful,
 * because it is what lifts a rider off the seat onto the carriage floor.
 *
 * <p>So the relocation is refused when the contraption under the rider moved appreciably
 * on its last tick, and left entirely alone otherwise. No position is ever compared, which
 * is what makes this immune to the stale-server-position problem that defeated the
 * distance test.
 *
 * <p>Refusing is safe because a rider leaving a seat is ALREADY in the right place:
 * {@code AbstractContraptionEntity.positionRider} puts them at the seat's global position
 * every client tick, so they are standing exactly where the seat is and the contraption's
 * collision pass keeps carrying them from there. The stale snapshot is what would move
 * them somewhere wrong.
 *
 * <h2>The threshold</h2>
 *
 * <p>{@link #MIN_CONTRAPTION_MOTION} is 0.1 Blocks/tick, i.e. 2 m/s. Below that a whole
 * tick of latency is under a tenth of a block and the snapshot is as good as live; above
 * it the error is a visible step, and it is the fast case this exists for. Nothing
 * plausible sits on the boundary - Create's own contraptions move at 0-0.1 Blocks/tick and
 * this mod's trains reach 5.0.
 *
 * <p>Expressed as the contraption's own motion rather than as a speed, so it needs no unit
 * conversion and cannot disagree with the motion the carry itself is computed from.
 */
@Mixin(ContraptionHandler.class)
public class ContraptionDismountMixin {

    @Unique
    private static final Logger electroEnergetics$LOG = LoggerFactory.getLogger("CEE-DIAG");

    /** Contraption travel per tick above which a stored dismount position is stale. */
    @Unique
    private static final double MIN_CONTRAPTION_MOTION = 0.1d;

    /**
     * Refuses a dismount relocation while the contraption under the rider is moving.
     *
     * <p>The first of the TWO paths that consume {@code ContraptionDismountLocation} - the
     * other is {@code AbstractContraptionEntity.getDismountLocationForPassenger}, guarded
     * in {@code AbstractContraptionEntityMixin}. Both remove the tag, so whichever runs
     * first wins and the other sees nothing; guarding one would leave the bug reachable
     * through the other.
     */
    @Inject(method = "entitiesWhoJustDismountedGetSentToTheRightLocation", at = @At("HEAD"),
            cancellable = true, remap = false)
    private static void electroEnergetics$keepRiderOnMovingCarriage(LivingEntity rider,
                                                                   Level world, CallbackInfo ci) {
        try {
            if (!world.isClientSide)
                return;
            if (!rider.getPersistentData().contains("ContraptionDismountLocation"))
                return;

            AbstractContraptionEntity moving = electroEnergetics$movingContraptionUnder(rider);
            if (moving == null)
                return;   // stationary: Create's relocation is correct, let it happen

            // Clear the tag so nothing can apply the stale position after this.
            rider.getPersistentData().remove("ContraptionDismountLocation");
            ci.cancel();

            electroEnergetics$LOG.info("DISMOUNT cancelled: carriage moving {} b/t, rider left in place",
                    String.format("%.3f",
                            moving.position().subtract(moving.getPrevPositionVec()).length()));
        } catch (Throwable ignored) {
            // A guard must never be the thing that breaks a dismount.
        }
    }

    /**
     * The moving contraption the rider is standing in, or {@code null} if none is moving.
     *
     * <p>Tested against the contraption's own footprint rather than any stored link,
     * because on the client a rider who has just stood up has no passenger link left to
     * test - that is the whole situation being handled.
     */
    @Unique
    private static AbstractContraptionEntity electroEnergetics$movingContraptionUnder(
            LivingEntity rider) {
        List<AbstractContraptionEntity> nearby = rider.level().getEntitiesOfClass(
                AbstractContraptionEntity.class,
                rider.getBoundingBox().inflate(1.5d),
                e -> e.getBoundingBox().inflate(0.5d).contains(rider.position()));

        for (AbstractContraptionEntity contraption : nearby) {
            double motion = contraption.position()
                    .subtract(contraption.getPrevPositionVec())
                    .length();
            if (motion > MIN_CONTRAPTION_MOTION)
                return contraption;
        }
        return null;
    }
}
