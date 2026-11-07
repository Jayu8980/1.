package com.hft.piano2midi.core.engine;

/** The transcription engines the app can run, with the decoding parameters each one starts from. */
public enum Engine {
    /** Sony hFT-Transformer (ISMIR 2023): piano, bundled, supports live streaming. */
    HFT("hft", 16000, 0.5, 0.5, 0.5, true),
    /** Kyutai x Mirelo MuScriptor medium: multi-instrument, downloaded on demand. */
    /** Kyutai x Mirelo MuScriptor medium: multi-instrument, bundled (int8 ONNX, 292 MB). */
    MUSCRIPTOR("muscriptor_medium", 16000, 0, 0, 0, false);

    public final String id;
    public final int sampleRate;
    /** Default slider values (p1, p2, p3); see {@link EngineOutput#decode}. */
    public final double p1, p2, p3;
    public final boolean live;
    Engine(String id, int sampleRate, double p1, double p2, double p3, boolean live) {
        this.id = id;
        this.sampleRate = sampleRate;
        this.p1 = p1;
        this.p2 = p2;
        this.p3 = p3;
        this.live = live;
    }

    /** True when the notes come from thresholded frame activations (adjustable). */
    public boolean hasThresholds() {
        return this == HFT;
    }

    public static Engine byId(String id) {
        for (Engine e : values()) if (e.id.equals(id)) return e;
        return HFT;
    }
}
