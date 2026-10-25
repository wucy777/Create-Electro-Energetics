package com.george_vi.electroenergetics;

import com.george_vi.electroenergetics.content.railway_electrification.pantograph.PantographType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class CEEPantographTypes {
    private static final DeferredRegister<PantographType> PANTOGRAPH_TYPE =
            DeferredRegister.create(CEERegistries.PANTOGRAPH_TYPE, CreateElectroEnergetics.ID);

    /**
     * Half-width of the contact strip, in Blocks - the sideways tolerance for collecting current.
     *
     * <p>Upstream used 2.0. Raised to 2.5 for the two overhead types.
     *
     * <p>Why: a rigid carriage cannot keep its pantograph under a curved catenary. The arm is fixed
     * in the carriage's frame while the wire follows the arc, so on a compact loop the lateral offset
     * at the middle of a carriage reaches past 2 Blocks and the train drops its pantograph on every
     * bend - then has to be re-raised on every straight, by which time it has lost speed. 2.0 was not
     * enough for the operator's layout.
     *
     * <p>2.5 rather than an arbitrary larger number because that is the width the contact strip is
     * understood to be: the debug range box in {@code PantographMovementBehaviour} traces
     * z = -1.5 .. +1.5 Blocks, i.e. a three-Block strip, so 2.5 is the value at which the tolerance
     * stops being narrower than the metal it is modelling.
     *
     * <p>The cost, stated plainly: this is a wider net than the metal, so a pantograph can collect
     * from a wire up to three Blocks laterally rather than two. On adjacent parallel tracks that
     * could mean contact with the neighbouring line. That is the deliberate trade - a train that keeps
     * its supply through a bend is worth more than a strict reading of the strip's width, and the
     * operator's layout has one wire above each track.
     *
     * <p>A constant rather than a config option: it is geometry that has to stay in step with the
     * model, and the third-rail type below must NOT be widened with it.
     */
    // The electrical tolerance, in Blocks either side of the pantograph's centre.
    //
    // Upstream used 2.0. That is too tight for a catenary that curves while the carriage stays rigid: a
    // train two Blocks into a bend would lose contact and stop. 2.5 keeps half a Block of margin on top of
    // upstream without allowing a train to draw power from a wire it is not under.
    public static final float CONTACT_HALF_WIDTH = 2.5f;

    // The width the collector bar is DRAWN at. Deliberately narrower than the tolerance above.
    //
    // These two numbers used to disagree by a factor of three: the tolerance had been widened to three
    // Blocks while the model still spanned a little over one, so a train three Blocks off the wire still
    // drew power and the bar visibly floated under nothing. Drawing to the full tolerance fixes that but
    // makes the bar five Blocks wide, which is not what a pantograph looks like. Drawing to 2.0 leaves
    // the half-Block of curve margin as the only gap, which is small enough not to read as a gap.
    public static final float CONTACT_DRAW_HALF_WIDTH = 2.0f;

    // The collector bar in connecting_surface.json runs from x=-9 to x=25 model units about a centre at
    // x=8, so its half-length is 17/16 Blocks. The renderer scales by this ratio so the drawn bar matches
    // CONTACT_DRAW_HALF_WIDTH exactly, and the two cannot drift apart again.
    private static final float MODEL_HALF_WIDTH = 17f / 16f;
    public static final float CONTACT_MODEL_SCALE = CONTACT_DRAW_HALF_WIDTH / MODEL_HALF_WIDTH;

    public static final DeferredHolder<PantographType, PantographType> STANDARD = PANTOGRAPH_TYPE.register("standard", () -> new PantographType(3.25f, 0.25f, 0.5f, CONTACT_HALF_WIDTH));
    public static final DeferredHolder<PantographType, PantographType> DOUBLE = PANTOGRAPH_TYPE.register("double", () -> new PantographType(3.25f, 0.5f, 0.5f, CONTACT_HALF_WIDTH));
    // NOT widened. A third-rail shoe runs on a rail at a fixed lateral position by construction -
    // there is no "curved catenary above a rigid carriage" problem for it - and widening it would let
    // a shoe reach the neighbouring running rail, which is a short circuit rather than a bend.
    public static final DeferredHolder<PantographType, PantographType> RAIL_CONTACT_SHOE = PANTOGRAPH_TYPE.register("rail_contact_shoe", () -> new PantographType(1f, 0, -0.75f, 0.5f));

    public static void register(IEventBus bus) {
        PANTOGRAPH_TYPE.register(bus);
    }
}
