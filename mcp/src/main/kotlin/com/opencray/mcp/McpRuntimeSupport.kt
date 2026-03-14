package com.opencray.mcp

object McpRuntimeSupport {
  const val BRIDGE_STATUS_EXPOSURE_ONLY: String = "exposure_only"
  const val REMOTE_TOOL_BRIDGE_AVAILABLE: Boolean = false

  val SUPPORTED_AGENT_TOOL_NAMES: Set<String> = setOf("mcp_list_servers")

  fun bridgeSummary(): String =
    "Runtime currently supports MCP exposure reporting only; remote MCP tools are not callable yet."
}
