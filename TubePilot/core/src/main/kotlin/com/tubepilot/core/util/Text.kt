package com.tubepilot.core.util

import java.util.Locale

object TextUtil {
    /** Word shingles used for near-duplicate detection between scripts. */
    fun shingles(text: String, size: Int = 3): Set<String> {
        val words = text.lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
        if (words.size < size) return words.toSet()
        return (0..words.size - size).mapTo(HashSet()) { words.subList(it, it + size).joinToString(" ") }
    }

    fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() && b.isEmpty()) return 0.0
        val inter = a.count { it in b }
        val union = a.size + b.size - inter
        return if (union == 0) 0.0 else inter.toDouble() / union
    }

    /** Splits text into sentences, keeping Devanagari danda as a terminator. */
    fun sentences(text: String): List<String> =
        text.split(Regex("(?<=[.!?।])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }

    /** UTF-8 byte length, which is how YouTube measures description limits. */
    fun utf8Length(text: String): Int = text.toByteArray(Charsets.UTF_8).size

    fun truncateUtf8(text: String, maxBytes: Int): String {
        if (utf8Length(text) <= maxBytes) return text
        var end = text.length
        while (end > 0 && utf8Length(text.substring(0, end)) > maxBytes) end--
        return text.substring(0, end)
    }

    fun slug(text: String, max: Int = 40): String =
        text.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-').take(max).ifEmpty { "video" }
}
