package com.opencray.runtime

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.RetryRequest
import com.opencray.core.orchestrator.RuntimeExecutionHooks
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SandboxSessionInfoToolTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun definitionsIncludeSandboxSessionInfoToolWhenServiceIsConfigured() {
    val workspaceRoot = temporaryFolder.newFolder("sandbox-session-info-definitions").toPath()
    val dispatcher = OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(workspaceRoot),
        sandboxSessionInfoService = SandboxSessionInfoService {
          SandboxSessionInfoResult(
            providerId = "e2b",
            source = SandboxSessionInfoSource.NONE,
          )
        },
      ),
    )

    val toolNames = dispatcher.definitions().map { definition -> definition.name }

    assertTrue(toolNames.contains("sandbox_session_info"))
  }

  @Test
  fun hiddenSandboxPrefixHidesSandboxSessionInfoToolDefinition() {
    val workspaceRoot = temporaryFolder.newFolder("sandbox-session-info-hidden").toPath()
    val dispatcher = OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(workspaceRoot),
        sandboxSessionInfoService = SandboxSessionInfoService {
          SandboxSessionInfoResult(
            providerId = "e2b",
            source = SandboxSessionInfoSource.NONE,
          )
        },
        hiddenToolNamePrefixes = setOf("sandbox_"),
      ),
    )

    val toolNames = dispatcher.definitions().map { definition -> definition.name }

    assertFalse(toolNames.contains("sandbox_session_info"))
  }

  @Test
  fun sandboxSessionInfoReturnsMergedSessionMetadata() {
    val workspaceRoot = temporaryFolder.newFolder("sandbox-session-info-dispatch").toPath()
    val dispatcher = OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(workspaceRoot),
        sandboxSessionInfoService = SandboxSessionInfoService {
          SandboxSessionInfoResult(
            providerId = "e2b",
            source = SandboxSessionInfoSource.ACTIVE_AND_PERSISTED,
            lifecycleStatus = SandboxSessionLifecycleStatus.STALE,
            sandboxId = "sb-info",
            sandboxDomain = "e2b.app",
            templateId = "base",
            workspaceRoot = workspaceRoot.toString(),
            updatedAtEpochMs = 123L,
            previewCandidatePorts = listOf(3000, 4173),
            runningRequestIds = listOf("req-1", "req-2"),
            sessionLastActivityAtEpochMs = 111L,
            sessionStaleAfterEpochMs = 999L,
            sessionIsStale = true,
            recommendedRefreshAfterMs = 45_000L,
            remoteWorkspaceRoot = "/home/user/opencray/workspace-sticky/sb-info",
            lastPreviewUrl = "https://4173-sb-info.e2b.app/",
            lastPreviewPort = 4173,
            lastPreviewPath = "/",
            lastPreviewProbeStatus = "ready",
            lastPreviewProbeHttpStatusCode = 200,
            lastPreviewProbeMessage = null,
            lastPreviewOpenedAtEpochMs = 456L,
            lastPreviewProbeObservedAtEpochMs = 457L,
            lastPreviewProbeSource = "session_info_auto",
            previewAutoProbeAttempted = true,
          )
        },
      ),
    )

    val result = dispatcher.dispatch(
      task = developerTask(),
      call = AgentToolCall(
        toolName = "sandbox_session_info",
        arguments = JsonObject(emptyMap()),
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertTrue(result.content.contains("lifecycle appears stale"))
    assertTrue(result.content.contains("session_lifecycle_status=stale"))
    assertTrue(result.content.contains("preview_auto_probe_attempted=true"))
    assertTrue(result.content.contains("preview_candidate_ports=3000,4173"))
    assertTrue(result.content.contains("running_request_count=2"))
    assertTrue(result.content.contains("running_request_ids=req-1,req-2"))
    assertTrue(result.content.contains("remote_workspace_root=/home/user/opencray/workspace-sticky/sb-info"))
    assertTrue(result.content.contains("last_preview_url=https://4173-sb-info.e2b.app/"))
    assertEquals("true", result.metadata["sandboxSessionPresent"])
    assertEquals("active_memory_and_persisted", result.metadata["sandboxSessionSource"])
    assertEquals("stale", result.metadata["sandboxSessionLifecycleStatus"])
    assertEquals("true", result.metadata["sandboxSessionIsStale"])
    assertEquals("true", result.metadata["sandboxPreviewAutoProbeAttempted"])
    assertEquals("e2b", result.metadata["sandboxProvider"])
    assertEquals("sb-info", result.metadata["sandboxId"])
    assertEquals("3000,4173", result.metadata["sandboxPreviewCandidatePorts"])
    assertEquals("111", result.metadata["sandboxSessionLastActivityAtEpochMs"])
    assertEquals("999", result.metadata["sandboxSessionStaleAfterEpochMs"])
    assertEquals("45000", result.metadata["sandboxSessionAutoRefreshAfterMs"])
    assertEquals("2", result.metadata["sandboxRunningRequestCount"])
    assertEquals("req-1,req-2", result.metadata["sandboxRunningRequestIds"])
    assertEquals("/home/user/opencray/workspace-sticky/sb-info", result.metadata["sandboxRemoteWorkspaceRoot"])
    assertEquals("https://4173-sb-info.e2b.app/", result.metadata["sandboxLastPreviewUrl"])
    assertEquals("4173", result.metadata["sandboxLastPreviewPort"])
    assertEquals("/", result.metadata["sandboxLastPreviewPath"])
    assertEquals("ready", result.metadata["sandboxLastPreviewProbeStatus"])
    assertEquals("200", result.metadata["sandboxLastPreviewProbeHttpStatus"])
    assertEquals("456", result.metadata["sandboxLastPreviewOpenedAtEpochMs"])
    assertEquals("457", result.metadata["sandboxLastPreviewProbeObservedAtEpochMs"])
    assertEquals("session_info_auto", result.metadata["sandboxLastPreviewProbeSource"])
  }

  @Test
  fun sandboxSessionInfoReturnsNoSessionMetadataWhenUnavailable() {
    val workspaceRoot = temporaryFolder.newFolder("sandbox-session-info-missing").toPath()
    val dispatcher = OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(workspaceRoot),
        sandboxSessionInfoService = SandboxSessionInfoService {
          SandboxSessionInfoResult(
            providerId = "e2b",
            source = SandboxSessionInfoSource.NONE,
            lifecycleStatus = SandboxSessionLifecycleStatus.NONE,
          )
        },
      ),
    )

    val result = dispatcher.dispatch(
      task = developerTask(),
      call = AgentToolCall(
        toolName = "sandbox_session_info",
        arguments = JsonObject(emptyMap()),
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertTrue(result.content.contains("No reusable cloud sandbox session is recorded"))
    assertEquals("false", result.metadata["sandboxSessionPresent"])
    assertEquals("none", result.metadata["sandboxSessionSource"])
    assertEquals("none", result.metadata["sandboxSessionLifecycleStatus"])
    assertEquals("false", result.metadata["sandboxSessionIsStale"])
    assertEquals("false", result.metadata["sandboxPreviewAutoProbeAttempted"])
    assertEquals("0", result.metadata["sandboxRunningRequestCount"])
    assertEquals(null, result.metadata["sandboxId"])
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
    requestRetry = { _: RetryRequest -> error("Retry not expected in SandboxSessionInfoToolTest.") },
  )
}
