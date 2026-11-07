package com.hft.piano2midi.core.engine;

import com.hft.piano2midi.core.Note;

import java.util.List;

/** Result of a transcription engine that can be (re-)decoded into notes. */
public interface EngineOutput {
    /**
     * @param p1   first decoding parameter (engine specific, e.g. onset threshold)
     * @param p2   second parameter (e.g. offset or frame threshold)
     * @param p3   third parameter (e.g. frame threshold or minimum note length)
     * @param mode engine-specific mode (e.g. hFT note-end mode)
     */
    List<Note> decode(double p1, double p2, double p3, int mode);

    /** Seconds of audio covered. */
    double seconds();
}
