package mmmm.core.tools;

import mmmm.core.frame.FormatSniffer;
import mmmm.core.frame.FrameParser;
import mmmm.core.frame.FrameParsers;
import mmmm.core.media.Codec;
import mmmm.core.media.StreamInfo;
import mmmm.core.security.EgressGuard;
import mmmm.core.source.IcyHttpSource;
import mmmm.core.source.SourceConfig;
import mmmm.core.source.SourceMetadata;
import mmmm.core.source.StationResolver;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Runs the server-side pipeline against a real station, headlessly.
 *
 * <p>Step 1 of the build order, and the loop worth living in while the pipeline is under
 * construction: transport, sniffing and framing all exercise here, where a failure is a stack trace
 * in a terminal rather than silence in a game you had to launch first.
 *
 * <p>What it actually checks is the timeline, which is the part sync depends on. It compares the
 * media time the parser derived against the wall-clock time the download took. For a live stream
 * those should agree closely — a station sends about one second of audio per second. A persistent
 * gap means the frame arithmetic is wrong, and that is a bug which would otherwise surface only as
 * slow drift between players hours later, looking convincingly like a clock problem.
 *
 * <pre>{@code
 * java -cp core/build/classes/java/main mmmm.core.tools.StreamProbe <url> [seconds] [out.raw]
 * }</pre>
 *
 * <p>Any dumped file is the raw encoded stream — {@code ffplay out.raw} to hear it. Nothing is
 * decoded here, because the server never decodes (ADR-0004).
 */
public final class StreamProbe {

    private StreamProbe() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: StreamProbe <url> [seconds] [output-file]");
            System.exit(2);
        }
        URI url = URI.create(args[0].trim());
        int seconds = args.length > 1 ? Integer.parseInt(args[1]) : 15;
        Path output = args.length > 2 ? Path.of(args[2]) : null;

        // Arbitrary URLs are this tool's whole purpose, so it opts into the permissive guard.
        // Private and link-local ranges are still refused (ADR-0011).
        EgressGuard guard = EgressGuard.allowingAnyPublicHost();

        System.out.println("resolving  " + url);
        StationResolver.Resolution resolution = StationResolver.resolve(url, guard);
        System.out.println("transport  " + resolution.transport());
        System.out.println("endpoint   " + resolution.uri());

        if (resolution.transport() == StationResolver.Transport.HLS) {
            System.err.println("HLS is not implemented yet; it is the last item in the build order.");
            System.exit(1);
        }

        try (IcyHttpSource source = IcyHttpSource.open(
                resolution.uri(), guard,
                title -> System.out.println("\ntitle      " + title),
                SourceConfig.DEFAULT)) {

            SourceMetadata metadata = source.metadata();
            metadata.name().ifPresent(n -> System.out.println("station    " + n));
            metadata.genre().ifPresent(g -> System.out.println("genre      " + g));
            metadata.contentType().ifPresent(c -> System.out.println("mime       " + c));
            if (metadata.bitrateKbps() > 0) {
                System.out.println("bitrate    " + metadata.bitrateKbps() + " kbps");
            }

            new StreamProbe().run(source, metadata, seconds, output);
        }
    }

    /**
     * Wall time to ignore before measuring the media/wall ratio.
     *
     * <p>Icecast burst-on-connect hands a new listener several seconds of buffered audio the
     * instant it connects, so the first moments run far faster than realtime — 15 s of audio inside
     * the first wall second is typical. Averaging from t=0 therefore reports a ratio around 2 for a
     * perfectly correct parser, and a check that cries wolf on every real station is a check
     * everyone learns to ignore. Only the steady state after the burst says anything about the
     * frame arithmetic.
     */
    private static final long SETTLE_NANOS = 4_000_000_000L;

    private FrameParser parser;
    private Codec codec;
    private long frames;
    private long payloadBytes;
    private long totalBytes;
    private long firstPtsMicros = -1;
    private long lastPtsMicros;
    private long startNanos;
    private long lastProgressNanos;

    /** Wall and media positions at the end of the settling window, or -1 if not reached. */
    private long settleWallNanos = -1;
    private long settleMediaMicros;

    private void run(IcyHttpSource source, SourceMetadata metadata, int seconds, Path output)
            throws IOException {

        byte[] buffer = new byte[8192];
        byte[] sniffPrefix = new byte[FormatSniffer.RECOMMENDED_BYTES];
        int sniffed = 0;

        startNanos = System.nanoTime();
        long deadline = startNanos + seconds * 1_000_000_000L;

        try (OutputStream out = output == null ? null : Files.newOutputStream(output)) {
            while (System.nanoTime() < deadline) {
                int n = source.read(buffer, 0, buffer.length);
                if (n < 0) {
                    System.out.println("\norigin closed the connection");
                    break;
                }
                totalBytes += n;
                if (out != null) {
                    out.write(buffer, 0, n);
                }

                if (parser == null) {
                    int take = Math.min(n, sniffPrefix.length - sniffed);
                    System.arraycopy(buffer, 0, sniffPrefix, sniffed, take);
                    sniffed += take;
                    if (sniffed < 4) {
                        continue;
                    }
                    final int prefixLength = sniffed;
                    codec = FormatSniffer
                            .sniffOrContentType(sniffPrefix, 0, prefixLength, metadata.contentType().orElse(null))
                            .orElseThrow(() -> new IOException("Unrecognised stream format; first bytes were "
                                    + hex(sniffPrefix, prefixLength)));
                    System.out.println("codec      " + codec + " (sniffed)");
                    parser = FrameParsers.forCodec(codec);
                }

                parser.feed(buffer, 0, n, this::countFrame);
                reportProgress();
            }
        }

        System.out.println();
        summarise(output);
    }

    private void countFrame(mmmm.core.media.MediaFrame frame) {
        frames++;
        payloadBytes += frame.size();
        if (firstPtsMicros < 0) {
            firstPtsMicros = frame.ptsMicros();
        }
        lastPtsMicros = frame.ptsMicros();
    }

    private void reportProgress() {
        long now = System.nanoTime();
        long elapsed = now - startNanos;

        if (settleWallNanos < 0 && elapsed >= SETTLE_NANOS) {
            settleWallNanos = elapsed;
            settleMediaMicros = parser.currentPtsMicros();
        }

        // Once per second. Terminals collapse the \r into one line; a pipe or log keeps them all,
        // and twenty lines a second would bury the summary that actually matters.
        if (now - lastProgressNanos < 1_000_000_000L) {
            return;
        }
        lastProgressNanos = now;
        System.out.printf(Locale.ROOT, "\r%6.1fs wall  %6.1fs media  %6d frames  %8d bytes",
                elapsed / 1e9, parser.currentPtsMicros() / 1e6, frames, totalBytes);
        System.out.flush();
    }

    private void summarise(Path output) {
        if (parser == null || frames == 0) {
            System.out.println("no frames parsed — the stream format was not recognised");
            return;
        }

        double wallSeconds = (System.nanoTime() - startNanos) / 1e9;
        double mediaSeconds = parser.currentPtsMicros() / 1e6;

        System.out.println("codec      " + codec);
        StreamInfo info = parser.streamInfo().orElse(null);
        if (info != null) {
            System.out.println("format     " + info.sampleRate() + " Hz, " + info.channels() + " ch");
            if (info.codecInit().length > 0) {
                System.out.println("codecInit  " + info.codecInit().length
                        + " bytes (a mid-stream join cannot decode without these)");
            }
        }
        System.out.printf(Locale.ROOT, "frames     %d, %d payload bytes of %d read%n",
                frames, payloadBytes, totalBytes);
        System.out.printf(Locale.ROOT, "pts range  %.3fs .. %.3fs%n",
                firstPtsMicros / 1e6, lastPtsMicros / 1e6);
        System.out.printf(Locale.ROOT, "media      %.2fs in %.2fs wall%n", mediaSeconds, wallSeconds);
        reportTimeline(wallSeconds, mediaSeconds);

        // Bytes the parser never handed on. Container framing accounts for a little; a large
        // figure means frames are being dropped.
        double unframed = 100.0 * (totalBytes - payloadBytes) / totalBytes;
        System.out.printf(Locale.ROOT, "unframed   %.1f%% of bytes read%n", unframed);

        if (output != null) {
            System.out.println("wrote      " + output.toAbsolutePath());
            System.out.println("           play with: ffplay -autoexit " + output);
        }
    }

    /**
     * Reports the media/wall ratio, measured after the burst has drained.
     *
     * <p>This is the check the whole tool exists for: a live station sends about one second of
     * audio per second, so a steady-state ratio away from 1.0 means the frame durations are being
     * computed wrongly. In the game that bug would surface only as slow drift between players,
     * hours later, looking convincingly like a clock problem.
     */
    private void reportTimeline(double wallSeconds, double mediaSeconds) {
        if (settleWallNanos < 0) {
            System.out.printf(Locale.ROOT,
                    "timeline   not measured — run for more than %ds so the connect burst can drain%n",
                    SETTLE_NANOS / 1_000_000_000L);
            return;
        }

        double settleWall = settleWallNanos / 1e9;
        double settleMedia = settleMediaMicros / 1e6;
        double steadyWall = wallSeconds - settleWall;
        double steadyMedia = mediaSeconds - settleMedia;

        if (steadyWall < 2.0) {
            System.out.printf(Locale.ROOT,
                    "timeline   not measured — only %.1fs of steady state after the burst%n", steadyWall);
            return;
        }

        // What the origin handed over before throttling to realtime. Informational, but worth
        // seeing: the relay will receive this same burst at session start, and the backlog ring
        // has to cope with several seconds of audio arriving at once.
        double burst = settleMedia - settleWall;
        if (burst > 1.0) {
            System.out.printf(Locale.ROOT, "burst      %.1fs of buffered audio on connect%n", burst);
        }

        double ratio = steadyMedia / steadyWall;
        if (ratio < 0.95 || ratio > 1.05) {
            System.out.printf(Locale.ROOT,
                    "WARNING    steady-state media/wall ratio %.3f over %.1fs — suspect the frame "
                            + "duration arithmetic%n", ratio, steadyWall);
        } else {
            System.out.printf(Locale.ROOT,
                    "timeline   ok (steady-state media/wall ratio %.3f over %.1fs)%n", ratio, steadyWall);
        }
    }

    private static String hex(byte[] b, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(len, 8); i++) {
            sb.append(String.format(Locale.ROOT, "%02X ", b[i]));
        }
        return sb.toString().trim();
    }
}
