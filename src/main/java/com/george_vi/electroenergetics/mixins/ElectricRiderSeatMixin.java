package com.george_vi.electroenergetics.mixins;

import com.george_vi.electroenergetics.content.railway_electrification.ElectricTrainData;
import com.george_vi.electroenergetics.mixin_interfaces.ICEETrainExtension;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
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
 * Keeps a rider seated when they leave the controls of a fast electric train.
 *
 * <h2>The behaviour this replaces, and why it was wrong</h2>
 *
 * <p>Leaving the seat of a moving train hands the player to {@code ContraptionCollider}'s surface branch,
 * which carries anything standing on a contraption along with it. Measured at speed:
 *
 * <pre>
 *   t=863769  p=(334.50,-59.00,-398.50)  pax=0  ContraptionCollider:284
 *   t=863769  p=(334.50,-59.00,-394.62)  d=3.88 ContraptionCollider:309
 *   t=863769  p=(334.50,-59.05,-394.62)  dY=-0.05 LocalPlayer:915
 * </pre>
 *
 * <p>The player is dragged along at the carriage's speed - 3.88 Blocks per tick - while gravity pulls
 * them down through the floor. Every previous attempt adjusted WHERE they landed:
 *
 * <ul>
 *   <li>pin the dismount value to their own position - they are still inside the collider, still dragged;</li>
 *   <li>ease the forced teleport - the displacement is unchanged, only its animation;</li>
 *   <li>suppress the teleport - same as the first, and measured as such;</li>
 *   <li>place them on ground beside the carriage - that is not what was asked for, and at this speed the
 *       ground beside the carriage is a moving target.</li>
 * </ul>
 *
 * <p>All four moved the problem around. None addressed it, because the problem is not where the player is
 * put: it is that a rider is detached at 70-100 Blocks per second. At real speeds a passenger does not
 * step off a moving train, and this mod allows speeds where the game's collision model has no sensible
 * answer for someone who tries.
 *
 * <h2>What this does</h2>
 *
 * <p>When a player leaves the seat of an electric train that is above Create's own ceiling, the seat is
 * kept. They stop driving - the controls are released as normal - but they remain a passenger, so the
 * carry continues to hold them in place and nothing hands them to the collider.
 *
 * <p>Below the threshold the vanilla behaviour is untouched: the player steps off normally, because at
 * Create's own speeds stepping off is what the game is designed for.
 *
 * <h2>Why the threshold is Create's ceiling and not a new number</h2>
 *
 * <p>{@code train.maxSpeed()} is exactly the speed above which Create's own model stops being the model
 * in play. Below it, Create's answer is correct and is kept. Above it, this mod is the thing that put the
 * train there, so this mod is responsible for the rider. The boundary is therefore not a tuning value - it
 * is the same line the rest of this feature already uses.
 *
 * <h2>Cost</h2>
 *
 * <p>One speed comparison and one train lookup, on the single tick a player leaves a seat. Nothing runs
 * while they are riding or on foot.
 */
@Mixin(value = com.simibubi.create.content.contraptions.AbstractContraptionEntity.class,
        remap = false)
public class ElectricRiderSeatMixin {

    @Unique
    private static final Logger electroEnergetics$LOG = LoggerFactory.getLogger("CEE-SEAT-KEEP");

    /**
     * Runs before the passenger is removed, so the seat can be re-established if the speed calls for it.
     *
     * <p>{@code removePassenger} is where Create computes the dismount location and where vanilla detaches
     * the entity. Re-attaching after that point would cost a second boarding, so the decision is made
     * here and the removal is cancelled instead.
     */
    @Inject(method = "removePassenger", at = @At("HEAD"), cancellable = true, remap = false)
    private void electroEnergetics$keepSeatAtSpeed(Entity passenger, CallbackInfo ci) {
        try {
            if (!(passenger instanceof Player))
                return;

            if (!((Object) this instanceof CarriageContraptionEntity self))
                return;                     // a plain contraption, not a carriage
            if (self.level().isClientSide())
                return;                     // the server owns the passenger list

            var carriage = self.getCarriage();
            if (carriage == null || carriage.train == null)
                return;

            ElectricTrainData data = ((ICEETrainExtension) carriage.train).getElectricTrainData();
            if (data == null || !data.hasTractionMotors)
                return;                     // fuel train or metro: Create's behaviour, unchanged

            double speed = Math.abs(carriage.train.speed);
            double ceiling = carriage.train.maxSpeed();
            if (speed <= ceiling)
                return;                     // inside Create's model: let them step off

            // Above Create's ceiling this mod is what put the train there, so the rider stays aboard.
            // The seat is untouched, which means the carry keeps holding them and the collider never
            // gets a chance to drag them.
            electroEnergetics$LOG.info(
                    "SEAT-KEPT player={} speed={} ceiling={} - dismount refused above Create's model",
                    passenger.getName().getString(),
                    String.format("%.2f", speed), String.format("%.2f", ceiling));
            ci.cancel();
        } catch (Throwable ignored) {
            // A seat fix must never be the thing that breaks a dismount.
        }
    }
}
