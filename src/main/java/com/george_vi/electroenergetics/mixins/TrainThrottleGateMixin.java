package com.george_vi.electroenergetics.mixins;

import com.george_vi.electroenergetics.content.railway_electrification.DriverAuthority;
import com.george_vi.electroenergetics.content.railway_electrification.ElectricTrainData;
import com.george_vi.electroenergetics.content.railway_electrification.TrainDriverState;
import com.george_vi.electroenergetics.mixin_interfaces.ICEETrainExtension;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.TrainHUDUpdatePacket;
import com.simibubi.create.content.trains.entity.Train;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stops a non-driver from moving the train's throttle, which is Create's speed-wheel control.
 *
 * <h2>Why this is a control channel at all</h2>
 *
 * <p>{@code TrainHUDUpdatePacket.Serverbound} is what the experience-bar speed wheel sends, and it
 * writes {@code Train.throttle} directly. That value scales the top speed of a Create-driven train
 * ({@code CarriageContraptionEntity.control}, {@code Navigation}), so on an electric train handed
 * back to Create it is a real control - a player who is not the driver could wind the throttle down
 * and stop the driver's train from pulling away, or wind it up to the ceiling.
 *
 * <p>The mod's own client already takes the wheel away from a lever-driven train
 * ({@code TrainHUDMixin.onScroll}), and in CREATE mode the wheel is legitimately Create's - which
 * is exactly why the rule has to live on the server: the client's suppression is a convenience, and
 * a viewer's read-only cab must not be able to reach the throttle by any route.
 *
 * <p>Addresses the driver only, like every other control packet. Non-gated trains are untouched.
 */
@Mixin(value = TrainHUDUpdatePacket.Serverbound.class, remap = false)
public class TrainThrottleGateMixin {

    @Inject(method = "handle", at = @At("HEAD"), cancellable = true, remap = false)
    private void electroEnergetics$driverOnlyThrottle(ServerPlayer player, CallbackInfo ci) {
        Train train = Create.RAILWAYS.sided(player.level()).trains
                .get(((TrainHUDUpdateAccessor) (Object) this).electroEnergetics$trainId());
        if (train == null || !DriverAuthority.appliesTo(train))
            return;
        ElectricTrainData data = ((ICEETrainExtension) train).getElectricTrainData();
        TrainDriverState driver = data.driver;
        if (!driver.isDriver(player.getUUID()))
            ci.cancel();
    }
}
