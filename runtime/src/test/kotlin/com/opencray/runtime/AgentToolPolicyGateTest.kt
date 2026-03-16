package com.opencray.runtime

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.RetryRequest
import com.opencray.core.orchestrator.RuntimeExecutionHooks
import com.opencray.runtime.web.WebContentFetcher
import com.opencray.runtime.web.WebFetchRequest
import com.opencray.runtime.web.WebFetchResult
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentToolPolicyGateTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun autoModeWriteRemainsAllowedInsideDedicatedWorkspace() {
    val workspaceRoot = temporaryFolder.newFolder("tool-policy-write").toPath()
    val dispatcher = OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(workspaceRoot),
      ),
    )

    val result = dispatcher.dispatch(
      task = agentTask(
        metadata = mapOf("chatMode" to "AUTO"),
      ),
      call = AgentToolCall(
        toolName = "workspace_write_file",
        arguments = JsonObject(
          mapOf(
            "path" to JsonPrimitive("notes.txt"),
            "content" to JsonPrimitive("hello"),
          ),
        ),
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertEquals("ALLOW_AUTO_STANDARD", result.metadata["policyReasonCode"])
    assertEquals(
      "hello",
      String(Files.readAllBytes(workspaceRoot.resolve("notes.txt")), StandardCharsets.UTF_8),
    )
  }

  @Test
  fun settingsOverrideCanAllowDeletesWithoutApproval() {
    val workspaceRoot = temporaryFolder.newFolder("tool-policy-delete-override-allow").toPath()
    val target = workspaceRoot.resolve("notes.txt")
    Files.write(target, "delete me".toByteArray(StandardCharsets.UTF_8))
    val dispatcher = OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(workspaceRoot),
      ),
    )

    val result = dispatcher.dispatch(
      task = agentTask(
        metadata = mapOf(
          "chatMode" to "AUTO",
          "fileDeletesPolicyId" to "allow",
        ),
      ),
      call = AgentToolCall(
        toolName = "workspace_delete_file",
        arguments = JsonObject(
          mapOf("path" to JsonPrimitive("notes.txt")),
        ),
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertEquals("SETTINGS_OVERRIDE_ALLOW", result.metadata["policyReasonCode"])
    assertFalse(Files.exists(target))
  }

  @Test
  fun autoModeDeleteRequiresApprovalAndDoesNotMutateFile() {
    val workspaceRoot = temporaryFolder.newFolder("tool-policy-delete").toPath()
    val target = workspaceRoot.resolve("notes.txt")
    Files.write(target, "keep me".toByteArray(StandardCharsets.UTF_8))
    val dispatcher = OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(workspaceRoot),
      ),
    )

    val result = dispatcher.dispatch(
      task = agentTask(
        metadata = mapOf("chatMode" to "AUTO"),
      ),
      call = AgentToolCall(
        toolName = "workspace_delete_file",
        arguments = JsonObject(
          mapOf("path" to JsonPrimitive("notes.txt")),
        ),
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.DENIED, result.status)
    assertEquals("APPROVAL_REQUIRED", result.errorCode)
    assertEquals("ASK_AUTO_DESTRUCTIVE", result.metadata["policyReasonCode"])
    assertEquals("STANDARD", result.metadata["approvalRisk"])
    assertTrue(Files.exists(target))
    assertEquals("keep me", String(Files.readAllBytes(target), StandardCharsets.UTF_8))
  }

  @Test
  fun settingsOverrideCanBlockWritesEvenInDeveloperMode() {
    val workspaceRoot = temporaryFolder.newFolder("tool-policy-write-override-block").toPath()
    val dispatcher = OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(workspaceRoot),
      ),
    )

    val result = dispatcher.dispatch(
      task = agentTask(
        metadata = mapOf(
          "chatMode" to "DEVELOPER",
          "fileChangesPolicyId" to "block",
        ),
      ),
      call = AgentToolCall(
        toolName = "workspace_write_file",
        arguments = JsonObject(
          mapOf(
            "path" to JsonPrimitive("blocked.txt"),
            "content" to JsonPrimitive("denied"),
          ),
        ),
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.DENIED, result.status)
    assertEquals("DENY_POLICY", result.errorCode)
    assertEquals("SETTINGS_OVERRIDE_BLOCK", result.metadata["policyReasonCode"])
    assertFalse(Files.exists(workspaceRoot.resolve("blocked.txt")))
  }

  @Test
  fun settingsOverrideCanRequireApprovalForWritesInDeveloperMode() {
    val workspaceRoot = temporaryFolder.newFolder("tool-policy-write-override-ask").toPath()
    val dispatcher = OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(workspaceRoot),
      ),
    )

    val result = dispatcher.dispatch(
      task = agentTask(
        metadata = mapOf(
          "chatMode" to "DEVELOPER",
          "fileChangesPolicyId" to "ask",
        ),
      ),
      call = AgentToolCall(
        toolName = "workspace_write_file",
        arguments = JsonObject(
          mapOf(
            "path" to JsonPrimitive("notes.txt"),
            "content" to JsonPrimitive("hello"),
          ),
        ),
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.DENIED, result.status)
    assertEquals("APPROVAL_REQUIRED", result.errorCode)
    assertEquals("SETTINGS_OVERRIDE_ASK", result.metadata["policyReasonCode"])
    assertEquals("STANDARD", result.metadata["approvalRisk"])
    assertFalse(Files.exists(workspaceRoot.resolve("notes.txt")))
  }

  @Test
  fun safeModeDeleteRequiresHighRiskApprovalAndDoesNotMutateFile() {
    val workspaceRoot = temporaryFolder.newFolder("tool-policy-safe-delete").toPath()
    val target = workspaceRoot.resolve("notes.txt")
    Files.write(target, "keep me".toByteArray(StandardCharsets.UTF_8))
    val dispatcher = OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(workspaceRoot),
      ),
    )

    val result = dispatcher.dispatch(
      task = agentTask(
        metadata = mapOf("chatMode" to "SAFE"),
      ),
      call = AgentToolCall(
        toolName = "workspace_delete_file",
        arguments = JsonObject(
          mapOf("path" to JsonPrimitive("notes.txt")),
        ),
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.DENIED, result.status)
    assertEquals("HIGH_RISK_APPROVAL_REQUIRED", result.errorCode)
    assertEquals("ASK_SAFE_DESTRUCTIVE_HIGH_RISK", result.metadata["policyReasonCode"])
    assertEquals("HIGH_RISK", result.metadata["approvalRisk"])
    assertTrue(result.content.contains("High-risk approval required"))
    assertTrue(Files.exists(target))
    assertEquals("keep me", String(Files.readAllBytes(target), StandardCharsets.UTF_8))
  }

  @Test
  fun settingsOverrideCannotBypassProtectedDeleteDenial() {
    val workspaceRoot = temporaryFolder.newFolder("tool-policy-protected-delete").toPath()
    val target = workspaceRoot.resolve("agent.md")
    Files.write(target, "protected".toByteArray(StandardCharsets.UTF_8))
    val dispatcher = OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(workspaceRoot),
      ),
    )

    val result = dispatcher.dispatch(
      task = agentTask(
        metadata = mapOf(
          "chatMode" to "DEVELOPER",
          "fileDeletesPolicyId" to "allow",
        ),
      ),
      call = AgentToolCall(
        toolName = "workspace_delete_file",
        arguments = JsonObject(
          mapOf("path" to JsonPrimitive("agent.md")),
        ),
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.DENIED, result.status)
    assertEquals("DENY_POLICY", result.errorCode)
    assertEquals("DENY_PROTECTED_FILE", result.metadata["policyReasonCode"])
    assertTrue(Files.exists(target))
  }

  @Test
  fun autoModeCommandRequiresApprovalBeforeSpawn() {
    val workspaceRoot = temporaryFolder.newFolder("tool-policy-command").toPath()
    val runner = RecordingRunner()
    val dispatcher = OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(workspaceRoot),
        commandExecutor = CommandExecutor(runner = runner),
      ),
    )

    val result = dispatcher.dispatch(
      task = agentTask(
        metadata = mapOf("chatMode" to "AUTO"),
      ),
      call = AgentToolCall(
        toolName = "command_exec",
        arguments = JsonObject(
          mapOf(
            "command" to JsonPrimitive("git"),
            "working_directory" to JsonPrimitive("."),
          ),
        ),
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.DENIED, result.status)
    assertEquals("APPROVAL_REQUIRED", result.errorCode)
    assertEquals("STANDARD", result.metadata["approvalRisk"])
    assertEquals(0, runner.spawnCount)
  }

  @Test
  fun safeModeCommandRequiresHighRiskApprovalBeforeSpawn() {
    val workspaceRoot = temporaryFolder.newFolder("tool-policy-safe-command").toPath()
    val runner = RecordingRunner()
    val dispatcher = OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(workspaceRoot),
        commandExecutor = CommandExecutor(runner = runner),
      ),
    )

    val result = dispatcher.dispatch(
      task = agentTask(
        metadata = mapOf("chatMode" to "SAFE"),
      ),
      call = AgentToolCall(
        toolName = "command_exec",
        arguments = JsonObject(
          mapOf(
            "command" to JsonPrimitive("git"),
            "working_directory" to JsonPrimitive("."),
          ),
        ),
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.DENIED, result.status)
    assertEquals("HIGH_RISK_APPROVAL_REQUIRED", result.errorCode)
    assertEquals("ASK_SAFE_COMMAND_HIGH_RISK", result.metadata["policyReasonCode"])
    assertEquals("HIGH_RISK", result.metadata["approvalRisk"])
    assertTrue(result.content.contains("High-risk approval"))
    assertEquals(0, runner.spawnCount)
  }

  @Test
  fun autoModeWebFetchRequiresApprovalBeforeNetworkAccess() {
    val workspaceRoot = temporaryFolder.newFolder("tool-policy-webfetch").toPath()
    val fetcher = RecordingWebContentFetcher()
    val dispatcher = OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(workspaceRoot),
        webContentFetcher = fetcher,
      ),
    )

    val result = dispatcher.dispatch(
      task = agentTask(
        metadata = mapOf("chatMode" to "AUTO"),
      ),
      call = AgentToolCall(
        toolName = "WebFetch",
        arguments = JsonObject(
          mapOf("url" to JsonPrimitive("https://example.com/post")),
        ),
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.DENIED, result.status)
    assertEquals("APPROVAL_REQUIRED", result.errorCode)
    assertEquals("ASK_AUTO_NETWORK", result.metadata["policyReasonCode"])
    assertEquals("STANDARD", result.metadata["approvalRisk"])
    assertEquals(0, fetcher.requestCount)
  }

  @Test
  fun safeModeWebFetchRequiresHighRiskApprovalBeforeNetworkAccess() {
    val workspaceRoot = temporaryFolder.newFolder("tool-policy-safe-webfetch").toPath()
    val fetcher = RecordingWebContentFetcher()
    val dispatcher = OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(workspaceRoot),
        webContentFetcher = fetcher,
      ),
    )

    val result = dispatcher.dispatch(
      task = agentTask(
        metadata = mapOf("chatMode" to "SAFE"),
      ),
      call = AgentToolCall(
        toolName = "WebFetch",
        arguments = JsonObject(
          mapOf("url" to JsonPrimitive("https://example.com/post")),
        ),
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.DENIED, result.status)
    assertEquals("HIGH_RISK_APPROVAL_REQUIRED", result.errorCode)
    assertEquals("ASK_SAFE_NETWORK_HIGH_RISK", result.metadata["policyReasonCode"])
    assertEquals("HIGH_RISK", result.metadata["approvalRisk"])
    assertEquals(0, fetcher.requestCount)
  }

  @Test
  fun autoModePythonRequiresApprovalBeforeRuntimeExec() {
    val workspaceRoot = temporaryFolder.newFolder("tool-policy-python").toPath()
    val dispatcher = OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(workspaceRoot),
      ),
    )

    val result = dispatcher.dispatch(
      task = agentTask(
        metadata = mapOf("chatMode" to "AUTO"),
      ),
      call = AgentToolCall(
        toolName = "python_exec",
        arguments = JsonObject(
          mapOf("script_path" to JsonPrimitive("scripts/run.py")),
        ),
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.DENIED, result.status)
    assertEquals("APPROVAL_REQUIRED", result.errorCode)
    assertEquals("ASK_AUTO_COMMAND", result.metadata["policyReasonCode"])
    assertEquals("STANDARD", result.metadata["approvalRisk"])
    assertEquals(osPath("scripts/run.py"), result.metadata["scriptPath"])
    assertFalse(Files.exists(workspaceRoot.resolve("scripts").resolve("run.py")))
  }

  @Test
  fun safeModePythonRequiresHighRiskApprovalBeforeRuntimeExec() {
    val workspaceRoot = temporaryFolder.newFolder("tool-policy-safe-python").toPath()
    val dispatcher = OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(workspaceRoot),
      ),
    )

    val result = dispatcher.dispatch(
      task = agentTask(
        metadata = mapOf("chatMode" to "SAFE"),
      ),
      call = AgentToolCall(
        toolName = "python_exec",
        arguments = JsonObject(
          mapOf("script_path" to JsonPrimitive("scripts/run.py")),
        ),
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.DENIED, result.status)
    assertEquals("HIGH_RISK_APPROVAL_REQUIRED", result.errorCode)
    assertEquals("ASK_SAFE_COMMAND_HIGH_RISK", result.metadata["policyReasonCode"])
    assertEquals("HIGH_RISK", result.metadata["approvalRisk"])
    assertEquals(osPath("scripts/run.py"), result.metadata["scriptPath"])
    assertFalse(Files.exists(workspaceRoot.resolve("scripts").resolve("run.py")))
  }

  @Test
  fun approvedToolGrantOnlyAllowsMatchingToolOnRetry() {
    val workspaceRoot = temporaryFolder.newFolder("tool-policy-approved-tool").toPath()
    val dispatcher = OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(workspaceRoot),
        approvedTaskId = "task-approved",
        approvedToolName = "Write",
      ),
    )

    val writeResult = dispatcher.dispatch(
      task = agentTask(
        id = "task-approved",
        metadata = mapOf("chatMode" to "SAFE"),
      ),
      call = AgentToolCall(
        toolName = "Write",
        arguments = JsonObject(
          mapOf(
            "file_path" to JsonPrimitive("notes.txt"),
            "content" to JsonPrimitive("hello"),
          ),
        ),
      ),
      hooks = runtimeHooks(),
    )
    val deleteResult = dispatcher.dispatch(
      task = agentTask(
        id = "task-approved",
        metadata = mapOf("chatMode" to "SAFE"),
      ),
      call = AgentToolCall(
        toolName = "workspace_delete_file",
        arguments = JsonObject(
          mapOf("path" to JsonPrimitive("notes.txt")),
        ),
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, writeResult.status)
    assertEquals("USER_APPROVED_RETRY", writeResult.metadata["policyReasonCode"])
    assertEquals(AgentToolResultStatus.DENIED, deleteResult.status)
    assertEquals("HIGH_RISK_APPROVAL_REQUIRED", deleteResult.errorCode)
  }

  @Test
  fun approvedTaskFallbackStillAllowsRetryWhenToolContextIsUnavailable() {
    val workspaceRoot = temporaryFolder.newFolder("tool-policy-approved-task").toPath()
    val dispatcher = OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(workspaceRoot),
        approvedTaskId = "task-approved",
      ),
    )

    val result = dispatcher.dispatch(
      task = agentTask(
        id = "task-approved",
        metadata = mapOf("chatMode" to "SAFE"),
      ),
      call = AgentToolCall(
        toolName = "Write",
        arguments = JsonObject(
          mapOf(
            "file_path" to JsonPrimitive("notes.txt"),
            "content" to JsonPrimitive("hello"),
          ),
        ),
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertEquals("USER_APPROVED_RETRY", result.metadata["policyReasonCode"])
  }

  @Test
  fun coarseTaskDenyStillOverridesDeveloperModeAllowance() {
    val workspaceRoot = temporaryFolder.newFolder("tool-policy-coarse-deny").toPath()
    val dispatcher = OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(workspaceRoot),
      ),
    )

    val result = dispatcher.dispatch(
      task = agentTask(
        policyDecision = PolicyDecision(
          outcome = PolicyDecisionOutcome.DENY,
          reasonCode = "HOST_DENY",
          detail = "Host denied mutation.",
        ),
        metadata = mapOf("chatMode" to "DEVELOPER"),
      ),
      call = AgentToolCall(
        toolName = "workspace_write_file",
        arguments = JsonObject(
          mapOf(
            "path" to JsonPrimitive("blocked.txt"),
            "content" to JsonPrimitive("nope"),
          ),
        ),
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.DENIED, result.status)
    assertEquals("DENY_POLICY", result.errorCode)
    assertEquals("HOST_DENY", result.metadata["policyReasonCode"])
    assertFalse(Files.exists(workspaceRoot.resolve("blocked.txt")))
  }

  @Test
  fun coarseTaskDenyStillOverridesDeveloperModeEditAllowance() {
    val workspaceRoot = temporaryFolder.newFolder("tool-policy-edit-host-deny").toPath()
    val target = workspaceRoot.resolve("blocked.txt")
    Files.write(target, "before".toByteArray(StandardCharsets.UTF_8))
    val dispatcher = OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(workspaceRoot),
      ),
    )

    val result = dispatcher.dispatch(
      task = agentTask(
        policyDecision = PolicyDecision(
          outcome = PolicyDecisionOutcome.DENY,
          reasonCode = "HOST_DENY",
          detail = "Host denied edit mutation.",
        ),
        metadata = mapOf("chatMode" to "DEVELOPER"),
      ),
      call = AgentToolCall(
        toolName = "Edit",
        arguments = JsonObject(
          mapOf(
            "file_path" to JsonPrimitive("blocked.txt"),
            "old_string" to JsonPrimitive("before"),
            "new_string" to JsonPrimitive("after"),
          ),
        ),
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.DENIED, result.status)
    assertEquals("DENY_POLICY", result.errorCode)
    assertEquals("HOST_DENY", result.metadata["policyReasonCode"])
    assertEquals("before", String(Files.readAllBytes(target), StandardCharsets.UTF_8))
  }

  @Test
  fun coarseTaskDenyStillOverridesDeveloperModePythonAllowance() {
    val workspaceRoot = temporaryFolder.newFolder("tool-policy-python-host-deny").toPath()
    val dispatcher = OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(workspaceRoot),
      ),
    )

    val result = dispatcher.dispatch(
      task = agentTask(
        policyDecision = PolicyDecision(
          outcome = PolicyDecisionOutcome.DENY,
          reasonCode = "HOST_DENY",
          detail = "Host denied python execution.",
        ),
        metadata = mapOf("chatMode" to "DEVELOPER"),
      ),
      call = AgentToolCall(
        toolName = "python_exec",
        arguments = JsonObject(
          mapOf("script_path" to JsonPrimitive("scripts/blocked.py")),
        ),
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.DENIED, result.status)
    assertEquals("DENY_POLICY", result.errorCode)
    assertEquals("HOST_DENY", result.metadata["policyReasonCode"])
    assertEquals(osPath("scripts/blocked.py"), result.metadata["scriptPath"])
  }

  private fun agentTask(
    id: String = "task-${System.nanoTime()}",
    policyDecision: PolicyDecision = PolicyDecision(
      outcome = PolicyDecisionOutcome.ALLOW,
      reasonCode = "HOST_ALLOW",
    ),
    metadata: Map<String, String> = emptyMap(),
  ): AgentTask = AgentTask(
    id = id,
    type = AgentTaskType.TOOL_CALL,
    input = """{"type":"tool_call"}""",
    policyDecision = policyDecision,
    metadata = metadata,
    createdAtEpochMs = 1_000L,
  )

  private fun runtimeHooks(): RuntimeExecutionHooks = RuntimeExecutionHooks(
    isCancellationRequested = { false },
    requestRetry = { _: RetryRequest -> error("Retry not expected in AgentToolPolicyGateTest.") },
  )

  private fun osPath(path: String): String = path.replace('/', File.separatorChar)

  private class RecordingRunner : CommandProcessRunner {
    var spawnCount: Int = 0
      private set

    override fun run(
      commandLine: List<String>,
      workingDirectory: String?,
      config: CommandExecutionConfig,
      hooks: RuntimeExecutionHooks,
    ): CommandSpawnResult {
      spawnCount += 1
      return CommandSpawnResult(
        exitCode = 0,
        stdout = "",
        stderr = "",
        processStarted = true,
      )
    }
  }

  private class RecordingWebContentFetcher : WebContentFetcher {
    var requestCount: Int = 0
      private set

    override fun fetch(request: WebFetchRequest): WebFetchResult {
      requestCount += 1
      return WebFetchResult(
        requestedUrl = request.url,
        finalUrl = request.url,
        statusCode = 200,
        contentType = "text/plain",
        content = "ok",
      )
    }
  }
}
