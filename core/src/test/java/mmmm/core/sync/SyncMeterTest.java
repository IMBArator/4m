package mmmm.core.sync;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The readout's rolling window.
 *
 * <p>Arithmetic, but arithmetic with an index that wraps — and it feeds the only instrument the
 * multi-client sync work has. A window that quietly averaged in stale samples would produce a
 * plausible wrong number, which is the failure mode a diagnostic must not have.
 */
class SyncMeterTest {

    @Test
    void anEmptyMeterReportsNothingRatherThanZero() {
        SyncMeter meter = new SyncMeter(4);
        assertFalse(meter.hasSamples());
        assertEquals(0, meter.sampleCount());
        // Normal speed, not 0.0 — a trim of zero would mean playback stopped dead.
        assertEquals(1.0, meter.meanRateTrim(), 1e-9);
    }

    @Test
    void aSingleSampleIsItsOwnMeanAndHasNoSpan() {
        SyncMeter meter = new SyncMeter(4);
        meter.sample(12_000, 1.0005, 0);
        assertEquals(12_000, meter.meanDriftMicros());
        assertEquals(0, meter.driftSpanMicros());
        assertEquals(1.0005, meter.meanRateTrim(), 1e-9);
    }

    @Test
    void thePartlyFilledWindowAveragesOnlyWhatItHas() {
        SyncMeter meter = new SyncMeter(10);
        meter.sample(10_000, 1.0, 0);
        meter.sample(20_000, 1.0, 0);
        assertEquals(2, meter.sampleCount());
        // 15_000, not 3_000 — dividing by the window rather than the sample count would report a
        // fifth of the real drift while the window filled, i.e. exactly when someone is watching.
        assertEquals(15_000, meter.meanDriftMicros());
    }

    /** The wrap is the part with an off-by-one in it. */
    @Test
    void afullWindowForgetsTheOldestSample() {
        SyncMeter meter = new SyncMeter(3);
        meter.sample(1_000, 1.0, 0);
        meter.sample(2_000, 1.0, 0);
        meter.sample(3_000, 1.0, 0);
        assertEquals(2_000, meter.meanDriftMicros());

        // Pushes out the 1_000: the window is now 2_000, 3_000, 4_000.
        meter.sample(4_000, 1.0, 0);
        assertEquals(3, meter.sampleCount(), "the window must not grow past its size");
        assertEquals(3_000, meter.meanDriftMicros());
        assertEquals(2_000, meter.driftSpanMicros());
    }

    /**
     * The span is what gets compared against the hard-resync threshold, so it has to be the real
     * peak-to-peak excursion and not a smoothed version of it.
     */
    @Test
    void theSpanIsPeakToPeakAcrossZero() {
        SyncMeter meter = new SyncMeter(8);
        meter.sample(-160_000, 1.0, 0);
        meter.sample(25_000, 1.0, 0);
        meter.sample(-40_000, 1.0, 0);
        assertEquals(185_000, meter.driftSpanMicros());
        assertTrue(meter.driftSpanMicros() > 0, "a span is a magnitude and is never negative");
    }

    /**
     * The rate, not the total. A session resyncing on every tick is a different situation from one
     * that resynced twice an hour ago, and only the rate tells them apart.
     */
    @Test
    void theResyncRateIsTheChangeAcrossTheWindow() {
        SyncMeter meter = new SyncMeter(4);
        meter.sample(0, 1.0, 3060);
        meter.sample(0, 1.0, 3061);
        meter.sample(0, 1.0, 3062);
        assertEquals(2, meter.resyncsInWindow());
    }

    @Test
    void aHighTotalWithNoRecentResyncsReportsZero() {
        SyncMeter meter = new SyncMeter(4);
        meter.sample(0, 1.0, 3065);
        meter.sample(0, 1.0, 3065);
        assertEquals(0, meter.resyncsInWindow(),
                "a stable total means nothing is going wrong now, however large it is");
    }

    @Test
    void resetDiscardsTheWindow() {
        SyncMeter meter = new SyncMeter(4);
        meter.sample(50_000, 1.0009, 0);
        meter.reset();
        assertFalse(meter.hasSamples());
        assertEquals(0, meter.meanDriftMicros());
        assertEquals(1.0, meter.meanRateTrim(), 1e-9);
    }

    /** A session change must not leave the old session's samples averaging into the new one. */
    @Test
    void samplesAfterResetDoNotIncludeThePreviousWindow() {
        SyncMeter meter = new SyncMeter(4);
        meter.sample(100_000, 1.0, 0);
        meter.sample(100_000, 1.0, 0);
        meter.reset();
        meter.sample(0, 1.0, 0);
        assertEquals(0, meter.meanDriftMicros());
        assertEquals(1, meter.sampleCount());
    }
}
