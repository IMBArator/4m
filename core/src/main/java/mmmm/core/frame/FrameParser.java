package mmmm.core.frame;

import mmmm.core.media.MediaFrame;
import mmmm.core.media.StreamInfo;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * Splits an encoded byte stream into frames and stamps each with a presentation timestamp.
 *
 * <p>This is what makes synchronisation possible, and it is why the server can do it without a
 * codec on its classpath (ADR-0004). Splitting on arbitrary byte boundaries would leave frames of
 * unknown duration; parsing the container's headers yields an exact duration per frame, so the
 * server can build a sample-accurate timeline from header arithmetic alone.
 *
 * <p>Push-based: feed bytes as they arrive, receive whole frames. Implementations buffer whatever
 * partial frame is left over.
 *
 * <p>Server side. Not thread-safe — one parser per relay session thread.
 */
public interface FrameParser {

    /**
     * Consumes bytes and emits every complete frame found.
     *
     * <p>The consumer is called synchronously and must not block; it feeds the relay fan-out, and
     * a slow consumer stalls the session for every listener on it.
     */
    void feed(byte[] data, int off, int len, Consumer<MediaFrame> out);

    /**
     * Stream parameters, once enough has been parsed to know them.
     *
     * <p>Empty until the first frame header is read. For Vorbis it stays empty until all three
     * codec-init packets have been captured, because a client cannot use the info without them.
     */
    Optional<StreamInfo> streamInfo();

    /**
     * Timestamp the next frame would carry, in microseconds.
     *
     * <p>Exposed for the backlog ring, which needs to know how far the timeline has advanced
     * without waiting for another frame.
     */
    long currentPtsMicros();
}
