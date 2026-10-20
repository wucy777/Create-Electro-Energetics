package com.george_vi.electroenergetics.mixins;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Measures whether a carry actually lands the rider on the seat, by comparing the position the carry
 * asks for with the position the rider has AFTER the carry has run.
 *
 * <h2>What the previous probe proved, and what it left</h2>
 *
 * <p>Measuring the gap BEFORE the carry showed it climbing from about 1.7 Blocks to 7-10.5 Blocks on the
 * client while staying at 1.9-2.2 on the server, and it showed that the seat position equals the
 * carriage anchor exactly (offset 0.00 on every sample). So the seat computation is not drifting - the
 * rider simply is not arriving at the seat.
 *
 * <p>A gap that grows across ticks means each carry moves the rider LESS than the carriage advanced. The
 * carry runs (a later sample exists), it is asked for the right destination, and the rider ends up short.
 *
 * <h2>Why this measures AFTER</h2>
 *
 * <p>Capturing the same rider's position immediately after {@code positionRider} returns, and comparing
 * it with the destination printed on entry, separates the two remaining possibilities in one line:
 *
 * <pre>
 *   CARRY side=.. target=(..) arrived=(..) short=.. gapBefore=..
 * </pre>
 *
 * <p>{@code short} is how far short of the target the rider actually ended up. Non-zero means the move
 * itself is being clipped between the call and the result - which is what a collision-aware
 * {@code MoveFunction} does when a single step is large. Zero means the rider arrived and the next tick's
 * gap was opened somewhere else.
 *
 * <h2>Cost</h2>
 *
 * <p>Two position reads and a subtraction per 20 rider-ticks. Nothing when there is no player passenger.
 */
@Mixin(value = AbstractContraptionEntity.class, remap = false)
public class SeatGapTraceMixin {

    @Unique
    private static final Logger electroEnergetics$LOG = LoggerFactory.getLogger("CEE-CARRY");

    @Unique
    private static int electroEnergetics$n;

    @Unique
    private static Vec3 electroEnergetics$target;

    @Unique
    private static Vec3 electroEnergetics$before;

    @Unique
    private static boolean electroEnergetics$tracing;

    @Inject(
            method = "positionRider(Lnet/minecraft/world/entity/Entity;"
                    + "Lnet/minecraft/world/entity/Entity$MoveFunction;)V",
            at = @At("HEAD"), remap = false)
    private void electroEnergetics$onEnter(Entity passenger, Entity.MoveFunction callback,
                                           CallbackInfo ci) {
        electroEnergetics$tracing = false;
        try {
            if (!(passenger instanceof Player))
                return;
            if (!((Object) this instanceof CarriageContraptionEntity cce))
                return;
            if ((electroEnergetics$n++ % 20) != 0)
                return;

            Vec3 seat = cce.getPassengerPosition(passenger, 1.0f);
            electroEnergetics$target = seat;
            electroEnergetics$before = passenger.position();
            electroEnergetics$tracing = true;

            if (seat == null) {
                electroEnergetics$LOG.info("CARRY side={} target=NULL player={}",
                        side(cce), fmt(passenger.position()));
            }
        } catch (Throwable ignored) {
        }
    }

    @Inject(
            method = "positionRider(Lnet/minecraft/world/entity/Entity;"
                    + "Lnet/minecraft/world/entity/Entity$MoveFunction;)V",
            at = @At("RETURN"), remap = false)
    private void electroEnergetics$onReturn(Entity passenger, Entity.MoveFunction callback,
                                            CallbackInfo ci) {
        try {
            if (!electroEnergetics$tracing)
                return;
            electroEnergetics$tracing = false;

            Vec3 target = electroEnergetics$target;
            Vec3 before = electroEnergetics$before;
            Vec3 after = passenger.position();
            if (target == null)
                return;

            double shortBy = target.distanceTo(after);
            double moved = before.distanceTo(after);
            double needed = before.distanceTo(target);

            electroEnergetics$LOG.info(
                    "CARRY side={} needed={} moved={} short={} before={} after={} target={}",
                    side((CarriageContraptionEntity) (Object) this),
                    String.format("%.2f", needed), String.format("%.2f", moved),
                    String.format("%.2f", shortBy), fmt(before), fmt(after), fmt(target));
        } catch (Throwable ignored) {
            // A diagnostic must never be the thing that breaks the game.
        }
    }

    @Unique
    private static String side(AbstractContraptionEntity e) {
        return e.level().isClientSide() ? "CLIENT" : "SERVER";
    }

    @Unique
    private static String fmt(Vec3 v) {
        return String.format("(%.2f,%.2f,%.2f)", v.x, v.y, v.z);
    }
}
