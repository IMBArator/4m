package mmmm.core.media;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The timeline is what sync rests on, so these tests are deliberately paranoid about arithmetic.
 */
class TimelineTest {

    @Test
    void convertsWholeSecondsExactly() {
        assertEquals(0, Timeline.toMicros(0, 44100));
        assertEquals(1_000_000, Timeline.toMicros(44100, 44100));
        assertEquals(60_000_000, Timeline.toMicros(44100 * 60, 44100));
    }

    @Test
    void roundTripsThroughSamples() {
        for (int rate : new int[]{44100, 48000, 22050, 8000}) {
            for (long seconds : new long[]{1, 60, 3600}) {
                long samples = rate * seconds;
                assertEquals(samples, Timeline.toSamples(Timeline.toMicros(samples, rate), rate),
                        "round trip at " + rate + "Hz over " + seconds + "s");
            }
        }
    }

    /**
     * The regression this whole class exists to prevent.
     *
     * <p>An MPEG-1 frame at 44.1 kHz is 1152/44100 s = 26122.448... µs. Rounding per frame and
     * summing loses ~0.45 µs each time. Over an hour that is ~62 ms of one-directional creep, which
     * in-game looks exactly like a clock bug and is not one.
     */
    @Test
    void doesNotAccumulateRoundingErrorOverAnHour() {
        final int rate = 44100;
        final int samplesPerFrame = 1152;
        final long framesPerHour = (long) rate * 3600 / samplesPerFrame;

        Timeline timeline = new Timeline(rate);
        for (long i = 0; i < framesPerHour; i++) {
            timeline.emit(samplesPerFrame);
        }

        long exact = Timeline.toMicros(framesPerHour * samplesPerFrame, rate);
        assertEquals(exact, timeline.currentMicros(),
                "timeline must equal the exact conversion of its total sample count");

        // Demonstrate what the naive approach would have cost, so the test documents the stakes.
        long naivePerFrame = 1_000_000L * samplesPerFrame / rate;
        long naiveTotal = naivePerFrame * framesPerHour;
        long naiveError = Math.abs(exact - naiveTotal);
        assertTrue(naiveError > 50_000,
                "expected accumulating deltas to drift >50ms per hour, measured " + naiveError + "us");
    }

    @Test
    void survivesSampleCountsThatWouldOverflowNaiveMultiplication() {
        // total * 1_000_000 overflows a long past ~9.2e12 samples, about 6.6 years at 44.1 kHz.
        long samples = 44100L * 60 * 60 * 24 * 365 * 10; // ten years
        long micros = Timeline.toMicros(samples, 44100);
        assertTrue(micros > 0, "ten years of samples must not overflow to a negative timestamp");
        assertEquals(samples, Timeline.toSamples(micros, 44100));
    }

    @Test
    void emitReturnsStartOfFrameThenAdvances() {
        Timeline timeline = new Timeline(48000);

        assertEquals(0, timeline.emit(48000), "first frame starts at zero");
        assertEquals(1_000_000, timeline.emit(48000), "second frame starts one second in");
        assertEquals(2_000_000, timeline.currentMicros());
        assertEquals(96000, timeline.totalSamples());
    }

    @Test
    void seekToSampleOverridesTheRunningTotal() {
        Timeline timeline = new Timeline(44100);
        timeline.emit(1152);

        timeline.seekToSample(44100);

        assertEquals(1_000_000, timeline.currentMicros());
        assertEquals(44100, timeline.totalSamples());
    }

    @Test
    void rejectsNonsenseConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new Timeline(0));
        assertThrows(IllegalArgumentException.class, () -> new Timeline(-44100));
        assertThrows(IllegalArgumentException.class, () -> new Timeline(44100).emit(-1));
        assertThrows(IllegalArgumentException.class, () -> new Timeline(44100).seekToSample(-1));
    }
}
