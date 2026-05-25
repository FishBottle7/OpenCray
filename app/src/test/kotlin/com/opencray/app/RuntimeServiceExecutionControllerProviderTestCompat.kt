package com.opencray.app

private val compatDefaultRuntimeServiceExecutionControllerProviderInstance =
  ProcessScopedRuntimeServiceExecutionControllerProvider(
    runtimeTarget = DEFAULT_RUNTIME_SERVICE_TARGET,
    runtimeExecutionDependenciesLoader = testRuntimeExecutionDependenciesLoader(),
    runtimeOwnerBootstrapProvider = defaultRuntimeOwnerBootstrapProvider(),
  )

internal fun defaultRuntimeServiceExecutionControllerProvider():
  RuntimeServiceExecutionControllerProvider =
    compatDefaultRuntimeServiceExecutionControllerProviderInstance

internal fun defaultProcessScopedRuntimeServiceExecutionControllerProviderForTest():
  ProcessScopedRuntimeServiceExecutionControllerProvider =
    compatDefaultRuntimeServiceExecutionControllerProviderInstance

internal fun ProcessScopedRuntimeServiceExecutionControllerProvider.peek():
  RuntimeServiceExecutionController? =
    processScopedRuntimeServiceExecutionControllerField().get(this)
      as? RuntimeServiceExecutionController

internal fun ProcessScopedRuntimeServiceExecutionControllerProvider.reset():
  RuntimeServiceExecutionController? {
  val field = processScopedRuntimeServiceExecutionControllerField()
  val previousController = synchronized(this) {
    @Suppress("UNCHECKED_CAST")
    (field.get(this) as? RuntimeServiceExecutionController).also {
      field.set(this, null)
    }
  }
  previousController?.dispose()
  return previousController
}

internal fun ProcessScopedRuntimeServiceExecutionControllerProvider.swap(
  controller: RuntimeServiceExecutionController?,
): RuntimeServiceExecutionController? {
  if (controller == null) {
    return reset()
  }
  val field = processScopedRuntimeServiceExecutionControllerField()
  val previousController = synchronized(this) {
    val currentController = field.get(this) as? RuntimeServiceExecutionController
    if (currentController === controller) {
      return@synchronized null
    }
    field.set(this, controller)
    currentController
  }
  previousController?.dispose()
  return previousController
}

private fun processScopedRuntimeServiceExecutionControllerField() =
  ProcessScopedRuntimeServiceExecutionControllerProvider::class.java
    .getDeclaredField("controller")
    .apply { isAccessible = true }
