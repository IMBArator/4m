package mmmm.core.relay;

/**
 * Synthetic MPEG-1 Layer III frames, headers only.
 *
 * <p>The relay parses frame headers and never decodes (ADR-0004), so a zero-filled body is
 * indistinguishable from real audio as far as anything under test is concerned. That keeps these
 * tests free of a binary fixture and free of the internet.
 */
final class Mp3Fixture {

    /** 128 kbps, 44.1 kHz, MPEG-1 Layer III: {@code floor(144 * 128000 / 44100)}. */
    static final int FRAME_BYTES = 417;

    /** 1152 samples at 44.1 kHz, in microseconds. Not an integer, which is the whole point. */
    static final double FRAME_MICROS = 1152 * 1_000_000.0 / 44100.0;

    static final int SAMPLE_RATE = 44100;

    private Mp3Fixture() {
    }

    /** {@code count} contiguous frames, ready to be fed to {@code Mp3FrameParser}. */
    static byte[] frames(int count) {
        byte[] out = new byte[count * FRAME_BYTES];
        for (int i = 0; i < count; i++) {
            int base = i * FRAME_BYTES;
            out[base] = (byte) 0xFF;
            out[base + 1] = (byte) 0xFB;   // MPEG-1, Layer III, no CRC
            out[base + 2] = (byte) 0x90;   // 128 kbps, 44100 Hz, no padding
            out[base + 3] = (byte) 0x40;   // joint stereo
        }
        return out;
    }

    static long framesForMicros(long micros) {
        return (long) Math.ceil(micros / FRAME_MICROS);
    }
}
