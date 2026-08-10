package mmmm.core.codec;

/**
 * The shape of decoded audio: sample rate and channel count.
 *
 * <p>The sample <em>encoding</em> is not a parameter. Every {@link Decoder} emits <b>signed 16-bit
 * little-endian interleaved</b> PCM, because that is what OpenAL takes and what
 * {@code mmmm.core.audio.PcmRingBuffer} assumes when it pads an underrun with zero bytes — zero is
 * silence in signed 16-bit, and would be a loud DC offset in unsigned.
 *
 * @param sampleRate samples per second per channel, as the decoder actually outputs
 * @param channels   1 for mono, 2 for stereo, interleaved left-then-right
 */
public record PcmFormat(int sampleRate, int channels) {

    public PcmFormat {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive, was " + sampleRate);
        }
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be positive, was " + channels);
        }
    }

    /** Bytes per sample frame — one sample for every channel. */
    public int frameBytes() {
        return channels * 2;
    }

    /** Bytes this format needs to hold {@code micros} of audio. Used for ring sizing. */
    public long bytesForMicros(long micros) {
        return (micros * sampleRate / 1_000_000L) * frameBytes();
    }
}
