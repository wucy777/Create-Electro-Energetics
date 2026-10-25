package com.george_vi.electroenergetics.mixins;

import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads the server's "a teleport has been sent but not yet acknowledged" field.
 *
 * <h2>What a teleport actually does</h2>
 *
 * <p>{@code ServerGamePacketListenerImpl.teleport} applies the destination immediately - it calls
 * {@code absMoveTo} before returning. So the server position is the NEW one from the tick the command
 * runs; an earlier version of this file claimed otherwise and that claim was wrong.
 *
 * <p>What makes the field worth reading is the SECOND application. When the client acknowledges,
 * {@code handleAcceptTeleportPacket} calls {@code absMoveTo} again with the same stored destination. Any
 * position change made in between is overwritten.
 *
 * <p>That is the window this exists to close. A player teleported between two seats on a moving train can
 * be seated by the gate during the interval; the acknowledgement then drags them back to the static
 * destination coordinate, which by then is two to five Blocks behind the train at line speed. The player
 * sees the seat take hold and then a snap backwards. Skipping a player whose teleport is still pending
 * keeps the seat until the second application has landed, so nothing has to be undone.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public interface TeleportPendingAccessor {

    /** The destination of an unacknowledged teleport, or null when none is in flight. */
    @Accessor("awaitingPositionFromClient")
    Vec3 electroEnergetics$awaitingPositionFromClient();
}