package mmmm.core.codec;

import mmmm.core.media.MediaFrame;

import java.io.Closeable;
import java.util.Optional;

/**
 * Turns encoded {@link MediaFrame}s into PCM. Client side only.
 *
 * <h2>Why the interface looks like this</h2>
 * Three constraints shaped it, and each rules out an obvious simpler signature:
 *
 * <ul>
 *   <li><b>No {@code byte[] decode(MediaFrame)}.</b> One input frame does not map to one output
 *       buffer. HE-AAC's SBR emits at twice the declared rate, chained Ogg streams restart mid-flow,
 *       and a native video decoder may buffer several frames before producing anything. Output goes
 *       to a {@link PcmSink} instead, called zero or more times per input.</li>
 *   <li><b>No assumption of pure Java.</b> The Vorbis implementation wraps Minecraft's STB-backed
 *       {@code OggAudioStream} and so lives in {@code common/}, not here; video will be native too.
 *       Nothing in this interface may assume a decoder is allocation-free or JVM-only.</li>
 *   <li><b>No thread-affinity guarantee.</b> A native decoder may be pinned to the thread that
 *       created it. One instance therefore belongs to one thread — the session's decode thread —
 *       and implementations need not be thread-safe.</li>
 * </ul>
 *
 * <h2>Errors are expected, not exceptional</h2>
 * Live radio delivers corrupt frames. A decoder must resynchronise and keep going rather than
 * throw: one bad frame is a click, and a thrown exception is the end of the evening's listening.
 * {@link #decode} therefore declares no checked exception, and implementations count what they
 * dropped instead.
 */
public interface Decoder extends Closeable {

    /**
     * Decodes one frame, passing any resulting PCM to {@code sink}.
     *
     * <p>Never throws for bad input. A frame that cannot be decoded is skipped, counted by
     * {@link #framesDropped()}, and decoding continues with the next one.
     */
    void decode(MediaFrame frame, PcmSink sink);

    /**
     * The output format, once known.
     *
     * <p>Empty until the first frame has been decoded — the format comes from the bitstream, not
     * from any header the caller has. Take the format from here rather than from
     * {@code StreamInfo}: for HE-AAC the ADTS header declares the base rate and the decoder emits
     * double it, so the two genuinely disagree.
     */
    Optional<PcmFormat> format();

    /**
     * Drops buffered state and prepares to decode from an arbitrary new position.
     *
     * <p>The hard-resync path: the drift controller has decided playback is too far out to trim and
     * the session is jumping to a new timestamp, so anything held from before the jump is stale.
     */
    void reset();

    /** Frames that could not be decoded. Steady growth means a genuinely bad stream. */
    long framesDropped();

    @Override
    void close();
}
