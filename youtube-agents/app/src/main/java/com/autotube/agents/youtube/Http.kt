package com.autotube.agents.youtube

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class HttpException(val code: Int, val body: String) : IOException("HTTP $code: ${body.take(300)}")

/** Minimal blocking HTTP helpers (call from Dispatchers.IO). */
object Http {
    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    fun form(params: Map<String, String>) = params.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }

    fun request(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        contentType: String? = null,
    ): Pair<HttpURLConnection, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 30_000
        c.readTimeout = 120_000
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (body != null) {
            c.doOutput = true
            if (contentType != null) c.setRequestProperty("Content-Type", contentType)
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
        }
        return c to readBody(c)
    }

    fun json(method: String, url: String, token: String?, body: JSONObject? = null, extra: Map<String, String> = emptyMap()): JSONObject {
        val headers = HashMap(extra)
        if (token != null) headers["Authorization"] = "Bearer $token"
        val (_, text) = request(method, url, headers, body?.toString()?.toByteArray(), "application/json; charset=UTF-8")
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    /** Streams a file as the request body (used for video upload). */
    fun putFile(url: String, token: String, file: File, contentType: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "PUT"
        c.connectTimeout = 30_000
        c.readTimeout = 600_000
        c.doOutput = true
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Content-Type", contentType)
        c.setFixedLengthStreamingMode(file.length())
        c.outputStream.use { out -> file.inputStream().use { it.copyTo(out, 256 * 1024) } }
        return readBody(c)
    }

    private fun readBody(c: HttpURLConnection): String {
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw HttpException(code, text)
        return text
    }
}
