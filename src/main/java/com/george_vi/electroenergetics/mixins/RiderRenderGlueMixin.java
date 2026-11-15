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
 * Glues a rider's RENDER interpolation base to the seat position the contraption's own transform
 * produces, per tick.
 *
 * <h2>The shake, and where it came from</h2>
 *
 * <p>A rider on a moving contraption gets their position from two writers: the vehicle's own
 * {@code positionRider} (the client transform - which seat, transformed by where the CLIENT thinks
 * the carriage is) and the vanilla server sync. {@code Entity.setPos} resets the interpolation
 * baseline ({@code xo/yo/zo}) to the newly written position, so the vanilla render drew the rider
 * stepping a whole tick of travel every tick while the train rendered through its own
 * interpolation - the violent forward-only shake at speed.
 *
 * <p>The first fix cached last tick's seat figure and restored it as the baseline, which killed the
 * stepping and - once the baseline learned the seat offset and body drop that {@code positionRider}
 * applies AFTER {@code getPassengerPosition} - the vertical sawtooth too. But a cache is written
 * whenever {@code positionRider} last happened to run, and the entity tick order decides whether
 * that is one or two anchor generations behind the carriage's own render. On a train changing speed
 * the two differ by a growing slice of a tick, which the rider's interpolation turned into a smooth
 * fore-and-aft sway - the residual "sin" rocking this file removes.
 *
 * <h2>The fix</h2>
 *
 * <p>The baseline is computed, not cached: the exact passenger point {@code getPassengerPosition}
 * builds (seat corner + attachment offset + body drop, carriage-local), transformed by the PREV
 * anchor instead of the current one. That is precisely the carriage's own render interpolation
 * endpoints, in the same order, whoever ticks first - the rider glides inside the rendered train
 * with no lag to turn into a sway. Client side only; the server's authoritative position is
 * untouched.
 */
@Mixin(value = AbstractContraptionEntity.class, remap = false)
public abstract class RiderRenderGlueMixin {

    @Shadow(remap = false)
    public abstract Contraption getContraption();

    @Shadow(remap = false)
    public abstract Vec3 toGlobalVector(Vec3 localPos, float partialTicks, boolean usePrevAnchor);

    @Shadow(remap = false)
    public abstract Vec3 getPassengerPosition(Entity entity, float partialTicks);

    @Inject(method = "positionRider", at = @At("RETURN"), remap = false)
    private void electroEnergetics$glueRiderRender(Entity entity, Entity.MoveFunction moveFunction,
                                                   CallbackInfo ci) {
        if (!entity.level().isClientSide())
            return;
        Contraption contraption = getContraption();
        if (contraption == null)
            return;
        BlockPos seat = contraption.getSeatOf(entity.getUUID());
        if (seat == null)
            return;
        // What positionRider applies on top of getPassengerPosition: the seat offset and a -0.125
        // body drop, both added to the Y after the transform (nested contraption riders are exempt
        // from the drop). Caching the raw figure instead was the vertical sawtooth - the render
        // interpolated between two heights a constant apart, 20 times a second, while MiniHUD showed
        // the true y perfectly still.
        double appliedYOffset = com.simibubi.create.content.contraptions.actors.seat.SeatEntity
                .getCustomEntitySeatOffset(entity)
                + (entity instanceof AbstractContraptionEntity ? 0d : -0.125d);
        Vec3 current = getPassengerPosition(entity, 1.0f);
        if (current == null)
            return;

        // The same passenger point under the PREV anchor. getPassengerPosition builds the local
        // point as the seat's lower corner plus (0.5, attachmentY..., 0.5) - the attachment term
        // being minus the vehicle attachment y, plus the body height, plus 0.125, minus the seat
        // offset - then adds center-of-zero (0.5, 0.5, 0.5) and subtracts (0.5, bodyHeight, 0.5)
        // around the transform. Replicating that arithmetic with usePrevAnchor = true reproduces
        // the point exactly one anchor generation back: no cache, no dependence on whether the
        // passenger or the carriage ticked first.
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

        entity.xo = previous.x;
        entity.yo = previous.y;
        entity.zo = previous.z;
    }
}
