package mmmm.core.frame;

import mmmm.core.media.Codec;
import mmmm.core.media.MediaFrame;
import mmmm.core.media.StreamInfo;
import mmmm.core.media.Timeline;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * Splits AAC in ADTS framing.
 *
 * <p>ADTS carries a full header on every frame — that is the point of it, and it is why an AAC radio
 * stream can be joined mid-flight without any out-of-band setup data (contrast Vorbis, see
 * {@link OggFrameParser}). Frame length is stated in the header, so framing is exact.
 *
 * <pre>
 *   AAAAAAAA AAAABCCD EEFFFFGH HHIJKLMM MMMMMMMM MMMOOOOO OOOOOOPP
 *   A sync (12 bits)   B version  C layer (always 00)   D protection absent
 *   E profile          F sampling frequency index       G private
 *   H channel config   I original  J home  K copyright id  L copyright start
 *   M frame length including header (13 bits)
 *   O buffer fullness  P number of raw data blocks - 1
 * </pre>
 *
 * <p>Server side, single-threaded.
 */
public final class AdtsFrameParser implements FrameParser {

    /**
     * Samples per AAC raw data block.
     *
     * <p>HE-AAC (AAC+) needs no special case here, which is worth stating because it looks wrong at
     * first glance. Such a stream declares its <em>base</em> rate in the ADTS header — 22050 Hz is
     * typical — and SBR doubles it to 44100 Hz on decode. The header therefore describes 1024
     * samples at 22050 Hz while the decoder emits 2048 at 44100 Hz. Both are 46.44 ms, so frame
     * durations and the timeline built from them are identical either way, and only the decoder
     * needs to know the difference.
     */
    private static final int SAMPLES_PER_BLOCK = 1024;

    private static final int[] SAMPLE_RATES = {
            96000, 88200, 64000, 48000, 44100, 32000,
            24000, 22050, 16000, 12000, 11025, 8000, 7350
    };

    private static final int HEADER_BYTES = 7;
    private static final int HEADER_BYTES_WITH_CRC = 9;
    private static final int MAX_RESYNC_SCAN = 64 * 1024;

    private final FrameBuffer buffer = new FrameBuffer(16 * 1024);
    private final int streamId;

    private Timeline timeline;
    private StreamInfo info;

    public AdtsFrameParser() {
        this(0);
    }

    public AdtsFrameParser(int streamId) {
        this.streamId = streamId;
    }

    @Override
    public void feed(byte[] data, int off, int len, Consumer<MediaFrame> out) {
        buffer.append(data, off, len);

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
                buffer.skip(1);
                continue;
            }
            if (buffer.available() < header.frameBytes()) {
                break;
            }

            if (timeline == null) {
                timeline = new Timeline(header.sampleRate());
                info = StreamInfo.audio(streamId, Codec.AAC, header.sampleRate(), header.channels());
            }

            byte[] payload = buffer.copy(0, header.frameBytes());
            long pts = timeline.emit(header.samples());
            buffer.skip(header.frameBytes());
            out.accept(new MediaFrame(streamId, pts, true, payload));
        }
        buffer.compact();
    }

    /** Offset of the next 12-bit sync word with the layer field clear, or -1. */
    private int findSync() {
        int limit = Math.min(buffer.available() - 1, MAX_RESYNC_SCAN);
        for (int i = 0; i < limit; i++) {
            if (buffer.get(i) == 0xFF && (buffer.get(i + 1) & 0xF6) == 0xF0) {
                return i;
            }
        }
        if (buffer.available() > 1) {
            buffer.skip(buffer.available() - 1);
        }
        return -1;
    }

    /** @return the parsed header, or null if its fields are invalid */
    private Header parseHeader() {
        int b1 = buffer.get(1);
        int b2 = buffer.get(2);
        int b3 = buffer.get(3);
        int b4 = buffer.get(4);
        int b5 = buffer.get(5);
        int b6 = buffer.get(6);

        boolean protectionAbsent = (b1 & 0x01) != 0;
        int rateIndex = (b2 >> 2) & 0x0F;
        if (rateIndex >= SAMPLE_RATES.length) {
            return null;
        }
        int sampleRate = SAMPLE_RATES[rateIndex];

        // Channel configuration spans the low bit of byte 2 and the top two bits of byte 3.
        int channelConfig = ((b2 & 0x01) << 2) | ((b3 >> 6) & 0x03);
        if (channelConfig == 0) {
            // 0 means the configuration lives in an inline PCE, which we would have to decode to
            // read. Assume stereo: this only affects the advertised channel count, not framing.
            channelConfig = 2;
        }
        // Configs 1..7 map to channel counts 1..8, with 7 meaning 7.1.
        int channels = channelConfig == 7 ? 8 : Math.min(channelConfig, 6);

        // Frame length: 13 bits spanning bytes 3, 4 and 5.
        int frameBytes = ((b3 & 0x03) << 11) | (b4 << 3) | ((b5 >> 5) & 0x07);
        int minimum = protectionAbsent ? HEADER_BYTES : HEADER_BYTES_WITH_CRC;
        if (frameBytes < minimum) {
            return null;
        }

        int blocks = (b6 & 0x03) + 1;
        return new Header(sampleRate, channels, blocks * SAMPLES_PER_BLOCK, frameBytes);
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
