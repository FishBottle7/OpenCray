package com.opencray.runtime

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.RetryRequest
import com.opencray.core.orchestrator.RuntimeExecutionHooks
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SandboxPreviewToolTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun definitionsIncludeSandboxPreviewToolWhenServiceIsConfigured() {
    val workspaceRoot = temporaryFolder.newFolder("sandbox-preview-definitions").toPath()
    val dispatcher = OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(workspaceRoot),
        sandboxPreviewService = SandboxPreviewService {
          SandboxPreviewResult(
            url = "https://3000-sb-1.e2b.app/",
            providerId = "e2b",
            sandboxId = "sb-1",
            sandboxDomain = "e2b.app",
            port = 3000,
          )
        },
      ),
    )

    val definition = dispatcher.definitions().first { tool -> tool.name == "sandbox_preview_open" }

    assertFalse(definition.parameters.first { parameter -> parameter.name == "port" }.required)
  }

  @Test
  fun hiddenSandboxPrefixHidesSandboxPreviewToolDefinition() {
    val workspaceRoot = temporaryFolder.newFolder("sandbox-preview-hidden").toPath()
    val dispatcher = OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(workspaceRoot),
        sandboxPreviewService = SandboxPreviewService {
          SandboxPreviewResult(
            url = "https://3000-sb-1.e2b.app/",
            providerId = "e2b",
            port = 3000,
          )
        },
        hiddenToolNamePrefixes = setOf("sandbox_"),
      ),
    )

    val toolNames = dispatcher.definitions().map { definition -> definition.name }

    assertFalse(toolNames.contains("sandbox_preview_open"))
  }

  @Test
  fun sandboxPreviewOpenReturnsPreviewUrlMetadata() {
    val workspaceRoot = temporaryFolder.newFolder("sandbox-preview-dispatch").toPath()
    val dispatcher = OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(workspaceRoot),
        sandboxPreviewService = SandboxPreviewService { request ->
          val requestedPort = requireNotNull(request.port)
          SandboxPreviewResult(
            url = "https://${requestedPort}-sb-1.e2b.app${request.path.orEmpty()}",
            providerId = "e2b",
            sandboxId = "sb-1",
            sandboxDomain = "e2b.app",
            port = requestedPort,
            path = request.path,
            accessHeaderName = "E2B-Traffic-Access-Token",
            accessTokenConfigured = true,
            probeStatus = SandboxPreviewProbeStatus.READY,
            probeHttpStatusCode = 200,
          )
        },
      ),
    )

    val result = dispatcher.dispatch(
      task = developerTask(),
      call = AgentToolCall(
        toolName = "sandbox_preview_open",
        arguments = JsonObject(
          mapOf(
            "port" to JsonPrimitive(3000),
            "path" to JsonPrimitive("/health"),
          ),
        ),
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertTrue(result.content.contains("preview_url=https://3000-sb-1.e2b.app/health"))
    assertEquals("https://3000-sb-1.e2b.app/health", result.metadata["previewUrl"])
    assertEquals("3000", result.metadata["previewPort"])
    assertEquals("explicit", result.metadata["previewPortSelection"])
    assertEquals("/health", result.metadata["previewPath"])
    assertEquals("e2b", result.metadata["sandboxProvider"])
    assertEquals("sb-1", result.metadata["sandboxId"])
    assertEquals("ready", result.metadata["previewProbeStatus"])
    assertEquals("200", result.metadata["previewProbeHttpStatus"])
    assertEquals("true", result.metadata["previewAccessTokenConfigured"])
    assertTrue(result.content.contains("probe_status=ready"))
    assertTrue(result.content.contains("probe_http_status=200"))
  }

  @Test
  fun sandboxPreviewOpenAllowsServiceResolvedPortWhenArgumentIsOmitted() {
    val workspaceRoot = temporaryFolder.newFolder("sandbox-preview-auto-port").toPath()
    val dispatcher = OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(workspaceRoot),
        sandboxPreviewService = SandboxPreviewService { request ->
          assertEquals(null, request.port)
          SandboxPreviewResult(
            url = "https://4173-sb-1.e2b.app/",
            providerId = "e2b",
            sandboxId = "sb-1",
            sandboxDomain = "e2b.app",
            port = 4173,
            probeStatus = SandboxPreviewProbeStatus.READY,
            probeHttpStatusCode = 200,
          )
        },
      ),
    )

    val result = dispatcher.dispatch(
      task = developerTask(),
      call = AgentToolCall(
        toolName = "sandbox_preview_open",
        arguments = JsonObject(emptyMap()),
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertTrue(result.content.contains("preview_url=https://4173-sb-1.e2b.app/"))
    assertEquals("4173", result.metadata["previewPort"])
    assertEquals("auto", result.metadata["previewPortSelection"])
  }

  private fun developerTask(): AgentTask = AgentTask(
    id = "task-${System.nanoTime()}",
    type = AgentTaskType.TOOL_CALL,
    input = """{"type":"tool_call"}""",
    policyDecision = PolicyDecision(
      outcome = PolicyDecisionOutcome.ALLOW,
      reasonCode = "HOST_ALLOW",
    ),
    metadata = mapOf("chatMode" to "DEVELOPER"),
    createdAtEpochMs = 1_000L,
  )

  private fun runtimeHooks(): RuntimeExecutionHooks = RuntimeExecutionHooks(
    isCancellationRequested = { false },
    requestRetry = { _: RetryRequest -> error("Retry not expected in SandboxPreviewToolTest.") },
  )
}
