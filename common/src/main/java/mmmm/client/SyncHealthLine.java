package mmmm.client;

import mmmm.core.sync.ClockFilter;
import mmmm.core.sync.SyncFormat;
import mmmm.core.sync.SyncMeter;

/**
 * The one-line sync-health readout, built once and shown in two places.
 *
 * <p>The control panel renders it and the debug log writes it, and they must agree — a log that says
 * something different from the screen is a second thing to distrust while diagnosing the first. So
 * the line is built here and neither caller formats anything.
 *
 * <pre>
 *   drift -68ms ±92ms · buf 3.0s · trim -210ppm · rtt 4ms · resync 20/s
 * </pre>
 *
 * <p>The figures come from {@link ClientMediaSession}'s own {@link SyncMeter}, which is sampled once
 * per drift-loop step. Deliberately not sampled by the caller: the screen only exists while it is
 * open, so a screen-owned window would be empty for its first second and would collect nothing at all
 * for the log.
 */
public final class SyncHealthLine {

    private SyncHealthLine() {
    }

    /**
     * @return the line, or null when there is nothing meaningful to say yet
     */
    public static String of(ClientMediaSession session, ClockFilter clock) {
        if (session == null) {
            return null;
        }
        if (!clock.isConverged()) {
            // Before convergence the drift figure is not measuring anything — playback is being held
            // deliberately — so showing it invites chasing a number that is not yet real.
            return "clock: syncing (" + clock.sampleCount() + " samples)";
        }
        if (!session.hasAudio()) {
            return "buffering: " + SyncFormat.seconds(session.bufferedMicros())
                    + " of " + (session.presentationDelayMs() / 1000.0) + "s";
        }

        SyncMeter meter = session.syncMeter();
        if (!meter.hasSamples()) {
            // The drift loop has not run for this session yet, so there is nothing measured. Saying
            // "+0ms" here would be the readout's third way of reporting perfect sync when it simply
            // has no data — the failure this class exists to avoid.
            return "starting: " + SyncFormat.seconds(session.bufferedMicros()) + " buffered";
        }

        // Every field is padded to a fixed width. The line is centred, so a field that gains a
        // character re-centres the whole line and slides every digit sideways; a constant length
        // keeps each number in the same place, which is the difference between a readout that can
        // be read at a glance and one that has to be screenshotted.
        StringBuilder line = new StringBuilder();
        line.append("drift ").append(pad(SyncFormat.signedMillis(meter.meanDriftMicros()), 7));
        line.append(" ±").append(pad(SyncFormat.millis(meter.driftSpanMicros() * 1000L / 2), 6));
        line.append(" · buf ").append(pad(SyncFormat.seconds(session.bufferedMicros()), 5));
        line.append(" · trim ").append(pad(SyncFormat.rateTrimPpm(meter.meanRateTrim()), 8));
        line.append(" · rtt ").append(pad(SyncFormat.millis(clock.bestRoundTripNanos()), 5));

        if (session.underrunning()) {
            line.append(" · UNDERRUN");
        }

        // The rate, not the total. A total says something went wrong at some point; a rate says it is
        // going wrong right now, and tells you the drift and trim beside it describe a controller
        // being reset faster than it can act.
        long resyncRate = meter.resyncsInWindow();
        if (resyncRate > 0) {
            line.append(" · resync ").append(resyncRate).append("/s");
        } else if (session.hardResyncCount() > 0) {
            line.append(" · resync ").append(session.hardResyncCount()).append(" total");
        }
        if (session.framesDroppedInbound() > 0) {
            line.append(" · dropped ").append(session.framesDroppedInbound());
        }
        return line.toString();
    }

    /** Right-aligns to a fixed width, so the line's length does not change as values do. */
    private static String pad(String value, int width) {
        if (value.length() >= width) {
            return value;
        }
        return " ".repeat(width - value.length()) + value;
    }
}
