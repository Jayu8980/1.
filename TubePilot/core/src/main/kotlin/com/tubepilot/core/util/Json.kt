package com.tubepilot.core.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** Shared JSON configuration. Lenient on input because LLM output is not always strict. */
val TubeJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
    coerceInputValues = true
    explicitNulls = false
    allowTrailingComma = true
}

val PrettyJson: Json = Json(TubeJson) { prettyPrint = true }

object JsonText {
    /**
     * Extracts the first complete JSON object or array from model output, tolerating markdown
     * fences and prose around it. Returns null when no balanced JSON value is present.
     */
    fun extract(text: String): String? {
        val start = text.indexOfFirst { it == '{' || it == '[' }
        if (start < 0) return null
        val open = text[start]
        val close = if (open == '{') '}' else ']'
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }

    fun truncate(text: String, max: Int = 4000): String =
        if (text.length <= max) text else text.take(max) + "…[${text.length - max} more chars]"
}

/** Converts a kotlinx JsonElement into plain Kotlin values (Map/List/String/Number/Boolean/null). */
fun JsonElement.toPlain(): Any? = when (this) {
    is JsonNull -> null
    is JsonObject -> entries.associate { (k, v) -> k to v.toPlain() }
    is JsonArray -> map { it.toPlain() }
    is JsonPrimitive -> when {
        isString -> content
        booleanOrNull != null -> booleanOrNull
        longOrNull != null -> longOrNull
        doubleOrNull != null -> doubleOrNull
        else -> content
    }
}

/** Converts plain Kotlin/Java values (as produced by Jackson or org.json) into a JsonElement. */
fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is Map<*, *> -> JsonObject(entries.associate { (k, v) -> k.toString() to v.toJsonElement() })
    is Iterable<*> -> JsonArray(map { it.toJsonElement() })
    is Array<*> -> JsonArray(map { it.toJsonElement() })
    is Boolean -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is CharSequence -> JsonPrimitive(toString())
    else -> JsonPrimitive(toString())
}
