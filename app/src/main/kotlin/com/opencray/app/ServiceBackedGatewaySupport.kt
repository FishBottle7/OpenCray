package com.opencray.app

internal fun serviceOwnedGatewayUnavailableMessage(
  surface: String,
  operation: String,
  connectionState: RuntimeServiceConnectionState,
): String = buildString {
  append(surface)
  append(" operation '")
  append(operation)
  append("' requires a binder-backed runtime service gateway. ")
  append("Current connection state: phase=")
  append(connectionState.phase)
  append(", transport=")
  append(connectionState.transport)
  append(", binderAvailable=")
  append(connectionState.binderAvailable)
  connectionState.fallbackReason
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?.let { reason ->
      append(", fallbackReason=")
      append(reason)
    }
}

internal fun <T> requireBinderBackedGateway(
  surface: String,
  operation: String,
  gateway: T?,
  connectionState: RuntimeServiceConnectionState,
): T {
  // Binder dispatch success is the real source of truth here. Connection-state publication can
  // lag slightly behind the binder callback that produced this payload, especially across the
  // bind-pending -> binder-connected handoff on worker threads.
  if (gateway != null) {
    return gateway
  }
  throw IllegalStateException(
    serviceOwnedGatewayUnavailableMessage(
      surface = surface,
      operation = operation,
      connectionState = connectionState,
    ),
  )
}

internal fun <T> cachedGatewayProvider(
  provider: () -> T,
): () -> T {
  val cachedValue = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    provider()
  }
  return { cachedValue.value }
}

internal const val SERVICE_GATEWAY_BIND_AWAIT_TIMEOUT_MS: Long = 10_000L

internal fun <TGateway, TPayload> observeWithDynamicGateway(
  initialGateway: (() -> TGateway)? = null,
  currentGateway: () -> TGateway,
  observeConnectionState: ((RuntimeServiceConnectionState) -> Unit) -> (() -> Unit),
  observe: (TGateway, (TPayload) -> Unit) -> (() -> Unit),
  listener: (TPayload) -> Unit,
): () -> Unit {
  val lock = Any()
  var disposed = false
  var activeGateway: TGateway? = null
  var activeDisposer: (() -> Unit)? = null
  var initialSubscriptionConsumed = false

  fun resubscribeIfNeeded(force: Boolean = false) {
    synchronized(lock) {
      if (disposed) {
        return
      }
      val nextGateway = if (!initialSubscriptionConsumed) {
        initialSubscriptionConsumed = true
        initialGateway?.invoke() ?: currentGateway()
      } else {
        currentGateway()
      }
      if (!force && activeGateway === nextGateway) {
        return
      }
      activeDisposer?.invoke()
      activeGateway = nextGateway
      activeDisposer = observe(nextGateway, listener)
    }
  }

  resubscribeIfNeeded()
  val disposeConnectionObservation = observeConnectionState {
    // Connection-state churn alone should not reset the observer when the
    // effective gateway instance has not changed.
    resubscribeIfNeeded()
  }
  // Re-check once after observation starts so a binder that connected during registration
  // does not leave the stream stuck on the projection fallback until the next state change.
  resubscribeIfNeeded()
  return {
    val disposer = synchronized(lock) {
      if (disposed) {
        return@synchronized null
      }
      disposed = true
      activeGateway = null
      activeDisposer.also {
        activeDisposer = null
      }
    }
    disposeConnectionObservation()
    disposer?.invoke()
  }
}
