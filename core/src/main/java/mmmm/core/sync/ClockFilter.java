package mmmm.core.sync;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Estimates the offset between the client's clock and the server's.
 *
 * <p>NTP-style exchange over the existing connection: the client stamps a ping, the server stamps
 * the pong, and the client sees both plus the round trip.
 *
 * <h2>Why the minimum, not the mean</h2>
 * The naive estimator averages samples, which is wrong here. Round-trip delay is not symmetric
 * noise around a true value — it is a floor (the real path delay) plus a one-sided, heavy-tailed
 * queueing delay. Averaging pulls the estimate towards the queueing, and on a connection shared
 * with game traffic that queueing is substantial and bursty.
 *
 * <p>The sample with the lowest round trip is the one that spent least time queued, so its
 * {@code (rtt/2)} assumption of path symmetry is least wrong. Keeping the best sample in a sliding
 * window is standard NTP practice and materially more accurate than any average.
 *
 * <p>Not thread-safe; owned by the client session.
 */
public final class ClockFilter {

    /**
     * Samples retained. At one ping per 5 s this is about a minute of history — long enough to
     * find a quiet moment, short enough to track genuine clock drift.
     */
    private static final int DEFAULT_WINDOW = 12;

    private final int window;
    private final Deque<Sample> samples = new ArrayDeque<>();

    private long offsetNanos;
    private long roundTripNanos = Long.MAX_VALUE;
    private boolean converged;

    /** Samples required before the estimate is trusted enough to start playback. */
    private static final int MIN_SAMPLES_TO_CONVERGE = 3;

    public ClockFilter() {
        this(DEFAULT_WINDOW);
    }

    public ClockFilter(int window) {
        if (window < 1) {
            throw new IllegalArgumentException("window must be >= 1");
        }
        this.window = window;
    }

    /**
     * Records one completed ping/pong exchange.
     *
     * @param sentNanos     client clock when the ping was sent
     * @param serverNanos   server clock when the ping was received
     * @param receivedNanos client clock when the pong arrived
     */
    public void update(long sentNanos, long serverNanos, long receivedNanos) {
        long rtt = receivedNanos - sentNanos;
        if (rtt < 0) {
            // A backwards round trip means the clock jumped mid-exchange. The sample is meaningless.
            return;
        }
        // Assume the two legs are equal, so the server's stamp corresponds to the midpoint.
        long offset = serverNanos - (sentNanos + rtt / 2);

        samples.addLast(new Sample(rtt, offset));
        while (samples.size() > window) {
            samples.removeFirst();
        }
        recompute();
    }

    private void recompute() {
        long bestRtt = Long.MAX_VALUE;
        long bestOffset = 0;
        for (Sample s : samples) {
            if (s.rtt() < bestRtt) {
                bestRtt = s.rtt();
                bestOffset = s.offset();
            }
        }
        roundTripNanos = bestRtt;
        offsetNanos = bestOffset;
        if (samples.size() >= MIN_SAMPLES_TO_CONVERGE) {
            converged = true;
        }
    }

    /**
     * Whether the estimate can be trusted.
     *
     * <p>Playback waits for this. Starting before the clock settles means starting at the wrong
     * position and then hard-resyncing, which is audible — a short silence at join is not.
     */
    public boolean isConverged() {
        return converged;
    }

    /** Add to a client timestamp to get the corresponding server timestamp. */
    public long offsetNanos() {
        return offsetNanos;
    }

    /** Best observed round trip, in nanoseconds. A rough confidence bound on the offset. */
    public long bestRoundTripNanos() {
        return roundTripNanos == Long.MAX_VALUE ? 0 : roundTripNanos;
    }

    /** Server-clock estimate for a given client-clock instant. */
    public long toServerNanos(long clientNanos) {
        return clientNanos + offsetNanos;
    }

    public int sampleCount() {
        return samples.size();
    }

    private record Sample(long rtt, long offset) {
    }
}
