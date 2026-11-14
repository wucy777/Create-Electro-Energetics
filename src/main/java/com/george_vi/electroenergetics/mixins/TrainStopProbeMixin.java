package com.george_vi.electroenergetics.mixins;

import com.george_vi.electroenergetics.mixin_interfaces.ICEETrainExtension;
import com.simibubi.create.content.trains.entity.Train;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Once per second per train, when the lever sits in BRAKE without any of the flags that justify
 * it, the whole driving state is dumped. A brake the driver did not ask for has to come from
 * somewhere; this names the owner or rules this mod out in one run.
 *
 * <p>Cost: one field read per train per tick for the game-time gate, and one string build per
 * second per train while the lever is in BRAKE. Nothing when the train drives.
 */
@Mixin(Train.class)
public class TrainStopProbeMixin {

    @Inject(method = "tick", at = @At("RETURN"), remap = false)
    private void electroEnergetics$stateDump(Level level, CallbackInfo ci) {
        Train self = (Train) (Object) this;
        try {
            var data = ((com.george_vi.electroenergetics.mixin_interfaces.ICEETrainExtension) self)
                    .getElectricTrainData();
            if (data == null || data.driver == null)
                return;
            var d = data.driver;
            if (d.gear != com.george_vi.electroenergetics.content.railway_electrification.TrainGear.BRAKE)
                return;
            long now = level.getGameTime();
            if (now % 20 != 0)
                return;
            System.out.println(String.format(
                    "[state] t=%d speed=%.3f gear=%s trip=%s armed=%s ovl=%s ovlBrk=%s "
                            + "unseatedTicks=%d cw=%d blocked=%s derailed=%s station=%s "
                            + "hold=%s arrivals=%s",
                    now, self.speed, d.gear, d.vigilanceTripped, d.emergencyArmed,
                    d.overloaded, d.overloadBraking, d.unseatedTicks, d.confirmWaiting,
                    d.blockedParked, self.derailed, self.getCurrentStation() != null,
                    d.emergencyHeldGear, d.autoArrive));
        } catch (Throwable ignored) {
            // A probe must never be the reason a train misbehaves.
        }
    }
}
