package com.george_vi.electroenergetics.content.railway_electrification;

/**
 * Server-side driving state for one electric train, held on
 * {@link ElectricTrainData}.
 *
 * <p>Kept separate from the traction figures so it is obvious what is a command
 * from the driver and what is a computed result. Everything in here is written by
 * the control path and read by the simulation.
 */
public class TrainDriverState {

    /** Seconds between driver-confirmation prompts, matching a real vigilance timer. */
    public static final int CONFIRM_INTERVAL_TICKS = 30 * 20;

    /**
     * How long a prompt may go unanswered before the lever drops to the brake.
     * The prompt appears at the interval and the driver then has this long to
     * acknowledge it; overshooting applies the brake, as a real vigilance device
     * does, rather than simply nagging.
     */
    public static final int CONFIRM_GRACE_TICKS = 10 * 20;

    /** The lever. Defaults to the brake, which is where every failure lands. */
    public TrainGear gear = TrainGear.BRAKE;

    /**
     * Ticks remaining in which a driver is considered to be at the controls.
     *
     * <p>A countdown rather than a boolean because the two sides run on different
     * schedules: {@code control()} is driven by key packets every few ticks while
     * {@code Train.tick()} runs every tick, so a flag set by one and cleared by
     * the other would flicker the train between gear control and Create's own.
     * Refreshed whenever a driver is seen, and allowed to lapse on its own.
     */
    public int driverTicks = 0;

    /** True while a driver is at the controls, i.e. the gear law owns the train. */
    public boolean isDriven() {
        return driverTicks > 0;
    }

    /** Called each train tick, before the motion is integrated. */
    public void tickDriverPresence() {
        if (driverTicks > 0)
            driverTicks--;
    }

    /** Called from the control path whenever a driver is at the controls. */
    public void markDriverPresent() {
        driverTicks = 10;
    }

    /** Speed cruise is holding, in Blocks/Second. Captured when cruise is selected. */
    public double cruiseSpeed = 0d;

    /** Whether the emergency brake is armed (reverse selected while moving). */
    public boolean emergencyArmed = false;

    /** Whether the emergency brake has been fired. Locks out traction until the next station. */
    public boolean emergencyPenalty = false;

    /** Ticks until the next confirmation prompt. */
    public int confirmTimer = CONFIRM_INTERVAL_TICKS;

    /** Ticks the current prompt has been waiting. Negative when no prompt is up. */
    public int confirmWaiting = -1;

    /** Ticks of emergency-brake application remaining, while it is slowing the train. */
    public int emergencyTicks = 0;

    /**
     * Speed cap applied while the emergency penalty is in force [Blocks/Second].
     * The penalty lasts until the next station call, so a driver who uses it has
     * to live with a slow train for the rest of that leg.
     */
    public static final double PENALTY_SPEED = 40d / 3.6d;   // 40 km/h

    /** Reset the per-leg state when a scheduled stop is reached. */
    public void clearPenalty() {
        emergencyPenalty = false;
        emergencyArmed = false;
        emergencyTicks = 0;
    }

    /** Drop the lever to the brake and cancel anything in progress. */
    public void failSafe() {
        gear = TrainGear.BRAKE;
        emergencyArmed = false;
    }
}
