package com.george_vi.electroenergetics.mixins;

import com.george_vi.electroenergetics.content.railway_electrification.rider.CarriageRiderFrame;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stops Create's carry from writing a position the rider frame already owns.
 *
 * <h2>The double-write this exists to remove</h2>
 *
 * <p>Measured per tick, with the frame active:
 *
 * <pre>
 *   t=888832 #1  d= 0.001  Player                    &lt;- the frame
 *   t=888832 #2  d=11.241  AbstractContraptionEntity   &lt;- Create's carry
 *   t=888833 #1  d= 0.002  Player
 *   t=888833 #2  d=22.546  AbstractContraptionEntity
 * </pre>
 *
 * <p>against a carriage speed of 4.63 Blocks/tick from the flight recorder in the same session. Both
 * writers believed they were responsible: the frame placed the player from the carriage's frame, then the
 * carry moved them again from its own anchor, and the two displacements added. That is where the 2x to 5x
 * values came from, and it is why every measurement of "how far did the rider move" in this investigation
 * has been unreliable.
 *
 * <p>Suppression is at the head of the carry, so the second write never happens rather than being undone
 * afterwards. One map lookup decides it.
 *
 * <h2>Why this is a separate mixin</h2>
 *
 * <p>{@code positionRider} is declared on {@code AbstractContraptionEntity}, not on {@code Player}. A first
 * attempt put this injection inside the {@code Player}-targeted mixin, which compiles and applies but can
 * never match - the method does not exist on that class. The ownership checker does not catch it because it
 * verifies the method name against the named class only when the two are supplied together; this file keeps
 * them together by construction.
 *
 * <h2>Cost</h2>
 *
 * <p>One map lookup per carry attempt. A player not tracked by the frame falls through to vanilla.
 */
@Mixin(value = AbstractContraptionEntity.class, remap = false)
public class CarriageCarrySuppressMixin {

    @Unique
    private static final Logger electroEnergetics$LOG = LoggerFactory.getLogger("CEE-SUPPRESS");

    @Inject(
            method = "positionRider(Lnet/minecraft/world/entity/Entity;"
                    + "Lnet/minecraft/world/entity/Entity$MoveFunction;)V",
            at = @At("HEAD"), cancellable = true, remap = false)
    private void electroEnergetics$suppress(Entity passenger, Entity.MoveFunction callback,
                                            CallbackInfo ci) {
        try {
            if (!(passenger instanceof Player player))
                return;
            if (!player.level().isClientSide())
                return;
            if (CarriageRiderFrame.get(player) == null)
                return;

            ci.cancel();
        } catch (Throwable ignored) {
            // Falling through to the normal carry is always safe.
        }
    }
}