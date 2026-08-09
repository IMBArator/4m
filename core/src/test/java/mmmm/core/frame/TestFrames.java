package mmmm.core.frame;

import java.io.ByteArrayOutputStream;

/**
 * Synthesises valid MP3, ADTS and Ogg data for tests.
 *
 * <p>Built programmatically rather than checked in as binary fixtures, deliberately: a test that
 * says "144 * 128000 / 44100 + 0 = 417 bytes" states its expectation in the open, where a
 * disagreement with the parser is visible. A binary blob asserts the same thing invisibly, and when
 * it fails you cannot tell whether the parser or the fixture is wrong.
 */
final class TestFrames {

    private TestFrames() {
    }

    // ---------------------------------------------------------------- MP3

    /**
     * One MPEG-1 Layer III frame, header plus filler.
     *
     * @param bitrateKbps must be a valid MPEG-1 Layer III bitrate
     * @param sampleRate  44100, 48000 or 32000
     */
    static byte[] mp3Frame(int bitrateKbps, int sampleRate, boolean padding) {
        int bitrateIndex = switch (bitrateKbps) {
            case 32 -> 1; case 40 -> 2; case 48 -> 3; case 56 -> 4;
            case 64 -> 5; case 80 -> 6; case 96 -> 7; case 112 -> 8;
            case 128 -> 9; case 160 -> 10; case 192 -> 11; case 224 -> 12;
            case 256 -> 13; case 320 -> 14;
            default -> throw new IllegalArgumentException("Not an MPEG-1 L3 bitrate: " + bitrateKbps);
        };
        int rateIndex = switch (sampleRate) {
            case 44100 -> 0; case 48000 -> 1; case 32000 -> 2;
            default -> throw new IllegalArgumentException("Not an MPEG-1 rate: " + sampleRate);
        };

        int length = (144 * bitrateKbps * 1000) / sampleRate + (padding ? 1 : 0);
        byte[] frame = new byte[length];
        frame[0] = (byte) 0xFF;
        // 111 sync | 11 MPEG-1 | 01 Layer III | 1 no CRC
        frame[1] = (byte) 0xFB;
        frame[2] = (byte) ((bitrateIndex << 4) | (rateIndex << 2) | ((padding ? 1 : 0) << 1));
        // Joint stereo, so the parser should report 2 channels.
        frame[3] = (byte) 0x40;
        return frame;
    }

    /** Expected byte length of an MPEG-1 Layer III frame. */
    static int mp3FrameLength(int bitrateKbps, int sampleRate, boolean padding) {
        return (144 * bitrateKbps * 1000) / sampleRate + (padding ? 1 : 0);
    }

    /** A minimal ID3v2 tag of the given payload size, as stations prepend to a stream. */
    static byte[] id3v2Tag(int payloadSize) {
        byte[] tag = new byte[10 + payloadSize];
        tag[0] = 'I';
        tag[1] = 'D';
        tag[2] = '3';
        tag[3] = 3;
        tag[4] = 0;
        tag[5] = 0;
        // Synchsafe size: 7 significant bits per byte.
        tag[6] = (byte) ((payloadSize >> 21) & 0x7F);
        tag[7] = (byte) ((payloadSize >> 14) & 0x7F);
        tag[8] = (byte) ((payloadSize >> 7) & 0x7F);
        tag[9] = (byte) (payloadSize & 0x7F);
        return tag;
    }

    // --------------------------------------------------------------- ADTS

    /**
     * One ADTS AAC frame.
     *
     * @param sampleRate must be in the ADTS rate table
     * @param channels   channel configuration, 1..6
     * @param totalBytes whole frame including the 7-byte header
     */
    static byte[] adtsFrame(int sampleRate, int channels, int totalBytes) {
        int rateIndex = switch (sampleRate) {
            case 96000 -> 0; case 88200 -> 1; case 64000 -> 2; case 48000 -> 3;
            case 44100 -> 4; case 32000 -> 5; case 24000 -> 6; case 22050 -> 7;
            case 16000 -> 8; case 12000 -> 9; case 11025 -> 10; case 8000 -> 11;
            case 7350 -> 12;
            default -> throw new IllegalArgumentException("Not an ADTS rate: " + sampleRate);
        };
        if (totalBytes < 7) {
            throw new IllegalArgumentException("ADTS frame must be at least 7 bytes");
        }

        byte[] frame = new byte[totalBytes];
        frame[0] = (byte) 0xFF;
        // 1111 sync | 1 MPEG-4 | 00 layer | 1 protection absent
        frame[1] = (byte) 0xF1;
        // 00 profile (AAC Main) | rate index | 0 private | top bit of channel config
        frame[2] = (byte) ((rateIndex << 2) | ((channels >> 2) & 0x01));
        // low two bits of channel config, then the top two bits of the 13-bit length
        frame[3] = (byte) (((channels & 0x03) << 6) | ((totalBytes >> 11) & 0x03));
        frame[4] = (byte) ((totalBytes >> 3) & 0xFF);
        // low three bits of length, then buffer fullness
        frame[5] = (byte) (((totalBytes & 0x07) << 5) | 0x1F);
        // remaining fullness bits, and 00 = one raw data block
        frame[6] = (byte) 0xFC;
        return frame;
    }

    // ---------------------------------------------------------------- Ogg

    /**
     * One Ogg page.
     *
     * @param flags     header type: 0x02 beginning of stream, 0x04 end of stream
     * @param granule   granule position, or -1 when no packet ends on this page
     * @param packets   packet payloads; each is laced so that it terminates on this page
     */
    static byte[] oggPage(int flags, long granule, int serial, int sequence, byte[]... packets) {
        ByteArrayOutputStream segmentTable = new ByteArrayOutputStream();
        ByteArrayOutputStream body = new ByteArrayOutputStream();

        for (byte[] packet : packets) {
            int remaining = packet.length;
            while (remaining >= 255) {
                segmentTable.write(255);
                remaining -= 255;
            }
            // A lacing value below 255 terminates the packet; a packet that is an exact multiple of
            // 255 still needs an explicit zero to say so.
            segmentTable.write(remaining);
            body.write(packet, 0, packet.length);
        }

        byte[] segments = segmentTable.toByteArray();
        byte[] payload = body.toByteArray();

        ByteArrayOutputStream page = new ByteArrayOutputStream();
        page.write('O');
        page.write('g');
        page.write('g');
        page.write('S');
        page.write(0);
        page.write(flags);
        writeInt64LE(page, granule);
        writeInt32LE(page, serial);
        writeInt32LE(page, sequence);
        writeInt32LE(page, 0); // CRC; nothing in the parser verifies it
        page.write(segments.length);
        page.write(segments, 0, segments.length);
        page.write(payload, 0, payload.length);
        return page.toByteArray();
    }

    /** A Vorbis identification packet declaring the given rate and channel count. */
    static byte[] vorbisIdentificationPacket(int sampleRate, int channels) {
        byte[] packet = new byte[30];
        packet[0] = 1;
        packet[1] = 'v';
        packet[2] = 'o';
        packet[3] = 'r';
        packet[4] = 'b';
        packet[5] = 'i';
        packet[6] = 's';
        // bytes 7..10: version, left zero
        packet[11] = (byte) channels;
        packet[12] = (byte) (sampleRate & 0xFF);
        packet[13] = (byte) ((sampleRate >> 8) & 0xFF);
        packet[14] = (byte) ((sampleRate >> 16) & 0xFF);
        packet[15] = (byte) ((sampleRate >> 24) & 0xFF);
        return packet;
    }

    static byte[] vorbisCommentPacket() {
        byte[] packet = new byte[24];
        packet[0] = 3;
        packet[1] = 'v';
        packet[2] = 'o';
        packet[3] = 'r';
        packet[4] = 'b';
        packet[5] = 'i';
        packet[6] = 's';
        return packet;
    }

    static byte[] vorbisSetupPacket() {
        byte[] packet = new byte[64];
        packet[0] = 5;
        packet[1] = 'v';
        packet[2] = 'o';
        packet[3] = 'r';
        packet[4] = 'b';
        packet[5] = 'i';
        packet[6] = 's';
        return packet;
    }

    static byte[] audioPacket(int size) {
        return new byte[size];
    }

    private static void writeInt32LE(ByteArrayOutputStream out, int value) {
        for (int i = 0; i < 4; i++) {
            out.write((value >> (8 * i)) & 0xFF);
        }
    }

    private static void writeInt64LE(ByteArrayOutputStream out, long value) {
        for (int i = 0; i < 8; i++) {
            out.write((int) ((value >> (8 * i)) & 0xFF));
        }
    }

    /** Concatenates byte arrays, for feeding a parser one blob. */
    static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] p : parts) {
            total += p.length;
        }
        byte[] out = new byte[total];
        int pos = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, pos, p.length);
            pos += p.length;
        }
        return out;
    }
}
