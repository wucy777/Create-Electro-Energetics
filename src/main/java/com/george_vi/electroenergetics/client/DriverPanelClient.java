package com.george_vi.electroenergetics.client;

import com.george_vi.electroenergetics.content.railway_electrification.TrainHudData;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.actors.trainControls.ControlsHandler;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.Objects;
import java.util.UUID;

/**
 * Keeps the local cab panel in step with the server's one-driver rule.
 *
 * <h2>What this does, and why it has to</h2>
 *
 * <p>Three things, all of them consequences of the panel being Create's:
 *
 * <ol>
 *   <li><b>Mirror repair.</b> Create's own "controlling player" on the contraption entity is
 *       written locally by its right-click prediction. A player who was refused the controls
 *       had already written themselves into it before the server answered, so from then on
 *       Create's client code would think they were at the controls - and a later, legitimate
 *       click would take its "you are already driving, so this is a release" branch and
 *       never re-open the cab. The server's claim is written back over it here. This is a
 *       local write of a synced field only; the server remains the authority and overwrites
 *       it with its own next update if the two ever disagree the other way.</li>
 *   <li><b>The panel dies with the driver.</b> When the claim goes from a driver to nobody,
 *       every player's panel closes - the ex-driver's and every viewer's. Re-opening it
 *       takes a fresh right-click, which is the requirement: the controls must not be handed
 *       over automatically, and a viewer who was watching the previous driver must give up
 *       their view and ask again.</li>
 *   <li><b>A dead panel is closed.</b> If a panel is open while nobody owns the controls and
 *       no claim arrives within a couple of seconds, the claim the client asked for was
 *       refused for a reason that will not resolve itself (a derailed train, say). Closing
 *       the panel beats leaving an inert cab up with no explanation.</li>
 * </ol>
 *
 * <p>Nothing here decides who drives: every answer comes from {@link TrainHudData}'s copy
 * of the server's claim. The only state is what this client last saw, which is what makes
 * the "the driver left" transition detectable.
 */
@OnlyIn(Dist.CLIENT)
public final class DriverPanelClient {

    /**
     * Ticks a panel may sit open with nobody claiming it before it is closed.
     *
     * <p>One second. The case this covers is a panel opened optimistically (the client asked to
     * drive and the claim is in flight) and a panel whose claim the server refused silently - a
     * derailed train, where Create's own {@code startControlling} says no and no claim is recorded.
     * Long enough that an ordinary claim, which answers within a tick or two even on a slow
     * connection, never trips it; short enough that a refused panel does not sit there looking
     * usable.
     */
    private static final int UNCLAIMED_PANEL_TICKS = 20;

    /**
     * Distance from the carriage at which the panel is closed, in Blocks.
     *
     * <p>The panel is opened at the controls and the player normally stays within a couple of
     * Blocks of them - they are standing on the carriage. This only matters for a viewer who
     * opened the panel and then walked off, where nothing else would ever close it: the release
     * broadcast is range-limited, and a client that walked out of range would otherwise keep
     * showing a read-only cab of a train it can no longer see. Large enough that no ordinary
     * standing position on a moving consist is ever inside it by accident.
     */
    private static final double MAX_PANEL_DISTANCE = 32.0d;

    /** The train whose panel was last inspected, so a change of panel is not read as a release. */
    private static UUID observedTrain;
    /** The claim last seen for {@link #observedTrain}; {@code null} means "nobody". */
    private static UUID observedClaim;
    /** Whether a claim was known at all when it was last observed. */
    private static boolean observedKnown;
    /** Consecutive ticks a panel has been open with no driver claimed. */
    private static int unclaimedTicks;

    private DriverPanelClient() {}

    /** Called once per client tick, before the cab keys are read. */
    public static void tick() {
        AbstractContraptionEntity entity = ControlsHandler.getContraption();
        if (!(entity instanceof CarriageContraptionEntity cce)) {
            reset();
            return;
        }
        Carriage carriage = cce.getCarriage();
        Train train = carriage == null ? null : carriage.train;
        if (train == null) {
            reset();
            return;
        }
        // A cab panel does not follow the player around the world. See MAX_PANEL_DISTANCE.
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null
                || mc.player.distanceToSqr(entity) > MAX_PANEL_DISTANCE * MAX_PANEL_DISTANCE) {
            close();
            return;
        }
        // Nothing said about this train yet. Not knowing is not the same as nobody driving,
        // and acting on it would close the cab of a driver whose claim packet is still on
        // its way.
        if (!TrainHudData.hasDriverClaim(train.id))
            return;

        UUID claim = TrainHudData.driverClaim(train.id);

        // 1. Mirror repair. See the class comment: this is the value Create's own code reads.
        if (!Objects.equals(entity.getControllingPlayer().orElse(null), claim))
            entity.setControllingPlayer(claim);

        // 2. The driver left: everybody's panel closes, nobody inherits the controls.
        if (observedKnown && train.id.equals(observedTrain)
                && observedClaim != null && claim == null) {
            close();
            return;
        }

        // 3. An unclaimed panel that never became claimed.
        if (claim == null) {
            if (++unclaimedTicks > UNCLAIMED_PANEL_TICKS) {
                close();
                return;
            }
        } else {
            unclaimedTicks = 0;
        }

        observedTrain = train.id;
        observedClaim = claim;
        observedKnown = true;
    }

    /**
     * The read-only line for the panel's status row, or {@code null} when the local player is
     * driving (or when the server has not said yet - a driver must not see "read only" flash
     * up for the tick before their own claim arrives).
     *
     * <p>A blank row instead of a dimmed panel on purpose: the panel is a view of the train's
     * state and a viewer may legitimately want all of it. What they may not do is act, and
     * the status row is where the panel already explains why its controls are inert.
     */
    public static String readOnlyStatus(UUID trainId) {
        if (!TrainHudData.hasDriverClaim(trainId))
            return null;
        UUID claim = TrainHudData.driverClaim(trainId);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && claim != null && claim.equals(mc.player.getUUID()))
            return null;
        return Component.translatable(claim == null
                ? "electroenergetics.train.driver.readOnlyFree"
                : "electroenergetics.train.driver.readOnly").getString();
    }

    /** Close the local cab panel and forget the transition state. */
    private static void close() {
        ControlsHandler.stopControlling();
        reset();
    }

    private static void reset() {
        observedTrain = null;
        observedClaim = null;
        observedKnown = false;
        unclaimedTicks = 0;
    }
}
