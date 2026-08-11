package mmmm.core.sync;

import java.util.Locale;

/**
 * Turns the sync quantities into the strings a readout shows.
 *
 * <h2>Why this is not three lines at the call site</h2>
 * Because it was, and one of them was wrong. {@link DriftController#rateTrim()} is a playback-rate
 * <em>multiplier</em> hovering around 1.0 — it is handed straight to {@code SoundInstance.getPitch()}
 * — not a correction around zero. Formatting it as parts per million without subtracting the 1.0
 * renders {@code +1000000ppm} on a perfectly healthy session: a number that looks like a catastrophe
 * and is a unit error.
 *
 * <p>That is the characteristic failure of a diagnostic. It is not that it breaks — it is that it
 * confidently reports something false, and then gets believed, because the whole reason anyone is
 * reading it is that they do not already know the answer. A readout used to chase a sync bug has to
 * be more trustworthy than the thing it is measuring, so the conversions live here, in
 * loader-free code, under test.
 */
public final class SyncFormat {

    private SyncFormat() {
    }

    /**
     * Microseconds as signed milliseconds, e.g. {@code +12ms}.
     *
     * <p>The sign is always shown, including on zero. Drift is a signed quantity — ahead and behind
     * are different problems — and an unsigned {@code 12ms} invites reading it as a magnitude.
     */
    public static String signedMillis(long micros) {
        long millis = micros / 1000L;
        return (millis >= 0 ? "+" : "") + millis + "ms";
    }

    /** Microseconds as seconds to one decimal, e.g. {@code 2.9s}. For buffer depths. */
    public static String seconds(long micros) {
        return String.format(Locale.ROOT, "%.1fs", micros / 1_000_000.0);
    }

    /**
     * A rate-trim multiplier as parts per million away from normal speed, e.g. {@code +38ppm}.
     *
     * <p>Takes the multiplier, not the correction: {@code 1.0} is normal speed and formats as
     * {@code +0ppm}. {@link DriftController#MAX_TRIM} is 0.001, so the honest range is ±1000 ppm and
     * anything outside that means the caller passed the wrong quantity.
     */
    public static String rateTrimPpm(double rateTrim) {
        long ppm = Math.round((rateTrim - 1.0) * 1_000_000.0);
        return (ppm >= 0 ? "+" : "") + ppm + "ppm";
    }

    /** Nanoseconds as whole milliseconds, e.g. {@code 4ms}. For round-trip times. */
    public static String millis(long nanos) {
        return (nanos / 1_000_000L) + "ms";
    }
}
