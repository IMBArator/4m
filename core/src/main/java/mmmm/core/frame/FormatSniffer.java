package mmmm.core.frame;

import mmmm.core.media.Codec;

import java.util.Locale;
import java.util.Optional;

/**
 * Identifies a codec from the leading bytes of a stream.
 *
 * <p>The sniff takes precedence over {@code Content-Type}. Stations mislabel constantly —
 * {@code audio/mpeg} on an AAC stream is common, and {@code application/octet-stream} tells us
 * nothing — so the header is a tie-breaker, not evidence.
 */
public final class FormatSniffer {

    /** Enough for an ID3v2 header plus the first frame sync that follows it. */
    public static final int RECOMMENDED_BYTES = 16;

    private FormatSniffer() {
    }

    /**
     * @param prefix first bytes of the stream; 4 or more is plenty
     * @return the codec, or empty if the bytes match nothing known
     */
    public static Optional<Codec> sniff(byte[] prefix, int off, int len) {
        if (prefix == null || len < 2) {
            return Optional.empty();
        }

        // "OggS" — an Ogg page header. Vorbis is the only Ogg payload we support.
        if (len >= 4 && matches(prefix, off, 'O', 'g', 'g', 'S')) {
            return Optional.of(Codec.VORBIS);
        }

        // "ID3" — an ID3v2 tag, only ever prepended to MPEG audio in this context.
        if (len >= 3 && matches(prefix, off, 'I', 'D', '3')) {
            return Optional.of(Codec.MP3);
        }

        int b0 = prefix[off] & 0xFF;
        int b1 = prefix[off + 1] & 0xFF;

        // Both MPEG audio and ADTS AAC begin with a run of sync bits, so the sync alone does not
        // discriminate. The layer field does: MPEG audio reserves 00, and ADTS requires it.
        //
        //   byte1: 1111 1111
        //   byte2: 111x xxxx   MPEG audio  (11-bit sync)  layer != 00
        //   byte2: 1111 x00x   ADTS AAC    (12-bit sync)  layer == 00
        if (b0 == 0xFF && (b1 & 0xE0) == 0xE0) {
            int layer = (b1 >> 1) & 0x03;
            if (layer == 0) {
                return (b1 & 0xF0) == 0xF0 ? Optional.of(Codec.AAC) : Optional.empty();
            }
            return Optional.of(Codec.MP3);
        }

        return Optional.empty();
    }

    public static Optional<Codec> sniff(byte[] prefix) {
        return prefix == null ? Optional.empty() : sniff(prefix, 0, prefix.length);
    }

    /**
     * Sniffs, falling back to the advertised content type only when the bytes are inconclusive.
     *
     * @param contentType a MIME type such as {@code audio/mpeg}, may be null
     */
    public static Optional<Codec> sniffOrContentType(byte[] prefix, int off, int len, String contentType) {
        Optional<Codec> sniffed = sniff(prefix, off, len);
        return sniffed.isPresent() ? sniffed : fromContentType(contentType);
    }

    public static Optional<Codec> fromContentType(String contentType) {
        if (contentType == null) {
            return Optional.empty();
        }
        String type = contentType.split(";")[0].trim().toLowerCase(Locale.ROOT);
        return switch (type) {
            case "audio/mpeg", "audio/mp3", "audio/mpeg3", "audio/x-mpeg" -> Optional.of(Codec.MP3);
            case "audio/aac", "audio/aacp", "audio/x-aac" -> Optional.of(Codec.AAC);
            case "audio/ogg", "application/ogg", "audio/vorbis" -> Optional.of(Codec.VORBIS);
            default -> Optional.empty();
        };
    }

    private static boolean matches(byte[] b, int off, char... chars) {
        for (int i = 0; i < chars.length; i++) {
            if ((b[off + i] & 0xFF) != chars[i]) {
                return false;
            }
        }
        return true;
    }
}
