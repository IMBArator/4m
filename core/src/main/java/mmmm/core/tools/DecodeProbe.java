package mmmm.core.tools;

import mmmm.core.codec.Decoder;
import mmmm.core.codec.JLayerDecoder;
import mmmm.core.codec.PcmFormat;
import mmmm.core.frame.FormatSniffer;
import mmmm.core.frame.FrameParser;
import mmmm.core.frame.FrameParsers;
import mmmm.core.media.Codec;
import mmmm.core.media.MediaFrame;
import mmmm.core.security.EgressGuard;
import mmmm.core.source.IcyHttpSource;
import mmmm.core.source.SourceConfig;
import mmmm.core.source.SourceMetadata;
import mmmm.core.source.StationResolver;
import mmmm.core.source.StreamSource;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Decodes a station or a captured file to a {@code .wav} so a human can listen to it.
 *
 * <p>This is the verification step for the decoders that no unit test can replace. A frame parser
 * can be checked against arithmetic; a decoder can only really be checked by ear. Wrong channel
 * order, a byte-order slip, a dropped bit reservoir and a half-rate output all produce PCM that is
 * the right length and the right shape, and sounds obviously broken.
 *
 * <pre>{@code
 * ./tools/build-core.sh probe https://ice1.somafm.com/groovesalad-128-mp3 20 salad.mp3
 * ./tools/build-core.sh decode salad.mp3 salad.wav
 * ./tools/build-core.sh decode https://ice1.somafm.com/groovesalad-128-mp3 live.wav 20
 * }</pre>
 *
 * <p>Writes a canonical 44-byte RIFF header, then the decoder's PCM verbatim. Because
 * {@link mmmm.core.codec.Decoder} is specified to emit signed 16-bit little-endian interleaved
 * samples — which is also WAV's native layout — a correct decoder needs no conversion here. If this
 * tool has to massage the bytes to make them play, the decoder is wrong.
 */
public final class DecodeProbe {

    private static final int READ_BUFFER = 8192;

    private DecodeProbe() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: DecodeProbe <url-or-file> <out.wav> [seconds]");
            System.exit(2);
        }
        String source = args[0].trim();
        Path output = Path.of(args[1]);
        int seconds = args.length >= 3 ? Integer.parseInt(args[2]) : 0;

        // Write the PCM to a temporary file first: the WAV header carries byte counts that are not
        // known until the last frame is decoded, and streaming to a growable file then patching the
        // header is simpler than buffering an unbounded amount of audio in memory.
        Path pcmFile = Files.createTempFile("4m-decode", ".pcm");
        PcmFormat format;
        long pcmBytes;
        long framesIn = 0;
        Decoder decoder = new JLayerDecoder();

        try (OutputStream pcmOut = new BufferedOutputStream(Files.newOutputStream(pcmFile))) {
            Counter counter = new Counter(pcmOut);
            framesIn = source.startsWith("http://") || source.startsWith("https://")
                    ? decodeStation(URI.create(source), seconds, decoder, counter)
                    : decodeFile(Path.of(source), decoder, counter);
            pcmBytes = counter.bytes;
            format = decoder.format().orElse(null);
        } finally {
            decoder.close();
        }

        if (format == null || pcmBytes == 0) {
            Files.deleteIfExists(pcmFile);
            System.err.println("Nothing decoded — no PCM produced. Frames in: " + framesIn);
            System.exit(1);
        }

        writeWav(pcmFile, output, format, pcmBytes);
        Files.deleteIfExists(pcmFile);

        double durationSeconds = (double) pcmBytes / (format.sampleRate() * (double) format.frameBytes());
        System.out.printf(Locale.ROOT, "format     %d Hz, %d ch, signed 16-bit LE%n",
                format.sampleRate(), format.channels());
        System.out.printf(Locale.ROOT, "frames in  %d%n", framesIn);
        System.out.printf(Locale.ROOT, "dropped    %d%n", decoder.framesDropped());
        System.out.printf(Locale.ROOT, "pcm        %d bytes, %.2f s%n", pcmBytes, durationSeconds);
        System.out.printf(Locale.ROOT, "wrote      %s%n", output.toAbsolutePath());
        System.out.println();
        System.out.println("Now listen to it. Length alone proves nothing.");
    }

    private static long decodeFile(Path path, Decoder decoder, Counter out) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            return pump(in, decoder, out, null, Long.MAX_VALUE);
        }
    }

    private static long decodeStation(URI url, int seconds, Decoder decoder, Counter out)
            throws IOException {
        EgressGuard guard = EgressGuard.allowingAnyPublicHost();
        StationResolver.Resolution resolution = StationResolver.resolve(url, guard);
        if (resolution.transport() == StationResolver.Transport.HLS) {
            throw new IOException("HLS is not implemented yet; it is the last item in the build order.");
        }
        long deadline = seconds > 0
                ? System.nanoTime() + seconds * 1_000_000_000L
                : Long.MAX_VALUE;

        try (StreamSource source = IcyHttpSource.open(
                resolution.uri(), guard, title -> System.out.println("title      " + title),
                SourceConfig.DEFAULT)) {
            SourceMetadata metadata = source.metadata();
            metadata.name().ifPresent(name -> System.out.println("station    " + name));
            return pump(source, decoder, out, metadata.contentType().orElse(null), deadline);
        }
    }

    /** Reads from {@code in}, sniffs the codec, parses frames and decodes them until exhausted. */
    private static long pump(Object in, Decoder decoder, Counter out, String contentType, long deadline)
            throws IOException {
        byte[] buffer = new byte[READ_BUFFER];
        byte[] sniffPrefix = new byte[FormatSniffer.RECOMMENDED_BYTES];
        int sniffed = 0;
        FrameParser parser = null;
        long frames = 0;

        while (System.nanoTime() < deadline) {
            int n = in instanceof StreamSource source
                    ? source.read(buffer, 0, buffer.length)
                    : ((InputStream) in).read(buffer, 0, buffer.length);
            if (n < 0) {
                break;
            }
            if (n == 0) {
                continue;
            }

            if (parser == null) {
                int take = Math.min(n, sniffPrefix.length - sniffed);
                System.arraycopy(buffer, 0, sniffPrefix, sniffed, take);
                sniffed += take;
                if (sniffed < 4) {
                    continue;
                }
                final int prefixLength = sniffed;
                Codec codec = FormatSniffer.sniffOrContentType(sniffPrefix, 0, prefixLength, contentType)
                        .orElseThrow(() -> new IOException("Unrecognised stream format; first bytes were "
                                + hex(sniffPrefix, prefixLength)));
                if (codec != Codec.MP3) {
                    throw new IOException("Only MP3 decodes today — this stream is " + codec
                            + ". Vorbis lives in common/ (it wraps Minecraft's OggAudioStream) and "
                            + "AAC is blocked on real JAAD coordinates.");
                }
                System.out.println("codec      " + codec + " (sniffed)");
                parser = FrameParsers.forCodec(codec);
            }

            // The sniff did not consume anything — these same bytes must still reach the parser.
            parser.feed(buffer, 0, n, frame -> decodeOne(frame, decoder, out));
            frames = out.frames;
        }
        return frames;
    }

    private static void decodeOne(MediaFrame frame, Decoder decoder, Counter out) {
        out.frames++;
        decoder.decode(frame, out::write);
    }

    /** Counts what the decoder produced while forwarding it to a stream. */
    private static final class Counter {
        private final OutputStream sink;
        private long bytes;
        private long frames;

        Counter(OutputStream sink) {
            this.sink = sink;
        }

        void write(byte[] pcm, int off, int len) {
            try {
                sink.write(pcm, off, len);
                bytes += len;
            } catch (IOException e) {
                throw new UncheckedWriteException(e);
            }
        }
    }

    /** A PcmSink cannot throw IOException, so a write failure has to travel unchecked. */
    private static final class UncheckedWriteException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UncheckedWriteException(IOException cause) {
            super(cause);
        }
    }

    private static void writeWav(Path pcmFile, Path output, PcmFormat format, long pcmBytes)
            throws IOException {
        int byteRate = format.sampleRate() * format.frameBytes();
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(output.toFile()))) {
            out.write(new byte[]{'R', 'I', 'F', 'F'});
            writeInt32LE(out, (int) (36 + pcmBytes));
            out.write(new byte[]{'W', 'A', 'V', 'E'});

            out.write(new byte[]{'f', 'm', 't', ' '});
            writeInt32LE(out, 16);            // PCM fmt chunk size
            writeInt16LE(out, 1);             // format 1 = uncompressed PCM
            writeInt16LE(out, format.channels());
            writeInt32LE(out, format.sampleRate());
            writeInt32LE(out, byteRate);
            writeInt16LE(out, format.frameBytes());
            writeInt16LE(out, 16);            // bits per sample

            out.write(new byte[]{'d', 'a', 't', 'a'});
            writeInt32LE(out, (int) pcmBytes);

            Files.copy(pcmFile, out);
        }
    }

    private static void writeInt32LE(OutputStream out, int value) throws IOException {
        for (int i = 0; i < 4; i++) {
            out.write((value >> (8 * i)) & 0xFF);
        }
    }

    private static void writeInt16LE(OutputStream out, int value) throws IOException {
        for (int i = 0; i < 2; i++) {
            out.write((value >> (8 * i)) & 0xFF);
        }
    }

    private static String hex(byte[] data, int length) {
        StringBuilder sb = new StringBuilder(length * 3);
        for (int i = 0; i < length; i++) {
            sb.append(String.format(Locale.ROOT, "%02x ", data[i]));
        }
        return sb.toString().trim();
    }
}
