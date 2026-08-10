package mmmm.client;

import mmmm.core.media.MediaFrame;
import mmmm.core.media.StreamInfo;
import net.minecraft.core.BlockPos;

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

    /**
     * Client → server: what the player wants a radio set to.
     *
     * <p>Carries the whole intended state rather than a single action, so the handler is idempotent
     * and there is one validation path instead of three. The cost is that two players editing the
     * same radio in the same tick can overwrite each other, which for a radio block is not a problem
     * worth an action enum.
     *
     * <p><b>Nothing here is trusted.</b> This is the only message a client can send that changes
     * server state, and the channel registers its messages without direction enforcement — so any
     * connected client can send this for any position, with any string. {@code ServerNetwork}
     * re-checks reach, permission and the URL itself; the screen's own gating is cosmetic.
     */
    public record ConfigureRadio(BlockPos pos, String station, boolean playing, float volume) {
    }
}
