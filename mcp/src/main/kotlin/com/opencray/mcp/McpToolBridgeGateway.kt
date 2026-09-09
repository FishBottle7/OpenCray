package com.opencray.mcp

import kotlinx.serialization.json.JsonObject

/**
 * Runtime-facing contract for proxying agent tool calls to remote MCP servers.
 *
 * The runtime stays pure Kotlin: the app layer implements this over
 * McpConnectionManager (lazily connecting ENABLED RemoteHttp servers,
 * reusing the live connection per server) and injects it through
 * OpenCrayToolDispatcherConfig. When no gateway is injected the MCP proxy
 * tools are not registered.
 *
 * Learning: The gateway only exposes tool surface shapes; registry records,
 * credential resolution, and transport endpoints stay app-side so no
 * plaintext secret ever crosses this boundary.
 */
interface McpToolBridgeGateway {
  /** Lists discovered tools for one enabled server, connecting on first use. */
  fun listTools(serverId: String): List<McpToolDescriptor>

  /**
   * Calls one remote tool on one enabled server. The tool name is the
   * remote server's own tool name, not the mcp__ proxy name.
   */
  fun callTool(
    serverId: String,
    remoteToolName: String,
    arguments: JsonObject,
  ): McpToolBridgeResult

  /** Snapshot of per-server connection status for exposure reporting. */
  fun connectionSummaries(): List<McpConnectionSummary>
}

data class McpToolBridgeResult(
  val success: Boolean,
  val content: String,
  val errorCode: String? = null,
)

data class McpConnectionSummary(
  val serverId: String,
  val state: McpConnectionState,
  val connectedToolCount: Int,
  val lastError: String? = null,
)
