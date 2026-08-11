package mmmm.core.sync;

/**
 * A rolling window over the drift loop's output, so a human can read it.
 *
 * <h2>Why an average is not a nicety here</h2>
 * The instantaneous drift is dominated by an artefact, not by sync error. Client position is derived
 * as "where the writer is, minus what is still buffered" (§5.3) — which is exact, and deliberately
 * so — but the sound engine drains the ring in chunks, so the buffered figure sawtooths by one read
 * on every cycle. Drift inherits that sawtooth whole. Measured in game the span was around 185 ms
 * peak-to-peak while the underlying error was far smaller.
 *
 * <p>Read instantaneously, that is unreadable and actively misleading: it invites chasing a swing
 * that is the audio callback's block size rather than a clock problem.
 *
 * <p><b>The span is worth showing, not hiding.</b> It measures the read granularity, and it is the
 * quantity to watch against {@link DriftController#HARD_RESYNC_MICROS}: once the sawtooth's excursion
 * reaches that threshold, a spurious hard resync fires on the peak of an otherwise healthy session,
 * and each one is audible. A readout that smoothed the span away would conceal precisely the failure
 * it exists to catch.
 *
 * <p>Not thread-safe: sample and read from the client thread.
 */
public final class SyncMeter {

    /** One second at the drift loop's 20 Hz. Long enough to cover several read cycles. */
    public static final int DEFAULT_WINDOW = 20;

    private final long[] driftMicros;
    private final double[] rateTrim;
    private final long[] resyncCount;

    private int next;
    private int count;

    public SyncMeter() {
        this(DEFAULT_WINDOW);
    }

    public SyncMeter(int window) {
        if (window <= 0) {
            throw new IllegalArgumentException("window must be positive");
        }
        this.driftMicros = new long[window];
        this.rateTrim = new double[window];
        this.resyncCount = new long[window];
    }

    /**
     * One sample per drift-loop step. Extra calls per step would weight that step more heavily.
     *
     * @param hardResyncCount the session's running total, not a delta — {@link #resyncsInWindow()}
     *                        takes the difference across the window
     */
    public void sample(long driftMicros, double rateTrim, long hardResyncCount) {
        this.driftMicros[next] = driftMicros;
        this.rateTrim[next] = rateTrim;
        this.resyncCount[next] = hardResyncCount;
        next = (next + 1) % this.driftMicros.length;
        if (count < this.driftMicros.length) {
            count++;
        }
    }

    /** Discards the window. For when the session changes underneath and old samples describe it. */
    public void reset() {
        next = 0;
        count = 0;
    }

    public boolean hasSamples() {
        return count > 0;
    }

    public int sampleCount() {
        return count;
    }

    /** Mean drift over the window, or 0 with no samples. */
    public long meanDriftMicros() {
        if (count == 0) {
            return 0;
        }
        long total = 0;
        for (int i = 0; i < count; i++) {
            total += driftMicros[i];
        }
        return total / count;
    }

    /**
     * Peak-to-peak drift over the window — the size of the sawtooth, not an error bar.
     *
     * @return max minus min, always non-negative; 0 with fewer than two samples
     */
    public long driftSpanMicros() {
        if (count == 0) {
            return 0;
        }
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for (int i = 0; i < count; i++) {
            min = Math.min(min, driftMicros[i]);
            max = Math.max(max, driftMicros[i]);
        }
        return max - min;
    }

    /**
     * Hard resyncs that happened during the window — with a one-second window, resyncs per second.
     *
     * <p>The rate is the number that matters, not the total. A total of 3065 says only that
     * something went wrong at some point; a rate of 20/s says it is going wrong right now, on every
     * tick, and that the drift and trim figures beside it describe a controller being reset faster
     * than it can act. Each resync is an audible jump, so anything above zero during steady playback
     * is a fault.
     */
    public long resyncsInWindow() {
        if (count == 0) {
            return 0;
        }
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for (int i = 0; i < count; i++) {
            min = Math.min(min, resyncCount[i]);
            max = Math.max(max, resyncCount[i]);
        }
        return max - min;
    }

    /** Mean playback-rate multiplier over the window, or 1.0 (normal speed) with no samples. */
    public double meanRateTrim() {
        if (count == 0) {
            return 1.0;
        }
        double total = 0.0;
        for (int i = 0; i < count; i++) {
            total += rateTrim[i];
        }
        return total / count;
    }
}
