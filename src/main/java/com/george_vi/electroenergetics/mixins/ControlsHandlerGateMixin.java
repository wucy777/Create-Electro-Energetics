package com.george_vi.electroenergetics.mixins;

import com.george_vi.electroenergetics.content.railway_electrification.TrainHudData;
import com.simibubi.create.content.contraptions.actors.trainControls.ControlsHandler;
import com.simibubi.create.content.contraptions.actors.trainControls.ControlsInputPacket;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import net.createmod.catnip.platform.services.NetworkHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.UUID;

/**
 * Stops a read-only cab from sending Create's control packets at all.
 *
 * <h2>Why the client matters when the server already refuses</h2>
 *
 * <p>{@code ControlsHandler.tick} runs while Create believes the local player holds the controls,
 * and it sends {@code ControlsInputPacket} every few ticks as a keep-alive even when nothing is
 * pressed - plus one immediately on ESC. A viewer of a read-only cab is in exactly that state
 * locally (Create's panel is open; the server never granted them control), so without this the
 * viewer would spend a small packet a few times a second, forever, on input the server is only
 * going to drop.
 *
 * <p>This is not the enforcement - the server refuses those packets regardless
 * ({@code ControlsInputGateMixin}), and a modified client can send anything. It is the client half
 * of the same rule: a panel that is not allowed to control should not be producing control traffic,
 * and ESC on a read-only cab should close the panel without spending a packet to say so.
 *
 * <p>Gated on the claim having been announced, so a train this feature does not own keeps upstream's
 * behaviour exactly. The target train comes from the packet rather than from
 * {@code ControlsHandler.getContraption()}, because ESC's stop packet is sent after the handler has
 * already cleared its own reference - reading its state there would let exactly that packet through.
 */
@Mixin(ControlsHandler.class)
public class ControlsHandlerGateMixin {

    @Redirect(
            method = "tick",
            at = @At(value = "INVOKE",
                    target = "Lnet/createmod/catnip/platform/services/NetworkHelper;sendToServer(Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;)V"),
            remap = false)
    private static void electroEnergetics$noControlTrafficFromViewer(NetworkHelper network,
                                                                     CustomPacketPayload payload) {
        if (payload instanceof ControlsInputPacket packet && isViewerOnly(packet))
            return;
        network.sendToServer(payload);
    }

    /** Whether this input packet belongs to a gated train this client does not drive. */
    private static boolean isViewerOnly(ControlsInputPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null)
            return false;
        Entity entity = mc.level.getEntity(packet.contraptionEntityId());
        if (!(entity instanceof CarriageContraptionEntity cce))
            return false;
        Carriage carriage = cce.getCarriage();
        Train train = carriage == null ? null : carriage.train;
        if (train == null || !TrainHudData.hasDriverClaim(train.id))
            return false;
        UUID local = mc.player.getUUID();
        return !TrainHudData.isDriver(train.id, local);
    }
}
