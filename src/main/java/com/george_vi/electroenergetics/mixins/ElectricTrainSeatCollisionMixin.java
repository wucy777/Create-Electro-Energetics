package com.george_vi.electroenergetics.mixins;

import com.george_vi.electroenergetics.content.railway_electrification.DriverAuthority;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.actors.seat.SeatInteractionBehaviour;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prevents Create's collision auto-seat from putting mobs in electric train seats. */
@Mixin(value = SeatInteractionBehaviour.class, remap = false)
public class ElectricTrainSeatCollisionMixin {

    @Inject(method = "handleEntityCollision", at = @At("HEAD"), cancellable = true, remap = false)
    private void electroEnergetics$keepNonPlayersOffElectricTrains(
            Entity entity, BlockPos localPos, AbstractContraptionEntity contraptionEntity,
            CallbackInfo ci) {
        if (entity instanceof Player || entity.level().isClientSide
                || !(contraptionEntity instanceof CarriageContraptionEntity cce))
            return;
        Carriage carriage = cce.getCarriage();
        if (carriage != null && carriage.train != null && DriverAuthority.appliesTo(carriage.train))
            ci.cancel();
    }
}
