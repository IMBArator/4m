package mmmm.core.relay;

import mmmm.core.media.MediaFrame;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

/**
 * A rolling window of the most recent frames, so a client joining mid-stream starts instantly.
 *
 * <p>Without this, a joining client would receive its first frame at the live edge and then have to
 * wait out the whole presentation delay in silence before that frame was due to be rendered. The
 * backlog hands over the window {@code [now - D, now]} up front, so playback begins at once and is
 * already in sync with everyone else (ADR-0005, master plan §4.4).
 *
 * <p>Trimming is by presentation timestamp rather than by frame count, because frame durations
 * differ between codecs and even between frames — MPEG-1 Layer III carries 1152 samples, MPEG-2 and
 * 2.5 carry 576, and a station may switch. A byte cap sits on top so a stream whose timestamps are
 * nonsense cannot grow the window without bound.
 *
 * <p>Not thread-safe; {@link RelaySession} owns one and guards it with the session lock.
 */
public final class FrameBacklog {

    private final long windowMicros;
    private final int maxBytes;

    private final Deque<MediaFrame> frames = new ArrayDeque<>();
    private long bytes;

    /**
     * @param windowMicros how much history to keep, in microseconds
     * @param maxBytes     hard ceiling regardless of timestamps
     */
    public FrameBacklog(long windowMicros, int maxBytes) {
        if (windowMicros <= 0) {
            throw new IllegalArgumentException("windowMicros must be positive, was " + windowMicros);
        }
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive, was " + maxBytes);
        }
        this.windowMicros = windowMicros;
        this.maxBytes = maxBytes;
    }

    /** Appends a frame and drops whatever has fallen out of the window behind it. */
    public void add(MediaFrame frame) {
        frames.addLast(frame);
        bytes += frame.size();

        long newest = frame.ptsMicros();
        while (frames.size() > 1) {
            MediaFrame oldest = frames.peekFirst();
            boolean outOfWindow = newest - oldest.ptsMicros() > windowMicros;
            if (!outOfWindow && bytes <= maxBytes) {
                break;
            }
            frames.removeFirst();
            bytes -= oldest.size();
        }
    }

    /**
     * The current window, oldest first.
     *
     * <p>Starts at a keyframe. For audio every frame is a keyframe so this costs one comparison; for
     * video it is load-bearing, because a decoder handed a window starting mid-GOP produces garbage
     * until the next keyframe arrives.
     */
    public List<MediaFrame> snapshot() {
        List<MediaFrame> out = new ArrayList<>(frames.size());
        Iterator<MediaFrame> it = frames.iterator();
        boolean started = false;
        while (it.hasNext()) {
            MediaFrame frame = it.next();
            if (!started) {
                if (!frame.keyframe()) {
                    continue;
                }
                started = true;
            }
            out.add(frame);
        }
        return out;
    }

    /** Timestamp of the oldest retained frame, or -1 when empty. */
    public long oldestPtsMicros() {
        MediaFrame oldest = frames.peekFirst();
        return oldest == null ? -1 : oldest.ptsMicros();
    }

    /** Timestamp of the newest retained frame, or -1 when empty. */
    public long newestPtsMicros() {
        MediaFrame newest = frames.peekLast();
        return newest == null ? -1 : newest.ptsMicros();
    }

    public int frameCount() {
        return frames.size();
    }

    public long byteCount() {
        return bytes;
    }

    /** Drops everything. Used when a reconnect restarts the timeline. */
    public void clear() {
        frames.clear();
        bytes = 0;
    }
}
