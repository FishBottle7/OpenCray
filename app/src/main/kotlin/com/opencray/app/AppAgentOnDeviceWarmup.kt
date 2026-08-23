package com.opencray.app

import com.opencray.core.contracts.AgentTask
import com.opencray.llm.LiteLlmBuiltinToolDefinition
import com.opencray.runtime.AgentToolDefinition
import com.opencray.runtime.AgentToolParameter
import java.net.URI
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun visibleWarmupToolDefinitions(
  allDefinitions: List<AgentToolDefinition>,
  llmMetadata: Map<String, String>,
  memoryToolsEnabled: Boolean,
): List<AgentToolDefinition> {
  if (llmMetadata["onDeviceLiteModeEnabled"]?.trim()?.lowercase() == "true") {
    return emptyList()
  }
  val memoryAwareDefinitions = if (memoryToolsEnabled) {
    allDefinitions
  } else {
    allDefinitions.filterNot { definition ->
      definition.name.equals("memory_search", ignoreCase = true) ||
        definition.name.equals("memory_get", ignoreCase = true)
    }
  }
  val webSearchEnabled = llmMetadata["webSearchEnabled"]
    ?.trim()
    ?.lowercase()
    ?.let { rawValue ->
      when (rawValue) {
        "true" -> true
        "false" -> false
        else -> null
      }
    } ?: true
  return if (webSearchEnabled) {
    memoryAwareDefinitions
  } else {
    memoryAwareDefinitions.filterNot { definition ->
      definition.name.equals("WebSearch", ignoreCase = true)
    }
  }
}

internal fun builtinToolsForWarmup(
  visibleToolDefinitions: List<AgentToolDefinition>,
  llmMetadata: Map<String, String>,
): List<LiteLlmBuiltinToolDefinition> {
  val nativeProviderWebSearchEnabled = llmMetadata["nativeWebSearchEnabled"]
    ?.trim()
    ?.lowercase()
    ?.let { rawValue ->
      when (rawValue) {
        "true" -> true
        "false" -> false
        else -> null
      }
    } ?: (
      llmMetadata["protocol"]?.trim()?.lowercase() == LlmProviderProtocols.OPENAI_RESPONSES &&
        officialOpenAiRouteForHostMetadata(llmMetadata)
      )
  if (!nativeProviderWebSearchEnabled) {
    return emptyList()
  }
  val hostWebSearchVisible = visibleToolDefinitions.any { definition ->
    definition.name.equals("WebSearch", ignoreCase = true)
  }
  if (!hostWebSearchVisible) {
    return emptyList()
  }
  return listOf(
    LiteLlmBuiltinToolDefinition(
      type = com.opencray.llm.LiteLlmBuiltinToolType.WEB_SEARCH,
      includeSources = true,
    ),
  )
}

private fun officialOpenAiRouteForHostMetadata(llmMetadata: Map<String, String>): Boolean {
  val hostBaseUrl = llmMetadata[AppAgentSessionTaskRuntimeFactory.HOST_METADATA_BASE_URL]
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?: return false
  val host = runCatching {
    URI(hostBaseUrl).host.orEmpty().lowercase()
  }.getOrDefault("")
  return host == "api.openai.com" || host.endsWith(".openai.com")
}

internal fun functionToolDefinitionsForWarmup(
  visibleToolDefinitions: List<AgentToolDefinition>,
  builtinTools: List<LiteLlmBuiltinToolDefinition>,
  llmMetadata: Map<String, String>,
): List<AgentToolDefinition> {
  val dualExposeWebSearchEnabled = llmMetadata["dualExposeWebSearch"]
    ?.trim()
    ?.lowercase() == "true"
  val hidesHostWebSearch = builtinTools.any { tool ->
    tool.type == com.opencray.llm.LiteLlmBuiltinToolType.WEB_SEARCH
  } && !dualExposeWebSearchEnabled
  return if (hidesHostWebSearch) {
    visibleToolDefinitions.filterNot { definition ->
      definition.name.equals("WebSearch", ignoreCase = true)
    }
  } else {
    visibleToolDefinitions
  }
}

internal fun onDeviceWarmupTask(sessionId: String): AgentTask {
  val createdAtEpochMs = System.currentTimeMillis()
  return AgentTask(
    id = "warmup-$sessionId",
    type = com.opencray.core.contracts.AgentTaskType.PROMPT,
    input = "Prime the on-device model cache.",
    createdAtEpochMs = createdAtEpochMs,
    updatedAtEpochMs = createdAtEpochMs,
    metadata = mapOf(AppAgentSessionTaskRuntimeFactory.METADATA_HOST_SESSION_ID to sessionId),
  )
}

internal fun AgentToolDefinition.toWarmupLiteLlmToolDefinition(
  strict: Boolean,
): com.opencray.llm.LiteLlmToolDefinition = com.opencray.llm.LiteLlmToolDefinition(
  name = name,
  description = description,
  inputSchema = toWarmupJsonSchema(strict = strict),
  strict = strict.takeIf { it },
)

private fun AgentToolDefinition.toWarmupJsonSchema(
  strict: Boolean = false,
): JsonObject = buildJsonObject {
  put("type", "object")
  put(
    "properties",
    buildJsonObject {
      parameters.forEach { parameter ->
        put(
          parameter.name,
          parameter.toWarmupJsonSchemaProperty(
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

private fun AgentToolParameter.toWarmupJsonSchemaProperty(
  strict: Boolean = false,
  nullable: Boolean = false,
): JsonObject {
  val baseSchema = jsonSchema ?: buildJsonObject {
    when (type.trim().lowercase()) {
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
  return strictCompatibleWarmupToolSchema(
    schema = baseSchema,
    nullable = nullable,
  )
}

private fun strictCompatibleWarmupToolSchema(
  schema: JsonObject,
  nullable: Boolean = false,
): JsonObject {
  val normalized = normalizeWarmupToolSchemaForStrictMode(schema)
  return if (nullable) {
    allowNullInWarmupToolSchema(normalized)
  } else {
    normalized
  }
}

private fun normalizeWarmupToolSchemaForStrictMode(
  schema: JsonObject,
): JsonObject {
  val normalizedEntries = schema.mapValues { (key, value) ->
    when {
      key == "properties" && value is JsonObject ->
        normalizeWarmupToolSchemaProperties(value, schema)
      key in strictWarmupToolSchemaChildArrayKeys && value is JsonArray -> JsonArray(
        value.map { item ->
          if (item is JsonObject) {
            normalizeWarmupToolSchemaForStrictMode(item)
          } else {
            item
          }
        },
      )
      key == "items" && value is JsonObject -> normalizeWarmupToolSchemaForStrictMode(value)
      else -> value
    }
  }.toMutableMap()
  if (warmupToolSchemaTypeNames(schema).contains("object")) {
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

private fun normalizeWarmupToolSchemaProperties(
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
      put(
        propertyName,
        strictCompatibleWarmupToolSchema(
          schema = propertySchema,
          nullable = propertyName !in requiredPropertyNames,
        ),
      )
    }
  }
}

private fun allowNullInWarmupToolSchema(
  schema: JsonObject,
): JsonObject {
  if (warmupToolSchemaAllowsNull(schema)) {
    return schema
  }
  val mutableEntries = schema.toMutableMap()
  when (val typeValue = schema["type"]) {
    is JsonPrimitive -> {
      mutableEntries["type"] = JsonArray(listOf(typeValue, JsonPrimitive("null")))
    }

    is JsonArray -> {
      val normalizedTypes = typeValue
        .mapNotNull { item -> (item as? JsonPrimitive)?.content }
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

private fun warmupToolSchemaAllowsNull(schema: JsonObject): Boolean {
  if ("null" in warmupToolSchemaTypeNames(schema)) {
    return true
  }
  return (schema["anyOf"] as? JsonArray)
    ?.any { option ->
      (option as? JsonObject)?.let(::warmupToolSchemaAllowsNull) == true
    } == true
}

private fun warmupToolSchemaTypeNames(
  schema: JsonObject,
): Set<String> = when (val typeValue = schema["type"]) {
  is JsonPrimitive -> setOf(typeValue.content)
  is JsonArray -> typeValue.mapNotNull { item -> (item as? JsonPrimitive)?.content }.toSet()
  else -> emptySet()
}

private val strictWarmupToolSchemaChildArrayKeys: Set<String> = setOf("anyOf", "allOf", "oneOf")
