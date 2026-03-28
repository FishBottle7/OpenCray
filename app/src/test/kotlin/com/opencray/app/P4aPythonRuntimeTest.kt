package com.opencray.app

import com.opencray.core.contracts.ExecutionStatus
import com.opencray.runtime.PythonExecRequest
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.thread
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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
  fun execWritesFallbackServiceStartArgumentBeforeLaunch() {
    val runtimeRoot = temporaryFolder.newFolder("python-runtime-start-argument").toPath()
    val workspaceRoot = temporaryFolder.newFolder("workspace-start-argument").toPath()
    lateinit var runtime: P4aPythonRuntime
    var capturedStartArgumentJson = ""
    val launcher = object : P4aPythonRuntime.P4aPythonRuntimeLauncher {
      override fun launch(
        request: P4aPythonRuntime.P4aPythonLaunchRequest,
      ): P4aPythonRuntime.P4aPythonRuntimeLaunchResult {
        val startArgumentPath = runtime.serviceStartArgumentPath()
        assertTrue(Files.exists(startArgumentPath))
        capturedStartArgumentJson = String(Files.readAllBytes(startArgumentPath), StandardCharsets.UTF_8)
        return P4aPythonRuntime.P4aPythonRuntimeLaunchResult.Unavailable(
          errorCode = P4aPythonRuntime.ERROR_P4A_RUNTIME_UNAVAILABLE,
          errorMessage = "runtime unavailable",
        )
      }
    }
    runtime = P4aPythonRuntime.fromRuntimeRoot(runtimeRoot = runtimeRoot, launcher = launcher, json = json)

    val result = runtime.exec(
      PythonExecRequest(
        taskId = "task-start-argument",
        workspaceRoot = workspaceRoot,
        scriptPath = workspaceRoot.resolve("demo.py"),
        timeoutMs = 12_000L,
        requestId = "proc-start-argument",
      ),
    )

    val startArgumentJson = json.parseToJsonElement(capturedStartArgumentJson).jsonObject
    assertEquals(
      runtimeRoot.toString(),
      startArgumentJson.getValue("runtimeRoot").jsonPrimitive.content,
    )
    assertEquals(
      "proc-start-argument",
      startArgumentJson.getValue("requestId").jsonPrimitive.content,
    )
    assertEquals(
      runtimeRoot.resolve("requests/proc-start-argument.json").toString(),
      startArgumentJson.getValue("requestPath").jsonPrimitive.content,
    )
    assertEquals(
      runtimeRoot.resolve("results/proc-start-argument.json").toString(),
      startArgumentJson.getValue("resultPath").jsonPrimitive.content,
    )
    assertEquals(
      runtimeRoot.resolve("logs/proc-start-argument.log").toString(),
      startArgumentJson.getValue("logPath").jsonPrimitive.content,
    )
    assertEquals("100", startArgumentJson.getValue("pollIntervalMs").jsonPrimitive.content)
    assertEquals("false", startArgumentJson.getValue("once").jsonPrimitive.content)
    assertEquals(
      runtime.serviceStartArgumentPath().toString(),
      result.metadata["serviceStartArgumentPath"],
    )
  }

  @Test
  fun execWritesBridgeRequestBeforeReturningUnavailable() {
    val runtimeRoot = temporaryFolder.newFolder("python-runtime-unavailable").toPath()
    val workspaceRoot = temporaryFolder.newFolder("workspace-unavailable").toPath()
    var capturedRequestJson = ""
    val launcher = object : P4aPythonRuntime.P4aPythonRuntimeLauncher {
      override fun launch(
        request: P4aPythonRuntime.P4aPythonLaunchRequest,
      ): P4aPythonRuntime.P4aPythonRuntimeLaunchResult {
        capturedRequestJson = String(Files.readAllBytes(request.requestPath), StandardCharsets.UTF_8)
        return P4aPythonRuntime.P4aPythonRuntimeLaunchResult.Unavailable(
          errorCode = P4aPythonRuntime.ERROR_P4A_RUNTIME_UNAVAILABLE,
          errorMessage = "runtime unavailable",
          metadata = mapOf("launcherState" to "test-unavailable"),
        )
      }
    }
    val runtime = P4aPythonRuntime.fromRuntimeRoot(runtimeRoot = runtimeRoot, launcher = launcher, json = json)

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
    assertEquals("test-unavailable", result.metadata["launcherState"])

    val requestPath = runtimeRoot.resolve("requests").resolve("${result.metadata.getValue("requestId")}.json")
    val cancelPath = runtimeRoot.resolve("cancels").resolve("${result.metadata.getValue("requestId")}.cancel")
    assertTrue(Files.notExists(requestPath))
    assertTrue(Files.exists(cancelPath))
    assertEquals("true", result.metadata["cleanupRequestDeleted"])
    assertEquals("true", result.metadata["cleanupCancelMarkerWritten"])

    val requestJson = json.parseToJsonElement(
      capturedRequestJson,
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
    var stopCalls = 0
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

      override fun stop(): Map<String, String> {
        stopCalls += 1
        return mapOf("launcherStopState" to "stop_requested")
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
    assertEquals("100", result.metadata["servicePollIntervalMs"])
    assertEquals("persistent_stop_after_result", result.metadata["serviceRunMode"])
    assertEquals("stop_requested", result.metadata["launcherStopState"])
    assertEquals(1, stopCalls)
    assertEquals(100L, result.startedAtEpochMs)
    assertEquals(140L, result.finishedAtEpochMs)
    assertTrue(Files.notExists(runtimeRoot.resolve("requests").resolve("${result.metadata.getValue("requestId")}.json")))
  }

  @Test
  fun execUsesProvidedRequestIdAndCancelPathInBridgeRequest() {
    val runtimeRoot = temporaryFolder.newFolder("python-runtime-request-id").toPath()
    val workspaceRoot = temporaryFolder.newFolder("workspace-request-id").toPath()
    var capturedRequest: P4aPythonRuntime.P4aPythonLaunchRequest? = null
    var stopCalls = 0
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

      override fun stop(): Map<String, String> {
        stopCalls += 1
        return mapOf("launcherStopState" to "stop_requested")
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
    assertEquals(100L, launchRequest.servicePollIntervalMs)
    assertTrue(!launchRequest.runOnce)
    assertEquals("stop_requested", result.metadata["launcherStopState"])
    assertEquals(1, stopCalls)
    assertEquals("proc-fixed-id", result.metadata["requestId"])
    assertEquals(runtimeRoot.resolve("cancels").resolve("proc-fixed-id.cancel").toString(), result.metadata["cancelPath"])
    assertTrue(Files.notExists(runtimeRoot.resolve("requests").resolve("proc-fixed-id.json")))
  }

  @Test
  fun execCleansUpRequestArtifactsWhenLauncherThrowsAfterRequestWrite() {
    val runtimeRoot = temporaryFolder.newFolder("python-runtime-launcher-throw").toPath()
    val workspaceRoot = temporaryFolder.newFolder("workspace-launcher-throw").toPath()
    val launcher = object : P4aPythonRuntime.P4aPythonRuntimeLauncher {
      override fun launch(
        request: P4aPythonRuntime.P4aPythonLaunchRequest,
      ): P4aPythonRuntime.P4aPythonRuntimeLaunchResult {
        assertTrue(Files.exists(request.requestPath))
        throw IllegalStateException("launcher boom")
      }
    }
    val runtime = P4aPythonRuntime.fromRuntimeRoot(runtimeRoot = runtimeRoot, launcher = launcher, json = json)

    val result = runtime.exec(
      PythonExecRequest(
        taskId = "task-launcher-throw",
        workspaceRoot = workspaceRoot,
        scriptPath = workspaceRoot.resolve("demo.py"),
        timeoutMs = 8_000L,
      ),
    )

    val requestId = result.metadata.getValue("requestId")
    assertEquals(ExecutionStatus.FAILED, result.status)
    assertEquals(P4aPythonRuntime.ERROR_P4A_REQUEST_PREPARATION_FAILED, result.errorCode)
    assertEquals("true", result.metadata["cleanupRequestDeleted"])
    assertEquals("true", result.metadata["cleanupCancelMarkerWritten"])
    assertTrue(Files.notExists(runtimeRoot.resolve("requests/$requestId.json")))
    assertTrue(Files.exists(runtimeRoot.resolve("cancels/$requestId.cancel")))
  }

  @Test
  fun execCleansUpRequestArtifactsWhenBridgeResultParsingFails() {
    val runtimeRoot = temporaryFolder.newFolder("python-runtime-parse-failed").toPath()
    val workspaceRoot = temporaryFolder.newFolder("workspace-parse-failed").toPath()
    var stopCalls = 0
    val launcher = object : P4aPythonRuntime.P4aPythonRuntimeLauncher {
      override fun launch(
        request: P4aPythonRuntime.P4aPythonLaunchRequest,
      ): P4aPythonRuntime.P4aPythonRuntimeLaunchResult {
        Files.write(
          request.resultPath,
          "{not-json}\n".toByteArray(StandardCharsets.UTF_8),
        )
        return P4aPythonRuntime.P4aPythonRuntimeLaunchResult.Dispatched(
          metadata = mapOf("launcherState" to "test-dispatched"),
        )
      }

      override fun stop(): Map<String, String> {
        stopCalls += 1
        return mapOf("launcherStopState" to "stop_requested")
      }
    }
    val runtime = P4aPythonRuntime.fromRuntimeRoot(runtimeRoot = runtimeRoot, launcher = launcher, json = json)

    val result = runtime.exec(
      PythonExecRequest(
        taskId = "task-parse-failed",
        workspaceRoot = workspaceRoot,
        scriptPath = workspaceRoot.resolve("demo.py"),
        timeoutMs = 8_000L,
      ),
    )

    val requestId = result.metadata.getValue("requestId")
    assertEquals(ExecutionStatus.FAILED, result.status)
    assertEquals(P4aPythonRuntime.ERROR_P4A_RESULT_PARSE_FAILED, result.errorCode)
    assertEquals("stop_requested", result.metadata["launcherStopState"])
    assertEquals(1, stopCalls)
    assertEquals("true", result.metadata["cleanupRequestDeleted"])
    assertTrue(Files.notExists(runtimeRoot.resolve("requests/$requestId.json")))
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
          val executionStartedAt = System.currentTimeMillis()
          writeServiceMarker(
            path = runtime.serviceReadyPath(),
            state = "processing",
            startupRequestId = request.bridgeRequest.requestId,
            currentRequestId = request.bridgeRequest.requestId,
            claimedRequestId = request.bridgeRequest.requestId,
            executionStartedAtEpochMs = executionStartedAt,
          )
          writeServiceMarker(
            path = runtime.serviceStatePath(),
            state = "processing",
            startupRequestId = request.bridgeRequest.requestId,
            currentRequestId = request.bridgeRequest.requestId,
            claimedRequestId = request.bridgeRequest.requestId,
            executionStartedAtEpochMs = executionStartedAt,
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
        timeoutMs = 120L,
        startupTimeoutMs = 160L,
      ),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals(0, result.exitCode)
    assertEquals("120", result.metadata["scriptTimeoutMs"])
    assertEquals("160", result.metadata["startupTimeoutMs"])
    assertEquals("25", result.metadata["servicePollIntervalMs"])
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
    assertEquals("true", result.metadata["cleanupRequestDeleted"])
    assertEquals("true", result.metadata["cleanupCancelMarkerWritten"])
    assertTrue(result.stderr.contains("service_ready: exists=false"))
    assertTrue(result.stderr.contains("service_state: exists=false"))
  }

  @Test
  fun execDoesNotSpendStartupBudgetDuringLauncherPreparation() {
    val runtimeRoot = temporaryFolder.newFolder("python-runtime-launcher-delay").toPath()
    val workspaceRoot = temporaryFolder.newFolder("workspace-launcher-delay").toPath()
    lateinit var runtime: P4aPythonRuntime
    val launcher = object : P4aPythonRuntime.P4aPythonRuntimeLauncher {
      override fun launch(
        request: P4aPythonRuntime.P4aPythonLaunchRequest,
      ): P4aPythonRuntime.P4aPythonRuntimeLaunchResult {
        Thread.sleep(120L)
        thread(start = true, isDaemon = true) {
          Thread.sleep(20L)
          val executionStartedAt = System.currentTimeMillis()
          writeServiceMarker(
            path = runtime.serviceReadyPath(),
            state = "processing",
            startupRequestId = request.bridgeRequest.requestId,
            currentRequestId = request.bridgeRequest.requestId,
            claimedRequestId = request.bridgeRequest.requestId,
            executionStartedAtEpochMs = executionStartedAt,
          )
          writeServiceMarker(
            path = runtime.serviceStatePath(),
            state = "processing",
            startupRequestId = request.bridgeRequest.requestId,
            currentRequestId = request.bridgeRequest.requestId,
            claimedRequestId = request.bridgeRequest.requestId,
            executionStartedAtEpochMs = executionStartedAt,
          )
          Files.write(
            request.resultPath,
            json.encodeToString(
              P4aPythonRuntime.P4aPythonExecBridgeResult(
                requestId = request.bridgeRequest.requestId,
                taskId = request.bridgeRequest.taskId,
                status = "success",
                exitCode = 0,
                stdout = "python ok after prepare",
                stderr = "",
                startedAtEpochMs = executionStartedAt,
                finishedAtEpochMs = executionStartedAt + 10L,
              ),
            ).toByteArray(StandardCharsets.UTF_8),
          )
        }
        return P4aPythonRuntime.P4aPythonRuntimeLaunchResult.Dispatched(
          metadata = mapOf("launcherState" to "test-delayed-dispatch"),
        )
      }
    }
    runtime = P4aPythonRuntime.fromRuntimeRoot(runtimeRoot = runtimeRoot, launcher = launcher, json = json)

    val result = runtime.exec(
      PythonExecRequest(
        taskId = "task-launcher-delay",
        workspaceRoot = workspaceRoot,
        scriptPath = workspaceRoot.resolve("demo.py"),
        timeoutMs = 40L,
        startupTimeoutMs = 30L,
      ),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals(0, result.exitCode)
    assertEquals("python ok after prepare", result.stdout)
    assertEquals("test-delayed-dispatch", result.metadata["launcherState"])
    assertTrue((result.metadata.getValue("launcherDispatchDurationMs").toLong()) >= 100L)
    assertEquals(
      result.metadata["launcherDispatchCompletedAtEpochMs"],
      result.metadata["startupTimerStartedAtEpochMs"],
    )
  }

  @Test
  fun execReturnsQueueTimeoutDiagnosticsWhenAnotherRequestBlocksClaim() {
    val runtimeRoot = temporaryFolder.newFolder("python-runtime-queue-timeout").toPath()
    val workspaceRoot = temporaryFolder.newFolder("workspace-queue-timeout").toPath()
    lateinit var runtime: P4aPythonRuntime
    val launcher = object : P4aPythonRuntime.P4aPythonRuntimeLauncher {
      override fun launch(
        request: P4aPythonRuntime.P4aPythonLaunchRequest,
      ): P4aPythonRuntime.P4aPythonRuntimeLaunchResult {
        thread(start = true, isDaemon = true) {
          Thread.sleep(10L)
          val foreignRequestId = "foreign-running-request"
          val foreignStartedAt = System.currentTimeMillis()
          writeServiceMarker(
            path = runtime.serviceReadyPath(),
            state = "processing",
            startupRequestId = request.bridgeRequest.requestId,
            currentRequestId = foreignRequestId,
            claimedRequestId = foreignRequestId,
            executionStartedAtEpochMs = foreignStartedAt,
            updatedAtEpochMs = foreignStartedAt,
          )
          writeServiceMarker(
            path = runtime.serviceStatePath(),
            state = "processing",
            startupRequestId = request.bridgeRequest.requestId,
            currentRequestId = foreignRequestId,
            claimedRequestId = foreignRequestId,
            executionStartedAtEpochMs = foreignStartedAt,
            updatedAtEpochMs = foreignStartedAt,
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
        taskId = "task-queue-timeout",
        workspaceRoot = workspaceRoot,
        scriptPath = workspaceRoot.resolve("demo.py"),
        timeoutMs = 40L,
        startupTimeoutMs = 80L,
      ),
    )

    assertEquals(ExecutionStatus.TIMEOUT, result.status)
    assertEquals(P4aPythonRuntime.ERROR_P4A_QUEUE_TIMEOUT, result.errorCode)
    assertEquals("queue", result.metadata["timeoutStage"])
    assertEquals("foreign-running-request", result.metadata["blockingRequestId"])
    assertEquals("processing", result.metadata["serviceState"])
    assertEquals("foreign-running-request", result.metadata["serviceClaimedRequestId"])
    assertEquals("false", result.metadata["serviceReadyObserved"])
    assertEquals("true", result.metadata["cleanupRequestDeleted"])
    assertEquals("true", result.metadata["cleanupCancelMarkerWritten"])
  }

  @Test
  fun execReturnsResultTimeoutDiagnosticsAfterServiceBecomesReady() {
    val runtimeRoot = temporaryFolder.newFolder("python-runtime-result-timeout").toPath()
    val workspaceRoot = temporaryFolder.newFolder("workspace-result-timeout").toPath()
    lateinit var runtime: P4aPythonRuntime
    var stopCalls = 0
    val launcher = object : P4aPythonRuntime.P4aPythonRuntimeLauncher {
      override fun launch(
        request: P4aPythonRuntime.P4aPythonLaunchRequest,
      ): P4aPythonRuntime.P4aPythonRuntimeLaunchResult {
        val executionStartedAt = System.currentTimeMillis()
        writeServiceMarker(
          path = runtime.serviceReadyPath(),
          state = "processing",
          startupRequestId = request.bridgeRequest.requestId,
          currentRequestId = request.bridgeRequest.requestId,
          claimedRequestId = request.bridgeRequest.requestId,
          executionStartedAtEpochMs = executionStartedAt,
        )
        writeServiceMarker(
          path = runtime.serviceStatePath(),
          state = "processing",
          startupRequestId = request.bridgeRequest.requestId,
          currentRequestId = request.bridgeRequest.requestId,
          claimedRequestId = request.bridgeRequest.requestId,
          executionStartedAtEpochMs = executionStartedAt,
        )
        Files.write(
          request.logPath,
          "service booted\nwaiting for result write\n".toByteArray(StandardCharsets.UTF_8),
        )
        return P4aPythonRuntime.P4aPythonRuntimeLaunchResult.Dispatched(
          metadata = mapOf("launcherState" to "test-dispatched"),
        )
      }

      override fun stop(): Map<String, String> {
        stopCalls += 1
        return mapOf("launcherStopState" to "stop_requested")
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
    assertEquals("true", result.metadata["cleanupRequestDeleted"])
    assertEquals("true", result.metadata["cleanupCancelMarkerWritten"])
    assertEquals("stop_requested", result.metadata["launcherStopState"])
    assertEquals(1, stopCalls)
    assertTrue(result.stderr.contains("service_state_preview:"))
    assertTrue(result.stderr.contains("log_tail:"))
  }

  @Test
  fun execIgnoresFreshMarkersUntilCurrentRequestIsClaimed() {
    val runtimeRoot = temporaryFolder.newFolder("python-runtime-request-bound-ready").toPath()
    val workspaceRoot = temporaryFolder.newFolder("workspace-request-bound-ready").toPath()
    lateinit var runtime: P4aPythonRuntime
    val launcher = object : P4aPythonRuntime.P4aPythonRuntimeLauncher {
      override fun launch(
        request: P4aPythonRuntime.P4aPythonLaunchRequest,
      ): P4aPythonRuntime.P4aPythonRuntimeLaunchResult {
        thread(start = true, isDaemon = true) {
          val foreignRequestId = "foreign-running-request"
          Thread.sleep(10L)
          val foreignStartedAt = System.currentTimeMillis()
          writeServiceMarker(
            path = runtime.serviceReadyPath(),
            state = "processing",
            startupRequestId = request.bridgeRequest.requestId,
            currentRequestId = foreignRequestId,
            claimedRequestId = foreignRequestId,
            executionStartedAtEpochMs = foreignStartedAt,
          )
          writeServiceMarker(
            path = runtime.serviceStatePath(),
            state = "processing",
            startupRequestId = request.bridgeRequest.requestId,
            currentRequestId = foreignRequestId,
            claimedRequestId = foreignRequestId,
            executionStartedAtEpochMs = foreignStartedAt,
          )
          Thread.sleep(260L)
          val currentExecutionStartedAt = System.currentTimeMillis()
          writeServiceMarker(
            path = runtime.serviceReadyPath(),
            state = "processing",
            startupRequestId = request.bridgeRequest.requestId,
            currentRequestId = request.bridgeRequest.requestId,
            claimedRequestId = request.bridgeRequest.requestId,
            executionStartedAtEpochMs = currentExecutionStartedAt,
          )
          writeServiceMarker(
            path = runtime.serviceStatePath(),
            state = "processing",
            startupRequestId = request.bridgeRequest.requestId,
            currentRequestId = request.bridgeRequest.requestId,
            claimedRequestId = request.bridgeRequest.requestId,
            executionStartedAtEpochMs = currentExecutionStartedAt,
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
                startedAtEpochMs = currentExecutionStartedAt,
                finishedAtEpochMs = currentExecutionStartedAt + 10L,
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
        taskId = "task-request-bound-ready",
        workspaceRoot = workspaceRoot,
        scriptPath = workspaceRoot.resolve("demo.py"),
        timeoutMs = 200L,
        startupTimeoutMs = 800L,
      ),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals(0, result.exitCode)
    assertEquals("python ok", result.stdout)
  }

  private fun writeServiceMarker(
    path: Path,
    state: String,
    startupRequestId: String? = null,
    currentRequestId: String? = null,
    claimedRequestId: String? = null,
    executionStartedAtEpochMs: Long? = null,
    updatedAtEpochMs: Long? = null,
  ) {
    Files.createDirectories(path.parent)
    Files.write(
      path,
      json.encodeToString(
        buildJsonObject {
          put("state", JsonPrimitive(state))
          startupRequestId?.let { put("startupRequestId", JsonPrimitive(it)) }
          currentRequestId?.let { put("currentRequestId", JsonPrimitive(it)) }
          claimedRequestId?.let { put("claimedRequestId", JsonPrimitive(it)) }
          executionStartedAtEpochMs?.let { put("executionStartedAtEpochMs", JsonPrimitive(it)) }
          updatedAtEpochMs?.let { put("updatedAtEpochMs", JsonPrimitive(it)) }
        },
      ).toByteArray(StandardCharsets.UTF_8),
    )
  }
}
