package mmmm.core.sync;

/**
 * How much audio has been handed to the sound system but not yet heard.
 *
 * <h2>The gap this closes</h2>
 * Client position is derived as "where the writer is, minus what is still buffered" — exact about
 * <em>content</em>, and deliberately so, because the ring pads underruns and drops on overrun and a
 * read counter would see neither. But it is blind to everything downstream of the ring: OpenAL keeps
 * several seconds queued, and that audio has left the ring without having been played.
 *
 * <p>Measured in game, the error was <b>2.2 seconds</b>. Drift sat pinned at −2.2 s, a hard resync
 * fired on every tick and could not help — a resync corrects by discarding, and this error needs the
 * reader to wait — and the effective presentation delay was 0.4 s instead of the intended 3.0 s.
 * Worse for the whole point of the project: the amount queued depends on the listener's audio stack,
 * so every client sat at its own offset, which is exactly what relaying exists to prevent.
 *
 * <h2>Why this is not the wall-clock derivation the design rejected</h2>
 * Deriving <em>position</em> from elapsed time fails for the reason above: padded silence and dropped
 * audio break the mapping between time and content. This estimates only the <em>queue depth</em>,
 * where that objection does not apply — silence occupies playback time exactly like audio does, so
 * "samples handed over, minus samples that have had time to play" stays true whatever the ring did.
 * Position still comes from the ring; this only accounts for the last hop.
 *
 * <p>The alternative was reading {@code AL_BUFFERS_QUEUED} straight from the OpenAL source, which is
 * exact but needs access transformers into two private vanilla fields. ADR-0007's whole finding was
 * that the sound engine did not have to be reached into; this keeps that true.
 *
 * <p>Not thread-safe. {@link #onRead} is called from the audio thread and {@link #queuedSamples} from
 * the client thread; both only touch longs, and an estimate that is one read stale is well inside its
 * own error.
 */
public final class OutputQueueEstimator {

    private static final long UNSET = Long.MIN_VALUE;

    private final int sampleRate;
    private final long maxQueuedSamples;

    private long handedOutSamples;
    private long firstReadNanos = UNSET;

    /**
     * @param sampleRate       samples per second of the stream being played
     * @param maxQueuedSeconds sanity bound on the answer. Not a tuning knob: it stops a suspended
     *                         process or a clock jump from producing an absurd queue depth that
     *                         would then be subtracted from the playback position.
     */
    public OutputQueueEstimator(int sampleRate, double maxQueuedSeconds) {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive");
        }
        this.sampleRate = sampleRate;
        this.maxQueuedSamples = (long) (maxQueuedSeconds * sampleRate);
    }

    /** One call per read the sound system makes, with however many samples it took. */
    public void onRead(long samples, long nowNanos) {
        if (firstReadNanos == UNSET) {
            // The first read is the best available marker for "playback began": the channel pumps
            // its buffers and then starts the source. Any error here is a one-off of a few
            // milliseconds, against a quantity measured in seconds.
            firstReadNanos = nowNanos;
        }
        handedOutSamples += samples;
    }

    /**
     * Samples handed over that cannot have been played yet.
     *
     * @return zero before the first read, and never negative — the sound system cannot have played
     *         more than it was given, so a negative result means the elapsed-time estimate has run
     *         ahead (a pause, or a suspended process) and the honest answer is "nothing is queued"
     */
    public long queuedSamples(long nowNanos) {
        if (firstReadNanos == UNSET) {
            return 0;
        }
        long elapsedNanos = Math.max(0, nowNanos - firstReadNanos);
        long playedSamples = elapsedNanos / 1_000_000_000L * sampleRate
                + (elapsedNanos % 1_000_000_000L) * sampleRate / 1_000_000_000L;
        long queued = handedOutSamples - playedSamples;
        return Math.max(0, Math.min(queued, maxQueuedSamples));
    }

    /**
     * Forgets everything. For when the channel is destroyed and re-created — a resource reload or an
     * audio-device switch — because the queue goes with it and the old figures describe a sound that
     * no longer exists.
     */
    public void reset() {
        handedOutSamples = 0;
        firstReadNanos = UNSET;
    }
}
