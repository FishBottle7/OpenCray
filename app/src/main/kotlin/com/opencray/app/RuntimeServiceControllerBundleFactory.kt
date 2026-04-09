package com.opencray.app

import android.app.Service
import android.content.Context
import android.os.Handler

internal data class RuntimeServiceControllerBundle(
  val keepAliveController: RuntimeServiceKeepAliveController,
  val runtimeForegroundController: RuntimeForegroundController,
)

internal fun interface RuntimeServiceControllerBundleFactory {
  fun create(
    service: Service,
    appContext: Context,
    mainHandler: Handler,
  ): RuntimeServiceControllerBundle
}

internal object DefaultRuntimeServiceControllerBundleFactory :
  RuntimeServiceControllerBundleFactory {
  override fun create(
    service: Service,
    appContext: Context,
    mainHandler: Handler,
  ): RuntimeServiceControllerBundle = RuntimeServiceControllerBundle(
    keepAliveController = RuntimeServiceKeepAliveController(
      scheduler = HandlerRuntimeServiceDelayScheduler(mainHandler),
      stopRequester = service::stopSelfResult,
    ),
    runtimeForegroundController = RuntimeForegroundController(
      serviceAdapter = AndroidRuntimeForegroundServiceAdapter(
        service = service,
        notificationFactory = RuntimeActiveNotificationFactory(appContext),
      ),
      mainThreadPoster = HandlerMainThreadPoster(mainHandler),
    ),
  )
}
