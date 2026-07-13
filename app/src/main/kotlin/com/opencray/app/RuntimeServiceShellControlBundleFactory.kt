package com.opencray.app

import android.app.Service
import android.content.Context
import android.os.Handler
import android.os.Looper

internal data class RuntimeServiceShellControlBundle(
  val keepAliveController: RuntimeServiceKeepAliveController,
  val runtimeForegroundController: RuntimeForegroundController,
  val attach: () -> Unit = { },
  val dispose: () -> Unit = { },
)

internal data class RuntimeServiceRetainedShellControl(
  val keepAliveController: RuntimeServiceKeepAliveController,
  val runtimeForegroundController: RuntimeForegroundController,
)

internal fun interface RuntimeServiceShellControlBundleFactory {
  fun create(
    service: Service,
    appContext: Context,
    mainHandler: Handler,
    runtimeTarget: RuntimeServiceTarget,
    retainedShellControl: RuntimeServiceRetainedShellControl,
  ): RuntimeServiceShellControlBundle
}

internal class DefaultRuntimeServiceShellControlBundleFactory(
  private val appVisibilitySignalAccessProvider: (Context) -> AppVisibilitySignalAccess =
    ::defaultAppVisibilitySignalAccess,
  private val runtimeForegroundServiceAdapterFactory: (
    Service,
    Context,
    RuntimeServiceTarget,
  ) -> RuntimeForegroundServiceAdapter =
    { service, context, target ->
      AndroidRuntimeForegroundServiceAdapter(
        service = service,
        notificationFactory = RuntimeActiveNotificationFactory(context),
        notificationId = runtimeActiveForegroundNotificationId(target),
      )
    },
  private val stopRequesterProvider: (Service) -> ((Int) -> Boolean) =
    ::runtimeServiceStopRequester,
) : RuntimeServiceShellControlBundleFactory {
  override fun create(
    service: Service,
    appContext: Context,
    mainHandler: Handler,
    runtimeTarget: RuntimeServiceTarget,
    retainedShellControl: RuntimeServiceRetainedShellControl,
  ): RuntimeServiceShellControlBundle {
    val appVisibilitySignalAccess = appVisibilitySignalAccessProvider(appContext)
    val runtimeForegroundServiceAdapter = runtimeForegroundServiceAdapterFactory(
      service,
      appContext,
      runtimeTarget,
    )
    val visibilityCoordinator = RuntimeServiceShellVisibilityCoordinator(
      appVisibleProvider = appVisibilitySignalAccess::currentVisibility,
      visibilityRegistrar = appVisibilitySignalAccess::observe,
      keepAliveController = retainedShellControl.keepAliveController,
      runtimeForegroundController = retainedShellControl.runtimeForegroundController,
    )
    return RuntimeServiceShellControlBundle(
      keepAliveController = retainedShellControl.keepAliveController,
      runtimeForegroundController = retainedShellControl.runtimeForegroundController,
      attach = {
        retainedShellControl.keepAliveController.bindStopRequester(
          stopRequesterProvider(service),
        )
        retainedShellControl.runtimeForegroundController.bindServiceAdapter(
          runtimeForegroundServiceAdapter,
        )
        visibilityCoordinator.attach()
      },
      dispose = {
        visibilityCoordinator.dispose()
        retainedShellControl.runtimeForegroundController.unbindServiceAdapter(
          runtimeForegroundServiceAdapter,
        )
        retainedShellControl.keepAliveController.unbindStopRequester()
      },
    )
  }
}

internal fun createRuntimeServiceRetainedShellControl(
  appContext: Context,
): RuntimeServiceRetainedShellControl {
  val mainHandler = Handler(Looper.getMainLooper())
  val appVisibilitySignalAccess = defaultAppVisibilitySignalAccess(appContext)
  val strongBackgroundSettingsAccess = AndroidStrongBackgroundSettingsAccess.fromContext(
    appContext,
  )
  val policyProvider = {
    strongBackgroundShellPolicy(
      snapshot = strongBackgroundSettingsAccess.loadSnapshot(),
    )
  }
  return RuntimeServiceRetainedShellControl(
    keepAliveController = RuntimeServiceKeepAliveController(
      scheduler = HandlerRuntimeServiceDelayScheduler(mainHandler),
      backgroundIdleGraceMsProvider = {
        policyProvider().backgroundIdleGraceMs
      },
      appVisibleProvider = appVisibilitySignalAccess::currentVisibility,
    ),
    runtimeForegroundController = RuntimeForegroundController(
      retainForegroundDuringIdleGraceProvider = {
        policyProvider().retainForegroundDuringIdleGrace
      },
      appVisibleProvider = appVisibilitySignalAccess::currentVisibility,
      mainThreadPoster = HandlerMainThreadPoster(mainHandler),
    ),
  )
}

internal fun runtimeServiceStopRequester(
  service: Service,
): (Int) -> Boolean = { startId ->
  service.stopSelfResult(startId)
}

private data class RuntimeServiceBackgroundShellPolicy(
  val backgroundIdleGraceMs: Long,
  val retainForegroundDuringIdleGrace: Boolean,
)

private class RuntimeServiceShellVisibilityCoordinator(
  private val appVisibleProvider: () -> Boolean,
  private val visibilityRegistrar: ((Boolean) -> Unit) -> (() -> Unit),
  private val keepAliveController: RuntimeServiceKeepAliveController,
  private val runtimeForegroundController: RuntimeForegroundController,
) {
  private val lock = Any()
  private var attached: Boolean = false
  private var visibilityObservationDisposer: (() -> Unit)? = null

  fun attach() {
    synchronized(lock) {
      if (attached) {
        return
      }
      attached = true
    }
    val disposer = visibilityRegistrar(::onAppVisibilityChanged)
    synchronized(lock) {
      if (!attached) {
        disposer.invoke()
        return
      }
      visibilityObservationDisposer = disposer
    }
    onAppVisibilityChanged(appVisibleProvider())
  }

  fun dispose() {
    val disposer = synchronized(lock) {
      if (!attached) {
        return
      }
      attached = false
      val resolvedDisposer = visibilityObservationDisposer
      visibilityObservationDisposer = null
      resolvedDisposer
    }
    disposer?.invoke()
  }

  private fun onAppVisibilityChanged(appVisible: Boolean) {
    keepAliveController.onAppVisibilityChanged(appVisible)
    runtimeForegroundController.onAppVisibilityChanged(appVisible)
  }
}

private fun strongBackgroundShellPolicy(
  snapshot: Map<String, Any?>,
): RuntimeServiceBackgroundShellPolicy {
  val tierId = snapshot["tierId"] as? String ?: StrongBackgroundTierIds.BASELINE
  return when (tierId) {
    StrongBackgroundTierIds.STRONG_BACKGROUND -> RuntimeServiceBackgroundShellPolicy(
      backgroundIdleGraceMs = STRONG_BACKGROUND_IDLE_GRACE_MS,
      retainForegroundDuringIdleGrace = true,
    )

    StrongBackgroundTierIds.ACTIVE_BACKGROUND -> RuntimeServiceBackgroundShellPolicy(
      backgroundIdleGraceMs = ACTIVE_BACKGROUND_IDLE_GRACE_MS,
      retainForegroundDuringIdleGrace = true,
    )

    else -> RuntimeServiceBackgroundShellPolicy(
      backgroundIdleGraceMs = RuntimeServiceKeepAliveState.DEFAULT_IDLE_GRACE_MS,
      retainForegroundDuringIdleGrace = false,
    )
  }
}

private const val ACTIVE_BACKGROUND_IDLE_GRACE_MS: Long = 5 * 60_000L
private const val STRONG_BACKGROUND_IDLE_GRACE_MS: Long = 15 * 60_000L
