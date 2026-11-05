package com.hft.piano2midi;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import com.hft.piano2midi.core.Note;
import com.hft.piano2midi.core.synth.Sequence;
import com.hft.piano2midi.core.synth.SoundFont;
import com.hft.piano2midi.core.synth.Synth;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Streams the SoundFont synth to an AudioTrack. Handles sequence playback (with an
 * accurate play position for the keyboard / sheet animation) and live notes from the
 * on-screen keyboard. All synth access happens on the audio thread via a command queue.
 */
final class SynthPlayer {

    static final int LIVE_CHANNEL = 3;
    private static final int BLOCK = 256;

    private final int rate;
    private final Synth synth;
    private final ConcurrentLinkedQueue<Runnable> commands = new ConcurrentLinkedQueue<Runnable>();
    private Thread thread;
    private volatile boolean running;

    // sequence state (audio thread writes, UI reads)
    private Sequence sequence;
    private volatile boolean playing;
    private volatile double seqFrom;
    private volatile long seqStartFrame = -1;
    private volatile AudioTrack track;
    private volatile Runnable onFinished;

    SynthPlayer(SoundFont font) {
        int r = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC);
        rate = r >= 22050 && r <= 96000 ? r : 44100;
        synth = new Synth(font, rate);
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

    /** Starts playing `notes` (parts in partMask) from `from` seconds. */
    void play(List<Note> notes, int partMask, final double from) {
        final Sequence seq = new Sequence(notes, partMask);
        seq.seek(from);
        playing = true;
        seqFrom = from;
        seqStartFrame = -1;
        post(new Runnable() {
            @Override
            public void run() {
                synth.allNotesOff();
                sequence = seq;
                seqStartFrame = -2; // set on the first rendered block
            }
        });
        ensureThread();
    }

    void stop() {
        playing = false;
        post(new Runnable() {
            @Override
            public void run() {
                sequence = null;
                synth.allNotesOff();
            }
        });
    }

    boolean isPlaying() {
        return playing;
    }

    /** Current playback position in seconds (what is audible now). */
    double position() {
        AudioTrack t = track;
        long start = seqStartFrame;
        if (!playing || t == null || start < 0) return seqFrom;
        long head = t.getPlaybackHeadPosition() & 0xFFFFFFFFL;
        return seqFrom + Math.max(0, head - start) / (double) rate;
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
                Sequence seq = sequence;
                if (seq != null) {
                    if (seqStartFrame == -2) seqStartFrame = framesWritten;
                    double tEnd = seqFrom + (framesWritten + BLOCK - Math.max(0, seqStartFrame)) / (double) rate;
                    seq.dispatchUntil(synth, tEnd);
                    if (seq.finished() && synth.activeVoices() == 0) {
                        sequence = null;
                        playing = false;
                        Runnable f = onFinished;
                        if (f != null) f.run();
                    }
                }
                synth.render(l, r, BLOCK);
                for (int i = 0; i < BLOCK; i++) {
                    pcm[2 * i] = (short) (l[i] * 32767f);
                    pcm[2 * i + 1] = (short) (r[i] * 32767f);
                }
                int w = t.write(pcm, 0, pcm.length);
                if (w < 0) break;
                framesWritten += BLOCK;
                if (sequence == null && synth.activeVoices() == 0 && commands.isEmpty()) {
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
