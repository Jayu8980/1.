package com.hft.piano2midi.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Minimal Standard MIDI File (format 1) writer: tempo track + one piano track. */
public final class MidiWriter {

    public static final int PPQ = 480;
    public static final int TEMPO_US = 500000; // 120 BPM -> 960 ticks per second

    private MidiWriter() {}

    private static final class Ev {
        final long tick;
        final int order; // note-offs (0) before note-ons (1) at the same tick
        final byte[] data;

        Ev(long tick, int order, byte[] data) {
            this.tick = tick;
            this.order = order;
            this.data = data;
        }
    }

    private static long toTick(double sec) {
        return Math.max(0L, Math.round(sec * PPQ * 1e6 / TEMPO_US));
    }

    public static byte[] toBytes(List<Note> notes, String trackName) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try {
            write(notes, trackName, bos);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return bos.toByteArray();
    }

    public static void write(List<Note> notes, String trackName, OutputStream os) throws IOException {
        // Track 0: tempo + time signature
        ByteArrayOutputStream t0 = new ByteArrayOutputStream();
        writeVar(t0, 0);
        t0.write(new byte[]{(byte) 0xFF, 0x51, 0x03,
                (byte) (TEMPO_US >> 16), (byte) (TEMPO_US >> 8), (byte) TEMPO_US});
        writeVar(t0, 0);
        t0.write(new byte[]{(byte) 0xFF, 0x58, 0x04, 0x04, 0x02, 0x18, 0x08});
        writeVar(t0, 0);
        t0.write(new byte[]{(byte) 0xFF, 0x2F, 0x00});

        // Track 1: notes
        List<Ev> evs = new ArrayList<Ev>();
        for (Note n : notes) {
            long on = toTick(n.onset);
            long off = Math.max(on + 1, toTick(n.offset));
            int vel = Math.max(1, Math.min(127, n.velocity));
            int p = Math.max(0, Math.min(127, n.pitch));
            evs.add(new Ev(on, 1, new byte[]{(byte) 0x90, (byte) p, (byte) vel}));
            evs.add(new Ev(off, 0, new byte[]{(byte) 0x80, (byte) p, 0x40}));
        }
        Collections.sort(evs, new Comparator<Ev>() {
            @Override
            public int compare(Ev a, Ev b) {
                int c = Long.compare(a.tick, b.tick);
                return c != 0 ? c : Integer.compare(a.order, b.order);
            }
        });
        ByteArrayOutputStream t1 = new ByteArrayOutputStream();
        if (trackName != null && !trackName.isEmpty()) {
            byte[] name = trackName.getBytes("UTF-8");
            writeVar(t1, 0);
            t1.write(new byte[]{(byte) 0xFF, 0x03});
            writeVar(t1, name.length);
            t1.write(name);
        }
        writeVar(t1, 0);
        t1.write(new byte[]{(byte) 0xC0, 0x00}); // program 0: Acoustic Grand Piano
        long last = 0;
        for (Ev e : evs) {
            writeVar(t1, e.tick - last);
            t1.write(e.data);
            last = e.tick;
        }
        writeVar(t1, 0);
        t1.write(new byte[]{(byte) 0xFF, 0x2F, 0x00});

        os.write(new byte[]{'M', 'T', 'h', 'd', 0, 0, 0, 6, 0, 1, 0, 2, (byte) (PPQ >> 8), (byte) PPQ});
        writeChunk(os, t0.toByteArray());
        writeChunk(os, t1.toByteArray());
        os.flush();
    }

    private static void writeChunk(OutputStream os, byte[] body) throws IOException {
        int n = body.length;
        os.write(new byte[]{'M', 'T', 'r', 'k', (byte) (n >> 24), (byte) (n >> 16), (byte) (n >> 8), (byte) n});
        os.write(body);
    }

    private static void writeVar(OutputStream os, long value) throws IOException {
        long buffer = value & 0x7F;
        while ((value >>= 7) > 0) {
            buffer <<= 8;
            buffer |= ((value & 0x7F) | 0x80);
        }
        while (true) {
            os.write((int) (buffer & 0xFF));
            if ((buffer & 0x80) != 0) buffer >>= 8;
            else break;
        }
    }
}
