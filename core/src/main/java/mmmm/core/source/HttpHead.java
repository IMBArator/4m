package mmmm.core.source;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * A parsed response head, tolerant of the ICY protocol.
 *
 * <p>Shoutcast answers {@code ICY 200 OK} instead of {@code HTTP/1.1 200 OK}. That is not a
 * malformed edge case the JDK clients tolerate — it fails their status-line parse outright, which is
 * why we parse this ourselves (ADR-0009).
 *
 * <p>Parsing reads the underlying stream <b>one byte at a time</b>, deliberately. A
 * {@link java.io.BufferedReader} would read ahead into the audio body and there would be no way to
 * give those bytes back.
 */
public final class HttpHead {

    private final String protocol;
    private final int statusCode;
    private final String reasonPhrase;
    private final Map<String, String> headers;

    private HttpHead(String protocol, int statusCode, String reasonPhrase, Map<String, String> headers) {
        this.protocol = protocol;
        this.statusCode = statusCode;
        this.reasonPhrase = reasonPhrase;
        this.headers = headers;
    }

    /**
     * Reads and parses a response head, leaving the stream positioned at the first body byte.
     *
     * @param maxBytes cap on the whole head, so an origin cannot stream headers indefinitely
     */
    public static HttpHead parse(InputStream in, int maxBytes) throws IOException {
        Counter budget = new Counter(maxBytes);

        String statusLine = readLine(in, budget);
        if (statusLine == null || statusLine.isEmpty()) {
            throw new IOException("Origin closed the connection before sending a status line");
        }

        // "ICY 200 OK" and "HTTP/1.1 200 OK" both split into protocol / code / reason.
        String[] parts = statusLine.split(" ", 3);
        if (parts.length < 2) {
            throw new IOException("Unparseable status line: " + statusLine);
        }
        String protocol = parts[0];
        if (!protocol.startsWith("HTTP/") && !protocol.equalsIgnoreCase("ICY")) {
            throw new IOException("Not an HTTP or ICY response, status line was: " + statusLine);
        }
        int code;
        try {
            code = Integer.parseInt(parts[1].trim());
        } catch (NumberFormatException e) {
            throw new IOException("Non-numeric status code in: " + statusLine);
        }
        String reason = parts.length > 2 ? parts[2].trim() : "";

        Map<String, String> headers = new LinkedHashMap<>();
        String line;
        while ((line = readLine(in, budget)) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                // Some Shoutcast builds emit stray lines. Skipping beats failing the connection.
                continue;
            }
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            headers.putIfAbsent(name, value);
        }
        if (line == null) {
            throw new EOFException("Origin closed the connection during headers");
        }
        return new HttpHead(protocol, code, reason, headers);
    }

    /** Reads a CRLF- or LF-terminated line as ISO-8859-1. Returns null at end of stream. */
    private static String readLine(InputStream in, Counter budget) throws IOException {
        StringBuilder sb = new StringBuilder(64);
        int b;
        while ((b = in.read()) != -1) {
            budget.take();
            if (b == '\n') {
                int len = sb.length();
                if (len > 0 && sb.charAt(len - 1) == '\r') {
                    sb.setLength(len - 1);
                }
                return sb.toString();
            }
            sb.append((char) (b & 0xFF));
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    public String protocol() {
        return protocol;
    }

    public int statusCode() {
        return statusCode;
    }

    public String reasonPhrase() {
        return reasonPhrase;
    }

    /** True when the origin used the Shoutcast {@code ICY} status line rather than HTTP. */
    public boolean isIcyProtocol() {
        return protocol.equalsIgnoreCase("ICY");
    }

    public Optional<String> header(String name) {
        return Optional.ofNullable(headers.get(name.toLowerCase(Locale.ROOT)));
    }

    public OptionalInt intHeader(String name) {
        Optional<String> raw = header(name);
        if (raw.isEmpty()) {
            return OptionalInt.empty();
        }
        try {
            return OptionalInt.of(Integer.parseInt(raw.get().trim()));
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }

    public Map<String, String> headers() {
        return Map.copyOf(headers);
    }

    public boolean isRedirect() {
        return switch (statusCode) {
            case 301, 302, 303, 307, 308 -> true;
            default -> false;
        };
    }

    public boolean isSuccess() {
        return statusCode == 200;
    }

    /** How often the origin injects a metadata block, or 0 when it sends none. */
    public int metaInt() {
        return intHeader("icy-metaint").orElse(0);
    }

    public SourceMetadata toMetadata() {
        return new SourceMetadata(
                header("icy-name").map(String::trim).filter(s -> !s.isEmpty()),
                header("icy-genre").map(String::trim).filter(s -> !s.isEmpty()),
                header("content-type").map(s -> s.split(";")[0].trim().toLowerCase(Locale.ROOT)),
                intHeader("icy-br").orElse(0));
    }

    @Override
    public String toString() {
        return protocol + " " + statusCode + " " + reasonPhrase + " " + headers;
    }

    /** Enforces the head size cap without threading a counter through every signature. */
    private static final class Counter {
        private final int max;
        private int used;

        Counter(int max) {
            this.max = max;
        }

        void take() throws IOException {
            if (++used > max) {
                throw new IOException("Response head exceeded " + max + " bytes");
            }
        }
    }
}
