package com.george_vi.electroenergetics.mixins;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Keeps the per-tick displacement of a contact point on a contraption within the same
 * order as the contraption's own motion.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Create carries anybody standing on a moving contraption by asking where the
 * material point under their feet will be next tick -
 * {@code getContactPointMotion} - and teleporting them by that. The answer has two
 * parts:
 *
 * <pre>
 *   contraptionLocalMovement  = where THAT POINT goes as the contraption ROTATES
 *   contraptionAnchorMovement = where the contraption as a whole goes
 * </pre>
 *
 * <p>The second is the train's linear motion and is well behaved. The first scales with
 * the point's distance from the carriage pivot times the per-tick YAW, so on a curve it
 * grows with speed in a way the linear motion does not, and nothing anywhere bounds it:
 * {@code ContraptionCollider} applies it unclamped to the rider's position and feeds it
 * to the bounce maths as well.
 *
 * <p>That is the mechanism behind the reported ejection of riders from a fast electric
 * train. It is a Create assumption - that a contraption turns slowly enough for the
 * rotation term to stay small - which this mod's speed removes. Since this mod is what
 * raises the speed, this mod absorbs the consequence.
 *
 * <h2>Why the bound is relative, and not a fixed clamp</h2>
 *
 * <p>A fixed clamp (say 0.5 blocks) would be actively harmful: the rider has to be moved
 * by at least the train's own per-tick motion or they are simply left behind, and at
 * 100 m/s that is 5 blocks per tick - ten times the fixed figure. Clamping to it would
 * make a fast train shed its passengers every tick.
 *
 * <p>So the bound is expressed RELATIVE to the anchor motion, which is the part that is
 * always correct: the rotation term may contribute as much again as the train's own
 * travel, plus a small allowance, which is far more than any legitimately banked curve
 * needs and still bounds the runaway. On straight track the rotation term is already
 * ~zero and this never engages.
 *
 * <p>The consequence to be honest about: on a genuinely tight curve taken fast, a rider
 * is now displaced by slightly less than the geometry would put them at. That is a
 * cosmetic lag against an ejection, and the accompanying curve speed limit
 * ({@code TrainMixin.electroEnergetics$maxTurnSpeed}) means a train should not be
 * entering such a curve fast enough for it to be felt.
 *
 * <p>Common side rather than client only: the server never runs the carry for a player
 * ({@code ContraptionCollider} skips {@code PlayerType.SERVER}), but it does run it for
 * other entities, and {@code saveRemotePlayerFromClipping} runs for remote players on
 * the client. Bounding on both sides keeps them agreeing, which is the point - a
 * divergence here is exactly what becomes a rubber-band correction.
 *
 * <p>This bound was originally set at 2x the contraption's travel, which made it INERT:
 * at 5 Blocks/tick the limit came to 11 while the value being bounded is at most the
 * train's own 5 plus a rotation term, so nothing could ever trip it. A bound that cannot
 * fire is decoration rather than a fix, and it was shipped on a theory that a later
 * bytecode-level investigation did not confirm. It is 1.25x now, which leaves straight
 * track untouched and engages only when rotation contributes more than about a quarter of
 * the train's per-tick travel.
 */
@Mixin(AbstractContraptionEntity.class)
public class AbstractContraptionEntityMixin {

    /**
     * How much of the rotation term to keep, as a multiple of the contraption's own
     * per-tick travel.
     *
     * <p>1.25 rather than the 2.0 this started at, and the reason is that 2.0 made the
     * bound INERT - it could never engage. At 5 Blocks/tick of travel the old limit was
     * 5 x 2 + 1 = 11, while the value being bounded is the train's own motion (about 5)
     * plus the rotation term, so nothing short of a 6-Block/tick rotation term would ever
     * trip it. A bound that cannot fire is not a fix, it is decoration, and this one was
     * shipped on a theory that a later, bytecode-level investigation did not confirm.
     *
     * <p>At 1.25 the limit is 5 x 1.25 + 0.5 = 6.75, so straight track - where the
     * rotation term is ~0 and the value is the train's own 5 - is comfortably inside it and
     * the carry is untouched. It engages only when rotation adds more than about a quarter
     * of the train's per-tick travel, which is a genuinely large swing for a contact point
     * on a carriage and is the regime where the term stops being a carry and becomes a
     * throw.
     */
    private static final double ROTATION_ALLOWANCE = 1.25;

    /** Absolute allowance added to the bound, for a contraption moving very slowly. */
    private static final double MIN_ALLOWANCE = 0.5;

    @ModifyReturnValue(method = "getContactPointMotion", at = @At("RETURN"), remap = false)
    private Vec3 electroEnergetics$boundContactPointMotion(Vec3 original) {
        if (original == null || original == Vec3.ZERO)
            return original;

        AbstractContraptionEntity self = (AbstractContraptionEntity) (Object) this;
        Vec3 anchor = self.position().subtract(self.getPrevPositionVec());
        double anchorLength = anchor.length();

        double limit = anchorLength * ROTATION_ALLOWANCE + MIN_ALLOWANCE;
        double length = original.length();
        if (!Double.isFinite(length) || length <= limit)
            return original;

        // Scaled rather than clamped per axis, so the DIRECTION the rider is carried in
        // is unchanged and only the distance is trimmed. Clamping axis-wise would bend
        // the motion towards a corner and change which way the rider ends up going.
        return original.scale(limit / length);
    }
}
