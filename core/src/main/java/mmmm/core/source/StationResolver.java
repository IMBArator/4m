package mmmm.core.source;

import mmmm.core.security.EgressGuard;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

import mmmm.core.frame.FormatSniffer;

/**
 * Turns a station link into something we can actually stream from.
 *
 * <p>A station URL copied from a website is usually a playlist rather than audio, and playlists
 * chain — a {@code .pls} listing a {@code .m3u} listing the real endpoint is normal. This resolves
 * the chain and reports which transport to use at the end of it.
 */
public final class StationResolver {

    /** How the resolved URL must be consumed. */
    public enum Transport {
        /** A continuous Icecast/Shoutcast stream — {@link IcyHttpSource}. */
        ICY_HTTP,
        /** A segmented HLS media playlist, polled and fetched segment by segment. */
        HLS
    }

    /**
     * @param uri       the endpoint to stream from, after following any playlists
     * @param transport how to read it
     */
    public record Resolution(URI uri, Transport transport) {
    }

    /** Playlists chain, but not deeply. Beyond this it is a loop or a misconfiguration. */
    private static final int MAX_PLAYLIST_DEPTH = 3;

    /** Enough to classify a playlist and to sniff a codec; playlists are small. */
    private static final int PROBE_BYTES = 8 * 1024;

    private StationResolver() {
    }

    public static Resolution resolve(URI uri, EgressGuard guard) throws IOException {
        return resolve(uri, guard, SourceConfig.DEFAULT);
    }

    /**
     * Follows playlist indirection and identifies the transport.
     *
     * <p>Probing opens a connection, reads a few kilobytes, and closes it — so a station that turns
     * out to be plain audio costs one extra connection before the real one. That is deliberate: it
     * removes any reliance on file extensions, which stations get wrong constantly, and a relay
     * session connects once and then stays up for hours, so the cost is paid once and is invisible.
     */
    public static Resolution resolve(URI uri, EgressGuard guard, SourceConfig config) throws IOException {
        URI current = uri;

        for (int depth = 0; depth < MAX_PLAYLIST_DEPTH; depth++) {
            byte[] probe = probe(current, guard, config);

            // Audio wins over any playlist interpretation: an MP3 frame header could in principle
            // appear in text, but text cannot appear in a valid frame header.
            if (FormatSniffer.sniff(probe).isPresent()) {
                return new Resolution(current, Transport.ICY_HTTP);
            }

            String text = new String(probe, StandardCharsets.UTF_8);
            PlaylistParser.Kind kind = PlaylistParser.classify(text);
            if (kind == PlaylistParser.Kind.NONE) {
                // Unrecognised. Hand it to the ICY transport anyway so the failure surfaces from
                // the frame parser, which can say what it actually saw.
                return new Resolution(current, Transport.ICY_HTTP);
            }
            if (kind == PlaylistParser.Kind.HLS) {
                return new Resolution(current, Transport.HLS);
            }

            List<String> urls = PlaylistParser.extractUrls(text, kind);
            if (urls.isEmpty()) {
                throw new IOException("Playlist at " + current + " contains no stream URLs");
            }
            URI next = current.resolve(urls.get(0).trim());
            if (next.equals(current)) {
                throw new IOException("Playlist at " + current + " points at itself");
            }
            current = next;
        }
        throw new IOException("Playlist nesting deeper than " + MAX_PLAYLIST_DEPTH + " starting from " + uri);
    }

    /** Opens, reads a bounded prefix, closes. */
    private static byte[] probe(URI uri, EgressGuard guard, SourceConfig config) throws IOException {
        try (IcyHttpSource source = IcyHttpSource.open(uri, guard, StreamTitleListener.NONE, config)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(PROBE_BYTES);
            byte[] chunk = new byte[1024];
            while (out.size() < PROBE_BYTES) {
                int n = source.read(chunk, 0, Math.min(chunk.length, PROBE_BYTES - out.size()));
                if (n < 0) {
                    break;
                }
                out.write(chunk, 0, n);
            }
            return out.toByteArray();
        }
    }
}
