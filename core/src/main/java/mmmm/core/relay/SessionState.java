package mmmm.core.relay;

/** Where a {@link RelaySession} is in its lifecycle. Surfaced to players in the status line. */
public enum SessionState {

    /** Opening the origin connection, or waiting out a reconnect backoff. */
    CONNECTING,

    /**
     * Connected and reading, but not yet relaying.
     *
     * <p>The session is measuring the origin's burst so it can place the stream epoch; see
     * {@link RelaySession} for why that cannot be done from the first frame.
     */
    BUFFERING,

    /** Relaying frames to subscribers. */
    PLAYING,

    /** The origin dropped or failed; retrying with backoff. Frames are not flowing. */
    RECONNECTING,

    /**
     * Given up permanently.
     *
     * <p>Only for failures that retrying cannot fix — a refused egress destination, or a codec no
     * client could decode. Network faults stay in {@link #RECONNECTING} indefinitely.
     */
    FAILED,

    /** Closed by us, because the last block referencing it went away. */
    CLOSED
}
