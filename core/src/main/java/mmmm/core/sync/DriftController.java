package mmmm.core.sync;

/**
 * Keeps a client's playback position locked to the shared clock. See ADR-0005.
 *
 * <p>A proportional-integral loop, plus a hard-resync escape hatch:
 *
 * <table border="1">
 *   <caption>Control law</caption>
 *   <tr><th>Drift</th><th>Action</th></tr>
 *   <tr><td>under 10 ms</td><td>proportional term off; the integral term keeps holding</td></tr>
 *   <tr><td>10–250 ms</td><td>full PI control, trimmed to at most ±0.1 %</td></tr>
 *   <tr><td>over 250 ms</td><td>hard resync — flush and jump</td></tr>
 * </table>
 *
 * <h2>Why integral, not proportional alone</h2>
 * The dominant disturbance is not network jitter, it is the listener's sound card. Consumer audio
 * clocks run tens to hundreds of parts per million off nominal, and that error is <em>constant</em>.
 * A proportional-only controller cannot cancel a constant disturbance: it needs a standing error to
 * produce a standing output, so it parks at a permanent offset instead of converging. Against a
 * 50 ppm card it settles around 12 ms off and stays there — inside the resync threshold, so nothing
 * ever flags it, while every client sits at its own offset.
 *
 * <p>The integral term supplies that standing trim on its own, letting the error go to zero. This
 * is the difference between clients that converge and clients that merely stop diverging.
 *
 * <h2>Why the trim is inaudible</h2>
 * 0.1 % is about 1.7 cents of pitch, far below perception, whereas dropping or duplicating samples
 * clicks every time. Applying it is nearly free: {@code AL_PITCH} already resamples and Minecraft
 * re-reads {@code SoundInstance.getPitch()} every tick, so {@link #rateTrim()} is the whole control
 * surface.
 *
 * <p>0.1 % also sets a floor on correction speed: closing a 100 ms gap takes at least 100 seconds.
 * That is a deliberate trade — inaudible and slow beats fast and audible — and it is why anything
 * past 250 ms is jumped rather than trimmed.
 *
 * <h2>Tick rate</h2>
 * The gains assume {@link #update} is called once per Minecraft tick, 20 Hz. They are tuned for
 * critical damping at that rate; calling it at a materially different rate will change the damping.
 *
 * <p>The hard-resync path is not only for network faults — it also covers resuming from an ESC
 * pause, where frames kept arriving while the channel was stopped. The position is simply
 * re-derived from the clock, so that case needs no separate handling.
 *
 * <p>Not thread-safe; owned by the client session.
 */
public final class DriftController {

    /** Below this, corrections would cost more in hunting than they gain in accuracy. */
    public static final long DEADBAND_MICROS = 10_000;

    /** Above this, no trim within the inaudible budget could close the gap in reasonable time. */
    public static final long HARD_RESYNC_MICROS = 250_000;

    /** Maximum rate deviation. 0.001 is ~1.7 cents — inaudible. */
    public static final double MAX_TRIM = 0.001;

    /** Expected interval between {@link #update} calls: one Minecraft tick. */
    private static final double TICK_MICROS = 50_000;

    /**
     * Drift at which the proportional term alone demands full trim.
     *
     * <p>Well below {@link #HARD_RESYNC_MICROS} on purpose. Saturating only at the resync threshold
     * sounds tidy — the loop uses its full authority exactly where it would otherwise give up — but
     * it makes the gain so small that the loop's time constant runs to minutes, and a controller
     * that takes four minutes to correct 100 ms is not correcting anything in practice.
     */
    private static final double SATURATION_MICROS = 20_000;

    /** Proportional gain, in trim per microsecond of drift. */
    private static final double KP = MAX_TRIM / SATURATION_MICROS;

    /**
     * Integral gain, in trim per (microsecond × tick).
     *
     * <p>Set for critical damping: for the plant {@code e'' + T·KP·e' + T·KI·e = 0}, damping ratio
     * one requires {@code KI = T·KP²/4}. Underdamping here would show up as the playback rate
     * oscillating around correct, which is the one artefact this design exists to avoid.
     */
    private static final double KI = TICK_MICROS * KP * KP / 4.0;

    /** Anti-windup bound: the integral term alone may never exceed the whole trim budget. */
    private static final double MAX_INTEGRAL = MAX_TRIM / KI;

    /**
     * How much of the newly computed trim is adopted per update.
     *
     * <p>A step change in playback rate is audible as a chirp even when the destination rate is
     * not, so the loop eases towards its target instead of jumping. Fast relative to the control
     * loop itself, so it does not affect stability.
     */
    private static final double SLEW = 0.2;

    private double rateTrim = 1.0;
    private double integralMicroTicks;
    private long lastDriftMicros;
    private long hardResyncCount;

    /**
     * What the client should do about its current position.
     *
     * @param actualPtsMicros where playback actually is
     * @param targetPtsMicros where the shared clock says it should be
     * @return {@link Action#HARD_RESYNC} if the caller must flush and jump, else
     *         {@link Action#CONTINUE} with {@link #rateTrim()} updated
     */
    public Action update(long actualPtsMicros, long targetPtsMicros) {
        long drift = targetPtsMicros - actualPtsMicros;
        lastDriftMicros = drift;

        if (Math.abs(drift) > HARD_RESYNC_MICROS) {
            hardResyncCount++;
            resetAfterResync();
            return Action.HARD_RESYNC;
        }

        // The integral accumulates even inside the deadband. That is what lets it converge on the
        // standing trim a constant clock error needs; gating it would leave the error parked at the
        // deadband edge instead of at zero.
        integralMicroTicks = clamp(integralMicroTicks + drift, -MAX_INTEGRAL, MAX_INTEGRAL);

        // The proportional term is gated, so small fluctuations do not drive the rate around.
        double proportional = Math.abs(drift) >= DEADBAND_MICROS ? KP * drift : 0.0;

        double correction = clamp(proportional + KI * integralMicroTicks, -MAX_TRIM, MAX_TRIM);
        double target = 1.0 + correction;
        rateTrim += (target - rateTrim) * SLEW;
        return Action.CONTINUE;
    }

    /**
     * Resets after a hard resync; the jump has already removed the error the integral held.
     *
     * <p><b>{@link #lastDriftMicros} is deliberately left alone.</b> It used to be zeroed here, which
     * destroyed the evidence at exactly the moment it mattered: a session resyncing on every tick
     * reported a drift of zero to the health readout, i.e. perfect sync, because the reset ran
     * between the measurement and the read. A diagnostic that reads zero while the thing it measures
     * is failing is worse than no diagnostic. The field records what was last <em>observed</em>; the
     * control state is what gets reset.
     */
    public void resetAfterResync() {
        rateTrim = 1.0;
        integralMicroTicks = 0.0;
    }

    /**
     * Multiplier for playback rate; feed straight into {@code SoundInstance.getPitch()}.
     *
     * <p>Always within {@code 1 ± MAX_TRIM}.
     */
    public double rateTrim() {
        return rateTrim;
    }

    /** Most recent drift, positive when playback is behind the clock. For the health readout. */
    public long lastDriftMicros() {
        return lastDriftMicros;
    }

    /** Hard resyncs so far. A number that climbs during normal playback means something is wrong. */
    public long hardResyncCount() {
        return hardResyncCount;
    }

    /**
     * Standing rate correction the integral has settled on.
     *
     * <p>Worth surfacing in the health readout: it is a direct measurement of this machine's audio
     * clock error, so a large steady value points at hardware rather than at the network.
     */
    public double standingCorrection() {
        return KI * integralMicroTicks;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public enum Action {
        /** Keep playing; apply {@link #rateTrim()}. */
        CONTINUE,
        /** Drop buffered audio and restart at the target timestamp. */
        HARD_RESYNC
    }
}
