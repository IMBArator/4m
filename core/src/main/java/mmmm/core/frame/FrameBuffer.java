package mmmm.core.frame;

/**
 * A growable byte buffer for reassembling frames that arrive split across reads.
 *
 * <p>Network reads land on arbitrary boundaries, so a parser almost never receives whole frames.
 * This holds the remainder between calls and compacts it once consumed, so a long-running stream
 * does not walk the buffer forwards forever.
 *
 * <p>Not thread-safe.
 */
final class FrameBuffer {

    private byte[] data;
    private int start;
    private int end;

    FrameBuffer(int initialCapacity) {
        this.data = new byte[Math.max(initialCapacity, 64)];
    }

    void append(byte[] src, int off, int len) {
        ensureRoom(len);
        System.arraycopy(src, off, data, end, len);
        end += len;
    }

    int available() {
        return end - start;
    }

    /** Byte at {@code start + index}, unsigned. Caller must have checked {@link #available()}. */
    int get(int index) {
        return data[start + index] & 0xFF;
    }

    /** Big-endian 32-bit read at {@code start + index}. */
    long getUint32BE(int index) {
        return ((long) get(index) << 24) | (get(index + 1) << 16) | (get(index + 2) << 8) | get(index + 3);
    }

    /** Little-endian 32-bit read at {@code start + index}. */
    long getUint32LE(int index) {
        return ((long) get(index + 3) << 24) | (get(index + 2) << 16) | (get(index + 1) << 8) | get(index);
    }

    /** Little-endian 64-bit read at {@code start + index}. */
    long getInt64LE(int index) {
        long v = 0;
        for (int i = 7; i >= 0; i--) {
            v = (v << 8) | get(index + i);
        }
        return v;
    }

    /** Copies {@code len} bytes from {@code start + index} into a new array. */
    byte[] copy(int index, int len) {
        byte[] out = new byte[len];
        System.arraycopy(data, start + index, out, 0, len);
        return out;
    }

    /** Whether the bytes at {@code index} match the given ASCII marker. */
    boolean matches(int index, String ascii) {
        if (available() < index + ascii.length()) {
            return false;
        }
        for (int i = 0; i < ascii.length(); i++) {
            if (get(index + i) != ascii.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    void skip(int len) {
        start += len;
        if (start == end) {
            start = 0;
            end = 0;
        }
    }

    /**
     * Drops consumed bytes to the front.
     *
     * <p>Called after each parse pass. Without it a stream running for hours would grow the buffer
     * without bound even though only a frame's worth is ever live.
     */
    void compact() {
        if (start == 0) {
            return;
        }
        int len = end - start;
        if (len > 0) {
            System.arraycopy(data, start, data, 0, len);
        }
        start = 0;
        end = len;
    }

    private void ensureRoom(int len) {
        if (end + len <= data.length) {
            return;
        }
        compact();
        if (end + len <= data.length) {
            return;
        }
        int capacity = data.length;
        while (capacity < end + len) {
            capacity *= 2;
        }
        byte[] bigger = new byte[capacity];
        System.arraycopy(data, 0, bigger, 0, end);
        data = bigger;
    }
}
