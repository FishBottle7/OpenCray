package com.opencray.app

internal data class HostRuntimeDiagnosticsBridge(
  val runtimeOwnerDescriptor: HostRuntimeLifecycleDescriptor,
  val runtimeControllerDescriptor: RuntimeControllerLifecycleDescriptor? = null,
  val runtimeServiceDescriptor: RuntimeServiceLifecycleDescriptor? = null,
  val localRuntimeServerStateProvider: () -> LocalRuntimeServerState? = { null },
  val runtimeServiceWorkStateProvider: () -> RuntimeServiceWorkState? = { null },
  val runtimeServiceKeepAliveStateProvider: () -> RuntimeServiceKeepAliveState? = { null },
  val runtimeServiceKeepAliveChangeRegistrar: RuntimeServiceKeepAliveChangeRegistrar? = null,
  val runtimeServiceConnectionStateProvider: () -> RuntimeServiceConnectionState? = { null },
  val runtimeServiceConnectionChangeRegistrar: RuntimeServiceConnectionChangeRegistrar? = null,
) {
  fun registerSnapshotObservers(
    emitShellSnapshot: () -> Unit,
    emitChatRuntimeSnapshot: () -> Unit,
  ): () -> Unit {
    val disposeConnectionObserver = runtimeServiceConnectionChangeRegistrar?.register {
      emitShellSnapshot()
      emitChatRuntimeSnapshot()
    }
    val disposeKeepAliveObserver = runtimeServiceKeepAliveChangeRegistrar?.register {
      emitShellSnapshot()
      emitChatRuntimeSnapshot()
    }
    return {
      disposeKeepAliveObserver?.invoke()
      disposeConnectionObserver?.invoke()
    }
  }

  companion object {
    fun create(
      runtimeOwnerDescriptor: HostRuntimeLifecycleDescriptor,
      runtimeControllerDescriptor: RuntimeControllerLifecycleDescriptor? = null,
      runtimeServiceDescriptor: RuntimeServiceLifecycleDescriptor? = null,
      localRuntimeServerStateProvider: () -> LocalRuntimeServerState? = { null },
      runtimeServiceWorkState: RuntimeServiceWorkState? = null,
      runtimeServiceWorkStateProvider: () -> RuntimeServiceWorkState? = {
        runtimeServiceWorkState
      },
      runtimeServiceKeepAliveState: RuntimeServiceKeepAliveState? = null,
      runtimeServiceKeepAliveStateProvider: () -> RuntimeServiceKeepAliveState? = {
        runtimeServiceKeepAliveState
      },
      runtimeServiceKeepAliveChangeRegistrar: RuntimeServiceKeepAliveChangeRegistrar? = null,
      runtimeServiceConnectionState: RuntimeServiceConnectionState? = null,
      runtimeServiceConnectionStateProvider: () -> RuntimeServiceConnectionState? = {
        runtimeServiceConnectionState
      },
      runtimeServiceConnectionChangeRegistrar: RuntimeServiceConnectionChangeRegistrar? = null,
    ): HostRuntimeDiagnosticsBridge = HostRuntimeDiagnosticsBridge(
      runtimeOwnerDescriptor = runtimeOwnerDescriptor,
      runtimeControllerDescriptor = runtimeControllerDescriptor,
      runtimeServiceDescriptor = runtimeServiceDescriptor,
      localRuntimeServerStateProvider = localRuntimeServerStateProvider,
      runtimeServiceWorkStateProvider = runtimeServiceWorkStateProvider,
      runtimeServiceKeepAliveStateProvider = runtimeServiceKeepAliveStateProvider,
      runtimeServiceKeepAliveChangeRegistrar = runtimeServiceKeepAliveChangeRegistrar,
      runtimeServiceConnectionStateProvider = runtimeServiceConnectionStateProvider,
      runtimeServiceConnectionChangeRegistrar = runtimeServiceConnectionChangeRegistrar,
    )
  }
}
