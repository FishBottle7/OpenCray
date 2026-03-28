package com.opencray.runtime

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.RetryRequest
import com.opencray.core.orchestrator.RuntimeExecutionHooks
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExecutionAttachmentArtifactSummaryTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

  @Test
  fun pythonExecAppendsAttachmentArtifactSummaryFromExecutionMetadata() {
    val workspaceRoot = temporaryFolder.newFolder("python-exec-attachment-summary").toPath()
    Files.createDirectories(workspaceRoot.resolve("scripts"))
    Files.write(
      workspaceRoot.resolve("scripts/run.py"),
      "print('hello from python attachment summary')".toByteArray(StandardCharsets.UTF_8),
    )
    val artifacts = OpenCrayAttachmentArtifacts.fromWorkspaceRelativePaths(
      listOf("reports/result.json", "artifacts/chart.png"),
    )
    val dispatcher = OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(workspaceRoot),
        pythonRuntimeAdapter = object : PythonScriptRuntime {
          override fun exec(request: PythonExecRequest): ExecutionResult = ExecutionResult(
            taskId = request.taskId,
            status = ExecutionStatus.SUCCESS,
            exitCode = 0,
            stdout = "runtime-ok",
            stderr = "",
            startedAtEpochMs = 10L,
            finishedAtEpochMs = 20L,
            metadata = OpenCrayAttachmentArtifacts.encodeMetadata(json, artifacts) + mapOf(
              "runtimeBackend" to "recording-runtime",
            ),
          )
        },
      ),
    )

    val result = dispatcher.dispatch(
      task = developerTask(),
      call = AgentToolCall(
        toolName = "python_exec",
        arguments = JsonObject(mapOf("script_path" to JsonPrimitive("scripts/run.py"))),
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertTrue(result.content.contains("runtime-ok"))
    assertTrue(result.content.contains("Workspace artifact(s) available:"))
    assertTrue(result.content.contains("artifact_id=${artifacts.first().artifactId}"))
    assertTrue(result.content.contains("relative_path=${artifacts.first().relativePath}"))
    assertEquals(
      artifacts.first().artifactId,
      result.metadata[OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_ID],
    )
  }

  @Test
  fun commandExecAppendsAttachmentArtifactSummaryFromExecutionMetadata() {
    val workspaceRoot = temporaryFolder.newFolder("command-exec-attachment-summary").toPath()
    val artifacts = OpenCrayAttachmentArtifacts.fromWorkspaceRelativePaths(
      listOf("build/report.txt"),
    )
    val dispatcher = OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(workspaceRoot),
        commandExecutor = object : CommandExecutor() {
          override fun execute(
            request: CommandExecutionRequest,
            policyDecision: PolicyDecision,
            approvalToken: CommandApprovalToken?,
            hooks: RuntimeExecutionHooks,
          ): ExecutionResult = ExecutionResult(
            taskId = request.taskId,
            status = ExecutionStatus.SUCCESS,
            exitCode = 0,
            stdout = "command-ok",
            stderr = "",
            policyDecision = policyDecision,
            startedAtEpochMs = 30L,
            finishedAtEpochMs = 40L,
            metadata = request.metadata + OpenCrayAttachmentArtifacts.encodeMetadata(json, artifacts),
          )
        },
      ),
    )

    val result = dispatcher.dispatch(
      task = developerTask(),
      call = AgentToolCall(
        toolName = "command_exec",
        arguments = JsonObject(mapOf("command" to JsonPrimitive("git"))),
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertTrue(result.content.contains("command-ok"))
    assertTrue(result.content.contains("artifact_id=${artifacts.first().artifactId}"))
    assertTrue(result.content.contains("relative_path=${artifacts.first().relativePath}"))
    assertEquals(
      artifacts.first().relativePath,
      result.metadata[OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_RELATIVE_PATH],
    )
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
    requestRetry = { _: RetryRequest -> error("Retry not expected in ExecutionAttachmentArtifactSummaryTest.") },
  )
}
