package mmmm.core.source;

import mmmm.core.security.EgressDeniedException;
import mmmm.core.security.EgressGuard;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;

/**
 * Reads an Icecast or Shoutcast stream over a hand-rolled HTTP/1.0 client. See ADR-0009.
 *
 * <p>Server side. Produces encoded bytes with inline metadata already stripped; never decodes.
 *
 * <h2>Why not a JDK client</h2>
 * Shoutcast replies {@code ICY 200 OK}, which both {@code HttpURLConnection} and
 * {@code java.net.http.HttpClient} reject outright. Owning the socket also gives us the two things
 * ADR-0011 needs: connecting to an address that has already been validated rather than re-resolving
 * a hostname, and re-validating after every redirect.
 *
 * <h2>Burst on connect</h2>
 * Icecast hands a new listener its whole buffer the instant the connection opens, then throttles to
 * realtime. Measured against real stations that burst runs from a few seconds to over thirty, so a
 * relay session receives a large block of audio at once before settling down. Anything downstream
 * that assumes bytes arrive at roughly the stream bitrate — a backlog ring, a bandwidth estimate, a
 * media-versus-wall-clock check — has to expect it.
 */
public final class IcyHttpSource implements StreamSource {

    private final Socket socket;
    private final IcyMetadataStream body;
    private final SourceMetadata metadata;
    private final URI finalUri;

    private IcyHttpSource(Socket socket, IcyMetadataStream body, SourceMetadata metadata, URI finalUri) {
        this.socket = socket;
        this.body = body;
        this.metadata = metadata;
        this.finalUri = finalUri;
    }

    public static IcyHttpSource open(URI uri, EgressGuard guard, StreamTitleListener listener)
            throws IOException {
        return open(uri, guard, listener, SourceConfig.DEFAULT);
    }

    /**
     * Connects, follows redirects, and returns a source positioned at the first audio byte.
     *
     * @throws EgressDeniedException if the destination — or any redirect target — is refused
     */
    public static IcyHttpSource open(URI uri, EgressGuard guard, StreamTitleListener listener,
                                     SourceConfig config) throws IOException {
        URI current = uri;

        for (int hop = 0; hop <= config.maxRedirects(); hop++) {
            // Re-validated on every hop. An allowlisted host redirecting to the cloud metadata
            // endpoint is the bypass this closes (ADR-0011).
            InetAddress address = guard.check(current);

            Socket socket = connect(current, address, config);
            boolean handedOff = false;
            try {
                sendRequest(socket, current, config);

                InputStream in = new BufferedInputStream(socket.getInputStream(), 16 * 1024);
                HttpHead head = HttpHead.parse(in, config.maxHeaderBytes());

                if (head.isRedirect()) {
                    String location = head.header("location")
                            .orElseThrow(() -> new IOException(
                                    "Origin returned " + head.statusCode() + " with no Location header"));
                    current = resolveRedirect(current, location);
                    continue;
                }
                if (!head.isSuccess()) {
                    throw new IOException("Origin returned " + head.statusCode() + " " + head.reasonPhrase());
                }

                IcyMetadataStream body = new IcyMetadataStream(in, head.metaInt(), listener);
                handedOff = true;
                return new IcyHttpSource(socket, body, head.toMetadata(), current);
            } finally {
                if (!handedOff) {
                    closeQuietly(socket);
                }
            }
        }
        throw new IOException("Exceeded " + config.maxRedirects() + " redirects starting from " + uri);
    }

    private static Socket connect(URI uri, InetAddress address, SourceConfig config) throws IOException {
        boolean https = "https".equalsIgnoreCase(uri.getScheme());
        int port = uri.getPort() != -1 ? uri.getPort() : (https ? 443 : 80);

        Socket plain = new Socket();
        try {
            // Connect to the address the guard validated, NOT to the hostname. Re-resolving here
            // would reopen the DNS rebinding hole the guard exists to close.
            plain.connect(new InetSocketAddress(address, port), config.connectTimeoutMs());
            plain.setSoTimeout(config.readTimeoutMs());
            plain.setTcpNoDelay(true);

            if (!https) {
                return plain;
            }

            // The (Socket, host, port, autoClose) overload is declared on SSLSocketFactory, not on
            // the SocketFactory that getDefault() is typed as — hence the cast on the factory.
            // It layers TLS over the already-connected socket and sets SNI from the hostname.
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            SSLSocket ssl = (SSLSocket) factory.createSocket(plain, uri.getHost(), port, true);
            // SSLSocket does NOT verify that the certificate matches the hostname by default —
            // only HttpsURLConnection does that for you. Without this the TLS is decorative.
            SSLParameters params = ssl.getSSLParameters();
            params.setEndpointIdentificationAlgorithm("HTTPS");
            ssl.setSSLParameters(params);
            ssl.startHandshake();
            return ssl;
        } catch (IOException e) {
            closeQuietly(plain);
            throw e;
        }
    }

    private static void sendRequest(Socket socket, URI uri, SourceConfig config) throws IOException {
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null) {
            path = path + "?" + uri.getRawQuery();
        }
        String hostHeader = uri.getPort() != -1 ? uri.getHost() + ":" + uri.getPort() : uri.getHost();

        // HTTP/1.0 with an explicit close: these connections are single and endless, so keep-alive
        // and chunked transfer have nothing to offer.
        String request = "GET " + path + " HTTP/1.0\r\n"
                + "Host: " + hostHeader + "\r\n"
                + "User-Agent: " + config.userAgent() + "\r\n"
                + "Icy-MetaData: 1\r\n"
                + "Accept: */*\r\n"
                + "Connection: close\r\n"
                + "\r\n";

        OutputStream out = socket.getOutputStream();
        out.write(request.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    static URI resolveRedirect(URI base, String location) throws IOException {
        try {
            URI target = base.resolve(location.trim());
            if (target.getHost() == null) {
                throw new IOException("Redirect to a URL with no host: " + location);
            }
            return target;
        } catch (IllegalArgumentException e) {
            throw new IOException("Unparseable redirect target: " + location, e);
        }
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        return body.read(b, off, len);
    }

    @Override
    public SourceMetadata metadata() {
        return metadata;
    }

    /** The URL actually being read, after redirects. Useful in logs when a station moves. */
    public URI finalUri() {
        return finalUri;
    }

    @Override
    public void close() {
        closeQuietly(socket);
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Closing a socket we are abandoning; nothing useful to do with a failure here.
        }
    }

    /** Kept so {@link URISyntaxException} stays referenced for callers parsing station URLs. */
    public static URI parse(String url) throws IOException {
        try {
            return new URI(url.trim());
        } catch (URISyntaxException e) {
            throw new IOException("Not a valid URL: " + url, e);
        }
    }
}
