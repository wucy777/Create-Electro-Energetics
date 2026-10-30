package com.george_vi.electroenergetics.client;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.actors.seat.SeatEntity;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Turns a rider's view with the contraption, in the two cases Create's own rotation does not cover.
 *
 * <h2>Who owns this behaviour</h2>
 *
 * <p>The original release has no view code of its own: all 2209 entries of
 * {@code Create-Electro-Energetics-1.21.1.zip}, every file under {@code mixins/} included, were read and
 * there is no camera, yaw or rider-rotation path in it. The feature is Create's.
 * {@code ContraptionPlayerPassengerRotation.frame()} adds the per-frame change of
 * {@code CarriageContraptionEntity.getViewYRot(partialTicks)} to the player's yaw, and
 * {@code ClientEvents} runs it after particles, gated on {@code rotateWhenSeated} (enabled in this pack;
 * the class is present in the installed {@code create-1.21.1-6.0.10} jar with bytecode identical to the
 * source this was read from).
 *
 * <h2>Why the previous version could not supply a rotation</h2>
 *
 * <p>It read {@code ContraptionRotationState.yRotation + getYawOffset()}. For a carriage,
 * {@code OrientedContraptionEntity.getRotationState} builds that as {@code -yaw + getInitialYaw()}, plus
 * {@code secondYRotation = -yaw} when pitched - the same angle {@code getViewYRot} returns, up to the
 * constant initial yaw. Its delta is therefore the delta Create already applies: on a rider Create
 * handles, it turned them twice; where the angle is still, it did nothing. It also wrote
 * {@code yRotO}/{@code yHeadRotO}, which fought the render interpolation instead of helping it.
 *
 * <h2>What this fills</h2>
 *
 * <ul>
 *   <li><b>A rider whose direct vehicle is a {@link SeatEntity} mounted on the contraption.</b> Create's
 *       hook returns unless {@code player.getVehicle()} is an {@link AbstractContraptionEntity} itself,
 *       so a seat entity between the player and the carriage makes the rider invisible to it. The seat
 *       turns with the carriage and the view stays in world orientation. {@code
 *       RiderSeatGate.shouldLockDismount} already has to handle this vehicle shape, so it is a real case
 *       and not a hypothetical one.</li>
 *   <li><b>A rider whose carriage does not report a changing view yaw at all.</b> That is the reported
 *       failure: the carriage swings around a world-fixed view, and the SEAT diagnostic taken in this
 *       project measured {@code getViewYRot} standing still while the train was moving. When that angle
 *       does not move there is nothing for Create's hook to apply, however it is called.</li>
 * </ul>
 *
 * <h2>Where the second source comes from, and why its sign is not a guess</h2>
 *
 * <p>{@code Carriage.DimensionalCarriageEntity.alignEntity} derives the carriage's yaw as
 * {@code atan2(rotationAnchorZ, rotationAnchorX) * 180 / PI + 180} from its two bogey anchors, and
 * {@code getViewYRot} returns that value negated. A heading {@code h = atan2(dz, dx)} therefore reaches
 * the view as {@code -(h + 180)}, whatever order the two anchors arrive in. So the change in a heading
 * derived from the carriage's own displacement - {@code -atan2(dz, dx)} - is expressed in exactly the
 * same convention as the change in {@code getViewYRot}, including the sign. This class uses that
 * direction only when {@code getViewYRot} is standing still, which is precisely when Create has nothing
 * to apply.
 *
 * <p>A rider Create already turns is never touched: if the angle it reads is moving, this class applies
 * nothing and a second rotation cannot be added on top.
 *
 * <h2>Cost</h2>
 *
 * <p>Two vehicle tests per tick for every player. For a rider, one angle read, one vector subtraction
 * and one wrapped subtraction; the heading is only computed while the contraption is moving. No
 * allocation.
 */
public final class RiderViewFollow {

    // Temporary: one line a second while riding, so a view that does not turn can be diagnosed from which
    // branch is taken instead of guessed at.
    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("electroenergetics/viewdiag");
    private static int diagTicks;

    private RiderViewFollow() {}

    /** Angle below which a change is float noise rather than motion [degrees]. */
    private static final float EPSILON = 1.0e-4f;

    /** Displacement below which the direction of travel is noise, not a heading [Blocks/tick]. */
    private static final double MIN_MOTION = 0.02;

    /**
     * A single step larger than this is a teleport of the carriage (or of its bookkeeping), and replaying
     * it into the player's view would throw the camera. Real carriages turn far less than this per tick.
     */
    private static final float MAX_STEP = 30.0f;

    private static int followedEntity = -1;
    private static float lastViewYaw = Float.NaN;
    private static float lastHeading = Float.NaN;

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) {
            forget();
            return;
        }

        Entity vehicle = player.getVehicle();
        boolean createCanSeeRider = vehicle instanceof AbstractContraptionEntity;

        AbstractContraptionEntity contraption;
        if (createCanSeeRider)
            contraption = (AbstractContraptionEntity) vehicle;
        else if (vehicle instanceof SeatEntity && vehicle.getRootVehicle() instanceof AbstractContraptionEntity root)
            contraption = root;
        else {
            forget();
            return;
        }

        // The same two angle branches Create's own frame() picks from.
        float viewYaw = contraption instanceof CarriageContraptionEntity carriage
                ? carriage.getViewYRot(1.0f)
                : contraption.getRotationState().yRotation;

        // The contraption's direction of travel, expressed in the same convention as the view angle.
        Vec3 motion = contraption.isPrevPosInvalid() ? Vec3.ZERO
                : contraption.position().subtract(contraption.getPrevPositionVec());
        boolean moving = motion.x * motion.x + motion.z * motion.z >= MIN_MOTION * MIN_MOTION;
        float heading = moving ? (float) (-Mth.atan2(motion.z, motion.x) * 180.0 / Math.PI) : Float.NaN;

        if (contraption.getId() != followedEntity || Float.isNaN(lastViewYaw)) {
            followedEntity = contraption.getId();
            lastViewYaw = viewYaw;
            lastHeading = heading;
            return;
        }

        float viewDelta = Mth.wrapDegrees(viewYaw - lastViewYaw);
        lastViewYaw = viewYaw;

        float headingDelta = 0.0f;
        if (moving) {
            if (!Float.isNaN(lastHeading))
                headingDelta = Mth.wrapDegrees(heading - lastHeading);
            lastHeading = heading;
        } else {
            // Standing still: the next movement must not be measured against the heading the contraption
            // had before it stopped.
            lastHeading = Float.NaN;
        }

        // A rider Create can see is already being turned whenever the angle it reads is moving; adding
        // anything here would turn them twice.
        boolean createIsTurning = Math.abs(viewDelta) > EPSILON;
        if (++diagTicks % 60 == 0) {
            LOG.info("VIEWDIAG vehicle={} createCanSee={} viewYaw={} viewDelta={} headingDelta={} "
                            + "createTurning={}",
                    vehicle == null ? "none" : vehicle.getClass().getSimpleName(),
                    createCanSeeRider, String.format("%.3f", viewYaw),
                    String.format("%.3f", viewDelta), String.format("%.3f", headingDelta),
                    createIsTurning);
        }
        if (createCanSeeRider && createIsTurning)
            return;

        // Otherwise apply the angle Create would have used, or - when it reports no rotation at all -
        // the one the contraption's own direction of travel gives.
        float delta = createIsTurning ? viewDelta : headingDelta;
        delta = Mth.clamp(delta, -MAX_STEP, MAX_STEP);
        if (Math.abs(delta) < EPSILON)
            return;

        player.setYRot(player.getYRot() + delta);
    }

    private static void forget() {
        followedEntity = -1;
        lastViewYaw = Float.NaN;
        lastHeading = Float.NaN;
    }
}
