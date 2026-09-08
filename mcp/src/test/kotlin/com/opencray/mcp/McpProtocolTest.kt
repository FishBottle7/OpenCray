package com.opencray.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpProtocolTest {

  @Test
  fun InitializeParams_carriesProtocolVersionAndClientInfo() {
    val params = McpProtocol.initializeParams(McpClientInfo(name = "OpenCray", version = "2.0"))
    val objectParams = params.jsonObject

    assertEquals(MCP_PROTOCOL_VERSION, objectParams["protocolVersion"]?.jsonPrimitive?.content)
    assertEquals(
      "OpenCray",
      objectParams["clientInfo"]?.jsonObject?.get("name")?.jsonPrimitive?.content,
    )
    assertEquals(
      "2.0",
      objectParams["clientInfo"]?.jsonObject?.get("version")?.jsonPrimitive?.content,
    )
    assertTrue(objectParams.containsKey("capabilities"))
  }

  @Test
  fun InitializeParams_defaultsClientInfoToOpenCray() {
    val params = McpProtocol.initializeParams(McpConnectionManager.DEFAULT_CLIENT_INFO).jsonObject
    assertEquals("OpenCray", params["clientInfo"]?.jsonObject?.get("name")?.jsonPrimitive?.content)
  }

  @Test
  fun ToolsListParams_isNullWithoutCursor() {
    assertNull(McpProtocol.toolsListParams(null))
    val withCursor = McpProtocol.toolsListParams("page-2")
    assertEquals("page-2", withCursor?.get("cursor")?.jsonPrimitive?.content)
  }

  @Test
  fun ToolsCallParams_embedsNameAndArguments() {
    val arguments = buildJsonObject { put("query", "hello") }
    val params = McpProtocol.toolsCallParams("search_docs", arguments).jsonObject

    assertEquals("search_docs", params["name"]?.jsonPrimitive?.content)
    assertEquals(
      "hello",
      params["arguments"]?.jsonObject?.get("query")?.jsonPrimitive?.content,
    )
  }

  @Test
  fun ToolsCallParams_defaultsArgumentsToEmptyObject() {
    val params = McpProtocol.toolsCallParams("ping", null).jsonObject
    assertEquals(0, params["arguments"]?.jsonObject?.size)
  }

  @Test
  fun DecodeInitializeResult_readsServerInfoAndToolsCapability() {
    val payload = kotlinx.serialization.json.Json.parseToJsonElement(
      """
      {
        "protocolVersion": "2025-11-05",
        "serverInfo": {"name": "docs-proxy", "version": "0.3.1"},
        "capabilities": {"tools": {"listChanged": true}}
      }
      """,
    )
    val result = McpProtocol.decodeInitializeResult(payload)

    assertEquals("2025-11-05", result.protocolVersion)
    assertEquals("docs-proxy", result.serverInfo.name)
    assertEquals("0.3.1", result.serverInfo.version)
    assertTrue(result.supportsTools())
  }

  @Test
  fun DecodeInitializeResult_toleratesMissingCapabilities() {
    val payload = kotlinx.serialization.json.Json.parseToJsonElement(
      """{"protocolVersion":"1.2","serverInfo":{"name":"s","version":"0"}}""",
    )
    val result = McpProtocol.decodeInitializeResult(payload)
    assertFalse(result.supportsTools())
    assertNull(result.capabilities)
  }

  @Test
  fun DecodeToolsListResult_readsToolsAndNextCursor() {
    val payload = kotlinx.serialization.json.Json.parseToJsonElement(
      """
      {
        "tools": [
          {"name": "search", "description": "Search docs", "inputSchema": {"type": "object"}},
          {"name": "fetch"}
        ],
        "nextCursor": "cursor-2"
      }
      """,
    )
    val result = McpProtocol.decodeToolsListResult(payload)

    assertEquals(2, result.tools.size)
    assertEquals("search", result.tools[0].name)
    assertEquals("Search docs", result.tools[0].description)
    assertEquals("object", result.tools[0].inputSchema?.get("type")?.jsonPrimitive?.content)
    assertNull(result.tools[1].description)
    assertEquals("cursor-2", result.nextCursor)
  }

  @Test
  fun DecodeToolCallResult_readsTextBlocksAndErrorFlag() {
    val payload = kotlinx.serialization.json.Json.parseToJsonElement(
      """
      {
        "content": [
          {"type": "text", "text": "first"},
          {"type": "image", "data": "aGk=", "mimeType": "image/png"}
        ],
        "isError": true
      }
      """,
    )
    val result = McpProtocol.decodeToolCallResult(payload)

    assertEquals(2, result.content.size)
    assertTrue(result.isError)
    assertEquals("first", result.primaryText())
  }

  @Test
  fun ToolCallPrimaryText_fallsBackToBlockSummaryForBinaryContent() {
    val payload = kotlinx.serialization.json.Json.parseToJsonElement(
      """{"content":[{"type":"image","data":"aGk=","mimeType":"image/png"}]}""",
    )
    val result = McpProtocol.decodeToolCallResult(payload)
    assertEquals("data:image/png", result.primaryText())
  }

  @Test
  fun ToolCallPrimaryText_reportsEmptyResult() {
    val payload = kotlinx.serialization.json.Json.parseToJsonElement("""{}""")
    val result = McpProtocol.decodeToolCallResult(payload)
    assertEquals("Empty MCP tool result.", result.primaryText())
  }

  @Test
  fun EncodeToolArguments_parsesJsonStringOrNull() {
    assertNull(McpProtocol.encodeToolArguments(null))
    val arguments = McpProtocol.encodeToolArguments("""{"q":"x"}""")
    assertEquals("x", (arguments as JsonObject)["q"]?.jsonPrimitive?.content)
  }

  @Test
  fun MethodConstants_matchMcpWireNames() {
    assertEquals("initialize", McpProtocol.METHOD_INITIALIZE)
    assertEquals("notifications/initialized", McpProtocol.NOTIFICATION_INITIALIZED)
    assertEquals("tools/list", McpProtocol.METHOD_TOOLS_LIST)
    assertEquals("tools/call", McpProtocol.METHOD_TOOLS_CALL)
  }
}
