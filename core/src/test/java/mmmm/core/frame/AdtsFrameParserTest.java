package mmmm.core.frame;

import mmmm.core.media.Codec;
import mmmm.core.media.MediaFrame;
import mmmm.core.media.StreamInfo;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdtsFrameParserTest {

    private final List<MediaFrame> frames = new ArrayList<>();

    private void feed(AdtsFrameParser parser, byte[] data) {
        parser.feed(data, 0, data.length, frames::add);
    }

    @Test
    void framesAtTheLengthStatedInTheHeader() {
        AdtsFrameParser parser = new AdtsFrameParser();
        byte[] frame = TestFrames.adtsFrame(44100, 2, 384);

        feed(parser, TestFrames.concat(frame, frame, frame));

        assertEquals(3, frames.size());
        assertEquals(384, frames.get(0).size());

        StreamInfo info = parser.streamInfo().orElseThrow();
        assertEquals(Codec.AAC, info.codec());
        assertEquals(44100, info.sampleRate());
        assertEquals(2, info.channels());
    }

    @Test
    void stampsFramesAt1024SamplesEach() {
        AdtsFrameParser parser = new AdtsFrameParser();
        byte[] frame = TestFrames.adtsFrame(44100, 2, 384);

        feed(parser, TestFrames.concat(frame, frame, frame));

        // 1024 samples at 44.1 kHz = 23219.95... µs, so again not a round number.
        assertEquals(0, frames.get(0).ptsMicros());
        assertEquals(23219, frames.get(1).ptsMicros());
        assertEquals(46439, frames.get(2).ptsMicros());
    }

    @Test
    void handlesVariableFrameLengths() {
        AdtsFrameParser parser = new AdtsFrameParser();

        feed(parser, TestFrames.concat(
                TestFrames.adtsFrame(44100, 2, 200),
                TestFrames.adtsFrame(44100, 2, 512),
                TestFrames.adtsFrame(44100, 2, 350)));

        assertEquals(3, frames.size());
        assertEquals(200, frames.get(0).size());
        assertEquals(512, frames.get(1).size());
        assertEquals(350, frames.get(2).size());
    }

    @Test
    void reassemblesFramesSplitAcrossReads() {
        AdtsFrameParser parser = new AdtsFrameParser();
        byte[] frame = TestFrames.adtsFrame(48000, 2, 256);
        byte[] stream = TestFrames.concat(frame, frame, frame);

        for (int i = 0; i < stream.length; i += 5) {
            parser.feed(stream, i, Math.min(5, stream.length - i), frames::add);
        }

        assertEquals(3, frames.size());
        assertEquals(48000, parser.streamInfo().orElseThrow().sampleRate());
        // 1024 samples at 48 kHz is exactly 21333.33 µs, flooring to 21333.
        assertEquals(21333, frames.get(1).ptsMicros());
    }

    @Test
    void resynchronisesAfterGarbage() {
        AdtsFrameParser parser = new AdtsFrameParser();
        byte[] frame = TestFrames.adtsFrame(44100, 2, 384);
        byte[] garbage = new byte[100];
        java.util.Arrays.fill(garbage, (byte) 0x11);

        feed(parser, TestFrames.concat(frame, garbage, frame));

        assertEquals(2, frames.size());
    }

    @Test
    void ignoresAnMp3SyncWord() {
        // An MP3 header also starts 0xFF with sync bits set. The layer field is what separates
        // them, and getting that wrong would make each parser accept the other's stream.
        AdtsFrameParser parser = new AdtsFrameParser();
        byte[] mp3 = TestFrames.mp3Frame(128, 44100, false);

        feed(parser, mp3);

        assertTrue(frames.isEmpty(), "ADTS parser must not accept MPEG audio frames");
        assertTrue(parser.streamInfo().isEmpty());
    }

    @Test
    void reportsMonoStreams() {
        AdtsFrameParser parser = new AdtsFrameParser();

        feed(parser, TestFrames.adtsFrame(22050, 1, 128));

        StreamInfo info = parser.streamInfo().orElseThrow();
        assertEquals(1, info.channels());
        assertEquals(22050, info.sampleRate());
    }
}
