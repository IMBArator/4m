package mmmm.core.source;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpHeadTest {

    private static InputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.ISO_8859_1));
    }

    /**
     * The response that excludes every JDK HTTP client and is the whole reason for ADR-0009.
     */
    @Test
    void parsesTheShoutcastIcyStatusLine() throws IOException {
        HttpHead head = HttpHead.parse(stream("""
                ICY 200 OK\r
                icy-name:Example Radio\r
                icy-genre:Jazz\r
                icy-br:128\r
                icy-metaint:16000\r
                content-type:audio/mpeg\r
                \r
                """), 8192);

        assertTrue(head.isIcyProtocol());
        assertTrue(head.isSuccess());
        assertEquals(200, head.statusCode());
        assertEquals(16000, head.metaInt());

        SourceMetadata metadata = head.toMetadata();
        assertEquals("Example Radio", metadata.name().orElseThrow());
        assertEquals("Jazz", metadata.genre().orElseThrow());
        assertEquals("audio/mpeg", metadata.contentType().orElseThrow());
        assertEquals(128, metadata.bitrateKbps());
    }

    @Test
    void parsesAnOrdinaryHttpStatusLine() throws IOException {
        HttpHead head = HttpHead.parse(stream("""
                HTTP/1.1 200 OK\r
                Content-Type: audio/aacp\r
                icy-metaint: 8192\r
                \r
                """), 8192);

        assertFalse(head.isIcyProtocol());
        assertTrue(head.isSuccess());
        assertEquals(8192, head.metaInt());
        assertEquals("audio/aacp", head.toMetadata().contentType().orElseThrow());
    }

    @Test
    void leavesTheStreamPositionedAtTheFirstBodyByte() throws IOException {
        // A BufferedReader here would swallow the start of the audio with no way to give it back,
        // which is why parsing reads one byte at a time.
        InputStream in = stream("ICY 200 OK\r\nicy-metaint:100\r\n\r\nAUDIO");

        HttpHead.parse(in, 8192);

        byte[] rest = in.readAllBytes();
        assertEquals("AUDIO", new String(rest, StandardCharsets.ISO_8859_1));
    }

    @Test
    void acceptsBareLineFeedsWithoutCarriageReturns() throws IOException {
        HttpHead head = HttpHead.parse(stream("ICY 200 OK\nicy-br:64\n\n"), 8192);

        assertEquals(200, head.statusCode());
        assertEquals(64, head.toMetadata().bitrateKbps());
    }

    @Test
    void matchesHeaderNamesCaseInsensitively() throws IOException {
        HttpHead head = HttpHead.parse(stream("HTTP/1.1 200 OK\r\nICY-MetaInt: 4096\r\n\r\n"), 8192);

        assertEquals(4096, head.metaInt());
        assertEquals("4096", head.header("icy-metaint").orElseThrow());
    }

    @Test
    void recognisesRedirects() throws IOException {
        HttpHead head = HttpHead.parse(stream("""
                HTTP/1.1 302 Found\r
                Location: http://example.com/stream\r
                \r
                """), 8192);

        assertTrue(head.isRedirect());
        assertFalse(head.isSuccess());
        assertEquals("http://example.com/stream", head.header("location").orElseThrow());
    }

    @Test
    void reportsNoMetaIntWhenTheOriginOmitsIt() throws IOException {
        HttpHead head = HttpHead.parse(stream("ICY 200 OK\r\n\r\n"), 8192);

        assertEquals(0, head.metaInt(), "0 must mean pass-through, not a parse failure");
    }

    @Test
    void skipsMalformedHeaderLines() throws IOException {
        // Some Shoutcast builds emit stray lines. Dropping the connection over one would be a
        // worse outcome than ignoring it.
        HttpHead head = HttpHead.parse(stream("""
                ICY 200 OK\r
                this line has no colon\r
                icy-br:192\r
                \r
                """), 8192);

        assertEquals(192, head.toMetadata().bitrateKbps());
    }

    @Test
    void rejectsANonHttpResponse() {
        assertThrows(IOException.class, () -> HttpHead.parse(stream("GARBAGE\r\n\r\n"), 8192));
    }

    @Test
    void rejectsANonNumericStatusCode() {
        assertThrows(IOException.class, () -> HttpHead.parse(stream("HTTP/1.1 OK OK\r\n\r\n"), 8192));
    }

    @Test
    void enforcesTheHeadSizeCap() {
        // Without a cap, an origin could stream headers forever and exhaust memory.
        StringBuilder endless = new StringBuilder("ICY 200 OK\r\n");
        for (int i = 0; i < 500; i++) {
            endless.append("x-filler-").append(i).append(": ").append("y".repeat(100)).append("\r\n");
        }

        assertThrows(IOException.class, () -> HttpHead.parse(stream(endless.toString()), 1024));
    }

    @Test
    void rejectsAConnectionClosedDuringHeaders() {
        assertThrows(IOException.class, () -> HttpHead.parse(stream("ICY 200 OK\r\nicy-br:128\r\n"), 8192));
    }

    @Test
    void stripsParametersFromContentType() throws IOException {
        HttpHead head = HttpHead.parse(
                stream("HTTP/1.1 200 OK\r\nContent-Type: audio/mpeg; charset=utf-8\r\n\r\n"), 8192);

        assertEquals("audio/mpeg", head.toMetadata().contentType().orElseThrow());
    }
}
