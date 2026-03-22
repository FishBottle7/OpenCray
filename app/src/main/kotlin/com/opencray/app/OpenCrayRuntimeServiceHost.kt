package com.opencray.app

import android.content.Context
import com.opencray.runtime.OpenCraySubAgentEvent
import com.opencray.runtime.context.RuntimeConversationMessage

internal data class RuntimeServiceLifecycleDescriptor(
  val processStartId: String = OpenCrayProcessLifecycle.processStartId,
  val processStartedAtEpochMs: Long = OpenCrayProcessLifecycle.processStartedAtEpochMs,
  val serviceInstanceId: String = lifecycleId(prefix = "runtime-service"),
  val serviceCreatedAtEpochMs: Long = System.currentTimeMillis(),
) {
  fun snapshotMap(): Map<String, Any?> = mapOf(
    "processStartId" to processStartId,
    "processStartedAtEpochMs" to processStartedAtEpochMs,
    "serviceInstanceId" to serviceInstanceId,
    "serviceCreatedAtEpochMs" to serviceCreatedAtEpochMs,
  )
}

internal data class OpenCrayRuntimeReplayAccess(
  val approvalRejectionRecorder: (String, String, String, String?, Boolean) -> Unit,
  val approvalApprovedRecorder: (String, String, String, String?, Boolean) -> Unit,
  val subAgentReplayRecorder: (String, OpenCraySubAgentEvent) -> Unit,
  val runCancellationRecorder: (String, String, String, String?) -> Unit,
  val terminalReplayRepairer: (String, List<AgentRunSnapshot>) -> Unit,
)

internal data class OpenCrayRuntimeOwnerAccess(
  val lifecycleDescriptor: HostRuntimeLifecycleDescriptor,
  val sessionRuntimeManager: AgentSessionRuntimeManager,
  val runEventJournalStoreFactory: RunEventJournalStoreFactory,
  val promptCheckpointStoreFactory: PromptCheckpointStoreFactory,
  val supplementStoreFactory: AgentSessionSupplementStoreFactory,
  val transcriptMessagesProvider: (String) -> List<RuntimeConversationMessage>,
  val approvalRegistry: AgentTaskApprovalRegistry,
  val memoryIngestionCoordinator: ChatMemoryIngestionCoordinator,
  val replayAccess: OpenCrayRuntimeReplayAccess,
)

internal fun InProcessOpenCrayRuntimeOwner.toRuntimeOwnerAccess(): OpenCrayRuntimeOwnerAccess =
  OpenCrayRuntimeOwnerAccess(
    lifecycleDescriptor = lifecycleDescriptor,
    sessionRuntimeManager = sessionRuntimeManager,
    runEventJournalStoreFactory = runEventJournalStoreFactory,
    promptCheckpointStoreFactory = promptCheckpointStoreFactory,
    supplementStoreFactory = supplementStoreFactory,
    transcriptMessagesProvider = transcriptMessagesProvider,
    approvalRegistry = approvalRegistry,
    memoryIngestionCoordinator = memoryIngestionCoordinator,
    replayAccess = replayAccess,
  )

internal data class OpenCrayRuntimeServiceHost(
  val dependencies: OpenCrayRuntimeContextDependencies,
  val runtimeAccess: OpenCrayRuntimeOwnerAccess,
  val serviceLifecycle: RuntimeServiceLifecycleDescriptor,
)

internal object OpenCrayRuntimeServiceHostRegistry {
  @Volatile
  private var instance: OpenCrayRuntimeServiceHost? = null

  fun peek(): OpenCrayRuntimeServiceHost? = instance

  fun getOrCreate(
    context: Context,
    serviceLifecycleFactory: () -> RuntimeServiceLifecycleDescriptor = {
      RuntimeServiceLifecycleDescriptor()
    },
  ): OpenCrayRuntimeServiceHost {
    val appContext = context.applicationContext
    return instance ?: synchronized(this) {
      instance ?: createOpenCrayRuntimeServiceHost(
        appContext = appContext,
        serviceLifecycle = serviceLifecycleFactory(),
      ).also { created ->
        instance = created
      }
    }
  }
}

private fun createOpenCrayRuntimeServiceHost(
  appContext: Context,
  serviceLifecycle: RuntimeServiceLifecycleDescriptor,
): OpenCrayRuntimeServiceHost {
  val dependencies = loadOpenCrayRuntimeContextDependencies(appContext)
  val owner = ensureInProcessRuntimeOwner(dependencies)
  return OpenCrayRuntimeServiceHost(
    dependencies = dependencies,
    runtimeAccess = owner.toRuntimeOwnerAccess(),
    serviceLifecycle = serviceLifecycle,
  )
}
