package app.servercontrol.ui

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

// Lenient accessors for loosely typed JSON (smartctl, kopia, occ, immich).

operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)
operator fun JsonElement?.get(i: Int): JsonElement? = (this as? JsonArray)?.getOrNull(i)

val JsonElement?.arr: List<JsonElement> get() = (this as? JsonArray) ?: emptyList()
val JsonElement?.obj: Map<String, JsonElement> get() = (this as? JsonObject) ?: emptyMap()

val JsonElement?.str: String
    get() = when (this) {
        null, JsonNull -> ""
        is JsonPrimitive -> contentOrNull ?: ""
        else -> toString()
    }
val JsonElement?.long: Long? get() = (this as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
val JsonElement?.double: Double? get() = (this as? JsonPrimitive)?.doubleOrNull
val JsonElement?.bool: Boolean? get() = (this as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.let { c -> c == "1" || c == "true" } }

fun jsonString(s: String): String = JsonPrimitive(s).toString()
