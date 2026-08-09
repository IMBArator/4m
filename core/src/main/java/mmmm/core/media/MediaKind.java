package mmmm.core.media;

/** What a stream carries. Video is not implemented; the constant exists so the wire format does
 *  not have to change when it is. */
public enum MediaKind {
    AUDIO,
    VIDEO
}
