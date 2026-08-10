package mmmm.server;

import mmmm.block.RadioBlockEntity;
import mmmm.core.relay.RelayManager;
import mmmm.core.relay.RelaySession;
import mmmm.core.transport.MediaTransport;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Server-side lifecycle for every radio in the world.
 *
 * <p>Two independent jobs, deliberately separate (see {@link RelayManager}):
 * <ul>
 *   <li><b>Sessions follow blocks.</b> A radio that is switched on holds a claim on its station's
 *       upstream connection, and drops it when switched off, broken or unloaded.</li>
 *   <li><b>Transmission follows players.</b> Only players in earshot receive frames, which is the
 *       main bandwidth lever the positional design gives us for free (master plan §5.4).</li>
 * </ul>
 *
 * <p>Loader-agnostic: it drives {@code :core} and Minecraft, and nothing else. Each loader supplies
 * the {@link RelayManager} because building one needs a
 * {@link mmmm.core.transport.MediaTransport}, which is where the loader's packet API lives.
 */
public final class RadioServer {

    /**
     * Attenuation range of the sound, in blocks. Matches the {@code attenuation_distance} vanilla
     * gives a sound by default, which is what actually decides where the radio stops being audible.
     */
    private static final int AUDIBLE_RANGE = 16;

    /** Subscribe a little before a player can hear anything, so the buffer is full when they can. */
    private static final double SUBSCRIBE_RADIUS_SQ = square(AUDIBLE_RANGE + 8);

    /**
     * Unsubscribe well after. The gap is hysteresis: without it a player standing on the boundary
     * subscribes and unsubscribes every proximity round, which costs a StreamOpen with a full
     * backlog each time.
     */
    private static final double UNSUBSCRIBE_RADIUS_SQ = square(AUDIBLE_RANGE + 16);

    /** Proximity is recomputed at this interval. Players do not move 8 blocks in a second. */
    private static final int PROXIMITY_INTERVAL_TICKS = 20;

    private static RelayManager manager;

    /**
     * Players who should be receiving each session, accumulated during a proximity round.
     *
     * <p>Built up as blocks tick and applied once at the end of the tick, because the answer is a
     * property of the session — the union over every block playing that station — and no single
     * block can compute it.
     */
    private static final Map<Integer, Set<MediaTransport.SubscriberId>> desired = new HashMap<>();

    private static boolean proximityRoundOpen;

    private RadioServer() {
    }

    public static void install(RelayManager relayManager) {
        manager = relayManager;
    }

    public static RelayManager manager() {
        return manager;
    }

    /** Closes every upstream connection. Server stop, and world unload in singleplayer. */
    public static void shutdown() {
        if (manager != null) {
            manager.close();
            manager = null;
        }
        desired.clear();
        proximityRoundOpen = false;
    }

    /** Drive from the end of the server tick, after every block entity has ticked. */
    public static void serverTick() {
        if (manager == null) {
            return;
        }
        if (proximityRoundOpen) {
            for (RelaySession session : manager.sessions()) {
                session.syncSubscribers(desired.getOrDefault(session.sessionId(), Set.of()));
            }
            desired.clear();
            proximityRoundOpen = false;
        }
        manager.tick();
    }

    /** Drive from the radio's block entity ticker, server side. */
    public static void tickBlock(RadioBlockEntity radio) {
        if (manager == null || !(radio.getLevel() instanceof ServerLevel level)) {
            return;
        }

        if (!radio.isPlaying()) {
            release(radio);
            return;
        }

        URI wanted = parseStation(radio.getStation());
        if (wanted == null) {
            // An unparseable URL cannot be retried into working, and leaving the block "playing"
            // would retry it every tick forever.
            radio.setPlaying(false);
            release(radio);
            return;
        }

        RelaySession session = radio.getServerSession();
        if (session == null || !wanted.equals(radio.getHeldStation())) {
            release(radio);
            session = manager.acquire(wanted);
            radio.setServerSession(session, wanted);
        }
        radio.setSessionId(session.sessionId());

        if (level.getGameTime() % PROXIMITY_INTERVAL_TICKS == 0) {
            collectListeners(level, radio, session);
        }
    }

    /** Adds every player this block can be heard by to the session's desired set. */
    private static void collectListeners(ServerLevel level, RadioBlockEntity radio, RelaySession session) {
        proximityRoundOpen = true;
        Set<MediaTransport.SubscriberId> forSession =
                desired.computeIfAbsent(session.sessionId(), key -> new HashSet<>());

        double x = radio.getBlockPos().getX() + 0.5;
        double y = radio.getBlockPos().getY() + 0.5;
        double z = radio.getBlockPos().getZ() + 0.5;

        for (ServerPlayer player : level.players()) {
            PlayerSubscriber subscriber = new PlayerSubscriber(player.getUUID());
            // Hysteresis: a player already listening keeps listening out to the wider radius.
            double limit = session.hasSubscriber(subscriber) ? UNSUBSCRIBE_RADIUS_SQ : SUBSCRIBE_RADIUS_SQ;
            if (player.distanceToSqr(x, y, z) <= limit) {
                forSession.add(subscriber);
            }
        }
    }

    /** Broken, unloaded, or switched off. */
    public static void blockRemoved(RadioBlockEntity radio) {
        release(radio);
    }

    /** A player disconnected; no session may keep sending to them. */
    public static void playerLeft(UUID uuid) {
        if (manager != null) {
            manager.removeSubscriberEverywhere(new PlayerSubscriber(uuid));
        }
    }

    private static void release(RadioBlockEntity radio) {
        URI held = radio.getHeldStation();
        if (held == null) {
            return;
        }
        radio.setServerSession(null, null);
        radio.setSessionId(RadioBlockEntity.NO_SESSION);
        if (manager != null) {
            manager.release(held);
        }
    }

    private static URI parseStation(String url) {
        try {
            URI uri = URI.create(url.trim());
            return uri.getHost() == null ? null : uri;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Sessions currently open, for the {@code /mmmm} status readout and for tests. */
    public static List<RelaySession> sessions() {
        return manager == null ? List.of() : new ArrayList<>(manager.sessions());
    }

    private static double square(double value) {
        return value * value;
    }
}
