package com.maple.dpinviewer;

/** Streaming 16 kHz digital reference to the recorder's 44.1 kHz mono PCM. */
final class EchoPcm {
    private final short[] ring = new short[1024];
    private int head, size, phase;

    void append(short[] fourChannels, int count) {
        if (count < 0 || count > fourChannels.length || count % 4 != 0
                || size + count / 4 > ring.length) throw new IllegalArgumentException("PCM frame size");
        for (int i = 0; i < count; i += 4) {
            // Channels 0/1 are microphones; only the digital reference reaches the encoder.
            ring[(head + size++) % ring.length] = (short)
                    (((int) fourChannels[i + 2] + fourChannels[i + 3]) / 2);
        }
    }

    int read(short[] output, int offset, int count) {
        int written = 0;
        while (written < count && size >= 2) {
            int left = ring[head], right = ring[(head + 1) % ring.length];
            output[offset + written++] = (short) (left + (right - left) * phase / 44100);
            phase += 16000;
            if (phase >= 44100) {
                phase -= 44100;
                head = (head + 1) % ring.length;
                size--;
            }
        }
        return written;
    }

    void clear() {
        java.util.Arrays.fill(ring, (short) 0);
        head = size = phase = 0;
    }
}
