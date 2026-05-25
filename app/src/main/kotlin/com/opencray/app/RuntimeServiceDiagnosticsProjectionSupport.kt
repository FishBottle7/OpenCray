package com.opencray.app

internal fun MutableMap<String, Any?>.putRuntimeServiceDiagnosticsSnapshot(
  hostLifecycle: HostRuntimeLifecycleDescriptor,
  runtimeControllerLifecycle: RuntimeControllerLifecycleDescriptor? = null,
  runtimeOwnerLifecycle: HostRuntimeLifecycleDescriptor? = null,
  runtimeOwnerWorkSummary: RuntimeOwnerWorkSummary? = null,
  runtimeServiceLifecycle: RuntimeServiceLifecycleDescriptor? = null,
  runtimeServiceWorkState: RuntimeServiceWorkState? = null,
  runtimeServiceKeepAliveState: RuntimeServiceKeepAliveState? = null,
  runtimeServiceConnectionState: RuntimeServiceConnectionState? = null,
  localRuntimeServerState: LocalRuntimeServerState? = null,
  includeNullRuntimeServiceFields: Boolean = false,
) {
  localRuntimeServerState?.let { state ->
    put("localRuntimeServerState", state.snapshotMap())
  }
  put("hostLifecycle", hostLifecycle.snapshotMap())
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
