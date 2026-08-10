package mmmm.core.relay;

import mmmm.core.security.EgressGuard;
import mmmm.core.source.IcyHttpSource;
import mmmm.core.source.SourceConfig;
import mmmm.core.source.StationResolver;
import mmmm.core.source.StreamSource;
import mmmm.core.source.StreamTitleListener;

import java.io.IOException;
import java.net.URI;

/**
 * Opens the origin connection for a {@link RelaySession}.
 *
 * <p>A seam, for one reason: it lets the session be tested against a byte array instead of the
 * internet. The relay's interesting behaviour — epoch placement, the backlog window, fan-out,
 * reconnect — is all downstream of "bytes arrived", and none of it should need a live station to
 * exercise.
 */
@FunctionalInterface
public interface SourceOpener {

    /**
     * Connects and returns a source positioned at the first encoded byte.
     *
     * @param titles receives {@code StreamTitle} updates for the life of the connection
     */
    StreamSource open(URI uri, StreamTitleListener titles) throws IOException;

    /**
     * The real thing: resolve any playlist indirection, then open an Icecast/Shoutcast stream.
     *
     * @throws mmmm.core.security.EgressDeniedException if the guard refuses the destination, at any
     *                                                  point in the redirect chain
     */
    static SourceOpener network(EgressGuard guard, SourceConfig config) {
        return (uri, titles) -> {
            StationResolver.Resolution resolution = StationResolver.resolve(uri, guard, config);
            if (resolution.transport() == StationResolver.Transport.HLS) {
                throw new UnsupportedCodecException(
                        "HLS is not implemented yet; it is the last item in the build order.");
            }
            return IcyHttpSource.open(resolution.uri(), guard, titles, config);
        };
    }
}
