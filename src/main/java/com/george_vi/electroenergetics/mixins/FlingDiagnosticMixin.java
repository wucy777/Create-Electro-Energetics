package com.george_vi.electroenergetics.mixins;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * TEMPORARY DIAGNOSTIC - a measurement, not a fix. Remove once it has answered.
 *
 * <h2>Why this exists</h2>
 *
 * <p>A rider on a fast electric train is ejected from it. Two fixes were shipped and both
 * were refuted by evidence, so this records what actually happens rather than guessing a
 * third time.
 *
 * <p>What was ruled out, and how:
 *
 * <ul>
 *   <li><b>The server's "moved wrongly" rubber-band.</b> Wrapping that teleport was meant
 *       to fix this. The string {@code moved wrongly} occurs ZERO times in every recorded
 *       session, while other WARN lines in the same log appear 1254 times - so logging
 *       works and that branch never runs.</li>
 *   <li><b>{@code handleDamageFromTrain}'s impulse.</b> It adds up to 3 Blocks/tick and is
 *       4x train speed before its clamp, which made it a strong suspect. It is gated on
 *       {@code trainsCauseDamage}, which is ON in this instance, and it would deal about
 *       80 damage at 5 Blocks/tick - instant death. The log shows no damage and no deaths
 *       at all, so it returns early; the gate that does it is likely
 *       {@code if (!entity.onGround())}, since a carriage is not made of world blocks and
 *       a rider standing on one is never "on ground".</li>
 *   <li><b>A curve speed limit.</b> Removed by the user's decision. Not coming back.</li>
 * </ul>
 *
 * <h2>What it records</h2>
 *
 * <p>Once per tick, and only while the local player is inside a contraption's footprint
 * that is moving faster than {@link #MIN_REPORT_SPEED}, it logs:
 *
 * <pre>
 *   carry   the contact-point displacement Create is about to move the rider by
 *   anchor  the contraption's own per-tick travel
 *   moved   how far the player ACTUALLY moved since the previous tick
 * </pre>
 *
 * <p>That set of three is what separates the remaining candidates. If {@code moved} is much
 * larger than {@code carry}, something downstream of the carry is throwing the player - the
 * position resolution in {@code collideEntities}, or a dismount teleport. If {@code carry}
 * itself is much larger than {@code anchor}, the contact-point rotation term is the source
 * after all.
 *
 * <h2>Deliberate limits</h2>
 *
 * <p>CLIENT ONLY, and registered in the client section of the mixin config: it reads the
 * local player, so it must not be loaded on a dedicated server. It also only logs above
 * {@link #MIN_REPORT_SPEED}, so ordinary Create traffic produces no output and the file
 * stays short enough to read. It adds no network traffic. The cost while inactive is one
 * length comparison; the values logged are ones the observed method computed anyway, so the
 * only new work is formatting, and only during a deliberate test.
 */
@Mixin(AbstractContraptionEntity.class)
public class FlingDiagnosticMixin {

    /** Below this per-tick travel nothing is logged: Create's own speeds are not of interest. */
    @Unique
    private static final double MIN_REPORT_SPEED = 1.5d;

    /** The player's position at the previous report, so real movement can be measured. */
    @Unique
    private static Vec3 electroEnergetics$lastPos;

    @Unique
    private static final Logger electroEnergetics$LOG = LoggerFactory.getLogger("CEE-DIAG");

    @ModifyReturnValue(method = "getContactPointMotion", at = @At("RETURN"), remap = false)
    private Vec3 electroEnergetics$diagnoseCarry(Vec3 original) {
        try {
            AbstractContraptionEntity self = (AbstractContraptionEntity) (Object) this;
            if (!self.level().isClientSide)
                return original;

            Vec3 anchor = self.position().subtract(self.getPrevPositionVec());
            if (anchor.length() < MIN_REPORT_SPEED)
                return original;

            LocalPlayer player = Minecraft.getInstance().player;
            if (player == null || !self.getBoundingBox().inflate(0.5d).contains(player.position()))
                return original;

            Vec3 moved = electroEnergetics$lastPos == null
                    ? Vec3.ZERO
                    : player.position().subtract(electroEnergetics$lastPos);
            electroEnergetics$lastPos = player.position();

            electroEnergetics$LOG.info(
                    "carry={} anchor={} moved={} delta={} bps={}",
                    fmt(original), fmt(anchor), fmt(moved), fmt(player.getDeltaMovement()),
                    String.format("%.1f", anchor.length() * 20d));
        } catch (Throwable ignored) {
            // A diagnostic must never be the thing that breaks the game.
        }
        return original;
    }

    /** Compact, fixed-width vector formatting so a tick's line stays readable in the log. */
    @Unique
    private static String fmt(Vec3 v) {
        return String.format("(%6.2f,%6.2f,%6.2f)", v.x, v.y, v.z);
    }
}
