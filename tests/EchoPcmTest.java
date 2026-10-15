package com.maple.dpinviewer;

import java.util.ArrayList;
import java.util.List;

public final class EchoPcmTest {
    public static void main(String[] args) {
        List<Short> small = convert(17), large = convert(4096);
        if (!small.equals(large)) throw new AssertionError("Read boundaries changed PCM");
        int expected = (int) Math.ceil(159999.0 * 44100 / 16000);
        if (small.size() != expected) throw new AssertionError("Sample clock drift: " + small.size());
        for (short value : small) if (value != 2000)
            throw new AssertionError("Microphone samples leaked into reference: " + value);
        EchoPcm pcm = new EchoPcm();
        pcm.append(new short[]{32767, -32768, 0, 0, -32768, 32767, 0, 0}, 8);
        short[] out = new short[16];
        int count = pcm.read(out, 2, 10);
        for (int i = 2; i < 2 + count; i++) if (out[i] != 0) throw new AssertionError("Mic isolation");
        pcm.clear();
        if (pcm.read(out, 0, out.length) != 0) throw new AssertionError("Clear retained data");
        pcm.append(new short[]{0, 0, -32768, -32768, 0, 0, 32767, 32767}, 8);
        count = pcm.read(out, 0, out.length);
        if (count != 3 || out[0] != -32768 || out[1] <= out[0] || out[2] <= out[1])
            throw new AssertionError("Interpolation overflow");
        try {
            pcm.append(new short[5], 5);
            throw new AssertionError("Accepted partial frame");
        } catch (IllegalArgumentException expectedError) { }
        System.out.println("RESULT=success");
    }

    private static List<Short> convert(int readSize) {
        EchoPcm pcm = new EchoPcm();
        short[] input = new short[1280], output = new short[readSize];
        List<Short> result = new ArrayList<>();
        for (int block = 0; block < 500; block++) {
            for (int i = 0; i < input.length; i += 4) {
                input[i] = 32767;
                input[i + 1] = -32768;
                input[i + 2] = 1000;
                input[i + 3] = 3000;
            }
            pcm.append(input, input.length);
            int n;
            while ((n = pcm.read(output, 0, output.length)) != 0)
                for (int i = 0; i < n; i++) result.add(output[i]);
        }
        return result;
    }
}
