package com.opencray.mcp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * JSON-RPC 2.0 wire envelope shared by all MCP calls.
 *
 * Learning: Keeping the envelope layer free of MCP semantics makes protocol
 * revisions (initialize/tools methods) localizable to McpProtocol.kt.
 */
@Serializable
data class McpJsonRpcRequest(
  val jsonrpc: String = "2.0",
  val id: Long,
  val method: String,
  val params: JsonObject? = null,
)

@Serializable
data class McpJsonRpcError(
  val code: Int,
  val message: String,
  val data: JsonElement? = null,
)

@Serializable
data class McpJsonRpcResponse(
  val jsonrpc: String = "2.0",
  val id: Long? = null,
  val result: JsonElement? = null,
  val error: McpJsonRpcError? = null,
)

@Serializable
data class McpJsonRpcNotification(
  val jsonrpc: String = "2.0",
  val method: String,
  val params: JsonObject? = null,
)

object McpJsonRpc {
  const val PARSE_ERROR: Int = -32700
  const val INVALID_REQUEST: Int = -32600
  const val METHOD_NOT_FOUND: Int = -32601
  const val INVALID_PARAMS: Int = -32602
  const val INTERNAL_ERROR: Int = -32603

  @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
  val json: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
  }

  fun request(id: Long, method: String, params: JsonObject? = null): McpJsonRpcRequest =
    McpJsonRpcRequest(id = id, method = method, params = params)

  fun notification(method: String, params: JsonObject? = null): McpJsonRpcNotification =
    McpJsonRpcNotification(method = method, params = params)

  fun encodeRequest(request: McpJsonRpcRequest): String =
    json.encodeToString(McpJsonRpcRequest.serializer(), request)

  fun encodeNotification(notification: McpJsonRpcNotification): String =
    json.encodeToString(McpJsonRpcNotification.serializer(), notification)

  fun decodeResponse(text: String): McpJsonRpcResponse =
    json.decodeFromString(McpJsonRpcResponse.serializer(), text)

  fun params(vararg pairs: Pair<String, JsonElement>): JsonObject = buildJsonObject {
    pairs.forEach { (key, value) -> put(key, value) }
  }
}
