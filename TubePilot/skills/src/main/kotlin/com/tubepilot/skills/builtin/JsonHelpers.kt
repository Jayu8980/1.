package com.tubepilot.skills.builtin

import com.tubepilot.core.util.TubeJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

internal fun JsonObject.str(key: String, default: String = ""): String =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: default

internal fun JsonObject.long(key: String, default: Long): Long = (this[key] as? JsonPrimitive)?.longOrNull
    ?: (this[key] as? JsonPrimitive)?.doubleOrNull?.toLong() ?: default

internal fun JsonObject.int(key: String, default: Int): Int = long(key, default.toLong()).toInt()

internal fun JsonObject.double(key: String, default: Double): Double = (this[key] as? JsonPrimitive)?.doubleOrNull ?: default

internal fun JsonObject.bool(key: String, default: Boolean): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: default

internal fun JsonObject.strings(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.takeIf { s -> s.isNotBlank() } }.orEmpty()

internal fun JsonObject.objects(key: String): List<JsonObject> = (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

internal fun <T> T.toJsonObject(serializer: KSerializer<T>): JsonObject = TubeJson.encodeToJsonElement(serializer, this).jsonObject

internal fun <T> JsonElement.decodeAs(serializer: KSerializer<T>): T = TubeJson.decodeFromJsonElement(serializer, this)

internal fun <T> List<T>.toJsonArray(serializer: KSerializer<T>): JsonArray = JsonArray(map { TubeJson.encodeToJsonElement(serializer, it) })

internal fun jsonOf(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(
    pairs.associate { (k, v) ->
        k to when (v) {
            null -> JsonNull
            is JsonElement -> v
            is String -> JsonPrimitive(v)
            is Number -> JsonPrimitive(v)
            is Boolean -> JsonPrimitive(v)
            is List<*> -> JsonArray(v.map { item -> if (item is JsonElement) item else JsonPrimitive(item?.toString()) })
            else -> JsonPrimitive(v.toString())
        }
    },
)
