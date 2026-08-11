package mmmm.forge.client;

import mmmm.Tinted;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.event.RegisterColorHandlersEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.RegistryObject;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Colour handlers for everything that reuses a vanilla greyscale texture instead of shipping art.
 *
 * <h2>Why this is not in {@link ForgeClientSetup}</h2>
 * {@code RegisterColorHandlersEvent} fires <em>before</em> {@code FMLClientSetupEvent}, so
 * installing these from {@code ForgeClientSetup.install()} is too late and every tinted block and
 * item silently renders plain iron grey, with nothing in the log. In {@code Minecraft}'s
 * constructor, Forge's {@code ClientModLoader.begin()} only <em>registers</em> the resource-reload
 * listener that later dispatches the setup events, while {@code BlockColors.createDefault()} and
 * {@code ItemColors.createDefault()} run further down that same constructor. So the listeners have
 * to be added at mod-construction time instead — which is why {@code MmmmForge} calls
 * {@link #install} from its constructor rather than from {@code clientSetup}.
 *
 * <p>This class is still subject to the rule {@link ForgeClientSetup} documents: it names client
 * types, so it lives in a {@code client} package, and the call that reaches it must not drag those
 * types into the entry class's bytecode.
 */
public final class ForgeTints {

    private static List<RegistryObject<Block>> blocks = List.of();
    private static List<RegistryObject<Item>> items = List.of();

    private ForgeTints() {
    }

    /**
     * Called from {@code MmmmForge}'s constructor, behind a dist check.
     *
     * <p>The listeners are method references rather than lambdas, for the reason
     * {@link ForgeClientSetup} spells out: a lambda body naming {@code RegisterColorHandlersEvent}
     * would compile into the enclosing class, and the enclosing class has to stay loadable on a
     * dedicated server.
     *
     * <p>The registry objects are resolved lazily inside the listeners, not here. Registries are in
     * fact already populated by the time the colour events fire, but resolving late costs nothing
     * and removes the ordering assumption entirely.
     */
    public static void install(IEventBus modBus,
                               List<RegistryObject<Block>> tintedBlocks,
                               List<RegistryObject<Item>> tintedItems) {
        blocks = tintedBlocks;
        items = tintedItems;
        modBus.addListener(ForgeTints::registerBlockColors);
        modBus.addListener(ForgeTints::registerItemColors);
    }

    private static void registerBlockColors(RegisterColorHandlersEvent.Block event) {
        for (RegistryObject<Block> block : blocks) {
            event.register(ForgeTints::blockTint, block.get());
        }
    }

    /**
     * The {@code BlockItem}s need their own registration: {@code ItemColors} is a separate registry
     * keyed by item and does not consult {@code BlockColors}, which is why vanilla registers an
     * explicit delegate for every tinted block item it has. Skipping this leaves the blocks
     * correctly coloured in the world and plain grey in the inventory, hotbar and dropped-item
     * render — a split that looks like a model bug rather than a missing listener.
     */
    private static void registerItemColors(RegisterColorHandlersEvent.Item event) {
        for (RegistryObject<Block> block : blocks) {
            event.register(ForgeTints::itemTint, block.get().asItem());
        }
        for (RegistryObject<Item> item : items) {
            event.register(ForgeTints::itemTint, item.get());
        }
    }

    // level and pos are null for every inventory render: vanilla's block-item colour delegate calls
    // BlockColors.getColor(state, null, null, tintIndex). Dereferencing either would NPE there and
    // nowhere else, so this must stay a pure function of the state.
    private static int blockTint(BlockState state, @Nullable BlockAndTintGetter level,
                                 @Nullable BlockPos pos, int tintIndex) {
        return state.getBlock() instanceof Tinted tinted ? tinted.tint(tintIndex) : -1;
    }

    private static int itemTint(ItemStack stack, int tintIndex) {
        Item item = stack.getItem();
        if (item instanceof Tinted tinted) {
            return tinted.tint(tintIndex);
        }
        if (item instanceof BlockItem blockItem && blockItem.getBlock() instanceof Tinted tinted) {
            return tinted.tint(tintIndex);
        }
        return -1;
    }
}
