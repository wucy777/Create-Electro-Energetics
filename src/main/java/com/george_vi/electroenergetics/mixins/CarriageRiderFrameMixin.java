package com.george_vi.electroenergetics.mixins;

import com.george_vi.electroenergetics.content.railway_electrification.rider.CarriageRiderFrame;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Owns a player's position on an electric carriage: captured before the tick, resolved after it.
 *
 * <h2>Why capture-then-resolve, and not "correct the position"</h2>
 *
 * <p>Every earlier attempt corrected a position another writer had produced. Inside one client tick the
 * writers are ordered:
 *
 * <pre>
 *   Entity.tick  -&gt; aiStep -&gt; travel     gravity reads onGround, pulls the rider down
 *   Entity.tick  -&gt; move                 applies the result
 *   onTickPost   -&gt; ContraptionCollider  places the rider on the carriage, sets onGround
 *   collision    -&gt; ContraptionCollider  pushes the rider sideways
 * </pre>
 *
 * <p>Correcting at any one point loses to the next. Measured consequence at 3.9 Blocks/tick:
 *
 * <pre>
 *   t=871034  y=-59.07  d= 0.00  dY= 0.00  sp=0     sink
 *   t=871034  y=-59.07  d= 3.48  dY= 0.00  sp=1     collider lifts back
 *   t=871034  y=-59.17  d= 0.00  dY=-0.10  sp=1     sink again
 * </pre>
 *
 * <p>and every few ticks the deficit is settled in one step - d=10.44 against a carriage speed of 3.48 -
 * which is the sideways throw and the fall through the floor.
 *
 * <h2>The model</h2>
 *
 * <p>The player's position is stored as an offset in the carriage's own frame. It is written once, at the
 * end of the tick - after input, gravity and collision have all run - so nothing subsequently overwrites it.
 * Between ticks the stored offset is the player's own doing: walking changes it, jumping changes it, the
 * carriage moving does not.
 *
 * <p>Resolving {@code anchor + rotate(local)} at the end of the tick is what carries the player along with
 * the carriage, turns them when it turns, and gives them a floor that travels with them instead of one the
 * game has to rediscover each tick.
 *
 * <h2>What is deliberately NOT done</h2>
 *
 * <p>The stored offset is captured at the START of the tick from wherever the game left the player, and
 * resolved at the END. It is never re-recorded between those two points, so the player's own movement is
 * applied exactly once - by the game - rather than being applied and then re-applied by this class.
 *
 * <h2>Why the injection is on Player and not LivingEntity</h2>
 *
 * <p>Three builds hooked {@code LivingEntity.tick} and never ran. The player chain is
 * {@code LocalPlayer.tick -> AbstractClientPlayer.tick -> Player.tick}, and {@code Player.tick} does not
 * call {@code super.tick} - so {@code LivingEntity.tick} is never reached for a player at all. A mixin that
 * looks correct, compiles, applies, logs nothing and changes nothing is exactly what that produces.
 *
 * <h2>Cost</h2>
 *
 * <p>Two coordinate transforms per tick for a player on an electric carriage. A player elsewhere pays one
 * instanceof and one map miss.
 */
@Mixin(value = net.minecraft.world.entity.player.Player.class, remap = false)
public class CarriageRiderFrameMixin {

    /** The carriage frame captured at the head of this tick, or null when not on an electric carriage. */
    @Unique
    private static final org.slf4j.Logger electroEnergetics$LOG =
            org.slf4j.LoggerFactory.getLogger("CEE-FRAME");

    @Unique
    private static int electroEnergetics$n;

    @Unique
    private CarriageRiderFrame.Local electroEnergetics$pending;

    /**
     * Captures the frame before anything moves the player.
     *
     * <p>This is the offset the player ended the previous tick with. It is not derived from this tick's
     * movement, which has not happened yet.
     */
    @Inject(method = "tick", at = @At("HEAD"), remap = false)
    private void electroEnergetics$capture(CallbackInfo ci) {
        electroEnergetics$pending = null;
        try {
            if (!((Object) this instanceof Player player))
                return;
            // Client only. The server never carries a rider - the client computes the seat position and
            // reports it - so on the server this hook sees pax=false and an empty vehicle on every tick,
            // which is what 311 diagnostic lines showed: side=SERVER, cls=ServerPlayer, pax=false.
            if (!player.level().isClientSide())
                return;

            CarriageContraptionEntity carriage = CarriageRiderFrame.carriageOf(player);
            if (electroEnergetics$n++ % 20 == 0) {
                electroEnergetics$LOG.info("FRAME side={} cls={} pax={} veh={} carriage={} electric={}",
                        player.level().isClientSide() ? "CLIENT" : "SERVER",
                        player.getClass().getSimpleName(),
                        player.isPassenger(),
                        player.getVehicle() == null ? "-" : player.getVehicle().getClass().getSimpleName(),
                        carriage == null ? "-" : carriage.getId(),
                        carriage != null && CarriageRiderFrame.isElectric(carriage));
            }
            if (carriage == null) {
                CarriageRiderFrame.forget(player);
                return;
            }

            var stored = CarriageRiderFrame.get(player);
            if (CarriageRiderFrame.belongsTo(stored, carriage)) {
                electroEnergetics$pending = stored;
            } else {
                // First tick aboard: adopt wherever the game has them rather than inventing a position.
                electroEnergetics$pending = CarriageRiderFrame.record(player, carriage);
            }
        } catch (Throwable ignored) {
            // A frame fix must never be the thing that breaks movement.
        }
    }

    /**
     * Writes the player's position for this tick, once, after everything else has had its say.
     *
     * <p>The stored offset is updated by however far the player moved this tick, measured in the carriage
     * frame. That keeps walking and jumping as ordinary local motion while making the carriage's own motion
     * a property of the frame rather than something to be chased.
     */
    @Inject(method = "tick", at = @At("TAIL"), remap = false)
    private void electroEnergetics$resolve(CallbackInfo ci) {
        try {
            var pending = electroEnergetics$pending;
            if (pending == null)
                return;
            if (!((Object) this instanceof Player player))
                return;
            if (!player.level().isClientSide())
                return;

            CarriageContraptionEntity carriage = CarriageRiderFrame.carriageOf(player);
            if (carriage == null || !CarriageRiderFrame.belongsTo(pending, carriage)) {
                CarriageRiderFrame.forget(player);
                return;
            }

            // How far the player moved this tick, expressed in the carriage frame. Walking and jumping show
            // up here as ordinary local displacement.
            Vec3 nowLocal = carriage.toLocalVector(player.position(), 1.0f);
            double dx = nowLocal.x - pending.local().x;
            double dy = nowLocal.y - pending.local().y;
            double dz = nowLocal.z - pending.local().z;

            double height = pending.local().y + dy;
            double floor = pending.floorY();

            // Standing: settle onto the floor so a tick that lost it cannot accumulate into a fall. A jump
            // is above the band and keeps its own height.
            //
            // The lower bound is a small positive rather than a large negative: measured, a player settled
            // exactly onto the floor each tick bounced between the floor and 0.1 Blocks below it, because
            // the game's own gravity added a tick of downward motion before the settle ran and the settle
            // then pushed it back up. Alternating dY of +0.10 and -0.10 on consecutive ticks is that
            // oscillation, and it is visible on screen as a jitter.
            //
            // Snapping whenever the player is anywhere within the band, rather than only above the floor,
            // collapses the oscillation: there is no state in which a settled player is below the floor.
            boolean standing = height - floor >= -0.5 && height - floor <= 0.75;
            if (standing) {
                height = floor;
                // Kill the vertical component outright, not just the downward half: an upward 0.10 left
                // over from the bounce is what produces the next tick's overshoot.
                Vec3 m = player.getDeltaMovement();
                if (Math.abs(m.y) < 0.5)
                    player.setDeltaMovement(m.x, 0, m.z);
            }

            Vec3 updated = new Vec3(pending.local().x + dx, height, pending.local().z + dz);
            Vec3 world = carriage.toGlobalVector(updated, 1.0f);
            player.setPos(world.x, world.y, world.z);

            if (standing) {
                player.setOnGround(true);
                player.fallDistance = 0;
                Vec3 motion = player.getDeltaMovement();
                if (motion.y < 0)
                    player.setDeltaMovement(motion.x, 0, motion.z);
            }

            CarriageRiderFrame.put(player, new CarriageRiderFrame.Local(
                    pending.carriageId(), updated, floor));
            electroEnergetics$pending = null;
        } catch (Throwable ignored) {
            // A frame fix must never be the thing that breaks movement.
        }
    }
}
