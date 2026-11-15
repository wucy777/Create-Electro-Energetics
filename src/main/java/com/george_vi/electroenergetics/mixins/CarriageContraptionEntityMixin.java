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
    private GlobalStation electroEnergetics$ownApproach(Navigation navigation, boolean doubleEnded,
                                                        Operation<GlobalStation> original) {
        CarriageContraptionEntity self = (CarriageContraptionEntity) (Object) this;
        Carriage carriage = self.getCarriage();
        if (carriage == null || carriage.train == null || carriage.train.navigation == null)
            return original.call(navigation, doubleEnded);
        Train train = carriage.train;
        ElectricTrainData data = ((ICEETrainExtension) train).getElectricTrainData();
        if (data == null || !data.underElectricControl() || data.driver == null)
            return original.call(navigation, doubleEnded);

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
        // So the scan is done here, once, from the train's own position: every station on the graph, the one whose
        // path is shortest, with no speed-dependent floor. What a human driver considers "the next station" does
        // not change because the train is slow.
        GlobalStation station = electroEnergetics$nearestStation(navigation);
        if (station == null)
            return original.call(navigation, doubleEnded);   // nothing reachable: Create may answer as it likes
        if (data.driver.approachLatch || data.driver.autoArrive)
            return null;                     // Create stands down; the mod owns the hold
        DiscoveredPath path = train.navigation.findPathTo(station, -1.0d);
        if (path == null || path.destination != station)
            return null;
        double vMs = Math.abs(train.speed) * 20d;
        double gradeTravel = train.speed < 0 ? -data.trackGrade : data.trackGrade;
        double needed = TrainTractionModel.stoppingDistance(vMs, gradeTravel, train.carriages.size());
        if (Boolean.getBoolean("cee.debugApproach")) {
            System.out.println(String.format(
                    "[gate] v=%.3f x=%.2f needed=%.2f held=%s station=%s",
                    vMs, path.distance, needed, data.driver.isSpaceHeld(), station.name));
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
     * The nearest station this train can actually path to, with no speed-dependent floor.
     *
     * <p>Create's own scan passes {@code minDistance = max(32, ...)} and rejects stations beyond the distance the
     * train would need to turn around - both functions of speed, both useless at a crawl. This asks the graph for
     * every station and takes the shortest path. The list is small (stations on one rail network, not blocks), and
     * the call happens where Create's scan used to, so the cost is the same order.
     */
    @org.spongepowered.asm.mixin.Unique
    private GlobalStation electroEnergetics$nearestStation(Navigation navigation) {
        try {
            Train train = ((CarriageContraptionEntity) (Object) this).getCarriage().train;
            if (train == null || train.graph == null)
                return null;
            // The 5-argument form: (minDistance, maxDistance, doubleEnded, results, test).
            // minDistance = 0 and maxDistance = -1 are exactly "no floor, no ceiling" - which is the whole
            // point. The lambda accepts every station the graph reaches; the path find below decides which.
            java.util.ArrayList<GlobalStation> all = new java.util.ArrayList<>();
            navigation.search(0d, -1d, true, all,
                    (distance, maxDistance, edgeMap, edgePair, candidate) -> true);
            GlobalStation best = null;
            double bestDistance = Double.MAX_VALUE;
            for (GlobalStation candidate : all) {
                DiscoveredPath path = train.navigation.findPathTo(candidate, -1.0d);
                if (path == null || path.destination != candidate)
                    continue;
                if (path.distance < bestDistance) {
                    bestDistance = path.distance;
                    best = candidate;
                }
            }
            return best;
        } catch (Throwable ignored) {
            return null;                     // a scan that throws must not take the tick with it
        }
    }
}
