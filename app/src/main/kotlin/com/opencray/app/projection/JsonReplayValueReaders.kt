package com.opencray.app.projection

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun JsonObject.replayString(key: String): String? =
  (this[key] as? JsonPrimitive)
    ?.content
    ?.trim()
    ?.takeIf(String::isNotBlank)

internal fun JsonObject.replayInt(key: String): Int? =
  (this[key] as? JsonPrimitive)
    ?.content
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?.toIntOrNull()

internal fun JsonObject.replayBoolean(key: String): Boolean? =
  (this[key] as? JsonPrimitive)
    ?.content
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?.toBooleanStrictOrNull()

internal fun JsonObject.replayObject(key: String): JsonObject? =
  this[key] as? JsonObject

internal fun JsonObject.replayStringMap(key: String): Map<String, String> =
  replayObject(key)
    ?.mapNotNull { (entryKey, entryValue) ->
      (entryValue as? JsonPrimitive)
        ?.content
        ?.trim()
        ?.takeIf { value -> value.isNotBlank() }
        ?.let { value -> entryKey to value }
    }
    ?.toMap(linkedMapOf())
    .orEmpty()

internal fun JsonObject.replayObjectArray(key: String): List<JsonObject>? =
  (this[key] as? JsonArray)?.mapNotNull { entry -> entry as? JsonObject }

internal fun JsonObject.replayArraySize(key: String): Int? =
  (this[key] as? JsonArray)?.size
