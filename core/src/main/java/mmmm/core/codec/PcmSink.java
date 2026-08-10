package mmmm.core.codec;

/**
 * Receives decoded PCM.
 *
 * <p>A callback rather than a return value on purpose. A decoder may emit zero, one or several
 * buffers for a single input frame — SBR and chained streams both do — and a future native-backed
 * video decoder may not produce output on the same call that consumed the input at all. Returning
 * "the PCM for this frame" would bake in an assumption none of those honour.
 *
 * <p>The array passed in is <b>borrowed</b>: it belongs to the decoder and is reused on the next
 * call. Copy anything you need to keep. In practice the only implementation copies straight into
 * {@code mmmm.core.audio.PcmRingBuffer}, which is exactly the intended use.
 *
 * <p>Called synchronously from whatever thread drove the decode, and must not block.
 */
@FunctionalInterface
public interface PcmSink {

    /**
     * @param pcm signed 16-bit little-endian interleaved samples
     * @param off first valid byte
     * @param len number of valid bytes, always a whole number of sample frames
     */
    void accept(byte[] pcm, int off, int len);
}
