package com.george_vi.electroenergetics.events;

import com.george_vi.electroenergetics.CreateElectroEnergetics;
import com.simibubi.create.Create;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Catches the one way a player ends up INSIDE a train without boarding: tunneling.
 *
 * <p>A flying or falling player covers up to 1.6 Blocks in one tick, a carriage wall is one Block
 * thick, and vanilla resolves player-initiated movement at the destination only - so the wall is
 * never sampled and the player lands in the interior. That is the reported fly-mode clip: flight
 * stops on contact (upstream's own quirk, deliberately kept), the player then falls, and the fall
 * crosses the shell in a single tick. Walking players top out near 0.3 Blocks per tick and can
 * never trigger this, which is what keeps the guard inert for ordinary play.
 *
 * <h2>The test, and what it costs</h2>
 *
 * <p>The suspicious move is marched instead of endpoint-tested: the consists are shelled in thin
 * disguise plates, so a tunneled move lands in interior AIR and an endpoint test sees nothing. The
 * path is sampled at quarter-Block steps against the contraption's own block map - the same
 * local-coordinate query the seating gate uses, no world AABB anywhere (HC-01). The signature that
 * arms the march is a displacement past {@link #TUNNEL_SPEED} while airborne.
 *
 * <h2>The response</h2>
 *
 * <p>A single reversion to the previous position with the motion cleared. It fires once on the
 * offending tick and never becomes a per-tick force: a player who is legitimately inside the
 * carriage - walked in through an open side, or seated - was never outside-to-inside with a
 * tunneling-sized displacement, and is never touched.
 */
@EventBusSubscriber(modid = CreateElectroEnergetics.ID)
public final class FlyTunnelGuard {

    /**
     * Displacement past which a single move can cross a shell wall [Blocks/tick].
     *
     * <p>The consists are built from THIN disguise plates (the operator models real EMU shells), so
     * the wall a move has to clear is a fraction of a Block - which is why the gate alone is not the
     * detection: the detection is the path march below, and this gate only keeps the march away from
     * ordinary walking and sprint-jumping speeds. Flying and falling moves start at ~0.6 and go up.
     */
    private static final double TUNNEL_SPEED = 0.6d;

    /**
     * Path-march resolution [Blocks]. A quarter-Block step guarantees at least three samples inside
     * any full-Block cell the move crosses, and the disguise plates live inside their own cells - so
     * any crossing of a plate's cell puts at least one sample inside it.
     */
    private static final double SAMPLE_STEP = 0.25d;

    /** Upper bound on march samples, so an absurd move cannot spin the loop. */
    private static final int MAX_SAMPLES = 24;

    /** The position each player held at the end of their previous tick, for the outside test. */
    private static final Map<UUID, Vec3> LAST_POS = new HashMap<>();

    private FlyTunnelGuard() {}

    /** Forget one player's tracked position (logout, dimension change). */
    public static void forget(UUID playerId) {
        LAST_POS.remove(playerId);
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player))
            return;
        if (player.level().isClientSide() || player.isPassenger() || player.isDeadOrDying())
            return;

        // Tracked for EVERY player, every tick, before any gate. Gating the tracking itself on
        // "airborne" would let a stale position survive a walk across the map and then present a
        // huge phantom displacement on the first airborne tick - a false tunneling signature.
        Vec3 now = player.position();
        Vec3 last = LAST_POS.put(player.getUUID(), now);
        // First tick after login or a dimension change: there is no "outside" to compare against,
        // and a genuine teleport lands wherever it lands - both are let through.
        if (last == null || last.distanceToSqr(now) < TUNNEL_SPEED * TUNNEL_SPEED)
            return;
        // Airborne only: flying, gliding, or already falling with speed. A grounded player cannot
        // tunnel, and the seated/walking cases must never reach the lookups at all.
        if (!(player.getAbilities().flying || player.isFallFlying() || player.fallDistance > 1.0f))
            return;

        for (Train train : Create.RAILWAYS.trains.values()) {
            if (!revertIfTunneled(train, last, now))
                continue;
            player.teleportTo((ServerLevel) player.level(), last.x, last.y, last.z,
                    player.getYRot(), player.getXRot());
            player.setDeltaMovement(Vec3.ZERO);
            player.fallDistance = 0f;
            player.displayClientMessage(
                    Component.translatable("electroenergetics.flytunnel.rejected"), true);
            return;
        }
    }

    /**
     * Whether the move from {@code last} to {@code now} crossed any block of this consist.
     *
     * <p>The ENDPOINTS are useless for this and it is worth stating why: the consists are shelled in
     * thin disguise plates (real-EMU proportions), so a tunneled move lands in the INTERIOR AIR cell
     * just past the wall - both endpoints in air, one wall silently crossed between them. What
     * detects the crossing is the PATH: the segment is marched at {@link #SAMPLE_STEP} resolution and
     * any sample landing inside a block cell of the contraption is a wall the move passed through.
     * The local frame is this tick's, for both ends - the standard approximation, and it costs one
     * transform per sample against a block map, never a world AABB (HC-01).
     */
    private static boolean crossedShell(Contraption contraption, Vec3 last, Vec3 now) {
        Vec3 delta = now.subtract(last);
        double length = delta.length();
        int steps = (int) Math.min(MAX_SAMPLES, Math.ceil(length / SAMPLE_STEP));
        for (int i = 1; i <= steps; i++) {
            Vec3 sample = last.add(delta.scale(i / (double) steps));
            Vec3 local = contraption.entity.toLocalVector(sample, 1.0f);
            if (contraption.getBlocks().containsKey(BlockPos.containing(local)))
                return true;
        }
        return false;
    }

    /** Whether this train is the one the move tunneled into. */
    private static boolean revertIfTunneled(Train train, Vec3 last, Vec3 now) {
        for (Carriage carriage : train.carriages) {
            CarriageContraptionEntity entity = carriage.anyAvailableEntity();
            if (entity == null || entity.level().isClientSide())
                continue;
            Contraption contraption = entity.getContraption();
            if (contraption == null)
                continue;
            if (crossedShell(contraption, last, now))
                return true;
        }
        return false;
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        forget(event.getEntity().getUUID());
    }
}
