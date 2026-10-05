package com.george_vi.electroenergetics.mixins;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.ContraptionCollider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Stops a fast electric train flinging the people standing on it out of the train.
 *
 * <p>Reported at about 70 Blocks/Second: a player riding without sitting in a seat -
 * the driver, or a passenger - is thrown clear of the carriage, and at Create's own
 * speeds (about 30 Blocks/Second) it never happened. This mod is what raises the speed,
 * so this mod has to deal with the consequence.
 *
 * <h2>What actually happens</h2>
 *
 * <p>Create carries a standing rider by teleporting them along with the carriage each
 * tick ({@code ContraptionCollider.collideEntities}), and on the way it runs them
 * through {@code handleDamageFromTrain} - the "a train hit you" path, which is meant
 * for someone standing on the TRACK and being run over. That method does:
 *
 * <pre>
 *   diffMotion = contraptionMotion - entityMotion      // how much faster the train is
 *   damage     = diffMotion.length()
 *   added      = entityMotion + horizontalDir * damage * 4 + diffMotion
 *   return VecHelper.clamp(added, 3)
 * </pre>
 *
 * <p>The velocity it adds is proportional to the train's speed, and the result is only
 * clamped to 3 blocks per tick. At 30 Blocks/Second the carriage moves 1.5 per tick and
 * the add is survivable; at 70 it moves 3.5 per tick, {@code damage} is about 3.5, the
 * add before clamping is roughly 17, and the clamp turns that into a flat 3 blocks per
 * tick sideways plus 3 up - about 60 Blocks/Second, straight off the train.
 *
 * <p>Two guards are supposed to stop this, and at high speed they stop being reliable:
 * the {@code "ContraptionGrounded"} early return is set only client-side, from a single
 * 0.2-block probe below the player, and the {@code onGround} test is evaluated before
 * Create marks the rider as grounded. At 3.5 blocks per tick the rider skips over the
 * probe often enough to fall through both.
 *
 * <h2>The fix, and why it is this narrow</h2>
 *
 * <p>The method is left returning a velocity; it is only prevented from returning a
 * LAUNCH when the entity is standing on the very contraption that would be hitting it.
 * That is the honest test for "this is not a run-over": a player inside the carriage's
 * own footprint cannot be run over by it. Anybody else - on the track, on a platform,
 * beside the rails - is untouched and still takes the damage and the knockback exactly
 * as before.
 *
 * <p>Deliberately not disabled wholesale, and not gated on this mod's trains: a Create
 * train that somehow ran at this speed would have the identical bug, and the condition
 * here is about geometry, not about who owns the train.
 *
 * <p>The footprint test is the contraption's own bounding box. The obvious alternative -
 * mapping the entity into the contraption's frame and looking for its blocks, which is
 * how the surrounding code asks similar questions - was rejected because it needs a
 * partial-tick parameter, and the two candidate values differ by a whole tick of train
 * travel: 3.5 blocks at the speed this bug occurs at. A bounding box has no such
 * ambiguity and is one comparison rather than a scan.
 *
 * <p>Damage still applies. Being run over is a real mechanic and a player in the way of
 * a moving train should still be hurt; it is the ejection that is the bug. Because this
 * is a return-value modification, the {@code entity.hurt} call inside the original has
 * already run by the time this decides anything - so only the launch is removed, and
 * the collision response and the physics are untouched.
 */
@Mixin(ContraptionCollider.class)
public class ContraptionColliderMixin {

    /**
     * How far outside the carriage's bounding box still counts as "aboard".
     *
     * <p>Small, because the box already covers the carriage's full width and the rider
     * stands inside it. This exists only to absorb the entity's own width and the
     * sub-tick jitter of being teleported along with the train. Deliberately not larger:
     * a player standing on a platform TO THE SIDE of a passing train must stay outside
     * this box, or they would stop being pushed by one.
     */
    private static final double ABOARD_INFLATE = 0.5d;

    @ModifyReturnValue(method = "handleDamageFromTrain", at = @At("RETURN"), remap = false)
    private static Vec3 electroEnergetics$noLaunchWhenAboard(
            Vec3 original,
            @Local(argsOnly = true) AbstractContraptionEntity contraptionEntity,
            @Local(argsOnly = true) Entity entity) {
        if (original == null || entity == null || contraptionEntity == null)
            return original;
        if (!isAboard(contraptionEntity, entity))
            return original;
        // The rider is on the train, so this is not a run-over: leave their motion as it
        // was and let Create's normal carriage-carrying do the work.
        return entity.getDeltaMovement();
    }

    /**
     * Whether the entity is standing on this contraption.
     *
     * <p>Fails to {@code false} on anything unexpected - the safe direction, since false
     * preserves Create's original behaviour exactly.
     */
    private static boolean isAboard(AbstractContraptionEntity contraptionEntity, Entity entity) {
        try {
            return contraptionEntity.getBoundingBox()
                    .inflate(ABOARD_INFLATE)
                    .contains(entity.position());
        } catch (Throwable ignored) {
            return false;
        }
    }
}
