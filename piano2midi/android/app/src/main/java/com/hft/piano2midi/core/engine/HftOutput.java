package com.hft.piano2midi.core.engine;

import com.hft.piano2midi.core.Note;
import com.hft.piano2midi.core.NoteDecoder;
import com.hft.piano2midi.core.Transcriber;

import java.util.List;

/** hFT-Transformer frame outputs; p1/p2/p3 = onset/offset/frame thresholds, mode = note-end mode. */
public final class HftOutput implements EngineOutput {
    public final Transcriber.Result result;
    private final double seconds;

    public HftOutput(Transcriber.Result result, double seconds) {
        this.result = result;
        this.seconds = seconds;
    }

    @Override
    public List<Note> decode(double p1, double p2, double p3, int mode) {
        NoteDecoder.OffsetMode m = mode == 1 ? NoteDecoder.OffsetMode.LONGER
                : mode == 2 ? NoteDecoder.OffsetMode.OFFSET : NoteDecoder.OffsetMode.SHORTER;
        return NoteDecoder.decode(result, Math.max(0.01, p1), Math.max(0.01, p2), Math.max(0.01, p3), m, true);
    }

    @Override
    public double seconds() {
        return seconds;
    }
}
