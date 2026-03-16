package com.opencray.runtime

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.RetryRequest
import com.opencray.core.orchestrator.RuntimeExecutionHooks
import com.opencray.runtime.process.AgentProcessRegistry
import com.opencray.runtime.process.ManagedProcessSnapshot
import com.opencray.runtime.process.ManagedProcessStartRequest
import com.opencray.runtime.process.ManagedProcessStatus
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentManagedProcessToolTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun safeModeProcessStartRequiresHighRiskApprovalBeforeSpawn() {
    val workspaceRoot = temporaryFolder.newFolder("process-tool-safe").toPath()
    val registry = RecordingProcessRegistry(workspaceRoot = workspaceRoot)
    val dispatcher = OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(workspaceRoot),
        processRegistry = registry,
      ),
    )

    val result = dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "SAFE")),
      call = AgentToolCall(
        toolName = "ProcessStart",
        arguments = JsonObject(
          mapOf(
            "command" to JsonPrimitive("npm"),
            "args" to kotlinx.serialization.json.buildJsonArray {
              add(JsonPrimitive("run"))
              add(JsonPrimitive("dev"))
            },
          ),
        ),
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.DENIED, result.status)
    assertEquals("HIGH_RISK_APPROVAL_REQUIRED", result.errorCode)
    assertEquals("HIGH_RISK", result.metadata["approvalRisk"])
    assertEquals(0, registry.startCount)
  }

  @Test
  fun safeModeBashRequiresHighRiskApprovalBeforeSpawn() {
    val workspaceRoot = temporaryFolder.newFolder("bash-tool-safe").toPath()
    val registry = RecordingProcessRegistry(workspaceRoot = workspaceRoot)
    val dispatcher = OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(workspaceRoot),
        processRegistry = registry,
      ),
    )

    val result = dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "SAFE")),
      call = AgentToolCall(
        toolName = "Bash",
        arguments = JsonObject(
          mapOf("command" to JsonPrimitive("Get-ChildItem")),
        ),
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.DENIED, result.status)
    assertEquals("HIGH_RISK_APPROVAL_REQUIRED", result.errorCode)
    assertEquals("HIGH_RISK", result.metadata["approvalRisk"])
    assertEquals(0, registry.startCount)
  }

  @Test
  fun developerModeManagedProcessToolsCanStartReadWaitListAndTerminate() {
    val workspaceRoot = temporaryFolder.newFolder("process-tool-developer").toPath()
    val registry = RecordingProcessRegistry(workspaceRoot = workspaceRoot)
    val dispatcher = OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(workspaceRoot),
        processRegistry = registry,
      ),
    )

    val startResult = dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "ProcessStart",
        arguments = JsonObject(
          mapOf(
            "command" to JsonPrimitive("npm"),
            "args" to kotlinx.serialization.json.buildJsonArray {
              add(JsonPrimitive("run"))
              add(JsonPrimitive("dev"))
            },
            "working_directory" to JsonPrimitive("."),
            "timeout_ms" to JsonPrimitive(120000),
          ),
        ),
      ),
      hooks = runtimeHooks(),
    )
    val processId = requireNotNull(startResult.metadata["processId"])

    val readResult = dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "ProcessRead",
        arguments = JsonObject(mapOf("process_id" to JsonPrimitive(processId))),
      ),
      hooks = runtimeHooks(),
    )
    val waitResult = dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "ProcessWait",
        arguments = JsonObject(
          mapOf(
            "process_id" to JsonPrimitive(processId),
            "timeout_ms" to JsonPrimitive(250),
          ),
        ),
      ),
      hooks = runtimeHooks(),
    )
    val listResult = dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(toolName = "ProcessList"),
      hooks = runtimeHooks(),
    )
    val terminateResult = dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "ProcessTerminate",
        arguments = JsonObject(mapOf("process_id" to JsonPrimitive(processId))),
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, startResult.status)
    assertEquals(1, registry.startCount)
    assertEquals("ALLOW_DEVELOPER_OVERRIDE", startResult.metadata["policyReasonCode"])
    assertEquals("RUNNING", startResult.metadata["processStatus"])
    assertTrue(startResult.content.contains("process_id=$processId"))
    assertTrue(readResult.content.contains("status=running"))
    assertEquals("server ready", registry.waitSnapshots.single().stdout)
    assertTrue(waitResult.content.contains("status=success"))
    assertTrue(waitResult.content.contains("[stdout]"))
    assertTrue(waitResult.content.contains("server ready"))
    assertTrue(listResult.content.contains(processId))
    assertTrue(terminateResult.content.contains("process_id=$processId"))
    assertEquals(1, registry.terminateCount)
  }

  @Test
  fun developerModeProcessStartCanLaunchManagedPythonScript() {
    val workspaceRoot = temporaryFolder.newFolder("process-tool-python").toPath()
    Files.createDirectories(workspaceRoot.resolve("scripts"))
    Files.write(
      workspaceRoot.resolve("scripts").resolve("run.py"),
      "print('hello')".toByteArray(StandardCharsets.UTF_8),
    )
    val registry = RecordingProcessRegistry(workspaceRoot = workspaceRoot)
    val dispatcher = OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(workspaceRoot),
        processRegistry = registry,
      ),
    )

    val startResult = dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "ProcessStart",
        arguments = JsonObject(
          mapOf(
            "script_path" to JsonPrimitive("scripts/run.py"),
            "args" to kotlinx.serialization.json.buildJsonArray {
              add(JsonPrimitive("--flag"))
            },
            "python_executable" to JsonPrimitive("python3"),
            "timeout_ms" to JsonPrimitive(5000),
          ),
        ),
      ),
      hooks = runtimeHooks(),
    )

    val startRequest = registry.startRequests.single()
    assertEquals(AgentToolResultStatus.SUCCESS, startResult.status)
    assertEquals("python3", startRequest.command)
    assertEquals(listOf("-m", "python_runner.runner", "exec"), startRequest.args.take(3))
    val workspaceArgIndex = startRequest.args.indexOf("--workspace")
    assertTrue(workspaceArgIndex >= 0)
    assertEquals(startRequest.workingDirectory, startRequest.args[workspaceArgIndex + 1])
    assertTrue(startRequest.args.contains("--script"))
    assertTrue(startRequest.args.any { argument -> argument.endsWith("scripts${java.io.File.separator}run.py") })
    assertTrue(startRequest.args.contains("--timeout-seconds"))
    assertTrue(startRequest.args.contains("5.0"))
    assertEquals(listOf("--", "--flag"), startRequest.args.takeLast(2))
    assertTrue(startRequest.workingDirectory.orEmpty().endsWith(workspaceRoot.fileName.toString()))
    assertEquals("python_exec", startRequest.metadata["runtimeKind"])
    assertEquals("scripts/run.py", startRequest.metadata["scriptPath"])
    assertEquals("python3", startRequest.metadata["pythonExecutable"])
    assertEquals("scripts/run.py", startResult.metadata["scriptPath"])
    assertTrue(startResult.content.contains("runtime_kind=python_exec"))
    assertTrue(startResult.content.contains("script_path=scripts/run.py"))
    assertTrue(startResult.content.contains("python_executable=python3"))
  }

  @Test
  fun processReadFailsCleanlyWhenProcessIsMissing() {
    val workspaceRoot = temporaryFolder.newFolder("process-tool-missing").toPath()
    val dispatcher = OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(workspaceRoot),
        processRegistry = RecordingProcessRegistry(workspaceRoot = workspaceRoot),
      ),
    )

    val result = dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "ProcessRead",
        arguments = JsonObject(mapOf("process_id" to JsonPrimitive("proc-missing"))),
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.FAILED, result.status)
    assertEquals("PROCESS_NOT_FOUND", result.errorCode)
    assertTrue(result.content.contains("proc-missing"))
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
    requestRetry = { _: RetryRequest -> error("Retry not expected in AgentManagedProcessToolTest.") },
  )

  private class RecordingProcessRegistry(
    private val workspaceRoot: Path,
  ) : AgentProcessRegistry {
    val startRequests = mutableListOf<ManagedProcessStartRequest>()
    val waitSnapshots = mutableListOf<ManagedProcessSnapshot>()
    var startCount: Int = 0
      private set
    var terminateCount: Int = 0
      private set
    private val snapshotsById = linkedMapOf<String, ManagedProcessSnapshot>()

    override fun start(request: ManagedProcessStartRequest): ManagedProcessSnapshot {
      startCount += 1
      startRequests += request
      val snapshot = ManagedProcessSnapshot(
        processId = request.processId,
        taskId = request.taskId,
        command = request.command,
        args = request.args,
        workingDirectory = request.workingDirectory,
        status = ManagedProcessStatus.RUNNING,
        processStarted = true,
        timeoutMs = request.timeoutMs,
        startedAtEpochMs = 1_000L,
        updatedAtEpochMs = 1_000L,
        metadata = request.metadata,
      )
      snapshotsById[request.processId] = snapshot
      return snapshot
    }

    override fun list(): List<ManagedProcessSnapshot> = snapshotsById.values.toList()

    override fun read(processId: String): ManagedProcessSnapshot? = snapshotsById[processId]

    override fun wait(processId: String, timeoutMs: Long): ManagedProcessSnapshot? {
      val existing = snapshotsById[processId] ?: return null
      val waited = existing.copy(
        status = ManagedProcessStatus.SUCCESS,
        stdout = "server ready",
        exitCode = 0,
        updatedAtEpochMs = existing.updatedAtEpochMs + timeoutMs,
        finishedAtEpochMs = existing.updatedAtEpochMs + timeoutMs,
      )
      snapshotsById[processId] = waited
      waitSnapshots += waited
      return waited
    }

    override fun terminate(processId: String): ManagedProcessSnapshot? {
      terminateCount += 1
      val existing = snapshotsById[processId] ?: return null
      val terminated = existing.copy(
        status = ManagedProcessStatus.CANCELLED,
        exitCode = 137,
        errorCode = "CANCELLED",
        errorMessage = "Managed process terminated.",
        updatedAtEpochMs = existing.updatedAtEpochMs + 1,
        finishedAtEpochMs = existing.updatedAtEpochMs + 1,
        cancelled = true,
      )
      snapshotsById[processId] = terminated
      return terminated
    }
  }
}
