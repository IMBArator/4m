package mmmm.forge;

import mmmm.Mmmm;
import mmmm.block.RadioBlock;
import mmmm.block.RadioBlockEntity;
import mmmm.block.TintedBlock;
import mmmm.item.DepolarizationHammerItem;
import mmmm.item.TintedItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.List;

/**
 * Forge registry objects. Loader-specific by necessity — {@code DeferredRegister} and
 * {@code RegistryObject} are Forge API, so this cannot live in {@code common/} (ADR-0002).
 */
public final class Registration {

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, Mmmm.MOD_ID);
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, Mmmm.MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, Mmmm.MOD_ID);
    public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
            DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, Mmmm.MOD_ID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Mmmm.MOD_ID);

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

    public static final RegistryObject<BlockEntityType<RadioBlockEntity>> RADIO_BLOCK_ENTITY =
            BLOCK_ENTITIES.register("radio",
                    () -> BlockEntityType.Builder.of(RadioBlockEntity::new, RADIO.get()).build(null));

    /**
     * Resolves to a placeholder ogg that is never read: the audio arrives from
     * {@link mmmm.client.RadioSoundInstance#getStream}. The registration exists so vanilla's sound
     * manager has an event to look up, and so {@code Sound.shouldStream()} is true — that flag is
     * what routes {@link mmmm.client.RadioSoundInstance} through the streaming-source path that
     * calls {@code getStream} (master plan §7.3).
     */
    public static final RegistryObject<SoundEvent> RADIO_STREAM =
            SOUND_EVENTS.register("radio_stream",
                    () -> SoundEvent.createVariableRangeEvent(Mmmm.id("radio_stream")));

    // ------------------------------------------------------------- PA cabinet base materials

    // Bass, mid-range and treble (docs/crafting_idea.md). None of the nine entries below ships any
    // artwork: the blocks reuse minecraft:block/iron_block and the ingots and nuggets reuse
    // minecraft:item/iron_{ingot,nugget}, all told apart by the tint registered in
    // mmmm.forge.client.ForgeTints. The vanilla iron textures are near-greyscale, which is what
    // makes that work — see mmmm.Tinted for why a saturated base such as gold could not be used.

    private static final int BASS_TINT = 0x5A3FA0;
    private static final int MID_RANGE_TINT = 0x2E9E5B;
    private static final int TREBLE_TINT = 0x7FD4E8;

    public static final RegistryObject<Block> BASS_BLOCK = BLOCKS.register("bass_block",
            () -> new TintedBlock(metalBlock(MapColor.COLOR_PURPLE), BASS_TINT));
    public static final RegistryObject<Block> MID_RANGE_BLOCK = BLOCKS.register("mid_range_block",
            () -> new TintedBlock(metalBlock(MapColor.COLOR_GREEN), MID_RANGE_TINT));
    public static final RegistryObject<Block> TREBLE_BLOCK = BLOCKS.register("treble_block",
            () -> new TintedBlock(metalBlock(MapColor.COLOR_LIGHT_BLUE), TREBLE_TINT));

    /**
     * Vanilla's metal-block feel.
     *
     * <p>Unlike the radio above, {@code requiresCorrectToolForDrops()} is safe here — and only
     * because {@code data/minecraft/tags/blocks/needs_iron_tool.json} lists all three. Without that
     * tag the flag makes them drop nothing at all, ever, with no error anywhere. The two must never
     * be separated.
     */
    private static BlockBehaviour.Properties metalBlock(MapColor mapColor) {
        return BlockBehaviour.Properties.of()
                .mapColor(mapColor)
                .strength(5.0F, 6.0F)
                .sound(SoundType.METAL)
                .requiresCorrectToolForDrops();
    }

    public static final RegistryObject<Item> BASS_BLOCK_ITEM = ITEMS.register("bass_block",
            () -> new BlockItem(BASS_BLOCK.get(), new Item.Properties()));
    public static final RegistryObject<Item> MID_RANGE_BLOCK_ITEM = ITEMS.register("mid_range_block",
            () -> new BlockItem(MID_RANGE_BLOCK.get(), new Item.Properties()));
    public static final RegistryObject<Item> TREBLE_BLOCK_ITEM = ITEMS.register("treble_block",
            () -> new BlockItem(TREBLE_BLOCK.get(), new Item.Properties()));

    public static final RegistryObject<Item> BASS_INGOT = ITEMS.register("bass_ingot",
            () -> new TintedItem(new Item.Properties(), BASS_TINT));
    public static final RegistryObject<Item> MID_RANGE_INGOT = ITEMS.register("mid_range_ingot",
            () -> new TintedItem(new Item.Properties(), MID_RANGE_TINT));
    public static final RegistryObject<Item> TREBLE_INGOT = ITEMS.register("treble_ingot",
            () -> new TintedItem(new Item.Properties(), TREBLE_TINT));

    public static final RegistryObject<Item> BASS_NUGGET = ITEMS.register("bass_nugget",
            () -> new TintedItem(new Item.Properties(), BASS_TINT));
    public static final RegistryObject<Item> MID_RANGE_NUGGET = ITEMS.register("mid_range_nugget",
            () -> new TintedItem(new Item.Properties(), MID_RANGE_TINT));
    public static final RegistryObject<Item> TREBLE_NUGGET = ITEMS.register("treble_nugget",
            () -> new TintedItem(new Item.Properties(), TREBLE_TINT));

    /** Everything whose model is tinted rather than textured. Consumed by {@code ForgeTints}. */
    public static final List<RegistryObject<Block>> TINTED_BLOCKS =
            List.of(BASS_BLOCK, MID_RANGE_BLOCK, TREBLE_BLOCK);
    public static final List<RegistryObject<Item>> TINTED_ITEMS =
            List.of(BASS_INGOT, MID_RANGE_INGOT, TREBLE_INGOT,
                    BASS_NUGGET, MID_RANGE_NUGGET, TREBLE_NUGGET);

    // ------------------------------------------------------------- tools

    /**
     * Turns vanilla metal blocks into the three above.
     *
     * <p>{@code durability()} also forces the stack size to 1, so no separate {@code stacksTo(1)} is
     * needed. 250 is an iron pickaxe's, which is what the placeholder model looks like.
     */
    public static final RegistryObject<Item> DEPOLARIZATION_HAMMER =
            ITEMS.register("depolarization_hammer",
                    () -> new DepolarizationHammerItem(new Item.Properties().durability(250)));

    // ------------------------------------------------------------- creative tab

    /**
     * One 4M tab for everything, rather than scattering across vanilla ones.
     *
     * <p>{@code CreativeModeTab} lives in {@code net.minecraft.world.item}, not the client packages,
     * so building it here — {@code displayItems} lambda included — names no client type and is safe
     * outside {@code mmmm.forge.client}.
     */
    public static final RegistryObject<CreativeModeTab> TAB = CREATIVE_MODE_TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup." + Mmmm.MOD_ID))
                    .icon(() -> new ItemStack(RADIO_ITEM.get()))
                    .displayItems((parameters, output) -> {
                        output.accept(RADIO_ITEM.get());
                        output.accept(DEPOLARIZATION_HAMMER.get());
                        output.accept(BASS_BLOCK_ITEM.get());
                        output.accept(MID_RANGE_BLOCK_ITEM.get());
                        output.accept(TREBLE_BLOCK_ITEM.get());
                        output.accept(BASS_INGOT.get());
                        output.accept(MID_RANGE_INGOT.get());
                        output.accept(TREBLE_INGOT.get());
                        output.accept(BASS_NUGGET.get());
                        output.accept(MID_RANGE_NUGGET.get());
                        output.accept(TREBLE_NUGGET.get());
                    })
                    .build());

    private Registration() {
    }
}
