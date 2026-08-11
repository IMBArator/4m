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

        advanceOver(estimator, 0, SECOND, 1.0);
        assertEquals(3L * RATE, estimator.queuedSamples(), RATE / 100);
        advanceOver(estimator, SECOND, SECOND, 1.0);
        assertEquals(2L * RATE, estimator.queuedSamples(), RATE / 100);
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

        advanceOver(nominal, 0, SECOND, 1.0);
        advanceOver(fast, 0, SECOND, 1.001);

        assertTrue(fast.queuedSamples() < nominal.queuedSamples(),
                "a faster rate must consume more, or the drift loop's feedback is reversed");
    }

    @Test
    void aSlowerPlaybackRateDrainsTheQueueSlower() {
        OutputQueueEstimator nominal = new OutputQueueEstimator(RATE, 8.0);
        OutputQueueEstimator slow = new OutputQueueEstimator(RATE, 8.0);
        nominal.onRead(4L * RATE, 0);
        slow.onRead(4L * RATE, 0);

        advanceOver(nominal, 0, SECOND, 1.0);
        advanceOver(slow, 0, SECOND, 0.999);

        assertTrue(slow.queuedSamples() > nominal.queuedSamples());
    }

    /** The trim changes over time, so history must not be rewritten with the current value. */
    @Test
    void theRateIsIntegratedNotAppliedToTheWholeHistory() {
        OutputQueueEstimator estimator = new OutputQueueEstimator(RATE, 8.0);
        estimator.onRead(8L * RATE, 0);

        advanceOver(estimator, 0, SECOND, 1.0);
        advanceOver(estimator, SECOND, SECOND, 1.0);
        // Only this last second is played fast; the two before it stay at nominal.
        advanceOver(estimator, 2 * SECOND, SECOND, 2.0);

        // 1 + 1 + 2 = 4 seconds played, of 8 handed over.
        assertEquals(4L * RATE, estimator.queuedSamples(), RATE / 100);
    }

    /** Steady state: read a second's worth every second, and the queue depth holds. */
    @Test
    void refillingAtRealTimeHoldsTheQueueSteady() {
        OutputQueueEstimator estimator = new OutputQueueEstimator(RATE, 8.0);
        estimator.onRead(4L * RATE, 0);

        for (int second = 1; second <= 10; second++) {
            advanceOver(estimator, (second - 1) * SECOND, SECOND, 1.0);
            estimator.onRead(RATE, second * SECOND);
            assertEquals(4L * RATE, estimator.queuedSamples(), RATE / 100,
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
        advanceOver(estimator, 0, 5 * SECOND, 1.0);
        assertEquals(0, estimator.queuedSamples());
    }

    /**
     * The ESC-pause case, and the one that actually bit. Nothing played during the pause, so nothing
     * may be credited to it — and the damage from getting this wrong is permanent, because the
     * played estimate keeps the excess for the rest of the session.
     */
    @Test
    void aPauseIsNotCountedAsPlayback() {
        OutputQueueEstimator estimator = new OutputQueueEstimator(RATE, 8.0);
        estimator.onRead(4L * RATE, 0);

        // A minute of ESC menu arrives as one enormous step once the client ticks again.
        estimator.advance(60 * SECOND, 1.0);

        assertEquals(4L * RATE, estimator.queuedSamples(),
                "the sound system was paused too, so its queue is untouched");
    }

    /** And the estimate keeps working afterwards rather than being poisoned by the gap. */
    @Test
    void playbackResumesNormallyAfterAPause() {
        OutputQueueEstimator estimator = new OutputQueueEstimator(RATE, 8.0);
        estimator.onRead(4L * RATE, 0);
        estimator.advance(60 * SECOND, 1.0);

        advanceOver(estimator, 60 * SECOND, SECOND / 10, 1.0);
        assertEquals(3.9 * RATE, estimator.queuedSamples(), RATE / 50.0,
                "a tenth of a second after resuming, a tenth of a second has played");
    }

    /** A stutter is real playback and must still be counted, or the cap becomes a slow leak. */
    @Test
    void anOrdinaryStutterIsStillCounted() {
        OutputQueueEstimator estimator = new OutputQueueEstimator(RATE, 8.0);
        estimator.onRead(4L * RATE, 0);
        estimator.advance(SECOND / 5, 1.0);
        assertEquals(3.8 * RATE, estimator.queuedSamples(), RATE / 50.0);
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
        advanceOver(estimator, 0, SECOND / 2, 1.0);

        long half = estimator.queuedSamples();
        assertTrue(Math.abs(half - RATE / 2) < 100,
                "half a second in, half a second should remain, got " + half);
    }

    /**
     * Advances in 50 ms steps, the way the drift loop actually does at 20 Hz.
     *
     * <p>The tests used to step a whole second at a time, which is not a thing that happens and which
     * the pause cap now correctly refuses to count. Simulating the real cadence keeps them honest
     * about what they are testing.
     *
     * @return the timestamp reached
     */
    private static long advanceOver(OutputQueueEstimator estimator, long fromNanos,
                                    long durationNanos, double rate) {
        long step = SECOND / 20;
        long now = fromNanos;
        for (long elapsed = 0; elapsed < durationNanos; elapsed += step) {
            now = fromNanos + Math.min(elapsed + step, durationNanos);
            estimator.advance(now, rate);
        }
        return now;
    }
}
