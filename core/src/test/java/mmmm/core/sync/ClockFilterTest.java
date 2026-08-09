package mmmm.core.sync;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The clock estimator from ADR-0005.
 *
 * <p>The interesting claim to verify is that keeping the minimum-RTT sample beats averaging. That
 * is not obvious in the abstract, so the tests build a network model where it is measurable:
 * a fixed path delay plus one-sided, occasionally huge queueing delay, which is what sharing a TCP
 * connection with game traffic actually looks like.
 */
class ClockFilterTest {

    private static final long MS = 1_000_000L;

    @Test
    void recoversAKnownOffsetFromACleanExchange() {
        ClockFilter filter = new ClockFilter();
        long trueOffset = 5_000 * MS;
        long sent = 1_000 * MS;
        long rtt = 40 * MS;

        // The server stamps the midpoint of a symmetric path.
        filter.update(sent, sent + rtt / 2 + trueOffset, sent + rtt);

        assertEquals(trueOffset, filter.offsetNanos());
        assertEquals(rtt, filter.bestRoundTripNanos());
    }

    @Test
    void waitsForSeveralSamplesBeforeReportingConvergence() {
        ClockFilter filter = new ClockFilter();

        assertFalse(filter.isConverged(), "no samples means no estimate");
        filter.update(0, 10 * MS, 20 * MS);
        assertFalse(filter.isConverged(), "one sample is not enough to trust");
        filter.update(100 * MS, 110 * MS, 120 * MS);
        assertFalse(filter.isConverged());
        filter.update(200 * MS, 210 * MS, 220 * MS);

        assertTrue(filter.isConverged(),
                "playback waits on this; starting early means an audible resync");
    }

    @Test
    void ignoresASampleWithANegativeRoundTrip() {
        ClockFilter filter = new ClockFilter();
        filter.update(0, 10 * MS, 20 * MS);
        long before = filter.offsetNanos();

        // A pong that appears to arrive before its ping means the clock jumped mid-exchange.
        filter.update(500 * MS, 505 * MS, 400 * MS);

        assertEquals(before, filter.offsetNanos(), "a nonsense sample must not move the estimate");
        assertEquals(1, filter.sampleCount());
    }

    @Test
    void prefersTheLeastDelayedSample() {
        ClockFilter filter = new ClockFilter();
        long trueOffset = 1_000 * MS;

        // A badly queued sample first, then a clean one.
        addSample(filter, 0, trueOffset, 400 * MS, 380 * MS);
        addSample(filter, 1000 * MS, trueOffset, 20 * MS, 0);

        assertEquals(20 * MS, filter.bestRoundTripNanos());
        assertEquals(trueOffset, filter.offsetNanos(),
                "the quiet sample carries the accurate offset and must win");
    }

    /**
     * The claim behind choosing a minimum filter over an average, measured.
     *
     * <p>Queueing delay is one-sided and heavy-tailed, so its mean is biased upwards. An averaging
     * estimator inherits that bias; a minimum filter finds the quietest moment and does not.
     */
    @Test
    void beatsAveragingUnderRealisticAsymmetricJitter() {
        Random random = new Random(20260808L);
        long trueOffset = 3_000 * MS;
        long basePathDelay = 30 * MS;

        ClockFilter filter = new ClockFilter(12);
        long offsetSum = 0;
        int count = 0;

        for (int i = 0; i < 12; i++) {
            // Queueing is one-sided: it only ever adds delay, and occasionally a lot.
            long outboundQueue = (long) (random.nextDouble() * 15 * MS);
            long inboundQueue = (long) (random.nextDouble() * 15 * MS);
            if (i % 4 == 0) {
                inboundQueue += 120 * MS; // a burst of game traffic
            }

            long sent = i * 1000L * MS;
            long serverStamp = sent + basePathDelay / 2 + outboundQueue + trueOffset;
            long received = sent + basePathDelay + outboundQueue + inboundQueue;

            filter.update(sent, serverStamp, received);
            offsetSum += serverStamp - (sent + (received - sent) / 2);
            count++;
        }

        long minimumFilterError = Math.abs(filter.offsetNanos() - trueOffset);
        long averagingError = Math.abs((offsetSum / count) - trueOffset);

        assertTrue(minimumFilterError < averagingError,
                "minimum filter error " + minimumFilterError / MS + "ms should beat averaging's "
                        + averagingError / MS + "ms");
    }

    @Test
    void tracksTheClockAsOldSamplesLeaveTheWindow() {
        ClockFilter filter = new ClockFilter(4);

        // A very quiet early sample, which would dominate forever without a bounded window.
        addSample(filter, 0, 1_000 * MS, 5 * MS, 0);
        assertEquals(1_000 * MS, filter.offsetNanos());

        // The true offset then shifts, and later samples all reflect the new value.
        for (int i = 1; i <= 4; i++) {
            addSample(filter, i * 1000L * MS, 2_000 * MS, 50 * MS, 0);
        }

        assertEquals(2_000 * MS, filter.offsetNanos(),
                "the stale best sample must age out so genuine clock drift is tracked");
        assertEquals(4, filter.sampleCount());
    }

    @Test
    void convertsClientInstantsToServerTime() {
        ClockFilter filter = new ClockFilter();
        addSample(filter, 0, 750 * MS, 10 * MS, 0);

        assertEquals(1_750 * MS, filter.toServerNanos(1_000 * MS));
    }

    /**
     * @param extraInbound delay added to the return leg only, which breaks the symmetry the
     *                     {@code rtt/2} estimate assumes and so biases that sample
     */
    private static void addSample(ClockFilter filter, long sent, long trueOffset,
                                  long rtt, long extraInbound) {
        long serverStamp = sent + (rtt - extraInbound) / 2 + trueOffset;
        filter.update(sent, serverStamp, sent + rtt);
    }
}
