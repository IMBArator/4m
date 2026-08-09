package mmmm.core.audio;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PcmRingBufferTest {

    @Test
    void readsBackWhatWasWritten() {
        PcmRingBuffer ring = new PcmRingBuffer(1024);
        byte[] written = {1, 2, 3, 4, 5};

        ring.write(written, 0, written.length);
        byte[] read = new byte[5];
        int real = ring.read(read, 0, 5);

        assertEquals(5, real);
        assertArrayEquals(written, read);
        assertFalse(ring.lastReadWasUnderrun());
    }

    /**
     * The property the whole class exists for.
     *
     * <p>Minecraft's {@code Channel.updateStream()} treats a short read as end-of-stream and stops
     * the sound for good. Underruns are routine on live radio, so a short read here would mean the
     * first network hiccup of the evening silently ends playback.
     */
    @Test
    void underrunFillsWithSilenceRatherThanReturningShort() {
        PcmRingBuffer ring = new PcmRingBuffer(1024);
        ring.write(new byte[]{9, 9, 9}, 0, 3);

        byte[] dest = new byte[10];
        java.util.Arrays.fill(dest, (byte) 0x7F);
        int real = ring.read(dest, 0, 10);

        assertEquals(3, real, "reports how much was real audio");
        assertArrayEquals(new byte[]{9, 9, 9, 0, 0, 0, 0, 0, 0, 0}, dest,
                "the whole buffer must be filled, with silence where audio ran out");
        assertTrue(ring.lastReadWasUnderrun());
        assertEquals(7, ring.totalSilenceInserted());
    }

    @Test
    void completeUnderrunStillFillsTheBuffer() {
        PcmRingBuffer ring = new PcmRingBuffer(1024);

        byte[] dest = new byte[8];
        java.util.Arrays.fill(dest, (byte) 0x55);
        int real = ring.read(dest, 0, 8);

        assertEquals(0, real);
        assertArrayEquals(new byte[8], dest, "an empty ring yields silence, not a zero-length read");
    }

    @Test
    void overrunDropsTheOldestAudio() {
        // For live radio, stale audio is worthless: if the reader stalled, staying current beats
        // preserving a backlog that would only have to be resynced away.
        PcmRingBuffer ring = new PcmRingBuffer(8);

        ring.write(new byte[]{1, 2, 3, 4, 5, 6}, 0, 6);
        int dropped = ring.write(new byte[]{7, 8, 9, 10}, 0, 4);

        assertEquals(2, dropped);
        byte[] dest = new byte[8];
        ring.read(dest, 0, 8);
        assertArrayEquals(new byte[]{3, 4, 5, 6, 7, 8, 9, 10}, dest);
        assertEquals(2, ring.totalDropped());
    }

    @Test
    void aWriteLargerThanTheRingKeepsOnlyItsTail() {
        PcmRingBuffer ring = new PcmRingBuffer(4);

        ring.write(new byte[]{1, 2, 3, 4, 5, 6, 7, 8}, 0, 8);

        byte[] dest = new byte[4];
        ring.read(dest, 0, 4);
        assertArrayEquals(new byte[]{5, 6, 7, 8}, dest, "the newest audio is the audio worth keeping");
    }

    @Test
    void wrapsAroundCorrectly() {
        PcmRingBuffer ring = new PcmRingBuffer(10);
        byte[] scratch = new byte[6];

        // Push the write cursor most of the way round, then straddle the wrap point.
        ring.write(new byte[]{1, 2, 3, 4, 5, 6, 7}, 0, 7);
        ring.read(scratch, 0, 6);
        ring.write(new byte[]{8, 9, 10, 11, 12}, 0, 5);

        byte[] dest = new byte[6];
        assertEquals(6, ring.read(dest, 0, 6));
        assertArrayEquals(new byte[]{7, 8, 9, 10, 11, 12}, dest);
    }

    /**
     * Resuming from an ESC pause: frames kept arriving while the channel was stopped, and the
     * player must come back to live audio rather than to minutes-old buffered content.
     */
    @Test
    void fastForwardKeepsOnlyTheNewestAudio() {
        PcmRingBuffer ring = new PcmRingBuffer(100);
        ring.write(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10}, 0, 10);

        int dropped = ring.fastForwardTo(3);

        assertEquals(7, dropped);
        assertEquals(3, ring.available());
        byte[] dest = new byte[3];
        ring.read(dest, 0, 3);
        assertArrayEquals(new byte[]{8, 9, 10}, dest);
    }

    @Test
    void fastForwardIsANoOpWhenAlreadyShortEnough() {
        PcmRingBuffer ring = new PcmRingBuffer(100);
        ring.write(new byte[]{1, 2, 3}, 0, 3);

        assertEquals(0, ring.fastForwardTo(10));
        assertEquals(3, ring.available());
    }

    @Test
    void clearDiscardsEverything() {
        PcmRingBuffer ring = new PcmRingBuffer(64);
        ring.write(new byte[32], 0, 32);

        ring.clear();

        assertEquals(0, ring.available());
    }

    /**
     * One writer and one reader is the real configuration: decode thread in, audio thread out.
     * A torn read here would be an audible glitch that no amount of staring at the code would find.
     */
    @Test
    void survivesConcurrentWriterAndReader() throws Exception {
        final int chunk = 512;
        final int rounds = 2000;
        PcmRingBuffer ring = new PcmRingBuffer(8192);

        AtomicBoolean corrupted = new AtomicBoolean(false);
        AtomicLong realBytesRead = new AtomicLong();
        CountDownLatch done = new CountDownLatch(2);

        Thread writer = new Thread(() -> {
            try {
                byte[] buf = new byte[chunk];
                for (int round = 0; round < rounds; round++) {
                    java.util.Arrays.fill(buf, (byte) (round % 127 + 1));
                    ring.write(buf, 0, chunk);
                }
            } finally {
                done.countDown();
            }
        }, "test-writer");

        Thread reader = new Thread(() -> {
            try {
                byte[] buf = new byte[chunk];
                for (int round = 0; round < rounds; round++) {
                    int real = ring.read(buf, 0, chunk);
                    realBytesRead.addAndGet(real);
                    // Every byte must be either silence or a value the writer actually wrote;
                    // anything else means a torn copy across the wrap point.
                    for (int i = 0; i < real; i++) {
                        int value = buf[i] & 0xFF;
                        if (value != 0 && (value < 1 || value > 127)) {
                            corrupted.set(true);
                        }
                    }
                }
            } finally {
                done.countDown();
            }
        }, "test-reader");

        writer.start();
        reader.start();
        assertTrue(done.await(30, TimeUnit.SECONDS), "threads should finish promptly");

        assertFalse(corrupted.get(), "no torn reads across the wrap point");
        assertTrue(realBytesRead.get() > 0, "the reader should have seen real audio");
    }
}
