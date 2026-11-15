package com.george_vi.electroenergetics.mixins;

import com.george_vi.electroenergetics.content.railway_electrification.CabOrientation;
import com.george_vi.electroenergetics.content.railway_electrification.ElectricManualSpeed;
import com.george_vi.electroenergetics.content.railway_electrification.ElectricTrainData;
import com.george_vi.electroenergetics.content.railway_electrification.TrainTractionModel;
import com.george_vi.electroenergetics.mixin_interfaces.ICEETrainExtension;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Navigation;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.DiscoveredPath;
import com.simibubi.create.content.trains.station.GlobalStation;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;

/**
 * Lets a manually driven electric train reach the speed its traction model
 * actually allows, instead of Create's fuel-train handicap.
 *
 * <p>This is the server-side half: {@code control()} computes
 * {@code topSpeed = maxSpeed() * manualTrainSpeedModifier} and feeds it to
 * {@code targetSpeed}. Leaving the factor at 0.75 capped a 100 m/s set at
 * 75 m/s. See {@link ElectricManualSpeed}.
 *
 * <p>The call site is inside the branch that has already returned on the client,
 * so this only ever runs on the server.
 */
@Mixin(CarriageContraptionEntity.class)
public class CarriageContraptionEntityMixin {

    @ModifyExpressionValue(
            method = "control",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/createmod/catnip/config/ConfigBase$ConfigFloat;getF()F"),
            remap = false)
    private float electroEnergetics$electricTrainManualTopSpeed(float original) {
        Carriage carriage = ((CarriageContraptionEntity) (Object) this).getCarriage();
        return ElectricManualSpeed.topSpeedFactor(carriage == null ? null : carriage.train, original);
    }

    /**
     * Record which way the cab the driver is holding faces, once a tick.
     *
     * <p>{@code control()} is the right hook because it is the one method that knows
     * BOTH the driving player and the exact controls block they are standing at, and
     * the server calls it every tick while the controls are held. Create computes the
     * same value here as a local and uses it to flip its own {@code targetSpeed}; the
     * lever does not go through that path, so it has to be told separately.
     *
     * <p>Recorded rather than passed down because the gear law runs on the train's own
     * tick, which is not this call: by the time the law runs, the controls block
     * position is long out of scope. Storing it on the train's driver state also keys
     * it to the same driver identity the lever packets are checked against.
     *
     * <p>Only for a consist with traction motors, so a fuel train's state is never
     * touched. {@code control()} already returns early on the client and for an invalid
     * driver, so a value is only ever written for someone genuinely at the controls.
     */
    @Inject(method = "control", at = @At("HEAD"), remap = false)
    private void electroEnergetics$recordCabOrientation(BlockPos controlsLocalPos,
                                                       Collection<Integer> heldControls,
                                                       Player player,
                                                       CallbackInfoReturnable<Boolean> cir) {
        CarriageContraptionEntity self = (CarriageContraptionEntity) (Object) this;
        Carriage carriage = self.getCarriage();
        if (carriage == null || carriage.train == null)
            return;
        ElectricTrainData data = ((ICEETrainExtension) carriage.train).getElectricTrainData();
        if (data == null || !data.hasTractionMotors)
            return;
        // Only while this mod actually owns the train. Once it has been handed to Create there is no
        // lever of ours to orient, and Create computes the cab's direction for its own controls.
        if (!data.underElectricControl())
            return;
        data.driver.setDriverCabInverted(CabOrientation.isInverted(self, controlsLocalPos));

        // Track the space key for automatic station arrival.
        //
        // Recorded here because this is the only place that sees the held keys, and it
        // is refreshed every tick the driver holds anything at all. Index 4 is jump,
        // which the controls block reads as "take me to the next station" - the button
        // Create's own HUD calls the automatic arrival control.
        //
        // A countdown rather than a flag: Create stops calling this method entirely once
        // no key is held, so the release of space is only ever visible as the absence of
        // further reports, and there is no event to catch. See TrainDriverState.tickSpace.
        if (heldControls.contains(4))
            data.driver.setSpaceHeld();
    }

    /**
     * THE OWN APPROACH. The operator ruled Create's hold-space machinery unfit for this mod's
     * driving model and asked for a separate one, so this wrap is the seam: for a driven electric
     * train, Create's scan still runs (its result is our input) but its branch STANDS DOWN - this
     * handler returns null, control() takes the cleanup path, and nothing Create-side navigates,
     * prompts or re-arms. What remains of Create is machinery, not policy: the path find (which
     * also carries the signal reservation once started), the distance the scan keeps fresh, and the
     * front listener that completes an arrival at crawl speed.
     *
     * <p>The lifecycle is the mod's, in this one evaluation per tick while the key is held: latch
     * (one hold = one arrival - after an arrival the key still being down asks for nothing) ->
     * armed (the servo owns the speed; nothing re-arms behind its back) -> the gate on the FRESH
     * path find, not on the navigation's mutable distance: stoppable (x >= v^2/(2 a_eff), the drag
     * the operator asked for inside a_eff) means startNavigation here - the reservation, the servo,
     * and a ~5 Hz action-bar readout; otherwise one warning per press with the numbers and the
     * lever keeps the train (driving in anyway ends in the listener's stop AT the platform, which
     * GameEvents parks as the dispatcher's remote stop). Fuel trains and unowned trains keep
     * Create's branch exactly as it was.
     */
    @WrapOperation(method = "control", at = @At(value = "INVOKE",
            target = "Lcom/simibubi/create/content/trains/entity/Navigation;findNearestApproachable(Z)Lcom/simibubi/create/content/trains/station/GlobalStation;"),
            remap = false)
    private GlobalStation electroEnergetics$ownApproach(Navigation navigation, boolean forward,
                                                        Operation<GlobalStation> original) {
        CarriageContraptionEntity self = (CarriageContraptionEntity) (Object) this;
        Carriage carriage = self.getCarriage();
        if (carriage == null || carriage.train == null || carriage.train.navigation == null)
            return original.call(navigation, forward);
        Train train = carriage.train;
        ElectricTrainData data = ((ICEETrainExtension) train).getElectricTrainData();
        if (data == null || !data.underElectricControl() || data.driver == null)
            return original.call(navigation, forward);

        // CREATE'S OWN SCAN IS THE WRONG TOOL, AND THIS IS WHY THE PROMPT NEVER SHOWED.
        //
        // findNearestApproachable is written for an AUTOMATIC train that must be able to turn around after the
        // stop, so it searches with two limits of its own:
        //
        //     turnaround  = 0.75 * v^2 / (2a)          -- and a station is rejected when turnaround < its distance
        //     minDistance = max(32.0, 1.5 * v^2/(2a))  -- stations nearer than this are not even considered
        //
        // Both are functions of SPEED. At a crawl - the operator's test: 1 m/s, edging up to the platform - the
        // turnaround figure collapses to a fraction of a Block and every station fails the first test, while the
        // 32-Block floor hides everything within 32 Blocks anyway. Measured behaviour, straight from the probe:
        //
        //     [gate] v=0.883 x=-0.43 needed=0.45 station=Track Station
        //
        // x NEGATIVE - the train was already level with the platform before the scan ever admitted it existed.
        // That is the whole report: "the prompt never appears, and when it finally says anything it says it cannot
        // stop". Both statements are the same scan refusing to look.
        //
        // So the scan is done here, once, from the train's own position, with the speed-dependent floor and the
        // turning-circle test removed - see electroEnergetics$nearestStationPath. The backward direction is asked
        // for exactly as Create asks for it: a double-ended train driving backwards approaches through its rear cab,
        // and searching forwards regardless would report a station behind it as unreachable.
        //
        // The stopping distance is computed FIRST, because it is also what bounds the scan: a station further away
        // than the train's own stopping distance plus a margin cannot be prompted for yet, so looking for it is
        // wasted frontier.
        double vMs = Math.abs(train.speed) * 20d;
        double gradeTravel = train.speed < 0 ? -data.trackGrade : data.trackGrade;
        double needed = electroEnergetics$stoppingDistance(vMs, gradeTravel, train.carriages.size());
        DiscoveredPath path = electroEnergetics$nearestStationPath(navigation, forward, needed);
        if (path == null)
            return original.call(navigation, forward);   // nothing reachable: Create may answer as it likes
        GlobalStation station = path.destination;
        // WHICH STATION COUNTS AS "THE NEXT ONE".
        //
        // Create signs the path distance by the direction it drives in: positive is ahead of the leading end,
        // negative is behind it (a station the train would have to REVERSE into). The operator's rule is about
        // the station the train is running AT, and a station behind it is not that - Create's own directional
        // scan never offered one either. So a negative distance is not an approach, and it must not raise the
        // "cannot stop" warning for a station the driver is not heading for.
        if (path.distance <= 0d) {
            data.driver.arrivalRefused = false;
            return null;
        }
        if (data.driver.approachLatch || data.driver.autoArrive)
            return null;                     // Create stands down; the mod owns the hold
        if (Boolean.getBoolean("cee.debugApproach")) {
            System.out.println(String.format(
                    "[gate] v=%.3f x=%.2f needed=%.2f held=%s station=%s forward=%s",
                    vMs, path.distance, needed, data.driver.isSpaceHeld(), station.name, forward));
        }
        java.util.UUID controller = self.getControllingPlayer().orElse(null);
        net.minecraft.server.level.ServerPlayer holder = controller == null
                || self.level().getServer() == null ? null
                : self.level().getServer().getPlayerList().getPlayer(controller);
        // THE PROMPT. Create's own is drawn from this same call, which this wrap returns null for - so turning
        // its branch off turned the prompt off with it, and for a while the mod had no "hold space" prompt at
        // all: the only lines a driver ever saw were the two ENDINGS (armed, and refused). Reported as "the
        // hold-space prompt hardly ever appears"; it was never drawn.
        //
        // The operator's rule, which is the whole gate:
        //
        //   x >= v^2/(2a) + drag     the prompt SHOWS, and holding space from that instant stops at the platform
        //   x <  that                the prompt is WITHHELD and holding space warns instead
        //
        // where a is the brake gear's absolute maximum deceleration and drag is what resistance and grade give
        // for free. Both sides of that comparison are the same method (TrainTractionModel.arrivalBrakeRate), so
        // what the prompt promises is exactly what the controller can deliver. The check runs on EVERY pass, not
        // once: releasing space and holding it again re-evaluates from the new x and v, which is the operator's
        // requirement - the verdict is a function of where the train is now, never of what it was.
        if (holder == null)
            return null;

        if (path.distance >= needed) {
            // Prompt first: the driver must see the invitation before the key does anything.
            holder.displayClientMessage(Component.translatable(
                    "electroenergetics.train.arrival.prompt", station.name,
                    (int) Math.floor(Math.max(path.distance, 0d))), true);

            if (!data.driver.isSpaceHeld())
                return null;                     // shown, not taken: Create's branch stays down, nothing arms

            train.navigation.startNavigation(path);   // the path and its signal reservation, ours
            data.driver.arrivalRefused = false;
            data.driver.autoArrive = true;
            // Remember what the driver had selected, so cancelling can restore it.
            data.driver.autoArriveGear = data.driver.gear;
            if (self.level().getGameTime() % 4 == 0)
                holder.displayClientMessage(Component.translatable(
                        "electroenergetics.train.arrival.active",
                        station.name,
                        (int) Math.ceil(Math.max(train.navigation.distanceToDestination, 0d))), true);
            return null;
        }
        if (!data.driver.arrivalRefused) {
            data.driver.arrivalRefused = true;
            holder.displayClientMessage(Component.translatable(
                    "electroenergetics.train.arrival.tooClose",
                    String.format("%.0f", vMs * 3.6d),
                    (int) Math.ceil(needed),
                    (int) Math.floor(Math.max(path.distance, 0d))), true);
        }
        return null;
    }

    /**
     * The floor and the headroom of the station scan, in Blocks.
     *
     * <p>The scan is bounded by the distance at which the answer can still matter: the prompt may only be shown when
     * the remaining distance reaches the stopping distance, so a station further away than that plus a margin is one
     * the driver has not reached the decision point for yet. Bounding the search there keeps it cheap at a crawl and
     * no more expensive than Create's own scan at speed, without changing the guarantee in the direction that
     * matters: <b>a prompt that shows is always a stop that can be made</b>.
     *
     * <p>The floor is what makes the operator's own test work. At their measured 0.883 m/s the stopping distance is
     * 0.45 Blocks, and a scan bounded by that would find nothing at all - which is exactly the pathology this
     * replaces. 64 Blocks is the same order as Create's own 32-Block floor, so a station four Blocks away is seen,
     * and the scan stays trivial.
     *
     * <p>{@code control()} runs once per tick per driven train (ControlsServerHandler.tick -> control), so the bound
     * is not a nicety: an open-ended scan here would be a whole-graph Dijkstra every tick.
     */
    @org.spongepowered.asm.mixin.Unique
    private static final double APPROACH_SCAN_FLOOR = 64d;
    @org.spongepowered.asm.mixin.Unique
    private static final double APPROACH_SCAN_MARGIN = 64d;

    /**
     * The stopping distance, straight from the model.
     *
     * <p>Deliberately NOT memoised. A cache here would have to be keyed per train - {@code control()} runs for
     * every driven train in the same tick - and a shared one would hand one train another's figure, which is a
     * wrong braking distance silently promising a stop that cannot be made. The integral is 33 evaluations of
     * {@code arrivalBrakeRate}, which is a handful of multiplies and two config reads each: of the order of a
     * thousand arithmetic operations per tick per driven train. That is noise next to Create's own path search on
     * the same tick, and correctness is worth more than it.
     */
    @org.spongepowered.asm.mixin.Unique
    private static double electroEnergetics$stoppingDistance(double vMs, double gradeTravel, int carriages) {
        return TrainTractionModel.stoppingDistance(vMs, gradeTravel, carriages);
    }

    /**
     * The path to the nearest reachable station, with no speed-dependent floor and no turn-around requirement.
     *
     * <p>Create's own scan passes {@code minDistance = .75 v^2/(2a)} and {@code maxDistance = max(32, 1.5 v^2/(2a))}
     * - both functions of speed - and rejects a station whose distance is BELOW the turning circle, which is a rule
     * for an AUTOMATIC train that must be able to reverse out of a station afterwards. A driver asking whether the
     * next station is stoppable for is asking a different question, and the answer does not change because the
     * train is slow. At the operator's measured crawl (0.883 m/s) the turning circle collapses to 0.29 Blocks, so
     * every station four Blocks away failed it - the reported "the prompt never appears".
     *
     * <h2>The previous version of this method never returned a station at all</h2>
     *
     * <p>It called {@code navigation.search(0, -1, true, all, ...)} and then iterated {@code all}. But
     * {@code destinations} is an INPUT to {@code search}, never an output: it is only read, for the octile distance
     * estimate that orders the frontier -
     *
     * <pre>
     *     if (destinations != null &amp;&amp; !destinations.isEmpty()) {
     *         remainingDist = Double.MAX_VALUE;
     *         for (GlobalStation destination : destinations) { ...octile... }
     *     }
     * </pre>
     *
     * - and nothing in {@code search} adds to it. Checked against the shipped jar (Create 6.0.10): the only
     * {@code ArrayList} calls inside {@code search} are {@code iterator()} twice and {@code isEmpty()} once, and
     * there is no {@code add}. So the list came back exactly as it went in - empty - and the loop under it never
     * ran. On top of that the maxDistance passed was 0, and {@code search} drops every frontier entry whose
     * distance exceeds maxDistance, so the scan was not merely useless but almost empty. The method returned null on
     * every tick, the wrap fell through to Create's speed-limited scan, and the prompt went with it.
     *
     * <p>So the scan is done the way Create does it - one bounded {@code search} whose callback returns true to
     * accept a station and stop - with the speed-dependent terms removed.
     *
     * @return the path to the nearest reachable, unoccupied station, or null when there is none in range
     */
    @org.spongepowered.asm.mixin.Unique
    private DiscoveredPath electroEnergetics$nearestStationPath(Navigation navigation, boolean forward,
                                                               double needed) {
        try {
            Carriage carriage = ((CarriageContraptionEntity) (Object) this).getCarriage();
            if (carriage == null || carriage.train == null || carriage.train.graph == null)
                return null;
            Train train = carriage.train;
            // THE BOUND. The prompt can only ever show once x >= needed, so a station beyond needed plus a margin
            // is one whose decision point has not arrived yet. The floor is what keeps the crawl case working: at
            // 0.883 m/s `needed` is 0.45 Blocks, and a scan bounded by that would find nothing - the pathology this
            // whole method replaces. Bounded at 64 Blocks it sees the station four Blocks away, for the same order
            // of work Create's own 32-Block floor does.
            double scanRange = Math.max(APPROACH_SCAN_FLOOR, needed + APPROACH_SCAN_MARGIN);
            // The callback's contract: return true to accept and END the search. The frontier is a priority queue
            // ordered by distance, so the first station that passes is the nearest one - which is why this needs no
            // candidate list and no second pass.
            com.simibubi.create.content.trains.station.GlobalStation[] found =
                    new com.simibubi.create.content.trains.station.GlobalStation[1];
            // destinations = null on purpose: the octile estimate that list triggers is only an accelerator when
            // the destinations are known in advance. Here it is whatever we find, so the estimate would be pure
            // per-frontier-node cost over a list that says nothing.
            navigation.search(scanRange, -1d, forward, null,
                    (distance, cost, edgeMap, edgePair, station) -> {
                        // A station another train is standing at is not available to approach: Create's own rule,
                        // and the reason an occupied platform must not be offered.
                        Train present = station.getPresentTrain();
                        if (present != null && present != train)
                            return false;
                        found[0] = station;
                        return true;
                    });
            if (found[0] == null)
                return null;
            // The path, for its distance and its signal reservation. findPathTo is told to look only at this one
            // station, so it costs one search per direction rather than one per station on the network.
            DiscoveredPath path = navigation.findPathTo(found[0], -1.0d);
            if (path == null || path.destination != found[0])
                return null;
            return path;
        } catch (Throwable ignored) {
            return null;                     // a scan that throws must not take the tick with it
        }
    }
}
