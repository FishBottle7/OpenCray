package com.opencray.app

internal data class OpenCrayRuntimeServiceBridgeSnapshot(
  val dependencies: OpenCrayRuntimeContextDependencies,
  val runtimeAccess: OpenCrayRuntimeOwnerAccess,
  val serviceLifecycle: RuntimeServiceLifecycleDescriptor,
)

internal interface OpenCrayRuntimeServiceBridge {
  fun loadSnapshot(): OpenCrayRuntimeServiceBridgeSnapshot
}

internal interface OpenCrayRuntimeServiceBinderAccess {
  fun loadSnapshot(): OpenCrayRuntimeServiceBridgeSnapshot
}

internal class InProcessOpenCrayRuntimeServiceBridge(
  private val hostProvider: () -> OpenCrayRuntimeServiceHost,
) : OpenCrayRuntimeServiceBridge {
  override fun loadSnapshot(): OpenCrayRuntimeServiceBridgeSnapshot =
    hostProvider().toBridgeSnapshot()
}

internal class BinderBackedOpenCrayRuntimeServiceBridge(
  private val binderAccess: OpenCrayRuntimeServiceBinderAccess,
) : OpenCrayRuntimeServiceBridge {
  override fun loadSnapshot(): OpenCrayRuntimeServiceBridgeSnapshot =
    binderAccess.loadSnapshot()
}

internal fun OpenCrayRuntimeServiceHost.toBridgeSnapshot(): OpenCrayRuntimeServiceBridgeSnapshot =
  OpenCrayRuntimeServiceBridgeSnapshot(
    dependencies = dependencies,
    runtimeAccess = runtimeAccess,
    serviceLifecycle = serviceLifecycle,
  )
