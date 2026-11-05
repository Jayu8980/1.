package com.hft.piano2midi.core.synth;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * SoundFont 2 reader. Sample data stays in the (ideally memory-mapped) buffer;
 * presets are flattened into {@link Region}s with fully combined generators.
 */
public final class SoundFont {

    // generator ids (SF2.01 section 8.1.2)
    static final int START_OFS = 0, END_OFS = 1, STARTLOOP_OFS = 2, ENDLOOP_OFS = 3, START_COARSE = 4;
    static final int FILTER_FC = 8, FILTER_Q = 9, END_COARSE = 12, PAN = 17;
    static final int DELAY_VOL = 33, ATTACK_VOL = 34, HOLD_VOL = 35, DECAY_VOL = 36, SUSTAIN_VOL = 37,
            RELEASE_VOL = 38, KEY_TO_HOLD = 39, KEY_TO_DECAY = 40;
    static final int INSTRUMENT = 41, KEY_RANGE = 43, VEL_RANGE = 44, STARTLOOP_COARSE = 45, KEYNUM = 46,
            VELOCITY = 47, ATTENUATION = 48, ENDLOOP_COARSE = 50, COARSE_TUNE = 51, FINE_TUNE = 52, SAMPLE_ID = 53,
            SAMPLE_MODES = 54, SCALE_TUNING = 56, EXCLUSIVE = 57, ROOT_KEY = 58;
    static final int NUM_GENS = 61;

    public static final class Sample {
        public final String name;
        final int start, end, loopStart, loopEnd, rate, origPitch, pitchCorrection;

        Sample(String name, int start, int end, int loopStart, int loopEnd, int rate, int origPitch, int corr) {
            this.name = name;
            this.start = start;
            this.end = end;
            this.loopStart = loopStart;
            this.loopEnd = loopEnd;
            this.rate = rate;
            this.origPitch = origPitch;
            this.pitchCorrection = corr;
        }
    }

    /** A playable key/velocity zone with its combined generator values. */
    public static final class Region {
        final int keyLo, keyHi, velLo, velHi;
        final int[] gen;
        final Sample sample;

        Region(int keyLo, int keyHi, int velLo, int velHi, int[] gen, Sample sample) {
            this.keyLo = keyLo;
            this.keyHi = keyHi;
            this.velLo = velLo;
            this.velHi = velHi;
            this.gen = gen;
            this.sample = sample;
        }
    }

    public static final class Preset {
        public final String name;
        public final int bank, program;
        final List<Region> regions = new ArrayList<Region>();

        Preset(String name, int bank, int program) {
            this.name = name;
            this.bank = bank;
            this.program = program;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    public final String name;
    public final List<Preset> presets;
    final ShortBuffer data;

    private SoundFont(String name, List<Preset> presets, ShortBuffer data) {
        this.name = name;
        this.presets = presets;
        this.data = data;
    }

    public Preset find(int bank, int program) {
        for (Preset p : presets) if (p.bank == bank && p.program == program) return p;
        for (Preset p : presets) if (p.program == program) return p;
        return presets.isEmpty() ? null : presets.get(0);
    }

    // ---------------------------------------------------------------- parsing

    private static final class Chunk {
        final String id;
        final int off, size;

        Chunk(String id, int off, int size) {
            this.id = id;
            this.off = off;
            this.size = size;
        }
    }

    private static String fourcc(ByteBuffer b, int off) {
        char[] c = new char[4];
        for (int i = 0; i < 4; i++) c[i] = (char) (b.get(off + i) & 0xFF);
        return new String(c);
    }

    private static String cstr(ByteBuffer b, int off, int max) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < max; i++) {
            int c = b.get(off + i) & 0xFF;
            if (c == 0) break;
            sb.append((char) c);
        }
        return sb.toString().trim();
    }

    private static List<Chunk> children(ByteBuffer b, int off, int end) throws IOException {
        List<Chunk> out = new ArrayList<Chunk>();
        while (off + 8 <= end) {
            String id = fourcc(b, off);
            int size = b.getInt(off + 4);
            if (size < 0 || off + 8 + (long) size > b.capacity()) throw new IOException("corrupt SoundFont chunk " + id);
            out.add(new Chunk(id, off + 8, size));
            off += 8 + size + (size & 1);
        }
        return out;
    }

    private static final class Zone {
        final int[] gen = new int[NUM_GENS];
        final boolean[] set = new boolean[NUM_GENS];
        int keyLo = 0, keyHi = 127, velLo = 0, velHi = 127;

        void put(int op, short amount) {
            if (op >= NUM_GENS) return;
            if (op == KEY_RANGE) {
                keyLo = amount & 0xFF;
                keyHi = (amount >> 8) & 0xFF;
            } else if (op == VEL_RANGE) {
                velLo = amount & 0xFF;
                velHi = (amount >> 8) & 0xFF;
            } else if (op == SAMPLE_ID || op == INSTRUMENT) {
                gen[op] = amount & 0xFFFF;
            } else {
                gen[op] = amount;
            }
            set[op] = true;
        }
    }

    private static List<List<Zone>> readZones(ByteBuffer b, Chunk hdr, int hdrSize, int bagOff, Chunk bag, Chunk gen) {
        int n = hdr.size / hdrSize - 1;
        List<List<Zone>> out = new ArrayList<List<Zone>>();
        for (int i = 0; i < n; i++) {
            int b0 = b.getShort(hdr.off + i * hdrSize + bagOff) & 0xFFFF;
            int b1 = b.getShort(hdr.off + (i + 1) * hdrSize + bagOff) & 0xFFFF;
            List<Zone> zones = new ArrayList<Zone>();
            for (int j = b0; j < b1; j++) {
                int g0 = b.getShort(bag.off + j * 4) & 0xFFFF;
                int g1 = b.getShort(bag.off + (j + 1) * 4) & 0xFFFF;
                Zone z = new Zone();
                for (int g = g0; g < g1; g++) {
                    z.put(b.getShort(gen.off + g * 4) & 0xFFFF, b.getShort(gen.off + g * 4 + 2));
                }
                zones.add(z);
            }
            out.add(zones);
        }
        return out;
    }

    private static int[] defaults() {
        int[] g = new int[NUM_GENS];
        g[FILTER_FC] = 13500;
        g[DELAY_VOL] = g[ATTACK_VOL] = g[HOLD_VOL] = g[DECAY_VOL] = g[RELEASE_VOL] = -12000;
        g[KEYNUM] = g[VELOCITY] = g[ROOT_KEY] = -1;
        g[SCALE_TUNING] = 100;
        return g;
    }

    /** Generators that are not summed with preset-level values. */
    private static boolean instrumentOnly(int op) {
        return op <= 4 || op == 12 || op == 45 || op == 50 || op == KEYNUM || op == VELOCITY || op == SAMPLE_MODES
                || op == EXCLUSIVE || op == ROOT_KEY || op == SAMPLE_ID || op == INSTRUMENT || op == KEY_RANGE
                || op == VEL_RANGE;
    }

    public static SoundFont load(ByteBuffer buf) throws IOException {
        ByteBuffer b = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        if (b.capacity() < 12 || !"RIFF".equals(fourcc(b, 0)) || !"sfbk".equals(fourcc(b, 8))) {
            throw new IOException("Not a SoundFont 2 file");
        }
        Chunk smpl = null, phdr = null, pbag = null, pgen = null, inst = null, ibag = null, igen = null, shdr = null;
        String name = "SoundFont";
        for (Chunk c : children(b, 12, Math.min(b.capacity(), 8 + b.getInt(4)))) {
            if (!"LIST".equals(c.id)) continue;
            String kind = fourcc(b, c.off);
            for (Chunk s : children(b, c.off + 4, c.off + c.size)) {
                if ("INFO".equals(kind) && "INAM".equals(s.id)) name = cstr(b, s.off, s.size);
                else if ("smpl".equals(s.id)) smpl = s;
                else if ("phdr".equals(s.id)) phdr = s;
                else if ("pbag".equals(s.id)) pbag = s;
                else if ("pgen".equals(s.id)) pgen = s;
                else if ("inst".equals(s.id)) inst = s;
                else if ("ibag".equals(s.id)) ibag = s;
                else if ("igen".equals(s.id)) igen = s;
                else if ("shdr".equals(s.id)) shdr = s;
            }
        }
        if (smpl == null || phdr == null || pbag == null || pgen == null || inst == null || ibag == null
                || igen == null || shdr == null) {
            throw new IOException("Incomplete SoundFont (only 16-bit SF2 files are supported)");
        }
        ByteBuffer sd = b.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        sd.position(smpl.off);
        sd.limit(smpl.off + smpl.size);
        ShortBuffer data = sd.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();

        int ns = shdr.size / 46 - 1;
        Sample[] samples = new Sample[Math.max(0, ns)];
        for (int i = 0; i < ns; i++) {
            int o = shdr.off + i * 46;
            samples[i] = new Sample(cstr(b, o, 20), b.getInt(o + 20), b.getInt(o + 24), b.getInt(o + 28),
                    b.getInt(o + 32), b.getInt(o + 36), b.get(o + 40) & 0xFF, b.get(o + 41));
        }
        List<List<Zone>> instZones = readZones(b, inst, 22, 20, ibag, igen);
        List<List<Zone>> presetZones = readZones(b, phdr, 38, 24, pbag, pgen);

        List<Preset> presets = new ArrayList<Preset>();
        for (int p = 0; p < presetZones.size(); p++) {
            int o = phdr.off + p * 38;
            Preset preset = new Preset(cstr(b, o, 20), b.getShort(o + 22) & 0xFFFF, b.getShort(o + 20) & 0xFFFF);
            List<Zone> pz = presetZones.get(p);
            Zone pGlobal = (!pz.isEmpty() && !pz.get(0).set[INSTRUMENT]) ? pz.get(0) : null;
            for (Zone z : pz) {
                if (!z.set[INSTRUMENT] || z.gen[INSTRUMENT] >= instZones.size()) continue;
                List<Zone> iz = instZones.get(z.gen[INSTRUMENT]);
                Zone iGlobal = (!iz.isEmpty() && !iz.get(0).set[SAMPLE_ID]) ? iz.get(0) : null;
                int pkLo = z.set[KEY_RANGE] || pGlobal == null ? z.keyLo : pGlobal.keyLo;
                int pkHi = z.set[KEY_RANGE] || pGlobal == null ? z.keyHi : pGlobal.keyHi;
                int pvLo = z.set[VEL_RANGE] || pGlobal == null ? z.velLo : pGlobal.velLo;
                int pvHi = z.set[VEL_RANGE] || pGlobal == null ? z.velHi : pGlobal.velHi;
                for (Zone y : iz) {
                    if (!y.set[SAMPLE_ID] || y.gen[SAMPLE_ID] >= samples.length) continue;
                    int[] g = defaults();
                    if (iGlobal != null) for (int k = 0; k < NUM_GENS; k++) if (iGlobal.set[k]) g[k] = iGlobal.gen[k];
                    for (int k = 0; k < NUM_GENS; k++) if (y.set[k]) g[k] = y.gen[k];
                    for (int k = 0; k < NUM_GENS; k++) {
                        if (instrumentOnly(k)) continue;
                        if (z.set[k]) g[k] += z.gen[k];
                        else if (pGlobal != null && pGlobal.set[k]) g[k] += pGlobal.gen[k];
                    }
                    int ikLo = y.set[KEY_RANGE] || iGlobal == null ? y.keyLo : iGlobal.keyLo;
                    int ikHi = y.set[KEY_RANGE] || iGlobal == null ? y.keyHi : iGlobal.keyHi;
                    int ivLo = y.set[VEL_RANGE] || iGlobal == null ? y.velLo : iGlobal.velLo;
                    int ivHi = y.set[VEL_RANGE] || iGlobal == null ? y.velHi : iGlobal.velHi;
                    int kLo = Math.max(pkLo, ikLo), kHi = Math.min(pkHi, ikHi);
                    int vLo = Math.max(pvLo, ivLo), vHi = Math.min(pvHi, ivHi);
                    if (kLo > kHi || vLo > vHi) continue;
                    preset.regions.add(new Region(kLo, kHi, vLo, vHi, g, samples[y.gen[SAMPLE_ID]]));
                }
            }
            if (!preset.regions.isEmpty()) presets.add(preset);
        }
        Collections.sort(presets, new Comparator<Preset>() {
            @Override
            public int compare(Preset a, Preset c) {
                return a.bank != c.bank ? Integer.compare(a.bank, c.bank) : Integer.compare(a.program, c.program);
            }
        });
        if (presets.isEmpty()) throw new IOException("SoundFont contains no playable presets");
        return new SoundFont(name, presets, data);
    }
}
