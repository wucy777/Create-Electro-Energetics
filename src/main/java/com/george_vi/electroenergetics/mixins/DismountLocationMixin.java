package com.george_vi.electroenergetics.mixins;

import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Puts a dismounting player on the ground BESIDE the carriage, not on the seat they just left.
 *
 * <h2>What the last two builds got wrong</h2>
 *
 * <p>The forced teleport after a dismount was treated as the bug and suppressed. The retest said the
 * player was still thrown, and the trace showed why the suppression made a different symptom:
 *
 * <pre>
 *   t=860427  p=(332.77,-58.18,-492.31)  pax=0   dismounted, zero displacement
 *   t=860428  p=(334.19,-58.18,-488.90)  d=3.70  ContraptionCollider:309
 *   t=860429  p=(335.22,-58.26,-485.35)  d=3.70  ContraptionCollider:309
 *   t=860430  p=(335.85,-58.36,-481.71)  d=3.69  ContraptionCollider:309
 * </pre>
 *
 * <p>Blocked inside the carriage, the player is picked up by the collider's surface branch, which carries
 * whatever is standing on a contraption along with it - here at 3.5 Blocks per tick, the carriage's own
 * speed. Gravity meanwhile pulls them down at 0.1 Blocks per tick, so they slide along the carriage at
 * 70 m/s and sink through its floor. That is "being thrown", exactly as reported.
 *
 * <p>So the teleport is not the bug; its DESTINATION is. Create's dismount location is
 * {@code getPassengerPosition}, which is the seat - inside the carriage. At Create's own speeds the
 * player is pushed along slowly enough to walk off; at this mod's speeds they cannot.
 *
 * <h2>What this does</h2>
 *
 * <p>For an electric train, replaces that destination with a position on solid ground beside the
 * carriage: the seat's horizontal position moved sideways off the carriage, then dropped to the highest
 * solid block below it.
 *
 * <p>Sideways before down, not down from the seat: dropping straight down from the seat lands on the
 * carriage's own floor or its bogies, and the player is still inside the collider. Moving out first puts
 * them clear of it.
 *
 * <p>The search is bounded and cheap: a handful of block lookups, once per dismount. Nothing runs while
 * the player is aboard.
 *
 * <h2>Why ground and not "wherever they were"</h2>
 *
 * <p>Leaving them at the seat position is what the previous build did, and it is what the collider picks
 * up. A dismount has to end somewhere the carriage will not drag them from.
 */
@Mixin(value = com.simibubi.create.content.contraptions.AbstractContraptionEntity.class,
        remap = false)
public class DismountLocationMixin {

    @Unique
    private static final Logger electroEnergetics$LOG = LoggerFactory.getLogger("CEE-DISMOUNT");

    @Inject(method = "getDismountLocationForPassenger", at = @At("RETURN"),
            cancellable = true, remap = false)
    private void electroEnergetics$besideTheCarriage(LivingEntity rider,
                                                     CallbackInfoReturnable<Vec3> cir) {
        try {
            if (!((Object) this instanceof CarriageContraptionEntity self))
                return;
            if (self.level().isClientSide())
                return;
            if (!(rider instanceof Player))
                return;

            var carriage = self.getCarriage();
            if (carriage == null || carriage.train == null)
                return;
            var data = ((com.george_vi.electroenergetics.mixin_interfaces.ICEETrainExtension)
                    carriage.train).getElectricTrainData();
            if (data == null || !data.hasTractionMotors)
                return;                     // fuel train: Create's own behaviour, unchanged

            Vec3 seat = cir.getReturnValue();
            if (seat == null)
                return;

            Vec3 ground = electroEnergetics$groundBeside(self.level(), seat);
            if (ground == null)
                return;                     // nowhere safe found; leave Create's answer alone

            cir.setReturnValue(ground);
            electroEnergetics$LOG.info("DISMOUNT seat={} ground={}",
                    fmt(seat), fmt(ground));
        } catch (Throwable ignored) {
            // A dismount fix must never break a dismount.
        }
    }

    /**
     * A standing position on solid ground beside the carriage, or null when none is within reach.
     *
     * <p>Steps sideways in both directions from the seat and takes the first column that has ground
     * within a few Blocks below. Bounded to a small radius so a carriage over a void does not scan the
     * world; when nothing is found the caller keeps Create's own answer.
     */
    @Unique
    private static Vec3 electroEnergetics$groundBeside(Level level, Vec3 seat) {
        for (int radius = 2; radius <= 4; radius++) {
            for (int sign = -1; sign <= 1; sign += 2) {
                int bx = (int) Math.floor(seat.x) + radius * sign;
                int bz = (int) Math.floor(seat.z);
                BlockPos column = new BlockPos(bx, (int) Math.floor(seat.y), bz);

                BlockPos ground = electroEnergetics$solidBelow(level, column, 8);
                if (ground == null)
                    continue;

                Vec3 candidate = new Vec3(bx + 0.5, ground.getY() + 1.0, bz + 0.5);
                // Clear headroom, so the player is not placed inside the carriage's own blocks.
                if (!level.isEmptyBlock(BlockPos.containing(candidate))
                        || !level.isEmptyBlock(BlockPos.containing(candidate.add(0, 1, 0))))
                    continue;
                return candidate;
            }
        }
        return null;
    }

    @Unique
    private static BlockPos electroEnergetics$solidBelow(Level level, BlockPos from, int maxDepth) {
        BlockPos.MutableBlockPos cursor = from.mutable();
        for (int i = 0; i < maxDepth; i++) {
            if (!level.isEmptyBlock(cursor)) {
                return cursor.immutable().above();
            }
            cursor.move(0, -1, 0);
        }
        return null;
    }

    @Unique
    private static String fmt(Vec3 v) {
        return String.format("(%.2f,%.2f,%.2f)", v.x, v.y, v.z);
    }
}
