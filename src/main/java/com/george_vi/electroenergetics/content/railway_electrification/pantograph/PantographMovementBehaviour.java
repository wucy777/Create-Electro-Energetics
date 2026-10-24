package com.george_vi.electroenergetics.content.railway_electrification.pantograph;

import com.george_vi.electroenergetics.CEEPartialModels;
import com.george_vi.electroenergetics.client.WireRenderer;
import com.george_vi.electroenergetics.config.CEEConfigs;
import com.george_vi.electroenergetics.content.railway_electrification.TrainHudData;
import com.george_vi.electroenergetics.content.railway_electrification.catenary.CatenaryConnection;
import com.george_vi.electroenergetics.foundation.nodes.InWorldNodeConnection;
import com.george_vi.electroenergetics.mixin_interfaces.IPantographList;
import com.george_vi.electroenergetics.simulation.infrastructure.WireData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.contraptions.render.ContraptionMatrices;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.foundation.virtualWorld.VirtualRenderWorld;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.createmod.catnip.data.Pair;
import net.createmod.catnip.math.VecHelper;
import net.createmod.catnip.render.CachedBuffers;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import static net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING;

public class PantographMovementBehaviour implements MovementBehaviour {

    /**
     * Sideways tolerance for finding catenary, in Blocks - client side.
     *
     * <p>Must equal {@code CEEPantographTypes.CONTACT_HALF_WIDTH} and therefore the server's
     * {@code sidewaysReach}. Written as its own constant rather than imported because the type
     * registry is loaded from a datapack registry on the client too, but reading it here would mean
     * a lookup on a hot path (every catenary segment, every tick, for every arm) to fetch a number
     * that is fixed by the model.
     *
     * <p>The coupling is deliberate and asserted by {@code _cache/check_pantograph_reach.py}: if the
     * two ever drift, the visible symptom is an arm dropping into a bend while the train is still
     * drawing current, which is exactly the bug this constant replaced.
     */
    private static final float CONTACT_HALF_WIDTH = 2.5f;

    @Override
    public void tick(MovementContext context) {
        float targetExtensionState = context.blockEntityData.getFloat("ExtensionState");
        float currentExtensionState = context.data.getFloat("CurrentExtensionState");
        float prevExtensionState = currentExtensionState;
        currentExtensionState = currentExtensionState < targetExtensionState ? Math.min(targetExtensionState, currentExtensionState + 0.05f) : Math.max(targetExtensionState, currentExtensionState - 0.05f);

        boolean isDouble = context.state.getValue(PantographBlock.DOUBLE);

        if (!(context.contraption.entity instanceof CarriageContraptionEntity e))
            return;

        // The ARM follows the driver's handle. It does NOT set it.
        //
        // This used to be the other way round, and it was the only way a pantograph could be
        // worked at all: Create's Contraption Controls block toggled `context.disabled`, and this
        // method pushed that into the entry's `active` flag. That is gone. Whether a pantograph is
        // up is now a per-pantograph decision the driver makes from the cab, sent to the server and
        // validated there against the catenary geometry - see SetPantographPacket. So this method
        // only reads the flag and animates the arm to match.
        //
        // Create's controls block is still honoured as a physical override, because it is a real
        // block a player may have fitted and silently ignoring it would be worse than obeying it.
        //
        // A NULL entry means one of two very different things, and they must not be confused.
        //
        // ON THE SERVER it means a crosswise-mounted pantograph, which CarriageContraptionMixin
        // deliberately leaves out of the list (:79-87). Not in the driver's control, never collects
        // current, but still a real block that used to follow the wire whenever the controls block
        // allowed - so it keeps the old behaviour.
        //
        // ON THE CLIENT it is the NORMAL case for EVERY pantograph, and that is the trap. A client
        // carriage is built from AddTrainPacket (Carriage.STREAM_CODEC), which carries only the
        // bogeys and the spacing - it never goes through Carriage.read, which is the only thing that
        // populates the list. So the client's list is empty and this lookup returns null for a
        // pantograph that the server knows perfectly well is lowered.
        //
        // Treating that as "not in the list, so use the controls block" made the arm render RAISED
        // whenever no controls block was fitted, whatever the driver had commanded: the electrical
        // state was correct and the picture contradicted it. The driver lowers a pantograph, the
        // train stops collecting, and the arm sits up against the wire anyway.
        //
        // The client's only source of per-pantograph truth is the state the server syncs to the
        // driver, so that is asked below before falling back.
        boolean raised;
        TrainPantographEntry entry = ((IPantographList) e.getCarriage())
                .getPantographState(context.localPos);
        if (entry != null) {
            raised = entry.active && !context.disabled;
        } else {
            Boolean synced = syncedActive(e, context);
            raised = (synced == null || synced) && !context.disabled;
        }
        if (!raised)
            targetExtensionState = 0f;

        if (context.world.isClientSide && currentExtensionState == targetExtensionState && prevExtensionState != currentExtensionState)
            context.world.playLocalSound(context.position.x, context.position.y, context.position.z, SoundEvents.CHAIN_HIT, SoundSource.NEUTRAL, 0.1f, 1f, false);



        // Look for the connection point, and try to adjust the pantographs extension state to fit that point

        // Arm geometry, client-side only. Gated on `raised` as well as on the controls block:
        //
        //   raised, wire in reach   -> the arm follows the wire (the bisection below)
        //   raised, nothing in reach-> targetExtensionState = 0, so it comes down
        //   NOT raised              -> this whole block is skipped and the arm stays down
        //
        // That last line is the removal of automatic raising. Previously the arm was pushed up
        // whenever wire was in reach, whoever wanted it; now a pantograph only goes up when the
        // driver raises it, and the geometry here only decides how high the arm has to sit to touch
        // what is already there. Automatic LOWERING is kept, because a real pantograph cannot be
        // left pressing against nothing.
        if (context.world.isClientSide && !context.disabled && raised) {
            double pantographX = context.state.getValue(PantographBlock.FACING).getAxisDirection() == Direction.AxisDirection.NEGATIVE ? 0.5 : -0.5;
            if (!isDouble)
                pantographX /= 2;
            float halfPantoReach = 1.625f;
            Direction.Axis axis = context.state.getValue(FACING).getAxis();
            Vec3 pantographPos = new Vec3(axis == Direction.Axis.X ? pantographX : 0, halfPantoReach, axis == Direction.Axis.Z ? pantographX : 0);
            pantographPos = context.rotation.apply(pantographPos);
            pantographPos = pantographPos.add(context.position);

            Vec3 connectionPoint = null;
            if (CEEConfigs.client().debugPantographRange.get())
                context.world.addParticle(ParticleTypes.SCRAPE, pantographPos.x, pantographPos.y, pantographPos.z, 0, 0, 0);
            for (CatenaryConnection connection : WireRenderer.CATENARY) {
                Vec3 start = connection.pos1().getBottomCenter();
                Vec3 end = connection.pos2().getBottomCenter();
                Vec3 cp = checkCatenary(start, end, pantographPos, context, halfPantoReach);
                if (cp != null) {
                    if (connectionPoint == null || connectionPoint.y > cp.y)
                        connectionPoint = cp;
                }
            }

            for (Pair<InWorldNodeConnection, WireData> connection : WireRenderer.WIRE_CONNECTIONS) {
                if (connection.getSecond().wireType().getSag() != 0)
                    continue;
                Vec3 start = connection.getFirst().node1().getPosition(context.world);
                Vec3 end = connection.getFirst().node2().getPosition(context.world);
                if (start == null || end == null)
                    continue;
                float halfThickness = connection.getSecond().wireType().getThickness() * 0.5f;
                Vec3 cp = checkCatenary(start, end, pantographPos, context, halfPantoReach);
                if (cp != null) {
                    if (connectionPoint == null || connectionPoint.y > cp.y)
                        connectionPoint = cp.add(0, -halfThickness + 0.015f, 0);
                }
            }

            if (connectionPoint != null) {
                float lo = 0;
                float hi = 2.7f;
                for (int i = 0; i < 20; i++) {
                    float m1 = lo + (hi - lo) / 3;
                    float m2 = hi - (hi - lo) / 3;
                    if (Math.abs(getConnectorPos(m1, context).y - connectionPoint.y) < Math.abs(getConnectorPos(m2, context).y - connectionPoint.y))
                        hi = m2;
                    else
                        lo = m1;
                }
//                context.world.addParticle(ParticleTypes.SCULK_CHARGE_POP, connectionPoint.x, connectionPoint.y, connectionPoint.z, 0, 0, 0);
                float newExtensionState = (lo + hi) / 2;
                if (targetExtensionState == currentExtensionState && targetExtensionState != 0)
                    currentExtensionState = newExtensionState;

                targetExtensionState = newExtensionState;
            } else {
                targetExtensionState = 0f;
            }

            if (CEEConfigs.client().debugPantographRange.get() && (AnimationTickHolder.getTicks() % 3) == 0) {
                Vec3 cp = getConnectorPos(currentExtensionState, context);
                context.world.addParticle(ParticleTypes.ELECTRIC_SPARK, cp.x, cp.y, cp.z, 0, 0, 0);

                Vec3 pp = new Vec3(axis == Direction.Axis.X ? pantographX : 0, halfPantoReach, axis == Direction.Axis.Z ? pantographX : 0);
                for (float y = -halfPantoReach; y <= halfPantoReach; y += 2 / 16f) {
                    float v = (y + halfPantoReach) * 0.1f + 0.125f;
                    v = v - (v % (1 / 16f));
                    for (float x = -v; x <= v; x += 2 / 16f) {
                        for (float z = -1.5f; z <= 1.5f; z += 2 / 16f) {
                            boolean edgeX = x == -v || x == v;
                            boolean edgeY = y == -halfPantoReach || y == halfPantoReach;
                            boolean edgeZ = z == -1.5f || z == 1.5f;
                            if ((edgeX && edgeY && !edgeZ) || (edgeX && !edgeY && edgeZ) || (!edgeX && edgeY && edgeZ)) {
                                Vec3 pos = context.rotation.apply(pp.add(axis == Direction.Axis.X ? x : z, y - 0.25f, axis == Direction.Axis.X ? z : x)).add(context.position);
                                context.world.addParticle(ParticleTypes.DOLPHIN, pos.x, pos.y, pos.z, 0, 0, 0);
                            }

                        }
                    }
                }
            }
        }

        context.data.putBoolean("Disabled", context.disabled);

        context.data.putFloat("CurrentExtensionState", currentExtensionState);
        context.blockEntityData.putFloat("ExtensionState", targetExtensionState);
        context.data.putFloat("PrevExtensionState", prevExtensionState);
    }

    /**
     * What the server last said this pantograph's handle position is, or null if it has not said.
     *
     * <p>Only needed on the client, where the carriage carries no list of its own. The server sends
     * the driver one entry per pantograph, keyed by the carriage's index in the consist and by the
     * same local position {@code IPantographList} keys on, so the two sides agree on identity even
     * after the train is reassembled.
     *
     * <p>Returning null means "no answer yet", which the caller treats as the old behaviour rather
     * than as "lowered": on the single tick between a carriage appearing and the server's first sync
     * arriving, folding every arm would be a visible twitch on every train that enters view.
     */
    private static Boolean syncedActive(CarriageContraptionEntity e, MovementContext context) {
        Carriage carriage = e.getCarriage();
        if (carriage == null || carriage.train == null)
            return null;
        int index = carriage.train.carriages.indexOf(carriage);
        if (index < 0)
            return null;
        for (SyncTrainPantographsPacket.Entry s : TrainHudData.pantographs(carriage.train.id)) {
            if (s.carriageIndex() == index && s.localPos().equals(context.localPos))
                return s.active();
        }
        return null;
    }

    Vec3 getConnectorPos(float extensionState, MovementContext context) {
        Direction.Axis axis = context.state.getValue(PantographBlock.FACING).getAxis();
        if (context.state.getValue(PantographBlock.DOUBLE)) {
            float lowerArmRadians = (-90 + extensionState * 27) * Mth.DEG_TO_RAD;
            double armHingePosY = Mth.cos(lowerArmRadians) * 1.5;
            double armHingePosX = Mth.sin(lowerArmRadians) * 1.5;

            float b = (float) (-0.4f - Math.abs(armHingePosX));
            float a = Mth.sqrt(-b * b + 2f * 2f);

            double pantographX = context.state.getValue(PantographBlock.FACING).getAxisDirection() == Direction.AxisDirection.NEGATIVE ? 0.5 : -0.5;
            Vec3 connectorPlatePos = new Vec3(axis == Direction.Axis.X ? pantographX : 0, 0.1875 + a + armHingePosY, axis == Direction.Axis.Z ? pantographX : 0);
            connectorPlatePos = context.rotation.apply(connectorPlatePos);
            connectorPlatePos = connectorPlatePos.add(context.position);
            return connectorPlatePos;
        }
        float lowerArmRadians = (-75 + extensionState * 30) * Mth.DEG_TO_RAD;

        double armHingePosY = Mth.cos(lowerArmRadians) * 1.875;
        double armHingePosX = Mth.sin(lowerArmRadians) * 1.875;

        float upperArmRadians = (89 - extensionState * 50) * Mth.DEG_TO_RAD;
        double pantographX = context.state.getValue(PantographBlock.FACING).getAxisDirection() == Direction.AxisDirection.NEGATIVE ? 0.25 : -0.25;
        Vec3 connectorPlatePos = new Vec3((axis == Direction.Axis.X ? pantographX : 0), armHingePosY, armHingePosX + (axis == Direction.Axis.Z ? pantographX : 0)).add(0, Mth.cos(upperArmRadians) * 1.9, Mth.sin(upperArmRadians) * 1.9);
        connectorPlatePos = context.rotation.apply(connectorPlatePos);
        connectorPlatePos = connectorPlatePos.add(context.position);
        return connectorPlatePos;
    }

    @Override
    public boolean mustTickWhileDisabled() {
        return true;
    }

    @Override
    public void onDisabledByControls(MovementContext context) {
        MovementBehaviour.super.onDisabledByControls(context);
    }

    @Override
    public void renderInContraption(MovementContext context, VirtualRenderWorld renderWorld, ContraptionMatrices matrices, MultiBufferSource buffer) {
        PoseStack ms = matrices.getViewProjection();
        BlockState state = context.state;
        float extensionState = Mth.lerp(AnimationTickHolder.getPartialTicks(), context.data.getFloat("PrevExtensionState"), context.data.getFloat("CurrentExtensionState"));

        float yRot = state.getValue(FACING).toYRot();
        if (state.getValue(FACING).getAxis() == Direction.Axis.Z)
            yRot += 180;

        int light = LevelRenderer.getLightColor(renderWorld, context.localPos);
        int color = DyeColor.byName(context.blockEntityData.getString("Color"), DyeColor.WHITE).getTextureDiffuseColor();
        if (state.getValue(PantographBlock.DOUBLE)) {
            float rotationFactor = 27;

            CachedBuffers.partial(CEEPartialModels.PANTOGRAPH_BASE_DOUBLE, state)
                    .transform(matrices.getModel())
                    .center().rotateYDegrees(yRot).uncenter()
                    .color(color)
                    .light(light)
                    .useLevelLight(context.world, matrices.getWorld())
                    .renderInto(ms, buffer.getBuffer(RenderType.cutout()));

            CachedBuffers.partial(CEEPartialModels.PANTOGRAPH_LOWER_ARMS_DOUBLE, state)
                    .transform(matrices.getModel())
                    .center().rotateYDegrees(yRot).uncenter()
                    .translate(0, 0.375, 0.5)
                    .rotateXDegrees(-90 + extensionState * rotationFactor)
                    .color(color)
                    .light(light)
                    .useLevelLight(context.world, matrices.getWorld())
                    .renderInto(ms, buffer.getBuffer(RenderType.solid()));

            float lowerArmRadians = (-90 + extensionState * rotationFactor) * Mth.DEG_TO_RAD;
            double armHingePosY = Mth.cos(lowerArmRadians) * 1.5;
            double armHingePosX = Mth.sin(lowerArmRadians) * 1.5;

            float b = (float) (-0.4f - Math.abs(armHingePosX));
            float a = Mth.sqrt(-b * b + 2f * 2f);


            CachedBuffers.partial(CEEPartialModels.PANTOGRAPH_UPPER_ARMS_DOUBLE, state)
                    .transform(matrices.getModel())
                    .center().rotateYDegrees(yRot).uncenter()
                    .translate(0, armHingePosY + 0.375, armHingePosX + 0.5)
                    .rotateX((float) Mth.atan2(a, b) - Mth.HALF_PI)
                    .color(color)
                    .light(light)
                    .useLevelLight(context.world, matrices.getWorld())
                    .renderInto(ms, buffer.getBuffer(RenderType.cutout()));

            CachedBuffers.partial(CEEPartialModels.PANTOGRAPH_CONNECTING_SURFACE_DOUBLE, state)
                    .transform(matrices.getModel())
                    .center().rotateYDegrees(yRot).uncenter()
                    .translate(0, 0.5625 + a + armHingePosY, 1)
                    .light(light)
                    .useLevelLight(context.world, matrices.getWorld())
                    .renderInto(ms, buffer.getBuffer(RenderType.cutout()));

            float springHingePosY = Mth.cos(lowerArmRadians + 0.5f) * 0.425f;
            float springHingePosX = Mth.sin(lowerArmRadians + 0.5f) * 0.425f;

            CachedBuffers.partial(CEEPartialModels.PANTOGRAPH_SPRINGS_DOUBLE, state)
                    .transform(matrices.getModel())
                    .center().rotateYDegrees(yRot).uncenter()
                    .translate(0, springHingePosY + 0.375, springHingePosX + 0.5f)
                    .scaleZ((-springHingePosX + 0.5f) * (16/13f))
                    .rotateXDegrees(0)
                    .light(light)
                    .useLevelLight(context.world, matrices.getWorld())
                    .renderInto(ms, buffer.getBuffer(RenderType.cutout()));

        } else {
            CachedBuffers.partial(CEEPartialModels.PANTOGRAPH_BASE, state)
                    .transform(matrices.getModel())
                    .center().rotateYDegrees(yRot).uncenter()
                    .color(color)
                    .light(light)
                    .useLevelLight(context.world, matrices.getWorld())
                    .renderInto(ms, buffer.getBuffer(RenderType.cutout()));

            CachedBuffers.partial(CEEPartialModels.PANTOGRAPH_LOWER_ARM, state)
                    .transform(matrices.getModel())
                    .center().rotateYDegrees(yRot).uncenter()
                    .translate(0, 0.375, 0.8125)
                    .rotateXDegrees(-75 + extensionState * 30)
                    .color(color)
                    .light(light)
                    .useLevelLight(context.world, matrices.getWorld())
                    .renderInto(ms, buffer.getBuffer(RenderType.solid()));
            float lowerArmRadians = (-75 + extensionState * 30) * Mth.DEG_TO_RAD;

            double armHingePosY = Mth.cos(lowerArmRadians) * 1.875;
            double armHingePosX = Mth.sin(lowerArmRadians) * 1.875;

            CachedBuffers.partial(CEEPartialModels.PANTOGRAPH_UPPER_ARM, state)
                    .transform(matrices.getModel())
                    .center().rotateYDegrees(yRot).uncenter()
                    .translate(0, 0.375, 0.8125)
                    .translate(0, armHingePosY, armHingePosX)
                    .rotateXDegrees(-1 - extensionState * 50)
                    .color(color)
                    .light(light)
                    .useLevelLight(context.world, matrices.getWorld())
                    .renderInto(ms, buffer.getBuffer(RenderType.solid()));

            CachedBuffers.partial(CEEPartialModels.PANTOGRAPH_UPPER_ARM_ARM, state)
                    .transform(matrices.getModel())
                    .center().rotateYDegrees(yRot).uncenter()
                    .translate(12/16f, 0.375, 0.8125)
                    .translate(0, armHingePosY, armHingePosX)
                    .rotateXDegrees(-1 - extensionState * 50)
                    .rotateYDegrees(12.5f)
                    .color(color)
                    .light(light)
                    .useLevelLight(context.world, matrices.getWorld())
                    .renderInto(ms, buffer.getBuffer(RenderType.solid()));

            CachedBuffers.partial(CEEPartialModels.PANTOGRAPH_UPPER_ARM_ARM, state)
                    .transform(matrices.getModel())
                    .center().rotateYDegrees(yRot).uncenter()
                    .translate(4/16f, 0.375, 0.8125)
                    .translate(0, armHingePosY, armHingePosX)
                    .rotateXDegrees(-1 - extensionState * 50)
                    .rotateYDegrees(-12.5f)
                    .color(color)
                    .light(light)
                    .useLevelLight(context.world, matrices.getWorld())
                    .renderInto(ms, buffer.getBuffer(RenderType.solid()));

            float upperArmRadians = (89 - extensionState * 50) * Mth.DEG_TO_RAD;
            CachedBuffers.partial(CEEPartialModels.PANTOGRAPH_CONNECTING_SURFACE, state)
                    .transform(matrices.getModel())
                    .center().rotateYDegrees(yRot).uncenter()
                    .translate(0, 0.375, 0.8125)
                    .translate(0, armHingePosY, armHingePosX)
                    .translate(0, Mth.cos(upperArmRadians) * 1.9, Mth.sin(upperArmRadians) * 1.9)
                    .light(light)
                    .useLevelLight(context.world, matrices.getWorld())
                    .renderInto(ms, buffer.getBuffer(RenderType.cutout()));

            CachedBuffers.partial(CEEPartialModels.PANTOGRAPH_SPRINGS, state)
                    .transform(matrices.getModel())
                    .center().rotateYDegrees(yRot).uncenter()
                    .translate(0, 0.375, 0.25)
                    .rotateXDegrees(-22 + extensionState * -20)
                    .scale(1, 1, 1 + extensionState / 2)
                    .light(light)
                    .useLevelLight(context.world, matrices.getWorld())
                    .renderInto(ms, buffer.getBuffer(RenderType.cutout()));

            CachedBuffers.partial(CEEPartialModels.PANTOGRAPH_CONNECTING_ROD, state)
                    .transform(matrices.getModel())
                    .center().rotateYDegrees(yRot).uncenter()
                    .translate(0, 0.375, 0.1875)
                    .rotateXDegrees(-77 + extensionState * 43)
                    .color(color)
                    .light(light)
                    .useLevelLight(context.world, matrices.getWorld())
                    .renderInto(ms, buffer.getBuffer(RenderType.cutout()));
        }
    }

    Vec3 checkCatenary(Vec3 start, Vec3 end, Vec3 pantographPos, MovementContext context, float halfPantoReach) {
        double t = 0;

        Vec3 ab = end.subtract(start);
        Vec3 ap = pantographPos.subtract(start);
        double denom = ab.lengthSqr();
        if (denom != 0)
            t = ap.dot(ab) / denom;
        if (t < -0.01 || t > 1.01)
            return null;

        Vec3 closest = VecHelper.lerp((float) t, start, end);
//        Vec3 closest = Minecraft.getInstance().player.position();
//        context.world.addParticle(ParticleTypes.SCULK_CHARGE_POP, closest.x, closest.y, closest.z, 0, 0, 0);
//        context.world.addParticle(ParticleTypes.SCULK_CHARGE_POP, pantographPos.x, pantographPos.y, pantographPos.z, 0, 0, 0);

        Vec3 distance = pantographPos.subtract(closest);
        distance = VecHelper.rotate(distance, -context.contraption.entity.getViewYRot(0), Direction.Axis.Y);
        float xTol = (float) ((distance.y + halfPantoReach) * 0.2f + 0.125f);
        // The sideways tolerance must be the SAME as the server's `sidewaysReach`, or the arm is drawn
        // as having lost the wire while the train is still collecting from it.
        //
        // It used to be a hard-coded 1.5, which was narrower than the server's 2.0 and narrower than
        // the strip itself - so the two sides disagreed twice over, and the visible symptom was an arm
        // dropping into a bend while power was still flowing. Now both read the same type field, so a
        // future change to the type cannot leave the client behind again.
        float sideways = CONTACT_HALF_WIDTH;
        if (Math.abs(distance.z) < sideways && Math.abs(distance.x) < xTol
                && Math.abs(distance.y) < halfPantoReach)
            return closest;

        return null;
    }

    @Override
    public boolean disableBlockEntityRendering() {
        return true;
    }
}
