package com.george_vi.electroenergetics.mixins;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Glues a rider's RENDER interpolation base to the seat position the contraption's own
 * transform produces, per tick.
 *
 * <h2>The shake, and where it comes from</h2>
 *
 * <p>A rider on a moving contraption gets their position from two writers: the vehicle's own
 * {@code positionRider} (the client transform - which seat, transformed by where the CLIENT
 * thinks the carriage is) and the vanilla server sync (movement packets derived from where the
 * SERVER thinks it is). The two disagree by up to one tick of travel - at the speeds an
 * electric consist runs, that is several Blocks - and whichever lands last owns the rider's
 * interpolation baseline ({@code xo/yo/zo}). A baseline set from the stale server value makes
 * the rendered rider extrapolate a whole tick of travel forward from a position behind the
 * train: one violent forward jerk per sync, forever - the reported shake, all forward, one
 * end of the train.
 *
 * <h2>The fix</h2>
 *
 * <p>After {@code positionRider} has placed the rider at the client transform's seat position,
 * the interpolation baseline is set to the PREVIOUS tick's seat position (the prev-anchor
 * transform of the same seat). The vanilla render then interpolates the rider between
 * consecutive seat positions of the client transform - exactly the motion the rendered train
 * shows - and the server's stale baseline is overwritten every tick instead of owning the
 * draw. Client side only; the server's authoritative position is untouched.
 */
@Mixin(value = AbstractContraptionEntity.class, remap = false)
public abstract class RiderRenderGlueMixin {

    @Shadow(remap = false)
    public abstract Contraption getContraption();

    @Shadow(remap = false)
    public abstract Vec3 toGlobalVector(Vec3 localPos, float partialTicks, boolean usePrevAnchor);

    @Shadow(remap = false)
    public abstract Vec3 getPassengerPosition(Entity entity, float partialTicks);

    @Unique
    private static final Map<UUID, Vec3> electroEnergetics$lastRiderSeat = new HashMap<>();

    @Inject(method = "positionRider", at = @At("RETURN"), remap = false)
    private void electroEnergetics$glueRiderRender(Entity entity, Entity.MoveFunction moveFunction,
                                                   CallbackInfo ci) {
        if (!entity.level().isClientSide())
            return;
        Contraption contraption = getContraption();
        if (contraption == null)
            return;
        net.minecraft.core.BlockPos seat = contraption.getSeatOf(entity.getUUID());
        if (seat == null)
            return;
        // The rider's tick position is whatever the original just applied - getPassengerPosition at
        // partial 1.0, the CURRENT anchor's seat. Entity.setPos resets the interpolation baseline
        // (xo/yo/zo) to that same figure, which makes the rider TELEPORT between ticks while the
        // train renders through its own interpolation - at 5 Blocks/tick that is the violent
        // forward-only shake. Restoring the baseline to the PREVIOUS tick's seat position re-enables
        // the interpolation across exactly the seat travel of this tick: the rider glides with the
        // rendered train instead of stepping.
        Vec3 current = getPassengerPosition(entity, 1.0f);
        Vec3 previous = electroEnergetics$lastRiderSeat.put(entity.getUUID(), current);
        if (previous == null)
            return;                                    // first tick: no baseline to glue yet
        entity.xo = previous.x;
        entity.yo = previous.y;
        entity.zo = previous.z;
    }
}
