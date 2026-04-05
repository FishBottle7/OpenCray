package com.opencray.app

import java.util.UUID

internal object SandboxExecutionTraceMetadata {
  const val TRACE_SCHEMA_VERSION: String = "1"

  const val KEY_TRACE_SCHEMA_VERSION: String = "sandboxTraceSchemaVersion"
  const val KEY_TRACE_ID: String = "sandboxTraceId"
  const val KEY_ROUTE_KIND: String = "sandboxTraceRouteKind"
  const val KEY_ROUTE_SPAN_ID: String = "sandboxTraceRouteSpanId"
  const val KEY_EXECUTION_BACKEND: String = "sandboxTraceExecutionBackend"
  const val KEY_EXECUTION_SPAN_ID: String = "sandboxTraceExecutionSpanId"
  const val KEY_EXECUTION_PARENT_SPAN_ID: String = "sandboxTraceExecutionParentSpanId"
  const val KEY_BACKEND_KIND: String = "sandboxTraceBackendKind"
  const val KEY_BACKEND_SPAN_ID: String = "sandboxTraceBackendSpanId"
  const val KEY_BACKEND_PARENT_SPAN_ID: String = "sandboxTraceBackendParentSpanId"
  const val KEY_RECONNECT_SPAN_ID: String = "sandboxTraceReconnectSpanId"
  const val KEY_RECONNECT_PARENT_SPAN_ID: String = "sandboxTraceReconnectParentSpanId"
  const val KEY_PROVIDER_START_SPAN_ID: String = "sandboxTraceProviderStartSpanId"
  const val KEY_PROVIDER_START_PARENT_SPAN_ID: String = "sandboxTraceProviderStartParentSpanId"
  const val KEY_PROVIDER_CONNECT_SPAN_ID: String = "sandboxTraceProviderConnectSpanId"
  const val KEY_PROVIDER_CONNECT_PARENT_SPAN_ID: String = "sandboxTraceProviderConnectParentSpanId"
  const val KEY_PROVIDER_TERMINATE_SPAN_ID: String = "sandboxTraceProviderTerminateSpanId"
  const val KEY_PROVIDER_TERMINATE_PARENT_SPAN_ID: String = "sandboxTraceProviderTerminateParentSpanId"

  fun routeMetadata(
    metadata: Map<String, String>,
    routeKind: String,
    executionBackend: String,
  ): Map<String, String> {
    val traceId = metadata.optionalTraceValue(KEY_TRACE_ID) ?: nextId(prefix = "trace")
    val routeSpanId = metadata.optionalTraceValue(KEY_ROUTE_SPAN_ID) ?: nextId(prefix = "route")
    val executionSpanId = metadata.optionalTraceValue(KEY_EXECUTION_SPAN_ID) ?: nextId(prefix = "exec")
    return buildMap {
      put(KEY_TRACE_SCHEMA_VERSION, TRACE_SCHEMA_VERSION)
      put(KEY_TRACE_ID, traceId)
      put(KEY_ROUTE_KIND, routeKind)
      put(KEY_ROUTE_SPAN_ID, routeSpanId)
      put(KEY_EXECUTION_BACKEND, executionBackend)
      put(KEY_EXECUTION_SPAN_ID, executionSpanId)
      put(KEY_EXECUTION_PARENT_SPAN_ID, routeSpanId)
    }
  }

  fun backendMetadata(
    metadata: Map<String, String>,
    backendKind: String,
  ): Map<String, String> {
    val traceId = metadata.optionalTraceValue(KEY_TRACE_ID) ?: nextId(prefix = "trace")
    val backendSpanId = metadata.optionalTraceValue(KEY_BACKEND_SPAN_ID) ?: nextId(prefix = "backend")
    return buildMap {
      put(KEY_TRACE_SCHEMA_VERSION, TRACE_SCHEMA_VERSION)
      put(KEY_TRACE_ID, traceId)
      put(KEY_BACKEND_KIND, backendKind)
      put(KEY_BACKEND_SPAN_ID, backendSpanId)
      metadata.optionalTraceValue(KEY_RECONNECT_SPAN_ID)
        ?.let { reconnectSpanId -> put(KEY_BACKEND_PARENT_SPAN_ID, reconnectSpanId) }
        ?: metadata.optionalTraceValue(KEY_EXECUTION_SPAN_ID)
          ?.let { executionSpanId -> put(KEY_BACKEND_PARENT_SPAN_ID, executionSpanId) }
          ?: metadata.optionalTraceValue(KEY_ROUTE_SPAN_ID)
            ?.let { routeSpanId -> put(KEY_BACKEND_PARENT_SPAN_ID, routeSpanId) }
    }
  }

  fun reconnectMetadata(
    metadata: Map<String, String>,
  ): Map<String, String> {
    val traceId = metadata.optionalTraceValue(KEY_TRACE_ID) ?: nextId(prefix = "trace")
    val reconnectSpanId = nextId(prefix = "reconnect")
    return buildMap {
      put(KEY_TRACE_SCHEMA_VERSION, TRACE_SCHEMA_VERSION)
      put(KEY_TRACE_ID, traceId)
      put(KEY_RECONNECT_SPAN_ID, reconnectSpanId)
      metadata.optionalTraceValue(KEY_BACKEND_SPAN_ID)
        ?.let { backendSpanId -> put(KEY_RECONNECT_PARENT_SPAN_ID, backendSpanId) }
        ?: metadata.optionalTraceValue(KEY_EXECUTION_SPAN_ID)
          ?.let { executionSpanId -> put(KEY_RECONNECT_PARENT_SPAN_ID, executionSpanId) }
    }
  }

  fun providerStartMetadata(
    metadata: Map<String, String>,
  ): Map<String, String> = providerOperationMetadata(
    metadata = metadata,
    spanKey = KEY_PROVIDER_START_SPAN_ID,
    parentKey = KEY_PROVIDER_START_PARENT_SPAN_ID,
    prefix = "provider-start",
    preferReconnectParent = false,
  )

  fun providerConnectMetadata(
    metadata: Map<String, String>,
  ): Map<String, String> = providerOperationMetadata(
    metadata = metadata,
    spanKey = KEY_PROVIDER_CONNECT_SPAN_ID,
    parentKey = KEY_PROVIDER_CONNECT_PARENT_SPAN_ID,
    prefix = "provider-connect",
    preferReconnectParent = true,
  )

  fun providerTerminateMetadata(
    metadata: Map<String, String>,
  ): Map<String, String> = providerOperationMetadata(
    metadata = metadata,
    spanKey = KEY_PROVIDER_TERMINATE_SPAN_ID,
    parentKey = KEY_PROVIDER_TERMINATE_PARENT_SPAN_ID,
    prefix = "provider-terminate",
    preferReconnectParent = false,
  )

  private fun providerOperationMetadata(
    metadata: Map<String, String>,
    spanKey: String,
    parentKey: String,
    prefix: String,
    preferReconnectParent: Boolean,
  ): Map<String, String> {
    val traceId = metadata.optionalTraceValue(KEY_TRACE_ID) ?: nextId(prefix = "trace")
    val spanId = metadata.optionalTraceValue(spanKey) ?: nextId(prefix = prefix)
    return buildMap {
      put(KEY_TRACE_SCHEMA_VERSION, TRACE_SCHEMA_VERSION)
      put(KEY_TRACE_ID, traceId)
      put(spanKey, spanId)
      val parentSpanId = if (preferReconnectParent) {
        metadata.optionalTraceValue(KEY_RECONNECT_SPAN_ID)
          ?: metadata.optionalTraceValue(KEY_BACKEND_SPAN_ID)
          ?: metadata.optionalTraceValue(KEY_EXECUTION_SPAN_ID)
      } else {
        metadata.optionalTraceValue(KEY_BACKEND_SPAN_ID)
          ?: metadata.optionalTraceValue(KEY_RECONNECT_SPAN_ID)
          ?: metadata.optionalTraceValue(KEY_EXECUTION_SPAN_ID)
      }
      parentSpanId?.let { put(parentKey, it) }
    }
  }

  private fun Map<String, String>.optionalTraceValue(key: String): String? =
    get(key)?.trim()?.takeIf(String::isNotBlank)

  private fun nextId(prefix: String): String =
    "$prefix-${UUID.randomUUID().toString().replace("-", "").take(16)}"
}
