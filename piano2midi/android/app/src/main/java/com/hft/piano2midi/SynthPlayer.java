package com.hft.piano2midi;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import com.hft.piano2midi.core.Note;
import com.hft.piano2midi.core.synth.PlaybackEngine;
import com.hft.piano2midi.core.synth.Sequence;
import com.hft.piano2midi.core.synth.SoundFont;
import com.hft.piano2midi.core.synth.Synth;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Streams the {@link PlaybackEngine} (SoundFont synth + metronome, variable speed) to an
 * AudioTrack and reports the audible musical position for the keyboard / sheet animation.
 * Live notes from the on-screen keyboard go through the same synth. All engine access
 * happens on the audio thread via a command queue.
 */
final class SynthPlayer {

    static final int LIVE_CHANNEL = 3;
    private static final int BLOCK = 256;
    private static final int ANCHORS = 512;

    private final int rate;
    private final Synth synth;
    private final PlaybackEngine engine;
    private final ConcurrentLinkedQueue<Runnable> commands = new ConcurrentLinkedQueue<Runnable>();
    private Thread thread;
    private volatile boolean running;

    private volatile boolean playing;
    private volatile double idlePosition;
    private volatile AudioTrack track;
    private volatile Runnable onFinished;

    // (frame written, musical time, speed) for recent blocks: maps the playback head to music time
    private final long[] anchorFrame = new long[ANCHORS];
    private final double[] anchorMusic = new double[ANCHORS];
    private final double[] anchorSpeed = new double[ANCHORS];
    private int anchorCount;
    private long firstSeqFrame = -1;

    SynthPlayer(SoundFont font) {
        int r = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC);
        rate = r >= 22050 && r <= 96000 ? r : 44100;
        synth = new Synth(font, rate);
        engine = new PlaybackEngine(synth, rate);
    }

    void setFont(final SoundFont font, final SoundFont.Preset[] presets) {
        post(new Runnable() {
            @Override
            public void run() {
                synth.setFont(font);
                for (int c = 0; c < presets.length; c++) synth.setPreset(c, presets[c]);
            }
        });
    }

    void setPreset(final int channel, final SoundFont.Preset p) {
        post(new Runnable() {
            @Override
            public void run() {
                synth.setPreset(channel, p);
            }
        });
    }

    void setOnFinished(Runnable r) {
        onFinished = r;
    }

    /** Beat grid for the metronome. */
    void setGrid(final double origin, final double beatSec, final int beatsPerBar) {
        post(new Runnable() {
            @Override
            public void run() {
                engine.setGrid(origin, beatSec, beatsPerBar);
            }
        });
    }

    void setMetronome(final boolean on) {
        post(new Runnable() {
            @Override
            public void run() {
                engine.setMetronome(on);
            }
        });
    }

    void setClickVolume(final float v) {
        post(new Runnable() {
            @Override
            public void run() {
                engine.setClickGain(v);
            }
        });
    }

    /** Playback speed (1.0 = original tempo); applied immediately, also while playing. */
    void setSpeed(final double s) {
        post(new Runnable() {
            @Override
            public void run() {
                engine.setSpeed(s);
            }
        });
    }

    /** Starts playing `notes` (parts in partMask) from `from` seconds, optionally after a count-in. */
    void play(List<Note> notes, int partMask, final double from, final int countInBars) {
        final Sequence seq = new Sequence(notes, partMask);
        playing = true;
        idlePosition = from;
        post(new Runnable() {
            @Override
            public void run() {
                engine.start(seq, from, countInBars);
                synchronized (anchorFrame) {
                    anchorCount = 0;
                    firstSeqFrame = -1;
                }
            }
        });
        ensureThread();
    }

    void play(List<Note> notes, int partMask, double from) {
        play(notes, partMask, from, 0);
    }

    void stop() {
        playing = false;
        post(new Runnable() {
            @Override
            public void run() {
                engine.stop();
            }
        });
    }

    boolean isPlaying() {
        return playing;
    }

    /** Musical position (seconds of the transcription) that is audible right now. */
    double position() {
        AudioTrack t = track;
        if (!playing || t == null) return idlePosition;
        long head = t.getPlaybackHeadPosition() & 0xFFFFFFFFL;
        synchronized (anchorFrame) {
            if (anchorCount == 0 || head < firstSeqFrame) return idlePosition;
            // newest anchor at or before the head
            for (int i = anchorCount - 1; i >= Math.max(0, anchorCount - ANCHORS); i--) {
                int k = i % ANCHORS;
                if (anchorFrame[k] <= head) {
                    return anchorMusic[k] + (head - anchorFrame[k]) / (double) rate * anchorSpeed[k];
                }
            }
            int k = Math.max(0, anchorCount - ANCHORS) % ANCHORS;
            return anchorMusic[k];
        }
    }

    void liveNoteOn(final int key, final int vel) {
        post(new Runnable() {
            @Override
            public void run() {
                synth.noteOn(LIVE_CHANNEL, key, vel);
            }
        });
        ensureThread();
    }

    void liveNoteOff(final int key) {
        post(new Runnable() {
            @Override
            public void run() {
                synth.noteOff(LIVE_CHANNEL, key);
            }
        });
    }

    void release() {
        running = false;
        playing = false;
        Thread t = thread;
        if (t != null) {
            try {
                t.join(500);
            } catch (InterruptedException ignored) { }
        }
    }

    private void post(Runnable r) {
        commands.add(r);
    }

    private synchronized void ensureThread() {
        if (thread != null) return;
        running = true;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                audioLoop();
            }
        }, "synth");
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.start();
    }

    private void audioLoop() {
        int minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack t = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(Math.max(minBuf, BLOCK * 4 * 8))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build();
        track = t;
        float[] l = new float[BLOCK], r = new float[BLOCK];
        short[] pcm = new short[BLOCK * 2];
        long framesWritten = 0;
        int idleBlocks = 0;
        try {
            t.play();
            while (running) {
                Runnable cmd;
                while ((cmd = commands.poll()) != null) cmd.run();
                boolean seqActive = engine.isPlaying();
                double m = engine.render(l, r, BLOCK);
                if (seqActive) {
                    synchronized (anchorFrame) {
                        int k = anchorCount % ANCHORS;
                        anchorFrame[k] = framesWritten;
                        anchorMusic[k] = m;
                        anchorSpeed[k] = engine.speed();
                        if (anchorCount == 0) firstSeqFrame = framesWritten;
                        anchorCount++;
                    }
                    if (engine.finished()) {
                        engine.stop();
                        playing = false;
                        Runnable f = onFinished;
                        if (f != null) f.run();
                    }
                }
                for (int i = 0; i < BLOCK; i++) {
                    pcm[2 * i] = (short) (l[i] * 32767f);
                    pcm[2 * i + 1] = (short) (r[i] * 32767f);
                }
                int w = t.write(pcm, 0, pcm.length);
                if (w < 0) break;
                framesWritten += BLOCK;
                if (!engine.isPlaying() && synth.activeVoices() == 0 && commands.isEmpty()) {
                    if (++idleBlocks > rate * 3 / BLOCK) {
                        // stop the thread after 3 s of silence (atomically with ensureThread)
                        synchronized (this) {
                            if (commands.isEmpty()) {
                                thread = null;
                                break;
                            }
                        }
                    }
                } else {
                    idleBlocks = 0;
                }
            }
        } finally {
            track = null;
            try {
                t.stop();
            } catch (Exception ignored) { }
            t.release();
            synchronized (this) {
                if (thread == Thread.currentThread()) thread = null;
            }
        }
    }
}
