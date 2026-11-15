package com.tubepilot.llm.provider

import com.tubepilot.core.error.ProviderException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.BufferedSource
import java.io.IOException

/** Minimal Server-Sent Events reader for OkHttp responses (OpenAI and Gemini streaming). */
internal object Sse {
    /** Calls [onData] for each `data:` payload. Stops at the `[DONE]` sentinel or end of stream. */
    suspend fun read(source: BufferedSource, onData: suspend (String) -> Unit) {
        val buffer = StringBuilder()
        while (true) {
            currentCoroutineContext().ensureActive()
            val line = source.readUtf8Line() ?: break
            when {
                line.isEmpty() -> {
                    if (buffer.isNotEmpty()) {
                        val payload = buffer.toString()
                        buffer.clear()
                        if (payload == "[DONE]") return
                        onData(payload)
                    }
                }
                line.startsWith("data:") -> {
                    if (buffer.isNotEmpty()) buffer.append('\n')
                    buffer.append(line.removePrefix("data:").trimStart())
                }
                else -> Unit // event:, id:, retry: and comments are not needed here.
            }
        }
        if (buffer.isNotEmpty() && buffer.toString() != "[DONE]") onData(buffer.toString())
    }
}

/** Executes a request and maps transport/HTTP failures onto retryable or terminal errors. */
internal fun OkHttpClient.executeChecked(provider: String, request: Request): Response {
    val call: Call = newCall(request)
    val response = try {
        call.execute()
    } catch (e: IOException) {
        throw ProviderException(provider, "Network error: ${e.message}", null, retryable = true, cause = e)
    }
    if (!response.isSuccessful) {
        val body = runCatching { response.body?.string() }.getOrNull().orEmpty().take(800)
        response.close()
        val code = response.code
        throw ProviderException(
            provider,
            "HTTP $code: $body",
            code,
            retryable = code == 408 || code == 409 || code == 429 || code >= 500,
        )
    }
    return response
}
