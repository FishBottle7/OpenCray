package com.opencray.app

internal fun MutableMap<String, Any?>.putRuntimeServiceDiagnosticsSnapshot(
  hostLifecycle: HostRuntimeLifecycleDescriptor,
  runtimeControllerLifecycle: RuntimeControllerLifecycleDescriptor? = null,
  runtimeOwnerLifecycle: HostRuntimeLifecycleDescriptor? = null,
  runtimeOwnerWorkSummary: RuntimeOwnerWorkSummary? = null,
  runtimeServiceLifecycle: RuntimeServiceLifecycleDescriptor? = null,
  runtimeServiceWorkState: RuntimeServiceWorkState? = null,
  runtimeServiceKeepAliveState: RuntimeServiceKeepAliveState? = null,
  runtimeServiceOwnerLease: RuntimeServiceOwnerLease? = null,
  runtimeServiceInterruptedRunRepair: RuntimeServiceInterruptedRunRepairProjection? = null,
  runtimeServiceConnectionState: RuntimeServiceConnectionState? = null,
  localRuntimeServerState: LocalRuntimeServerState? = null,
  includeNullRuntimeServiceFields: Boolean = false,
) {
  localRuntimeServerState?.let { state ->
    put("localRuntimeServerState", state.snapshotMap())
  }
  put("hostLifecycle", hostLifecycle.snapshotMap())
  put(
    "runtimeExecutionOwnership",
    runtimeExecutionOwnershipDescriptor(
      hostLifecycle = hostLifecycle,
      runtimeControllerLifecycle = runtimeControllerLifecycle,
      runtimeOwnerLifecycle = runtimeOwnerLifecycle,
      runtimeServiceLifecycle = runtimeServiceLifecycle,
    ).snapshotMap(),
  )
  runtimeControllerLifecycle?.let { lifecycle ->
    put("runtimeControllerLifecycle", lifecycle.snapshotMap())
  }
  runtimeOwnerLifecycle?.let { lifecycle ->
    put("runtimeOwnerLifecycle", lifecycle.snapshotMap())
  }
  runtimeOwnerWorkSummary?.let { summary ->
    put("runtimeOwnerWorkSummary", summary.snapshotMap())
  }
  putRuntimeServiceField(
    key = "runtimeServiceLifecycle",
    value = runtimeServiceLifecycle?.snapshotMap(),
    includeNullField = includeNullRuntimeServiceFields,
  )
  putRuntimeServiceField(
    key = "runtimeServiceWorkState",
    value = runtimeServiceWorkState?.snapshotMap(),
    includeNullField = includeNullRuntimeServiceFields,
  )
  putRuntimeServiceField(
    key = "runtimeServiceKeepAliveState",
    value = runtimeServiceKeepAliveState?.snapshotMap(),
    includeNullField = includeNullRuntimeServiceFields,
  )
  putRuntimeServiceField(
    key = "runtimeServiceOwnerLease",
    value = runtimeServiceOwnerLease?.snapshotMap(),
    includeNullField = includeNullRuntimeServiceFields,
  )
  putRuntimeServiceField(
    key = "runtimeServiceInterruptedRunRepair",
    value = runtimeServiceInterruptedRunRepair?.snapshotMap(),
    includeNullField = includeNullRuntimeServiceFields,
  )
  putRuntimeServiceField(
    key = "runtimeServiceConnectionState",
    value = runtimeServiceConnectionState?.snapshotMap(),
    includeNullField = includeNullRuntimeServiceFields,
  )
}

private fun MutableMap<String, Any?>.putRuntimeServiceField(
  key: String,
  value: Any?,
  includeNullField: Boolean,
) {
  if (includeNullField || value != null) {
    put(key, value)
  }
}

internal data class RuntimeExecutionOwnershipDescriptor(
  val ownershipTier: String = RuntimeExecutionOwnershipTiers.RUNTIME_PROCESS,
  val controllerProcessSeparate: Boolean = false,
  val executionProcessStartId: String,
  val runtimeOwnerProcessStartId: String,
  val runtimeControllerProcessStartId: String? = null,
  val runtimeServiceProcessStartId: String? = null,
  val runtimeServiceProcessName: String? = null,
  val expectedRuntimeServiceProcessName: String? = null,
  val dedicatedRuntimeServiceProcess: Boolean? = null,
  val runtimeServiceProcessMismatchReason: String? = null,
) {
  fun snapshotMap(): Map<String, Any?> = buildMap {
    put("ownershipTier", ownershipTier)
    put("controllerProcessSeparate", controllerProcessSeparate)
    put("executionProcessStartId", executionProcessStartId)
    put("runtimeOwnerProcessStartId", runtimeOwnerProcessStartId)
    runtimeControllerProcessStartId?.let { processStartId ->
      put("runtimeControllerProcessStartId", processStartId)
    }
    runtimeServiceProcessStartId?.let { processStartId ->
      put("runtimeServiceProcessStartId", processStartId)
    }
    runtimeServiceProcessName?.let { processName ->
      put("runtimeServiceProcessName", processName)
    }
    expectedRuntimeServiceProcessName?.let { processName ->
      put("expectedRuntimeServiceProcessName", processName)
    }
    dedicatedRuntimeServiceProcess?.let { isDedicated ->
      put("dedicatedRuntimeServiceProcess", isDedicated)
    }
    runtimeServiceProcessMismatchReason?.let { reason ->
      put("runtimeServiceProcessMismatchReason", reason)
    }
  }
}

private fun runtimeExecutionOwnershipDescriptor(
  hostLifecycle: HostRuntimeLifecycleDescriptor,
  runtimeControllerLifecycle: RuntimeControllerLifecycleDescriptor?,
  runtimeOwnerLifecycle: HostRuntimeLifecycleDescriptor?,
  runtimeServiceLifecycle: RuntimeServiceLifecycleDescriptor?,
): RuntimeExecutionOwnershipDescriptor {
  val resolvedRuntimeOwnerLifecycle = runtimeOwnerLifecycle ?: hostLifecycle
  val serviceProcess = runtimeServiceLifecycle?.serviceProcess
  return RuntimeExecutionOwnershipDescriptor(
    executionProcessStartId = runtimeServiceLifecycle?.processStartId
      ?: resolvedRuntimeOwnerLifecycle.processStartId,
    runtimeOwnerProcessStartId = resolvedRuntimeOwnerLifecycle.processStartId,
    runtimeControllerProcessStartId = runtimeControllerLifecycle?.processStartId,
    runtimeServiceProcessStartId = runtimeServiceLifecycle?.processStartId,
    runtimeServiceProcessName = serviceProcess?.processName,
    expectedRuntimeServiceProcessName = serviceProcess?.expectedProcessName,
    dedicatedRuntimeServiceProcess = serviceProcess?.isDedicatedRuntimeProcess,
    runtimeServiceProcessMismatchReason = serviceProcess?.mismatchReason,
  )
}
