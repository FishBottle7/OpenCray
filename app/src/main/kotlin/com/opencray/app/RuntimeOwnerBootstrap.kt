package com.opencray.app

internal data class RuntimeOwnerBootstrap(
  val runtimeOwnerLifecycle: HostRuntimeLifecycleDescriptor,
  val ownerObservationAccess: RuntimeOwnerObservationAccess,
  val notificationHostAccess: RuntimeNotificationHostAccess,
  val approvalDecisionHostAccess: RuntimeApprovalDecisionHostAccess,
  val chatMutationAccess: RuntimeChatMutationAccess,
  val chatSubmissionHostAccess: RuntimeChatSubmissionHostAccess,
  val runtimeReplayAccess: OpenCrayRuntimeReplayAccess,
  val onDeviceWarmupPlanner: (String) -> OnDeviceLlmWarmupSpec? = { null },
  val retainedHandle: RetainedRuntimeOwnerHandle? = null,
  private val disposeHandler: () -> Unit = {},
) {
  private val disposeLock = Any()
  private var disposed: Boolean = false

  fun dispose() {
    val handler = synchronized(disposeLock) {
      if (disposed) {
        null
      } else {
        disposed = true
        disposeHandler
      }
    } ?: return
    handler()
  }
}

internal fun RuntimeOwnerBootstrap.toRuntimeServicePort(): RuntimeServicePort = runtimeServicePort(
  lifecycleDescriptor = runtimeOwnerLifecycle,
  ownerObservationAccess = ownerObservationAccess,
  notificationHostAccess = notificationHostAccess,
  approvalDecisionHostAccess = approvalDecisionHostAccess,
  chatMutationAccess = chatMutationAccess,
  chatSubmissionHostAccess = chatSubmissionHostAccess,
  runtimeReplayAccess = runtimeReplayAccess,
  onDeviceWarmupPlanner = onDeviceWarmupPlanner,
)

internal interface RetainedRuntimeOwnerHandle {
  fun createReplacementBootstrap(): RuntimeOwnerBootstrap

  fun disposeRetainedOwner()
}

internal class RuntimeServiceRetainedOwnerState(
  initialBootstrap: RuntimeOwnerBootstrap,
  private val replacementBootstrapProvider: (RuntimeOwnerBootstrap) -> RuntimeOwnerBootstrap,
  private val finalBootstrapDisposer: (RuntimeOwnerBootstrap) -> Unit = RuntimeOwnerBootstrap::dispose,
) {
  private val lock = Any()
  private var currentBootstrap: RuntimeOwnerBootstrap = initialBootstrap
  private var currentServicePort: RuntimeServicePort =
    initialBootstrap.toRuntimeServicePort()
  private var disposed: Boolean = false

  fun currentRuntimeServicePort(): RuntimeServicePort =
    synchronized(lock) {
      currentServicePort
    }

  fun replaceRuntimeOwner(): RuntimeOwnerBootstrap {
    val previousBootstrap = synchronized(lock) {
      check(!disposed) {
        "Retained runtime owner state has already been disposed."
      }
      currentBootstrap
    }
    val nextBootstrap = replacementBootstrapProvider(previousBootstrap)
    val nextServicePort = nextBootstrap.toRuntimeServicePort()
    val bootstrapToDispose = try {
      synchronized(lock) {
        check(!disposed) {
          "Retained runtime owner state has already been disposed."
        }
        check(currentBootstrap === previousBootstrap) {
          "Retained runtime owner was replaced concurrently."
        }
        currentBootstrap.also {
          currentBootstrap = nextBootstrap
          currentServicePort = nextServicePort
        }
      }
    } catch (failure: Throwable) {
      nextBootstrap.dispose()
      throw failure
    }
    bootstrapToDispose.dispose()
    return nextBootstrap
  }

  fun dispose() {
    val bootstrapToDispose = synchronized(lock) {
      if (disposed) {
        null
      } else {
        disposed = true
        currentBootstrap
      }
    } ?: return
    finalBootstrapDisposer(bootstrapToDispose)
  }
}
