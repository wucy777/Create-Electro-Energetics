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
     * <p>Upstream used 2.0. The operator's own loop then measured a real failure at 2.5, so it is 3.5.
     *
     * <p>Why: a rigid carriage cannot keep its pantograph under a curved catenary. The arm is fixed
     * in the carriage's frame while the wire follows the arc, so the arm is pulled off the wire by
     * the chord of the carriage between its two bogeys. On the operator's layout the turns are tight
     * (about 14.5 Blocks of radius) while the carriages are 25 Blocks long, and a sweep of a rigid
     * carriage around the whole loop measured a worst-case sideways offset of 3.11 Blocks. 2.5 left
     * positions on the tightest turns where one arm lost contact while the other - mounted at a
     * different point of the same train - did not, which is exactly the reported "two identical arms,
     * only one works" symptom.
     *
     * <p>The asymmetry was never the facing: the arm's facing only ever shifted its test point along
     * the track (+-backOffset), and reconstructing the operator's save showed that offset changes the
     * lateral distance by less than 0.02 Blocks. What differs between two arms on one train is their
     * POSITION along the carriage, and on a tight curve that is what crosses the tolerance first.
     *
     * <p>The cost, stated plainly: this is 1.7 Blocks wider per side than upstream's net, so a
     * pantograph can collect from a wire up to three and a half Blocks laterally. The consequence is
     * that an ADJACENT PARALLEL LINE's catenary can now be picked up as well if it runs closer than
     * that - in a station throat or a double-track section, a train could draw from the neighbouring
     * line's wire. The operator's own layout has one wire above each track and no such pair, so this
     * was accepted; a server with two electrified tracks 3 Blocks apart should treat 3.5 as a known
     * limitation rather than a bug, and the value is one constant to change if that ever bites. The
     * alternative - a curvature-dependent tolerance - was considered and rejected because the server
     * only sees the two bogey anchors and has no cheap curvature estimate on this hot path.
     *
     * <p>A constant rather than a config option: it is geometry that has to stay in step with the
     * model, and the third-rail type below must NOT be widened with it.
     */
    // The electrical tolerance, in Blocks either side of the pantograph's centre.
    //
    // Upstream used 2.0, then this fork used 2.5 after the first round of lost contact on bends. The
    // operator's own track then showed the remaining failure, and a carriage-against-catenary sweep
    // measured 3.11 Blocks of worst-case sideways offset, so 3.5 is the first value with margin.
    public static final float CONTACT_HALF_WIDTH = 3.5f;

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
