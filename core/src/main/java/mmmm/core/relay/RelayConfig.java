package mmmm.core.relay;

/**
 * Tuning for a relay session.
 *
 * @param presentationDelayMs   how far behind the live edge every client renders (ADR-0005). Must
 *                              exceed worst-case client jitter plus decode time.
 * @param backlogMarginMs       extra history kept beyond the presentation delay, so a client that
 *                              joins between two frames still receives a full window
 * @param initialBackoffMs      first reconnect delay after the origin drops
 * @param maxBackoffMs          ceiling the exponential backoff climbs to
 * @param stableBeforeResetMs   time a connection must survive before the backoff is considered
 *                              recovered and resets to {@code initialBackoffMs}
 * @param settleQuietMs         how long the epoch estimate must stop improving before the burst is
 *                              considered drained — see {@link RelaySession}
 * @param maxSettleBytes        cap on audio held while settling, so a badly behaved origin that
 *                              never stops bursting cannot exhaust the heap
 * @param readBufferBytes       size of the socket read buffer
 */
public record RelayConfig(
        int presentationDelayMs,
        int backlogMarginMs,
        long initialBackoffMs,
        long maxBackoffMs,
        long stableBeforeResetMs,
        long settleQuietMs,
        int maxSettleBytes,
        int readBufferBytes) {

    public static final RelayConfig DEFAULT = new RelayConfig(
            3_000,
            1_000,
            1_000,
            30_000,
            30_000,
            2_000,
            8 * 1024 * 1024,
            8192);

    public RelayConfig {
        if (presentationDelayMs <= 0) throw new IllegalArgumentException("presentationDelayMs must be positive");
        if (backlogMarginMs < 0) throw new IllegalArgumentException("backlogMarginMs must be >= 0");
        if (initialBackoffMs <= 0) throw new IllegalArgumentException("initialBackoffMs must be positive");
        if (maxBackoffMs < initialBackoffMs) throw new IllegalArgumentException("maxBackoffMs < initialBackoffMs");
        if (stableBeforeResetMs < 0) throw new IllegalArgumentException("stableBeforeResetMs must be >= 0");
        if (settleQuietMs <= 0) throw new IllegalArgumentException("settleQuietMs must be positive");
        if (maxSettleBytes <= 0) throw new IllegalArgumentException("maxSettleBytes must be positive");
        if (readBufferBytes <= 0) throw new IllegalArgumentException("readBufferBytes must be positive");
    }

    /** History the backlog ring holds, in microseconds. */
    public long backlogWindowMicros() {
        return (presentationDelayMs + backlogMarginMs) * 1000L;
    }

    public RelayConfig withPresentationDelayMs(int millis) {
        return new RelayConfig(millis, backlogMarginMs, initialBackoffMs, maxBackoffMs,
                stableBeforeResetMs, settleQuietMs, maxSettleBytes, readBufferBytes);
    }
}
