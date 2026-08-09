package mmmm.core.transport;

import mmmm.core.media.MediaFrame;
import mmmm.core.media.StreamInfo;

import java.util.List;

/**
 * How relayed media reaches a client. See ADR-0006.
 *
 * <p>There is exactly one implementation today — Minecraft's own connection, via the loader's
 * packet channel — and this interface will therefore look like over-engineering. It is not.
 *
 * <p>Audio at 128 kbps fits comfortably down the game connection and costs server operators no
 * configuration at all. Video does not: it is 30–100× the bitrate, over a TCP socket shared with
 * gameplay, where head-of-line blocking turns a media stall into visible rubber-banding. When video
 * arrives it will very likely need a side channel, and retrofitting an interface through a codebase
 * that called the packet API directly from every site is exactly the kind of change that spreads
 * everywhere. Adopting it now, while there is nothing to migrate, keeps that decision cheap.
 *
 * <p>{@code :core} cannot see the loader's networking API, so that dependency direction makes the
 * violation impossible rather than merely discouraged.
 *
 * <p>Implementations must be safe to call from a relay session thread.
 */
public interface MediaTransport {

    /**
     * Announces a session to one subscriber and supplies what it needs to start decoding.
     *
     * @param backlog frames covering the presentation delay window, so the client starts
     *                immediately and already in sync instead of waiting out the delay. For video
     *                this must begin at a keyframe.
     */
    void openStream(SubscriberId subscriber,
                    int sessionId,
                    String originName,
                    List<StreamInfo> streams,
                    long streamEpochServerNanos,
                    int presentationDelayMs,
                    List<MediaFrame> backlog);

    /** Sends one frame to one subscriber. Called at the pacing interval, not per frame. */
    void sendFrames(SubscriberId subscriber, int sessionId, List<MediaFrame> frames);

    /**
     * Sends a "now playing" title.
     *
     * @param ptsMicros when the title becomes current, so the display changes in sync with the
     *                  audio rather than on arrival
     */
    void sendTitle(SubscriberId subscriber, int sessionId, long ptsMicros, String title);

    void closeStream(SubscriberId subscriber, int sessionId);

    /**
     * Opaque handle for one recipient.
     *
     * <p>{@code :core} must not know what a player is, so the loader modules wrap whatever
     * identifier they use.
     */
    interface SubscriberId {
    }
}
