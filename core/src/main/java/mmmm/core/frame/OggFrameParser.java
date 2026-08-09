package mmmm.core.frame;

import mmmm.core.media.Codec;
import mmmm.core.media.MediaFrame;
import mmmm.core.media.StreamInfo;
import mmmm.core.media.Timeline;

import java.io.ByteArrayOutputStream;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Splits Ogg-encapsulated Vorbis into pages and derives timing from the granule position.
 *
 * <p>Ogg is the awkward one of the three, for two reasons that both bite at exactly the moment a
 * client joins an already-playing stream.
 *
 * <h2>Codec init</h2>
 * A Vorbis decoder cannot start from an arbitrary packet. It needs three header packets —
 * identification, comment and setup — which appear only at the very start of a logical stream. A
 * client subscribing mid-song will never see them, so they are captured here and carried
 * out-of-band in {@link StreamInfo#codecInit()}.
 *
 * <h2>Chained streams</h2>
 * Icecast does not send one continuous Ogg stream. It starts a <em>new logical stream</em> at every
 * track change, with a fresh serial number, fresh headers, and a granule position restarting at
 * zero. A parser that assumes a single logical stream reports end-of-stream at the first track
 * change. So a beginning-of-stream page re-captures the headers, and the timeline carries forward
 * across the boundary instead of jumping backwards with the granule counter.
 *
 * <h2>Timing</h2>
 * The granule position is an absolute sample counter, which makes it authoritative — unlike MP3 and
 * ADTS, where durations are accumulated frame by frame. The timeline follows the container rather
 * than tracking it independently.
 *
 * <p>Server side, single-threaded.
 */
public final class OggFrameParser implements FrameParser {

    private static final int CAPTURE_PATTERN_BYTES = 4;
    private static final int FIXED_HEADER_BYTES = 27;

    private static final int FLAG_BEGINNING_OF_STREAM = 0x02;

    /** Vorbis identification packet; carries sample rate and channel count. */
    private static final int PACKET_IDENTIFICATION = 1;

    /** Identification, comment, setup. A decoder needs all three before any audio packet. */
    private static final int VORBIS_HEADER_PACKETS = 3;

    private static final int MAX_RESYNC_SCAN = 256 * 1024;

    private final FrameBuffer buffer = new FrameBuffer(64 * 1024);
    private final int streamId;

    private Timeline timeline;
    private StreamInfo info;

    /** Raw header pages of the current logical stream, replayed to late joiners verbatim. */
    private final ByteArrayOutputStream codecInit = new ByteArrayOutputStream(8192);
    private int headerPacketsSeen;
    private int sampleRate;
    private int channels;

    /**
     * Samples elapsed before the current logical stream began.
     *
     * <p>This is what keeps the timeline monotonic across a track change, when the container's
     * granule counter restarts at zero.
     */
    private long chainOffsetSamples;

    /**
     * A first granule above this is taken as a mid-stream join rather than a stream start.
     *
     * <p>Icecast replays a new listener the cached header pages — carrying the beginning-of-stream
     * flag — and then splices in the live feed wherever it currently is. So the BOS flag does
     * <em>not</em> mean the audio starts at granule zero, and a live encoder that has been running
     * for hours hands over a first granule far from it. Radio Paradise gives a new listener one
     * around the two-minute mark; read as absolute, that puts the second frame two minutes after
     * the first, and nothing downstream survives that.
     *
     * <p>Granule magnitude is what separates the two cases, with orders of magnitude to spare. A
     * genuine first page holds a fraction of a second — encoders flush pages every 20–500 ms — while
     * a mid-stream splice is minutes or hours in. Five seconds sits far above the former and far
     * below the latter.
     *
     * <p>The threshold matters because anchoring is not free: an anchored page is treated as
     * zero-length. Doing that once at session start is invisible against a multi-second
     * presentation delay. Doing it at every track change would discard ~50 ms per track, and that
     * accumulates into exactly the slow drift this parser exists to prevent.
     */
    private static final int ANCHOR_THRESHOLD_SECONDS = 5;

    /**
     * Granule position the current logical stream's timeline is measured from, or -1 before the
     * first timed page has been seen. Zero for a stream that genuinely starts at the beginning.
     */
    private long granuleOrigin = -1;

    private long lastGranule = -1;

    public OggFrameParser() {
        this(0);
    }

    public OggFrameParser(int streamId) {
        this.streamId = streamId;
    }

    @Override
    public void feed(byte[] data, int off, int len, Consumer<MediaFrame> out) {
        buffer.append(data, off, len);

        while (true) {
            int offset = findCapturePattern();
            if (offset < 0) {
                break;
            }
            if (offset > 0) {
                buffer.skip(offset);
            }
            if (buffer.available() < FIXED_HEADER_BYTES) {
                break;
            }

            int segmentCount = buffer.get(26);
            int headerBytes = FIXED_HEADER_BYTES + segmentCount;
            if (buffer.available() < headerBytes) {
                break;
            }

            int payloadBytes = 0;
            int packetsEndingHere = 0;
            for (int i = 0; i < segmentCount; i++) {
                int lacing = buffer.get(FIXED_HEADER_BYTES + i);
                payloadBytes += lacing;
                // A lacing value below 255 terminates a packet. Counting terminators is what makes
                // this correct when headers share a page or span several.
                if (lacing < 255) {
                    packetsEndingHere++;
                }
            }

            int pageBytes = headerBytes + payloadBytes;
            if (buffer.available() < pageBytes) {
                break;
            }

            int flags = buffer.get(5);
            long granule = buffer.getInt64LE(6);
            byte[] page = buffer.copy(0, pageBytes);
            byte[] payload = payloadBytes > 0 ? buffer.copy(headerBytes, payloadBytes) : new byte[0];
            buffer.skip(pageBytes);

            if ((flags & FLAG_BEGINNING_OF_STREAM) != 0) {
                beginLogicalStream();
            }

            if (headerPacketsSeen < VORBIS_HEADER_PACKETS) {
                captureHeaderPage(page, payload, packetsEndingHere);
                continue;
            }
            if (timeline == null) {
                // Headers seen but unusable — no sample rate. Nothing sensible to emit.
                continue;
            }

            // The page's audio starts where the timeline currently stands; the granule position
            // states where it ends. A granule of -1 means no packet completes on this page, so it
            // carries no timing and the timeline does not move.
            long pts = timeline.currentMicros();
            if (granule >= 0) {
                if (granuleOrigin < 0) {
                    long anchorThreshold = (long) timeline.sampleRate() * ANCHOR_THRESHOLD_SECONDS;
                    granuleOrigin = granule > anchorThreshold ? granule : 0;
                }
                // Measured from this logical stream's origin, which is zero for a stream we saw
                // start and the join point for one we caught mid-flight. Clamped because a granule
                // going backwards within a stream would otherwise rewind the timeline.
                long elapsed = Math.max(0, granule - granuleOrigin);
                long absolute = chainOffsetSamples + elapsed;
                if (absolute > timeline.totalSamples()) {
                    timeline.seekToSample(absolute);
                }
                lastGranule = granule;
            }
            out.accept(new MediaFrame(streamId, pts, true, page));
        }
        buffer.compact();
    }

    /**
     * Resets per-logical-stream state at a track boundary, carrying the timeline forward.
     *
     * <p>The incoming stream's granule position restarts at zero, so everything played so far
     * becomes a fixed offset added to it.
     */
    private void beginLogicalStream() {
        if (timeline != null) {
            chainOffsetSamples = timeline.totalSamples();
        }
        codecInit.reset();
        headerPacketsSeen = 0;
        // Re-anchored per logical stream. Icecast normally restarts the granule at zero for a new
        // track, but nothing guarantees it, and re-anchoring costs nothing when it does.
        granuleOrigin = -1;
        lastGranule = -1;
    }

    /** Accumulates header pages verbatim until all three Vorbis header packets have completed. */
    private void captureHeaderPage(byte[] page, byte[] payload, int packetsEndingHere) {
        if (payload.length > 0 && (payload[0] & 0xFF) == PACKET_IDENTIFICATION && payload.length >= 16) {
            // "\1vorbis", version(4), channels(1), sampleRate(4), ...
            channels = payload[11] & 0xFF;
            sampleRate = (int) readUint32LE(payload, 12);
        }

        codecInit.write(page, 0, page.length);
        headerPacketsSeen += packetsEndingHere;

        if (headerPacketsSeen >= VORBIS_HEADER_PACKETS && sampleRate > 0) {
            if (timeline == null) {
                timeline = new Timeline(sampleRate);
            }
            info = StreamInfo.audio(streamId, Codec.VORBIS, sampleRate,
                    channels > 0 ? channels : 2, codecInit.toByteArray());
        }
    }

    private int findCapturePattern() {
        int limit = Math.min(buffer.available() - CAPTURE_PATTERN_BYTES, MAX_RESYNC_SCAN);
        for (int i = 0; i <= limit; i++) {
            if (buffer.matches(i, "OggS")) {
                return i;
            }
        }
        // Retain the last three bytes: a capture pattern may straddle this read boundary.
        int keep = Math.min(buffer.available(), CAPTURE_PATTERN_BYTES - 1);
        if (buffer.available() > keep) {
            buffer.skip(buffer.available() - keep);
        }
        return -1;
    }

    private static long readUint32LE(byte[] b, int off) {
        return ((long) (b[off + 3] & 0xFF) << 24)
                | ((b[off + 2] & 0xFF) << 16)
                | ((b[off + 1] & 0xFF) << 8)
                | (b[off] & 0xFF);
    }

    @Override
    public Optional<StreamInfo> streamInfo() {
        return Optional.ofNullable(info);
    }

    @Override
    public long currentPtsMicros() {
        return timeline == null ? 0L : timeline.currentMicros();
    }

    /** Granule position of the last timed page, or -1. Test visibility. */
    long lastGranule() {
        return lastGranule;
    }

    /** Samples elapsed before the current logical stream. Test visibility. */
    long chainOffsetSamples() {
        return chainOffsetSamples;
    }
}
