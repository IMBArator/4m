package mmmm.core.frame;

import mmmm.core.media.Codec;
import mmmm.core.media.MediaFrame;
import mmmm.core.media.StreamInfo;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OggFrameParserTest {

    private static final int FLAG_BOS = 0x02;

    private final List<MediaFrame> frames = new ArrayList<>();

    private void feed(OggFrameParser parser, byte[] data) {
        parser.feed(data, 0, data.length, frames::add);
    }

    /** The three header pages every logical Vorbis stream opens with. */
    private static byte[] headerPages(int serial, int sampleRate, int channels) {
        return TestFrames.concat(
                TestFrames.oggPage(FLAG_BOS, 0, serial, 0,
                        TestFrames.vorbisIdentificationPacket(sampleRate, channels)),
                TestFrames.oggPage(0, 0, serial, 1,
                        TestFrames.vorbisCommentPacket(), TestFrames.vorbisSetupPacket()));
    }

    @Test
    void capturesCodecInitAndStreamParameters() {
        OggFrameParser parser = new OggFrameParser();

        feed(parser, headerPages(1, 44100, 2));

        StreamInfo info = parser.streamInfo().orElseThrow();
        assertEquals(Codec.VORBIS, info.codec());
        assertEquals(44100, info.sampleRate());
        assertEquals(2, info.channels());
        assertTrue(info.codecInit().length > 0,
                "a client joining mid-stream cannot decode Vorbis without the header packets");
    }

    @Test
    void countsHeaderPacketsNotHeaderPages() {
        // The comment and setup packets share one page here. Counting pages would see two and wait
        // forever for a third; counting packet terminators in the segment table sees three.
        OggFrameParser parser = new OggFrameParser();

        feed(parser, headerPages(1, 48000, 2));

        assertTrue(parser.streamInfo().isPresent(),
                "three header packets across two pages must complete the stream info");
    }

    @Test
    void derivesTimingFromGranulePosition() {
        OggFrameParser parser = new OggFrameParser();
        feed(parser, headerPages(1, 44100, 2));

        feed(parser, TestFrames.concat(
                TestFrames.oggPage(0, 44100, 1, 2, TestFrames.audioPacket(100)),
                TestFrames.oggPage(0, 88200, 1, 3, TestFrames.audioPacket(100))));

        assertEquals(2, frames.size());
        assertEquals(0, frames.get(0).ptsMicros(), "first audio page starts at zero");
        assertEquals(1_000_000, frames.get(1).ptsMicros(), "granule 44100 at 44.1 kHz is one second");
    }

    @Test
    void ignoresPagesWithNoGranule() {
        OggFrameParser parser = new OggFrameParser();
        feed(parser, headerPages(1, 44100, 2));

        // -1 means no packet completes on this page, so it carries no timing.
        feed(parser, TestFrames.oggPage(0, -1, 1, 2, TestFrames.audioPacket(100)));
        feed(parser, TestFrames.oggPage(0, 44100, 1, 3, TestFrames.audioPacket(100)));

        assertEquals(2, frames.size());
        assertEquals(0, frames.get(0).ptsMicros());
        assertEquals(0, frames.get(1).ptsMicros(), "timeline only advances on a real granule");
        assertEquals(1_000_000, parser.currentPtsMicros());
    }

    /**
     * Joining a live stream mid-flight, which is what actually happens on every connect.
     *
     * <p>Icecast replays the cached header pages — beginning-of-stream flag and all — then splices
     * in the live feed wherever the encoder currently is. Read as absolute, that first granule puts
     * the second frame two minutes after the first.
     *
     * <p>Only a real encoder exposes this; a fixture starting at granule zero passes either way,
     * which is exactly why it is pinned here.
     */
    @Test
    void anchorsTheTimelineWhenJoiningMidStream() {
        OggFrameParser parser = new OggFrameParser();
        feed(parser, headerPages(1, 44100, 2));

        // Join mid-song: the encoder is already 120 s in.
        long joinGranule = 44100L * 120;
        feed(parser, TestFrames.concat(
                TestFrames.oggPage(0, joinGranule, 1, 2, TestFrames.audioPacket(100)),
                TestFrames.oggPage(0, joinGranule + 44100, 1, 3, TestFrames.audioPacket(100)),
                TestFrames.oggPage(0, joinGranule + 88200, 1, 4, TestFrames.audioPacket(100))));

        assertEquals(3, frames.size());
        assertEquals(0, frames.get(0).ptsMicros(), "playback starts at zero, not at the encoder's clock");
        assertEquals(0, frames.get(1).ptsMicros(),
                "the first page is treated as zero-length; timing starts from its granule");
        assertEquals(1_000_000, frames.get(2).ptsMicros(),
                "one second of granule must be one second of timeline, not 121 seconds");
        assertEquals(2_000_000, parser.currentPtsMicros());
    }

    /**
     * The other half of the anchoring rule, and the reason it is conditional.
     *
     * <p>Anchoring discards the anchor page's duration. Once at session start that is invisible;
     * at every track change it would shed ~50 ms a track and accumulate into precisely the slow
     * drift this parser exists to prevent. So a stream that genuinely starts at the beginning must
     * take the granule as absolute and lose nothing.
     */
    @Test
    void doesNotAnchorWhenTheStreamStartsAtTheBeginning() {
        OggFrameParser parser = new OggFrameParser();
        feed(parser, headerPages(1, 44100, 2));

        // A real first page holds a fraction of a second, nothing like a mid-stream granule.
        feed(parser, TestFrames.concat(
                TestFrames.oggPage(0, 4410, 1, 2, TestFrames.audioPacket(100)),
                TestFrames.oggPage(0, 8820, 1, 3, TestFrames.audioPacket(100))));

        assertEquals(2, frames.size());
        assertEquals(0, frames.get(0).ptsMicros());
        assertEquals(100_000, frames.get(1).ptsMicros(),
                "the first page's 0.1s must be counted, not discarded as an anchor");
        assertEquals(200_000, parser.currentPtsMicros());
    }

    /**
     * The failure that ends playback at the first track change if it is not handled.
     *
     * <p>Icecast starts a fresh logical stream per track: new serial, new headers, granule back to
     * zero. The timeline must carry forward rather than jump backwards.
     */
    @Test
    void continuesTimelineAcrossAChainedStream() {
        OggFrameParser parser = new OggFrameParser();
        feed(parser, headerPages(1, 44100, 2));
        feed(parser, TestFrames.oggPage(0, 44100, 1, 2, TestFrames.audioPacket(100)));

        // Track change: new serial, headers again, granule restarting at zero.
        feed(parser, headerPages(2, 44100, 2));
        feed(parser, TestFrames.oggPage(0, 22050, 2, 2, TestFrames.audioPacket(100)));

        assertEquals(2, frames.size());
        assertEquals(0, frames.get(0).ptsMicros());
        assertEquals(1_000_000, frames.get(1).ptsMicros(),
                "the new stream's audio must start where the previous one ended, not at zero");
        assertEquals(44100, parser.chainOffsetSamples());
        assertEquals(1_500_000, parser.currentPtsMicros(),
                "granule 22050 into the second stream is 0.5s past the 1.0s boundary");
    }

    @Test
    void recapturesCodecInitAtATrackChange() {
        OggFrameParser parser = new OggFrameParser();
        feed(parser, headerPages(1, 44100, 2));
        byte[] firstInit = parser.streamInfo().orElseThrow().codecInit();

        feed(parser, headerPages(2, 48000, 1));
        StreamInfo second = parser.streamInfo().orElseThrow();

        assertEquals(48000, second.sampleRate(), "parameters follow the new logical stream");
        assertEquals(1, second.channels());
        // Both streams have the same page structure, so the lengths match; only the contents
        // differ. Comparing lengths would pass no matter what, which is why this compares bytes.
        assertFalse(Arrays.equals(firstInit, second.codecInit()),
                "codec init must describe the new stream, not the previous one");
        assertEquals(firstInit.length, second.codecInit().length,
                "and it must be rebuilt rather than appended to");
    }

    @Test
    void reassemblesPagesSplitAcrossReads() {
        OggFrameParser parser = new OggFrameParser();
        byte[] stream = TestFrames.concat(
                headerPages(1, 44100, 2),
                TestFrames.oggPage(0, 44100, 1, 2, TestFrames.audioPacket(300)),
                TestFrames.oggPage(0, 88200, 1, 3, TestFrames.audioPacket(300)));

        for (int i = 0; i < stream.length; i += 11) {
            parser.feed(stream, i, Math.min(11, stream.length - i), frames::add);
        }

        assertEquals(2, frames.size());
        assertEquals(1_000_000, frames.get(1).ptsMicros());
    }

    @Test
    void lacesPacketsLongerThan255Bytes() {
        // A 600-byte packet needs lacing values 255, 255, 90. Miscounting those would both
        // mis-frame the page and miscount header packets.
        OggFrameParser parser = new OggFrameParser();
        feed(parser, headerPages(1, 44100, 2));

        feed(parser, TestFrames.oggPage(0, 44100, 1, 2, TestFrames.audioPacket(600)));

        assertEquals(1, frames.size());
        assertTrue(frames.get(0).size() > 600, "page must contain the whole packet plus its header");
    }

    @Test
    void skipsGarbageBeforeACapturePattern() {
        OggFrameParser parser = new OggFrameParser();
        byte[] garbage = new byte[50];
        java.util.Arrays.fill(garbage, (byte) 0x7E);

        feed(parser, TestFrames.concat(garbage, headerPages(1, 44100, 2)));

        assertTrue(parser.streamInfo().isPresent());
    }
}
