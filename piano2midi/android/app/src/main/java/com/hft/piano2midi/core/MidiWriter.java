package com.hft.piano2midi.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Standard MIDI File (format 1) writer: a tempo track plus one track per part.
 * Times are written relative to `origin` (the first downbeat) at the given tempo,
 * so bars line up in DAWs and notation programs.
 */
public final class MidiWriter {

    public static final int PPQ = 480;

    /** One output track. */
    public static final class Track {
        public final String name;
        public final int channel, program;
        public final List<Note> notes;

        public Track(String name, int channel, int program, List<Note> notes) {
            this.name = name;
            this.channel = channel;
            this.program = program;
            this.notes = notes;
        }
    }

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

    /** Single piano track at 120 BPM, times as transcribed. */
    public static void write(List<Note> notes, String trackName, OutputStream os) throws IOException {
        List<Track> t = new ArrayList<Track>();
        t.add(new Track(trackName, 0, 0, notes));
        write(t, 120.0, 4, 0.0, os);
    }

    public static byte[] toBytes(List<Track> tracks, double bpm, int beatsPerBar, double origin) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try {
            write(tracks, bpm, beatsPerBar, origin, bos);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return bos.toByteArray();
    }

    public static void write(List<Track> tracks, double bpm, int beatsPerBar, double origin, OutputStream os)
            throws IOException {
        int tempoUs = (int) Math.round(60_000_000.0 / bpm);
        double ticksPerSec = PPQ * bpm / 60.0;

        ByteArrayOutputStream t0 = new ByteArrayOutputStream();
        writeVar(t0, 0);
        t0.write(new byte[]{(byte) 0xFF, 0x51, 0x03, (byte) (tempoUs >> 16), (byte) (tempoUs >> 8), (byte) tempoUs});
        writeVar(t0, 0);
        t0.write(new byte[]{(byte) 0xFF, 0x58, 0x04, (byte) beatsPerBar, 0x02, 0x18, 0x08});
        writeVar(t0, 0);
        t0.write(new byte[]{(byte) 0xFF, 0x2F, 0x00});

        List<byte[]> bodies = new ArrayList<byte[]>();
        bodies.add(t0.toByteArray());
        for (Track tr : tracks) {
            int ch = tr.channel & 0x0F;
            List<Ev> evs = new ArrayList<Ev>();
            for (Note n : tr.notes) {
                long on = Math.max(0L, Math.round((n.onset - origin) * ticksPerSec));
                long off = Math.max(on + 1, Math.round((n.offset - origin) * ticksPerSec));
                int vel = Math.max(1, Math.min(127, n.velocity));
                int p = Math.max(0, Math.min(127, n.pitch));
                evs.add(new Ev(on, 1, new byte[]{(byte) (0x90 | ch), (byte) p, (byte) vel}));
                evs.add(new Ev(off, 0, new byte[]{(byte) (0x80 | ch), (byte) p, 0x40}));
            }
            Collections.sort(evs, new Comparator<Ev>() {
                @Override
                public int compare(Ev a, Ev b) {
                    int c = Long.compare(a.tick, b.tick);
                    return c != 0 ? c : Integer.compare(a.order, b.order);
                }
            });
            ByteArrayOutputStream t = new ByteArrayOutputStream();
            if (tr.name != null && !tr.name.isEmpty()) {
                byte[] name = tr.name.getBytes("UTF-8");
                writeVar(t, 0);
                t.write(new byte[]{(byte) 0xFF, 0x03});
                writeVar(t, name.length);
                t.write(name);
            }
            writeVar(t, 0);
            t.write(new byte[]{(byte) (0xC0 | ch), (byte) (tr.program & 0x7F)});
            long last = 0;
            for (Ev e : evs) {
                writeVar(t, e.tick - last);
                t.write(e.data);
                last = e.tick;
            }
            writeVar(t, 0);
            t.write(new byte[]{(byte) 0xFF, 0x2F, 0x00});
            bodies.add(t.toByteArray());
        }

        int n = bodies.size();
        os.write(new byte[]{'M', 'T', 'h', 'd', 0, 0, 0, 6, 0, 1, (byte) (n >> 8), (byte) n, (byte) (PPQ >> 8), (byte) PPQ});
        for (byte[] b : bodies) writeChunk(os, b);
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
