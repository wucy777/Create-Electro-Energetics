package com.george_vi.electroenergetics;

import com.george_vi.electroenergetics.content.railway_electrification.pantograph.PantographType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class CEEPantographTypes {
    private static final DeferredRegister<PantographType> PANTOGRAPH_TYPE =
            DeferredRegister.create(CEERegistries.PANTOGRAPH_TYPE, CreateElectroEnergetics.ID);

    /**
     * Horizontal contact tolerance on either side of the collector centre, in Blocks.
     *
     * <p>The contact sample is the centre of the block, correcting the upstream corner offset. The final
     * operator-tested compromise is 2.5 Blocks: it keeps the eight-piece 1/8-curve loop energized while
     * avoiding the 3.0-3.5 range that would reach too far beyond the visible collector. The drawing remains
     * narrower than this tolerance by design, to absorb the chord error of a rigid carriage on a curve.
     * The operator accepts that very brief edge pickup can occur at the model's extension.
     *
     * <p>This does not apply to the third-rail shoe, whose lateral position is fixed by the running rail.
     */
    // Center-sampled electrical tolerance. Keep the client constant in PantographMovementBehaviour in sync.
    public static final float CONTACT_HALF_WIDTH = 2.5f;

    // The width the collector bar is DRAWN at. Deliberately narrower than the tolerance above.
    //
    // These two numbers used to disagree by a factor of three: the tolerance had been widened while the
    // model still spanned a little over one Block, so a train well off the wire still drew power and the bar
    // visibly floated under nothing. Drawing to the full tolerance fixes that but makes the bar seven Blocks
    // wide, which is not what a pantograph looks like. A drawn half-width of 1.5625 is what the operator
    // asked for: the model's own 17/16 plus half a Block per side.
    //
    // The gap between this and CONTACT_HALF_WIDTH is the deliberate part of the judgement: the contact test
    // answers "could the strip reach that wire on this curve", not "is the bar drawn underneath it".
    public static final float CONTACT_DRAW_HALF_WIDTH = 1.5625f;

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
