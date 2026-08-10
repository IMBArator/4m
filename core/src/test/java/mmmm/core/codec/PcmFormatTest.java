package mmmm.core.codec;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PcmFormatTest {

    @Test
    void frameBytesCoversEveryChannel() {
        assertEquals(4, new PcmFormat(44100, 2).frameBytes());
        assertEquals(2, new PcmFormat(44100, 1).frameBytes());
    }

    @Test
    void sizesARingFromADuration() {
        PcmFormat stereo = new PcmFormat(44100, 2);

        // One second of CD-quality stereo is the familiar 176_400 bytes.
        assertEquals(176_400, stereo.bytesForMicros(1_000_000));
        assertEquals(529_200, stereo.bytesForMicros(3_000_000));
    }

    @Test
    void rejectsImpossibleFormats() {
        assertThrows(IllegalArgumentException.class, () -> new PcmFormat(0, 2));
        assertThrows(IllegalArgumentException.class, () -> new PcmFormat(-1, 2));
        assertThrows(IllegalArgumentException.class, () -> new PcmFormat(44100, 0));
    }
}
