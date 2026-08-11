package mmmm;

import mmmm.block.RadioBlockEntity;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Registry objects that {@code common/} needs but cannot itself register.
 *
 * <p>{@code DeferredRegister} and {@code RegistryObject} are loader API, so registration lives in
 * {@code forge/} and {@code neoforge/} (ADR-0002). But the shared code genuinely needs the results:
 * a {@link net.minecraft.world.level.block.entity.BlockEntity} has to pass its own type to
 * {@code super}, and the client has to name a {@link SoundEvent} to play. This is the seam between
 * the two.
 *
 * <p>Suppliers rather than values, because registry contents do not exist yet when the loader
 * installs them — {@code DeferredRegister} entries are populated during mod loading, well after the
 * entry class runs. Calling {@link #radioBlockEntity()} before that point throws with a message that
 * says so, which is a far better failure than a null dereference three frames deep in vanilla.
 */
public final class MmmmContent {

    private static Supplier<BlockEntityType<RadioBlockEntity>> radioBlockEntity;
    private static Supplier<SoundEvent> radioStream;
    private static Supplier<Map<Block, Block>> depolarizations;
    private static Map<Block, Block> depolarizationsResolved;

    /** Called once by each loader's entry class, before anything can be placed or played. */
    public static void bind(Supplier<BlockEntityType<RadioBlockEntity>> blockEntity,
                            Supplier<SoundEvent> sound) {
        radioBlockEntity = blockEntity;
        radioStream = sound;
    }

    public static BlockEntityType<RadioBlockEntity> radioBlockEntity() {
        return require(radioBlockEntity, "radio block entity type").get();
    }

    /**
     * The streaming sound event the radio plays through.
     *
     * <p>It resolves to a placeholder ogg that is never read: the audio arrives from
     * {@link mmmm.client.RadioSoundInstance#getStream}. The registration exists so vanilla's sound
     * manager has something to look up and so the sound is marked as streaming.
     */
    public static SoundEvent radioStream() {
        return require(radioStream, "radio stream sound event").get();
    }

    /**
     * The vanilla → 4M block conversions the depolarization hammer performs.
     *
     * <p>A supplier of the whole map, rather than a map of suppliers: the loader knows the mapping
     * when it constructs, but cannot resolve the right-hand side until the registries are filled.
     * The map-of-suppliers shape also fights the type system — {@code Map.of(GOLD_BLOCK, BASS)}
     * infers {@code Map<Block, RegistryObject<Block>>}, which is not a {@code Map<Block,
     * Supplier<Block>>}, and needs an explicit type witness to compile.
     */
    public static void bindDepolarizations(Supplier<Map<Block, Block>> conversions) {
        depolarizations = conversions;
        depolarizationsResolved = null;
    }

    /**
     * @return the 4M block {@code from} depolarizes into, or {@code null} if the hammer does nothing
     *         to it — the overwhelmingly common case on any given right-click, so not an error
     */
    public static Block depolarized(Block from) {
        // Resolved once and cached, so a right-click is a plain map lookup rather than a rebuild.
        // Single-threaded in practice (the server thread handles useOn), and a benign race would at
        // worst build the same immutable map twice.
        Map<Block, Block> table = depolarizationsResolved;
        if (table == null) {
            table = require(depolarizations, "depolarization table").get();
            depolarizationsResolved = table;
        }
        return table.get(from);
    }

    private static <T> Supplier<T> require(Supplier<T> supplier, String what) {
        if (supplier == null) {
            throw new IllegalStateException(
                    "The " + what + " was used before the loader bound it. MmmmContent.bind() must run "
                            + "in the mod entry class, before any block is placed.");
        }
        return supplier;
    }

    private MmmmContent() {
    }
}
