package com.george_vi.electroenergetics.mixins;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Glues a seat rider's RENDER position and interpolation base to the seat point the contraption's
 * own transform produces, at the one moment both are exactly right.
 *
 * <h2>The whole shake family, in one mechanism</h2>
 *
 * <p>A seat rider's position is written during the RIDER's tick: vanilla rideTick calls
 * {@code positionRider}, which transforms the seat by whatever the anchor fields hold at that
 * moment. The rider's entity id is lower than its vehicle's (a player exists long before the train
 * is assembled), so the rider ticks FIRST - before the vehicle's tick has rotated
 * {@code prevAnchor → anchor}. Every position the vanilla write produces is therefore one anchor
 * generation behind the transform the carriage will render with, and {@code setPos} resets the
 * interpolation baseline ({@code xo/yo/zo}) to that stale figure besides. What the renderer then
 * does with a one-generation-stale endpoint and a reset baseline depends on what the train is
 * doing, which is why this kept changing shape: at speed it was a forward-only jerk, after the
 * first fix a vertical sawtooth, then a fore-and-aft sway, then a lateral one.
 *
 * <h2>The fix</h2>
 *
 * <p>{@code CarriageContraptionEntity.tick} calls super first and only then runs its own
 * {@code alignPassenger} loop, which resets rider YAW but provably never writes rider positions.
 * So at {@code AbstractContraptionEntity.tick}'s RETURN the anchor pair is exactly the pair the
 * carriage's render will interpolate ({@code prevAnchor = N-1, anchor = N}), and nothing after it
 * moves rider positions. There - and only there - this glue places each seat rider at the CURRENT
 * anchor's seat point and sets the baseline to the SAME point under the PREV anchor (the exact
 * passenger arithmetic {@code getPassengerPosition} builds, replicated with
 * {@code usePrevAnchor = true}, including the seat offset and body drop that
 * {@code positionRider} adds on top). Rider and carriage then render the identical interpolation,
 * whoever ticks first, whatever the train is doing. Client side only.
 */
@Mixin(value = AbstractContraptionEntity.class, remap = false)
public abstract class RiderRenderGlueMixin {

    @Shadow(remap = false)
    public abstract Contraption getContraption();

    @Shadow(remap = false)
    public abstract Vec3 toGlobalVector(Vec3 localPos, float partialTicks, boolean usePrevAnchor);

    @Shadow(remap = false)
    public abstract Vec3 getPassengerPosition(Entity entity, float partialTicks);

    @Inject(method = "tick", at = @At("RETURN"), remap = false)
    private void electroEnergetics$glueRiderRender(CallbackInfo ci) {
        AbstractContraptionEntity self = (AbstractContraptionEntity) (Object) this;
        if (self.level().isClientSide == false)
            return;
        Contraption contraption = getContraption();
        if (contraption == null)
            return;
        for (Entity entity : self.getPassengers()) {
            BlockPos seat = contraption.getSeatOf(entity.getUUID());
            if (seat == null)
                continue;
            // positionRider applies getPassengerPosition's figure PLUS a seat offset and a -0.125
            // body drop on the Y (nested-contraption riders exempt from the drop). Both endpoints
            // below carry the same extra term, or the render interpolates between two heights a
            // constant apart - the vertical sawtooth this file already fixed once.
            double appliedYOffset = com.simibubi.create.content.contraptions.actors.seat.SeatEntity
                    .getCustomEntitySeatOffset(entity)
                    + (entity instanceof AbstractContraptionEntity ? 0d : -0.125d);
            Vec3 current = getPassengerPosition(entity, 1.0f);
            if (current == null)
                continue;

            // The same passenger point under the PREV anchor. getPassengerPosition builds the local
            // point as the seat's lower corner plus (0.5, -attachmentY + bodyHeight + 0.125
            // - seatOffset, 0.5), transforms it, then adds center-of-zero (0.5, 0.5, 0.5) and
            // subtracts (0.5, bodyHeight, 0.5). Replicating that with usePrevAnchor = true
            // reproduces the point one anchor generation back.
            AABB box = entity.getBoundingBox();
            double bodyHeight = box.getYsize();
            double localY = -entity.getVehicleAttachmentPoint((Entity) (Object) this).y
                    + bodyHeight + 0.125d
                    - com.simibubi.create.content.contraptions.actors.seat.SeatEntity
                            .getCustomEntitySeatOffset(entity);
            Vec3 local = Vec3.atLowerCornerOf(seat).add(0.5d, localY, 0.5d);
            Vec3 previous = toGlobalVector(local, 1.0f, true)
                    .add(0.5d, 0.5d, 0.5d)
                    .subtract(0.5d, bodyHeight, 0.5d)
                    .add(0.0d, appliedYOffset, 0.0d);

            entity.setPos(current.x, current.y + appliedYOffset, current.z);
            entity.xo = previous.x;
            entity.yo = previous.y;
            entity.zo = previous.z;
        }
    }
}
