package mmmm.client;

import com.mojang.logging.LogUtils;
import mmmm.block.RadioBlockEntity;
import mmmm.core.media.MediaFrame;
import mmmm.core.media.StreamInfo;
import mmmm.core.sync.ClockFilter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.LongConsumer;

/**
 * Owns every {@link ClientMediaSession} on this client, the shared clock estimate, and the per-tick
 * drift loop that keeps each session locked to that clock.
 *
 * <h2>Two cycles, both on the client thread</h2>
 * <ul>
 *   <li><b>Per block, per tick</b> via {@link #tickBlock}: ensure a session exists for the block,
 *       ensure a sound is playing, and step the drift controller.</li>
 *   <li><b>Once per tick</b> via {@link #onClientTick}: run the clock-ping cadence and reap blocks
 *       that have stopped ticking (chunk unload, dimension change, block broken).</li>
 * </ul>
 *
 * <h2>Why the clock is shared, not per-session</h2>
 * One server, one clock. Splitting the filter per session would mean every radio on the same client
 * converges at a different offset and every radio drifts independently — which is the same bug as
 * each client drifting, only inside one client. The relay exists to give every listener one shared
 * timeline; the clock filter mirrors that on the client.
 *
 * <p>All methods run on the client thread. Packet handlers enqueue onto it via
 * {@code NetworkEvent.Context.enqueueWork}; the block ticker is on it by construction. No locking.
 */
public final class ClientMedia {

    /** Pings per second of normal cadence; one every 5 s (master plan §5.1). */
    private static final int PING_INTERVAL_TICKS = 100;

    /** Burst size on join; converges the clock in well under a second instead of ~15 s. */
    private static final int PING_BURST = 8;

    /** Blocks this old without a tick are considered gone. Generous, to survive a slow chunk. */
    private static final long STALE_TICKS = 40;

    private static final Map<Integer, ClientMediaSession> sessions = new HashMap<>();
    private static final Map<Integer, Long> sessionCreatedTick = new HashMap<>();
    private static final Map<BlockPos, Integer> sessionByBlock = new HashMap<>();
    private static final Map<BlockPos, RadioSoundInstance> sounds = new HashMap<>();
    private static final Map<BlockPos, Long> lastSeenTick = new HashMap<>();
    private static final Map<Integer, String> titleBySession = new HashMap<>();

    private static ClockFilter clock = new ClockFilter();
    private static long tickCount;
    private static long nextPingTick;
    private static int pingBurstRemaining;

    /**
     * Sends one clock ping, given the client's {@code nanoTime()} stamp. Installed by the loader's
     * client setup; the body constructs the loader-specific message and ships it. Keeping the seam
     * here (rather than calling a loader class directly) is what keeps {@code common/}
     * loader-neutral — ADR-0002.
     */
    private static LongConsumer pingSender = clientNanos -> { };

    public static void setPingSender(LongConsumer sender) {
        pingSender = sender;
    }

    private ClientMedia() {
    }

    // ------------------------------------------------------------------ per-block tick

    /** Installed via {@code RadioBlock.setClientTicker} from the loader's client setup. */
    public static void tickBlock(RadioBlockEntity radio) {
        BlockPos pos = radio.getBlockPos();

        if (!radio.isPlaying() || radio.getSessionId() == RadioBlockEntity.NO_SESSION) {
            stopSound(pos);
            sessionByBlock.remove(pos);
            lastSeenTick.remove(pos);
            reapOrphanedSessions();
            return;
        }

        int sid = radio.getSessionId();
        lastSeenTick.put(pos, tickCount);
        sessionByBlock.put(pos, sid);

        ClientMediaSession session = sessions.get(sid);
        if (session == null) {
            // StreamOpen has not arrived yet — the block entity syncs faster than the relay settles.
            return;
        }

        if (!clock.isConverged()) {
            // Holding playback here is deliberate. Starting before the clock settles means starting
            // at the wrong position and hard-resyncing immediately, which is audible. A short
            // silence at join is not.
            return;
        }

        SoundManager sm = Minecraft.getInstance().getSoundManager();
        RadioSoundInstance sound = sounds.get(pos);

        // Warmup: wait until the ring has enough decoded audio for BOTH the drift controller's
        // initial HARD_RESYNC and OpenAL's initial pump.
        //
        // The first steer() triggers a HARD_RESYNC because the ring's playback position is at the
        // start of the backlog while the clock says "you're presentationDelay into the stream".
        // The resync fast-forwards the ring by ~presentationDelay seconds, so that much audio is
        // discarded. What remains must still fill OpenAL's initial pump (QUEUED_BUFFER_COUNT(4) ×
        // BUFFER_DURATION_SECONDS(1) = 4s). So the threshold is presentationDelay + pumpDuration.
        //
        // Without this, the pump reads real audio followed by silence (ring underrun after the
        // flush), and the listener hears a hiccup at startup.
        int pumpSeconds = 4;
        int warmupSeconds = session.presentationDelayMs() / 1000 + pumpSeconds;
        int minStartBytes = warmupSeconds * session.sampleRate() * 2;
        if (sound == null && session.bufferedBytes() < minStartBytes) {
            return;
        }

        long serverNow = clock.toServerNanos(System.nanoTime());
        session.steer(serverNow);

        // isActive catches the SoundEngine.reload() case: a resource-pack reload or audio-device
        // switch destroys every channel, and the only way back is to issue play() again
        // (master plan §10).
        boolean needNew = sound == null
                || sound.sessionId() != sid
                || !sm.isActive(sound);
        if (needNew) {
            if (sound != null) {
                sm.stop(sound);
            }
            sound = new RadioSoundInstance(pos, sid, radio.getVolume());
            sounds.put(pos, sound);
            sm.play(sound);
        }
        sound.setRateTrim(session.rateTrim());
        // Follow the block's synced volume. Pushed every tick rather than only on change because the
        // block entity is the authority: whatever another player just set on the server arrives here
        // as a normal block update, and this is where it reaches the sound.
        sound.setVolume(radio.getVolume());
        suppressMusic(true);
    }

    // ------------------------------------------------------------------ global tick

    /** Drive from {@code TickEvent.ClientTickEvent} END. */
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int SYNC_LOG_INTERVAL_TICKS = 20;

    public static void onClientTick() {
        tickCount++;

        // ESC pause stops the level tick (block entities, server) but NOT the client tick event.
        // So without this guard the stale sweep runs during pause, reaps every session whose block
        // can't refresh its heartbeat, and the radio is silent forever on resume.
        if (Minecraft.getInstance().isPaused()) {
            return;
        }

        if (!sessions.isEmpty()) {
            if (pingBurstRemaining > 0) {
                pingBurstRemaining--;
                sendPing();
            } else if (tickCount >= nextPingTick) {
                sendPing();
                nextPingTick = tickCount + PING_INTERVAL_TICKS;
            }
        }

        long threshold = tickCount - STALE_TICKS;
        Iterator<Map.Entry<BlockPos, Long>> it = lastSeenTick.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, Long> e = it.next();
            if (e.getValue() < threshold) {
                BlockPos pos = e.getKey();
                stopSound(pos);
                sessionByBlock.remove(pos);
                it.remove();
            }
        }
        reapOrphanedSessions();
        logSyncHealth();
    }

    /**
     * Writes the health line to the client log once a second, when the config asks for it.
     *
     * <p>Same line as the control panel, from the same meter, so the two can never disagree while
     * someone is trying to work out which of them to believe. Rate-limited to 1 Hz: the drift loop
     * runs at 20, and twenty lines a second per radio is not a log anyone reads.
     */
    private static void logSyncHealth() {
        if (sessions.isEmpty() || !ClientDebug.syncLog() || tickCount % SYNC_LOG_INTERVAL_TICKS != 0) {
            return;
        }
        for (Map.Entry<BlockPos, Integer> entry : sessionByBlock.entrySet()) {
            ClientMediaSession session = sessions.get(entry.getValue());
            String line = SyncHealthLine.of(session, clock);
            if (line != null) {
                LOGGER.info("Radio at {}: {}", entry.getKey(), line);
            }
        }
    }

    // ------------------------------------------------------------------ packet ingress

    public static void openSession(int sessionId, StreamInfo info, long epochNanos,
                                   int presentationDelayMs, java.util.List<MediaFrame> backlog) {
        if (sessions.containsKey(sessionId)) {
            return;
        }
        ClientMediaSession session = new ClientMediaSession(sessionId, info, epochNanos, presentationDelayMs);
        sessions.put(sessionId, session);
        sessionCreatedTick.put(sessionId, tickCount);
        for (MediaFrame frame : backlog) {
            session.acceptFrame(frame);
        }
        // A fresh session means we need the clock, fast: burst a few pings in a row rather than
        // waiting the first 5 s for the steady-state cadence to fire.
        pingBurstRemaining = PING_BURST;
    }

    public static void acceptFrames(int sessionId, java.util.List<MediaFrame> frames) {
        ClientMediaSession session = sessions.get(sessionId);
        if (session != null) {
            for (MediaFrame frame : frames) {
                session.acceptFrame(frame);
            }
        }
    }

    public static void setTitle(int sessionId, long ptsMicros, String title) {
        titleBySession.put(sessionId, title);
    }

    public static void closeSession(int sessionId) {
        ClientMediaSession session = sessions.remove(sessionId);
        sessionCreatedTick.remove(sessionId);
        titleBySession.remove(sessionId);
        if (session != null) {
            session.close();
        }
        // Any block still pointing at this session: drop the sound. The next tick will find no
        // session and stop cleanly.
        for (Map.Entry<BlockPos, Integer> entry : sessionByBlock.entrySet()) {
            if (entry.getValue() == sessionId) {
                stopSound(entry.getKey());
            }
        }
    }

    public static void onClockPong(long clientNanos, long serverNanos) {
        clock.update(clientNanos, serverNanos, System.nanoTime());
    }

    // ------------------------------------------------------------------ query

    /** Used by {@link RadioSoundInstance#getStream} to find the audio for its block. */
    public static ClientMediaSession sessionForBlock(BlockPos pos) {
        Integer sid = sessionByBlock.get(pos);
        return sid == null ? null : sessions.get(sid);
    }

    /** Latest "now playing" title, for the future GUI; {@code null} when nothing has arrived. */
    public static String titleFor(int sessionId) {
        return titleBySession.get(sessionId);
    }

    public static ClockFilter clock() {
        return clock;
    }

    // ------------------------------------------------------------------ teardown

    /** Disconnect, world unload, or resource-pack reload that wipes state. Stops everything. */
    public static void shutdown() {
        SoundManager sm;
        try {
            sm = Minecraft.getInstance().getSoundManager();
        } catch (Throwable ignored) {
            sm = null;
        }
        if (sm != null) {
            for (RadioSoundInstance sound : sounds.values()) {
                sm.stop(sound);
            }
        }
        sounds.clear();
        suppressMusic(false);
        for (ClientMediaSession session : sessions.values()) {
            session.close();
        }
        sessions.clear();
        sessionCreatedTick.clear();
        sessionByBlock.clear();
        lastSeenTick.clear();
        titleBySession.clear();
        clock = new ClockFilter();
        pingBurstRemaining = 0;
        nextPingTick = 0;
    }

    // ------------------------------------------------------------------ internals

    /**
     * Suppresses vanilla background music while any radio is playing.
     *
     * <p>Called every tick while a radio is active: {@code stopPlaying()} cancels the current song
     * and the {@code MusicManager}'s internal delay prevents the next one for a while, so this
     * catches any music that sneaks in between calls. Restored (allowed to resume) when the last
     * radio stops.
     */
    private static void suppressMusic(boolean suppress) {
        if (suppress) {
            Minecraft.getInstance().getMusicManager().stopPlaying();
        }
    }

    private static void stopSound(BlockPos pos) {
        RadioSoundInstance sound = sounds.remove(pos);
        if (sound != null) {
            Minecraft.getInstance().getSoundManager().stop(sound);
        }
        if (sounds.isEmpty()) {
            suppressMusic(false);
        }
    }

    /**
     * Closes any session no block currently references.
     *
     * <p>A grace period is essential: {@link #openSession} creates a session on the packet thread
     * before any block has had a chance to claim it via {@link #tickBlock}. Without the grace
     * period, the very next sweep would reap every freshly-arrived session.
     */
    private static void reapOrphanedSessions() {
        Iterator<Map.Entry<Integer, ClientMediaSession>> it = sessions.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, ClientMediaSession> e = it.next();
            int sid = e.getKey();
            if (sessionByBlock.containsValue(sid)) {
                continue;
            }
            long created = sessionCreatedTick.getOrDefault(sid, 0L);
            if (tickCount - created < STALE_TICKS) {
                continue;
            }
            e.getValue().close();
            sessionCreatedTick.remove(sid);
            titleBySession.remove(sid);
            it.remove();
        }
    }

    private static void sendPing() {
        pingSender.accept(System.nanoTime());
    }
}
