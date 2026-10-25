package com.george_vi.electroenergetics.mixins;

import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads the server's "a teleport is in flight" field.
 *
 * <p>A {@code /tp} on a server player does not move them. It sends a position packet and stores the
 * destination in {@code awaitingPositionFromClient}; the position only becomes real when the client
 * acknowledges and {@code handleAcceptTeleportPacket} calls {@code absMoveTo}. Until then
 * {@code player.position()} still reports where they were BEFORE the command.
 *
 * <p>That matters for seating: a player teleported from one seat to another would otherwise be seated by
 * the nearest-free-seat search using their old position - putting them back where they started, which is
 * the opposite of what the command asked for. The seat search must run against the position the player
 * actually ends up at, so a player with a teleport in flight is skipped until it lands.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public interface TeleportPendingAccessor {

    /** The destination of an unacknowledged teleport, or null when none is in flight. */
    @Accessor("awaitingPositionFromClient")
    Vec3 electroEnergetics$awaitingPositionFromClient();
}