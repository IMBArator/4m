package mmmm.core.relay;

/**
 * Tuning for a relay session.
 *
 * @param presentationDelayMs   how far behind the live edge every client renders (ADR-0005). Must
 *                              exceed worst-case client jitter plus decode time — <b>and the
 *                              client's own output queue</b>, which is the constraint that actually
 *                              binds. See {@link #MIN_USEFUL_PRESENTATION_DELAY_MS}.
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

    /**
     * Below this, the presentation delay cannot be honoured at all.
     *
     * <p>Minecraft's OpenAL channel queues {@code QUEUED_BUFFER_COUNT × BUFFER_DURATION_SECONDS}
     * = 4 × 1 s of audio, and {@code attachBufferStream} demands all four the instant playback
     * starts. A client cannot hold audio back by less than its own audio stack buffers: the ring is
     * drained dry at startup, the shortfall is padded with silence, and that silence offsets the
     * timeline permanently.
     *
     * <p>This was measured, not reasoned about. At a 3 s delay the readout showed a rock-steady
     * {@code drift +1119ms} — almost exactly the 1 s the 4 s pump could not get from a 3 s ring —
     * with a hard resync firing on every tick and unable to help, because a resync corrects by
     * discarding and this error needs audio that was never there.
     *
     * <p>The saving grace for sync is that 4 s is a vanilla constant, identical on every client, so
     * it shifts everyone equally rather than spreading them out.
     */
    public static final int MIN_USEFUL_PRESENTATION_DELAY_MS = 4_000;

    public static final RelayConfig DEFAULT = new RelayConfig(
            // Comfortably above the 4 s output queue, leaving ~2 s of ring to absorb jitter. The
            // cost is a longer wait before audio starts, which is the right trade for a radio:
            // being late together is the entire point, and being early alone is the failure.
            6_000,
            1_000,
            1_000,
            30_000,
            30_000,
            2_000,
            8 * 1024 * 1024,
            8192);

    public RelayConfig {
        if (presentationDelayMs <= 0) throw new IllegalArgumentException("presentationDelayMs must be positive");
        // A warning rather than a rejection: a shorter delay is legitimate for a test that never
        // reaches a real sound device, and :core must not know what a client's audio stack is. But
        // shipping one silently would reintroduce a bug that took a purpose-built readout to find.
        if (presentationDelayMs < MIN_USEFUL_PRESENTATION_DELAY_MS) {
            System.getLogger(RelayConfig.class.getName()).log(System.Logger.Level.WARNING,
                    "presentationDelayMs " + presentationDelayMs + " is below the client's "
                            + MIN_USEFUL_PRESENTATION_DELAY_MS + " ms output queue; playback will be "
                            + "padded with silence at startup and will never reach the shared clock");
        }
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
