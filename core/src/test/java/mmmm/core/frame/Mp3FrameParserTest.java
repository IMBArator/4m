package mmmm.core.frame;

import mmmm.core.media.Codec;
import mmmm.core.media.MediaFrame;
import mmmm.core.media.StreamInfo;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Mp3FrameParserTest {

    private final List<MediaFrame> frames = new ArrayList<>();

    private void feed(Mp3FrameParser parser, byte[] data) {
        parser.feed(data, 0, data.length, frames::add);
    }

    @Test
    void framesAtTheDeclaredLengthAndReportsStreamParameters() {
        Mp3FrameParser parser = new Mp3FrameParser();
        byte[] frame = TestFrames.mp3Frame(128, 44100, false);

        feed(parser, TestFrames.concat(frame, frame, frame));

        assertEquals(3, frames.size());
        // 144 * 128000 / 44100 = 417
        assertEquals(417, frames.get(0).size());

        StreamInfo info = parser.streamInfo().orElseThrow();
        assertEquals(Codec.MP3, info.codec());
        assertEquals(44100, info.sampleRate());
        assertEquals(2, info.channels());
    }

    @Test
    void stampsEachFrameWithItsExactStartTime() {
        Mp3FrameParser parser = new Mp3FrameParser();
        byte[] frame = TestFrames.mp3Frame(128, 44100, false);

        feed(parser, TestFrames.concat(frame, frame, frame));

        // Each MPEG-1 Layer III frame is 1152 samples. At 44.1 kHz that is 26122.448... µs, so the
        // timestamps must not be exact multiples of a rounded per-frame duration.
        assertEquals(0, frames.get(0).ptsMicros());
        assertEquals(26122, frames.get(1).ptsMicros());
        assertEquals(52244, frames.get(2).ptsMicros());
    }

    @Test
    void handlesPaddedFrames() {
        Mp3FrameParser parser = new Mp3FrameParser();
        byte[] unpadded = TestFrames.mp3Frame(128, 44100, false);
        byte[] padded = TestFrames.mp3Frame(128, 44100, true);

        feed(parser, TestFrames.concat(unpadded, padded, unpadded));

        assertEquals(3, frames.size());
        assertEquals(417, frames.get(0).size());
        assertEquals(418, frames.get(1).size(), "padding bit adds exactly one byte");
        assertEquals(417, frames.get(2).size());
    }

    @Test
    void reassemblesFramesSplitAcrossReads() {
        Mp3FrameParser parser = new Mp3FrameParser();
        byte[] stream = TestFrames.concat(
                TestFrames.mp3Frame(128, 44100, false),
                TestFrames.mp3Frame(128, 44100, false),
                TestFrames.mp3Frame(128, 44100, false));

        // Network reads land on arbitrary boundaries; 7 bytes at a time is a hostile but legal one.
        for (int i = 0; i < stream.length; i += 7) {
            parser.feed(stream, i, Math.min(7, stream.length - i), frames::add);
        }

        assertEquals(3, frames.size());
        assertEquals(0, frames.get(0).ptsMicros());
        assertEquals(26122, frames.get(1).ptsMicros());
    }

    @Test
    void skipsLeadingId3Tag() {
        Mp3FrameParser parser = new Mp3FrameParser();
        byte[] frame = TestFrames.mp3Frame(128, 44100, false);

        feed(parser, TestFrames.concat(TestFrames.id3v2Tag(500), frame, frame));

        assertEquals(2, frames.size());
        assertEquals(417, frames.get(0).size());
    }

    @Test
    void resynchronisesAfterGarbage() {
        Mp3FrameParser parser = new Mp3FrameParser();
        byte[] frame = TestFrames.mp3Frame(128, 44100, false);
        byte[] garbage = new byte[64];
        java.util.Arrays.fill(garbage, (byte) 0x42);

        feed(parser, TestFrames.concat(frame, garbage, frame));

        assertEquals(2, frames.size(), "must recover the frame after the corrupt run");
    }

    @Test
    void rejectsFreeFormatAndInvalidBitrateIndices() {
        Mp3FrameParser parser = new Mp3FrameParser();
        // Sync bits set, but bitrate index 0 (free format) and 15 (invalid) cannot be framed from
        // the header alone, so neither may be accepted.
        byte[] freeFormat = {(byte) 0xFF, (byte) 0xFB, (byte) 0x00, 0x40};
        byte[] invalid = {(byte) 0xFF, (byte) 0xFB, (byte) 0xF0, 0x40};

        feed(parser, TestFrames.concat(freeFormat, invalid));

        assertTrue(frames.isEmpty());
        assertFalse(parser.streamInfo().isPresent());
    }

    @Test
    void detectsMonoFromChannelMode() {
        Mp3FrameParser parser = new Mp3FrameParser();
        byte[] frame = TestFrames.mp3Frame(128, 44100, false);
        frame[3] = (byte) 0xC0; // channel mode 11 = single channel

        feed(parser, frame);

        assertEquals(1, parser.streamInfo().orElseThrow().channels());
    }

    @Test
    void handlesA48kHzStream() {
        Mp3FrameParser parser = new Mp3FrameParser();
        byte[] frame = TestFrames.mp3Frame(192, 48000, false);

        feed(parser, TestFrames.concat(frame, frame));

        assertEquals(2, frames.size());
        // 144 * 192000 / 48000 = 576
        assertEquals(576, frames.get(0).size());
        assertEquals(48000, parser.streamInfo().orElseThrow().sampleRate());
        // 1152 samples at 48 kHz is exactly 24000 µs.
        assertEquals(24_000, frames.get(1).ptsMicros());
    }
}
