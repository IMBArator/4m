package mmmm.forge;

import mmmm.Mmmm;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/** Forge entry point. */
@Mod(Mmmm.MOD_ID)
public final class MmmmForge {

    public MmmmForge() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        Registration.BLOCKS.register(modBus);
        Registration.ITEMS.register(modBus);
        modBus.addListener(MmmmForge::addCreativeTabContents);
    }

    /**
     * One block does not justify a creative tab of its own; it goes in a vanilla one. Revisit once
     * there is more than a single item to group.
     *
     * <p>{@code BuildCreativeModeTabContentsEvent} implements {@code IModBusEvent}, so this listener
     * belongs on the mod bus, not the Forge bus.
     */
    private static void addCreativeTabContents(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(Registration.RADIO_ITEM);
        }
    }
}
