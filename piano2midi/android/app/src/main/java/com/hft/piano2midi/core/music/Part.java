package com.hft.piano2midi.core.music;

/** Voice roles a piano transcription is split into. */
public final class Part {
    public static final int MELODY = 0;
    public static final int CHORDS = 1;
    public static final int BASS = 2;
    public static final int COUNT = 3;

    public static final String[] NAMES = {"Melody", "Chords", "Bass"};
    /** ARGB colours used in the piano roll / keyboard. */
    public static final int[] COLORS = {0xFFFF7043, 0xFF66BB6A, 0xFF42A5F5};

    private Part() {}

    public static int mask(int part) {
        return 1 << part;
    }

    public static final int ALL = 7;
}
