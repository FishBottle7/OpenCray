package com.opencray.app

import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.orchestrator.RetryRequest
import com.opencray.core.orchestrator.RuntimeExecutionHooks
import com.opencray.runtime.CancellablePythonScriptRuntime
import com.opencray.runtime.CommandExecutionConfig
import com.opencray.runtime.PythonExecRequest
import com.opencray.runtime.PythonScriptRuntime
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PythonBackedCommandExecutionTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

  @Test
  fun commandRunnerParsesStructuredPayloadFromPythonRuntime() {
    val workspaceRoot = temporaryFolder.newFolder("python-backed-command-runner").toPath()
    val runtime = RecordingPythonRuntime(
      result = ExecutionResult(
        taskId = "task-command",
        status = ExecutionStatus.SUCCESS,
        exitCode = 0,
        stdout = encodedPayload(
          CommandWrapperResultPayload(
            exitCode = 7,
            stdout = "sandbox stdout",
            stderr = "sandbox stderr",
            processStarted = true,
            timedOut = false,
            cancelled = false,
            outputLimitExceeded = false,
          ),
        ),
        stderr = "",
        startedAtEpochMs = 100L,
        finishedAtEpochMs = 200L,
        metadata = mapOf("runtimeBackend" to "e2b_code_interpreter"),
      ),
    )
    val runner = PythonBackedCommandProcessRunner(
      workspaceRoot = workspaceRoot,
      pythonRuntime = runtime,
      json = json,
    )

    val result = runner.run(
      commandLine = listOf("git", "status"),
      workingDirectory = workspaceRoot.toString(),
      config = CommandExecutionConfig(timeoutMs = 5_000L),
      hooks = hooks(),
    )

    val request = runtime.lastRequest
    assertNotNull(request)
    assertTrue(requireNotNull(request).scriptPath.toString().contains(".opencray"))
    assertTrue(requireNotNull(runtime.lastScriptSource).contains("local_workspace_root = payload['localWorkspaceRoot']"))
    assertTrue(requireNotNull(runtime.lastScriptSource).contains("resolve_working_directory"))
    val payload = decodePayload(requireNotNull(request).args.single())
    assertEquals(workspaceRoot.toString(), payload.workingDirectory)
    assertEquals(workspaceRoot.toString(), payload.localWorkspaceRoot)
    assertEquals(7, result.exitCode)
    assertEquals("sandbox stdout", result.stdout)
    assertEquals("sandbox stderr", result.stderr)
    assertEquals("e2b_code_interpreter", result.metadata["runtimeBackend"])
  }

  @Test
  fun managedCommandControllerRequestsCancellationThroughPythonRuntime() {
    val workspaceRoot = temporaryFolder.newFolder("python-backed-managed-command").toPath()
    val runtime = BlockingCancellablePythonRuntime(json)
    val controller = SandboxPythonManagedCommandControllerFactory(
      workspaceRoot = workspaceRoot,
      pythonRuntime = runtime,
      json = json,
    ).start(
      com.opencray.runtime.process.ManagedProcessStartRequest(
        processId = "proc-cancel",
        taskId = "task-cancel",
        command = "npm",
        args = listOf("run", "dev"),
        workingDirectory = workspaceRoot.toString(),
        timeoutMs = 5_000L,
        requestedAtEpochMs = 1_000L,
      ),
    )

    assertTrue(runtime.started.await(2, TimeUnit.SECONDS))

    val terminateSnapshot = controller.terminate()
    assertEquals("true", terminateSnapshot.metadata["terminationRequested"])
    assertEquals("true", terminateSnapshot.metadata["terminationRequestAccepted"])

    runtime.finish.countDown()
    val waited = controller.await(2_000L)
    assertEquals(com.opencray.runtime.process.ManagedProcessStatus.CANCELLED, waited.status)
    assertEquals("CANCELLED", waited.errorCode)
  }

  private fun hooks(): RuntimeExecutionHooks = RuntimeExecutionHooks(
    isCancellationRequested = { false },
    requestRetry = { _: RetryRequest -> error("Retry not expected in PythonBackedCommandExecutionTest.") },
  )

  private fun encodedPayload(payload: CommandWrapperResultPayload): String {
    val encoded = Base64.getEncoder().encodeToString(
      json.encodeToString(CommandWrapperResultPayload.serializer(), payload).toByteArray(StandardCharsets.UTF_8),
    )
    return "$COMMAND_RESULT_PREFIX$encoded"
  }

  private fun decodePayload(encodedPayload: String): CommandWrapperPayload {
    val decoded = String(Base64.getDecoder().decode(encodedPayload), StandardCharsets.UTF_8)
    return json.decodeFromString(CommandWrapperPayload.serializer(), decoded)
  }

  private class RecordingPythonRuntime(
    private val result: ExecutionResult,
  ) : PythonScriptRuntime {
    var lastRequest: PythonExecRequest? = null
    var lastScriptSource: String? = null

    override fun exec(request: PythonExecRequest): ExecutionResult {
      lastRequest = request
      lastScriptSource = String(Files.readAllBytes(request.scriptPath), StandardCharsets.UTF_8)
      return result.copy(taskId = request.taskId)
    }
  }

  private class BlockingCancellablePythonRuntime(
    private val json: Json,
  ) : PythonScriptRuntime, CancellablePythonScriptRuntime {
    val started = CountDownLatch(1)
    val finish = CountDownLatch(1)
    private val cancellationRequested = AtomicBoolean(false)

    override fun exec(request: PythonExecRequest): ExecutionResult {
      started.countDown()
      finish.await(2, TimeUnit.SECONDS)
      return if (cancellationRequested.get()) {
        ExecutionResult(
          taskId = request.taskId,
          status = ExecutionStatus.CANCELLED,
          exitCode = null,
          stdout = "",
          stderr = "",
          errorCode = "CANCELLED",
          errorMessage = "cancelled",
          startedAtEpochMs = 100L,
          finishedAtEpochMs = 200L,
        )
      } else {
        ExecutionResult(
          taskId = request.taskId,
          status = ExecutionStatus.SUCCESS,
          exitCode = 0,
          stdout = "$COMMAND_RESULT_PREFIX${
            Base64.getEncoder().encodeToString(
              json.encodeToString(
                CommandWrapperResultPayload.serializer(),
                CommandWrapperResultPayload(
                  exitCode = 0,
                  stdout = "done",
                  stderr = "",
                  processStarted = true,
                ),
              ).toByteArray(StandardCharsets.UTF_8),
            )
          }",
          stderr = "",
          startedAtEpochMs = 100L,
          finishedAtEpochMs = 200L,
        )
      }
    }

    override fun requestCancellation(requestId: String): Boolean {
      cancellationRequested.set(true)
      return true
    }
  }
}
