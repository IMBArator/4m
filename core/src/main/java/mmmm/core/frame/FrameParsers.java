package mmmm.core.frame;

import mmmm.core.media.Codec;

/** Builds the right {@link FrameParser} for a codec. */
public final class FrameParsers {

    private FrameParsers() {
    }

    public static FrameParser forCodec(Codec codec, int streamId) {
        return switch (codec) {
            case MP3 -> new Mp3FrameParser(streamId);
            case AAC -> new AdtsFrameParser(streamId);
            case VORBIS -> new OggFrameParser(streamId);
        };
    }

    public static FrameParser forCodec(Codec codec) {
        return forCodec(codec, 0);
    }
}
