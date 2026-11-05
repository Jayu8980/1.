package com.hft.piano2midi.core.synth;

/**
 * Renders a {@link Sequence} through the {@link Synth} on a musical clock that can run at any
 * speed (tempo change), with an optional metronome locked to the beat grid (accented first beat)
 * and an optional count-in before the music starts.
 *
 * Musical time is the transcription's own time line in seconds; at speed 0.5 one second of
 * music takes two seconds of audio. Not thread-safe: use from the audio thread only.
 */
public final class PlaybackEngine {

    private final Synth synth;
    private final int rate;

    private Sequence sequence;
    private double music;          // musical time at the start of the next block
    private double speed = 1.0;
    private double musicEnd;       // stop the sequence clock here (end of music)

    // beat grid
    private boolean metronome;
    private double origin, beatSec = 0.5;
    private int beatsPerBar = 4;
    private float clickGain = 0.45f;

    // loop / practice
    private boolean loop;
    private double loopA, loopB;
    private boolean practice, countInEachLoop;
    private double practiceStart = 0.6, practiceStep = 0.05, practiceTarget = 1.0;
    private int loops;
    private float[] tl = new float[1024], tr = new float[1024];

    // click voice
    private int clickPos = -1, clickLen;
    private double clickPhase, clickFreq;
    private float clickAmp;

    public PlaybackEngine(Synth synth, int rate) {
        this.synth = synth;
        this.rate = rate;
    }

    public Synth synth() {
        return synth;
    }

    public void setGrid(double origin, double beatSec, int beatsPerBar) {
        this.origin = origin;
        this.beatSec = Math.max(0.05, beatSec);
        this.beatsPerBar = Math.max(1, beatsPerBar);
    }

    public void setMetronome(boolean on) {
        metronome = on;
    }

    public void setClickGain(float g) {
        clickGain = g;
    }

    /** Repeat [a, b) (musical seconds) while playing. */
    public void setLoop(boolean on, double a, double b) {
        loop = on && b > a + 0.05;
        // transcribed onsets jitter by a few ms around bar lines: shift both edges slightly
        // earlier so a downbeat chord belongs to the bar it starts, not the previous one
        double guard = Math.min(0.05, beatSec / 4);
        loopA = a - guard;
        loopB = b - guard;
        if (loop && sequence != null && (music >= loopB || music < loopA - barSec() - 1e-6)) wrap();
    }

    /**
     * Practice mode: start each play at `start` speed and add `step` after every repeat until
     * `target`; optionally count in one bar before each repeat.
     */
    public void setPractice(boolean on, double start, double step, double target, boolean countInEach) {
        boolean was = practice;
        practice = on;
        practiceStart = start;
        practiceStep = step;
        practiceTarget = target;
        countInEachLoop = countInEach;
        if (on && !was && sequence != null) speed = Math.min(speed, practiceStart);
        if (!on && was) speed = practiceTarget;
    }

    /** Completed repeats since play started. */
    public int loops() {
        return loops;
    }

    private double barSec() {
        return beatSec * beatsPerBar;
    }

    private void wrap() {
        loops++;
        synth.allNotesOff();
        if (sequence != null) sequence.seek(loopA);
        music = countInEachLoop ? loopA - barSec() : loopA;
        if (practice) speed = Math.min(practiceTarget, speed + practiceStep);
    }

    public void setSpeed(double s) {
        speed = Math.max(0.1, Math.min(4.0, s));
    }

    public double speed() {
        return speed;
    }

    /**
     * Starts the sequence at musical time `from`. With countInBars > 0 the clock starts that many
     * bars earlier (aligned to the beat grid) so the metronome counts in before the first note.
     */
    public void start(Sequence seq, double from, int countInBars) {
        synth.allNotesOff();
        sequence = seq;
        loops = 0;
        if (loop && (from < loopA || from >= loopB)) from = loopA;
        if (practice) speed = Math.min(practiceStart, practiceTarget);
        seq.seek(from);
        musicEnd = seq.duration;
        double start = from;
        if (countInBars > 0) {
            // first beat at or before `from`, then back the requested number of bars
            double k = Math.floor((from - origin) / beatSec + 1e-6);
            start = origin + k * beatSec - countInBars * beatsPerBar * beatSec;
        }
        music = start;
    }

    public void stop() {
        sequence = null;
        synth.allNotesOff();
    }

    public boolean isPlaying() {
        return sequence != null;
    }

    /** Musical time at the start of the next rendered block. */
    public double musicTime() {
        return music;
    }

    /** True once all notes were sent and have died away. */
    public boolean finished() {
        return !loop && sequence != null && sequence.finished() && music > musicEnd && synth.activeVoices() == 0;
    }

    /** Renders one block (wrapping at the loop end sample-accurately); returns the musical time at its start. */
    public double render(float[] left, float[] right, int frames) {
        double m0 = music;
        if (tl.length < frames) {
            tl = new float[frames];
            tr = new float[frames];
        }
        int done = 0;
        while (done < frames) {
            int n = frames - done;
            if (sequence != null && loop) {
                if (music >= loopB - 1e-9) {
                    wrap();
                    continue;
                }
                int toB = (int) Math.ceil((loopB - music) / speed * rate);
                n = Math.max(1, Math.min(n, toB));
            }
            renderChunk(left, right, done, n);
            done += n;
        }
        return m0;
    }

    private void renderChunk(float[] left, float[] right, int off, int n) {
        double m0 = music;
        double dm = n / (double) rate * speed;
        Sequence seq = sequence;
        if (seq != null) seq.dispatchUntil(synth, m0 + dm);
        synth.render(tl, tr, n);
        if (seq != null) {
            if (metronome) scheduleClicks(m0, m0 + dm);
            music = m0 + dm;
        }
        mixClick(tl, tr, n, m0);
        System.arraycopy(tl, 0, left, off, n);
        System.arraycopy(tr, 0, right, off, n);
    }

    private int pendingClickFrame = -1;
    private boolean pendingAccent;

    private void scheduleClicks(double m0, double m1) {
        long k = (long) Math.ceil((m0 - origin) / beatSec - 1e-9);
        double t = origin + k * beatSec;
        if (t < m1) {
            pendingClickFrame = (int) Math.floor((t - m0) / speed * rate);
            pendingAccent = Math.floorMod(k, (long) beatsPerBar) == 0;
        }
    }

    private void mixClick(float[] left, float[] right, int frames, double m0) {
        for (int i = 0; i < frames; i++) {
            if (i == pendingClickFrame) {
                clickPos = 0;
                clickLen = rate * 45 / 1000;
                clickFreq = pendingAccent ? 1760 : 1175;
                clickAmp = pendingAccent ? clickGain : clickGain * 0.7f;
                clickPhase = 0;
                pendingClickFrame = -1;
            }
            if (clickPos >= 0) {
                float env = (float) Math.exp(-clickPos / (rate * 0.008));
                float attack = Math.min(1f, clickPos / (rate * 0.0008f));
                float v = (float) Math.sin(clickPhase) * env * attack * clickAmp;
                clickPhase += 2 * Math.PI * clickFreq / rate;
                left[i] = Math.max(-1f, Math.min(1f, left[i] + v));
                right[i] = Math.max(-1f, Math.min(1f, right[i] + v));
                if (++clickPos >= clickLen) clickPos = -1;
            }
        }
        if (pendingClickFrame >= frames) pendingClickFrame -= frames;
        else if (pendingClickFrame >= 0) pendingClickFrame = -1;
    }
}
