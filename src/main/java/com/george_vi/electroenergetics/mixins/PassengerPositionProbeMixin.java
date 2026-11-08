package com.george_vi.electroenergetics.mixins;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * TEMPORARY DIAGNOSTIC - see Document\\临时诊断日志清单.md. The whole file goes.
 *
 * <h2>The question this answers</h2>
 *
 * <p>The server says the driver is seated, riding and at the controls for the whole run - 187 heartbeats, no gaps.
 * On screen the driver leaves the train and stands beside the track while it keeps going. So the disagreement is
 * not about state at all, it is about WHERE the rider is drawn, and the only function that decides that is this one.
 *
 * <h2>What is printed</h2>
 *
 * <p>The seat position the contraption resolves, converted to world coordinates, next to where the rider's own
 * entity actually is. While everything is working those two agree to within a block; the tick they diverge is the
 * tick the operator is thrown out, and the numbers say whether the cause is a missing seat, a bad transform, or
 * the rider's entity being somewhere it should not be.
 *
 * <p>Only printed for the local player and only when the gap exceeds a tolerance, so an ordinary ride is silent and
 * a run costs nothing.
 */
@Mixin(AbstractContraptionEntity.class)
public class PassengerPositionProbeMixin {

    /** Beyond this the two answers disagree by more than interpolation can explain. */
    private static final double TOLERANCE_SQ = 4.0d * 4.0d;

    @Inject(method = "getPassengerPosition", at = @At("RETURN"), cancellable = false, remap = false)
    private void electroEnergetics$comparePosition(Entity rider, float partialTicks,
            CallbackInfoReturnable<Vec3> cir) {
        try {
            Vec3 seatWorld = cir.getReturnValue();
            if (seatWorld == null)
                return;
            AbstractContraptionEntity self = (AbstractContraptionEntity) (Object) this;
            if (self.level() == null || !self.level().isClientSide())
                return;
            // One line per divergent tick is already a lot at 100 ticks a second; the whole point is the first one.
            double gapSq = seatWorld.distanceToSqr(rider.getX(), rider.getY(), rider.getZ());
            if (gapSq <= TOLERANCE_SQ)
                return;
            Vec3 anchor = self.position();
            System.out.println(String.format(
                    "[rider-gap] gap=%.2f seat=(%.1f,%.1f,%.1f) rider=(%.1f,%.1f,%.1f) "
                            + "entPos=(%.1f,%.1f,%.1f) entId=%d partial=%.2f",
                    Math.sqrt(gapSq),
                    seatWorld.x, seatWorld.y, seatWorld.z,
                    rider.getX(), rider.getY(), rider.getZ(),
                    anchor.x, anchor.y, anchor.z,
                    self.getId(), partialTicks));
        } catch (Throwable ignored) {
            // A probe must never be the reason a rider is misplaced.
        }
    }
}
