package mmmm.core.audio;

/**
 * A bounded PCM ring, written by the decode thread and drained by the audio thread.
 *
 * <h2>Underrun returns silence, never a short read</h2>
 * This is the single most important property here, and it is not an obvious one.
 * Minecraft's {@code Channel.updateStream()} treats a short or empty {@code read()} as
 * end-of-stream and stops the sound permanently. Underruns are routine on live radio — every
 * network hiccup is one. If an underrun surfaced as a short read, the first hiccup of the evening
 * would silently end playback with no error anywhere.
 *
 * <p>So {@link #read} always fills the requested length, padding with silence, and reports how much
 * of that was real audio through {@link #lastReadWasUnderrun()} for the health readout.
 *
 * <h2>Overrun drops the oldest</h2>
 * The writer never blocks. For live radio, stale audio has no value: if the reader has stalled,
 * the right thing is to lose the backlog and stay current rather than accumulate a delay that must
 * later be resynced away.
 *
 * <p>Thread-safe for exactly one reader and one writer.
 */
public final class PcmRingBuffer {

    private final byte[] data;
    private final Object lock = new Object();

    private int readPos;
    private int writePos;
    private int stored;

    private long totalWritten;
    private long totalDropped;
    private long totalSilenceInserted;
    private volatile boolean lastReadUnderrun;

    /** @param capacityBytes ring size; roughly {@code seconds * sampleRate * channels * 2} */
    public PcmRingBuffer(int capacityBytes) {
        if (capacityBytes <= 0) {
            throw new IllegalArgumentException("capacityBytes must be positive");
        }
        this.data = new byte[capacityBytes];
    }

    /**
     * Writes PCM, discarding the oldest content if the ring is full.
     *
     * <p>Never blocks and never rejects: a decode thread that had to wait on the audio thread would
     * stall the network read behind it.
     *
     * @return bytes of previously buffered audio discarded to make room
     */
    public int write(byte[] src, int off, int len) {
        if (len <= 0) {
            return 0;
        }
        synchronized (lock) {
            int dropped = 0;

            // A write larger than the whole ring can only keep its tail.
            if (len >= data.length) {
                off += len - data.length;
                len = data.length;
            }
            int overflow = stored + len - data.length;
            if (overflow > 0) {
                readPos = (readPos + overflow) % data.length;
                stored -= overflow;
                dropped = overflow;
                totalDropped += overflow;
            }

            int firstChunk = Math.min(len, data.length - writePos);
            System.arraycopy(src, off, data, writePos, firstChunk);
            int remainder = len - firstChunk;
            if (remainder > 0) {
                System.arraycopy(src, off + firstChunk, data, 0, remainder);
            }
            writePos = (writePos + len) % data.length;
            stored += len;
            totalWritten += len;
            return dropped;
        }
    }

    /**
     * Fills {@code dest} completely, padding with silence if the ring runs dry.
     *
     * @return bytes of real audio supplied; any shortfall was silence
     */
    public int read(byte[] dest, int off, int len) {
        if (len <= 0) {
            return 0;
        }
        synchronized (lock) {
            int available = Math.min(len, stored);

            if (available > 0) {
                int firstChunk = Math.min(available, data.length - readPos);
                System.arraycopy(data, readPos, dest, off, firstChunk);
                int remainder = available - firstChunk;
                if (remainder > 0) {
                    System.arraycopy(data, 0, dest, off + firstChunk, remainder);
                }
                readPos = (readPos + available) % data.length;
                stored -= available;
            }

            int shortfall = len - available;
            if (shortfall > 0) {
                // Signed 16-bit PCM silence is zero, so a zero fill is literally silent.
                java.util.Arrays.fill(dest, off + available, off + len, (byte) 0);
                totalSilenceInserted += shortfall;
            }
            lastReadUnderrun = shortfall > 0;
            return available;
        }
    }

    /** Discards everything buffered. Used by the hard-resync path. */
    public void clear() {
        synchronized (lock) {
            readPos = 0;
            writePos = 0;
            stored = 0;
        }
    }

    /**
     * Drops all but the newest {@code keepBytes}.
     *
     * <p>This is the resume-from-pause path: the game paused, frames kept arriving, and the player
     * must come back to live audio rather than to whatever was buffered minutes ago.
     *
     * @return bytes discarded
     */
    public int fastForwardTo(int keepBytes) {
        synchronized (lock) {
            if (keepBytes >= stored) {
                return 0;
            }
            int drop = stored - Math.max(keepBytes, 0);
            readPos = (readPos + drop) % data.length;
            stored -= drop;
            totalDropped += drop;
            return drop;
        }
    }

    public int available() {
        synchronized (lock) {
            return stored;
        }
    }

    public int capacity() {
        return data.length;
    }

    /** Whether the most recent read had to insert silence. Drives the health readout. */
    public boolean lastReadWasUnderrun() {
        return lastReadUnderrun;
    }

    public long totalWritten() {
        synchronized (lock) {
            return totalWritten;
        }
    }

    /** Bytes discarded to overrun or fast-forward. Steady growth means the reader is too slow. */
    public long totalDropped() {
        synchronized (lock) {
            return totalDropped;
        }
    }

    /** Silence inserted on underrun. Steady growth means the writer cannot keep up. */
    public long totalSilenceInserted() {
        synchronized (lock) {
            return totalSilenceInserted;
        }
    }
}
