package com.hft.piano2midi.core;

import java.nio.FloatBuffer;
import java.util.Collections;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

/** hFT-Transformer forward pass through ONNX Runtime (works on Android and desktop JVMs). */
public final class OnnxModel implements Transcriber.Model, AutoCloseable {

    private final OrtEnvironment env;
    private final OrtSession session;
    private final long[] shape = {1, FeatureExtractor.N_MELS, Transcriber.WINDOW};

    public OnnxModel(String modelPath, int threads) throws OrtException {
        env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        if (threads > 0) opts.setIntraOpNumThreads(threads);
        session = env.createSession(modelPath, opts);
    }

    @Override
    public void run(float[] spec, float[] onset, float[] offset, float[] mpe, int[] velocity) throws OrtException {
        OnnxTensor input = OnnxTensor.createTensor(env, FloatBuffer.wrap(spec), shape);
        try {
            Map<String, OnnxTensor> feed = Collections.singletonMap("spec", input);
            OrtSession.Result out = session.run(feed);
            try {
                ((OnnxTensor) out.get("onset").get()).getFloatBuffer().get(onset);
                ((OnnxTensor) out.get("offset").get()).getFloatBuffer().get(offset);
                ((OnnxTensor) out.get("mpe").get()).getFloatBuffer().get(mpe);
                java.nio.LongBuffer v = ((OnnxTensor) out.get("velocity").get()).getLongBuffer();
                for (int i = 0; i < velocity.length; i++) velocity[i] = (int) v.get(i);
            } finally {
                out.close();
            }
        } finally {
            input.close();
        }
    }

    @Override
    public void close() throws OrtException {
        session.close();
    }
}
