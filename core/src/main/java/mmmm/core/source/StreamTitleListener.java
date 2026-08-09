package mmmm.core.source;

/**
 * Receives "now playing" titles as the origin announces them.
 *
 * <p>Called from the relay session's own thread, never from the game thread. Implementations must
 * not block: a slow listener stalls the read loop and starves every client on that session.
 */
@FunctionalInterface
public interface StreamTitleListener {

    /** A no-op listener, for callers that do not care about titles. */
    StreamTitleListener NONE = title -> { };

    /**
     * @param title the {@code StreamTitle} value, already unquoted; may be empty when the station
     *              sends a metadata block with no title
     */
    void onStreamTitle(String title);
}
