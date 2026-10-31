package com.autotube.agents.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.autotube.agents.youtube.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

/** Optional royalty-free imagery from Pexels (free API key). */
class StockImages(private val apiKey: String) {
    suspend fun find(query: String, portrait: Boolean): Bitmap? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || query.isBlank()) return@withContext null
        try {
            val url = "https://api.pexels.com/v1/search?per_page=1&orientation=${if (portrait) "portrait" else "landscape"}&query=${Http.enc(query)}"
            val (_, body) = Http.request("GET", url, mapOf("Authorization" to apiKey))
            val photo = JSONObject(body).optJSONArray("photos")?.optJSONObject(0) ?: return@withContext null
            val src = photo.getJSONObject("src")
            val imgUrl = src.optString(if (portrait) "portrait" else "landscape").ifBlank { src.optString("large") }
            val bytes = URL(imgUrl).openStream().use { it.readBytes() }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 1000 && bounds.outHeight / (sample * 2) >= 1000) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (e: Exception) { null }
    }
}
