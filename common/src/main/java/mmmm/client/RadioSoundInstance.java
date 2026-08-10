package mmmm.client;

import mmmm.MmmmContent;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

import java.util.concurrent.CompletableFuture;

/**
 * The streaming-source sound a playing radio emits.
 *
 * <h2>Why we override {@code getStream}</h2>
 * Forge patches {@link SoundInstance} with a default {@code getStream} that delegates to
 * {@link SoundBufferLibrary#getStream}; {@code SoundEngine.play} calls <em>that</em> method on the
 * instance rather than the library directly. So overriding it to return our own
 * {@link RadioAudioStream} is the entire integration with the sound engine — no Mixin, no AT, no
 * fragile hook into a vanilla method (master plan §7.3, ADR-0007 amendment).
 *
 * <h2>Why this class is tickable</h2>
 * Two reasons. First, the drift controller's rate trim has to be applied to {@code AL_PITCH} every
 * tick — the sound engine re-reads {@link #getPitch()} for ticking sounds, so overriding it to
 * return the trim is the whole control surface (master plan §5.3). Second, vanilla's ticking-sound
 * machinery gives us {@link #tick()} as the natural place to notice the underlying session has gone
 * away and stop ourselves.
 *
 * <h2>Attenuation</h2>
 * {@link net.minecraft.client.resources.sounds.SoundInstance.Attenuation#LINEAR} is what makes the
 * sound positional — OpenAL then applies 3D positioning, which it only does for mono sources. That
 * is the downmix's whole reason for existing; see {@link ClientMediaSession}.
 */
public final class RadioSoundInstance extends AbstractTickableSoundInstance {

    private final int sessionId;
    private final BlockPos pos;
    private float ratePitch = 1.0F;

    public RadioSoundInstance(BlockPos pos, int sessionId, float volume) {
        super(MmmmContent.radioStream(), SoundSource.RECORDS, RandomSource.create());
        this.pos = pos;
        this.sessionId = sessionId;
        this.volume = volume;
        this.looping = true;
        this.attenuation = Attenuation.LINEAR;
        this.relative = false;
        this.x = pos.getX() + 0.5;
        this.y = pos.getY() + 0.5;
        this.z = pos.getZ() + 0.5;
    }

    public int sessionId() {
        return sessionId;
    }

    public BlockPos blockPos() {
        return pos;
    }

    /** {@inheritDoc} — the override that supplies our stream to the sound engine. */
    @Override
    public CompletableFuture<net.minecraft.client.sounds.AudioStream> getStream(
            SoundBufferLibrary buffers, Sound sound, boolean looping) {
        ClientMediaSession session = ClientMedia.sessionForBlock(pos);
        if (session == null || session.sessionId() != sessionId) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.completedFuture(new RadioAudioStream(session));
    }

    @Override
    public float getPitch() {
        return ratePitch;
    }

    /** Drives {@code AL_PITCH}; called once per tick from {@link ClientMedia#tickBlock}. */
    public void setRateTrim(double trim) {
        this.ratePitch = (float) trim;
    }

    @Override
    public void tick() {
        ClientMediaSession session = ClientMedia.sessionForBlock(pos);
        if (session == null || session.sessionId() != sessionId || session.isClosed()) {
            // The relay dropped the subscriber, the block was broken, or the chunk unloaded. We do
            // not get a callback for any of those on the client; noticing it here is the cleanup.
            stop();
        }
    }
}
