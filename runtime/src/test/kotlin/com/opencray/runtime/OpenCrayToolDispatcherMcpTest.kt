package com.opencray.runtime

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.RuntimeExecutionHooks
import com.opencray.mcp.McpClientExposureReport
import com.opencray.mcp.McpRuntimeSupport
import java.nio.file.Files
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OpenCrayToolDispatcherMcpTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun definitions_describeMcpSupportAsExposureOnly() {
    val dispatcher = dispatcher()

    val mcpDefinitions = dispatcher.definitions().filter { definition ->
      definition.name.startsWith("mcp_")
    }
    val definition = requireNotNull(dispatcher.definitions().firstOrNull { it.name == "mcp_list_servers" })

    assertEquals(McpRuntimeSupport.SUPPORTED_AGENT_TOOL_NAMES, mcpDefinitions.map { it.name }.toSet())
    assertTrue(definition.description.contains("does not proxy remote MCP tools yet"))
  }

  @Test
  fun dispatchingMcpListServers_reportsBridgeLimitsInContentAndMetadata() {
    val dispatcher = dispatcher(
      mcpExposureReport = McpClientExposureReport(
        activeClients = emptyList(),
        blockedClients = emptyList(),
      ),
    )

    val result = dispatcher.dispatch(
      task = task(),
      call = AgentToolCall(toolName = "mcp_list_servers", arguments = JsonObject(emptyMap())),
      hooks = RuntimeExecutionHooks(
        isCancellationRequested = { false },
        requestRetry = { _ -> Unit },
      ),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertTrue(result.content.contains(McpRuntimeSupport.bridgeSummary()))
    assertEquals("read_mcp", result.metadata["capabilityKind"])
    assertEquals("none", result.metadata["workspaceRelation"])
    assertEquals("exposure_only", result.metadata["bridgeStatus"])
    assertEquals("false", result.metadata["remoteToolBridgeAvailable"])
    assertEquals("mcp_list_servers", result.metadata["supportedAgentTools"])
  }

  private fun dispatcher(
    mcpExposureReport: McpClientExposureReport? = null,
  ): OpenCrayToolDispatcher {
    val workspaceRoot = temporaryFolder.newFolder("dispatcher-mcp-workspace").toPath()
    Files.createDirectories(workspaceRoot)
    return OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(workspaceRoot),
        mcpExposureReport = mcpExposureReport,
      ),
    )
  }

  private fun task(): AgentTask = AgentTask(
    id = "task-mcp-list",
    type = AgentTaskType.PROMPT,
    input = "List MCP servers.",
    policyDecision = PolicyDecision(
      outcome = PolicyDecisionOutcome.ALLOW,
      reasonCode = "TEST_ALLOW",
    ),
    createdAtEpochMs = 1L,
  )
}
