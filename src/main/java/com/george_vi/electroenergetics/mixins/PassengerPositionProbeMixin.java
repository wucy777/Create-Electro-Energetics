package com.george_vi.electroenergetics.mixins;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * TEMPORARY DIAGNOSTIC - see Document\临时诊断日志清单.md
 *
 * <p>Watches {@code getPassengerPosition} and prints what it was asked for and what it produced, or the exception it
 * threw. This is the call that decides where a rider is drawn, and it is the only thing between "the server says the
 * driver is seated" (which the heartbeat proves) and "the driver is outside the train on screen".
 */
@Mixin(AbstractContraptionEntity.class)
public class PassengerPositionProbeMixin {

    @Inject(method = "getPassengerPosition", at = @At("HEAD"), remap = false)
    private void electroEnergetics$probeHead(Entity rider, float partialTicks,
            CallbackInfoReturnable<Vec3> cir) {
        try {
            AbstractContraptionEntity self = (AbstractContraptionEntity) (Object) this;
            if (self.getContraption() == null)
                return;
            var map = self.getContraption().getSeatMapping();
            var seats = self.getContraption().getSeats();
            Integer idx = map.get(rider.getUUID());
            String problem = "";
            if (idx == null)
                problem = " NO-MAPPING";
            else if (idx < 0 || idx >= seats.size())
                problem = " OUT-OF-RANGE idx=" + idx + " seats=" + seats.size();
            if (!problem.isEmpty())
                System.out.println("[passpos] rider=" + rider.getName().getString()
                        + " entities=" + self.getId() + problem
                        + " mappingSize=" + map.size()
                        + " seatCount=" + seats.size());
        } catch (Throwable t) {
            System.out.println("[passpos] probe threw " + t);
        }
    }
}
