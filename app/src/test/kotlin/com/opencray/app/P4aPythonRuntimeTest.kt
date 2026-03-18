package com.opencray.app

import com.opencray.core.contracts.ExecutionStatus
import com.opencray.runtime.PythonExecRequest
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class P4aPythonRuntimeTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

  @Test
  fun execWritesBridgeRequestBeforeReturningUnavailable() {
    val runtimeRoot = temporaryFolder.newFolder("python-runtime-unavailable").toPath()
    val workspaceRoot = temporaryFolder.newFolder("workspace-unavailable").toPath()
    val runtime = P4aPythonRuntime.fromRuntimeRoot(runtimeRoot)

    val result = runtime.exec(
      PythonExecRequest(
        taskId = "task-unavailable",
        workspaceRoot = workspaceRoot,
        scriptPath = workspaceRoot.resolve("demo.py"),
        args = listOf("--flag", "value"),
        timeoutMs = 12_000L,
      ),
    )

    assertEquals(ExecutionStatus.FAILED, result.status)
    assertEquals(P4aPythonRuntime.ERROR_P4A_RUNTIME_UNAVAILABLE, result.errorCode)
    assertEquals("p4a", result.metadata["runtimeBackend"])
    assertEquals("file_json_bridge", result.metadata["runtimeTransport"])
    assertEquals("unwired", result.metadata["launcherState"])

    val requestPath = runtimeRoot.resolve("requests").resolve("${result.metadata.getValue("requestId")}.json")
    assertTrue(Files.exists(requestPath))

    val requestJson = json.parseToJsonElement(
      String(Files.readAllBytes(requestPath), StandardCharsets.UTF_8),
    ).jsonObject

    assertEquals(
      P4aPythonRuntime.BRIDGE_SCHEMA_VERSION.toString(),
      requestJson.getValue("schemaVersion").jsonPrimitive.content,
    )
    assertEquals("task-unavailable", requestJson.getValue("taskId").jsonPrimitive.content)
    assertEquals(workspaceRoot.toString(), requestJson.getValue("workspaceRoot").jsonPrimitive.content)
    assertEquals(workspaceRoot.resolve("demo.py").toString(), requestJson.getValue("scriptPath").jsonPrimitive.content)
    assertEquals("12000", requestJson.getValue("timeoutMs").jsonPrimitive.content)
    assertEquals(
      listOf("--flag", "value"),
      requestJson.getValue("args").jsonArray.map { element -> element.jsonPrimitive.content },
    )
  }

  @Test
  fun execReturnsBridgeResultWhenLauncherWritesResultFile() {
    val runtimeRoot = temporaryFolder.newFolder("python-runtime-success").toPath()
    val workspaceRoot = temporaryFolder.newFolder("workspace-success").toPath()
    val launcher = object : P4aPythonRuntime.P4aPythonRuntimeLauncher {
      override fun launch(
        request: P4aPythonRuntime.P4aPythonLaunchRequest,
      ): P4aPythonRuntime.P4aPythonRuntimeLaunchResult {
        val bridgeResult = P4aPythonRuntime.P4aPythonExecBridgeResult(
          requestId = request.bridgeRequest.requestId,
          taskId = request.bridgeRequest.taskId,
          status = "success",
          exitCode = 0,
          stdout = "python ok",
          stderr = "",
          startedAtEpochMs = 100L,
          finishedAtEpochMs = 140L,
          metadata = mapOf("pythonVersion" to "3.11-test"),
        )
        Files.write(
          request.resultPath,
          json.encodeToString(bridgeResult).toByteArray(StandardCharsets.UTF_8),
        )
        return P4aPythonRuntime.P4aPythonRuntimeLaunchResult.Dispatched(
          metadata = mapOf("launcherState" to "test-dispatched"),
        )
      }
    }
    val runtime = P4aPythonRuntime.fromRuntimeRoot(runtimeRoot = runtimeRoot, launcher = launcher, json = json)

    val result = runtime.exec(
      PythonExecRequest(
        taskId = "task-success",
        workspaceRoot = workspaceRoot,
        scriptPath = workspaceRoot.resolve("demo.py"),
        timeoutMs = 12_000L,
      ),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals(0, result.exitCode)
    assertEquals("python ok", result.stdout)
    assertEquals("", result.stderr)
    assertEquals("3.11-test", result.metadata["pythonVersion"])
    assertEquals("test-dispatched", result.metadata["launcherState"])
    assertEquals("p4a", result.metadata["runtimeBackend"])
    assertEquals("file_json_bridge", result.metadata["runtimeTransport"])
    assertEquals(100L, result.startedAtEpochMs)
    assertEquals(140L, result.finishedAtEpochMs)
  }

  @Test
  fun execUsesProvidedRequestIdAndCancelPathInBridgeRequest() {
    val runtimeRoot = temporaryFolder.newFolder("python-runtime-request-id").toPath()
    val workspaceRoot = temporaryFolder.newFolder("workspace-request-id").toPath()
    var capturedRequest: P4aPythonRuntime.P4aPythonLaunchRequest? = null
    val launcher = object : P4aPythonRuntime.P4aPythonRuntimeLauncher {
      override fun launch(
        request: P4aPythonRuntime.P4aPythonLaunchRequest,
      ): P4aPythonRuntime.P4aPythonRuntimeLaunchResult {
        capturedRequest = request
        val bridgeResult = P4aPythonRuntime.P4aPythonExecBridgeResult(
          requestId = request.bridgeRequest.requestId,
          taskId = request.bridgeRequest.taskId,
          status = "success",
          exitCode = 0,
          stdout = "python ok",
          stderr = "",
          startedAtEpochMs = 200L,
          finishedAtEpochMs = 240L,
        )
        Files.write(
          request.resultPath,
          json.encodeToString(bridgeResult).toByteArray(StandardCharsets.UTF_8),
        )
        return P4aPythonRuntime.P4aPythonRuntimeLaunchResult.Dispatched()
      }
    }
    val runtime = P4aPythonRuntime.fromRuntimeRoot(runtimeRoot = runtimeRoot, launcher = launcher, json = json)

    val result = runtime.exec(
      PythonExecRequest(
        taskId = "task-request-id",
        workspaceRoot = workspaceRoot,
        scriptPath = workspaceRoot.resolve("demo.py"),
        timeoutMs = 8_000L,
        requestId = "proc-fixed-id",
      ),
    )

    val launchRequest = checkNotNull(capturedRequest)
    assertEquals("proc-fixed-id", launchRequest.bridgeRequest.requestId)
    assertEquals(runtimeRoot.resolve("cancels").resolve("proc-fixed-id.cancel").toString(), launchRequest.bridgeRequest.cancelPath)
    assertEquals("proc-fixed-id", result.metadata["requestId"])
    assertEquals(runtimeRoot.resolve("cancels").resolve("proc-fixed-id.cancel").toString(), result.metadata["cancelPath"])
  }

  @Test
  fun requestCancellationWritesCancelMarkerFile() {
    val runtimeRoot = temporaryFolder.newFolder("python-runtime-cancel-marker").toPath()
    val runtime = P4aPythonRuntime.fromRuntimeRoot(runtimeRoot = runtimeRoot)

    val accepted = runtime.requestCancellation("proc-cancel-marker")

    assertTrue(accepted)
    assertTrue(Files.exists(runtime.cancelPathFor("proc-cancel-marker")))
  }

  @Test
  fun execAllowsSeparateStartupBudgetBeforeScriptTimeoutBegins() {
    val runtimeRoot = temporaryFolder.newFolder("python-runtime-startup-budget").toPath()
    val workspaceRoot = temporaryFolder.newFolder("workspace-startup-budget").toPath()
    lateinit var runtime: P4aPythonRuntime
    val launcher = object : P4aPythonRuntime.P4aPythonRuntimeLauncher {
      override fun launch(
        request: P4aPythonRuntime.P4aPythonLaunchRequest,
      ): P4aPythonRuntime.P4aPythonRuntimeLaunchResult {
        thread(start = true, isDaemon = true) {
          Thread.sleep(40L)
          Files.createDirectories(runtime.serviceStatePath().parent)
          Files.write(
            runtime.serviceReadyPath(),
            """{"state":"ready","requestId":"${request.bridgeRequest.requestId}"}""".toByteArray(StandardCharsets.UTF_8),
          )
          Files.write(
            runtime.serviceStatePath(),
            """{"state":"processing","currentRequestId":"${request.bridgeRequest.requestId}"}""".toByteArray(StandardCharsets.UTF_8),
          )
          Thread.sleep(40L)
          Files.write(
            request.resultPath,
            json.encodeToString(
              P4aPythonRuntime.P4aPythonExecBridgeResult(
                requestId = request.bridgeRequest.requestId,
                taskId = request.bridgeRequest.taskId,
                status = "success",
                exitCode = 0,
                stdout = "python ok",
                stderr = "",
                startedAtEpochMs = 300L,
                finishedAtEpochMs = 420L,
              ),
            ).toByteArray(StandardCharsets.UTF_8),
          )
        }
        return P4aPythonRuntime.P4aPythonRuntimeLaunchResult.Dispatched(
          metadata = mapOf("launcherState" to "test-dispatched"),
        )
      }
    }
    runtime = P4aPythonRuntime.fromRuntimeRoot(runtimeRoot = runtimeRoot, launcher = launcher, json = json)

    val result = runtime.exec(
      PythonExecRequest(
        taskId = "task-startup-budget",
        workspaceRoot = workspaceRoot,
        scriptPath = workspaceRoot.resolve("demo.py"),
        timeoutMs = 60L,
        startupTimeoutMs = 80L,
      ),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals(0, result.exitCode)
    assertEquals("60", result.metadata["scriptTimeoutMs"])
    assertEquals("80", result.metadata["startupTimeoutMs"])
    assertEquals("test-dispatched", result.metadata["launcherState"])
  }

  @Test
  fun execReturnsStartupTimeoutDiagnosticsWhenServiceNeverBecomesReady() {
    val runtimeRoot = temporaryFolder.newFolder("python-runtime-startup-timeout").toPath()
    val workspaceRoot = temporaryFolder.newFolder("workspace-startup-timeout").toPath()
    val launcher = object : P4aPythonRuntime.P4aPythonRuntimeLauncher {
      override fun launch(
        request: P4aPythonRuntime.P4aPythonLaunchRequest,
      ): P4aPythonRuntime.P4aPythonRuntimeLaunchResult = P4aPythonRuntime.P4aPythonRuntimeLaunchResult.Dispatched(
        metadata = mapOf("launcherState" to "test-dispatched"),
      )
    }
    val runtime = P4aPythonRuntime.fromRuntimeRoot(runtimeRoot = runtimeRoot, launcher = launcher, json = json)

    val result = runtime.exec(
      PythonExecRequest(
        taskId = "task-startup-timeout",
        workspaceRoot = workspaceRoot,
        scriptPath = workspaceRoot.resolve("demo.py"),
        timeoutMs = 20L,
        startupTimeoutMs = 30L,
      ),
    )

    assertEquals(ExecutionStatus.TIMEOUT, result.status)
    assertEquals(P4aPythonRuntime.ERROR_P4A_STARTUP_TIMEOUT, result.errorCode)
    assertEquals("startup", result.metadata["timeoutStage"])
    assertEquals("true", result.metadata["requestExists"])
    assertEquals("false", result.metadata["resultExists"])
    assertEquals("false", result.metadata["serviceReadyExists"])
    assertEquals("false", result.metadata["serviceStateExists"])
    assertTrue(result.stderr.contains("service_ready: exists=false"))
    assertTrue(result.stderr.contains("service_state: exists=false"))
  }

  @Test
  fun execReturnsResultTimeoutDiagnosticsAfterServiceBecomesReady() {
    val runtimeRoot = temporaryFolder.newFolder("python-runtime-result-timeout").toPath()
    val workspaceRoot = temporaryFolder.newFolder("workspace-result-timeout").toPath()
    lateinit var runtime: P4aPythonRuntime
    val launcher = object : P4aPythonRuntime.P4aPythonRuntimeLauncher {
      override fun launch(
        request: P4aPythonRuntime.P4aPythonLaunchRequest,
      ): P4aPythonRuntime.P4aPythonRuntimeLaunchResult {
        Files.createDirectories(runtime.serviceStatePath().parent)
        Files.write(
          runtime.serviceReadyPath(),
          """{"state":"ready","requestId":"${request.bridgeRequest.requestId}"}""".toByteArray(StandardCharsets.UTF_8),
        )
        Files.write(
          runtime.serviceStatePath(),
          """{"state":"processing","currentRequestId":"${request.bridgeRequest.requestId}"}""".toByteArray(StandardCharsets.UTF_8),
        )
        Files.write(
          request.logPath,
          "service booted\nwaiting for result write\n".toByteArray(StandardCharsets.UTF_8),
        )
        return P4aPythonRuntime.P4aPythonRuntimeLaunchResult.Dispatched(
          metadata = mapOf("launcherState" to "test-dispatched"),
        )
      }
    }
    runtime = P4aPythonRuntime.fromRuntimeRoot(runtimeRoot = runtimeRoot, launcher = launcher, json = json)

    val result = runtime.exec(
      PythonExecRequest(
        taskId = "task-result-timeout",
        workspaceRoot = workspaceRoot,
        scriptPath = workspaceRoot.resolve("demo.py"),
        timeoutMs = 40L,
        startupTimeoutMs = 30L,
      ),
    )

    assertEquals(ExecutionStatus.TIMEOUT, result.status)
    assertEquals(P4aPythonRuntime.ERROR_P4A_RESULT_TIMEOUT, result.errorCode)
    assertEquals("result", result.metadata["timeoutStage"])
    assertEquals("true", result.metadata["serviceReadyExists"])
    assertEquals("true", result.metadata["serviceStateExists"])
    assertEquals("true", result.metadata["logExists"])
    assertEquals("true", result.metadata["serviceReadyObserved"])
    assertTrue(result.stderr.contains("service_state_preview:"))
    assertTrue(result.stderr.contains("log_tail:"))
  }
}
