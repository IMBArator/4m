package mmmm;

import mmmm.block.RadioBlockEntity;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.level.block.entity.BlockEntityType;

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
