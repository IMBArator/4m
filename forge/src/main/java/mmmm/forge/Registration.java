package mmmm.forge;

import mmmm.Mmmm;
import mmmm.block.RadioBlock;
import mmmm.block.RadioBlockEntity;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
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
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, Mmmm.MOD_ID);
    public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
            DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, Mmmm.MOD_ID);

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

    private Registration() {
    }
}
