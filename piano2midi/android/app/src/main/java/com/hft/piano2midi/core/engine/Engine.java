package com.hft.piano2midi.core.engine;

/** The transcription engines the app can run, with the decoding parameters each one starts from. */
public enum Engine {
    /** Sony hFT-Transformer (ISMIR 2023): piano, bundled, supports live streaming. */
    HFT("hft", 16000, 0.5, 0.5, 0.5, true, null),
    /** Kyutai x Mirelo MuScriptor medium: multi-instrument, downloaded on demand. */
    MUSCRIPTOR("muscriptor_medium", 16000, 0, 0, 0, false, new Model[]{
            new Model("muscriptor-medium/muscriptor.onnx", 306018593L,
                    "526840874517f8f4c5fca337385a55f5cff97e7c1b984fd870cb8a25bd6aaec2", 4),
            new Model("muscriptor-medium/ms_frontend.bin", 2107392L,
                    "5cae3ba75b866a6c94af66e836dfe74a63459084e0e0acbd41cefa8d205d4211", 1)});

    /** Where downloadable models are published (files under piano2midi/models/). */
    public static final String MODEL_BASE_URL =
            "https://github.com/Jayu8980/1./raw/ccr-0beb1834-f8votw/piano2midi/models/";

    /**
     * A downloadable model file: path relative to {@link #MODEL_BASE_URL}. Files over GitHub's
     * size limit are stored in {@code parts} pieces named path.00, path.01, ... and joined.
     */
    public static final class Model {
        public final String path;
        public final long size;
        public final String sha256;
        public final int parts;

        Model(String path, long size, String sha256, int parts) {
            this.path = path;
            this.size = size;
            this.sha256 = sha256;
            this.parts = parts;
        }

        public String partUrl(int i) {
            return MODEL_BASE_URL + (parts == 1 ? path : path + String.format(java.util.Locale.US, ".%02d", i));
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

    /** True when the notes come from thresholded frame activations (adjustable). */
    public boolean hasThresholds() {
        return this == HFT;
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
