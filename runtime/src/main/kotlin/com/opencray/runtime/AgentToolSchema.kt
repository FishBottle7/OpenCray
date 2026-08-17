package com.opencray.runtime

import com.opencray.llm.LiteLlmToolDefinition
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun AgentToolDefinition.toJsonSchema(strict: Boolean = false): JsonObject = buildJsonObject {
  put("type", "object")
  put(
    "properties",
    buildJsonObject {
      parameters.forEach { parameter ->
        put(
          parameter.name,
          parameter.toJsonSchemaProperty(
            strict = strict,
            nullable = strict && !parameter.required,
          ),
        )
      }
    },
  )
  val requiredParameters = if (strict) {
    parameters.map(AgentToolParameter::name)
  } else {
    parameters.filter(AgentToolParameter::required).map(AgentToolParameter::name)
  }
  if (strict || requiredParameters.isNotEmpty()) {
    put(
      "required",
      buildJsonArray {
        requiredParameters.forEach { requiredParameter ->
          add(JsonPrimitive(requiredParameter))
        }
      },
    )
  }
  put("additionalProperties", false)
}

internal fun AgentToolDefinition.toLiteLlmToolDefinition(strict: Boolean = false): LiteLlmToolDefinition =
  LiteLlmToolDefinition(
    name = name,
    description = description,
    inputSchema = toJsonSchema(strict = strict),
    strict = strict.takeIf { it },
  )

private fun AgentToolParameter.toJsonSchemaProperty(
  strict: Boolean = false,
  nullable: Boolean = false,
): JsonObject {
  val baseSchema = jsonSchema ?: buildJsonObject {
    when (type.trim().lowercase(Locale.ROOT)) {
      "string" -> put("type", "string")
      "number" -> put("type", "number")
      "boolean" -> put("type", "boolean")
      "string[]" -> {
        put("type", "array")
        put(
          "items",
          buildJsonObject {
            put("type", "string")
          },
        )
      }

      "object[]" -> {
        put("type", "array")
        put(
          "items",
          buildJsonObject {
            put("type", "object")
          },
        )
      }

      else -> put("type", "string")
    }
    put("description", description)
  }
  if (!strict) {
    return baseSchema
  }
  return strictCompatibleToolSchema(
    schema = baseSchema,
    nullable = nullable,
  )
}

internal fun multiEditArraySchema(description: String): JsonObject = objectArraySchema(
  description = description,
  itemParameters = listOf(
    AgentToolParameter(
      name = "old_string",
      type = "string",
      required = true,
      description = "Exact text to replace.",
    ),
    AgentToolParameter(
      name = "new_string",
      type = "string",
      required = true,
      description = "Replacement text.",
    ),
    AgentToolParameter(
      name = "replace_all",
      type = "boolean",
      required = false,
      description = "Replace every match instead of requiring a unique match.",
    ),
  ),
)

internal fun todoEntryArraySchema(description: String): JsonObject = objectArraySchema(
  description = description,
  itemParameters = listOf(
    AgentToolParameter(
      name = "content",
      type = "string",
      required = true,
      description = "User-visible todo text.",
    ),
    AgentToolParameter(
      name = "status",
      type = "string",
      required = true,
      description = "Todo lifecycle state.",
      jsonSchema = buildJsonObject {
        put("type", "string")
        put("description", "Todo lifecycle state. Supported values: pending, in_progress, completed.")
        put(
          "enum",
          buildJsonArray {
            add(JsonPrimitive("pending"))
            add(JsonPrimitive("in_progress"))
            add(JsonPrimitive("completed"))
          },
        )
      },
    ),
    AgentToolParameter(
      name = "activeForm",
      type = "string",
      required = false,
      description = "Optional present-progress phrasing shown while the todo is active.",
    ),
  ),
)

internal fun scheduledTaskTriggerSchema(): JsonObject = buildJsonObject {
  put("type", "object")
  put(
    "description",
    "Use exactly one trigger form: at for one absolute time, after for one relative delay, or start_at plus rrule for recurrence.",
  )
  put(
    "properties",
    buildJsonObject {
      put(
        "at",
        buildJsonObject {
          put("type", "string")
          put(
            "description",
            "One absolute run time as an ISO-8601 date-time with offset, for example 2026-04-11T21:00:00+08:00.",
          )
        },
      )
      put(
        "after",
        buildJsonObject {
          put("type", "string")
          put(
            "description",
            "One relative delay as an ISO-8601 duration, for example PT2H or P1D.",
          )
        },
      )
      put(
        "start_at",
        buildJsonObject {
          put("type", "string")
          put(
            "description",
            "Recurrence anchor time as an ISO-8601 date-time. Include an offset, or pair a local date-time with timezone.",
          )
        },
      )
      put(
        "timezone",
        buildJsonObject {
          put("type", "string")
          put(
            "description",
            "Optional recurrence timezone such as Asia/Shanghai. Required when start_at does not already include an offset or timezone.",
          )
        },
      )
      put(
        "rrule",
        buildJsonObject {
          put("type", "string")
          put(
            "description",
            "RFC5545-style recurrence rule, for example FREQ=WEEKLY;BYDAY=MO,TU or FREQ=MONTHLY;BYMONTHDAY=1.",
          )
        },
      )
      put(
        "exdates",
        buildJsonObject {
          put("type", "array")
          put("description", "Optional ISO-8601 date-times to skip from the recurrence set.")
          put(
            "items",
            buildJsonObject {
              put("type", "string")
            },
          )
        },
      )
      put(
        "rdates",
        buildJsonObject {
          put("type", "array")
          put("description", "Optional ISO-8601 date-times to add to the recurrence set.")
          put(
            "items",
            buildJsonObject {
              put("type", "string")
            },
          )
        },
      )
    },
  )
  put("additionalProperties", false)
}

private fun objectArraySchema(
  description: String,
  itemParameters: List<AgentToolParameter>,
): JsonObject = buildJsonObject {
  put("type", "array")
  put("description", description)
  put(
    "items",
    buildJsonObject {
      put("type", "object")
      put(
        "properties",
        buildJsonObject {
          itemParameters.forEach { parameter ->
            put(parameter.name, parameter.toJsonSchemaProperty())
          }
        },
      )
      itemParameters
        .filter(AgentToolParameter::required)
        .map(AgentToolParameter::name)
        .takeIf(List<String>::isNotEmpty)
        ?.let { requiredParameters ->
          put(
            "required",
            buildJsonArray {
              requiredParameters.forEach { requiredParameter ->
                add(JsonPrimitive(requiredParameter))
              }
            },
          )
        }
      put("additionalProperties", false)
    },
  )
}

private fun strictCompatibleToolSchema(
  schema: JsonObject,
  nullable: Boolean = false,
): JsonObject {
  val normalized = normalizeToolSchemaForStrictMode(schema)
  return if (nullable) {
    allowNullInToolSchema(normalized)
  } else {
    normalized
  }
}

private fun normalizeToolSchemaForStrictMode(
  schema: JsonObject,
): JsonObject {
  val normalizedEntries = schema.mapValues { (key, value) ->
    when {
      key == "properties" && value is JsonObject -> normalizeToolSchemaProperties(value, schema)
      key in STRICT_TOOL_SCHEMA_CHILD_ARRAY_KEYS && value is JsonArray -> JsonArray(
        value.map { item ->
          if (item is JsonObject) {
            normalizeToolSchemaForStrictMode(item)
          } else {
            item
          }
        },
      )
      key == "items" && value is JsonObject -> normalizeToolSchemaForStrictMode(value)
      else -> value
    }
  }.toMutableMap()
  if (toolSchemaTypeNames(schema).contains("object")) {
    if ("additionalProperties" !in normalizedEntries) {
      normalizedEntries["additionalProperties"] = JsonPrimitive(false)
    }
    val propertyNames = (normalizedEntries["properties"] as? JsonObject)
      ?.keys
      ?.toList()
      .orEmpty()
    normalizedEntries["required"] = JsonArray(propertyNames.map(::JsonPrimitive))
  }
  return JsonObject(normalizedEntries)
}

private fun normalizeToolSchemaProperties(
  properties: JsonObject,
  ownerSchema: JsonObject,
): JsonObject {
  val requiredPropertyNames = (ownerSchema["required"] as? JsonArray)
    ?.mapNotNull { item -> (item as? JsonPrimitive)?.content }
    ?.toSet()
    .orEmpty()
  return buildJsonObject {
    properties.forEach { (propertyName, propertyValue) ->
      val propertySchema = propertyValue as? JsonObject ?: return@forEach
      val normalizedProperty = strictCompatibleToolSchema(
        schema = propertySchema,
        nullable = propertyName !in requiredPropertyNames,
      )
      put(propertyName, normalizedProperty)
    }
    if (properties.isEmpty()) {
      return@buildJsonObject
    }
  }
}

private fun allowNullInToolSchema(
  schema: JsonObject,
): JsonObject {
  if (toolSchemaAllowsNull(schema)) {
    return schema
  }
  val mutableEntries = schema.toMutableMap()
  when (val typeValue = schema["type"]) {
    is JsonPrimitive -> {
      mutableEntries["type"] = JsonArray(listOf(typeValue, JsonPrimitive("null")))
    }

    is JsonArray -> {
      val normalizedTypes = typeValue
        .mapNotNull { item -> item as? JsonPrimitive }
        .map(JsonPrimitive::content)
        .toMutableList()
      if ("null" !in normalizedTypes) {
        normalizedTypes += "null"
      }
      mutableEntries["type"] = JsonArray(normalizedTypes.map(::JsonPrimitive))
    }

    else -> {
      val anyOf = buildJsonArray {
        (schema["anyOf"] as? JsonArray)?.forEach(::add) ?: add(schema)
        add(buildJsonObject { put("type", "null") })
      }
      return buildJsonObject {
        schema["description"]?.let { description -> put("description", description) }
        put("anyOf", JsonArray(anyOf))
      }
    }
  }
  (schema["enum"] as? JsonArray)?.let { enumValues ->
    if (enumValues.none { item -> item is JsonNull }) {
      mutableEntries["enum"] = JsonArray(enumValues + JsonNull)
    }
  }
  return JsonObject(mutableEntries)
}

private fun toolSchemaAllowsNull(schema: JsonObject): Boolean {
  if ("null" in toolSchemaTypeNames(schema)) {
    return true
  }
  return (schema["anyOf"] as? JsonArray)
    ?.any { option ->
      (option as? JsonObject)?.let(::toolSchemaAllowsNull) == true
    } == true
}

private fun toolSchemaTypeNames(
  schema: JsonObject,
): Set<String> = when (val typeValue = schema["type"]) {
  is JsonPrimitive -> setOf(typeValue.content)
  is JsonArray -> typeValue.mapNotNull { item -> (item as? JsonPrimitive)?.content }.toSet()
  else -> emptySet()
}

private val STRICT_TOOL_SCHEMA_CHILD_ARRAY_KEYS: Set<String> = setOf("anyOf", "allOf", "oneOf")
