package com.george_vi.electroenergetics.content.railway_electrification;

/**
 * The driver's lever positions on an electric train.
 *
 * <p>This replaces Create's throttle-plus-W/S scheme for electric trains only.
 * A real EMU is driven by a single handle with a handful of detents: you notch it
 * to a running position and the train holds that, rather than holding a key down
 * and scrolling a wheel. Fuel trains keep Create's controls untouched.
 *
 * <p>{@link #BRAKE} is the default a train sits in. That matters for safety: a
 * train whose driver walks away, or that loses its driver-confirmation heartbeat,
 * falls back to a held brake rather than to a runaway.
 */
public enum TrainGear {
    /** 加速: full tractive effort, then full power once past the base speed. */
    ACCELERATE("accelerate"),

    /**
     * 匀速: hold a speed in the direction this notch drives (the cab's forward); climbs at
     * full power if needed, brakes on a descent.
     *
     * <p>The speed it holds is the one captured when it is engaged, in the direction the
     * notch drives. A setpoint taken while the train is running the other way cannot be
     * held - a hold position cannot hold speed against its own direction - so that case is
     * braked to a stand instead. See {@code TrainTractionModel.gearAcceleration}'s CRUISE
     * branch and {@code TrainDriverState.cruiseSpeed}.
     */
    CRUISE("cruise"),

    /** 切断动力: traction off and no brake. Drag and gravity only, so it rolls on a slope. */
    COAST("coast"),

    /** 刹车: constant net deceleration, with regeneration. */
    BRAKE("brake"),

    /** 倒车: reverse from rest; while moving it arms the emergency brake instead. */
    REVERSE("reverse");

    /**
     * Human-readable id, and the intended wire format.
     *
     * <p>It is NOT what the wire uses today: the gear packets send {@code ordinal()} and the HUD decodes by
     * ordinal. So reordering these constants IS a protocol change, whatever this comment used to claim.
     * {@link #byId} is unused, kept for the day the packets move to ids - at which point reordering becomes
     * safe and this note should go.
     */
    public final String id;

    TrainGear(String id) {
        this.id = id;
    }

    public static TrainGear byId(String id) {
        for (TrainGear g : values())
            if (g.id.equals(id))
                return g;
        return BRAKE;
    }

    /** Whether this position commands traction in the direction of travel. */
    public boolean appliesTraction() {
        return this == ACCELERATE || this == CRUISE || this == REVERSE;
    }

    /** Whether this position applies the brake. */
    public boolean appliesBrake() {
        return this == BRAKE;
    }
}
