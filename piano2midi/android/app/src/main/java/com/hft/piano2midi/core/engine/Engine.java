package com.hft.piano2midi.core.engine;

/**
 * The transcription engines the app can run, with the decoding parameters each one
 * starts from. Selecting an engine resets the three sliders to these defaults.
 */
public enum Engine {
    /** Sony hFT-Transformer (ISMIR 2023): piano only, bundled, supports live streaming. */
    HFT("hft", 16000, 0.5, 0.5, 0.5, true, null),
    /** Spotify Basic Pitch (ICASSP 2022): small and fast, any pitched instrument, bundled. */
    BASIC_PITCH("basic_pitch", 22050, BasicPitch.DEFAULT_ONSET, BasicPitch.DEFAULT_FRAME, 125, false, null),
    /** Google MT3, "ismir2021" checkpoint: piano with velocities. Downloaded on demand. */
    MT3_PIANO("mt3_piano", 16000, 0, 0, 0, false, new Model[]{
            new Model("mt3-piano/mt3_encoder.onnx", 26734637L,
                    "fb5bed291673db22f38ca104788cec7e36f92c91b8c4d537ec6a6dc9fc218cac"),
            new Model("mt3-piano/mt3_decoder.onnx", 28199560L,
                    "82fd2584731bda3ed055f7f686c461180394db722a1bff1e60c0a759520318e8")}),
    /** Google MT3, multi-instrument checkpoint. Downloaded on demand. */
    MT3_MULTI("mt3_multi", 16000, 0, 0, 0, false, new Model[]{
            new Model("mt3-multi/mt3_encoder.onnx", 26734637L,
                    "fa0f117c626e83212ff5e098d553d39abae966505cd7a5d954d92743bf2d4ba9"),
            new Model("mt3-multi/mt3_decoder.onnx", 28068488L,
                    "cd5b4b81a237b8baf5cebeb83b953f05dfce8591f69b6985179433502a9a2e0f")});

    /** Where downloadable models are published (files under piano2midi/models/). */
    public static final String MODEL_BASE_URL =
            "https://github.com/Jayu8980/1./raw/ccr-0beb1834-f8votw/piano2midi/models/";

    /** A downloadable model file: path relative to {@link #MODEL_BASE_URL}. */
    public static final class Model {
        public final String path;
        public final long size;
        public final String sha256;

        Model(String path, long size, String sha256) {
            this.path = path;
            this.size = size;
            this.sha256 = sha256;
        }
    }

    public final String id;
    public final int sampleRate;
    /** Default slider values (p1, p2, p3); see {@link EngineOutput#decode}. */
    public final double p1, p2, p3;
    public final boolean live;
    /** Files to download first, or null when the model is bundled in the APK. */
    public final Model[] downloads;

    Engine(String id, int sampleRate, double p1, double p2, double p3, boolean live, Model[] downloads) {
        this.id = id;
        this.sampleRate = sampleRate;
        this.p1 = p1;
        this.p2 = p2;
        this.p3 = p3;
        this.live = live;
        this.downloads = downloads;
    }

    /** True when the notes come straight out of the model (no thresholds to tune). */
    public boolean hasThresholds() {
        return this == HFT || this == BASIC_PITCH;
    }

    public long downloadSize() {
        long s = 0;
        if (downloads != null) for (Model m : downloads) s += m.size;
        return s;
    }

    public static Engine byId(String id) {
        for (Engine e : values()) if (e.id.equals(id)) return e;
        return HFT;
    }
}
