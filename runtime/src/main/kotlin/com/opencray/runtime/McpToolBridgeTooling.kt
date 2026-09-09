package com.opencray.runtime

import com.opencray.core.contracts.AgentTask
import com.opencray.mcp.McpRuntimeSupport
import com.opencray.runtime.policy.McpToolCallIntent
import com.opencray.runtime.policy.ToolMetadataContextRequest
import com.opencray.runtime.policy.ToolTargetKind
import com.opencray.runtime.policy.ToolWorkspaceRelation
import kotlinx.serialization.json.JsonObject

private const val MCP_BRIDGE_GATEWAY_UNAVAILABLE_CONTENT =
  "MCP tool bridge is unavailable in the current execution environment."

internal fun OpenCrayToolDispatcher.listMcpTools(): AgentToolResult {
  val toolName = "mcp_list_tools"
  val gateway = config.mcpToolBridgeGateway
    ?: return unavailableMcpBridgeGateway(toolName)
  val report = config.mcpExposureReport
  val summaries = gateway.connectionSummaries()
  val lines = buildList {
    summaries.forEach { summary ->
      val serverName = report?.activeClients?.firstOrNull { it.id == summary.serverId }?.displayName
        ?: summary.serverId
      add(
        "server\t${summary.serverId}\t$serverName\t${summary.state.name.lowercase()}" +
          "\ttools=${summary.connectedToolCount}",
      )
      gateway.listTools(summary.serverId).forEach { tool ->
        val proxyName = McpRuntimeSupport.proxyToolName(summary.serverId, tool.name) ?: return@forEach
        val description = tool.description?.replace('\n', ' ')?.take(160) ?: ""
        add("tool\t$proxyName\t${tool.name}\t$description")
      }
      summary.lastError?.takeIf(String::isNotBlank)?.let { add("error\t${summary.serverId}\t$it") }
    }
  }
  return AgentToolResult(
    toolName = toolName,
    status = AgentToolResultStatus.SUCCESS,
    content = lines.joinToString(separator = "\n").ifBlank { "No enabled MCP servers." },
    metadata = toolPolicyPipeline.resultMetadata(
      toolName = toolName,
      request = ToolMetadataContextRequest(
        workspaceRelation = ToolWorkspaceRelation.NONE,
        targetSummary = "servers=${summaries.size}",
      ),
      metadata = mapOf(
        "bridgeStatus" to McpRuntimeSupport.BRIDGE_STATUS_BRIDGE_READY,
        "remoteToolBridgeAvailable" to McpRuntimeSupport.REMOTE_TOOL_BRIDGE_AVAILABLE.toString(),
        "serverCount" to summaries.size.toString(),
        "toolCount" to summaries.sumOf { it.connectedToolCount }.toString(),
      ),
    ),
  )
}

internal fun OpenCrayToolDispatcher.callMcpProxyTool(
  task: AgentTask,
  toolName: String,
  arguments: JsonObject,
): AgentToolResult {
  val gateway = config.mcpToolBridgeGateway
    ?: return unavailableMcpBridgeGateway(toolName)
  val (serverId, remoteToolName) = McpRuntimeSupport.parseProxyToolName(toolName)
    ?: return mcpProxyToolNotFound(toolName)
  val targetSummary = "$serverId.$remoteToolName"
  val plan = toolPolicyPipeline.plan(
    task = task,
    toolName = toolName,
    metadataRequest = ToolMetadataContextRequest(
      targetKind = ToolTargetKind.NONE,
      workspaceRelation = ToolWorkspaceRelation.NONE,
      targetSummary = targetSummary,
    ),
    intent = McpToolCallIntent(
      serverId = serverId,
      remoteToolName = remoteToolName,
      argumentSummary = arguments.keys.sorted().joinToString(separator = ",").take(200)
        .takeIf(String::isNotBlank),
    ),
  )
  toolPolicyPipeline.gate(
    plan = plan,
    askDetail = "Approval is required before calling remote MCP tool '$remoteToolName' on server '$serverId'.",
    denyDetail = "$toolName is blocked by the current safety policy.",
  )?.let { return it }
  val outcome = gateway.callTool(
    serverId = serverId,
    remoteToolName = remoteToolName,
    arguments = arguments,
  )
  return AgentToolResult(
    toolName = toolName,
    status = if (outcome.success) AgentToolResultStatus.SUCCESS else AgentToolResultStatus.FAILED,
    content = outcome.content,
    errorCode = outcome.errorCode,
    errorMessage = if (outcome.success) null else outcome.content,
    metadata = toolPolicyPipeline.resultMetadata(
      plan = plan,
      metadata = mapOf(
        "mcpServerId" to serverId,
        "mcpRemoteToolName" to remoteToolName,
      ),
    ),
  )
}

private fun OpenCrayToolDispatcher.unavailableMcpBridgeGateway(
  toolName: String,
): AgentToolResult = AgentToolResult(
  toolName = toolName,
  status = AgentToolResultStatus.FAILED,
  content = MCP_BRIDGE_GATEWAY_UNAVAILABLE_CONTENT,
  errorCode = "MCP_BRIDGE_UNAVAILABLE",
  metadata = toolPolicyPipeline.resultMetadata(
    toolName = toolName,
    request = ToolMetadataContextRequest(
      workspaceRelation = ToolWorkspaceRelation.NONE,
    ),
  ),
)

private fun mcpProxyToolNotFound(
  toolName: String,
): AgentToolResult = AgentToolResult(
  toolName = toolName,
  status = AgentToolResultStatus.FAILED,
  content = "MCP proxy tool '$toolName' could not be resolved to a server and tool name.",
  errorCode = "MCP_TOOL_NOT_FOUND",
)
