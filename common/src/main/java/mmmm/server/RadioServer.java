package mmmm.server;

import com.mojang.logging.LogUtils;
import mmmm.Stations;
import mmmm.block.RadioBlockEntity;
import mmmm.core.relay.RelayManager;
import mmmm.core.relay.RelaySession;
import mmmm.core.relay.SessionState;
import mmmm.core.security.EgressGuard;
import mmmm.core.transport.MediaTransport;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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

    private static final Logger LOGGER = LogUtils.getLogger();

    private static RelayManager manager;
    private static MinecraftServer server;

    /** What any player may reach: the shipped stations, and nothing else. */
    private static final EgressGuard SHIPPED = EgressGuard.allowing(Stations.allowedHosts());

    /**
     * What a station an operator has authorised may reach: any public host, ranges still blocked.
     *
     * <p>This is wider than "the host the operator typed", and it has to be. A station URL is
     * normally a playlist that names an endpoint on a different domain — {@code radiobob.de} sends
     * you to {@code regiocast.streamabc.net} — and that endpoint's hostname can vary between
     * requests, so it cannot be enumerated in advance. Authorising only the typed host makes the
     * feature refuse nearly every real station, which is exactly what it did on first use.
     *
     * <p>The security delta is small and worth being explicit about. The operator has already chosen
     * to stream from a host they do not control the contents of; letting that host's playlist name a
     * second <em>public</em> host adds little. What actually protects the server — refusal of
     * loopback, RFC1918, CGNAT and link-local, including the cloud metadata endpoint — is unchanged
     * and applies to every hop of the chain. That is why ADR-0011 keeps range blocking as a separate
     * layer rather than folding it into the allowlist.
     */
    private static final EgressGuard OPERATOR_AUTHORISED = EgressGuard.allowingAnyPublicHost();

    /**
     * Hosts an operator has authorised, snapshotted for the relay threads.
     *
     * <p>A field rather than a lookup, and not as an optimisation: the relay threads ask when they
     * connect, and answering means reading the world's {@link RadioAllowlist} through
     * {@code DimensionDataStorage}, which is not thread-safe. So it is refreshed on the server thread
     * and read as an immutable snapshot everywhere else.
     */
    private static volatile Set<String> authorisedHosts = Set.of();

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

    public static void install(MinecraftServer minecraftServer, RelayManager relayManager) {
        server = minecraftServer;
        manager = relayManager;
        refreshAuthorisedHosts();
    }

    public static RelayManager manager() {
        return manager;
    }

    /**
     * The policy a given station connects under.
     *
     * <p>Safe to call from any thread; see {@link #authorisedHosts}.
     *
     * <p>Keyed on the station a player entered, not on the host finally reached — the two differ for
     * most real stations, and the authorisation was granted for the former.
     */
    public static EgressGuard egressGuardFor(URI station) {
        String host = station.getHost();
        if (host != null && authorisedHosts.contains(host.toLowerCase(Locale.ROOT))) {
            return OPERATOR_AUTHORISED;
        }
        return SHIPPED;
    }

    /**
     * Permits a host for this world, from now on and across restarts.
     *
     * <p>Server thread only — it writes world data. Callers must already have established that the
     * player is allowed to do this; this method is the mechanism, not the policy.
     *
     * @return false if the list is full or there is no world to record it against
     */
    public static boolean authoriseHost(String host) {
        if (server == null) {
            return false;
        }
        if (!RadioAllowlist.get(server).authorise(host)) {
            return false;
        }
        refreshAuthorisedHosts();
        return true;
    }

    /**
     * Server thread only — {@link RadioAllowlist} reads world data.
     *
     * <p>Both server types load their levels before firing {@code ServerStartingEvent}, so the
     * overworld is there when {@link #install} calls this. The empty fallback is for the case where
     * that stops being true: no authorisations means only the shipped stations work, which is the
     * direction a security default should fail in.
     */
    private static void refreshAuthorisedHosts() {
        authorisedHosts = server == null || server.overworld() == null
                ? Set.of()
                : RadioAllowlist.get(server).hosts();
    }

    /** Closes every upstream connection. Server stop, and world unload in singleplayer. */
    public static void shutdown() {
        if (manager != null) {
            manager.close();
            manager = null;
        }
        server = null;
        authorisedHosts = Set.of();
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
            // A FAILED state is left standing on purpose: it is the only explanation the player gets
            // for why the radio stopped by itself, and clearing it here would erase that a tick later.
            if (radio.getSessionState() != SessionState.FAILED) {
                radio.setSessionState(null);
            }
            return;
        }

        URI wanted = parseStation(radio.getStation());
        if (wanted == null) {
            // An unparseable URL cannot be retried into working, and leaving the block "playing"
            // would retry it every tick forever.
            radio.setPlaying(false);
            radio.setSessionState(SessionState.FAILED);
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
        radio.setSessionState(session.state());

        if (session.state() == SessionState.FAILED) {
            // FAILED is terminal by design — the relay does not retry a refused destination or an
            // undecodable stream, because retrying cannot fix either. Without this the block would
            // sit "playing" forever, holding a session that will never produce a byte, and the only
            // symptom would be silence.
            LOGGER.warn("Radio at {} stopped: station {} failed permanently ({})",
                    radio.getBlockPos(), wanted, session.lastError());
            notifyConfigurer(radio, session.lastError());
            radio.setPlaying(false);
            release(radio);
            return;
        }

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

    /**
     * Tells whoever last configured this radio why it stopped.
     *
     * <p>The relay discovers the failure seconds after the player asked for it, on another thread,
     * so there is nothing to return from the packet handler. Without this the only account of what
     * went wrong is a line in the server log, which the player cannot see — and "Failed" on the
     * screen with no reason is barely better than silence.
     */
    private static void notifyConfigurer(RadioBlockEntity radio, String error) {
        UUID who = radio.getLastConfiguredBy();
        if (who == null || server == null) {
            return;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(who);
        if (player != null) {
            player.sendSystemMessage(Component.literal("Radio stopped: "
                    + (error == null ? "the station could not be played." : error))
                    .withStyle(ChatFormatting.RED));
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
