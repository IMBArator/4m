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
        assertEquals(0, estimator.queuedSamples());
    }

    /** The startup gulp: the channel pumps its buffers before a single sample can have played. */
    @Test
    void everythingHandedOverAtOnceIsStillQueued() {
        OutputQueueEstimator estimator = new OutputQueueEstimator(RATE, 8.0);
        estimator.onRead(4L * RATE, 0);
        assertEquals(4L * RATE, estimator.queuedSamples());
    }

    @Test
    void theQueueDrainsWithWallClockTime() {
        OutputQueueEstimator estimator = new OutputQueueEstimator(RATE, 8.0);
        estimator.onRead(4L * RATE, 0);

        estimator.advance(SECOND, 1.0);
        assertEquals(3L * RATE, estimator.queuedSamples());
        estimator.advance(2 * SECOND, 1.0);
        assertEquals(2L * RATE, estimator.queuedSamples());
    }

    /**
     * The loop-stability case. A trim above nominal means audio really is consumed faster, so the
     * estimate must drain faster too — otherwise raising the trim inflates the apparent queue,
     * position moves backwards, measured drift rises, and the controller pushes the trim up again.
     * That positive feedback was observed as a trim ramping steadily towards its ceiling.
     */
    @Test
    void aFasterPlaybackRateDrainsTheQueueFaster() {
        OutputQueueEstimator nominal = new OutputQueueEstimator(RATE, 8.0);
        OutputQueueEstimator fast = new OutputQueueEstimator(RATE, 8.0);
        nominal.onRead(4L * RATE, 0);
        fast.onRead(4L * RATE, 0);

        nominal.advance(SECOND, 1.0);
        fast.advance(SECOND, 1.001);

        assertTrue(fast.queuedSamples() < nominal.queuedSamples(),
                "a faster rate must consume more, or the drift loop's feedback is reversed");
    }

    @Test
    void aSlowerPlaybackRateDrainsTheQueueSlower() {
        OutputQueueEstimator nominal = new OutputQueueEstimator(RATE, 8.0);
        OutputQueueEstimator slow = new OutputQueueEstimator(RATE, 8.0);
        nominal.onRead(4L * RATE, 0);
        slow.onRead(4L * RATE, 0);

        nominal.advance(SECOND, 1.0);
        slow.advance(SECOND, 0.999);

        assertTrue(slow.queuedSamples() > nominal.queuedSamples());
    }

    /** The trim changes over time, so history must not be rewritten with the current value. */
    @Test
    void theRateIsIntegratedNotAppliedToTheWholeHistory() {
        OutputQueueEstimator estimator = new OutputQueueEstimator(RATE, 8.0);
        estimator.onRead(8L * RATE, 0);

        estimator.advance(SECOND, 1.0);
        estimator.advance(2 * SECOND, 1.0);
        // Only this last second is played fast; the two before it stay at nominal.
        estimator.advance(3 * SECOND, 2.0);

        // 1 + 1 + 2 = 4 seconds played, of 8 handed over.
        assertEquals(4L * RATE, estimator.queuedSamples(), RATE / 100);
    }

    /** Steady state: read a second's worth every second, and the queue depth holds. */
    @Test
    void refillingAtRealTimeHoldsTheQueueSteady() {
        OutputQueueEstimator estimator = new OutputQueueEstimator(RATE, 8.0);
        estimator.onRead(4L * RATE, 0);

        for (int second = 1; second <= 10; second++) {
            estimator.advance(second * SECOND, 1.0);
            estimator.onRead(RATE, second * SECOND);
            assertEquals(4L * RATE, estimator.queuedSamples(),
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
        estimator.advance(60 * SECOND, 1.0);
        assertEquals(0, estimator.queuedSamples());
    }

    /**
     * The bound exists for a suspended process or a clock jump: without it, a huge handedOut figure
     * would be subtracted wholesale from the position.
     */
    @Test
    void theEstimateIsBounded() {
        OutputQueueEstimator estimator = new OutputQueueEstimator(RATE, 8.0);
        estimator.onRead(1000L * RATE, 0);
        assertEquals(8L * RATE, estimator.queuedSamples());
    }

    @Test
    void resetForgetsTheDestroyedChannel() {
        OutputQueueEstimator estimator = new OutputQueueEstimator(RATE, 8.0);
        estimator.onRead(4L * RATE, 0);
        estimator.reset();

        assertEquals(0, estimator.queuedSamples(), "a re-created channel starts with an empty queue");
        estimator.onRead(RATE, 10 * SECOND);
        assertEquals(RATE, estimator.queuedSamples(),
                "and the clock restarts from the new first read, not the old one");
    }

    /** Sub-second arithmetic must not lose precision to integer division. */
    @Test
    void partialSecondsDrainProportionally() {
        OutputQueueEstimator estimator = new OutputQueueEstimator(RATE, 8.0);
        estimator.onRead(RATE, 0);
        estimator.advance(SECOND / 2, 1.0);

        long half = estimator.queuedSamples();
        assertTrue(Math.abs(half - RATE / 2) < 100,
                "half a second in, half a second should remain, got " + half);
    }
}
