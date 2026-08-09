package mmmm.core.source;

import java.io.Closeable;
import java.io.IOException;

/**
 * A byte stream of encoded media pulled from an origin.
 *
 * <p>Implementations hand back <em>clean</em> encoded bytes: anything the transport interleaved for
 * its own purposes (Icecast metadata blocks, HLS segment boundaries) has already been removed.
 *
 * <p>Server side only. Nothing here decodes.
 */
public interface StreamSource extends Closeable {

    /**
     * Reads up to {@code len} bytes of encoded media.
     *
     * <p>Blocks until at least one byte is available. Returns {@code -1} at end of stream — which
     * for a live radio station means the origin dropped the connection, not that playback finished.
     *
     * @return bytes read, or {@code -1} at end of stream
     */
    int read(byte[] b, int off, int len) throws IOException;

    /** What the origin said about itself. Available once the source is open. */
    SourceMetadata metadata();
}
