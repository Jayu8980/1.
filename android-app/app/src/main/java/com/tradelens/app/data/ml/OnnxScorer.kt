package com.tradelens.app.data.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.tradelens.app.data.engine.EngineClient
import com.tradelens.app.data.engine.ModelMeta
import com.tradelens.app.data.net.AppJson
import com.tradelens.app.domain.Candle
import com.tradelens.app.domain.Features
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer
import javax.inject.Inject
import javax.inject.Singleton

data class MlScore(val probUp: Double, val threshold: Double, val model: String) {
    val signal get() = when { probUp >= threshold -> "LONG bias"; probUp <= 1 - threshold -> "SHORT bias"; else -> "No edge" }
}

/** Runs the engine-exported LightGBM/XGBoost model (ONNX) on-device for signal scoring. */
@Singleton
class OnnxScorer @Inject constructor(@ApplicationContext private val ctx: Context, private val engine: EngineClient) {
    private val modelFile get() = File(ctx.filesDir, "model.onnx")
    private val metaFile get() = File(ctx.filesDir, "model_meta.json")
    private var session: OrtSession? = null
    private var meta: ModelMeta? = null

    fun hasModel() = modelFile.exists() && metaFile.exists()
    fun meta(): ModelMeta? = meta ?: runCatching { AppJson.decodeFromString(ModelMeta.serializer(), metaFile.readText()) }.getOrNull()?.also { meta = it }

    suspend fun download(): ModelMeta = withContext(Dispatchers.IO) {
        val m = engine.api.modelMeta()
        engine.api.model().byteStream().use { input -> modelFile.outputStream().use { input.copyTo(it) } }
        metaFile.writeText(AppJson.encodeToString(ModelMeta.serializer(), m))
        session?.close(); session = null; meta = m
        m
    }

    suspend fun score(candles: List<Candle>): MlScore? = withContext(Dispatchers.Default) {
        if (!hasModel()) return@withContext null
        val m = meta() ?: return@withContext null
        val f = Features.compute(candles)
        if (f.isEmpty()) return@withContext null
        val env = OrtEnvironment.getEnvironment()
        val s = session ?: env.createSession(modelFile.absolutePath, OrtSession.SessionOptions()).also { session = it }
        val names = m.features.ifEmpty { Features.NAMES }
        val vec = FloatArray(names.size) { (f[names[it]] ?: 0.0).toFloat() }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(vec), longArrayOf(1, names.size.toLong())).use { t ->
            s.run(mapOf(s.inputNames.first() to t)).use { out ->
                // skl2onnx/onnxmltools classifiers output [label, probabilities]; take P(class=1).
                val probs = out.get(out.size() - 1).value
                val p = when (probs) {
                    is Array<*> -> (probs.firstOrNull() as? FloatArray)?.getOrNull(1)?.toDouble()
                        ?: ((probs.firstOrNull() as? Map<*, *>)?.get(1L) as? Float)?.toDouble()
                    is List<*> -> ((probs.firstOrNull() as? Map<*, *>)?.get(1L) as? Float)?.toDouble()
                    else -> null
                } ?: return@withContext null
                MlScore(p, m.threshold, m.name)
            }
        }
    }
}
