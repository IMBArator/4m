package mmmm.core.relay;

import mmmm.core.security.EgressGuard;
import mmmm.core.source.IcyHttpSource;
import mmmm.core.source.SourceConfig;
import mmmm.core.source.StationResolver;
import mmmm.core.source.StreamSource;
import mmmm.core.source.StreamTitleListener;

import java.io.IOException;
import java.net.URI;
import java.util.function.Function;

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
        return network(uri -> guard, config);
    }

    /**
     * As {@link #network(EgressGuard, SourceConfig)}, but chooses the guard per station.
     *
     * <p>Two reasons it takes the station rather than a fixed value.
     *
     * <p>First, the policy is not fixed for the life of a server. An operator authorising a station
     * must not have to restart for it to take effect, and a session reconnecting an hour later must
     * see the policy as it stands then, not as it stood at startup.
     *
     * <p>Second, and the one that is easy to get wrong: <b>the host a player types is usually not
     * the host the audio comes from.</b> A station URL is typically a {@code .pls} or {@code .m3u}
     * playlist naming an endpoint on an entirely different domain — often a CDN whose hostname
     * varies between requests. {@code streams.radiobob.de/…/play.pls} resolves to
     * {@code regiocast.streamabc.net}. So "may this station be streamed" is a question about the
     * whole resolution chain, not about one hostname, and only the caller knows how far its
     * authorisation was meant to reach. Handing it the URI is what lets it answer.
     *
     * <p>Neither weakens ADR-0011's mechanics. Whichever guard comes back is still consulted after
     * DNS resolution and still on every redirect hop, and its address-range refusals are absolute.
     */
    static SourceOpener network(Function<URI, EgressGuard> guards, SourceConfig config) {
        return (uri, titles) -> {
            EgressGuard guard = guards.apply(uri);
            StationResolver.Resolution resolution = StationResolver.resolve(uri, guard, config);
            if (resolution.transport() == StationResolver.Transport.HLS) {
                throw new UnsupportedCodecException(
                        "HLS is not implemented yet; it is the last item in the build order.");
            }
            return IcyHttpSource.open(resolution.uri(), guard, titles, config);
        };
    }
}
