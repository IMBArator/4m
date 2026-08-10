package mmmm.core.relay;

import mmmm.core.transport.MediaTransport;

import java.io.Closeable;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Owns every {@link RelaySession} on the server and keeps exactly one per station.
 *
 * <h2>Two counts, deliberately not one</h2>
 * A session is refcounted by <em>blocks</em>, not by listeners. Those are different lifetimes and
 * conflating them produces the wrong behaviour in both directions: tie the upstream connection to
 * nearby players and every player who wanders off and back pays a fresh connect plus the settling
 * window, turning a working radio into several seconds of silence; tie it to nothing and the server
 * sits as a phantom listener on someone else's station forever.
 *
 * <p>So {@link #acquire}/{@link #release} follow blocks that are switched on, and
 * {@link RelaySession#addSubscriber} follows players in earshot. The upstream socket closes when the
 * last block lets go.
 *
 * <p>Thread-safe; every method is called from the server thread.
 */
public final class RelayManager implements Closeable {

    /**
     * Ticks between media sends. Two rather than one: the same bytes in half the packets, with
     * headers amortised and writes that stay smooth instead of bursty (master plan §5.3).
     */
    public static final int SEND_INTERVAL_TICKS = 2;

    private final SourceOpener opener;
    private final RelayConfig config;
    private final MediaTransport transport;

    private final Map<String, Entry> byStation = new LinkedHashMap<>();
    private final Map<Integer, RelaySession> byId = new HashMap<>();

    private int nextSessionId = 1;
    private int tickCounter;

    public RelayManager(SourceOpener opener, RelayConfig config, MediaTransport transport) {
        this.opener = opener;
        this.config = config;
        this.transport = transport;
    }

    /**
     * Returns the session for a station, starting it if this is the first block to ask.
     *
     * <p>Every call must be paired with exactly one {@link #release}.
     */
    public synchronized RelaySession acquire(URI station) {
        String key = station.toString();
        Entry entry = byStation.get(key);
        if (entry == null) {
            RelaySession session = new RelaySession(nextSessionId++, station, opener, config, transport);
            entry = new Entry(session);
            byStation.put(key, entry);
            byId.put(session.sessionId(), session);
            session.start();
        }
        entry.refCount++;
        return entry.session;
    }

    /** Drops one block's claim, closing the upstream connection when the last one goes. */
    public synchronized void release(URI station) {
        String key = station.toString();
        Entry entry = byStation.get(key);
        if (entry == null) {
            return;
        }
        entry.refCount--;
        if (entry.refCount <= 0) {
            byStation.remove(key);
            byId.remove(entry.session.sessionId());
            entry.session.close();
        }
    }

    /** Drive from the server tick. Sends accumulated frames every {@link #SEND_INTERVAL_TICKS}. */
    public synchronized void tick() {
        if (++tickCounter < SEND_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;
        for (Entry entry : byStation.values()) {
            entry.session.drain();
        }
    }

    /**
     * Drops a recipient from every session.
     *
     * <p>The disconnect path. A player who logs out while in earshot of three radios must not leave
     * three sessions holding a reference to a connection that no longer exists.
     */
    public synchronized void removeSubscriberEverywhere(MediaTransport.SubscriberId subscriber) {
        for (Entry entry : byStation.values()) {
            entry.session.removeSubscriber(subscriber);
        }
    }

    public synchronized RelaySession sessionById(int sessionId) {
        return byId.get(sessionId);
    }

    public synchronized List<RelaySession> sessions() {
        List<RelaySession> out = new ArrayList<>(byStation.size());
        for (Entry entry : byStation.values()) {
            out.add(entry.session);
        }
        return out;
    }

    public synchronized int sessionCount() {
        return byStation.size();
    }

    /** Blocks currently holding a claim on a station, or 0 if it has none. */
    public synchronized int refCount(URI station) {
        Entry entry = byStation.get(station.toString());
        return entry == null ? 0 : entry.refCount;
    }

    /** Closes every session. Server shutdown, and world unload in singleplayer. */
    @Override
    public synchronized void close() {
        Collection<Entry> entries = new ArrayList<>(byStation.values());
        byStation.clear();
        byId.clear();
        for (Entry entry : entries) {
            entry.session.close();
        }
    }

    private static final class Entry {
        private final RelaySession session;
        private int refCount;

        private Entry(RelaySession session) {
            this.session = session;
        }
    }
}
