package com.opencray.app

internal data class HostRuntimeDiagnosticsBridge(
  val runtimeOwnerDescriptor: HostRuntimeLifecycleDescriptor,
  val runtimeServiceDescriptor: RuntimeServiceLifecycleDescriptor? = null,
  val runtimeServiceWorkStateProvider: () -> RuntimeServiceWorkState? = { null },
  val runtimeServiceKeepAliveStateProvider: () -> RuntimeServiceKeepAliveState? = { null },
  val runtimeServiceKeepAliveChangeRegistrar: RuntimeServiceKeepAliveChangeRegistrar? = null,
  val runtimeServiceConnectionStateProvider: () -> RuntimeServiceConnectionState? = { null },
  val runtimeServiceConnectionChangeRegistrar: RuntimeServiceConnectionChangeRegistrar? = null,
) {
  fun registerSnapshotObservers(
    emitShellSnapshot: () -> Unit,
    emitChatRuntimeSnapshot: () -> Unit,
  ) {
    runtimeServiceConnectionChangeRegistrar?.register {
      emitShellSnapshot()
      emitChatRuntimeSnapshot()
    }
    runtimeServiceKeepAliveChangeRegistrar?.register {
      emitShellSnapshot()
      emitChatRuntimeSnapshot()
    }
  }

  companion object {
    fun create(
      runtimeOwnerDescriptor: HostRuntimeLifecycleDescriptor,
      runtimeServiceDescriptor: RuntimeServiceLifecycleDescriptor? = null,
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
      runtimeServiceDescriptor = runtimeServiceDescriptor,
      runtimeServiceWorkStateProvider = runtimeServiceWorkStateProvider,
      runtimeServiceKeepAliveStateProvider = runtimeServiceKeepAliveStateProvider,
      runtimeServiceKeepAliveChangeRegistrar = runtimeServiceKeepAliveChangeRegistrar,
      runtimeServiceConnectionStateProvider = runtimeServiceConnectionStateProvider,
      runtimeServiceConnectionChangeRegistrar = runtimeServiceConnectionChangeRegistrar,
    )
  }
}
