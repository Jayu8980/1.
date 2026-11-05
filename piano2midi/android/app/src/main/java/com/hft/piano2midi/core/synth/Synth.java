package com.hft.piano2midi.core.synth;

import java.nio.ShortBuffer;

/**
 * Small polyphonic SoundFont sampler: linear interpolation, SF2 volume envelope
 * (delay/attack/hold/decay/sustain/release, key-scaled hold/decay), loops, pan,
 * tuning, initial attenuation, velocity curve and a static resonant low-pass
 * filter (including the default velocity-to-cutoff modulator).
 *
 * Not thread-safe: call noteOn/noteOff/render from one thread (the audio thread).
 */
public final class Synth {

    public static final int CHANNELS = 16;
    private static final int MAX_VOICES = 96;

    private final int outRate;
    private final Voice[] voices = new Voice[MAX_VOICES];
    private final SoundFont.Preset[] channelPreset = new SoundFont.Preset[CHANNELS];
    private final float[] channelGain = new float[CHANNELS];
    private SoundFont font;
    private long clock;
    private float masterGain = 0.5f;

    public Synth(SoundFont font, int outRate) {
        this.outRate = outRate;
        for (int i = 0; i < MAX_VOICES; i++) voices[i] = new Voice();
        for (int c = 0; c < CHANNELS; c++) channelGain[c] = 1f;
        setFont(font);
    }

    public void setFont(SoundFont font) {
        allSoundOff();
        this.font = font;
        for (int c = 0; c < CHANNELS; c++) channelPreset[c] = font.find(0, 0);
    }

    public SoundFont font() {
        return font;
    }

    public void setPreset(int channel, SoundFont.Preset preset) {
        channelPreset[channel] = preset;
    }

    public void setChannelGain(int channel, float gain) {
        channelGain[channel] = gain;
    }

    public void setMasterGain(float g) {
        masterGain = g;
    }

    public void allSoundOff() {
        for (Voice v : voices) v.active = false;
    }

    public void allNotesOff() {
        for (Voice v : voices) if (v.active) v.release();
    }

    public int activeVoices() {
        int n = 0;
        for (Voice v : voices) if (v.active) n++;
        return n;
    }

    public void noteOff(int channel, int key) {
        for (Voice v : voices) {
            if (v.active && !v.released && v.channel == channel && v.key == key) v.release();
        }
    }

    public void noteOn(int channel, int key, int vel) {
        if (vel <= 0) {
            noteOff(channel, key);
            return;
        }
        SoundFont.Preset p = channelPreset[channel];
        if (p == null) return;
        noteOff(channel, key); // re-strike
        for (SoundFont.Region r : p.regions) {
            if (key < r.keyLo || key > r.keyHi || vel < r.velLo || vel > r.velHi) continue;
            Voice v = allocate();
            v.start(r, channel, key, vel, clock++);
        }
    }

    private Voice allocate() {
        Voice best = null;
        for (Voice v : voices) if (!v.active) return v;
        // steal: quietest released voice, else the oldest
        for (Voice v : voices) {
            if (best == null) best = v;
            else if (v.released && (!best.released || v.envGain < best.envGain)) best = v;
            else if (v.released == best.released && v.age < best.age) best = v;
        }
        return best;
    }

    /** Adds `frames` stereo samples to left/right (which are cleared first). */
    public void render(float[] left, float[] right, int frames) {
        for (int i = 0; i < frames; i++) {
            left[i] = 0f;
            right[i] = 0f;
        }
        ShortBuffer data = font.data;
        for (Voice v : voices) {
            if (v.active) v.render(data, left, right, frames, masterGain * channelGain[v.channel]);
        }
        for (int i = 0; i < frames; i++) {
            left[i] = softClip(left[i]);
            right[i] = softClip(right[i]);
        }
    }

    /** Linear up to 0.7, then a tanh knee that never exceeds 1. */
    private static float softClip(float x) {
        float ax = Math.abs(x);
        if (ax <= 0.7f) return x;
        float y = 0.7f + 0.3f * (float) Math.tanh((ax - 0.7f) / 0.3f);
        return x < 0 ? -y : y;
    }

    private static double tc2sec(int tc) {
        return tc <= -12000 ? 0.0 : Math.pow(2.0, tc / 1200.0);
    }

    private final class Voice {
        boolean active, released;
        int channel, key;
        long age;
        // sample playback
        double pos, step;
        int end, loopStart, loopEnd, loopMode;
        float gainL, gainR;
        // envelope (stage: 0 delay, 1 attack, 2 hold, 3 decay, 4 sustain, 5 release)
        int stage;
        double stageLeft; // samples
        double attackSamples, holdSamples, decayRate, releaseRate, sustainCb;
        double cb; // current attenuation in centibels during decay/sustain/release
        double attackLevel;
        float envGain;
        // filter
        boolean filter;
        float b0, b1, b2, a1, a2, z1, z2;

        void start(SoundFont.Region r, int channel, int key, int vel, long age) {
            int[] g = r.gen;
            SoundFont.Sample s = r.sample;
            this.channel = channel;
            this.key = key;
            this.age = age;
            active = true;
            released = false;
            int start = s.start + g[SoundFont.START_OFS] + 32768 * g[SoundFont.START_COARSE];
            end = s.end + g[SoundFont.END_OFS] + 32768 * g[SoundFont.END_COARSE];
            loopStart = s.loopStart + g[SoundFont.STARTLOOP_OFS] + 32768 * g[SoundFont.STARTLOOP_COARSE];
            loopEnd = s.loopEnd + g[SoundFont.ENDLOOP_OFS] + 32768 * g[SoundFont.ENDLOOP_COARSE];
            int cap = font.data.capacity() - 1;
            end = Math.max(0, Math.min(end, cap));
            start = Math.max(0, Math.min(start, end));
            loopMode = g[SoundFont.SAMPLE_MODES] & 3;
            if (loopMode == 2 || loopEnd <= loopStart + 1 || loopStart < start || loopEnd > end) loopMode = 0;
            pos = start;

            int pitchKey = g[SoundFont.KEYNUM] >= 0 ? g[SoundFont.KEYNUM] : key;
            int velocity = g[SoundFont.VELOCITY] >= 0 ? g[SoundFont.VELOCITY] : vel;
            int root = g[SoundFont.ROOT_KEY] >= 0 ? g[SoundFont.ROOT_KEY] : s.origPitch;
            double cents = (pitchKey - root) * g[SoundFont.SCALE_TUNING] + g[SoundFont.COARSE_TUNE] * 100.0
                    + g[SoundFont.FINE_TUNE] + s.pitchCorrection;
            step = Math.pow(2.0, cents / 1200.0) * s.rate / outRate;

            // amplitude: initial attenuation (EMU-style 0.4 scaling) and the default
            // velocity->attenuation modulator (negative concave, 960 cB) = (vel/127)^2
            double atten = 0.4 * g[SoundFont.ATTENUATION];
            double amp = Math.pow(10.0, -atten / 200.0) * (velocity / 127.0) * (velocity / 127.0);
            double pan = Math.max(-500, Math.min(500, g[SoundFont.PAN])) / 1000.0 + 0.5;
            gainL = (float) (amp * Math.cos(pan * Math.PI / 2));
            gainR = (float) (amp * Math.sin(pan * Math.PI / 2));

            // envelope
            double delay = tc2sec(g[SoundFont.DELAY_VOL]) * outRate;
            attackSamples = Math.max(1, tc2sec(g[SoundFont.ATTACK_VOL]) * outRate);
            holdSamples = tc2sec(g[SoundFont.HOLD_VOL] + (60 - key) * g[SoundFont.KEY_TO_HOLD]) * outRate;
            double decaySec = tc2sec(g[SoundFont.DECAY_VOL] + (60 - key) * g[SoundFont.KEY_TO_DECAY]);
            decayRate = 1000.0 / Math.max(1.0, decaySec * outRate); // cB per sample over full 100 dB range
            double relSec = Math.max(0.006, tc2sec(g[SoundFont.RELEASE_VOL]));
            releaseRate = 1000.0 / (relSec * outRate);
            sustainCb = Math.max(0, Math.min(1000, g[SoundFont.SUSTAIN_VOL]));
            cb = 0;
            attackLevel = 0;
            stage = 0;
            stageLeft = delay;
            envGain = 0;

            // static low-pass (initialFilterFc + default velocity->Fc modulator)
            double fcCents = g[SoundFont.FILTER_FC] - 2400.0 * (1.0 - velocity / 127.0);
            double fc = 8.176 * Math.pow(2.0, fcCents / 1200.0);
            filter = fc < 0.45 * outRate && fcCents < 13500;
            z1 = z2 = 0;
            if (filter) {
                double qDb = Math.max(0, g[SoundFont.FILTER_Q]) / 10.0;
                double q = Math.max(0.5, Math.pow(10.0, qDb / 20.0) / Math.sqrt(2));
                double w = 2 * Math.PI * Math.max(20, fc) / outRate;
                double alpha = Math.sin(w) / (2 * q);
                double cw = Math.cos(w);
                double a0 = 1 + alpha;
                // compensate resonance gain like FluidSynth (divide by sqrt(q))
                double comp = 1.0 / Math.sqrt(Math.max(1.0, q));
                b0 = (float) ((1 - cw) / 2 / a0 * comp);
                b1 = (float) ((1 - cw) / a0 * comp);
                b2 = b0;
                a1 = (float) (-2 * cw / a0);
                a2 = (float) ((1 - alpha) / a0);
            }
        }

        void release() {
            if (released) return;
            released = true;
            if (stage < 3) {
                // convert current linear attack level to attenuation
                double lvl = stage == 1 ? attackLevel : (stage == 0 ? 0.0 : 1.0);
                cb = lvl <= 1e-5 ? 1000 : -200.0 * Math.log10(lvl);
            }
            stage = 5;
            if (loopMode == 3) loopMode = 0; // play to the end of the sample
        }

        private float nextEnv() {
            switch (stage) {
                case 0:
                    if (--stageLeft > 0) return 0f;
                    stage = 1;
                    stageLeft = attackSamples;
                    // fall through
                case 1:
                    attackLevel += 1.0 / attackSamples;
                    if (attackLevel >= 1.0) {
                        attackLevel = 1.0;
                        stage = 2;
                        stageLeft = holdSamples;
                    }
                    return (float) attackLevel;
                case 2:
                    if (--stageLeft > 0) return 1f;
                    stage = 3;
                    // fall through
                case 3:
                    cb += decayRate;
                    if (cb >= sustainCb) {
                        cb = sustainCb;
                        stage = 4;
                    }
                    break;
                case 4:
                    break;
                default:
                    cb += releaseRate;
                    if (cb >= 960) {
                        active = false;
                        return 0f;
                    }
            }
            return cb >= 960 ? 0f : (float) Math.pow(10.0, -cb / 200.0);
        }

        void render(ShortBuffer data, float[] left, float[] right, int frames, float gain) {
            float gl = gainL * gain, gr = gainR * gain;
            for (int i = 0; i < frames; i++) {
                // envelope is evaluated every 16 samples for speed
                if ((i & 15) == 0) {
                    float e = 0;
                    for (int k = 0; k < 16 && active; k++) e = nextEnv();
                    envGain = e;
                    if (!active) return;
                    if (stage == 4 && sustainCb >= 960) {
                        active = false;
                        return;
                    }
                }
                int ip = (int) pos;
                if (ip + 1 >= end) {
                    active = false;
                    return;
                }
                float frac = (float) (pos - ip);
                float a = data.get(ip), b = data.get(ip + 1);
                if (loopMode != 0 && ip + 1 >= loopEnd) b = data.get(loopStart);
                float x = (a + (b - a) * frac) * (1f / 32768f);
                if (filter) {
                    float y = b0 * x + z1;
                    z1 = b1 * x - a1 * y + z2;
                    z2 = b2 * x - a2 * y;
                    x = y;
                }
                x *= envGain;
                left[i] += x * gl;
                right[i] += x * gr;
                pos += step;
                if (loopMode != 0 && pos >= loopEnd) pos -= (loopEnd - loopStart);
            }
        }
    }
}
