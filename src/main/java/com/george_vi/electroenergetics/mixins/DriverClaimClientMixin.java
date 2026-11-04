package com.george_vi.electroenergetics.mixins;

import com.george_vi.electroenergetics.content.railway_electrification.TrainHudData;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.actors.trainControls.ControlsInteractionBehaviour;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

/**
 * The client half of the one-driver rule: a player who is not the driver gets a read-only
 * cab instead of Create's local prediction of control.
 *
 * <h2>Why the client has to be told anything at all</h2>
 *
 * <p>Create handles a controls-block click locally before the server hears about it: the
 * client calls the same {@code ControlsInteractionBehaviour}, which sets the local
 * "controlling player" and opens the cab. Left alone, a second player's client would
 * therefore believe it was driving for a tick, show an interactive panel, and - worse -
 * write its own uuid into the synced "controlling player" data, which then suppresses the
 * mirror repair that would otherwise keep the two sides in step.
 *
 * <p>So when the authority has already been announced and it is somebody else, this
 * injection consumes the click: Create's prediction does not run and does not claim
 * anything, but the cab is opened by hand ({@link ControlsHandler#startControlling}), which
 * is what makes the panel visible. The player sees the same panel the driver does and can
 * operate none of it - every keypress is refused on the server as well, this is only the
 * client's half of the same rule.
 *
 * <p>When the client has not been told who drives yet, and when the train is free, Create's
 * normal path is left alone: it is the optimistic half of the claim handshake, and the
 * authority corrects it a tick later either way.
 *
 * <p>Client only, and a separate class from the server half because only this side may
 * reference {@code ControlsHandler}.
 */
@Mixin(value = ControlsInteractionBehaviour.class, remap = false)
public class DriverClaimClientMixin {

    @Inject(method = "handlePlayerInteraction", at = @At("HEAD"), cancellable = true, remap = false)
    private void electroEnergetics$readOnlyCab(Player player, InteractionHand activeHand,
                                               BlockPos localPos,
                                               AbstractContraptionEntity contraptionEntity,
                                               CallbackInfoReturnable<Boolean> cir) {
        if (!player.level().isClientSide)
            return;
        if (!(contraptionEntity instanceof CarriageContraptionEntity cce))
            return;
        Carriage carriage = cce.getCarriage();
        Train train = carriage == null ? null : carriage.train;
        if (train == null)
            return;
        if (!TrainHudData.hasDriverClaim(train.id))
            return;   // not told yet: leave the optimistic prediction to Create
        UUID owner = TrainHudData.driverClaim(train.id);
        if (owner == null)
            return;   // free: this click is how a driver claims it
        if (owner.equals(player.getUUID()))
            return;   // the driver: Create's own handling is what releases the controls

        // Somebody else drives. The operator's rule is that the click does NOTHING AT ALL - not even a
        // read-only view of the panel. Every panel and every seat of a driven train is closed to everybody
        // else, which is what keeps the seating unambiguous.
        //
        // The click is still consumed: letting Create's local prediction run would write this player's uuid
        // into the synced "controlling player" field, which is the one thing the mirror repair cannot undo
        // cleanly. Consuming it without opening the cab is the whole behaviour.
        cir.setReturnValue(true);
    }
}
