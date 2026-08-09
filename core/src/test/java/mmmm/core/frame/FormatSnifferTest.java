package mmmm.core.frame;

import mmmm.core.media.Codec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormatSnifferTest {

    @Test
    void detectsOggFromItsCapturePattern() {
        byte[] ogg = {'O', 'g', 'g', 'S', 0, 2, 0, 0};

        assertEquals(Codec.VORBIS, FormatSniffer.sniff(ogg).orElseThrow());
    }

    @Test
    void detectsMp3FromAFrameSync() {
        assertEquals(Codec.MP3, FormatSniffer.sniff(TestFrames.mp3Frame(128, 44100, false)).orElseThrow());
    }

    @Test
    void detectsMp3FromALeadingId3Tag() {
        byte[] tagged = TestFrames.concat(TestFrames.id3v2Tag(100),
                TestFrames.mp3Frame(128, 44100, false));

        assertEquals(Codec.MP3, FormatSniffer.sniff(tagged).orElseThrow());
    }

    @Test
    void detectsAacFromAnAdtsHeader() {
        assertEquals(Codec.AAC, FormatSniffer.sniff(TestFrames.adtsFrame(44100, 2, 128)).orElseThrow());
    }

    /**
     * MP3 and ADTS both open with a run of sync bits, so the sync alone cannot separate them. The
     * layer field can: MPEG audio reserves {@code 00} and ADTS requires it. Getting this backwards
     * hands every AAC station to the MP3 parser.
     */
    @Test
    void separatesMp3FromAacByTheLayerField() {
        for (int bitrate : new int[]{64, 128, 320}) {
            byte[] mp3 = TestFrames.mp3Frame(bitrate, 44100, false);
            assertEquals(Codec.MP3, FormatSniffer.sniff(mp3).orElseThrow(),
                    "MPEG audio at " + bitrate + "kbps");
        }
        for (int rate : new int[]{22050, 44100, 48000}) {
            byte[] aac = TestFrames.adtsFrame(rate, 2, 200);
            assertEquals(Codec.AAC, FormatSniffer.sniff(aac).orElseThrow(),
                    "ADTS at " + rate + "Hz");
        }
    }

    @Test
    void returnsEmptyForUnrecognisedBytes() {
        assertTrue(FormatSniffer.sniff(new byte[]{'R', 'I', 'F', 'F'}).isEmpty());
        assertTrue(FormatSniffer.sniff(new byte[]{0, 0, 0, 0}).isEmpty());
    }

    @Test
    void returnsEmptyForInsufficientOrMissingInput() {
        assertTrue(FormatSniffer.sniff(new byte[0]).isEmpty());
        assertTrue(FormatSniffer.sniff(new byte[]{(byte) 0xFF}).isEmpty());
        assertTrue(FormatSniffer.sniff(null).isEmpty());
    }

    @Test
    void mapsCommonContentTypes() {
        assertEquals(Codec.MP3, FormatSniffer.fromContentType("audio/mpeg").orElseThrow());
        assertEquals(Codec.AAC, FormatSniffer.fromContentType("audio/aacp").orElseThrow());
        assertEquals(Codec.VORBIS, FormatSniffer.fromContentType("application/ogg").orElseThrow());
        assertEquals(Codec.MP3, FormatSniffer.fromContentType("audio/mpeg; charset=utf-8").orElseThrow());
        assertTrue(FormatSniffer.fromContentType("application/octet-stream").isEmpty());
        assertTrue(FormatSniffer.fromContentType(null).isEmpty());
    }

    /**
     * Stations mislabel constantly — {@code audio/mpeg} on an AAC stream is common. The bytes are
     * evidence; the header is only a hint.
     */
    @Test
    void sniffedBytesOverrideAMisleadingContentType() {
        byte[] aac = TestFrames.adtsFrame(44100, 2, 200);

        assertEquals(Codec.AAC,
                FormatSniffer.sniffOrContentType(aac, 0, aac.length, "audio/mpeg").orElseThrow());
    }

    @Test
    void fallsBackToContentTypeWhenBytesAreInconclusive() {
        byte[] unknown = {0x00, 0x11, 0x22, 0x33};

        assertEquals(Codec.MP3,
                FormatSniffer.sniffOrContentType(unknown, 0, unknown.length, "audio/mpeg").orElseThrow());
    }

    @Test
    void respectsOffsetAndLength() {
        byte[] padded = TestFrames.concat(new byte[]{9, 9, 9}, new byte[]{'O', 'g', 'g', 'S'});

        assertEquals(Codec.VORBIS, FormatSniffer.sniff(padded, 3, 4).orElseThrow());
    }
}
