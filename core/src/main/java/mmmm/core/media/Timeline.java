package mmmm.core.media;

/**
 * Converts a cumulative sample count into microsecond timestamps, exactly.
 *
 * <p>This class exists for one reason: to make the accumulating-rounding-error bug impossible to
 * write. See {@link MediaFrame} for why that bug matters. The rule it enforces is that the
 * authoritative counter is <em>samples</em>, and microseconds are always derived from the running
 * total rather than summed from per-frame conversions.
 *
 * <p>The conversion splits the division so that neither exactness nor range is sacrificed:
 * <pre>{@code
 *   micros = (total / rate) * 1_000_000 + ((total % rate) * 1_000_000) / rate
 * }</pre>
 * The naive {@code total * 1_000_000 / rate} would be exact too, but overflows {@code long} after
 * about 6.6 years of 44.1 kHz audio. The split form has no such limit, and the intermediate
 * {@code (total % rate) * 1_000_000} cannot overflow for any real sample rate.
 *
 * <p>Not thread-safe. One instance belongs to one parsing thread.
 */
public final class Timeline {

    private static final long MICROS_PER_SECOND = 1_000_000L;

    private final int sampleRate;
    private long totalSamples;

    public Timeline(int sampleRate) {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive, was " + sampleRate);
        }
        this.sampleRate = sampleRate;
    }

    /**
     * Returns the timestamp of the frame starting here, then advances past it.
     *
     * @param samples samples in the frame just emitted
     * @return the presentation timestamp of that frame, in microseconds
     */
    public long emit(int samples) {
        if (samples < 0) {
            throw new IllegalArgumentException("samples must be >= 0, was " + samples);
        }
        long pts = currentMicros();
        totalSamples += samples;
        return pts;
    }

    /** Timestamp of the next frame to be emitted, in microseconds. */
    public long currentMicros() {
        return toMicros(totalSamples, sampleRate);
    }

    public long totalSamples() {
        return totalSamples;
    }

    public int sampleRate() {
        return sampleRate;
    }

    /**
     * Jumps the counter to an absolute sample position. Used by the Ogg parser, whose granule
     * position is itself an absolute sample counter, so the timeline follows the container rather
     * than trying to track it independently.
     */
    public void seekToSample(long absoluteSample) {
        if (absoluteSample < 0) {
            throw new IllegalArgumentException("absoluteSample must be >= 0, was " + absoluteSample);
        }
        this.totalSamples = absoluteSample;
    }

    /** Exact floor of {@code samples * 1_000_000 / sampleRate}, without overflow. */
    public static long toMicros(long samples, int sampleRate) {
        return (samples / sampleRate) * MICROS_PER_SECOND
                + ((samples % sampleRate) * MICROS_PER_SECOND) / sampleRate;
    }

    /** Inverse of {@link #toMicros}, flooring. */
    public static long toSamples(long micros, int sampleRate) {
        return (micros / MICROS_PER_SECOND) * sampleRate
                + ((micros % MICROS_PER_SECOND) * sampleRate) / MICROS_PER_SECOND;
    }
}
