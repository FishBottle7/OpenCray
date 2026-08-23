package com.opencray.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.opencray.app.facade.llm.EmptyLlmConfigFacade
import com.opencray.app.facade.llm.LlmConfigFacade
import com.opencray.app.facade.llm.LlmConfigSnapshot
import com.opencray.app.facade.llm.LocalLlmConfigFacade
import com.opencray.app.facade.media.EmptyMediaSpeechSettingsFacade
import com.opencray.app.facade.media.LocalMediaSpeechSettingsFacade
import com.opencray.app.facade.media.MediaSpeechSettingsFacade
import com.opencray.runtime.memory.MemoryCandidateExtractor
import com.opencray.app.facade.mcp.EmptyMcpSettingsFacade
import com.opencray.app.facade.mcp.LocalMcpSettingsFacade
import com.opencray.app.facade.mcp.McpSettingsFacade
import com.opencray.app.facade.notifications.EmptyNotificationSettingsFacade
import com.opencray.app.facade.notifications.LocalNotificationSettingsFacade
import com.opencray.app.facade.notifications.NotificationSettingsFacade
import com.opencray.app.facade.personalization.EmptyPersonalizationFacade
import com.opencray.app.facade.personalization.LocalPersonalizationFacade
import com.opencray.app.facade.personalization.PersonalizationFacade
import com.opencray.app.facade.search.EmptyNetworkSearchConfigFacade
import com.opencray.app.facade.search.LocalNetworkSearchConfigFacade
import com.opencray.app.facade.search.NetworkSearchConfigFacade
import com.opencray.app.facade.safety.EmptySafetySettingsFacade
import com.opencray.app.facade.safety.LocalSafetySettingsFacade
import com.opencray.app.facade.safety.SafetySettingsFacade
import com.opencray.app.facade.safety.SafetySettingsSnapshot
import com.opencray.app.facade.skills.EmptySkillsFacade
import com.opencray.app.facade.skills.LocalSkillsFacade
import com.opencray.app.facade.skills.SkillsFacade
import com.opencray.app.facade.settings.LocalSettingsFacade
import com.opencray.app.facade.settings.SettingsFacade
import com.opencray.app.shell.AppShellStateStore
import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.ERROR_RESTART_REQUIRES_EXPLICIT_RETRY
import com.opencray.core.orchestrator.EXECUTION_KIND_CHECKPOINT_RESUME
import com.opencray.core.orchestrator.METADATA_EXECUTION_ID
import com.opencray.core.orchestrator.METADATA_EXECUTION_KIND
import com.opencray.core.orchestrator.METADATA_EXECUTION_ORDINAL
import com.opencray.core.orchestrator.QueueTaskLifecycleState
import com.opencray.core.orchestrator.RuntimeExecutionHooks
import com.opencray.llm.LiteLlmMetadataKeys
import com.opencray.persistence.model.ChatAttachmentEntry
import com.opencray.persistence.model.ChatAttachmentKind
import com.opencray.persistence.model.ChatTranscriptMessageEntry
import com.opencray.persistence.model.ChatTranscriptRole
import com.opencray.persistence.model.MemoryRecord
import com.opencray.persistence.security.CredentialRef
import com.opencray.persistence.security.SecretVault
import com.opencray.persistence.security.SecretVaultStorageClass
import com.opencray.persistence.security.SecretValue
import com.opencray.runtime.AgentToolCall
import com.opencray.runtime.AgentToolResult
import com.opencray.runtime.AgentToolResultStatus
import com.opencray.runtime.AgentTodoEntry
import com.opencray.runtime.AgentTodoStatus
import com.opencray.runtime.ERROR_LLM_RETRY_EXHAUSTED_AWAITING_RESUME
import com.opencray.runtime.OpenCrayApprovalEvent
import com.opencray.runtime.OpenCrayApprovalPhase
import com.opencray.runtime.OpenCrayAgentRunEvent
import com.opencray.runtime.OpenCrayAttachmentArtifact
import com.opencray.runtime.OpenCrayAttachmentArtifactMetadataKeys
import com.opencray.runtime.NoOpOpenCrayAgentRuntimeEventSink
import com.opencray.runtime.OpenCrayAssistantPhaseEvent
import com.opencray.runtime.OpenCrayAssistantEvent
import com.opencray.runtime.OpenCrayCancellationEvent
import com.opencray.runtime.OpenCrayExecutionMetadataKeys
import com.opencray.runtime.OpenCrayFinalAttachment
import com.opencray.runtime.OpenCrayLifecycleEvent
import com.opencray.runtime.OpenCrayMemoryRetrievalEvent
import com.opencray.runtime.OpenCrayMemoryWriteEvent
import com.opencray.runtime.OpenCrayPromptCheckpointBoundary
import com.opencray.runtime.OpenCrayPromptResumeMetadata
import com.opencray.runtime.OpenCrayPromptResumeState
import com.opencray.runtime.OpenCrayRunLifecyclePhase
import com.opencray.runtime.OpenCraySubAgentEvent
import com.opencray.runtime.OpenCraySubAgentPhase
import com.opencray.runtime.OpenCraySupplementEvent
import com.opencray.runtime.OpenCrayToolCallEvent
import com.opencray.runtime.OpenCrayToolResultEvent
import com.opencray.runtime.ProviderNativeWebSearchSupport
import com.opencray.runtime.TodoWriteMetadataKeys
import com.opencray.runtime.context.RuntimeConversationAttachment
import com.opencray.runtime.context.RuntimeConversationAttachmentKind
import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.context.RuntimeConversationMessageKind
import com.opencray.runtime.context.RuntimeConversationRole
import com.opencray.runtime.context.RuntimeSoulProfile
import com.opencray.policy.SafetyAutomationMode
import com.opencray.policy.SafetySettingsMetadataKeys
import com.opencray.runtime.memory.MemoryOperatorAction
import com.opencray.runtime.memory.MemoryRecordExtensionKeys
import com.opencray.runtime.process.ManagedProcessSnapshot
import com.opencray.runtime.subagent.SubAgentApprovalResume
import com.opencray.runtime.subagent.SubAgentContinuationKind
import com.opencray.runtime.subagent.SubAgentHandleState
import com.opencray.runtime.subagent.SubAgentExecutionState
import com.opencray.runtime.subagent.SubAgentSessionLink
import com.opencray.runtime.subagent.SubAgentLiveContextSnapshot
import com.opencray.runtime.soul.MemoryBackedSoulProfileResolver
import com.opencray.runtime.soul.RuntimeSoulProfileSeedFactory
import com.opencray.runtime.soul.SoulProfileResolver
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import com.opencray.app.projection.*
import org.opencray.app.R

private const val HOST_CHAT_DEBUG_TAG: String = "OpenCrayDiag"

private fun hostChatDebug(message: String) {
  runCatching { Log.d(HOST_CHAT_DEBUG_TAG, message) }
}

internal class OpenCrayHostRuntime internal constructor(
  internal val appContext: Context?,
  internal val stateStore: AppShellStateStore,
  internal val chatSessionStore: ChatSessionLocalStore,
  internal var settingsFacade: SettingsFacade,
  internal var notificationSettingsFacade: NotificationSettingsFacade,
  internal var networkSearchConfigFacade: NetworkSearchConfigFacade,
  internal var mediaSpeechSettingsFacade: MediaSpeechSettingsFacade,
  internal val sandboxSettingsRepository: SandboxSettingsRepository? =
    appContext?.let(SandboxSettingsRepository::fromContext),
  internal var llmConfigFacade: LlmConfigFacade,
  internal var personalizationFacade: PersonalizationFacade,
  internal val personalizationLocalStore: PersonalizationLocalStore? = null,
  private val workspaceSoulProfileStore: WorkspaceSoulProfileStore = WorkspaceSoulProfileStore(),
  internal var mcpSettingsFacade: McpSettingsFacade,
  internal var safetySettingsFacade: SafetySettingsFacade,
  internal var skillsFacade: SkillsFacade,
  private val workspaceRootProvider: (() -> Path)?,
  private val workspaceEntryOpener: ((Path, String) -> Unit)? = null,
  private val externalUriOpener: ((String) -> Unit)? = null,
  private val approvedReadRootsProvider: () -> ApprovedReadRootsSnapshot = {
    ApprovedReadRootsSnapshot(
      roots = emptySet(),
      summary = "workspace=unavailable",
    )
  },
  private val workspaceSnapshotProvider: () -> Map<String, Any?>,
  internal val strongBackgroundSettingsAccess: StrongBackgroundSettingsAccess =
    NoOpStrongBackgroundSettingsAccess,
  private val voiceMetadataAnalyzer: AppAgentWorkspaceVoiceMetadataAnalyzer,
  private val voiceMetadataBackfillExecutor: Executor,
  private val voiceMetadataCacheStore: AppAgentWorkspaceVoiceMetadataCacheStore? = null,
  internal val runtimeHostAccess: OpenCrayRuntimeHostAccess,
  private val subAgentSessionLinkStoreFactory: SubAgentSessionLinkStoreFactory =
    inMemorySubAgentSessionLinkStoreFactory(),
  private val todoSnapshotProvider: (String) -> ChatSessionTodoPresentation = {
    ChatSessionTodoPresentation.empty()
  },
  private val transcriptMessagesProvider: (String) -> List<RuntimeConversationMessage> = { emptyList() },
  private val directTaskRuntimeFactory: AgentSessionTaskRuntimeFactory? = null,
  private val memoryIngestionCoordinator: ChatMemoryIngestionCoordinator? = null,
  private val approvalReplayRecorder: (String, String, String, String?, Boolean, RuntimeReplayExecutionContext) -> Unit = { _, _, _, _, _, _ -> },
  private val approvalApprovedReplayRecorder: (String, String, String, String?, Boolean, RuntimeReplayExecutionContext) -> Unit = { _, _, _, _, _, _ -> },
  private val subAgentReplayRecorder: (String, OpenCraySubAgentEvent) -> Unit = { _, _ -> },
  private val runCancellationReplayRecorder: (String, String, String, String?, RuntimeReplayExecutionContext) -> Unit = { _, _, _, _, _ -> },
  private val terminalReplayRepairer: (String, List<AgentRunSnapshot>) -> Unit = { _, _ -> },
  internal var strings: HostRuntimeStrings,
  internal val mainThreadPoster: MainThreadPoster,
  private val providedOnDeviceLlmWarmupController: OnDeviceLlmWarmupController? = null,
  internal val localHostGateway: OpenCrayLocalHostGateway = DefaultOpenCrayLocalHostGateway(
    appContext = appContext,
    workspaceRootProvider = workspaceRootProvider,
    workspaceEntryOpener = workspaceEntryOpener,
    externalUriOpener = externalUriOpener,
    workspaceSnapshotProvider = workspaceSnapshotProvider,
    mainThreadPoster = mainThreadPoster,
  ),
  internal val lifecycleDescriptor: HostRuntimeLifecycleDescriptor,
  internal val runtimeDiagnosticsBridge: HostRuntimeDiagnosticsBridge =
    HostRuntimeDiagnosticsBridge(
      runtimeOwnerDescriptor = lifecycleDescriptor,
      localRuntimeServerStateProvider = { defaultLocalRuntimeServerState() },
    ),
  private val resumeActiveSessionOnInit: Boolean = false,
  private val chatUnreadMessageState: ChatUnreadMessageState = ChatUnreadMessageState(),
  private val chatPendingApprovalState: ChatPendingApprovalState = ChatPendingApprovalState(),
  internal val chatRuntimeEventState: ChatRuntimeEventState = ChatRuntimeEventState(),
) : OpenCrayLocalHostGateway,
  OpenCrayShellGateway,
  OpenCrayChatRuntimeGateway,
  OpenCraySkillsGateway,
  OpenCraySettingsGateway {
  internal val lock = Any()
  private var disposed: Boolean = false
  private var runtimeObservationDisposer: (() -> Unit)? = null
  private var runtimeDiagnosticsObservationDisposer: (() -> Unit)? = null
  private val liveAssistantDraftLock = Any()
  private val shellGateway = HostShellGatewayImpl(this)
  private val settingsGateway = HostSettingsGatewayImpl(this)
  private val hostLocalHostGateway = HostLocalHostGatewayImpl(this)
  private val skillsGateway = HostSkillsGatewayImpl(this)
  private val chatRuntimeGateway = HostChatRuntimeGatewayImpl(this)
  private val soulProfileResolver = SoulProfileResolver()
  private val runtimeSoulProfileSeedFactory = RuntimeSoulProfileSeedFactory()
  private val memoryBackedSoulProfileResolver = MemoryBackedSoulProfileResolver()
  private val liveAssistantDraftsBySession =
    linkedMapOf<String, LinkedHashMap<String, LiveAssistantDraftSnapshot>>()
  internal val chatDebugProjector = ProjectionOnlyChatDebugProjector(
    personalizationLocalStore = personalizationLocalStore,
    workspaceRootProvider = workspaceRootProvider?.let { provider -> { provider() } },
    workspaceSoulProfileStore = workspaceSoulProfileStore,
    soulProfileResolver = soulProfileResolver,
    runtimeSoulProfileSeedFactory = runtimeSoulProfileSeedFactory,
    memoryBackedSoulProfileResolver = memoryBackedSoulProfileResolver,
  )
  private val recoveryPlanner = RunRecoveryPlanner()
  internal val chatSessionMutationCoordinator = ChatSessionMutationCoordinator(
    chatSessionStore = chatSessionStore,
    runtimeHostAccess = runtimeHostAccess,
    chatUnreadMessageState = chatUnreadMessageState,
    pendingApprovalState = chatPendingApprovalState,
    runtimeEventState = chatRuntimeEventState,
    terminalReplayRepairer = terminalReplayRepairer,
    mediaGc = ::sweepWorkspaceChatMedia,
  )
  internal val chatSubmissionCoordinator = ChatSubmissionCoordinator(
    chatSessionStore = chatSessionStore,
    runtimeHostAccess = runtimeHostAccess,
    taskSafetyMetadataProvider = { safetyMetadataForTask(safetySettingsFacade.load()) },
    taskMetadataProvider = { submissionSource ->
      lifecycleDescriptor.taskMetadata(
        submissionSource = submissionSource,
      )
    },
    workspaceRootProvider = workspaceRootProvider,
    approvedReadRootsProvider = approvedReadRootsProvider,
    voiceMetadataAnalyzer = voiceMetadataAnalyzer,
    agentThinkingTextProvider = { strings.agentThinking },
  )
  internal val chatRunControlCoordinator = ChatRunControlCoordinator(
    runtimeHostAccess = runtimeHostAccess,
    findRunSnapshotForIdentifier = { runIdOrTaskId ->
      synchronized(lock) {
        findRunSnapshotForIdentifierLocked(runIdOrTaskId)
      }
    },
    pendingApprovalForRun = { run ->
      synchronized(lock) {
        pendingApprovalForIdentifier(run.sessionId, run.taskId)
      }
    },
    clearPendingApproval = { sessionId, taskId ->
      synchronized(lock) {
        clearPendingApprovalLocked(sessionId, taskId)
      }
    },
    clearApproval = { sessionId, taskId ->
      synchronized(lock) {
        clearApproval(sessionId, taskId)
      }
    },
    clearPromptCheckpoint = { sessionId, taskId ->
      synchronized(lock) {
        clearPromptCheckpointLocked(sessionId, taskId)
      }
    },
    recordRuntimeEvent = { sessionId, event ->
      synchronized(lock) {
        recordRuntimeEventLocked(sessionId, event)
      }
    },
    runCancellationReplayRecorder = runCancellationReplayRecorder,
    subAgentReplayRecorder = subAgentReplayRecorder,
    subAgentTerminalEventFactory = { approval, summary, emittedAtEpochMs ->
      approval.toApprovalDecisionRecord().subAgentTerminalEvent(
        summary = summary,
        emittedAtEpochMs = emittedAtEpochMs,
      )
    },
    cancellationEventFactory = { run, approval, emittedAtEpochMs ->
      synchronized(lock) {
        cancellationRuntimeEvent(
          run,
          approval,
          emittedAtEpochMs,
          localeIsChinese = isChineseHostLocale(),
        )
      }
    },
    delegatedChildCancelledWhileWaitingSummaryProvider = {
      synchronized(lock) {
        delegatedChildCancelledWhileWaitingSummary(localeIsChinese = isChineseHostLocale())
      }
    },
    nowEpochMsProvider = System::currentTimeMillis,
    hasRecordedCancellation = { run ->
      synchronized(lock) {
        chatRuntimeEventState.eventsForSession(run.sessionId).hasRecordedCancellationFor(run)
      }
    },
  )
  internal val chatApprovalDecisionCoordinator = ChatApprovalDecisionCoordinator(
    resolveApproval = ::findPendingApprovalMatchLocked,
    approvalSubject = { approvalMatch ->
      ApprovalDecisionSubject(
        sessionId = approvalMatch.sessionId,
        supportsSessionApproval = approvalMatch.approval.supportsSessionApproval,
        decisionRecord = approvalMatch.approval.toApprovalDecisionRecord(),
      )
    },
    shouldDeferDecisionUntilManualResume = { approvalMatch ->
      val approval = approvalMatch.approval
      val run = findRunSnapshotForIdentifierLocked(approval.taskId)
        ?: error("Run '${approval.runId}' is unavailable.")
      shouldDeferApprovalDecisionUntilManualResume(
        run = run,
        approval = approval,
      )
    },
    markApprovalApproved = { subject ->
      runtimeHostAccess.markApprovalApproved(
        sessionId = subject.sessionId,
        taskId = subject.taskId,
        toolName = subject.decisionRecord.resumeToolName ?: subject.decisionRecord.toolName,
        promptResumeState = subject.decisionRecord.promptResumeState,
        subAgentApprovalResume = subject.decisionRecord.subAgentApprovalResume,
      )
    },
    markApprovalRejected = { subject ->
      runtimeHostAccess.markApprovalRejected(
        sessionId = subject.sessionId,
        taskId = subject.taskId,
        toolName = subject.decisionRecord.resumeToolName ?: subject.decisionRecord.toolName,
        promptResumeState = subject.decisionRecord.promptResumeState,
        subAgentApprovalResume = subject.decisionRecord.subAgentApprovalResume,
      )
    },
    clearApproval = ::clearApproval,
    upsertCheckpoint = ::persistPromptCheckpointLocked,
    removeCheckpoint = ::clearPromptCheckpointLocked,
    requestResumeTask = { sessionId, taskId ->
      runtimeSession(sessionId).requestResumeTask(taskId)
    },
    requestCancel = { sessionId, taskId ->
      runtimeSession(sessionId).requestCancel(taskId)
    },
    recordApprovalApprovedReplay = { subject ->
      approvalApprovedReplayRecorder(
        subject.sessionId,
        subject.taskId,
        subject.runId,
        subject.decisionRecord.toolName,
        subject.decisionRecord.isHighRisk,
        subject.decisionRecord.replayExecutionContext(),
      )
    },
    recordApprovalRejectedReplay = { subject ->
      approvalReplayRecorder(
        subject.sessionId,
        subject.taskId,
        subject.runId,
        subject.decisionRecord.toolName,
        subject.decisionRecord.isHighRisk,
        subject.decisionRecord.replayExecutionContext(),
      )
    },
    recordApprovalResultEvent = ::recordRuntimeEventLocked,
    recordSubAgentEvent = { sessionId, event ->
      subAgentReplayRecorder(sessionId, event)
      recordRuntimeEventLocked(sessionId, event)
    },
    clearPendingApproval = ::clearPendingApprovalLocked,
    setSessionApprovalGranted = { sessionId, approved ->
      chatSessionStore.setNativeWebSearchSessionApproved(
        sessionId = sessionId,
        approved = approved,
      )
    },
    replacePendingMessageWithThinking = { sessionId, pendingMessageId, text ->
      chatSessionStore.replaceMessage(
        sessionId = sessionId,
        messageId = pendingMessageId,
        role = ChatTranscriptRole.ASSISTANT,
        text = text,
      )
    },
    appendToolMessage = { sessionId, text ->
      appendApprovalToolMessage(
        chatSessionStore = chatSessionStore,
        sessionId = sessionId,
        text = text,
      )
    },
    stringsProvider = {
      ApprovalDecisionStrings(
        agentThinking = strings.agentThinking,
        approvalApproved = strings.chatApprovalApproved,
        approvalApprovedForSession = strings.chatApprovalApprovedForSession,
        approvalRejected = strings.chatApprovalRejected,
        deferredApprovalApproved = deferredApprovalRecordedText(
          localeIsChinese = isChineseHostLocale(),
        ),
        deferredApprovalApprovedForSession = deferredApprovalRecordedForSessionText(
          localeIsChinese = isChineseHostLocale(),
        ),
        deferredApprovalRejected = deferredApprovalRejectedText(
          localeIsChinese = isChineseHostLocale(),
        ),
        delegatedChildApprovalApprovedSummary = delegatedChildApprovalApprovedSummary(
          localeIsChinese = isChineseHostLocale(),
        ),
        delegatedChildApprovalApprovedText = strings.chatApprovalApproved,
        delegatedChildApprovalRejectedSummary = delegatedChildApprovalRejectedStopSummary(
          localeIsChinese = isChineseHostLocale(),
        ),
      )
    },
    nowEpochMsProvider = System::currentTimeMillis,
  )
  internal val shellListeners = linkedSetOf<(Map<String, Any?>) -> Unit>()
  internal val settingsOverviewListeners = linkedSetOf<(Map<String, Any?>) -> Unit>()
  internal val skillsListeners = linkedSetOf<(Map<String, Any?>) -> Unit>()
  internal val chatListeners = linkedSetOf<(Map<String, Any?>) -> Unit>()
  internal val chatRuntimeListeners = linkedSetOf<(Map<String, Any?>) -> Unit>()
  internal val liveAssistantDraftEventListeners = linkedSetOf<(Map<String, Any?>) -> Unit>()
  internal val runtimeEventDeltaListeners = linkedSetOf<(Map<String, Any?>) -> Unit>()
  private val runtimeEventStreamInstanceId: String = lifecycleId(prefix = "runtime-stream")
  private val runtimeEventDeltaSequencesBySession = linkedMapOf<String, Long>()
  private val onDeviceLlmWarmupController: OnDeviceLlmWarmupController =
    providedOnDeviceLlmWarmupController ?: defaultOnDeviceLlmWarmupController()
  private val voiceMetadataBackfillInFlight = ConcurrentHashMap.newKeySet<String>()

  init {
    runtimeObservationDisposer = runtimeHostAccess.observe(
      object : AgentSessionRuntimeListener {
        override fun onTaskStarted(sessionId: String, task: AgentTask) {
          val emission = synchronized(lock) {
            if (!hasSessionLocked(sessionId)) {
              return@synchronized EventEmissionDecision(shouldEmit = false)
            }
            EventEmissionDecision(
              shouldEmit = true,
              emitRuntimeEventDelta = runtimeEventDeltaListeners.isNotEmpty(),
              emitRuntimeSnapshotFallback = runtimeEventDeltaListeners.isEmpty(),
            )
          }
          if (!emission.shouldEmit) {
            return
          }
          emitShellSnapshot()
          when {
            emission.emitRuntimeEventDelta -> {
              buildRuntimeTaskDeltaPayload(
                sessionId = sessionId,
                task = task,
                sequence = 0L,
              )?.let { payload ->
                emitRuntimeEventDelta(assignRuntimeEventDeltaSequence(sessionId, payload))
              }
            }

            emission.emitRuntimeSnapshotFallback -> {
              emitChatSnapshot()
              emitChatRuntimeSnapshot()
            }
          }
        }

        override fun onTaskFinished(sessionId: String, task: AgentTask, result: ExecutionResult) {
          val pendingMessageId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID]
            ?.takeIf(String::isNotBlank)
          val completedTurn = synchronized(lock) {
            if (!hasSessionLocked(sessionId)) {
              return@synchronized null
            }
            clearAssistantDraftLocked(
              sessionId = sessionId,
              pendingMessageId = pendingMessageId,
            )
            val activeSessionIdBeforeUpdate = chatSessionStore.loadState().activeSession.sessionId
            val baseFinalText = finalTextForLocked(
              sessionId = sessionId,
              task = task,
              result = result,
            )
            val markdownCompatibility = attachmentMarkdownCompatibilityLocked(
              sessionId = sessionId,
              task = task,
              text = baseFinalText,
            )
            val finalAttachmentArchive = finalAttachmentArchiveForResultLocked(
              sessionId = sessionId,
              task = task,
              result = result,
              compatibilityAttachments = markdownCompatibility.attachments,
            )
            val finalAttachments = finalAttachmentArchive.attachments
            val finalText = finalizedAssistantText(
              text = markdownCompatibility.rewrittenText,
              attachments = finalAttachments,
              attachmentFailureText = finalAttachmentArchive.failureText,
            )
            val llmRetryPausedResult = isLlmRetryPausedResult(result)
            if (llmRetryPausedResult) {
              persistGeneralResumeCheckpointFromResultLocked(
                sessionId = sessionId,
                task = task,
                result = result,
              )
            }
            if (isApprovalRequiredResult(result)) {
              val approval = recordPendingApprovalLocked(
                sessionId = sessionId,
                task = task,
                result = result,
              )
              recordRuntimeEventLocked(
                sessionId = sessionId,
                event = approvalRequiredRuntimeEvent(
                  approval = approval,
                  emittedAtEpochMs = result.finishedAtEpochMs,
                ),
              )
            } else {
              clearPendingApprovalLocked(sessionId = sessionId, taskId = task.id)
              clearApproval(sessionId = sessionId, taskId = task.id)
              if (!llmRetryPausedResult) {
                clearPromptCheckpointLocked(sessionId = sessionId, taskId = task.id)
              }
            }
            pendingMessageId?.let { messageId ->
              chatSessionStore.replaceMessage(
                sessionId = sessionId,
                messageId = messageId,
                role = ChatTranscriptRole.ASSISTANT,
                text = finalText,
                attachments = finalAttachments,
              )
            }
            incrementUnreadIfBackgroundUpdateLocked(
              sessionId = sessionId,
              activeSessionId = activeSessionIdBeforeUpdate,
              text = finalText,
              attachments = finalAttachments,
            )
            CompletedTurnForMemoryIngestion(
              sessionId = sessionId,
              task = task,
              result = result,
              userInput = resolvedUserTextLocked(
                sessionId = sessionId,
                pendingMessageId = pendingMessageId,
                task = task,
              ),
              assistantOutput = finalText,
              attachments = finalAttachments,
              toolObservations = successfulToolObservationsLocked(sessionId = sessionId, task = task),
            )
          }
          if (completedTurn == null) {
            return
          }
          scheduleVoiceMetadataBackfill(completedTurn.attachments)
          val ingestionSummary = runCatching {
            memoryIngestionCoordinator?.ingestCompletedTurn(
              sessionId = completedTurn.sessionId,
              task = completedTurn.task,
              result = completedTurn.result,
              userInput = completedTurn.userInput,
              assistantOutput = completedTurn.assistantOutput,
              toolObservations = completedTurn.toolObservations,
            )
          }.getOrNull()
          if (ingestionSummary != null && !ingestionSummary.isEmpty) {
            synchronized(lock) {
              if (!hasSessionLocked(completedTurn.sessionId)) {
                return@synchronized
              }
              recordRuntimeEventLocked(
                sessionId = completedTurn.sessionId,
                event = OpenCrayMemoryWriteEvent(
                  runId = runIdFor(completedTurn.task),
                  taskId = completedTurn.task.id,
                  executionId = executionIdFromMetadata(completedTurn.result.metadata),
                  executionOrdinal = executionOrdinalFromMetadata(completedTurn.result.metadata),
                  executionKind = executionKindFromMetadata(completedTurn.result.metadata),
                  writtenRecordIds = ingestionSummary.writtenRecords.map { record -> record.id },
                  writtenKinds = ingestionSummary.writtenRecords.mapNotNull { record ->
                    record.extensions["kind"]
                  }.distinct().sorted(),
                  resolvedRecordIds = ingestionSummary.resolvedRecords.map { record -> record.id },
                  reopenedRecordIds = ingestionSummary.reopenedRecords.map { record -> record.id },
                  reaffirmedRecordIds = ingestionSummary.reaffirmedRecords.map { record -> record.id },
                  expiredRecordIds = ingestionSummary.expiredRecordIds,
                  stewardshipPlanSteps = ingestionSummary.stewardshipPlanSteps,
                  stewardshipPlanGraph = ingestionSummary.stewardshipPlanGraph,
                  emittedAtEpochMs = completedTurn.result.finishedAtEpochMs,
                ),
              )
            }
          }
          val emission = synchronized(lock) {
            if (!hasSessionLocked(completedTurn.sessionId)) {
              return@synchronized EventEmissionDecision(shouldEmit = false)
            }
            val completedRunId = runIdFor(completedTurn.task)
            if (
              isApprovalRequiredResult(completedTurn.result) ||
              !runStillAcceptsSupplementsLocked(completedTurn.sessionId, completedRunId)
            ) {
              promoteSupplementsForRunLocked(
                sessionId = completedTurn.sessionId,
                runId = completedRunId,
                taskId = completedTurn.task.id,
              )
            }
            startNextQueuedChatRunLocked(completedTurn.sessionId)
            EventEmissionDecision(
              shouldEmit = true,
              emitRuntimeEventDelta = runtimeEventDeltaListeners.isNotEmpty(),
              emitRuntimeSnapshotFallback = runtimeEventDeltaListeners.isEmpty(),
            )
          }
          if (!emission.shouldEmit) {
            return
          }
          repairTerminalReplay(completedTurn.sessionId)
          emitShellSnapshot()
          emitChatSnapshot()
          when {
            emission.emitRuntimeEventDelta -> {
              buildRuntimeTaskDeltaPayload(
                sessionId = completedTurn.sessionId,
                task = completedTurn.task,
                sequence = 0L,
              )?.let { payload ->
                emitRuntimeEventDelta(assignRuntimeEventDeltaSequence(completedTurn.sessionId, payload))
              }
            }

            emission.emitRuntimeSnapshotFallback -> emitChatRuntimeSnapshot()
          }
        }

        override fun onRunEvent(sessionId: String, task: AgentTask, event: OpenCrayAgentRunEvent) {
          val emission = synchronized(lock) {
            if (!hasSessionLocked(sessionId)) {
              return@synchronized EventEmissionDecision(shouldEmit = false)
            }
            recordRuntimeEventLocked(sessionId = sessionId, event = event)
            maybePersistAssistantPhaseChatMessageLocked(
              sessionId = sessionId,
              task = task,
              event = event,
            )
            maybePersistGeneralResumeCheckpointLocked(
              sessionId = sessionId,
              task = task,
              event = event,
            )
            EventEmissionDecision(
              shouldEmit = true,
              emitRuntimeEventDelta =
                runtimeEventDeltaListeners.isNotEmpty() &&
                  shouldEmitRuntimeEventDelta(event),
              emitRuntimeSnapshotFallback = runtimeEventDeltaListeners.isEmpty(),
            )
          }
          if (!emission.shouldEmit) {
            return
          }
          when {
            emission.emitRuntimeEventDelta -> {
              buildRuntimeTaskDeltaPayload(
                sessionId = sessionId,
                task = task,
                sequence = 0L,
                event = event,
              )?.let { payload ->
                emitRuntimeEventDelta(assignRuntimeEventDeltaSequence(sessionId, payload))
              }
            }

            emission.emitRuntimeSnapshotFallback && shouldEmitRuntimeEventDelta(event) ->
              emitChatRuntimeSnapshot()
          }
        }

        override fun onAssistantDraftUpdated(
          sessionId: String,
          task: AgentTask,
          text: String,
          emittedAtEpochMs: Long,
        ) {
          val shouldAccept = synchronized(lock) {
            if (!hasSessionLocked(sessionId)) {
              return@synchronized false
            }
            true
          }
          if (!shouldAccept) {
            return
          }
          val draftEventPayload = updateAssistantDraft(
            sessionId = sessionId,
            task = task,
            text = text,
            emittedAtEpochMs = emittedAtEpochMs,
          )
            ?.toLiveAssistantDraftEventPayload(sessionId = sessionId, cleared = false)
            ?.let { payload -> assignRuntimeRealtimeEnvelope(sessionId = sessionId, payload = payload) }
          if (draftEventPayload != null) {
            emitLiveAssistantDraftEvent(draftEventPayload)
          }
        }

        override fun onAssistantDraftCleared(
          sessionId: String,
          task: AgentTask,
          emittedAtEpochMs: Long,
        ) {
          val shouldAccept = synchronized(lock) {
            if (!hasSessionLocked(sessionId)) {
              return@synchronized false
            }
            true
          }
          if (!shouldAccept) {
            return
          }
          val pendingMessageId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID]
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: return
          val draftEventPayload = if (
            clearAssistantDraft(
              sessionId = sessionId,
              pendingMessageId = pendingMessageId,
            )
          ) {
            liveAssistantDraftEventPayload(
              sessionId = sessionId,
              runId = runIdFor(task),
              taskId = task.id,
              executionId = executionIdFromMetadata(task.metadata),
              pendingMessageId = pendingMessageId,
              text = "",
              updatedAtEpochMs = emittedAtEpochMs,
              cleared = true,
            )
          } else {
            null
          }
          if (draftEventPayload != null) {
            emitLiveAssistantDraftEvent(
              assignRuntimeRealtimeEnvelope(sessionId = sessionId, payload = draftEventPayload),
            )
          }
        }

        override fun onToolResult(
          sessionId: String,
          task: AgentTask,
          turn: Int,
          call: AgentToolCall,
          result: AgentToolResult,
        ) {
          if (!call.toolName.equals("TodoWrite", ignoreCase = true)) {
            return
          }
          val shouldEmit = synchronized(lock) { hasSessionLocked(sessionId) }
          if (!shouldEmit) {
            return
          }
          emitChatSnapshot()
        }
      },
    )
    runtimeDiagnosticsObservationDisposer = runtimeDiagnosticsBridge.registerSnapshotObservers(
      emitShellSnapshot = ::emitShellSnapshot,
      emitChatRuntimeSnapshot = ::emitChatRuntimeSnapshot,
    )
    if (resumeActiveSessionOnInit) {
      ensureActiveSessionResumed()
    }
    scheduleStartupOnDeviceWarmup()
  }

  override fun loadShellSnapshot(): Map<String, Any?> = shellGateway.loadShellSnapshot()

  override fun observeShell(listener: (Map<String, Any?>) -> Unit): () -> Unit =
    shellGateway.observeShell(listener)

  override fun saveShellDestination(
    selectedTab: String,
    settingsSubpage: String?,
  ) {
    shellGateway.saveShellDestination(selectedTab, settingsSubpage)
  }

  override fun loadSettingsOverview(): Map<String, Any?> = settingsGateway.loadSettingsOverview()

  override fun observeSettingsOverview(listener: (Map<String, Any?>) -> Unit): () -> Unit =
    settingsGateway.observeSettingsOverview(listener)

  override fun loadSettingsDetail(routeIdRaw: String): Map<String, Any?> =
    settingsGateway.loadSettingsDetail(routeIdRaw)

  override fun loadNotificationSettings(): Map<String, Any?> =
    settingsGateway.loadNotificationSettings()

  override fun saveNotificationSettings(payload: Map<String, Any?>): Map<String, Any?> =
    settingsGateway.saveNotificationSettings(payload)

  override fun loadScheduledTasks(): Map<String, Any?> =
    settingsGateway.loadScheduledTasks()

  override fun loadScheduledTask(scheduleId: String): Map<String, Any?> =
    settingsGateway.loadScheduledTask(scheduleId)

  override fun updateScheduledTaskEnabled(
    scheduleId: String,
    enabled: Boolean,
  ): Map<String, Any?> = settingsGateway.updateScheduledTaskEnabled(
    scheduleId = scheduleId,
    enabled = enabled,
  )

  override fun runScheduledTaskNow(scheduleId: String): Map<String, Any?> =
    settingsGateway.runScheduledTaskNow(scheduleId)

  override fun snoozeScheduledTask(
    scheduleId: String,
    durationMinutes: Int,
  ): Map<String, Any?> = settingsGateway.snoozeScheduledTask(
    scheduleId = scheduleId,
    durationMinutes = durationMinutes,
  )

  override fun loadStrongBackgroundSnapshot(): Map<String, Any?> =
    settingsGateway.loadStrongBackgroundSnapshot()

  override fun performStrongBackgroundAction(actionId: String): Map<String, Any?> =
    settingsGateway.performStrongBackgroundAction(actionId)

  override fun loadNetworkSearchConfig(): Map<String, Any?> =
    settingsGateway.loadNetworkSearchConfig()

  override fun saveNetworkSearchConfig(
    slots: List<Map<String, Any?>>,
  ): Map<String, Any?> = settingsGateway.saveNetworkSearchConfig(slots)

  override fun loadMediaSpeechConfig(): Map<String, Any?> =
    settingsGateway.loadMediaSpeechConfig()

  override fun saveMediaSpeechConfig(
    payload: Map<String, Any?>,
  ): Map<String, Any?> = settingsGateway.saveMediaSpeechConfig(payload)

  override fun loadSandboxSettings(): Map<String, Any?> =
    settingsGateway.loadSandboxSettings()

  override fun saveSandboxSettings(payload: Map<String, Any?>): Map<String, Any?> =
    settingsGateway.saveSandboxSettings(payload)

  override fun loadLlmConfig(): Map<String, Any?> = settingsGateway.loadLlmConfig()

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
  ): Map<String, Any?> = settingsGateway.saveLlmConfig(
    enabled = enabled,
    streamingEnabled = streamingEnabled,
    providerMode = providerMode,
    providerId = providerId,
    selectedProviderOptionId = selectedProviderOptionId,
    protocol = protocol,
    providerName = providerName,
    providerNotes = providerNotes,
    baseUrl = baseUrl,
    apiKey = apiKey,
    model = model,
    reasoningEffort = reasoningEffort,
    systemPrompt = systemPrompt,
    openAiPromptCacheKeyStrategy = openAiPromptCacheKeyStrategy,
    openAiPromptCacheRetention = openAiPromptCacheRetention,
    anthropicPromptCachingEnabled = anthropicPromptCachingEnabled,
    anthropicPromptCacheTtl = anthropicPromptCacheTtl,
    contextBudgetPreset = contextBudgetPreset,
    contextBudgetReservedOutputTokens = contextBudgetReservedOutputTokens,
    contextBudgetSafetyMarginTokens = contextBudgetSafetyMarginTokens,
    contextBudgetEffectiveInputPercent = contextBudgetEffectiveInputPercent,
    selectedOnDeviceModelId = selectedOnDeviceModelId,
    onDeviceMaxContextWindow = onDeviceMaxContextWindow,
    onDeviceMaxTokens = onDeviceMaxTokens,
    onDeviceTopK = onDeviceTopK,
    onDeviceTopP = onDeviceTopP,
    onDeviceTemperature = onDeviceTemperature,
    onDeviceAccelerator = onDeviceAccelerator,
    onDeviceThinkingEnabled = onDeviceThinkingEnabled,
    onDeviceLiteModeEnabled = onDeviceLiteModeEnabled,
    contextWindowTokensOverride = contextWindowTokensOverride,
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
  ): Map<String, Any?> = settingsGateway.saveCustomLlmProvider(
    selectedProviderOptionId = selectedProviderOptionId,
    streamingEnabled = streamingEnabled,
    protocol = protocol,
    providerName = providerName,
    providerNotes = providerNotes,
    baseUrl = baseUrl,
    apiKey = apiKey,
    model = model,
    reasoningEffort = reasoningEffort,
    systemPrompt = systemPrompt,
    openAiPromptCacheKeyStrategy = openAiPromptCacheKeyStrategy,
    openAiPromptCacheRetention = openAiPromptCacheRetention,
    anthropicPromptCachingEnabled = anthropicPromptCachingEnabled,
    anthropicPromptCacheTtl = anthropicPromptCacheTtl,
    contextBudgetPreset = contextBudgetPreset,
    contextBudgetReservedOutputTokens = contextBudgetReservedOutputTokens,
    contextBudgetSafetyMarginTokens = contextBudgetSafetyMarginTokens,
    contextBudgetEffectiveInputPercent = contextBudgetEffectiveInputPercent,
    contextWindowTokensOverride = contextWindowTokensOverride,
  )

  override fun validateLlmConfig(
    providerId: String,
    protocol: String,
    baseUrl: String,
    apiKey: String,
    model: String,
    reasoningEffort: String,
    contextWindowTokensOverride: Int?,
  ): Map<String, Any?> = settingsGateway.validateLlmConfig(
    providerId = providerId,
    protocol = protocol,
    baseUrl = baseUrl,
    apiKey = apiKey,
    model = model,
    reasoningEffort = reasoningEffort,
    contextWindowTokensOverride = contextWindowTokensOverride,
  )

  override fun downloadOnDeviceLlmModel(modelId: String): Map<String, Any?> =
    settingsGateway.downloadOnDeviceLlmModel(modelId)

  override fun cancelOnDeviceLlmModelDownload(modelId: String): Map<String, Any?> =
    settingsGateway.cancelOnDeviceLlmModelDownload(modelId)

  override fun deleteOnDeviceLlmModel(modelId: String): Map<String, Any?> =
    settingsGateway.deleteOnDeviceLlmModel(modelId)

  override fun loadPersonalizationConfig(): Map<String, Any?> =
    settingsGateway.loadPersonalizationConfig()

  override fun savePersonalizationConfig(
    presetId: String,
    customLabel: String,
    customGuidance: String,
  ): Map<String, Any?> = settingsGateway.savePersonalizationConfig(
    presetId = presetId,
    customLabel = customLabel,
    customGuidance = customGuidance,
  )

  override fun setAppLanguage(languageId: String): Map<String, Any?> =
    settingsGateway.setAppLanguage(languageId)

  override fun runPersonalizationReset(scopeId: String): Map<String, Any?> =
    settingsGateway.runPersonalizationReset(scopeId)

  override fun probeTwinImportSource(filePath: String): Map<String, Any?> =
    hostLocalHostGateway.probeTwinImportSource(filePath)

  override fun loadMcpSettings(): Map<String, Any?> =
    settingsGateway.loadMcpSettings()

  override fun setMcpMasterEnabled(enabled: Boolean): Map<String, Any?> =
    settingsGateway.setMcpMasterEnabled(enabled)

  override fun setMcpServerEnabled(
    serverId: String,
    enabled: Boolean,
  ): Map<String, Any?> = settingsGateway.setMcpServerEnabled(
    serverId = serverId,
    enabled = enabled,
  )

  override fun loadSafetySettings(): Map<String, Any?> =
    settingsGateway.loadSafetySettings()

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
  ): Map<String, Any?> = settingsGateway.saveSafetySettings(
    automationModeId = automationModeId,
    rollbackJournalEnabled = rollbackJournalEnabled,
    maxFilesPerBatch = maxFilesPerBatch,
    maxAgentTurns = maxAgentTurns,
    maxToolCalls = maxToolCalls,
    undoWindowHours = undoWindowHours,
    fileChangesPolicyId = fileChangesPolicyId,
    fileDeletesPolicyId = fileDeletesPolicyId,
    shellCommandsPolicyId = shellCommandsPolicyId,
    externalAccessModeId = externalAccessModeId,
    photoLibraryEnabled = photoLibraryEnabled,
    downloadsEnabled = downloadsEnabled,
    documentsEnabled = documentsEnabled,
    recordingsEnabled = recordingsEnabled,
    workspaceAccessProfileId = workspaceAccessProfileId,
    readOnlyOutsideWorkspace = readOnlyOutsideWorkspace,
    liveContextModeId = liveContextModeId,
    memoryToolsEnabled = memoryToolsEnabled,
    subAgentContextDefaultModeId = subAgentContextDefaultModeId,
    subAgentContextProfileOverrides = subAgentContextProfileOverrides,
  )

  override fun loadSkillsSnapshot(
    query: String,
    suggestedLimit: Int,
  ): Map<String, Any?> = skillsGateway.loadSkillsSnapshot(
    query = query,
    suggestedLimit = suggestedLimit,
  )

  override fun observeSkills(listener: (Map<String, Any?>) -> Unit): () -> Unit =
    skillsGateway.observeSkills(listener)

  override fun loadFilesSnapshot(): Map<String, Any?> =
    hostLocalHostGateway.loadFilesSnapshot()

  override fun loadWorkspaceImagePreview(relativePath: String): Map<String, Any?> =
    hostLocalHostGateway.loadWorkspaceImagePreview(relativePath)

  override fun loadWorkspaceTextPreview(relativePath: String): Map<String, Any?> =
    hostLocalHostGateway.loadWorkspaceTextPreview(relativePath)

  override fun loadWorkspaceVoicePlaybackSource(relativePath: String): Map<String, Any?> =
    hostLocalHostGateway.loadWorkspaceVoicePlaybackSource(relativePath)

  override fun loadWorkspaceTextDocument(relativePath: String): Map<String, Any?> =
    hostLocalHostGateway.loadWorkspaceTextDocument(relativePath)

  override fun openWorkspaceEntry(relativePath: String) {
    hostLocalHostGateway.openWorkspaceEntry(relativePath)
  }

  override fun openExternalUri(uri: String) {
    hostLocalHostGateway.openExternalUri(uri)
  }

  override fun copyRichTextToClipboard(plainText: String, htmlText: String?) {
    hostLocalHostGateway.copyRichTextToClipboard(plainText = plainText, htmlText = htmlText)
  }

  override fun createWorkspaceFolder(
    parentRelativePath: String,
    name: String,
  ): Map<String, Any?> = hostLocalHostGateway.createWorkspaceFolder(parentRelativePath, name)

  override fun createWorkspaceTextFile(
    parentRelativePath: String,
    name: String,
  ): Map<String, Any?> = hostLocalHostGateway.createWorkspaceTextFile(parentRelativePath, name)

  override fun renameWorkspaceEntry(
    targetRelativePath: String,
    newName: String,
  ): Map<String, Any?> = hostLocalHostGateway.renameWorkspaceEntry(targetRelativePath, newName)

  override fun deleteWorkspaceEntries(relativePaths: List<String>): Map<String, Any?> =
    hostLocalHostGateway.deleteWorkspaceEntries(relativePaths)

  override fun saveWorkspaceTextDocument(
    targetRelativePath: String,
    content: String,
  ): Map<String, Any?> = hostLocalHostGateway.saveWorkspaceTextDocument(targetRelativePath, content)

  override fun pasteWorkspaceEntries(
    sourceRelativePaths: List<String>,
    destinationRelativePath: String,
    move: Boolean,
  ): Map<String, Any?> = hostLocalHostGateway.pasteWorkspaceEntries(
    sourceRelativePaths = sourceRelativePaths,
    destinationRelativePath = destinationRelativePath,
    move = move,
  )

  override fun shareWorkspaceEntries(relativePaths: List<String>) {
    hostLocalHostGateway.shareWorkspaceEntries(relativePaths)
  }

  override fun saveWorkspaceMediaAttachment(
    relativePath: String,
    kind: String,
  ): Map<String, Any?> = hostLocalHostGateway.saveWorkspaceMediaAttachment(relativePath, kind)

  override fun showNativeToast(message: String) {
    hostLocalHostGateway.showNativeToast(message)
  }

  override fun setSkillEnabled(skillId: String, enabled: Boolean) {
    skillsGateway.setSkillEnabled(skillId = skillId, enabled = enabled)
  }

  override fun installSuggestedSkill(skillId: String): String =
    skillsGateway.installSuggestedSkill(skillId)

  override fun installSkillSource(
    sourceRef: String,
    selectedSkillName: String,
  ): String = skillsGateway.installSkillSource(
    sourceRef = sourceRef,
    selectedSkillName = selectedSkillName,
  )

  override fun installSkillSourceBatch(
    sourceRef: String,
    selectedSkillNames: List<String>,
  ): String = skillsGateway.installSkillSourceBatch(
    sourceRef = sourceRef,
    selectedSkillNames = selectedSkillNames,
  )

  override fun inspectSkillSource(sourceRef: String): Map<String, Any?> =
    skillsGateway.inspectSkillSource(sourceRef)

  override fun deleteInstalledSkill(skillId: String): String =
    skillsGateway.deleteInstalledSkill(skillId)

  override fun refreshSkills(): String =
    skillsGateway.refreshSkills()

  override fun checkInstalledSkillUpdates(skillId: String): String =
    skillsGateway.checkInstalledSkillUpdates(skillId)

  override fun updateInstalledSkill(skillId: String): String =
    skillsGateway.updateInstalledSkill(skillId)

  override fun loadSkillInstructions(skillId: String): Map<String, Any?> =
    skillsGateway.loadSkillInstructions(skillId)

  override fun loadSuggestedSkillInstructions(
    sourceRef: String,
    selectedSkillName: String,
  ): Map<String, Any?> = skillsGateway.loadSuggestedSkillInstructions(
    sourceRef = sourceRef,
    selectedSkillName = selectedSkillName,
  )

  override fun activateSkillsInstallSource(sourceId: String): String =
    skillsGateway.activateSkillsInstallSource(sourceId)

  override fun loadChatSnapshot(): Map<String, Any?> =
    chatRuntimeGateway.loadChatSnapshot()

  internal fun loadChatSnapshotForEmission(): Map<String, Any?> =
    loadChatSnapshot(includeRuntimeActivity = false)

  internal fun loadChatSnapshot(includeRuntimeActivity: Boolean): Map<String, Any?> {
    val initialBuild = synchronized(lock) {
      buildChatSnapshotLocked(includeRuntimeActivity = includeRuntimeActivity)
    }
    val mergedSynchronously = scheduleVoiceMetadataBackfill(initialBuild.visibleAttachments)
    val finalBuild = if (mergedSynchronously) {
      synchronized(lock) {
        buildChatSnapshotLocked(includeRuntimeActivity = includeRuntimeActivity)
      }
    } else {
      initialBuild
    }
    return finalBuild.snapshot
  }

  private fun buildChatSnapshotLocked(
    includeRuntimeActivity: Boolean,
  ): ChatSnapshotBuildResult {
    val chatState = chatSessionStore.loadState()
    val activeSession = chatState.activeSession
    repairStaleSupplementsLocked(activeSession.sessionId)
    val llmConfig = llmConfigFacade.load()
    val warmupState = onDeviceWarmupStateForSnapshotLocked(llmConfig)
    val visibleMessages = activeSession.messages.filter(::isVisibleChatMessage)
    val pendingUserInputs = chatSessionStore.loadPendingUserInputs(activeSession.sessionId)
    val pendingSupplements = supplementStoreForSession(activeSession.sessionId).snapshot()
    val runs = if (includeRuntimeActivity) {
      runtimeSession(activeSession.sessionId).listRuns()
    } else {
      emptyList()
    }
    val recentEvents = if (includeRuntimeActivity) {
      userVisibleRuntimeEvents(
        runs = runs,
        recentEvents = mergedRuntimeEventsLocked(
          sessionId = activeSession.sessionId,
          runs = runs,
        ),
      )
    } else {
      emptyList()
    }
    val displayedRuns = if (includeRuntimeActivity) {
      displayedRunsForSnapshot(
        runs = runs,
        recentEvents = recentEvents,
        userVisibleRuns = ::userVisibleRuns,
        isInternalCheckpointEvent = ::isInternalPromptCheckpointEvent,
      )
    } else {
      emptyList()
    }
    val renderedMessages = renderedChatMessages(
      visibleMessages = visibleMessages,
      runs = displayedRuns,
      runtimeEvents = recentEvents,
      pendingUserInputs = pendingUserInputs,
      pendingSupplements = pendingSupplements,
      transcriptMessageToMap = ::chatMessageToMap,
    )
    val pendingCount = visiblePendingTaskCount(activeSession.sessionId)
    val pendingApprovals = pendingApprovalsForSession(activeSession.sessionId)
    val pendingUserInputCount = pendingUserInputs.size
    val pendingSupplementCount = pendingSupplements.size
    val activeSessionTitle = displaySessionTitle(activeSession.title)
    val todoPresentation = todoSnapshotProvider(activeSession.sessionId)
    val todos = todoPresentation.todos.map(::todoSnapshotMap)
    val awaitingDirection = latestRunForSnapshot(displayedRuns)?.let { run ->
      isAwaitingDirectionRun(run) || isDeferredApprovalDecisionAwaitingResumeRun(run)
    } == true
    val shouldShowWarmupState = warmupState.blocksChatInput() &&
      pendingApprovals.isEmpty() &&
      pendingSupplementCount == 0 &&
      pendingCount == 0 &&
      pendingUserInputCount == 0 &&
      !awaitingDirection
    val summaryBody = when {
      shouldShowWarmupState -> {
        strings.chatSummaryOnDevicePreparing
      }
      pendingApprovals.isEmpty() && awaitingDirection -> {
        strings.chatSummaryAwaitingDirection
      }
      pendingApprovals.isNotEmpty() && pendingUserInputCount > 0 -> {
        strings.chatSummaryApprovalFollowUpRecorded
      }
      pendingApprovals.isNotEmpty() -> {
        strings.chatSummaryApprovalRequired
      }
      pendingSupplementCount > 0 -> {
        strings.chatSummarySupplementRecorded
      }
      pendingCount > 0 || pendingUserInputCount > 0 -> {
        strings.chatSummaryReplyInProgress
      }
      visibleMessages.isEmpty() -> {
        strings.chatSummaryStartNewSession
      }
      else -> {
        strings.chatSummaryRestored
      }
    }
    val runtimeActivity = if (includeRuntimeActivity) {
      runtimeActivitySnapshotMap(
        sessionId = activeSession.sessionId,
        displayedRuns = displayedRuns,
        recentEvents = recentEvents,
      )
    } else {
      null
    }
    val runtimeActivityUpdatedAtEpochMs = (runtimeActivity?.get("updatedAtEpochMs") as? Number)
      ?.toLong()
      ?: 0L
    return ChatSnapshotBuildResult(
      snapshot = mapOf(
      "screenTitle" to strings.chatScreenTitle,
      "modeLabel" to currentChatModeLabelLocked(),
      "sessionButtonLabel" to strings.chatSessionButtonLabel,
      "composerPlaceholder" to composerPlaceholderForSnapshot(
        displayedRuns = displayedRuns,
        hasPendingApprovals = pendingApprovals.isNotEmpty(),
        warmupState = warmupState,
      ),
      "summary" to mapOf(
        "title" to activeSessionTitle,
        "badge" to strings.chatMessagesBadge(renderedMessages.size),
        "body" to summaryBody,
      ),
      "messages" to renderedMessages,
      "todos" to todos,
      "todoState" to when (todoPresentation.state) {
        ChatSessionTodoPresentationState.EMPTY -> "empty"
        ChatSessionTodoPresentationState.ACTIVE -> "active"
        ChatSessionTodoPresentationState.ARCHIVED_COMPLETED -> "archived_completed"
      },
      "todoHideDelayMs" to todoPresentation.hideDelayMs,
      "todoCompletedAtEpochMs" to todoPresentation.completedAtEpochMs,
      "pendingApprovals" to pendingApprovals.map { approval ->
        mapOf(
          "runId" to approval.runId,
          "taskId" to approval.taskId,
          "pendingMessageId" to approval.pendingMessageId,
          "toolName" to approval.toolName,
          "requestSummary" to approval.requestSummary,
          "primaryDetail" to approval.primaryDetail,
          "pathDetails" to approval.pathDetails,
          "workingDirectory" to approval.workingDirectory,
          "reason" to approval.reason,
          "message" to approval.message,
          "risk" to if (approval.isHighRisk) "high_risk" else "standard",
          "isHighRisk" to approval.isHighRisk,
          "title" to approval.title,
          "body" to approval.body,
          "approveLabel" to strings.chatApprovalApproveLabel,
          "supportsSessionApproval" to approval.supportsSessionApproval,
          "approveForSessionLabel" to approval.approveForSessionLabel,
          "rejectLabel" to strings.chatApprovalRejectLabel,
        )
      },
      "drawer" to mapOf(
        "eyebrow" to strings.chatRecentSessionsEyebrow,
        "title" to strings.chatRecentSessionsTitle,
        "ctaLabel" to strings.chatNewSessionLabel,
        "sessions" to chatState.sessions.map { session ->
          val unreadCount = unreadCountForSessionLocked(
            sessionId = session.sessionId,
            activeSessionId = activeSession.sessionId,
          )
          mapOf(
            "sessionId" to session.sessionId,
            "title" to displaySessionTitle(session.title),
            "preview" to drawerPreviewTextLocked(
              sessionId = session.sessionId,
              fallbackPreview = session.lastMessagePreview,
              includeRuntimePreview = includeRuntimeActivity,
            ),
            "meta" to strings.chatMessagesBadge(session.messageCount),
            "lastMessageAtEpochMs" to session.lastMessageAtEpochMs,
            "isSelected" to (session.sessionId == activeSession.sessionId),
            "unreadCount" to unreadCount,
          )
        },
      ),
      "runtimeActivity" to runtimeActivity,
      "updatedAtEpochMs" to maxOf(
        activeSession.updatedAtEpochMs,
        chatState.sessions.maxOfOrNull(ChatSessionLocalStore.SessionSummary::updatedAtEpochMs) ?: 0L,
        runtimeActivityUpdatedAtEpochMs,
      ),
      "isInputEnabled" to !warmupState.blocksChatInput(),
    ),
      visibleAttachments = visibleMessages.flatMap(ChatTranscriptMessageEntry::attachments),
    )
  }

  private fun todoSnapshotMap(entry: AgentTodoEntry): Map<String, Any?> = mapOf(
    "content" to entry.content,
    "status" to when (entry.status) {
      AgentTodoStatus.PENDING -> "pending"
      AgentTodoStatus.IN_PROGRESS -> "in_progress"
      AgentTodoStatus.COMPLETED -> "completed"
    },
    "activeForm" to entry.activeForm,
  )

  override fun observeChat(listener: (Map<String, Any?>) -> Unit): () -> Unit =
    chatRuntimeGateway.observeChat(listener)

  override fun loadChatRuntimeSnapshot(): Map<String, Any?> =
    chatRuntimeGateway.loadChatRuntimeSnapshot()

  override fun loadChatRunSnapshot(runId: String): Map<String, Any?>? =
    chatRuntimeGateway.loadChatRunSnapshot(runId)

  override fun waitForChatRun(
    runId: String,
    timeoutMs: Long,
  ): Map<String, Any?>? = chatRuntimeGateway.waitForChatRun(runId, timeoutMs)

  fun waitForChatRun(runId: String): Map<String, Any?>? =
    chatRuntimeGateway.waitForChatRun(runId)

  override fun observeChatRuntime(listener: (Map<String, Any?>) -> Unit): () -> Unit =
    chatRuntimeGateway.observeChatRuntime(listener)

  override fun observeLiveAssistantDraftEvents(listener: (Map<String, Any?>) -> Unit): () -> Unit =
    chatRuntimeGateway.observeLiveAssistantDraftEvents(listener)

  override fun observeRuntimeEventDeltas(listener: (Map<String, Any?>) -> Unit): () -> Unit =
    chatRuntimeGateway.observeRuntimeEventDeltas(listener)

  internal fun dispose() {
    val runtimeDisposer: (() -> Unit)?
    val diagnosticsDisposer: (() -> Unit)?
    synchronized(lock) {
      if (disposed) {
        return
      }
      disposed = true
      shellListeners.clear()
      settingsOverviewListeners.clear()
      skillsListeners.clear()
      chatListeners.clear()
      chatRuntimeListeners.clear()
      liveAssistantDraftEventListeners.clear()
      runtimeEventDeltaListeners.clear()
      runtimeDisposer = runtimeObservationDisposer
      diagnosticsDisposer = runtimeDiagnosticsObservationDisposer
      runtimeObservationDisposer = null
      runtimeDiagnosticsObservationDisposer = null
    }
    diagnosticsDisposer?.invoke()
    runtimeDisposer?.invoke()
  }

  override fun refreshSandboxSessionInfo() {
    chatRuntimeGateway.refreshSandboxSessionInfo()
  }

  override fun loadMemoryDebugSnapshot(): Map<String, Any?> =
    chatRuntimeGateway.loadMemoryDebugSnapshot()

  override fun loadSoulDebugSnapshot(): Map<String, Any?> =
    chatRuntimeGateway.loadSoulDebugSnapshot()

  override fun searchMemoryDebug(
    query: String,
    maxResults: Int,
    minScore: Int,
  ): Map<String, Any?> = chatRuntimeGateway.searchMemoryDebug(
    query = query,
    maxResults = maxResults,
    minScore = minScore,
  )

  override fun getMemoryDebugSlice(
    path: String,
    fromLine: Int?,
    lines: Int,
  ): Map<String, Any?> = chatRuntimeGateway.getMemoryDebugSlice(
    path = path,
    fromLine = fromLine,
    lines = lines,
  )

  override fun applyMemoryDebugAction(
    recordId: String,
    actionId: String,
  ): Map<String, Any?> = chatRuntimeGateway.applyMemoryDebugAction(
    recordId = recordId,
    actionId = actionId,
  )

  override fun loadMemoryDebugLinksSnapshot(): Map<String, Any?> =
    chatRuntimeGateway.loadMemoryDebugLinksSnapshot()

  override fun createChatSession() {
    chatRuntimeGateway.createChatSession()
  }

  override fun selectChatSession(sessionId: String) {
    chatRuntimeGateway.selectChatSession(sessionId)
  }

  override fun copyChatSession(sessionId: String) {
    chatRuntimeGateway.copyChatSession(sessionId)
  }

  override fun branchChatSessionFromMessage(
    sessionId: String,
    messageId: String,
  ) {
    chatRuntimeGateway.branchChatSessionFromMessage(sessionId, messageId)
  }

  override fun deleteChatSession(sessionId: String) {
    chatRuntimeGateway.deleteChatSession(sessionId)
  }

  override fun deleteChatMessage(
    sessionId: String,
    messageId: String,
  ) {
    chatRuntimeGateway.deleteChatMessage(sessionId, messageId)
  }

  override fun recallChatMessage(
    sessionId: String,
    messageId: String,
  ) {
    chatRuntimeGateway.recallChatMessage(sessionId, messageId)
  }

  private fun sweepWorkspaceChatMedia() {
    val workspaceRoot = workspaceRootProvider?.invoke() ?: return
    AppAgentWorkspaceMediaGc.sweep(
      workspaceRoot = workspaceRoot,
      chatSessionStore = chatSessionStore,
    )
  }

  override fun approveChatApproval(taskIdOrRunId: String) {
    chatRuntimeGateway.approveChatApproval(taskIdOrRunId)
  }

  override fun approveChatApprovalForSession(taskIdOrRunId: String) {
    chatRuntimeGateway.approveChatApprovalForSession(taskIdOrRunId)
  }

  override fun rejectChatApproval(taskIdOrRunId: String) {
    chatRuntimeGateway.rejectChatApproval(taskIdOrRunId)
  }

  override fun interruptChatRun(taskIdOrRunId: String) {
    chatRuntimeGateway.interruptChatRun(taskIdOrRunId)
  }

  override fun retryChatRun(taskIdOrRunId: String) {
    chatRuntimeGateway.retryChatRun(taskIdOrRunId)
  }

  override fun submitChatMessage(
    text: String,
    attachments: List<OpenCrayFinalAttachment>,
  ): Map<String, Any?>? = chatRuntimeGateway.submitChatMessage(
    text = text,
    attachments = attachments,
  )

  fun submitChatMessage(text: String): Map<String, Any?>? =
    chatRuntimeGateway.submitChatMessage(text)

  fun getMemoryDebugSlice(path: String): Map<String, Any?> =
    chatRuntimeGateway.getMemoryDebugSlice(path)

  fun getMemoryDebugSlice(path: String, fromLine: Int?): Map<String, Any?> =
    chatRuntimeGateway.getMemoryDebugSlice(path, fromLine)

  override fun importDraftChatAttachments(
    requestedKind: String,
    uriStrings: List<String>,
  ): List<Map<String, Any?>> = hostLocalHostGateway.importDraftChatAttachments(
    requestedKind = requestedKind,
    uriStrings = uriStrings,
  )

  private fun ensureActiveSessionResumed() {
    val activeSessionId = synchronized(lock) { chatSessionStore.loadState().activeSession.sessionId }
    runtimeSession(activeSessionId).resume()
    repairTerminalReplay(activeSessionId)
  }

  private fun repairTerminalReplay(sessionId: String) {
    val runs = synchronized(lock) {
      runtimeSession(sessionId).listRuns().also { sessionRuns ->
        if (hasSessionLocked(sessionId)) {
          repairRestoredTerminalMessagesLocked(
            sessionId = sessionId,
            runs = sessionRuns,
          )
        }
      }
    }
    terminalReplayRepairer(sessionId, runs)
  }

  private fun submitPromptRunLocked(
    sessionId: String,
    userText: String,
    attachments: List<ChatAttachmentEntry> = emptyList(),
  ): AgentRunSubmission = chatSubmissionCoordinator.submitPromptRun(
    sessionId = sessionId,
    userText = userText,
    attachments = attachments,
  )

  private fun startNextQueuedChatRunLocked(sessionId: String): AgentRunSubmission? =
    chatSubmissionCoordinator.startNextQueuedChatRun(sessionId)

  private fun resumePausedRunWithUserInputLocked(
    sessionId: String,
    run: AgentRunSnapshot,
    userText: String,
    attachments: List<ChatAttachmentEntry>,
  ): AgentRunSubmission = chatSubmissionCoordinator.resumePausedRunWithUserInput(
    sessionId = sessionId,
    run = run,
    userText = userText,
    attachments = attachments,
  )

  private fun hasSessionLocked(sessionId: String): Boolean = chatSessionStore.loadState().sessions
    .any { session -> session.sessionId == sessionId }

  internal fun runtimeSession(sessionId: String): OpenCrayRuntimeSessionAccess =
    runtimeHostAccess.session(sessionId)

  private fun clearApproval(sessionId: String, taskId: String) {
    runtimeHostAccess.clearApproval(sessionId = sessionId, taskId = taskId)
  }

  private fun retainKnownApprovalTasks(sessionId: String, taskIds: Set<String>) {
    runtimeHostAccess.retainKnownApprovalTasks(sessionId = sessionId, taskIds = taskIds)
  }

  private fun isApprovalApproved(sessionId: String, taskId: String): Boolean =
    runtimeHostAccess.isApprovalApproved(sessionId = sessionId, taskId = taskId)

  private fun isApprovalRejected(sessionId: String, taskId: String): Boolean =
    runtimeHostAccess.isApprovalRejected(sessionId = sessionId, taskId = taskId)

  private fun approvalStateForTaskLocked(
    sessionId: String,
    taskId: String,
  ): AgentTaskApprovalState? = approvalDecisionState(
    approved = isApprovalApproved(sessionId, taskId),
    rejected = isApprovalRejected(sessionId, taskId),
    checkpoint = promptCheckpointStoreForSession(sessionId).get(taskId),
  )

  private fun approvalDecisionCheckpointKind(
    sessionId: String,
    taskId: String,
  ): PromptCheckpointKind? = promptCheckpointStoreForSession(sessionId)
    .get(taskId)
    ?.checkpointKind

  // Use run projection here so chat state settles immediately when a result is already known.
  private fun pendingTaskCount(sessionId: String): Int = runtimeSession(sessionId)
    .listRuns()
    .count { run -> !run.isTerminal }

  private fun visiblePendingTaskCount(sessionId: String): Int = userVisibleRuns(
    runtimeSession(sessionId).listRuns(),
  ).count(AgentRunSnapshot::isActive)

  private fun userVisibleRuns(
    runs: List<AgentRunSnapshot>,
  ): List<AgentRunSnapshot> = runs.filter(::isUserVisibleRun)

  private fun userVisibleRuntimeEvents(
    runs: List<AgentRunSnapshot>,
    recentEvents: List<OpenCrayAgentRunEvent>,
  ): List<OpenCrayAgentRunEvent> {
    if (recentEvents.isEmpty()) {
      return emptyList()
    }
    val runsByRunId = runs.associateBy(AgentRunSnapshot::runId)
    val runsByTaskId = runs.associateBy(AgentRunSnapshot::taskId)
    return recentEvents.filter { event ->
      if (isInternalPromptCheckpointEvent(event)) {
        return@filter false
      }
      val matchingRun = runsByRunId[event.runId]
        ?: runsByTaskId[event.taskId]
        ?: findRunSnapshotForIdentifierLocked(event.runId)
        ?: findRunSnapshotForIdentifierLocked(event.taskId)
      matchingRun?.let(::isUserVisibleRun) != false
    }
  }

  internal fun isUserVisibleRun(run: AgentRunSnapshot): Boolean =
    !isInternalDetachedSubAgentRecoveryRun(run)

  private fun isInternalDetachedSubAgentRecoveryRun(run: AgentRunSnapshot): Boolean =
    run.lifecycleDiagnostics.submissionSource == RunSubmissionSources.RUNTIME_SERVICE_SUBAGENT_RECOVERY

  private fun supplementStoreForSession(sessionId: String): SessionSupplementStore =
    runtimeHostAccess.supplementStore(sessionId)

  private fun runEventJournalStoreForSession(sessionId: String): RunEventJournalStore =
    runtimeHostAccess.runEventJournalStore(sessionId)

  private fun promptCheckpointStoreForSession(sessionId: String): PromptCheckpointStore =
    runtimeHostAccess.promptCheckpointStore(sessionId)

  private fun supplementTargetRunLocked(sessionId: String): AgentRunSnapshot? =
    chatSubmissionCoordinator.supplementTargetRun(sessionId)

  private fun runStillAcceptsSupplementsLocked(
    sessionId: String,
    runId: String,
  ): Boolean = runtimeSession(sessionId)
    .findRun(runId)
    ?.isActive == true

  private fun appendRunSupplementLocked(
    sessionId: String,
    run: AgentRunSnapshot,
    text: String,
  ) {
    supplementStoreForSession(sessionId).append(
      runId = run.runId,
      taskId = run.taskId,
      text = text,
    )
  }

  private fun promoteSupplementsForRunLocked(
    sessionId: String,
    runId: String,
    taskId: String,
  ) {
    promoteSupplementEntriesLocked(
      sessionId = sessionId,
      entries = supplementStoreForSession(sessionId).consumeForRun(
        runId = runId,
        taskId = taskId,
      ),
    )
  }

  private fun repairStaleSupplementsLocked(sessionId: String) =
    chatSubmissionCoordinator.repairStaleSupplements(sessionId)

  private fun promoteSupplementEntriesLocked(
    sessionId: String,
    entries: List<MidLoopSupplementEntry>,
  ) {
    entries
      .sortedBy(MidLoopSupplementEntry::createdAtEpochMs)
      .forEach { entry ->
        chatSessionStore.enqueuePendingUserInput(
          sessionId = sessionId,
          text = entry.text,
        )
      }
  }

  private fun runBlocksMidLoopSupplements(run: AgentRunSnapshot): Boolean =
    run.executionStatus == ExecutionStatus.DENIED &&
      isApprovalRequiredError(run.errorCode)

  private fun pendingApprovalsForSession(
    sessionId: String,
    pruneCheckpointState: Boolean = true,
  ): List<PendingApprovalSnapshot> {
    val sessionHandle = runtimeSession(sessionId)
    val snapshot = sessionHandle.snapshot()
    val runsByTaskId = sessionHandle.listRuns().associateBy(AgentRunSnapshot::taskId)
    val transientApprovals = chatPendingApprovalState.approvalsForSession(sessionId)
    val knownTaskIds = snapshot.tasks.mapTo(linkedSetOf()) { taskSnapshot -> taskSnapshot.task.id } +
      transientApprovals.keys +
      runsByTaskId.keys
    if (pruneCheckpointState) {
      retainKnownApprovalTasks(sessionId = sessionId, taskIds = knownTaskIds)
      promptCheckpointStoreForSession(sessionId).retainKnownTasks(knownTaskIds)
    }

    val combined = linkedMapOf<String, PendingApprovalSnapshot>()
    snapshot.tasks
      .asSequence()
      .filter { taskSnapshot ->
        (taskSnapshot.lifecycleState == QueueTaskLifecycleState.SUSPENDED ||
          taskSnapshot.lifecycleState == QueueTaskLifecycleState.FAILED) &&
          isApprovalRequiredError(taskSnapshot.lastErrorCode)
      }
      .map { taskSnapshot ->
        val runSnapshot = runsByTaskId[taskSnapshot.task.id]
        val metadata = runSnapshot?.resultMetadata.orEmpty()
        val isHighRisk = approvalIsHighRisk(
          errorCode = taskSnapshot.lastErrorCode,
          metadata = metadata,
        )
        pendingApprovalSnapshot(
          runId = runSnapshot?.runId ?: runIdFor(taskSnapshot.task),
          taskId = taskSnapshot.task.id,
          pendingMessageId = runSnapshot?.pendingMessageId
            ?: taskSnapshot.task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID]
              ?.takeIf(String::isNotBlank),
          isHighRisk = isHighRisk,
          metadata = metadata,
          errorBody = sanitizeApprovalBody(
            body = runSnapshot?.errorMessage ?: taskSnapshot.lastErrorMessage,
            isHighRisk = isHighRisk,
            strings = strings,
          ),
          toolReason = runSnapshot?.resultMetadata?.get("toolReason")
            ?: runSnapshot?.lastEvent?.let(::toolReasonFromEvent),
          strings = strings,
          localeIsChinese = isChineseHostLocale(),
          replayJson = replayJson,
        )
      }
      .forEach { approval ->
        combined[approval.taskId] = approval
      }
    transientApprovals.values.forEach { approval ->
      combined[approval.taskId] = approval
    }
    return combined.values.filter { approval ->
      approvalStateForTaskLocked(sessionId, approval.taskId) == null
    }
  }

  private fun pendingApprovalForIdentifier(
    sessionId: String,
    taskIdOrRunId: String,
  ): PendingApprovalSnapshot? = pendingApprovalsForSession(sessionId)
    .firstOrNull { approval ->
      approval.taskId == taskIdOrRunId || approval.runId == taskIdOrRunId
    }

  private fun findPendingApprovalMatchLocked(
    taskIdOrRunId: String,
  ): PendingApprovalMatch? = knownSessionIdsLocked().firstNotNullOfOrNull { sessionId ->
    pendingApprovalForIdentifier(sessionId, taskIdOrRunId)?.let { approval ->
      PendingApprovalMatch(
        sessionId = sessionId,
        approval = approval,
      )
    }
  }

  internal fun listPendingApprovalNotificationTargets(): List<RuntimePendingApprovalNotificationTarget> =
    synchronized(lock) {
      knownSessionIdsLocked().flatMap { sessionId ->
        val sessionTitle = sessionDisplayTitleLocked(sessionId)
        pendingApprovalsForSession(sessionId).map { approval ->
          RuntimePendingApprovalNotificationTarget(
            sessionId = sessionId,
            sessionTitle = sessionTitle,
            runId = approval.runId,
            taskId = approval.taskId,
            title = approval.title,
            body = approval.body,
            isHighRisk = approval.isHighRisk,
          )
        }
      }
    }

  internal fun sessionDisplayTitleForNotification(sessionId: String): String = synchronized(lock) {
    sessionDisplayTitleLocked(sessionId)
  }

  internal fun summarizeResultForNotification(
    sessionId: String,
    task: AgentTask,
    result: ExecutionResult,
  ): String = synchronized(lock) {
    finalTextForLocked(
      sessionId = sessionId,
      task = task,
      result = result,
    )
  }

  private fun knownSessionIdsLocked(): List<String> {
    return knownChatSessionIds(chatSessionStore)
  }

  private fun sessionDisplayTitleLocked(sessionId: String): String {
    val state = chatSessionStore.loadState()
    val summary = state.sessions.firstOrNull { session -> session.sessionId == sessionId }
    val fallbackTitle = summary?.title?.takeIf(String::isNotBlank)
      ?: chatSessionStore.loadSession(sessionId)?.title?.takeIf(String::isNotBlank)
      ?: strings.chatDefaultSessionTitle
    return displaySessionTitle(fallbackTitle)
  }

  private fun recordPendingApprovalLocked(
    sessionId: String,
    task: AgentTask,
    result: ExecutionResult,
  ): PendingApprovalSnapshot {
    val isHighRisk = approvalIsHighRisk(
      errorCode = result.errorCode,
      metadata = result.metadata,
    )
    val approval = pendingApprovalSnapshot(
      runId = runIdFor(task),
      taskId = task.id,
      pendingMessageId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID]
        ?.takeIf(String::isNotBlank),
      isHighRisk = isHighRisk,
      metadata = result.metadata,
      errorBody = sanitizeApprovalBody(
        body = result.errorMessage,
        isHighRisk = isHighRisk,
        strings = strings,
      ),
      toolReason = result.metadata["toolReason"],
      strings = strings,
      localeIsChinese = isChineseHostLocale(),
      replayJson = replayJson,
    )
    chatPendingApprovalState.put(
      sessionId = sessionId,
      taskId = task.id,
      approval = approval,
    )
    persistPromptCheckpointLocked(
      sessionId = sessionId,
      checkpoint = approval.toApprovalDecisionRecord().decisionCheckpoint(
        sessionId = sessionId,
        checkpointKind = PromptCheckpointKind.WAITING_APPROVAL,
        nowEpochMs = result.finishedAtEpochMs,
      ),
    )
    return approval
  }

  private fun maybePersistGeneralResumeCheckpointLocked(
    sessionId: String,
    task: AgentTask,
    event: OpenCrayAgentRunEvent,
  ) {
    val eventRunId: String
    val eventTaskId: String
    val eventToolName: String?
    val eventMetadata: Map<String, String>
    val emittedAtEpochMs: Long
    val checkpointKind: PromptCheckpointKind
    when (event) {
      is OpenCrayToolResultEvent -> {
        eventRunId = event.runId
        eventTaskId = event.taskId
        eventToolName = event.result.toolName
        eventMetadata = event.result.metadata
        emittedAtEpochMs = event.emittedAtEpochMs
        checkpointKind = promptCheckpointKindForRuntimeEvent(
          event = event,
          metadata = eventMetadata,
        ) ?: return
      }

      is OpenCraySupplementEvent -> {
        eventRunId = event.runId
        eventTaskId = event.taskId
        eventToolName = null
        eventMetadata = event.metadata
        emittedAtEpochMs = event.emittedAtEpochMs
        checkpointKind = promptCheckpointKindForRuntimeEvent(
          event = event,
          metadata = eventMetadata,
        ) ?: return
      }

      is OpenCrayAssistantPhaseEvent -> {
        eventRunId = event.runId
        eventTaskId = event.taskId
        eventToolName = null
        eventMetadata = event.metadata
        emittedAtEpochMs = event.emittedAtEpochMs
        checkpointKind = promptCheckpointKindForRuntimeEvent(
          event = event,
          metadata = eventMetadata,
        ) ?: return
      }

      else -> return
    }
    val promptResumeState = OpenCrayPromptResumeMetadata.decodeFromMetadata(
      metadata = eventMetadata,
      json = replayJson,
    ) ?: return
    val promptCheckpointBoundary = OpenCrayPromptResumeMetadata.decodeCheckpointBoundary(eventMetadata)
    persistPromptCheckpointLocked(
      sessionId = sessionId,
      checkpoint = PersistedPromptCheckpoint(
        sessionId = sessionId,
        runId = eventRunId,
        taskId = eventTaskId,
        checkpointId = "checkpoint-$emittedAtEpochMs-${UUID.randomUUID().toString().take(8)}",
        checkpointKind = checkpointKind,
        createdAtEpochMs = emittedAtEpochMs,
        updatedAtEpochMs = emittedAtEpochMs,
        toolName = eventToolName,
        pendingMessageId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID]
          ?.takeIf(String::isNotBlank),
        promptCheckpointBoundary = promptCheckpointBoundary,
        promptResumeState = promptResumeState,
      ),
    )
  }

  private fun persistGeneralResumeCheckpointFromResultLocked(
    sessionId: String,
    task: AgentTask,
    result: ExecutionResult,
  ) {
    val promptResumeState = OpenCrayPromptResumeMetadata.decodeFromMetadata(
      metadata = result.metadata,
      json = replayJson,
    ) ?: return
    val promptCheckpointBoundary = OpenCrayPromptResumeMetadata.decodeCheckpointBoundary(result.metadata)
    persistPromptCheckpointLocked(
      sessionId = sessionId,
      checkpoint = PersistedPromptCheckpoint(
        sessionId = sessionId,
        runId = runIdFor(task),
        taskId = task.id,
        checkpointId = "checkpoint-${result.finishedAtEpochMs}-${UUID.randomUUID().toString().take(8)}",
        checkpointKind = PromptCheckpointKind.GENERAL_RESUME,
        createdAtEpochMs = result.finishedAtEpochMs,
        updatedAtEpochMs = result.finishedAtEpochMs,
        pendingMessageId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID]
          ?.takeIf(String::isNotBlank),
        promptCheckpointBoundary = promptCheckpointBoundary,
        promptResumeState = promptResumeState,
      ),
    )
  }

  private fun persistPromptCheckpointLocked(
    sessionId: String,
    checkpoint: PersistedPromptCheckpoint,
  ) {
    promptCheckpointStoreForSession(sessionId).upsert(checkpoint)
  }

  private fun clearPromptCheckpointLocked(sessionId: String, taskId: String) {
    promptCheckpointStoreForSession(sessionId).remove(taskId)
  }

  private fun clearPendingApprovalLocked(sessionId: String, taskId: String) {
    chatPendingApprovalState.remove(sessionId, taskId)
  }

  private fun incrementUnreadIfBackgroundUpdateLocked(
    sessionId: String,
    activeSessionId: String,
    text: String?,
    attachments: List<ChatAttachmentEntry> = emptyList(),
  ) = chatUnreadMessageState.incrementIfBackgroundUpdate(
    sessionId = sessionId,
    activeSessionId = activeSessionId,
    text = text,
    attachments = attachments,
  )

  private fun clearUnreadCountLocked(sessionId: String) {
    chatUnreadMessageState.clear(sessionId)
  }

  private fun unreadCountForSessionLocked(
    sessionId: String,
    activeSessionId: String,
  ): Int = chatUnreadMessageState.countForSession(
    sessionId = sessionId,
    activeSessionId = activeSessionId,
  )

  private fun recordRuntimeEventLocked(sessionId: String, event: OpenCrayAgentRunEvent) {
    recordRuntimeEventLocked(
      sessionId = sessionId,
      event = event,
      persistToJournal = true,
    )
  }

  private fun recordRuntimeEventLocked(
    sessionId: String,
    event: OpenCrayAgentRunEvent,
    persistToJournal: Boolean,
  ) {
    chatRuntimeEventState.append(
      sessionId = sessionId,
      event = event,
      maxHistory = MAX_RUNTIME_EVENT_HISTORY,
    )
    if (persistToJournal) {
      runEventJournalStoreForSession(sessionId).append(event)
    }
    maybeClearPromptCheckpointAfterRuntimeEventLocked(sessionId = sessionId, event = event)
  }

  private fun shouldEmitRuntimeEventDelta(event: OpenCrayAgentRunEvent): Boolean =
    !isDebugOnlyRuntimeEvent(event) && !isInternalPromptCheckpointEvent(event)

  private fun nextRuntimeEventDeltaSequenceLocked(sessionId: String): Long {
    val next = (runtimeEventDeltaSequencesBySession[sessionId] ?: 0L) + 1L
    runtimeEventDeltaSequencesBySession[sessionId] = next
    return next
  }

  private fun currentRuntimeEventSequenceLocked(sessionId: String): Long =
    runtimeEventDeltaSequencesBySession[sessionId] ?: 0L

  private fun assignRuntimeEventDeltaSequence(
    sessionId: String,
    payload: Map<String, Any?>,
  ): Map<String, Any?> = assignRuntimeRealtimeEnvelope(sessionId = sessionId, payload = payload)

  private fun assignRuntimeRealtimeEnvelope(
    sessionId: String,
    payload: Map<String, Any?>,
  ): Map<String, Any?> =
    assignRuntimeRealtimeEnvelope(
      sessionId = sessionId,
      payload = payload,
      streamInstanceId = runtimeEventStreamInstanceId,
      nextSequence = { synchronized(lock) { nextRuntimeEventDeltaSequenceLocked(sessionId) } },
    )

  private fun buildRuntimeTaskDeltaPayload(
    sessionId: String,
    task: AgentTask,
    sequence: Long,
    event: OpenCrayAgentRunEvent? = null,
  ): Map<String, Any?>? {
    val visibleEvent = event?.takeIf(::shouldEmitRuntimeEventDelta)
    val run = runtimeSession(sessionId).findRun(runIdFor(task))
    val visibleRun = run?.takeIf(::isUserVisibleRun)
    val displayedRuns = visibleRun?.let(::listOf).orEmpty()
    val activeRuns = displayedRuns.filter(AgentRunSnapshot::isActive).map(::runSnapshotToMap)
    val retainedRuns = retainedRunsForSnapshot(
      displayedRuns,
      isAwaitingDirectionRun = ::isAwaitingDirectionRun,
      isInterruptedOnRestoreRun = ::isInterruptedOnRestoreRun,
    ).map(::runSnapshotToMap)
    val updatedAtEpochMs = maxOf(
      visibleEvent?.emittedAtEpochMs ?: 0L,
      visibleRun?.updatedAtEpochMs ?: 0L,
      visibleRun?.lastEvent?.emittedAtEpochMs ?: 0L,
    )
    if (activeRuns.isEmpty() && retainedRuns.isEmpty() && visibleEvent == null) {
      return null
    }
    return buildMap {
      put("sessionId", sessionId)
      put("sequence", sequence)
      put("executionId", visibleEvent?.executionId ?: visibleRun?.executionId)
      put("updatedAtEpochMs", updatedAtEpochMs)
      put("runPatchMode", "merge")
      put("events", visibleEvent?.let(::runtimeEventToMap)?.let(::listOf) ?: emptyList<Map<String, Any?>>())
      if (visibleRun != null) {
        put("activeRuns", activeRuns)
        put("retainedRuns", retainedRuns)
      }
    }
  }

  private fun maybeClearPromptCheckpointAfterRuntimeEventLocked(
    sessionId: String,
    event: OpenCrayAgentRunEvent,
  ) {
    val checkpoint = promptCheckpointStoreForSession(sessionId).get(event.taskId) ?: return
    if (
      checkpoint.checkpointKind != PromptCheckpointKind.APPROVED_PENDING_RESUME &&
      checkpoint.checkpointKind != PromptCheckpointKind.REJECTED_PENDING_RESUME
    ) {
      return
    }
    when (event) {
      is OpenCrayApprovalEvent -> return
      is OpenCraySubAgentEvent -> if (event.phase == OpenCraySubAgentPhase.RESUMED) {
        return
      }
      is OpenCrayLifecycleEvent -> if (event.phase == OpenCrayRunLifecyclePhase.START) {
        return
      }
      else -> Unit
    }
    clearPromptCheckpointLocked(sessionId = sessionId, taskId = event.taskId)
  }

  private fun promptCheckpointKindForRuntimeEvent(
    event: OpenCrayAgentRunEvent,
    metadata: Map<String, String>,
  ): PromptCheckpointKind? {
    val runtimeBoundary = OpenCrayPromptResumeMetadata.decodeCheckpointBoundary(metadata)
    return when (runtimeBoundary) {
      com.opencray.runtime.OpenCrayPromptCheckpointBoundary.PRE_MODEL_REQUEST ->
        PromptCheckpointKind.PRE_MODEL_REQUEST

      com.opencray.runtime.OpenCrayPromptCheckpointBoundary.ACTION_BATCH_PARSED ->
        PromptCheckpointKind.ACTION_BATCH_PARSED

      com.opencray.runtime.OpenCrayPromptCheckpointBoundary.COMMENTARY_EMITTED ->
        PromptCheckpointKind.COMMENTARY_EMITTED

      com.opencray.runtime.OpenCrayPromptCheckpointBoundary.TOOL_RESULT_COMMITTED ->
        PromptCheckpointKind.TOOL_RESULT_COMMITTED

      com.opencray.runtime.OpenCrayPromptCheckpointBoundary.SUPPLEMENT_INGESTED ->
        PromptCheckpointKind.SUPPLEMENT_INGESTED

      com.opencray.runtime.OpenCrayPromptCheckpointBoundary.FINALIZATION_COMPLETE ->
        PromptCheckpointKind.FINALIZATION_COMPLETE

      null -> when (event) {
        is OpenCrayToolResultEvent,
        is OpenCraySupplementEvent,
        -> PromptCheckpointKind.GENERAL_RESUME

        is OpenCrayAssistantPhaseEvent -> null
        else -> null
      }
    }
  }

  private fun successfulToolObservationsLocked(sessionId: String, task: AgentTask): List<String> {
    val runId = runIdFor(task)
    return chatRuntimeEventState.eventsForSession(sessionId)
      .asSequence()
      .filter { event -> event.runId == runId }
      .mapNotNull { event ->
        (event as? OpenCrayToolResultEvent)
          ?.takeIf { toolEvent -> toolEvent.result.status == AgentToolResultStatus.SUCCESS }
          ?.result
          ?.content
          ?.trim()
          ?.takeIf(String::isNotBlank)
      }
      .distinct()
      .toList()
  }

  private fun resolvedUserTextLocked(
    sessionId: String,
    pendingMessageId: String?,
    task: AgentTask,
  ): String {
    val messageId = pendingMessageId?.takeIf(String::isNotBlank) ?: return task.input
    val messages = chatSessionStore.loadSession(sessionId)?.messages.orEmpty()
    val assistantIndex = messages.indexOfFirst { message -> message.messageId == messageId }
    if (assistantIndex <= 0) {
      return task.input
    }
    return messages
      .take(assistantIndex)
      .lastOrNull { message -> message.role == ChatTranscriptRole.USER }
      ?.text
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: task.input
  }

  private fun maybePersistAssistantPhaseChatMessageLocked(
    sessionId: String,
    task: AgentTask,
    event: OpenCrayAgentRunEvent,
  ) {
    val assistantPhaseEvent = event as? OpenCrayAssistantPhaseEvent ?: return
    if (assistantPhaseEvent.isFinal || hideAssistantPhaseFromChatBubble(assistantPhaseEvent)) {
      return
    }
    val pendingMessageId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: return
    val run = runtimeSession(sessionId).findRun(assistantPhaseEvent.runId) ?: return
    if (!eventMatchesRunExecution(run = run, event = assistantPhaseEvent)) {
      return
    }
    val projectedText = projectedRuntimeMessageText(event = assistantPhaseEvent)
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: return
    chatSessionStore.insertMessageBefore(
      sessionId = sessionId,
      anchorMessageId = pendingMessageId,
      role = ChatTranscriptRole.ASSISTANT,
      text = projectedText,
      messageId = runtimeProjectedMessageId(assistantPhaseEvent),
      createdAtEpochMs = assistantPhaseEvent.emittedAtEpochMs.takeIf { emittedAt -> emittedAt > 0L },
    )
  }

  private fun updateAssistantDraft(
    sessionId: String,
    task: AgentTask,
    text: String,
    emittedAtEpochMs: Long,
  ): LiveAssistantDraftSnapshot? {
    val pendingMessageId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: return null
    val normalizedText = text.trim().takeIf(String::isNotBlank) ?: return null
    val updatedDraft = LiveAssistantDraftSnapshot(
      runId = runIdFor(task),
      taskId = task.id,
      executionId = executionIdFromMetadata(task.metadata),
      pendingMessageId = pendingMessageId,
      text = normalizedText,
      updatedAtEpochMs = emittedAtEpochMs,
    )
    return synchronized(liveAssistantDraftLock) {
      val sessionDrafts = liveAssistantDraftsBySession.getOrPut(sessionId) { linkedMapOf() }
      val existing = sessionDrafts[pendingMessageId]
      if (existing == updatedDraft) {
        null
      } else {
        sessionDrafts[pendingMessageId] = updatedDraft
        updatedDraft
      }
    }
  }

  private fun clearAssistantDraftLocked(
    sessionId: String,
    pendingMessageId: String?,
  ): Boolean = clearAssistantDraft(
    sessionId = sessionId,
    pendingMessageId = pendingMessageId,
  )

  private fun clearAssistantDraft(
    sessionId: String,
    pendingMessageId: String?,
  ): Boolean {
    val normalizedPendingMessageId = pendingMessageId
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: return false
    return synchronized(liveAssistantDraftLock) {
      val sessionDrafts = liveAssistantDraftsBySession[sessionId] ?: return@synchronized false
      val removed = sessionDrafts.remove(normalizedPendingMessageId) != null
      if (sessionDrafts.isEmpty()) {
        liveAssistantDraftsBySession.remove(sessionId)
      }
      removed
    }
  }

  internal fun runtimeActivitySnapshotLocked(sessionId: String): Map<String, Any?> {
    val runs = runtimeSession(sessionId).listRuns()
    if (runs.isNotEmpty()) {
      runtimeSession(sessionId).retainKnownSubAgentParentRuns(
        runs.mapTo(linkedSetOf(), AgentRunSnapshot::runId),
      )
    }
    val recentEvents = userVisibleRuntimeEvents(
      runs = runs,
      recentEvents = mergedRuntimeEventsLocked(
        sessionId = sessionId,
        runs = runs,
      ),
    )
    val displayedRuns = displayedRunsForSnapshot(
      runs = runs,
      recentEvents = recentEvents,
      userVisibleRuns = ::userVisibleRuns,
      isInternalCheckpointEvent = ::isInternalPromptCheckpointEvent,
    )
    return runtimeActivitySnapshotMap(
      sessionId = sessionId,
      displayedRuns = displayedRuns,
      recentEvents = recentEvents,
    )
  }

  private fun runtimeActivitySnapshotMap(
    sessionId: String,
    displayedRuns: List<AgentRunSnapshot>,
    recentEvents: List<OpenCrayAgentRunEvent>,
  ): Map<String, Any?> {
    val subAgentSnapshots = subAgentSnapshotsForActivity(
      sessionId = sessionId,
      displayedRuns = displayedRuns,
      recentEvents = recentEvents,
      sessionAccessor = ::runtimeSession,
      subAgentLinkStoreFactory = subAgentSessionLinkStoreFactory,
      promptCheckpointStoreFor = ::promptCheckpointStoreForSession,
    )
    val liveAssistantDrafts = liveAssistantDraftsForSnapshot(
      sessionId = sessionId,
      displayedRuns = displayedRuns,
      recentEvents = recentEvents,
      sessionDraftsProvider = { draftSessionId ->
        synchronized(liveAssistantDraftLock) {
          liveAssistantDraftsBySession[draftSessionId]
            ?.toMap(linkedMapOf())
            .orEmpty()
        }
      },
    )
    val updatedAtEpochMs = runtimeActivityUpdatedAtEpochMs(
      displayedRuns = displayedRuns,
      recentEvents = recentEvents,
      subAgentSnapshots = subAgentSnapshots,
      liveAssistantDrafts = liveAssistantDrafts,
      hostCreatedAtEpochMs = lifecycleDescriptor.hostCreatedAtEpochMs,
    )
    return buildMap {
      put("sessionId", sessionId)
      put("streamInstanceId", runtimeEventStreamInstanceId)
      put("lastSequence", currentRuntimeEventSequenceLocked(sessionId))
      put("updatedAtEpochMs", updatedAtEpochMs)
      putRuntimeServiceDiagnosticsSnapshot(
        hostLifecycle = lifecycleDescriptor,
        runtimeControllerLifecycle = runtimeDiagnosticsBridge.runtimeControllerDescriptor,
        runtimeOwnerLifecycle = runtimeDiagnosticsBridge.runtimeOwnerDescriptor,
        runtimeOwnerWorkSummary = runtimeHostAccess.activeWorkSummary(),
        runtimeServiceLifecycle = runtimeDiagnosticsBridge.runtimeServiceDescriptor,
        runtimeServiceWorkState = runtimeDiagnosticsBridge.runtimeServiceWorkStateProvider(),
        runtimeServiceKeepAliveState =
          runtimeDiagnosticsBridge.runtimeServiceKeepAliveStateProvider(),
        runtimeServiceOwnerLease = runtimeDiagnosticsBridge.runtimeServiceOwnerLeaseProvider(),
        runtimeServiceConnectionState =
          runtimeDiagnosticsBridge.runtimeServiceConnectionStateProvider(),
        includeNullRuntimeServiceFields = true,
      )
      put(
        "activeRuns",
        displayedRuns.filter(AgentRunSnapshot::isActive).map(::runSnapshotToMap),
      )
      put(
        "retainedRuns",
        retainedRunsForSnapshot(
          displayedRuns,
          isAwaitingDirectionRun = ::isAwaitingDirectionRun,
          isInterruptedOnRestoreRun = ::isInterruptedOnRestoreRun,
        ).map(::runSnapshotToMap),
      )
      put("subAgents", subAgentSnapshots.map(::subAgentSnapshotToMap))
      put("events", recentEvents.map(::runtimeEventToMap))
      put("liveAssistantDrafts", liveAssistantDrafts.map(::liveAssistantDraftToMap))
    }
  }

  private fun composerPlaceholderForSnapshot(
    displayedRuns: List<AgentRunSnapshot>,
    hasPendingApprovals: Boolean,
    warmupState: OnDeviceLlmWarmupState = OnDeviceLlmWarmupState(),
  ): String {
    if (warmupState.phase == OnDeviceLlmWarmupPhase.FAILED) {
      return warmupState.failureMessage
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: strings.composerPlaceholder
    }
    if (warmupState.blocksChatInput()) {
      return strings.chatMessageOnDevicePreparing
    }
    if (hasPendingApprovals) {
      return strings.composerPlaceholder
    }
    val latestRun = latestRunForSnapshot(displayedRuns) ?: return strings.composerPlaceholder
    if (isDeferredApprovalDecisionAwaitingResumeRun(latestRun)) {
      return strings.composerPlaceholder
    }
    return if (isAwaitingDirectionRun(latestRun)) {
      strings.composerRejectedPlaceholder
    } else {
      strings.composerPlaceholder
    }
  }

  private fun onDeviceWarmupStateForSnapshotLocked(
    llmConfig: LlmConfigSnapshot,
  ): OnDeviceLlmWarmupState = llmConfig.onDeviceWarmupSpecOrNull()?.let { spec ->
    onDeviceLlmWarmupController.ensureWarm(spec)
  } ?: onDeviceLlmWarmupController.clear()

  private fun defaultOnDeviceLlmWarmupController(): OnDeviceLlmWarmupController {
    val context = appContext ?: return NoOpOnDeviceLlmWarmupController
    return AppOnDeviceLlmWarmupController(
      runtime = LiteRtOnDeviceRuntime.fromContext(context),
      onStateChanged = ::emitChatSnapshot,
    )
  }

  private fun scheduleStartupOnDeviceWarmup() {
    synchronized(lock) {
      onDeviceWarmupStateForSnapshotLocked(llmConfigFacade.load())
    }
  }

  private fun latestRunForSnapshot(
    runs: List<AgentRunSnapshot>,
  ): AgentRunSnapshot? = latestChatRunForSnapshot(runs)

  private fun isRejectedAwaitingDirectionRun(run: AgentRunSnapshot): Boolean =
    (run.lastEvent as? OpenCrayApprovalEvent)?.phase == OpenCrayApprovalPhase.REJECTED

  private fun isAwaitingDirectionRun(run: AgentRunSnapshot): Boolean =
    isRejectedAwaitingDirectionRun(run) ||
      isUserInterruptedAwaitingDirectionRun(run) ||
      isLlmRetryPausedAwaitingResumeRun(run)

  private fun isDeferredApprovalDecisionAwaitingResumeRun(run: AgentRunSnapshot): Boolean =
    chatRunIsDeferredApprovalAwaitingResume(
      run = run,
      runtimeHostAccess = runtimeHostAccess,
    )

  private fun isUserInterruptedAwaitingDirectionRun(run: AgentRunSnapshot): Boolean {
    val latestVisibleEvent = latestVisibleRunEventLocked(run)
    return (latestVisibleEvent as? OpenCrayCancellationEvent)?.outcome == "user_interrupted"
  }

  private fun isApprovalWaitingRun(run: AgentRunSnapshot): Boolean =
    run.lifecycleState == QueueTaskLifecycleState.SUSPENDED &&
      run.executionStatus == ExecutionStatus.DENIED &&
      isApprovalRequiredError(run.errorCode)

  private fun shouldDeferApprovalDecisionUntilManualResume(
    run: AgentRunSnapshot,
    approval: PendingApprovalSnapshot,
  ): Boolean = isApprovalWaitingRun(run) &&
    isUserInterruptedAwaitingDirectionRun(run) &&
    (approval.runId == run.runId || approval.taskId == run.taskId)

  private fun latestVisibleRunEventLocked(
    run: AgentRunSnapshot,
  ): OpenCrayAgentRunEvent? = displayedLastEvent(
    run = run,
    recentEvents = mergedRuntimeEventsLocked(
      sessionId = run.sessionId,
      runs = runtimeSession(run.sessionId).listRuns(),
    ),
    isInternalCheckpointEvent = ::isInternalPromptCheckpointEvent,
  ) ?: run.lastEvent

  private fun isLlmRetryPausedAwaitingResumeRun(run: AgentRunSnapshot): Boolean =
    chatRunIsLlmRetryPausedAwaitingResume(run)

  private fun isInterruptedOnRestoreRun(run: AgentRunSnapshot): Boolean =
    chatRunIsInterruptedOnRestore(run)

  private fun requiresExplicitRetryAfterRestoreLocked(sessionId: String): Boolean =
    latestRunForSnapshot(runtimeSession(sessionId).listRuns())
      ?.let { run -> !run.isActive && isInterruptedOnRestoreRun(run) }
      ?: false

  internal fun isChineseHostLocale(): Boolean =
    strings.localeTag.trim().lowercase(Locale.US).startsWith("zh")

  private fun mergedRuntimeEventsLocked(
    sessionId: String,
    runs: List<AgentRunSnapshot>,
  ): List<OpenCrayAgentRunEvent> {
    val hasDurableJournal = runCatching {
      runEventJournalStoreForSession(sessionId).hasEntries()
    }.getOrDefault(false)
    val liveEvents = chatRuntimeEventState.eventsForSession(sessionId)
    val replayedEvents = replayedRuntimeEventsLocked(
      sessionId = sessionId,
      runs = runs,
      liveEvents = liveEvents,
    )
    val merged = ArrayList<OpenCrayAgentRunEvent>(replayedEvents.size + liveEvents.size)
    val seen = linkedSetOf<String>()
    (replayedEvents + liveEvents).forEach { event ->
      if (seen.add(runtimeEventDedupKey(event))) {
        merged += event
      }
    }
    supplementalApprovalEventsLocked(
      sessionId = sessionId,
      runs = runs,
      existingEvents = merged,
    ).forEach { event ->
      if (seen.add(runtimeEventDedupKey(event))) {
        merged += event
      }
    }
    return merged
      .filterNot(::isDebugOnlyRuntimeEvent)
      .let { filtered ->
        if (hasDurableJournal) {
          filtered
        } else {
          filtered.takeLast(MAX_RUNTIME_EVENT_HISTORY)
        }
      }
  }

  private fun isDebugOnlyRuntimeEvent(event: OpenCrayAgentRunEvent): Boolean =
    event is OpenCrayMemoryWriteEvent &&
      event.runId.startsWith(MEMORY_DEBUG_RUN_ID_PREFIX) &&
      event.taskId.startsWith(MEMORY_DEBUG_TASK_ID_PREFIX)

  private fun isInternalPromptCheckpointEvent(event: OpenCrayAgentRunEvent): Boolean =
    event is OpenCraySupplementEvent &&
      event.checkpoint == INTERNAL_PROMPT_CHECKPOINT_MARKER

  private fun supplementalApprovalEventsLocked(
    sessionId: String,
    runs: List<AgentRunSnapshot>,
    existingEvents: List<OpenCrayAgentRunEvent>,
  ): List<OpenCrayAgentRunEvent> {
    val approvals = pendingApprovalsForSession(
      sessionId = sessionId,
      pruneCheckpointState = false,
    )
    if (approvals.isEmpty()) {
      return emptyList()
    }
    val runsByTaskId = runs.associateBy(AgentRunSnapshot::taskId)
    return approvals.mapNotNull { approval ->
      val alreadyPresent = existingEvents.any { event ->
        event is OpenCrayApprovalEvent &&
          event.phase == OpenCrayApprovalPhase.REQUIRED &&
          (event.taskId == approval.taskId || event.runId == approval.runId)
      }
      if (alreadyPresent) {
        return@mapNotNull null
      }
      val run = runsByTaskId[approval.taskId]
      approvalRequiredRuntimeEvent(
        approval = approval,
        emittedAtEpochMs = run?.updatedAtEpochMs ?: System.currentTimeMillis(),
      )
    }
  }

  private fun replayedRuntimeEventsLocked(
    sessionId: String,
    runs: List<AgentRunSnapshot>,
    liveEvents: List<OpenCrayAgentRunEvent>,
  ): List<OpenCrayAgentRunEvent> {
    val journalEvents = runCatching {
      runEventJournalStoreForSession(sessionId).listRuntimeEvents()
    }.getOrDefault(emptyList())
    val transcriptMessages = runCatching {
      transcriptMessagesProvider(sessionId)
    }.getOrDefault(emptyList())
    if (transcriptMessages.isEmpty()) {
      return journalEvents
    }
    val replayedEvents = transcriptMessages.mapIndexedNotNull { index, message ->
      parseReplayedRuntimeEvent(
        message = message,
        sourceIndex = index,
      )
    }
    if (replayedEvents.isEmpty()) {
      return journalEvents
    }
    val replayBackfill = assignReplayEmissionTimes(
      replayedEvents = replayedEvents,
      runs = runs,
      liveEvents = journalEvents + liveEvents,
    )
    if (journalEvents.isEmpty()) {
      return replayBackfill
    }
    val merged = ArrayList<OpenCrayAgentRunEvent>(replayBackfill.size + journalEvents.size)
    val seen = linkedSetOf<String>()
    (replayBackfill + journalEvents).forEach { event ->
      if (seen.add(runtimeEventDedupKey(event))) {
        merged += event
      }
    }
    return merged
  }

  private fun parseReplayedRuntimeEvent(
    message: RuntimeConversationMessage,
    sourceIndex: Int,
  ): ReplayedRuntimeEvent? {
    val content = message.content.trim()
    val replayedPayload = message.replayedRuntimePayloadOrNull()
    val event = replayedPayload?.let { replay ->
      when (replay.kind) {
        ReplayedRuntimeEventKind.TOOL_CALL -> parseReplayedToolCallEvent(replay.payload)
        ReplayedRuntimeEventKind.TOOL_RESULT -> parseReplayedToolResultEvent(replay.payload)
        ReplayedRuntimeEventKind.ASSISTANT_PHASE -> parseReplayedAssistantPhaseEvent(replay.payload)
        ReplayedRuntimeEventKind.SUPPLEMENT -> parseReplayedSupplementEvent(replay.payload)
        ReplayedRuntimeEventKind.SUBAGENT -> parseReplayedSubAgentEvent(replay.payload)
      }
    } ?: when {
      content.startsWith("approval_approved") -> parseReplayedApprovalEvent(
        content = content,
        phase = OpenCrayApprovalPhase.APPROVED,
      )

      content.startsWith("approval_rejected") -> parseReplayedApprovalEvent(
        content = content,
        phase = OpenCrayApprovalPhase.REJECTED,
      )

      content.startsWith("run_interrupted") -> parseReplayedCancellationEvent(
        content = content,
      )

      else -> null
    } ?: return null
    return ReplayedRuntimeEvent(
      sourceIndex = sourceIndex,
      event = event,
    )
  }

  private fun parseReplayedToolCallEvent(payload: String): OpenCrayToolCallEvent? {
    val decoded = decodeReplayPayload(payload) ?: return null
    val identifiers = replayIdentifiers(decoded) ?: return null
    val toolName = decoded.replayString("tool_name") ?: return null
    return OpenCrayToolCallEvent(
      runId = identifiers.first,
      taskId = identifiers.second,
      executionId = decoded.replayString("execution_id"),
      executionOrdinal = decoded.replayInt("execution_ordinal"),
      executionKind = decoded.replayString("execution_kind"),
      turn = decoded.replayInt("turn") ?: 0,
      call = AgentToolCall(
        id = decoded.replayString("tool_call_id"),
        toolName = toolName,
        arguments = decoded.replayObject("arguments") ?: JsonObject(emptyMap()),
        reason = decoded.replayString("reason"),
      ),
      emittedAtEpochMs = 0L,
    )
  }

  private fun parseReplayedToolResultEvent(payload: String): OpenCrayToolResultEvent? {
    val decoded = decodeReplayPayload(payload) ?: return null
    val identifiers = replayIdentifiers(decoded) ?: return null
    val toolName = decoded.replayString("tool_name") ?: return null
    val status = decoded.replayString("status")
      ?.let(::parseReplayToolResultStatus)
      ?: AgentToolResultStatus.SUCCESS
    return OpenCrayToolResultEvent(
      runId = identifiers.first,
      taskId = identifiers.second,
      executionId = decoded.replayString("execution_id"),
      executionOrdinal = decoded.replayInt("execution_ordinal"),
      executionKind = decoded.replayString("execution_kind"),
      turn = decoded.replayInt("turn") ?: 0,
      call = AgentToolCall(
        id = decoded.replayString("tool_call_id"),
        toolName = toolName,
      ),
      result = AgentToolResult(
        toolName = toolName,
        status = status,
        content = decoded.replayString("content")
          ?.takeIf(String::isNotBlank)
          ?: "Tool finished.",
        exitCode = decoded.replayInt("exit_code"),
        stdout = decoded.replayString("stdout").orEmpty(),
        stderr = decoded.replayString("stderr").orEmpty(),
        errorCode = decoded.replayString("error_code"),
        errorMessage = decoded.replayString("error_message"),
        metadata = decoded.replayStringMap("metadata"),
      ),
      emittedAtEpochMs = 0L,
    )
  }

  private fun parseReplayedAssistantPhaseEvent(payload: String): OpenCrayAssistantEvent? {
    val decoded = decodeReplayPayload(payload) ?: return null
    val identifiers = replayIdentifiers(decoded) ?: return null
    val isFinal = decoded.replayString("phase")
      ?.trim()
      ?.lowercase(Locale.US) == "final_answer"
    return OpenCrayAssistantEvent(
      runId = identifiers.first,
      taskId = identifiers.second,
      executionId = decoded.replayString("execution_id"),
      executionOrdinal = decoded.replayInt("execution_ordinal"),
      executionKind = decoded.replayString("execution_kind"),
      turn = decoded.replayInt("turn") ?: 0,
      text = decoded.replayString("text") ?: return null,
      responseFormat = decoded.replayString("response_format"),
      isFinal = isFinal,
      stage = decoded.replayString("stage"),
      emittedAtEpochMs = 0L,
    )
  }

  private fun parseReplayedSupplementEvent(payload: String): OpenCraySupplementEvent? {
    val decoded = decodeReplayPayload(payload) ?: return null
    val identifiers = replayIdentifiers(decoded) ?: return null
    return OpenCraySupplementEvent(
      runId = identifiers.first,
      taskId = identifiers.second,
      executionId = decoded.replayString("execution_id"),
      executionOrdinal = decoded.replayInt("execution_ordinal"),
      executionKind = decoded.replayString("execution_kind"),
      turn = decoded.replayInt("turn") ?: 0,
      entryId = decoded.replayString("entry_id") ?: return null,
      text = decoded.replayString("text") ?: return null,
      checkpoint = decoded.replayString("checkpoint") ?: "turn_start",
      metadata = decoded.replayStringMap("metadata"),
      emittedAtEpochMs = 0L,
    )
  }

  private fun parseReplayedSubAgentEvent(payload: String): OpenCraySubAgentEvent? {
    val decoded = decodeReplayPayload(payload) ?: return null
    val identifiers = replayIdentifiers(decoded) ?: return null
    val phase = decoded.replayString("phase")
      ?.let { rawValue ->
        OpenCraySubAgentPhase.entries.firstOrNull { phase ->
          phase.name.equals(rawValue, ignoreCase = true)
        }
      }
      ?: OpenCraySubAgentPhase.STARTED
    return OpenCraySubAgentEvent(
      runId = identifiers.first,
      taskId = identifiers.second,
      executionId = decoded.replayString("execution_id"),
      executionOrdinal = decoded.replayInt("execution_ordinal"),
      executionKind = decoded.replayString("execution_kind"),
      agentId = decoded.replayString("agent_id"),
      phase = phase,
      childRunId = decoded.replayString("child_run_id") ?: identifiers.first,
      childTaskId = decoded.replayString("child_task_id") ?: identifiers.second,
      label = decoded.replayString("label") ?: "Task",
      subagentType = decoded.replayString("subagent_type") ?: "general-purpose",
      contextMode = decoded.replayString("context_mode") ?: "delegated",
      depth = decoded.replayInt("depth") ?: 1,
      summary = decoded.replayString("summary"),
      executionState = SubAgentExecutionState.fromWireValue(
        decoded.replayString("execution_state"),
      ) ?: SubAgentExecutionState.RUNNING,
      continuationKind = SubAgentContinuationKind.fromWireValue(
        decoded.replayString("continuation_kind"),
      ) ?: SubAgentContinuationKind.NONE,
      resumable = decoded.replayBoolean("resumable") ?: false,
      requiresUserAction = decoded.replayBoolean("requires_user_action") ?: false,
      isHighRisk = decoded.replayBoolean("is_high_risk") ?: false,
      closed = decoded.replayBoolean("closed") ?: false,
      turn = decoded.replayInt("turn"),
      emittedAtEpochMs = 0L,
    )
  }

  private fun parseReplayedApprovalEvent(
    content: String,
    phase: OpenCrayApprovalPhase,
  ): OpenCrayApprovalEvent? {
    val fields = replayTokenFields(content)
    val runId = fields["run_id"]?.trim()?.takeIf { value -> value.isNotBlank() }
      ?: return null
    val taskId = fields["task_id"]?.trim()?.takeIf { value -> value.isNotBlank() }
      ?: runId
    val toolName = fields["tool_name"]?.trim()?.takeIf(String::isNotBlank)
    val isHighRisk = fields["risk"]?.trim()?.equals("high_risk", ignoreCase = true) == true
    return OpenCrayApprovalEvent(
      runId = runId,
      taskId = taskId,
      executionId = replayExecutionId(fields),
      executionOrdinal = replayExecutionOrdinal(fields),
      executionKind = replayExecutionKind(fields),
      phase = phase,
      toolName = toolName,
      text = when (phase) {
        OpenCrayApprovalPhase.REQUIRED -> strings.chatSummaryApprovalRequired
        OpenCrayApprovalPhase.APPROVED -> strings.chatApprovalApproved
        OpenCrayApprovalPhase.REJECTED -> strings.chatApprovalRejected
      },
      isHighRisk = isHighRisk,
      emittedAtEpochMs = 0L,
    )
  }

  private fun parseReplayedCancellationEvent(content: String): OpenCrayCancellationEvent? {
    val fields = replayTokenFields(content)
    val runId = fields["run_id"]?.trim()?.takeIf { value -> value.isNotBlank() }
      ?: return null
    val taskId = fields["task_id"]?.trim()?.takeIf { value -> value.isNotBlank() }
      ?: runId
    val toolName = fields["tool_name"]?.trim()?.takeIf(String::isNotBlank)
    val outcome = fields["outcome"]?.trim()?.takeIf(String::isNotBlank)
    return OpenCrayCancellationEvent(
      runId = runId,
      taskId = taskId,
      executionId = replayExecutionId(fields),
      executionOrdinal = replayExecutionOrdinal(fields),
      executionKind = replayExecutionKind(fields),
      toolName = toolName,
      outcome = outcome,
      text = cancellationTimelineText(
        toolName = toolName,
        localeIsChinese = isChineseHostLocale(),
      ),
      emittedAtEpochMs = 0L,
    )
  }

  private fun decodeReplayPayload(payload: String): JsonObject? =
    runCatching {
      replayJson.parseToJsonElement(payload).jsonObject
    }.getOrNull()

  private fun replayTokenFields(content: String): Map<String, String> =
    content
      .trim()
      .split(' ')
      .drop(1)
      .mapNotNull { token ->
        val separatorIndex = token.indexOf('=')
        if (separatorIndex <= 0 || separatorIndex >= token.lastIndex) {
          return@mapNotNull null
        }
        val key = token.substring(0, separatorIndex).trim()
        val value = token.substring(separatorIndex + 1).trim()
        if (key.isEmpty() || value.isEmpty()) {
          null
        } else {
          key to value
        }
      }
      .toMap(linkedMapOf())

  private fun replayIdentifiers(payload: JsonObject): Pair<String, String>? {
    val runId = payload.replayString("run_id")
      ?: payload.replayString("task_id")
      ?: return null
    val taskId = payload.replayString("task_id") ?: runId
    return runId to taskId
  }

  private fun replayExecutionId(fields: Map<String, String>): String? =
    fields["execution_id"]?.trim()?.takeIf(String::isNotBlank)

  private fun replayExecutionOrdinal(fields: Map<String, String>): Int? =
    fields["execution_ordinal"]?.trim()?.toIntOrNull()

  private fun replayExecutionKind(fields: Map<String, String>): String? =
    fields["execution_kind"]?.trim()?.takeIf(String::isNotBlank)

  private fun parseReplayToolResultStatus(raw: String): AgentToolResultStatus? =
    AgentToolResultStatus.entries.firstOrNull { status ->
      status.name.equals(raw, ignoreCase = true)
    }

  private fun assignReplayEmissionTimes(
    replayedEvents: List<ReplayedRuntimeEvent>,
    runs: List<AgentRunSnapshot>,
    liveEvents: List<OpenCrayAgentRunEvent>,
  ): List<OpenCrayAgentRunEvent> {
    val replayCountByRun = replayedEvents.groupingBy { replay ->
      replayRunGroupKey(
        event = replay.event,
        sourceIndex = replay.sourceIndex,
      )
    }.eachCount()
    val emittedCountByRun = linkedMapOf<String, Int>()
    val runsByRunId = runs.associateBy(AgentRunSnapshot::runId)
    val runsByTaskId = runs.associateBy(AgentRunSnapshot::taskId)
    return replayedEvents.map { replay ->
      val groupKey = replayRunGroupKey(
        event = replay.event,
        sourceIndex = replay.sourceIndex,
      )
      val emittedCount = emittedCountByRun[groupKey] ?: 0
      emittedCountByRun[groupKey] = emittedCount + 1
      val liveEventsForRun = liveEvents.filter { liveEvent ->
        liveEvent.runId == replay.event.runId ||
          (replay.event.runId.isBlank() && liveEvent.taskId == replay.event.taskId)
      }
      val lifecycleStartAt = liveEventsForRun
        .mapNotNull { liveEvent ->
          (liveEvent as? OpenCrayLifecycleEvent)
            ?.takeIf { event -> event.phase == OpenCrayRunLifecyclePhase.START }
            ?.emittedAtEpochMs
        }
        .minOrNull()
      val firstLiveAt = liveEventsForRun.minOfOrNull(OpenCrayAgentRunEvent::emittedAtEpochMs)
      val runSnapshot = runsByRunId[replay.event.runId] ?: runsByTaskId[replay.event.taskId]
      val replayBaseTime = when {
        lifecycleStartAt != null -> lifecycleStartAt + 1L
        firstLiveAt != null -> (firstLiveAt - (replayCountByRun[groupKey] ?: 1).toLong()).coerceAtLeast(1L)
        runSnapshot != null -> (runSnapshot.updatedAtEpochMs - (replayCountByRun[groupKey] ?: 1).toLong()).coerceAtLeast(1L)
        else -> (replay.sourceIndex + 1).toLong()
      }
      replay.event.withEmittedAtEpochMs(replayBaseTime + emittedCount.toLong())
    }
  }

  private fun replayRunGroupKey(
    event: OpenCrayAgentRunEvent,
    sourceIndex: Int,
  ): String = event.runId
    .takeIf(String::isNotBlank)
    ?: event.taskId
      .takeIf(String::isNotBlank)
      ?: "replay-$sourceIndex"

  internal fun findRunSnapshotLocked(runId: String): AgentRunSnapshot? {
    val sessionIds = chatSessionStore.loadState().sessions
      .mapTo(linkedSetOf()) { session -> session.sessionId }
    return sessionIds.firstNotNullOfOrNull { sessionId ->
      runtimeSession(sessionId).findRun(runId)
    }
  }

  private fun findRunSnapshotForIdentifierLocked(runIdOrTaskId: String): AgentRunSnapshot? {
    val byRunId = findRunSnapshotLocked(runIdOrTaskId)
    if (byRunId != null) {
      return byRunId
    }
    return findChatRunSnapshotForIdentifier(
      sessionIds = knownSessionIdsLocked(),
      runtimeHostAccess = runtimeHostAccess,
      runIdOrTaskId = runIdOrTaskId,
    )
  }

  internal fun waitForRunSnapshot(runId: String, timeoutMs: Long): AgentRunSnapshot? {
    val boundedTimeoutMs = timeoutMs.coerceAtLeast(0L)
    val existing = synchronized(lock) { findRunSnapshotLocked(runId) }
    if (existing != null) {
      return runtimeSession(existing.sessionId).waitForRun(runId, boundedTimeoutMs)
    }
    val deadline = System.currentTimeMillis() + boundedTimeoutMs
    while (true) {
      val discovered = synchronized(lock) { findRunSnapshotLocked(runId) }
      if (discovered != null) {
        return runtimeSession(discovered.sessionId).waitForRun(
          runId,
          (deadline - System.currentTimeMillis()).coerceAtLeast(0L),
        )
      }
      if (System.currentTimeMillis() >= deadline) {
        return null
      }
      Thread.sleep(RUN_LOOKUP_POLL_INTERVAL_MS)
    }
  }

  internal fun runSubmissionToMap(submission: AgentRunSubmission): Map<String, Any?> =
    com.opencray.app.projection.runSubmissionToMap(submission)

  internal fun runSnapshotToMap(run: AgentRunSnapshot): Map<String, Any?> =
    runSnapshotToMap(
      run = run,
      finalAttachmentsForRun = ::finalAttachmentsForRunLocked,
      recoveryPlanForRun = ::recoveryPlanForRun,
      runtimeEventMapper = ::runtimeEventToMap,
    )

  private fun recoveryPlanForRun(run: AgentRunSnapshot): RunRecoveryPlan? =
    com.opencray.app.projection.recoveryPlanForRun(
      run = run,
      approvalStateForTask = ::approvalStateForTaskLocked,
      promptCheckpointStoreFor = ::promptCheckpointStoreForSession,
      journalStoreFor = ::runEventJournalStoreForSession,
      planner = recoveryPlanner,
    )

  internal fun newMemoryDebugActionAuditEntry(
    recordId: String,
    action: MemoryOperatorAction,
    sessionId: String,
  ): MemoryDebugActionAuditEntry {
    val occurredAtEpochMs = System.currentTimeMillis()
    return MemoryDebugActionAuditEntry(
      entryId = "memory-debug-action-$occurredAtEpochMs-${UUID.randomUUID().toString().take(8)}",
      recordId = recordId,
      action = action.wireValue,
      sessionId = sessionId,
      runId = "$MEMORY_DEBUG_RUN_ID_PREFIX${UUID.randomUUID().toString().take(8)}",
      taskId = "$MEMORY_DEBUG_TASK_ID_PREFIX${action.wireValue}-${UUID.randomUUID().toString().take(8)}",
      occurredAtEpochMs = occurredAtEpochMs,
    )
  }

  private fun runtimeEventToMap(event: OpenCrayAgentRunEvent): Map<String, Any?> =
    runtimeEventToMap(
      event = event,
      hasPromptResumeCheckpointMetadata = ::hasPromptResumeCheckpointMetadata,
      supplementMetadataSnapshot = ::supplementMetadataSnapshot,
      toolResultMetadataSnapshot = ::toolResultMetadataSnapshot,
    )


  private fun isApprovalRequiredResult(result: ExecutionResult): Boolean =
    result.status == ExecutionStatus.DENIED && isApprovalRequiredError(result.errorCode)

  private fun isLlmRetryPausedResult(result: ExecutionResult): Boolean =
    result.errorCode == ERROR_LLM_RETRY_EXHAUSTED_AWAITING_RESUME

  private fun isApprovalRequiredError(errorCode: String?): Boolean =
    errorCode == ERROR_APPROVAL_REQUIRED || errorCode == ERROR_HIGH_RISK_APPROVAL_REQUIRED

  private fun isVisibleChatMessage(message: ChatTranscriptMessageEntry): Boolean =
    message.role != ChatTranscriptRole.SYSTEM

  private fun displaySessionTitle(rawTitle: String): String =
    if (rawTitle == ChatSessionLocalStore.DEFAULT_SESSION_TITLE) {
      strings.chatDefaultSessionTitle
    } else {
      rawTitle
    }

  private fun chatMessageToMap(message: ChatTranscriptMessageEntry): Map<String, Any?> {
    val resolvedText = message.text ?: chatSessionStore.promptTemplateBody(message.promptTemplateRefId).orEmpty()
    val kind = when (message.role) {
      ChatTranscriptRole.USER -> "outbound"
      ChatTranscriptRole.ASSISTANT -> "inbound"
      ChatTranscriptRole.TOOL -> "timeline"
      ChatTranscriptRole.SYSTEM -> "timeline"
    }
    val visibleText = when (message.role) {
      ChatTranscriptRole.ASSISTANT -> sanitizePotentialInternalAgentText(
        text = resolvedText,
        fallback = strings.agentInternalPayloadHidden,
      )

      else -> resolvedText
    }
    return chatMessageSnapshotMap(
      messageId = message.messageId,
      kind = kind,
      text = visibleText,
      createdAtEpochMs = message.createdAtEpochMs,
      attachments = message.attachments.map(::chatAttachmentSnapshotMap),
    )
  }

  private fun chatMessageSnapshotMap(
    messageId: String,
    kind: String,
    text: String,
    meta: String = "",
    createdAtEpochMs: Long? = null,
    isEphemeral: Boolean = false,
    attachments: List<Map<String, Any?>> = emptyList(),
  ): Map<String, Any?> = buildMap {
    put("messageId", messageId)
    put("kind", kind)
    put("text", text)
    put("meta", meta)
    createdAtEpochMs?.let { timestamp ->
      put("createdAtEpochMs", timestamp)
    }
    put("isEphemeral", isEphemeral)
    if (attachments.isNotEmpty()) {
      put("attachments", attachments)
    }
  }

  private fun chatAttachmentSnapshotMap(
    attachment: ChatAttachmentEntry,
  ): Map<String, Any?> = buildMap {
    put("attachmentId", attachment.attachmentId)
    put(
      "kind",
      when (attachment.kind) {
        com.opencray.persistence.model.ChatAttachmentKind.IMAGE -> "image"
        com.opencray.persistence.model.ChatAttachmentKind.VOICE,
        com.opencray.persistence.model.ChatAttachmentKind.AUDIO -> "voice"
        com.opencray.persistence.model.ChatAttachmentKind.FILE -> "file"
      },
    )
    put("displayName", attachment.displayName)
    put("localPath", attachment.localPath)
    attachment.mimeType?.let { mimeType -> put("mimeType", mimeType) }
    attachment.sizeBytes?.let { sizeBytes -> put("sizeBytes", sizeBytes) }
    attachment.widthPx?.let { widthPx -> put("widthPx", widthPx) }
    attachment.heightPx?.let { heightPx -> put("heightPx", heightPx) }
    attachment.durationMs?.let { durationMs -> put("durationMs", durationMs) }
    if (attachment.waveformBars.isNotEmpty()) {
      put("waveformBars", attachment.waveformBars)
    }
    attachment.transcriptText?.let { transcriptText -> put("transcriptText", transcriptText) }
    attachment.contentSha256?.let { contentSha256 -> put("contentSha256", contentSha256) }
  }

  private fun finalAttachmentsForRunLocked(
    run: AgentRunSnapshot,
  ): List<ChatAttachmentEntry> {
    val pendingMessageId = run.pendingMessageId
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: return emptyList()
    return chatSessionStore.loadSession(run.sessionId)
      ?.messages
      ?.firstOrNull { message ->
        message.messageId == pendingMessageId &&
          message.role == ChatTranscriptRole.ASSISTANT
      }
      ?.attachments
      .orEmpty()
  }

  private fun finalTextForLocked(
    sessionId: String,
    task: AgentTask,
    result: ExecutionResult,
  ): String = finalTextForRunLocked(
    sessionId = sessionId,
    runId = runIdFor(task),
    result = result,
    allowToolSummaryFallback = task.type != AgentTaskType.PROMPT,
  )

  private fun finalTextForRunLocked(
    sessionId: String,
    runId: String,
    result: ExecutionResult,
    allowToolSummaryFallback: Boolean = true,
  ): String {
    if (isLlmRetryPausedResult(result)) {
      return llmRetryPausedMessage()
    }
    val toolSummaryFallback = if (allowToolSummaryFallback) {
      successfulToolSummaryFallbackTextForRunLocked(
        sessionId = sessionId,
        runId = runId,
        result = result,
      )
    } else {
      null
    }
    val rawText = when (result.status) {
      ExecutionStatus.SUCCESS -> {
        if (result.stdout.isBlank() && hasFinalAttachments(result)) {
          ""
        } else {
          result.stdout.ifBlank { toolSummaryFallback ?: strings.agentEmptyAnswer }
        }
      }
      ExecutionStatus.CANCELLED -> strings.agentCancelled
      ExecutionStatus.DENIED -> result.errorMessage ?: strings.agentFailed(
        result.errorCode ?: result.status.name,
      )
      ExecutionStatus.FAILED -> toolSummaryFallback ?: if (
        result.errorCode == AppAgentSessionTaskRuntimeFactory.ERROR_CODE_MISSING_LLM_CONFIG
      ) {
        strings.agentMissingLlm
      } else {
        strings.agentFailed(result.errorMessage ?: result.errorCode ?: result.status.name)
      }

      else -> toolSummaryFallback ?: strings.agentFailed(
        result.errorMessage ?: result.errorCode ?: result.status.name,
      )
    }
    return sanitizePotentialInternalAgentText(
      text = rawText,
      fallback = when (result.status) {
        ExecutionStatus.SUCCESS -> toolSummaryFallback ?: strings.agentInternalPayloadHidden
        ExecutionStatus.DENIED -> approvalFallbackBody(
          isHighRisk = result.errorCode == ERROR_HIGH_RISK_APPROVAL_REQUIRED,
          strings = strings,
        )
        else -> toolSummaryFallback ?: strings.agentInternalPayloadHidden
      },
    )
  }

  private fun llmRetryPausedMessage(): String = if (
    strings.localeTag.startsWith("zh", ignoreCase = true)
  ) {
    "语言模型重试次数已耗尽，这次运行已暂停。发送下一条消息或手动继续后，会从当前检查点恢复。"
  } else {
    "LLM retries were exhausted, so this run is paused. Send another message or resume it to continue from the current checkpoint."
  }

  fun searchMemoryDebug(query: String): Map<String, Any?> =
    chatRuntimeGateway.searchMemoryDebug(query)

  private fun successfulToolSummaryFallbackTextLocked(
    sessionId: String,
    task: AgentTask,
    result: ExecutionResult,
  ): String? {
    if (task.type == AgentTaskType.PROMPT) {
      return null
    }
    return successfulToolSummaryFallbackTextForRunLocked(
      sessionId = sessionId,
      runId = runIdFor(task),
      result = result,
    )
  }

  private fun successfulToolSummaryFallbackTextForRunLocked(
    sessionId: String,
    runId: String,
    result: ExecutionResult,
  ): String? {
    if (
      hasFinalAttachments(result) ||
      !shouldUseSuccessfulToolSummaryFallback(result)
    ) {
      return null
    }
    val latestToolResult = latestSuccessfulToolResultEventForRunLocked(
      sessionId = sessionId,
      runId = runId,
    ) ?: return null
    val orderedEvents = chatRuntimeEventState.eventsForSession(sessionId)
      .filter { event -> event.runId == runId }
    val eventIndex = orderedEvents.indexOfLast { candidate -> candidate === latestToolResult }
    val pairedToolCall = if (eventIndex > 0) {
      previousToolCallEvent(
        orderedEvents = orderedEvents,
        beforeIndex = eventIndex,
        resultEvent = latestToolResult,
      )
    } else {
      null
    }
    return chatToolResultText(
      event = latestToolResult,
      pairedToolCall = pairedToolCall,
      localeIsChinese = isChineseHostLocale(),
    ).trim().takeIf(String::isNotBlank)
  }

  private fun shouldUseSuccessfulToolSummaryFallback(
    result: ExecutionResult,
  ): Boolean {
    val responseFormat = result.metadata["responseFormat"].orEmpty()
    val rawStdout = result.stdout.trim()
    return when (result.status) {
      ExecutionStatus.SUCCESS -> rawStdout.isBlank() || looksLikeInternalToolPayload(rawStdout)
      ExecutionStatus.FAILED,
      ExecutionStatus.TIMEOUT,
      -> {
        result.metadata["llmStatus"]?.isNotBlank() == true ||
          responseFormat == "protocol_error_exhausted"
      }

      else -> false
    }
  }

  private fun latestSuccessfulToolResultEventForTaskLocked(
    sessionId: String,
    task: AgentTask,
  ): OpenCrayToolResultEvent? = latestSuccessfulToolResultEventForRunLocked(
    sessionId = sessionId,
    runId = runIdFor(task),
  )

  private fun latestSuccessfulToolResultEventForRunLocked(
    sessionId: String,
    runId: String,
  ): OpenCrayToolResultEvent? {
    return chatRuntimeEventState.eventsForSession(sessionId)
      .asReversed()
      .firstNotNullOfOrNull { event ->
        (event as? OpenCrayToolResultEvent)
          ?.takeIf { toolEvent ->
            toolEvent.runId == runId &&
              toolEvent.result.status == AgentToolResultStatus.SUCCESS
          }
      }
  }

  private fun hasFinalAttachments(result: ExecutionResult): Boolean =
    result.metadata[OpenCrayExecutionMetadataKeys.FINAL_ATTACHMENTS_JSON]
      ?.trim()
      ?.isNotEmpty() == true

  private fun finalAttachmentRequestsForResult(
    result: ExecutionResult,
  ): List<OpenCrayFinalAttachment> {
    val attachmentsJson = result.metadata[OpenCrayExecutionMetadataKeys.FINAL_ATTACHMENTS_JSON]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: return emptyList()
    return runCatching {
      Json.decodeFromString(
        ListSerializer(OpenCrayFinalAttachment.serializer()),
        attachmentsJson,
      )
    }.getOrDefault(emptyList())
  }

  private fun finalAttachmentArchiveForResultLocked(
    sessionId: String,
    task: AgentTask,
    result: ExecutionResult,
    compatibilityAttachments: List<OpenCrayFinalAttachment> = emptyList(),
  ): FinalAttachmentArchiveResult = finalAttachmentArchiveForResultLocked(
    sessionId = sessionId,
    runId = runIdFor(task),
    result = result,
    compatibilityAttachments = compatibilityAttachments,
  )

  private fun finalAttachmentArchiveForResultLocked(
    sessionId: String,
    runId: String,
    result: ExecutionResult,
    compatibilityAttachments: List<OpenCrayFinalAttachment> = emptyList(),
  ): FinalAttachmentArchiveResult {
    val explicitAttachments = finalAttachmentRequestsForResult(result)
    if (explicitAttachments.isEmpty() && compatibilityAttachments.isEmpty()) {
      return FinalAttachmentArchiveResult()
    }
    val workspaceRoot = workspaceRootProvider?.invoke()
      ?: return FinalAttachmentArchiveResult(
        failureText = strings.agentAttachmentSaveFailed("workspace is unavailable"),
      )
    val resolvedExplicitAttachments = resolveFinalChatAttachmentsLocked(
      sessionId = sessionId,
      attachments = resolveFinalAttachmentArtifactsLocked(
        sessionId = sessionId,
        runId = runId,
        attachments = explicitAttachments,
      ),
    )
    val resolvedAttachments = dedupeFinalAttachments(
      attachments = resolvedExplicitAttachments + compatibilityAttachments,
    )
    val archivableAttachments = resolvedAttachments.filterNot(::isUnresolvedFinalAttachmentReference)
    return runCatching {
      val archivedAttachments = AppChatAttachmentArchiver.archive(
        workspaceRoot = workspaceRoot,
        approvedReadRoots = approvedReadRootsProvider().roots,
        sessionId = sessionId,
        attachments = archivableAttachments,
        voiceMetadataAnalyzer = NoOpVoiceMetadataAnalyzer,
      )
      FinalAttachmentArchiveResult(
        attachments = archivedAttachments,
        failureText = missingAttachmentFailureText(
          requestedCount = resolvedAttachments.size,
          archivedCount = archivedAttachments.size,
        ),
      )
    }.getOrElse { throwable ->
      FinalAttachmentArchiveResult(
        failureText = strings.agentAttachmentSaveFailed(
          throwable.message?.trim()?.takeIf(String::isNotBlank) ?: throwable::class.java.simpleName,
        ),
      )
    }
  }

  private fun isUnresolvedFinalAttachmentReference(attachment: OpenCrayFinalAttachment): Boolean =
    attachment.relativePath.isNullOrBlank() &&
      attachment.path.isNullOrBlank() &&
      (
        !attachment.artifactId.isNullOrBlank() ||
          !attachment.chatAttachmentId.isNullOrBlank()
        )

  private fun missingAttachmentFailureText(
    requestedCount: Int,
    archivedCount: Int,
  ): String? {
    val missingCount = requestedCount - archivedCount
    if (missingCount <= 0) {
      return null
    }
    val detail = if (missingCount == 1) {
      "1 attachment was missing, outside approved roots, or unsupported"
    } else {
      "$missingCount attachments were missing, outside approved roots, or unsupported"
    }
    return strings.agentAttachmentSaveFailed(detail)
  }

  private fun finalizedAssistantText(
    text: String,
    attachments: List<ChatAttachmentEntry>,
    attachmentFailureText: String? = null,
  ): String {
    val failureText = attachmentFailureText?.trim()?.takeIf(String::isNotBlank)
    val baseText = if (text.isBlank() && attachments.isNotEmpty()) {
      ""
    } else {
      text
    }
    return when {
      failureText == null -> baseText
      baseText.isBlank() -> failureText
      else -> "$baseText\n\n$failureText"
    }
  }

  private fun repairRestoredTerminalMessagesLocked(
    sessionId: String,
    runs: List<AgentRunSnapshot>,
  ) {
    runs
      .asSequence()
      .filter(AgentRunSnapshot::isTerminal)
      .sortedBy(AgentRunSnapshot::acceptedAtEpochMs)
      .forEach { run ->
        val pendingMessageId = run.pendingMessageId?.trim()?.takeIf(String::isNotBlank) ?: return@forEach
        val repaired = restoredTerminalMessageForRunLocked(
          sessionId = sessionId,
          run = run,
        ) ?: return@forEach
        val message = chatSessionStore.loadSession(sessionId)
          ?.messages
          ?.firstOrNull { candidate -> candidate.messageId == pendingMessageId }
          ?: return@forEach
        if (
          message.role == ChatTranscriptRole.ASSISTANT &&
          message.text.orEmpty() == repaired.text &&
          message.attachments == repaired.attachments
        ) {
          return@forEach
        }
        clearAssistantDraftLocked(
          sessionId = sessionId,
          pendingMessageId = pendingMessageId,
        )
        chatSessionStore.replaceMessage(
          sessionId = sessionId,
          messageId = pendingMessageId,
          role = ChatTranscriptRole.ASSISTANT,
          text = repaired.text,
          attachments = repaired.attachments,
        )
      }
  }

  private fun restoredTerminalMessageForRunLocked(
    sessionId: String,
    run: AgentRunSnapshot,
  ): RestoredTerminalMessage? {
    val result = restoredTerminalResultForRunLocked(
      sessionId = sessionId,
      run = run,
    ) ?: return null
    val baseFinalText = finalTextForRunLocked(
      sessionId = sessionId,
      runId = run.runId,
      result = result,
      allowToolSummaryFallback = false,
    )
    val markdownCompatibility = attachmentMarkdownCompatibilityLocked(
      sessionId = sessionId,
      runId = run.runId,
      text = baseFinalText,
    )
    val finalAttachmentArchive = finalAttachmentArchiveForResultLocked(
      sessionId = sessionId,
      runId = run.runId,
      result = result,
      compatibilityAttachments = markdownCompatibility.attachments,
    )
    val text = finalizedAssistantText(
      text = markdownCompatibility.rewrittenText,
      attachments = finalAttachmentArchive.attachments,
      attachmentFailureText = finalAttachmentArchive.failureText,
    )
    if (text.isBlank() && finalAttachmentArchive.attachments.isEmpty()) {
      return null
    }
    return RestoredTerminalMessage(
      text = text,
      attachments = finalAttachmentArchive.attachments,
    )
  }

  private fun restoredTerminalResultForRunLocked(
    sessionId: String,
    run: AgentRunSnapshot,
  ): ExecutionResult? {
    val status = run.executionStatus ?: when (run.lifecycleState) {
      QueueTaskLifecycleState.COMPLETED -> ExecutionStatus.SUCCESS
      QueueTaskLifecycleState.CANCELLED -> ExecutionStatus.CANCELLED
      QueueTaskLifecycleState.FAILED -> ExecutionStatus.FAILED
      else -> null
    } ?: return null
    return if (status == ExecutionStatus.SUCCESS) {
      synthesizedTerminalSuccessResultForRunLocked(
        sessionId = sessionId,
        run = run,
      )
    } else {
      synthesizedTerminalResultForRun(
        run = run,
        status = status,
      )
    }
  }

  private fun synthesizedTerminalSuccessResultForRunLocked(
    sessionId: String,
    run: AgentRunSnapshot,
  ): ExecutionResult? {
    val event = latestFinalizationAssistantEvent(
      journalEntries = runEventJournalStoreForSession(sessionId).listForRun(run.runId),
      fallbackEvent = run.lastEvent,
    ) ?: return null
    if (
      event.text.isBlank() &&
      !event.metadata.containsKey(OpenCrayExecutionMetadataKeys.FINAL_ATTACHMENTS_JSON)
    ) {
      return null
    }
    val startedAtEpochMs = minOf(run.acceptedAtEpochMs, event.emittedAtEpochMs)
    return ExecutionResult(
      taskId = run.taskId,
      status = ExecutionStatus.SUCCESS,
      stdout = event.text,
      startedAtEpochMs = startedAtEpochMs,
      finishedAtEpochMs = maxOf(startedAtEpochMs, event.emittedAtEpochMs),
      metadata = buildMap {
        putAll(run.resultMetadata)
        putAll(event.metadata)
        event.responseFormat
          ?.trim()
          ?.takeIf(String::isNotBlank)
          ?.let { responseFormat ->
            if (!containsKey("responseFormat")) {
              put("responseFormat", responseFormat)
            }
          }
        run.executionId
          ?.trim()
          ?.takeIf(String::isNotBlank)
          ?.let { executionId ->
            if (!containsKey(METADATA_EXECUTION_ID)) {
              put(METADATA_EXECUTION_ID, executionId)
            }
          }
        if (run.executionOrdinal > 0 && !containsKey(METADATA_EXECUTION_ORDINAL)) {
          put(METADATA_EXECUTION_ORDINAL, run.executionOrdinal.toString())
        }
        run.executionKind
          ?.trim()
          ?.takeIf(String::isNotBlank)
          ?.let { executionKind ->
            if (!containsKey(METADATA_EXECUTION_KIND)) {
              put(METADATA_EXECUTION_KIND, executionKind)
            }
          }
      },
    )
  }

  private fun synthesizedTerminalResultForRun(
    run: AgentRunSnapshot,
    status: ExecutionStatus,
  ): ExecutionResult = ExecutionResult(
    taskId = run.taskId,
    status = status,
    errorCode = run.errorCode,
    errorMessage = run.errorMessage,
    startedAtEpochMs = run.acceptedAtEpochMs,
    finishedAtEpochMs = maxOf(run.acceptedAtEpochMs, run.updatedAtEpochMs),
    metadata = run.resultMetadata,
  )

  private fun latestFinalizationAssistantEvent(
    journalEntries: List<PersistedRunJournalEntry>,
    fallbackEvent: OpenCrayAgentRunEvent?,
  ): OpenCrayAssistantPhaseEvent? = journalEntries
    .asReversed()
    .asSequence()
    .mapNotNull { entry ->
      entry.payload.toRuntimeEventOrNull() as? OpenCrayAssistantPhaseEvent
    }
    .firstOrNull(::hasFinalizationBoundary)
    ?: (fallbackEvent as? OpenCrayAssistantPhaseEvent)?.takeIf(::hasFinalizationBoundary)

  private fun hasFinalizationBoundary(event: OpenCrayAssistantPhaseEvent): Boolean =
    event.isFinal &&
      OpenCrayPromptResumeMetadata.decodeCheckpointBoundary(event.metadata) ==
      OpenCrayPromptCheckpointBoundary.FINALIZATION_COMPLETE

  private fun attachmentMarkdownCompatibilityLocked(
    sessionId: String,
    task: AgentTask,
    text: String,
  ): AttachmentMarkdownCompatibility = attachmentMarkdownCompatibilityLocked(
    sessionId = sessionId,
    runId = runIdFor(task),
    text = text,
  )

  private fun attachmentMarkdownCompatibilityLocked(
    sessionId: String,
    runId: String,
    text: String,
  ): AttachmentMarkdownCompatibility {
    if (text.isBlank()) {
      return AttachmentMarkdownCompatibility(rewrittenText = text)
    }
    val references = parseAttachmentMarkdownReferences(text)
    if (references.isEmpty()) {
      return AttachmentMarkdownCompatibility(rewrittenText = text)
    }
    val candidates = attachmentMarkdownCandidatesLocked(
      sessionId = sessionId,
      runId = runId,
    )
    val resolvedReferences = references.map { reference ->
      ResolvedAttachmentMarkdownReference(
        reference = reference,
        attachment = resolveAttachmentMarkdownReference(
          reference = reference,
          candidates = candidates,
        ),
      )
    }
    return AttachmentMarkdownCompatibility(
      rewrittenText = rewriteAttachmentMarkdownText(
        text = text,
        resolvedReferences = resolvedReferences,
      ),
      attachments = dedupeFinalAttachments(
        resolvedReferences.mapNotNull { resolved ->
          resolved.attachment?.toFinalAttachment(
            forceImage = resolved.reference.isImage,
          )
        },
      ),
    )
  }

  private fun attachmentMarkdownCandidatesLocked(
    sessionId: String,
    task: AgentTask,
  ): List<AttachmentMarkdownCandidate> = attachmentMarkdownCandidatesLocked(
    sessionId = sessionId,
    runId = runIdFor(task),
  )

  private fun attachmentMarkdownCandidatesLocked(
    sessionId: String,
    runId: String,
  ): List<AttachmentMarkdownCandidate> {
    val runCandidates = attachmentArtifactsForRunLocked(
      sessionId = sessionId,
      runId = runId,
      artifactIds = emptySet(),
    ).map { (artifactId, artifact) ->
      AttachmentMarkdownCandidate(
        artifactId = artifactId,
        relativePath = artifact.relativePath,
        displayName = artifact.displayName
          ?.trim()
          ?.takeIf(String::isNotBlank)
          ?: artifact.relativePath.substringAfterLast('/'),
        kindHint = artifact.kindHint,
        mimeType = artifact.mimeType,
        durationMs = artifact.durationMs,
        waveformBars = artifact.waveformBars,
        transcriptText = artifact.transcriptText,
      )
    }
    val sessionCandidates = buildList {
      val seenLocalPaths = linkedSetOf<String>()
      chatSessionStore.loadSession(sessionId)
        ?.messages
        ?.asReversed()
        ?.forEach { message ->
          message.attachments
            .asReversed()
            .forEach attachmentLoop@ { attachment ->
              val localPath = attachment.localPath.trim()
              if (localPath.isBlank() || !seenLocalPaths.add(localPath)) {
                return@attachmentLoop
              }
              add(
                AttachmentMarkdownCandidate(
                  attachmentId = attachment.attachmentId,
                  relativePath = localPath,
                  displayName = attachment.displayName,
                  kindHint = attachment.kind.toWireKind(),
                  mimeType = attachment.mimeType,
                  durationMs = attachment.durationMs,
                  waveformBars = attachment.waveformBars,
                  transcriptText = attachment.transcriptText,
                ),
              )
            }
        }
    }
    return runCandidates + sessionCandidates
  }

  private fun AttachmentMarkdownCandidate.toFinalAttachment(forceImage: Boolean): OpenCrayFinalAttachment =
    OpenCrayFinalAttachment(
      kind = if (forceImage) "image" else kindHint,
      relativePath = relativePath,
      displayName = displayName,
      mimeType = mimeType,
      durationMs = durationMs,
      waveformBars = waveformBars,
      transcriptText = transcriptText,
    )

  private fun dedupeFinalAttachments(
    attachments: List<OpenCrayFinalAttachment>,
  ): List<OpenCrayFinalAttachment> {
    val seen = linkedSetOf<String>()
    return attachments.filter { attachment ->
      val chatAttachmentKey = attachment.chatAttachmentId
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let { chatAttachmentId -> "chat:$chatAttachmentId" }
      val key = attachment.relativePath
        ?.trim()
        ?.replace('\\', '/')
        ?.takeIf(String::isNotBlank)
        ?.let { relativePath -> "relative:$relativePath" }
        ?: chatAttachmentKey
        ?: attachment.path
          ?.trim()
          ?.takeIf(String::isNotBlank)
          ?.let { path -> "path:$path" }
        ?: attachment.artifactId
          ?.trim()
          ?.takeIf(String::isNotBlank)
          ?.let { artifactId -> "artifact:$artifactId" }
        ?: return@filter false
      seen.add(key)
    }
  }

  private fun scheduleVoiceMetadataBackfill(
    attachments: List<ChatAttachmentEntry>,
  ): Boolean {
    val voiceAttachments = attachments.filter { attachment ->
      attachment.kind == ChatAttachmentKind.VOICE
    }
    if (voiceAttachments.isEmpty()) {
      return false
    }
    primeVoiceMetadataCache(voiceAttachments)
    var mergedSynchronously = false
    val cacheStore = voiceMetadataCacheStore
    if (cacheStore != null) {
      val missingMetadataContentHashes = voiceAttachments
        .filter(::hasMissingVoiceMetadata)
        .mapNotNull { attachment -> normalizedContentSha256(attachment.contentSha256) }
        .distinct()
      missingMetadataContentHashes.forEach { contentSha256 ->
        val cachedMetadata = cacheStore.get(contentSha256) ?: return@forEach
        if (chatSessionStore.mergeVoiceAttachmentMetadata(contentSha256, cachedMetadata)) {
          mergedSynchronously = true
        }
      }
      if (mergedSynchronously) {
        emitChatSnapshot()
      }
    }
    voiceAttachments
      .filter(::requiresVoiceMetadataAnalysis)
      .mapNotNull(::voiceMetadataBackfillCandidateFor)
      .distinctBy(VoiceMetadataBackfillCandidate::contentSha256)
      .forEach { candidate ->
        if (cacheStore?.get(candidate.contentSha256)?.let(::hasAnalyzedVoiceMetadata) == true) {
          return@forEach
        }
        if (!voiceMetadataBackfillInFlight.add(candidate.contentSha256)) {
          return@forEach
        }
        voiceMetadataBackfillExecutor.execute {
          try {
            resolveVoiceMetadataBackfill(candidate)
          } finally {
            voiceMetadataBackfillInFlight.remove(candidate.contentSha256)
          }
        }
      }
    return mergedSynchronously
  }

  private fun primeVoiceMetadataCache(attachments: List<ChatAttachmentEntry>) {
    val cacheStore = voiceMetadataCacheStore ?: return
    attachments.forEach { attachment ->
      val contentSha256 = normalizedContentSha256(attachment.contentSha256) ?: return@forEach
      val metadata = AppAgentWorkspaceVoiceMetadata(
        durationMs = attachment.durationMs,
        waveformBars = attachment.waveformBars,
        transcriptText = attachment.transcriptText,
      ).normalized()
      if (!metadata.isMeaningful()) {
        return@forEach
      }
      cacheStore.put(contentSha256, metadata)
    }
  }

  private fun chatDraftAttachmentMap(
    attachment: ImportedChatAttachmentDraft,
  ): Map<String, Any?> = buildMap {
    put("kind", attachment.kind)
    put("displayName", attachment.displayName)
    put("relativePath", attachment.relativePath)
    attachment.mimeType?.let { mimeType -> put("mimeType", mimeType) }
    attachment.sizeBytes?.let { sizeBytes -> put("sizeBytes", sizeBytes) }
  }

  private fun hasMissingVoiceMetadata(attachment: ChatAttachmentEntry): Boolean =
    attachment.kind == ChatAttachmentKind.VOICE &&
      (
        attachment.durationMs == null ||
          attachment.waveformBars.isEmpty() ||
          attachment.transcriptText.isNullOrBlank()
        )

  private fun requiresVoiceMetadataAnalysis(attachment: ChatAttachmentEntry): Boolean =
    attachment.kind == ChatAttachmentKind.VOICE &&
      (
        attachment.durationMs == null ||
          attachment.waveformBars.isEmpty()
        )

  private fun voiceMetadataBackfillCandidateFor(
    attachment: ChatAttachmentEntry,
  ): VoiceMetadataBackfillCandidate? {
    val contentSha256 = normalizedContentSha256(attachment.contentSha256) ?: return null
    val localPath = attachment.localPath.trim().takeIf(String::isNotBlank) ?: return null
    return VoiceMetadataBackfillCandidate(
      contentSha256 = contentSha256,
      localPath = localPath,
      mimeType = attachment.mimeType?.trim()?.takeIf(String::isNotBlank),
    )
  }

  private fun resolveVoiceMetadataBackfill(candidate: VoiceMetadataBackfillCandidate) {
    val workspaceRoot = workspaceRootProvider?.invoke() ?: return
    val normalizedWorkspaceRoot = workspaceRoot.toAbsolutePath().normalize()
    val resolvedPath = normalizedWorkspaceRoot
      .resolve(candidate.localPath)
      .normalize()
    if (!resolvedPath.startsWith(normalizedWorkspaceRoot) || !Files.isRegularFile(resolvedPath)) {
      return
    }
    val metadata = voiceMetadataAnalyzer.analyze(
      path = resolvedPath,
      mimeType = candidate.mimeType,
    )?.normalized() ?: return
    if (!metadata.isMeaningful()) {
      return
    }
    voiceMetadataCacheStore?.put(candidate.contentSha256, metadata)
    if (chatSessionStore.mergeVoiceAttachmentMetadata(candidate.contentSha256, metadata)) {
      emitChatSnapshot()
    }
  }

  private fun normalizedContentSha256(value: String?): String? =
    value
      ?.trim()
      ?.lowercase(Locale.US)
      ?.takeIf(String::isNotBlank)

  private fun AppAgentWorkspaceVoiceMetadata.normalized(): AppAgentWorkspaceVoiceMetadata =
    AppAgentWorkspaceVoiceMetadata(
      durationMs = durationMs?.takeIf { value -> value >= 0L },
      waveformBars = waveformBars.map { value -> value.coerceIn(0, 100) },
      transcriptText = transcriptText?.trim()?.takeIf(String::isNotBlank),
    )

  private fun AppAgentWorkspaceVoiceMetadata.isMeaningful(): Boolean =
    durationMs != null || waveformBars.isNotEmpty() || !transcriptText.isNullOrBlank()

  private fun hasAnalyzedVoiceMetadata(metadata: AppAgentWorkspaceVoiceMetadata): Boolean =
    metadata.durationMs != null && metadata.waveformBars.isNotEmpty()

  private fun resolveFinalAttachmentArtifactsLocked(
    sessionId: String,
    runId: String,
    attachments: List<OpenCrayFinalAttachment>,
  ): List<OpenCrayFinalAttachment> {
    val requestedArtifactIds = attachments.mapNotNull { attachment ->
      if (!attachment.relativePath.isNullOrBlank() || !attachment.path.isNullOrBlank()) {
        return@mapNotNull null
      }
      attachment.artifactId?.trim()?.takeIf(String::isNotBlank)
    }.toSet()
    if (requestedArtifactIds.isEmpty()) {
      return attachments
    }
    val artifactsById = attachmentArtifactsForRunLocked(
      sessionId = sessionId,
      runId = runId,
      artifactIds = requestedArtifactIds,
    ).toMutableMap()
    val workspaceRoot = workspaceRootProvider?.invoke()
    (requestedArtifactIds - artifactsById.keys).forEach { artifactId ->
      resolveWorkspaceMediaArtifact(
        workspaceRoot = workspaceRoot,
        artifactId = artifactId,
      )?.let { artifact ->
        artifactsById[artifact.artifactId] = artifact.toResolvedAttachmentArtifact()
      }
    }
    return attachments.map { attachment ->
      if (!attachment.relativePath.isNullOrBlank() || !attachment.path.isNullOrBlank()) {
        return@map attachment
      }
      val artifactId = attachment.artifactId?.trim()?.takeIf(String::isNotBlank)
        ?: return@map attachment
      val artifact = artifactsById[artifactId] ?: return@map attachment
      attachment.copy(
        kind = attachment.kind ?: artifact.kindHint,
        relativePath = artifact.relativePath,
        displayName = attachment.displayName ?: artifact.displayName,
        mimeType = attachment.mimeType ?: artifact.mimeType,
        durationMs = attachment.durationMs ?: artifact.durationMs,
        waveformBars = attachment.waveformBars.ifEmpty { artifact.waveformBars },
        transcriptText = attachment.transcriptText ?: artifact.transcriptText,
      )
    }
  }

  private fun resolveFinalChatAttachmentsLocked(
    sessionId: String,
    attachments: List<OpenCrayFinalAttachment>,
  ): List<OpenCrayFinalAttachment> {
    val requestedChatAttachmentIds = attachments.mapNotNull { attachment ->
      if (
        !attachment.relativePath.isNullOrBlank() ||
        !attachment.path.isNullOrBlank() ||
        !attachment.artifactId.isNullOrBlank()
      ) {
        return@mapNotNull null
      }
      attachment.chatAttachmentId?.trim()?.takeIf(String::isNotBlank)
    }.toSet()
    if (requestedChatAttachmentIds.isEmpty()) {
      return attachments
    }
    val attachmentsById = buildMap<String, ChatAttachmentEntry> {
      chatSessionStore.loadSession(sessionId)
        ?.messages
        ?.asReversed()
        ?.forEach { message ->
          message.attachments
            .asReversed()
            .forEach { attachment ->
              val attachmentId = attachment.attachmentId.trim()
              if (attachmentId.isNotEmpty() && !containsKey(attachmentId)) {
                put(attachmentId, attachment)
              }
            }
        }
    }
    return attachments.map { attachment ->
      if (
        !attachment.relativePath.isNullOrBlank() ||
        !attachment.path.isNullOrBlank() ||
        !attachment.artifactId.isNullOrBlank()
      ) {
        return@map attachment
      }
      val chatAttachmentId = attachment.chatAttachmentId?.trim()?.takeIf(String::isNotBlank)
        ?: return@map attachment
      val sessionAttachment = attachmentsById[chatAttachmentId] ?: return@map attachment
      attachment.copy(
        kind = attachment.kind ?: sessionAttachment.kind.toWireKind(),
        relativePath = sessionAttachment.localPath,
        displayName = attachment.displayName ?: sessionAttachment.displayName,
        mimeType = attachment.mimeType ?: sessionAttachment.mimeType,
        durationMs = attachment.durationMs ?: sessionAttachment.durationMs,
        waveformBars = attachment.waveformBars.ifEmpty { sessionAttachment.waveformBars },
        transcriptText = attachment.transcriptText ?: sessionAttachment.transcriptText,
      )
    }
  }

  private fun attachmentArtifactsForRunLocked(
    sessionId: String,
    runId: String,
    artifactIds: Set<String>,
  ): Map<String, ResolvedAttachmentArtifact> {
    val liveEvents = chatRuntimeEventState.eventsForSession(sessionId)
      .filter { event -> event.runId == runId }
      .asReversed()
    val durableEvents = runCatching {
      runEventJournalStoreForSession(sessionId).listForRun(runId)
        .asReversed()
        .mapNotNull { entry -> entry.payload.toRuntimeEventOrNull() }
    }.getOrDefault(emptyList())
    val resolved = linkedMapOf<String, ResolvedAttachmentArtifact>()
    (liveEvents + durableEvents).forEach { event ->
      val toolEvent = event as? OpenCrayToolResultEvent ?: return@forEach
      if (toolEvent.runId != runId) {
        return@forEach
      }
      resolvedAttachmentArtifactsFromMetadata(toolEvent.result.metadata).forEach artifactLoop@ { artifact ->
        if (artifactIds.isNotEmpty() && artifact.artifactId !in artifactIds) {
          return@artifactLoop
        }
        if (artifact.artifactId in resolved) {
          return@artifactLoop
        }
        resolved[artifact.artifactId] = ResolvedAttachmentArtifact(
          relativePath = artifact.relativePath,
          displayName = artifact.displayName,
          kindHint = artifact.kindHint,
          mimeType = artifact.mimeType,
          durationMs = artifact.durationMs,
          waveformBars = artifact.waveformBars,
          transcriptText = artifact.transcriptText,
        )
      }
    }
    return resolved
  }

  private fun OpenCrayAttachmentArtifact.toResolvedAttachmentArtifact(): ResolvedAttachmentArtifact =
    ResolvedAttachmentArtifact(
      relativePath = relativePath,
      displayName = displayName,
      kindHint = kindHint,
      mimeType = mimeType,
      durationMs = durationMs,
      waveformBars = waveformBars,
      transcriptText = transcriptText,
    )

  private fun resolvedAttachmentArtifactsFromMetadata(
    metadata: Map<String, String>,
  ): List<OpenCrayAttachmentArtifact> {
    metadata[OpenCrayAttachmentArtifactMetadataKeys.ARTIFACTS_JSON]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { artifactsJson ->
        runCatching {
          Json.decodeFromString(
            ListSerializer(OpenCrayAttachmentArtifact.serializer()),
            artifactsJson,
          )
        }.getOrNull()?.takeIf(List<OpenCrayAttachmentArtifact>::isNotEmpty)?.let { artifacts ->
          return artifacts
        }
      }
    val artifactId = metadata[OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_ID]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: return emptyList()
    val relativePath = metadata[OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_RELATIVE_PATH]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: return emptyList()
    return listOf(
      OpenCrayAttachmentArtifact(
        artifactId = artifactId,
        relativePath = relativePath,
        displayName = metadata[OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_DISPLAY_NAME]
          ?.trim()
          ?.takeIf(String::isNotBlank),
        kindHint = metadata[OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_KIND_HINT]
          ?.trim()
          ?.takeIf(String::isNotBlank),
        mimeType = metadata[OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_MIME_TYPE]
          ?.trim()
          ?.takeIf(String::isNotBlank),
        durationMs = metadata[OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_DURATION_MS]
          ?.trim()
          ?.toLongOrNull(),
        waveformBars = metadata[OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_WAVEFORM_BARS]
          ?.split(',')
          ?.mapNotNull { value -> value.trim().toIntOrNull() }
          .orEmpty(),
        transcriptText = metadata[OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_TRANSCRIPT_TEXT]
          ?.trim()
          ?.takeIf(String::isNotBlank),
      ),
    )
  }

  private fun drawerPreviewTextLocked(
    sessionId: String,
    fallbackPreview: String,
    includeRuntimePreview: Boolean = true,
  ): String {
    val normalizedFallback = snapshotDrawerPreviewText(
      text = fallbackPreview,
      strings = strings,
    )
    if (!includeRuntimePreview) {
      return normalizedFallback
    }
    val runs = runtimeSession(sessionId).listRuns()
    if (runs.isEmpty()) {
      return normalizedFallback
    }
    val recentEvents = userVisibleRuntimeEvents(
      runs = runs,
      recentEvents = mergedRuntimeEventsLocked(
        sessionId = sessionId,
        runs = runs,
      ),
    )
    val displayedRuns = displayedRunsForSnapshot(
      runs = runs,
      recentEvents = recentEvents,
      userVisibleRuns = ::userVisibleRuns,
      isInternalCheckpointEvent = ::isInternalPromptCheckpointEvent,
    )
    val latestRun = latestRunForSnapshot(displayedRuns) ?: return normalizedFallback
    val pendingApproval = pendingApprovalsForSession(sessionId).firstOrNull { approval ->
      approval.runId == latestRun.runId || approval.taskId == latestRun.taskId
    }
    val shouldOverride = pendingApproval != null ||
      isAwaitingDirectionRun(latestRun) ||
      isDeferredApprovalDecisionAwaitingResumeRun(latestRun) ||
      isDrawerPlaceholderPreview(normalizedFallback, strings = strings)
    val runtimePreview = if (shouldOverride) {
      drawerRuntimePreviewText(
        run = latestRun,
        pendingApproval = pendingApproval,
        recentEvents = recentEvents,
      )
    } else {
      null
    }
    return snapshotDrawerPreviewText(
      text = runtimePreview ?: normalizedFallback,
      strings = strings,
    )
  }

  private fun drawerRuntimePreviewText(
    run: AgentRunSnapshot,
    pendingApproval: PendingApprovalSnapshot?,
    recentEvents: List<OpenCrayAgentRunEvent>,
  ): String? {
    pendingApproval?.let { approval ->
      return if (approval.isHighRisk) {
        strings.chatHighRiskApprovalRequiredBody
      } else {
        strings.chatSummaryApprovalRequired
      }
    }
    if (isAwaitingDirectionRun(run) || isDeferredApprovalDecisionAwaitingResumeRun(run)) {
      return strings.chatSummaryAwaitingDirection
    }
    val lastEvent = run.lastEvent ?: return null
    return when (lastEvent) {
      is OpenCrayAssistantPhaseEvent -> if (lastEvent.isFinal) {
        lastEvent.text.trim().takeIf(String::isNotBlank)
      } else {
        chatProgressText(lastEvent).trim().takeIf(String::isNotBlank)
      }
      is OpenCrayToolCallEvent ->
        chatToolCallText(lastEvent, localeIsChinese = isChineseHostLocale())
          .trim().takeIf(String::isNotBlank)
      is OpenCrayToolResultEvent -> {
        val orderedRunEvents = executionScopedRunEvents(
          run = run,
          recentEvents = recentEvents,
        )
          .sortedWith(
            compareBy<OpenCrayAgentRunEvent> { event -> event.emittedAtEpochMs }
              .thenBy { event -> runtimeEventDedupKey(event) },
          )
        val resultIndex = orderedRunEvents.indexOfLast { event ->
          event is OpenCrayToolResultEvent &&
            runtimeEventDedupKey(event) == runtimeEventDedupKey(lastEvent)
        }
        val pairedToolCall = previousToolCallEvent(
          orderedEvents = orderedRunEvents,
          beforeIndex = if (resultIndex >= 0) resultIndex else orderedRunEvents.size,
          resultEvent = lastEvent,
        )
        chatToolResultText(
          event = lastEvent,
          pairedToolCall = pairedToolCall,
          localeIsChinese = isChineseHostLocale(),
        ).trim().takeIf(String::isNotBlank)
      }
      is OpenCrayApprovalEvent -> when (lastEvent.phase) {
        OpenCrayApprovalPhase.REQUIRED -> {
          if (lastEvent.isHighRisk) {
            strings.chatHighRiskApprovalRequiredBody
          } else {
            strings.chatSummaryApprovalRequired
          }
        }
        OpenCrayApprovalPhase.APPROVED,
        OpenCrayApprovalPhase.REJECTED -> lastEvent.text.trim().takeIf(String::isNotBlank)
      }
      is OpenCraySubAgentEvent -> lastEvent.summary
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: lastEvent.label.trim().takeIf(String::isNotBlank)
      is OpenCraySupplementEvent -> lastEvent.text.trim().takeIf(String::isNotBlank)
      is OpenCrayCancellationEvent -> lastEvent.text.trim().takeIf(String::isNotBlank)
      else -> null
    }
  }

  private fun toolResultMetadataSnapshot(metadata: Map<String, String>): Map<String, String> {
    val allowedKeys = setOf(
      "path",
      "filePath",
      "sourcePath",
      "destinationPath",
      "pattern",
      "glob",
      "entryCount",
      "matchCount",
      "byteCount",
      "totalLineCount",
      "offset",
      "limit",
      "returnedLineCount",
      "truncated",
      "resultLimitApplied",
      "resultTruncated",
      "resultLimitKind",
      "replacementCount",
      "editCount",
      TodoWriteMetadataKeys.TODO_COUNT,
      TodoWriteMetadataKeys.MUTATED,
      TodoWriteMetadataKeys.PLAN_CHANGED,
      TodoWriteMetadataKeys.PENDING_TODO_COUNT,
      TodoWriteMetadataKeys.IN_PROGRESS_TODO_COUNT,
      TodoWriteMetadataKeys.COMPLETED_TODO_COUNT,
      TodoWriteMetadataKeys.ADDED_TODO_COUNT,
      TodoWriteMetadataKeys.REMOVED_TODO_COUNT,
      TodoWriteMetadataKeys.STATUS_CHANGED_TODO_COUNT,
      TodoWriteMetadataKeys.COMPLETED_TODO_DELTA_COUNT,
      TodoWriteMetadataKeys.ACTIVE_TODO_CHANGED,
      TodoWriteMetadataKeys.ACTIVE_TODO_CONTENT,
      "workingDirectory",
      "processId",
      "shellCommand",
      "scriptPath",
      "checkpointEntryCount",
    )
    return buildMap {
      allowedKeys.forEach { key ->
        metadata[key]
          ?.trim()
          ?.takeIf(String::isNotBlank)
          ?.let { value -> put(key, value) }
      }
    }
  }

  private fun supplementMetadataSnapshot(metadata: Map<String, String>): Map<String, String> =
    metadata.mapNotNull { (key, value) ->
      val normalizedKey = key.trim()
      val normalizedValue = value.trim()
      if (
        normalizedKey.isBlank() ||
        normalizedValue.isBlank() ||
        normalizedKey == OpenCrayPromptResumeMetadata.KEY_PROMPT_RESUME_JSON ||
        normalizedKey == OpenCrayPromptResumeMetadata.KEY_PROMPT_CHECKPOINT_BOUNDARY
      ) {
        null
      } else {
        normalizedKey to normalizedValue
      }
    }.toMap(linkedMapOf())

  private fun hasPromptResumeCheckpointMetadata(metadata: Map<String, String>): Boolean =
    metadata[OpenCrayPromptResumeMetadata.KEY_PROMPT_RESUME_JSON]
      ?.trim()
      ?.isNotBlank() == true

  private fun looksLikeInternalToolPayload(text: String): Boolean =
    approvalSupportLooksLikeInternalToolPayload(text)

  internal fun currentMcpExposureReport() =
    synchronized(lock) { mcpSettingsFacade.currentExposureReport() }

  internal fun currentEnabledSkillRoots() =
    synchronized(lock) { skillsFacade.enabledSkillRoots() }

  private fun currentChatModeLabelLocked(): String =
    chatModeLabelFor(safetySettingsFacade.load().automationMode)

  private data class ResolvedAttachmentArtifact(
    val relativePath: String,
    val displayName: String? = null,
    val kindHint: String? = null,
    val mimeType: String? = null,
    val durationMs: Long? = null,
    val waveformBars: List<Int> = emptyList(),
    val transcriptText: String? = null,
  )

private data class AttachmentMarkdownCompatibility(
  val rewrittenText: String,
  val attachments: List<OpenCrayFinalAttachment> = emptyList(),
)

private data class RestoredTerminalMessage(
  val text: String,
  val attachments: List<ChatAttachmentEntry>,
)

  private data class FinalAttachmentArchiveResult(
    val attachments: List<ChatAttachmentEntry> = emptyList(),
    val failureText: String? = null,
  )

  private fun chatModeLabelFor(mode: SafetyAutomationMode): String = when (mode) {
    SafetyAutomationMode.SAFE -> strings.chatModeSafeLabel
    SafetyAutomationMode.AUTO -> strings.chatModeLabel
    SafetyAutomationMode.DEV -> strings.chatModeDeveloperLabel
  }

  internal fun safetyMetadataForTask(
    snapshot: SafetySettingsSnapshot,
  ): Map<String, String> = buildTaskSafetyMetadata(
    snapshot = snapshot,
    approvedReadRoots = approvedReadRootsProvider(),
  )

  internal fun refreshLocalizedResourcesLocked() {
    val baseContext = appContext ?: return
    val localizedContext = OpenCrayLocaleManager.wrap(baseContext)
    settingsFacade = LocalSettingsFacade.fromContext(localizedContext)
    llmConfigFacade = LocalLlmConfigFacade.fromContext(localizedContext)
    personalizationFacade = LocalPersonalizationFacade.create(
      context = localizedContext,
      store = PersonalizationLocalStore.fromContext(baseContext),
      soulProfileStore = workspaceSoulProfileStore,
      workspaceRootProvider = { AppAgentWorkspace.ensureRootForContext(baseContext) },
      queueIdleProvider = {
        val activeSessionId = chatSessionStore.loadState().activeSession.sessionId
        pendingTaskCount(activeSessionId) == 0
      },
    )
    mcpSettingsFacade = LocalMcpSettingsFacade.create(
      context = localizedContext,
      settingsStore = McpSettingsStore.fromContext(baseContext),
      registryStore = AppMcpRegistryStore.fromContext(baseContext),
    )
    safetySettingsFacade = LocalSafetySettingsFacade.fromContext(baseContext)
    skillsFacade = LocalSkillsFacade.fromContext(localizedContext)
    strings = localizedHostRuntimeStrings(localizedContext)
  }

  internal fun observeWithInitial(
    listeners: LinkedHashSet<(Map<String, Any?>) -> Unit>,
    initialPayload: Map<String, Any?>,
    listener: (Map<String, Any?>) -> Unit,
    onListenerSetChanged: (() -> Unit)? = null,
  ): () -> Unit {
    synchronized(lock) {
      listeners += listener
    }
    onListenerSetChanged?.invoke()
    mainThreadPoster.post { listener(initialPayload) }
    return {
      synchronized(lock) {
        listeners -= listener
      }
      onListenerSetChanged?.invoke()
    }
  }

  internal fun emitChatSnapshot() {
    emitSnapshotLazy(chatListeners, ::loadChatSnapshotForEmission)
  }

  internal fun emitChatRuntimeSnapshot() {
    val currentListeners = synchronized(lock) { chatRuntimeListeners.toList() }
    if (currentListeners.isEmpty()) {
      return
    }
    mainThreadPoster.post {
      val payload = loadChatRuntimeSnapshot()
      hostChatDebug(
        "host.emitChatRuntimeSnapshot ${chatRuntimePayloadDebugSummary(payload)}",
      )
      currentListeners.forEach { listener -> listener(payload) }
    }
  }

  private fun emitLiveAssistantDraftEvent(payload: Map<String, Any?>) {
    emitSnapshot(liveAssistantDraftEventListeners, payload)
  }

  private fun emitRuntimeEventDelta(payload: Map<String, Any?>) {
    emitSnapshot(runtimeEventDeltaListeners, payload)
  }

  internal fun emitShellSnapshot() {
    val payload = loadShellSnapshot()
    emitSnapshot(shellListeners, payload)
  }

  internal fun emitSettingsOverview() {
    val payload = loadSettingsOverview()
    emitSnapshot(settingsOverviewListeners, payload)
  }

  internal fun emitSkillsSnapshot() {
    val payload = loadSkillsSnapshot(query = "", suggestedLimit = 0)
    emitSnapshot(skillsListeners, payload)
  }

  private fun emitSnapshot(
    listeners: LinkedHashSet<(Map<String, Any?>) -> Unit>,
    payload: Map<String, Any?>,
  ) {
    val currentListeners = synchronized(lock) { listeners.toList() }
    if (currentListeners.isEmpty()) {
      return
    }
    mainThreadPoster.post {
      currentListeners.forEach { listener -> listener(payload) }
    }
  }

  private fun emitSnapshotLazy(
    listeners: LinkedHashSet<(Map<String, Any?>) -> Unit>,
    payloadProvider: () -> Map<String, Any?>,
  ) {
    val currentListeners = synchronized(lock) { listeners.toList() }
    if (currentListeners.isEmpty()) {
      return
    }
    mainThreadPoster.post {
      val payload = payloadProvider()
      currentListeners.forEach { listener -> listener(payload) }
    }
  }

  private fun chatRuntimePayloadDebugSummary(payload: Map<String, Any?>): String {
    val activeRuns = (payload["activeRuns"] as? List<*>)
      .orEmpty()
      .mapNotNull { item -> item as? Map<*, *> }
    val runSummary = activeRuns.joinToString(separator = ";") { run ->
      val runId = (run["runId"] as? String).orEmpty()
      val taskId = (run["taskId"] as? String).orEmpty()
      val managedProcessCount = (run["managedProcesses"] as? List<*>)?.size ?: 0
      val managedProcessIds = (run["managedProcessIds"] as? List<*>)?.joinToString(",") ?: ""
      val runningManagedProcessCount = run["runningManagedProcessCount"] ?: 0
      val hasLiveManagedProcesses = run["hasLiveManagedProcesses"] ?: false
      val lastEvent = run["lastEvent"] as? Map<*, *>
      val lastKind = lastEvent?.get("kind") as? String ?: "-"
      val lastTool = lastEvent?.get("toolName") as? String ?: "-"
      "${runId.takeLast(12)} task=${taskId.takeLast(12)} mp=$managedProcessCount[$managedProcessIds] running=$runningManagedProcessCount live=$hasLiveManagedProcesses last=$lastKind/$lastTool"
    }
    return "session=${payload["sessionId"] ?: "-"} activeRuns=${activeRuns.size} retainedRuns=${(payload["retainedRuns"] as? List<*>)?.size ?: 0} events=${(payload["events"] as? List<*>)?.size ?: 0} runs=[$runSummary]"
  }

  private fun liveAssistantDraftEventPayload(
    sessionId: String,
    runId: String,
    taskId: String,
    executionId: String?,
    pendingMessageId: String,
    text: String,
    updatedAtEpochMs: Long,
    cleared: Boolean,
  ): Map<String, Any?> = mapOf(
    "sessionId" to sessionId,
    "runId" to runId,
    "taskId" to taskId,
    "executionId" to executionId,
    "pendingMessageId" to pendingMessageId,
    "text" to text,
    "updatedAtEpochMs" to updatedAtEpochMs,
    "cleared" to cleared,
  )

  private fun assistantDraftRuntimeEvent(
    task: AgentTask,
    text: String,
    emittedAtEpochMs: Long,
  ): OpenCrayAssistantEvent = OpenCrayAssistantEvent(
    runId = runIdFor(task),
    taskId = task.id,
    turn = -1,
    text = text.trim(),
    isFinal = false,
    stage = AppAgentSessionTaskRuntimeFactory.PERSISTED_DRAFT_ASSISTANT_STAGE,
    emittedAtEpochMs = emittedAtEpochMs,
  )

  private fun LiveAssistantDraftSnapshot.toLiveAssistantDraftEventPayload(
    sessionId: String,
    cleared: Boolean,
  ): Map<String, Any?> = liveAssistantDraftEventPayload(
    sessionId = sessionId,
    runId = runId,
    taskId = taskId,
    executionId = executionId,
    pendingMessageId = pendingMessageId,
    text = text,
    updatedAtEpochMs = updatedAtEpochMs,
    cleared = cleared,
  )

  private fun ChatAttachmentKind.toWireKind(): String = when (this) {
    ChatAttachmentKind.IMAGE -> "image"
    ChatAttachmentKind.VOICE,
    ChatAttachmentKind.AUDIO,
    -> "voice"
    ChatAttachmentKind.FILE -> "file"
  }

  companion object {
    private const val ERROR_APPROVAL_REQUIRED: String = "APPROVAL_REQUIRED"
    internal const val ERROR_HIGH_RISK_APPROVAL_REQUIRED: String = "HIGH_RISK_APPROVAL_REQUIRED"
    internal const val DEFAULT_RUN_WAIT_TIMEOUT_MS: Long = 15_000L
    private const val RUN_LOOKUP_POLL_INTERVAL_MS: Long = 50L
    private const val MAX_RUNTIME_EVENT_HISTORY: Int = 24
    private const val INTERNAL_PROMPT_CHECKPOINT_MARKER: String = "internal_prompt_checkpoint"
    private const val MEMORY_DEBUG_RUN_ID_PREFIX: String = "run-memory-debug-"
    private const val MEMORY_DEBUG_TASK_ID_PREFIX: String = "memory-debug-"
    private const val TODO_ARCHIVE_VISIBILITY_DURATION_MS: Long = 4_000L
    private val replayJson: Json = Json { ignoreUnknownKeys = true }

    internal fun createWithRuntimeAccess(
      appContext: Context? = null,
      stateStore: AppShellStateStore,
      chatSessionStore: ChatSessionLocalStore,
      settingsFacade: SettingsFacade,
      notificationSettingsFacade: NotificationSettingsFacade,
      networkSearchConfigFacade: NetworkSearchConfigFacade,
      mediaSpeechSettingsFacade: MediaSpeechSettingsFacade,
      sandboxSettingsRepository: SandboxSettingsRepository?,
      llmConfigFacade: LlmConfigFacade,
      personalizationFacade: PersonalizationFacade,
      personalizationLocalStore: PersonalizationLocalStore?,
      workspaceSoulProfileStore: WorkspaceSoulProfileStore,
      mcpSettingsFacade: McpSettingsFacade,
      safetySettingsFacade: SafetySettingsFacade,
      skillsFacade: SkillsFacade,
      workspaceRootProvider: (() -> Path)? = null,
      workspaceEntryOpener: ((Path, String) -> Unit)? = null,
      externalUriOpener: ((String) -> Unit)? = null,
      approvedReadRootsProvider: () -> ApprovedReadRootsSnapshot,
      workspaceSnapshotProvider: () -> Map<String, Any?>,
      strongBackgroundSettingsAccess: StrongBackgroundSettingsAccess,
      voiceMetadataAnalyzer: AppAgentWorkspaceVoiceMetadataAnalyzer,
      voiceMetadataBackfillExecutor: Executor,
      voiceMetadataCacheStore: AppAgentWorkspaceVoiceMetadataCacheStore? = null,
      runtimeHostAccess: OpenCrayRuntimeHostAccess,
      subAgentSessionLinkStoreFactory: SubAgentSessionLinkStoreFactory =
        inMemorySubAgentSessionLinkStoreFactory(),
      todoSnapshotProvider: (String) -> ChatSessionTodoPresentation,
      transcriptMessagesProvider: (String) -> List<RuntimeConversationMessage>,
      directTaskRuntimeFactory: AgentSessionTaskRuntimeFactory? = null,
      memoryIngestionCoordinator: ChatMemoryIngestionCoordinator? = null,
      approvalReplayRecorder: (String, String, String, String?, Boolean, RuntimeReplayExecutionContext) -> Unit = { _, _, _, _, _, _ -> },
      approvalApprovedReplayRecorder: (String, String, String, String?, Boolean, RuntimeReplayExecutionContext) -> Unit = { _, _, _, _, _, _ -> },
      subAgentReplayRecorder: (String, OpenCraySubAgentEvent) -> Unit = { _, _ -> },
      runCancellationReplayRecorder: (String, String, String, String?, RuntimeReplayExecutionContext) -> Unit = { _, _, _, _, _ -> },
      terminalReplayRepairer: (String, List<AgentRunSnapshot>) -> Unit = { _, _ -> },
      strings: HostRuntimeStrings = HostRuntimeStrings(
        localeTag = "en",
        shellHostLabel = "HOST CONNECTED",
        shellHostSummary = "Android host bridge is attached to the live app runtime.",
        chatScreenTitle = "Chat",
        chatModeLabel = "AUTO",
        chatModeSafeLabel = "SAFE",
        chatModeDeveloperLabel = "DEV",
        chatSessionButtonLabel = "Sessions",
        chatRecentSessionsEyebrow = "Recent sessions",
        chatRecentSessionsTitle = "Recent sessions",
        chatNewSessionLabel = "New session",
        chatDefaultSessionTitle = "New chat",
        chatMessagesBadge = { count -> "$count messages" },
        chatSummaryReplyInProgress = "Reply in progress",
        chatSummaryOnDevicePreparing = "Preparing the on-device model.",
        chatSummaryAwaitingDirection = "Waiting for your next instruction.",
        chatSummarySupplementRecorded = "Recorded. This will be applied to the current run when it reaches the next safe checkpoint.",
        chatSummaryApprovalFollowUpRecorded = "Recorded. The current run is waiting for approval, so this message will be handled after that decision.",
        chatSummaryStartNewSession = "Start a new session",
        chatSummaryRestored = "Local transcript is restored into the runtime window for each task.",
        skillInstalled = { skillId -> "Installed $skillId." },
        skillRemoved = { skillId -> "Removed $skillId." },
        skillsReloaded = "Reloaded skills from local storage.",
        composerPlaceholder = "Message OpenCray",
        chatMessageOnDevicePreparing = "Preparing on-device model",
        composerRejectedPlaceholder = "Tell OpenCray differently",
        agentThinking = "Thinking",
        agentCancelled = "Interrupted",
        agentMissingLlm = "Missing LLM",
        agentEmptyAnswer = "The model returned an empty answer.",
        agentFailed = { detail -> "Failed: $detail" },
        chatApprovalApproveForSessionLabel = "Allow session",
        chatApprovalApprovedForSession = "Approval granted for this session. The agent is resuming.",
      ),
      mainThreadPoster: MainThreadPoster = ImmediateMainThreadPoster,
      lifecycleDescriptor: HostRuntimeLifecycleDescriptor = HostRuntimeLifecycleDescriptor(),
      runtimeDiagnosticsBridge: HostRuntimeDiagnosticsBridge =
        HostRuntimeDiagnosticsBridge(runtimeOwnerDescriptor = lifecycleDescriptor),
      resumeActiveSessionOnInit: Boolean = true,
      onDeviceLlmWarmupController: OnDeviceLlmWarmupController? = null,
    ): OpenCrayHostRuntime =
    HostRuntimeFactory.createWithRuntimeAccess(
      appContext = appContext,
      stateStore = stateStore,
      chatSessionStore = chatSessionStore,
      settingsFacade = settingsFacade,
      notificationSettingsFacade = notificationSettingsFacade,
      networkSearchConfigFacade = networkSearchConfigFacade,
      mediaSpeechSettingsFacade = mediaSpeechSettingsFacade,
      sandboxSettingsRepository = sandboxSettingsRepository,
      llmConfigFacade = llmConfigFacade,
      personalizationFacade = personalizationFacade,
      personalizationLocalStore = personalizationLocalStore,
      workspaceSoulProfileStore = workspaceSoulProfileStore,
      mcpSettingsFacade = mcpSettingsFacade,
      safetySettingsFacade = safetySettingsFacade,
      skillsFacade = skillsFacade,
      workspaceRootProvider = workspaceRootProvider,
      workspaceEntryOpener = workspaceEntryOpener,
      externalUriOpener = externalUriOpener,
      approvedReadRootsProvider = approvedReadRootsProvider,
      workspaceSnapshotProvider = workspaceSnapshotProvider,
      strongBackgroundSettingsAccess = strongBackgroundSettingsAccess,
      voiceMetadataAnalyzer = voiceMetadataAnalyzer,
      voiceMetadataBackfillExecutor = voiceMetadataBackfillExecutor,
      voiceMetadataCacheStore = voiceMetadataCacheStore,
      runtimeHostAccess = runtimeHostAccess,
      subAgentSessionLinkStoreFactory = subAgentSessionLinkStoreFactory,
      todoSnapshotProvider = todoSnapshotProvider,
      transcriptMessagesProvider = transcriptMessagesProvider,
      directTaskRuntimeFactory = directTaskRuntimeFactory,
      memoryIngestionCoordinator = memoryIngestionCoordinator,
      approvalReplayRecorder = approvalReplayRecorder,
      approvalApprovedReplayRecorder = approvalApprovedReplayRecorder,
      subAgentReplayRecorder = subAgentReplayRecorder,
      runCancellationReplayRecorder = runCancellationReplayRecorder,
      terminalReplayRepairer = terminalReplayRepairer,
      strings = strings,
      mainThreadPoster = mainThreadPoster,
      onDeviceLlmWarmupController = onDeviceLlmWarmupController,
      lifecycleDescriptor = lifecycleDescriptor,
      runtimeDiagnosticsBridge = runtimeDiagnosticsBridge,
      resumeActiveSessionOnInit = resumeActiveSessionOnInit,
    )

    internal fun inMemorySandboxSettingsRepository(): SandboxSettingsRepository {
      val secrets = linkedMapOf<CredentialRef, SecretValue>()
      return SandboxSettingsRepository(
        store = SandboxSettingsStore(
          keyValueStore = InMemorySandboxSettingsKeyValueStore(),
        ),
        secretManager = AppSecretManager(
          vault = object : SecretVault {
            override val storageClass: SecretVaultStorageClass =
              SecretVaultStorageClass.TEST_IN_MEMORY

            override fun put(ref: CredentialRef, secret: SecretValue) {
              secrets[ref] = secret
            }

            override fun get(ref: CredentialRef): SecretValue? = secrets[ref]

            override fun delete(ref: CredentialRef): Boolean = secrets.remove(ref) != null
          },
        ),
      )
    }

    private val NoOpVoiceMetadataAnalyzer: AppAgentWorkspaceVoiceMetadataAnalyzer =
      AppAgentWorkspaceVoiceMetadataAnalyzer { _, _ -> null }

    internal fun localizedHostRuntimeStrings(context: Context): HostRuntimeStrings = HostRuntimeStrings(
      localeTag = LocaleSettingsStore.fromContext(context).loadLanguage().tag,
      shellHostLabel = context.getString(R.string.flutter_host_label_android),
      shellHostSummary = context.getString(R.string.flutter_host_summary_android),
      chatScreenTitle = context.getString(R.string.shell_tab_chat),
      chatModeLabel = context.getString(R.string.chat_mode_auto),
      chatModeSafeLabel = context.getStringByNameOrFallback(
        resourceName = "chat_mode_safe",
        fallback = "SAFE",
      ),
      chatModeDeveloperLabel = context.getStringByNameOrFallback(
        resourceName = "chat_mode_dev",
        fallback = "DEV",
      ),
      chatSessionButtonLabel = context.getString(R.string.chat_sessions_button),
      chatRecentSessionsEyebrow = context.getString(R.string.chat_recent_sessions_eyebrow),
      chatRecentSessionsTitle = context.getString(R.string.chat_recent_sessions_title),
      chatNewSessionLabel = context.getString(R.string.chat_new_session),
      chatDefaultSessionTitle = context.getString(R.string.chat_default_session_title),
      chatMessagesBadge = { count ->
        context.getString(R.string.chat_messages_badge, count)
      },
      chatSummaryReplyInProgress = context.getString(R.string.chat_summary_reply_in_progress),
      chatSummaryOnDevicePreparing = context.getString(
        R.string.chat_summary_on_device_preparing,
      ),
      chatSummaryAwaitingDirection = context.getString(R.string.chat_summary_awaiting_direction),
      chatSummarySupplementRecorded = context.getString(R.string.chat_summary_supplement_recorded),
      chatSummaryApprovalFollowUpRecorded = context.getString(
        R.string.chat_summary_approval_follow_up_recorded,
      ),
      chatSummaryStartNewSession = context.getString(R.string.chat_summary_start_new_session),
      chatSummaryRestored = context.getString(R.string.chat_summary_restored),
      skillInstalled = { skillId ->
        context.getString(R.string.skills_message_installed, skillId)
      },
      skillRemoved = { skillId ->
        context.getString(R.string.skills_message_removed, skillId)
      },
      skillsReloaded = context.getString(R.string.skills_message_reloaded),
      composerPlaceholder = context.getString(R.string.chat_message_opencray),
      chatMessageOnDevicePreparing = context.getString(
        R.string.chat_message_on_device_preparing,
      ),
      composerRejectedPlaceholder = context.getString(
        R.string.chat_message_opencray_do_differently,
      ),
      agentThinking = context.getString(R.string.chat_agent_thinking),
      agentCancelled = context.getString(R.string.chat_agent_cancelled),
      agentMissingLlm = context.getString(R.string.chat_agent_missing_llm),
      agentEmptyAnswer = context.getString(
        R.string.chat_agent_failed,
        "The model returned an empty answer.",
      ),
      agentFailed = { detail ->
        context.getString(R.string.chat_agent_failed, detail)
      },
      agentAttachmentSaveFailed = { detail ->
        context.getString(R.string.chat_agent_attachment_save_failed, detail)
      },
      chatApprovalApproveLabel = context.getStringByNameOrFallback(
        resourceName = "chat_approval_approve_label",
        fallback = "Approve",
      ),
      chatApprovalApproveForSessionLabel = context.getString(
        R.string.chat_approval_approve_for_session_label,
      ),
      chatApprovalRejectLabel = context.getStringByNameOrFallback(
        resourceName = "chat_approval_reject_label",
        fallback = "Reject",
      ),
      chatApprovalApproved = context.getString(R.string.chat_approval_approved),
      chatApprovalApprovedForSession = context.getString(
        R.string.chat_approval_approved_for_session,
      ),
      chatApprovalRejected = context.getString(R.string.chat_approval_rejected),
    )

    private fun Context.getStringByNameOrFallback(
      resourceName: String,
      fallback: String,
    ): String {
      val resourceId = resources.getIdentifier(resourceName, "string", packageName)
      return if (resourceId != 0) getString(resourceId) else fallback
    }
  }
}

private data class CompletedTurnForMemoryIngestion(
  val sessionId: String,
  val task: AgentTask,
  val result: ExecutionResult,
  val userInput: String,
  val assistantOutput: String,
  val attachments: List<ChatAttachmentEntry>,
  val toolObservations: List<String>,
)

private data class VoiceMetadataBackfillCandidate(
  val contentSha256: String,
  val localPath: String,
  val mimeType: String?,
)

internal data class RuntimePendingApprovalNotificationTarget(
  val sessionId: String,
  val sessionTitle: String,
  val runId: String,
  val taskId: String,
  val title: String,
  val body: String,
  val isHighRisk: Boolean,
)

internal data class PendingApprovalSnapshot(
  val runId: String,
  val taskId: String,
  val pendingMessageId: String?,
  val executionId: String?,
  val executionOrdinal: Int?,
  val executionKind: String?,
  val toolName: String?,
  val resumeToolName: String?,
  val promptCheckpointBoundary: com.opencray.runtime.OpenCrayPromptCheckpointBoundary?,
  val promptResumeState: OpenCrayPromptResumeState?,
  val subAgentApprovalResume: SubAgentApprovalResume?,
  val requestSummary: String?,
  val primaryDetail: String?,
  val pathDetails: List<String>,
  val workingDirectory: String?,
  val reason: String?,
  val message: String?,
  val isHighRisk: Boolean,
  val supportsSessionApproval: Boolean = false,
  val approveForSessionLabel: String? = null,
  val subAgentLifecycle: PendingApprovalSubAgentLifecycle? = null,
  val title: String,
  val body: String,
)

internal data class PendingApprovalMatch(
  val sessionId: String,
  val approval: PendingApprovalSnapshot,
)

internal data class PendingApprovalSubAgentLifecycle(
  val agentId: String? = null,
  val childRunId: String,
  val childTaskId: String,
  val label: String,
  val subagentType: String,
  val contextMode: String,
  val depth: Int,
  val liveContext: SubAgentLiveContextSnapshot? = null,
)

private data class ReplayedRuntimeEvent(
  val sourceIndex: Int,
  val event: OpenCrayAgentRunEvent,
)


private data class EventEmissionDecision(
  val shouldEmit: Boolean,
  val emitRuntimeEventDelta: Boolean = false,
  val emitRuntimeSnapshotFallback: Boolean = false,
)

private data class ChatSnapshotBuildResult(
  val snapshot: Map<String, Any?>,
  val visibleAttachments: List<ChatAttachmentEntry>,
)

private fun PendingApprovalSnapshot.replayExecutionContext(): RuntimeReplayExecutionContext =
  RuntimeReplayExecutionContext(
    executionId = executionId,
    executionOrdinal = executionOrdinal,
    executionKind = executionKind,
  )

private fun AgentRunSnapshot.replayExecutionContext(): RuntimeReplayExecutionContext =
  RuntimeReplayExecutionContext(
    executionId = executionId,
    executionOrdinal = executionOrdinal.takeIf { ordinal -> ordinal > 0 },
    executionKind = executionKind,
  )

private fun OpenCrayAgentRunEvent.withEmittedAtEpochMs(emittedAtEpochMs: Long): OpenCrayAgentRunEvent =
  when (this) {
    is OpenCrayLifecycleEvent -> copy(emittedAtEpochMs = emittedAtEpochMs)
    is OpenCrayAssistantEvent -> copy(emittedAtEpochMs = emittedAtEpochMs)
    is OpenCraySupplementEvent -> copy(emittedAtEpochMs = emittedAtEpochMs)
    is OpenCrayApprovalEvent -> copy(emittedAtEpochMs = emittedAtEpochMs)
    is OpenCraySubAgentEvent -> copy(emittedAtEpochMs = emittedAtEpochMs)
    is OpenCrayToolCallEvent -> copy(emittedAtEpochMs = emittedAtEpochMs)
    is OpenCrayToolResultEvent -> copy(emittedAtEpochMs = emittedAtEpochMs)
    is OpenCrayMemoryWriteEvent -> copy(emittedAtEpochMs = emittedAtEpochMs)
    is OpenCrayMemoryRetrievalEvent -> copy(emittedAtEpochMs = emittedAtEpochMs)
    is OpenCrayCancellationEvent -> copy(emittedAtEpochMs = emittedAtEpochMs)
  }

internal fun interface MainThreadPoster {
  fun post(action: () -> Unit)
}

internal fun interface RuntimeServiceConnectionChangeRegistrar {
  fun register(listener: () -> Unit): () -> Unit
}

internal fun interface RuntimeServiceKeepAliveChangeRegistrar {
  fun register(listener: () -> Unit): () -> Unit
}

internal class HandlerMainThreadPoster(
  private val handler: Handler,
) : MainThreadPoster {
  override fun post(action: () -> Unit) {
    handler.post(action)
  }
}

internal object ImmediateMainThreadPoster : MainThreadPoster {
  override fun post(action: () -> Unit) {
    action()
  }
}
