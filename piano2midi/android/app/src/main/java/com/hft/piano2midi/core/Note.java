package com.hft.piano2midi.core;

/** A transcribed note. Times are in seconds. */
public final class Note {
    public final int pitch;
    public final double onset;
    public double offset;
    public final int velocity;
    /** {@link com.hft.piano2midi.core.music.Part} ordinal assigned by PartSplitter. */
    public int part;

    public Note(int pitch, double onset, double offset, int velocity) {
        this.pitch = pitch;
        this.onset = onset;
        this.offset = offset;
        this.velocity = velocity;
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.US, "%.4f\t%.4f\t%d\t%d", onset, offset, pitch, velocity);
    }
}
