package mmmm.client;

import net.minecraft.client.sounds.AudioStream;

import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Adapter from a {@link ClientMediaSession}'s mono PCM ring to Minecraft's
 * {@link AudioStream} interface.
 *
 * <p>{@link ClientMediaSession#readPcm} already guarantees a full read every time, padding with
 * silence on underrun. That property is the only reason this class can be this thin: the
 * streaming-source path in {@code Channel.updateStream} treats a short read as end-of-stream and
 * stops the sound permanently (master plan §7.3), so the "never short" guarantee has to live
 * somewhere, and it lives one layer down.
 *
 * <p>One {@link AudioStream} instance is created per {@link RadioSoundInstance}, and {@code read}
 * is called repeatedly on the audio thread; the scratch array is therefore held as a field rather
 * than allocated per call.
 */
public final class RadioAudioStream implements AudioStream {

    private final ClientMediaSession session;
    private final AudioFormat format;

    /** Reused across reads; only the audio thread touches it. Grown, never shrunk. */
    private byte[] scratch = new byte[0];

    public RadioAudioStream(ClientMediaSession session) {
        this.session = session;
        this.format = new AudioFormat(session.sampleRate(), 16, 1, true, false);
    }

    @Override
    public AudioFormat getFormat() {
        return format;
    }

    @Override
    public ByteBuffer read(int bytes) {
        if (scratch.length < bytes) {
            scratch = new byte[bytes];
        }
        session.readPcm(scratch, 0, bytes);

        // Direct buffer, little-endian: that is the byte order the PCM was written in (16-bit signed
        // samples, low byte first) and what OpenAL expects. LWJGL accepts heap buffers too, but the
        // streaming path is hot enough that the extra copy on every pump is worth avoiding.
        ByteBuffer buf = ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(scratch, 0, bytes).flip();
        return buf;
    }

    @Override
    public void close() {
        // The session outlives this stream — it is shared between the sound and the drift loop, and
        // owned by ClientMedia. Closing it here would stop every other sound on the same station.
    }
}
