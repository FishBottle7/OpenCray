package com.opencray.runtime

import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Paths

class RuntimeAdapterParityTest {
  @Test
  fun runRuntimeAdapterParityPreservesNormalizedEnvelopeAcrossInAppAndStubMappings() {
    val adapter = TermuxRuntimeAdapter.v1Unavailable(clock = clockOf(1_000L, 1_005L, 2_000L, 2_010L))

    val execMetadata = linkedMapOf(
      "traceId" to "trace-exec-parity",
      "origin" to "runtime-adapter-parity-test",
    )
    val pythonExecRequest = PythonExecRequest(
      taskId = "task-exec-parity",
      workspaceRoot = Paths.get("build", "tmp", "runtime-parity"),
      scriptPath = Paths.get("scripts", "hello.py"),
      args = listOf("--flag"),
      timeoutMs = 3_000L,
      pythonExecutable = "python3",
    )
    val execRequest = pythonExecRequest.toTermuxRuntimeRequest(metadata = execMetadata)

    assertEquals(pythonExecRequest.taskId, execRequest.taskId)
    assertEquals(pythonExecRequest.workspaceRoot, execRequest.workspaceRoot)
    assertEquals(pythonExecRequest.scriptPath, execRequest.scriptPath)
    assertEquals(pythonExecRequest.args, execRequest.args)
    assertEquals(pythonExecRequest.timeoutMs, execRequest.timeoutMs)
    assertEquals(pythonExecRequest.pythonExecutable, execRequest.pythonExecutable)
    assertEquals(execMetadata, execRequest.metadata)

    val inAppExecResponse = TermuxRuntimeResponse.from(
      request = execRequest,
      backend = TermuxRuntimeBackend.IN_APP,
      availability = adapter.availability,
      result = successfulResult(
        taskId = execRequest.taskId,
        metadata = execMetadata,
        startedAtEpochMs = 10L,
        finishedAtEpochMs = 25L,
        stdout = "exec-ok",
      ),
    )
    val stubExecResponse = adapter.exec(execRequest)

    assertNormalizedEnvelopeParity(execRequest, inAppExecResponse, stubExecResponse)
    assertEquals(TermuxRuntimeBackend.IN_APP, inAppExecResponse.backend)
    assertEquals(TermuxRuntimeBackend.TERMUX_STUB, stubExecResponse.backend)
    assertEquals(ExecutionStatus.SUCCESS, inAppExecResponse.result.status)
    assertEquals(ExecutionStatus.DENIED, stubExecResponse.result.status)
    assertEquals(0, inAppExecResponse.result.exitCode)
    assertEquals(TermuxRuntimeContract.ERROR_TERMUX_UNAVAILABLE, stubExecResponse.result.errorCode)
    printParityEvidenceLine(
      label = "task17-termux-contract-happy-exec",
      request = execRequest,
      inAppResponse = inAppExecResponse,
      stubResponse = stubExecResponse,
    )

    val installMetadata = linkedMapOf(
      "traceId" to "trace-install-parity",
      "origin" to "runtime-adapter-parity-test",
    )
    val pipInstallRequest = PipInstallRequest(
      taskId = "task-install-parity",
      workspaceRoot = Paths.get("build", "tmp", "runtime-parity"),
      requirements = listOf("numpy==1.26.4", "pydantic==2.6.4"),
      timeoutMs = 5_000L,
      pythonExecutable = "python3",
    )
    val installRequest = pipInstallRequest.toTermuxRuntimeRequest(metadata = installMetadata)

    assertEquals(pipInstallRequest.taskId, installRequest.taskId)
    assertEquals(pipInstallRequest.workspaceRoot, installRequest.workspaceRoot)
    assertEquals(pipInstallRequest.requirements, installRequest.requirements)
    assertEquals(pipInstallRequest.timeoutMs, installRequest.timeoutMs)
    assertEquals(pipInstallRequest.pythonExecutable, installRequest.pythonExecutable)
    assertEquals(installMetadata, installRequest.metadata)

    val inAppInstallResponse = TermuxRuntimeResponse.from(
      request = installRequest,
      backend = TermuxRuntimeBackend.IN_APP,
      availability = adapter.availability,
      result = successfulResult(
        taskId = installRequest.taskId,
        metadata = installMetadata,
        startedAtEpochMs = 30L,
        finishedAtEpochMs = 60L,
        stdout = "install-ok",
      ),
    )
    val stubInstallResponse = adapter.install(installRequest)

    assertNormalizedEnvelopeParity(installRequest, inAppInstallResponse, stubInstallResponse)
    assertEquals(TermuxRuntimeBackend.IN_APP, inAppInstallResponse.backend)
    assertEquals(TermuxRuntimeBackend.TERMUX_STUB, stubInstallResponse.backend)
    assertEquals(ExecutionStatus.SUCCESS, inAppInstallResponse.result.status)
    assertEquals(ExecutionStatus.DENIED, stubInstallResponse.result.status)
    assertEquals(0, inAppInstallResponse.result.exitCode)
    assertEquals(TermuxRuntimeContract.ERROR_TERMUX_UNAVAILABLE, stubInstallResponse.result.errorCode)
    printParityEvidenceLine(
      label = "task17-termux-contract-happy-install",
      request = installRequest,
      inAppResponse = inAppInstallResponse,
      stubResponse = stubInstallResponse,
    )
  }

  @Test
  fun runNoTermuxHardDependencyInV1ReturnsDeterministicUnavailableStubResponse() {
    val adapter = TermuxRuntimeAdapter.v1Unavailable(clock = clockOf(5_000L, 5_000L))
    val request = PythonExecRequest(
      taskId = "task-no-termux-hard-dependency",
      workspaceRoot = Paths.get("missing", "workspace"),
      scriptPath = Paths.get("missing", "workspace", "missing-script.py"),
      args = listOf("--dry-run"),
      timeoutMs = 1_000L,
      pythonExecutable = "definitely-not-a-real-python-binary",
    ).toTermuxRuntimeRequest(
      metadata = mapOf(
        "traceId" to "trace-no-termux",
      )
    )

    val response = adapter.exec(request)

    assertEquals(TermuxRuntimeBackend.TERMUX_STUB, adapter.backend)
    assertFalse(adapter.availability.isAvailable)
    assertEquals(TermuxV1Marker.UNAVAILABLE_IN_V1, adapter.availability.v1Marker)
    assertEquals(request.taskId, response.taskId)
    assertEquals(TermuxRuntimeOperation.EXEC, response.operation)
    assertEquals(TermuxRuntimeBackend.TERMUX_STUB, response.backend)
    assertEquals(adapter.availability, response.availability)
    assertEquals(ExecutionStatus.DENIED, response.result.status)
    assertEquals(TermuxRuntimeContract.ERROR_TERMUX_UNAVAILABLE, response.result.errorCode)
    assertEquals(adapter.availability.detail, response.result.errorMessage)
    assertEquals("trace-no-termux", response.result.metadata["traceId"])
    assertEquals("exec", response.result.metadata[TermuxRuntimeContract.METADATA_RUNTIME_OPERATION])
    assertEquals(
      "termux_stub",
      response.result.metadata[TermuxRuntimeContract.METADATA_RUNTIME_BACKEND],
    )
    assertEquals(
      "false",
      response.result.metadata[TermuxRuntimeContract.METADATA_TERMUX_AVAILABLE],
    )
    assertEquals(
      "unavailable_in_v1",
      response.result.metadata[TermuxRuntimeContract.METADATA_TERMUX_V1_MARKER],
    )
    assertTrue(response.result.finishedAtEpochMs >= response.result.startedAtEpochMs)
    printFailureGuardEvidenceLine(
      label = "task17-termux-contract-failure-guard",
      request = request,
      response = response,
    )
  }

  private fun assertNormalizedEnvelopeParity(
    request: TermuxRuntimeRequest,
    inAppResponse: TermuxRuntimeResponse,
    stubResponse: TermuxRuntimeResponse,
  ) {
    assertEquals(request.taskId, inAppResponse.taskId)
    assertEquals(request.taskId, stubResponse.taskId)
    assertEquals(request.operation, inAppResponse.operation)
    assertEquals(request.operation, stubResponse.operation)
    assertEquals(inAppResponse.operation, stubResponse.operation)
    assertEquals(inAppResponse.availability, stubResponse.availability)
    assertEquals(request.metadata["traceId"], inAppResponse.result.metadata["traceId"])
    assertEquals(request.metadata["traceId"], stubResponse.result.metadata["traceId"])
    assertEquals(request.metadata["origin"], inAppResponse.result.metadata["origin"])
    assertEquals(request.metadata["origin"], stubResponse.result.metadata["origin"])
    assertEquals(
      request.operation.name.lowercase(),
      inAppResponse.result.metadata[TermuxRuntimeContract.METADATA_RUNTIME_OPERATION],
    )
    assertEquals(
      inAppResponse.result.metadata[TermuxRuntimeContract.METADATA_RUNTIME_OPERATION],
      stubResponse.result.metadata[TermuxRuntimeContract.METADATA_RUNTIME_OPERATION],
    )
    assertEquals(
      TermuxRuntimeBackend.IN_APP.name.lowercase(),
      inAppResponse.result.metadata[TermuxRuntimeContract.METADATA_RUNTIME_BACKEND],
    )
    assertEquals(
      TermuxRuntimeBackend.TERMUX_STUB.name.lowercase(),
      stubResponse.result.metadata[TermuxRuntimeContract.METADATA_RUNTIME_BACKEND],
    )
    assertEquals(
      adapterUnavailableValue(inAppResponse),
      stubResponse.result.metadata[TermuxRuntimeContract.METADATA_TERMUX_AVAILABLE],
    )
    assertEquals(
      adapterV1MarkerValue(inAppResponse),
      stubResponse.result.metadata[TermuxRuntimeContract.METADATA_TERMUX_V1_MARKER],
    )
  }

  private fun adapterUnavailableValue(response: TermuxRuntimeResponse): String =
    response.availability.isAvailable.toString().also { availabilityValue ->
      assertEquals(
        availabilityValue,
        response.result.metadata[TermuxRuntimeContract.METADATA_TERMUX_AVAILABLE],
      )
    }

  private fun adapterV1MarkerValue(response: TermuxRuntimeResponse): String =
    response.availability.v1Marker.name.lowercase().also { markerValue ->
      assertEquals(
        markerValue,
        response.result.metadata[TermuxRuntimeContract.METADATA_TERMUX_V1_MARKER],
      )
    }

  private fun printParityEvidenceLine(
    label: String,
    request: TermuxRuntimeRequest,
    inAppResponse: TermuxRuntimeResponse,
    stubResponse: TermuxRuntimeResponse,
  ) {
    println(
      "$label operation=${request.operation.name.lowercase()} task_id=${request.taskId} " +
        "in_app_backend=${inAppResponse.backend.name.lowercase()} " +
        "stub_backend=${stubResponse.backend.name.lowercase()} " +
        "in_app_runtime_backend=${inAppResponse.result.metadata[TermuxRuntimeContract.METADATA_RUNTIME_BACKEND]} " +
        "stub_runtime_backend=${stubResponse.result.metadata[TermuxRuntimeContract.METADATA_RUNTIME_BACKEND]} " +
        "termux_available=${stubResponse.result.metadata[TermuxRuntimeContract.METADATA_TERMUX_AVAILABLE]} " +
        "termux_v1_marker=${stubResponse.result.metadata[TermuxRuntimeContract.METADATA_TERMUX_V1_MARKER]} " +
        "in_app_status=${inAppResponse.result.status.name.lowercase()} " +
        "stub_status=${stubResponse.result.status.name.lowercase()} " +
        "stub_error_code=${stubResponse.result.errorCode}"
    )
  }

  private fun printFailureGuardEvidenceLine(
    label: String,
    request: TermuxRuntimeRequest.Exec,
    response: TermuxRuntimeResponse,
  ) {
    println(
      "$label operation=${response.operation.name.lowercase()} task_id=${response.taskId} " +
        "python_executable=${request.pythonExecutable} " +
        "backend=${response.backend.name.lowercase()} " +
        "runtime_backend=${response.result.metadata[TermuxRuntimeContract.METADATA_RUNTIME_BACKEND]} " +
        "termux_available=${response.result.metadata[TermuxRuntimeContract.METADATA_TERMUX_AVAILABLE]} " +
        "termux_v1_marker=${response.result.metadata[TermuxRuntimeContract.METADATA_TERMUX_V1_MARKER]} " +
        "status=${response.result.status.name.lowercase()} " +
        "error_code=${response.result.errorCode}"
    )
  }

  private fun successfulResult(
    taskId: String,
    metadata: Map<String, String>,
    startedAtEpochMs: Long,
    finishedAtEpochMs: Long,
    stdout: String,
  ): ExecutionResult = ExecutionResult(
    taskId = taskId,
    status = ExecutionStatus.SUCCESS,
    exitCode = 0,
    stdout = stdout,
    stderr = "",
    startedAtEpochMs = startedAtEpochMs,
    finishedAtEpochMs = finishedAtEpochMs,
    metadata = metadata,
  )

  private fun clockOf(vararg values: Long): () -> Long {
    require(values.isNotEmpty()) { "clockOf requires at least one value." }
    var index = 0
    return {
      val next = values.getOrElse(index) { values.last() }
      index += 1
      next
    }
  }
}

// Learnings: The Termux response wrapper already lets V1 reuse one normalized envelope for both in-app successes and stubbed unavailable responses.
// Issues: Parity here is still contract-level only because V1 intentionally has no real Termux backend execution path yet.
// Learnings: Printing deterministic summary lines from the focused parity tests makes the evidence files show the normalized contract markers directly instead of only the Gradle success banner.
// Issues: The evidence remains scoped to unit-test observability, so it intentionally proves metadata parity and V1 guarding without exercising any real Termux process integration.
