package com.george_vi.electroenergetics.mixins;

import com.simibubi.create.content.trains.TrainHUDUpdatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.UUID;

/**
 * Reads the train id off a {@code TrainHUDUpdatePacket}, for the throttle gate.
 *
 * <p>The id lives on the abstract parent as a protected field, and Java's access rules put it out
 * of reach of a mixin (which is neither a subclass nor in Create's package). An accessor on the
 * declaring class is the least inventive way to read it: the generated method is on the parent, so
 * the {@code Serverbound} subclass has it too.
 */
@Mixin(TrainHUDUpdatePacket.class)
public interface TrainHUDUpdateAccessor {

    /** The train this update is about. */
    @Accessor("trainId")
    UUID electroEnergetics$trainId();
}
