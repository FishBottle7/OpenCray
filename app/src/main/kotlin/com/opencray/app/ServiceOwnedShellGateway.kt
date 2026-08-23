package com.opencray.app

import com.opencray.app.shell.AppShellDestination
import com.opencray.app.shell.AppShellStateStore

internal class ServiceOwnedShellGateway(
  private val stateStore: AppShellStateStore,
  private var localeTag: String,
  private var hostLabel: String,
  private var hostSummary: String,
  private val runtimeHostAccess: RuntimeOwnerObservationAccess,
  private val runtimeControllerLifecycle: RuntimeControllerLifecycleDescriptor? = null,
  private val runtimeServiceLifecycle: RuntimeServiceLifecycleDescriptor,
  private val runtimeServiceWorkStateProvider: () -> RuntimeServiceWorkState?,
  private val runtimeServiceKeepAliveStateProvider: () -> RuntimeServiceKeepAliveState? = { null },
  private val runtimeServiceKeepAliveChangeRegistrar: RuntimeServiceKeepAliveChangeRegistrar? = null,
  private val runtimeServiceOwnerLeaseProvider: () -> RuntimeServiceOwnerLease? = { null },
  private val runtimeServiceConnectionStateProvider: () -> RuntimeServiceConnectionState? = { null },
  private val localRuntimeServerStateProvider: () -> LocalRuntimeServerState? = { null },
  private val mainThreadPoster: MainThreadPoster = ImmediateMainThreadPoster,
  private val hostLifecycleDescriptor: HostRuntimeLifecycleDescriptor,
) : OpenCrayShellGateway {
  private val lock = Any()
  private val listeners = linkedSetOf<(Map<String, Any?>) -> Unit>()
  private var disposed: Boolean = false
  private val runtimeHostObservationDisposer: () -> Unit
  private val keepAliveObservationDisposer: (() -> Unit)?

  init {
    runtimeHostObservationDisposer = runtimeHostAccess.observe(
      object : AgentSessionRuntimeListener {
        override fun onTaskStarted(
          sessionId: String,
          task: com.opencray.core.contracts.AgentTask,
        ) {
          emitShellSnapshot()
        }

        override fun onTaskFinished(
          sessionId: String,
          task: com.opencray.core.contracts.AgentTask,
          result: com.opencray.core.contracts.ExecutionResult,
        ) {
          emitShellSnapshot()
        }
      },
    )
    keepAliveObservationDisposer = runtimeServiceKeepAliveChangeRegistrar?.register {
      emitShellSnapshot()
    }
  }

  override fun loadShellSnapshot(): Map<String, Any?> = buildMap {
    val currentLocaleTag: String
    val currentHostLabel: String
    val currentHostSummary: String
    val destination = stateStore.load()
    synchronized(lock) {
      currentLocaleTag = localeTag
      currentHostLabel = hostLabel
      currentHostSummary = hostSummary
    }
    put("initialTab", destination.selectedTab.routeKey)
    put("settingsSubpage", destination.settingsSubpage.routeKey)
    put("localeTag", currentLocaleTag)
    put("hostLabel", currentHostLabel)
    put("hostSummary", currentHostSummary)
    put("isHostConnected", true)
    putRuntimeServiceDiagnosticsSnapshot(
      localRuntimeServerState = localRuntimeServerStateProvider(),
      hostLifecycle = hostLifecycleDescriptor,
      runtimeControllerLifecycle = runtimeControllerLifecycle,
      runtimeOwnerLifecycle = runtimeHostAccess.lifecycleDescriptor,
      runtimeOwnerWorkSummary = runtimeHostAccess.activeWorkSummary(),
      runtimeServiceLifecycle = runtimeServiceLifecycle,
      runtimeServiceWorkState = runtimeServiceWorkStateProvider(),
      runtimeServiceKeepAliveState = runtimeServiceKeepAliveStateProvider(),
      runtimeServiceOwnerLease = runtimeServiceOwnerLeaseProvider(),
      runtimeServiceConnectionState = runtimeServiceConnectionStateProvider(),
    )
  }

  override fun observeShell(listener: (Map<String, Any?>) -> Unit): () -> Unit {
    synchronized(lock) {
      listeners += listener
    }
    mainThreadPoster.post {
      listener(loadShellSnapshot())
    }
    return {
      synchronized(lock) {
        listeners -= listener
      }
    }
  }

  override fun saveShellDestination(
    selectedTab: String,
    settingsSubpage: String?,
  ) {
    stateStore.save(
      AppShellDestination.fromRaw(
        selectedTabRaw = selectedTab,
        settingsSubpageRaw = settingsSubpage,
      ),
    )
    emitShellSnapshot()
  }

  internal fun updateLocalizedResources(
    localeTag: String,
    hostLabel: String,
    hostSummary: String,
  ) {
    synchronized(lock) {
      this.localeTag = localeTag
      this.hostLabel = hostLabel
      this.hostSummary = hostSummary
    }
  }

  internal fun emitLocalizedSnapshotChanged() {
    emitShellSnapshot()
  }

  internal fun dispose() {
    val disposers = synchronized(lock) {
      if (disposed) {
        null
      } else {
        disposed = true
        listeners.clear()
        runtimeHostObservationDisposer to keepAliveObservationDisposer
      }
    } ?: return
    disposers.second?.invoke()
    disposers.first.invoke()
  }

  private fun emitShellSnapshot() {
    val currentListeners = synchronized(lock) { listeners.toList() }
    if (currentListeners.isEmpty()) {
      return
    }
    val payload = loadShellSnapshot()
    mainThreadPoster.post {
      currentListeners.forEach { listener -> listener(payload) }
    }
  }
}
