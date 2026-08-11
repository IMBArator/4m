package mmmm.core.sync;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The readout's unit conversions.
 *
 * <p>Worth testing despite being arithmetic, because a diagnostic that lies is worse than no
 * diagnostic: it gets believed precisely when nobody knows the right answer. The rate-trim case is
 * here because it was got wrong first time.
 */
class SyncFormatTest {

    /**
     * The one that shipped wrong. A trim of 1.0 is normal speed, not a million ppm — treating the
     * multiplier as a correction renders a healthy session as a catastrophe.
     */
    @Test
    void normalPlaybackRateIsZeroPartsPerMillion() {
        assertEquals("+0ppm", SyncFormat.rateTrimPpm(1.0));
    }

    @Test
    void rateTrimIsMeasuredFromNormalSpeed() {
        assertEquals("+500ppm", SyncFormat.rateTrimPpm(1.0005));
        assertEquals("-500ppm", SyncFormat.rateTrimPpm(0.9995));
    }

    /** The controller's own ceiling should land on a round number, not something like 999 ppm. */
    @Test
    void theControllersLimitFormatsAsAThousandPartsPerMillion() {
        assertEquals("+1000ppm", SyncFormat.rateTrimPpm(1.0 + DriftController.MAX_TRIM));
        assertEquals("-1000ppm", SyncFormat.rateTrimPpm(1.0 - DriftController.MAX_TRIM));
    }

    /** Ahead and behind are different problems, so the sign is never dropped. */
    @Test
    void driftKeepsItsSign() {
        assertEquals("+12ms", SyncFormat.signedMillis(12_000));
        assertEquals("-12ms", SyncFormat.signedMillis(-12_000));
        assertEquals("+0ms", SyncFormat.signedMillis(0));
    }

    /** The deadband is 10 ms, so a readout must be able to show the difference either side of it. */
    @Test
    void driftIsLegibleAroundTheDeadband() {
        assertEquals("+9ms", SyncFormat.signedMillis(DriftController.DEADBAND_MICROS - 1000));
        assertEquals("+10ms", SyncFormat.signedMillis(DriftController.DEADBAND_MICROS));
    }

    @Test
    void bufferDepthIsSecondsToOneDecimal() {
        assertEquals("2.9s", SyncFormat.seconds(2_900_000));
        assertEquals("0.0s", SyncFormat.seconds(0));
        // Locale-independent: a comma here would be a decimal separator from the host locale
        // leaking into the readout.
        assertTrue(SyncFormat.seconds(3_500_000).contains("."));
    }

    @Test
    void roundTripIsWholeMilliseconds() {
        assertEquals("4ms", SyncFormat.millis(4_000_000));
        assertEquals("0ms", SyncFormat.millis(999_999));
    }
}
