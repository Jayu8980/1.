package com.hft.piano2midi;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Typeface;
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
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.HorizontalScrollView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.hft.piano2midi.core.FeatureExtractor;
import com.hft.piano2midi.core.LiveTranscriber;
import com.hft.piano2midi.core.WavWriter;
import com.hft.piano2midi.core.MidiWriter;
import com.hft.piano2midi.core.Note;
import com.hft.piano2midi.core.NoteDecoder;
import com.hft.piano2midi.core.OnnxModel;
import com.hft.piano2midi.core.Resampler;
import com.hft.piano2midi.core.Transcriber;
import com.hft.piano2midi.core.music.ChordRecognizer;
import com.hft.piano2midi.core.music.Key;
import com.hft.piano2midi.core.music.Monophonic;
import com.hft.piano2midi.core.music.Part;
import com.hft.piano2midi.core.music.PartSplitter;
import com.hft.piano2midi.core.music.Tempo;
import com.hft.piano2midi.core.sheet.Score;
import com.hft.piano2midi.core.sheet.SheetLayout;
import com.hft.piano2midi.core.synth.SoundFont;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {

    private static final int REQ_PICK = 1, REQ_SAVE = 2, REQ_SF2 = 3, REQ_SAVE_WAV = 4, REQ_MIC = 5, REQ_MIC_LISTEN = 6;
    private static final String MODEL_ASSET = "hft_transformer.onnx";
    private static final String SF2_ASSET = "instruments.sf2";
    private static final String CUSTOM_SF2 = "custom.sf2";
    /** Memory bound: PCM + features + frame outputs for 10 min fit comfortably in a large heap. */
    private static final double MAX_SECONDS = 10 * 60;
    private static final int[] DEFAULT_PROGRAMS = {0, 0, 0};
    private static final int FMT_MIDI = 0, FMT_PDF = 1;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    // views
    private Button pickBtn, convertBtn;
    private android.widget.ImageButton playBtn, stopBtn;
    private Button[] tabs;
    private View[] tabViews;
    private TextView fileText, statusText, statsText, timeText, onsetLabel, offsetLabel, frameLabel;
    private TextView tempoLabel, analysisText, sf2Text;
    private SeekBar onsetSeek, offsetSeek, frameSeek, tempoSeek;
    private Spinner offsetModeSpinner, timeSigSpinner, exportSpinner;
    private final Spinner[] instSpinners = new Spinner[3];
    private final CheckBox[] partBoxes = new CheckBox[3];
    private ProgressBar progress;
    private PianoRollView roll;
    private HorizontalScrollView rollScroll;
    private FallingNotesView falling;
    private SheetView sheet;
    private ScrollView sheetScroll;
    private KeyboardView keyboard;

    // transcription state
    private Uri audioUri;
    private String audioName = "transcription";
    private volatile boolean cancelled;
    private Thread worker;
    private Transcriber.Result result;
    private List<Note> notes = Collections.emptyList();
    private double audioSeconds;
    private double maxNoteDur;

    // analysis
    private Tempo tempo;
    private Key key;
    private List<ChordRecognizer.Chord> chords = Collections.emptyList();
    private double bpmOverride = 0;
    private boolean sheetDirty = true;

    // playback
    private SoundFont font;
    private final SoundFont.Preset[] presets = new SoundFont.Preset[3];
    private SynthPlayer player;
    private Typeface musicFont;
    private int partMask = Part.ALL;
    private double pausedAt = 0;
    private int tab = 0;
    private final int[] activeKeys = new int[128];
    private boolean suppressSpinner;
    // navigation
    private View[] pages;
    private TextView[] navItems;
    private int page = -1;

    // microphone recording
    private Button recordBtn, saveWavBtn;
    private ProgressBar levelBar;
    // metronome / speed
    private CheckBox metronomeBox, countInBox;
    private TextView beatText, speedLabel;
    private SeekBar speedSeek;
    private double speed = 1.0;
    // loop / practice
    private CheckBox loopBox, practiceBox, practiceCountIn;
    private TextView loopAText, loopBText, loopStatus, practiceStartLabel;
    private SeekBar practiceStartSeek;
    private Spinner practiceStepSpinner, practiceMuteSpinner;
    private View practiceOpts;
    private int loopA = 1, loopB = 4;
    private static final double[] PRACTICE_STEPS = {0, 0.05, 0.10, 0.20};
    // wait mode
    private CheckBox waitBox, waitMicBox;
    private View waitOpts, playPartRow;
    private TextView waitStatus, waitMidiText;
    private MidiInput midi;
    private MicListener mic;
    private int lastPressed = -1, lastWrong;
    private final boolean[] accompSounding = new boolean[128];
    private volatile MicRecorder recorder;
    private float[] recordedPcm;
    private int recordedLen, recordedRate;
    private volatile double liveHead;
    private volatile int liveNoteCount;
    private double liveShown;
    private final Runnable recordTick = new Runnable() {
        @Override
        public void run() {
            MicRecorder r = recorder;
            if (r == null) return;
            levelBar.setProgress((int) Math.min(100, Math.sqrt(r.rms()) * 220));
            if (r.isRunning()) {
                statusText.setText(getString(R.string.recording_status, clock(r.seconds()), clock(liveHead), liveNoteCount));
            }
            // glide the live view toward the transcription head
            liveShown += (liveHead - liveShown) * 0.12;
            falling.setPosition(liveShown);
            for (int i = 0; i < 128; i++) activeKeys[i] = -1;
            for (Note n : notes) {
                if (n.onset <= liveShown && n.offset > liveShown - 0.05) activeKeys[n.pitch] = n.part;
            }
            keyboard.setActive(activeKeys);
            keyboard.postOnAnimation(this);
        }
    };

    // pending export (waiting for the save dialog)
    private int pendingFormat, pendingMask;

    private final Runnable frame = new Runnable() {
        @Override
        public void run() {
            if (player == null || !player.isPlaying()) return;
            showPosition(player.position(), true);
            updateLoopStatus();
            if (practiceBox.isChecked()) updateSpeedLabel();
            updateWait(player.position());
            keyboard.postOnAnimation(this);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        musicFont = Typeface.createFromAsset(getAssets(), "fonts/Bravura.otf");

        pickBtn = (Button) findViewById(R.id.pick);
        convertBtn = (Button) findViewById(R.id.convert);
        playBtn = (android.widget.ImageButton) findViewById(R.id.play);
        stopBtn = (android.widget.ImageButton) findViewById(R.id.stop);
        findViewById(R.id.viz_frame).setClipToOutline(true);
        // sections live on their own pages now: open by default
        setupSection(R.id.sec_loop_h, R.id.sec_loop_b, "p_sec_loop", true);
        setupSection(R.id.sec_inst_h, R.id.sec_inst_b, "p_sec_inst", true);
        setupSection(R.id.sec_music_h, R.id.sec_music_b, "p_sec_music", true);
        setupSection(R.id.sec_detect_h, R.id.sec_detect_b, "p_sec_detect", true);
        findViewById(R.id.theme_btn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showThemeDialog();
            }
        });
        fileText = (TextView) findViewById(R.id.file_name);
        statusText = (TextView) findViewById(R.id.status);
        statsText = (TextView) findViewById(R.id.stats);
        timeText = (TextView) findViewById(R.id.time);
        onsetLabel = (TextView) findViewById(R.id.onset_label);
        offsetLabel = (TextView) findViewById(R.id.offset_label);
        frameLabel = (TextView) findViewById(R.id.frame_label);
        tempoLabel = (TextView) findViewById(R.id.tempo_label);
        analysisText = (TextView) findViewById(R.id.analysis);
        sf2Text = (TextView) findViewById(R.id.sf2_name);
        onsetSeek = (SeekBar) findViewById(R.id.onset_seek);
        offsetSeek = (SeekBar) findViewById(R.id.offset_seek);
        frameSeek = (SeekBar) findViewById(R.id.frame_seek);
        tempoSeek = (SeekBar) findViewById(R.id.tempo_seek);
        offsetModeSpinner = (Spinner) findViewById(R.id.offset_mode);
        timeSigSpinner = (Spinner) findViewById(R.id.time_sig);
        exportSpinner = (Spinner) findViewById(R.id.export_part);
        instSpinners[0] = (Spinner) findViewById(R.id.inst_melody);
        instSpinners[1] = (Spinner) findViewById(R.id.inst_chords);
        instSpinners[2] = (Spinner) findViewById(R.id.inst_bass);
        partBoxes[0] = (CheckBox) findViewById(R.id.part_melody);
        partBoxes[1] = (CheckBox) findViewById(R.id.part_chords);
        partBoxes[2] = (CheckBox) findViewById(R.id.part_bass);
        progress = (ProgressBar) findViewById(R.id.progress);
        roll = (PianoRollView) findViewById(R.id.roll);
        rollScroll = (HorizontalScrollView) findViewById(R.id.roll_scroll);
        falling = (FallingNotesView) findViewById(R.id.falling);
        sheet = (SheetView) findViewById(R.id.sheet);
        sheetScroll = (ScrollView) findViewById(R.id.sheet_scroll);
        keyboard = (KeyboardView) findViewById(R.id.keyboard);
        metronomeBox = (CheckBox) findViewById(R.id.metronome);
        countInBox = (CheckBox) findViewById(R.id.count_in);
        beatText = (TextView) findViewById(R.id.beat);
        speedLabel = (TextView) findViewById(R.id.speed_label);
        speedSeek = (SeekBar) findViewById(R.id.speed_seek);
        metronomeBox.setChecked(prefs.getBoolean("metronome", false));
        countInBox.setChecked(prefs.getBoolean("count_in", false));
        speed = prefs.getInt("speed_pct", 100) / 100.0;
        speedSeek.setProgress((int) Math.round(speed * 100 - 25) / 5);
        metronomeBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                prefs.edit().putBoolean("metronome", on).apply();
                if (player != null) player.setMetronome(on);
            }
        });
        countInBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                prefs.edit().putBoolean("count_in", on).apply();
            }
        });
        speedSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (fromUser) setSpeed((25 + 5 * p) / 100.0);
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) { }

            @Override
            public void onStopTrackingTouch(SeekBar sb) { }
        });
        findViewById(R.id.speed_reset).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setSpeed(1.0);
                speedSeek.setProgress(15);
            }
        });
        updateSpeedLabel();
        setupLoopUi();
        recordBtn = (Button) findViewById(R.id.record);
        saveWavBtn = (Button) findViewById(R.id.save_wav);
        levelBar = (ProgressBar) findViewById(R.id.level);
        recordBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (recorder != null) {
                    stopRecording();
                } else if (worker == null) {
                    if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                            == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        startRecording();
                    } else {
                        requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQ_MIC);
                    }
                }
            }
        });
        saveWavBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (recordedPcm == null) return;
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("audio/wav");
                i.putExtra(Intent.EXTRA_TITLE, safeName(audioName) + ".wav");
                startActivityForResult(i, REQ_SAVE_WAV);
            }
        });

        tabs = new Button[]{(Button) findViewById(R.id.tab_falling), (Button) findViewById(R.id.tab_roll),
                (Button) findViewById(R.id.tab_sheet)};
        tabViews = new View[]{falling, rollScroll, sheetScroll};
        for (int i = 0; i < tabs.length; i++) {
            final int idx = i;
            tabs[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectTab(idx);
                }
            });
        }

        setupSpinner(offsetModeSpinner, R.array.offset_modes);
        setupSpinner(timeSigSpinner, R.array.time_sigs);
        setupSpinner(exportSpinner, R.array.export_parts);
        final CheckBox monoBox = (CheckBox) findViewById(R.id.export_mono);
        final Spinner monoRule = (Spinner) findViewById(R.id.export_mono_rule);
        setupSpinner(monoRule, R.array.mono_rules);
        monoRule.setSelection(prefs.getInt("mono_rule", Monophonic.HIGHEST));
        monoBox.setChecked(prefs.getBoolean("mono", false));
        final Runnable monoVis = new Runnable() {
            @Override
            public void run() {
                int v = monoBox.isChecked() ? View.VISIBLE : View.GONE;
                findViewById(R.id.export_mono_row).setVisibility(v);
                findViewById(R.id.export_mono_hint).setVisibility(v);
            }
        };
        monoVis.run();
        monoBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                prefs.edit().putBoolean("mono", on).apply();
                monoVis.run();
            }
        });
        monoRule.setOnItemSelectedListener(new SimpleItemListener() {
            @Override
            void onSelected(int position) {
                prefs.edit().putInt("mono_rule", position).apply();
            }
        });
        offsetModeSpinner.setOnItemSelectedListener(new SimpleItemListener() {
            @Override
            void onSelected(int position) {
                redecode();
            }
        });
        timeSigSpinner.setOnItemSelectedListener(new SimpleItemListener() {
            @Override
            void onSelected(int position) {
                analyze();
            }
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

        tempoSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) tempoLabel.setText(getString(R.string.tempo_label, p + 40, ""));
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) { }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                bpmOverride = s.getProgress() + 40;
                analyze();
            }
        });
        findViewById(R.id.tempo_auto).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                bpmOverride = 0;
                analyze();
            }
        });

        for (int i = 0; i < 3; i++) {
            partBoxes[i].setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton b, boolean checked) {
                    partMask = (partBoxes[0].isChecked() ? 1 : 0) | (partBoxes[1].isChecked() ? 2 : 0)
                            | (partBoxes[2].isChecked() ? 4 : 0);
                    roll.setPartMask(partMask);
                    falling.setPartMask(partMask);
                    sheetDirty = true;
                    if (tab == 2) rebuildSheet();
                    applyWait();
                    restartIfPlaying();
                }
            });
        }

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
        stopBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopPlayback();
                pausedAt = 0;
                showPosition(0, false);
            }
        });
        roll.setSeekListener(new PianoRollView.SeekListener() {
            @Override
            public void onSeek(double sec) {
                seek(sec);
            }
        });
        keyboard.setListener(new KeyboardView.Listener() {
            @Override
            public void onKeyDown(int pitch) {
                if (player != null) player.liveNoteOn(pitch, 96);
                userPressed(pitch);
            }

            @Override
            public void onKeyUp(int pitch) {
                if (player != null) player.liveNoteOff(pitch);
            }
        });
        sheetScroll.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override
            public void onScrollChange(View v, int x, int y, int ox, int oy) {
                sheet.invalidate();
            }
        });

        findViewById(R.id.save_midi).setOnClickListener(exportClick(FMT_MIDI, false));
        findViewById(R.id.save_pdf).setOnClickListener(exportClick(FMT_PDF, false));
        findViewById(R.id.share_midi).setOnClickListener(exportClick(FMT_MIDI, true));
        findViewById(R.id.share_pdf).setOnClickListener(exportClick(FMT_PDF, true));
        findViewById(R.id.load_sf2).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                startActivityForResult(i, REQ_SF2);
            }
        });
        findViewById(R.id.builtin_sf2).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.edit().putBoolean("custom_sf2", false).apply();
                loadSoundFont(null);
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

        selectTab(prefs.getInt("tab", 0));
        setupNav();
        setResultButtonsEnabled(false);
        playBtn.setEnabled(false);
        Object retained = getLastNonConfigurationInstance();
        if (!(retained instanceof Retained) && savedInstanceState == null && prefs.getBoolean("splash", true)) {
            showSplash();
        }
        if (retained instanceof Retained) {
            restore((Retained) retained);
        } else {
            loadSoundFont(prefs.getBoolean("custom_sf2", false) ? new File(getFilesDir(), CUSTOM_SF2) : null);
            handleIncomingIntent(getIntent());
        }
    }

    private abstract static class SimpleItemListener implements AdapterView.OnItemSelectedListener {
        private boolean first = true;

        abstract void onSelected(int position);

        @Override
        public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            if (first) {
                first = false;
                return;
            }
            onSelected(position);
        }

        @Override
        public void onNothingSelected(AdapterView<?> parent) { }
    }

    private void setupSpinner(Spinner s, int array) {
        ArrayAdapter<CharSequence> a = ArrayAdapter.createFromResource(this, array, android.R.layout.simple_spinner_item);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(a);
    }

    private void selectTab(int idx) {
        tab = Math.max(0, Math.min(2, idx));
        for (int i = 0; i < tabs.length; i++) {
            boolean on = i == tab;
            tabViews[i].setVisibility(on ? View.VISIBLE : View.GONE);
            tabs[i].setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    getColor(on ? R.color.tab_on : R.color.tab_off)));
            tabs[i].setTextColor(getColor(on ? R.color.tab_on_text : R.color.tab_off_text));
        }
        prefs.edit().putInt("tab", tab).apply();
        if (tab == 2 && sheetDirty) rebuildSheet();
        showPosition(player != null && player.isPlaying() ? player.position() : pausedAt, false);
    }

    // ------------------------------------------------------------ soundfont

    private File ensureAsset(String asset) throws IOException {
        File f = new File(getFilesDir(), asset);
        long assetLen = -1;
        try {
            android.content.res.AssetFileDescriptor afd = getAssets().openFd(asset);
            assetLen = afd.getLength();
            afd.close();
        } catch (IOException ignored) {
            // compressed asset: length unknown
        }
        if (f.isFile() && (assetLen < 0 ? f.length() > 0 : f.length() == assetLen)) return f;
        File tmp = new File(getFilesDir(), asset + ".tmp");
        InputStream in = getAssets().open(asset);
        try {
            copy(in, tmp);
        } finally {
            in.close();
        }
        if (!tmp.renameTo(f)) throw new IOException("could not install " + asset);
        return f;
    }

    private static void copy(InputStream in, File dst) throws IOException {
        OutputStream out = new FileOutputStream(dst);
        try {
            byte[] buf = new byte[1 << 16];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
        } finally {
            out.close();
        }
    }

    private static SoundFont mapSoundFont(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        try {
            FileChannel ch = in.getChannel();
            MappedByteBuffer buf = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());
            return SoundFont.load(buf);
        } finally {
            in.close();
        }
    }

    /** Loads the built-in SoundFont (file == null) or a custom one, off the UI thread. */
    private void loadSoundFont(final File file) {
        sf2Text.setText(R.string.sf2_loading);
        new Thread(new Runnable() {
            @Override
            public void run() {
                SoundFont sf = null;
                String err = null;
                try {
                    sf = mapSoundFont(file != null ? file : ensureAsset(SF2_ASSET));
                } catch (Throwable e) {
                    err = e.getMessage() != null ? e.getMessage() : e.toString();
                    if (file != null) {
                        try {
                            sf = mapSoundFont(ensureAsset(SF2_ASSET));
                        } catch (Throwable ignored) { }
                    }
                }
                final SoundFont fsf = sf;
                final String ferr = err;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (ferr != null) {
                            Toast.makeText(MainActivity.this, getString(R.string.sf2_error, ferr), Toast.LENGTH_LONG).show();
                            prefs.edit().putBoolean("custom_sf2", false).apply();
                        }
                        if (fsf != null) applySoundFont(fsf);
                    }
                });
            }
        }, "sf2-load").start();
    }

    private void applySoundFont(SoundFont sf) {
        font = sf;
        sf2Text.setText(getString(R.string.sf2_info, sf.name, sf.presets.size()));
        List<String> names = new ArrayList<String>();
        for (SoundFont.Preset p : sf.presets) {
            names.add(String.format(Locale.US, "%s  (%d:%d)", p.name, p.bank, p.program));
        }
        suppressSpinner = true;
        for (int part = 0; part < 3; part++) {
            ArrayAdapter<String> a = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, names);
            a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            instSpinners[part].setAdapter(a);
            int prog = prefs.getInt("prog" + part, DEFAULT_PROGRAMS[part]);
            int bank = prefs.getInt("bank" + part, 0);
            SoundFont.Preset p = sf.find(bank, prog);
            presets[part] = p;
            instSpinners[part].setSelection(Math.max(0, sf.presets.indexOf(p)), false);
            final int fpart = part;
            instSpinners[part].setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    if (suppressSpinner || font == null) return;
                    SoundFont.Preset sel = font.presets.get(position);
                    presets[fpart] = sel;
                    prefs.edit().putInt("prog" + fpart, sel.program).putInt("bank" + fpart, sel.bank).apply();
                    if (player != null) {
                        player.setPreset(fpart, sel);
                        if (fpart == Part.MELODY) player.setPreset(SynthPlayer.LIVE_CHANNEL, sel);
                    }
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) { }
            });
        }
        ui.post(new Runnable() {
            @Override
            public void run() {
                suppressSpinner = false;
            }
        });
        SoundFont.Preset[] chans = {presets[0], presets[1], presets[2], presets[0]};
        if (player == null) player = new SynthPlayer(sf);
        {
            player.setOnFinished(new Runnable() {
                @Override
                public void run() {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            pausedAt = 0;
                            setPlayIcon(false);
                            showPosition(0, false);
                        }
                    });
                }
            });
        }
        player.setFont(sf, chans);
        syncPlayerTiming();
        playBtn.setEnabled(!notes.isEmpty());
    }

    private void importSoundFont(final Uri uri) {
        sf2Text.setText(R.string.sf2_loading);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    File tmp = new File(getFilesDir(), CUSTOM_SF2 + ".tmp");
                    InputStream in = getContentResolver().openInputStream(uri);
                    if (in == null) throw new IOException("cannot open file");
                    try {
                        copy(in, tmp);
                    } finally {
                        in.close();
                    }
                    mapSoundFont(tmp); // validate before replacing
                    File dst = new File(getFilesDir(), CUSTOM_SF2);
                    if (dst.exists() && !dst.delete()) throw new IOException("cannot replace SoundFont");
                    if (!tmp.renameTo(dst)) throw new IOException("cannot store SoundFont");
                    prefs.edit().putBoolean("custom_sf2", true).apply();
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            loadSoundFont(new File(getFilesDir(), CUSTOM_SF2));
                        }
                    });
                } catch (final Throwable e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(MainActivity.this, getString(R.string.sf2_error, e.getMessage()),
                                    Toast.LENGTH_LONG).show();
                            if (font != null) sf2Text.setText(getString(R.string.sf2_info, font.name, font.presets.size()));
                        }
                    });
                }
            }
        }, "sf2-import").start();
    }

    // ------------------------------------------------------------ theme

    private static final int THEME_SYSTEM = 0, THEME_LIGHT = 1, THEME_DARK = 2;
    private boolean pendingRecreate;
    private Retained retainedForRecreate;

    /** Applies the Light / Dark override before any resources are loaded. */
    @Override
    protected void attachBaseContext(android.content.Context base) {
        int mode = base.getSharedPreferences("settings", MODE_PRIVATE).getInt("theme", THEME_SYSTEM);
        if (mode != THEME_SYSTEM) {
            android.content.res.Configuration c = new android.content.res.Configuration(base.getResources().getConfiguration());
            c.uiMode = (c.uiMode & ~android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                    | (mode == THEME_DARK ? android.content.res.Configuration.UI_MODE_NIGHT_YES
                    : android.content.res.Configuration.UI_MODE_NIGHT_NO);
            base = base.createConfigurationContext(c);
        }
        super.attachBaseContext(base);
    }

    private boolean resourcesNight() {
        return (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    /** System dark mode toggled while following the system theme: rebuild the UI in the new colours. */
    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (prefs.getInt("theme", THEME_SYSTEM) != THEME_SYSTEM) return;
        boolean sysNight = (newConfig.uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        if (sysNight != resourcesNight()) recreateKeepingState();
    }

    @Override
    public boolean onCreateOptionsMenu(android.view.Menu menu) {
        menu.add(0, 1, 0, R.string.theme).setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_IF_ROOM
                | android.view.MenuItem.SHOW_AS_ACTION_WITH_TEXT);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (item.getItemId() != 1) return super.onOptionsItemSelected(item);
        showThemeDialog();
        return true;
    }

    private void showThemeDialog() {
        int cur = prefs.getInt("theme", THEME_SYSTEM);
        new AlertDialog.Builder(this)
                .setTitle(R.string.theme)
                .setSingleChoiceItems(R.array.themes, cur, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        d.dismiss();
                        if (which == prefs.getInt("theme", THEME_SYSTEM)) return;
                        prefs.edit().putInt("theme", which).apply();
                        recreateKeepingState();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Animated intro on top of the UI (not shown again when the activity is re-created for a theme switch). */
    private void showSplash() {
        final SplashView sv = new SplashView(this);
        addContentView(sv, new android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        sv.play(new Runnable() {
            @Override
            public void run() {
                sv.post(new Runnable() {
                    @Override
                    public void run() {
                        if (sv.getParent() instanceof android.view.ViewGroup) {
                            ((android.view.ViewGroup) sv.getParent()).removeView(sv);
                        }
                    }
                });
            }
        });
    }

    private void setPlayIcon(boolean playing) {
        if (playBtn == null) return;
        playBtn.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        playBtn.setContentDescription(getString(playing ? R.string.pause : R.string.play));
    }

    /** Collapsible card: tapping the header shows/hides the body; the state is remembered. */
    private void setupSection(int headerId, int bodyId, final String key, boolean defaultOpen) {
        final TextView header = (TextView) findViewById(headerId);
        final View body = findViewById(bodyId);
        final Runnable apply = new Runnable() {
            @Override
            public void run() {
                boolean open = body.getVisibility() == View.VISIBLE;
                android.graphics.drawable.Drawable[] d = header.getCompoundDrawablesRelative();
                header.setCompoundDrawablesRelativeWithIntrinsicBounds(d[0], null,
                        getDrawable(open ? R.drawable.ic_collapse : R.drawable.ic_expand), null);
            }
        };
        body.setVisibility(prefs.getBoolean(key, defaultOpen) ? View.VISIBLE : View.GONE);
        apply.run();
        header.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean open = body.getVisibility() != View.VISIBLE;
                body.setVisibility(open ? View.VISIBLE : View.GONE);
                prefs.edit().putBoolean(key, open).apply();
                apply.run();
            }
        });
    }

    private void recreateKeepingState() {
        if (worker != null || recorder != null) {
            // don't interrupt a transcription or recording; switch when it is done
            pendingRecreate = true;
            Toast.makeText(this, R.string.theme_after_task, Toast.LENGTH_SHORT).show();
            return;
        }
        retainedForRecreate = capture();
        recreate();
    }

    /** Everything a theme switch must carry over to the new activity instance. */
    private static final class Retained {
        Uri audioUri;
        String audioName;
        Transcriber.Result result;
        double audioSeconds, bpmOverride, pausedAt;
        float[] recordedPcm;
        int recordedLen, recordedRate;
        SoundFont font;
        SynthPlayer player;
        int onset, offset, frame, offsetMode, timeSig, exportPart, loopA, loopB, tab;
        boolean[] parts = new boolean[3];
        boolean loop, practice, wait;
        String status;
    }

    private Retained capture() {
        Retained r = new Retained();
        r.audioUri = audioUri;
        r.audioName = audioName;
        r.result = result;
        r.audioSeconds = audioSeconds;
        r.bpmOverride = bpmOverride;
        r.pausedAt = player != null && player.isPlaying() ? Math.max(0, player.position()) : pausedAt;
        stopPlayback();
        r.recordedPcm = recordedPcm;
        r.recordedLen = recordedLen;
        r.recordedRate = recordedRate;
        r.font = font;
        r.player = player;
        r.onset = onsetSeek.getProgress();
        r.offset = offsetSeek.getProgress();
        r.frame = frameSeek.getProgress();
        r.offsetMode = offsetModeSpinner.getSelectedItemPosition();
        r.timeSig = timeSigSpinner.getSelectedItemPosition();
        r.exportPart = exportSpinner.getSelectedItemPosition();
        for (int i = 0; i < 3; i++) r.parts[i] = partBoxes[i].isChecked();
        r.loop = loopBox.isChecked();
        r.practice = practiceBox.isChecked();
        r.wait = waitBox.isChecked();
        r.loopA = loopA;
        r.loopB = loopB;
        r.tab = tab;
        r.status = statusText.getText().toString();
        return r;
    }

    @Override
    @SuppressWarnings("deprecation")
    public Object onRetainNonConfigurationInstance() {
        return retainedForRecreate != null ? retainedForRecreate : capture();
    }

    private void restore(Retained r) {
        player = r.player;
        audioUri = r.audioUri;
        audioName = r.audioName;
        bpmOverride = r.bpmOverride;
        recordedPcm = r.recordedPcm;
        recordedLen = r.recordedLen;
        recordedRate = r.recordedRate;
        onsetSeek.setProgress(r.onset);
        offsetSeek.setProgress(r.offset);
        frameSeek.setProgress(r.frame);
        updateThresholdLabels();
        offsetModeSpinner.setSelection(r.offsetMode, false);
        timeSigSpinner.setSelection(r.timeSig, false);
        exportSpinner.setSelection(r.exportPart, false);
        for (int i = 0; i < 3; i++) partBoxes[i].setChecked(r.parts[i]);
        loopBox.setChecked(r.loop);
        practiceBox.setChecked(r.practice);
        waitBox.setChecked(r.wait);
        loopA = r.loopA;
        loopB = r.loopB;
        if (audioUri != null || recordedPcm != null) {
            fileText.setText(audioName);
            convertBtn.setEnabled(audioUri != null);
        }
        saveWavBtn.setVisibility(recordedPcm != null ? View.VISIBLE : View.GONE);
        if (r.font != null) applySoundFont(r.font);
        else loadSoundFont(prefs.getBoolean("custom_sf2", false) ? new File(getFilesDir(), CUSTOM_SF2) : null);
        if (r.result != null) {
            result = r.result;
            audioSeconds = r.audioSeconds;
            redecode();
        }
        pausedAt = r.pausedAt;
        statusText.setText(r.status);
        selectTab(r.tab);
        showPosition(pausedAt, false);
    }

    // ------------------------------------------------------------ input

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
        if (requestCode == REQ_PICK) setAudio(data.getData());
        else if (requestCode == REQ_SAVE) saveTo(data.getData());
        else if (requestCode == REQ_SF2) importSoundFont(data.getData());
        else if (requestCode == REQ_SAVE_WAV) saveWav(data.getData());
    }

    private void setAudio(Uri uri) {
        if (worker != null || recorder != null) return;
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

    // ------------------------------------------------------------ decoding / analysis

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
        PartSplitter.split(notes);
        maxNoteDur = 0;
        for (Note n : notes) maxNoteDur = Math.max(maxNoteDur, n.offset - n.onset);
        analyze();
        updateStats(-1);
        boolean any = !notes.isEmpty();
        setResultButtonsEnabled(any);
        playBtn.setEnabled(any && font != null);
    }

    private int beatsPerBar() {
        switch (timeSigSpinner.getSelectedItemPosition()) {
            case 1: return 3;
            case 2: return 2;
            default: return 4;
        }
    }

    /** Tempo, key and chord detection, then refresh every view that depends on them. */
    private void analyze() {
        if (notes.isEmpty()) {
            tempo = null;
            key = null;
            chords = Collections.emptyList();
        } else {
            int bpb = beatsPerBar();
            tempo = bpmOverride > 0 ? Tempo.withBpm(notes, bpmOverride, bpb) : Tempo.estimate(notes, bpb);
            key = Key.estimate(notes);
            chords = ChordRecognizer.recognize(notes, tempo, key);
        }
        roll.setData(notes, chords, tempo, audioSeconds);
        roll.setPartMask(partMask);
        falling.setData(notes, chords, tempo);
        falling.setPartMask(partMask);
        if (tempo != null) {
            tempoSeek.setProgress((int) Math.round(tempo.bpm) - 40);
            tempoLabel.setText(getString(R.string.tempo_label, (int) Math.round(tempo.bpm),
                    bpmOverride > 0 ? "" : getString(R.string.tempo_auto_suffix)));
            StringBuilder sb = new StringBuilder();
            String last = null;
            int count = 0;
            for (ChordRecognizer.Chord c : chords) {
                if (c.root < 0 || c.name.equals(last)) continue;
                if (count++ > 0) sb.append(" | ");
                sb.append(c.name);
                last = c.name;
                if (count >= 48) {
                    sb.append(" …");
                    break;
                }
            }
            analysisText.setText(getString(R.string.analysis, key.name(), sb.length() > 0 ? sb.toString() : "–"));
        } else {
            tempoLabel.setText("");
            analysisText.setText("");
        }
        sheetDirty = true;
        if (tab == 2) rebuildSheet();
        syncPlayerTiming();
        updateSpeedLabel();
        if (loopBox != null) applyLoop(false);
        applyWait();
        updateSummary();
        showPosition(pausedAt, false);
    }

    private Score buildScore(int mask, String title) {
        List<Note> sel = PartSplitter.filter(notes, mask);
        boolean grand = (mask & Part.mask(Part.CHORDS)) != 0 || Integer.bitCount(mask) > 1;
        List<ChordRecognizer.Chord> cs = mask == Part.mask(Part.BASS) ? null : chords;
        return Score.build(sel, tempo, key, cs, grand, title);
    }

    private void rebuildSheet() {
        sheetDirty = false;
        if (tempo == null || notes.isEmpty()) {
            sheet.setLayout(null, musicFont);
            return;
        }
        sheet.setLayout(new SheetLayout(buildScore(partMask, audioName)), musicFont);
        if (loopBox != null) sheet.setLoopMeasures(loopActive() ? loopA - 1 : -1, loopB - 1);
    }

    private void updateStats(long elapsedMs) {
        int[] c = new int[3];
        for (Note n : notes) c[n.part]++;
        String s = getString(R.string.stats, notes.size(), audioSeconds, c[0], c[1], c[2]);
        if (elapsedMs >= 0) s += "  ·  " + getString(R.string.elapsed, elapsedMs / 1000.0);
        statsText.setText(s);
    }

    private void setResultButtonsEnabled(boolean on) {
        int[] ids = {R.id.save_midi, R.id.save_pdf, R.id.share_midi, R.id.share_pdf};
        for (int id : ids) findViewById(id).setEnabled(on);
        stopBtn.setEnabled(on);
    }

    // ------------------------------------------------------------ transcription

    private void setStatus(final String msg, final int permille) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                statusText.setText(msg);
                progress.setProgress(permille);
            }
        });
    }

    private void startTranscription() {
        if (audioUri == null) return;
        stopPlayback();
        cancelled = false;
        result = null;
        notes = Collections.emptyList();
        pausedAt = 0;
        analyze();
        statsText.setText("");
        setResultButtonsEnabled(false);
        playBtn.setEnabled(false);
        pickBtn.setEnabled(false);
        recordBtn.setEnabled(false);
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
                    File modelFile = ensureAsset(MODEL_ASSET);
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
                        final Transcriber.Result r = Transcriber.transcribe(feat, model, new Transcriber.Progress() {
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
                        final long elapsed = SystemClock.elapsedRealtime() - t0;
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                result = r;
                                audioSeconds = seconds;
                                finishWorker();
                                redecode();
                                updateStats(elapsed);
                                statusText.setText(R.string.status_done);
                                showPage(PAGE_PLAY, true);
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
        if (pendingRecreate && recorder == null) {
            pendingRecreate = false;
            ui.post(new Runnable() {
                @Override
                public void run() {
                    recreateKeepingState();
                }
            });
        }
        recordBtn.setEnabled(true);
        pickBtn.setEnabled(true);
        convertBtn.setEnabled(audioUri != null);
        convertBtn.setText(R.string.convert);
        progress.setVisibility(View.GONE);
    }

    // ------------------------------------------------------------ microphone

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MIC_LISTEN) {
            boolean ok = grantResults.length > 0 && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
            if (!ok) {
                waitMicBox.setChecked(false);
                Toast.makeText(this, R.string.wait_mic_permission, Toast.LENGTH_LONG).show();
            } else if (player != null && player.isPlaying() && waitBox.isChecked()) {
                startMic();
            }
            return;
        }
        if (requestCode != REQ_MIC) return;
        if (grantResults.length > 0 && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startRecording();
        } else {
            Toast.makeText(this, R.string.mic_denied, Toast.LENGTH_LONG).show();
        }
    }

    private void startRecording() {
        if (worker != null || recorder != null) return;
        stopPlayback();
        final MicRecorder rec;
        try {
            android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
            boolean unprocessed = "true".equals(am.getProperty(android.media.AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED));
            rec = new MicRecorder(MAX_SECONDS, unprocessed);
            rec.start();
        } catch (Throwable e) {
            Toast.makeText(this, getString(R.string.mic_error, e.getMessage()), Toast.LENGTH_LONG).show();
            return;
        }
        recorder = rec;
        cancelled = false;
        result = null;
        notes = Collections.emptyList();
        pausedAt = 0;
        liveHead = 0;
        liveShown = 0;
        liveNoteCount = 0;
        recordedPcm = null;
        analyze();
        audioName = getString(R.string.recording_name,
                new java.text.SimpleDateFormat("yyyy-MM-dd HH-mm", Locale.US).format(new java.util.Date()));
        fileText.setText(audioName + " — " + getString(R.string.recording_hint));
        statusText.setText(R.string.recording_starting);
        statsText.setText("");
        recordBtn.setText(R.string.record_stop);
        recordBtn.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_stop, 0, 0, 0);
        saveWavBtn.setVisibility(View.GONE);
        levelBar.setVisibility(View.VISIBLE);
        pickBtn.setEnabled(false);
        convertBtn.setEnabled(false);
        setResultButtonsEnabled(false);
        playBtn.setEnabled(false);
        falling.setLive(true);
        showPage(PAGE_PLAY, true);
        falling.setData(notes, null, null);
        if (tab == 2) selectTab(0);
        keyboard.postOnAnimation(recordTick);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                final long t0 = SystemClock.elapsedRealtime();
                try {
                    File modelFile = ensureAsset(MODEL_ASSET);
                    int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
                    OnnxModel model = new OnnxModel(modelFile.getAbsolutePath(), threads);
                    try {
                        LiveTranscriber lt = new LiveTranscriber(model, rec.sampleRate());
                        while (rec.isRunning() && !cancelled) {
                            Object[] snap = rec.snapshot();
                            if (lt.step((float[]) snap[0], (Integer) snap[1])) {
                                publishLive(lt);
                            } else {
                                Thread.sleep(40);
                            }
                        }
                        rec.stop();
                        Object[] snap = rec.snapshot();
                        final float[] pcm = (float[]) snap[0];
                        final int len = (Integer) snap[1];
                        lt.finish(pcm, len);
                        while (!cancelled) {
                            final int left = lt.remainingChunks();
                            ui.post(new Runnable() {
                                @Override
                                public void run() {
                                    statusText.setText(getString(R.string.recording_finishing, left));
                                }
                            });
                            if (!lt.finishStep()) break;
                            publishLive(lt);
                        }
                        if (cancelled) throw new InterruptedException();
                        final Transcriber.Result r = lt.result();
                        final double seconds = lt.samples16k() / (double) FeatureExtractor.SAMPLE_RATE;
                        final int rate = rec.sampleRate();
                        final long elapsed = SystemClock.elapsedRealtime() - t0;
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                endRecordingUi();
                                recordedPcm = pcm;
                                recordedLen = len;
                                recordedRate = rate;
                                saveWavBtn.setVisibility(len > 0 ? View.VISIBLE : View.GONE);
                                result = r;
                                audioSeconds = seconds;
                                finishWorker();
                                redecode();
                                updateStats(elapsed);
                                statusText.setText(R.string.status_done);
                                showPage(PAGE_PLAY, true);
                            }
                        });
                    } finally {
                        model.close();
                    }
                } catch (final Throwable e) {
                    rec.stop();
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            endRecordingUi();
                            finishWorker();
                            if (e instanceof InterruptedException) {
                                statusText.setText(R.string.status_cancelled);
                            } else {
                                String msg = e instanceof OutOfMemoryError ? getString(R.string.err_oom)
                                        : (e.getMessage() != null ? e.getMessage() : e.toString());
                                statusText.setText(getString(R.string.status_error, msg));
                            }
                        }
                    });
                }
            }
        }, "live-transcriber");
        worker.start();
    }

    /** Decodes the partial result on the worker thread and shows it. */
    private void publishLive(LiveTranscriber lt) {
        Transcriber.Result partial = lt.result();
        final List<Note> ns = NoteDecoder.decode(partial, Math.max(0.01, thr(onsetSeek)),
                Math.max(0.01, thr(offsetSeek)), Math.max(0.01, thr(frameSeek)), NoteDecoder.OffsetMode.SHORTER, true);
        PartSplitter.split(ns);
        final double head = lt.transcribedSeconds();
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (recorder == null && worker == null) return;
                notes = ns;
                liveHead = head;
                liveNoteCount = ns.size();
                falling.setData(ns, null, null);
                roll.setData(ns, null, null, head);
                roll.setPartMask(partMask);
                if (tab == 1) rollScroll.scrollTo(Math.max(0, (int) roll.xFor(head) - rollScroll.getWidth() * 2 / 3), 0);
            }
        });
    }

    private void stopRecording() {
        MicRecorder r = recorder;
        if (r == null) return;
        recordBtn.setEnabled(false);
        levelBar.setVisibility(View.GONE);
        // the worker notices, stops the device and transcribes the remaining audio
        new Thread(new Runnable() {
            @Override
            public void run() {
                MicRecorder rr = recorder;
                if (rr != null) rr.stop();
            }
        }, "mic-stop").start();
    }

    private void endRecordingUi() {
        recorder = null;
        ui.removeCallbacks(recordTick);
        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        recordBtn.setText(R.string.record);
        recordBtn.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_mic, 0, 0, 0);
        recordBtn.setEnabled(true);
        levelBar.setVisibility(View.GONE);
        falling.setLive(false);
        fileText.setText(audioName);
        for (int i = 0; i < 128; i++) activeKeys[i] = -1;
        keyboard.setActive(activeKeys);
    }

    private void saveWav(Uri uri) {
        try {
            OutputStream os = getContentResolver().openOutputStream(uri);
            if (os == null) throw new IOException("cannot open destination");
            try {
                WavWriter.write(recordedPcm, recordedLen, recordedRate, new java.io.BufferedOutputStream(os));
            } finally {
                os.close();
            }
            Toast.makeText(this, R.string.wav_saved, Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Toast.makeText(this, getString(R.string.status_error, e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------ navigation

    private static final int PAGE_HOME = 0, PAGE_PLAY = 1, PAGE_PRACTICE = 2, PAGE_EXPORT = 3, PAGE_SETTINGS = 4;

    private void setupNav() {
        pages = new View[]{findViewById(R.id.page_home), findViewById(R.id.page_play), findViewById(R.id.page_practice),
                findViewById(R.id.page_export), findViewById(R.id.page_settings)};
        navItems = new TextView[]{(TextView) findViewById(R.id.nav_home), (TextView) findViewById(R.id.nav_play),
                (TextView) findViewById(R.id.nav_practice), (TextView) findViewById(R.id.nav_export),
                (TextView) findViewById(R.id.nav_settings)};
        for (int i = 0; i < navItems.length; i++) {
            final int idx = i;
            navItems[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showPage(idx, true);
                }
            });
        }
        int[][] links = {{R.id.home_open_player, PAGE_PLAY}, {R.id.home_open_export, PAGE_EXPORT},
                {R.id.tile_practice, PAGE_PRACTICE}, {R.id.tile_sheet, PAGE_PLAY}, {R.id.tile_sounds, PAGE_SETTINGS}};
        for (final int[] l : links) {
            findViewById(l[0]).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (l[0] == R.id.tile_sheet) selectTab(2);
                    showPage(l[1], true);
                }
            });
        }
        findViewById(R.id.theme_btn2).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showThemeDialog();
            }
        });
        findViewById(R.id.about2).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                findViewById(R.id.about).performClick();
            }
        });
        showPage(prefs.getInt("page", PAGE_HOME), false);
        updateSummary();
    }

    private void showPage(int i, boolean animate) {
        if (pages == null) return;
        i = Math.max(0, Math.min(pages.length - 1, i));
        if (i == page) return;
        page = i;
        for (int k = 0; k < pages.length; k++) {
            boolean on = k == i;
            pages[k].setVisibility(on ? View.VISIBLE : View.GONE);
            navItems[k].setSelected(on);
        }
        if (animate) {
            View v = pages[i];
            v.setAlpha(0f);
            v.setTranslationY(24 * getResources().getDisplayMetrics().density);
            v.animate().alpha(1f).translationY(0).setDuration(180).start();
        }
        prefs.edit().putInt("page", i).apply();
        // views that size themselves to their page need a fresh frame
        showPosition(player != null && player.isPlaying() ? player.position() : pausedAt, false);
    }

    /** Home page summary and mini player title. */
    private void updateSummary() {
        TextView sNotes = (TextView) findViewById(R.id.stat_notes);
        if (sNotes == null) return;
        TextView sTempo = (TextView) findViewById(R.id.stat_tempo), sKey = (TextView) findViewById(R.id.stat_key);
        TextView chordsText = (TextView) findViewById(R.id.home_chords);
        TextView mini = (TextView) findViewById(R.id.mini_title);
        if (notes.isEmpty() || tempo == null || key == null) {
            sNotes.setText("–");
            sTempo.setText("–");
            sKey.setText("–");
            chordsText.setText(R.string.home_empty);
            mini.setText(result != null ? audioName : getString(R.string.mini_idle));
            return;
        }
        int[] c = new int[3];
        for (Note n : notes) c[n.part]++;
        sNotes.setText(String.valueOf(notes.size()));
        sTempo.setText(String.valueOf((int) Math.round(tempo.bpm)));
        sKey.setText(key.pcName(key.tonic) + (key.minor ? "m" : ""));
        StringBuilder sb = new StringBuilder();
        String last = null;
        int count = 0;
        for (ChordRecognizer.Chord ch : chords) {
            if (ch.root < 0 || ch.name.equals(last)) continue;
            if (count++ > 0) sb.append("  ");
            sb.append(ch.name);
            last = ch.name;
            if (count >= 12) {
                sb.append("  …");
                break;
            }
        }
        chordsText.setText(getString(R.string.home_chords, audioSeconds, c[0], c[1], c[2], sb.length() > 0 ? sb : "–"));
        mini.setText(audioName);
    }

    // ------------------------------------------------------------ loop / practice

    private void setupLoopUi() {
        loopBox = (CheckBox) findViewById(R.id.loop_on);
        practiceBox = (CheckBox) findViewById(R.id.practice_on);
        practiceCountIn = (CheckBox) findViewById(R.id.practice_countin);
        loopAText = (TextView) findViewById(R.id.loop_a);
        loopBText = (TextView) findViewById(R.id.loop_b);
        loopStatus = (TextView) findViewById(R.id.loop_status);
        practiceStartLabel = (TextView) findViewById(R.id.practice_start_label);
        practiceStartSeek = (SeekBar) findViewById(R.id.practice_start);
        practiceStepSpinner = (Spinner) findViewById(R.id.practice_step);
        practiceMuteSpinner = (Spinner) findViewById(R.id.practice_mute);
        practiceOpts = findViewById(R.id.practice_opts);
        waitBox = (CheckBox) findViewById(R.id.wait_on);
        waitMicBox = (CheckBox) findViewById(R.id.wait_mic);
        waitOpts = findViewById(R.id.wait_opts);
        playPartRow = findViewById(R.id.play_part_row);
        waitStatus = (TextView) findViewById(R.id.wait_status);
        waitMidiText = (TextView) findViewById(R.id.wait_midi);
        waitMidiText.setText(R.string.wait_midi_none);
        waitMicBox.setChecked(prefs.getBoolean("wait_mic", false));
        waitBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                waitOpts.setVisibility(on ? View.VISIBLE : View.GONE);
                updatePlayPartRow();
                if (!on) {
                    keyboard.setExpected(null);
                    waitStatus.setText("");
                    stopMic();
                } else if (player != null && player.isPlaying() && waitMicBox.isChecked()) {
                    startMic();
                }
                applyWait();
                restartIfPlaying();
            }
        });
        waitMicBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                prefs.edit().putBoolean("wait_mic", on).apply();
                if (on && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQ_MIC_LISTEN);
                }
                if (on && player != null && player.isPlaying() && waitBox.isChecked()) startMic();
                if (!on) stopMic();
                applyWait();
            }
        });
        midi = MidiInput.create(this, new MidiInput.Listener() {
            @Override
            public void onMidiNote(int pitch, int velocity, boolean on) {
                if (!on) return;
                userPressed(pitch);
            }

            @Override
            public void onMidiDevices(String names) {
                waitMidiText.setText(names.isEmpty() ? getString(R.string.wait_midi_none) : getString(R.string.wait_midi, names));
            }
        });
        setupSpinner(practiceStepSpinner, R.array.practice_steps);
        setupSpinner(practiceMuteSpinner, R.array.practice_mutes);
        practiceStepSpinner.setSelection(prefs.getInt("practice_step", 1));
        practiceMuteSpinner.setSelection(prefs.getInt("practice_mute", 0));
        practiceCountIn.setChecked(prefs.getBoolean("practice_countin", false));
        practiceStartSeek.setProgress(prefs.getInt("practice_start", 7));

        CompoundButton.OnCheckedChangeListener boxes = new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                if (b == practiceBox && on) loopBox.setChecked(true);
                if (b == loopBox && !on) practiceBox.setChecked(false);
                if (b == practiceCountIn) prefs.edit().putBoolean("practice_countin", on).apply();
                practiceOpts.setVisibility(practiceBox.isChecked() ? View.VISIBLE : View.GONE);
                updatePlayPartRow();
                applyLoop(b == practiceBox);
                if (b == practiceBox) applyWait();
            }
        };
        loopBox.setOnCheckedChangeListener(boxes);
        practiceBox.setOnCheckedChangeListener(boxes);
        practiceCountIn.setOnCheckedChangeListener(boxes);
        practiceStartSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                practiceStartLabel.setText(getString(R.string.practice_start, 25 + 5 * p));
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) { }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                prefs.edit().putInt("practice_start", sb.getProgress()).apply();
                applyLoop(false);
            }
        });
        practiceStartLabel.setText(getString(R.string.practice_start, 25 + 5 * practiceStartSeek.getProgress()));
        practiceStepSpinner.setOnItemSelectedListener(new SimpleItemListener() {
            @Override
            void onSelected(int position) {
                prefs.edit().putInt("practice_step", position).apply();
                applyLoop(false);
            }
        });
        practiceMuteSpinner.setOnItemSelectedListener(new SimpleItemListener() {
            @Override
            void onSelected(int position) {
                prefs.edit().putInt("practice_mute", position).apply();
                applyWait();
                restartIfPlaying();
            }
        });
        int[][] steppers = {{R.id.loop_a_minus, 0, -1}, {R.id.loop_a_plus, 0, 1}, {R.id.loop_b_minus, 1, -1}, {R.id.loop_b_plus, 1, 1}};
        for (final int[] st : steppers) {
            findViewById(st[0]).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (st[1] == 0) {
                        loopA += st[2];
                        if (loopA > loopB) loopB = loopA;
                    } else {
                        loopB += st[2];
                        if (loopB < loopA) loopA = loopB;
                    }
                    applyLoop(false);
                }
            });
        }
        findViewById(R.id.loop_a_here).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loopA = barAt(currentPosition());
                if (loopB < loopA) loopB = loopA;
                applyLoop(false);
            }
        });
        findViewById(R.id.loop_b_here).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loopB = barAt(currentPosition());
                if (loopA > loopB) loopA = loopB;
                applyLoop(false);
            }
        });
        practiceOpts.setVisibility(View.GONE);
    }

    private double currentPosition() {
        return player != null && player.isPlaying() ? player.position() : pausedAt;
    }

    private int barCount() {
        if (tempo == null || notes.isEmpty()) return 1;
        double end = 0;
        for (Note n : notes) end = Math.max(end, n.offset);
        return Math.max(1, (int) Math.ceil((end - tempo.origin) / tempo.barSec() - 1e-6));
    }

    private int barAt(double sec) {
        if (tempo == null) return 1;
        return Math.max(1, Math.min(barCount(), (int) Math.floor((sec - tempo.origin) / tempo.barSec()) + 1));
    }

    private double barStart(int bar) {
        return tempo.origin + (bar - 1) * tempo.barSec();
    }

    private boolean loopActive() {
        return tempo != null && !notes.isEmpty() && (loopBox.isChecked() || practiceBox.isChecked());
    }

    /** Part muted in practice mode ("you play"), or -1. */
    private int mutedPart() {
        if (!practiceBox.isChecked() && !waitBox.isChecked()) return -1;
        int p = practiceMuteSpinner.getSelectedItemPosition();
        return p <= 0 ? -1 : p - 1;
    }

    /** Parts that are audible: the checked parts minus the one you practise. */
    private int playMask() {
        if (waitBox.isChecked()) return partMask & ~waitTargetMask();
        int m = mutedPart();
        return m < 0 ? partMask : partMask & ~(1 << m);
    }

    // ------------------------------------------------------------ wait mode

    private void updatePlayPartRow() {
        playPartRow.setVisibility(practiceBox.isChecked() || waitBox.isChecked() ? View.VISIBLE : View.GONE);
    }

    /** Parts the user has to play in wait mode: the chosen part, or every enabled part. */
    private int waitTargetMask() {
        int m = mutedPart();
        return m >= 0 ? (1 << m) & partMask : partMask;
    }

    private void applyWait() {
        if (player == null || waitBox == null) return;
        boolean on = waitBox.isChecked() && !notes.isEmpty();
        player.setWait(notes, on ? waitTargetMask() : 0, on && waitMicBox.isChecked());
        if (!on) keyboard.setExpected(null);
    }

    /** A key from the on-screen keyboard, a MIDI keyboard or the microphone. */
    private void userPressed(int pitch) {
        if (player == null) return;
        lastPressed = pitch;
        if (waitBox.isChecked() && player.isPlaying()) player.press(pitch);
    }

    private void startMic() {
        if (mic != null) return;
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        try {
            android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
            boolean unprocessed = "true".equals(am.getProperty(android.media.AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED));
            mic = new MicListener(unprocessed, new com.hft.piano2midi.core.music.NoteListener.Callback() {
                @Override
                public void onNote(final int pitch) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            userPressed(pitch);
                        }
                    });
                }
            });
            mic.start();
        } catch (Throwable e) {
            mic = null;
            Toast.makeText(this, getString(R.string.mic_error, e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    private void stopMic() {
        if (mic != null) {
            mic.stop();
            mic = null;
        }
    }

    private String noteName(int p) {
        String n = key != null ? key.pcName(p % 12) : new String[]{"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"}[p % 12];
        return n + (p / 12 - 1);
    }

    /** Per frame while playing in wait mode: key hints, status, mic watch list, wrong-key flash. */
    private void updateWait(double pos) {
        if (!waitBox.isChecked() || player == null) return;
        int[] ex = player.expected();
        keyboard.setExpected(ex);
        int wrong = player.wrong();
        if (wrong > lastWrong && lastPressed >= 0) keyboard.flashWrong(lastPressed);
        lastWrong = wrong;
        if (player.waiting() && ex.length > 0) {
            StringBuilder sb = new StringBuilder();
            for (int p : ex) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(noteName(p));
            }
            waitStatus.setText(getString(R.string.wait_waiting, sb, player.correct(), wrong));
        } else {
            waitStatus.setText(getString(R.string.wait_score, player.correct(), wrong));
        }
        if (mic != null) {
            // tell the listener what to expect and which pitches our own speaker is playing
            int pm = playMask();
            boolean[] snd = new boolean[128];
            for (Note n : notes) {
                if (n.onset > pos + 0.1) break;
                if (n.offset > pos - 0.1 && (pm & (1 << n.part)) != 0) snd[n.pitch] = true;
            }
            mic.update(ex, snd);
        }
    }

    private void restartIfPlaying() {
        if (player != null && player.isPlaying()) player.play(notes, playMask(), player.position());
    }

    /** Clamps the bar range and sends loop / practice settings to the player and views. */
    private void applyLoop(boolean restart) {
        int n = barCount();
        loopA = Math.max(1, Math.min(n, loopA));
        loopB = Math.max(loopA, Math.min(n, loopB));
        loopAText.setText(String.valueOf(loopA));
        loopBText.setText(String.valueOf(loopB));
        boolean on = loopActive();
        double a = tempo != null ? barStart(loopA) : 0, b = tempo != null ? barStart(loopB + 1) : 0;
        roll.setLoop(on ? a : -1, b);
        falling.setLoop(on ? a : -1, b);
        sheet.setLoopMeasures(on ? loopA - 1 : -1, loopB - 1);
        if (player != null) {
            player.setLoop(on, a, b);
            player.setPractice(on && practiceBox.isChecked(), (25 + 5 * practiceStartSeek.getProgress()) / 100.0,
                    PRACTICE_STEPS[Math.max(0, practiceStepSpinner.getSelectedItemPosition())], speed,
                    practiceCountIn.isChecked());
        }
        loopStatus.setText(on ? getString(R.string.loop_ready, loopA, loopB, b - a) : "");
        if (restart) restartIfPlaying();
    }

    private void updateLoopStatus() {
        if (!loopActive() || player == null || !player.isPlaying()) return;
        loopStatus.setText(getString(R.string.loop_status, loopA, loopB, player.loops() + 1,
                (int) Math.round(player.currentSpeed() * 100)));
    }

    // ------------------------------------------------------------ metronome / speed

    private void setSpeed(double s) {
        speed = Math.max(0.25, Math.min(2.0, s));
        prefs.edit().putInt("speed_pct", (int) Math.round(speed * 100)).apply();
        if (player != null) player.setSpeed(speed);
        updateSpeedLabel();
        if (loopBox != null) applyLoop(false);
    }

    private void updateSpeedLabel() {
        int pct = (int) Math.round(speed * 100);
        if (player != null && player.isPlaying() && practiceBox != null && practiceBox.isChecked()) {
            pct = (int) Math.round(player.currentSpeed() * 100);
        }
        speedLabel.setText(tempo != null ? getString(R.string.speed_label, pct, (int) Math.round(tempo.bpm * speed))
                : getString(R.string.speed_label_nobpm, pct));
    }

    /** Sends the current beat grid and metronome settings to the player. */
    private void syncPlayerTiming() {
        if (player == null) return;
        if (tempo != null) player.setGrid(tempo.origin, tempo.beatSec(), tempo.beatsPerBar);
        player.setMetronome(metronomeBox.isChecked());
        player.setSpeed(speed);
    }

    private void updateBeat(double pos, boolean playing) {
        if (tempo == null) {
            beatText.setText("");
            return;
        }
        int n = tempo.beatsPerBar;
        int cur = -1;
        if (playing) {
            long k = (long) Math.floor((pos - tempo.origin) / tempo.beatSec() + 1e-6);
            cur = (int) Math.floorMod(k, (long) n);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(i == cur ? '\u25CF' : '\u25CB');
        beatText.setText(sb);
    }

    // ------------------------------------------------------------ playback

    private void togglePlay() {
        if (player == null || notes.isEmpty()) return;
        if (player.isPlaying()) {
            stopMic();
            pausedAt = Math.max(0, player.position());
            player.stop();
            setPlayIcon(false);
            showPosition(pausedAt, false);
            return;
        }
        double end = 0;
        for (Note n : notes) end = Math.max(end, n.offset);
        if (pausedAt >= end) pausedAt = 0;
        syncPlayerTiming();
        applyLoop(false);
        if (loopActive() && (pausedAt < barStart(loopA) || pausedAt >= barStart(loopB + 1))) pausedAt = barStart(loopA);
        applyWait();
        lastWrong = 0;
        if (waitBox.isChecked() && waitMicBox.isChecked()) startMic();
        player.play(notes, playMask(), pausedAt, countInBox.isChecked() && tempo != null ? 1 : 0);
        setPlayIcon(true);
        keyboard.postOnAnimation(frame);
    }

    private void seek(double sec) {
        pausedAt = sec;
        if (player != null && player.isPlaying()) {
            player.play(notes, playMask(), sec);
        } else {
            showPosition(sec, false);
        }
    }

    private void stopPlayback() {
        stopMic();
        if (keyboard != null) keyboard.setExpected(null);
        if (player != null) player.stop();
        if (playBtn != null) setPlayIcon(false);
    }

    private static String clock(double s) {
        int t = (int) Math.max(0, s);
        return String.format(Locale.US, "%d:%02d", t / 60, t % 60);
    }

    /** Updates keyboard, falling notes, roll and sheet cursor for a playback position. */
    private void showPosition(double pos, boolean follow) {
        boolean playing = player != null && player.isPlaying();
        for (int i = 0; i < 128; i++) activeKeys[i] = -1;
        if (playing) {
            int lo = 0, hi = notes.size();
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (notes.get(mid).onset <= pos) lo = mid + 1;
                else hi = mid;
            }
            for (int i = lo - 1; i >= 0; i--) {
                Note n = notes.get(i);
                if (n.onset < pos - maxNoteDur) break;
                if (n.offset > pos && (partMask & (1 << n.part)) != 0) {
                    if (activeKeys[n.pitch] < 0 || n.part == Part.MELODY) activeKeys[n.pitch] = n.part;
                }
            }
        }
        keyboard.setActive(activeKeys);
        updateBeat(pos, playing);
        timeText.setText(clock(pos) + " / " + clock(audioSeconds));
        ProgressBar mp = (ProgressBar) findViewById(R.id.mini_progress);
        if (mp != null) mp.setProgress(audioSeconds > 0 ? (int) (1000 * Math.max(0, Math.min(1, pos / audioSeconds))) : 0);
        if (tab == 0) {
            falling.setPosition(pos);
        } else if (tab == 1) {
            roll.setPlayhead(pos);
            if (follow) {
                int x = (int) roll.xFor(pos) - rollScroll.getWidth() / 3;
                rollScroll.scrollTo(Math.max(0, x), 0);
            }
        } else {
            float y = sheet.setPosition(pos);
            if (follow && y >= 0) {
                int target = (int) y - sheetScroll.getHeight() / 5;
                int cur = sheetScroll.getScrollY();
                if (Math.abs(target - cur) > sheetScroll.getHeight() / 3) sheetScroll.smoothScrollTo(0, Math.max(0, target));
            }
        }
    }

    // ------------------------------------------------------------ export

    private int exportMask() {
        switch (exportSpinner.getSelectedItemPosition()) {
            case 1: return partMask;
            case 2: return Part.mask(Part.MELODY);
            case 3: return Part.mask(Part.CHORDS);
            case 4: return Part.mask(Part.BASS);
            default: return Part.ALL;
        }
    }

    private String exportName(int mask, int format) {
        String suffix;
        if (mask == Part.ALL) suffix = "";
        else if (mask == Part.mask(Part.MELODY)) suffix = " - melody";
        else if (mask == Part.mask(Part.CHORDS)) suffix = " - chords";
        else if (mask == Part.mask(Part.BASS)) suffix = " - bass";
        else suffix = " - selection";
        if (monoExport()) suffix += " - mono";
        if (format == FMT_PDF) suffix += " - sheet";
        return safeName(audioName + suffix) + (format == FMT_PDF ? ".pdf" : ".mid");
    }

    private View.OnClickListener exportClick(final int format, final boolean share) {
        return new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int mask = exportMask();
                if (mask == 0 || PartSplitter.filter(notes, mask).isEmpty() || tempo == null) {
                    Toast.makeText(MainActivity.this, R.string.nothing_to_export, Toast.LENGTH_SHORT).show();
                    return;
                }
                if (share) {
                    share(format, mask);
                    return;
                }
                pendingFormat = format;
                pendingMask = mask;
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType(format == FMT_PDF ? "application/pdf" : "audio/midi");
                i.putExtra(Intent.EXTRA_TITLE, exportName(mask, format));
                startActivityForResult(i, REQ_SAVE);
            }
        };
    }

    private boolean monoExport() {
        return ((CheckBox) findViewById(R.id.export_mono)).isChecked();
    }

    private int monoRule() {
        return Math.max(0, ((Spinner) findViewById(R.id.export_mono_rule)).getSelectedItemPosition());
    }

    private void writeExport(int format, int mask, OutputStream os) throws IOException {
        if (monoExport()) {
            List<Note> line = Monophonic.apply(PartSplitter.filter(notes, mask), monoRule());
            if (format == FMT_PDF) {
                int lo = 127, hi = 0;
                for (Note n : line) {
                    lo = Math.min(lo, n.pitch);
                    hi = Math.max(hi, n.pitch);
                }
                boolean grand = !line.isEmpty() && hi - lo > 24 && lo < 55 && hi > 64;
                Score sc = Score.build(line, tempo, key, mask == Part.mask(Part.BASS) ? null : chords, grand,
                        audioName + " (" + getString(R.string.mono_suffix) + ")");
                PdfExporter.write(new SheetLayout(sc), musicFont, os);
            } else {
                // one track; instrument of the part the line mostly comes from
                int[] c = new int[3];
                for (Note n : line) c[n.part]++;
                int part = c[0] >= c[1] && c[0] >= c[2] ? 0 : c[1] >= c[2] ? 1 : 2;
                int program = presets[part] != null ? presets[part].program : 0;
                List<MidiWriter.Track> tracks = new ArrayList<MidiWriter.Track>();
                tracks.add(new MidiWriter.Track("Monophonic", 0, program, line));
                MidiWriter.write(tracks, tempo.bpm, tempo.beatsPerBar, tempo.origin, os);
            }
            return;
        }
        if (format == FMT_PDF) {
            String title = audioName;
            if (mask != Part.ALL) {
                List<String> ps = new ArrayList<String>();
                for (int p = 0; p < 3; p++) if ((mask & (1 << p)) != 0) ps.add(Part.NAMES[p]);
                title += " (" + android.text.TextUtils.join(", ", ps) + ")";
            }
            PdfExporter.write(new SheetLayout(buildScore(mask, title)), musicFont, os);
        } else {
            List<MidiWriter.Track> tracks = new ArrayList<MidiWriter.Track>();
            for (int p = 0; p < 3; p++) {
                if ((mask & (1 << p)) == 0) continue;
                int program = presets[p] != null ? presets[p].program : 0;
                tracks.add(new MidiWriter.Track(Part.NAMES[p], p, program, PartSplitter.filter(notes, 1 << p)));
            }
            MidiWriter.write(tracks, tempo.bpm, tempo.beatsPerBar, tempo.origin, os);
        }
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
                writeExport(pendingFormat, pendingMask, os);
            } finally {
                os.close();
            }
            Toast.makeText(this, pendingFormat == FMT_PDF ? R.string.pdf_saved : R.string.saved, Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Toast.makeText(this, getString(R.string.status_error, e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    private void share(int format, int mask) {
        try {
            File f = new File(FileShareProvider.shareDir(this), exportName(mask, format));
            FileOutputStream os = new FileOutputStream(f);
            try {
                writeExport(format, mask, os);
            } finally {
                os.close();
            }
            Uri uri = FileShareProvider.uriFor(f.getName());
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType(format == FMT_PDF ? "application/pdf" : "audio/midi");
            i.putExtra(Intent.EXTRA_STREAM, uri);
            i.setClipData(ClipData.newRawUri(f.getName(), uri));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, getString(R.string.share)));
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.status_error, e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------ lifecycle

    @Override
    protected void onStop() {
        super.onStop();
        // background apps get silence from the microphone: finish the recording instead
        if (recorder != null) stopRecording();
        if (player != null && player.isPlaying()) {
            pausedAt = player.position();
            stopPlayback();
        }
    }

    @Override
    protected void onDestroy() {
        cancelled = true;
        stopMic();
        if (midi != null) midi.close();
        if (player != null && !isChangingConfigurations()) player.release();
        super.onDestroy();
    }
}
