package com.george_vi.electroenergetics.events;

import com.george_vi.electroenergetics.CreateElectroEnergetics;
import com.george_vi.electroenergetics.content.railway_electrification.RiderSeatGate;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.Train;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /eeseat} - runs the seating pass once, on demand, at any speed.
 *
 * <p>Exists so the seat rules can be exercised while standing still. Waiting for 28 Blocks/second to watch a
 * seat decision meant every test was also a test of the speed gate, the brake, the capacity message and the
 * train's motion, and a wrong seat could not be told apart from a wrong position.
 *
 * <p>Diagnostic tooling, not a feature: it changes no state a normal tick would not change, and it is safe to
 * call twice.
 */
@EventBusSubscriber(modid = CreateElectroEnergetics.ID)
public final class SeatDebugCommand {

    private SeatDebugCommand() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("eeseat")
                .executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    Train train = trainOf(player);
                    if (train == null) {
                        ctx.getSource().sendFailure(Component.literal("no train nearby"));
                        return 0;
                    }
                    RiderSeatGate.pruneStaleSeats(train);
                    int seated = RiderSeatGate.trySeatAll(train);
                    boolean standing = RiderSeatGate.anyUnseated(train);
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "seated=" + seated
                                    + " stillStanding=" + standing
                                    + " speed=" + String.format("%.2f", Math.abs(train.speed) * 20d)
                                    + " bps"), false);
                    return seated;
                }));
    }

    private static Train trainOf(ServerPlayer player) {
        Train nearest = null;
        double best = 32d * 32d;
        for (Train train : Create.RAILWAYS.trains.values()) {
            for (var carriage : train.carriages) {
                var entity = carriage.anyAvailableEntity();
                if (entity == null)
                    continue;
                if (player.getRootVehicle() == entity || player.getVehicle() == entity)
                    return train;
                double d = entity.distanceToSqr(player);
                if (d < best) {
                    best = d;
                    nearest = train;
                }
            }
        }
        return nearest;
    }
}