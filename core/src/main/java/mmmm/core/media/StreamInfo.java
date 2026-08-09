package mmmm.core.media;

import java.util.Arrays;
import java.util.Objects;

/**
 * Everything a client needs to start decoding a stream it has just joined.
 *
 * <p>Sent once in the stream-open message, before any frames. The {@code codecInit} field is what
 * makes mid-stream joining work: see {@link Codec#needsCodecInit()}.
 *
 * @param streamId   identifies this track within its session
 * @param codec      how {@link MediaFrame#payload()} is encoded
 * @param sampleRate audio sample rate in Hz; 0 for video
 * @param channels   audio channel count; 0 for video
 * @param width      video width in pixels; 0 for audio
 * @param height     video height in pixels; 0 for audio
 * @param codecInit  out-of-band decoder setup data, empty when the codec does not need it
 */
public record StreamInfo(
        int streamId,
        Codec codec,
        int sampleRate,
        int channels,
        int width,
        int height,
        byte[] codecInit) {

    private static final byte[] NO_INIT = new byte[0];

    public StreamInfo {
        Objects.requireNonNull(codec, "codec");
        codecInit = codecInit == null ? NO_INIT : codecInit;
        if (codec.needsCodecInit() && codecInit.length == 0) {
            throw new IllegalArgumentException(
                    codec + " requires codecInit; a client joining mid-stream could not decode without it");
        }
    }

    public static StreamInfo audio(int streamId, Codec codec, int sampleRate, int channels) {
        return new StreamInfo(streamId, codec, sampleRate, channels, 0, 0, NO_INIT);
    }

    public static StreamInfo audio(int streamId, Codec codec, int sampleRate, int channels, byte[] codecInit) {
        return new StreamInfo(streamId, codec, sampleRate, channels, 0, 0, codecInit);
    }

    public MediaKind kind() {
        return codec.kind();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof StreamInfo other)) return false;
        return streamId == other.streamId
                && codec == other.codec
                && sampleRate == other.sampleRate
                && channels == other.channels
                && width == other.width
                && height == other.height
                && Arrays.equals(codecInit, other.codecInit);
    }

    @Override
    public int hashCode() {
        return Objects.hash(streamId, codec, sampleRate, channels, width, height) * 31
                + Arrays.hashCode(codecInit);
    }

    @Override
    public String toString() {
        return "StreamInfo[id=" + streamId + ", " + codec + ", " + sampleRate + "Hz, "
                + channels + "ch, init=" + codecInit.length + " bytes]";
    }
}
