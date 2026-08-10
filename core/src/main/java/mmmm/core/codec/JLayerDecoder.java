package mmmm.core.codec;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.BitstreamException;
import javazoom.jl.decoder.DecoderException;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.Obuffer;
import javazoom.jl.decoder.SampleBuffer;
import mmmm.core.media.MediaFrame;

import java.io.InputStream;
import java.util.Optional;

/**
 * MP3 decode via JLayer.
 *
 * <h2>One MediaFrame is one MPEG frame</h2>
 * {@code Mp3FrameParser} hands over complete frames, header included, so this decodes exactly one
 * MPEG frame per {@link #decode} call. That is why the whole thing can be driven synchronously with
 * no internal queue: there is never a partial frame to hold onto.
 *
 * <h2>Why the Bitstream is long-lived</h2>
 * MP3 has a <b>bit reservoir</b> — a frame may spend bits carried over from earlier frames. JLayer
 * tracks that inside the layer-III decoder, across calls, on the same {@code Decoder} instance.
 * Building a fresh {@code Bitstream} and {@code Decoder} per frame would look tidier and would
 * quietly mangle every frame that used the reservoir, which on a real station is most of them. So
 * both instances live as long as the stream does, fed by an {@link InputStream} we push into.
 *
 * <h2>Errors resynchronise</h2>
 * A corrupt frame drops that frame and clears anything half-read, rather than propagating. The
 * reservoir is lost across the gap, so the frame after a bad one may click; that is the correct
 * trade against ending playback. {@link #framesDropped()} counts them.
 *
 * <p>Not thread-safe. One instance belongs to one decode thread.
 */
public final class JLayerDecoder implements Decoder {

    private final PushStream input = new PushStream();

    private Bitstream bitstream = new Bitstream(input);
    private javazoom.jl.decoder.Decoder decoder = new javazoom.jl.decoder.Decoder();

    private PcmFormat format;
    private byte[] scratch = new byte[0];
    private long framesDropped;

    @Override
    public void decode(MediaFrame frame, PcmSink sink) {
        input.append(frame.payload());
        try {
            Header header = bitstream.readFrame();
            if (header == null) {
                // The parser promised a whole frame, so this means the bytes were not one after
                // all. Drop them rather than leaving a partial frame to corrupt the next call.
                framesDropped++;
                input.clear();
                return;
            }
            Obuffer output = decoder.decodeFrame(header, bitstream);
            bitstream.closeFrame();

            if (format == null) {
                format = new PcmFormat(decoder.getOutputFrequency(), decoder.getOutputChannels());
            }
            if (output instanceof SampleBuffer samples) {
                emit(samples, sink);
            }
        } catch (BitstreamException | DecoderException | RuntimeException e) {
            // RuntimeException is in here deliberately: JLayer throws ArrayIndexOutOfBounds from
            // its Huffman tables on some malformed frames instead of a DecoderException, and a
            // decoder that dies on a corrupt frame is useless for live radio.
            framesDropped++;
            bitstream.closeFrame();
            input.clear();
        }
    }

    private void emit(SampleBuffer samples, PcmSink sink) {
        int count = samples.getBufferLength();
        if (count > 0) {
            short[] pcm = samples.getBuffer();
            int bytes = count * 2;
            if (scratch.length < bytes) {
                scratch = new byte[bytes];
            }
            for (int i = 0; i < count; i++) {
                short s = pcm[i];
                scratch[i * 2] = (byte) (s & 0xFF);
                scratch[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
            }
            sink.accept(scratch, 0, bytes);
        }
        // JLayer's SampleBuffer.write_buffer() is a no-op stub meant for subclasses to override, so
        // nothing resets the write pointer for us. Without this the buffer fills and overflows.
        samples.clear_buffer();
    }

    @Override
    public Optional<PcmFormat> format() {
        return Optional.ofNullable(format);
    }

    @Override
    public void reset() {
        input.clear();
        closeQuietly();
        // A new pair, because the bit reservoir and the synthesis filter's overlap state both
        // describe audio we are about to jump away from.
        bitstream = new Bitstream(input);
        decoder = new javazoom.jl.decoder.Decoder();
        format = null;
    }

    @Override
    public long framesDropped() {
        return framesDropped;
    }

    @Override
    public void close() {
        closeQuietly();
        input.clear();
    }

    private void closeQuietly() {
        try {
            bitstream.close();
        } catch (BitstreamException ignored) {
            // Closing a bitstream over an in-memory buffer that we are discarding anyway. There is
            // no resource behind it to leak and nothing a caller could do about a failure here.
        }
    }

    /**
     * An {@link InputStream} over bytes pushed in from outside.
     *
     * <p>Returns -1 when drained. {@code Bitstream} treats that as end of stream for the current
     * read, which is fine because we only ever read within a frame we have already appended in
     * full — it never has cause to read past the end.
     */
    private static final class PushStream extends InputStream {

        private byte[] buffer = new byte[4096];
        private int position;
        private int limit;

        void append(byte[] data) {
            compact();
            int needed = limit + data.length;
            if (needed > buffer.length) {
                byte[] grown = new byte[Math.max(needed, buffer.length * 2)];
                System.arraycopy(buffer, 0, grown, 0, limit);
                buffer = grown;
            }
            System.arraycopy(data, 0, buffer, limit, data.length);
            limit += data.length;
        }

        void clear() {
            position = 0;
            limit = 0;
        }

        private void compact() {
            if (position > 0) {
                System.arraycopy(buffer, position, buffer, 0, limit - position);
                limit -= position;
                position = 0;
            }
        }

        @Override
        public int read() {
            return position < limit ? buffer[position++] & 0xFF : -1;
        }

        @Override
        public int read(byte[] dest, int off, int len) {
            if (position >= limit) {
                return -1;
            }
            int n = Math.min(len, limit - position);
            System.arraycopy(buffer, position, dest, off, n);
            position += n;
            return n;
        }

        @Override
        public int available() {
            return limit - position;
        }
    }
}
