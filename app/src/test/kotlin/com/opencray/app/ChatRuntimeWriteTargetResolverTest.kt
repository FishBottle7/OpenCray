package com.opencray.app

import android.content.Context
import android.content.ContextWrapper
import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskState
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.QueueTaskLifecycleState
import com.opencray.core.orchestrator.SessionLifecycleState
import com.opencray.core.orchestrator.SessionQueueSnapshot
import com.opencray.core.orchestrator.SessionQueueTaskSnapshot
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatRuntimeWriteTargetResolverTest {
  @get:Rule
  val temporaryFolder = TemporaryFolder()

  @Test
  fun projectionBackedResolverRoutesSubmitToDetachedBackgroundWhenActiveSessionHasScheduledWork() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-submit"))
    val runtimeRoot = temporaryFolder.newFolder("runtime-root-submit")
    val queueFactory = FileBackedAgentQueueSnapshotStoreFactory(runtimeRoot)
    val checkpointFactory = FileBackedPromptCheckpointStoreFactory(runtimeRoot)
    val sessionId = chatStore.loadState().activeSession.sessionId
    queueFactory.forChatSession(sessionId).save(
      sessionQueueSnapshot(
        sessionId = sessionId,
        task = taskForRouting(
          taskId = "task-scheduled",
          runId = "run-scheduled",
          metadata = mapOf(
            ScheduledTaskMetadataKeys.SCHEDULE_ID to "schedule-1",
          ),
          state = AgentTaskState.RUNNING,
          updatedAtEpochMs = 200L,
        ),
        lifecycleState = QueueTaskLifecycleState.RUNNING,
      ),
    )
    val resolver = ProjectionBackedChatRuntimeWriteTargetResolver(
      chatSessionStore = chatStore,
      queueSnapshotStoreFactory = queueFactory,
      promptCheckpointStoreFactory = checkpointFactory,
    )

    val target = resolver.targetFor(
      OpenCrayChatWriteCommand.SubmitChatMessage(
        text = "continue",
        attachments = emptyList(),
      ),
    )

    assertEquals(RuntimeServiceTarget.DETACHED_BACKGROUND, target)
  }

  @Test
  fun projectionBackedResolverRoutesApprovalByRunIdToDetachedBackground() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-approval"))
    val runtimeRoot = temporaryFolder.newFolder("runtime-root-approval")
    val queueFactory = FileBackedAgentQueueSnapshotStoreFactory(runtimeRoot)
    val checkpointFactory = FileBackedPromptCheckpointStoreFactory(runtimeRoot)
    val sessionId = chatStore.loadState().activeSession.sessionId
    queueFactory.forChatSession(sessionId).save(
      sessionQueueSnapshot(
        sessionId = sessionId,
        task = taskForRouting(
          taskId = "task-approval",
          runId = "run-approval",
          metadata = mapOf(
            METADATA_DETACHED_CONTROL_KIND to DETACHED_CONTROL_KIND_SUBAGENT_RECOVERY_WAIT,
          ),
          state = AgentTaskState.SUSPENDED,
          updatedAtEpochMs = 300L,
        ),
        lifecycleState = QueueTaskLifecycleState.SUSPENDED,
      ),
    )
    val resolver = ProjectionBackedChatRuntimeWriteTargetResolver(
      chatSessionStore = chatStore,
      queueSnapshotStoreFactory = queueFactory,
      promptCheckpointStoreFactory = checkpointFactory,
    )

    val target = resolver.targetFor(
      OpenCrayChatWriteCommand.ApproveChatApproval("run-approval"),
    )

    assertEquals(RuntimeServiceTarget.DETACHED_BACKGROUND, target)
  }

  @Test
  fun projectionBackedResolverRoutesRunControlAndRefreshCommandsToDetachedBackground() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-run-control"))
    val runtimeRoot = temporaryFolder.newFolder("runtime-root-run-control")
    val queueFactory = FileBackedAgentQueueSnapshotStoreFactory(runtimeRoot)
    val checkpointFactory = FileBackedPromptCheckpointStoreFactory(runtimeRoot)
    val sessionId = chatStore.loadState().activeSession.sessionId
    queueFactory.forChatSession(sessionId).save(
      sessionQueueSnapshot(
        sessionId = sessionId,
        task = taskForRouting(
          taskId = "task-run-control",
          runId = "run-run-control",
          metadata = mapOf(
            METADATA_DETACHED_CONTROL_KIND to DETACHED_CONTROL_KIND_SUBAGENT_RECOVERY_WAIT,
          ),
          state = AgentTaskState.RUNNING,
          updatedAtEpochMs = 500L,
        ),
        lifecycleState = QueueTaskLifecycleState.RUNNING,
      ),
    )
    val resolver = ProjectionBackedChatRuntimeWriteTargetResolver(
      chatSessionStore = chatStore,
      queueSnapshotStoreFactory = queueFactory,
      promptCheckpointStoreFactory = checkpointFactory,
    )

    assertEquals(
      RuntimeServiceTarget.DETACHED_BACKGROUND,
      resolver.targetFor(OpenCrayChatWriteCommand.RefreshSandboxSessionInfo),
    )
    assertEquals(
      RuntimeServiceTarget.DETACHED_BACKGROUND,
      resolver.targetFor(
        OpenCrayChatWriteCommand.ApproveChatApprovalForSession("run-run-control"),
      ),
    )
    assertEquals(
      RuntimeServiceTarget.DETACHED_BACKGROUND,
      resolver.targetFor(OpenCrayChatWriteCommand.RejectChatApproval("run-run-control")),
    )
    assertEquals(
      RuntimeServiceTarget.DETACHED_BACKGROUND,
      resolver.targetFor(OpenCrayChatWriteCommand.InterruptChatRun("run-run-control")),
    )
    assertEquals(
      RuntimeServiceTarget.DETACHED_BACKGROUND,
      resolver.targetFor(OpenCrayChatWriteCommand.RetryChatRun("run-run-control")),
    )
  }

  @Test
  fun projectionBackedResolverDefaultsToInteractiveWhenNoDetachedWorkMatches() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-interactive"))
    val runtimeRoot = temporaryFolder.newFolder("runtime-root-interactive")
    val queueFactory = FileBackedAgentQueueSnapshotStoreFactory(runtimeRoot)
    val checkpointFactory = FileBackedPromptCheckpointStoreFactory(runtimeRoot)
    val resolver = ProjectionBackedChatRuntimeWriteTargetResolver(
      chatSessionStore = chatStore,
      queueSnapshotStoreFactory = queueFactory,
      promptCheckpointStoreFactory = checkpointFactory,
    )

    val target = resolver.targetFor(
      OpenCrayChatWriteCommand.SubmitChatMessage(
        text = "hello",
        attachments = emptyList(),
      ),
    )

    assertEquals(RuntimeServiceTarget.INTERACTIVE, target)
  }

  @Test
  fun projectionBackedResolverRoutesToLiveSessionOwnerWithoutRouteableTask() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-live-owner"))
    val runtimeRoot = temporaryFolder.newFolder("runtime-root-live-owner")
    val queueFactory = FileBackedAgentQueueSnapshotStoreFactory(runtimeRoot)
    val checkpointFactory = FileBackedPromptCheckpointStoreFactory(runtimeRoot)
    val sessionOwnerStore = FileBackedRuntimeSessionOwnerLeaseStore.fromRootDirectory(runtimeRoot)
    val sessionId = chatStore.loadState().activeSession.sessionId
    sessionOwnerStore.acquire(
      RuntimeSessionOwnerLease(
        sessionId = sessionId,
        target = RuntimeServiceTarget.DETACHED_BACKGROUND,
        processStartId = "process-detached",
        runtimeOwnerId = "owner-detached",
        runtimeControllerId = "controller-detached",
        durableRuntimeControllerId = "durable-detached",
        acquiredAtEpochMs = 1_000L,
        heartbeatAtEpochMs = 1_000L,
        expiresAtEpochMs = Long.MAX_VALUE,
      ),
    )
    val resolver = ProjectionBackedChatRuntimeWriteTargetResolver(
      chatSessionStore = chatStore,
      queueSnapshotStoreFactory = queueFactory,
      promptCheckpointStoreFactory = checkpointFactory,
      sessionOwnerLeaseStore = sessionOwnerStore,
    )

    assertEquals(
      RuntimeServiceTarget.DETACHED_BACKGROUND,
      resolver.targetForSession(sessionId),
    )
  }

  @Test
  fun projectionBackedResolverDefaultsToInteractiveForSessionMutations() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-session-mutations"))
    val runtimeRoot = temporaryFolder.newFolder("runtime-root-session-mutations")
    val queueFactory = FileBackedAgentQueueSnapshotStoreFactory(runtimeRoot)
    val checkpointFactory = FileBackedPromptCheckpointStoreFactory(runtimeRoot)
    val resolver = ProjectionBackedChatRuntimeWriteTargetResolver(
      chatSessionStore = chatStore,
      queueSnapshotStoreFactory = queueFactory,
      promptCheckpointStoreFactory = checkpointFactory,
    )

    assertEquals(
      RuntimeServiceTarget.INTERACTIVE,
      resolver.targetFor(OpenCrayChatWriteCommand.CreateChatSession),
    )
    assertEquals(
      RuntimeServiceTarget.INTERACTIVE,
      resolver.targetFor(OpenCrayChatWriteCommand.SelectChatSession("session-1")),
    )
    assertEquals(
      RuntimeServiceTarget.INTERACTIVE,
      resolver.targetFor(OpenCrayChatWriteCommand.DeleteChatSession("session-1")),
    )
  }

  @Test
  fun projectionBackedResolverRoutesSessionLookupToDetachedBackground() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-session-lookup"))
    val runtimeRoot = temporaryFolder.newFolder("runtime-root-session-lookup")
    val queueFactory = FileBackedAgentQueueSnapshotStoreFactory(runtimeRoot)
    val checkpointFactory = FileBackedPromptCheckpointStoreFactory(runtimeRoot)
    val sessionId = chatStore.loadState().activeSession.sessionId
    queueFactory.forChatSession(sessionId).save(
      sessionQueueSnapshot(
        sessionId = sessionId,
        task = taskForRouting(
          taskId = "task-detached-session",
          runId = "run-detached-session",
          metadata = mapOf(
            ScheduledTaskMetadataKeys.SCHEDULE_ID to "schedule-session-1",
          ),
          state = AgentTaskState.RUNNING,
          updatedAtEpochMs = 600L,
        ),
        lifecycleState = QueueTaskLifecycleState.RUNNING,
      ),
    )
    val resolver = ProjectionBackedChatRuntimeWriteTargetResolver(
      chatSessionStore = chatStore,
      queueSnapshotStoreFactory = queueFactory,
      promptCheckpointStoreFactory = checkpointFactory,
    )

    assertEquals(
      RuntimeServiceTarget.DETACHED_BACKGROUND,
      resolver.targetForSession(sessionId),
    )
  }

  @Test
  fun runtimeTargetForFlutterBridgeEntryPrefersRunIdThenTaskIdThenSessionId() {
    val context = MinimalContext()
    val resolver = object : ChatRuntimeWriteTargetResolver {
      override fun targetFor(command: OpenCrayChatWriteCommand): RuntimeServiceTarget =
        RuntimeServiceTarget.INTERACTIVE

      override fun targetForSession(sessionId: String): RuntimeServiceTarget =
        if (sessionId == "session-detached") {
          RuntimeServiceTarget.DETACHED_BACKGROUND
        } else {
          RuntimeServiceTarget.INTERACTIVE
        }

      override fun targetForIdentifier(taskIdOrRunId: String): RuntimeServiceTarget =
        if (taskIdOrRunId.startsWith("detached")) {
          RuntimeServiceTarget.DETACHED_BACKGROUND
        } else {
          RuntimeServiceTarget.INTERACTIVE
        }
    }
    val factory = ChatRuntimeWriteTargetResolverFactory { resolver }

    assertEquals(
      RuntimeServiceTarget.DETACHED_BACKGROUND,
      runtimeTargetForFlutterBridgeEntry(
        context = context,
        notificationTaskId = "interactive-task",
        notificationRunId = "detached-run",
        chatSessionId = "session-interactive",
        targetResolverFactory = factory,
        environment = OpenCrayRuntimeServiceEnvironment(
          projectionHostLifecycleDescriptor = HostRuntimeLifecycleDescriptor(),
        ),
      ),
    )
    assertEquals(
      RuntimeServiceTarget.DETACHED_BACKGROUND,
      runtimeTargetForFlutterBridgeEntry(
        context = context,
        notificationTaskId = "detached-task",
        chatSessionId = "session-interactive",
        targetResolverFactory = factory,
        environment = OpenCrayRuntimeServiceEnvironment(
          projectionHostLifecycleDescriptor = HostRuntimeLifecycleDescriptor(),
        ),
      ),
    )
    assertEquals(
      RuntimeServiceTarget.DETACHED_BACKGROUND,
      runtimeTargetForFlutterBridgeEntry(
        context = context,
        chatSessionId = "session-detached",
        targetResolverFactory = factory,
        environment = OpenCrayRuntimeServiceEnvironment(
          projectionHostLifecycleDescriptor = HostRuntimeLifecycleDescriptor(),
        ),
      ),
    )
    assertEquals(
      RuntimeServiceTarget.INTERACTIVE,
      runtimeTargetForFlutterBridgeEntry(
        context = context,
        targetResolverFactory = factory,
        environment = OpenCrayRuntimeServiceEnvironment(
          projectionHostLifecycleDescriptor = HostRuntimeLifecycleDescriptor(),
        ),
      ),
    )
  }

  @Test
  fun runtimeTargetForFlutterBridgeEntryUsesEnvironmentDefaultsWhenResolverAndTargetAreOmitted() {
    val expectedTarget = RuntimeServiceTarget.DETACHED_BACKGROUND
    val context = RuntimeEnvironmentContext(
      OpenCrayRuntimeServiceEnvironment(
        projectionHostLifecycleDescriptor = HostRuntimeLifecycleDescriptor(),
        defaultClientRuntimeServiceTarget = expectedTarget,
        chatRuntimeWriteTargetResolverFactoryProvider = {
          ChatRuntimeWriteTargetResolverFactory {
            object : ChatRuntimeWriteTargetResolver {
              override fun targetFor(command: OpenCrayChatWriteCommand): RuntimeServiceTarget =
                RuntimeServiceTarget.INTERACTIVE
            }
          }
        },
      ),
    )

    val target = runtimeTargetForFlutterBridgeEntry(
      context = context,
      environment = context.openCrayRuntimeServiceEnvironment,
    )

    assertEquals(expectedTarget, target)
  }

  private fun sessionQueueSnapshot(
    sessionId: String,
    task: AgentTask,
    lifecycleState: QueueTaskLifecycleState,
  ): SessionQueueSnapshot = SessionQueueSnapshot(
    sessionId = sessionId,
    agentId = "agent",
    lifecycleState = SessionLifecycleState.RUNNING,
    tasks = listOf(
      SessionQueueTaskSnapshot(
        enqueueOrder = 1L,
        task = task,
        lifecycleState = lifecycleState,
      ),
    ),
    updatedAtEpochMs = task.updatedAtEpochMs,
  )

  private fun taskForRouting(
    taskId: String,
    runId: String,
    metadata: Map<String, String>,
    state: AgentTaskState,
    updatedAtEpochMs: Long,
  ): AgentTask = AgentTask(
    id = taskId,
    type = AgentTaskType.PROMPT,
    input = "input",
    state = state,
    policyDecision = PolicyDecision(
      outcome = PolicyDecisionOutcome.ALLOW,
      reasonCode = "test",
    ),
    createdAtEpochMs = updatedAtEpochMs - 100L,
    updatedAtEpochMs = updatedAtEpochMs,
    metadata = metadata + mapOf(
      AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID to runId,
    ),
  )

  private class MinimalContext : ContextWrapper(null) {
    override fun getApplicationContext(): Context = this
  }

  private class RuntimeEnvironmentContext(
    override val openCrayRuntimeServiceEnvironment: OpenCrayRuntimeServiceEnvironment,
  ) : ContextWrapper(null), OpenCrayRuntimeServiceEnvironmentOwner {
    override fun getApplicationContext(): Context = this
  }
}
