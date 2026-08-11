package mmmm.core.relay;

import mmmm.core.media.MediaFrame;
import mmmm.core.security.EgressDeniedException;
import mmmm.core.source.SourceMetadata;
import mmmm.core.source.StreamSource;
import mmmm.core.source.StreamTitleListener;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The relay against a fake origin.
 *
 * <p>These are timing tests, which is a thing to be honest about: they drive a real thread against a
 * source that delivers audio at real speed, because the behaviour under test — placing the stream
 * epoch after the origin's connect burst has drained — is defined in terms of wall time and cannot
 * be observed any other way. The tolerances are wide enough to survive a loaded machine, and the
 * configured timings are compressed so a whole session runs in about a second.
 */
class RelaySessionTest {

    private static final URI STATION = URI.create("http://example.invalid/stream");

    /** Compressed relative to {@link RelayConfig#DEFAULT}: same shape, about a second per test. */
    private static final RelayConfig FAST = new RelayConfig(
            1_000,      // presentation delay
            200,        // backlog margin
            50,         // initial backoff
            200,        // max backoff
            10_000,     // stable-before-reset
            150,        // settle quiet period
            8 * 1024 * 1024,
            8192);

    /**
     * A station that hands over a burst and then plays at real speed.
     *
     * <p>The burst is the whole reason {@link RelaySession} defers its epoch, so it has to be in the
     * fixture, not abstracted out of it.
     */
    private static final class BurstingSource implements StreamSource {
        private final byte[] data;
        private final int burstBytes;
        private int position;
        private volatile boolean closed;
        private boolean trickling;
        private long trickleStartNanos;
        private long trickleFramesSent;

        BurstingSource(int burstFrames, int trickleFrames) {
            this.data = Mp3Fixture.frames(burstFrames + trickleFrames);
            this.burstBytes = burstFrames * Mp3Fixture.FRAME_BYTES;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (closed) {
                return -1;
            }
            if (position >= data.length) {
                return -1;
            }
            int chunk;
            if (position < burstBytes) {
                chunk = Math.min(len, burstBytes - position);
            } else {
                // One frame per frame-duration: realtime, so `arrival - pts` stops falling and the
                // session can conclude the burst has drained.
                //
                // Paced against an absolute deadline, not a fixed sleep per frame. A frame is
                // 26.122 ms and Thread.sleep only takes whole milliseconds, so sleeping
                // round(26.122) = 26 ms per frame runs this station permanently ~0.12 ms/frame
                // fast. `arrival - pts` then improves on *every* frame, RelaySession keeps
                // resetting its quiet period, settle() is never reached and the session sits in
                // BUFFERING until the test times out. Whether that reproduced depended on how far
                // the host's sleep overshot 26 ms, so it passed on a loaded machine and failed on
                // one with accurate timers — eight failures here, all from that one cause.
                //
                // A deadline also cannot accumulate drift: a late frame is absorbed by the next
                // wait rather than pushing every subsequent frame back.
                chunk = Math.min(len, Mp3Fixture.FRAME_BYTES);
                if (!trickling) {
                    trickling = true;
                    trickleStartNanos = System.nanoTime();
                }
                long dueNanos = trickleStartNanos
                        + Math.round(++trickleFramesSent * Mp3Fixture.FRAME_MICROS * 1000.0);
                long waitNanos = dueNanos - System.nanoTime();
                if (waitNanos > 0) {
                    try {
                        Thread.sleep(waitNanos / 1_000_000L, (int) (waitNanos % 1_000_000L));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return -1;
                    }
                }
            }
            chunk = Math.min(chunk, data.length - position);
            System.arraycopy(data, position, b, off, chunk);
            position += chunk;
            return chunk;
        }

        @Override
        public SourceMetadata metadata() {
            return new SourceMetadata(Optional.of("Test Station"), Optional.empty(),
                    Optional.of("audio/mpeg"), 128);
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static boolean awaitUntil(BooleanSupplier condition, long timeoutMs) {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }

    /** Drains on a cadence, the way the server tick does. */
    private static boolean pumpUntil(RelaySession session, BooleanSupplier condition, long timeoutMs) {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (System.nanoTime() < deadline) {
            session.drain();
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        session.drain();
        return condition.getAsBoolean();
    }

    @Test
    void announcesTheStreamOnlyAfterTheBurstHasDrained() throws Exception {
        FakeTransport transport = new FakeTransport();
        FakeTransport.Subscriber alice = new FakeTransport.Subscriber("alice");

        RelaySession session = new RelaySession(7, STATION,
                (uri, titles) -> new BurstingSource(100, 400), FAST, transport);
        try {
            session.addSubscriber(alice);
            session.start();

            // Draining during BUFFERING must announce nothing: a client told the epoch before it is
            // known would play at the wrong position for the life of the session.
            session.drain();
            assertTrue(transport.opens().isEmpty(), "announced before settling");

            assertTrue(pumpUntil(session, () -> !transport.opens().isEmpty(), 4_000),
                    "never announced the stream; state was " + session.state());

            assertEquals(SessionState.PLAYING, session.state());
            assertEquals(1, transport.opens().size(), "a subscriber must be opened exactly once");

            FakeTransport.Opened opened = transport.opens().get(0);
            assertEquals(7, opened.sessionId());
            assertEquals("Test Station", opened.originName());
            assertEquals(1_000, opened.presentationDelayMs());
            assertEquals(1, opened.streams().size());
            assertEquals(Mp3Fixture.SAMPLE_RATE, opened.streams().get(0).sampleRate());
            assertFalse(opened.backlog().isEmpty(), "a joining client needs its window up front");
        } finally {
            session.close();
        }
    }

    /**
     * The property the whole settling dance exists to produce. If the epoch were taken from the
     * first frame it would sit a whole burst — seconds — away from this.
     */
    @Test
    void theEpochPlacesTheNewestFrameAtRoughlyNow() throws Exception {
        FakeTransport transport = new FakeTransport();
        RelaySession session = new RelaySession(1, STATION,
                (uri, titles) -> new BurstingSource(100, 400), FAST, transport);
        try {
            session.start();
            assertTrue(awaitUntil(() -> session.state() == SessionState.PLAYING, 4_000),
                    "never reached PLAYING, state was " + session.state());

            long elapsedMicros = (System.nanoTime() - session.epochNanos()) / 1000L;
            long newestPts = session.lastPtsMicros();
            long error = Math.abs(elapsedMicros - newestPts);

            assertTrue(error < 500_000,
                    "epoch is " + error / 1000 + " ms out; the newest frame should map to about now. "
                            + "elapsed=" + elapsedMicros + "us newestPts=" + newestPts + "us");

            // 2.6 s of burst was received, so an epoch naively taken at the first frame would be
            // wrong by about that much. Prove the test could actually tell the difference.
            long burstMicros = (long) (100 * Mp3Fixture.FRAME_MICROS);
            assertTrue(burstMicros > 2_000_000, "fixture no longer produces a meaningful burst");
        } finally {
            session.close();
        }
    }

    @Test
    void backlogIsTrimmedToThePresentationWindow() throws Exception {
        FakeTransport transport = new FakeTransport();
        FakeTransport.Subscriber alice = new FakeTransport.Subscriber("alice");

        RelaySession session = new RelaySession(1, STATION,
                (uri, titles) -> new BurstingSource(200, 400), FAST, transport);
        try {
            session.addSubscriber(alice);
            session.start();
            assertTrue(pumpUntil(session, () -> !transport.opens().isEmpty(), 4_000),
                    "never announced the stream");

            List<MediaFrame> backlog = transport.opens().get(0).backlog();
            long span = backlog.get(backlog.size() - 1).ptsMicros() - backlog.get(0).ptsMicros();

            // 5.2 s of burst was received but the window is 1.2 s, so the trim must have happened.
            assertTrue(span <= 1_300_000,
                    "backlog spans " + span / 1000 + " ms, window is 1200 ms");
            assertTrue(span >= 800_000,
                    "backlog spans only " + span / 1000 + " ms; a joining client would start short");
        } finally {
            session.close();
        }
    }

    /**
     * The subscriber is opened with a backlog that already contains everything buffered, so the
     * batch sent in the same drain must not repeat those frames.
     */
    @Test
    void aJoiningSubscriberDoesNotReceiveItsBacklogTwice() throws Exception {
        FakeTransport transport = new FakeTransport();
        FakeTransport.Subscriber alice = new FakeTransport.Subscriber("alice");

        RelaySession session = new RelaySession(1, STATION,
                (uri, titles) -> new BurstingSource(100, 400), FAST, transport);
        try {
            session.addSubscriber(alice);
            session.start();
            assertTrue(pumpUntil(session, () -> !transport.opens().isEmpty(), 4_000),
                    "never announced the stream");

            List<MediaFrame> backlog = transport.opens().get(0).backlog();
            long newestInBacklog = backlog.get(backlog.size() - 1).ptsMicros();

            // Let a few more frames arrive and be sent normally.
            assertTrue(pumpUntil(session, () -> transport.framesSent().size() >= 3, 2_000),
                    "no frames were streamed after the open");

            for (MediaFrame sent : transport.framesSent()) {
                assertTrue(sent.ptsMicros() > newestInBacklog,
                        "frame at " + sent.ptsMicros() + "us was already in the backlog (newest "
                                + newestInBacklog + "us) — the client would hear it twice");
            }
        } finally {
            session.close();
        }
    }

    @Test
    void titlesAreStampedWithTheTimestampTheyBecomeCurrent() throws Exception {
        FakeTransport transport = new FakeTransport();
        FakeTransport.Subscriber alice = new FakeTransport.Subscriber("alice");

        StreamTitleListener[] captured = new StreamTitleListener[1];
        RelaySession session = new RelaySession(1, STATION, (uri, titles) -> {
            captured[0] = titles;
            return new BurstingSource(100, 400);
        }, FAST, transport);

        try {
            session.addSubscriber(alice);
            session.start();
            assertTrue(pumpUntil(session, () -> !transport.opens().isEmpty(), 4_000),
                    "never announced the stream");

            assertNotNull(captured[0], "the source was never given a title listener");
            captured[0].onStreamTitle("Artist - Track");

            assertTrue(pumpUntil(session, () -> !transport.titles().isEmpty(), 2_000),
                    "the title never reached the subscriber");

            FakeTransport.Titled titled = transport.titles().get(0);
            assertEquals("Artist - Track", titled.title());
            assertTrue(titled.ptsMicros() > 0,
                    "a title sent with no timestamp would flip the display out of sync with the audio");
        } finally {
            session.close();
        }
    }

    /** Retrying an allowlist rejection would be a busy loop that could never succeed. */
    @Test
    void egressDenialFailsWithoutRetrying() throws Exception {
        FakeTransport transport = new FakeTransport();
        AtomicInteger attempts = new AtomicInteger();

        RelaySession session = new RelaySession(1, STATION, (uri, titles) -> {
            attempts.incrementAndGet();
            throw new EgressDeniedException("host not on the allowlist");
        }, FAST, transport);

        try {
            session.start();
            assertTrue(awaitUntil(() -> session.state() == SessionState.FAILED, 2_000),
                    "expected FAILED, was " + session.state());

            Thread.sleep(300);   // several backoff periods at 50 ms
            assertEquals(1, attempts.get(), "a refused destination must not be retried");
            assertNotNull(session.lastError());
        } finally {
            session.close();
        }
    }

    @Test
    void unrecognisedFormatFailsWithoutRetrying() throws Exception {
        FakeTransport transport = new FakeTransport();
        AtomicInteger attempts = new AtomicInteger();

        RelaySession session = new RelaySession(1, STATION, (uri, titles) -> {
            attempts.incrementAndGet();
            return new StreamSource() {
                private int served;

                @Override
                public int read(byte[] b, int off, int len) {
                    if (served > 0) {
                        return -1;
                    }
                    // Text: no MPEG sync word, no OggS, no ADTS.
                    byte[] junk = "this is not audio, it is an error page".getBytes(
                            java.nio.charset.StandardCharsets.US_ASCII);
                    System.arraycopy(junk, 0, b, off, junk.length);
                    served = junk.length;
                    return junk.length;
                }

                @Override
                public SourceMetadata metadata() {
                    return SourceMetadata.EMPTY;
                }

                @Override
                public void close() {
                }
            };
        }, FAST, transport);

        try {
            session.start();
            assertTrue(awaitUntil(() -> session.state() == SessionState.FAILED, 2_000),
                    "expected FAILED, was " + session.state());
            Thread.sleep(300);
            assertEquals(1, attempts.get(), "an undecodable stream must not be retried");
        } finally {
            session.close();
        }
    }

    /** A dropped origin is weather, not configuration: it must be retried indefinitely. */
    @Test
    void aDroppedOriginIsReconnected() throws Exception {
        FakeTransport transport = new FakeTransport();
        AtomicInteger attempts = new AtomicInteger();

        RelaySession session = new RelaySession(1, STATION, (uri, titles) -> {
            attempts.incrementAndGet();
            return new BurstingSource(2, 0);   // ends almost immediately
        }, FAST, transport);

        try {
            session.start();
            assertTrue(awaitUntil(() -> attempts.get() >= 3, 3_000),
                    "expected repeated reconnects, got " + attempts.get());
            assertTrue(session.state() != SessionState.FAILED,
                    "a network fault must not be permanent, state was " + session.state());
        } finally {
            session.close();
        }
    }

    @Test
    void closingStopsTheThreadAndClosesOpenStreams() throws Exception {
        FakeTransport transport = new FakeTransport();
        FakeTransport.Subscriber alice = new FakeTransport.Subscriber("alice");

        RelaySession session = new RelaySession(3, STATION,
                (uri, titles) -> new BurstingSource(100, 400), FAST, transport);
        session.addSubscriber(alice);
        session.start();
        assertTrue(pumpUntil(session, () -> !transport.opens().isEmpty(), 4_000),
                "never announced the stream");

        session.close();
        session.awaitStop(2_000);

        assertEquals(SessionState.CLOSED, session.state());
        assertEquals(List.of(3), transport.closes(), "the subscriber was not told the stream ended");
        assertEquals(0, session.subscriberCount());
    }

    @Test
    void removingASubscriberClosesOnlyItsStream() throws Exception {
        FakeTransport transport = new FakeTransport();
        FakeTransport.Subscriber alice = new FakeTransport.Subscriber("alice");
        FakeTransport.Subscriber bob = new FakeTransport.Subscriber("bob");

        RelaySession session = new RelaySession(5, STATION,
                (uri, titles) -> new BurstingSource(100, 400), FAST, transport);
        try {
            session.addSubscriber(alice);
            session.addSubscriber(bob);
            session.start();
            assertTrue(pumpUntil(session, () -> transport.opens().size() == 2, 4_000),
                    "both subscribers should have been opened");

            session.removeSubscriber(alice);

            assertEquals(List.of(5), transport.closes());
            assertEquals(1, session.subscriberCount());
        } finally {
            session.close();
        }
    }

    /** A subscriber added before the format is known must still be served once it is. */
    @Test
    void aSubscriberAddedWhileConnectingIsOpenedLater() throws Exception {
        FakeTransport transport = new FakeTransport();
        FakeTransport.Subscriber alice = new FakeTransport.Subscriber("alice");

        RelaySession session = new RelaySession(1, STATION,
                (uri, titles) -> new BurstingSource(100, 400), FAST, transport);
        try {
            session.start();
            session.addSubscriber(alice);   // races the connection, deliberately

            assertTrue(pumpUntil(session, () -> !transport.opens().isEmpty(), 4_000),
                    "a subscriber added during CONNECTING was never opened");
            assertEquals(1, transport.opens().size());
        } finally {
            session.close();
        }
    }
}
