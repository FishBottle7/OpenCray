package com.opencray.app

import android.content.Context
import java.util.concurrent.CountDownLatch

internal data class OpenCrayRuntimeServiceTransportBootstrap(
  val gatewayBundle: OpenCrayRuntimeServiceGatewayBundle,
  val ensureStarted: () -> Boolean = { true },
  val dispose: () -> Unit = {},
)

internal fun interface OpenCrayRuntimeServiceTransportBootstrapFactory {
  fun create(
    appContext: Context,
    runtimeTarget: RuntimeServiceTarget,
    localGatewayProvider: () -> OpenCrayLocalHostGateway,
    gatewayDependencies: RuntimeServiceGatewayBundleDependencies,
    runtimeServiceGatewayBundleFactory: RuntimeServiceGatewayBundleFactory,
    runtimeServiceKeepAliveStateProvider: () -> RuntimeServiceKeepAliveState,
    runtimeServiceKeepAliveChangeRegistrar: RuntimeServiceKeepAliveChangeRegistrar,
    transportCoordinator: RuntimeServiceTransportCoordinator,
  ): OpenCrayRuntimeServiceTransportBootstrap
}

internal class DefaultOpenCrayRuntimeServiceTransportBootstrapFactory(
  private val loopbackBootstrapFactory: RuntimeServiceLoopbackBootstrapFactory =
    DefaultRuntimeServiceLoopbackBootstrapFactory(),
) : OpenCrayRuntimeServiceTransportBootstrapFactory {
  override fun create(
    appContext: Context,
    runtimeTarget: RuntimeServiceTarget,
    localGatewayProvider: () -> OpenCrayLocalHostGateway,
    gatewayDependencies: RuntimeServiceGatewayBundleDependencies,
    runtimeServiceGatewayBundleFactory: RuntimeServiceGatewayBundleFactory,
    runtimeServiceKeepAliveStateProvider: () -> RuntimeServiceKeepAliveState,
    runtimeServiceKeepAliveChangeRegistrar: RuntimeServiceKeepAliveChangeRegistrar,
    transportCoordinator: RuntimeServiceTransportCoordinator,
  ): OpenCrayRuntimeServiceTransportBootstrap {
    val gatewayBundle = runtimeServiceGatewayBundleFactory.create(
      appContext = appContext,
      gatewayDependencies = gatewayDependencies,
      runtimeServiceKeepAliveStateProvider = runtimeServiceKeepAliveStateProvider,
      runtimeServiceKeepAliveChangeRegistrar = runtimeServiceKeepAliveChangeRegistrar,
    )
    val loopbackBootstrap = loopbackBootstrapFactory.create(
      appContext = appContext,
      runtimeTarget = runtimeTarget,
      localGatewayProvider = localGatewayProvider,
      gatewayBundle = gatewayBundle,
      transportCoordinator = transportCoordinator,
      runtimeOwnerWriteGuard = gatewayDependencies.runtimeServiceOwnerWriteGuard,
    )
    val lock = Any()
    var starting = false
    var activated = false
    var disposed = false
    var startCompletion: CountDownLatch? = null
    var loopbackDisposeClaimed = false
    fun claimLoopbackDisposeLocked(): Boolean {
      if (loopbackDisposeClaimed) {
        return false
      }
      loopbackDisposeClaimed = true
      return true
    }
    return OpenCrayRuntimeServiceTransportBootstrap(
      gatewayBundle = gatewayBundle,
      ensureStarted = ensureStarted@{
        val completion = synchronized(lock) {
          if (disposed) {
            return@ensureStarted false
          }
          if (starting || activated) {
            null
          } else {
            starting = true
            CountDownLatch(1).also { created ->
              startCompletion = created
            }
          }
        }
        if (completion == null) {
          return@ensureStarted synchronized(lock) { activated }
        }
        try {
          val started = loopbackBootstrap.ensureStarted()
          if (!started || synchronized(lock) { disposed }) {
            return@ensureStarted false
          }
          try {
            transportCoordinator.bindGatewayBundle(gatewayBundle)
          } catch (throwable: Throwable) {
            val boundDespiteFailure = runCatching {
              transportCoordinator.currentGatewayBundle() === gatewayBundle
            }.getOrDefault(false)
            if (boundDespiteFailure) {
              synchronized(lock) {
                activated = true
              }
            }
            throw throwable
          }
          synchronized(lock) {
            activated = true
            !disposed
          }
        } finally {
          synchronized(lock) {
            starting = false
            if (startCompletion === completion) {
              startCompletion = null
            }
          }
          completion.countDown()
        }
      },
      dispose = dispose@{
        val disposeAction = synchronized(lock) {
          if (disposed) {
            return@synchronized null
          }
          disposed = true
          Pair(startCompletion, claimLoopbackDisposeLocked())
        } ?: return@dispose
        try {
          if (disposeAction.second) {
            loopbackBootstrap.dispose()
          }
        } finally {
          disposeAction.first?.awaitUninterruptibly()
          val releaseFromCoordinator = synchronized(lock) { activated }
          if (releaseFromCoordinator) {
            transportCoordinator.releaseGatewayBundle(gatewayBundle)
          } else {
            gatewayBundle.dispose()
          }
        }
      },
    )
  }
}

private fun CountDownLatch.awaitUninterruptibly() {
  var interrupted = false
  while (true) {
    try {
      await()
      break
    } catch (_: InterruptedException) {
      interrupted = true
    }
  }
  if (interrupted) {
    Thread.currentThread().interrupt()
  }
}
