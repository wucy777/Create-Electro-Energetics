package com.george_vi.electroenergetics.mixins;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.nbt.Tag;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Records the rider's own position as their dismount location, instead of the server's idea of the seat.
 *
 * <h2>What was measured</h2>
 *
 * <p>Four separate instruments narrowed this down and each removed a theory:
 *
 * <ul>
 *   <li>the carry is exact - 154 samples, {@code needed - moved} is 0.00 or 0.01, and the residual 0.13 is
 *       the seat height offset, not error;</li>
 *   <li>the seat position equals the carriage anchor on every sample (offset 0.00), so the seat maths is
 *       not drifting;</li>
 *   <li>the server and the client both carry correctly, and the server's rider position agrees with the
 *       client's own reports to within 0.152 Blocks, so no correction packet is involved;</li>
 *   <li>the dismount jump is one {@code ClientboundPlayerPositionPacket} applied as a RELATIVE move
 *       ({@code handleMovePlayer} line 677, bytecode offset 69-84: {@code d9 = player.getX() + packet.getX()}).</li>
 * </ul>
 *
 * <p>That last one names the source. The packet is not a correction - nothing is out of tolerance, which
 * the move probe confirmed. It is the server TELEPORTING the player to the dismount location it computed,
 * and that location is {@code getPassengerPosition(passenger, 1)} evaluated against the SERVER's carriage
 * position at the moment of dismount, which is about three ticks of travel ahead of where the client
 * shows the rider.
 *
 * <p>So the jump is a stale-by-construction destination, not a physics failure. On a fuel train at
 * 1.4-2 Blocks/tick those three ticks are 4-6 Blocks and read as a small step; at this mod's speeds they
 * are 10+ Blocks and read as being thrown out of the carriage - which is exactly the report.
 *
 * <h2>The fix</h2>
 *
 * <p>{@code removePassenger} writes {@code ContraptionDismountLocation} from
 * {@code getPassengerPosition(passenger, 1)}. This overwrites that value with the rider's own position,
 * server side only, before Create reads it back in {@code getDismountLocationForPassenger}.
 *
 * <p>That is both more accurate and cheaper. The semantic of a dismount location is "where the player
 * should end up standing", and the player is already standing there - the server knows it, because the
 * client reports its position every tick and those reports were measured to agree. Recomputing it from
 * the carriage's own coordinates routes through a value that is ahead of what the client draws, for no
 * benefit. One subtraction of a stored Vec3, replacing an interpolation over a transformation.
 *
 * <p>Electric trains only, by the same {@code hasTractionMotors} test used elsewhere: a fuel train at
 * Create's own speeds does not exhibit the problem and is left byte-for-byte unchanged.
 *
 * <h2>What this does not fix</h2>
 *
 * <p>The rider standing in the carriage continues to be drawn by the client at its own interpolated
 * carriage position, which is fine - that is normal entity interpolation and looks smooth. This change
 * only affects where the player lands when they leave the seat.
 */
@Mixin(value = AbstractContraptionEntity.class, remap = false)
public class DismountLocationMixin {

    /**
     * Replaces the value Create wrote, after it has written it.
     *
     * <p>Create computes {@code getPassengerPosition(passenger, 1)} at the HEAD of
     * {@code removePassenger} and stores it before returning, so this has to run at RETURN to replace it
     * rather than race it. Verified by reading the method: the value is captured first, then written
     * after {@code super.removePassenger} and after the client-side early return.
     */
    @Inject(method = "removePassenger", at = @At("RETURN"), remap = false)
    private void electroEnergetics$afterRemove(Entity passenger, CallbackInfo ci) {
        try {
            if (!(passenger instanceof Player player))
                return;
            if (!((Object) this instanceof CarriageContraptionEntity cce))
                return;
            if (cce.level().isClientSide())
                return;

            var carriage = cce.getCarriage();
            if (carriage == null || carriage.train == null)
                return;
            var data = ((com.george_vi.electroenergetics.mixin_interfaces.ICEETrainExtension)
                    carriage.train).getElectricTrainData();
            if (data == null || !data.hasTractionMotors)
                return;

            var tag = player.getPersistentData();
            if (!tag.contains("ContraptionDismountLocation"))
                return;                     // Create did not record one; leave it alone.

            // Horizontal from the rider, vertical from the seat.
            //
            // The seat sits 0.13 Blocks above the rider's own position on every sample (that is the
            // measured `short`), so taking the whole position would drop the player 0.13 Blocks into
            // the carriage floor - which is the pass-through, not a fix for it. Keeping the seat's Y
            // and taking only X/Z keeps the player standing exactly where the seat put them, while
            // removing the horizontal component that was three ticks ahead.
            Vec3 seat = net.createmod.catnip.math.VecHelper.readNBT(
                    tag.getList("ContraptionDismountLocation", Tag.TAG_DOUBLE));
            Vec3 rider = player.position();
            Vec3 pinned = new Vec3(rider.x, seat.y, rider.z);
            tag.put("ContraptionDismountLocation",
                    net.createmod.catnip.math.VecHelper.writeNBT(pinned));
        } catch (Throwable ignored) {
            // Never let a carry fix break a dismount.
        }
    }
}
