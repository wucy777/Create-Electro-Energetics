package com.george_vi.electroenergetics.mixins;

import com.george_vi.electroenergetics.content.railway_electrification.ElectricTrainData;
import com.george_vi.electroenergetics.mixin_interfaces.ICEETrainExtension;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Carries a seated player without the collision test, on a train faster than Create's design.
 *
 * <h2>Why this is a mixin on the PARENT</h2>
 *
 * <p>{@code positionRider(Entity, MoveFunction)} is declared once, on
 * {@code AbstractContraptionEntity}, and subclasses do not override it. The first version of this
 * targeted {@code CarriageContraptionEntity} and crashed the client at startup with
 *
 * <pre>
 *   &#64;ModifyVariable ... could not find any targets matching
 *   'positionRider(Lnet/minecraft/world/entity/Entity;...Entity$MoveFunction;)V'
 *   in com/simibubi/create/content/trains/entity/CarriageContraptionEntity
 * </pre>
 *
 * <p>Mixin resolves targets against the named class only; it does not walk up to the declaring
 * superclass. The check that was supposed to catch this - "the method exists in the Create jar" - was
 * true and answered the wrong class, which is the same mistake as an assertion that passes for a reason
 * other than the one it claims.
 *
 * <h2>The defect</h2>
 *
 * <p>Create hands the seat position to a {@code MoveFunction}; vanilla's is collision-aware, which is
 * what makes a passenger slide along a wall instead of through one. At 5 Blocks per tick against a
 * 0.6 x 1.8 player box that resolves as "you hit something" and the move is clipped. Measured on a live
 * session at 80 Blocks/Second: rider displacement pinned at 0.90 Blocks per tick while the carriage
 * advanced 4.03, with a 16.08 Block jump on the dismount tick as the deficit settled. That is the
 * throw; the same clipping is the floor and wall pass-through, and the backwards drift when jumping.
 *
 * <p>Create's own ceiling is 1.4-2 Blocks per tick, which is exactly why a fuel train never shows it.
 *
 * <h2>What this does</h2>
 *
 * <p>Replaces the callback with a direct {@code setPos} for an electric train and a player passenger.
 * The destination comes from {@code contraption.getSeatOf}, so the rider is inside the carriage by
 * construction - the only boxes a collision test could hit were the carriage's own.
 *
 * <p>{@code hasTractionMotors} rather than {@code underElectricControl}: the carry must stay rigid while
 * the train coasts or is handed to Create too, since those are the states a player is aboard at speed.
 * A fuel train has no motors and keeps vanilla behaviour untouched.
 */
@Mixin(AbstractContraptionEntity.class)
public class AbstractContraptionEntityCarryMixin {

    @ModifyVariable(
            method = "positionRider(Lnet/minecraft/world/entity/Entity;"
                    + "Lnet/minecraft/world/entity/Entity$MoveFunction;)V",
            at = @At("HEAD"),
            argsOnly = true,
            index = 2,
            remap = false)
    private Entity.MoveFunction electroEnergetics$carryWithoutCollision(
            Entity.MoveFunction original, Entity passenger) {
        if (!(passenger instanceof Player))
            return original;
        if (!((Object) this instanceof CarriageContraptionEntity cce))
            return original;
        Carriage carriage = cce.getCarriage();
        if (carriage == null || carriage.train == null)
            return original;
        ElectricTrainData data = ((ICEETrainExtension) carriage.train).getElectricTrainData();
        if (data == null || !data.hasTractionMotors)
            return original;
        return (entity, x, y, z) -> entity.setPos(x, y, z);
    }
}
