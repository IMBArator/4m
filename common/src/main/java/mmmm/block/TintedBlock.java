package mmmm.block;

import mmmm.Tinted;
import net.minecraft.world.level.block.Block;

/**
 * A block that ships no texture of its own — see {@link Tinted}.
 *
 * <p>Registration is per-loader, so the tint is handed in by the constructor rather than read from
 * anywhere; the loader-side registry entry is the single place a 4M block's colour is written down.
 */
public class TintedBlock extends Block implements Tinted {

    private final int tint;

    public TintedBlock(Properties properties, int tint) {
        super(properties);
        this.tint = tint;
    }

    @Override
    public int tint(int tintIndex) {
        // Only index 0 exists in our model. Anything else gets the no-tint sentinel rather than an
        // accidental recolour, so a future multi-layer model fails visibly instead of subtly.
        return tintIndex == 0 ? tint : -1;
    }
}
