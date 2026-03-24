package com.opencray.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper

internal class OpenCrayAgentRuntimeService : Service() {
  private val binder = LocalBinder()
  private val serviceHost: OpenCrayRuntimeServiceHost by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    OpenCrayRuntimeServiceHostRegistry.getOrCreate(applicationContext)
  }
  private val keepAliveController: RuntimeServiceKeepAliveController by lazy(
    LazyThreadSafetyMode.SYNCHRONIZED,
  ) {
    RuntimeServiceKeepAliveController(
      scheduler = HandlerRuntimeServiceDelayScheduler(Handler(Looper.getMainLooper())),
      stopRequester = ::stopSelfResult,
    )
  }
  private var serviceWorkStateObservationDisposer: (() -> Unit)? = null
  private val serviceHostRuntime: OpenCrayHostRuntime by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    OpenCrayHostRuntime.createForRuntimeService(
      appContext = applicationContext,
      serviceHost = serviceHost,
      runtimeServiceKeepAliveStateProvider = keepAliveController::currentState,
      runtimeServiceKeepAliveChangeRegistrar = RuntimeServiceKeepAliveChangeRegistrar { listener ->
        keepAliveController.observe { listener() }
      },
    )
  }

  override fun onCreate() {
    super.onCreate()
    OpenCrayRuntimeServiceHostRegistry.getOrCreate(
      context = applicationContext,
      serviceLifecycleFactory = { RuntimeServiceLifecycleDescriptor() },
    )
    OpenCrayLocalRuntimeServerRegistry.ensureStarted(applicationContext)
    val serviceWorkStateTracker = serviceHost.serviceWorkStateTracker
    serviceWorkStateObservationDisposer = serviceWorkStateTracker.observe(
      keepAliveController::onWorkStateChanged,
    )
    keepAliveController.onWorkStateChanged(serviceWorkStateTracker.currentState())
  }

  override fun onStartCommand(
    intent: Intent?,
    flags: Int,
    startId: Int,
  ): Int {
    keepAliveController.onStartCommand(startId)
    return START_NOT_STICKY
  }

  override fun onBind(intent: Intent?): IBinder = binder

  override fun onDestroy() {
    serviceWorkStateObservationDisposer?.invoke()
    serviceWorkStateObservationDisposer = null
    keepAliveController.onDestroy()
    super.onDestroy()
  }

  internal inner class LocalBinder : Binder(), OpenCrayRuntimeServiceBinderAccess {
    override fun loadSnapshot(): OpenCrayRuntimeServiceBridgeSnapshot =
      serviceHost.toBridgeSnapshot(
        serviceKeepAliveState = keepAliveController.currentState(),
      )

    override fun loadShellGateway(): OpenCrayShellGateway = serviceHostRuntime

    override fun loadChatRuntimeGateway(): OpenCrayChatRuntimeGateway = serviceHostRuntime

    override fun loadSkillsGateway(): OpenCraySkillsGateway = serviceHostRuntime

    override fun loadSettingsGateway(): OpenCraySettingsGateway = serviceHostRuntime

    fun peekRuntimeOwnerLifecycle(): Map<String, Any?> =
      loadSnapshot()
        .runtimeAccess
        .lifecycleDescriptor
        .snapshotMap()

    fun peekRuntimeServiceLifecycle(): Map<String, Any?> =
      loadSnapshot()
        .serviceLifecycle
        .snapshotMap()

    fun peekRuntimeServiceWorkState(): Map<String, Any?> =
      loadSnapshot()
        .serviceWorkState
        .snapshotMap()

    fun peekRuntimeServiceKeepAliveState(): Map<String, Any?> =
      loadSnapshot()
        .serviceKeepAliveState
        .snapshotMap()
  }

  companion object {
    @Volatile
    private var client: OpenCrayRuntimeServiceClient? = null

    fun ensureStarted(context: Context) {
      val appContext = context.applicationContext
      OpenCrayRuntimeServiceHostRegistry.getOrCreate(
        context = appContext,
        serviceLifecycleFactory = { RuntimeServiceLifecycleDescriptor() },
      )
      runCatching {
        appContext.startService(
          Intent(appContext, OpenCrayAgentRuntimeService::class.java),
        )
      }
    }

    fun ensureClient(context: Context): OpenCrayRuntimeServiceClient {
      val appContext = context.applicationContext
      return client ?: synchronized(this) {
        client ?: AndroidBindingOpenCrayRuntimeServiceClient(appContext).also { created ->
          client = created
        }
      }
    }
  }
}
