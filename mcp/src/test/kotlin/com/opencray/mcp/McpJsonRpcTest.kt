package com.opencray.mcp

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpJsonRpcTest {

  @Test
  fun RequestEncoding_roundTripsWithParams() {
    val params = buildJsonObject { put("name", "search") }
    val body = McpJsonRpc.encodeRequest(McpJsonRpc.request(id = 7L, method = "tools/call", params = params))
    val decoded = McpJsonRpc.json.parseToJsonElement(body).jsonObject

    assertEquals("2.0", decoded["jsonrpc"]?.jsonPrimitive?.content)
    assertEquals("tools/call", decoded["method"]?.jsonPrimitive?.content)
    assertEquals(7L, decoded["id"]?.jsonPrimitive?.content?.toLong())
    assertEquals("search", decoded["params"]?.jsonObject?.get("name")?.jsonPrimitive?.content)
  }

  @Test
  fun RequestEncoding_omitsNullParams() {
    val body = McpJsonRpc.encodeRequest(McpJsonRpc.request(id = 1L, method = "tools/list"))
    assertFalse(body.contains("params"))
  }

  @Test
  fun NotificationEncoding_hasNoId() {
    val body = McpJsonRpc.encodeNotification(McpJsonRpc.notification("notifications/initialized"))
    val decoded = McpJsonRpc.json.parseToJsonElement(body).jsonObject

    assertTrue(decoded.containsKey("method"))
    assertFalse(decoded.containsKey("id"))
  }

  @Test
  fun ResponseDecoding_passesThroughResult() {
    val response = McpJsonRpc.decodeResponse(
      """{"jsonrpc":"2.0","id":3,"result":{"tools":[]}}""",
    )
    assertEquals(3L, response.id)
    assertNull(response.error)
    assertTrue(response.result.toString().contains("\"tools\""))
  }

  @Test
  fun ResponseDecoding_readsErrorCodeMessageAndData() {
    val response = McpJsonRpc.decodeResponse(
      """{"jsonrpc":"2.0","id":4,"error":{"code":-32601,"message":"Method not found","data":{"hint":"x"}}}""",
    )
    assertEquals(-32601, response.error?.code)
    assertEquals("Method not found", response.error?.message)
    assertTrue(response.error?.data.toString()?.contains("hint") == true)
  }

  @Test
  fun ResponseDecoding_ignoresUnknownKeys() {
    val response = McpJsonRpc.decodeResponse(
      """{"jsonrpc":"2.0","id":9,"result":{},"extra":"ignored"}""",
    )
    assertEquals(9L, response.id)
    assertNull(response.error)
  }

  @Test
  fun ErrorCodes_matchJsonRpcSpecification() {
    assertEquals(-32700, McpJsonRpc.PARSE_ERROR)
    assertEquals(-32600, McpJsonRpc.INVALID_REQUEST)
    assertEquals(-32601, McpJsonRpc.METHOD_NOT_FOUND)
    assertEquals(-32602, McpJsonRpc.INVALID_PARAMS)
    assertEquals(-32603, McpJsonRpc.INTERNAL_ERROR)
  }

  @Test
  fun ParamsBuilder_buildsJsonObject() {
    val params = McpJsonRpc.params(
      "protocolVersion" to kotlinx.serialization.json.JsonPrimitive("2025-11-05"),
    )
    assertEquals("2025-11-05", params["protocolVersion"]?.jsonPrimitive?.content)
  }
}
