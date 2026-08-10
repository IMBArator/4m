package mmmm.core.relay;

import mmmm.core.media.MediaFrame;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameBacklogTest {

    private static final int PAYLOAD = 417;

    private static MediaFrame frame(long ptsMicros) {
        return new MediaFrame(0, ptsMicros, true, new byte[PAYLOAD]);
    }

    private static MediaFrame frame(long ptsMicros, boolean keyframe) {
        return new MediaFrame(0, ptsMicros, keyframe, new byte[PAYLOAD]);
    }

    @Test
    void keepsOnlyTheRequestedWindow() {
        FrameBacklog backlog = new FrameBacklog(1_000_000, 1 << 20);

        // Three seconds of frames, one every 100 ms, into a one-second window.
        for (int i = 0; i <= 30; i++) {
            backlog.add(frame(i * 100_000L));
        }

        assertEquals(3_000_000, backlog.newestPtsMicros());
        assertTrue(backlog.oldestPtsMicros() >= 2_000_000,
                "anything older than the window must have been dropped, oldest was "
                        + backlog.oldestPtsMicros());
        assertEquals(11, backlog.frameCount());
    }

    /**
     * The window is what a joining client is handed, so an empty one means silence until the next
     * frame arrives. One frame is always better than none.
     */
    @Test
    void neverEmptiesItselfCompletely() {
        FrameBacklog backlog = new FrameBacklog(1_000, 1 << 20);

        backlog.add(frame(0));
        backlog.add(frame(60_000_000));

        assertEquals(1, backlog.frameCount());
        assertEquals(60_000_000, backlog.oldestPtsMicros());
    }

    /**
     * A station whose timestamps do not advance would otherwise grow the window without bound —
     * timestamps come from the origin, so they are untrusted input.
     */
    @Test
    void byteCapBoundsAStreamWithBrokenTimestamps() {
        FrameBacklog backlog = new FrameBacklog(60_000_000, 10 * PAYLOAD);

        for (int i = 0; i < 100; i++) {
            backlog.add(frame(0));
        }

        assertTrue(backlog.byteCount() <= 10L * PAYLOAD,
                "byte cap ignored, held " + backlog.byteCount());
    }

    /**
     * Audio frames are all keyframes so this is inert today. It is here because video is planned:
     * a window that starts mid-GOP decodes to garbage until the next keyframe.
     */
    @Test
    void snapshotStartsAtAKeyframe() {
        FrameBacklog backlog = new FrameBacklog(10_000_000, 1 << 20);

        backlog.add(frame(0, false));
        backlog.add(frame(100_000, false));
        backlog.add(frame(200_000, true));
        backlog.add(frame(300_000, false));

        List<MediaFrame> snapshot = backlog.snapshot();

        assertEquals(2, snapshot.size());
        assertEquals(200_000, snapshot.get(0).ptsMicros());
        assertTrue(snapshot.get(0).keyframe());
    }

    @Test
    void emptyBacklogReportsNoTimestamps() {
        FrameBacklog backlog = new FrameBacklog(1_000_000, 1 << 20);

        assertEquals(-1, backlog.oldestPtsMicros());
        assertEquals(-1, backlog.newestPtsMicros());
        assertTrue(backlog.snapshot().isEmpty());
    }

    @Test
    void rejectsNonsensicalBounds() {
        assertThrows(IllegalArgumentException.class, () -> new FrameBacklog(0, 1024));
        assertThrows(IllegalArgumentException.class, () -> new FrameBacklog(1000, 0));
    }
}
