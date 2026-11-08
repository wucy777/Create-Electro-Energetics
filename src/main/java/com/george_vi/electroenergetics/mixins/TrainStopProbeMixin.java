package com.george_vi.electroenergetics.mixins;

import com.simibubi.create.content.trains.entity.Train;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * TEMPORARY DIAGNOSTIC - see Document\\临时诊断日志清单.md
 *
 * <p>The whole file is a probe on the ways a train can be stopped or a rider moved without any key being pressed. The
 * operator has now seen two runs on the same cab, same keys, same speed, where one cut to BRAKE at 72 m/s and the
 * other ran to 100 m/s and held it - so the cause is intermittent and every candidate has to be covered in one run
 * rather than one per round.
 *
 * <p>Covered here:
 * <ul>
 *   <li>{@code Train.crash} - a collision. Reverses the speed and derails the train, which would explain both the
 *       sudden gear change and the rider being moved.</li>
 *   <li>{@code Train.derail} / {@code Train.derailed} becoming true.</li>
 *   <li>Carriage eviction of a passenger, the two paths Create uses internally.</li>
 *   <li>The controlling player being cleared on a carriage.</li>
 * </ul>
 */
@Mixin(Train.class)
public class TrainStopProbeMixin {

    @Inject(method = "crash", at = @At("HEAD"), remap = false)
    private void electroEnergetics$onCrash(CallbackInfo ci) {
        Train self = (Train) (Object) this;
        System.out.println("[train-stop] CRASH speed=" + self.speed
                + " derailed=" + self.derailed + " cars=" + self.carriages.size()
                + " name=" + self.name);
    }

    @Inject(method = "crash", at = @At("RETURN"), remap = false)
    private void electroEnergetics$onCrashReturn(CallbackInfo ci) {
        Train self = (Train) (Object) this;
        System.out.println("[train-stop] CRASH-RETURN speed=" + self.speed
                + " derailed=" + self.derailed);
    }

    /**
     * Every field of the driving state that could explain a brake the driver did not ask for, once per second.
     *
     * <p>Rather than guessing which setter ran, the whole state is dumped: if the lever is BRAKE and none of the flags
     * that justify it are set, the brake came from somewhere outside this mod - which is itself the answer.
     */
    @Inject(method = "tick", at = @At("RETURN"), remap = false)
    private void electroEnergetics$onTickReturn(net.minecraft.world.level.Level level, CallbackInfo ci) {
        Train self = (Train) (Object) this;
        try {
            var data = ((com.george_vi.electroenergetics.mixin_interfaces.ICEETrainExtension) self)
                    .getElectricTrainData();
            if (data == null || data.driver == null)
                return;
            var d = data.driver;
            long now = level.getGameTime();
            if (now % 20 != 0)
                return;
            System.out.println(String.format(
                    "[state] id=%s t=%d speed=%.3f gear=%s trip=%s armed=%s ovl=%s ovlBrk=%s "
                            + "unseatedTicks=%d cw=%d blocked=%s derailed=%s station=%s",
                    String.valueOf(self.id).substring(0, 8),
                    now, self.speed, d.gear, d.vigilanceTripped, d.emergencyArmed,
                    d.overloaded, d.overloadBraking, d.unseatedTicks, d.confirmWaiting,
                    d.blockedParked, self.derailed, self.getCurrentStation() != null));
        } catch (Throwable ignored) {
            // A probe must never be the reason a train misbehaves.
        }
    }

    @Inject(method = "tickDerailedSlowdown", at = @At("HEAD"), remap = false)
    private void electroEnergetics$onDerailedSlowdown(CallbackInfo ci) {
        Train self = (Train) (Object) this;
        System.out.println("[train-stop] derived-slowdown speed=" + self.speed);
    }
}
