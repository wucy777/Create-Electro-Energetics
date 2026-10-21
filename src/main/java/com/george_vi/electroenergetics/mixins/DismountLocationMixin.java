package com.george_vi.electroenergetics.mixins;

import com.simibubi.create.content.contraptions.ContraptionHandler;
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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Pins the dismount relocation to the player's own horizontal position, at the one place that moves them.
 *
 * <h2>Why this site, after two attempts at the writers</h2>
 *
 * <p>The dismount location is written twice and read twice:
 *
 * <pre>
 *   WRITE  AbstractContraptionEntity.removePassenger          (server)
 *   WRITE  ContraptionSeatMappingPacket.handle                (client)
 *   READ   AbstractContraptionEntity.getDismountLocationForPassenger    (computes where to land)
 *   READ   ContraptionHandler.entitiesWhoJustDismountedGetSentToTheRightLocation  (MOVES THEM)
 * </pre>
 *
 * <p>Two builds substituted the value at the write sites. The first changed the server's copy, which the
 * client's copy overwrote. The second hooked the client's write and never ran. Both were attempts to
 * control a value that has several suppliers and one consumer.
 *
 * <p>The consumer is the only place that matters:
 *
 * <pre>
 *   Vec3 position = VecHelper.readNBT(data.getList("ContraptionDismountLocation", ...));
 *   if (entityLiving.getVehicle() == null)
 *       entityLiving.absMoveTo(position.x, position.y, position.z, ...);   // &lt;-- the throw
 *   data.remove("ContraptionDismountLocation");
 * </pre>
 *
 * <p>Hooking here is independent of who wrote the value, whether they ran, and in what order. Every
 * dismount passes through it.
 *
 * <h2>What is replaced, and what is kept</h2>
 *
 * <p>X and Z are taken from the player: they stand exactly where this client draws them, and the
 * three-tick offset measured across six builds comes from evaluating the seat at
 * {@code partialTicks = 1} against an interpolated carriage. Y is kept from the recorded location, because
 * the seat sits 0.13 Blocks above the rider on every measured sample - the player's own Y would drop them
 * 0.13 Blocks into the carriage floor, which is the pass-through rather than a fix for it.
 *
 * <h2>Cost</h2>
 *
 * <p>Client side, once per dismount: one NBT list read, one Vec3, one NBT list write. The read-and-remove
 * happened anyway; the extra work is constructing one Vec3.
 */
@Mixin(value = ContraptionHandler.class, remap = false)
public class DismountLocationMixin {

    @Unique
    private static final Logger electroEnergetics$LOG = LoggerFactory.getLogger("CEE-PIN");

    @Inject(
            method = "entitiesWhoJustDismountedGetSentToTheRightLocation",
            at = @At("HEAD"), remap = false)
    private static void electroEnergetics$pinBeforeRelocation(LivingEntity rider, Level world,
                                                              CallbackInfo ci) {
        try {
            if (rider == null || world == null || !world.isClientSide)
                return;
            if (!(rider instanceof Player))
                return;

            var data = rider.getPersistentData();
            if (!data.contains("ContraptionDismountLocation"))
                return;

            Vec3 seat = net.createmod.catnip.math.VecHelper.readNBT(
                    data.getList("ContraptionDismountLocation", Tag.TAG_DOUBLE));
            Vec3 self = rider.position();
            Vec3 pinned = new Vec3(self.x, seat.y, self.z);

            data.put("ContraptionDismountLocation",
                    net.createmod.catnip.math.VecHelper.writeNBT(pinned));

            electroEnergetics$LOG.info("DISMOUNT-PIN seat={} rider={} pinned={}",
                    fmt(seat), fmt(self), fmt(pinned));
        } catch (Throwable ignored) {
            // A carry fix must never break a dismount.
        }
    }

    @Unique
    private static String fmt(Vec3 v) {
        return String.format("(%.2f,%.2f,%.2f)", v.x, v.y, v.z);
    }
}
