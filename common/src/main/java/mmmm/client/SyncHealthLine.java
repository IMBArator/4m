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
        StringBuilder line = new StringBuilder();
        line.append("drift ").append(SyncFormat.signedMillis(meter.meanDriftMicros()));
        line.append(" ±").append(SyncFormat.millis(meter.driftSpanMicros() * 1000L / 2));
        line.append(" · buf ").append(SyncFormat.seconds(session.bufferedMicros()));
        line.append(" · trim ").append(SyncFormat.rateTrimPpm(meter.meanRateTrim()));
        line.append(" · rtt ").append(SyncFormat.millis(clock.bestRoundTripNanos()));

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
}
