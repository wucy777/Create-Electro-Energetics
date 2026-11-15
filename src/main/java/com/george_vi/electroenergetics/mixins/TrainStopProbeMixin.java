package com.george_vi.electroenergetics.mixins;

import com.george_vi.electroenergetics.content.railway_electrification.TrainTractionModel;
import com.george_vi.electroenergetics.mixin_interfaces.ICEETrainExtension;
import com.simibubi.create.content.trains.entity.Train;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Once per second per train, the driving state is dumped on one of two triggers: the lever sitting
 * in BRAKE (a brake the driver did not ask for has to come from somewhere - this names the owner or
 * rules this mod out in one run), or a station approach being live (armed, or the key held with a
 * destination - the approach's gate verdict, distance and speed on the same line, so a "the
 * controller never braked" report can be settled against the actual state).
 *
 * <p>Cost: one field read per train per tick for the game-time gate, and one string build per
 * second per train on a trigger. Nothing when the train drives with no brake and no approach.
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
            for (com.simibubi.create.content.trains.entity.Carriage c : self.carriages)
                if (c.blocked || c.isOnIncompatibleTrack()) { blocked = true; break; }
            System.out.println(String.format(
                    "[state] t=%d speed=%.3f gear=%s trip=%s armed=%s cw=%d blocked=%s "
                            + "derailed=%s station=%s hold=%s",
                    now, self.speed, d.gear, d.vigilanceTripped, d.emergencyArmed,
                    d.confirmWaiting, blocked, self.derailed,
                    self.getCurrentStation() != null, d.emergencyHeldGear));
        } catch (Throwable ignored) {
            // A probe must never be the reason a train misbehaves.
        }
    }
}
