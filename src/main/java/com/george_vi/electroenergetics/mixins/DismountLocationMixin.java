package com.george_vi.electroenergetics.mixins;

import com.simibubi.create.content.contraptions.sync.ContraptionSeatMappingPacket;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.george_vi.electroenergetics.mixin_interfaces.ICEETrainExtension;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.nbt.Tag;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces the dismount location a client writes for itself, because that is where the throw is decided.
 *
 * <h2>Why the server-side fix did nothing</h2>
 *
 * <p>The previous build pinned {@code ContraptionDismountLocation} in
 * {@code AbstractContraptionEntity.removePassenger}, on the server. The dismount ratio was still 3.00.
 *
 * <p>The reason: the value is written TWICE. On the server in {@code removePassenger}, and again on the
 * CLIENT in {@code ContraptionSeatMappingPacket.handle} when the packet announcing the dismount arrives:
 *
 * <pre>
 *   if (dismountedId == player.getId()) {
 *       Vec3 v = contraptionEntity.getPassengerPosition(player, 1);
 *       player.getPersistentData().put("ContraptionDismountLocation", writeNBT(v));
 *   }
 * </pre>
 *
 * <p>The client write happens after the server's, so it wins and the server-side pin was discarded.
 *
 * <h2>Why it lands three ticks away</h2>
 *
 * <p>{@code getPassengerPosition(player, 1)} is evaluated at {@code partialTicks = 1} - the END of the
 * current tick - while the client draws the carriage at a partially interpolated position behind that.
 * The gap is therefore a fixed fraction of a tick's travel, and the measured values have been 3.00 ticks
 * on six consecutive builds across a factor-of-two speed range: 9.90/3.30, 11.05/3.68, 7.37/... all 3.00.
 *
 * <p>On a fuel train those three ticks are 4-6 Blocks and read as a small step; at this mod's speeds they
 * are 10+ Blocks and read as being thrown out of the carriage.
 *
 * <h2>The fix</h2>
 *
 * <p>After the handler has written its value, replace it: horizontal from the player, vertical from the
 * seat.
 *
 * <p>X/Z come from the player, who is exactly where this same client draws them, so there is no
 * interpolation gap to cross. Y comes from the seat, because the seat sits 0.13 Blocks above the rider on
 * every measured sample; taking the whole position would drop the player 0.13 Blocks into the carriage
 * floor, which is the pass-through rather than a fix for it.
 *
 * <p>Electric trains only, by the same {@code hasTractionMotors} test used elsewhere, so a fuel train's
 * dismount is untouched.
 *
 * <h2>Cost</h2>
 *
 * <p>One NBT read, one Vec3, one NBT write, on the single tick a player leaves a seat. Before that, one
 * field read on the packet's contraption.
 */
@Mixin(value = ContraptionSeatMappingPacket.class, remap = false)
public class DismountLocationMixin {

    @Unique
    private static final Logger electroEnergetics$LOG = LoggerFactory.getLogger("CEE-PIN");

    @Inject(method = "handle", at = @At("RETURN"), remap = false)
    private void electroEnergetics$pinDismount(LocalPlayer player, CallbackInfo ci) {
        try {
            if (player == null || player.clientLevel == null)
                return;

            var tag = player.getPersistentData();
            if (!tag.contains("ContraptionDismountLocation"))
                return;                     // nothing was written for this player on this packet

            // By the time this runs the seat mapping has been applied, so the player is no longer a
            // passenger. The carriage is located by proximity instead - the dismount leaves them within
            // a tick of travel of it, and 32 Blocks covers a carriage length plus that travel at any
            // speed this mod permits.
            CarriageContraptionEntity carriage = null;
            double best = Double.MAX_VALUE;
            for (var e : player.clientLevel.entitiesForRendering()) {
                if (!(e instanceof CarriageContraptionEntity cce))
                    continue;
                double d = cce.position().distanceTo(player.position());
                if (d < best) {
                    best = d;
                    carriage = cce;
                }
            }
            if (carriage == null || best > 32)
                return;

            var c = carriage.getCarriage();
            if (c == null || c.train == null)
                return;
            var data = ((ICEETrainExtension) c.train).getElectricTrainData();
            if (data == null || !data.hasTractionMotors)
                return;

            Vec3 seat = net.createmod.catnip.math.VecHelper.readNBT(
                    tag.getList("ContraptionDismountLocation", Tag.TAG_DOUBLE));
            Vec3 rider = player.position();
            Vec3 pinned = new Vec3(rider.x, seat.y, rider.z);

            tag.put("ContraptionDismountLocation",
                    net.createmod.catnip.math.VecHelper.writeNBT(pinned));

            electroEnergetics$LOG.info("DISMOUNT-PIN seat={} rider={} pinned={} dist={}",
                    fmt(seat), fmt(rider), fmt(pinned), String.format("%.2f", best));
        } catch (Throwable ignored) {
            // A carry fix must never break a dismount.
        }
    }

    @Unique
    private static String fmt(Vec3 v) {
        return String.format("(%.2f,%.2f,%.2f)", v.x, v.y, v.z);
    }
}
