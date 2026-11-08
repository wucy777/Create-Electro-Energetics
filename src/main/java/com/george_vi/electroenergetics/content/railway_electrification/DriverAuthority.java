package com.george_vi.electroenergetics.content.railway_electrification;

import com.george_vi.electroenergetics.mixin_interfaces.ICEETrainExtension;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import net.createmod.catnip.platform.CatnipServices;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * The one-driver rule: who may take a train's controls, and when the claim ends.
 *
 * <h2>The rule</h2>
 *
 * <p>A train has at most one driver. A player takes the controls by right-clicking a
 * controls block of that train while it is free; from that moment they are the ONLY
 * player who may operate it - the electric lever and pantographs (the mod's packets) and
 * Create's own W/S path alike. Everybody else who right-clicks a desk of the same train
 * gets a read-only view of the cab and no control at all. When the driver cancels, the
 * train is FREE: the controls are not handed to anybody else automatically, and the next
 * driver must right-click a controls block themselves.
 *
 * <h2>Where the authority lives</h2>
 *
 * <p>On {@link TrainDriverState#claimOwner}, and nowhere else. Create's own
 * "controlling player" record is kept in step for the driver (it is what makes Create's
 * W/S, its HUD and its animations work for them) but is treated as a MIRROR: a second
 * player is stopped before Create is allowed to write it, and the claim is what every
 * packet is checked against. Deriving the authority from Create's record instead was the
 * earlier state of this code and it cannot express "nobody may take this" - Create has no
 * concept of a reserved seat.
 *
 * <h2>Cancel semantics</h2>
 *
 * <p>These are the ONLY ways a claim ends, and they are deliberately different in kind:
 *
 * <ol>
 *   <li><b>Right-click a controls block of the train again.</b> Explicit, discoverable and
 *       server-side: it is the one action a player takes meaning "I am done". Also the
 *       only cancel that works from a distance, because it is an interaction.</li>
 *   <li><b>Out of reach of the desk.</b> Checked every server tick, at Create's own
 *       input range ({@link #DESK_REACH}). Covers walking away, being teleported, dying and
 *       respawning, and being moved by anything else - the cases where Create's mirror would
 *       otherwise keep saying the player is at a desk they are nowhere near.</li>
 *   <li><b>Gone.</b> The player disconnected, became a spectator, or the carriage holding
 *       the desk is no longer present.</li>
 *   <li><b>Create let go.</b> Pressing ESC or sneak releases Create's own controls, and
 *       Create also drops them for its own reasons (the train derailed, the desk was
 *       removed). The mirror record disappearing is therefore treated as a cancel. This is
 *       what keeps the mod's claim from outliving the cab the player actually left.</li>
 * </ol>
 *
 * <p>None of these hands the train to anybody. Releasing is a cancellation, not a
 * transfer.
 *
 * <h2>Grace</h2>
 *
 * <p>Routes 2-4 are corroboration checks, and any of them can flicker for a tick while a
 * contraption entity is replaced or a position packet lands late. A claim therefore has to
 * stay un-corroborated for {@link #INVALID_GRACE_TICKS} consecutive ticks before it is
 * released. Five ticks is a quarter of a second: long enough to ride out a transient,
 * short enough that a real cancel is still immediate from the player's point of view.
 */
public final class DriverAuthority {

    /**
     * How far from the claimed desk the driver may be, in Blocks, before the claim is released.
     *
     * <p>Sixteen, which is the range Create itself accepts a player's control input from:
     * {@code ControlsInputPacket.handle} tests {@code canInteractWithBlock(player, controlsPos, 16)}
     * before a key press is believed. Create has a SECOND, stricter range - {@code 8} in
     * {@code CarriageContraptionEntity.control}, where a player further than that is dropped from
     * the controls - and the two disagree in upstream's own code. The larger of the two is used
     * here on purpose: this check can only ever RELEASE, so being too strict costs the driver
     * their cab, and the server's copy of a standing player's position on a moving contraption
     * lags the carriage by a tick or two. At this mod's line speeds that lag is already several
     * Blocks; an 8-Block test would release a driver who did nothing but travel fast. The
     * stricter number is not lost: when Create drops the driver, its mirror record disappears and
     * the mirror check below releases the claim - it is merely checked at Create's own pace.
     */
    public static final double DESK_REACH = 16.0d;

    /** Consecutive un-corroborated ticks before a claim is released. */
    public static final int INVALID_GRACE_TICKS = 5;

    /** How far around the train a claim change is broadcast, in Blocks. */
    public static final double SYNC_RANGE = 100.0d;

    private DriverAuthority() {}

    /**
     * Whether the one-driver rule applies to this train at all.
     *
     * <p>The same definition of "electric train" the rest of the feature uses: traction
     * motors AND at least one collector, with a rail-shoe-only metro excluded because
     * upstream behaviour is the operator's ruling for those. A fuel train, a train with
     * motors but no pantograph, and a shoe-only metro all keep Create's controls exactly
     * as they were, for anybody, and never take a claim.
     */
    /** Whether this player owns the driving claim of this train. */
    public static boolean isClaimOwner(Train train, java.util.UUID playerId) {
        if (train == null || playerId == null)
            return false;
        var data = ((com.george_vi.electroenergetics.mixin_interfaces.ICEETrainExtension) train)
                .getElectricTrainData();
        return data != null && data.driver != null
                && playerId.equals(data.driver.claimOwner);
    }

    public static boolean appliesTo(Train train) {
        if (train == null)
            return false;
        ElectricTrainData data = dataOf(train);
        return data.hasTractionMotors && !data.railShoeOnly();
    }

    private static ElectricTrainData dataOf(Train train) {
        return ((ICEETrainExtension) train).getElectricTrainData();
    }

    /**
     * A player right-clicked a controls block of this train, on the server.
     *
     * @return {@code true} when the interaction has been handled and Create's own
     *         interaction must NOT run - a cancel, or a refusal of a second driver.
     *         {@code false} lets Create's normal right-click handling proceed, which on a
     *         free train is what records the driver there and opens their cab.
     */
    public static boolean onInteraction(ServerPlayer player, BlockPos localPos,
                                        CarriageContraptionEntity cce) {
        Carriage carriage = cce.getCarriage();
        Train train = carriage == null ? null : carriage.train;
        if (train == null || !appliesTo(train))
            return false;
        // A spectator can right-click a controls block, but Create's own startControlling
        // refuses them, so no claim is taken - a claim whose owner can never be corroborated
        // would only be released again on the next tick.
        if (player.isSpectator())
            return false;

        ElectricTrainData data = dataOf(train);
        TrainDriverState driver = data.driver;
        UUID owner = driver.claimOwner;

        // 1. Same player as the owner: this right-click is the explicit cancel. Every
        //    controls block of the train counts, not only the claimed one - a driver who
        //    walks to the other cab to shut down should not have to remember which desk
        //    they started at.
        if (owner != null && owner.equals(player.getUUID())) {
            release(train, driver);
            return true;
        }

        // 2. Somebody else owns it: refuse, and tell them why. The refusal is an action
        //    bar line because it is immediate feedback about a click, not a record. The
        //    claim is sent as well, so a client that had not heard about the driver yet
        //    learns it now instead of inferring anything from Create's mirror.
        if (owner != null) {
            sendClaimTo(player, train);
            player.displayClientMessage(Component.translatable(
                    "electroenergetics.train.driver.occupied", nameOf(player, owner)), true);
            return true;
        }

        // 3. Free. A derailed train cannot be driven - Create's own startControlling
        //    refuses it - so do not take a claim that would be released again a moment
        //    later. Send the (empty) claim anyway so a client that optimistically opened a
        //    read-only cab learns there is nobody to watch and closes it, then let Create
        //    answer the interaction (it refuses, doing nothing).
        if (train.derailed) {
            sendClaimTo(player, train);
            return false;
        }

        int carriageIndex = train.carriages.indexOf(carriage);
        if (carriageIndex < 0)
            return false;
        var claimEntity = carriage.anyAvailableEntity();
        System.out.println("[claim] carriage=" + carriageIndex + " desk=" + localPos
                + " seats=" + (claimEntity == null || claimEntity.getContraption() == null ? "?"
                        : String.valueOf(claimEntity.getContraption().getSeats().size()))
                + " cars=" + train.carriages.size());
        driver.claim(player.getUUID(), carriageIndex, localPos);
        // The desk's own seat is the driver's, and it cannot be taken by clicking the seat: the way to sit
        // in it is to take the controls. So the seating happens here, on the claim, rather than waiting for
        // the speed-gated pass to notice at 28 m/s - a driver alone on a stationary train would otherwise
        // stand at the desk indefinitely.
        RiderSeatGate.seatClaimedDriver(train, RiderSeatGate.reservedDriverSeats(train));
        // Broadcast BEFORE Create records its own mirror, deliberately: a viewer's client
        // may be looking at the panel already, and telling it the answer first means its
        // read-only state is right from the first frame.
        broadcastClaim(train, driver);
        // Let Create do its normal job: record the controlling player (the mirror its own
        // W/S path needs) and open the driver's cab.
        return false;
    }

    /**
     * Advance one train's claim validation. Called once per server tick, before the driving
     * state is used.
     *
     * <p>Also the single place that decides this tick's {@link TrainDriverState#driverId}
     * on a gated train: from the claim when it is valid, {@code null} when it is not. That
     * is what makes the driver state honest - it cannot say somebody is at the controls
     * unless the claim corroborates it.
     */
    public static void tickClaim(Train train, TrainDriverState driver, MinecraftServer server) {
        UUID owner = driver.claimOwner;
        if (owner == null) {
            driver.setDriverPresent(null);
            return;
        }

        ServerPlayer player = server.getPlayerList().getPlayer(owner);
        if (player == null || player.isSpectator()) {
            // Route 3: gone. No message - quitting does not need one, and a spectator
            // cannot be at the controls by definition.
            release(train, driver);
            driver.setDriverPresent(null);
            return;
        }

        // The driver must be IN the seat while the train is above the ceiling, and the check is made here
        // because refusing the dismount does not work: the refusal fires, the operator sees the message, and the
        // player leaves the seat anyway - Create removes the passenger on a path that does not pass through
        // Entity.stopRiding at all, so cancelling that method cancels nothing.
        //
        // What is checked instead is the OUTCOME, one tick later: is the driver still sitting. If not, the seat
        // is given back. This is one comparison on a vehicle reference for the single train that has a driver,
        // and it only runs while that train is above the ceiling - a stopped train does not reach it.
        //
        // A driver who has genuinely left the train is not touched: the corroboration test below releases the
        // claim for them, and there is nobody to seat.
        // THE seat, not any seat. A driver who slipped past the dismount lock and ended up in a
        // passenger seat was previously read as "seated" by this check and left there; the lock's
        // outcome rule only means something if the seat it lands in is the one the claim owns.
        // NOT here. This used to push the driver back into their reserved seat every tick, and seatClaimedDriver
        // EVICTS an occupant first - so a driver who was in any other seat was pulled off it and re-seated, at
        // speed, once per tick. At tick rate 500 that is the operator being flung down the train at 314 km/h with
        // no input of their own.
        //
        // The rule the operator actually wants is "you cannot leave the driver's seat at speed", and that is the
        // dismount lock's job. Restoring the seat is a recovery for a missed lock, not a per-tick force, and this
        // path is where it stopped being one.

        CarriageContraptionEntity cce = carriageEntity(train, driver.driverCarriage);
        boolean corroborated = cce != null
                && driver.driverDesk != null
                && cce.level() == player.level()
                && cce.canInteractWithBlock(player, driver.driverDesk, DESK_REACH)
                && mirrorHolds(cce, owner)
                // Standing up ends the claim. The operator's rule is that the controls and the driver's
                // seat are one thing: a player who leaves the seat is no longer at the controls, and a
                // claim that outlived its seat would hold the cab against everybody else while nobody was
                // driving. Grace of INVALID_GRACE_TICKS covers the tick a seat change is still settling.
                // Still at the controls means still ON the train - not necessarily still seated. Opening the
                // pause menu and coming back drops the seat client-side for a tick, and a claim released on
                // that tick is a driver standing up at speed, which the seat lock then cannot prevent: the
                // operator saw exactly that, thrown off the train on returning from ESC. The stand-up rule is
                // enforced by the seat lock and by release(), not by this corroboration test.
                // Riding counts, and so does standing in the cab - but not standing on the ground outside.
                //
                // The near test alone was too loose: a driver thrown clear of the train, still within the desk's
                // sixteen Blocks, kept the claim and the reserved seat. But riding alone is too tight in the other
                // direction, because getIndirectPassengers briefly misses a rider at speed and that is exactly the
                // tick this would fire on.
                //
                // So: riding, or standing on a carriage floor. NOT merely within reach of the desk.
                && (RiderSeatGate.isRidingTrain(train, player)
                    || RiderSeatGate.isStandingOnAnyFloor(train, player));
        if (!corroborated) {
            // Throttled to the first few ticks of a failure run: the point is to see WHICH term broke, and a
            // per-tick log would bury it in ten thousand identical lines.
            if (driver.claimInvalidTicks < 3 || driver.claimInvalidTicks == 5)
                System.out.println(String.format(
                        "[claim-invalid] t=%d cce=%s desk=%s sameLevel=%s reach=%s mirror=%s riding=%s floor=%s",
                        driver.claimInvalidTicks,
                        cce != null, driver.driverDesk != null,
                        cce != null && cce.level() == player.level(),
                        cce != null && driver.driverDesk != null
                                && cce.canInteractWithBlock(player, driver.driverDesk, DESK_REACH),
                        cce != null && mirrorHolds(cce, owner),
                        RiderSeatGate.isRidingTrain(train, player),
                        RiderSeatGate.isStandingOnAnyFloor(train, player)));
            if (++driver.claimInvalidTicks > INVALID_GRACE_TICKS) {
                // Reached before the release, so the message can tell the driver why.
                if (cce != null && driver.driverDesk != null
                        && cce.level() == player.level()
                        && !cce.canInteractWithBlock(player, driver.driverDesk, DESK_REACH))
                    player.displayClientMessage(Component.translatable(
                            "electroenergetics.train.driver.outOfReach"), true);
                release(train, driver);
                driver.setDriverPresent(null);
                return;
            }
        } else {
            driver.claimInvalidTicks = 0;
        }
        // Per-second heartbeat for the driver's own state, so a throw can be told apart from a dismount: if the
        // heartbeat keeps saying riding=true while the operator reports being outside, the seat was never lost and
        // the fault is in where the client is putting them.
        if (Boolean.getBoolean("cee.debugSeatLock") || true) {
            long now = server.getTickCount();
            if (now % 40 == 0)
                System.out.println(String.format(
                        "[heartbeat] tick=%d speed=%.3fbpt gear=%s inverted=%s cab=%d seated=%s riding=%s",
                        now, train.speed, data.driver.gear,
                        data.driver.driverCabInverted, data.driver.driverCarriage,
                        RiderSeatGate.isInClaimedDriverSeat(train, player),
                        RiderSeatGate.isRidingTrain(train, player)));
        }
        driver.setDriverPresent(owner);
    }

    /**
     * Release the claim and stop Create's mirror of it, on every carriage that still holds
     * one.
     *
     * <p>The mirror has to be stopped here rather than left to Create: on the explicit
     * cancel route Create's own interaction never runs (the click was consumed), and on the
     * automatic routes Create has either already let go or is about to be told. Stopping it
     * also sends Create's own "stopped controlling" packet to the ex-driver, which closes
     * their panel and tells them what happened without a second message from this mod.
     */
    public static void release(Train train, TrainDriverState driver) {
        UUID owner = driver.claimOwner;
        if (owner == null)
            return;
        BlockPos desk = driver.driverDesk == null ? BlockPos.ZERO : driver.driverDesk;
        // What ended the claim, and who was riding where. One line, printed only when the operator asks with
        // -Dcee.debugSeatLock=true. Reasoning about which of the release routes fired has cost several rounds;
        // this answers it from the log instead.
        if (true) {
            var d = ((com.george_vi.electroenergetics.mixin_interfaces.ICEETrainExtension) train)
                    .getElectricTrainData().driver;
            System.out.println(String.format(
                    "[claim-release] speed=%.2fbpt(%s ms) invalidTicks=%d desk=%s carriage=%d owner=%s",
                    Math.abs(train.speed), String.format("%.1f", RiderSeatGate.speedMs(train)),
                    d.claimInvalidTicks, desk, d.driverCarriage,
                    String.valueOf(owner).substring(0, 8)));
        }
        for (Carriage carriage : train.carriages) {
            carriage.forEachPresentEntity(cce -> {
                if (cce.getControllingPlayer().filter(owner::equals).isPresent())
                    cce.stopControlling(desk);
            });
        }
        // The claim and the seat go together: releasing the controls stands the driver up, so the reserved
        // seat is empty for the next driver instead of being occupied by somebody who no longer has the desk.
        //
        // NOT above the ceiling, though. Standing up at 80 m/s is being thrown off the train, and this method
        // runs on every automatic release as well as the deliberate one - out of reach of the desk, a dropped
        // mirror, the pause menu. The operator pressed nothing, was accelerating, and found themselves flung
        // down the train and re-seated as an ordinary passenger: that is this call.
        //
        // Above the ceiling the claim is dropped and the player stays where they are. They keep their seat, they
        // simply stop being the driver; the next driver can take the desk and the seating pass moves them if it
        // needs the seat back.
        // Whether it is safe to stand the driver up - and the question has to be asked in the units the train
        // actually moves in.
        //
        // atOrAboveCeiling compares against a Blocks-per-tick constant (28 m/s = 1.4), so under /tick rate 500 a
        // train doing 80 m/s reads 0.16 and the answer is "almost stopped". Every automatic release therefore
        // took the stand-up branch and forceDismount removed a driver who was doing 288 km/h and holding J.
        // That is the "I was thrown out of the seat without touching anything" report, and it is why it only
        // happened at speed on one particular day's testing.
        //
        // A physical question deserves a physical figure, so the speed is converted through the live tick rate
        // here. This is a RELEASE path, not a per-tick rule: it runs when a claim ends.
        boolean atSpeed = RiderSeatGate.speedMs(train) >= RiderSeatGate.createCeilingBps() - 1.0e-4d;
        boolean safeToStandUp = !atSpeed;
        if (owner != null && safeToStandUp) {
            for (Carriage carriage : train.carriages) {
                carriage.forEachPresentEntity(cce -> {
                    for (net.minecraft.world.entity.Entity passenger : cce.getIndirectPassengers()) {
                        if (passenger instanceof net.minecraft.world.entity.player.Player p
                                && owner.equals(p.getUUID()))
                            RiderSeatGate.forceDismount(p);
                    }
                });
            }
        }
        driver.releaseClaim();
        broadcastClaim(train, driver);
    }

    /** Send one player the current claim of this train. */
    public static void sendClaimTo(ServerPlayer player, Train train) {
        TrainDriverState driver = dataOf(train).driver;
        CatnipServices.NETWORK.sendToClient(player,
                new DriverClaimPacket(train.id, driver.claimOwner,
                            driver.claimOwner == null ? -1 : driver.driverCarriage));
    }

    /**
     * Broadcast this train's current claim to the players near it, because it just changed.
     *
     * <p>Nearby rather than the whole level: a claim can only be taken or used by somebody
     * within interaction reach of the train, so a player who cannot see the train cannot
     * care. The range matches the gauge sync for the same reason. Nothing is sent when
     * there is no present carriage to measure from; the claim itself is unaffected, and the
     * next interaction delivers it to whoever needs it.
     */
    public static void broadcastClaim(Train train, TrainDriverState driver) {
        UUID owner = driver.claimOwner;
        for (Carriage carriage : train.carriages) {
            CarriageContraptionEntity cce = carriage.anyAvailableEntity();
            if (cce == null)
                continue;
            if (!(cce.level() instanceof ServerLevel level))
                return;
            Vec3 pos = cce.position();
            CatnipServices.NETWORK.sendToClientsAround(level, pos, SYNC_RANGE,
                    new DriverClaimPacket(train.id, owner,
                        owner == null ? -1 : driver.driverCarriage));
            return;
        }
    }

    /** The present entity of one carriage, or {@code null} when it is not loaded. */
    private static CarriageContraptionEntity carriageEntity(Train train, int index) {
        if (index < 0 || index >= train.carriages.size())
            return null;
        return train.carriages.get(index).anyAvailableEntity();
    }

    /** Whether Create's record on this carriage still says the claim owner is at the controls. */
    private static boolean mirrorHolds(CarriageContraptionEntity cce, UUID owner) {
        boolean holds = cce.getControllingPlayer().filter(owner::equals).isPresent();
        if (!holds)
            System.out.println("[mirror] carriage=" + cce.carriageIndex
                    + " controlling=" + cce.getControllingPlayer().map(UUID::toString).orElse("none")
                    + " owner=" + String.valueOf(owner).substring(0, 8));
        return holds;
    }

    /** A player's display name, resolved from the server list, with a neutral fallback. */
    private static Component nameOf(ServerPlayer context, UUID id) {
        MinecraftServer server = context.getServer();
        ServerPlayer player = server == null ? null : server.getPlayerList().getPlayer(id);
        return player != null ? player.getDisplayName() : Component.literal("?");
    }
}
