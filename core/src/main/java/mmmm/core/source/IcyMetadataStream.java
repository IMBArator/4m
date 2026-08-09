package mmmm.core.source;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Strips Icecast/Shoutcast inline metadata out of an audio byte stream.
 *
 * <p>When a client sends {@code Icy-MetaData: 1}, the origin injects a metadata block after every
 * {@code icy-metaint} bytes of audio:
 *
 * <pre>
 *   [ metaint bytes of audio ][ 1 length byte ][ length*16 bytes of metadata ][ metaint bytes ]...
 * </pre>
 *
 * <p>The length byte is a count of 16-byte units, and is {@code 0} most of the time — a title only
 * changes between tracks. A client that fails to strip these plays them as audio, which is
 * immediately audible as a periodic click.
 *
 * <p>Separated from {@link IcyHttpSource} so the frame arithmetic can be tested against recorded
 * fixtures without a socket.
 *
 * <p>Not thread-safe; owned by one relay session thread.
 */
final class IcyMetadataStream {

    private final InputStream in;
    private final int metaInt;
    private final StreamTitleListener listener;

    private int untilMeta;
    private String lastTitle = "";

    /**
     * @param metaInt bytes of audio between metadata blocks; {@code <= 0} means the origin sends
     *                none and this becomes a pass-through
     */
    IcyMetadataStream(InputStream in, int metaInt, StreamTitleListener listener) {
        this.in = in;
        this.metaInt = metaInt;
        this.listener = listener == null ? StreamTitleListener.NONE : listener;
        this.untilMeta = metaInt;
    }

    /**
     * Reads up to {@code len} bytes of audio, consuming any metadata block reached on the way.
     *
     * <p>Never returns bytes from more than one inter-metadata segment in a single call: the read
     * is clamped at the boundary. That costs an extra call occasionally and keeps the arithmetic
     * obvious, which is the right trade for code whose failure mode is a subtle click.
     *
     * @return bytes read, or -1 at end of stream
     */
    int read(byte[] b, int off, int len) throws IOException {
        if (len == 0) {
            return 0;
        }
        if (metaInt <= 0) {
            return in.read(b, off, len);
        }
        if (untilMeta == 0) {
            if (!consumeMetadataBlock()) {
                return -1;
            }
            untilMeta = metaInt;
        }
        int toRead = Math.min(len, untilMeta);
        int n = in.read(b, off, toRead);
        if (n > 0) {
            untilMeta -= n;
        }
        return n;
    }

    /** @return false at end of stream */
    private boolean consumeMetadataBlock() throws IOException {
        int lengthUnits = in.read();
        if (lengthUnits < 0) {
            return false;
        }
        // The length byte is a count of 16-byte units and cannot exceed 255, so a block is at most
        // 4080 bytes. No bound check is needed here — there is no value the origin could send that
        // would make this allocation unreasonable.
        int length = lengthUnits * 16;
        if (length == 0) {
            return true;
        }
        byte[] raw = new byte[length];
        if (!readFully(raw)) {
            return false;
        }
        String title = extractStreamTitle(decode(raw));
        if (title != null && !title.equals(lastTitle)) {
            lastTitle = title;
            listener.onStreamTitle(title);
        }
        return true;
    }

    private boolean readFully(byte[] dest) throws IOException {
        int read = 0;
        while (read < dest.length) {
            int n = in.read(dest, read, dest.length - read);
            if (n < 0) {
                return false;
            }
            read += n;
        }
        return true;
    }

    /**
     * Decodes a metadata block to text.
     *
     * <p>The protocol never specified a charset. Most stations send UTF-8, a minority send
     * ISO-8859-1, and nothing distinguishes them but validity — so try UTF-8 strictly and fall back
     * on failure. Getting this backwards turns accented characters into mojibake in the "now
     * playing" line.
     */
    private static String decode(byte[] raw) {
        int end = raw.length;
        while (end > 0 && raw[end - 1] == 0) {
            end--;
        }
        CharsetDecoder strictUtf8 = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer decoded = strictUtf8.decode(ByteBuffer.wrap(raw, 0, end));
            return decoded.toString();
        } catch (CharacterCodingException e) {
            return new String(raw, 0, end, StandardCharsets.ISO_8859_1);
        }
    }

    /**
     * Pulls the title out of {@code StreamTitle='...';StreamUrl='...';}.
     *
     * <p>Titles routinely contain apostrophes, so the terminator is {@code ';} rather than the next
     * quote. Falling back to the last quote handles stations that omit the trailing semicolon.
     *
     * @return the title, or null if the block carries none
     */
    static String extractStreamTitle(String metadata) {
        final String key = "StreamTitle='";
        int start = metadata.indexOf(key);
        if (start < 0) {
            return null;
        }
        start += key.length();
        int end = metadata.indexOf("';", start);
        if (end < 0) {
            end = metadata.lastIndexOf('\'');
            if (end < start) {
                end = metadata.length();
            }
        }
        return metadata.substring(start, end).trim();
    }

    /** Bytes of audio remaining before the next metadata block. Test visibility. */
    int bytesUntilMetadata() {
        return untilMeta;
    }
}
