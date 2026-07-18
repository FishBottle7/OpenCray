package com.opencray.app

import android.content.Context
import com.opencray.core.contracts.AgentTask
import com.opencray.core.orchestrator.QueueTaskLifecycleState
import com.opencray.core.orchestrator.SessionQueueTaskSnapshot

internal fun runtimeServiceTargetForTask(
  task: AgentTask,
): RuntimeServiceTarget = runtimeServiceTargetForTaskMetadata(task.metadata)

internal fun runtimeServiceTargetForTaskMetadata(
  metadata: Map<String, String>,
): RuntimeServiceTarget = when {
  !metadata[ScheduledTaskMetadataKeys.SCHEDULE_ID].isNullOrBlank() ->
    RuntimeServiceTarget.DETACHED_BACKGROUND

  !metadata[METADATA_DETACHED_CONTROL_KIND].isNullOrBlank() ->
    RuntimeServiceTarget.DETACHED_BACKGROUND

  else -> RuntimeServiceTarget.INTERACTIVE
}

internal fun interface ChatRuntimeWriteTargetResolver {
  fun targetFor(command: OpenCrayChatWriteCommand): RuntimeServiceTarget

  fun targetForSession(sessionId: String): RuntimeServiceTarget = DEFAULT_CLIENT_RUNTIME_SERVICE_TARGET

  fun targetForIdentifier(taskIdOrRunId: String): RuntimeServiceTarget =
    DEFAULT_CLIENT_RUNTIME_SERVICE_TARGET
}

internal fun interface ChatRuntimeWriteTargetResolverFactory {
  fun create(context: Context): ChatRuntimeWriteTargetResolver
}

internal fun openCrayChatRuntimeWriteTargetResolverFactory(
  defaultTarget: RuntimeServiceTarget,
): ChatRuntimeWriteTargetResolverFactory =
  ChatRuntimeWriteTargetResolverFactory { context ->
    ProjectionBackedChatRuntimeWriteTargetResolver(
      chatSessionStore = ChatSessionLocalStore.fromContext(context.applicationContext),
      queueSnapshotStoreFactory = FileBackedAgentQueueSnapshotStoreFactory.fromContext(
        context.applicationContext,
      ),
      promptCheckpointStoreFactory = FileBackedPromptCheckpointStoreFactory.fromContext(
        context.applicationContext,
      ),
      sessionOwnerLeaseStore = FileBackedRuntimeSessionOwnerLeaseStore.fromContext(
        context.applicationContext,
      ),
      defaultTarget = defaultTarget,
    )
  }

internal class ProjectionBackedChatRuntimeWriteTargetResolver(
  private val chatSessionStore: ChatSessionLocalStore,
  private val queueSnapshotStoreFactory: AgentQueueSnapshotStoreFactory,
  private val promptCheckpointStoreFactory: PromptCheckpointStoreFactory,
  private val sessionOwnerLeaseStore: RuntimeSessionOwnerLeaseStore? = null,
  private val defaultTarget: RuntimeServiceTarget = DEFAULT_CLIENT_RUNTIME_SERVICE_TARGET,
) : ChatRuntimeWriteTargetResolver {
  override fun targetFor(command: OpenCrayChatWriteCommand): RuntimeServiceTarget = when (command) {
    OpenCrayChatWriteCommand.RefreshSandboxSessionInfo -> targetForActiveSessionInteraction()
    is OpenCrayChatWriteCommand.SubmitChatMessage -> targetForSubmitChatMessage(command)
    is OpenCrayChatWriteCommand.ApproveChatApproval ->
      targetForIdentifier(command.taskIdOrRunId)
    is OpenCrayChatWriteCommand.ApproveChatApprovalForSession ->
      targetForIdentifier(command.taskIdOrRunId)
    is OpenCrayChatWriteCommand.RejectChatApproval ->
      targetForIdentifier(command.taskIdOrRunId)
    is OpenCrayChatWriteCommand.InterruptChatRun ->
      targetForIdentifier(command.taskIdOrRunId)
    is OpenCrayChatWriteCommand.RetryChatRun ->
      targetForIdentifier(command.taskIdOrRunId)
    else -> defaultTarget
  }

  override fun targetForSession(sessionId: String): RuntimeServiceTarget =
    sessionOwnerTarget(sessionId) ?: routeTaskForSession(sessionId)?.let { taskSnapshot ->
      runtimeServiceTargetForTask(taskSnapshot.task)
    } ?: defaultTarget

  override fun targetForIdentifier(
    taskIdOrRunId: String,
  ): RuntimeServiceTarget = findTaskSnapshotForIdentifier(
    sessionIds = knownChatSessionIds(chatSessionStore),
    taskIdOrRunId = taskIdOrRunId,
  )?.let { routedTask ->
    sessionOwnerTarget(routedTask.sessionId)
      ?: runtimeServiceTargetForTask(routedTask.taskSnapshot.task)
  } ?: defaultTarget

  private fun targetForSubmitChatMessage(
    command: OpenCrayChatWriteCommand.SubmitChatMessage,
  ): RuntimeServiceTarget {
    if (command.text.trim().isEmpty() && command.attachments.isEmpty()) {
      return defaultTarget
    }
    return targetForActiveSessionInteraction()
  }

  private fun targetForActiveSessionInteraction(): RuntimeServiceTarget {
    val activeSessionId = chatSessionStore.loadState().activeSession.sessionId
    return targetForSession(activeSessionId)
  }

  private fun routeTaskForSession(
    sessionId: String,
  ): SessionQueueTaskSnapshot? {
    val queueTasks = queueTaskSnapshotsForSession(sessionId)
    val activeTask = queueTasks
      .filter(::isRoutingCandidate)
      .maxByOrNull(::routingTimestamp)
    if (activeTask != null) {
      return activeTask
    }
    return approvalRequiredTaskProjections(
      sessionId = sessionId,
      queueTaskSnapshots = queueTasks,
      runSnapshots = emptyList(),
      checkpoints = promptCheckpointStoreFactory.forChatSession(sessionId).list(),
    )
      .maxByOrNull { projection -> routingTimestamp(projection.taskSnapshot) }
      ?.taskSnapshot
  }

  private fun findTaskSnapshotForIdentifier(
    sessionIds: List<String>,
    taskIdOrRunId: String,
  ): RoutedSessionTask? = sessionIds.firstNotNullOfOrNull { sessionId ->
    val queueTasks = queueTaskSnapshotsForSession(sessionId)
    val taskSnapshot = queueTasks.firstOrNull { taskSnapshot ->
      matchesTaskIdentifier(taskSnapshot, taskIdOrRunId)
    } ?: approvalRequiredTaskProjections(
      sessionId = sessionId,
      queueTaskSnapshots = queueTasks,
      runSnapshots = emptyList(),
      checkpoints = promptCheckpointStoreFactory.forChatSession(sessionId).list(),
    ).firstOrNull { projection ->
      projection.taskId == taskIdOrRunId || projection.runId == taskIdOrRunId
    }?.taskSnapshot
    taskSnapshot?.let { snapshot ->
      RoutedSessionTask(sessionId = sessionId, taskSnapshot = snapshot)
    }
  }

  private fun sessionOwnerTarget(sessionId: String): RuntimeServiceTarget? = runCatching {
    sessionOwnerLeaseStore?.loadLiveOwner(sessionId)?.target
  }.getOrNull()

  private fun queueTaskSnapshotsForSession(
    sessionId: String,
  ): List<SessionQueueTaskSnapshot> =
    queueSnapshotStoreFactory.forChatSession(sessionId).load()?.tasks.orEmpty()

  private fun matchesTaskIdentifier(
    taskSnapshot: SessionQueueTaskSnapshot,
    taskIdOrRunId: String,
  ): Boolean = taskSnapshot.task.id == taskIdOrRunId ||
    runIdForTask(taskSnapshot.task) == taskIdOrRunId

  private fun isRoutingCandidate(
    taskSnapshot: SessionQueueTaskSnapshot,
  ): Boolean = taskSnapshot.lifecycleState in ROUTEABLE_TASK_LIFECYCLES

  private fun routingTimestamp(
    taskSnapshot: SessionQueueTaskSnapshot,
  ): Long = maxOf(
    taskSnapshot.task.updatedAtEpochMs,
    taskSnapshot.task.createdAtEpochMs,
  )

  private fun runIdForTask(task: AgentTask): String =
    task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID]
      ?.takeIf(String::isNotBlank)
      ?: task.id

  private companion object {
    private val ROUTEABLE_TASK_LIFECYCLES = setOf(
      QueueTaskLifecycleState.QUEUED,
      QueueTaskLifecycleState.RUNNING,
      QueueTaskLifecycleState.RETRY_PENDING,
      QueueTaskLifecycleState.SUSPENDED,
      QueueTaskLifecycleState.CANCEL_REQUESTED,
    )
  }
}

private data class RoutedSessionTask(
  val sessionId: String,
  val taskSnapshot: SessionQueueTaskSnapshot,
)

internal fun runtimeTargetForFlutterBridgeEntry(
  context: Context,
  notificationTaskId: String? = null,
  notificationRunId: String? = null,
  chatSessionId: String? = null,
  targetResolverFactory: ChatRuntimeWriteTargetResolverFactory? = null,
  defaultTarget: RuntimeServiceTarget? = null,
  environment: OpenCrayRuntimeServiceEnvironment,
): RuntimeServiceTarget {
  val resolvedTargetResolverFactory =
    targetResolverFactory ?: environment.chatRuntimeWriteTargetResolverFactory
  val resolvedDefaultTarget = defaultTarget ?: environment.defaultClientRuntimeServiceTarget
  val resolver = resolvedTargetResolverFactory.create(context.applicationContext)
  notificationRunId?.trim()?.takeIf(String::isNotBlank)?.let { runId ->
    return resolver.targetForIdentifier(runId)
  }
  notificationTaskId?.trim()?.takeIf(String::isNotBlank)?.let { taskId ->
    return resolver.targetForIdentifier(taskId)
  }
  chatSessionId?.trim()?.takeIf(String::isNotBlank)?.let { sessionId ->
    return resolver.targetForSession(sessionId)
  }
  return resolvedDefaultTarget
}
