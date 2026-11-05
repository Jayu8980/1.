package com.hft.piano2midi;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.database.Cursor;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.hft.piano2midi.core.FeatureExtractor;
import com.hft.piano2midi.core.MidiWriter;
import com.hft.piano2midi.core.Note;
import com.hft.piano2midi.core.NoteDecoder;
import com.hft.piano2midi.core.OnnxModel;
import com.hft.piano2midi.core.Resampler;
import com.hft.piano2midi.core.Transcriber;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.List;

public final class MainActivity extends Activity {

    private static final int REQ_PICK = 1;
    private static final int REQ_SAVE = 2;
    private static final String MODEL_ASSET = "hft_transformer.onnx";
    /** Memory bound: PCM + features + frame outputs for 10 min fit comfortably in a large heap. */
    private static final double MAX_SECONDS = 10 * 60;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private Button pickBtn, convertBtn, playBtn, saveBtn, shareBtn;
    private TextView fileText, statusText, statsText, onsetLabel, offsetLabel, frameLabel;
    private SeekBar onsetSeek, offsetSeek, frameSeek;
    private Spinner offsetModeSpinner;
    private ProgressBar progress;
    private PianoRollView roll;
    private HorizontalScrollView rollScroll;

    private Uri audioUri;
    private String audioName = "transcription";
    private volatile boolean cancelled;
    private Thread worker;

    private Transcriber.Result result;
    private List<Note> notes = Collections.emptyList();
    private double audioSeconds;

    private MediaPlayer player;
    private final Runnable playheadTick = new Runnable() {
        @Override
        public void run() {
            if (player != null && player.isPlaying()) {
                double t = player.getCurrentPosition() / 1000.0;
                roll.setPlayhead(t);
                int x = (int) roll.xFor(t) - rollScroll.getWidth() / 3;
                rollScroll.scrollTo(Math.max(0, x), 0);
                ui.postDelayed(this, 30);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        pickBtn = (Button) findViewById(R.id.pick);
        convertBtn = (Button) findViewById(R.id.convert);
        playBtn = (Button) findViewById(R.id.play);
        saveBtn = (Button) findViewById(R.id.save);
        shareBtn = (Button) findViewById(R.id.share);
        fileText = (TextView) findViewById(R.id.file_name);
        statusText = (TextView) findViewById(R.id.status);
        statsText = (TextView) findViewById(R.id.stats);
        onsetLabel = (TextView) findViewById(R.id.onset_label);
        offsetLabel = (TextView) findViewById(R.id.offset_label);
        frameLabel = (TextView) findViewById(R.id.frame_label);
        onsetSeek = (SeekBar) findViewById(R.id.onset_seek);
        offsetSeek = (SeekBar) findViewById(R.id.offset_seek);
        frameSeek = (SeekBar) findViewById(R.id.frame_seek);
        offsetModeSpinner = (Spinner) findViewById(R.id.offset_mode);
        progress = (ProgressBar) findViewById(R.id.progress);
        roll = (PianoRollView) findViewById(R.id.roll);
        rollScroll = (HorizontalScrollView) findViewById(R.id.roll_scroll);

        ArrayAdapter<CharSequence> modes = ArrayAdapter.createFromResource(this, R.array.offset_modes,
                android.R.layout.simple_spinner_item);
        modes.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        offsetModeSpinner.setAdapter(modes);
        offsetModeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                redecode();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });

        SeekBar.OnSeekBarChangeListener seekListener = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int p, boolean fromUser) {
                updateThresholdLabels();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) { }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                redecode();
            }
        };
        onsetSeek.setOnSeekBarChangeListener(seekListener);
        offsetSeek.setOnSeekBarChangeListener(seekListener);
        frameSeek.setOnSeekBarChangeListener(seekListener);
        updateThresholdLabels();

        pickBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"audio/*", "video/mp4", "video/webm", "application/ogg"});
                startActivityForResult(i, REQ_PICK);
            }
        });
        convertBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (worker != null) {
                    cancelled = true;
                    convertBtn.setEnabled(false);
                } else {
                    startTranscription();
                }
            }
        });
        playBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                togglePlay();
            }
        });
        saveBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("audio/midi");
                i.putExtra(Intent.EXTRA_TITLE, audioName + ".mid");
                startActivityForResult(i, REQ_SAVE);
            }
        });
        shareBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                share();
            }
        });
        findViewById(R.id.about).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle(R.string.about_title)
                        .setMessage(R.string.about_text)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            }
        });

        setResultButtonsEnabled(false);
        handleIncomingIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIncomingIntent(intent);
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        Uri uri = null;
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            uri = (Uri) intent.getParcelableExtra(Intent.EXTRA_STREAM);
        } else if (Intent.ACTION_VIEW.equals(intent.getAction())) {
            uri = intent.getData();
        }
        if (uri != null) setAudio(uri);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        if (requestCode == REQ_PICK) {
            setAudio(data.getData());
        } else if (requestCode == REQ_SAVE) {
            saveTo(data.getData());
        }
    }

    private void setAudio(Uri uri) {
        if (worker != null) return;
        audioUri = uri;
        String name = displayName(uri);
        fileText.setText(name);
        int dot = name.lastIndexOf('.');
        audioName = dot > 0 ? name.substring(0, dot) : name;
        convertBtn.setEnabled(true);
        statusText.setText(R.string.status_ready);
    }

    private String displayName(Uri uri) {
        String name = null;
        try {
            Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null) {
                try {
                    if (c.moveToFirst()) name = c.getString(0);
                } finally {
                    c.close();
                }
            }
        } catch (Exception ignored) { }
        if (name == null) name = uri.getLastPathSegment();
        return name != null ? name : "audio";
    }

    private double thr(SeekBar s) {
        return s.getProgress() / 100.0;
    }

    private void updateThresholdLabels() {
        onsetLabel.setText(getString(R.string.onset_threshold, thr(onsetSeek)));
        offsetLabel.setText(getString(R.string.offset_threshold, thr(offsetSeek)));
        frameLabel.setText(getString(R.string.frame_threshold, thr(frameSeek)));
    }

    private NoteDecoder.OffsetMode offsetMode() {
        switch (offsetModeSpinner.getSelectedItemPosition()) {
            case 1: return NoteDecoder.OffsetMode.LONGER;
            case 2: return NoteDecoder.OffsetMode.OFFSET;
            default: return NoteDecoder.OffsetMode.SHORTER;
        }
    }

    private void redecode() {
        if (result == null || worker != null) return;
        stopPlayback();
        notes = NoteDecoder.decode(result, Math.max(0.01, thr(onsetSeek)), Math.max(0.01, thr(offsetSeek)),
                Math.max(0.01, thr(frameSeek)), offsetMode(), true);
        roll.setNotes(notes, audioSeconds);
        updateStats(-1);
    }

    private void updateStats(long elapsedMs) {
        String s = getString(R.string.stats, notes.size(), audioSeconds);
        if (elapsedMs >= 0) {
            s += "  ·  " + getString(R.string.elapsed, elapsedMs / 1000.0);
        }
        statsText.setText(s);
    }

    private void setResultButtonsEnabled(boolean on) {
        playBtn.setEnabled(on);
        saveBtn.setEnabled(on);
        shareBtn.setEnabled(on);
    }

    private void setStatus(final String msg, final int permille) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                statusText.setText(msg);
                progress.setProgress(permille);
            }
        });
    }

    private File ensureModel() throws IOException {
        File f = new File(getFilesDir(), MODEL_ASSET);
        long assetLen = -1;
        try {
            android.content.res.AssetFileDescriptor afd = getAssets().openFd(MODEL_ASSET);
            assetLen = afd.getLength();
            afd.close();
        } catch (IOException ignored) {
            // compressed asset: length unknown, compare after copy
        }
        if (f.isFile() && (assetLen < 0 ? f.length() > 0 : f.length() == assetLen)) return f;
        File tmp = new File(getFilesDir(), MODEL_ASSET + ".tmp");
        InputStream in = getAssets().open(MODEL_ASSET);
        try {
            OutputStream out = new FileOutputStream(tmp);
            try {
                byte[] buf = new byte[1 << 16];
                int r;
                while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
        if (!tmp.renameTo(f)) throw new IOException("could not install model");
        return f;
    }

    private void startTranscription() {
        if (audioUri == null) return;
        stopPlayback();
        cancelled = false;
        result = null;
        notes = Collections.emptyList();
        roll.setNotes(notes, 0);
        statsText.setText("");
        setResultButtonsEnabled(false);
        pickBtn.setEnabled(false);
        convertBtn.setText(R.string.cancel);
        progress.setMax(1000);
        progress.setProgress(0);
        progress.setVisibility(View.VISIBLE);

        final Uri uri = audioUri;
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                final long t0 = SystemClock.elapsedRealtime();
                try {
                    setStatus(getString(R.string.status_loading_model), 0);
                    File modelFile = ensureModel();
                    int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
                    OnnxModel model = new OnnxModel(modelFile.getAbsolutePath(), threads);
                    try {
                        setStatus(getString(R.string.status_decoding), 0);
                        AudioDecoder.Pcm pcm = AudioDecoder.decode(MainActivity.this, uri, MAX_SECONDS,
                                new AudioDecoder.Progress() {
                                    @Override
                                    public void onProgress(float f) {
                                        setStatus(getString(R.string.status_decoding), (int) (f * 80));
                                    }

                                    @Override
                                    public boolean isCancelled() {
                                        return cancelled;
                                    }
                                });
                        if (pcm.samples.length == 0) throw new IOException(getString(R.string.err_empty));
                        setStatus(getString(R.string.status_resampling), 80);
                        float[] wav = Resampler.resample(pcm.samples, pcm.sampleRate, FeatureExtractor.SAMPLE_RATE,
                                new FeatureExtractor.Progress() {
                                    @Override
                                    public void onProgress(float f) {
                                        setStatus(getString(R.string.status_resampling), 80 + (int) (f * 40));
                                    }
                                });
                        pcm = null; // let the decoded full-rate PCM be collected
                        if (cancelled) throw new InterruptedException();
                        final double seconds = wav.length / (double) FeatureExtractor.SAMPLE_RATE;
                        setStatus(getString(R.string.status_features), 120);
                        float[][] feat = new FeatureExtractor().logMel(wav, new FeatureExtractor.Progress() {
                            @Override
                            public void onProgress(float f) {
                                setStatus(getString(R.string.status_features), 120 + (int) (f * 60));
                            }
                        });
                        wav = null;
                        if (cancelled) throw new InterruptedException();
                        final long tModel = SystemClock.elapsedRealtime();
                        Transcriber.Result r = Transcriber.transcribe(feat, model, new Transcriber.Progress() {
                            @Override
                            public void onChunk(int done, int total) {
                                long el = SystemClock.elapsedRealtime() - tModel;
                                long eta = done > 0 ? el * (total - done) / done / 1000 : 0;
                                setStatus(getString(R.string.status_transcribing, done, total, eta),
                                        180 + (int) (820L * done / total));
                            }

                            @Override
                            public boolean isCancelled() {
                                return cancelled;
                            }
                        });
                        final Transcriber.Result fr = r;
                        final long elapsed = SystemClock.elapsedRealtime() - t0;
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                result = fr;
                                audioSeconds = seconds;
                                finishWorker();
                                redecode();
                                updateStats(elapsed);
                                statusText.setText(R.string.status_done);
                                setResultButtonsEnabled(!notes.isEmpty());
                            }
                        });
                    } finally {
                        model.close();
                    }
                } catch (final InterruptedException e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            finishWorker();
                            statusText.setText(R.string.status_cancelled);
                        }
                    });
                } catch (final Throwable e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            finishWorker();
                            String msg = e instanceof OutOfMemoryError ? getString(R.string.err_oom)
                                    : (e.getMessage() != null ? e.getMessage() : e.toString());
                            statusText.setText(getString(R.string.status_error, msg));
                        }
                    });
                }
            }
        }, "transcriber");
        worker.start();
    }

    private void finishWorker() {
        worker = null;
        pickBtn.setEnabled(true);
        convertBtn.setEnabled(audioUri != null);
        convertBtn.setText(R.string.convert);
        progress.setVisibility(View.INVISIBLE);
    }

    private File writeShareFile() throws IOException {
        File f = new File(FileShareProvider.shareDir(this), safeName(audioName) + ".mid");
        FileOutputStream os = new FileOutputStream(f);
        try {
            MidiWriter.write(notes, audioName, os);
        } finally {
            os.close();
        }
        return f;
    }

    private static String safeName(String s) {
        String r = s.replaceAll("[^A-Za-z0-9._ -]", "_").trim();
        return r.isEmpty() ? "transcription" : r;
    }

    private void saveTo(Uri uri) {
        try {
            OutputStream os = getContentResolver().openOutputStream(uri);
            if (os == null) throw new IOException("cannot open destination");
            try {
                MidiWriter.write(notes, audioName, os);
            } finally {
                os.close();
            }
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Toast.makeText(this, getString(R.string.status_error, e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    private void share() {
        try {
            File f = writeShareFile();
            Uri uri = FileShareProvider.uriFor(f.getName());
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("audio/midi");
            i.putExtra(Intent.EXTRA_STREAM, uri);
            i.setClipData(ClipData.newRawUri(f.getName(), uri));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, getString(R.string.share)));
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.status_error, e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    private void togglePlay() {
        if (player != null) {
            stopPlayback();
            return;
        }
        try {
            File f = writeShareFile();
            player = new MediaPlayer();
            player.setDataSource(f.getAbsolutePath());
            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    stopPlayback();
                }
            });
            player.prepare();
            player.start();
            playBtn.setText(R.string.stop);
            ui.post(playheadTick);
        } catch (Exception e) {
            stopPlayback();
            Toast.makeText(this, getString(R.string.status_error, e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    private void stopPlayback() {
        ui.removeCallbacks(playheadTick);
        if (player != null) {
            try {
                player.stop();
            } catch (Exception ignored) { }
            player.release();
            player = null;
        }
        if (playBtn != null) playBtn.setText(R.string.play);
        if (roll != null) roll.setPlayhead(-1);
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopPlayback();
    }

    @Override
    protected void onDestroy() {
        cancelled = true;
        super.onDestroy();
    }
}
