package com.hft.piano2midi.core.music;

import com.hft.piano2midi.core.Note;

import java.util.List;

/** Musical key (Krumhansl-Kessler estimate) and note spelling. */
public final class Key {

    private static final double[] MAJOR = {6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88};
    private static final double[] MINOR = {6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17};
    private static final String[] SHARP_NAMES = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
    private static final String[] FLAT_NAMES = {"C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B"};
    // letter index (C=0..B=6) and alteration for sharp / flat spellings of each pitch class
    private static final int[] SHARP_LETTER = {0, 0, 1, 1, 2, 3, 3, 4, 4, 5, 5, 6};
    private static final int[] SHARP_ALTER = {0, 1, 0, 1, 0, 0, 1, 0, 1, 0, 1, 0};
    private static final int[] FLAT_LETTER = {0, 1, 1, 2, 2, 3, 4, 4, 5, 5, 6, 6};
    private static final int[] FLAT_ALTER = {0, -1, 0, -1, 0, 0, -1, 0, -1, 0, -1, 0};
    private static final int[] LETTER_PC = {0, 2, 4, 5, 7, 9, 11};
    /** Order in which sharps / flats appear in a key signature (letter indices). */
    public static final int[] SHARP_ORDER = {3, 0, 4, 1, 5, 2, 6};
    public static final int[] FLAT_ORDER = {6, 2, 5, 1, 4, 0, 3};

    public final int tonic; // pitch class
    public final boolean minor;
    /** Key signature: number of sharps (>0) or flats (<0). */
    public final int fifths;

    public Key(int tonic, boolean minor) {
        this.tonic = ((tonic % 12) + 12) % 12;
        this.minor = minor;
        int majorPc = minor ? (this.tonic + 3) % 12 : this.tonic;
        int f = (majorPc * 7) % 12; // C=0 G=1 D=2 ... F=11
        this.fifths = f > 6 ? f - 12 : f;
    }

    public static Key estimate(List<Note> notes) {
        double[] h = new double[12];
        for (Note n : notes) h[n.pitch % 12] += Math.min(2.0, Math.max(0.05, n.offset - n.onset));
        double best = -2;
        Key key = new Key(0, false);
        for (int t = 0; t < 12; t++) {
            double cMaj = corr(h, MAJOR, t), cMin = corr(h, MINOR, t);
            if (cMaj > best) {
                best = cMaj;
                key = new Key(t, false);
            }
            if (cMin > best) {
                best = cMin;
                key = new Key(t, true);
            }
        }
        return key;
    }

    private static double corr(double[] h, double[] prof, int t) {
        double mh = 0, mp = 0;
        for (int i = 0; i < 12; i++) {
            mh += h[i];
            mp += prof[i];
        }
        mh /= 12;
        mp /= 12;
        double num = 0, dh = 0, dp = 0;
        for (int i = 0; i < 12; i++) {
            double a = h[(i + t) % 12] - mh, b = prof[i] - mp;
            num += a * b;
            dh += a * a;
            dp += b * b;
        }
        return dh == 0 ? 0 : num / Math.sqrt(dh * dp);
    }

    public boolean useFlats() {
        return fifths < 0;
    }

    public String pcName(int pc) {
        pc = ((pc % 12) + 12) % 12;
        return useFlats() ? FLAT_NAMES[pc] : SHARP_NAMES[pc];
    }

    public String name() {
        return pcName(tonic) + (minor ? " minor" : " major");
    }

    /** Alteration the key signature applies to a letter (C=0..B=6). */
    public int signatureAlter(int letter) {
        if (fifths > 0) {
            for (int i = 0; i < fifths; i++) if (SHARP_ORDER[i] == letter) return 1;
        } else {
            for (int i = 0; i < -fifths; i++) if (FLAT_ORDER[i] == letter) return -1;
        }
        return 0;
    }

    /** Spelled pitch: diatonic step (octave*7 + letter, C4 = 28) and alteration. */
    public static final class Spelled {
        public final int step, alter;

        Spelled(int step, int alter) {
            this.step = step;
            this.alter = alter;
        }
    }

    public Spelled spell(int pitch) {
        int pc = pitch % 12;
        int letter, alter;
        // diatonic notes of the key first
        letter = -1;
        alter = 0;
        for (int l = 0; l < 7; l++) {
            int a = signatureAlter(l);
            if (((LETTER_PC[l] + a) % 12 + 12) % 12 == pc) {
                letter = l;
                alter = a;
                break;
            }
        }
        if (letter < 0) {
            if (minor && pc == (tonic + 11) % 12) {
                // raised leading tone of the minor key: one letter below the tonic
                int tl = useFlats() ? FLAT_LETTER[tonic] : SHARP_LETTER[tonic];
                letter = (tl + 6) % 7;
                alter = pc - LETTER_PC[letter];
                if (alter > 6) alter -= 12;
                if (alter < -6) alter += 12;
            } else if (useFlats()) {
                letter = FLAT_LETTER[pc];
                alter = FLAT_ALTER[pc];
            } else {
                letter = SHARP_LETTER[pc];
                alter = SHARP_ALTER[pc];
            }
        }
        int octave = Math.floorDiv(pitch - alter, 12) - 1;
        return new Spelled(octave * 7 + letter, alter);
    }
}
