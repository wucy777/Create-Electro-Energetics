package com.george_vi.electroenergetics.mixins;

import com.george_vi.electroenergetics.content.railway_electrification.DriverAuthority;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.actors.trainControls.ControlsInteractionBehaviour;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The server half of the one-driver rule: gates who may become Create's controlling
 * player.
 *
 * <p>{@code ControlsInteractionBehaviour} is Create's own right-click handling for a
 * controls block, and on the server it is the ONLY thing that writes
 * {@code AbstractContraptionEntity.CONTROLLED_BY}. Create itself has no concept of a
 * reserved cab - its code path is "stop whoever was controlling, then give it to me" - so
 * a second player walking up to the same train would silently take the controls from the
 * first. This injection runs before that code and, when this mod owns the decision (see
 * {@link DriverAuthority#appliesTo}), either takes the claim, cancels it, or refuses the
 * click outright.
 *
 * <p>A refusal returns {@code true}, which means "handled": Create must not run its
 * default handling, so it must not write its record and the second player must not become
 * the controlling player of anything. They still get to look - the client half opens a
 * read-only cab for them.
 *
 * <p>Server only. The client half is a separate mixin because only the client may touch
 * {@code ControlsHandler}; this class is loaded on both sides, so its inventory must stay
 * common.
 */
@Mixin(value = ControlsInteractionBehaviour.class, remap = false)
public class DriverClaimServerMixin {

    @Inject(method = "handlePlayerInteraction", at = @At("HEAD"), cancellable = true, remap = false)
    private void electroEnergetics$singleDriverGate(Player player, InteractionHand activeHand,
                                                    BlockPos localPos,
                                                    AbstractContraptionEntity contraptionEntity,
                                                    CallbackInfoReturnable<Boolean> cir) {
        // The client's prediction runs the same code first; the answer there is not the
        // authority and is not recorded. Only the server decides.
        if (player.level().isClientSide)
            return;
        if (!(player instanceof ServerPlayer serverPlayer))
            return;
        if (!(contraptionEntity instanceof CarriageContraptionEntity cce))
            return;
        if (DriverAuthority.onInteraction(serverPlayer, localPos, cce))
            cir.setReturnValue(true);
    }
}
