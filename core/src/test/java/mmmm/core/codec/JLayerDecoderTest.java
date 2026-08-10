package mmmm.core.codec;

import mmmm.core.media.MediaFrame;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What can be asserted about a decoder without real audio.
 *
 * <p>Correct decoding is verified by ear, not here — {@code tools/build-core.sh decode <url> out.wav}
 * writes a listenable file, because a byte-order slip, a swapped channel or a lost bit reservoir all
 * produce PCM of exactly the right length. These tests cover the other half: that malformed input
 * is survived rather than thrown, which is the property live radio actually depends on.
 */
class JLayerDecoderTest {

    /** Bytes that contain no MPEG sync word anywhere. */
    private static MediaFrame garbage() {
        byte[] payload = new byte[417];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i % 97);
        }
        return new MediaFrame(0, 0, false, payload);
    }

    /** A structurally valid MPEG-1 Layer III header with a zero-filled body. */
    private static MediaFrame headerOnlyFrame(long ptsMicros) {
        byte[] frame = new byte[417];
        frame[0] = (byte) 0xFF;
        frame[1] = (byte) 0xFB;   // MPEG-1, Layer III, no CRC
        frame[2] = (byte) 0x90;   // 128 kbps, 44100 Hz, no padding
        frame[3] = (byte) 0x40;   // joint stereo
        return new MediaFrame(0, ptsMicros, false, frame);
    }

    @Test
    void formatIsUnknownUntilSomethingDecodes() {
        try (JLayerDecoder decoder = new JLayerDecoder()) {
            assertTrue(decoder.format().isEmpty(), "nothing has been decoded yet");
        }
    }

    /**
     * The property that keeps the radio alive. A stream with a corrupt frame in it must cost one
     * frame, not the session — a decoder that propagates the failure ends playback permanently,
     * because nothing upstream is in a position to restart it.
     */
    @Test
    void garbageIsDroppedRatherThanThrown() {
        try (JLayerDecoder decoder = new JLayerDecoder()) {
            List<byte[]> emitted = new ArrayList<>();

            assertDoesNotThrow(() -> decoder.decode(garbage(), (pcm, off, len) ->
                    emitted.add(java.util.Arrays.copyOfRange(pcm, off, off + len))));

            assertEquals(1, decoder.framesDropped());
            assertTrue(emitted.isEmpty(), "garbage cannot produce audio");
        }
    }

    @Test
    void keepsGoingAfterABadFrame() {
        try (JLayerDecoder decoder = new JLayerDecoder()) {
            PcmSink discard = (pcm, off, len) -> { };

            decoder.decode(garbage(), discard);
            decoder.decode(headerOnlyFrame(0), discard);
            decoder.decode(headerOnlyFrame(26_122), discard);

            assertEquals(1, decoder.framesDropped(),
                    "only the garbage frame was unusable; the well-formed ones must not be counted");
            assertTrue(decoder.format().isPresent(), "a valid header reveals the output format");
            assertEquals(44100, decoder.format().orElseThrow().sampleRate());
        }
    }

    @Test
    void resetForgetsTheFormatAndDoesNotThrow() {
        try (JLayerDecoder decoder = new JLayerDecoder()) {
            decoder.decode(headerOnlyFrame(0), (pcm, off, len) -> { });
            assertTrue(decoder.format().isPresent());

            assertDoesNotThrow(decoder::reset);

            assertTrue(decoder.format().isEmpty(), "after a hard resync the format is rediscovered");
            assertDoesNotThrow(() -> decoder.decode(headerOnlyFrame(0), (pcm, off, len) -> { }));
        }
    }

    @Test
    void closingTwiceIsHarmless() {
        JLayerDecoder decoder = new JLayerDecoder();
        decoder.close();
        assertDoesNotThrow(decoder::close);
    }
}
