package mmmm.core.frame;

import mmmm.core.media.Codec;
import mmmm.core.media.MediaFrame;
import mmmm.core.media.StreamInfo;
import mmmm.core.media.Timeline;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * Splits MPEG-1/2/2.5 Layer III into frames, deriving each frame's exact duration from its header.
 *
 * <p>An MP3 frame header is four bytes and carries everything needed to compute both the frame's
 * length in bytes and its duration in samples. No decoding required, which is what lets the server
 * build the timeline without a codec (ADR-0004).
 *
 * <pre>
 *   AAAAAAAA AAABBCCD EEEEFFGH IIJJKLMM
 *   A sync (11 bits)   B version   C layer      D protection
 *   E bitrate index    F rate idx  G padding    H private
 *   I channel mode     J extension K copyright  L original  M emphasis
 * </pre>
 *
 * <p>Server side, single-threaded.
 */
public final class Mp3FrameParser implements FrameParser {

    /** Layer III sample counts. MPEG-2 and 2.5 use a half-size granule. */
    private static final int SAMPLES_MPEG1 = 1152;
    private static final int SAMPLES_MPEG2 = 576;

    private static final int[] BITRATES_MPEG1_L3 =
            {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, -1};
    private static final int[] BITRATES_MPEG2_L3 =
            {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, -1};

    private static final int[] RATES_MPEG1 = {44100, 48000, 32000};
    private static final int[] RATES_MPEG2 = {22050, 24000, 16000};
    private static final int[] RATES_MPEG25 = {11025, 12000, 8000};

    private static final int HEADER_BYTES = 4;

    /**
     * How far to hunt for a sync word before giving up on a byte.
     *
     * <p>Bounded so that a stream of garbage cannot make us buffer without limit while searching.
     */
    private static final int MAX_RESYNC_SCAN = 64 * 1024;

    private final FrameBuffer buffer = new FrameBuffer(16 * 1024);
    private final int streamId;

    private Timeline timeline;
    private StreamInfo info;
    private boolean skippedLeadingTag;

    public Mp3FrameParser() {
        this(0);
    }

    public Mp3FrameParser(int streamId) {
        this.streamId = streamId;
    }

    @Override
    public void feed(byte[] data, int off, int len, Consumer<MediaFrame> out) {
        buffer.append(data, off, len);

        if (!skippedLeadingTag) {
            if (!trySkipId3()) {
                return;
            }
        }

        while (true) {
            int offset = findSync();
            if (offset < 0) {
                break;
            }
            if (offset > 0) {
                buffer.skip(offset);
            }
            if (buffer.available() < HEADER_BYTES) {
                break;
            }

            Header header = parseHeader();
            if (header == null) {
                // Sync bits matched but the fields are nonsense — a false positive inside audio
                // data. Step one byte and keep hunting rather than trusting it.
                buffer.skip(1);
                continue;
            }
            if (buffer.available() < header.frameBytes()) {
                break;
            }

            if (timeline == null) {
                timeline = new Timeline(header.sampleRate());
                info = StreamInfo.audio(streamId, Codec.MP3, header.sampleRate(), header.channels());
            }

            byte[] payload = buffer.copy(0, header.frameBytes());
            long pts = timeline.emit(header.samples());
            buffer.skip(header.frameBytes());
            out.accept(new MediaFrame(streamId, pts, true, payload));
        }
        buffer.compact();
    }

    /**
     * Skips an ID3v2 tag if the stream opens with one.
     *
     * @return false if the tag is not yet fully buffered and parsing must wait
     */
    private boolean trySkipId3() {
        if (buffer.available() < 10) {
            return false;
        }
        if (!buffer.matches(0, "ID3")) {
            skippedLeadingTag = true;
            return true;
        }
        // Size is four synchsafe bytes: 7 significant bits each, high bit always clear.
        int size = (buffer.get(6) << 21) | (buffer.get(7) << 14) | (buffer.get(8) << 7) | buffer.get(9);
        int total = 10 + size;
        if (buffer.available() < total) {
            return false;
        }
        buffer.skip(total);
        skippedLeadingTag = true;
        return true;
    }

    /** Offset of the next plausible sync word, or -1 if none is buffered yet. */
    private int findSync() {
        int limit = Math.min(buffer.available() - 1, MAX_RESYNC_SCAN);
        for (int i = 0; i < limit; i++) {
            if (buffer.get(i) == 0xFF && (buffer.get(i + 1) & 0xE0) == 0xE0) {
                return i;
            }
        }
        // Nothing found. Drop everything but the last byte, which may be a sync word's first half.
        if (buffer.available() > 1) {
            buffer.skip(buffer.available() - 1);
        }
        return -1;
    }

    /** @return the parsed header, or null if the fields are invalid */
    private Header parseHeader() {
        int b1 = buffer.get(1);
        int b2 = buffer.get(2);
        int b3 = buffer.get(3);

        int versionBits = (b1 >> 3) & 0x03;
        int layerBits = (b1 >> 1) & 0x03;
        int bitrateIndex = (b2 >> 4) & 0x0F;
        int rateIndex = (b2 >> 2) & 0x03;
        int padding = (b2 >> 1) & 0x01;
        int channelMode = (b3 >> 6) & 0x03;

        // versionBits 01 is reserved; layerBits 01 is Layer III and the only one radio uses.
        if (versionBits == 0x01 || layerBits != 0x01 || rateIndex == 0x03) {
            return null;
        }
        // Index 0 is "free format" (no declared bitrate, frame length undeterminable from the
        // header) and 15 is invalid. Neither can be framed without decoding.
        if (bitrateIndex == 0 || bitrateIndex == 0x0F) {
            return null;
        }

        boolean mpeg1 = versionBits == 0x03;
        int[] rates = switch (versionBits) {
            case 0x03 -> RATES_MPEG1;
            case 0x02 -> RATES_MPEG2;
            default -> RATES_MPEG25;
        };
        int sampleRate = rates[rateIndex];
        int bitrateKbps = (mpeg1 ? BITRATES_MPEG1_L3 : BITRATES_MPEG2_L3)[bitrateIndex];
        if (bitrateKbps <= 0) {
            return null;
        }

        int samples = mpeg1 ? SAMPLES_MPEG1 : SAMPLES_MPEG2;
        // Layer III frame length in bytes. The constant is samplesPerFrame / 8: 144 for MPEG-1,
        // 72 for the half-size MPEG-2 granule.
        int coefficient = mpeg1 ? 144 : 72;
        int frameBytes = (coefficient * bitrateKbps * 1000) / sampleRate + padding;
        if (frameBytes <= HEADER_BYTES) {
            return null;
        }

        int channels = channelMode == 0x03 ? 1 : 2;
        return new Header(sampleRate, channels, samples, frameBytes);
    }

    @Override
    public Optional<StreamInfo> streamInfo() {
        return Optional.ofNullable(info);
    }

    @Override
    public long currentPtsMicros() {
        return timeline == null ? 0L : timeline.currentMicros();
    }

    private record Header(int sampleRate, int channels, int samples, int frameBytes) {
    }
}
