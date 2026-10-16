package com.george_vi.electroenergetics.mixins;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.ContraptionCollider;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.apache.commons.lang3.mutable.MutableInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Clears the carried rider's residual velocity once per tick. ONE change, nothing else.
 *
 * <h2>What this is testing</h2>
 *
 * <p>Create carries the rider by adding a displacement each tick
 * ({@code ContraptionCollider:307-310}), and then, ten lines later, writes the rider's motion
 * again from the value computed earlier in the pass:
 *
 * <pre>
 *   :309   entity.setPos(entityPosition.x + allowed.x, entityPosition.y, entityPosition.z + allowed.z)
 *   :313   entity.setDeltaMovement(entityMotion)
 * </pre>
 *
 * <p>The position is therefore integrated while the velocity that produced part of it is left in
 * place. On the next tick vanilla applies that velocity again, on top of the position already set,
 * and the pass adds its own displacement as well. Every tick the same motion is counted twice, and
 * the surplus compounds. At Create's own 28 m/s the per-tick surplus is about 1.4 Blocks and is
 * absorbed by ordinary movement; at this mod's 60 m/s it is 3 Blocks per tick, which is enough to
 * throw the rider clear of the train.
 *
 * <p>This is also consistent with the recorded behaviour: position matched the carriage exactly on
 * 59 of 60 ticks while seated ({@code ratio = 1.00}) and diverged by a large multiple of the
 * carriage's motion on the tick the rider left the seat - the moment the carriage stops owning
 * their motion and the accumulated surplus is no longer being overwritten.
 *
 * <p>So: clear it, and nothing else. No position write, no new carry, no change to Create's own
 * arithmetic. If the hypothesis is right the divergence stops growing; if it is wrong nothing has
 * been risked and the result is still clean evidence.
 *
 * <h2>Why at RETURN and not at the carry</h2>
 *
 * <p>{@code :313} runs AFTER the carry and would overwrite anything set earlier in the pass. The
 * only place a clear survives is after the method has finished.
 *
 * <h2>Why it must be scoped</h2>
 *
 * <p>{@code Entity.move} routes through {@code setPos} into {@code setPosRaw} - confirmed by
 * disassembling the shipped client jar - so clearing velocity unconditionally would wipe the
 * player's momentum every tick and freeze ordinary walking. It is therefore applied only when the
 * carriage registered the rider as a contact during the pass that just finished.
 *
 * <p>That discriminator is exact rather than approximate. {@code registerColliding}
 * ({@code AbstractContraptionEntity:141-143}) stores a fresh {@code MutableInt} at zero, and the
 * entity increments those counters on its own tick and removes entries above three. Entity ticks
 * run before {@code ClientTickEvent.Post}, where this collider pass happens, so a value of zero
 * after the pass means this carriage put them there DURING this pass - the rider is standing on it
 * right now. A rider who is jumping, falling, or in the aisle reads as not in contact and is left
 * completely alone.
 *
 * <h2>Scope and cost</h2>
 *
 * <p>CLIENT ONLY, train carriages only, local player only. The server skips clients outright
 * ({@code ContraptionCollider:120-121}), so the client stays authoritative for its own position as
 * it already is for Create's carry. No packets, no position writes, and the counters report
 * whether it fired at all, so a silent result is distinguishable from a no-op.
 */
@Mixin(ContraptionCollider.class)
public class CarriageClearVelocityMixin {

    @Unique
    private static final Logger electroEnergetics$LOG = LoggerFactory.getLogger("CEE-CARRY");

    @Unique
    private static final int SUMMARY_EVERY = 200;
    @Unique
    private static final int MAX_LINES = 80;

    @Unique
    private static int electroEnergetics$lines;
    @Unique
    private static long electroEnergetics$lastSummary = Long.MIN_VALUE;
    @Unique
    private static int electroEnergetics$cleared;
    @Unique
    private static double electroEnergetics$clearedSum;
    @Unique
    private static double electroEnergetics$clearedMax;

    /**
     * Clears the rider's velocity after the pass, if the carriage registered them as a contact.
     *
     * <p>At RETURN because the pass writes the velocity itself at {@code :313}, after the carry.
     */
    @Inject(method = "collideEntities", at = @At("RETURN"), remap = false)
    private static void electroEnergetics$clearResidual(AbstractContraptionEntity contraption,
                                                        CallbackInfo ci) {
        try {
            if (!contraption.level().isClientSide())
                return;
            if (!(contraption instanceof CarriageContraptionEntity))
                return;

            LocalPlayer player = Minecraft.getInstance().player;
            if (player == null || player.isPassenger() || player.isSpectator())
                return;

            // Exact contact test: zero means this carriage registered them during THIS pass.
            MutableInt contact = contraption.collidingEntities.get(player);
            if (contact == null || contact.intValue() != 0)
                return;

            double before = Math.hypot(player.getDeltaMovement().x, player.getDeltaMovement().z);
            if (before < 1.0e-4d)
                return;

            player.setDeltaMovement(0, 0, 0);

            electroEnergetics$cleared++;
            electroEnergetics$clearedSum += before;
            if (before > electroEnergetics$clearedMax)
                electroEnergetics$clearedMax = before;

            electroEnergetics$maybeSummary(contraption.level().getGameTime());
        } catch (Throwable ignored) {
            // A velocity clear must never be the thing that breaks the client.
        }
    }

    @Unique
    private static void electroEnergetics$maybeSummary(long tick) {
        // The sentinel is tested FIRST. Written as `tick - Long.MIN_VALUE` this overflows to a
        // negative number that is always below the interval, so the guard would return forever and
        // no summary would ever print - a mistake that already cost one test run to find.
        if (electroEnergetics$lastSummary == Long.MIN_VALUE) {
            electroEnergetics$lastSummary = tick;
            return;
        }
        if (tick - electroEnergetics$lastSummary < SUMMARY_EVERY)
            return;
        electroEnergetics$lastSummary = tick;
        if (electroEnergetics$lines++ >= MAX_LINES)
            return;

        electroEnergetics$LOG.info(
                "CARRY CLEAR cleared={} residualAvg={} residualMax={}",
                electroEnergetics$cleared,
                String.format("%.4f", electroEnergetics$cleared == 0 ? 0d
                        : electroEnergetics$clearedSum / electroEnergetics$cleared),
                String.format("%.4f", electroEnergetics$clearedMax));

        electroEnergetics$cleared = 0;
        electroEnergetics$clearedSum = 0;
        electroEnergetics$clearedMax = 0;
    }
}
