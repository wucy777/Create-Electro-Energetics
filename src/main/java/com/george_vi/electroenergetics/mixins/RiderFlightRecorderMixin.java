package com.george_vi.electroenergetics.mixins;

import com.simibubi.create.content.contraptions.ContraptionHandler;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang3.mutable.MutableInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * A read-only flight recorder for the rider, so that a play session needs no description at all.
 *
 * <h2>Why a recorder rather than another counter</h2>
 *
 * <p>The previous instrumented build reported averages per hundred ticks, which cannot show an
 * event that happens in a handful of ticks - an ejection is over before an average notices it,
 * and I then had to ask the user to describe what they saw. They have reasonably stopped doing
 * that. This instead keeps the last {@link #CAP} ticks in a ring buffer and dumps the whole
 * window AUTOMATICALLY when something anomalous happens, so the log carries the evidence whether
 * or not anyone is watching for it.
 *
 * <h2>What is already established, so this does not re-litigate it</h2>
 *
 * <pre>
 *   build 59 contains none of my rider code and the problem persists  (CEE-CARRY = 0 in the log)
 *   so this is Create's own carry, at twice the speed it was designed for
 *   Create's train ceiling is 28 m/s; this mod reaches 60 m/s = 3.0 Blocks per tick
 *   the carry selection box is bounds.inflate(2).expandTowards(0, 32, 0)   (ContraptionCollider:100)
 *   the carry itself is at :294-311, gated on surfaceCollision
 *   :280 bails when neither hard nor surface collision fires
 * </pre>
 *
 * <h2>What each recorded field is for</h2>
 *
 * <p><b>Player:</b> position, delta movement, onGround, whether a passenger, and the vehicle id.
 * The passenger flags are the ones that matter most, because "left the seat and was immediately
 * thrown clear" is a claim about that transition and nothing recorded it before.
 *
 * <p><b>Nearest carriage:</b> id, distance, its own motion this tick, its contact-point motion at
 * the player's position, whether the player is inside its true box, whether they are inside the
 * inflated selection box, and the contact counter the carriage holds for them. Together these say
 * whether the player was inside the carriage at all, whether Create considered them in contact,
 * and how much displacement the contact point implies.
 *
 * <p><b>Contact counter:</b> a fresh {@code MutableInt} at zero means the carriage registered the
 * player during the pass that just ran; anything else means it did not.
 *
 * <h2>Triggers</h2>
 *
 * <ul>
 *   <li><b>DISMOUNT</b> - the player was a passenger last tick and is not now.</li>
 *   <li><b>STEP</b> - the player moved much further in one tick than the carriage did, while not
 *       a passenger. A carried rider's step and the carriage's travel should agree.</li>
 *   <li><b>FAST</b> - the player is not a passenger but is moving horizontally at speed, which
 *       nobody standing on a carriage should be doing.</li>
 * </ul>
 *
 * <h2>Cost</h2>
 *
 * <p>Nothing at all unless a train carriage is within {@link #NEAR} Blocks: that path returns
 * before any string is built. With a carriage nearby it is one entity query and one line per tick,
 * ring-buffered, with dumps hard capped at {@link #MAX_DUMPS}. It is entirely read-only - it never
 * moves the player, never touches velocity, and never sends anything.
 */
@Mixin(ContraptionHandler.class)
public class RiderFlightRecorderMixin {

    @Unique
    private static final Logger electroEnergetics$LOG = LoggerFactory.getLogger("CEE-RIDER");

    /** Ticks of history kept and dumped on an anomaly. */
    @Unique
    private static final int CAP = 60;

    /** Hard cap on dumps, so a long session cannot fill the log. */
    @Unique
    private static final int MAX_DUMPS = 25;

    /** Recording radius, in Blocks. Outside this the recorder does nothing at all. */
    @Unique
    private static final double NEAR = 16d;

    /** A step this much larger than the carriage's own travel is anomalous. */
    @Unique
    private static final double STEP_SLACK = 1.5d;

    /** Horizontal speed above which a non-passenger is considered to be flying. */
    @Unique
    private static final double FAST = 2.0d;

    @Unique
    private static final String[] electroEnergetics$ring = new String[CAP];
    @Unique
    private static int electroEnergetics$ringIdx;
    @Unique
    private static int electroEnergetics$ringCount;
    @Unique
    private static int electroEnergetics$dumps;
    @Unique
    private static boolean electroEnergetics$wasPassenger;
    @Unique
    private static Vec3 electroEnergetics$lastPos;
    @Unique
    private static long electroEnergetics$lastTick = Long.MIN_VALUE;

    /**
     * Samples once per tick, after {@code ContraptionHandler} has run every carriage's collider.
     *
     * <p>This is the right hook because it is the one point in the tick where the carries have all
     * finished, so the player's position here is the outcome of the whole pass rather than an
     * intermediate state.
     */
    @Inject(method = "tick", at = @At("RETURN"), remap = false)
    private static void electroEnergetics$sample(Level world, CallbackInfo ci) {
        try {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer player = mc.player;
            if (player == null || mc.level == null)
                return;
            if (!world.isClientSide() || world != mc.level)
                return;

            List<CarriageContraptionEntity> near = world.getEntitiesOfClass(
                    CarriageContraptionEntity.class, player.getBoundingBox().inflate(NEAR));

            // No train nearby: forget the history so an unrelated later event does not dump a
            // window full of walking around a village.
            if (near.isEmpty()) {
                electroEnergetics$reset(player);
                return;
            }

            CarriageContraptionEntity best = null;
            double bestDistance = Double.MAX_VALUE;
            for (CarriageContraptionEntity carriage : near) {
                double d = carriage.position().distanceTo(player.position());
                if (d < bestDistance) {
                    bestDistance = d;
                    best = carriage;
                }
            }
            if (best == null)
                return;

            long tick = world.getGameTime();
            Vec3 motion = best.position().subtract(best.getPrevPositionVec());
            Vec3 contact = best.getContactPointMotion(player.position());
            MutableInt counter = best.collidingEntities.get(player);
            boolean inBox = best.getBoundingBox().inflate(0.3d).contains(player.position());
            boolean inSelection = best.getBoundingBox().inflate(2d).expandTowards(0, 32, 0)
                    .contains(player.position());
            Vec3 own = player.getDeltaMovement();
            Entity vehicle = player.getVehicle();

            String line = String.format(
                    "p=(%.2f,%.2f,%.2f) vy=%.3f own=(%.2f,%.2f,%.2f) grd=%s pax=%s veh=%s "
                            + "| car#%d d=%.2f mot=(%.2f,%.2f) cp=(%.2f,%.2f,%.2f) "
                            + "inBox=%s inSel=%s reg=%s",
                    player.getX(), player.getY(), player.getZ(), own.y,
                    own.x, own.y, own.z,
                    player.onGround() ? "1" : "0",
                    player.isPassenger() ? "1" : "0",
                    vehicle == null ? "-" : String.valueOf(vehicle.getId()),
                    best.getId(), bestDistance,
                    motion.x, motion.z,
                    contact.x, contact.y, contact.z,
                    inBox ? "1" : "0",
                    inSelection ? "1" : "0",
                    counter == null ? "-" : String.valueOf(counter.intValue()));

            // ---- anomaly detection, all against the immediately preceding tick only ----
            String why = "";
            if (electroEnergetics$lastPos != null && tick == electroEnergetics$lastTick + 1) {
                double step = player.position().distanceTo(electroEnergetics$lastPos);
                double travelled = motion.length();
                if (!player.isPassenger() && step > travelled + STEP_SLACK)
                    why += String.format("STEP(step=%.2f,car=%.2f) ", step, travelled);
            }
            if (electroEnergetics$wasPassenger && !player.isPassenger())
                why += "DISMOUNT ";
            if (!player.isPassenger() && Math.hypot(own.x, own.z) > FAST)
                why += String.format("FAST(h=%.2f) ", Math.hypot(own.x, own.z));

            // ---- record ----
            electroEnergetics$ring[electroEnergetics$ringIdx] = "t=" + tick + " " + line;
            electroEnergetics$ringIdx = (electroEnergetics$ringIdx + 1) % CAP;
            if (electroEnergetics$ringCount < CAP)
                electroEnergetics$ringCount++;

            if (!why.isEmpty()) {
                // WHO moved the player, printed with the anomaly rather than reconstructed afterwards.
                //
                // This recorder samples position once per tick, at RETURN, so a tick that teleports the
                // player out and back looks like no movement at all, and a tick that teleports them in
                // one direction looks like one anonymous step. Three separate attempts to attribute
                // that step by reading code were wrong, because the write happens inside the tick and
                // leaves no trace at the tick boundary.
                //
                // The stack frame at the moment the step is observed is the only thing that names the
                // writer without guessing. The writer is not necessarily on THIS stack - the step may
                // have been applied earlier in the same tick - so this prints what it can see and says
                // so, rather than implying the top frame is the culprit.
                String frames = electroEnergetics$frames();
                why += " | STACK@" + frames;
                electroEnergetics$dump(why.trim(), tick);
            }

            electroEnergetics$wasPassenger = player.isPassenger();
            electroEnergetics$lastPos = player.position();
            electroEnergetics$lastTick = tick;
        } catch (Throwable ignored) {
            // A recorder must never be the thing that breaks the client.
        }
    }

    /**
     * Logs the teleport that puts a freshly dismounted rider back at their seat's position.
     *
     * <p>{@code ContraptionHandler:64-77} reads the location Create stored when the passenger was
     * removed and calls {@code absMoveTo} with it. That is an unconditional position write, and
     * "left the seat and was immediately thrown clear" is precisely a claim about this moment, so
     * both the stored value and the rider's position are recorded.
     */
    @Inject(method = "entitiesWhoJustDismountedGetSentToTheRightLocation",
            at = @At("HEAD"), remap = false)
    private static void electroEnergetics$dismount(LivingEntity rider, Level world,
                                                   CallbackInfo ci) {
        try {
            if (!(rider instanceof LocalPlayer))
                return;
            CompoundTag data = rider.getPersistentData();
            if (!data.contains("ContraptionDismountLocation"))
                return;
            electroEnergetics$LOG.info(
                    "RIDER DISMOUNT target={} at={} veh={} pax={}",
                    data.get("ContraptionDismountLocation"),
                    String.format("(%.2f,%.2f,%.2f)", rider.getX(), rider.getY(), rider.getZ()),
                    rider.getVehicle() == null ? "-" : rider.getVehicle().getId(),
                    rider.isPassenger() ? "1" : "0");
        } catch (Throwable ignored) {
            // Never break the client.
        }
    }

    @Unique
    private static void electroEnergetics$reset(LocalPlayer player) {
        electroEnergetics$ringCount = 0;
        electroEnergetics$ringIdx = 0;
        electroEnergetics$wasPassenger = player.isPassenger();
        electroEnergetics$lastPos = player.position();
        electroEnergetics$lastTick = Long.MIN_VALUE;
    }

    @Unique
    private static void electroEnergetics$dump(String why, long tick) {
        if (electroEnergetics$dumps >= MAX_DUMPS)
            return;
        electroEnergetics$LOG.info("RIDER DUMP #{} tick={} why=[{}]", ++electroEnergetics$dumps,
                tick, why);
        int count = Math.min(electroEnergetics$ringCount, CAP);
        for (int back = count - 1; back >= 0; back--) {
            int index = (electroEnergetics$ringIdx - 1 - back + 2 * CAP) % CAP;
            String entry = electroEnergetics$ring[index];
            if (entry != null)
                electroEnergetics$LOG.info("   -{} {}", back, entry);
        }
    }

    /**
     * A short stack trace, taken where the anomaly is observed.
     *
     * <p>Names the code that was running, which is the one piece of evidence the position snapshots
     * cannot carry: a teleport applied earlier in the same tick is invisible at the tick boundary.
     *
     * <p>Starts above this class and above the mixin plumbing, and stops at the first five frames that
     * are not reflection or logger internals - enough to name a caller chain without filling the log.
     */
    @Unique
    private static String electroEnergetics$frames() {
        StackTraceElement[] stack = new Throwable().getStackTrace();
        StringBuilder sb = new StringBuilder();
        int taken = 0;
        for (StackTraceElement e : stack) {
            String cls = e.getClassName();
            if (cls.startsWith("com.george_vi.electroenergetics.mixins.RiderFlightRecorderMixin"))
                continue;
            if (cls.startsWith("java.lang.Thread") || cls.startsWith("org.slf4j")
                    || cls.startsWith("java.lang.Throwable"))
                continue;
            if (taken > 0)
                sb.append(" <- ");
            sb.append(cls.substring(cls.lastIndexOf('.') + 1))
              .append(':').append(e.getLineNumber())
              .append('(').append(e.getMethodName()).append(')');
            if (++taken >= 5)
                break;
        }
        return sb.length() == 0 ? "?" : sb.toString();
    }
}
