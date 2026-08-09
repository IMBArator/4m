package mmmm.core.media;

import java.util.Arrays;
import java.util.Objects;

/**
 * One codec frame with its presentation timestamp.
 *
 * <p>This is the unit the server relays and the client schedules. It is deliberately not
 * audio-specific.
 *
 * <h2>Why microseconds</h2>
 * A sample count would be the natural unit for audio and is useless for video. Microseconds are
 * finer than one sample at 44.1 kHz (22.68 µs), so nothing is lost by using them.
 *
 * <p><b>Derive {@code ptsMicros} from a cumulative exact counter — never by accumulating rounded
 * per-frame deltas.</b> An MP3 frame at 44.1 kHz is 1152/44100 s = 26122.448... µs. Rounding that
 * per frame and summing loses roughly 0.45 µs each time, which is 62 ms after an hour: a slow,
 * one-directional drift that looks exactly like a clock bug and is not one. {@link Timeline} exists
 * to make that mistake hard to write.
 *
 * @param streamId   which track within a session; lets one session carry audio and video later
 * @param ptsMicros  presentation timestamp in microseconds since the session epoch
 * @param keyframe   always {@code true} for audio; for video, marks where a decoder may start
 * @param payload    the encoded frame, not copied on construction — treat as immutable
 */
public record MediaFrame(int streamId, long ptsMicros, boolean keyframe, byte[] payload) {

    public MediaFrame {
        Objects.requireNonNull(payload, "payload");
        if (ptsMicros < 0) {
            throw new IllegalArgumentException("ptsMicros must be >= 0, was " + ptsMicros);
        }
    }

    public int size() {
        return payload.length;
    }

    /** Value equality including payload contents; records would otherwise compare arrays by identity. */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MediaFrame other)) return false;
        return streamId == other.streamId
                && ptsMicros == other.ptsMicros
                && keyframe == other.keyframe
                && Arrays.equals(payload, other.payload);
    }

    @Override
    public int hashCode() {
        return Objects.hash(streamId, ptsMicros, keyframe) * 31 + Arrays.hashCode(payload);
    }

    @Override
    public String toString() {
        return "MediaFrame[stream=" + streamId + ", pts=" + ptsMicros + "us, "
                + payload.length + " bytes" + (keyframe ? ", key" : "") + "]";
    }
}
