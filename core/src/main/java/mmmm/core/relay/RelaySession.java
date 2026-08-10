package mmmm.core.relay;

import mmmm.core.frame.FormatSniffer;
import mmmm.core.frame.FrameParser;
import mmmm.core.frame.FrameParsers;
import mmmm.core.media.Codec;
import mmmm.core.media.MediaFrame;
import mmmm.core.media.StreamInfo;
import mmmm.core.security.EgressDeniedException;
import mmmm.core.source.StreamSource;
import mmmm.core.transport.MediaTransport;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * One upstream connection, fanned out to many clients on a shared timeline.
 *
 * <p>A daemon thread reads the origin, splits it into frames and hands them to
 * {@link MediaTransport}. Frames are relayed unchanged — the server never decodes (ADR-0004).
 *
 * <h2>The epoch cannot be taken from the first frame</h2>
 * This is the subtle part, and getting it wrong produces a session where every client is seconds out
 * of position with no obvious cause.
 *
 * <p>Clients render the frame stamped {@code pts} at server time {@code epoch + pts}. The obvious
 * way to place that epoch is "server time when the first frame arrived", and it is wrong, because
 * Icecast hands a new listener its entire buffer the instant the socket opens — measured against
 * real stations, anywhere from a few seconds to over thirty — and only then throttles to realtime.
 * During that burst media time races ahead of wall time, so an epoch taken at the first frame places
 * every subsequent frame that much too far in the future.
 *
 * <p>What is invariant is {@code arrival - pts}: during the burst it falls steadily, and once the
 * origin settles to realtime it stops falling. So the epoch is the <em>minimum</em> of that
 * difference, and the session stays in {@link SessionState#BUFFERING} until it has stopped improving
 * for {@link RelayConfig#settleQuietMs()}. Same reasoning as the min-RTT clock filter: the quantity
 * is a floor plus a one-sided delay, so the minimum is the estimator, never the mean.
 *
 * <p>Frames received while settling are held and published once the epoch is known, rather than
 * discarded. That is what makes the burst useful instead of merely awkward: it <em>is</em> the
 * backlog window a joining client needs, delivered for free before anyone has asked for it.
 *
 * <h2>Reconnects keep the announced epoch</h2>
 * Subscribers were told the epoch when their stream opened, so a reconnect must not move it. The
 * fresh parser restarts its timeline at zero, so settling runs again and the result is applied as a
 * {@code ptsOffset} that lands the new frames back on the original epoch.
 *
 * <p>Thread-safe. The relay thread publishes; the game thread subscribes and drains.
 */
public final class RelaySession implements Closeable {

    /** Sentinel for "the epoch has never been placed", distinguishable from a real nanoTime. */
    private static final long EPOCH_UNSET = Long.MIN_VALUE;

    private final int sessionId;
    private final URI uri;
    private final SourceOpener opener;
    private final RelayConfig config;
    private final MediaTransport transport;
    private final LongSupplier nanoClock;

    /** Guards the backlog, the pending batch, the subscriber table and all timeline fields. */
    private final Object lock = new Object();

    private final FrameBacklog backlog;
    private final List<MediaFrame> pending = new ArrayList<>();

    /** Subscriber to "has been sent a StreamOpen". Insertion-ordered so sends are deterministic. */
    private final Map<MediaTransport.SubscriberId, Boolean> subscribers = new LinkedHashMap<>();

    private final Thread thread;

    // --- timeline state, guarded by lock ---
    private FrameParser parser;
    private StreamInfo streamInfo;
    private long epochNanos = EPOCH_UNSET;
    private long ptsOffsetMicros;
    private long lastPtsMicros;

    // --- settling state, guarded by lock ---
    private final List<MediaFrame> settleBuffer = new ArrayList<>();
    private long settleBufferBytes;
    private long epochCandidateNanos = Long.MAX_VALUE;
    private long lastImprovementNanos;
    private boolean settled;
    private String pendingTitle;

    private volatile SessionState state = SessionState.CONNECTING;
    private volatile String originName;
    private volatile String lastError;
    private volatile boolean closed;
    private volatile long framesRelayed;
    private volatile long bytesRelayed;

    public RelaySession(int sessionId, URI uri, SourceOpener opener, RelayConfig config,
                        MediaTransport transport) {
        this(sessionId, uri, opener, config, transport, System::nanoTime);
    }

    public RelaySession(int sessionId, URI uri, SourceOpener opener, RelayConfig config,
                        MediaTransport transport, LongSupplier nanoClock) {
        this.sessionId = sessionId;
        this.uri = uri;
        this.opener = opener;
        this.config = config;
        this.transport = transport;
        this.nanoClock = nanoClock;
        this.backlog = new FrameBacklog(config.backlogWindowMicros(), config.maxSettleBytes());

        this.thread = new Thread(this::runSession, "mmmm-relay-" + sessionId);
        // Daemon: a session left running must never be the reason a dedicated server refuses to exit.
        this.thread.setDaemon(true);
    }

    public void start() {
        thread.start();
    }

    // ------------------------------------------------------------------ subscribers

    /**
     * Adds a recipient. It receives a {@code StreamOpen} with the backlog on the next {@link #drain},
     * or immediately once the session reaches {@link SessionState#PLAYING} if it is still settling.
     */
    public void addSubscriber(MediaTransport.SubscriberId subscriber) {
        synchronized (lock) {
            subscribers.putIfAbsent(subscriber, Boolean.FALSE);
        }
    }

    public void removeSubscriber(MediaTransport.SubscriberId subscriber) {
        synchronized (lock) {
            Boolean opened = subscribers.remove(subscriber);
            if (Boolean.TRUE.equals(opened)) {
                transport.closeStream(subscriber, sessionId);
            }
        }
    }

    public int subscriberCount() {
        synchronized (lock) {
            return subscribers.size();
        }
    }

    public boolean hasSubscriber(MediaTransport.SubscriberId subscriber) {
        synchronized (lock) {
            return subscribers.containsKey(subscriber);
        }
    }

    /**
     * Makes the subscriber set exactly {@code wanted}.
     *
     * <p>Proximity has to be decided per session rather than per block, because two radios playing
     * the same station share one. Deciding it per block means the far one unsubscribes a player the
     * near one just subscribed, and which of the two ticks last determines whether anyone hears
     * anything — an intermittent fault that depends on block placement order.
     */
    public void syncSubscribers(Collection<MediaTransport.SubscriberId> wanted) {
        synchronized (lock) {
            for (MediaTransport.SubscriberId subscriber : wanted) {
                subscribers.putIfAbsent(subscriber, Boolean.FALSE);
            }
            List<MediaTransport.SubscriberId> stale = new ArrayList<>();
            for (MediaTransport.SubscriberId subscriber : subscribers.keySet()) {
                if (!wanted.contains(subscriber)) {
                    stale.add(subscriber);
                }
            }
            for (MediaTransport.SubscriberId subscriber : stale) {
                removeSubscriber(subscriber);
            }
        }
    }

    /**
     * Sends everything accumulated since the last call. Drive this from the server tick.
     *
     * <p>Batching is the point: at 128 kbps a two-tick interval carries about 1.6 KB, which is one
     * packet instead of dozens of frame-sized ones, and it keeps writes smooth rather than bursty
     * (master plan §5.3).
     */
    public void drain() {
        synchronized (lock) {
            if (streamInfo == null || !settled) {
                // Nothing can be announced before the format and the epoch are both known.
                return;
            }

            // Open newcomers first. The backlog snapshot already contains everything in `pending`,
            // so a subscriber opened here must be held back from this round's batch or it would
            // receive those frames twice.
            List<MediaTransport.SubscriberId> justOpened = null;
            for (Map.Entry<MediaTransport.SubscriberId, Boolean> entry : subscribers.entrySet()) {
                if (Boolean.TRUE.equals(entry.getValue())) {
                    continue;
                }
                transport.openStream(entry.getKey(), sessionId, originName == null ? uri.getHost() : originName,
                        List.of(streamInfo), epochNanos, config.presentationDelayMs(), backlog.snapshot());
                entry.setValue(Boolean.TRUE);
                if (justOpened == null) {
                    justOpened = new ArrayList<>(2);
                }
                justOpened.add(entry.getKey());
            }

            if (pending.isEmpty()) {
                return;
            }
            List<MediaFrame> batch = List.copyOf(pending);
            pending.clear();

            for (Map.Entry<MediaTransport.SubscriberId, Boolean> entry : subscribers.entrySet()) {
                if (Boolean.TRUE.equals(entry.getValue())
                        && (justOpened == null || !justOpened.contains(entry.getKey()))) {
                    transport.sendFrames(entry.getKey(), sessionId, batch);
                }
            }
        }
    }

    // ------------------------------------------------------------------ the relay thread

    private void runSession() {
        long backoffMs = config.initialBackoffMs();

        while (!closed) {
            long connectedAtNanos = 0;
            setState(SessionState.CONNECTING);
            try (StreamSource source = opener.open(uri, this::onTitle)) {
                connectedAtNanos = nanoClock.getAsLong();
                source.metadata().name().ifPresent(name -> originName = name);
                beginConnection();
                setState(SessionState.BUFFERING);
                pump(source);
            } catch (EgressDeniedException e) {
                // Configuration, not weather. Retrying cannot make an allowlist accept a host.
                fail("refused by the egress allowlist: " + e.getMessage());
                return;
            } catch (UnsupportedCodecException e) {
                fail(e.getMessage());
                return;
            } catch (IOException | RuntimeException e) {
                lastError = e.toString();
            }

            if (closed) {
                break;
            }
            // A connection that stayed up a long time before dropping is not a failing origin, it is
            // a normal disconnect. Starting the next backoff from the ceiling would then punish an
            // hours-long healthy session for one hiccup.
            if (connectedAtNanos != 0
                    && nanoClock.getAsLong() - connectedAtNanos >= config.stableBeforeResetMs() * 1_000_000L) {
                backoffMs = config.initialBackoffMs();
            }
            setState(SessionState.RECONNECTING);
            if (!sleep(backoffMs)) {
                break;
            }
            backoffMs = Math.min(backoffMs * 2, config.maxBackoffMs());
        }
        setState(SessionState.CLOSED);
    }

    /** Reads the origin until it ends or we are closed. */
    private void pump(StreamSource source) throws IOException {
        byte[] buffer = new byte[config.readBufferBytes()];
        byte[] sniffPrefix = new byte[FormatSniffer.RECOMMENDED_BYTES];
        int sniffed = 0;
        String contentType = source.metadata().contentType().orElse(null);

        while (!closed) {
            int n = source.read(buffer, 0, buffer.length);
            if (n < 0) {
                return;
            }
            if (n == 0) {
                continue;
            }
            bytesRelayed += n;

            if (parser == null) {
                int take = Math.min(n, sniffPrefix.length - sniffed);
                System.arraycopy(buffer, 0, sniffPrefix, sniffed, take);
                sniffed += take;
                if (sniffed < 4) {
                    continue;
                }
                Codec codec = FormatSniffer.sniffOrContentType(sniffPrefix, 0, sniffed, contentType)
                        .orElseThrow(() -> new UnsupportedCodecException(
                                "Unrecognised stream format at " + uri));
                parser = FrameParsers.forCodec(codec);
                // The sniff consumed nothing — these same bytes must still reach the parser below.
            }

            parser.feed(buffer, 0, n, this::onFrame);
        }
    }

    /** Resets everything that describes one connection, so a reconnect starts clean. */
    private void beginConnection() {
        synchronized (lock) {
            parser = null;
            settled = false;
            settleBuffer.clear();
            settleBufferBytes = 0;
            epochCandidateNanos = Long.MAX_VALUE;
            lastImprovementNanos = 0;
        }
    }

    /** Called on the relay thread for every frame the parser produces. */
    private void onFrame(MediaFrame raw) {
        long arrivalNanos = nanoClock.getAsLong();
        synchronized (lock) {
            if (settled) {
                publish(restamp(raw));
                return;
            }

            long candidate = arrivalNanos - raw.ptsMicros() * 1000L;
            if (candidate < epochCandidateNanos) {
                epochCandidateNanos = candidate;
                lastImprovementNanos = arrivalNanos;
            }
            settleBuffer.add(raw);
            settleBufferBytes += raw.size();

            boolean quietLongEnough =
                    arrivalNanos - lastImprovementNanos >= config.settleQuietMs() * 1_000_000L;
            // The byte cap is the guard against an origin that never stops bursting; without it a
            // misbehaving station could hold the whole session in BUFFERING while the heap fills.
            if (quietLongEnough || settleBufferBytes >= config.maxSettleBytes()) {
                settle();
            }
        }
    }

    /** Places the epoch, then releases everything held while measuring it. Caller holds the lock. */
    private void settle() {
        if (streamInfo == null && parser != null) {
            streamInfo = parser.streamInfo().orElse(null);
        }
        if (streamInfo == null) {
            // Vorbis withholds StreamInfo until all three codec-init packets are captured, and a
            // client cannot decode without them. Keep buffering rather than announcing a stream
            // nobody could start.
            return;
        }

        if (epochNanos == EPOCH_UNSET) {
            epochNanos = epochCandidateNanos;
            ptsOffsetMicros = 0;
        } else {
            // A reconnect. Subscribers already hold the original epoch, so the new timeline is
            // shifted onto it instead of the epoch being moved onto the new timeline.
            ptsOffsetMicros = (epochCandidateNanos - epochNanos) / 1000L;
        }
        settled = true;

        for (MediaFrame frame : settleBuffer) {
            publish(restamp(frame));
        }
        settleBuffer.clear();
        settleBufferBytes = 0;
        setState(SessionState.PLAYING);
    }

    private MediaFrame restamp(MediaFrame raw) {
        if (ptsOffsetMicros == 0) {
            return raw;
        }
        return new MediaFrame(raw.streamId(), raw.ptsMicros() + ptsOffsetMicros,
                raw.keyframe(), raw.payload());
    }

    /** Caller holds the lock. */
    private void publish(MediaFrame frame) {
        backlog.add(frame);
        pending.add(frame);
        lastPtsMicros = frame.ptsMicros();
        framesRelayed++;

        if (pendingTitle != null) {
            String title = pendingTitle;
            pendingTitle = null;
            for (Map.Entry<MediaTransport.SubscriberId, Boolean> entry : subscribers.entrySet()) {
                if (Boolean.TRUE.equals(entry.getValue())) {
                    transport.sendTitle(entry.getKey(), sessionId, frame.ptsMicros(), title);
                }
            }
        }
    }

    /**
     * A {@code StreamTitle} arrived in the metadata channel.
     *
     * <p>Held rather than sent, so it can be stamped with the timestamp of the next frame. Titles
     * arrive at {@code icy-metaint} boundaries in the byte stream, which is roughly where the new
     * track starts — sending it on arrival would flip the display seconds before the audio changes,
     * because every client is deliberately a presentation delay behind.
     */
    private void onTitle(String title) {
        synchronized (lock) {
            pendingTitle = title;
        }
    }

    // ------------------------------------------------------------------ lifecycle

    private void setState(SessionState next) {
        if (state != SessionState.FAILED && state != SessionState.CLOSED) {
            state = next;
        }
    }

    private void fail(String reason) {
        lastError = reason;
        state = SessionState.FAILED;
    }

    /** @return false if we were interrupted, meaning the session is shutting down */
    private boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public void close() {
        closed = true;
        state = SessionState.CLOSED;
        thread.interrupt();

        synchronized (lock) {
            for (Map.Entry<MediaTransport.SubscriberId, Boolean> entry : subscribers.entrySet()) {
                if (Boolean.TRUE.equals(entry.getValue())) {
                    transport.closeStream(entry.getKey(), sessionId);
                }
            }
            subscribers.clear();
            pending.clear();
            backlog.clear();
        }
    }

    /** Waits for the relay thread to exit. For shutdown paths and tests. */
    public void awaitStop(long millis) throws InterruptedException {
        thread.join(millis);
    }

    // ------------------------------------------------------------------ readouts

    public int sessionId() {
        return sessionId;
    }

    public URI uri() {
        return uri;
    }

    public SessionState state() {
        return state;
    }

    /** Station name from {@code icy-name}, falling back to the host. */
    public String originName() {
        String name = originName;
        return name != null ? name : uri.getHost();
    }

    /** Most recent failure, or null. Shown in the status line when reconnecting. */
    public String lastError() {
        return lastError;
    }

    public long framesRelayed() {
        return framesRelayed;
    }

    public long bytesRelayed() {
        return bytesRelayed;
    }

    /** Server time at which the frame stamped {@code pts = 0} is due. Meaningless until settled. */
    public long epochNanos() {
        synchronized (lock) {
            return epochNanos;
        }
    }

    public long lastPtsMicros() {
        synchronized (lock) {
            return lastPtsMicros;
        }
    }

    public int backlogFrames() {
        synchronized (lock) {
            return backlog.frameCount();
        }
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "RelaySession[%d, %s, %s, %d subs, %d frames]",
                sessionId, uri, state, subscriberCount(), framesRelayed);
    }
}
