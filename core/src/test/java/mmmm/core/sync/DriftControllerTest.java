package mmmm.core.sync;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The control loop from ADR-0005, exercised against a simulated clock.
 *
 * <p>Two properties matter and neither is provable by inspection: it must converge, and it must not
 * hunt. A loop that oscillates around the target is worse than none, because every swing is a
 * continuous pitch wobble rather than a single correction.
 */
class DriftControllerTest {

    @Test
    void keepsTheProportionalTermOffInsideTheDeadband() {
        DriftController controller = new DriftController();

        DriftController.Action action = controller.update(1_000_000, 1_005_000);

        assertEquals(DriftController.Action.CONTINUE, action);
        // 5 ms of drift is inaudible, and reacting proportionally to it would only cause hunting.
        // The integral term still sees the sample — that is what lets it converge on a standing
        // clock error — but one sample's contribution is negligible.
        assertEquals(1.0, controller.rateTrim(), 1e-6,
                "a single small sample must not move the rate meaningfully");
    }

    @Test
    void speedsUpWhenBehindTheClock() {
        DriftController controller = new DriftController();

        // Target ahead of actual: we are behind and must catch up.
        for (int i = 0; i < 50; i++) {
            controller.update(1_000_000, 1_100_000);
        }

        assertTrue(controller.rateTrim() > 1.0,
                "playing behind the clock must speed up, trim was " + controller.rateTrim());
    }

    @Test
    void slowsDownWhenAheadOfTheClock() {
        DriftController controller = new DriftController();

        for (int i = 0; i < 50; i++) {
            controller.update(1_100_000, 1_000_000);
        }

        assertTrue(controller.rateTrim() < 1.0,
                "playing ahead of the clock must slow down, trim was " + controller.rateTrim());
    }

    @Test
    void neverExceedsTheInaudibleTrimLimit() {
        DriftController controller = new DriftController();

        // Drive it as hard as possible without crossing into hard-resync territory.
        for (int i = 0; i < 200; i++) {
            controller.update(0, DriftController.HARD_RESYNC_MICROS - 1);
        }

        assertTrue(controller.rateTrim() <= 1.0 + DriftController.MAX_TRIM + 1e-9,
                "trim must stay within the inaudible band, was " + controller.rateTrim());
        assertTrue(controller.rateTrim() >= 1.0 - DriftController.MAX_TRIM - 1e-9);
    }

    @Test
    void hardResyncsOnAStepTooLargeToTrimAway() {
        DriftController controller = new DriftController();

        DriftController.Action action = controller.update(0, 500_000);

        assertEquals(DriftController.Action.HARD_RESYNC, action);
        assertEquals(1, controller.hardResyncCount());
        assertEquals(1.0, controller.rateTrim(), 1e-9, "trim resets; the jump does the correcting");
    }

    /**
     * A 500 ms step must produce exactly one resync, not a run of them.
     *
     * <p>Repeated resyncs would be audible as stuttering, and would mean the loop never settles.
     */
    @Test
    void aSingleStepCausesExactlyOneResync() {
        DriftController controller = new DriftController();
        SimulatedPlayback playback = new SimulatedPlayback();

        playback.advanceBy(500_000); // a stall, e.g. resuming from pause

        for (int tick = 0; tick < 200; tick++) {
            DriftController.Action action = controller.update(playback.actualPts(), playback.targetPts());
            if (action == DriftController.Action.HARD_RESYNC) {
                playback.jumpToTarget();
                controller.resetAfterResync();
            } else {
                playback.tick(controller.rateTrim());
            }
        }

        assertEquals(1, controller.hardResyncCount(),
                "one disturbance must yield one resync, not a repeating cycle");
    }

    @Test
    void convergesIntoTheDeadbandAndStaysThere() {
        DriftController controller = new DriftController();
        SimulatedPlayback playback = new SimulatedPlayback();

        // 100 ms behind: correctable by trim, but well outside the deadband.
        playback.advanceBy(-100_000);

        for (int tick = 0; tick < 5000; tick++) {
            controller.update(playback.actualPts(), playback.targetPts());
            playback.tick(controller.rateTrim());
        }

        long finalDrift = Math.abs(controller.lastDriftMicros());
        assertTrue(finalDrift < DriftController.DEADBAND_MICROS,
                "must converge inside the deadband, ended at " + finalDrift + "us");
        assertEquals(0, controller.hardResyncCount(), "trim alone should have handled 100ms");
    }

    @Test
    void correctsAPersistentHardwareClockOffset() {
        // A sound card running 50 ppm slow is entirely normal and would separate two clients by
        // ~180 ms over an hour if nothing corrected it.
        DriftController controller = new DriftController();
        SimulatedPlayback playback = new SimulatedPlayback();
        playback.setHardwareRateError(-0.00005);

        for (int tick = 0; tick < 20000; tick++) {
            controller.update(playback.actualPts(), playback.targetPts());
            playback.tick(controller.rateTrim());
        }

        assertTrue(Math.abs(controller.lastDriftMicros()) < DriftController.DEADBAND_MICROS,
                "a constant rate error must be absorbed, drift was " + controller.lastDriftMicros());
        assertEquals(0, controller.hardResyncCount());
    }

    @Test
    void doesNotHuntOnceSettled() {
        DriftController controller = new DriftController();
        SimulatedPlayback playback = new SimulatedPlayback();
        playback.advanceBy(-50_000);

        for (int tick = 0; tick < 3000; tick++) {
            controller.update(playback.actualPts(), playback.targetPts());
            playback.tick(controller.rateTrim());
        }

        // Having settled, measure how far the trim wanders over the next stretch.
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (int tick = 0; tick < 1000; tick++) {
            controller.update(playback.actualPts(), playback.targetPts());
            playback.tick(controller.rateTrim());
            min = Math.min(min, controller.rateTrim());
            max = Math.max(max, controller.rateTrim());
        }

        assertTrue(max - min < DriftController.MAX_TRIM,
                "settled trim must not swing across its whole range; span was " + (max - min));
    }

    /**
     * A minimal playback model: a clock, a position, and a hardware rate error.
     *
     * <p>One tick is 50 ms, roughly Minecraft's tick rate.
     */
    private static final class SimulatedPlayback {
        private static final long TICK_MICROS = 50_000;

        private long targetPts;
        private long actualPts;
        private double hardwareRateError;

        void tick(double rateTrim) {
            targetPts += TICK_MICROS;
            actualPts += Math.round(TICK_MICROS * rateTrim * (1.0 + hardwareRateError));
        }

        /** Positive puts playback ahead of the clock; negative puts it behind. */
        void advanceBy(long micros) {
            actualPts += micros;
        }

        void jumpToTarget() {
            actualPts = targetPts;
        }

        void setHardwareRateError(double error) {
            this.hardwareRateError = error;
        }

        long targetPts() {
            return targetPts;
        }

        long actualPts() {
            return actualPts;
        }
    }

    /**
     * The reset must not erase what was measured.
     *
     * <p>It used to, and the consequence was severe: a client hard-resyncing on every tick reported
     * a drift of zero to the health readout — i.e. perfect sync — because the reset ran between the
     * measurement and the read. The readout claimed everything was fine while playback thrashed.
     */
    @Test
    void aHardResyncKeepsTheDriftItMeasured() {
        DriftController controller = new DriftController();
        long huge = DriftController.HARD_RESYNC_MICROS * 4;

        assertEquals(DriftController.Action.HARD_RESYNC, controller.update(0, huge));
        assertEquals(huge, controller.lastDriftMicros(),
                "the observed drift is evidence and must survive the reset");
    }

    /** What the reset is actually for: the control state, which the jump has made meaningless. */
    @Test
    void aHardResyncClearsTheControlState() {
        DriftController controller = new DriftController();
        controller.update(0, DriftController.HARD_RESYNC_MICROS * 4);
        assertEquals(1.0, controller.rateTrim(), 1e-9);
        assertEquals(0.0, controller.standingCorrection(), 1e-9);
    }
}
