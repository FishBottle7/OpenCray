package com.opencray.app

import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.RetryRequest
import com.opencray.core.orchestrator.RuntimeExecutionHooks
import com.opencray.runtime.CommandExecutionRequest
import com.opencray.runtime.CommandExecutor
import com.opencray.runtime.process.ManagedProcessController
import com.opencray.runtime.process.ManagedProcessControllerFactory
import com.opencray.runtime.process.ManagedProcessSnapshot
import com.opencray.runtime.process.ManagedProcessStartRequest
import com.opencray.runtime.process.ManagedProcessStatus
import com.opencray.runtime.process.ReconnectableManagedProcessControllerFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class E2BSandboxCommandExecutionBackendFactoryTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun fallsBackToPythonWrapperMetadataWhenProviderNativeBackendIsUnavailable() {
    val workspaceRoot = temporaryFolder.newFolder("sandbox-command-backend-fallback").toPath()
    val backend = E2BSandboxCommandExecutionBackendFactory.create(
      fallbackBackend = FakeSandboxCommandExecutionBackend(
        capabilities = SandboxCommandBackendCapabilities(
          backendKind = "python_wrapper",
          providerNative = false,
          supportsStreamingLogs = false,
          supportsReconnect = false,
        ),
        commandStdout = "fallback-command",
        processBackendName = "fallback-process",
      ),
    )

    val result = backend.createCommandExecutor().execute(
      request = CommandExecutionRequest(
        taskId = "task-fallback-command",
        command = "git",
        args = listOf("status"),
        workingDirectory = workspaceRoot.toString(),
        requestedAtEpochMs = 100L,
      ),
      policyDecision = allowPolicy(),
      approvalToken = null,
      hooks = hooks(),
    )

    assertEquals("fallback-command", result.stdout)
    assertEquals("python_wrapper", result.metadata["sandboxCommandBackendKind"])
    assertEquals("provider_native_preferred", result.metadata["sandboxCommandBackendRequestedKind"])
    assertEquals("python_wrapper", result.metadata["sandboxCommandBackendResolvedKind"])
    assertEquals("true", result.metadata["sandboxCommandProviderNativeRequested"])
    assertEquals("false", result.metadata["sandboxCommandProviderNativeAvailable"])
    assertEquals(
      E2BSandboxCommandExecutionBackendFactory.REASON_PROVIDER_NATIVE_TRANSPORT_STACK_MISSING,
      result.metadata["sandboxCommandBackendFallbackReasonCode"],
    )
  }

  @Test
  fun decoratesManagedProcessSnapshotsWithProviderNativeFallbackMetadata() {
    val workspaceRoot = temporaryFolder.newFolder("sandbox-command-process-fallback").toPath()
    val backend = E2BSandboxCommandExecutionBackendFactory.create(
      fallbackBackend = FakeSandboxCommandExecutionBackend(
        capabilities = SandboxCommandBackendCapabilities(
          backendKind = "python_wrapper",
          providerNative = false,
          supportsStreamingLogs = false,
          supportsReconnect = false,
        ),
        commandStdout = "unused",
        processBackendName = "fallback-process",
      ),
    )

    val snapshot = backend.createManagedProcessControllerFactory().start(
      ManagedProcessStartRequest(
        processId = "proc-fallback",
        taskId = "task-fallback",
        command = "npm",
        args = listOf("run", "dev"),
        workingDirectory = workspaceRoot.toString(),
        timeoutMs = 5_000L,
        requestedAtEpochMs = 100L,
      ),
    ).snapshot()

    assertEquals("fallback-process", snapshot.metadata["factoryBackend"])
    assertEquals("python_wrapper", snapshot.metadata["sandboxCommandBackendKind"])
    assertEquals("provider_native_preferred", snapshot.metadata["sandboxCommandBackendRequestedKind"])
    assertEquals("python_wrapper", snapshot.metadata["sandboxCommandBackendResolvedKind"])
    assertEquals("true", snapshot.metadata["sandboxCommandProviderNativeRequested"])
    assertEquals("false", snapshot.metadata["sandboxCommandProviderNativeAvailable"])
    assertEquals(
      E2BSandboxCommandExecutionBackendFactory.REASON_PROVIDER_NATIVE_TRANSPORT_STACK_MISSING,
      snapshot.metadata["sandboxCommandBackendFallbackReasonCode"],
    )
  }

  @Test
  fun usesProviderNativeBackendWhenCandidateIsAvailable() {
    val workspaceRoot = temporaryFolder.newFolder("sandbox-command-backend-native").toPath()
    val fallbackBackend = FakeSandboxCommandExecutionBackend(
      capabilities = SandboxCommandBackendCapabilities(
        backendKind = "python_wrapper",
        providerNative = false,
        supportsStreamingLogs = false,
        supportsReconnect = false,
      ),
      commandStdout = "fallback-command",
      processBackendName = "fallback-process",
    )
    val nativeBackend = FakeSandboxCommandExecutionBackend(
      capabilities = SandboxCommandBackendCapabilities(
        backendKind = "provider_native",
        providerNative = true,
        supportsStreamingLogs = true,
        supportsReconnect = true,
        supportsManagedProcessLiveObservation = true,
        supportsManagedProcessObservationCursorResume = true,
        supportsManagedProcessObservationBackfill = true,
      ),
      commandStdout = "native-command",
      processBackendName = "native-process",
    )
    val backend = E2BSandboxCommandExecutionBackendFactory.create(
      fallbackBackend = fallbackBackend,
      providerNativeCandidate = SandboxCommandBackendCandidate(
        backend = nativeBackend,
      ),
    )

    val result = backend.createCommandExecutor().execute(
      request = CommandExecutionRequest(
        taskId = "task-native-command",
        command = "git",
        args = listOf("status"),
        workingDirectory = workspaceRoot.toString(),
        requestedAtEpochMs = 100L,
      ),
      policyDecision = allowPolicy(),
      approvalToken = null,
      hooks = hooks(),
    )

    assertEquals("native-command", result.stdout)
    assertEquals("provider_native", result.metadata["sandboxCommandBackendKind"])
    assertEquals("provider_native_preferred", result.metadata["sandboxCommandBackendRequestedKind"])
    assertEquals("provider_native", result.metadata["sandboxCommandBackendResolvedKind"])
    assertEquals("true", result.metadata["sandboxCommandProviderNativeRequested"])
    assertEquals("true", result.metadata["sandboxCommandProviderNativeAvailable"])
    assertNull(result.metadata["sandboxCommandBackendFallbackReasonCode"])
    assertEquals("true", result.metadata["sandboxCommandSupportsStreamingLogs"])
    assertEquals("true", result.metadata["sandboxCommandSupportsReconnect"])
    assertEquals("true", result.metadata["sandboxCommandSupportsManagedProcessLiveObservation"])
    assertEquals(
      "true",
      result.metadata["sandboxCommandSupportsManagedProcessObservationCursorResume"],
    )
    assertEquals("true", result.metadata["sandboxCommandSupportsManagedProcessObservationBackfill"])
  }

  @Test
  fun decoratesReconnectSnapshotsWithProviderNativeSelectionMetadata() {
    val workspaceRoot = temporaryFolder.newFolder("sandbox-command-backend-native-reconnect").toPath()
    val nativeBackend = FakeSandboxCommandExecutionBackend(
      capabilities = SandboxCommandBackendCapabilities(
        backendKind = "provider_native",
        providerNative = true,
        supportsStreamingLogs = false,
        supportsReconnect = true,
        supportsManagedProcessLiveObservation = true,
        supportsManagedProcessObservationCursorResume = false,
        supportsManagedProcessObservationBackfill = false,
      ),
      commandStdout = "native-command",
      processBackendName = "native-process",
    )
    val backend = E2BSandboxCommandExecutionBackendFactory.create(
      fallbackBackend = FakeSandboxCommandExecutionBackend(
        capabilities = SandboxCommandBackendCapabilities(
          backendKind = "python_wrapper",
          providerNative = false,
          supportsStreamingLogs = false,
          supportsReconnect = false,
        ),
        commandStdout = "fallback-command",
        processBackendName = "fallback-process",
      ),
      providerNativeCandidate = SandboxCommandBackendCandidate(
        backend = nativeBackend,
      ),
    )

    val factory = backend.createManagedProcessControllerFactory() as ReconnectableManagedProcessControllerFactory
    val controller = factory.reconnect(
      ManagedProcessSnapshot(
        processId = "proc-native",
        taskId = "task-native",
        command = "npm",
        args = listOf("run", "dev"),
        workingDirectory = workspaceRoot.toString(),
        status = ManagedProcessStatus.RUNNING,
        processStarted = true,
        timeoutMs = 5_000L,
        startedAtEpochMs = 100L,
        updatedAtEpochMs = 100L,
        metadata = mapOf(
          "sandboxCommandBackendResolvedKind" to "provider_native",
        ),
      ),
    )

    assertNotNull(controller)
    val snapshot = controller!!.snapshot()
    assertEquals("native-process", snapshot.metadata["factoryBackend"])
    assertEquals("provider_native_preferred", snapshot.metadata["sandboxCommandBackendRequestedKind"])
    assertEquals("provider_native", snapshot.metadata["sandboxCommandBackendResolvedKind"])
    assertEquals("true", snapshot.metadata["sandboxCommandProviderNativeAvailable"])
    assertEquals("true", snapshot.metadata["sandboxCommandSupportsManagedProcessLiveObservation"])
    assertEquals(
      "false",
      snapshot.metadata["sandboxCommandSupportsManagedProcessObservationCursorResume"],
    )
    assertEquals("false", snapshot.metadata["sandboxCommandSupportsManagedProcessObservationBackfill"])
  }

  private fun allowPolicy(): PolicyDecision = PolicyDecision(
    outcome = PolicyDecisionOutcome.ALLOW,
    reasonCode = "TEST_ALLOW",
  )

  private fun hooks(): RuntimeExecutionHooks = RuntimeExecutionHooks(
    isCancellationRequested = { false },
    requestRetry = { _: RetryRequest ->
      error("Retry not expected in E2BSandboxCommandExecutionBackendFactoryTest.")
    },
  )

  private class FakeSandboxCommandExecutionBackend(
    override val capabilities: SandboxCommandBackendCapabilities,
    private val commandStdout: String,
    private val processBackendName: String,
  ) : SandboxCommandExecutionBackend {
    override fun createCommandExecutor(): CommandExecutor = object : CommandExecutor() {
      override fun execute(
        request: CommandExecutionRequest,
        policyDecision: PolicyDecision,
        approvalToken: com.opencray.runtime.CommandApprovalToken?,
        hooks: RuntimeExecutionHooks,
      ): ExecutionResult = ExecutionResult(
        taskId = request.taskId,
        status = ExecutionStatus.SUCCESS,
        exitCode = 0,
        stdout = commandStdout,
        stderr = "",
        policyDecision = policyDecision,
        startedAtEpochMs = 100L,
        finishedAtEpochMs = 200L,
        metadata = request.metadata + capabilities.metadata(),
      )
    }

    override fun createManagedProcessControllerFactory(): ManagedProcessControllerFactory =
      object : ReconnectableManagedProcessControllerFactory {
        override fun start(request: ManagedProcessStartRequest): ManagedProcessController =
          object : ManagedProcessController {
            override fun snapshot(): ManagedProcessSnapshot = snapshotFor(request)

            override fun await(timeoutMs: Long): ManagedProcessSnapshot = snapshotFor(request)

            override fun terminate(): ManagedProcessSnapshot = snapshotFor(request)

            private fun snapshotFor(
              request: ManagedProcessStartRequest,
            ): ManagedProcessSnapshot = ManagedProcessSnapshot(
              processId = request.processId,
              taskId = request.taskId,
              command = request.command,
              args = request.args,
              workingDirectory = request.workingDirectory,
              status = ManagedProcessStatus.RUNNING,
              processStarted = true,
              timeoutMs = request.timeoutMs,
              startedAtEpochMs = 100L,
              updatedAtEpochMs = 100L,
              metadata = request.metadata + capabilities.metadata() + mapOf(
                "factoryBackend" to processBackendName,
              ),
            )
          }

        override fun reconnect(snapshot: ManagedProcessSnapshot): ManagedProcessController =
          object : ManagedProcessController {
            override fun snapshot(): ManagedProcessSnapshot = snapshot.copy(
              metadata = snapshot.metadata + capabilities.metadata() + mapOf(
                "factoryBackend" to processBackendName,
              ),
            )

            override fun await(timeoutMs: Long): ManagedProcessSnapshot = snapshot()

            override fun terminate(): ManagedProcessSnapshot = snapshot()
          }
      }
  }
}
