package com.opencray.app

import com.opencray.runtime.process.ManagedProcessController
import com.opencray.runtime.process.ManagedProcessControllerFactory
import com.opencray.runtime.process.ManagedProcessSnapshot
import com.opencray.runtime.process.ManagedProcessStartRequest
import com.opencray.runtime.process.ManagedProcessStatus
import com.opencray.runtime.process.ReconnectableManagedProcessControllerFactory

internal class RoutingManagedProcessControllerFactory(
  private val settingsProvider: () -> ResolvedSandboxSettings,
  private val pythonRuntimeFactory: ManagedProcessControllerFactory,
  private val localFactory: ManagedProcessControllerFactory,
  private val sandboxFactoryProvider: (ResolvedSandboxSettings) -> ManagedProcessControllerFactory? = { null },
  private val clock: () -> Long = { System.currentTimeMillis() },
) : ReconnectableManagedProcessControllerFactory {
  override fun start(request: ManagedProcessStartRequest): ManagedProcessController {
    if (request.metadata["managedByPythonRuntime"]?.equals("true", ignoreCase = true) == true) {
      val traceMetadata = SandboxExecutionTraceMetadata.routeMetadata(
        metadata = request.metadata,
        routeKind = "managed_process_start",
        executionBackend = "python_runtime",
      )
      return pythonRuntimeFactory.start(request.copy(metadata = request.metadata + traceMetadata))
    }

    val settings = settingsProvider()
    val sandboxFactory = if (SandboxExecutionRouting.shouldResolveSandboxBackend(settings)) {
      sandboxFactoryProvider(settings)
    } else {
      null
    }
    val selection = SandboxExecutionRouting.resolveSelection(
      settings = settings,
      sandboxRuntimeAvailable = sandboxFactory != null,
    )
    val traceMetadata = SandboxExecutionTraceMetadata.routeMetadata(
      metadata = request.metadata,
      routeKind = "managed_process_start",
      executionBackend = selection.resolvedBackend.wireValue,
    )
    val routedRequest = request.copy(
      metadata = request.metadata + traceMetadata + selection.metadata(),
    )
    return when (selection.resolvedBackend) {
      ResolvedExecutionBackend.LOCAL_HOST -> localFactory.start(routedRequest)
      ResolvedExecutionBackend.SANDBOX_REMOTE -> requireNotNull(sandboxFactory).start(routedRequest)
      ResolvedExecutionBackend.UNAVAILABLE -> ImmediateManagedProcessController(
        snapshot = ManagedProcessSnapshot(
          processId = routedRequest.processId,
          taskId = routedRequest.taskId,
          command = routedRequest.command,
          args = routedRequest.args,
          workingDirectory = routedRequest.workingDirectory,
          status = ManagedProcessStatus.FAILED,
          processStarted = false,
          timeoutMs = routedRequest.timeoutMs,
          errorCode = selection.errorCode ?: RoutingPythonScriptRuntime.ERROR_SANDBOX_BACKEND_UNAVAILABLE,
          errorMessage = selection.detail,
          startedAtEpochMs = clock(),
          updatedAtEpochMs = clock(),
          finishedAtEpochMs = clock(),
          ownerIdentity = routedRequest.ownerIdentity,
          metadata = routedRequest.metadata,
        ),
      )
    }
  }

  override fun reconnect(snapshot: ManagedProcessSnapshot): ManagedProcessController? {
    if (snapshot.metadata["managedByPythonRuntime"]?.equals("true", ignoreCase = true) == true) {
      return (pythonRuntimeFactory as? ReconnectableManagedProcessControllerFactory)?.reconnect(
        snapshot.copy(
          metadata = snapshot.metadata + SandboxExecutionTraceMetadata.reconnectMetadata(snapshot.metadata),
        ),
      )
    }
    return when (snapshot.metadata["executionBackend"]?.trim()) {
      ResolvedExecutionBackend.SANDBOX_REMOTE.wireValue -> {
        val sandboxFactory = sandboxFactoryProvider(settingsProvider())
          as? ReconnectableManagedProcessControllerFactory
          ?: return null
        sandboxFactory.reconnect(
          snapshot.copy(
            metadata = snapshot.metadata + SandboxExecutionTraceMetadata.reconnectMetadata(snapshot.metadata),
          ),
        )
      }

      ResolvedExecutionBackend.LOCAL_HOST.wireValue ->
        (localFactory as? ReconnectableManagedProcessControllerFactory)?.reconnect(
          snapshot.copy(
            metadata = snapshot.metadata + SandboxExecutionTraceMetadata.reconnectMetadata(snapshot.metadata),
          ),
        )

      else -> null
    }
  }
}

private class ImmediateManagedProcessController(
  private val snapshot: ManagedProcessSnapshot,
) : ManagedProcessController {
  override fun snapshot(): ManagedProcessSnapshot = snapshot

  override fun await(timeoutMs: Long): ManagedProcessSnapshot = snapshot

  override fun terminate(): ManagedProcessSnapshot = snapshot
}
