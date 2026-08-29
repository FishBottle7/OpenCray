package com.opencray.app

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import com.opencray.app.facade.mcp.EmptyMcpSettingsFacade
import com.opencray.app.facade.safety.EmptySafetySettingsFacade
import com.opencray.app.facade.skills.EmptySkillsFacade
import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskState
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.orchestrator.SessionLifecycleState
import com.opencray.core.orchestrator.QueueTaskLifecycleState
import com.opencray.core.orchestrator.SessionQueueSnapshot
import com.opencray.core.orchestrator.SessionQueueTaskSnapshot
import com.opencray.persistence.model.MemoryRecord
import com.opencray.persistence.model.ChatTranscriptRole
import com.opencray.persistence.store.MemoryStore
import com.opencray.runtime.OpenCrayCancellationEvent
import com.opencray.runtime.OpenCrayPromptResumeState
import com.opencray.runtime.OpenCraySubAgentEvent
import com.opencray.runtime.subagent.SubAgentApprovalResume
import com.opencray.runtime.subagent.SubAgentContinuationKind
import com.opencray.runtime.subagent.SubAgentExecutionState
import com.opencray.runtime.subagent.SubAgentExecutionSnapshot
import com.opencray.runtime.subagent.SubAgentHandleState
import com.opencray.runtime.subagent.SubAgentPendingApprovalDecision
import java.util.ArrayDeque
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder

abstract class RuntimeServiceHostTestBase {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  internal class NoOpAgentSessionRuntimeManager : AgentSessionRuntimeManager {
    override fun forSession(sessionId: String): AgentSessionHandle = error("unused in test")

    override fun observe(listener: AgentSessionRuntimeListener): () -> Unit = { }

    override fun release(sessionId: String) = Unit

    override fun releaseIdleSessions() = Unit
  }

  internal class RecordingAgentSessionRuntimeManager : AgentSessionRuntimeManager {
    private val handlesBySession = linkedMapOf<String, RecordingAgentSessionHandle>()
    val resumedSessionIds = mutableListOf<String>()

    fun putHandle(handle: RecordingAgentSessionHandle) {
      handlesBySession[handle.sessionId] = handle
    }

    override fun forSession(sessionId: String): AgentSessionHandle =
      handlesBySession.getOrPut(sessionId) {
        RecordingAgentSessionHandle(sessionId, resumedSessionIds)
      }

    override fun observe(listener: AgentSessionRuntimeListener): () -> Unit = { }

    override fun release(sessionId: String) = Unit

    override fun releaseIdleSessions() = Unit
  }

  internal class RecordingAgentSessionHandle(
    override val sessionId: String,
    private val resumedSessionIds: MutableList<String>,
    private val runs: List<AgentRunSnapshot> = emptyList(),
    private val queueSnapshot: SessionQueueSnapshot? = null,
    private val hasPendingWork: Boolean = false,
    private val hasLiveManagedProcesses: Boolean = false,
    initialSubAgentHandles: List<SubAgentHandleState> = emptyList(),
    private val cancelRequestResult: Boolean = false,
    private val resumeRequestResult: Boolean = false,
  ) : AgentSessionHandle {
    val submittedTasks = mutableListOf<AgentTask>()
    val detachedControlTasks = mutableListOf<AgentTask>()
    val cancelledTaskIds = mutableListOf<String>()
    val resumedTaskIds = mutableListOf<String>()
    private var subAgentHandles: List<SubAgentHandleState> = initialSubAgentHandles
    var ensureProcessingCallCount: Int = 0
      private set
    var resumeSubAgentActorsCallCount: Int = 0
      private set

    override fun submitPrompt(
      userText: String,
      pendingMessageId: String,
      visibleThroughMessageId: String,
      policyDecision: PolicyDecision,
      metadata: Map<String, String>,
    ): AgentRunSubmission = error("unused in test")

    override fun submitTask(task: AgentTask): AgentRunSubmission {
      submittedTasks += task
      val runId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID]
        ?: "run-${task.id}"
      return AgentRunSubmission(
        sessionId = sessionId,
        runId = runId,
        taskId = task.id,
        acceptedAtEpochMs = task.createdAtEpochMs,
      )
    }

    override fun ensureProcessing() {
      ensureProcessingCallCount += 1
    }

    override fun requestCancel(taskId: String): Boolean {
      cancelledTaskIds += taskId
      return cancelRequestResult
    }

    override fun requestRetry(taskId: String): Boolean = false

    override fun requestResumeTask(taskId: String): Boolean {
      resumedTaskIds += taskId
      return resumeRequestResult
    }

    override fun listRuns(): List<AgentRunSnapshot> = runs

    override fun findRun(runId: String): AgentRunSnapshot? = runs.firstOrNull { run -> run.runId == runId }

    override fun waitForRun(runId: String, timeoutMs: Long): AgentRunSnapshot? = findRun(runId)

    override fun requestCancelForPendingMessageIds(pendingMessageIds: Set<String>): Int = 0

    override fun resume(): SessionLifecycleState {
      resumedSessionIds += sessionId
      scheduleRecoverableSubAgents()
      return SessionLifecycleState.IDLE
    }

    private fun triggerSubAgentActors(): Int {
      resumeSubAgentActorsCallCount += 1
      return subAgentHandles.count { handle ->
        handle.pendingApprovalDecision != null ||
          (
            handle.snapshot.state == SubAgentExecutionState.BACKGROUND_QUEUED &&
              handle.pendingApprovalResume == null
            )
      }
    }

    override fun snapshot(): SessionQueueSnapshot = queueSnapshot ?: SessionQueueSnapshot(
      sessionId = sessionId,
      agentId = "test-agent",
      updatedAtEpochMs = 1_000L,
      tasks = submittedTasks.mapIndexed { index, task ->
        SessionQueueTaskSnapshot(
          enqueueOrder = index.toLong(),
          task = task,
          lifecycleState = QueueTaskLifecycleState.QUEUED,
        )
      },
    )

    override fun hasPendingWork(): Boolean = hasPendingWork

    override fun hasLiveManagedProcesses(): Boolean = hasLiveManagedProcesses

    override fun listSubAgentHandles(): List<SubAgentHandleState> = subAgentHandles

    override fun setSubAgentPendingApprovalDecision(
      agentId: String,
      parentRunId: String,
      pendingApprovalDecision: SubAgentPendingApprovalDecision?,
    ): Boolean {
      var updated = false
      subAgentHandles = subAgentHandles.map { handle ->
        if (handle.agentId != agentId || handle.parentRunId != parentRunId) {
          handle
        } else {
          updated = true
          if (pendingApprovalDecision == null) {
            handle.copy(pendingApprovalDecision = null)
          } else {
            handle.copy(
              pendingApprovalDecision = pendingApprovalDecision,
              updatedAtEpochMs = maxOf(
                handle.updatedAtEpochMs,
                pendingApprovalDecision.recordedAtEpochMs,
              ),
            )
          }
        }
      }
      if (updated && pendingApprovalDecision != null) {
        triggerSubAgentActors()
      }
      return updated
    }

    override fun listVisibleSubAgentTasks(): List<AgentTask> = detachedControlTasks.toList()

    override fun submitSubAgentRecoveryTask(
      agentId: String,
      parentRunId: String,
      taskId: String,
      createdAtEpochMs: Long,
      submissionSource: String,
    ): AgentRunSubmission {
      val task = syntheticSubAgentRecoveryWaitTask(
        sessionId = sessionId,
        agentId = agentId,
        parentRunId = parentRunId,
        taskId = taskId,
        createdAtEpochMs = createdAtEpochMs,
        metadata = HostRuntimeLifecycleDescriptor().taskMetadata(
          submissionSource = submissionSource,
        ),
      )
      detachedControlTasks += task
      val runId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID]
        ?: "run-${task.id}"
      return AgentRunSubmission(
        sessionId = sessionId,
        runId = runId,
        taskId = task.id,
        acceptedAtEpochMs = task.createdAtEpochMs,
      )
    }

    fun scheduleRecoverableSubAgents(): Int {
      val activeParentRunIds = runs
        .filter(AgentRunSnapshot::isActive)
        .map(AgentRunSnapshot::runId)
        .toSet()
      val pendingRecoveryKeys = detachedControlTasks.mapNotNull { task ->
        val agentId = task.metadata[METADATA_SUBAGENT_RECOVERY_AGENT_ID]
          ?.trim()
          ?.takeIf(String::isNotBlank)
        val parentRunId = task.metadata[METADATA_SUBAGENT_RECOVERY_PARENT_RUN_ID]
          ?.trim()
          ?.takeIf(String::isNotBlank)
        if (agentId == null || parentRunId == null) {
          null
        } else {
          parentRunId to agentId
        }
      }.toSet()
      val resumableHandles = subAgentHandles.filter { handle ->
        handle.snapshot.state == SubAgentExecutionState.BACKGROUND_QUEUED &&
          handle.pendingApprovalResume == null &&
          handle.parentRunId !in activeParentRunIds &&
          (handle.parentRunId to handle.agentId) !in pendingRecoveryKeys
      }
      resumableHandles.forEach { handle ->
        val taskId = syntheticSubAgentRecoveryTaskId(
          sessionId = sessionId,
          agentId = handle.agentId,
          parentRunId = handle.parentRunId,
        )
        submitSubAgentRecoveryTask(
          agentId = handle.agentId,
          parentRunId = handle.parentRunId,
          taskId = taskId,
          createdAtEpochMs = 1_500L,
          submissionSource = RunSubmissionSources.RUNTIME_SERVICE_SUBAGENT_RECOVERY,
        )
      }
      return resumableHandles.size
    }
  }

  internal fun backgroundSubAgentHandle(agentId: String): SubAgentHandleState = SubAgentHandleState(
    agentId = agentId,
    childRunId = "child-run-$agentId",
    childTaskId = "child-task-$agentId",
    description = "Inspect README",
    prompt = "Read README.md and summarize it.",
    subagentType = "researcher",
    contextMode = "minimal",
    parentRunId = "parent-run-$agentId",
    parentTaskId = "parent-task-$agentId",
    parentTurn = 1,
    depth = 1,
    snapshot = SubAgentExecutionSnapshot.backgroundRunning(),
    createdAtEpochMs = 1_000L,
    updatedAtEpochMs = 1_100L,
  )

  internal fun queuedSubAgentHandle(agentId: String): SubAgentHandleState = SubAgentHandleState(
    agentId = agentId,
    childRunId = "child-run-$agentId",
    childTaskId = "child-task-$agentId",
    description = "Inspect README",
    prompt = "Read README.md and summarize it.",
    subagentType = "researcher",
    contextMode = "minimal",
    parentRunId = "parent-run-$agentId",
    parentTaskId = "parent-task-$agentId",
    parentTurn = 1,
    depth = 1,
    snapshot = SubAgentExecutionSnapshot.backgroundQueued(),
    createdAtEpochMs = 1_000L,
    updatedAtEpochMs = 1_100L,
  )

  internal fun waitingApprovalSubAgentHandle(agentId: String): SubAgentHandleState = queuedSubAgentHandle(
    agentId = agentId,
  ).copy(
    snapshot = SubAgentExecutionSnapshot(
      state = SubAgentExecutionState.WAITING_APPROVAL,
      continuationKind = SubAgentContinuationKind.PROMPT_RESUME,
      resumable = true,
      requiresUserAction = true,
      isHighRisk = false,
      headline = "Delegated child run requires approval.",
    ),
    pendingApprovalResume = SubAgentApprovalResume(
      approvedToolName = "Edit",
      promptResumeState = OpenCrayPromptResumeState(turnIndex = 0, toolCallCount = 1),
      agentId = agentId,
      childRunId = "child-run-$agentId",
      childTaskId = "child-task-$agentId",
    ),
  )

  internal class InMemoryMemoryStore : MemoryStore {
    private val records = linkedMapOf<String, MemoryRecord>()

    override fun list(): List<MemoryRecord> = records.values.toList()

    override fun upsert(record: MemoryRecord) {
      records[record.id] = record
    }

    override fun delete(id: String): Boolean = records.remove(id) != null

    override fun clear(): Boolean {
      val hadEntries = records.isNotEmpty()
      records.clear()
      return hadEntries
    }
  }

  internal class RecordingBindingAdapter : OpenCrayRuntimeServiceBindingAdapter {
    var bindCount: Int = 0
      private set
    var unbindCount: Int = 0
      private set
    private var connection: ServiceConnection? = null

    override fun bind(
      context: android.content.Context,
      intent: Intent,
      connection: ServiceConnection,
      flags: Int,
    ): Boolean {
      bindCount += 1
      this.connection = connection
      return true
    }

    override fun unbind(
      context: android.content.Context,
      connection: ServiceConnection,
    ) {
      if (this.connection === connection) {
        this.connection = null
      }
      unbindCount += 1
    }

    fun connect(binder: Binder) {
      checkNotNull(connection).onServiceConnected(
        ComponentName("org.opencray.app", "OpenCrayAgentRuntimeService"),
        binder,
      )
    }

    fun nullBind() {
      checkNotNull(connection).onNullBinding(
        ComponentName("org.opencray.app", "OpenCrayAgentRuntimeService"),
      )
    }

    fun disconnect() {
      checkNotNull(connection).onServiceDisconnected(
        ComponentName("org.opencray.app", "OpenCrayAgentRuntimeService"),
      )
    }
  }

  internal class RecordingRuntimeServiceControllerWireAccess(
    private val protocolVersion: Int,
    private val runtimeTarget: String?,
    private val projectionSnapshotJson: String?,
    private val supportedCapabilities: Long =
      RuntimeServiceControllerCapabilities.PROJECTION_READ,
    private val writeDispatcher: (String) -> String? = { null },
  ) : RuntimeServiceControllerWireAccess {
    var snapshotLoadCount: Int = 0
      private set
    var capabilityLoadCount: Int = 0
      private set
    val writeCommandJson = mutableListOf<String>()

    override fun protocolVersion(): Int = protocolVersion

    override fun runtimeTarget(): String? = runtimeTarget

    override fun capabilities(): Long {
      capabilityLoadCount += 1
      return supportedCapabilities
    }

    override fun loadProjectionSnapshotJson(): String? {
      snapshotLoadCount += 1
      return projectionSnapshotJson
    }

    override fun dispatchWriteCommandJson(commandJson: String): String? {
      writeCommandJson += commandJson
      return writeDispatcher(commandJson)
    }
  }

  internal class RecordingRuntimeServiceDelayScheduler : RuntimeServiceDelayScheduler {
    private val tasks = ArrayDeque<RecordingDelayedTask>()
    val scheduledDelayMs = mutableListOf<Long>()

    override fun schedule(
      delayMs: Long,
      action: () -> Unit,
    ): RuntimeServiceDelayedTask {
      scheduledDelayMs += delayMs
      val task = RecordingDelayedTask(delayMs = delayMs, action = action)
      tasks += task
      return task
    }

    fun runNext() {
      while (tasks.isNotEmpty()) {
        val task = tasks.removeFirst()
        if (task.cancelled) {
          continue
        }
        task.action()
        return
      }
    }

    private class RecordingDelayedTask(
      val delayMs: Long,
      val action: () -> Unit,
    ) : RuntimeServiceDelayedTask {
      var cancelled: Boolean = false

      override fun cancel() {
        cancelled = true
      }
    }
  }

  internal class RecordingRuntimeForegroundServiceAdapter : RuntimeForegroundServiceAdapter {
    val startedModels = mutableListOf<RuntimeForegroundNotificationModel>()
    var stopCount: Int = 0
      private set

    override fun startOrUpdateForeground(model: RuntimeForegroundNotificationModel) {
      startedModels += model
    }

    override fun stopForeground(removeNotification: Boolean) {
      if (removeNotification) {
        stopCount += 1
      }
    }
  }

  internal class RecordingShellGateway(
    private val label: String,
  ) : OpenCrayShellGateway {
    override fun loadShellSnapshot(): Map<String, Any?> = mapOf("source" to "$label-shell")

    override fun observeShell(listener: (Map<String, Any?>) -> Unit): () -> Unit {
      listener(loadShellSnapshot())
      return { }
    }

    override fun saveShellDestination(
      selectedTab: String,
      settingsSubpage: String?,
    ) = Unit
  }

  internal class RecordingRuntimeServiceClient(
    var currentShellGateway: OpenCrayShellGateway?,
    var currentChatGateway: OpenCrayChatRuntimeGateway?,
    var dispatchChatWriteCommandHandler: ((OpenCrayChatWriteCommand) -> OpenCrayChatWriteDispatchResult)? = null,
    var currentSkillsGateway: OpenCraySkillsGateway?,
    var currentSettingsGateway: OpenCraySettingsGateway?,
    var dispatchSkillsWriteCommandHandler: ((OpenCraySkillsWriteCommand) -> OpenCraySkillsWriteDispatchResult)? = null,
    var dispatchSettingsWriteCommandHandler: ((OpenCraySettingsWriteCommand) -> OpenCraySettingsWriteDispatchResult)? = null,
  ) : OpenCrayRuntimeServiceClient {
    private val listeners = linkedSetOf<(RuntimeServiceConnectionState) -> Unit>()
    private var currentConnectionState: RuntimeServiceConnectionState =
      RuntimeServiceConnectionState.inProcessFallback()
    var loadConnectionStateCallCount: Int = 0
      private set
    var peekConnectionStateCallCount: Int = 0
      private set
    var loadShellGatewayCallCount: Int = 0
      private set
    var peekShellGatewayCallCount: Int = 0
      private set
    var loadChatRuntimeGatewayCallCount: Int = 0
      private set
    var peekChatRuntimeGatewayCallCount: Int = 0
      private set
    var loadSkillsGatewayCallCount: Int = 0
      private set
    var peekSkillsGatewayCallCount: Int = 0
      private set
    var loadSettingsGatewayCallCount: Int = 0
      private set
    var peekSettingsGatewayCallCount: Int = 0
      private set

    override fun loadSnapshot(): OpenCrayRuntimeServiceClientSnapshot = error("unused in test")

    override fun loadConnectionState(): RuntimeServiceConnectionState {
      loadConnectionStateCallCount += 1
      return currentConnectionState
    }

    override fun peekConnectionState(): RuntimeServiceConnectionState {
      peekConnectionStateCallCount += 1
      return currentConnectionState
    }

    override fun loadShellGateway(): OpenCrayShellGateway? {
      loadShellGatewayCallCount += 1
      return currentShellGateway
    }

    override fun peekShellGateway(): OpenCrayShellGateway? {
      peekShellGatewayCallCount += 1
      return currentShellGateway
    }

    override fun loadChatRuntimeGateway(): OpenCrayChatRuntimeGateway? {
      loadChatRuntimeGatewayCallCount += 1
      return currentChatGateway
    }

    override fun peekChatRuntimeGateway(): OpenCrayChatRuntimeGateway? {
      peekChatRuntimeGatewayCallCount += 1
      return currentChatGateway
    }

    override fun dispatchChatWriteCommand(
      command: OpenCrayChatWriteCommand,
    ): OpenCrayChatWriteDispatchResult? = dispatchChatWriteCommandHandler?.invoke(command)

    override fun loadSkillsGateway(): OpenCraySkillsGateway? {
      loadSkillsGatewayCallCount += 1
      return currentSkillsGateway
    }

    override fun peekSkillsGateway(): OpenCraySkillsGateway? {
      peekSkillsGatewayCallCount += 1
      return currentSkillsGateway
    }

    override fun dispatchSkillsWriteCommand(
      command: OpenCraySkillsWriteCommand,
    ): OpenCraySkillsWriteDispatchResult? = dispatchSkillsWriteCommandHandler?.invoke(command)

    override fun loadSettingsGateway(): OpenCraySettingsGateway? {
      loadSettingsGatewayCallCount += 1
      return currentSettingsGateway
    }

    override fun peekSettingsGateway(): OpenCraySettingsGateway? {
      peekSettingsGatewayCallCount += 1
      return currentSettingsGateway
    }

    override fun dispatchSettingsWriteCommand(
      command: OpenCraySettingsWriteCommand,
    ): OpenCraySettingsWriteDispatchResult? = dispatchSettingsWriteCommandHandler?.invoke(command)

    override fun observeConnectionState(listener: (RuntimeServiceConnectionState) -> Unit): () -> Unit {
      listeners += listener
      return { listeners -= listener }
    }

    override fun observePassiveConnectionState(
      listener: (RuntimeServiceConnectionState) -> Unit,
    ): () -> Unit = observeConnectionState(listener)

    fun emitConnectionStateChanged(state: RuntimeServiceConnectionState = RuntimeServiceConnectionState.binderConnected()) {
      currentConnectionState = state
      listeners.toList().forEach { listener -> listener(state) }
    }
  }

  internal class SkillsGatewayAvailableOnObserveClient(
    private val binderGateway: OpenCraySkillsGateway,
  ) : OpenCrayRuntimeServiceClient {
    private var observing: Boolean = false

    override fun loadSnapshot(): OpenCrayRuntimeServiceClientSnapshot = error("unused in test")

    override fun loadConnectionState(): RuntimeServiceConnectionState =
      if (observing) {
        RuntimeServiceConnectionState.binderConnected()
      } else {
        RuntimeServiceConnectionState.bindingPending()
      }

    override fun loadSkillsGateway(): OpenCraySkillsGateway? =
      if (observing) binderGateway else null

    override fun peekSkillsGateway(): OpenCraySkillsGateway? =
      if (observing) binderGateway else null

    override fun observeConnectionState(listener: (RuntimeServiceConnectionState) -> Unit): () -> Unit {
      observing = true
      return { }
    }

    override fun observePassiveConnectionState(
      listener: (RuntimeServiceConnectionState) -> Unit,
    ): () -> Unit = observeConnectionState(listener)
  }

  internal class ChatGatewayAvailableOnObserveClient(
    private val binderGateway: OpenCrayChatRuntimeGateway,
  ) : OpenCrayRuntimeServiceClient {
    private var observing: Boolean = false

    override fun loadSnapshot(): OpenCrayRuntimeServiceClientSnapshot = error("unused in test")

    override fun loadConnectionState(): RuntimeServiceConnectionState =
      if (observing) {
        RuntimeServiceConnectionState.binderConnected()
      } else {
        RuntimeServiceConnectionState.bindingPending()
      }

    override fun loadChatRuntimeGateway(): OpenCrayChatRuntimeGateway? =
      if (observing) binderGateway else null

    override fun peekChatRuntimeGateway(): OpenCrayChatRuntimeGateway? =
      if (observing) binderGateway else null

    override fun observeConnectionState(listener: (RuntimeServiceConnectionState) -> Unit): () -> Unit {
      observing = true
      return { }
    }

    override fun observePassiveConnectionState(
      listener: (RuntimeServiceConnectionState) -> Unit,
    ): () -> Unit = observeConnectionState(listener)
  }

  internal class SettingsGatewayAvailableOnObserveClient(
    private val binderGateway: OpenCraySettingsGateway,
  ) : OpenCrayRuntimeServiceClient {
    private var observing: Boolean = false

    override fun loadSnapshot(): OpenCrayRuntimeServiceClientSnapshot = error("unused in test")

    override fun loadConnectionState(): RuntimeServiceConnectionState =
      if (observing) {
        RuntimeServiceConnectionState.binderConnected()
      } else {
        RuntimeServiceConnectionState.bindingPending()
      }

    override fun loadSettingsGateway(): OpenCraySettingsGateway? =
      if (observing) binderGateway else null

    override fun peekSettingsGateway(): OpenCraySettingsGateway? =
      if (observing) binderGateway else null

    override fun observeConnectionState(listener: (RuntimeServiceConnectionState) -> Unit): () -> Unit {
      observing = true
      return { }
    }

    override fun observePassiveConnectionState(
      listener: (RuntimeServiceConnectionState) -> Unit,
    ): () -> Unit = observeConnectionState(listener)
  }

  internal class RecordingChatRuntimeGateway(
    private val label: String,
  ) : OpenCrayRuntimeServiceChatGateway {
    var chatPayload: Map<String, Any?> = mapOf("source" to "$label-chat")
    var chatRuntimePayload: Map<String, Any?> = mapOf("source" to "$label-runtime")
    var liveAssistantDraftEventPayload: Map<String, Any?>? = null
    var submittedText: String? = null
      private set
    var createChatSessionCallCount: Int = 0
      private set
    val copiedSessionIds = mutableListOf<String>()
    val branchedSessionRequests = mutableListOf<Pair<String, String>>()
    var memoryDebugActionRecordId: String? = null
      private set
    var memoryDebugActionId: String? = null
      private set
    var approvedTaskIdOrRunId: String? = null
      private set
    var approvedForSessionTaskIdOrRunId: String? = null

    var approvedAsBatchTaskIdOrRunId: String? = null
      private set
    var notifiedChatSnapshotCount: Int = 0
      private set
    private val chatListeners = linkedSetOf<(Map<String, Any?>) -> Unit>()
    private val liveDraftListeners = linkedSetOf<(Map<String, Any?>) -> Unit>()
    private val runtimeListeners = linkedSetOf<(Map<String, Any?>) -> Unit>()

    override fun loadChatSnapshot(): Map<String, Any?> = chatPayload

    override fun observeChat(listener: (Map<String, Any?>) -> Unit): () -> Unit {
      chatListeners += listener
      listener(loadChatSnapshot())
      return {
        chatListeners.remove(listener)
      }
    }

    override fun observeLiveAssistantDraftEvents(listener: (Map<String, Any?>) -> Unit): () -> Unit {
      liveDraftListeners += listener
      liveAssistantDraftEventPayload?.let(listener)
      return {
        liveDraftListeners.remove(listener)
      }
    }

    override fun loadChatRuntimeSnapshot(): Map<String, Any?> = chatRuntimePayload

    override fun loadChatRunSnapshot(runId: String): Map<String, Any?>? = mapOf(
      "source" to "$label-run",
      "runId" to runId,
    )

    override fun waitForChatRun(runId: String, timeoutMs: Long): Map<String, Any?>? = mapOf(
      "source" to "$label-wait",
      "runId" to runId,
      "timeoutMs" to timeoutMs,
    )

    override fun observeChatRuntime(listener: (Map<String, Any?>) -> Unit): () -> Unit {
      runtimeListeners += listener
      listener(loadChatRuntimeSnapshot())
      return {
        runtimeListeners.remove(listener)
      }
    }

    fun emitChatSnapshot() {
      val payload = loadChatSnapshot()
      chatListeners.toList().forEach { listener ->
        listener(payload)
      }
    }

    fun emitChatRuntimeSnapshot() {
      val payload = loadChatRuntimeSnapshot()
      runtimeListeners.toList().forEach { listener ->
        listener(payload)
      }
    }

    fun emitLiveAssistantDraftEvent() {
      val payload = liveAssistantDraftEventPayload ?: return
      liveDraftListeners.toList().forEach { listener ->
        listener(payload)
      }
    }

    override fun refreshSandboxSessionInfo() = Unit

    override fun loadMemoryDebugSnapshot(): Map<String, Any?> = emptyMap()

    override fun loadMemoryDebugLinksSnapshot(): Map<String, Any?> = emptyMap()

    override fun loadSoulDebugSnapshot(): Map<String, Any?> = emptyMap()

    override fun searchMemoryDebug(query: String, maxResults: Int, minScore: Int): Map<String, Any?> =
      emptyMap()

    override fun getMemoryDebugSlice(path: String, fromLine: Int?, lines: Int): Map<String, Any?> =
      emptyMap()

    override fun applyMemoryDebugAction(recordId: String, actionId: String): Map<String, Any?> {
      memoryDebugActionRecordId = recordId
      memoryDebugActionId = actionId
      return mapOf(
        "source" to "$label-memory-action",
        "recordId" to recordId,
        "action" to actionId,
      )
    }

    override fun createChatSession() {
      createChatSessionCallCount += 1
    }

    override fun copyChatSession(sessionId: String) {
      copiedSessionIds += sessionId
    }

    override fun deleteChatSession(sessionId: String) = Unit

    override fun selectChatSession(sessionId: String) = Unit

    override fun branchChatSessionFromMessage(sessionId: String, messageId: String) {
      branchedSessionRequests += sessionId to messageId
    }

    override fun deleteChatMessage(sessionId: String, messageId: String) = Unit

    override fun recallChatMessage(sessionId: String, messageId: String) = Unit

    override fun submitChatMessage(
      text: String,
      attachments: List<com.opencray.runtime.OpenCrayFinalAttachment>,
    ): Map<String, Any?>? {
      submittedText = text
      return mapOf("source" to "$label-submit", "submittedText" to text)
    }

    override fun approveChatApproval(taskIdOrRunId: String) {
      approvedTaskIdOrRunId = taskIdOrRunId
    }

    override fun approveChatApprovalForSession(taskIdOrRunId: String) {
      approvedForSessionTaskIdOrRunId = taskIdOrRunId
    }

    override fun approveChatApprovalAsBatch(taskIdOrRunId: String) {
      approvedAsBatchTaskIdOrRunId = taskIdOrRunId
    }

    override fun rejectChatApproval(taskIdOrRunId: String) = Unit

    override fun interruptChatRun(taskIdOrRunId: String) = Unit

    override fun retryChatRun(taskIdOrRunId: String) = Unit

    override fun notifyChatSnapshotsChanged() {
      notifiedChatSnapshotCount += 1
    }
  }

  internal class RecordingSkillsGateway(
    private val label: String,
  ) : OpenCraySkillsGateway {
    var lastInstalledSourceRef: String? = null
      private set
    var observeSkillsCount: Int = 0
      private set
    var lastSetSkillEnabledSkillId: String? = null
      private set
    var lastDeletedSkillId: String? = null
      private set
    var refreshCount: Int = 0
      private set
    var lastActivatedSourceId: String? = null
      private set

    override fun loadSkillsSnapshot(query: String, suggestedLimit: Int): Map<String, Any?> = mapOf(
      "source" to "$label-skills",
      "query" to query,
      "suggestedLimit" to suggestedLimit,
    )

    override fun observeSkills(listener: (Map<String, Any?>) -> Unit): () -> Unit {
      observeSkillsCount += 1
      listener(loadSkillsSnapshot(query = "", suggestedLimit = 0))
      return { }
    }

    override fun setSkillEnabled(skillId: String, enabled: Boolean) {
      lastSetSkillEnabledSkillId = skillId
    }

    override fun installSuggestedSkill(skillId: String): String =
      installSkillSource(sourceRef = skillId, selectedSkillName = "")

    override fun installSkillSource(
      sourceRef: String,
      selectedSkillName: String,
    ): String {
      lastInstalledSourceRef = sourceRef
      return "Installed $sourceRef via $label."
    }

    override fun installSkillSourceBatch(
      sourceRef: String,
      selectedSkillNames: List<String>,
    ): String = "Installed ${selectedSkillNames.size} skills via $label."

    override fun inspectSkillSource(sourceRef: String): Map<String, Any?> =
      mapOf("source" to "$label-inspect", "sourceRef" to sourceRef)

    override fun deleteInstalledSkill(skillId: String): String {
      lastDeletedSkillId = skillId
      return "Removed $skillId via $label."
    }

    override fun refreshSkills(): String {
      refreshCount += 1
      return "Refreshed via $label."
    }

    override fun checkInstalledSkillUpdates(skillId: String): String = "Checked $skillId via $label."

    override fun updateInstalledSkill(skillId: String): String = "Updated $skillId via $label."

    override fun loadSkillInstructions(skillId: String): Map<String, Any?> =
      mapOf("source" to "$label-instructions", "skillId" to skillId)

    override fun loadSuggestedSkillInstructions(
      sourceRef: String,
      selectedSkillName: String,
    ): Map<String, Any?> = mapOf(
      "source" to "$label-suggested-instructions",
      "sourceRef" to sourceRef,
      "selectedSkillName" to selectedSkillName,
    )

    override fun activateSkillsInstallSource(sourceId: String): String {
      lastActivatedSourceId = sourceId
      return sourceId
    }
  }

  internal class RecordingProjectionSkillsFacade : com.opencray.app.facade.skills.SkillsFacade {
    val loadQueries = mutableListOf<String>()
    val loadSuggestedLimits = mutableListOf<Int>()

    override fun loadSnapshot(
      query: String,
      suggestedLimit: Int,
    ): com.opencray.app.facade.skills.SkillsSnapshot {
      loadQueries += query
      loadSuggestedLimits += suggestedLimit
      val suggestions = listOf(
        com.opencray.app.facade.skills.SuggestedSkillSnapshot(
          id = "voice-notes",
          name = "voice-notes",
          description = "Capture voice notes into the workspace",
          sourceRef = "voice-notes",
          sourceLabel = "Local catalog",
        ),
        com.opencray.app.facade.skills.SuggestedSkillSnapshot(
          id = "git-sync",
          name = "git-sync",
          description = "Synchronize git branches",
          sourceRef = "git-sync",
          sourceLabel = "Local catalog",
        ),
      ).filter { item ->
        query.isBlank() ||
          item.name.contains(query, ignoreCase = true) ||
          item.description.contains(query, ignoreCase = true)
      }
      return com.opencray.app.facade.skills.SkillsSnapshot(
        installedSkills = listOf(
          com.opencray.app.facade.skills.InstalledSkillSnapshot(
            id = "installed-skill",
            name = "installed-skill",
            description = "Installed skill",
            isEnabled = true,
            sourceDirectoryPath = "/skills/installed-skill",
            canDelete = true,
          ),
        ),
        installSources = listOf(
          com.opencray.app.facade.skills.InstallSourceSnapshot(
            id = "curated-library",
            title = "Curated",
            subtitle = "Local catalog",
            actionLabel = "Inspect",
            isAvailable = true,
          ),
        ),
        suggestedSkills = suggestions,
      )
    }

    override fun setSkillEnabled(skillId: String, enabled: Boolean): Boolean = false

    override fun installSkillSource(
      sourceRef: String,
      selectedSkillName: String,
    ): com.opencray.app.facade.skills.SkillInstallRequestResult =
      com.opencray.app.facade.skills.SkillInstallRequestResult(errorMessage = "unused in test")

    override fun installSuggestedSkill(skillId: String): Boolean = false

    override fun installSkillSourceBatch(
      sourceRef: String,
      selectedSkillNames: List<String>,
    ): com.opencray.runtime.skills.SkillPackageBatchInstallAttempt =
      com.opencray.runtime.skills.SkillPackageBatchInstallAttempt(
        errorCode = "UNUSED",
        errorMessage = "unused in test",
      )

    override fun inspectSkillSource(
      sourceRef: String,
    ): com.opencray.runtime.skills.SkillSourceInspectionAttempt =
      com.opencray.runtime.skills.SkillSourceInspectionAttempt(
        errorCode = "UNUSED",
        errorMessage = "unused in test",
      )

    override fun deleteInstalledSkill(skillId: String): Boolean = false

    override fun refresh() = Unit

    override fun checkInstalledSkillUpdates(
      skillId: String,
    ): com.opencray.runtime.skills.SkillPackageCheckReport =
      com.opencray.runtime.skills.SkillPackageCheckReport(results = emptyList())

    override fun updateInstalledSkill(
      skillId: String,
    ): com.opencray.runtime.skills.SkillPackageUpdateReport =
      com.opencray.runtime.skills.SkillPackageUpdateReport(results = emptyList())

    override fun loadInstructions(skillId: String): com.opencray.app.facade.skills.SkillInstructionsSnapshot? =
      com.opencray.app.facade.skills.SkillInstructionsSnapshot(
        id = skillId,
        name = skillId,
        description = "Instructions for $skillId",
        body = "# $skillId",
        sourceDirectoryPath = "/skills/$skillId",
        isEnabled = true,
        canDelete = true,
      )

    override fun loadSuggestedInstructions(
      sourceRef: String,
      selectedSkillName: String,
    ): com.opencray.app.facade.skills.SkillInstructionsSnapshot? =
      com.opencray.app.facade.skills.SkillInstructionsSnapshot(
        id = selectedSkillName.ifBlank { sourceRef },
        name = selectedSkillName.ifBlank { sourceRef },
        description = "Suggested instructions for ${selectedSkillName.ifBlank { sourceRef }}",
        body = "# ${selectedSkillName.ifBlank { sourceRef }}",
        sourceDirectoryPath = "/skills/${selectedSkillName.ifBlank { sourceRef }}",
        isEnabled = false,
        canDelete = false,
      )

    override fun enabledSkillRoots(): List<java.io.File> = emptyList()

    override fun activateInstallSource(sourceId: String): String = sourceId
  }

  internal class RecordingServiceOwnedSkillsFacade : com.opencray.app.facade.skills.SkillsFacade {
    val loadQueries = mutableListOf<String>()
    val loadSuggestedLimits = mutableListOf<Int>()
    var lastLoadQuery: String? = null
      private set
    var lastLoadSuggestedLimit: Int? = null
      private set
    var lastSetSkillEnabledSkillId: String? = null
      private set
    var lastSetSkillEnabledValue: Boolean? = null
      private set
    var lastInstalledSourceRef: String? = null
      private set
    var lastInstalledSelectedSkillName: String? = null
      private set
    var lastBatchInstalledSourceRef: String? = null
      private set
    var lastBatchInstalledSkillNames: List<String> = emptyList()
      private set
    var lastCheckedSkillId: String? = null
      private set
    var lastUpdatedSkillId: String? = null
      private set

    override fun loadSnapshot(
      query: String,
      suggestedLimit: Int,
    ): com.opencray.app.facade.skills.SkillsSnapshot {
      loadQueries += query
      loadSuggestedLimits += suggestedLimit
      lastLoadQuery = query
      lastLoadSuggestedLimit = suggestedLimit
      return com.opencray.app.facade.skills.SkillsSnapshot(
        installedSkills = listOf(
          com.opencray.app.facade.skills.InstalledSkillSnapshot(
            id = "voice-notes",
            name = "voice-notes",
            description = "Voice notes",
            isEnabled = true,
            sourceDirectoryPath = "/skills/voice-notes",
            canDelete = true,
          ),
        ),
        installSources = listOf(
          com.opencray.app.facade.skills.InstallSourceSnapshot(
            id = "github-url",
            title = "GitHub",
            subtitle = "Remote source",
            actionLabel = "Inspect",
            isAvailable = true,
          ),
        ),
        suggestedSkills = listOf(
          com.opencray.app.facade.skills.SuggestedSkillSnapshot(
            id = "voice-notes",
            name = "voice-notes",
            description = "Voice notes",
            sourceRef = "github:opencray/skills",
            sourceLabel = "Remote",
          ),
        ),
      )
    }

    override fun setSkillEnabled(skillId: String, enabled: Boolean): Boolean {
      lastSetSkillEnabledSkillId = skillId
      lastSetSkillEnabledValue = enabled
      return skillId == "voice-notes"
    }

    override fun installSkillSource(
      sourceRef: String,
      selectedSkillName: String,
    ): com.opencray.app.facade.skills.SkillInstallRequestResult {
      lastInstalledSourceRef = sourceRef
      lastInstalledSelectedSkillName = selectedSkillName
      return com.opencray.app.facade.skills.SkillInstallRequestResult(
        installedSkillId = selectedSkillName.ifBlank { "voice-notes" },
      )
    }

    override fun installSuggestedSkill(skillId: String): Boolean = false

    override fun installSkillSourceBatch(
      sourceRef: String,
      selectedSkillNames: List<String>,
    ): com.opencray.runtime.skills.SkillPackageBatchInstallAttempt {
      lastBatchInstalledSourceRef = sourceRef
      lastBatchInstalledSkillNames = selectedSkillNames
      return com.opencray.runtime.skills.SkillPackageBatchInstallAttempt(
        result = com.opencray.runtime.skills.SkillPackageBatchInstallResult(
          sourceType = "github",
          sourceRef = sourceRef,
          entries = selectedSkillNames.map { skillName ->
            com.opencray.runtime.skills.SkillPackageBatchInstallEntry(
              requestedSkillName = skillName,
              installedSkillId = skillName,
            )
          },
        ),
      )
    }

    override fun inspectSkillSource(
      sourceRef: String,
    ): com.opencray.runtime.skills.SkillSourceInspectionAttempt =
      com.opencray.runtime.skills.SkillSourceInspectionAttempt(
        result = com.opencray.runtime.skills.SkillSourceInspectionResult(
          sourceType = "github",
          sourceRef = sourceRef,
          sourcePath = "/cache/skills",
          resolvedRevision = "main",
          resolvedCommitSha = "abc123",
          candidates = listOf(
            com.opencray.runtime.skills.SkillSourceInspectionCandidate(
              name = "voice-notes",
              description = "Voice notes",
              relativePath = "voice-notes",
            ),
          ),
        ),
      )

    override fun deleteInstalledSkill(skillId: String): Boolean = skillId == "voice-notes"

    override fun refresh() = Unit

    override fun checkInstalledSkillUpdates(
      skillId: String,
    ): com.opencray.runtime.skills.SkillPackageCheckReport {
      lastCheckedSkillId = skillId
      return com.opencray.runtime.skills.SkillPackageCheckReport(
        results = listOf(
          com.opencray.runtime.skills.SkillPackageCheckResult(
            skillId = skillId,
            sourceType = "github",
            sourceRef = "github:opencray/skills",
            status = com.opencray.runtime.skills.SkillPackageCheckStatus.UPDATE_AVAILABLE,
            checkedAtEpochMs = 1_000L,
          ),
        ),
      )
    }

    override fun updateInstalledSkill(
      skillId: String,
    ): com.opencray.runtime.skills.SkillPackageUpdateReport {
      lastUpdatedSkillId = skillId
      return com.opencray.runtime.skills.SkillPackageUpdateReport(
        results = listOf(
          com.opencray.runtime.skills.SkillPackageUpdateResult(
            skillId = skillId,
            sourceType = "github",
            sourceRef = "github:opencray/skills",
            status = com.opencray.runtime.skills.SkillPackageUpdateStatus.UPDATED,
          ),
        ),
      )
    }

    override fun loadInstructions(skillId: String): com.opencray.app.facade.skills.SkillInstructionsSnapshot? =
      com.opencray.app.facade.skills.SkillInstructionsSnapshot(
        id = skillId,
        name = skillId,
        description = "Instructions for $skillId",
        body = "# $skillId",
        sourceDirectoryPath = "/skills/$skillId",
        isEnabled = true,
        canDelete = true,
      )

    override fun loadSuggestedInstructions(
      sourceRef: String,
      selectedSkillName: String,
    ): com.opencray.app.facade.skills.SkillInstructionsSnapshot? =
      com.opencray.app.facade.skills.SkillInstructionsSnapshot(
        id = selectedSkillName,
        name = selectedSkillName,
        description = "Suggested instructions",
        body = "# $selectedSkillName",
        sourceDirectoryPath = sourceRef,
        isEnabled = false,
        canDelete = false,
      )

    override fun enabledSkillRoots(): List<java.io.File> = emptyList()

    override fun activateInstallSource(sourceId: String): String = "activated:$sourceId"
  }

  internal class RecordingSettingsGateway(
    private val label: String,
  ) : OpenCraySettingsGateway {
    var lastMcpMasterEnabled: Boolean? = null
      private set
    var lastNetworkSearchSlots: List<Map<String, Any?>>? = null
      private set
    var lastNotificationSettingsPayload: Map<String, Any?>? = null
      private set
    var lastMediaSpeechPayload: Map<String, Any?>? = null
      private set
    var lastPersonalizationPresetId: String? = null
      private set
    var lastPersonalizationCustomLabel: String? = null
      private set
    var lastPersonalizationCustomGuidance: String? = null
      private set
    var lastPersonalizationResetScopeId: String? = null
      private set
    var lastSafetyAutomationModeId: String? = null
      private set
    var lastSandboxSettingsPayload: Map<String, Any?>? = null
      private set
    var lastAppLanguageId: String? = null
      private set
    var lastStrongBackgroundActionId: String? = null
      private set

    override fun loadSettingsOverview(): Map<String, Any?> = mapOf("source" to "$label-settings")

    override fun observeSettingsOverview(listener: (Map<String, Any?>) -> Unit): () -> Unit {
      listener(loadSettingsOverview())
      return { }
    }

    override fun loadSettingsDetail(routeIdRaw: String): Map<String, Any?> =
      mapOf("source" to "$label-settings-detail", "routeId" to routeIdRaw)

    override fun loadNotificationSettings(): Map<String, Any?> =
      mapOf("source" to "$label-notification-settings")

    override fun saveNotificationSettings(payload: Map<String, Any?>): Map<String, Any?> {
      lastNotificationSettingsPayload = payload
      return mapOf("source" to "$label-notification-settings-save")
    }

    override fun loadStrongBackgroundSnapshot(): Map<String, Any?> =
      mapOf("source" to "$label-strong-background")

    override fun performStrongBackgroundAction(actionId: String): Map<String, Any?> {
      lastStrongBackgroundActionId = actionId
      return mapOf("source" to "$label-strong-background-action", "actionId" to actionId)
    }

    override fun loadNetworkSearchConfig(): Map<String, Any?> =
      mapOf("source" to "$label-network-search")

    override fun saveNetworkSearchConfig(slots: List<Map<String, Any?>>): Map<String, Any?> {
      lastNetworkSearchSlots = slots
      return mapOf("source" to "$label-network-search-save", "slotCount" to slots.size)
    }

    override fun loadMediaSpeechConfig(): Map<String, Any?> =
      mapOf("source" to "$label-media-speech")

    override fun saveMediaSpeechConfig(payload: Map<String, Any?>): Map<String, Any?> {
      lastMediaSpeechPayload = payload
      return mapOf("source" to "$label-media-speech-save", "keys" to payload.keys.sorted())
    }

    override fun loadSandboxSettings(): Map<String, Any?> =
      mapOf("source" to "$label-sandbox-settings")

    override fun saveSandboxSettings(payload: Map<String, Any?>): Map<String, Any?> {
      lastSandboxSettingsPayload = payload
      return mapOf("source" to "$label-sandbox-settings-save")
    }

    override fun loadLlmConfig(): Map<String, Any?> = mapOf("source" to "$label-llm")

    override fun saveLlmConfig(
      enabled: Boolean,
      streamingEnabled: Boolean?,
      providerMode: String,
      providerId: String,
      selectedProviderOptionId: String,
      protocol: String,
      providerName: String,
      providerNotes: String,
      baseUrl: String,
      apiKey: String,
      model: String,
      reasoningEffort: String,
      systemPrompt: String,
      openAiPromptCacheKeyStrategy: String?,
      openAiPromptCacheRetention: String?,
      anthropicPromptCachingEnabled: Boolean?,
      anthropicPromptCacheTtl: String?,
      contextBudgetPreset: String?,
      contextBudgetReservedOutputTokens: Int?,
      contextBudgetSafetyMarginTokens: Int?,
      contextBudgetEffectiveInputPercent: Double?,
      selectedOnDeviceModelId: String,
      onDeviceMaxContextWindow: Int,
      onDeviceMaxTokens: Int,
      onDeviceTopK: Int,
      onDeviceTopP: Double,
      onDeviceTemperature: Double,
      onDeviceAccelerator: String,
      onDeviceThinkingEnabled: Boolean,
      onDeviceLiteModeEnabled: Boolean,
      contextWindowTokensOverride: Int?,
    ): Map<String, Any?> = mapOf(
      "source" to "$label-llm-save",
      "enabled" to enabled,
      "streamingEnabled" to streamingEnabled,
      "providerMode" to providerMode,
    )

    override fun saveCustomLlmProvider(
      selectedProviderOptionId: String,
      streamingEnabled: Boolean?,
      protocol: String,
      providerName: String,
      providerNotes: String,
      baseUrl: String,
      apiKey: String,
      model: String,
      reasoningEffort: String,
      systemPrompt: String,
      openAiPromptCacheKeyStrategy: String?,
      openAiPromptCacheRetention: String?,
      anthropicPromptCachingEnabled: Boolean?,
      anthropicPromptCacheTtl: String?,
      contextBudgetPreset: String?,
      contextBudgetReservedOutputTokens: Int?,
      contextBudgetSafetyMarginTokens: Int?,
      contextBudgetEffectiveInputPercent: Double?,
      contextWindowTokensOverride: Int?,
    ): Map<String, Any?> = mapOf("source" to "$label-custom-llm")

    override fun validateLlmConfig(
      providerId: String,
      protocol: String,
      baseUrl: String,
      apiKey: String,
      model: String,
      reasoningEffort: String,
      contextWindowTokensOverride: Int?,
    ): Map<String, Any?> = mapOf("source" to "$label-llm-validate", "providerId" to providerId)

    override fun downloadOnDeviceLlmModel(modelId: String): Map<String, Any?> =
      mapOf("source" to "$label-on-device-download", "modelId" to modelId)

    override fun cancelOnDeviceLlmModelDownload(modelId: String): Map<String, Any?> =
      mapOf("source" to "$label-on-device-cancel", "modelId" to modelId)

    override fun deleteOnDeviceLlmModel(modelId: String): Map<String, Any?> =
      mapOf("source" to "$label-on-device-delete", "modelId" to modelId)

    override fun loadPersonalizationConfig(): Map<String, Any?> =
      mapOf("source" to "$label-personalization")

    override fun savePersonalizationConfig(
      presetId: String,
      customLabel: String,
      customGuidance: String,
    ): Map<String, Any?> {
      lastPersonalizationPresetId = presetId
      lastPersonalizationCustomLabel = customLabel
      lastPersonalizationCustomGuidance = customGuidance
      return mapOf("source" to "$label-personalization-save", "presetId" to presetId)
    }

    override fun setAppLanguage(languageId: String): Map<String, Any?> {
      lastAppLanguageId = languageId
      return mapOf("source" to "$label-language", "languageId" to languageId)
    }

    override fun runPersonalizationReset(scopeId: String): Map<String, Any?> {
      lastPersonalizationResetScopeId = scopeId
      return mapOf("source" to "$label-personalization-reset", "scopeId" to scopeId)
    }

    override fun loadMcpSettings(): Map<String, Any?> =
      mapOf("source" to "$label-mcp")

    override fun setMcpMasterEnabled(enabled: Boolean): Map<String, Any?> {
      lastMcpMasterEnabled = enabled
      return mapOf("source" to "$label-mcp-master", "enabled" to enabled)
    }

    override fun setMcpServerEnabled(
      serverId: String,
      enabled: Boolean,
    ): Map<String, Any?> = mapOf(
      "source" to "$label-mcp-server",
      "serverId" to serverId,
      "enabled" to enabled,
    )

    override fun loadSafetySettings(): Map<String, Any?> =
      mapOf("source" to "$label-safety")

    override fun saveSafetySettings(
      automationModeId: String,
      rollbackJournalEnabled: Boolean,
      maxFilesPerBatch: Int,
      maxAgentTurns: Int,
      maxToolCalls: Int,
      undoWindowHours: Int,
      fileChangesPolicyId: String,
      fileDeletesPolicyId: String,
      shellCommandsPolicyId: String,
      externalAccessModeId: String,
      photoLibraryEnabled: Boolean,
      downloadsEnabled: Boolean,
      documentsEnabled: Boolean,
      recordingsEnabled: Boolean,
      workspaceAccessProfileId: String,
      readOnlyOutsideWorkspace: Boolean,
      liveContextModeId: String,
      memoryToolsEnabled: Boolean,
      subAgentContextDefaultModeId: String?,
      subAgentContextProfileOverrides: Map<String, String>,
    ): Map<String, Any?> {
      lastSafetyAutomationModeId = automationModeId
      return mapOf(
        "source" to "$label-safety-save",
        "automationModeId" to automationModeId,
        "liveContextModeId" to liveContextModeId,
      )
    }
  }

  internal class RecordingServiceOwnedSettingsFacade :
    com.opencray.app.facade.settings.SettingsFacade {
    var lastLoadedDetailRouteId: com.opencray.app.facade.settings.SettingsRouteId? = null
      private set

    override fun loadOverview(): com.opencray.app.facade.settings.SettingsOverviewSnapshot =
      com.opencray.app.facade.settings.SettingsOverviewSnapshot(
        eyebrow = "APP",
        title = "Settings",
        subtitle = "Configure the app.",
        deviceTitle = "Device",
        deviceSummary = "API routes",
        entries = listOf(
          com.opencray.app.facade.settings.SettingsHomeEntrySnapshot(
            routeId = com.opencray.app.facade.settings.SettingsRouteId.PERSONALIZATION,
            title = "Personalization",
          ),
        ),
      )

    override fun loadDetail(
      routeId: com.opencray.app.facade.settings.SettingsRouteId,
    ): com.opencray.app.facade.settings.SettingsDetailSnapshot {
      lastLoadedDetailRouteId = routeId
      return com.opencray.app.facade.settings.SettingsDetailSnapshot(
        routeId = routeId,
        title = routeId.wireValue,
        subtitle = "Detail for ${routeId.wireValue}",
        sections = listOf(
          com.opencray.app.facade.settings.SettingsSectionSnapshot(
            title = "Section",
            helperText = "Helper",
            rows = listOf(
              com.opencray.app.facade.settings.SettingsRowSnapshot.chevron(
                title = "Row",
                subtitle = "Subtitle",
              ),
            ),
          ),
        ),
      )
    }
  }

  internal class RecordingLlmConfigFacade : com.opencray.app.facade.llm.LlmConfigFacade {
    var lastSavedRequest: com.opencray.app.facade.llm.SaveLlmConfigRequest? = null
      private set
    var lastCustomProviderRequest: com.opencray.app.facade.llm.SaveCustomLlmProviderRequest? = null
      private set
    var lastValidatedRequest: com.opencray.app.facade.llm.ValidateLlmConfigRequest? = null
      private set

    override fun load(): com.opencray.app.facade.llm.LlmConfigSnapshot = snapshot(
      selectedProviderOptionId = "custom-provider",
      protocol = "anthropic",
      providerName = "Custom",
      providerNotes = "Notes",
      baseUrl = "https://example.com",
      apiKey = "secret",
      model = "kimi-k2.5",
      reasoningEffort = "medium",
      systemPrompt = "Prompt",
    )

    override fun save(
      request: com.opencray.app.facade.llm.SaveLlmConfigRequest,
    ): com.opencray.app.facade.llm.LlmConfigSnapshot {
      lastSavedRequest = request
      return snapshot(
        enabled = request.enabled,
        selectedProviderOptionId = request.selectedProviderOptionId,
        protocol = request.protocol,
        providerName = request.providerName,
        providerNotes = request.providerNotes,
        baseUrl = request.baseUrl,
        apiKey = request.apiKey,
        model = request.model,
        reasoningEffort = request.reasoningEffort,
        systemPrompt = request.systemPrompt,
        contextBudgetPreset = request.contextBudgetPreset ?: LlmSettingsState.DEFAULT_CONTEXT_BUDGET_PRESET,
        contextBudgetReservedOutputTokens = request.contextBudgetReservedOutputTokens,
        contextBudgetSafetyMarginTokens = request.contextBudgetSafetyMarginTokens,
        contextBudgetEffectiveInputPercent = request.contextBudgetEffectiveInputPercent,
      )
    }

    override fun saveCustomProvider(
      request: com.opencray.app.facade.llm.SaveCustomLlmProviderRequest,
    ): com.opencray.app.facade.llm.LlmConfigSnapshot {
      lastCustomProviderRequest = request
      return snapshot(
        enabled = true,
        selectedProviderOptionId = request.selectedProviderOptionId,
        protocol = request.protocol,
        providerName = request.providerName,
        providerNotes = request.providerNotes,
        baseUrl = request.baseUrl,
        apiKey = request.apiKey,
        model = request.model,
        reasoningEffort = request.reasoningEffort,
        systemPrompt = request.systemPrompt,
        contextBudgetPreset = request.contextBudgetPreset ?: LlmSettingsState.DEFAULT_CONTEXT_BUDGET_PRESET,
        contextBudgetReservedOutputTokens = request.contextBudgetReservedOutputTokens,
        contextBudgetSafetyMarginTokens = request.contextBudgetSafetyMarginTokens,
        contextBudgetEffectiveInputPercent = request.contextBudgetEffectiveInputPercent,
      )
    }

    override fun validate(
      request: com.opencray.app.facade.llm.ValidateLlmConfigRequest,
    ): com.opencray.app.facade.llm.LlmValidationResult {
      lastValidatedRequest = request
      return com.opencray.app.facade.llm.LlmValidationResult(
        isSuccess = true,
        message = "validated",
        agentCapability = LlmAgentCapabilitySnapshot(
          routeFingerprint = llmRouteFingerprint(
            protocol = request.protocol,
            baseUrl = request.baseUrl,
            model = request.model,
          ),
          verifiedAtEpochMs = 123L,
          visionInputSupported = true,
          nativeToolCallingAvailable = true,
          toolChoiceSupported = true,
          parallelToolCallsSupported = true,
          strictToolSchemaSupported = true,
        ),
      )
    }

    override fun downloadOnDeviceModel(
      modelId: String,
    ): com.opencray.app.facade.llm.LlmConfigSnapshot = load()

    override fun cancelOnDeviceModelDownload(
      modelId: String,
    ): com.opencray.app.facade.llm.LlmConfigSnapshot = load()

    override fun deleteOnDeviceModel(
      modelId: String,
    ): com.opencray.app.facade.llm.LlmConfigSnapshot = load()

    private fun snapshot(
      enabled: Boolean = true,
      selectedProviderOptionId: String,
      protocol: String,
      providerName: String,
      providerNotes: String,
      baseUrl: String,
      apiKey: String,
      model: String,
      reasoningEffort: String,
      systemPrompt: String,
      contextBudgetPreset: String = LlmSettingsState.DEFAULT_CONTEXT_BUDGET_PRESET,
      contextBudgetReservedOutputTokens: Int? = null,
      contextBudgetSafetyMarginTokens: Int? = null,
      contextBudgetEffectiveInputPercent: Double? = null,
    ): com.opencray.app.facade.llm.LlmConfigSnapshot =
      com.opencray.app.facade.llm.LlmConfigSnapshot(
        localeTag = "en",
        enabled = enabled,
        providerId = "custom",
        selectedProviderOptionId = selectedProviderOptionId,
        protocol = protocol,
        providerOptions = listOf(
          com.opencray.app.facade.llm.LlmProviderOptionSnapshot(
            id = "custom-provider",
            providerId = "custom",
            title = "Custom",
            subtitle = "Notes",
            defaultBaseUrl = "https://example.com",
            defaultModel = "kimi-k2.5",
            protocol = "anthropic",
            apiKey = "",
            isCustom = true,
          ),
          com.opencray.app.facade.llm.LlmProviderOptionSnapshot(
            id = "custom-provider-2",
            providerId = "custom",
            title = "Custom 2",
            subtitle = "More notes",
            defaultBaseUrl = "https://provider.example.com",
            defaultModel = "claude-kimi-hybrid",
            protocol = "anthropic",
            apiKey = "",
            isCustom = true,
          ),
        ),
        providerName = providerName,
        providerNotes = providerNotes,
        baseUrl = baseUrl,
        apiKey = apiKey,
        model = model,
        reasoningEffort = reasoningEffort,
        systemPrompt = systemPrompt,
        contextBudgetPreset = contextBudgetPreset,
        contextBudgetReservedOutputTokens = contextBudgetReservedOutputTokens,
        contextBudgetSafetyMarginTokens = contextBudgetSafetyMarginTokens,
        contextBudgetEffectiveInputPercent = contextBudgetEffectiveInputPercent,
        helperText = "helper",
        agentCapability = LlmAgentCapabilitySnapshot(
          routeFingerprint = llmRouteFingerprint(
            protocol = protocol,
            baseUrl = baseUrl,
            model = model,
          ),
          verifiedAtEpochMs = 100L,
          nativeToolCallingAvailable = true,
        ),
      )
  }

  internal class RecordingSandboxSettingsGatewayAccess : SandboxSettingsGatewayAccess {
    var lastSavedState: SandboxSettingsState? = null
      private set
    var lastSavedApiKey: String? = null
      private set

    private var current: ResolvedSandboxSettings = ResolvedSandboxSettings(
      state = SandboxSettingsState(
        enabled = false,
        providerId = "local",
        defaultBackend = "local",
        sessionMode = "persistent",
        autoResume = false,
        idleTimeoutMinutes = 15,
        startupTimeoutMs = 60_000L,
        requestTimeoutMs = 120_000L,
        timeoutAction = "stop",
        templateId = "default",
      ),
      e2bApiKey = null,
    )

    override fun load(): ResolvedSandboxSettings = current

    override fun save(
      state: SandboxSettingsState,
      e2bApiKey: String?,
    ): ResolvedSandboxSettings {
      lastSavedState = state
      lastSavedApiKey = e2bApiKey
      current = ResolvedSandboxSettings(
        state = state,
        e2bApiKey = e2bApiKey?.takeIf(String::isNotBlank),
      )
      return current
    }
  }

  internal class RecordingStrongBackgroundSettingsAccess : StrongBackgroundSettingsAccess {
    var lastActionId: String? = null
      private set

    override fun loadSnapshot(): Map<String, Any?> = mapOf(
      "source" to "service-strong-background",
      "available" to true,
      "tierId" to StrongBackgroundTierIds.ACTIVE_BACKGROUND,
      "setupComplete" to false,
      "recommendedActionIds" to listOf(StrongBackgroundActionIds.OPEN_NOTIFICATION_SETTINGS),
      "actions" to listOf(
        mapOf(
          "id" to StrongBackgroundActionIds.OPEN_NOTIFICATION_SETTINGS,
          "available" to true,
          "recommended" to true,
        ),
      ),
    )

    override fun performAction(actionId: String): Map<String, Any?> {
      lastActionId = actionId
      return mapOf(
        "source" to "service-strong-background-action",
        "actionId" to actionId,
        "available" to true,
        "launched" to true,
      )
    }
  }

  internal class RecordingAppLanguageSettingsGatewayAccess : AppLanguageSettingsGatewayAccess {
    var lastLanguageId: String? = null
      private set

    override fun setAppLanguage(languageId: String): Map<String, Any?> {
      lastLanguageId = languageId
      return mapOf(
        "source" to "service-language",
        "languageId" to languageId,
      )
    }
  }

  internal class RecordingNetworkSearchConfigFacade :
    com.opencray.app.facade.search.NetworkSearchConfigFacade {
    var lastSavedRequest: com.opencray.app.facade.search.SaveNetworkSearchConfigRequest? = null
      private set

    override fun load(): com.opencray.app.facade.search.NetworkSearchConfigSnapshot = snapshot(
      slots = listOf(
        com.opencray.app.facade.search.NetworkSearchSlotSnapshot(
          id = "default",
          providerId = "exa",
          label = "Exa",
          baseUrl = "https://api.exa.ai",
          model = "exa-search",
          apiKey = "search-key",
          enabled = true,
        ),
      ),
    )

    override fun save(
      request: com.opencray.app.facade.search.SaveNetworkSearchConfigRequest,
    ): com.opencray.app.facade.search.NetworkSearchConfigSnapshot {
      lastSavedRequest = request
      return snapshot(
        slots = request.slots.map { slot ->
          com.opencray.app.facade.search.NetworkSearchSlotSnapshot(
            id = slot.id,
            providerId = slot.providerId,
            label = slot.label,
            baseUrl = slot.baseUrl,
            model = slot.model,
            apiKey = slot.apiKey.orEmpty(),
            enabled = slot.enabled,
          )
        },
      )
    }

    private fun snapshot(
      slots: List<com.opencray.app.facade.search.NetworkSearchSlotSnapshot>,
    ): com.opencray.app.facade.search.NetworkSearchConfigSnapshot =
      com.opencray.app.facade.search.NetworkSearchConfigSnapshot(
        localeTag = "en",
        title = "Network & Search",
        subtitle = "Add API keys here. Enabled slots run top to bottom.",
        slots = slots,
      )
  }

  internal class RecordingMediaSpeechSettingsFacade :
    com.opencray.app.facade.media.MediaSpeechSettingsFacade {
    var lastSavedRequest: com.opencray.app.facade.media.SaveMediaSpeechConfigRequest? = null
      private set

    override fun load(): com.opencray.app.facade.media.MediaSpeechConfigSnapshot = snapshot(
      imageGeneration = com.opencray.app.facade.media.MediaProviderSnapshot(
        provider = "openai",
        baseUrl = "https://image.example.com",
        endpoint = "/images",
        model = "gpt-image-1",
        authProtocol = ProviderAuthProtocols.BEARER,
        apiKey = "image-key",
      ),
      videoGeneration = com.opencray.app.facade.media.MediaProviderSnapshot(
        provider = "runway",
        baseUrl = "https://video.example.com",
        endpoint = "/videos",
        model = "gen4",
        authProtocol = ProviderAuthProtocols.BEARER,
        apiKey = "video-key",
      ),
      voiceGeneration = com.opencray.app.facade.media.VoiceProviderSnapshot(
        provider = "openai",
        baseUrl = "https://voice.example.com",
        endpoint = "/speech",
        model = "tts-1",
        voicePreset = "alloy",
        authProtocol = ProviderAuthProtocols.BEARER,
        apiKey = "voice-key",
      ),
      sttRouteId = "on_device",
      externalStt = com.opencray.app.facade.media.MediaProviderSnapshot(
        provider = "deepgram",
        baseUrl = "https://stt.example.com",
        endpoint = "/listen",
        model = "nova-3",
        authProtocol = ProviderAuthProtocols.BEARER,
        apiKey = "stt-key",
      ),
      onDeviceModel = com.opencray.app.facade.media.OnDeviceSttSnapshot(
        modelPackage = "tiny.en",
        downloadStatus = "not_downloaded",
      ),
    )

    override fun save(
      request: com.opencray.app.facade.media.SaveMediaSpeechConfigRequest,
    ): com.opencray.app.facade.media.MediaSpeechConfigSnapshot {
      lastSavedRequest = request
      return snapshot(
        imageGeneration = com.opencray.app.facade.media.MediaProviderSnapshot(
          provider = request.imageGeneration.provider,
          baseUrl = request.imageGeneration.baseUrl,
          endpoint = request.imageGeneration.endpoint,
          model = request.imageGeneration.model,
          authProtocol = request.imageGeneration.authProtocol,
          apiKey = request.imageGeneration.apiKey.orEmpty(),
        ),
        videoGeneration = com.opencray.app.facade.media.MediaProviderSnapshot(
          provider = request.videoGeneration.provider,
          baseUrl = request.videoGeneration.baseUrl,
          endpoint = request.videoGeneration.endpoint,
          model = request.videoGeneration.model,
          authProtocol = request.videoGeneration.authProtocol,
          apiKey = request.videoGeneration.apiKey.orEmpty(),
        ),
        voiceGeneration = com.opencray.app.facade.media.VoiceProviderSnapshot(
          provider = request.voiceGeneration.provider,
          baseUrl = request.voiceGeneration.baseUrl,
          endpoint = request.voiceGeneration.endpoint,
          model = request.voiceGeneration.model,
          voicePreset = request.voiceGeneration.voicePreset,
          authProtocol = request.voiceGeneration.authProtocol,
          apiKey = request.voiceGeneration.apiKey.orEmpty(),
        ),
        sttRouteId = request.sttRouteId,
        externalStt = com.opencray.app.facade.media.MediaProviderSnapshot(
          provider = request.externalStt.provider,
          baseUrl = request.externalStt.baseUrl,
          endpoint = request.externalStt.endpoint,
          model = request.externalStt.model,
          authProtocol = request.externalStt.authProtocol,
          apiKey = request.externalStt.apiKey.orEmpty(),
        ),
        onDeviceModel = com.opencray.app.facade.media.OnDeviceSttSnapshot(
          modelPackage = request.onDeviceModel.modelPackage,
          downloadStatus = request.onDeviceModel.downloadStatus,
        ),
      )
    }

    private fun snapshot(
      imageGeneration: com.opencray.app.facade.media.MediaProviderSnapshot,
      videoGeneration: com.opencray.app.facade.media.MediaProviderSnapshot,
      voiceGeneration: com.opencray.app.facade.media.VoiceProviderSnapshot,
      sttRouteId: String,
      externalStt: com.opencray.app.facade.media.MediaProviderSnapshot,
      onDeviceModel: com.opencray.app.facade.media.OnDeviceSttSnapshot,
    ): com.opencray.app.facade.media.MediaSpeechConfigSnapshot =
      com.opencray.app.facade.media.MediaSpeechConfigSnapshot(
        localeTag = "en",
        title = "Media & Speech",
        subtitle = "Configure media APIs and STT routing.",
        imageGeneration = imageGeneration,
        videoGeneration = videoGeneration,
        voiceGeneration = voiceGeneration,
        sttRouteId = sttRouteId,
        externalStt = externalStt,
        onDeviceModel = onDeviceModel,
      )
  }

  internal class RecordingPersonalizationFacade :
    com.opencray.app.facade.personalization.PersonalizationFacade {
    var lastSavedRequest: com.opencray.app.facade.personalization.SavePersonalizationConfigRequest? = null
      private set
    var lastSetLanguageId: String? = null
      private set
    var lastResetScope: com.opencray.app.facade.personalization.PersonalizationResetScope? = null
      private set

    override fun load(): com.opencray.app.facade.personalization.PersonalizationConfigSnapshot = snapshot()

    override fun save(
      request: com.opencray.app.facade.personalization.SavePersonalizationConfigRequest,
    ): com.opencray.app.facade.personalization.PersonalizationConfigSnapshot {
      lastSavedRequest = request
      return snapshot(
        selectedPresetId = request.presetId,
        customLabel = request.customLabel,
        customGuidance = request.customGuidance,
      )
    }

    override fun setAppLanguage(
      languageId: String,
    ): com.opencray.app.facade.personalization.PersonalizationConfigSnapshot {
      lastSetLanguageId = languageId
      return snapshot(selectedAppLanguageId = languageId)
    }

    override fun reset(
      scope: com.opencray.app.facade.personalization.PersonalizationResetScope,
    ): com.opencray.app.facade.personalization.PersonalizationConfigSnapshot {
      lastResetScope = scope
      return snapshot(
        lastResetMessage = when (scope) {
          com.opencray.app.facade.personalization.PersonalizationResetScope.MEMORY ->
            "Memory reset staged."

          com.opencray.app.facade.personalization.PersonalizationResetScope.SOUL ->
            "Soul reset staged."
        },
      )
    }

    private fun snapshot(
      selectedPresetId: String = "steady",
      customLabel: String = "OpenCray",
      customGuidance: String = "",
      selectedAppLanguageId: String = "en",
      lastResetMessage: String? = null,
    ): com.opencray.app.facade.personalization.PersonalizationConfigSnapshot =
      com.opencray.app.facade.personalization.PersonalizationConfigSnapshot(
        title = "Personalization",
        subtitle = "Shape the assistant.",
        introTitle = "Intro",
        introBody = "Body",
        introHelper = "Helper",
        presetsTitle = "Presets",
        presetsHelper = "Preset helper",
        presets = listOf(
          com.opencray.app.facade.personalization.PersonalizationPresetSnapshot(
            id = "steady",
            title = "Steady",
            summary = "Balanced",
            voice = "Calm",
            status = if (selectedPresetId == "steady") "Selected" else "Available",
            isSelected = selectedPresetId == "steady",
          ),
          com.opencray.app.facade.personalization.PersonalizationPresetSnapshot(
            id = "warm",
            title = "Warm",
            summary = "Supportive",
            voice = "Warm",
            status = if (selectedPresetId == "warm") "Selected" else "Available",
            isSelected = selectedPresetId == "warm",
          ),
        ),
        selectedPresetId = selectedPresetId,
        customOverlayTitle = "Overlay",
        customOverlayHelper = "Overlay helper",
        customLabelHint = "Label",
        customLabelHelper = "Label helper",
        customGuidanceHint = "Guidance",
        customGuidanceHelper = "Guidance helper",
        customLabel = customLabel,
        customGuidance = customGuidance,
        behaviorDefaultsTitle = "Defaults",
        appLanguageTitle = "Language",
        appLanguageOptions = listOf(
          com.opencray.app.facade.personalization.PersonalizationLanguageOptionSnapshot(
            id = "en",
            title = "English",
            isSelected = selectedAppLanguageId == "en",
          ),
          com.opencray.app.facade.personalization.PersonalizationLanguageOptionSnapshot(
            id = "zh-CN",
            title = "简体中文",
            isSelected = selectedAppLanguageId == "zh-CN",
          ),
        ),
        selectedAppLanguageId = selectedAppLanguageId,
        livePreviewTitle = "Preview",
        livePreviewName = customLabel,
        livePreviewSummary = if (customGuidance.isBlank()) {
          "Default summary"
        } else {
          customGuidance
        },
        queueTitle = "Queue",
        queueBody = "Idle",
        queueIsIdle = true,
        lastResetTitle = "Last reset",
        lastResetMessage = lastResetMessage,
        resetActions = listOf(
          com.opencray.app.facade.personalization.PersonalizationResetActionSnapshot(
            scope = com.opencray.app.facade.personalization.PersonalizationResetScope.MEMORY,
            title = "Reset memory",
            scopeBody = "Clear memory.",
            retainBody = "Keep profile.",
            confirmationToken = "RESET MEMORY",
            inputHint = "Type RESET MEMORY",
            disabledGuidance = "Queue must be idle.",
            typeExactGuidance = "Type exact token.",
            armedGuidance = "Ready.",
            isInputEnabled = true,
          ),
          com.opencray.app.facade.personalization.PersonalizationResetActionSnapshot(
            scope = com.opencray.app.facade.personalization.PersonalizationResetScope.SOUL,
            title = "Reset soul",
            scopeBody = "Clear soul.",
            retainBody = "Keep memory.",
            confirmationToken = "RESET SOUL",
            inputHint = "Type RESET SOUL",
            disabledGuidance = "Queue must be idle.",
            typeExactGuidance = "Type exact token.",
            armedGuidance = "Ready.",
            isInputEnabled = true,
          ),
        ),
      )
  }

  internal class RecordingOnDeviceWarmupAccess : OnDeviceLlmWarmupAccess {
    var ensureWarmForActiveSessionCallCount: Int = 0
      private set

    override fun ensureWarmForSession(sessionId: String): OnDeviceLlmWarmupState =
      OnDeviceLlmWarmupState()

    override fun ensureWarmForActiveSession(): OnDeviceLlmWarmupState {
      ensureWarmForActiveSessionCallCount += 1
      return OnDeviceLlmWarmupState()
    }

    override fun clear(): OnDeviceLlmWarmupState = OnDeviceLlmWarmupState()
  }

  internal class RecordingSafetySettingsFacade : com.opencray.app.facade.safety.SafetySettingsFacade {
    var lastSavedRequest: com.opencray.app.facade.safety.SaveSafetySettingsRequest? = null
      private set

    override fun load(): com.opencray.app.facade.safety.SafetySettingsSnapshot = snapshot()

    override fun save(
      request: com.opencray.app.facade.safety.SaveSafetySettingsRequest,
    ): com.opencray.app.facade.safety.SafetySettingsSnapshot {
      lastSavedRequest = request
      return snapshot(
        automationModeId = request.automationModeId,
        rollbackJournalEnabled = request.rollbackJournalEnabled,
        maxFilesPerBatch = request.maxFilesPerBatch,
        maxAgentTurns = request.maxAgentTurns,
        maxToolCalls = request.maxToolCalls,
        undoWindowHours = request.undoWindowHours,
        fileChangesPolicyId = request.fileChangesPolicyId,
        fileDeletesPolicyId = request.fileDeletesPolicyId,
        shellCommandsPolicyId = request.shellCommandsPolicyId,
        externalAccessModeId = request.externalAccessModeId,
        photoLibraryEnabled = request.photoLibraryEnabled,
        downloadsEnabled = request.downloadsEnabled,
        documentsEnabled = request.documentsEnabled,
        recordingsEnabled = request.recordingsEnabled,
        workspaceAccessProfileId = request.workspaceAccessProfileId,
        readOnlyOutsideWorkspace = request.readOnlyOutsideWorkspace,
        liveContextModeId = request.liveContextModeId,
        memoryToolsEnabled = request.memoryToolsEnabled,
      )
    }

    private fun snapshot(
      automationModeId: String = "auto",
      rollbackJournalEnabled: Boolean = false,
      maxFilesPerBatch: Int = 5,
      maxAgentTurns: Int = 12,
      maxToolCalls: Int = 20,
      undoWindowHours: Int = 24,
      fileChangesPolicyId: String = "ask",
      fileDeletesPolicyId: String = "ask",
      shellCommandsPolicyId: String = "ask",
      externalAccessModeId: String = "allow",
      photoLibraryEnabled: Boolean = false,
      downloadsEnabled: Boolean = true,
      documentsEnabled: Boolean = true,
      recordingsEnabled: Boolean = false,
      workspaceAccessProfileId: String = "workspace_write",
      readOnlyOutsideWorkspace: Boolean = false,
      liveContextModeId: String = "full",
      memoryToolsEnabled: Boolean = true,
    ): com.opencray.app.facade.safety.SafetySettingsSnapshot =
      com.opencray.app.facade.safety.SafetySettingsSnapshot(
        automationMode = com.opencray.policy.SafetyAutomationMode.fromWireValue(automationModeId),
        rollbackJournalEnabled = rollbackJournalEnabled,
        maxFilesPerBatch = maxFilesPerBatch,
        maxAgentTurns = maxAgentTurns,
        maxToolCalls = maxToolCalls,
        undoWindowHours = undoWindowHours,
        fileChangesPolicy = com.opencray.policy.ToolPolicyOverride.fromWireValue(fileChangesPolicyId),
        fileDeletesPolicy = com.opencray.policy.ToolPolicyOverride.fromWireValue(fileDeletesPolicyId),
        shellCommandsPolicy = com.opencray.policy.ToolPolicyOverride.fromWireValue(shellCommandsPolicyId),
        externalAccessMode = com.opencray.policy.ExternalAccessMode.fromWireValue(externalAccessModeId),
        locations = listOf(
          com.opencray.app.facade.safety.SafetySettingsLocationSnapshot(
            id = "photo_library",
            enabled = photoLibraryEnabled,
          ),
          com.opencray.app.facade.safety.SafetySettingsLocationSnapshot(
            id = "downloads",
            enabled = downloadsEnabled,
          ),
          com.opencray.app.facade.safety.SafetySettingsLocationSnapshot(
            id = "documents",
            enabled = documentsEnabled,
          ),
          com.opencray.app.facade.safety.SafetySettingsLocationSnapshot(
            id = "recordings",
            enabled = recordingsEnabled,
          ),
        ),
        workspaceAccessProfile = com.opencray.policy.WorkspaceAccessProfile.fromWireValue(
          workspaceAccessProfileId,
        ),
        readOnlyOutsideWorkspace = readOnlyOutsideWorkspace,
        liveContextMode = LiveContextMode.fromWireValue(liveContextModeId),
        memoryToolsEnabled = memoryToolsEnabled,
      )
  }

  internal class RecordingMcpSettingsFacade : com.opencray.app.facade.mcp.McpSettingsFacade {
    var lastMasterEnabledValue: Boolean? = null
      private set
    var lastServerEnabledId: String? = null
      private set
    var lastServerEnabledValue: Boolean? = null
      private set

    override fun load(): com.opencray.app.facade.mcp.McpSettingsSnapshot =
      snapshot(masterEnabled = true, serverActionEnabled = false)

    override fun setMasterEnabled(enabled: Boolean): com.opencray.app.facade.mcp.McpSettingsSnapshot {
      lastMasterEnabledValue = enabled
      return snapshot(masterEnabled = enabled, serverActionEnabled = false)
    }

    override fun setServerEnabled(
      serverId: String,
      enabled: Boolean,
    ): com.opencray.app.facade.mcp.McpSettingsSnapshot {
      lastServerEnabledId = serverId
      lastServerEnabledValue = enabled
      return snapshot(masterEnabled = true, serverActionEnabled = enabled)
    }

    override fun currentExposureReport(): com.opencray.mcp.McpClientExposureReport =
      com.opencray.mcp.McpClientExposureReport(
        activeClients = emptyList(),
        blockedClients = emptyList(),
      )

    private fun snapshot(
      masterEnabled: Boolean,
      serverActionEnabled: Boolean,
    ): com.opencray.app.facade.mcp.McpSettingsSnapshot =
      com.opencray.app.facade.mcp.McpSettingsSnapshot(
        title = "MCP",
        subtitle = "Subtitle",
        masterTitle = "Master",
        masterSummary = "Summary",
        masterEnabled = masterEnabled,
        summaryLine = "1 active",
        serversTitle = "Servers",
        serversHelper = "Helper",
        masterDisabledTitle = if (masterEnabled) null else "Disabled",
        masterDisabledBody = if (masterEnabled) null else "Disabled body",
        servers = listOf(
          com.opencray.app.facade.mcp.McpServerSettingsSnapshot(
            id = "filesystem",
            title = "Filesystem",
            statusLabel = "Active",
            statusTone = "active",
            trustLine = "Trusted",
            authLine = "Ready",
            readinessLine = "Ready",
            transportLine = "Local",
            exposureLine = "Exposed",
            guidance = "Guidance",
            actionLabel = if (serverActionEnabled) "Disable" else "Enable",
            actionTurnsOn = !serverActionEnabled,
            isActionEnabled = serverActionEnabled,
          ),
        ),
      )
  }

  internal fun noOpRuntimeHostAccess(
    lifecycleDescriptor: HostRuntimeLifecycleDescriptor = HostRuntimeLifecycleDescriptor(),
  ): OpenCrayRuntimeHostAccess {
    val supplementStoreFactory = object : AgentSessionSupplementStoreFactory {
      private val stores = linkedMapOf<String, SessionSupplementStore>()

      override fun forChatSession(sessionId: String): SessionSupplementStore =
        stores.getOrPut(sessionId) { InMemorySessionSupplementStore() }
    }
    return DefaultOpenCrayRuntimeHostAccess(
      lifecycleDescriptor = lifecycleDescriptor,
      sessionRuntimeManager = NoOpAgentSessionRuntimeManager(),
      runEventJournalStoreFactory = inMemoryRunEventJournalStoreFactory(),
      promptCheckpointStoreFactory = inMemoryPromptCheckpointStoreFactory(),
      supplementStoreFactory = supplementStoreFactory,
      approvalRegistry = AgentTaskApprovalRegistry(),
    )
  }

  internal fun projectionStoreFor(
    snapshot: OpenCrayRuntimeServiceBridgeSnapshot,
  ): RuntimeServiceProjectionStore = inMemoryRuntimeServiceProjectionStore().apply {
    saveSnapshot(snapshot.toProjectionSnapshot())
  }

  internal class FilesDirBackedContext(
    private val resolvedFilesDir: java.io.File,
  ) : ContextWrapper(null) {
    override fun getApplicationContext(): Context = this

    override fun getFilesDir(): java.io.File = resolvedFilesDir
  }

  internal fun bridgeSnapshot(root: java.io.File): OpenCrayRuntimeServiceBridgeSnapshot {
    val runtimeOwnerLifecycle = HostRuntimeLifecycleDescriptor()
    val runtimeControllerLifecycle = RuntimeControllerLifecycleDescriptor(
      controllerInstanceId = "controller-bridge",
      durableControllerId = "controller-bridge-durable",
    )
    return OpenCrayRuntimeServiceBridgeSnapshot(
      runtimeOwnerLifecycle = runtimeOwnerLifecycle,
      runtimeOwnerWorkSummary = RuntimeOwnerWorkSummary(),
      runtimeControllerLifecycle = runtimeControllerLifecycle,
      serviceLifecycle = RuntimeServiceLifecycleDescriptor(),
      serviceWorkState = RuntimeServiceWorkStateTracker(
        workSummaryProvider = ::RuntimeOwnerWorkSummary,
      ).apply {
        refresh()
      }.currentState(),
      serviceKeepAliveState = RuntimeServiceKeepAliveState(
        phase = RuntimeServiceKeepAliveState.PHASE_CREATED,
        changedAtEpochMs = 1_000L,
      ),
      localRuntimeServerState = LocalRuntimeServerState(
        phase = LocalRuntimeServerState.PHASE_LISTENING,
        bindAddress = "127.0.0.1",
        requestedPort = 42_617,
        listeningPort = 42_617,
        lastStartedAtEpochMs = 900L,
        changedAtEpochMs = 1_000L,
      ),
    )
  }

  internal data class PendingApprovalServiceHostFixture(
    val serviceHost: OpenCrayRuntimeServiceHost,
    val sessionId: String,
    val runId: String,
    val taskId: String,
    val chatStore: ChatSessionLocalStore,
    val checkpointStore: PromptCheckpointStore,
    val journalStore: RunEventJournalStore,
    val handle: RecordingAgentSessionHandle,
    val approvalRegistry: AgentTaskApprovalRegistry,
    val subAgentReplayEvents: List<OpenCraySubAgentEvent>,
  )

  internal fun pendingApprovalServiceHostFixture(
    root: java.io.File,
    resumeRequestResult: Boolean = false,
    cancelRequestResult: Boolean = false,
    appendInterruptEvent: Boolean = false,
    includeQueueApprovalTask: Boolean = true,
    subAgentHandles: List<SubAgentHandleState> = emptyList(),
    resultMetadata: Map<String, String> = mapOf(
      "toolName" to "Bash",
      "canonicalToolName" to "bash",
    ),
    checkpointToolName: String = "bash",
  ): PendingApprovalServiceHostFixture {
    val chatStore = ChatSessionLocalStore(root.resolve("chat-session"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val runId = "pending-approval-run-1"
    val taskId = "pending-approval-task-1"
    val pendingMessageId = "pending-message-1"
    chatStore.appendMessage(sessionId, ChatTranscriptRole.USER, "Need approval")
    val queueSnapshot = SessionQueueSnapshot(
      sessionId = sessionId,
      agentId = "test-agent",
      lifecycleState = SessionLifecycleState.RUNNING,
      updatedAtEpochMs = 1_200L,
      tasks = if (includeQueueApprovalTask) {
        listOf(
          SessionQueueTaskSnapshot(
            enqueueOrder = 1L,
            task = AgentTask(
              id = taskId,
              type = AgentTaskType.PROMPT,
              input = "Need approval",
              state = AgentTaskState.SUSPENDED,
              policyDecision = PolicyDecision(
                outcome = com.opencray.core.contracts.PolicyDecisionOutcome.ALLOW,
                reasonCode = "test",
              ),
              createdAtEpochMs = 1_000L,
              updatedAtEpochMs = 1_200L,
              metadata = mapOf(
                AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID to runId,
                AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID to pendingMessageId,
              ),
            ),
            lifecycleState = QueueTaskLifecycleState.SUSPENDED,
            attempt = 1,
            lastErrorCode = "APPROVAL_REQUIRED",
            lastErrorMessage = "Approval is required before Bash can run.",
          ),
        )
      } else {
        emptyList()
      },
    )
    val runSnapshot = AgentRunSnapshot(
      sessionId = sessionId,
      runId = runId,
      taskId = taskId,
      acceptedAtEpochMs = 1_000L,
      updatedAtEpochMs = 1_200L,
      lifecycleState = if (includeQueueApprovalTask) QueueTaskLifecycleState.SUSPENDED else null,
      taskState = if (includeQueueApprovalTask) AgentTaskState.SUSPENDED else null,
      attempt = 1,
      executionOrdinal = 1,
      executionId = "execution-1",
      executionKind = "initial",
      executionStatus = ExecutionStatus.DENIED,
      errorCode = "APPROVAL_REQUIRED",
      errorMessage = "Approval is required before Bash can run.",
      resultMetadata = resultMetadata,
      pendingMessageId = pendingMessageId,
    )
    val runtimeManager = RecordingAgentSessionRuntimeManager()
    val handle = RecordingAgentSessionHandle(
      sessionId = sessionId,
      resumedSessionIds = runtimeManager.resumedSessionIds,
      runs = listOf(runSnapshot),
      queueSnapshot = queueSnapshot,
      cancelRequestResult = cancelRequestResult,
      resumeRequestResult = resumeRequestResult,
      initialSubAgentHandles = subAgentHandles,
    )
    runtimeManager.putHandle(handle)
    val runEventJournalStoreFactory = inMemoryRunEventJournalStoreFactory()
    val promptCheckpointStoreFactory = inMemoryPromptCheckpointStoreFactory()
    val checkpointStore = promptCheckpointStoreFactory.forChatSession(sessionId)
    checkpointStore.upsert(
      PersistedPromptCheckpoint(
        sessionId = sessionId,
        runId = runId,
        taskId = taskId,
        checkpointId = "checkpoint-waiting-approval",
        checkpointKind = PromptCheckpointKind.WAITING_APPROVAL,
        createdAtEpochMs = 1_200L,
        updatedAtEpochMs = 1_200L,
        toolName = checkpointToolName,
        pendingMessageId = pendingMessageId,
      ),
    )
    val journalStore = runEventJournalStoreFactory.forChatSession(sessionId)
    if (appendInterruptEvent) {
      journalStore.append(
        OpenCrayCancellationEvent(
          runId = runId,
          taskId = taskId,
          executionId = "execution-1",
          executionOrdinal = 1,
          executionKind = "initial",
          outcome = "user_interrupted",
          text = "Interrupted while waiting for approval.",
          emittedAtEpochMs = 1_300L,
        ),
      )
    }
    val supplementStoreFactory = object : AgentSessionSupplementStoreFactory {
      private val stores = linkedMapOf<String, SessionSupplementStore>()

      override fun forChatSession(sessionId: String): SessionSupplementStore =
        stores.getOrPut(sessionId) { InMemorySessionSupplementStore() }
    }
    val approvalRegistry = AgentTaskApprovalRegistry()
    val subAgentReplayEvents = mutableListOf<OpenCraySubAgentEvent>()
    val lifecycleDescriptor = HostRuntimeLifecycleDescriptor()
    val runtimeAccess = OpenCrayRuntimeOwnerAccess(
      lifecycleDescriptor = lifecycleDescriptor,
      hostAccess = DefaultOpenCrayRuntimeHostAccess(
        lifecycleDescriptor = lifecycleDescriptor,
        sessionRuntimeManager = runtimeManager,
        runEventJournalStoreFactory = runEventJournalStoreFactory,
        promptCheckpointStoreFactory = promptCheckpointStoreFactory,
        supplementStoreFactory = supplementStoreFactory,
        approvalRegistry = approvalRegistry,
      ),
      transcriptMessagesProvider = { emptyList() },
      memoryIngestionCoordinator = ChatMemoryIngestionCoordinator(
        memoryStore = InMemoryMemoryStore(),
      ),
      replayAccess = OpenCrayRuntimeReplayAccess(
        approvalRejectionRecorder = { _, _, _, _, _, _ -> },
        approvalApprovedRecorder = { _, _, _, _, _, _ -> },
        subAgentReplayRecorder = { _, event -> subAgentReplayEvents += event },
        runCancellationRecorder = { _, _, _, _, _ -> },
        terminalReplayRepairer = { _, _ -> },
      ),
    )
    val serviceHost = OpenCrayRuntimeServiceHost(
      dependencies = runtimeTestDependencies(
        root = root,
        chatStore = chatStore,
      ),
      runtimeAccess = runtimeAccess,
      serviceLifecycle = RuntimeServiceLifecycleDescriptor(),
      serviceWorkStateTracker = RuntimeServiceWorkStateTracker(
        workSummaryProvider = runtimeAccess.hostAccess::activeWorkSummary,
      ).apply { refresh() },
    )
    return PendingApprovalServiceHostFixture(
      serviceHost = serviceHost,
      sessionId = sessionId,
      runId = runId,
      taskId = taskId,
      chatStore = chatStore,
      checkpointStore = checkpointStore,
      journalStore = journalStore,
      handle = handle,
      approvalRegistry = approvalRegistry,
      subAgentReplayEvents = subAgentReplayEvents,
    )
  }

  internal fun runtimeTestDependencies(
    root: java.io.File,
    chatStore: ChatSessionLocalStore,
  ): OpenCrayRuntimeContextDependencies {
    val workspaceRoot = root.toPath()
    return OpenCrayRuntimeContextDependencies(
      appContext = ContextWrapper(null),
      localizedContext = ContextWrapper(null),
      llmSettingsStore = LlmSettingsStore(InMemoryLlmSettingsKeyValueStore()),
      sandboxSettingsRepository = testSandboxSettingsRepository(),
      personalizationStore = PersonalizationLocalStore(root.resolve("personalization")),
      chatSessionStore = chatStore,
      skillsFacade = EmptySkillsFacade,
      mcpSettingsFacade = EmptyMcpSettingsFacade,
      webSearchSettingsStore = WebSearchSettingsStore(InMemoryWebSearchSettingsKeyValueStore()),
      providerUserAgent = "OpenCrayRuntimeServiceHostTest",
      workspaceRootProvider = { workspaceRoot },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      voiceMetadataCacheStore = null,
      soulProfileStore = WorkspaceSoulProfileStore(),
      liveContextModeStore = LiveContextModeStore(InMemoryLiveContextModeKeyValueStore()),
      safetySettingsFacade = EmptySafetySettingsFacade,
      mediaSpeechSettingsStore = MediaSpeechSettingsStore(InMemoryMediaSpeechSettingsKeyValueStore()),
      approvedReadRootsProvider = {
        ApprovedReadRootsSnapshot(
          roots = setOf(workspaceRoot),
          summary = "workspace=${workspaceRoot.toString().replace('\\', '/')}",
        )
      },
      workspaceSnapshotProvider = { emptyMap() },
      runtimeServiceAccessGateway = DefaultRuntimeServiceAccessGateway(
        defaultRuntimeServiceAccessDependencies(),
      ),
      chatRuntimeWriteTargetResolverFactory = ChatRuntimeWriteTargetResolverFactory {
        object : ChatRuntimeWriteTargetResolver {
          override fun targetFor(command: OpenCrayChatWriteCommand): RuntimeServiceTarget =
            RuntimeServiceTarget.INTERACTIVE
        }
      },
    )
  }

  internal fun projectionOnlyChatStrings(): ProjectionOnlyChatStrings = ProjectionOnlyChatStrings(
    localeTag = "en",
    screenTitle = "Chat",
    modeLabel = "AUTO",
    sessionButtonLabel = "Sessions",
    recentSessionsEyebrow = "Recent sessions",
    recentSessionsTitle = "Recent sessions",
    newSessionLabel = "New session",
    defaultSessionTitle = "New chat",
    messagesBadge = { count -> "$count messages" },
    summaryReplyInProgress = "Reply in progress",
    summaryStartNewSession = "Start a new session",
    summaryRestored = "Restored",
    summaryApprovalRequired = "Approval required before the agent can continue.",
    approvalRequiredTitle = "Approval required",
    highRiskApprovalRequiredTitle = "High-risk approval required",
    highRiskApprovalRequiredBody =
      "High-risk approval required. Review this request carefully before approving.",
    approvalApproveLabel = "Approve",
    approvalApproveForSessionLabel = "Allow session",
    approvalRejectLabel = "Reject",
    summaryAwaitingDirection = "Waiting for your next instruction.",
    composerPlaceholder = "Message OpenCray",
    composerRejectedPlaceholder = "Message OpenCray differently",
  )

  internal fun waitForCondition(
    timeoutMs: Long,
    condition: () -> Boolean,
  ) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      if (condition()) {
        return
      }
      Thread.sleep(10L)
    }
    assertTrue("Condition was not met within ${timeoutMs}ms.", condition())
  }
}
