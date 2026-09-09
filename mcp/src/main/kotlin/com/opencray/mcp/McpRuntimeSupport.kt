package com.opencray.mcp

object McpRuntimeSupport {
  const val BRIDGE_STATUS_BRIDGE_READY: String = "bridge_ready"
  const val REMOTE_TOOL_BRIDGE_AVAILABLE: Boolean = true

  /** Dynamic proxy tools are named `mcp__<server_id>__<remote_tool_name>`. */
  const val MCP_TOOL_NAME_PREFIX: String = "mcp__"
  private const val MCP_TOOL_NAME_SEPARATOR = "__"

  val SUPPORTED_AGENT_TOOL_NAMES: Set<String> = setOf("mcp_list_servers", "mcp_list_tools")

  fun bridgeSummary(): String =
    "Remote MCP tools are callable through the bridge; every proxy tool call goes through policy approval."

  /**
   * Parses `mcp__<server_id>__<remote_tool_name>` into its two parts.
   * Server ids and remote tool names must each be non-blank; separator
   * collisions inside either part are rejected because the mapping back to
   * (serverId, toolName) would be ambiguous.
   */
  fun parseProxyToolName(toolName: String): Pair<String, String>? {
    if (!toolName.startsWith(MCP_TOOL_NAME_PREFIX)) {
      return null
    }
    val remainder = toolName.removePrefix(MCP_TOOL_NAME_PREFIX)
    val separatorIndex = remainder.indexOf(MCP_TOOL_NAME_SEPARATOR)
    if (separatorIndex <= 0) {
      return null
    }
    val serverId = remainder.substring(0, separatorIndex)
    val remoteToolName = remainder.substring(separatorIndex + MCP_TOOL_NAME_SEPARATOR.length)
    if (serverId.isBlank() || remoteToolName.isBlank()) {
      return null
    }
    if (remoteToolName.contains(MCP_TOOL_NAME_SEPARATOR)) {
      return null
    }
    return serverId to remoteToolName
  }

  fun proxyToolName(serverId: String, remoteToolName: String): String? {
    if (serverId.isBlank() || remoteToolName.isBlank()) {
      return null
    }
    if (remoteToolName.contains(MCP_TOOL_NAME_SEPARATOR)) {
      return null
    }
    return "$MCP_TOOL_NAME_PREFIX$serverId$MCP_TOOL_NAME_SEPARATOR$remoteToolName"
  }
}
