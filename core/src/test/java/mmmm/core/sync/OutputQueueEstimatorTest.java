package mmmm.core.sync;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The output-queue estimate.
 *
 * <p>This term is subtracted from the playback position, so an error here becomes a sync error
 * directly. The numbers below are the ones measured in game: a 44.1 kHz mono stream where the sound
 * system took about four seconds in one gulp at startup and the queue settled around two.
 */
class OutputQueueEstimatorTest {

    private static final int RATE = 44_100;
    private static final long SECOND = 1_000_000_000L;

    @Test
    void nothingIsQueuedBeforePlaybackStarts() {
        OutputQueueEstimator estimator = new OutputQueueEstimator(RATE, 8.0);
        assertEquals(0, estimator.queuedSamples(SECOND * 100));
    }

    /** The startup gulp: the channel pumps its buffers before a single sample can have played. */
    @Test
    void everythingHandedOverAtOnceIsStillQueued() {
        OutputQueueEstimator estimator = new OutputQueueEstimator(RATE, 8.0);
        estimator.onRead(4L * RATE, 0);
        assertEquals(4L * RATE, estimator.queuedSamples(0));
    }

    @Test
    void theQueueDrainsWithWallClockTime() {
        OutputQueueEstimator estimator = new OutputQueueEstimator(RATE, 8.0);
        estimator.onRead(4L * RATE, 0);

        assertEquals(3L * RATE, estimator.queuedSamples(SECOND));
        assertEquals(2L * RATE, estimator.queuedSamples(2 * SECOND));
    }

    /** Steady state: read a second's worth every second, and the queue depth holds. */
    @Test
    void refillingAtRealTimeHoldsTheQueueSteady() {
        OutputQueueEstimator estimator = new OutputQueueEstimator(RATE, 8.0);
        estimator.onRead(4L * RATE, 0);

        for (int second = 1; second <= 10; second++) {
            estimator.onRead(RATE, second * SECOND);
            assertEquals(4L * RATE, estimator.queuedSamples(second * SECOND),
                    "the queue should stay four seconds deep at second " + second);
        }
    }

    /**
     * A pause stops playback but not the wall clock, so the elapsed estimate runs ahead. Reporting a
     * negative queue would push the playback position <em>forwards</em>, which is the wrong direction
     * and would look like the audio had jumped ahead rather than stalled.
     */
    @Test
    void theQueueIsNeverNegative() {
        OutputQueueEstimator estimator = new OutputQueueEstimator(RATE, 8.0);
        estimator.onRead(RATE, 0);
        assertEquals(0, estimator.queuedSamples(60 * SECOND));
    }

    /**
     * The bound exists for a suspended process or a clock jump: without it, a huge handedOut figure
     * would be subtracted wholesale from the position.
     */
    @Test
    void theEstimateIsBounded() {
        OutputQueueEstimator estimator = new OutputQueueEstimator(RATE, 8.0);
        estimator.onRead(1000L * RATE, 0);
        assertEquals(8L * RATE, estimator.queuedSamples(0));
    }

    @Test
    void resetForgetsTheDestroyedChannel() {
        OutputQueueEstimator estimator = new OutputQueueEstimator(RATE, 8.0);
        estimator.onRead(4L * RATE, 0);
        estimator.reset();

        assertEquals(0, estimator.queuedSamples(0), "a re-created channel starts with an empty queue");
        estimator.onRead(RATE, 10 * SECOND);
        assertEquals(RATE, estimator.queuedSamples(10 * SECOND),
                "and the clock restarts from the new first read, not the old one");
    }

    /** Sub-second arithmetic must not lose precision to integer division. */
    @Test
    void partialSecondsDrainProportionally() {
        OutputQueueEstimator estimator = new OutputQueueEstimator(RATE, 8.0);
        estimator.onRead(RATE, 0);

        long half = estimator.queuedSamples(SECOND / 2);
        assertTrue(Math.abs(half - RATE / 2) < 100,
                "half a second in, half a second should remain, got " + half);
    }
}
