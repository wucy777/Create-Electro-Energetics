package com.george_vi.electroenergetics.mixins;

import com.george_vi.electroenergetics.mixin_interfaces.ICEETrainExtension;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.Train;
import com.george_vi.electroenergetics.content.railway_electrification.TrainTractionModel;
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
            // OFF BY DEFAULT. This ran unconditionally: every tick on every train did a game-time read, and every
            // twentieth did a carriage scan, a 32-point braking-distance integration and a String.format, then
            // wrote a line to stdout. The lines are worth having while a fault is being chased, and the operator
            // asked for this one by name - but on a running save it is a log file that grows without limit and a
            // string built per train per second for nobody to read. -Dcee.debugProbe=true turns it back on.
            if (!Boolean.getBoolean("cee.debugProbe"))
                return;
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
            // The hold-space approach is Create's again, so there is no "armed" state of this mod's to report.
            // What is still worth a line is a train that is stopped with the handle off the brake - the
            // end-of-track case - and that is what this dump is for.
            if (d.gear != com.george_vi.electroenergetics.content.railway_electrification.TrainGear.BRAKE)
                return;
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
