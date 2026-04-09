package com.opencray.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper

internal data class OpenCrayRuntimeServiceBridgeSnapshot(
  val dependencies: OpenCrayRuntimeContextDependencies,
  val runtimeAccess: OpenCrayRuntimeOwnerAccess,
  val serviceLifecycle: RuntimeServiceLifecycleDescriptor,
  val serviceWorkState: RuntimeServiceWorkState,
  val serviceKeepAliveState: RuntimeServiceKeepAliveState,
)

internal data class RuntimeServiceBridgeSnapshotDependencies(
  val dependencies: OpenCrayRuntimeContextDependencies,
  val runtimeAccess: OpenCrayRuntimeOwnerAccess,
  val serviceLifecycle: RuntimeServiceLifecycleDescriptor,
)

internal data class RuntimeServiceConnectionState(
  val phase: String,
  val transport: String,
  val serviceStartRequested: Boolean,
  val bindingRequested: Boolean,
  val binderAvailable: Boolean,
  val fallbackReason: String? = null,
) {
  fun snapshotMap(): Map<String, Any?> = buildMap {
    put("phase", phase)
    put("transport", transport)
    put("serviceStartRequested", serviceStartRequested)
    put("bindingRequested", bindingRequested)
    put("binderAvailable", binderAvailable)
    fallbackReason
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { put("fallbackReason", it) }
  }

  companion object {
    private const val PHASE_BOUND: String = "bound"
    private const val PHASE_BINDING: String = "binding"
    private const val PHASE_DISCONNECTED: String = "disconnected"
    private const val PHASE_BINDING_DIED: String = "binding_died"
    private const val PHASE_NULL_BINDING: String = "null_binding"
    private const val PHASE_INVALID_BINDER: String = "invalid_binder"
    private const val PHASE_FALLBACK: String = "fallback"
    private const val TRANSPORT_BINDER: String = "binder"
    private const val TRANSPORT_IN_PROCESS: String = "in_process"

    fun binderConnected(
      serviceStartRequested: Boolean = true,
      bindingRequested: Boolean = true,
    ): RuntimeServiceConnectionState = RuntimeServiceConnectionState(
      phase = PHASE_BOUND,
      transport = TRANSPORT_BINDER,
      serviceStartRequested = serviceStartRequested,
      bindingRequested = bindingRequested,
      binderAvailable = true,
    )

    fun bindingPending(
      serviceStartRequested: Boolean = true,
    ): RuntimeServiceConnectionState = RuntimeServiceConnectionState(
      phase = PHASE_BINDING,
      transport = TRANSPORT_IN_PROCESS,
      serviceStartRequested = serviceStartRequested,
      bindingRequested = true,
      binderAvailable = false,
      fallbackReason = "binder_pending",
    )

    fun inProcessFallback(
      serviceStartRequested: Boolean = false,
      bindingRequested: Boolean = false,
      fallbackReason: String = "binder_unavailable",
    ): RuntimeServiceConnectionState = RuntimeServiceConnectionState(
      phase = PHASE_FALLBACK,
      transport = TRANSPORT_IN_PROCESS,
      serviceStartRequested = serviceStartRequested,
      bindingRequested = bindingRequested,
      binderAvailable = false,
      fallbackReason = fallbackReason,
    )

    fun bindFailed(
      serviceStartRequested: Boolean = true,
      fallbackReason: String = "bind_service_returned_false",
    ): RuntimeServiceConnectionState = RuntimeServiceConnectionState(
      phase = PHASE_FALLBACK,
      transport = TRANSPORT_IN_PROCESS,
      serviceStartRequested = serviceStartRequested,
      bindingRequested = true,
      binderAvailable = false,
      fallbackReason = fallbackReason,
    )

    fun serviceDisconnected(
      serviceStartRequested: Boolean = true,
      bindingRequested: Boolean = false,
    ): RuntimeServiceConnectionState = RuntimeServiceConnectionState(
      phase = PHASE_DISCONNECTED,
      transport = TRANSPORT_IN_PROCESS,
      serviceStartRequested = serviceStartRequested,
      bindingRequested = bindingRequested,
      binderAvailable = false,
      fallbackReason = "service_disconnected",
    )

    fun bindingDied(
      serviceStartRequested: Boolean = true,
    ): RuntimeServiceConnectionState = RuntimeServiceConnectionState(
      phase = PHASE_BINDING_DIED,
      transport = TRANSPORT_IN_PROCESS,
      serviceStartRequested = serviceStartRequested,
      bindingRequested = false,
      binderAvailable = false,
      fallbackReason = "binding_died",
    )

    fun nullBinding(
      serviceStartRequested: Boolean = true,
    ): RuntimeServiceConnectionState = RuntimeServiceConnectionState(
      phase = PHASE_NULL_BINDING,
      transport = TRANSPORT_IN_PROCESS,
      serviceStartRequested = serviceStartRequested,
      bindingRequested = false,
      binderAvailable = false,
      fallbackReason = "null_binding",
    )

    fun invalidBinder(
      serviceStartRequested: Boolean = true,
    ): RuntimeServiceConnectionState = RuntimeServiceConnectionState(
      phase = PHASE_INVALID_BINDER,
      transport = TRANSPORT_IN_PROCESS,
      serviceStartRequested = serviceStartRequested,
      bindingRequested = true,
      binderAvailable = false,
      fallbackReason = "binder_access_unavailable",
    )

    fun bindingReleased(
      serviceStartRequested: Boolean = true,
      fallbackReason: String = "binder_idle_released",
    ): RuntimeServiceConnectionState = inProcessFallback(
      serviceStartRequested = serviceStartRequested,
      bindingRequested = false,
      fallbackReason = fallbackReason,
    )
  }
}

internal data class OpenCrayRuntimeServiceClientSnapshot(
  val bridgeSnapshot: OpenCrayRuntimeServiceBridgeSnapshot,
  val connectionState: RuntimeServiceConnectionState,
)

internal interface OpenCrayRuntimeServiceClient {
  fun loadSnapshot(): OpenCrayRuntimeServiceClientSnapshot

  fun peekSnapshot(): OpenCrayRuntimeServiceClientSnapshot? = null

  fun peekProjectionSnapshot(): RuntimeServiceProjectionSnapshot? = null

  fun loadConnectionState(): RuntimeServiceConnectionState = loadSnapshot().connectionState

  fun peekConnectionState(): RuntimeServiceConnectionState = loadConnectionState()

  fun loadShellGateway(): OpenCrayShellGateway? = null

  fun peekShellGateway(): OpenCrayShellGateway? = null

  fun loadChatRuntimeGateway(): OpenCrayChatRuntimeGateway? = null

  fun peekChatRuntimeGateway(): OpenCrayChatRuntimeGateway? = null

  fun awaitChatRuntimeGateway(timeoutMs: Long): OpenCrayChatRuntimeGateway? =
    loadChatRuntimeGateway()

  fun dispatchChatWriteCommand(
    command: OpenCrayChatWriteCommand,
  ): OpenCrayChatWriteDispatchResult? = null

  fun loadSkillsGateway(): OpenCraySkillsGateway? = null

  fun peekSkillsGateway(): OpenCraySkillsGateway? = null

  fun awaitSkillsGateway(timeoutMs: Long): OpenCraySkillsGateway? =
    loadSkillsGateway()

  fun dispatchSkillsWriteCommand(
    command: OpenCraySkillsWriteCommand,
  ): OpenCraySkillsWriteDispatchResult? = null

  fun loadSettingsGateway(): OpenCraySettingsGateway? = null

  fun peekSettingsGateway(): OpenCraySettingsGateway? = null

  fun awaitSettingsGateway(timeoutMs: Long): OpenCraySettingsGateway? =
    loadSettingsGateway()

  fun dispatchSettingsWriteCommand(
    command: OpenCraySettingsWriteCommand,
  ): OpenCraySettingsWriteDispatchResult? = null

  fun observeConnectionState(listener: (RuntimeServiceConnectionState) -> Unit): () -> Unit = { }

  fun observePassiveConnectionState(listener: (RuntimeServiceConnectionState) -> Unit): () -> Unit =
    observeConnectionState(listener)
}

internal interface OpenCrayRuntimeServiceBridge {
  fun loadSnapshot(): OpenCrayRuntimeServiceBridgeSnapshot
}

internal interface OpenCrayRuntimeServiceBinderAccess {
  fun loadSnapshot(): OpenCrayRuntimeServiceBridgeSnapshot

  fun loadShellGateway(): OpenCrayShellGateway? = null

  fun loadChatRuntimeGateway(): OpenCrayChatRuntimeGateway? = null

  fun dispatchChatWriteCommand(
    command: OpenCrayChatWriteCommand,
  ): OpenCrayChatWriteDispatchResult? = null

  fun loadSkillsGateway(): OpenCraySkillsGateway? = null

  fun dispatchSkillsWriteCommand(
    command: OpenCraySkillsWriteCommand,
  ): OpenCraySkillsWriteDispatchResult? = null

  fun loadSettingsGateway(): OpenCraySettingsGateway? = null

  fun dispatchSettingsWriteCommand(
    command: OpenCraySettingsWriteCommand,
  ): OpenCraySettingsWriteDispatchResult? = null
}

internal class InProcessOpenCrayRuntimeServiceBridge(
  private val snapshotProvider: () -> OpenCrayRuntimeServiceBridgeSnapshot,
) : OpenCrayRuntimeServiceBridge {
  override fun loadSnapshot(): OpenCrayRuntimeServiceBridgeSnapshot =
    snapshotProvider()
}

internal class ExistingOpenCrayRuntimeServiceBridge(
  private val snapshotProvider: () -> OpenCrayRuntimeServiceBridgeSnapshot?,
  private val missingHostMessage: String =
    "Runtime service host is unavailable. Call OpenCrayRuntimeServiceAccess.ensureStarted(...) before loading fallback snapshots.",
) : OpenCrayRuntimeServiceBridge {
  override fun loadSnapshot(): OpenCrayRuntimeServiceBridgeSnapshot =
    checkNotNull(snapshotProvider()) { missingHostMessage }
}

internal class MissingOpenCrayRuntimeServiceBridge(
  private val missingSnapshotMessage: String =
    "Runtime service binder snapshot is unavailable. Use peekProjectionSnapshot() for binder-unavailable projection reads.",
) : OpenCrayRuntimeServiceBridge {
  override fun loadSnapshot(): OpenCrayRuntimeServiceBridgeSnapshot =
    error(missingSnapshotMessage)
}

internal class BinderBackedOpenCrayRuntimeServiceBridge(
  private val binderAccess: OpenCrayRuntimeServiceBinderAccess,
) : OpenCrayRuntimeServiceBridge {
  override fun loadSnapshot(): OpenCrayRuntimeServiceBridgeSnapshot =
    binderAccess.loadSnapshot()
}

internal class BridgeBackedOpenCrayRuntimeServiceClient(
  private val bridge: OpenCrayRuntimeServiceBridge,
  private val connectionState: RuntimeServiceConnectionState,
) : OpenCrayRuntimeServiceClient {
  override fun loadSnapshot(): OpenCrayRuntimeServiceClientSnapshot =
    OpenCrayRuntimeServiceClientSnapshot(
      bridgeSnapshot = bridge.loadSnapshot(),
      connectionState = connectionState,
    )

  override fun peekSnapshot(): OpenCrayRuntimeServiceClientSnapshot =
    loadSnapshot()

  override fun peekProjectionSnapshot(): RuntimeServiceProjectionSnapshot =
    bridge.loadSnapshot().toProjectionSnapshot()

  override fun loadConnectionState(): RuntimeServiceConnectionState = connectionState

  override fun peekConnectionState(): RuntimeServiceConnectionState = connectionState
}

internal interface OpenCrayRuntimeServiceBindingAdapter {
  fun bind(
    context: Context,
    intent: Intent,
    connection: ServiceConnection,
    flags: Int,
  ): Boolean

  fun unbind(
    context: Context,
    connection: ServiceConnection,
  )
}

internal object AndroidOpenCrayRuntimeServiceBindingAdapter : OpenCrayRuntimeServiceBindingAdapter {
  override fun bind(
    context: Context,
    intent: Intent,
    connection: ServiceConnection,
    flags: Int,
  ): Boolean = context.bindService(intent, connection, flags)

  override fun unbind(
    context: Context,
    connection: ServiceConnection,
  ) {
    context.unbindService(connection)
  }
}

private object NoOpRuntimeServiceDelayScheduler : RuntimeServiceDelayScheduler {
  override fun schedule(
    delayMs: Long,
    action: () -> Unit,
  ): RuntimeServiceDelayedTask = RuntimeServiceDelayedTask { }
}

private fun defaultBindingReleaseScheduler(
  mainThreadPoster: MainThreadPoster,
): RuntimeServiceDelayScheduler =
  if (mainThreadPoster === ImmediateMainThreadPoster) {
    NoOpRuntimeServiceDelayScheduler
  } else {
    HandlerRuntimeServiceDelayScheduler(Handler(Looper.getMainLooper()))
  }

private fun defaultRuntimeServiceStartRequester(context: Context) {
  OpenCrayRuntimeServiceAccess.ensureStarted(context)
}

private fun defaultRuntimeServiceBaseIntent(context: Context): Intent =
  OpenCrayRuntimeServiceAccess.baseIntent(context.applicationContext)

internal class AndroidBindingOpenCrayRuntimeServiceClient(
  private val appContext: Context,
  private val fallbackBridge: OpenCrayRuntimeServiceBridge =
    MissingOpenCrayRuntimeServiceBridge(),
  projectionStore: RuntimeServiceProjectionStore? = null,
  private val bindingAdapter: OpenCrayRuntimeServiceBindingAdapter =
    AndroidOpenCrayRuntimeServiceBindingAdapter,
  private val startRequester: (Context) -> Unit = ::defaultRuntimeServiceStartRequester,
  private val mainThreadPoster: MainThreadPoster =
    HandlerMainThreadPoster(Handler(Looper.getMainLooper())),
  private val bindingReleaseDelayMs: Long = DEFAULT_BINDING_RELEASE_DELAY_MS,
  private val bindingReleaseScheduler: RuntimeServiceDelayScheduler =
    defaultBindingReleaseScheduler(mainThreadPoster),
  private val serviceIntentFactory: (Context) -> Intent = ::defaultRuntimeServiceBaseIntent,
  private val bindingFlags: Int = Context.BIND_AUTO_CREATE,
  private val isMainThread: () -> Boolean = {
    Looper.myLooper() == Looper.getMainLooper()
  },
) : OpenCrayRuntimeServiceClient {
  private val lock: java.lang.Object = java.lang.Object()
  private val listeners = linkedSetOf<(RuntimeServiceConnectionState) -> Unit>()
  private val projectionSnapshotStore: RuntimeServiceProjectionStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    projectionStore ?: FileBackedRuntimeServiceProjectionStoreFactory.fromContext(appContext).create()
  }
  private var connectionObserverCount: Int = 0
  private var bindingEstablished: Boolean = false
  private var pendingBindingReleaseTask: RuntimeServiceDelayedTask? = null

  @Volatile
  private var binderAccess: OpenCrayRuntimeServiceBinderAccess? = null

  @Volatile
  private var connectionState: RuntimeServiceConnectionState =
    RuntimeServiceConnectionState.inProcessFallback(
      serviceStartRequested = false,
      bindingRequested = false,
      fallbackReason = "service_not_started",
    )

  private var serviceStartRequested: Boolean = false
  private var bindingRequested: Boolean = false

  private val serviceConnection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName, service: IBinder) {
      val shouldAcceptBinding = synchronized(lock) {
        bindingRequested || connectionObserverCount > 0
      }
      if (!shouldAcceptBinding) {
        runCatching {
          bindingAdapter.unbind(
            context = appContext,
            connection = this,
          )
        }
        return
      }
      val access = service as? OpenCrayRuntimeServiceBinderAccess
      synchronized(lock) {
        cancelPendingBindingReleaseLocked()
        bindingEstablished = true
        binderAccess = access
        lock.notifyAll()
      }
      if (access != null) {
        publishConnectionState(
          RuntimeServiceConnectionState.binderConnected(
            serviceStartRequested = currentServiceStartRequested(),
            bindingRequested = currentBindingRequested(),
          ),
        )
      } else {
        publishConnectionState(
          RuntimeServiceConnectionState.invalidBinder(
            serviceStartRequested = currentServiceStartRequested(),
          ),
        )
      }
      scheduleBindingReleaseIfIdle()
    }

    override fun onServiceDisconnected(name: ComponentName) {
      synchronized(lock) {
        bindingEstablished = false
        bindingRequested = false
        binderAccess = null
        lock.notifyAll()
      }
      publishConnectionState(
        RuntimeServiceConnectionState.serviceDisconnected(
          serviceStartRequested = currentServiceStartRequested(),
          bindingRequested = currentBindingRequested(),
        ),
      )
      if (shouldRetryBindingAfterDisconnect()) {
        requestBindingIfNeeded(force = true)
      }
    }

    override fun onNullBinding(name: ComponentName) {
      synchronized(lock) {
        bindingEstablished = false
        binderAccess = null
        bindingRequested = false
        lock.notifyAll()
      }
      publishConnectionState(
        RuntimeServiceConnectionState.nullBinding(
          serviceStartRequested = currentServiceStartRequested(),
        ),
      )
    }

    override fun onBindingDied(name: ComponentName) {
      synchronized(lock) {
        bindingEstablished = false
        bindingRequested = false
        binderAccess = null
        lock.notifyAll()
      }
      publishConnectionState(
        RuntimeServiceConnectionState.bindingDied(
          serviceStartRequested = currentServiceStartRequested(),
        ),
      )
      if (shouldRetryBindingAfterDeath()) {
        requestBindingIfNeeded(force = true)
      }
    }
  }

  override fun loadSnapshot(): OpenCrayRuntimeServiceClientSnapshot {
    return withTransientBindingAccess {
      val bridge = binderAccess?.let(::BinderBackedOpenCrayRuntimeServiceBridge) ?: fallbackBridge
      OpenCrayRuntimeServiceClientSnapshot(
        bridgeSnapshot = bridge.loadSnapshot(),
        connectionState = currentConnectionState(),
      )
    }
  }

  override fun peekSnapshot(): OpenCrayRuntimeServiceClientSnapshot? {
    val bridgeSnapshot = binderAccess
      ?.let { access -> runCatching { access.loadSnapshot() }.getOrNull() }
      ?: runCatching { fallbackBridge.loadSnapshot() }.getOrNull()
      ?: return null
    return OpenCrayRuntimeServiceClientSnapshot(
      bridgeSnapshot = bridgeSnapshot,
      connectionState = currentConnectionState(),
    )
  }

  override fun peekProjectionSnapshot(): RuntimeServiceProjectionSnapshot? =
    binderAccess
      ?.let { access -> runCatching { access.loadSnapshot().toProjectionSnapshot() }.getOrNull() }
      ?: projectionSnapshotStore.loadSnapshot()

  override fun loadConnectionState(): RuntimeServiceConnectionState = currentConnectionState()

  override fun peekConnectionState(): RuntimeServiceConnectionState = currentConnectionState()

  override fun loadShellGateway(): OpenCrayShellGateway? =
    withTransientBindingAccess { binderAccess?.loadShellGateway() }

  override fun peekShellGateway(): OpenCrayShellGateway? = binderAccess?.loadShellGateway()

  override fun loadChatRuntimeGateway(): OpenCrayChatRuntimeGateway? =
    withTransientBindingAccess { binderAccess?.loadChatRuntimeGateway() }

  override fun peekChatRuntimeGateway(): OpenCrayChatRuntimeGateway? =
    binderAccess?.loadChatRuntimeGateway()

  override fun awaitChatRuntimeGateway(timeoutMs: Long): OpenCrayChatRuntimeGateway? =
    withAwaitedBindingAccess(timeoutMs) { binderAccess?.loadChatRuntimeGateway() }

  override fun dispatchChatWriteCommand(
    command: OpenCrayChatWriteCommand,
  ): OpenCrayChatWriteDispatchResult? =
    withAwaitedBindingAccess(SERVICE_GATEWAY_BIND_AWAIT_TIMEOUT_MS) {
      binderAccess?.dispatchChatWriteCommand(command)
    }

  override fun loadSkillsGateway(): OpenCraySkillsGateway? =
    withTransientBindingAccess { binderAccess?.loadSkillsGateway() }

  override fun peekSkillsGateway(): OpenCraySkillsGateway? = binderAccess?.loadSkillsGateway()

  override fun awaitSkillsGateway(timeoutMs: Long): OpenCraySkillsGateway? =
    withAwaitedBindingAccess(timeoutMs) { binderAccess?.loadSkillsGateway() }

  override fun dispatchSkillsWriteCommand(
    command: OpenCraySkillsWriteCommand,
  ): OpenCraySkillsWriteDispatchResult? =
    withAwaitedBindingAccess(SERVICE_GATEWAY_BIND_AWAIT_TIMEOUT_MS) {
      binderAccess?.dispatchSkillsWriteCommand(command)
    }

  override fun loadSettingsGateway(): OpenCraySettingsGateway? =
    withTransientBindingAccess { binderAccess?.loadSettingsGateway() }

  override fun peekSettingsGateway(): OpenCraySettingsGateway? = binderAccess?.loadSettingsGateway()

  override fun awaitSettingsGateway(timeoutMs: Long): OpenCraySettingsGateway? =
    withAwaitedBindingAccess(timeoutMs) { binderAccess?.loadSettingsGateway() }

  override fun dispatchSettingsWriteCommand(
    command: OpenCraySettingsWriteCommand,
  ): OpenCraySettingsWriteDispatchResult? =
    withAwaitedBindingAccess(SERVICE_GATEWAY_BIND_AWAIT_TIMEOUT_MS) {
      binderAccess?.dispatchSettingsWriteCommand(command)
    }

  override fun observeConnectionState(listener: (RuntimeServiceConnectionState) -> Unit): () -> Unit =
    registerConnectionObserver(
      listener = listener,
      retainBinding = true,
    )
  override fun observePassiveConnectionState(
    listener: (RuntimeServiceConnectionState) -> Unit,
  ): () -> Unit = registerConnectionObserver(
    listener = listener,
    retainBinding = false,
  )

  private inline fun <T> withTransientBindingAccess(
    block: () -> T,
  ): T {
    ensureStartedAndBinding()
    return try {
      block()
    } finally {
      scheduleBindingReleaseIfIdle()
    }
  }

  private inline fun <T> withAwaitedBindingAccess(
    timeoutMs: Long,
    block: () -> T,
  ): T {
    ensureStartedAndBinding()
    return try {
      awaitBindingAccess(timeoutMs)
      block()
    } finally {
      scheduleBindingReleaseIfIdle()
    }
  }

  private fun registerConnectionObserver(
    listener: (RuntimeServiceConnectionState) -> Unit,
    retainBinding: Boolean,
  ): () -> Unit {
    if (retainBinding) {
      ensureStartedAndBinding()
    }
    synchronized(lock) {
      if (retainBinding) {
        cancelPendingBindingReleaseLocked()
        connectionObserverCount += 1
      }
      listeners += listener
    }
    return {
      val shouldScheduleRelease = synchronized(lock) {
        listeners.remove(listener)
        if (retainBinding && connectionObserverCount > 0) {
          connectionObserverCount -= 1
        }
        retainBinding && connectionObserverCount == 0
      }
      if (shouldScheduleRelease) {
        scheduleBindingReleaseIfIdle()
      }
    }
  }

  private fun ensureStartedAndBinding() {
    var shouldStart = false
    synchronized(lock) {
      cancelPendingBindingReleaseLocked()
      if (!serviceStartRequested) {
        serviceStartRequested = true
        shouldStart = true
      }
    }
    if (shouldStart) {
      startRequester(appContext)
    }
    requestBindingIfNeeded(force = false)
  }

  private fun awaitBindingAccess(timeoutMs: Long): Boolean {
    if (binderAccess != null) {
      return true
    }
    if (timeoutMs <= 0L || isMainThread()) {
      return binderAccess != null
    }
    val deadlineNanos = System.nanoTime() + (timeoutMs * 1_000_000L)
    synchronized(lock) {
      while (binderAccess == null && bindingRequested) {
        val remainingNanos = deadlineNanos - System.nanoTime()
        if (remainingNanos <= 0L) {
          break
        }
        val waitMs = maxOf(1L, remainingNanos / 1_000_000L)
        try {
          lock.wait(waitMs)
        } catch (_: InterruptedException) {
          Thread.currentThread().interrupt()
          break
        }
      }
      return binderAccess != null
    }
  }

  private fun requestBindingIfNeeded(force: Boolean) {
    val shouldBind: Boolean
    synchronized(lock) {
      if (binderAccess != null) {
        return
      }
      if (!force && (bindingRequested || bindingEstablished)) {
        return
      }
      bindingRequested = true
      shouldBind = true
    }
    if (!shouldBind) {
      return
    }
    publishConnectionState(RuntimeServiceConnectionState.bindingPending())
    mainThreadPoster.post {
      val shouldAttemptBind = synchronized(lock) {
        bindingRequested && !bindingEstablished && binderAccess == null
      }
      if (!shouldAttemptBind) {
        return@post
      }
      val bindingResult = runCatching {
        bindingAdapter.bind(
          context = appContext,
          intent = serviceIntentFactory(appContext),
          connection = serviceConnection,
          flags = bindingFlags,
        )
      }.getOrElse { throwable ->
        synchronized(lock) {
          bindingEstablished = false
          bindingRequested = false
          lock.notifyAll()
        }
        publishConnectionState(
          RuntimeServiceConnectionState.bindFailed(
            serviceStartRequested = currentServiceStartRequested(),
            fallbackReason = throwable.message ?: "bind_service_failed",
          ),
        )
        return@post
      }
      if (!bindingResult) {
        synchronized(lock) {
          bindingEstablished = false
          bindingRequested = false
          lock.notifyAll()
        }
        publishConnectionState(
          RuntimeServiceConnectionState.bindFailed(
            serviceStartRequested = currentServiceStartRequested(),
          ),
        )
        return@post
      }
      synchronized(lock) {
        bindingEstablished = true
      }
    }
  }

  private fun scheduleBindingReleaseIfIdle() {
    synchronized(lock) {
      if (connectionObserverCount > 0) {
        cancelPendingBindingReleaseLocked()
        return
      }
      if (!bindingRequested && !bindingEstablished && binderAccess == null) {
        return
      }
      cancelPendingBindingReleaseLocked()
      pendingBindingReleaseTask = bindingReleaseScheduler.schedule(bindingReleaseDelayMs) {
        releaseBindingIfIdle()
      }
    }
  }

  private fun releaseBindingIfIdle() {
    val shouldUnbind: Boolean
    val nextState: RuntimeServiceConnectionState
    synchronized(lock) {
      pendingBindingReleaseTask = null
      if (connectionObserverCount > 0) {
        return
      }
      if (!bindingRequested && !bindingEstablished && binderAccess == null) {
        return
      }
      shouldUnbind = bindingEstablished
      binderAccess = null
      bindingEstablished = false
      bindingRequested = false
      lock.notifyAll()
      nextState = RuntimeServiceConnectionState.bindingReleased(
        serviceStartRequested = serviceStartRequested,
      )
    }
    if (shouldUnbind) {
      runCatching {
        bindingAdapter.unbind(
          context = appContext,
          connection = serviceConnection,
        )
      }
    }
    publishConnectionState(nextState)
  }

  private fun cancelPendingBindingReleaseLocked() {
    pendingBindingReleaseTask?.cancel()
    pendingBindingReleaseTask = null
  }

  private fun shouldRetryBindingAfterDeath(): Boolean = synchronized(lock) {
    connectionObserverCount > 0
  }

  private fun shouldRetryBindingAfterDisconnect(): Boolean = synchronized(lock) {
    connectionObserverCount > 0
  }

  private fun currentServiceStartRequested(): Boolean = synchronized(lock) { serviceStartRequested }

  private fun currentBindingRequested(): Boolean = synchronized(lock) { bindingRequested }

  private fun currentConnectionState(): RuntimeServiceConnectionState = connectionState

  private fun publishConnectionState(nextState: RuntimeServiceConnectionState) {
    val currentListeners = synchronized(lock) {
      if (connectionState == nextState) {
        return
      }
      connectionState = nextState
      listeners.toList()
    }
    if (currentListeners.isEmpty()) {
      return
    }
    mainThreadPoster.post {
      currentListeners.forEach { listener -> listener(nextState) }
    }
  }

  private companion object {
    const val DEFAULT_BINDING_RELEASE_DELAY_MS: Long = 5_000L
  }
}

internal fun RuntimeServiceBridgeSnapshotDependencies.toBridgeSnapshot(
  serviceWorkState: RuntimeServiceWorkState,
  serviceKeepAliveState: RuntimeServiceKeepAliveState = RuntimeServiceKeepAliveState(),
): OpenCrayRuntimeServiceBridgeSnapshot = OpenCrayRuntimeServiceBridgeSnapshot(
  dependencies = dependencies,
  runtimeAccess = runtimeAccess,
  serviceLifecycle = serviceLifecycle,
  serviceWorkState = serviceWorkState,
  serviceKeepAliveState = serviceKeepAliveState,
)
