package com.george_vi.electroenergetics.mixins;

import com.george_vi.electroenergetics.mixin_interfaces.ICEETrainExtension;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.Train;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Once per second per train, the driving state is dumped on one of two triggers: the lever sitting
 * in BRAKE (a brake the driver did not ask for has to come from somewhere - this names the owner or
 * rules this mod out in one run), or a carriage being BLOCKED (Create's end-of-track loop zeroes
 * the speed and skips movement for as long as the flag holds, so a train stuck in it never
 * produces a brake dump - this trigger names the state instead, with the gear the driver is asking
 * for, and settles "the train refuses to move" against what Create and this mod each see).
 *
 * <p>Cost: one field read per train per tick for the game-time gate, one carriage scan and one
 * string build per second on a trigger. Nothing when the train drives unblocked and unbraked.
 */
@Mixin(Train.class)
public class TrainStopProbeMixin {

    @Inject(method = "tick", at = @At("RETURN"), remap = false)
    private void electroEnergetics$stateDump(Level level, CallbackInfo ci) {
        Train self = (Train) (Object) this;
        try {
            var data = ((ICEETrainExtension) self).getElectricTrainData();
            if (data == null || data.driver == null)
                return;
            long now = level.getGameTime();
            if (now % 20 != 0)
                return;
            var d = data.driver;
            boolean blocked = false;
            boolean incompatible = false;
            for (Carriage c : self.carriages) {
                if (c.blocked)
                    blocked = true;
                if (c.isOnIncompatibleTrack())
                    incompatible = true;
            }
            if (d.gear != com.george_vi.electroenergetics.content.railway_electrification.TrainGear.BRAKE
                    && !blocked && !incompatible)
                return;
            System.out.println(String.format(
                    "[state] t=%d speed=%.3f gear=%s trip=%s armed=%s cw=%d blocked=%s "
                            + "incompatible=%s derailed=%s station=%s hold=%s navDest=%s",
                    now, self.speed, d.gear, d.vigilanceTripped, d.emergencyArmed,
                    d.confirmWaiting, blocked, incompatible, self.derailed,
                    self.getCurrentStation() != null, d.emergencyHeldGear,
                    self.navigation != null && self.navigation.destination != null));
        } catch (Throwable ignored) {
            // A probe must never be the reason a train misbehaves.
        }
    }
}
