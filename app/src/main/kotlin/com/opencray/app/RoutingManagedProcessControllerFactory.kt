package com.opencray.app

import com.opencray.runtime.process.ManagedProcessController
import com.opencray.runtime.process.ManagedProcessControllerFactory
import com.opencray.runtime.process.ManagedProcessSnapshot
import com.opencray.runtime.process.ManagedProcessStartRequest
import com.opencray.runtime.process.ManagedProcessStatus

internal class RoutingManagedProcessControllerFactory(
  private val settingsProvider: () -> ResolvedSandboxSettings,
  private val pythonRuntimeFactory: ManagedProcessControllerFactory,
  private val localFactory: ManagedProcessControllerFactory,
  private val sandboxFactoryProvider: (ResolvedSandboxSettings) -> ManagedProcessControllerFactory? = { null },
  private val clock: () -> Long = { System.currentTimeMillis() },
) : ManagedProcessControllerFactory {
  override fun start(request: ManagedProcessStartRequest): ManagedProcessController {
    if (request.metadata["managedByPythonRuntime"]?.equals("true", ignoreCase = true) == true) {
      return pythonRuntimeFactory.start(request)
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
    val routedRequest = request.copy(
      metadata = request.metadata + selection.metadata(),
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
          metadata = routedRequest.metadata,
        ),
      )
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
