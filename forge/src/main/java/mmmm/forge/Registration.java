package mmmm.forge;

import mmmm.Mmmm;
import mmmm.block.RadioBlock;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Forge registry objects. Loader-specific by necessity — {@code DeferredRegister} and
 * {@code RegistryObject} are Forge API, so this cannot live in {@code common/} (ADR-0002).
 */
public final class Registration {

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, Mmmm.MOD_ID);
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, Mmmm.MOD_ID);

    public static final RegistryObject<Block> RADIO = BLOCKS.register("radio",
            () -> new RadioBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_RED)
                    .strength(2.0F)
                    .sound(SoundType.WOOD)
                    // The model is not a full cube, so neighbouring faces must still render.
                    .noOcclusion()));

    // Deliberately no requiresCorrectToolForDrops(): without a matching tool tag it would make
    // hand-mining silently drop nothing. The pickaxe tag is a speed bonus, not a gate.

    public static final RegistryObject<Item> RADIO_ITEM = ITEMS.register("radio",
            () -> new BlockItem(RADIO.get(), new Item.Properties()));

    private Registration() {
    }
}
