package com.hft.piano2midi.core;

import java.io.IOException;
import java.io.OutputStream;

/** 16-bit PCM mono WAV writer (for saving microphone recordings). */
public final class WavWriter {
    private WavWriter() {}

    private static void le32(OutputStream os, int v) throws IOException {
        os.write(v);
        os.write(v >> 8);
        os.write(v >> 16);
        os.write(v >> 24);
    }

    private static void le16(OutputStream os, int v) throws IOException {
        os.write(v);
        os.write(v >> 8);
    }

    public static void write(float[] samples, int n, int rate, OutputStream os) throws IOException {
        int data = n * 2;
        os.write(new byte[]{'R', 'I', 'F', 'F'});
        le32(os, 36 + data);
        os.write(new byte[]{'W', 'A', 'V', 'E', 'f', 'm', 't', ' '});
        le32(os, 16);
        le16(os, 1);
        le16(os, 1);
        le32(os, rate);
        le32(os, rate * 2);
        le16(os, 2);
        le16(os, 16);
        os.write(new byte[]{'d', 'a', 't', 'a'});
        le32(os, data);
        byte[] buf = new byte[8192];
        int k = 0;
        for (int i = 0; i < n; i++) {
            int v = Math.round(Math.max(-1f, Math.min(1f, samples[i])) * 32767f);
            buf[k++] = (byte) v;
            buf[k++] = (byte) (v >> 8);
            if (k == buf.length) {
                os.write(buf, 0, k);
                k = 0;
            }
        }
        os.write(buf, 0, k);
        os.flush();
    }
}
