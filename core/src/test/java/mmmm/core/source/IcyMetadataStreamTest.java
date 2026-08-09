package mmmm.core.source;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Metadata de-interleaving, tested byte-exactly.
 *
 * <p>The failure mode of getting this wrong is a periodic click in the audio and a corrupted frame
 * every {@code metaint} bytes, so these tests assert the audio comes out identical to what went in,
 * not merely that it is approximately right.
 */
class IcyMetadataStreamTest {

    private final List<String> titles = new ArrayList<>();

    /** Builds a response body with metadata blocks interleaved at the given interval. */
    private static byte[] interleave(byte[] audio, int metaInt, String... metadataBlocks) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int blockIndex = 0;
        int pos = 0;

        while (pos < audio.length) {
            int chunk = Math.min(metaInt, audio.length - pos);
            out.write(audio, pos, chunk);
            pos += chunk;

            if (chunk == metaInt) {
                if (blockIndex < metadataBlocks.length) {
                    byte[] meta = metadataBlocks[blockIndex++].getBytes(StandardCharsets.UTF_8);
                    int units = (meta.length + 15) / 16;
                    out.write(units);
                    out.write(meta, 0, meta.length);
                    // Pad to the 16-byte unit boundary, as the protocol requires.
                    for (int i = meta.length; i < units * 16; i++) {
                        out.write(0);
                    }
                } else {
                    out.write(0); // no metadata this interval
                }
            }
        }
        return out.toByteArray();
    }

    private byte[] readAll(IcyMetadataStream stream, int chunkSize) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[chunkSize];
        int n;
        while ((n = stream.read(buf, 0, buf.length)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static byte[] countingAudio(int length) {
        byte[] audio = new byte[length];
        for (int i = 0; i < length; i++) {
            audio[i] = (byte) (i % 251); // prime stride, so any misalignment shows up
        }
        return audio;
    }

    @Test
    void stripsMetadataAndPreservesAudioExactly() throws IOException {
        byte[] audio = countingAudio(400);
        byte[] wire = interleave(audio, 100, "StreamTitle='First';", "StreamTitle='Second';");

        IcyMetadataStream stream = new IcyMetadataStream(
                new ByteArrayInputStream(wire), 100, titles::add);

        assertArrayEquals(audio, readAll(stream, 64));
        assertEquals(List.of("First", "Second"), titles);
    }

    @Test
    void survivesReadsThatStraddleMetadataBoundaries() throws IOException {
        byte[] audio = countingAudio(1000);
        byte[] wire = interleave(audio, 100, "StreamTitle='Track';");

        // A read size that is coprime with the interval guarantees every read crosses a boundary
        // at some point.
        IcyMetadataStream stream = new IcyMetadataStream(
                new ByteArrayInputStream(wire), 100, titles::add);

        assertArrayEquals(audio, readAll(stream, 37));
    }

    @Test
    void handlesSingleByteReads() throws IOException {
        byte[] audio = countingAudio(250);
        byte[] wire = interleave(audio, 100, "StreamTitle='X';");

        IcyMetadataStream stream = new IcyMetadataStream(
                new ByteArrayInputStream(wire), 100, titles::add);

        assertArrayEquals(audio, readAll(stream, 1));
        assertEquals(List.of("X"), titles);
    }

    @Test
    void passesThroughWhenTheOriginSendsNoMetadata() throws IOException {
        byte[] audio = countingAudio(500);

        IcyMetadataStream stream = new IcyMetadataStream(
                new ByteArrayInputStream(audio), 0, titles::add);

        assertArrayEquals(audio, readAll(stream, 64));
        assertTrue(titles.isEmpty());
    }

    @Test
    void reportsATitleOnlyWhenItChanges() throws IOException {
        byte[] audio = countingAudio(400);
        byte[] wire = interleave(audio, 100,
                "StreamTitle='Same';", "StreamTitle='Same';", "StreamTitle='Different';");

        IcyMetadataStream stream = new IcyMetadataStream(
                new ByteArrayInputStream(wire), 100, titles::add);
        readAll(stream, 64);

        assertEquals(List.of("Same", "Different"), titles,
                "a repeated title must not fire an update; the GUI would flicker");
    }

    @Test
    void extractsTitlesContainingApostrophes() {
        // Terminating on the next quote instead of "'; truncates any title with an apostrophe,
        // which is a large share of real track names.
        assertEquals("Rock 'n' Roll",
                IcyMetadataStream.extractStreamTitle("StreamTitle='Rock 'n' Roll';StreamUrl='';"));
    }

    @Test
    void extractsTitleWithoutATrailingSemicolon() {
        assertEquals("Lonely", IcyMetadataStream.extractStreamTitle("StreamTitle='Lonely'"));
    }

    @Test
    void returnsNullWhenTheBlockCarriesNoTitle() {
        assertNull(IcyMetadataStream.extractStreamTitle("StreamUrl='http://example.com';"));
    }

    @Test
    void decodesUtf8Titles() throws IOException {
        byte[] audio = countingAudio(100);
        byte[] wire = interleave(audio, 100, "StreamTitle='Björk – Jóga';");

        IcyMetadataStream stream = new IcyMetadataStream(
                new ByteArrayInputStream(wire), 100, titles::add);
        readAll(stream, 64);

        assertEquals(List.of("Björk – Jóga"), titles);
    }

    @Test
    void fallsBackToLatin1ForInvalidUtf8() throws IOException {
        // 0xE9 alone is é in ISO-8859-1 and invalid UTF-8. Decoding it as UTF-8 with replacement
        // would put a replacement character in the "now playing" line.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[100]);
        byte[] meta = new byte[]{'S', 't', 'r', 'e', 'a', 'm', 'T', 'i', 't', 'l', 'e', '=', '\'',
                'C', 'a', 'f', (byte) 0xE9, '\'', ';'};
        int units = (meta.length + 15) / 16;
        out.write(units);
        out.write(meta);
        for (int i = meta.length; i < units * 16; i++) {
            out.write(0);
        }

        IcyMetadataStream stream = new IcyMetadataStream(
                new ByteArrayInputStream(out.toByteArray()), 100, titles::add);
        readAll(stream, 64);

        assertEquals(List.of("Café"), titles);
    }

    /**
     * A metadata block the origin promises but never finishes must end the stream cleanly.
     *
     * <p>The length byte is a count of 16-byte units and cannot exceed 255, so no origin can
     * declare a block big enough to be worth rejecting on size — 4080 bytes is the ceiling. What
     * can happen is the connection dropping mid-block, and that has to surface as end-of-stream so
     * the reconnect backoff takes over, not as a hang or an exception.
     */
    @Test
    void treatsATruncatedMetadataBlockAsEndOfStream() throws IOException {
        // Two audio bytes, then a block claiming 255 units (4080 bytes) that never arrives.
        byte[] wire = new byte[]{7, 7, (byte) 0xFF};

        IcyMetadataStream stream = new IcyMetadataStream(
                new ByteArrayInputStream(wire), 2, titles::add);

        byte[] dest = new byte[8];
        assertEquals(2, stream.read(dest, 0, 8), "the audio before the block still comes through");
        assertEquals(-1, stream.read(dest, 0, 8), "the truncated block ends the stream");
        assertTrue(titles.isEmpty(), "an incomplete block must not report a title");
    }

    @Test
    void tracksTheDistanceToTheNextMetadataBlock() throws IOException {
        byte[] audio = countingAudio(300);
        byte[] wire = interleave(audio, 100, "StreamTitle='T';");

        IcyMetadataStream stream = new IcyMetadataStream(
                new ByteArrayInputStream(wire), 100, titles::add);

        byte[] buf = new byte[40];
        stream.read(buf, 0, 40);
        assertEquals(60, stream.bytesUntilMetadata());
        stream.read(buf, 0, 40);
        assertEquals(20, stream.bytesUntilMetadata());
    }
}
