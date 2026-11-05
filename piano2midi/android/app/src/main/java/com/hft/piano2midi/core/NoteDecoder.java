package com.hft.piano2midi.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Converts frame-level onset/offset/frame(mpe)/velocity outputs into notes.
 * Faithful port of AMT.mpe2note() from the hFT-Transformer reference code.
 */
public final class NoteDecoder {

    public enum OffsetMode { SHORTER, LONGER, OFFSET }

    public static final double HOP_SEC = FeatureExtractor.HOP / (double) FeatureExtractor.SAMPLE_RATE;

    private NoteDecoder() {}

    private static final class Peak {
        final int loc;
        final double time;

        Peak(int loc, double time) {
            this.loc = loc;
            this.time = time;
        }
    }

    /** Local maxima above threshold with sub-frame (parabolic-ish) time refinement. */
    private static List<Peak> peaks(float[][] a, int j, double thr) {
        List<Peak> out = new ArrayList<Peak>();
        int n = a.length;
        for (int i = 0; i < n; i++) {
            float v = a[i][j];
            if (v < thr) continue;
            boolean left = true;
            for (int ii = i - 1; ii >= 0; ii--) {
                if (v > a[ii][j]) { left = true; break; }
                if (v < a[ii][j]) { left = false; break; }
            }
            boolean right = true;
            for (int ii = i + 1; ii < n; ii++) {
                if (v > a[ii][j]) { right = true; break; }
                if (v < a[ii][j]) { right = false; break; }
            }
            if (!(left && right)) continue;
            double t;
            if (i == 0 || i == n - 1) {
                t = i * HOP_SEC;
            } else {
                double p = a[i - 1][j], q = a[i + 1][j];
                if (p == q) {
                    t = i * HOP_SEC;
                } else if (p > q) {
                    t = i * HOP_SEC - (HOP_SEC * 0.5 * (p - q) / (v - q));
                } else {
                    t = i * HOP_SEC + (HOP_SEC * 0.5 * (q - p) / (v - p));
                }
            }
            out.add(new Peak(i, t));
        }
        return out;
    }

    public static List<Note> decode(Transcriber.Result r, double thrOnset, double thrOffset, double thrMpe,
                                    OffsetMode mode, boolean ignoreZeroVelocity) {
        List<Note> notes = new ArrayList<Note>();
        float[][] aMpe = r.mpe;
        int len = aMpe.length;
        for (int j = 0; j < Transcriber.NUM_NOTE; j++) {
            List<Peak> ons = peaks(r.onset, j, thrOnset);
            List<Peak> offs = peaks(r.offset, j, thrOffset);
            double timeOffset = 0.0, timeMpe = 0.0, timeNext;
            int offPtr = 0;
            for (int idx = 0; idx < ons.size(); idx++) {
                int locOnset = ons.get(idx).loc;
                double timeOnset = ons.get(idx).time;
                int locNext;
                if (idx + 1 < ons.size()) {
                    locNext = ons.get(idx + 1).loc;
                    timeNext = ons.get(idx + 1).time;
                } else {
                    locNext = len;
                    timeNext = (locNext - 1) * HOP_SEC;
                }

                // first offset peak after the onset (onsets are increasing, so the pointer is monotonic)
                int locOffset = locOnset + 1;
                boolean flagOffset = false;
                while (offPtr < offs.size() && offs.get(offPtr).loc <= locOnset) offPtr++;
                if (offPtr < offs.size()) {
                    locOffset = offs.get(offPtr).loc;
                    timeOffset = offs.get(offPtr).time;
                    flagOffset = true;
                }
                if (locOffset > locNext) {
                    locOffset = locNext;
                    timeOffset = timeNext;
                }

                // offset from frame activity (1 frame longer, as in the reference)
                int locMpe = locOnset + 1;
                boolean flagMpe = false;
                for (int ii = locOnset + 1; ii < locNext; ii++) {
                    if (aMpe[ii][j] < thrMpe) {
                        locMpe = ii;
                        flagMpe = true;
                        timeMpe = locMpe * HOP_SEC;
                        break;
                    }
                }

                int pitch = j + Transcriber.NOTE_MIN;
                int velocity = r.velocity[locOnset][j];
                double offsetValue;
                if (!flagOffset && !flagMpe) {
                    offsetValue = timeNext;
                } else if (flagOffset && !flagMpe) {
                    offsetValue = timeOffset;
                } else if (!flagOffset) {
                    offsetValue = timeMpe;
                } else if (mode == OffsetMode.OFFSET) {
                    offsetValue = timeOffset;
                } else if (mode == OffsetMode.LONGER) {
                    offsetValue = (locOffset >= locMpe) ? timeOffset : timeMpe;
                } else {
                    offsetValue = (locOffset <= locMpe) ? timeOffset : timeMpe;
                }

                if (!ignoreZeroVelocity || velocity > 0) {
                    notes.add(new Note(pitch, timeOnset, offsetValue, velocity));
                }
                int n = notes.size();
                if (n > 1) {
                    Note last = notes.get(n - 1), prev = notes.get(n - 2);
                    if (last.pitch == prev.pitch && last.onset < prev.offset) {
                        prev.offset = last.onset;
                    }
                }
            }
        }
        Collections.sort(notes, new Comparator<Note>() {
            @Override
            public int compare(Note a, Note b) {
                int c = Double.compare(a.onset, b.onset);
                return c != 0 ? c : Integer.compare(a.pitch, b.pitch);
            }
        });
        return notes;
    }
}
