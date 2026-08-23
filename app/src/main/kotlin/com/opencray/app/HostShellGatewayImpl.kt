package com.opencray.app

import com.opencray.app.shell.AppShellDestination

internal class HostShellGatewayImpl(
  private val host: OpenCrayHostRuntime,
) : OpenCrayShellGateway {
  override fun loadShellSnapshot(): Map<String, Any?> = buildMap {
    val destination = host.stateStore.load()
    put("initialTab", destination.selectedTab.routeKey)
    put("settingsSubpage", destination.settingsSubpage.routeKey)
    put("localeTag", host.strings.localeTag)
    put("hostLabel", host.strings.shellHostLabel)
    put("hostSummary", host.strings.shellHostSummary)
    put("isHostConnected", true)
    putRuntimeServiceDiagnosticsSnapshot(
      localRuntimeServerState = host.runtimeDiagnosticsBridge.localRuntimeServerStateProvider(),
      hostLifecycle = host.lifecycleDescriptor,
      runtimeControllerLifecycle = host.runtimeDiagnosticsBridge.runtimeControllerDescriptor,
      runtimeOwnerLifecycle = host.runtimeDiagnosticsBridge.runtimeOwnerDescriptor,
      runtimeOwnerWorkSummary = host.runtimeHostAccess.activeWorkSummary(),
      runtimeServiceLifecycle = host.runtimeDiagnosticsBridge.runtimeServiceDescriptor,
      runtimeServiceWorkState = host.runtimeDiagnosticsBridge.runtimeServiceWorkStateProvider(),
      runtimeServiceKeepAliveState =
        host.runtimeDiagnosticsBridge.runtimeServiceKeepAliveStateProvider(),
      runtimeServiceOwnerLease = host.runtimeDiagnosticsBridge.runtimeServiceOwnerLeaseProvider(),
      runtimeServiceConnectionState =
        host.runtimeDiagnosticsBridge.runtimeServiceConnectionStateProvider(),
      includeNullRuntimeServiceFields = true,
    )
  }

  override fun observeShell(listener: (Map<String, Any?>) -> Unit): () -> Unit =
    host.observeWithInitial(
      listeners = host.shellListeners,
      initialPayload = loadShellSnapshot(),
      listener = listener,
    )

  override fun saveShellDestination(
    selectedTab: String,
    settingsSubpage: String?,
  ) {
    val destination = AppShellDestination.fromRaw(
      selectedTabRaw = selectedTab,
      settingsSubpageRaw = settingsSubpage,
    )
    host.stateStore.save(destination)
    host.emitShellSnapshot()
  }
}
