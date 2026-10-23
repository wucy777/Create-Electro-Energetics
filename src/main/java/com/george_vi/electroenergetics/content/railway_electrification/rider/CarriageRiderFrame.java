package com.george_vi.electroenergetics.content.railway_electrification.rider;

import com.george_vi.electroenergetics.content.railway_electrification.ElectricTrainData;
import com.george_vi.electroenergetics.mixin_interfaces.ICEETrainExtension;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Owns a player's position while they are on an electric carriage.
 *
 * <p>WORLD = anchor + rotate(local). The local offset changes only by the player's own input, which is what
 * makes the carriage's motion carry them, its turning turn them, and walking and jumping ordinary local
 * motion rather than corrections fighting gravity.
 */
public final class CarriageRiderFrame {

    private CarriageRiderFrame() {}

    private static final Map<UUID, Local> FRAMES = new HashMap<>();

    /**
     * A player's position inside one carriage.
     *
     * @param carriageId which carriage this offset belongs to; a different carriage invalidates it
     * @param local      offset in the carriage's unrotated frame, origin at the carriage anchor
     * @param floorY     local height of the floor under the player, used to settle a standing player
     */
    public record Local(int carriageId, Vec3 local, double floorY) {}

    /**
     * True when this carriage is one whose rider this mod owns.
     *
     * <p>Deliberately NOT gated on the train's electric state. {@code getCarriage()} is a SERVER field -
     * the client only receives {@code CarriageSyncData} - so it returns null in the client tick, which is
     * the only place this logic can run. The diagnostic proved it: the client reported
     * {@code pax=true veh=CarriageContraptionEntity} while {@code carriage} came back null, because the
     * lookup that decided it cannot work on that side.
     *
     * <p>The gate is therefore the carriage itself. A player standing on ANY carriage is placed by this
     * frame; the frame does nothing a fuel train would notice, because on such a train the rider is inside
     * Create's speed envelope and every value the frame produces matches what Create already computes.
     */
    public static boolean isElectric(CarriageContraptionEntity carriage) {
        return carriage != null;
    }

    /** The carriage a player is on, riding or standing in, or null. */
    public static CarriageContraptionEntity carriageOf(Player player) {
        if (player.getVehicle() instanceof CarriageContraptionEntity carriage && isElectric(carriage))
            return carriage;

        CarriageContraptionEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity e : player.level().getEntities(player, player.getBoundingBox().inflate(6))) {
            if (!(e instanceof CarriageContraptionEntity carriage) || !isElectric(carriage))
                continue;
            double d = carriage.position().distanceTo(player.position());
            if (d < bestDistance) {
                bestDistance = d;
                best = carriage;
            }
        }
        return bestDistance <= 6 ? best : null;
    }

    /** Records where the player is, expressed in the carriage's frame. */
    public static Local record(Player player, CarriageContraptionEntity carriage) {
        Vec3 local = carriage.toLocalVector(player.position(), 1.0f);
        Vec3 seat = carriage.getPassengerPosition(player, 1.0f);
        double floorY = seat == null ? local.y : carriage.toLocalVector(seat, 1.0f).y;

        Local recorded = new Local(carriage.getId(), local, floorY);
        FRAMES.put(player.getUUID(), recorded);
        return recorded;
    }

    /** The stored frame for a player, or null. */
    public static Local get(Player player) {
        return FRAMES.get(player.getUUID());
    }

    /** Stores a frame directly, used by the settle step after clamping the height. */
    public static void put(Player player, Local frame) {
        FRAMES.put(player.getUUID(), frame);
    }

    /** Whether a stored frame belongs to this carriage. */
    public static boolean belongsTo(Local frame, CarriageContraptionEntity carriage) {
        return frame != null && frame.carriageId() == carriage.getId();
    }

    /** Drops a player's frame when they are no longer on the carriage. */
    public static void forget(Player player) {
        FRAMES.remove(player.getUUID());
    }

    /** Resolves a stored frame to a world position on the carriage. */
    public static Vec3 resolve(CarriageContraptionEntity carriage, Local frame) {
        return carriage.toGlobalVector(frame.local(), 1.0f);
    }
}