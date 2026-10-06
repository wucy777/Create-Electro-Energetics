package com.george_vi.electroenergetics.mixins;

import com.simibubi.create.content.contraptions.ContraptionHandler;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.LivingEntity;
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
 * Stops a rider being thrown clear of the train when they stand up at speed.
 *
 * <h2>The bug</h2>
 *
 * <p>Reported repeatedly, and specifically when LEAVING A SEAT rather than while sitting:
 * at high speed the driver is flung out of the train on standing up, and passengers are
 * expected to do the same. Sitting still is fine, Create's own trains are fine, and only a
 * train this mod has made fast throws its riders.
 *
 * <h2>Why it happens</h2>
 *
 * <p>Standing up relocates the rider to a position the SERVER worked out, applied by the
 * CLIENT a network round trip later:
 *
 * <pre>
 *   server  AbstractContraptionEntity.removePassenger
 *             transformedVector = getPassengerPosition(passenger, 1)   // the seat, NOW
 *             passenger.getPersistentData()
 *                 .put("ContraptionDismountLocation", writeNBT(transformedVector))
 *           ... that tag reaches the client a round trip later ...
 *   client  ContraptionHandler.entitiesWhoJustDismountedGetSentToTheRightLocation
 *             :74  entityLiving.absMoveTo(position.x, position.y, position.z, ...)
 * </pre>
 *
 * <p>Those coordinates were right when the server computed them, but the train has moved on
 * by the time they are used - by train speed times the latency. At 5.0 Blocks/tick a single
 * tick of latency is five blocks of error, pointing BACKWARDS along the track, so the rider
 * is put down where the seat used to be rather than where it is. At Create's own 2.0
 * Blocks/tick the same latency is one or two blocks and nobody notices, which is exactly
 * why this only surfaces once the speed goes up.
 *
 * <p>{@code absMoveTo} sets no {@code deltaMovement}, so nothing cancels the mismatch
 * afterwards: the rider is left standing in the wrong place with the train gone, and the
 * fall, or the next collision pass, does the throwing.
 *
 * <p>This is a mechanism read out of the code rather than a guess, and it is consistent
 * with what the logs rule OUT. An earlier round of mine wrapped the third {@code teleport}
 * in vanilla's {@code handleMovePlayer} (the "moved wrongly" rubber-band) and a later one
 * targeted {@code ContraptionCollider.handleDamageFromTrain}'s impulse. Both were disproved
 * by measurement: {@code moved wrongly} occurs ZERO times in every recorded session while
 * other WARN lines in the same log appear 1254 times, and the damage path would deal about
 * 80 damage at this speed yet the logs show no damage and no deaths at all. A diagnostic
 * watching the carry that moves a STANDING rider also came back clean - over 152 samples
 * the contact-point displacement never exceeded the train's own travel by more than 10
 * percent. That leaves this relocation as the one unmeasured step in the dismount path.
 *
 * <h2>The fix</h2>
 *
 * <p>A relocation is refused when it would move the rider further than
 * {@link #MAX_RELOCATION} blocks. The reasoning: the right destination for somebody
 * standing up is where their seat currently is, and a seated rider is ALREADY there,
 * because {@code AbstractContraptionEntity.positionRider} places them at the seat's global
 * position every client tick. So for the fast case, refusing the move leaves them exactly
 * where they should be, and the stale coordinates are worse than no correction at all.
 *
 * <p>The bound is far beyond any legitimate step. A dismount correction is a step to the
 * side of a seat - well under two blocks, and a whole carriage is only a few blocks long -
 * while the stale error is five blocks per tick of latency and grows with speed. Nothing
 * plausible lies between the two, so the bound cannot mistake one for the other. A slow or
 * stationary contraption has an error near zero and never reaches it, which is what keeps
 * Create's own contraptions behaving exactly as before. The bound is three rather than
 * tighter because this mixin applies to every contraption, and a large multi-block one can
 * legitimately seat somebody further from its origin than a train carriage does.
 *
 * <p>The tag is REMOVED even when the move is refused. Leaving it would retry the same
 * failed relocation every tick for as long as the train kept moving, and the tag is only
 * ever read once by design.
 *
 * <h2>Cost</h2>
 *
 * <p>Nothing unless a dismount relocation is pending: one NBT lookup that Create performs
 * immediately afterwards anyway, no allocation in the normal path, no network traffic, and
 * no per-tick work at all. Every refusal is logged, so if this ever refuses a legitimate
 * relocation the evidence is in the log rather than in a bug report.
 */
@Mixin(ContraptionHandler.class)
public class ContraptionDismountMixin {

    @Unique
    private static final Logger electroEnergetics$LOG = LoggerFactory.getLogger("CEE-DIAG");

    /** Furthest a dismount relocation may move a rider, in blocks. See the class comment. */
    @Unique
    private static final double MAX_RELOCATION = 3.0d;

    /**
     * TEMPORARY. How many ticks to keep reporting the rider after a relocation, so the
     * build can be checked in play rather than on trust. Remove with the block that uses it.
     *
     * <p>Deliberately here rather than in a second mixin: two mixins injecting at the HEAD
     * of the same method have no defined relative order, so the observation and the fix
     * could not be sure which ran first. One injection that does both removes the question.
     */
    @Unique
    private static final int TRACK_TICKS = 20;

    @Unique
    private static LivingEntity electroEnergetics$tracked;
    @Unique
    private static int electroEnergetics$trackedFor;

    @Inject(method = "entitiesWhoJustDismountedGetSentToTheRightLocation", at = @At("HEAD"),
            cancellable = true, remap = false)
    private static void electroEnergetics$refuseStaleDismount(LivingEntity rider, Level world,
                                                              CallbackInfo ci) {
        // Create's own guard, repeated because this injection runs before its body.
        if (!world.isClientSide)
            return;

        CompoundTag data = rider.getPersistentData();
        if (!data.contains("ContraptionDismountLocation"))
            return;

        ListTag list = data.getList("ContraptionDismountLocation", Tag.TAG_DOUBLE);
        if (list.size() < 3)
            return;

        Vec3 from = rider.position();
        Vec3 target = new Vec3(list.getDouble(0), list.getDouble(1), list.getDouble(2));
        double distance = target.subtract(from).length();

        // Start following the rider either way, so a case this bound does NOT catch is
        // still visible in the log instead of only in the bug report.
        electroEnergetics$tracked = rider;
        electroEnergetics$trackedFor = TRACK_TICKS;

        if (distance <= MAX_RELOCATION)
            return;   // an ordinary correction: let Create apply it

        data.remove("ContraptionDismountLocation");
        ci.cancel();

        // onGround is deliberately NOT touched. Create sets it false because it has just
        // moved the entity; here nothing moved, and the rider is still standing on the
        // carriage, so the collision pass is left to report their ground state as usual.
        electroEnergetics$LOG.info("DISMOUNT refused dist={} from={} to={} rider={}",
                String.format("%.2f", distance), fmt(from), fmt(target),
                rider.getType().toString());
    }

    /**
     * TEMPORARY. Reports the tracked rider for a few ticks after they stood up.
     *
     * <p>Because the throw, if there is one, is in the ticks AFTER the relocation - the
     * rider is either left beside the track or caught by the next collision pass - so
     * watching only the relocation would miss it. A rider who stays with the train shows a
     * per-tick step matching the train's own travel; one who is thrown shows a step far
     * larger than that, or a falling one.
     *
     * <p>Hooked at the head of the class's per-tick entry point, which the client already
     * calls once a tick, so this schedules nothing of its own and costs nothing while
     * nothing is tracked.
     */
    @Inject(method = "tick", at = @At("HEAD"), remap = false)
    private static void electroEnergetics$followTracked(Level world, CallbackInfo ci) {
        try {
            if (electroEnergetics$trackedFor <= 0 || electroEnergetics$tracked == null)
                return;

            LivingEntity rider = electroEnergetics$tracked;
            electroEnergetics$trackedFor--;

            electroEnergetics$LOG.info("TRACK t-{} pos={} delta={} onGround={} inTrain={}",
                    electroEnergetics$trackedFor, fmt(rider.position()),
                    fmt(rider.getDeltaMovement()), rider.onGround(),
                    rider.getVehicle() != null);

            if (electroEnergetics$trackedFor <= 0)
                electroEnergetics$tracked = null;
        } catch (Throwable ignored) {
            electroEnergetics$trackedFor = 0;
            electroEnergetics$tracked = null;
        }
    }

    @Unique
    private static String fmt(Vec3 v) {
        return String.format("(%7.2f,%7.2f,%7.2f)", v.x, v.y, v.z);
    }
}
