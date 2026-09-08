package com.opencray.mcp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** MCP protocol revision this client speaks on the wire. */
const val MCP_PROTOCOL_VERSION: String = "2025-11-05"

@Serializable
data class McpClientInfo(
  val name: String,
  val version: String,
)

@Serializable
data class McpServerInfo(
  val name: String,
  val version: String,
)

@Serializable
data class McpServerCapabilities(
  val tools: McpToolsCapability? = null,
)

@Serializable
data class McpToolsCapability(
  @SerialName("listChanged") val listChanged: Boolean? = null,
)

@Serializable
data class McpInitializeResult(
  @SerialName("protocolVersion") val protocolVersion: String,
  @SerialName("serverInfo") val serverInfo: McpServerInfo,
  val capabilities: McpServerCapabilities? = null,
) {
  fun supportsTools(): Boolean = capabilities?.tools != null
}

@Serializable
data class McpToolDescriptor(
  val name: String,
  val description: String? = null,
  @SerialName("inputSchema") val inputSchema: JsonObject? = null,
)

@Serializable
data class McpToolsListResult(
  val tools: List<McpToolDescriptor>,
  @SerialName("nextCursor") val nextCursor: String? = null,
)

/** Single content block in a tools/call result. */
@Serializable
data class McpToolCallContent(
  val type: String,
  val text: String? = null,
  val data: String? = null,
  val mimeType: String? = null,
)

@Serializable
data class McpToolCallResult(
  val content: List<McpToolCallContent> = emptyList(),
  @SerialName("structuredContent") val structuredContent: JsonObject? = null,
  @SerialName("isError") val isError: Boolean = false,
) {
  fun primaryText(): String = content
    .mapNotNull { it.text }
    .firstOrNull { it.isNotBlank() }
    ?: content.joinToString(separator = "\n") { block ->
      when {
        block.data != null -> "data:${block.mimeType ?: "binary"}"
        else -> "content:${block.type}"
      }
    }.ifBlank { "Empty MCP tool result." }
}

/**
 * MCP semantic codec: builds request params and decodes results for the
 * initialize / tools/list / tools/call method family.
 *
 * Learning: Malformed server payloads fail fast here with one codec boundary
 * instead of leaking JSON shape errors into the connection manager.
 */
object McpProtocol {
  const val METHOD_INITIALIZE: String = "initialize"
  const val NOTIFICATION_INITIALIZED: String = "notifications/initialized"
  const val METHOD_TOOLS_LIST: String = "tools/list"
  const val METHOD_TOOLS_CALL: String = "tools/call"

  private val json = McpJsonRpc.json

  fun initializeParams(clientInfo: McpClientInfo): JsonObject = buildJsonObject {
    put("protocolVersion", MCP_PROTOCOL_VERSION)
    put("capabilities", JsonObject(emptyMap()))
    put("clientInfo", json.encodeToJsonElement(McpClientInfo.serializer(), clientInfo))
  }

  fun toolsListParams(cursor: String?): JsonObject? = cursor?.let {
    buildJsonObject { put("cursor", it) }
  }

  fun toolsCallParams(toolName: String, arguments: JsonObject?): JsonObject = buildJsonObject {
    put("name", toolName)
    put("arguments", arguments ?: JsonObject(emptyMap()))
  }

  fun decodeInitializeResult(payload: JsonElement): McpInitializeResult =
    json.decodeFromJsonElement(McpInitializeResult.serializer(), payload)

  fun decodeToolsListResult(payload: JsonElement): McpToolsListResult =
    json.decodeFromJsonElement(McpToolsListResult.serializer(), payload)

  fun decodeToolCallResult(payload: JsonElement): McpToolCallResult =
    json.decodeFromJsonElement(McpToolCallResult.serializer(), payload)

  /** Convenience for tests and callers holding raw JSON strings. */
  fun encodeToolArguments(argumentsJson: String?): JsonObject? =
    argumentsJson?.let { json.decodeFromString(JsonObject.serializer(), it) }
}
