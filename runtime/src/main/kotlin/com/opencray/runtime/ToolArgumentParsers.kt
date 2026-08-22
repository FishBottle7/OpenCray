package com.opencray.runtime

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun JsonObject.requiredString(name: String): String =
  optionalString(name)?.takeIf { it.isNotBlank() }
    ?: throw IllegalArgumentException("Required argument '$name' must be a non-blank string.")

internal fun JsonObject.requiredText(name: String): String {
  val element = this[name]
    ?: throw IllegalArgumentException("Required argument '$name' must be a JSON string.")
  if (element == JsonNull) {
    throw IllegalArgumentException("Required argument '$name' must be a JSON string.")
  }
  val primitive = element as? JsonPrimitive
    ?: throw IllegalArgumentException("Argument '$name' must be a JSON string.")
  return primitive.content
}

internal fun JsonObject.requiredStringFrom(vararg names: String): String =
  optionalStringFrom(*names)?.takeIf { it.isNotBlank() }
    ?: throw IllegalArgumentException("One of ${names.joinToString(separator = ", ")} must be a non-blank string.")

internal fun JsonObject.optionalStringFrom(vararg names: String): String? =
  names.firstNotNullOfOrNull { name -> optionalString(name) }

internal fun JsonObject.optionalBooleanFrom(vararg names: String): Boolean? =
  names.firstNotNullOfOrNull { name -> optionalBoolean(name) }

internal fun JsonObject.optionalStringArrayFrom(vararg names: String): List<String> =
  names.firstNotNullOfOrNull { name ->
    this[name]?.let { optionalStringArray(name) }
  } ?: emptyList()

internal fun JsonObject.optionalString(name: String): String? {
  val element = this[name] ?: return null
  if (element == JsonNull) {
    return null
  }
  val primitive = element as? JsonPrimitive
    ?: throw IllegalArgumentException("Argument '$name' must be a JSON string.")
  return primitive.content
}

internal fun JsonObject.optionalInt(name: String): Int? {
  val element = this[name] ?: return null
  val primitive = element as? JsonPrimitive
    ?: throw IllegalArgumentException("Argument '$name' must be a JSON number.")
  return primitive.content.toIntOrNull()
    ?: throw IllegalArgumentException("Argument '$name' must be a JSON number.")
}

internal fun JsonObject.requiredInt(name: String): Int =
  optionalInt(name)
    ?: throw IllegalArgumentException("Required argument '$name' must be a JSON number.")

internal fun JsonObject.optionalLong(name: String): Long? {
  val element = this[name] ?: return null
  val primitive = element as? JsonPrimitive
    ?: throw IllegalArgumentException("Argument '$name' must be a JSON number.")
  return primitive.content.toLongOrNull()
    ?: throw IllegalArgumentException("Argument '$name' must be a JSON number.")
}

internal fun JsonObject.optionalBoolean(name: String): Boolean? {
  val element = this[name] ?: return null
  if (element == JsonNull) {
    return null
  }
  val primitive = element as? JsonPrimitive
    ?: throw IllegalArgumentException("Argument '$name' must be a JSON boolean.")
  return when (primitive.content.trim().lowercase()) {
    "true" -> true
    "false" -> false
    else -> throw IllegalArgumentException("Argument '$name' must be a JSON boolean.")
  }
}

internal fun JsonObject.optionalStringArray(name: String): List<String> {
  val element = this[name] ?: return emptyList()
  val array = element as? JsonArray
    ?: throw IllegalArgumentException("Argument '$name' must be a JSON array of strings.")
  return array.mapIndexed { index, entry ->
    val primitive = entry as? JsonPrimitive
      ?: throw IllegalArgumentException("Argument '$name' item $index must be a JSON string.")
    primitive.content
  }
}

internal fun JsonObject.optionalIntArray(name: String): List<Int> {
  val element = this[name] ?: return emptyList()
  if (element == JsonNull) {
    return emptyList()
  }
  val array = element as? JsonArray
    ?: throw IllegalArgumentException("Argument '$name' must be a JSON array of numbers.")
  return array.mapIndexed { index, entry ->
    val primitive = entry as? JsonPrimitive
      ?: throw IllegalArgumentException("Argument '$name' item $index must be a JSON number.")
    primitive.content.toIntOrNull()
      ?: throw IllegalArgumentException("Argument '$name' item $index must be a JSON number.")
  }
}

internal fun JsonObject.optionalObject(name: String): JsonObject? {
  val element = this[name] ?: return null
  if (element == JsonNull) {
    return null
  }
  return element as? JsonObject
    ?: throw IllegalArgumentException("Argument '$name' must be a JSON object.")
}

internal fun JsonObject.optionalObjectFrom(vararg names: String): JsonObject? =
  names.firstNotNullOfOrNull { name -> optionalObject(name) }

internal fun JsonObject.optionalObjectArray(name: String): List<JsonObject>? {
  val element = this[name] ?: return null
  if (element == JsonNull) {
    return null
  }
  val array = element as? JsonArray
    ?: throw IllegalArgumentException("Argument '$name' must be a JSON array of objects.")
  return array.mapIndexed { index, entry ->
    entry as? JsonObject
      ?: throw IllegalArgumentException("Argument '$name' item $index must be a JSON object.")
  }
}

internal fun JsonObject.requiredObjectArray(name: String): List<JsonObject> =
  optionalObjectArray(name)
    ?: throw IllegalArgumentException("Required argument '$name' must be a JSON array of objects.")
