package com.george_vi.electroenergetics.mixins;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Names the caller of every server-side player teleport, so the dismount teleport can be attributed
 * rather than guessed at.
 *
 * <h2>Why a probe and not another fix attempt</h2>
 *
 * <p>Four builds tried to change the dismount position by intercepting the places it is computed or
 * stored. Instrumentation showed the last one firing after the player had already arrived, which proves
 * the position is delivered by a packet, not read from the tag. The write tracer named the receiving
 * method ({@code handleMovePlayer}) but not the sender - and "the sender" is the only thing that decides
 * WHERE the packet says to go.
 *
 * <p>Every server-initiated player teleport goes through this one method; a dimension change, a command,
 * and a dismount all land here. So one stack trace from here, during a dismount, identifies the caller and
 * therefore the exact coordinate being sent. That is one round trip instead of another guess.
 *
 * <pre>
 *   TELEPORT target=(x,y,z) pax=.. vehicle=.. caller=A &lt;- B &lt;- C
 * </pre>
 *
 * <h2>Cost</h2>
 *
 * <p>A player teleports on dismounts, dimension changes and commands - a handful of times per session,
 * never per tick. The stack trace is only walked when the string is actually built.
 */
@Mixin(value = ServerGamePacketListenerImpl.class, remap = false)
public class DismountTeleportMixin {

    @Unique
    private static final Logger electroEnergetics$LOG = LoggerFactory.getLogger("CEE-TELEPORT");

    @Inject(method = "teleport(DDDFFLjava/util/Set;)V", at = @At("HEAD"), remap = false)
    private void electroEnergetics$logTeleport(double x, double y, double z, float yRot, float xRot,
                                               java.util.Set<?> relatives, CallbackInfo ci) {
        try {
            ServerPlayer self = ((ServerGamePacketListenerImpl) (Object) this).player;
            if (self == null || self.level().isClientSide())
                return;
            // Only riders, or someone who was one an instant ago: a teleport on foot is not our concern.
            boolean nearTrain = self.getVehicle() != null;
            if (!nearTrain) {
                for (var e : self.level().getEntities(self,
                        self.getBoundingBox().inflate(48))) {
                    if (e instanceof com.simibubi.create.content.trains.entity
                            .CarriageContraptionEntity) {
                        nearTrain = true;
                        break;
                    }
                }
            }
            if (!nearTrain)
                return;

            electroEnergetics$LOG.info("TELEPORT target={} pax={} vehicle={} caller={}",
                    String.format("(%.2f,%.2f,%.2f)", x, y, z),
                    self.isPassenger(),
                    self.getVehicle() == null ? "-" : self.getVehicle().getId(),
                    electroEnergetics$frames());
        } catch (Throwable ignored) {
            // A diagnostic must never be the thing that breaks a teleport.
        }
    }

    /**
     * The frames that decide where the teleport goes, skipping this class and the mixin plumbing.
     */
    @Unique
    private static String electroEnergetics$frames() {
        StackTraceElement[] stack = new Throwable().getStackTrace();
        StringBuilder sb = new StringBuilder();
        int taken = 0;
        for (StackTraceElement e : stack) {
            String cls = e.getClassName();
            if (cls.startsWith("com.george_vi.electroenergetics.mixins.DismountTeleportMixin"))
                continue;
            if (cls.startsWith("java.lang") || cls.startsWith("com.google")
                    || cls.startsWith("org.spongepowered"))
                continue;
            if (taken > 0)
                sb.append(" <- ");
            sb.append(cls.substring(cls.lastIndexOf('.') + 1))
              .append(':').append(e.getLineNumber())
              .append('(').append(e.getMethodName()).append(')');
            if (++taken >= 6)
                break;
        }
        return sb.length() == 0 ? "?" : sb.toString();
    }
}
