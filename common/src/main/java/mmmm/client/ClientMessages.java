package mmmm.client;

import mmmm.core.media.MediaFrame;
import mmmm.core.media.StreamInfo;

import java.util.List;

/**
 * Plain data carriers for the messages the client receives and sends over the media channel.
 *
 * <p>These are the loader-neutral half of {@link mmmm.forge.MmmmNetwork} (and its future NeoForge
 * equivalent): the wire format and the {@code NetworkEvent.Context} handling are loader-specific,
 * so they live in the loader modules. But the data itself is just {@code :core} types plus
 * primitives, so it can — and must — live here, where shared code can see it without depending back
 * on a loader package (ADR-0002).
 *
 * <p>Records, deliberately: the encode/decode/handle triple in {@code MmmmNetwork} is the only
 * place that mutates these, and a record makes "this is a value" unmistakable.
 */
public final class ClientMessages {

    private ClientMessages() {
    }

    /** Server → client: a session is live; here is how to start decoding it, plus the backlog. */
    public record StreamOpen(
            int sessionId,
            String originName,
            List<StreamInfo> streams,
            long epochNanos,
            int presentationDelayMs,
            List<MediaFrame> backlog) {
    }

    /** Server → client: a batch of relayed frames. */
    public record StreamData(int sessionId, List<MediaFrame> frames) {
    }

    /** Server → client: a "now playing" title, stamped with when it becomes current. */
    public record StreamMeta(int sessionId, long ptsMicros, String title) {
    }

    /** Server → client: the session has gone away. */
    public record StreamClose(int sessionId) {
    }

    /** Server → client: the server's half of the clock exchange. */
    public record ClockPong(long clientNanos, long serverNanos) {
    }

    /** Client → server: the client's half of the clock exchange. */
    public record ClockPing(long clientNanos) {
    }
}
