package com.george_vi.electroenergetics.content.railway_electrification;

/**
 * Which control scheme is currently driving an electric train.
 *
 * <h2>Why a third state exists</h2>
 *
 * <p>The obvious design is two states: an electric train is either driven by this mod's lever or
 * by Create's throttle-and-wheel. That breaks at the moment the handover happens. Create never
 * clamps a train's speed - it clamps its <i>target</i> speed and then ramps towards it at
 * {@code acceleration()}, which on a fuel train is a small figure tuned for a 28 Blocks/Second
 * design ceiling (confirmed by disassembling Create: {@code Train.approachTargetSpeed} only ever
 * moves {@code speed} towards {@code targetSpeed}). Handing it a train doing 100 Blocks/Second
 * therefore does not crash or teleport - but Create would spend a very long time dragging that
 * speed back with a rate meant for a much slower train, and every electric readout would be
 * meaningless during it.
 *
 * <p>{@link #DEAD_COAST} is the fix. When the pantographs stop working, the train does not change
 * hands immediately: this mod keeps it, with no traction, until running resistance and gradient
 * (and the driver's brake, which still works) have brought it down to a speed Create's own code is
 * designed to manage. Only then does {@link #CREATE} take over. The handover is therefore always a
 * transfer of a train that the receiving scheme can actually hold.
 *
 * <h2>What each state owns</h2>
 *
 * <table>
 *   <tr><th>state</th><th>traction</th><th>brake and vigilance</th><th>speed ceiling</th></tr>
 *   <tr><td>{@link #ELECTRIC}</td><td>this mod</td><td>this mod</td><td>traction model</td></tr>
 *   <tr><td>{@link #DEAD_COAST}</td><td>none</td><td>this mod</td><td>this mod's</td></tr>
 *   <tr><td>{@link #CREATE}</td><td>Create</td><td>Create</td><td>Create's</td></tr>
 * </table>
 *
 * <p>Being in {@link #DEAD_COAST} is what makes the loss of supply survivable rather than a
 * handover of a train nobody can control. It is deliberately a state a train can sit in for a long
 * time - that is the entire point of it.
 */
public enum TrainControlMode {

    /**
     * This mod's lever drives the train: pantographs working and supply adequate.
     */
    ELECTRIC,

    /**
     * The train is this mod's, but has no traction: no working pantograph, or supply below the
     * acceptable voltage.
     *
     * <p>Brake, lever and vigilance timer all still belong to this mod, because losing the wire
     * must not also lose the brake - on a real train the brake is pneumatic and works regardless.
     * Only the tractive effort is gone, and {@code isPowered} already handles that.
     *
     * <p>The train leaves this state in one of two directions: back to {@link #ELECTRIC} if a
     * pantograph is raised again, or to {@link #CREATE} once it has slowed enough.
     */
    DEAD_COAST,

    /**
     * Create's own controls drive the train, exactly as for a fuel train.
     *
     * <p>Entered once a train without working pantographs has slowed to a speed Create is designed
     * to handle. Once here, nothing of this mod's driving remains: no lever, no vigilance timer,
     * no electric ceiling. The user's instruction is explicit that the electric machinery must not
     * follow the train into this state - Create behaves as it always has.
     */
    CREATE
}
