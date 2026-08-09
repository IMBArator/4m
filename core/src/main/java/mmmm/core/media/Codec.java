package mmmm.core.media;

/**
 * Codecs the pipeline understands.
 *
 * <p>{@link #needsCodecInit()} is the property that matters for late-joining clients. A client that
 * subscribes mid-stream receives frames from wherever the stream currently is, so any codec whose
 * decoder cannot start from an arbitrary frame needs its initialisation data carried out-of-band in
 * {@link StreamInfo#codecInit()}. Vorbis is the case in v1 (three header packets); H.264 would be
 * the case for video (SPS/PPS).
 */
public enum Codec {

    /** MPEG-1/2/2.5 Layer III. Self-describing per frame. */
    MP3(MediaKind.AUDIO, false),

    /** AAC in ADTS framing. Self-describing per frame. */
    AAC(MediaKind.AUDIO, false),

    /** Vorbis in an Ogg container. Requires the identification/comment/setup header packets. */
    VORBIS(MediaKind.AUDIO, true);

    private final MediaKind kind;
    private final boolean needsCodecInit;

    Codec(MediaKind kind, boolean needsCodecInit) {
        this.kind = kind;
        this.needsCodecInit = needsCodecInit;
    }

    public MediaKind kind() {
        return kind;
    }

    /** Whether a decoder needs out-of-band setup data before it can decode any frame. */
    public boolean needsCodecInit() {
        return needsCodecInit;
    }
}
