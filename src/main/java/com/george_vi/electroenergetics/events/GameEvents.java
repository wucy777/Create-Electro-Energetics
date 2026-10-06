package com.george_vi.electroenergetics.events;

import com.george_vi.electroenergetics.*;
import com.george_vi.electroenergetics.client.ClientNodeData;
import com.george_vi.electroenergetics.client.ElectricPropertiesOverlay;
import com.george_vi.electroenergetics.client.TrainControlInput;
import com.george_vi.electroenergetics.client.WireEffects;
import com.george_vi.electroenergetics.client.WireRenderer;
import com.george_vi.electroenergetics.commands.CEECommands;
import com.george_vi.electroenergetics.config.CEEConfigs;
import com.george_vi.electroenergetics.content.accumulator.AccumulatorBlock;
import com.george_vi.electroenergetics.content.converter.ConverterBlockEntity;
import com.george_vi.electroenergetics.content.electrical_panel.ElectricalPanelBlock;
import com.george_vi.electroenergetics.content.electrical_panel.ElectricalPanelClientTicker;
import com.george_vi.electroenergetics.content.fuse.BlownFuseTracker;
import com.george_vi.electroenergetics.content.fuse.FuseBlockItem;
import com.george_vi.electroenergetics.content.linemans_stick.LinemansStickClientHandler;
import com.george_vi.electroenergetics.content.linemans_stick.LinemansStickItem;
import com.george_vi.electroenergetics.content.railway_electrification.TrainHudData;
import com.george_vi.electroenergetics.content.railway_electrification.gauges.ClientTrainGaugeData;
import com.george_vi.electroenergetics.content.railway_electrification.sound_effects.ElectricTrainSounds;
import com.george_vi.electroenergetics.content.wire.WireSync;
import com.george_vi.electroenergetics.content.wire.interaction.InteractDetachedNodePacket;
import com.george_vi.electroenergetics.content.wire.interaction.OutlinesOnWireRenderer;
import com.george_vi.electroenergetics.content.wire.interaction.WireInteractionBehaviour;
import com.george_vi.electroenergetics.content.wire.interaction.WireInteractionHandler;
import com.george_vi.electroenergetics.content.wire_spool.ChangeLengthWireInteractionBehaviour;
import com.george_vi.electroenergetics.content.wire_spool.WireApplyingBehaviour;
import com.george_vi.electroenergetics.content.wire_spool.WireSparkEffectTicker;
import com.george_vi.electroenergetics.devices.device.DevicesSavedData;
import com.george_vi.electroenergetics.foundation.CEEHoldInteractionHandler;
import com.george_vi.electroenergetics.foundation.CEELang;
import com.george_vi.electroenergetics.foundation.nodes.InWorldNode;
import com.george_vi.electroenergetics.simulation.infrastructure.InWorldNodeData;
import com.george_vi.electroenergetics.simulation.infrastructure.InfrastructureSavedData;
import com.simibubi.create.AllSoundEvents;
import com.george_vi.electroenergetics.content.railway_electrification.ElectricTrainData;
import com.george_vi.electroenergetics.content.railway_electrification.TrainDriverState;
import com.george_vi.electroenergetics.mixin_interfaces.ICEETrainExtension;
import net.minecraft.server.MinecraftServer;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.Train;
import java.util.UUID;
import dev.engine_room.flywheel.api.event.ReloadLevelRendererEvent;
import net.createmod.catnip.platform.CatnipServices;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderHighlightEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Objects;
import java.util.Optional;

@EventBusSubscriber(modid = CreateElectroEnergetics.ID)
public class GameEvents {

    @OnlyIn(Dist.CLIENT)
    @SubscribeEvent
    public static void tickClient(ClientTickEvent.Pre event) {
        if (Minecraft.getInstance().level == null || Minecraft.getInstance().player == null)
            return;
        WireApplyingBehaviour.tick();
        WireInteractionHandler.tick();
        OutlinesOnWireRenderer.OutlinerExt.tick();
        WireEffects.tick();
        CEEHoldInteractionHandler.tick();
        ElectricTrainSounds.tick();
        ClientTrainGaugeData.tick();
        // Drop HUD samples for trains that no longer exist on the client, so the
        // cache cannot grow without bound over a long session.
        TrainHudData.retain(id -> com.simibubi.create.CreateClient.RAILWAYS.trains.containsKey(id));
        LinemansStickClientHandler.tick();
        FuseBlockItem.tickClient();
        ElectricalPanelClientTicker.tick();

        ElectricPropertiesOverlay.INSTANCE.ticks++;

        // Read the cab keys once a tick. Does nothing at all unless the player is
        // driving an electric train, so it costs a few field reads when it is not.
        TrainControlInput.tick();

        // Safety?
        WireInteractionHandler.preventUseOnBlockPacket = false;

        for (ClientNodeData nodeData : WireRenderer.NODE_DATA.values()) {
            nodeData.tick();
        }
    }

    @OnlyIn(Dist.CLIENT)
    @SubscribeEvent
    public static void mouseScrolled(InputEvent.MouseScrollingEvent event) {
        double delta = event.getScrollDeltaY();
        event.setCanceled(FuseBlockItem.mouseScrolled(delta) || ChangeLengthWireInteractionBehaviour.mouseScrolled(delta));
    }

    /**
     * Clicks on the driver's lever panel are no longer handled.
     *
     * <p>They were, and it was wrong: while driving, the mouse is captured by the
     * game for looking around, so the cursor is not on screen and cannot be pointed
     * at a panel at all. The lever is driven by the raw keys in
     * {@link com.george_vi.electroenergetics.client.TrainControlInput} instead.
     */

    @OnlyIn(Dist.CLIENT)
    @SubscribeEvent
    public static void renderHighlightBlock(RenderHighlightEvent.Block event) {
        BlockPos pos = event.getTarget().getBlockPos();
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null)
            return;

        if (WireInteractionHandler.targetedPoint != null) {
            event.setCanceled(true);
            return;
        }

        BlockState state = level.getBlockState(pos);
        if (CEEBlocks.ACCUMULATOR.has(state))
            AccumulatorBlock.renderHighlightBlock(event, state);
        else if (state.getBlock() instanceof ElectricalPanelBlock)
            ElectricalPanelClientTicker.renderHighlightBlock(event, state);
    }

    @OnlyIn(Dist.CLIENT)
    @SubscribeEvent
    public static void renderLevel(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_ENTITIES)
            WireRenderer.render(event.getLevelRenderer(), event.getPoseStack(), event.getCamera());
    }

    @SubscribeEvent
    public static void serverTickEvent(ServerTickEvent.Post event) {
        if (ModEvents.changedConfigs.getAndSet(false))
            for (ServerLevel level : event.getServer().getAllLevels()) {
                InfrastructureSavedData sd = InfrastructureSavedData.load(level);
                sd.wireSimulationState.onReloadConfigs();
            }

        tickDriverState(event.getServer());
    }

    /**
     * Advance every electric train's driver state, once per tick.
     *
     * <p>Deliberately here rather than in {@code CatenaryModule.finishSimulation},
     * which is the natural home for per-train work but runs once per LEVEL per
     * tick. Everything in this method is a wall-clock countdown - the 30-second
     * vigilance prompt, the emergency brake's release, the penalty - so running it
     * once per dimension would make all of them fire at a multiple of their
     * intended rate. A server tick event fires exactly once, which is what a
     * countdown in ticks needs.
     *
     * <p>Cost is one pass over the train map with no allocation and no work for a
     * train nobody is driving, so it is a handful of integer decrements per tick.
     */
    private static void tickDriverState(MinecraftServer server) {
        if (Create.RAILWAYS.trains.isEmpty())
            return;

        for (Train train : Create.RAILWAYS.trains.values()) {
            ElectricTrainData data = ((ICEETrainExtension) train).getElectricTrainData();
            TrainDriverState driver = data.driver;

            // Ask Create who is holding this train's controls, once a tick.
            //
            // This replaces a heartbeat set from control(), and the reason matters:
            // control() is only invoked while the driver is HOLDING A KEY. Create's
            // keepalive keeps the context alive for a few ticks after the last key
            // press and then drops it, so a lever-driven train - where the driver
            // mostly is not pressing anything - would time out and fall back to
            // Create's controls after a few ticks, which is exactly the bug this
            // fixes. getControllingPlayer() is set when the player grabs the controls
            // and cleared only when they let go, so it is the honest signal for "is
            // somebody driving this train".
            driver.setDriverPresent(controllingPlayer(train));

            driver.tickDriverPresence();

            // A train under a running SCHEDULE is Create's to drive, not this mode's.
            //
            // Without this, a scheduled electric train would be captured the moment a
            // player happened to touch its controls and then walked away: wasDriven is
            // latched for life, so the train would stay "unmanned" and the gear law
            // would own its speed for the rest of the save - obeying whatever lever
            // position was left over instead of the schedule, and stopping itself every
            // 36 seconds. That is exactly the automation this mode must leave alone.
            //
            // Checked every tick rather than latched, because a schedule can be started
            // or paused at any time - a conductor boarding, a schedule item edit - and
            // the moment one is running the train must go back to Create. That is also
            // what makes handing a train over to automation work at all.
            //
            // paused == false means the schedule is running. A train with no schedule has
            // a paused runtime, so an ordinary hand-driven train is unaffected.
            if (train.runtime != null && !train.runtime.paused)
                driver.releaseUnmanned();

            // Vigilance: one escalating clock, from zero since the last acknowledgement.
            //
            //   20 s  amber warning (the prompt is coming)
            //   30 s  red warning (it is now overdue)
            //   36 s  the device TRIPS: the train is braked to a stand
            //
            // Acknowledging at any point resets the clock to zero, which is what makes
            // "press it early" also reset the 30 s rather than merely silencing the
            // warning - important, because the previous two-counter design had a
            // separate prompt timer that could disagree with the warning clock.
            //
            // Run while underGearControl(), which includes an UNMANNED train. That is
            // the whole point of leaving the clock running: a driver who walks away is
            // exactly as unresponsive as one who fell asleep, and the safety device
            // cannot tell the difference - so it must treat both the same and stop the
            // train. Resetting on the driver's absence would defeat the device entirely.
            //
            // And only while the train is MOVING. A stationary train cannot be made
            // safer by this device, so a stopped one stops counting - which is what a
            // real vigilance device does, and it is what fixes the reported bug: a
            // parked train ran the clock to 36 s, tripped, and because the trip clears
            // on the next tick once the train is at a stand, the only visible effect was
            // that the lever the driver had selected was silently overwritten with the
            // brake - every 36 seconds, forever. Selecting REVERSE to shunt and then
            // waiting at a signal lost the gear selection for no reason the driver could
            // see. Counting only while moving also means the clock measures time spent
            // RUNNING unattended, which is the hazard the device exists for.
            //
            // The threshold matches the "at a stand" tests used elsewhere (the trip
            // release and the end-of-track park), so all three agree on what stopped
            // means. A train creeping at the 1e-4 Blocks/tick the tests treat as rest is
            // not a train that needs a vigilance alarm.
            boolean moving = Math.abs(train.speed) > 1e-4d;
            boolean owned = driver.underGearControl();

            // Releasing a trip is NOT gated on moving: the trip's job is to stop the
            // train, so once the train is at a stand the trip has succeeded and must be
            // released. Gating this on `moving` would latch it on a stopped train
            // forever - the trip forces the brake, so the train could never start moving
            // again, and nothing could ever clear the condition that was holding it.
            if (driver.vigilanceTripped && owned) {
                // The backstop covers a train that somehow cannot reach a stand, so the
                // brake is not held on forever by a gradient it cannot stop on.
                if (!moving || ++driver.tripTicks > TrainDriverState.TRIP_BRAKE_TICKS) {
                    driver.clearTrip();
                    driver.tripTicks = 0;
                }
            } else if (owned && moving) {
                if (driver.confirmWaiting < TrainDriverState.TRIP_TICKS) {
                    driver.confirmWaiting++;
                } else {
                    driver.trip();
                    driver.tripTicks = 0;
                }
            } else if (owned) {
                // Owned but STOPPED: reset the clock to zero, every tick, so a train at a
                // stand always leaves with a full window.
                //
                // This is what the user asked for, and it replaces an earlier decision of
                // mine to HOLD the count instead. I had held it to stop a long station
                // dwell from acting as a free reset; the user's call is that stopping
                // genuinely does reset it, which is also what a real vigilance device
                // does - the clock measures time spent RUNNING unattended, and a train
                // standing still is not that. Resetting rather than holding is also the
                // simpler rule to reason about: the driver sees the count start from
                // scratch whenever they pull away, with no carry-over they cannot see.
                //
                // This cannot be used to defeat the device: acknowledging is not required
                // to get the reset, but the train has to actually be at a stand for it,
                // and the trip that a lapsed driver earns is a brake to a stop - so the
                // reset arrives at exactly the moment the device has already done its job.
                driver.confirmWaiting = 0;
                driver.tripTicks = 0;
            } else if (!owned) {
                // Genuinely nobody's train, and nobody ever drove it: a schedule-driven
                // one, or one parked up. Drop the clock and any trip, because a train
                // this mode does not own must not be pinned by a warning raised for
                // somebody else - and must not trip itself every 36 seconds forever.
                driver.confirmWaiting = 0;
                driver.clearTrip();
                driver.tripTicks = 0;
            }
            // A stopped train this mode owns keeps its clock where it is rather than
            // resetting it: standing at a platform for a minute should not hand the
            // driver a fresh 36 seconds the moment they pull away again. Holding the
            // value rather than clearing it is what keeps a long station dwell from
            // being a free reset, and it is also what makes the device honest - it
            // measures running time unattended, and the count carries over.

            // Emergency brake application, and the speed cap it leaves behind until
            // the next station call.
            if (driver.emergencyTicks > 0)
                driver.emergencyTicks--;
            if (driver.emergencyPenalty && train.getCurrentStation() != null)
                driver.clearPenalty();

            // Park the train when it runs out of track.
            //
            // Create stops the train at a buffer stop by zeroing its speed
            // (Train.travel sets speed = 0 when a carriage is blocked) but it leaves
            // whatever the driver had selected alone - so the lever stays in
            // ACCELERATE with the train stationary, which is both wrong as a report of
            // what happened and dangerous the moment the track clears. Reported by the
            // user: the train stopped at the end of the line but did not park itself in
            // the brake.
            //
            // Latched, and rearmed only once the train actually moves again. A rule
            // that re-asserts the brake every tick while blocked would fight the driver
            // forever: a previous version of this file did exactly that and made it
            // impossible to reverse away from a buffer stop, which is the one place a
            // driver needs reverse.
            // A tolerance rather than an exact zero: Create sets speed = 0 outright when
            // a carriage is blocked, but other tick handlers run between that and here,
            // and a float equality test on a value several systems write is a bug
            // waiting for a rounding difference.
            boolean outOfTrack = Math.abs(train.speed) < 1e-6d && isBlocked(train);
            if (outOfTrack && !driver.blockedParked) {
                driver.failSafe();
                driver.blockedParked = true;
            } else if (!outOfTrack && Math.abs(train.speed) > 1e-4d) {
                driver.blockedParked = false;   // moved again: allow a future park
            }

            // Automatic station arrival, the driver holding space with a destination.
            //
            // Create's Navigation runs the approach and stops the train at the platform;
            // the gear law follows Navigation's commanded speed while this is set (see
            // TrainMixin). Two transitions have to be handled here, because this is the
            // once-per-tick hook that sees both the controls and the train's state:
            //
            //   ENTER  the driver holds space and Create has started a navigation
            //   LEAVE  the space key is released, or the train has arrived
            //
            // On release before arrival the lever is restored, so the driver gets the
            // train back exactly as they left it and can carry on - cancelling an
            // approach should not also silently change the gear.
            //
            // On arrival the lever is dropped to the BRAKE and the train is parked,
            // which is what stops the reported bug: with the lever left in ACCELERATE,
            // releasing space at the platform pulled the train straight back out again,
            // because the gear law saw a traction position and obeyed it. Parking in the
            // brake means departure needs an explicit gear selection, which is also what
            // a real train does.
            driver.tickSpace();
            boolean arriving = driver.isSpaceHeld() && arrivingAtStation(train);

            if (arriving && !driver.autoArrive) {
                driver.autoArrive = true;
                // Remember what the driver had selected, so cancelling can restore it.
                driver.autoArriveGear = driver.gear;
            } else if (driver.autoArrive && !arriving) {
                // Arrived, or cancelled. Distinguishing them decides whether the lever is
                // restored or dropped: Create clears the destination on arrival, so a
                // train now sitting at a station it was navigating to has arrived.
                boolean arrived = train.getCurrentStation() != null;
                driver.autoArrive = false;
                if (arrived)
                    driver.failSafe();
                else
                    driver.gear = driver.autoArriveGear;
            }

            // The lever is deliberately NOT forced to the brake while a station is
            // held. An earlier version did that to stop a train being dispatched
            // still set to shunt, but it deadlocked instead: it fought the depart
            // path in the gear law, which releases the station only for a gear that
            // commands traction, so a train whose lever was pinned back to the brake
            // every tick could never leave the platform at all - and shunting at a
            // station, which is the one place a driver actually needs reverse, became
            // impossible. Leaving the lever alone is also harmless: reverse is capped
            // at a walking pace and takes an explicit selection.
        }
    }

    /**
     * Whether Create is currently navigating this train to a station.
     *
     * <p>The test is simply "a destination is set". That is what the space key does
     * when held away from a station - it starts a navigation to the nearest station the
     * train can approach - and it is also the state the space key is obeyed in above,
     * which is what keeps the two in step. A train already sitting at a station has no
     * destination, so holding space there does not re-enter automatic arrival: that
     * case is the "already arrived" prompt, and re-arming would restart a journey the
     * driver has just completed.
     */
    private static boolean arrivingAtStation(Train train) {
        return train.navigation != null && train.navigation.destination != null;
    }

    /**
     * Whether any carriage of this train is against a buffer stop or an incompatible
     * track, which is Create's own "the train cannot go further" condition.
     *
     * <p>Read from the carriages, which is where Create records it: {@code travel}
     * sets {@code carriage.blocked} from its travelling points every tick.
     */
    private static boolean isBlocked(Train train) {
        for (Carriage carriage : train.carriages) {
            if (carriage.blocked || carriage.isOnIncompatibleTrack())
                return true;
        }
        return false;
    }

    /**
     * Whether anybody is holding this train's controls, and who.
     *
     * <p>Create records this on the carriage's contraption entity when a player
     * interacts with the controls block, and clears it when they stop. Checking it
     * directly is what makes the lever survive long periods with no keys held.
     *
     * <p>Any carriage counts, not just the leading one: a driver can be at the
     * controls of whichever carriage they walked to.
     */
    private static UUID controllingPlayer(Train train) {
        for (Carriage carriage : train.carriages) {
            UUID[] found = new UUID[1];
            carriage.forEachPresentEntity(e -> e.getControllingPlayer().ifPresent(id -> {
                if (found[0] == null)
                    found[0] = id;
            }));
            if (found[0] != null)
                return found[0];
        }
        return null;
    }

    @SubscribeEvent
    public static void serverLevelTickEvent(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level) {
            InfrastructureSavedData sd = InfrastructureSavedData.load(level);
            sd.tick();

            // It's not done through EntityEvent.EnteringSection, because other mods may position the entity multiple
            // times a tick.
            // e.g. Sitting in a seat on a sable contraption causes it to teleport twice a tick between the plot
            // location and the normal location, causing WireSync to send a ton of wire update packets and flickering.
            for (ServerPlayer player : level.players()) {
                Vec3 pos = player.position();
                sd.wireSync.handlePlayerEnterNewSection(player,
                        ChunkPos.asLong(Mth.floor(pos.x) >> 4, Mth.floor(pos.z) >> 4));
            }

            BlownFuseTracker.tick();
        }
    }

    @SubscribeEvent
    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        ConverterBlockEntity.registerCapabilities(event);
    }

    @SubscribeEvent
    public static void enterDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player))
            return;
        InfrastructureSavedData sd = InfrastructureSavedData.load((ServerLevel) player.level());
        WireSync wireSync = sd.wireSync;
        wireSync.unloadForPlayer(player);
        wireSync.handlePlayerEnterNewSection(player, ChunkPos.asLong(player.blockPosition()));
    }

    @SubscribeEvent
    public static void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player))
            return;
        InfrastructureSavedData sd = InfrastructureSavedData.load((ServerLevel) player.level());
        WireSync wireSync = sd.wireSync;
        wireSync.unloadForPlayer(player);
        wireSync.handlePlayerEnterNewSection(player, ChunkPos.asLong(player.blockPosition()));
    }

    @SubscribeEvent
    public static void tick(EntityTickEvent.Pre event) {
        if (!(event.getEntity() instanceof Player player))
            return;
        LinemansStickItem.tickPlayerRange(player);
    }


    @SubscribeEvent
    public static void playerInteractItem(PlayerInteractEvent.RightClickItem event) {
        if (!event.getLevel().isClientSide() || event.getHand() != InteractionHand.MAIN_HAND)
            return;

        ItemStack stack = event.getItemStack();

        if (CEEHoldInteractionHandler.isInteracting()) {
            event.setCancellationResult(InteractionResult.FAIL);
            event.setCanceled(true);
            WireInteractionHandler.preventUseOnBlockPacket = true;
            return;
        }

        // Detached Node Interactions:
        if (WireApplyingBehaviour.targetingDetachedNode != null) {
            CatnipServices.NETWORK.sendToServer(new InteractDetachedNodePacket(WireApplyingBehaviour.targetingDetachedNode));

            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
            WireInteractionHandler.preventUseOnBlockPacket = true;
        }

        // Wire interactions:
        WireInteractionBehaviour behaviour = CEERegistries.WIRE_INTERACTION_BEHAVIOUR.stream()
                .filter(h -> h.isActiveFor(stack, event.getEntity()))
                .findFirst().orElse(null);
        if (behaviour == null)
            return;

        if (behaviour.tryUseOnWire(event.getLevel(), event.getEntity(), event.getHand())) {
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void playerInteractOnBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getHand() != InteractionHand.MAIN_HAND)
            return;

        ItemStack stack = event.getItemStack();
        if (event.getLevel() instanceof ServerLevel level) {
            BlockPos pos = event.getPos();
            // Node label renaming functionality:
            if (!stack.is(CEETags.NODE_RENAME_ITEM))
                return;

            InWorldNode hoveredNode = InWorldNode.closestNode(level, event.getHitVec().getLocation(), 1.5f, event.getPos());
            BlockState hoveredBlockState = level.getBlockState(pos);

            if (stack.is(CEETags.PANEL_ATTACHMENT_RENAME_ITEM) && hoveredBlockState.getBlock() instanceof ElectricalPanelBlock)
                return;

            if (hoveredNode == null)
                hoveredNode = InWorldNode.closestNode(level, pos, hoveredBlockState, 1.5f, event.getHitVec().getLocation());

            if (hoveredNode == null)
                return;

            InfrastructureSavedData sd = InfrastructureSavedData.load(level);

            InWorldNodeData nodeData = sd.getNodeData(hoveredNode);
            if (nodeData == null)
                return;

            String prevLabel = nodeData.label;
            nodeData.label = Optional.ofNullable(stack.get(DataComponents.CUSTOM_NAME))
                    .map(Component::getString)
                    .orElse(null);

            sd.wireSync.handleNodeLabelRename(nodeData);

            if (!Objects.equals(prevLabel, nodeData.label))
                if (nodeData.label == null)
                    AllSoundEvents.CLIPBOARD_ERASE.playOnServer(level, hoveredNode.sourcePos());
                else
                    AllSoundEvents.CLIPBOARD_CHECKMARK.playOnServer(level, hoveredNode.sourcePos());

            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
            WireInteractionHandler.preventUseOnBlockPacket = true;
            return;
        }

        if (CEEHoldInteractionHandler.isInteracting()) {
            event.setCancellationResult(InteractionResult.FAIL);
            event.setCanceled(true);
            WireInteractionHandler.preventUseOnBlockPacket = true;
            return;
        }

        // Detached Node Interactions:
        if (WireApplyingBehaviour.targetingDetachedNode != null) {
            CatnipServices.NETWORK.sendToServer(new InteractDetachedNodePacket(WireApplyingBehaviour.targetingDetachedNode));

            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
            WireInteractionHandler.preventUseOnBlockPacket = true;
        }

        // Wire interactions:
        WireInteractionBehaviour behaviour = CEERegistries.WIRE_INTERACTION_BEHAVIOUR.stream()
                .filter(h -> h.isActiveFor(stack, event.getEntity()))
                .findFirst().orElse(null);
        if (behaviour == null)
            return;

        if (behaviour.tryUseOnWire(event.getLevel(), event.getEntity(), event.getHand())) {
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
            WireInteractionHandler.preventUseOnBlockPacket = true;
        }
    }

    @SubscribeEvent
    public static void registerCommands(RegisterCommandsEvent event) {
        CEECommands.register(event.getDispatcher());
    }

    @SubscribeEvent
    public static void addToElectricGraph(AddToElectricGraphEvent event) {
        InfrastructureSavedData sd = event.sd;
        if (sd.wireSimulationState.rebuild)
            sd.wireSimulationState.rebuild();

        sd.catenaryModule.buildCircuit(event.builder);
        sd.wireElectrocutionModule.buildCircuit(event.builder);
        WireSparkEffectTicker.preTick(event.level);
    }

    @SubscribeEvent
    public static void finishElectricSimulation(FinishElectricSimulationEvent event) {
        InfrastructureSavedData sd = event.sd;
        sd.wireElectrocutionModule.finishSimulation(event.results);
        sd.catenaryModule.finishSimulation(event.results);
        WireSparkEffectTicker.postTick(event.level, event.results);
    }

    @SubscribeEvent
    public static void spawnMob(MobSpawnEvent.SpawnPlacementCheck event) {
        if (!CEEConfigs.server().bulbsPreventMobSpawns.get() ||
                event.getSpawnType() != MobSpawnType.NATURAL ||
                event.getResult() == MobSpawnEvent.SpawnPlacementCheck.Result.FAIL)
            return;
        DevicesSavedData sd = DevicesSavedData.load(event.getLevel().getLevel());
        if (sd.getDevices(CEESimulatedDeviceFeatureTypes.SPAWN_PREVENTING.get()).stream()
                .anyMatch(d -> d.pos.distSqr(event.getPos()) <= 400))
            event.setResult(MobSpawnEvent.SpawnPlacementCheck.Result.FAIL);
    }

    @OnlyIn(Dist.CLIENT)
    @SubscribeEvent
    public static void reloadLevelRenderer(ReloadLevelRendererEvent event) {
        WireRenderer.recreateVisuals();
    }

    @OnlyIn(Dist.CLIENT)
    @SubscribeEvent
    public static void itemTooltip(ItemTooltipEvent event) {
        if (event.getItemStack().is(CEETags.ELECTRICAL_PANEL_ATTACHMENT) &&
                CEEConfigs.client().displayPanelTooltip.get()) {
            event.getToolTip().add(CEELang.translateDirect("hint.placeable_in_electrical_panel"));
        }
    }

}
