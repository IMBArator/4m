package mmmm;

/**
 * Something whose model reuses a near-greyscale vanilla texture and gets its identity from a
 * multiplied tint colour instead of its own artwork.
 *
 * <p>The colour lives on the block or item rather than in the client's colour handler so that one
 * handler instance serves every tinted thing, and so adding the next one is a registry line and a
 * model file with no client-side change at all. A packed RGB int is data, not a client API, so
 * {@code common/} may hold it (ADR-0002).
 *
 * <p>The tint is <em>multiplied</em> into the texture, so it can darken and shift but never
 * brighten. The vanilla iron textures this mod tints average 220/255 luminance, which means the
 * rendered colour lands at roughly 0.7–0.95 of the value given here. Saturated bases such as gold
 * are unusable for this: yellow times blue is mud.
 */
public interface Tinted {

    /**
     * @param tintIndex the {@code tintindex} the model asked for
     * @return a packed RGB colour, or {@code -1} for vanilla's "leave this alone"
     */
    int tint(int tintIndex);
}
