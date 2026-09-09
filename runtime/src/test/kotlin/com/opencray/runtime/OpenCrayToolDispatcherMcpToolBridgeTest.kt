package com.opencray.runtime

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.McpServerTrustState
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.RetryRequest
import com.opencray.core.orchestrator.RuntimeExecutionHooks
import com.opencray.mcp.McpClientAuthDescriptor
import com.opencray.mcp.McpClientDescriptor
import com.opencray.mcp.McpClientExposureReport
import com.opencray.mcp.McpClientTransportDescriptor
import com.opencray.mcp.McpConnectionState
import com.opencray.mcp.McpConnectionSummary
import com.opencray.mcp.McpRuntimeSupport
import com.opencray.mcp.McpServerAuthStatus
import com.opencray.mcp.McpToolBridgeGateway
import com.opencray.mcp.McpToolBridgeResult
import com.opencray.mcp.McpToolDescriptor
import java.nio.file.Files
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OpenCrayToolDispatcherMcpToolBridgeTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun mcpListToolsIsRegisteredOnlyWhenGatewayIsInjected() {
    val withGatewayNames = dispatcher(McpToolBridgeGatewayFake()).definitions().map { it.name }
    assertTrue(withGatewayNames.contains("mcp_list_tools"))
    assertTrue(withGatewayNames.contains("mcp__demo__search"))

    val withoutGatewayNames = dispatcher(gateway = null).definitions().map { it.name }
    assertFalse(withoutGatewayNames.contains("mcp_list_tools"))
    assertFalse(withoutGatewayNames.any { it.startsWith(McpRuntimeSupport.MCP_TOOL_NAME_PREFIX) })
  }

  @Test
  fun mcpListToolsListsServersToolsAndErrors() {
    val dispatcher = dispatcher(McpToolBridgeGatewayFake())

    val result = dispatcher.dispatch(
      task = task(mode = "SAFE"),
      call = AgentToolCall(toolName = "mcp_list_tools", arguments = buildJsonObject {}),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertTrue(result.content.contains("server\tdemo\tDemo MCP\tconnected\ttools=2"))
    assertTrue(result.content.contains("tool\tmcp__demo__search\tsearch\tSearch indexed documents."))
    assertTrue(result.content.contains("tool\tmcp__demo__fetch\tfetch\tFetch one document."))
    assertTrue(result.content.contains("error\tdemo\tconnection reset"))
    assertEquals("read_mcp", result.metadata["capabilityKind"])
    assertEquals("bridge_ready", result.metadata["bridgeStatus"])
    assertEquals("1", result.metadata["serverCount"])
    assertEquals("2", result.metadata["toolCount"])
  }

  @Test
  fun proxyToolDefinitionsForwardRemoteSchemaAsJsonSchema() {
    val definitions = dispatcher(McpToolBridgeGatewayFake()).definitions()

    val proxy = requireNotNull(definitions.firstOrNull { it.name == "mcp__demo__search" })
    assertTrue(proxy.description.contains("Proxy for remote MCP tool 'search' on server 'demo'"))
    assertTrue(proxy.description.contains("policy-approved"))
    assertTrue(proxy.description.contains("Search indexed documents."))

    val queryParameter = requireNotNull(proxy.parameters.firstOrNull { it.name == "query" })
    assertTrue(queryParameter.required)
    val querySchema = requireNotNull(queryParameter.jsonSchema)
    assertEquals("string", (querySchema["type"] as? JsonPrimitive)?.content)

    val limitParameter = requireNotNull(proxy.parameters.firstOrNull { it.name == "limit" })
    assertFalse(limitParameter.required)
  }

  @Test
  fun proxyToolCallRequiresApprovalInSafeModeBeforeContactingServer() {
    val gateway = McpToolBridgeGatewayFake()
    val dispatcher = dispatcher(gateway)

    val result = dispatcher.dispatch(
      task = task(mode = "SAFE"),
      call = AgentToolCall(
        toolName = "mcp__demo__search",
        arguments = buildJsonObject {
          put("query", "opencray")
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.DENIED, result.status)
    assertEquals("APPROVAL_REQUIRED", result.errorCode)
    assertEquals("ASK_SAFE_MCP_TOOL", result.metadata["policyReasonCode"])
    assertEquals("STANDARD", result.metadata["approvalRisk"])
    assertEquals("mcp_tool", result.metadata["capabilityKind"])
    assertEquals("mcp_tool_call", result.metadata["intentCategory"])
    assertEquals("demo", result.metadata["mcpServerId"])
    assertEquals("search", result.metadata["mcpRemoteToolName"])
    assertEquals("query", result.metadata["mcpArgumentSummary"])
    assertNull(gateway.lastCallServerId)
  }

  @Test
  fun proxyToolCallRunsInAutoModeAndReturnsRemoteContent() {
    val gateway = McpToolBridgeGatewayFake()
    val dispatcher = dispatcher(gateway)

    val result = dispatcher.dispatch(
      task = task(mode = "AUTO"),
      call = AgentToolCall(
        toolName = "mcp__demo__search",
        arguments = buildJsonObject {
          put("query", "opencray")
          put("limit", 5)
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertNull(result.errorCode)
    assertEquals("ALLOW_AUTO_STANDARD", result.metadata["policyReasonCode"])
    assertEquals("mcp_tool_call", result.metadata["intentCategory"])
    assertEquals("demo", result.metadata["mcpServerId"])
    assertEquals("search", result.metadata["mcpRemoteToolName"])
    assertEquals("limit,query", result.metadata["mcpArgumentSummary"])
    assertTrue(result.content.contains("3 documents matched"))
    assertEquals("demo", gateway.lastCallServerId)
    assertEquals("search", gateway.lastCallRemoteToolName)
    assertEquals("opencray", (gateway.lastCallArguments?.get("query") as? JsonPrimitive)?.content)
  }

  @Test
  fun proxyToolCallFailsWithBridgeUnavailableWhenGatewayIsNotInjected() {
    val dispatcher = dispatcher(gateway = null)

    val result = dispatcher.dispatch(
      task = task(mode = "AUTO"),
      call = AgentToolCall(
        toolName = "mcp__demo__search",
        arguments = buildJsonObject {
          put("query", "opencray")
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.FAILED, result.status)
    assertEquals("MCP_BRIDGE_UNAVAILABLE", result.errorCode)
    assertTrue(result.content.contains("MCP tool bridge is unavailable"))
  }

  @Test
  fun proxyToolCallMapsRemoteFailureToDedicatedErrorCode() {
    val gateway = McpToolBridgeGatewayFake(
      callResult = McpToolBridgeResult(
        success = false,
        content = "Remote tool rejected the arguments.",
        errorCode = "MCP_TOOL_CALL_FAILED",
      ),
    )
    val dispatcher = dispatcher(gateway)

    val result = dispatcher.dispatch(
      task = task(mode = "AUTO"),
      call = AgentToolCall(
        toolName = "mcp__demo__search",
        arguments = buildJsonObject {
          put("query", "opencray")
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.FAILED, result.status)
    assertEquals("MCP_TOOL_CALL_FAILED", result.errorCode)
    assertEquals("demo", result.metadata["mcpServerId"])
    assertEquals("search", result.metadata["mcpRemoteToolName"])
  }

  @Test
  fun unresolvableProxyToolNameFailsWithToolNotFound() {
    val dispatcher = dispatcher(McpToolBridgeGatewayFake())

    val result = dispatcher.dispatch(
      task = task(mode = "AUTO"),
      call = AgentToolCall(
        toolName = "mcp__only-server-id",
        arguments = buildJsonObject {},
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.FAILED, result.status)
    assertEquals("MCP_TOOL_NOT_FOUND", result.errorCode)
    assertTrue(result.content.contains("could not be resolved"))
  }

  @Test
  fun approvedProxyToolCallContinuesAfterSafeModeApproval() {
    val gateway = McpToolBridgeGatewayFake()
    val approvedDispatcher = OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(newWorkspaceFolder()),
        mcpExposureReport = demoExposureReport(),
        mcpToolBridgeGateway = gateway,
        approvedTaskId = "task-mcp-bridge",
        approvedToolName = "mcp__demo__search",
      ),
    )

    val result = approvedDispatcher.dispatch(
      task = task(mode = "SAFE"),
      call = AgentToolCall(
        toolName = "mcp__demo__search",
        arguments = buildJsonObject {
          put("query", "opencray")
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertEquals("demo", gateway.lastCallServerId)
  }

  private fun dispatcher(
    gateway: McpToolBridgeGateway?,
  ): OpenCrayToolDispatcher = OpenCrayToolDispatcher(
    OpenCrayToolDispatcherConfig(
      workspaceRoots = setOf(newWorkspaceFolder()),
      mcpExposureReport = demoExposureReport(),
      mcpToolBridgeGateway = gateway,
    ),
  )

  private var workspaceFolderCounter: Int = 0

  private fun newWorkspaceFolder(): java.nio.file.Path {
    workspaceFolderCounter += 1
    val folder = temporaryFolder.newFolder("mcp-bridge-workspace-$workspaceFolderCounter").toPath()
    Files.createDirectories(folder)
    return folder
  }

  private fun demoExposureReport(): McpClientExposureReport = McpClientExposureReport(
    activeClients = listOf(
      McpClientDescriptor(
        id = "demo",
        displayName = "Demo MCP",
        transport = McpClientTransportDescriptor.RemoteHttp(
          url = "https://demo.example.test/mcp",
          headerNames = emptyList(),
          protocolVersion = "2025-11-05",
          requestTimeoutMs = 30_000L,
        ),
        auth = McpClientAuthDescriptor(
          status = McpServerAuthStatus.NOT_REQUIRED,
          isReady = true,
        ),
        declaredTrustState = McpServerTrustState.ENABLED,
        trustState = McpServerTrustState.ENABLED,
        manuallyEnabled = true,
        toolExposure = com.opencray.mcp.McpToolExposure.ACTIVE,
        registeredAtEpochMs = 1L,
        updatedAtEpochMs = 1L,
      ),
    ),
    blockedClients = emptyList(),
  )

  private fun task(
    mode: String,
  ): AgentTask = AgentTask(
    id = "task-mcp-bridge",
    type = AgentTaskType.TOOL_CALL,
    input = """{"type":"tool_call"}""",
    policyDecision = PolicyDecision(
      outcome = PolicyDecisionOutcome.ALLOW,
      reasonCode = "TEST_ALLOW",
    ),
    metadata = mapOf("chatMode" to mode),
    createdAtEpochMs = 1_000L,
  )

  private fun runtimeHooks(): RuntimeExecutionHooks = RuntimeExecutionHooks(
    isCancellationRequested = { false },
    requestRetry = { _: RetryRequest -> error("Retry not expected in MCP bridge tool test.") },
  )

  private class McpToolBridgeGatewayFake(
    private val callResult: McpToolBridgeResult = McpToolBridgeResult(
      success = true,
      content = "3 documents matched the query.",
    ),
  ) : McpToolBridgeGateway {
    var lastCallServerId: String? = null
      private set
    var lastCallRemoteToolName: String? = null
      private set
    var lastCallArguments: JsonObject? = null
      private set

    override fun listTools(serverId: String): List<McpToolDescriptor> = listOf(
      McpToolDescriptor(
        name = "search",
        description = "Search indexed documents.",
        inputSchema = buildJsonObject {
          put(
            "properties",
            buildJsonObject {
              put(
                "query",
                buildJsonObject {
                  put("type", "string")
                  put("description", "Search text.")
                },
              )
              put(
                "limit",
                buildJsonObject {
                  put("type", "number")
                  put("description", "Maximum results.")
                },
              )
            },
          )
          put("required", buildJsonArray { add(JsonPrimitive("query")) })
        },
      ),
      McpToolDescriptor(
        name = "fetch",
        description = "Fetch one document.",
      ),
    )

    override fun callTool(
      serverId: String,
      remoteToolName: String,
      arguments: JsonObject,
    ): McpToolBridgeResult {
      lastCallServerId = serverId
      lastCallRemoteToolName = remoteToolName
      lastCallArguments = arguments
      return callResult
    }

    override fun connectionSummaries(): List<McpConnectionSummary> = listOf(
      McpConnectionSummary(
        serverId = "demo",
        state = McpConnectionState.CONNECTED,
        connectedToolCount = 2,
        lastError = "connection reset",
      ),
    )
  }
}
