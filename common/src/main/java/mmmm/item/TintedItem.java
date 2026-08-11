package mmmm.item;

import mmmm.Tinted;
import net.minecraft.world.item.Item;

/**
 * An item that ships no texture of its own — see {@link Tinted}.
 *
 * <p>The ingots and nuggets use this against {@code minecraft:item/iron_ingot} and
 * {@code iron_nugget}, which is what makes the three materials read as one family: same silhouette,
 * different colour.
 */
public class TintedItem extends Item implements Tinted {

    private final int tint;

    public TintedItem(Properties properties, int tint) {
        super(properties);
        this.tint = tint;
    }

    @Override
    public int tint(int tintIndex) {
        // An `item/generated` model assigns tintindex N to layerN, and ours has only layer0.
        return tintIndex == 0 ? tint : -1;
    }
}
