package mmmm.client;

import mmmm.core.audio.PcmRingBuffer;
import mmmm.core.codec.Decoder;
import mmmm.core.codec.JLayerDecoder;
import mmmm.core.media.MediaFrame;
import mmmm.core.media.StreamInfo;
import mmmm.core.media.Timeline;
import mmmm.core.sync.DriftController;

import java.io.Closeable;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Decode and playback position for one radio block.
 *
 * <p>One of these per block, not per station: two radios playing the same stream decode it twice.
 * That is a deliberate simplification for now — the shared-decode design (master plan §3.4) has one
 * decoder feeding many cursors, and is worth doing once there is a reason to have several radios on
 * one station. The duplicated work is one MP3 decode, which is a rounding error next to rendering.
 *
 * <h2>Position is derived from the ring, not counted</h2>
 * The obvious way to track playback position is a counter incremented as the audio thread reads.
 * It is wrong here, because {@link PcmRingBuffer} deliberately lies in both directions: it pads
 * underruns with silence and drops the oldest audio on overrun. Both change how much real audio sits
 * between the decoder and the speaker, and a read counter sees neither. So position is computed as
 * "where the writer is, minus what is still buffered", which is exact by construction however much
 * the ring has padded or dropped.
 *
 * <p>The arithmetic goes through {@link Timeline} rather than accumulating per-write microseconds,
 * for the reason that class exists: a frame at 44.1 kHz is 26122.448… µs, and summing a rounded
 * version of that drifts about 60 ms per hour in one direction. Which looks exactly like a broken
 * clock, and is not one.
 *
 * <p>Thread-safe: the decode thread writes, the OpenAL thread reads, the client thread steers.
 */
public final class ClientMediaSession implements Closeable {

    /** Nothing has been decoded yet, so there is no position to report. */
    public static final long PTS_UNSET = Long.MIN_VALUE;

    /**
     * Frames held between the network and the decoder. A session opens with a backlog of roughly
     * the presentation delay — about 115 frames at 3 s — so this is generous, and it is bounded
     * because a stalled decode thread must cost audio rather than the heap.
     */
    private static final int QUEUE_CAPACITY = 1024;

    /** Ring headroom beyond the presentation delay, for jitter and for the origin's burst. */
    private static final int RING_MARGIN_MS = 6_000;

    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();

    private final int sessionId;
    private final int sampleRate;
    private final int channels;
    private final long epochNanos;
    private final int presentationDelayMs;

    private final BlockingQueue<MediaFrame> incoming = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final PcmRingBuffer ring;
    private final DriftController drift = new DriftController();
    private final Decoder decoder = new JLayerDecoder();
    private final Thread decodeThread;

    /** Guards the write cursor and its base timestamp, which must be read together. */
    private final Object posLock = new Object();
    private long writeBasePtsMicros = PTS_UNSET;
    private long writeSamples;

    private byte[] monoScratch = new byte[0];
    private long currentFramePtsMicros;

    private volatile boolean closed;
    private volatile boolean resyncRequested;
    private volatile long framesDroppedInbound;

    public ClientMediaSession(int sessionId, StreamInfo info, long epochNanos, int presentationDelayMs) {
        this.sessionId = sessionId;
        this.sampleRate = info.sampleRate() > 0 ? info.sampleRate() : 44_100;
        this.channels = info.channels() > 0 ? info.channels() : 2;
        this.epochNanos = epochNanos;
        this.presentationDelayMs = presentationDelayMs;

        // Mono after downmix: two bytes per sample.
        int ringBytes = (int) ((presentationDelayMs + RING_MARGIN_MS) / 1000.0 * sampleRate) * 2;
        this.ring = new PcmRingBuffer(Math.max(ringBytes, sampleRate * 2));

        this.decodeThread = new Thread(this::decodeLoop, "4m-decode-" + THREAD_COUNTER.incrementAndGet());
        this.decodeThread.setDaemon(true);
        this.decodeThread.start();
    }

    // ------------------------------------------------------------------ input

    /** Queues a frame for decoding. Never blocks: dropping stale audio beats stalling the network. */
    public void acceptFrame(MediaFrame frame) {
        if (closed) {
            return;
        }
        if (!incoming.offer(frame)) {
            framesDroppedInbound++;
        }
    }

    // ------------------------------------------------------------------ decode

    private void decodeLoop() {
        try {
            while (!closed) {
                MediaFrame frame = incoming.poll(100, TimeUnit.MILLISECONDS);
                if (frame == null) {
                    continue;
                }
                if (resyncRequested) {
                    resyncRequested = false;
                    applyFlush();
                }
                currentFramePtsMicros = frame.ptsMicros();
                decoder.decode(frame, this::acceptPcm);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            decoder.close();
        }
    }

    /**
     * Receives decoded PCM, downmixes it to mono and stores it.
     *
     * <p>The downmix is not a nicety. OpenAL applies 3D positioning <em>only</em> to mono sources
     * (ADR-0008): a stereo buffer plays at full volume everywhere, which silently removes the whole
     * point of a positional radio block. Vanilla music discs are mono for the same reason.
     */
    private void acceptPcm(byte[] pcm, int off, int len) {
        int bytesPerFrame = 2 * channels;
        int frames = len / bytesPerFrame;
        if (frames == 0) {
            return;
        }
        int monoBytes = frames * 2;
        if (monoScratch.length < monoBytes) {
            monoScratch = new byte[monoBytes];
        }

        if (channels == 1) {
            System.arraycopy(pcm, off, monoScratch, 0, monoBytes);
        } else {
            for (int i = 0; i < frames; i++) {
                int base = off + i * bytesPerFrame;
                int sum = 0;
                for (int c = 0; c < channels; c++) {
                    int lo = pcm[base + c * 2] & 0xFF;
                    int hi = pcm[base + c * 2 + 1];
                    sum += (short) ((hi << 8) | lo);
                }
                int mono = sum / channels;
                monoScratch[i * 2] = (byte) (mono & 0xFF);
                monoScratch[i * 2 + 1] = (byte) ((mono >> 8) & 0xFF);
            }
        }

        synchronized (posLock) {
            if (writeBasePtsMicros == PTS_UNSET) {
                writeBasePtsMicros = currentFramePtsMicros;
                writeSamples = 0;
            }
            ring.write(monoScratch, 0, monoBytes);
            writeSamples += frames;
        }
    }

    // ------------------------------------------------------------------ output

    /**
     * Fills {@code dest} completely, with silence where audio is missing.
     *
     * <p>Called from the sound engine's streaming thread. Returning less than asked for would be
     * read as end-of-stream and would stop the sound permanently (ADR-0007); the ring guarantees
     * a full buffer, which is why that guarantee lives there and not here.
     */
    public void readPcm(byte[] dest, int off, int len) {
        ring.read(dest, off, len);
    }

    // ------------------------------------------------------------------ position and sync

    /** Where playback actually is, or {@link #PTS_UNSET} before the first frame decodes. */
    public long playbackPtsMicros() {
        synchronized (posLock) {
            if (writeBasePtsMicros == PTS_UNSET) {
                return PTS_UNSET;
            }
            long bufferedSamples = ring.available() / 2L;
            return writeBasePtsMicros + Timeline.toMicros(writeSamples - bufferedSamples, sampleRate);
        }
    }

    /** Where the shared clock says playback should be, given an estimate of the server's clock. */
    public long targetPtsMicros(long serverNowNanos) {
        return (serverNowNanos - epochNanos) / 1000L - presentationDelayMs * 1000L;
    }

    /**
     * One step of the drift loop. Call once per client tick; the gains assume 20 Hz (ADR-0005).
     *
     * @return the drift in microseconds, positive when playback is behind the clock
     */
    public long steer(long serverNowNanos) {
        long actual = playbackPtsMicros();
        if (actual == PTS_UNSET) {
            return 0;
        }
        long target = targetPtsMicros(serverNowNanos);
        if (drift.update(actual, target) == DriftController.Action.HARD_RESYNC) {
            hardResync(target);
        }
        return target - actual;
    }

    /**
     * Flushes and jumps to the correct position.
     *
     * <p>Also the resume-from-pause path: the game paused, frames kept arriving, and the ring is now
     * holding minutes of audio nobody wants. Re-deriving the position from the clock handles that
     * without any special case, which is a decent sign the sync design is the right shape.
     */
    private void hardResync(long targetPtsMicros) {
        synchronized (posLock) {
            if (writeBasePtsMicros == PTS_UNSET) {
                return;
            }
            long writePts = writeBasePtsMicros + Timeline.toMicros(writeSamples, sampleRate);
            long keepMicros = writePts - targetPtsMicros;
            if (keepMicros <= 0) {
                // Even the newest decoded audio is older than the target: everything buffered is
                // stale. Start over from whatever arrives next.
                resyncRequested = true;
                return;
            }
            long keepBytes = Math.min(Timeline.toSamples(keepMicros, sampleRate) * 2, ring.capacity());
            ring.fastForwardTo((int) keepBytes);
        }
    }

    /** Runs on the decode thread, so the decoder is only ever touched from one place. */
    private void applyFlush() {
        decoder.reset();
        ring.clear();
        synchronized (posLock) {
            writeBasePtsMicros = PTS_UNSET;
            writeSamples = 0;
        }
        drift.resetAfterResync();
    }

    /** Playback rate multiplier, fed straight to {@code SoundInstance.getPitch()}. */
    public double rateTrim() {
        return drift.rateTrim();
    }

    // ------------------------------------------------------------------ readouts

    public int sessionId() {
        return sessionId;
    }

    public int sampleRate() {
        return sampleRate;
    }

    public int presentationDelayMs() {
        return presentationDelayMs;
    }

    public boolean hasAudio() {
        return playbackPtsMicros() != PTS_UNSET;
    }

    public boolean isClosed() {
        return closed;
    }

    public long lastDriftMicros() {
        return drift.lastDriftMicros();
    }

    public long hardResyncCount() {
        return drift.hardResyncCount();
    }

    public int bufferedBytes() {
        return ring.available();
    }

    public boolean underrunning() {
        return ring.lastReadWasUnderrun();
    }

    public long framesDroppedInbound() {
        return framesDroppedInbound;
    }

    @Override
    public void close() {
        closed = true;
        decodeThread.interrupt();
        incoming.clear();
    }
}
