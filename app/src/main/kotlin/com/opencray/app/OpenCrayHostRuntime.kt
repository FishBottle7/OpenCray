package com.opencray.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.opencray.app.facade.llm.EmptyLlmConfigFacade
import com.opencray.app.facade.llm.LlmConfigFacade
import com.opencray.app.facade.llm.LlmConfigSnapshot
import com.opencray.app.facade.llm.LlmProviderOptionSnapshot
import com.opencray.app.facade.llm.LlmValidationResult
import com.opencray.app.facade.llm.LocalLlmConfigFacade
import com.opencray.app.facade.llm.OnDeviceLlmModelOptionSnapshot
import com.opencray.app.facade.llm.SaveCustomLlmProviderRequest
import com.opencray.app.facade.llm.SaveLlmConfigRequest
import com.opencray.app.facade.llm.ValidateLlmConfigRequest
import com.opencray.app.facade.media.EmptyMediaSpeechSettingsFacade
import com.opencray.app.facade.media.LocalMediaSpeechSettingsFacade
import com.opencray.app.facade.media.MediaProviderSnapshot
import com.opencray.app.facade.media.MediaSpeechConfigSnapshot
import com.opencray.app.facade.media.MediaSpeechSettingsFacade
import com.opencray.app.facade.media.OnDeviceSttSnapshot
import com.opencray.app.facade.media.SaveMediaProviderRequest
import com.opencray.app.facade.media.SaveMediaSpeechConfigRequest
import com.opencray.app.facade.media.SaveOnDeviceSttRequest
import com.opencray.app.facade.media.SaveVoiceProviderRequest
import com.opencray.app.facade.media.VoiceProviderSnapshot
import com.opencray.runtime.memory.MemoryCandidateExtractor
import com.opencray.app.facade.mcp.EmptyMcpSettingsFacade
import com.opencray.app.facade.mcp.LocalMcpSettingsFacade
import com.opencray.app.facade.mcp.McpServerSettingsSnapshot
import com.opencray.app.facade.mcp.McpSettingsFacade
import com.opencray.app.facade.mcp.McpSettingsSnapshot
import com.opencray.app.facade.notifications.EmptyNotificationSettingsFacade
import com.opencray.app.facade.notifications.LocalNotificationSettingsFacade
import com.opencray.app.facade.notifications.NotificationSettingsFacade
import com.opencray.app.facade.personalization.EmptyPersonalizationFacade
import com.opencray.app.facade.personalization.LocalPersonalizationFacade
import com.opencray.app.facade.personalization.PersonalizationConfigSnapshot
import com.opencray.app.facade.personalization.PersonalizationLanguageOptionSnapshot
import com.opencray.app.facade.personalization.PersonalizationFacade
import com.opencray.app.facade.personalization.PersonalizationPresetSnapshot
import com.opencray.app.facade.personalization.PersonalizationResetActionSnapshot
import com.opencray.app.facade.personalization.PersonalizationResetScope
import com.opencray.app.facade.personalization.SavePersonalizationConfigRequest
import com.opencray.app.facade.search.EmptyNetworkSearchConfigFacade
import com.opencray.app.facade.search.LocalNetworkSearchConfigFacade
import com.opencray.app.facade.search.NetworkSearchConfigFacade
import com.opencray.app.facade.search.NetworkSearchConfigSnapshot
import com.opencray.app.facade.search.NetworkSearchSlotSnapshot
import com.opencray.app.facade.search.SaveNetworkSearchConfigRequest
import com.opencray.app.facade.search.SaveNetworkSearchSlotRequest
import com.opencray.app.facade.safety.EmptySafetySettingsFacade
import com.opencray.app.facade.safety.LocalSafetySettingsFacade
import com.opencray.app.facade.safety.SaveSafetySettingsRequest
import com.opencray.app.facade.safety.SafetySettingsFacade
import com.opencray.app.facade.safety.SafetySettingsLocationSnapshot
import com.opencray.app.facade.safety.SafetySettingsSnapshot
import com.opencray.app.facade.skills.EmptySkillsFacade
import com.opencray.app.facade.skills.LocalSkillsFacade
import com.opencray.app.facade.skills.SkillInstructionsSnapshot
import com.opencray.app.facade.skills.SkillsFacade
import com.opencray.app.facade.skills.SkillsSnapshot
import com.opencray.app.facade.settings.LocalSettingsFacade
import com.opencray.app.facade.settings.SettingsDetailSnapshot
import com.opencray.app.facade.settings.SettingsFacade
import com.opencray.app.facade.settings.SettingsOverviewSnapshot
import com.opencray.app.facade.settings.SettingsRouteId
import com.opencray.app.facade.settings.SettingsRowSnapshot
import com.opencray.app.facade.settings.SettingsSectionSnapshot
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
import com.opencray.runtime.memory.MemoryOperator
import com.opencray.runtime.memory.MemoryOperatorAction
import com.opencray.runtime.memory.MemoryOperatorRequest
import com.opencray.runtime.memory.MemoryRecordExtensionKeys
import com.opencray.runtime.subagent.SubAgentApprovalResume
import com.opencray.runtime.subagent.SubAgentApprovalResumeMetadata
import com.opencray.runtime.subagent.SubAgentContinuationKind
import com.opencray.runtime.subagent.SubAgentHandleState
import com.opencray.runtime.subagent.SubAgentExecutionState
import com.opencray.runtime.subagent.SubAgentLiveContextSnapshot
import com.opencray.runtime.skills.SkillPackageCheckReport
import com.opencray.runtime.skills.SkillPackageCheckResult
import com.opencray.runtime.skills.SkillPackageCheckStatus
import com.opencray.runtime.skills.SkillPackageUpdateReport
import com.opencray.runtime.skills.SkillPackageUpdateResult
import com.opencray.runtime.skills.SkillPackageUpdateStatus
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
import org.opencray.app.R

internal class OpenCrayHostRuntime private constructor(
  private val appContext: Context?,
  private val stateStore: AppShellStateStore,
  private val chatSessionStore: ChatSessionLocalStore,
  private var settingsFacade: SettingsFacade,
  private var notificationSettingsFacade: NotificationSettingsFacade,
  private var networkSearchConfigFacade: NetworkSearchConfigFacade,
  private var mediaSpeechSettingsFacade: MediaSpeechSettingsFacade,
  private val sandboxSettingsRepository: SandboxSettingsRepository? =
    appContext?.let(SandboxSettingsRepository::fromContext),
  private var llmConfigFacade: LlmConfigFacade,
  private var personalizationFacade: PersonalizationFacade,
  private val personalizationLocalStore: PersonalizationLocalStore? = null,
  private val workspaceSoulProfileStore: WorkspaceSoulProfileStore = WorkspaceSoulProfileStore(),
  private var mcpSettingsFacade: McpSettingsFacade,
  private var safetySettingsFacade: SafetySettingsFacade,
  private var skillsFacade: SkillsFacade,
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
  private val strongBackgroundSettingsAccess: StrongBackgroundSettingsAccess =
    NoOpStrongBackgroundSettingsAccess,
  private val voiceMetadataAnalyzer: AppAgentWorkspaceVoiceMetadataAnalyzer,
  private val voiceMetadataBackfillExecutor: Executor,
  private val voiceMetadataCacheStore: AppAgentWorkspaceVoiceMetadataCacheStore? = null,
  private val runtimeHostAccess: OpenCrayRuntimeHostAccess,
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
  private var strings: HostRuntimeStrings,
  private val mainThreadPoster: MainThreadPoster,
  private val providedOnDeviceLlmWarmupController: OnDeviceLlmWarmupController? = null,
  private val localHostGateway: OpenCrayLocalHostGateway = DefaultOpenCrayLocalHostGateway(
    appContext = appContext,
    workspaceRootProvider = workspaceRootProvider,
    workspaceEntryOpener = workspaceEntryOpener,
    externalUriOpener = externalUriOpener,
    workspaceSnapshotProvider = workspaceSnapshotProvider,
    mainThreadPoster = mainThreadPoster,
  ),
  private val lifecycleDescriptor: HostRuntimeLifecycleDescriptor = HostRuntimeLifecycleDescriptor(),
  private val runtimeDiagnosticsBridge: HostRuntimeDiagnosticsBridge =
    HostRuntimeDiagnosticsBridge(runtimeOwnerDescriptor = lifecycleDescriptor),
  private val resumeActiveSessionOnInit: Boolean = false,
  private val chatUnreadMessageState: ChatUnreadMessageState = ChatUnreadMessageState(),
  private val chatPendingApprovalState: ChatPendingApprovalState = ChatPendingApprovalState(),
  private val chatRuntimeEventState: ChatRuntimeEventState = ChatRuntimeEventState(),
) : OpenCrayLocalHostGateway,
  OpenCrayShellGateway,
  OpenCrayChatRuntimeGateway,
  OpenCraySkillsGateway,
  OpenCraySettingsGateway {
  private val lock = Any()
  private val fallbackSandboxSettingsRepository: SandboxSettingsRepository by lazy {
    Companion.inMemorySandboxSettingsRepository()
  }
  private val soulProfileResolver = SoulProfileResolver()
  private val runtimeSoulProfileSeedFactory = RuntimeSoulProfileSeedFactory()
  private val memoryBackedSoulProfileResolver = MemoryBackedSoulProfileResolver()
  private val liveAssistantDraftsBySession =
    linkedMapOf<String, LinkedHashMap<String, LiveAssistantDraftSnapshot>>()
  private val chatDebugProjector = ProjectionOnlyChatDebugProjector(
    personalizationLocalStore = personalizationLocalStore,
    workspaceRootProvider = workspaceRootProvider?.let { provider -> { provider() } },
    workspaceSoulProfileStore = workspaceSoulProfileStore,
    soulProfileResolver = soulProfileResolver,
    runtimeSoulProfileSeedFactory = runtimeSoulProfileSeedFactory,
    memoryBackedSoulProfileResolver = memoryBackedSoulProfileResolver,
  )
  private val recoveryPlanner = RunRecoveryPlanner()
  private val chatSessionMutationCoordinator = ChatSessionMutationCoordinator(
    chatSessionStore = chatSessionStore,
    runtimeHostAccess = runtimeHostAccess,
    chatUnreadMessageState = chatUnreadMessageState,
    pendingApprovalState = chatPendingApprovalState,
    runtimeEventState = chatRuntimeEventState,
    terminalReplayRepairer = terminalReplayRepairer,
  )
  private val chatSubmissionCoordinator = ChatSubmissionCoordinator(
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
  private val chatRunControlCoordinator = ChatRunControlCoordinator(
    runtimeHostAccess = runtimeHostAccess,
    findRunSnapshotForIdentifier = ::findRunSnapshotForIdentifierLocked,
    pendingApprovalForRun = { run ->
      pendingApprovalForIdentifier(run.sessionId, run.taskId)
    },
    clearPendingApproval = ::clearPendingApprovalLocked,
    clearApproval = ::clearApproval,
    clearPromptCheckpoint = ::clearPromptCheckpointLocked,
    recordRuntimeEvent = ::recordRuntimeEventLocked,
    runCancellationReplayRecorder = runCancellationReplayRecorder,
    subAgentReplayRecorder = subAgentReplayRecorder,
    subAgentTerminalEventFactory = { approval, summary, emittedAtEpochMs ->
      approval.toApprovalDecisionRecord().subAgentTerminalEvent(
        summary = summary,
        emittedAtEpochMs = emittedAtEpochMs,
      )
    },
    cancellationEventFactory = ::cancellationRuntimeEvent,
    delegatedChildCancelledWhileWaitingSummaryProvider = ::delegatedChildCancelledWhileWaitingSummary,
    nowEpochMsProvider = System::currentTimeMillis,
  )
  private val chatApprovalDecisionCoordinator = ChatApprovalDecisionCoordinator(
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
        deferredApprovalApproved = deferredApprovalRecordedText(),
        deferredApprovalApprovedForSession = deferredApprovalRecordedForSessionText(),
        deferredApprovalRejected = deferredApprovalRejectedText(),
        delegatedChildApprovalApprovedSummary = delegatedChildApprovalApprovedSummary(),
        delegatedChildApprovalApprovedText = strings.chatApprovalApproved,
        delegatedChildApprovalRejectedSummary = delegatedChildApprovalRejectedStopSummary(),
      )
    },
    nowEpochMsProvider = System::currentTimeMillis,
  )
  private val shellListeners = linkedSetOf<(Map<String, Any?>) -> Unit>()
  private val settingsOverviewListeners = linkedSetOf<(Map<String, Any?>) -> Unit>()
  private val skillsListeners = linkedSetOf<(Map<String, Any?>) -> Unit>()
  private val chatListeners = linkedSetOf<(Map<String, Any?>) -> Unit>()
  private val chatRuntimeListeners = linkedSetOf<(Map<String, Any?>) -> Unit>()
  private val liveAssistantDraftEventListeners = linkedSetOf<(Map<String, Any?>) -> Unit>()
  private val onDeviceLlmWarmupController: OnDeviceLlmWarmupController =
    providedOnDeviceLlmWarmupController ?: defaultOnDeviceLlmWarmupController()
  private val voiceMetadataBackfillInFlight = ConcurrentHashMap.newKeySet<String>()

  private fun resolvedSandboxSettingsRepository(): SandboxSettingsRepository =
    sandboxSettingsRepository ?: fallbackSandboxSettingsRepository

  init {
    runtimeHostAccess.observe(
      object : AgentSessionRuntimeListener {
        override fun onTaskStarted(sessionId: String, task: AgentTask) {
          val shouldEmit = synchronized(lock) { hasSessionLocked(sessionId) }
          if (!shouldEmit) {
            return
          }
          emitShellSnapshot()
          emitChatSnapshot()
          emitChatRuntimeSnapshot()
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
            val finalAttachments = finalAttachmentsForResultLocked(
              sessionId = sessionId,
              task = task,
              result = result,
              compatibilityAttachments = markdownCompatibility.attachments,
            )
            val finalText = finalizedAssistantText(
              text = markdownCompatibility.rewrittenText,
              attachments = finalAttachments,
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
                  reaffirmedRecordIds = ingestionSummary.reaffirmedRecords.map { record -> record.id },
                  expiredRecordIds = ingestionSummary.expiredRecordIds,
                  emittedAtEpochMs = completedTurn.result.finishedAtEpochMs,
                ),
              )
            }
          }
          synchronized(lock) {
            if (hasSessionLocked(completedTurn.sessionId)) {
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
            }
          }
          repairTerminalReplay(completedTurn.sessionId)
          emitShellSnapshot()
          emitChatSnapshot()
          emitChatRuntimeSnapshot()
        }

        override fun onRunEvent(sessionId: String, task: AgentTask, event: OpenCrayAgentRunEvent) {
          val emission = synchronized(lock) {
            if (!hasSessionLocked(sessionId)) {
              return@synchronized EventEmissionDecision(shouldEmit = false)
            }
            recordRuntimeEventLocked(sessionId = sessionId, event = event)
            maybePersistGeneralResumeCheckpointLocked(
              sessionId = sessionId,
              task = task,
              event = event,
            )
            EventEmissionDecision(
              shouldEmit = true,
              emitChatSnapshot =
                event !is OpenCrayToolResultEvent || !event.call.toolName.equals("TodoWrite", ignoreCase = true),
            )
          }
          if (!emission.shouldEmit) {
            return
          }
          if (emission.emitChatSnapshot) {
            emitChatSnapshot()
          }
          emitChatRuntimeSnapshot()
        }

        override fun onAssistantDraftUpdated(
          sessionId: String,
          task: AgentTask,
          text: String,
          emittedAtEpochMs: Long,
        ) {
          val draftEventPayload = synchronized(lock) {
            if (!hasSessionLocked(sessionId)) {
              return@synchronized null
            }
            if (
              !updateAssistantDraftLocked(
                sessionId = sessionId,
                task = task,
                text = text,
                emittedAtEpochMs = emittedAtEpochMs,
              )
            ) {
              return@synchronized null
            }
            val pendingMessageId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID]
              ?.trim()
              ?.takeIf(String::isNotBlank)
              ?: return@synchronized null
            liveAssistantDraftsBySession[sessionId]
              ?.get(pendingMessageId)
              ?.toLiveAssistantDraftEventPayload(sessionId = sessionId, cleared = false)
          }
          if (draftEventPayload != null) {
            emitLiveAssistantDraftEvent(draftEventPayload)
          }
        }

        override fun onAssistantDraftCleared(
          sessionId: String,
          task: AgentTask,
          emittedAtEpochMs: Long,
        ) {
          val draftEventPayload = synchronized(lock) {
            if (!hasSessionLocked(sessionId)) {
              return@synchronized null
            }
            val pendingMessageId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID]
              ?.trim()
              ?.takeIf(String::isNotBlank)
              ?: return@synchronized null
            if (
              !clearAssistantDraftLocked(
                sessionId = sessionId,
                pendingMessageId = pendingMessageId,
              )
            ) {
              return@synchronized null
            }
            liveAssistantDraftEventPayload(
              sessionId = sessionId,
              runId = runIdFor(task),
              taskId = task.id,
              pendingMessageId = pendingMessageId,
              text = "",
              updatedAtEpochMs = emittedAtEpochMs,
              cleared = true,
            )
          }
          if (draftEventPayload != null) {
            emitLiveAssistantDraftEvent(draftEventPayload)
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
    runtimeDiagnosticsBridge.registerSnapshotObservers(
      emitShellSnapshot = ::emitShellSnapshot,
      emitChatRuntimeSnapshot = ::emitChatRuntimeSnapshot,
    )
    if (resumeActiveSessionOnInit) {
      ensureActiveSessionResumed()
    }
    scheduleStartupOnDeviceWarmup()
  }

  override fun loadShellSnapshot(): Map<String, Any?> = buildMap {
    put("initialTab", stateStore.load().selectedTab.routeKey)
    put("localeTag", strings.localeTag)
    put("hostLabel", strings.shellHostLabel)
    put("hostSummary", strings.shellHostSummary)
    put("isHostConnected", true)
    putRuntimeServiceDiagnosticsSnapshot(
      localRuntimeServerState = OpenCrayLocalRuntimeServerRegistry.peekState(),
      hostLifecycle = lifecycleDescriptor,
      runtimeOwnerLifecycle = runtimeDiagnosticsBridge.runtimeOwnerDescriptor,
      runtimeOwnerWorkSummary = runtimeHostAccess.activeWorkSummary(),
      runtimeServiceLifecycle = runtimeDiagnosticsBridge.runtimeServiceDescriptor,
      runtimeServiceWorkState = runtimeDiagnosticsBridge.runtimeServiceWorkStateProvider(),
      runtimeServiceKeepAliveState = runtimeDiagnosticsBridge.runtimeServiceKeepAliveStateProvider(),
      runtimeServiceConnectionState =
        runtimeDiagnosticsBridge.runtimeServiceConnectionStateProvider(),
      includeNullRuntimeServiceFields = true,
    )
  }

  override fun observeShell(listener: (Map<String, Any?>) -> Unit): () -> Unit =
    observeWithInitial(
      listeners = shellListeners,
      initialPayload = loadShellSnapshot(),
      listener = listener,
    )

  override fun loadSettingsOverview(): Map<String, Any?> =
    synchronized(lock) { settingsFacade.loadOverview() }.toMap()

  override fun observeSettingsOverview(listener: (Map<String, Any?>) -> Unit): () -> Unit =
    observeWithInitial(
      listeners = settingsOverviewListeners,
      initialPayload = loadSettingsOverview(),
      listener = listener,
    )

  override fun loadSettingsDetail(routeIdRaw: String): Map<String, Any?> {
    val routeId = SettingsRouteId.fromWireValue(routeIdRaw) ?: SettingsRouteId.WORKSPACE_ACCESS
    return synchronized(lock) { settingsFacade.loadDetail(routeId) }.toMap()
  }

  override fun loadNotificationSettings(): Map<String, Any?> =
    synchronized(lock) { notificationSettingsFacade.load() }.toGatewayMap()

  override fun saveNotificationSettings(payload: Map<String, Any?>): Map<String, Any?> {
    val snapshot = synchronized(lock) {
      notificationSettingsFacade.save(payload.toSaveNotificationSettingsRequest())
    }
    return snapshot.toGatewayMap()
  }

  override fun loadStrongBackgroundSnapshot(): Map<String, Any?> = buildMap {
    putAll(strongBackgroundSettingsAccess.loadSnapshot())
    put(
      "runtimeServiceConnectionState",
      runtimeDiagnosticsBridge.runtimeServiceConnectionStateProvider()?.snapshotMap(),
    )
  }

  override fun performStrongBackgroundAction(actionId: String): Map<String, Any?> =
    strongBackgroundSettingsAccess.performAction(actionId)

  override fun loadNetworkSearchConfig(): Map<String, Any?> =
    synchronized(lock) { networkSearchConfigFacade.load() }.toMap()

  override fun saveNetworkSearchConfig(
    slots: List<Map<String, Any?>>,
  ): Map<String, Any?> {
    val snapshot = synchronized(lock) {
      networkSearchConfigFacade.save(
        SaveNetworkSearchConfigRequest(
          slots = slots.map { slot ->
            SaveNetworkSearchSlotRequest(
              id = slot["id"]?.toString().orEmpty(),
              providerId = slot["providerId"]?.toString().orEmpty(),
              label = slot["label"]?.toString().orEmpty(),
              baseUrl = slot["baseUrl"]?.toString().orEmpty(),
              model = slot["model"]?.toString().orEmpty(),
              apiKey = slot["apiKey"]?.toString().orEmpty(),
              enabled = slot["enabled"] as? Boolean ?: true,
            )
          },
        ),
      )
    }
    emitSettingsOverview()
    return snapshot.toMap()
  }

  override fun loadMediaSpeechConfig(): Map<String, Any?> =
    synchronized(lock) { mediaSpeechSettingsFacade.load() }.toMap()

  override fun saveMediaSpeechConfig(
    payload: Map<String, Any?>,
  ): Map<String, Any?> {
    val imageGeneration = payload["imageGeneration"] as? Map<String, Any?> ?: emptyMap()
    val voiceGeneration = payload["voiceGeneration"] as? Map<String, Any?> ?: emptyMap()
    val externalStt = payload["externalStt"] as? Map<String, Any?> ?: emptyMap()
    val onDeviceModel = payload["onDeviceModel"] as? Map<String, Any?> ?: emptyMap()
    val snapshot = synchronized(lock) {
      mediaSpeechSettingsFacade.save(
        SaveMediaSpeechConfigRequest(
          imageGeneration = SaveMediaProviderRequest(
            provider = imageGeneration["provider"]?.toString().orEmpty(),
            baseUrl = imageGeneration["baseUrl"]?.toString().orEmpty(),
            endpoint = imageGeneration["endpoint"]?.toString().orEmpty(),
            model = imageGeneration["model"]?.toString().orEmpty(),
          ),
          voiceGeneration = SaveVoiceProviderRequest(
            provider = voiceGeneration["provider"]?.toString().orEmpty(),
            baseUrl = voiceGeneration["baseUrl"]?.toString().orEmpty(),
            endpoint = voiceGeneration["endpoint"]?.toString().orEmpty(),
            voicePreset = voiceGeneration["voicePreset"]?.toString().orEmpty(),
          ),
          sttRouteId = payload["sttRouteId"]?.toString().orEmpty(),
          externalStt = SaveMediaProviderRequest(
            provider = externalStt["provider"]?.toString().orEmpty(),
            baseUrl = externalStt["baseUrl"]?.toString().orEmpty(),
            endpoint = externalStt["endpoint"]?.toString().orEmpty(),
            model = externalStt["model"]?.toString().orEmpty(),
          ),
          onDeviceModel = SaveOnDeviceSttRequest(
            modelPackage = onDeviceModel["modelPackage"]?.toString().orEmpty(),
            downloadStatus = onDeviceModel["downloadStatus"]?.toString().orEmpty(),
          ),
        ),
      )
    }
    emitSettingsOverview()
    return snapshot.toMap()
  }

  override fun loadSandboxSettings(): Map<String, Any?> =
    synchronized(lock) {
      resolvedSandboxSettingsRepository().load().toGatewayMap(strings.localeTag)
    }

  override fun saveSandboxSettings(payload: Map<String, Any?>): Map<String, Any?> {
    val saved = synchronized(lock) {
      val repository = resolvedSandboxSettingsRepository()
      val current = repository.load()
      val parsed = parseSandboxSettingsPayload(
        payload = payload,
        existingState = current.state,
      )
      repository.save(
        state = parsed.state,
        e2bApiKey = parsed.e2bApiKey,
      )
    }
    emitSettingsOverview()
    return saved.toGatewayMap(strings.localeTag)
  }

  override fun loadLlmConfig(): Map<String, Any?> =
    synchronized(lock) { llmConfigFacade.load() }.toMap()

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
  ): Map<String, Any?> {
    val snapshot = synchronized(lock) {
      llmConfigFacade.save(
        SaveLlmConfigRequest(
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
        ),
      )
    }
    return snapshot.toMap()
  }

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
  ): Map<String, Any?> {
    val snapshot = synchronized(lock) {
      llmConfigFacade.saveCustomProvider(
        SaveCustomLlmProviderRequest(
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
          openAiPromptCacheKeyStrategy = openAiPromptCacheKeyStrategy
            ?: LlmSettingsState.DEFAULT_OPENAI_PROMPT_CACHE_KEY_STRATEGY,
          openAiPromptCacheRetention = openAiPromptCacheRetention
            ?: LlmSettingsState.DEFAULT_OPENAI_PROMPT_CACHE_RETENTION,
          anthropicPromptCachingEnabled = anthropicPromptCachingEnabled
            ?: LlmSettingsState.DEFAULT_ANTHROPIC_PROMPT_CACHING_ENABLED,
          anthropicPromptCacheTtl = anthropicPromptCacheTtl
            ?: LlmSettingsState.DEFAULT_ANTHROPIC_PROMPT_CACHE_TTL,
          contextBudgetPreset = contextBudgetPreset,
          contextBudgetReservedOutputTokens = contextBudgetReservedOutputTokens,
          contextBudgetSafetyMarginTokens = contextBudgetSafetyMarginTokens,
          contextBudgetEffectiveInputPercent = contextBudgetEffectiveInputPercent,
        ),
      )
    }
    return snapshot.toMap()
  }

  override fun validateLlmConfig(
    providerId: String,
    protocol: String,
    baseUrl: String,
    apiKey: String,
    model: String,
    reasoningEffort: String,
  ): Map<String, Any?> = llmConfigFacade.validate(
    ValidateLlmConfigRequest(
      providerId = providerId,
      protocol = protocol,
      baseUrl = baseUrl,
      apiKey = apiKey,
      model = model,
      reasoningEffort = reasoningEffort,
    ),
  ).toMap()

  override fun downloadOnDeviceLlmModel(modelId: String): Map<String, Any?> {
    val snapshot = synchronized(lock) {
      llmConfigFacade.downloadOnDeviceModel(modelId)
    }
    emitSettingsOverview()
    return snapshot.toMap()
  }

  override fun cancelOnDeviceLlmModelDownload(modelId: String): Map<String, Any?> {
    val snapshot = synchronized(lock) {
      llmConfigFacade.cancelOnDeviceModelDownload(modelId)
    }
    emitSettingsOverview()
    return snapshot.toMap()
  }

  override fun deleteOnDeviceLlmModel(modelId: String): Map<String, Any?> {
    val snapshot = synchronized(lock) {
      llmConfigFacade.deleteOnDeviceModel(modelId)
    }
    emitSettingsOverview()
    return snapshot.toMap()
  }

  override fun loadPersonalizationConfig(): Map<String, Any?> =
    synchronized(lock) { personalizationFacade.load() }.toMap()

  override fun savePersonalizationConfig(
    presetId: String,
    customLabel: String,
    customGuidance: String,
  ): Map<String, Any?> {
    val snapshot = synchronized(lock) {
      personalizationFacade.save(
        SavePersonalizationConfigRequest(
          presetId = presetId,
          customLabel = customLabel,
          customGuidance = customGuidance,
        ),
      )
    }
    emitSettingsOverview()
    return snapshot.toMap()
  }

  override fun setAppLanguage(languageId: String): Map<String, Any?> {
    val snapshot = synchronized(lock) {
      val updated = personalizationFacade.setAppLanguage(languageId)
      if (appContext == null) {
        updated
      } else {
        refreshLocalizedResourcesLocked()
        personalizationFacade.load()
      }
    }
    emitShellSnapshot()
    emitSettingsOverview()
    emitSkillsSnapshot()
    emitChatSnapshot()
    return snapshot.toMap()
  }

  override fun runPersonalizationReset(scopeId: String): Map<String, Any?> {
    val snapshot = synchronized(lock) {
      personalizationFacade.reset(PersonalizationResetScope.fromWireValue(scopeId))
    }
    emitSettingsOverview()
    return snapshot.toMap()
  }

  override fun probeTwinImportSource(filePath: String): Map<String, Any?> =
    localHostGateway.probeTwinImportSource(filePath)

  override fun loadMcpSettings(): Map<String, Any?> =
    synchronized(lock) { mcpSettingsFacade.load() }.toMap()

  override fun setMcpMasterEnabled(enabled: Boolean): Map<String, Any?> =
    synchronized(lock) { mcpSettingsFacade.setMasterEnabled(enabled) }.toMap()

  override fun setMcpServerEnabled(
    serverId: String,
    enabled: Boolean,
  ): Map<String, Any?> = synchronized(lock) {
    mcpSettingsFacade.setServerEnabled(serverId = serverId, enabled = enabled)
  }.toMap()

  override fun loadSafetySettings(): Map<String, Any?> =
    synchronized(lock) { safetySettingsFacade.load() }.toMap()

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
    val snapshot = synchronized(lock) {
      safetySettingsFacade.save(
        SaveSafetySettingsRequest(
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
        ),
      )
    }
    emitChatSnapshot()
    return snapshot.toMap()
  }

  override fun loadSkillsSnapshot(
    query: String,
    suggestedLimit: Int,
  ): Map<String, Any?> {
    val normalizedQuery = query.trim()
    val snapshot = if (normalizedQuery.isEmpty()) {
      loadDefaultSkillsSnapshot()
    } else {
      loadQueriedSkillsSnapshot(
        query = normalizedQuery,
        suggestedLimit = suggestedLimit,
      )
    }
    return snapshot.toGatewayMap()
  }

  override fun observeSkills(listener: (Map<String, Any?>) -> Unit): () -> Unit =
    observeWithInitial(
      listeners = skillsListeners,
      initialPayload = loadSkillsSnapshot(query = "", suggestedLimit = 0),
      listener = listener,
    )

  override fun loadFilesSnapshot(): Map<String, Any?> =
    localHostGateway.loadFilesSnapshot()

  override fun loadWorkspaceImagePreview(relativePath: String): Map<String, Any?> =
    localHostGateway.loadWorkspaceImagePreview(relativePath)

  override fun loadWorkspaceTextPreview(relativePath: String): Map<String, Any?> =
    localHostGateway.loadWorkspaceTextPreview(relativePath)

  override fun loadWorkspaceVoicePlaybackSource(relativePath: String): Map<String, Any?> =
    localHostGateway.loadWorkspaceVoicePlaybackSource(relativePath)

  override fun loadWorkspaceTextDocument(relativePath: String): Map<String, Any?> =
    localHostGateway.loadWorkspaceTextDocument(relativePath)

  override fun openWorkspaceEntry(relativePath: String) {
    localHostGateway.openWorkspaceEntry(relativePath)
  }

  override fun openExternalUri(uri: String) {
    localHostGateway.openExternalUri(uri)
  }

  override fun copyRichTextToClipboard(plainText: String, htmlText: String?) {
    localHostGateway.copyRichTextToClipboard(plainText = plainText, htmlText = htmlText)
  }

  override fun createWorkspaceFolder(
    parentRelativePath: String,
    name: String,
  ): Map<String, Any?> = localHostGateway.createWorkspaceFolder(parentRelativePath, name)

  override fun createWorkspaceTextFile(
    parentRelativePath: String,
    name: String,
  ): Map<String, Any?> = localHostGateway.createWorkspaceTextFile(parentRelativePath, name)

  override fun renameWorkspaceEntry(
    targetRelativePath: String,
    newName: String,
  ): Map<String, Any?> = localHostGateway.renameWorkspaceEntry(targetRelativePath, newName)

  override fun deleteWorkspaceEntries(relativePaths: List<String>): Map<String, Any?> =
    localHostGateway.deleteWorkspaceEntries(relativePaths)

  override fun saveWorkspaceTextDocument(
    targetRelativePath: String,
    content: String,
  ): Map<String, Any?> = localHostGateway.saveWorkspaceTextDocument(targetRelativePath, content)

  override fun pasteWorkspaceEntries(
    sourceRelativePaths: List<String>,
    destinationRelativePath: String,
    move: Boolean,
  ): Map<String, Any?> = localHostGateway.pasteWorkspaceEntries(
    sourceRelativePaths = sourceRelativePaths,
    destinationRelativePath = destinationRelativePath,
    move = move,
  )

  override fun shareWorkspaceEntries(relativePaths: List<String>) {
    localHostGateway.shareWorkspaceEntries(relativePaths)
  }

  override fun showNativeToast(message: String) {
    localHostGateway.showNativeToast(message)
  }

  override fun setSkillEnabled(skillId: String, enabled: Boolean) {
    synchronized(lock) {
      require(skillsFacade.setSkillEnabled(skillId = skillId, enabled = enabled)) {
        "Skill '$skillId' is not installed."
      }
    }
    emitSkillsSnapshot()
  }

  override fun installSuggestedSkill(skillId: String): String {
    return installSkillSource(sourceRef = skillId, selectedSkillName = "")
  }

  override fun installSkillSource(
    sourceRef: String,
    selectedSkillName: String,
  ): String {
    val normalizedSourceRef = sourceRef.trim()
    val normalizedSelectedSkillName = selectedSkillName.trim()
    require(normalizedSourceRef.isNotEmpty()) {
      "Skill source cannot be blank."
    }
    val result = synchronized(lock) {
      skillsFacade.installSkillSource(
        sourceRef = normalizedSourceRef,
        selectedSkillName = normalizedSelectedSkillName,
      )
    }
    require(result.succeeded) {
      result.errorMessage?.trim()?.takeIf(String::isNotBlank)
        ?: "Unable to install '$normalizedSourceRef'."
    }
    emitSkillsSnapshot()
    return result.installedSkillId
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let(strings.skillInstalled)
      ?: strings.skillInstalled(
        normalizedSelectedSkillName.takeIf(String::isNotBlank) ?: normalizedSourceRef,
      )
  }

  override fun installSkillSourceBatch(
    sourceRef: String,
    selectedSkillNames: List<String>,
  ): String {
    val normalizedSourceRef = sourceRef.trim()
    val normalizedSelectedSkillNames = selectedSkillNames
      .asSequence()
      .map(String::trim)
      .filter(String::isNotBlank)
      .distinct()
      .toList()
    require(normalizedSourceRef.isNotEmpty()) {
      "Skill source cannot be blank."
    }
    require(normalizedSelectedSkillNames.isNotEmpty()) {
      "At least one skill must be selected."
    }
    val attempt = synchronized(lock) {
      skillsFacade.installSkillSourceBatch(
        sourceRef = normalizedSourceRef,
        selectedSkillNames = normalizedSelectedSkillNames,
      )
    }
    val result = requireNotNull(attempt.result) {
      attempt.errorMessage?.trim()?.takeIf(String::isNotBlank)
        ?: "Unable to install selected skills from '$normalizedSourceRef'."
    }
    if (result.failedCount > 0) {
      throw IllegalStateException(
        result.entries.firstNotNullOfOrNull { entry -> entry.errorMessage?.trim()?.takeIf(String::isNotBlank) }
          ?: "Unable to install selected skills from '$normalizedSourceRef'.",
      )
    }
    emitSkillsSnapshot()
    return if (normalizedSelectedSkillNames.size == 1) {
      strings.skillInstalled(normalizedSelectedSkillNames.single())
    } else {
      "Installed ${result.installedCount} skills."
    }
  }

  override fun inspectSkillSource(sourceRef: String): Map<String, Any?> {
    val normalizedSourceRef = sourceRef.trim()
    require(normalizedSourceRef.isNotEmpty()) {
      "Skill source cannot be blank."
    }
    val attempt = synchronized(lock) {
      skillsFacade.inspectSkillSource(normalizedSourceRef)
    }
    val result = requireNotNull(attempt.result) {
      attempt.errorMessage?.trim()?.takeIf(String::isNotBlank)
        ?: "Unable to inspect '$normalizedSourceRef'."
    }
    return result.toGatewayMap()
  }

  override fun deleteInstalledSkill(skillId: String): String {
    val normalizedSkillId = skillId.trim()
    require(normalizedSkillId.isNotEmpty()) {
      "Skill id cannot be blank."
    }
    val removed = synchronized(lock) {
      skillsFacade.deleteInstalledSkill(normalizedSkillId)
    }
    require(removed) {
      "Unable to remove '$normalizedSkillId'."
    }
    emitSkillsSnapshot()
    return strings.skillRemoved(normalizedSkillId)
  }

  override fun refreshSkills(): String {
    synchronized(lock) {
      skillsFacade.refresh()
    }
    emitSkillsSnapshot()
    return strings.skillsReloaded
  }

  override fun checkInstalledSkillUpdates(skillId: String): String {
    val normalizedSkillId = skillId.trim()
    val report = synchronized(lock) {
      skillsFacade.checkInstalledSkillUpdates(normalizedSkillId)
    }
    return renderInstalledSkillUpdateCheckMessage(
      requestedSkillId = normalizedSkillId.takeIf(String::isNotBlank),
      report = report,
    )
  }

  override fun updateInstalledSkill(skillId: String): String {
    val normalizedSkillId = skillId.trim()
    val report = synchronized(lock) {
      skillsFacade.updateInstalledSkill(normalizedSkillId)
    }
    if (report.updatedCount == 0 && report.failedCount > 0 && report.skippedCount == 0) {
      throw IllegalStateException(
        report.results.firstNotNullOfOrNull { result -> result.errorMessage?.trim()?.takeIf(String::isNotBlank) }
          ?: "SkillsUpdate failed.",
      )
    }
    emitSkillsSnapshot()
    return renderInstalledSkillUpdateMessage(
      requestedSkillId = normalizedSkillId.takeIf(String::isNotBlank),
      report = report,
    )
  }

  private fun renderInstalledSkillUpdateCheckMessage(
    requestedSkillId: String?,
    report: SkillPackageCheckReport,
  ): String {
    val requestedResult = requestedSkillId?.let { skillId ->
      report.results.firstOrNull { result -> result.skillId == skillId }
    }
    if (requestedResult != null) {
      return renderInstalledSkillUpdateCheckResult(requestedResult)
    }
    if (requestedSkillId != null) {
      return if (isChineseHostLocale()) {
        "未找到已安装的技能 '$requestedSkillId'。"
      } else {
        "Installed skill '$requestedSkillId' was not found."
      }
    }
    if (report.results.isEmpty()) {
      return if (isChineseHostLocale()) {
        "没有可检查更新的已安装技能。"
      } else {
        "No installed skills to check for updates."
      }
    }
    return if (isChineseHostLocale()) {
      "已检查 ${report.results.size} 个技能：可更新 ${report.updateAvailableCount} 个，已是最新 ${report.upToDateCount} 个，检查失败 ${report.sourceUnavailableCount + report.unsupportedCount} 个。"
    } else {
      "Checked ${report.results.size} skills: ${report.updateAvailableCount} update available, ${report.upToDateCount} up to date, ${report.sourceUnavailableCount + report.unsupportedCount} failed."
    }
  }

  private fun renderInstalledSkillUpdateCheckResult(
    result: SkillPackageCheckResult,
  ): String {
    val errorMessage = result.errorMessage?.trim()?.takeIf(String::isNotBlank)
    return when (result.status) {
      SkillPackageCheckStatus.UP_TO_DATE -> if (isChineseHostLocale()) {
        "技能 '${result.skillId}' 已是最新版本。"
      } else {
        "Skill '${result.skillId}' is up to date."
      }

      SkillPackageCheckStatus.UPDATE_AVAILABLE -> if (isChineseHostLocale()) {
        "技能 '${result.skillId}' 有可用更新。"
      } else {
        "Update available for '${result.skillId}'."
      }

      SkillPackageCheckStatus.SOURCE_UNAVAILABLE,
      SkillPackageCheckStatus.UNSUPPORTED_SOURCE,
      -> errorMessage ?: if (isChineseHostLocale()) {
        "无法检查技能 '${result.skillId}' 的更新。"
      } else {
        "Unable to check '${result.skillId}' for updates."
      }
    }
  }

  private fun renderInstalledSkillUpdateMessage(
    requestedSkillId: String?,
    report: SkillPackageUpdateReport,
  ): String {
    val requestedResult = requestedSkillId?.let { skillId ->
      report.results.firstOrNull { result -> result.skillId == skillId }
    }
    if (requestedResult != null) {
      return renderInstalledSkillUpdateResult(requestedResult)
    }
    if (requestedSkillId != null) {
      return if (isChineseHostLocale()) {
        "未找到已安装的技能 '$requestedSkillId'。"
      } else {
        "Installed skill '$requestedSkillId' was not found."
      }
    }
    if (report.results.isEmpty()) {
      return if (isChineseHostLocale()) {
        "没有可更新的已安装技能。"
      } else {
        "No installed skills to update."
      }
    }
    if (report.updatedCount == 0 && report.failedCount == 0) {
      return if (isChineseHostLocale()) {
        "所有已安装技能都已是最新版本。"
      } else {
        "All installed skills are already up to date."
      }
    }
    return if (isChineseHostLocale()) {
      "技能更新完成：已更新 ${report.updatedCount} 个，跳过 ${report.skippedCount} 个，失败 ${report.failedCount} 个。"
    } else {
      "Skill update finished: ${report.updatedCount} updated, ${report.skippedCount} skipped, ${report.failedCount} failed."
    }
  }

  private fun renderInstalledSkillUpdateResult(
    result: SkillPackageUpdateResult,
  ): String {
    val errorMessage = result.errorMessage?.trim()?.takeIf(String::isNotBlank)
    return when (result.status) {
      SkillPackageUpdateStatus.UPDATED -> if (isChineseHostLocale()) {
        "已更新技能 '${result.skillId}'。"
      } else {
        "Updated '${result.skillId}'."
      }

      SkillPackageUpdateStatus.SKIPPED -> if (result.checkStatus == SkillPackageCheckStatus.UP_TO_DATE) {
        if (isChineseHostLocale()) {
          "技能 '${result.skillId}' 已是最新版本。"
        } else {
          "Skill '${result.skillId}' is already up to date."
        }
      } else if (isChineseHostLocale()) {
        "已跳过技能 '${result.skillId}'。"
      } else {
        "Skipped '${result.skillId}'."
      }

      SkillPackageUpdateStatus.FAILED -> errorMessage ?: if (isChineseHostLocale()) {
        "无法更新技能 '${result.skillId}'。"
      } else {
        "Unable to update '${result.skillId}'."
      }
    }
  }

  override fun loadSkillInstructions(skillId: String): Map<String, Any?> {
    val instructions = synchronized(lock) {
      skillsFacade.loadInstructions(skillId)
    }
    requireNotNull(instructions) {
      "Skill '$skillId' is unavailable."
    }
    return instructions.toGatewayMap()
  }

  override fun loadSuggestedSkillInstructions(
    sourceRef: String,
    selectedSkillName: String,
  ): Map<String, Any?> {
    val normalizedSourceRef = sourceRef.trim()
    require(normalizedSourceRef.isNotEmpty()) {
      "Skill source cannot be blank."
    }
    val instructions = synchronized(lock) {
      skillsFacade.loadSuggestedInstructions(
        sourceRef = normalizedSourceRef,
        selectedSkillName = selectedSkillName.trim(),
      )
    }
    requireNotNull(instructions) {
      "Skill source '$normalizedSourceRef' is unavailable."
    }
    return instructions.toGatewayMap()
  }

  override fun activateSkillsInstallSource(sourceId: String): String =
    synchronized(lock) { skillsFacade.activateInstallSource(sourceId) }

  private fun loadDefaultSkillsSnapshot(): SkillsSnapshot {
    return synchronized(lock) { skillsFacade.loadSnapshot() }
  }

  private fun loadQueriedSkillsSnapshot(
    query: String,
    suggestedLimit: Int,
  ): SkillsSnapshot = synchronized(lock) {
    skillsFacade.loadSnapshot(
      query = query,
      suggestedLimit = suggestedLimit,
    )
  }

  override fun loadChatSnapshot(): Map<String, Any?> {
    val initialBuild = synchronized(lock) {
      buildChatSnapshotLocked()
    }
    val mergedSynchronously = scheduleVoiceMetadataBackfill(initialBuild.visibleAttachments)
    val finalBuild = if (mergedSynchronously) {
      synchronized(lock) {
        buildChatSnapshotLocked()
      }
    } else {
      initialBuild
    }
    return finalBuild.snapshot
  }

  private fun buildChatSnapshotLocked(): ChatSnapshotBuildResult {
    val chatState = chatSessionStore.loadState()
    val activeSession = chatState.activeSession
    repairStaleSupplementsLocked(activeSession.sessionId)
    val llmConfig = llmConfigFacade.load()
    val warmupState = onDeviceWarmupStateForSnapshotLocked(llmConfig)
    val visibleMessages = activeSession.messages.filter(::isVisibleChatMessage)
    val pendingUserInputs = chatSessionStore.loadPendingUserInputs(activeSession.sessionId)
    val pendingSupplements = supplementStoreForSession(activeSession.sessionId).snapshot()
    val runs = runtimeSession(activeSession.sessionId).listRuns()
    val recentEvents = userVisibleRuntimeEvents(
      runs = runs,
      recentEvents = mergedRuntimeEventsLocked(
        sessionId = activeSession.sessionId,
        runs = runs,
      ),
    )
    val displayedRuns = displayedRunsForSnapshot(
      runs = runs,
      recentEvents = recentEvents,
    )
    val renderedMessages = renderedChatMessagesLocked(
      visibleMessages = visibleMessages,
      runs = displayedRuns,
      runtimeEvents = recentEvents,
      pendingUserInputs = pendingUserInputs,
      pendingSupplements = pendingSupplements,
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
            ),
            "meta" to strings.chatMessagesBadge(session.messageCount),
            "lastMessageAtEpochMs" to session.lastMessageAtEpochMs,
            "isSelected" to (session.sessionId == activeSession.sessionId),
            "unreadCount" to unreadCount,
          )
        },
      ),
      "runtimeActivity" to runtimeActivitySnapshotMap(
        sessionId = activeSession.sessionId,
        displayedRuns = displayedRuns,
        recentEvents = recentEvents,
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
    observeWithInitial(
      listeners = chatListeners,
      initialPayload = loadChatSnapshot(),
      listener = listener,
    )

  override fun loadChatRuntimeSnapshot(): Map<String, Any?> = synchronized(lock) {
    val activeSessionId = chatSessionStore.loadState().activeSession.sessionId
    runtimeActivitySnapshotLocked(activeSessionId)
  }

  override fun loadChatRunSnapshot(runId: String): Map<String, Any?>? = synchronized(lock) {
    findRunSnapshotLocked(runId)
      ?.takeIf(::isUserVisibleRun)
      ?.let(::runSnapshotToMap)
  }

  override fun waitForChatRun(
    runId: String,
    timeoutMs: Long,
  ): Map<String, Any?>? = waitForRunSnapshot(runId, timeoutMs)
    ?.takeIf(::isUserVisibleRun)
    ?.let(::runSnapshotToMap)

  fun waitForChatRun(runId: String): Map<String, Any?>? =
    waitForChatRun(runId = runId, timeoutMs = DEFAULT_RUN_WAIT_TIMEOUT_MS)

  override fun observeChatRuntime(listener: (Map<String, Any?>) -> Unit): () -> Unit =
    observeWithInitial(
      listeners = chatRuntimeListeners,
      initialPayload = loadChatRuntimeSnapshot(),
      listener = listener,
    )

  override fun observeLiveAssistantDraftEvents(listener: (Map<String, Any?>) -> Unit): () -> Unit {
    synchronized(lock) {
      liveAssistantDraftEventListeners += listener
    }
    return {
      synchronized(lock) {
        liveAssistantDraftEventListeners -= listener
      }
    }
  }

  override fun refreshSandboxSessionInfo() {
    synchronized(lock) {
      val sessionId = chatSessionStore.loadState().activeSession.sessionId
      submitSandboxSessionInfoRefreshTask(
        sessionId = sessionId,
        runtimeHostAccess = runtimeHostAccess,
        taskSafetyMetadata = safetyMetadataForTask(safetySettingsFacade.load()),
        lifecycleDescriptor = lifecycleDescriptor,
      )
    }
    emitChatSnapshot()
    emitChatRuntimeSnapshot()
  }

  override fun loadMemoryDebugSnapshot(): Map<String, Any?> = synchronized(lock) {
    chatDebugProjector.loadMemoryDebugSnapshot(
      sessionId = chatSessionStore.loadState().activeSession.sessionId,
    )
  }

  override fun loadSoulDebugSnapshot(): Map<String, Any?> = synchronized(lock) {
    chatDebugProjector.loadSoulDebugSnapshot(
      sessionId = chatSessionStore.loadState().activeSession.sessionId,
    )
  }

  override fun searchMemoryDebug(
    query: String,
    maxResults: Int,
    minScore: Int,
  ): Map<String, Any?> = synchronized(lock) {
    chatDebugProjector.searchMemoryDebug(
      sessionId = chatSessionStore.loadState().activeSession.sessionId,
      query = query,
      maxResults = maxResults,
      minScore = minScore,
    )
  }

  override fun getMemoryDebugSlice(
    path: String,
    fromLine: Int?,
    lines: Int,
  ): Map<String, Any?> = synchronized(lock) {
    chatDebugProjector.getMemoryDebugSlice(
      sessionId = chatSessionStore.loadState().activeSession.sessionId,
      path = path,
      fromLine = fromLine,
      lines = lines,
    )
  }

  override fun applyMemoryDebugAction(
    recordId: String,
    actionId: String,
  ): Map<String, Any?> {
    return synchronized(lock) {
      val store = personalizationLocalStore
        ?: error("Memory debug actions require a personalization memory store.")
      val sessionId = chatSessionStore.loadState().activeSession.sessionId
      val action = MemoryOperatorAction.fromWireValue(actionId)
        ?: throw IllegalArgumentException("Unsupported memory debug action '$actionId'.")
      val result = MemoryOperator(
        store = store.asMemoryStore(),
      ).apply(
        MemoryOperatorRequest(
          recordId = recordId,
          action = action,
          actorSessionId = sessionId,
        ),
      )
      if (result.applied) {
        store.appendMemoryDebugActionAudit(
          newMemoryDebugActionAuditEntry(
            recordId = recordId,
            action = action,
            sessionId = sessionId,
          ),
        )
      }
      mapOf(
        "recordId" to recordId,
        "action" to action.wireValue,
        "applied" to result.applied,
      )
    }
  }

  override fun loadMemoryDebugLinksSnapshot(): Map<String, Any?> = synchronized(lock) {
    val activeSessionId = chatSessionStore.loadState().activeSession.sessionId
    val allRuns = chatSessionStore.loadState().sessions
      .mapTo(linkedSetOf()) { session -> session.sessionId }
      .flatMap { sessionId ->
        runtimeSession(sessionId).listRuns()
      }
    chatDebugProjector.loadMemoryDebugLinksSnapshot(
      activeSessionId = activeSessionId,
      allRuns = allRuns,
      runtimeEventsBySession = chatRuntimeEventState.snapshotBySession(),
    )
  }

  override fun createChatSession() {
    val sessionId = synchronized(lock) {
      chatSessionMutationCoordinator.createChatSession()
    }
    chatSessionMutationCoordinator.repairTerminalReplay(sessionId)
    emitChatSnapshot()
    emitChatRuntimeSnapshot()
  }

  override fun selectChatSession(sessionId: String) {
    val resolvedSessionId = synchronized(lock) {
      chatSessionMutationCoordinator.selectChatSession(sessionId)
    }
    chatSessionMutationCoordinator.repairTerminalReplay(resolvedSessionId)
    emitChatSnapshot()
    emitChatRuntimeSnapshot()
  }

  override fun copyChatSession(sessionId: String) {
    val copiedSessionId = synchronized(lock) {
      chatSessionMutationCoordinator.copyChatSession(sessionId)
    }
    chatSessionMutationCoordinator.repairTerminalReplay(copiedSessionId)
    emitChatSnapshot()
    emitChatRuntimeSnapshot()
  }

  override fun branchChatSessionFromMessage(
    sessionId: String,
    messageId: String,
  ) {
    val branchedSessionId = synchronized(lock) {
      chatSessionMutationCoordinator.branchChatSessionFromMessage(sessionId, messageId)
    } ?: return
    chatSessionMutationCoordinator.repairTerminalReplay(branchedSessionId)
    emitChatSnapshot()
    emitChatRuntimeSnapshot()
  }

  override fun deleteChatSession(sessionId: String) {
    val resolvedSessionId = synchronized(lock) {
      chatSessionMutationCoordinator.deleteChatSession(sessionId)
    } ?: return
    chatSessionMutationCoordinator.repairTerminalReplay(resolvedSessionId)
    emitChatSnapshot()
    emitChatRuntimeSnapshot()
  }

  override fun deleteChatMessage(
    sessionId: String,
    messageId: String,
  ) {
    val resolvedSessionId = synchronized(lock) {
      chatSessionMutationCoordinator.deleteChatMessage(sessionId, messageId)
    } ?: return
    chatSessionMutationCoordinator.repairTerminalReplay(resolvedSessionId)
    emitChatSnapshot()
    emitChatRuntimeSnapshot()
  }

  override fun recallChatMessage(
    sessionId: String,
    messageId: String,
  ) {
    val resolvedSessionId = synchronized(lock) {
      chatSessionMutationCoordinator.recallChatMessage(sessionId, messageId)
    } ?: return
    chatSessionMutationCoordinator.repairTerminalReplay(resolvedSessionId)
    emitChatSnapshot()
    emitChatRuntimeSnapshot()
  }

  override fun approveChatApproval(taskIdOrRunId: String) {
    synchronized(lock) {
      chatApprovalDecisionCoordinator.approve(taskIdOrRunId)
    }
    emitChatSnapshot()
    emitChatRuntimeSnapshot()
  }

  override fun approveChatApprovalForSession(taskIdOrRunId: String) {
    synchronized(lock) {
      chatApprovalDecisionCoordinator.approveForSession(taskIdOrRunId)
    }
    emitChatSnapshot()
    emitChatRuntimeSnapshot()
  }

  override fun rejectChatApproval(taskIdOrRunId: String) {
    synchronized(lock) {
      chatApprovalDecisionCoordinator.reject(taskIdOrRunId)
    }
    emitChatSnapshot()
    emitChatRuntimeSnapshot()
  }

  override fun interruptChatRun(taskIdOrRunId: String) {
    synchronized(lock) {
      chatRunControlCoordinator.interruptChatRun(taskIdOrRunId)
    }
    emitChatSnapshot()
    emitChatRuntimeSnapshot()
  }

  override fun retryChatRun(taskIdOrRunId: String) {
    synchronized(lock) {
      chatRunControlCoordinator.retryChatRun(taskIdOrRunId)
    }
    emitChatSnapshot()
    emitChatRuntimeSnapshot()
  }

  override fun submitChatMessage(
    text: String,
    attachments: List<OpenCrayFinalAttachment>,
  ): Map<String, Any?>? {
    val result = synchronized(lock) {
      chatSubmissionCoordinator.submitChatMessage(
        text = text,
        attachments = attachments,
      )
    }
    if (!result.didMutate) {
      return null
    }
    emitChatSnapshot()
    emitChatRuntimeSnapshot()
    return result.submission?.let(::runSubmissionToMap)
  }

  fun submitChatMessage(text: String): Map<String, Any?>? =
    submitChatMessage(
      text = text,
      attachments = emptyList(),
    )

  fun getMemoryDebugSlice(path: String): Map<String, Any?> =
    getMemoryDebugSlice(
      path = path,
      fromLine = null,
      lines = 12,
    )

  fun getMemoryDebugSlice(path: String, fromLine: Int?): Map<String, Any?> =
    getMemoryDebugSlice(
      path = path,
      fromLine = fromLine,
      lines = 12,
    )

  override fun importDraftChatAttachments(
    requestedKind: String,
    uriStrings: List<String>,
  ): List<Map<String, Any?>> = localHostGateway.importDraftChatAttachments(
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
      runtimeSession(sessionId).listRuns()
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

  private fun runtimeSession(sessionId: String): OpenCrayRuntimeSessionAccess =
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

  private fun isUserVisibleRun(run: AgentRunSnapshot): Boolean =
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
          ),
          toolReason = runSnapshot?.resultMetadata?.get("toolReason")
            ?: runSnapshot?.lastEvent?.let(::toolReasonFromEvent),
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
      ),
      toolReason = result.metadata["toolReason"],
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
    chatRuntimeEventState.append(
      sessionId = sessionId,
      event = event,
      maxHistory = MAX_RUNTIME_EVENT_HISTORY,
    )
    runEventJournalStoreForSession(sessionId).append(event)
    maybeClearPromptCheckpointAfterRuntimeEventLocked(sessionId = sessionId, event = event)
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

  private fun updateAssistantDraftLocked(
    sessionId: String,
    task: AgentTask,
    text: String,
    emittedAtEpochMs: Long,
  ): Boolean {
    val pendingMessageId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: return false
    val normalizedText = text.trim().takeIf(String::isNotBlank) ?: return false
    val sessionDrafts = liveAssistantDraftsBySession.getOrPut(sessionId) { linkedMapOf() }
    val updatedDraft = LiveAssistantDraftSnapshot(
      runId = runIdFor(task),
      taskId = task.id,
      pendingMessageId = pendingMessageId,
      text = normalizedText,
      updatedAtEpochMs = emittedAtEpochMs,
    )
    val existing = sessionDrafts[pendingMessageId]
    if (existing == updatedDraft) {
      return false
    }
    sessionDrafts[pendingMessageId] = updatedDraft
    return true
  }

  private fun clearAssistantDraftLocked(
    sessionId: String,
    pendingMessageId: String?,
  ): Boolean {
    val normalizedPendingMessageId = pendingMessageId
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: return false
    val sessionDrafts = liveAssistantDraftsBySession[sessionId] ?: return false
    val removed = sessionDrafts.remove(normalizedPendingMessageId) != null
    if (sessionDrafts.isEmpty()) {
      liveAssistantDraftsBySession.remove(sessionId)
    }
    return removed
  }

  private fun liveAssistantDraftsForSnapshot(
    sessionId: String,
    displayedRuns: List<AgentRunSnapshot>,
  ): List<LiveAssistantDraftSnapshot> {
    val sessionDrafts = liveAssistantDraftsBySession[sessionId].orEmpty()
    if (sessionDrafts.isEmpty()) {
      return emptyList()
    }
    val visiblePendingMessageIds = displayedRuns
      .asSequence()
      .filter(AgentRunSnapshot::isActive)
      .mapNotNull { run -> run.pendingMessageId?.trim()?.takeIf(String::isNotBlank) }
      .toSet()
    if (visiblePendingMessageIds.isEmpty()) {
      return emptyList()
    }
    return visiblePendingMessageIds.mapNotNull(sessionDrafts::get)
  }

  private fun runtimeActivitySnapshotLocked(sessionId: String): Map<String, Any?> {
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
    )
    val liveAssistantDrafts = liveAssistantDraftsForSnapshot(
      sessionId = sessionId,
      displayedRuns = displayedRuns,
    )
    return buildMap {
      put("sessionId", sessionId)
      putRuntimeServiceDiagnosticsSnapshot(
        hostLifecycle = lifecycleDescriptor,
        runtimeOwnerLifecycle = runtimeDiagnosticsBridge.runtimeOwnerDescriptor,
        runtimeOwnerWorkSummary = runtimeHostAccess.activeWorkSummary(),
        runtimeServiceLifecycle = runtimeDiagnosticsBridge.runtimeServiceDescriptor,
        runtimeServiceWorkState = runtimeDiagnosticsBridge.runtimeServiceWorkStateProvider(),
        runtimeServiceKeepAliveState =
          runtimeDiagnosticsBridge.runtimeServiceKeepAliveStateProvider(),
        runtimeServiceConnectionState =
          runtimeDiagnosticsBridge.runtimeServiceConnectionStateProvider(),
        includeNullRuntimeServiceFields = true,
      )
      put(
        "activeRuns",
        displayedRuns.filter(AgentRunSnapshot::isActive).map(::runSnapshotToMap),
      )
      put("retainedRuns", retainedRunsForSnapshot(displayedRuns).map(::runSnapshotToMap))
      put("subAgents", subAgentSnapshots.map(::subAgentSnapshotToMap))
      put("events", recentEvents.map(::runtimeEventToMap))
      put("liveAssistantDrafts", liveAssistantDrafts.map(::liveAssistantDraftToMap))
    }
  }

  private fun subAgentSnapshotsForActivity(
    sessionId: String,
    displayedRuns: List<AgentRunSnapshot>,
    recentEvents: List<OpenCrayAgentRunEvent>,
  ): List<SubAgentActivitySnapshot> {
    val registrySnapshots = subAgentSnapshotsFromDurableSources(sessionId)
    val visibleRunIds = displayedRuns
      .mapTo(linkedSetOf(), AgentRunSnapshot::runId)
      .ifEmpty {
        recentEvents
          .mapNotNullTo(linkedSetOf()) { event ->
            event.runId.trim().takeIf(String::isNotBlank)
          }
      }
      .ifEmpty {
        registrySnapshots.mapNotNullTo(linkedSetOf()) { snapshot ->
          snapshot.parentRunId.trim().takeIf(String::isNotBlank)
        }
      }
    if (visibleRunIds.isEmpty()) {
      return emptyList()
    }
    val eventSnapshotsByKey = linkedMapOf<String, SubAgentActivitySnapshot>()
    val grouped = linkedMapOf<String, SubAgentActivityAccumulator>()
    recentEvents.forEach { event ->
      val subAgentEvent = event as? OpenCraySubAgentEvent ?: return@forEach
      if (subAgentEvent.runId !in visibleRunIds) {
        return@forEach
      }
      val key = subAgentRegistryKey(subAgentEvent)
      val existing = grouped[key]
      grouped[key] = if (existing == null) {
        SubAgentActivityAccumulator(
          firstEvent = subAgentEvent,
          latestEvent = subAgentEvent,
          eventCount = 1,
        )
      } else {
        existing.copy(
          latestEvent = subAgentEvent,
          eventCount = existing.eventCount + 1,
        )
      }
    }
    grouped.values.forEach { accumulator ->
      val firstEvent = accumulator.firstEvent
      val latestEvent = accumulator.latestEvent
      val snapshot = SubAgentActivitySnapshot(
        parentRunId = latestEvent.runId,
        parentTaskId = latestEvent.taskId,
        childRunId = latestEvent.childRunId,
        childTaskId = latestEvent.childTaskId,
        label = latestEvent.label,
        subagentType = latestEvent.subagentType,
        contextMode = latestEvent.contextMode,
        depth = latestEvent.depth,
        phase = latestEvent.phase.name.lowercase(),
        status = latestEvent.executionState?.wireValue,
        executionState = latestEvent.executionState?.wireValue,
        continuationKind = latestEvent.continuationKind?.wireValue,
        resumable = latestEvent.resumable,
        requiresUserAction = latestEvent.requiresUserAction,
        isHighRisk = latestEvent.isHighRisk,
        summary = latestEvent.summary,
        startedAtEpochMs = firstEvent.emittedAtEpochMs,
        updatedAtEpochMs = latestEvent.emittedAtEpochMs,
        eventCount = accumulator.eventCount,
        mailboxMessageCount = 0,
        mailboxPendingMessageCount = 0,
        mailboxLastDeliveredMessageId = null,
      )
      eventSnapshotsByKey[subAgentRegistryKey(snapshot)] = snapshot
    }
    registrySnapshots
      .filter { snapshot -> snapshot.parentRunId in visibleRunIds }
      .forEach { snapshot ->
        val key = subAgentRegistryKey(snapshot)
        val existing = eventSnapshotsByKey[key]
        eventSnapshotsByKey[key] = if (existing == null) {
          snapshot
        } else {
          snapshot.copy(
            startedAtEpochMs = minOf(existing.startedAtEpochMs, snapshot.startedAtEpochMs),
            updatedAtEpochMs = maxOf(existing.updatedAtEpochMs, snapshot.updatedAtEpochMs),
            eventCount = maxOf(existing.eventCount, snapshot.eventCount),
            mailboxMessageCount = snapshot.mailboxMessageCount,
            mailboxPendingMessageCount = snapshot.mailboxPendingMessageCount,
            mailboxLastDeliveredMessageId = snapshot.mailboxLastDeliveredMessageId,
          )
        }
      }
    return eventSnapshotsByKey.values.toList()
  }

  private fun subAgentRegistryKey(
    event: OpenCraySubAgentEvent,
  ): String = listOf(
    event.runId,
    event.childRunId.trim().takeIf(String::isNotBlank)
      ?: event.childTaskId.trim().takeIf(String::isNotBlank)
      ?: event.label.trim(),
  ).joinToString(separator = "|")

  private fun subAgentRegistryKey(
    snapshot: SubAgentActivitySnapshot,
  ): String = listOf(
    snapshot.parentRunId,
    snapshot.childRunId.trim().takeIf(String::isNotBlank)
      ?: snapshot.childTaskId.trim().takeIf(String::isNotBlank)
      ?: snapshot.label.trim(),
  ).joinToString(separator = "|")

  private fun subAgentSnapshotsFromDurableSources(
    sessionId: String,
  ): List<SubAgentActivitySnapshot> {
    val latestByKey = linkedMapOf<String, SubAgentActivitySnapshot>()
    runtimeSession(sessionId)
      .listSubAgentHandles()
      .forEach { handle ->
        val snapshot = subAgentActivitySnapshot(handle)
        val key = subAgentRegistryKey(snapshot)
        val existing = latestByKey[key]
        if (existing == null || snapshot.updatedAtEpochMs >= existing.updatedAtEpochMs) {
          latestByKey[key] = snapshot
        }
      }
    promptCheckpointStoreForSession(sessionId)
      .list()
      .asReversed()
      .forEach { checkpoint ->
        checkpointSubAgentHandles(checkpoint).forEach { handle ->
          val snapshot = subAgentActivitySnapshot(handle)
          val key = subAgentRegistryKey(snapshot)
          val existing = latestByKey[key]
          if (existing == null || snapshot.updatedAtEpochMs >= existing.updatedAtEpochMs) {
            latestByKey[key] = snapshot
          }
        }
      }
    return latestByKey.values.toList()
  }

  private fun checkpointSubAgentHandles(
    checkpoint: PersistedPromptCheckpoint,
  ): Sequence<SubAgentHandleState> = sequenceOf(
    checkpoint.promptResumeState,
    checkpoint.subAgentPromptResumeState,
  ).filterNotNull().flatMap { state -> state.subAgentHandles.asSequence() }

  private fun subAgentActivitySnapshot(
    handle: SubAgentHandleState,
  ): SubAgentActivitySnapshot {
    val mailbox = handle.normalizedMailbox()
    return SubAgentActivitySnapshot(
      parentRunId = handle.parentRunId,
      parentTaskId = handle.parentTaskId,
      childRunId = handle.childRunId,
      childTaskId = handle.childTaskId,
      label = handle.description,
      subagentType = handle.subagentType,
      contextMode = handle.contextMode,
      depth = handle.depth,
      phase = subAgentPhaseFor(handle.snapshot.state),
      status = handle.snapshot.state.wireValue,
      executionState = handle.snapshot.state.wireValue,
      continuationKind = handle.snapshot.continuationKind.wireValue,
      resumable = handle.snapshot.resumable,
      requiresUserAction = handle.snapshot.requiresUserAction,
      isHighRisk = handle.snapshot.isHighRisk,
      summary = handle.snapshot.headline,
      startedAtEpochMs = handle.createdAtEpochMs,
      updatedAtEpochMs = handle.updatedAtEpochMs,
      eventCount = 0,
      mailboxMessageCount = mailbox.messages.size,
      mailboxPendingMessageCount = mailbox.pendingMessages().size,
      mailboxLastDeliveredMessageId = mailbox.lastDeliveredMessageId,
    )
  }

  private fun subAgentPhaseFor(
    state: SubAgentExecutionState,
  ): String = when (state) {
    SubAgentExecutionState.RUNNING,
    SubAgentExecutionState.BACKGROUND_QUEUED,
    -> OpenCraySubAgentPhase.STARTED.name.lowercase(Locale.US)

    SubAgentExecutionState.BACKGROUND_RUNNING ->
      OpenCraySubAgentPhase.RESUMED.name.lowercase(Locale.US)

    SubAgentExecutionState.WAITING_APPROVAL,
    SubAgentExecutionState.WAITING_HIGH_RISK_APPROVAL,
    SubAgentExecutionState.FAILED,
    -> OpenCraySubAgentPhase.FAILED.name.lowercase(Locale.US)

    SubAgentExecutionState.COMPLETED ->
      OpenCraySubAgentPhase.COMPLETED.name.lowercase(Locale.US)

    SubAgentExecutionState.CANCELLED ->
      OpenCraySubAgentPhase.CANCELLED.name.lowercase(Locale.US)
  }

  private fun subAgentSnapshotToMap(snapshot: SubAgentActivitySnapshot): Map<String, Any?> = mapOf(
    "parentRunId" to snapshot.parentRunId,
    "parentTaskId" to snapshot.parentTaskId,
    "childRunId" to snapshot.childRunId,
    "childTaskId" to snapshot.childTaskId,
    "label" to snapshot.label,
    "subagentType" to snapshot.subagentType,
    "contextMode" to snapshot.contextMode,
    "depth" to snapshot.depth,
    "phase" to snapshot.phase,
    "status" to snapshot.status,
    "executionState" to snapshot.executionState,
    "continuationKind" to snapshot.continuationKind,
    "resumable" to snapshot.resumable,
    "requiresUserAction" to snapshot.requiresUserAction,
    "isHighRisk" to snapshot.isHighRisk,
    "summary" to snapshot.summary,
    "startedAtEpochMs" to snapshot.startedAtEpochMs,
    "updatedAtEpochMs" to snapshot.updatedAtEpochMs,
    "eventCount" to snapshot.eventCount,
    "mailboxMessageCount" to snapshot.mailboxMessageCount,
    "mailboxPendingMessageCount" to snapshot.mailboxPendingMessageCount,
    "mailboxLastDeliveredMessageId" to snapshot.mailboxLastDeliveredMessageId,
  )

  private fun displayedRunsForSnapshot(
    runs: List<AgentRunSnapshot>,
    recentEvents: List<OpenCrayAgentRunEvent>,
  ): List<AgentRunSnapshot> = userVisibleRuns(runs).map { run ->
    displayRunSnapshot(
      run = run,
      recentEvents = recentEvents,
    )
  }

  private fun displayRunSnapshot(
    run: AgentRunSnapshot,
    recentEvents: List<OpenCrayAgentRunEvent>,
  ): AgentRunSnapshot = run.copy(
    lastEvent = displayedLastEvent(
      run = run,
      recentEvents = recentEvents,
    ),
  )

  private fun displayedLastEvent(
    run: AgentRunSnapshot,
    recentEvents: List<OpenCrayAgentRunEvent>,
  ): OpenCrayAgentRunEvent? {
    val runEvents = executionScopedRunEvents(
      run = run,
      recentEvents = recentEvents,
    )
    val latest = runEvents.lastOrNull() ?: return run.lastEvent?.takeIf { event ->
      !isInternalPromptCheckpointEvent(event) &&
        eventMatchesRunExecution(run = run, event = event)
    }
    if (latest is OpenCrayApprovalEvent && latest.phase != OpenCrayApprovalPhase.REQUIRED) {
      val previousMeaningful = runEvents
        .dropLast(1)
        .asReversed()
        .firstOrNull { event ->
          event !is OpenCrayApprovalEvent || event.phase == OpenCrayApprovalPhase.REQUIRED
        }
      if (
        previousMeaningful is OpenCraySubAgentEvent &&
        previousMeaningful.phase == OpenCraySubAgentPhase.RESUMED
      ) {
        return previousMeaningful
      }
    }
    return latest
  }

  private fun executionScopedRunEvents(
    run: AgentRunSnapshot,
    recentEvents: List<OpenCrayAgentRunEvent>,
  ): List<OpenCrayAgentRunEvent> {
    val runEvents = recentEvents.filter { event -> event.runId == run.runId }
    val currentExecutionId = run.executionId?.trim()?.takeIf(String::isNotBlank)
    if (currentExecutionId == null) {
      return if (
        run.pendingExecutionKind?.trim()?.takeIf(String::isNotBlank) != null &&
        run.isActive
      ) {
        emptyList()
      } else {
        runEvents
      }
    }
    val matching = runEvents.filter { event ->
      event.executionId?.trim() == currentExecutionId
    }
    if (matching.isNotEmpty()) {
      return matching
    }
    val hasTaggedEvents = runEvents.any { event ->
      !event.executionId?.trim().isNullOrEmpty() ||
        event.executionOrdinal != null ||
        !event.executionKind?.trim().isNullOrEmpty()
    }
    if (hasTaggedEvents || run.executionOrdinal > 0) {
      return emptyList()
    }
    return runEvents.filter { event ->
      event.executionId?.trim().isNullOrEmpty() &&
        event.executionOrdinal == null &&
        event.executionKind?.trim().isNullOrEmpty()
    }
  }

  private fun eventMatchesRunExecution(
    run: AgentRunSnapshot,
    event: OpenCrayAgentRunEvent,
  ): Boolean {
    if (event.runId != run.runId) {
      return false
    }
    val currentExecutionId = run.executionId?.trim()?.takeIf(String::isNotBlank)
    if (currentExecutionId == null) {
      return run.pendingExecutionKind?.trim()?.takeIf(String::isNotBlank) == null || !run.isActive
    }
    return event.executionId?.trim() == currentExecutionId
  }

  private fun composerPlaceholderForSnapshot(
    displayedRuns: List<AgentRunSnapshot>,
    hasPendingApprovals: Boolean,
    warmupState: OnDeviceLlmWarmupState = OnDeviceLlmWarmupState(),
  ): String {
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

  private fun retainedRunsForSnapshot(
    runs: List<AgentRunSnapshot>,
  ): List<AgentRunSnapshot> {
    val latestRun = latestRunForSnapshot(runs) ?: return emptyList()
    return if (
      latestRun.isTerminal &&
      !latestRun.hasLiveManagedProcesses &&
      (
        isAwaitingDirectionRun(latestRun) ||
          isInterruptedOnRestoreRun(latestRun)
        )
    ) {
      listOf(latestRun)
    } else {
      emptyList()
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
  ) ?: run.lastEvent

  private fun isLlmRetryPausedAwaitingResumeRun(run: AgentRunSnapshot): Boolean =
    chatRunIsLlmRetryPausedAwaitingResume(run)

  private fun isInterruptedOnRestoreRun(run: AgentRunSnapshot): Boolean =
    chatRunIsInterruptedOnRestore(run)

  private fun requiresExplicitRetryAfterRestoreLocked(sessionId: String): Boolean =
    latestRunForSnapshot(runtimeSession(sessionId).listRuns())
      ?.let { run -> !run.isActive && isInterruptedOnRestoreRun(run) }
      ?: false

  private fun renderedChatMessagesLocked(
    visibleMessages: List<ChatTranscriptMessageEntry>,
    runs: List<AgentRunSnapshot>,
    runtimeEvents: List<OpenCrayAgentRunEvent>,
    pendingUserInputs: List<PendingUserInputEntry>,
    pendingSupplements: List<MidLoopSupplementEntry>,
  ): List<Map<String, Any?>> {
    val projectedMessages = projectedRuntimeMessagesForChatLocked(
      runs = runs,
      runtimeEvents = runtimeEvents,
    )
    val projectedPendingUserMessages = projectedPendingUserMessagesLocked(pendingUserInputs)
    val projectedPendingSupplementMessages = projectedPendingSupplementMessagesLocked(pendingSupplements)
    if (
      projectedMessages.isEmpty() &&
      projectedPendingUserMessages.isEmpty() &&
      projectedPendingSupplementMessages.isEmpty()
    ) {
      return visibleMessages.map(::chatMessageToMap)
    }
    val visibleMessageIds = visibleMessages
      .mapTo(linkedSetOf(), ChatTranscriptMessageEntry::messageId)
    val projectedByAnchor = projectedMessages
      .mapNotNull { projection ->
        val anchorMessageId = projection.anchorMessageId ?: return@mapNotNull null
        if (anchorMessageId !in visibleMessageIds) {
          return@mapNotNull null
        }
        anchorMessageId to projection
      }
      .groupBy(
        keySelector = Pair<String, ProjectedRuntimeChatMessage>::first,
        valueTransform = Pair<String, ProjectedRuntimeChatMessage>::second,
      )
    val merged = ArrayList<Map<String, Any?>>(visibleMessages.size + projectedMessages.size)
    visibleMessages.forEach { message ->
      projectedByAnchor[message.messageId]?.forEach { projection ->
        merged += projection.snapshot
      }
      merged += chatMessageToMap(message)
    }
    merged += projectedPendingUserMessages
    merged += projectedPendingSupplementMessages
    return merged
  }

  private fun projectedPendingUserMessagesLocked(
    pendingUserInputs: List<PendingUserInputEntry>,
  ): List<Map<String, Any?>> = pendingUserInputs.map { pendingInput ->
    chatMessageSnapshotMap(
      messageId = pendingInput.queueId,
      kind = "outbound",
      text = pendingInput.text,
      createdAtEpochMs = pendingInput.createdAtEpochMs.takeIf { createdAt -> createdAt > 0L },
      isEphemeral = true,
      attachments = pendingInput.attachments.map(::chatAttachmentSnapshotMap),
    )
  }

  private fun projectedPendingSupplementMessagesLocked(
    pendingSupplements: List<MidLoopSupplementEntry>,
  ): List<Map<String, Any?>> = pendingSupplements.map { supplement ->
    chatMessageSnapshotMap(
      messageId = supplement.entryId,
      kind = "outbound",
      text = supplement.text,
      createdAtEpochMs = supplement.createdAtEpochMs.takeIf { createdAt -> createdAt > 0L },
      isEphemeral = true,
    )
  }

  private fun projectedRuntimeMessagesForChatLocked(
    runs: List<AgentRunSnapshot>,
    runtimeEvents: List<OpenCrayAgentRunEvent>,
  ): List<ProjectedRuntimeChatMessage> {
    if (runtimeEvents.isEmpty()) {
      return emptyList()
    }
    val pendingMessageIdByRunId = linkedMapOf<String, String>()
    val pendingMessageIdByTaskId = linkedMapOf<String, String>()
    val runsByRunId = linkedMapOf<String, AgentRunSnapshot>()
    val runsByTaskId = linkedMapOf<String, AgentRunSnapshot>()
    runs.forEach { run ->
      val pendingMessageId = run.pendingMessageId
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: return@forEach
      pendingMessageIdByRunId[run.runId] = pendingMessageId
      pendingMessageIdByTaskId[run.taskId] = pendingMessageId
      runsByRunId[run.runId] = run
      runsByTaskId[run.taskId] = run
    }
    val orderedEvents = runtimeEvents
      .withIndex()
      .sortedWith(
        compareBy<IndexedValue<OpenCrayAgentRunEvent>> { indexed ->
          indexed.value.emittedAtEpochMs
        }.thenBy(IndexedValue<OpenCrayAgentRunEvent>::index),
      )
      .map(IndexedValue<OpenCrayAgentRunEvent>::value)
    return orderedEvents.mapNotNull { event ->
      val run = runsByRunId[event.runId] ?: runsByTaskId[event.taskId] ?: return@mapNotNull null
      if (!eventMatchesRunExecution(run = run, event = event)) {
        return@mapNotNull null
      }
      val anchorMessageId = pendingMessageIdByRunId[event.runId]
        ?: pendingMessageIdByTaskId[event.taskId]
        ?: return@mapNotNull null
      val text = projectedRuntimeMessageText(
        event = event,
      ) ?: return@mapNotNull null
      if (text.isBlank()) {
        return@mapNotNull null
      }
      ProjectedRuntimeChatMessage(
        anchorMessageId = anchorMessageId,
        snapshot = chatMessageSnapshotMap(
          messageId = runtimeProjectedMessageId(event),
          kind = projectedRuntimeMessageKind(event),
          text = text,
          createdAtEpochMs = event.emittedAtEpochMs.takeIf { emittedAt -> emittedAt > 0L },
          isEphemeral = true,
        ),
      )
    }
  }

  private fun projectedRuntimeMessageText(
    event: OpenCrayAgentRunEvent,
  ): String? = when (event) {
    is OpenCrayAssistantPhaseEvent -> if (event.isFinal || hideAssistantPhaseFromChatBubble(event)) {
      null
    } else {
      chatProgressText(event)
    }
    is OpenCraySupplementEvent -> event.text.trim().takeIf(String::isNotBlank)
    is OpenCrayApprovalEvent -> null
    is OpenCrayToolCallEvent -> null
    is OpenCrayToolResultEvent -> null
    is OpenCrayCancellationEvent -> null
    else -> null
  }

  private fun projectedRuntimeMessageKind(event: OpenCrayAgentRunEvent): String = when (event) {
    is OpenCraySupplementEvent -> "outbound"
    else -> "inbound"
  }

  private fun hideAssistantPhaseFromChatBubble(event: OpenCrayAssistantPhaseEvent): Boolean =
    (
      event.stage
      ?.trim()
      ?.lowercase(Locale.US)
      ?.let(HIDDEN_ASSISTANT_CHAT_STAGES::contains)
      ) == true

  private fun chatProgressText(event: OpenCrayAssistantPhaseEvent): String {
    val stage = event.stage?.trim().orEmpty()
    val text = event.text.trim()
    return when {
      stage.isEmpty() -> text
      text.isEmpty() -> stage
      else -> "$stage\n\n$text"
    }
  }

  private fun chatToolCallText(event: OpenCrayToolCallEvent): String {
    val toolName = event.call.toolName.trim().takeIf(String::isNotBlank) ?: "Tool"
    val summary = toolActionSummary(
      toolName = toolName,
      arguments = event.call.arguments,
    )
    val reason = event.call.reason
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let(::toolReasonText)
    return joinProjectedChatSections(
      summary,
      reason,
    )
  }

  private fun chatToolResultText(
    event: OpenCrayToolResultEvent,
    pairedToolCall: OpenCrayToolCallEvent?,
  ): String {
    val toolName = event.result.toolName.trim().takeIf(String::isNotBlank)
      ?: event.call.toolName.trim().takeIf(String::isNotBlank)
      ?: "Tool"
    val summary = toolResultActionSummary(
      toolName = toolName,
      event = event,
      pairedToolCall = pairedToolCall,
    )
    val resultSummary = toolResultMetadataSummary(
      toolName = toolName,
      metadata = event.result.metadata,
    )
    val body = toolResultBodyText(event.result)
    return joinProjectedChatSections(
      summary,
      resultSummary,
      body,
    )
  }

  private fun previousToolCallEvent(
    orderedEvents: List<OpenCrayAgentRunEvent>,
    beforeIndex: Int,
    resultEvent: OpenCrayToolResultEvent,
  ): OpenCrayToolCallEvent? {
    val normalizedToolName = resultEvent.result.toolName.trim().takeIf(String::isNotBlank)
      ?: resultEvent.call.toolName.trim().takeIf(String::isNotBlank)
      ?: return null
    for (index in beforeIndex - 1 downTo 0) {
      val candidate = orderedEvents[index] as? OpenCrayToolCallEvent ?: continue
      if (candidate.runId != resultEvent.runId && candidate.taskId != resultEvent.taskId) {
        continue
      }
      if (candidate.call.toolName.trim().equals(normalizedToolName, ignoreCase = true)) {
        return candidate
      }
    }
    return null
  }

  private fun toolActionSummary(
    toolName: String,
    arguments: JsonObject,
  ): String {
    val normalizedToolName = toolName.trim()
    val fallback = if (isChineseHostLocale()) {
      "调用工具 $normalizedToolName"
    } else {
      "Call $normalizedToolName"
    }
    return when (normalizedToolName) {
      "Read",
      "workspace_read_file" -> {
        val path = arguments.replayString("file_path") ?: arguments.replayString("path")
        if (path == null) {
          fallback
        } else {
          val range = readRangeSummary(
            offset = arguments.replayInt("offset"),
            limit = arguments.replayInt("limit"),
          )
          if (isChineseHostLocale()) {
            "读取 $path${if (range.isNotEmpty()) "，$range" else ""}"
          } else {
            "Read $path${if (range.isNotEmpty()) " $range" else ""}"
          }
        }
      }

      "Grep" -> {
        val pattern = arguments.replayString("pattern")
        if (pattern == null) {
          fallback
        } else {
          val path = arguments.replayString("path") ?: "."
          val glob = arguments.replayString("glob")
          if (isChineseHostLocale()) {
            buildString {
              append("在 $path 中搜索 \"$pattern\"")
              glob?.let { append("，glob: $it") }
            }
          } else {
            buildString {
              append("Search \"$pattern\" in $path")
              glob?.let { append(" (glob: $it)") }
            }
          }
        }
      }

      "Glob" -> {
        val pattern = arguments.replayString("pattern")
        if (pattern == null) {
          fallback
        } else {
          val path = arguments.replayString("path") ?: "."
          if (isChineseHostLocale()) {
            "在 $path 中匹配 $pattern"
          } else {
            "Match $pattern in $path"
          }
        }
      }

      "LS",
      "workspace_list_files" -> {
        val path = arguments.replayString("path")
          ?: arguments.replayString("file_path")
          ?: "."
        if (isChineseHostLocale()) {
          "列出 $path"
        } else {
          "List $path"
        }
      }

      "Write",
      "workspace_write_file" -> {
        val path = arguments.replayString("file_path") ?: arguments.replayString("path")
        if (path == null) {
          fallback
        } else if (isChineseHostLocale()) {
          "写入 $path"
        } else {
          "Write $path"
        }
      }

      "Edit" -> {
        val path = arguments.replayString("file_path") ?: arguments.replayString("path")
        if (path == null) {
          fallback
        } else if (isChineseHostLocale()) {
          "编辑 $path"
        } else {
          "Edit $path"
        }
      }

      "MultiEdit" -> {
        val path = arguments.replayString("file_path") ?: arguments.replayString("path")
        if (path == null) {
          fallback
        } else {
          val editCount = arguments.replayArraySize("edits") ?: 0
          if (editCount > 0) {
            if (isChineseHostLocale()) {
              "对 $path 应用 $editCount 处编辑"
            } else {
              "Apply $editCount edit(s) to $path"
            }
          } else if (isChineseHostLocale()) {
            "批量编辑 $path"
          } else {
            "MultiEdit $path"
          }
        }
      }

      "TodoWrite" -> {
        if (!arguments.containsKey("todos")) {
          if (isChineseHostLocale()) {
            "读取当前待办列表"
          } else {
            "Read current todo list"
          }
        } else {
          todoSummaryFromArguments(arguments)?.let { summary ->
            return todoWriteActionSummary(summary = summary, mutated = true)
          }
          if (isChineseHostLocale()) {
            "更新待办列表"
          } else {
            "Update the todo list"
          }
        }
      }

      "ImportFile",
      "workspace_import_file" -> {
        val sourcePath = arguments.replayString("source_path")
        val destinationPath = arguments.replayString("destination_path")
        if (sourcePath == null || destinationPath == null) {
          fallback
        } else if (isChineseHostLocale()) {
          "导入 $sourcePath 到 $destinationPath"
        } else {
          "Import $sourcePath to $destinationPath"
        }
      }

      "Bash",
      "command_exec" -> {
        val command = arguments.replayString("command")
        if (command == null) {
          fallback
        } else if (isChineseHostLocale()) {
          "运行命令 $command"
        } else {
          "Run command $command"
        }
      }

      "python_exec" -> {
        val scriptPath = arguments.replayString("script_path")
        if (scriptPath == null) {
          fallback
        } else if (isChineseHostLocale()) {
          "运行 Python 脚本 $scriptPath"
        } else {
          "Run Python script $scriptPath"
        }
      }

      "python_runtime_manifest" -> {
        if (isChineseHostLocale()) {
          "查看 Python 运行时预装库清单"
        } else {
          "Inspect Python runtime manifest"
        }
      }

      "ProcessStart" -> {
        val scriptPath = arguments.replayString("script_path")
        val command = arguments.replayString("command")
        when {
          scriptPath != null && isChineseHostLocale() -> "启动后台 Python 进程 $scriptPath"
          scriptPath != null -> "Start background Python process $scriptPath"
          command != null && isChineseHostLocale() -> "启动后台进程 $command"
          command != null -> "Start background process $command"
          else -> fallback
        }
      }

      "ProcessRead" -> {
        val processId = arguments.replayString("process_id")
        if (processId == null) {
          fallback
        } else if (isChineseHostLocale()) {
          "读取进程 $processId 的输出"
        } else {
          "Read output for process $processId"
        }
      }

      "ProcessWait" -> {
        val processId = arguments.replayString("process_id")
        if (processId == null) {
          fallback
        } else if (isChineseHostLocale()) {
          "等待进程 $processId"
        } else {
          "Wait for process $processId"
        }
      }

      "ProcessTerminate" -> {
        val processId = arguments.replayString("process_id")
        if (processId == null) {
          fallback
        } else if (isChineseHostLocale()) {
          "终止进程 $processId"
        } else {
          "Terminate process $processId"
        }
      }

      "WebFetch" -> {
        val url = arguments.replayString("url")
        if (url == null) {
          fallback
        } else if (isChineseHostLocale()) {
          "抓取网页 $url"
        } else {
          "Fetch $url"
        }
      }

      "WebSearch" -> {
        val operation = arguments.replayString("operation")?.trim()?.lowercase()
        val query = arguments.replayString("query")
        val url = arguments.replayString("url")
        val text = arguments.replayString("text")
        when (operation) {
          "open_page" -> when {
            url == null -> fallback
            isChineseHostLocale() -> "打开搜索结果页面 $url"
            else -> "Open search result page $url"
          }

          "find_in_page" -> when {
            url == null && text == null -> fallback
            isChineseHostLocale() -> buildString {
              append("在页面内搜索")
              text?.let { append(" \"$it\"") }
              url?.let {
                append(" 于 ")
                append(it)
              }
            }
            else -> buildString {
              append("Find in page")
              text?.let {
                append(" \"")
                append(it)
                append("\"")
              }
              url?.let {
                append(" in ")
                append(it)
              }
            }
          }

          else -> when {
            query == null -> fallback
            isChineseHostLocale() -> "搜索网络 \"$query\""
            else -> "Search the web for \"$query\""
          }
        }
      }

      else -> fallback
    }
  }

  private fun toolResultActionSummary(
    toolName: String,
    event: OpenCrayToolResultEvent,
    pairedToolCall: OpenCrayToolCallEvent?,
  ): String {
    event.call.arguments.takeIf { arguments -> arguments.isNotEmpty() }?.let { arguments ->
      return toolActionSummary(toolName = toolName, arguments = arguments)
    }
    pairedToolCall?.call?.arguments?.takeIf { arguments -> arguments.isNotEmpty() }?.let { arguments ->
      return toolActionSummary(toolName = toolName, arguments = arguments)
    }
    return toolActionSummaryFromResultMetadata(
      toolName = toolName,
      metadata = event.result.metadata,
    )
  }

  private fun toolActionSummaryFromResultMetadata(
    toolName: String,
    metadata: Map<String, String>,
  ): String {
    val normalizedToolName = toolName.trim()
    val fallback = if (isChineseHostLocale()) {
      "工具 $normalizedToolName 已返回结果"
    } else {
      "$normalizedToolName returned a result"
    }
    return when (normalizedToolName) {
      "Read",
      "workspace_read_file" -> {
        val path = metadataValue(metadata, "filePath")
        if (path == null) {
          fallback
        } else {
          val range = readRangeSummary(
            offset = metadataInt(metadata, "offset"),
            limit = metadataInt(metadata, "limit"),
          )
          if (isChineseHostLocale()) {
            "读取 $path${if (range.isNotEmpty()) "，$range" else ""}"
          } else {
            "Read $path${if (range.isNotEmpty()) " $range" else ""}"
          }
        }
      }

      "LS",
      "workspace_list_files" -> {
        val path = metadataValue(metadata, "path") ?: "."
        if (isChineseHostLocale()) {
          "列出 $path"
        } else {
          "List $path"
        }
      }

      "Grep" -> {
        val pattern = metadataValue(metadata, "pattern")
        if (pattern == null) {
          fallback
        } else {
          val path = metadataValue(metadata, "path") ?: "."
          if (isChineseHostLocale()) {
            "在 $path 中搜索 \"$pattern\""
          } else {
            "Search \"$pattern\" in $path"
          }
        }
      }

      "Glob" -> {
        val pattern = metadataValue(metadata, "pattern")
        if (pattern == null) {
          fallback
        } else {
          val path = metadataValue(metadata, "path") ?: "."
          if (isChineseHostLocale()) {
            "在 $path 中匹配 $pattern"
          } else {
            "Match $pattern in $path"
          }
        }
      }

      "Write",
      "workspace_write_file",
      "Edit",
      "MultiEdit" -> {
        val path = metadataValue(metadata, "filePath")
        if (path == null) {
          fallback
        } else if (isChineseHostLocale()) {
          "更新 $path"
        } else {
          "Update $path"
        }
      }

      "TodoWrite" ->
        todoSummaryFromMetadata(metadata)?.let { summary ->
          todoWriteActionSummary(
            summary = summary,
            mutated = metadataBoolean(metadata, TodoWriteMetadataKeys.MUTATED) == true,
          )
        } ?: fallback

      "WebSearch" -> {
        val operation = metadataValue(metadata, ProviderNativeWebSearchSupport.RESULT_METADATA_OPERATION)
          ?.trim()
          ?.lowercase()
        val query = metadataValue(metadata, "query")
        val url = metadataValue(metadata, "url")
        val text = metadataValue(metadata, "text")
        when (operation) {
          "open_page" -> when {
            url == null -> fallback
            isChineseHostLocale() -> "打开搜索结果页面 $url"
            else -> "Open search result page $url"
          }

          "find_in_page" -> when {
            url == null && text == null -> fallback
            isChineseHostLocale() -> buildString {
              append("在页面内搜索")
              text?.let { append(" \"$it\"") }
              url?.let {
                append(" 于 ")
                append(it)
              }
            }
            else -> buildString {
              append("Find in page")
              text?.let {
                append(" \"")
                append(it)
                append("\"")
              }
              url?.let {
                append(" in ")
                append(it)
              }
            }
          }

          else -> when {
            query == null -> fallback
            isChineseHostLocale() -> "搜索网络 \"$query\""
            else -> "Search the web for \"$query\""
          }
        }
      }

      else -> fallback
    }
  }

  private fun toolResultMetadataSummary(
    toolName: String,
    metadata: Map<String, String>,
  ): String? {
    return when (toolName.trim()) {
      "LS",
      "workspace_list_files" -> {
        val entryCount = metadataInt(metadata, "entryCount") ?: return null
        val path = metadataValue(metadata, "path")
        val truncated = resultMetadataTruncated(metadata)
        if (isChineseHostLocale()) {
          val base = if (path == null) {
            "列出了 $entryCount 项"
          } else {
            "在 $path 中列出了 $entryCount 项"
          }
          if (truncated) "$base，结果已按结果上限截断" else base
        } else if (path == null) {
          buildString {
            append("Listed $entryCount entr${if (entryCount == 1) "y" else "ies"}")
            if (truncated) {
              append(". Output truncated at the tool result limit.")
            }
          }
        } else {
          buildString {
            append("Listed $entryCount entr${if (entryCount == 1) "y" else "ies"} in $path")
            if (truncated) {
              append(". Output truncated at the tool result limit.")
            }
          }
        }
      }

      "Read",
      "workspace_read_file" -> {
        val returnedLineCount = metadataInt(metadata, "returnedLineCount")
        val totalLineCount = metadataInt(metadata, "totalLineCount")
        val truncated = resultMetadataTruncated(metadata)
        val filePath = metadataValue(metadata, "filePath")
        if (returnedLineCount == null && totalLineCount == null && !truncated && filePath == null) {
          null
        } else if (isChineseHostLocale()) {
          buildList {
            filePath?.let(::add)
            returnedLineCount?.let { add("返回 $it 行") }
            totalLineCount?.let { add("文件总计 $it 行") }
            if (truncated) {
              add("结果已按读取预算截断")
            }
          }.joinToString(separator = "，").takeIf(String::isNotBlank)
        } else {
          buildList {
            returnedLineCount?.let { count ->
              add(if (count == 1) "Returned 1 line" else "Returned $count lines")
            }
            filePath?.let { path -> add("from $path") }
            totalLineCount?.let { count ->
              add(if (count == 1) "(1-line file)" else "($count-line file)")
            }
            if (truncated) {
              add("Output truncated to the read budget.")
            }
          }.joinToString(separator = " ").takeIf(String::isNotBlank)
        }
      }

      "Grep" -> {
        val matchCount = metadataInt(metadata, "matchCount") ?: return null
        val pattern = metadataValue(metadata, "pattern")
        val path = metadataValue(metadata, "path") ?: "."
        val truncated = resultMetadataTruncated(metadata)
        if (isChineseHostLocale()) {
          val base = if (pattern == null) {
            "在 $path 中找到 $matchCount 处匹配"
          } else {
            "在 $path 中为 \"$pattern\" 找到 $matchCount 处匹配"
          }
          if (truncated) "$base，结果已按结果上限截断" else base
        } else if (pattern == null) {
          if (matchCount == 1) {
            if (truncated) {
              "Found 1 match in $path. Output truncated at the tool result limit."
            } else {
              "Found 1 match in $path"
            }
          } else {
            if (truncated) {
              "Found $matchCount matches in $path. Output truncated at the tool result limit."
            } else {
              "Found $matchCount matches in $path"
            }
          }
        } else if (matchCount == 1) {
          if (truncated) {
            "Found 1 match for \"$pattern\" in $path. Output truncated at the tool result limit."
          } else {
            "Found 1 match for \"$pattern\" in $path"
          }
        } else {
          if (truncated) {
            "Found $matchCount matches for \"$pattern\" in $path. Output truncated at the tool result limit."
          } else {
            "Found $matchCount matches for \"$pattern\" in $path"
          }
        }
      }

      "Glob" -> {
        val matchCount = metadataInt(metadata, "matchCount") ?: return null
        val pattern = metadataValue(metadata, "pattern")
        val path = metadataValue(metadata, "path") ?: "."
        val truncated = resultMetadataTruncated(metadata)
        if (isChineseHostLocale()) {
          val base = if (pattern == null) {
            "在 $path 中匹配到 $matchCount 个路径"
          } else {
            "在 $path 中为 $pattern 匹配到 $matchCount 个路径"
          }
          if (truncated) "$base，结果已按结果上限截断" else base
        } else if (pattern == null) {
          if (truncated) {
            "Matched $matchCount path(s) in $path. Output truncated at the tool result limit."
          } else {
            "Matched $matchCount path(s) in $path"
          }
        } else {
          if (truncated) {
            "Matched $matchCount path(s) for $pattern in $path. Output truncated at the tool result limit."
          } else {
            "Matched $matchCount path(s) for $pattern in $path"
          }
        }
      }

      "Edit" -> {
        val replacementCount = metadataInt(metadata, "replacementCount") ?: return null
        val filePath = metadataValue(metadata, "filePath")
        if (isChineseHostLocale()) {
          if (filePath == null) {
            "应用了 $replacementCount 处替换"
          } else {
            "在 $filePath 中应用了 $replacementCount 处替换"
          }
        } else if (filePath == null) {
          "Applied $replacementCount replacement(s)"
        } else {
          "Applied $replacementCount replacement(s) in $filePath"
        }
      }

      "MultiEdit" -> {
        val replacementCount = metadataInt(metadata, "replacementCount")
        val editCount = metadataInt(metadata, "editCount")
        val filePath = metadataValue(metadata, "filePath")
        if (replacementCount == null && editCount == null && filePath == null) {
          null
        } else if (isChineseHostLocale()) {
          buildList {
            filePath?.let(::add)
            replacementCount?.let { add("$it 处替换") }
            editCount?.let { add("$it 个编辑块") }
          }.joinToString(separator = "，").takeIf(String::isNotBlank)?.let { "应用了 $it" }
        } else {
          buildList {
            replacementCount?.let { add("$it replacement(s)") }
            editCount?.let { add("across $it edit(s)") }
            filePath?.let { add("in $it") }
          }.joinToString(separator = " ").takeIf(String::isNotBlank)?.let { "Applied $it" }
        }
      }

      "TodoWrite" -> {
        todoWriteResultSummary(metadata)
      }

      "WebSearch" -> {
        val sourceCount = metadataInt(metadata, "sourceCount")
        val operation = metadataValue(metadata, ProviderNativeWebSearchSupport.RESULT_METADATA_OPERATION)
          ?.trim()
          ?.lowercase()
        val status = metadataValue(metadata, ProviderNativeWebSearchSupport.RESULT_METADATA_STATUS)
        val query = metadataValue(metadata, "query")
        val url = metadataValue(metadata, "url")
        val text = metadataValue(metadata, "text")
        val managed = metadataValue(
          metadata,
          ProviderNativeWebSearchSupport.RESULT_METADATA_PROVIDER_MANAGED,
        ) == "true"
        if (sourceCount == null && operation == null && status == null && query == null && url == null && text == null) {
          null
        } else if (isChineseHostLocale()) {
          buildList {
            if (managed) {
              add("原生搜索")
            }
            when (operation) {
              "open_page" -> url?.let { add("打开页面 $it") }
              "find_in_page" -> {
                text?.let { add("页内搜索 \"$it\"") }
                url?.let { add(it) }
              }
              else -> query?.let { add("搜索 \"$it\"") }
            }
            sourceCount?.let { add("来源 $it 个") }
            status?.let { add("状态 $it") }
          }.joinToString(separator = "，").takeIf(String::isNotBlank)
        } else {
          buildList {
            if (managed) {
              add("Provider-managed search")
            }
            when (operation) {
              "open_page" -> url?.let { add("opened $it") }
              "find_in_page" -> {
                text?.let { add("find \"$it\"") }
                url?.let { add("in $it") }
              }
              else -> query?.let { add("search \"$it\"") }
            }
            sourceCount?.let { add(if (it == 1) "1 source" else "$it sources") }
            status?.let { add("status $it") }
          }.joinToString(separator = " ").takeIf(String::isNotBlank)
        }
      }

      else -> null
    }
  }

  private fun toolResultBodyText(result: AgentToolResult): String? {
    result.errorMessage
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { return it }
    val preview = result.content.trim()
      .takeIf(String::isNotBlank)
      ?.takeUnless { content ->
        content.equals("Tool finished.", ignoreCase = true) ||
          content.equals("Tool completed.", ignoreCase = true)
      }
    if (preview != null) {
      return preview
    }
    return when (result.status) {
      AgentToolResultStatus.DENIED -> if (isChineseHostLocale()) {
        "工具调用被拒绝。"
      } else {
        "Tool call denied."
      }

      AgentToolResultStatus.CANCELLED -> if (isChineseHostLocale()) {
        "工具调用已取消。"
      } else {
        "Tool call cancelled."
      }

      AgentToolResultStatus.TIMEOUT -> if (isChineseHostLocale()) {
        "工具调用超时。"
      } else {
        "Tool call timed out."
      }

      AgentToolResultStatus.FAILED -> if (isChineseHostLocale()) {
        "工具调用失败。"
      } else {
        "Tool call failed."
      }

      AgentToolResultStatus.SUCCESS -> null
    }
  }

  private fun readRangeSummary(offset: Int?, limit: Int?): String {
    if (offset == null && limit == null) {
      return ""
    }
    if (isChineseHostLocale()) {
      if (offset != null && limit != null) {
        val endLine = offset + limit - 1
        return "第 $offset-$endLine 行"
      }
      if (offset != null) {
        return "从第 $offset 行开始"
      }
      return "前 $limit 行"
    }
    if (offset != null && limit != null) {
      val endLine = offset + limit - 1
      return "lines $offset-$endLine"
    }
    if (offset != null) {
      return "from line $offset"
    }
    return "first $limit lines"
  }

  private fun toolReasonText(reason: String): String =
    if (isChineseHostLocale()) {
      "原因：$reason"
    } else {
      "Reason: $reason"
    }

  private fun todoWriteActionSummary(
    summary: TodoSnapshotSummary,
    mutated: Boolean,
  ): String {
    if (!mutated) {
      return if (isChineseHostLocale()) {
        "读取当前待办列表"
      } else {
        "Read current todo list"
      }
    }
    if (summary.todoCount == 0) {
      return if (isChineseHostLocale()) {
        "清空待办列表"
      } else {
        "Clear the todo list"
      }
    }
    val breakdown = todoBreakdownSummary(summary)
    val active = summary.activeTodoContent
    return if (isChineseHostLocale()) {
      buildString {
        append("更新 ${summary.todoCount} 条待办")
        breakdown?.let { append("（$it）") }
        active?.let { append("，当前进行中：$it") }
      }
    } else {
      buildString {
        append("Update ${summary.todoCount} todo(s)")
        breakdown?.let { append(" ($it)") }
        active?.let { append(", active: $it") }
      }
    }
  }

  private fun todoWriteResultSummary(metadata: Map<String, String>): String? {
    val summary = todoSummaryFromMetadata(metadata) ?: return null
    val mutated = metadataBoolean(metadata, TodoWriteMetadataKeys.MUTATED) == true
    val planChanged = metadataBoolean(metadata, TodoWriteMetadataKeys.PLAN_CHANGED)
    if (!mutated) {
      if (summary.todoCount == 0) {
        return if (isChineseHostLocale()) {
          "当前待办列表为空"
        } else {
          "Current todo list is empty"
        }
      }
      val breakdown = todoBreakdownSummary(summary)
      return if (isChineseHostLocale()) {
        buildString {
          append("当前待办列表共 ${summary.todoCount} 项")
          breakdown?.let { append("，$it") }
          summary.activeTodoContent?.let { append("，当前进行中：$it") }
        }
      } else {
        buildString {
          append("Current todo list has ${summary.todoCount} item(s)")
          breakdown?.let { append(": $it") }
          summary.activeTodoContent?.let { append(". Active: $it") }
        }
      }
    }
    if (summary.todoCount == 0) {
      return if (isChineseHostLocale()) {
        if (planChanged == false) "待办列表未变化，当前为空" else "待办列表已清空"
      } else {
        if (planChanged == false) "Plan unchanged. Todo list is empty." else "Cleared the todo list"
      }
    }
    val details = mutableListOf<String>()
    val completedDeltaCount = metadataInt(metadata, TodoWriteMetadataKeys.COMPLETED_TODO_DELTA_COUNT) ?: 0
    val addedTodoCount = metadataInt(metadata, TodoWriteMetadataKeys.ADDED_TODO_COUNT) ?: 0
    val removedTodoCount = metadataInt(metadata, TodoWriteMetadataKeys.REMOVED_TODO_COUNT) ?: 0
    val statusChangedTodoCount = metadataInt(metadata, TodoWriteMetadataKeys.STATUS_CHANGED_TODO_COUNT) ?: 0
    val extraStatusChangeCount = (statusChangedTodoCount - completedDeltaCount).coerceAtLeast(0)
    if (completedDeltaCount > 0) {
      details += if (isChineseHostLocale()) {
        "完成 $completedDeltaCount 项"
      } else {
        "completed $completedDeltaCount"
      }
    }
    if (addedTodoCount > 0) {
      details += if (isChineseHostLocale()) {
        "新增 $addedTodoCount 项"
      } else {
        "added $addedTodoCount"
      }
    }
    if (removedTodoCount > 0) {
      details += if (isChineseHostLocale()) {
        "移除 $removedTodoCount 项"
      } else {
        "removed $removedTodoCount"
      }
    }
    if (extraStatusChangeCount > 0) {
      details += if (isChineseHostLocale()) {
        "更新 $extraStatusChangeCount 项状态"
      } else {
        "updated $extraStatusChangeCount status${if (extraStatusChangeCount == 1) "" else "es"}"
      }
    }
    if (details.isEmpty()) {
      todoBreakdownSummary(summary)?.let(details::add)
    }
    return if (isChineseHostLocale()) {
      buildString {
        append(if (planChanged == false) "待办计划未变化" else "待办计划已更新")
        if (details.isNotEmpty()) {
          append("：")
          append(details.joinToString(separator = "，"))
        }
        summary.activeTodoContent?.let { append("，当前进行中：$it") }
      }
    } else {
      buildString {
        append(if (planChanged == false) "Plan unchanged" else "Plan updated")
        if (details.isNotEmpty()) {
          append(": ")
          append(details.joinToString(separator = ", "))
        }
        summary.activeTodoContent?.let { append(". Active now: $it") }
      }
    }
  }

  private fun todoBreakdownSummary(summary: TodoSnapshotSummary): String? {
    if (summary.todoCount <= 0) {
      return null
    }
    return if (isChineseHostLocale()) {
      "${summary.pendingCount} 待处理，${summary.inProgressCount} 进行中，${summary.completedCount} 已完成"
    } else {
      "${summary.pendingCount} pending, ${summary.inProgressCount} in progress, ${summary.completedCount} completed"
    }
  }

  private fun todoSummaryFromArguments(arguments: JsonObject): TodoSnapshotSummary? {
    if (!arguments.containsKey("todos")) {
      return null
    }
    return todoSummaryFromTodoObjects(arguments.replayObjectArray("todos").orEmpty())
  }

  private fun todoSummaryFromMetadata(metadata: Map<String, String>): TodoSnapshotSummary? {
    val todoCount = metadataInt(metadata, TodoWriteMetadataKeys.TODO_COUNT) ?: return null
    return TodoSnapshotSummary(
      todoCount = todoCount,
      pendingCount = metadataInt(metadata, TodoWriteMetadataKeys.PENDING_TODO_COUNT) ?: 0,
      inProgressCount = metadataInt(metadata, TodoWriteMetadataKeys.IN_PROGRESS_TODO_COUNT) ?: 0,
      completedCount = metadataInt(metadata, TodoWriteMetadataKeys.COMPLETED_TODO_COUNT) ?: 0,
      activeTodoContent = metadataValue(metadata, TodoWriteMetadataKeys.ACTIVE_TODO_CONTENT),
    )
  }

  private fun todoSummaryFromTodoObjects(todos: List<JsonObject>): TodoSnapshotSummary {
    var pendingCount = 0
    var inProgressCount = 0
    var completedCount = 0
    var activeTodoContent: String? = null
    todos.forEach { todo ->
      when (AgentTodoStatus.fromLabelOrNull(todo.replayString("status"))) {
        AgentTodoStatus.PENDING -> pendingCount += 1
        AgentTodoStatus.IN_PROGRESS -> {
          inProgressCount += 1
          if (activeTodoContent == null) {
            activeTodoContent = todo.replayString("content")
          }
        }

        AgentTodoStatus.COMPLETED -> completedCount += 1
        null -> Unit
      }
    }
    return TodoSnapshotSummary(
      todoCount = todos.size,
      pendingCount = pendingCount,
      inProgressCount = inProgressCount,
      completedCount = completedCount,
      activeTodoContent = activeTodoContent,
    )
  }

  private fun joinProjectedChatSections(vararg sections: String?): String =
    sections
      .mapNotNull { section -> section?.trim()?.takeIf(String::isNotBlank) }
      .joinToString(separator = "\n\n")

  private fun isChineseHostLocale(): Boolean =
    strings.localeTag.trim().lowercase(Locale.US).startsWith("zh")

  private fun metadataValue(metadata: Map<String, String>, key: String): String? =
    metadata[key]
      ?.trim()
      ?.takeIf(String::isNotBlank)

  private fun metadataInt(metadata: Map<String, String>, key: String): Int? =
    metadataValue(metadata, key)?.toIntOrNull()

  private fun metadataBoolean(metadata: Map<String, String>, key: String): Boolean? =
    when (metadataValue(metadata, key)?.lowercase(Locale.US)) {
      "true" -> true
      "false" -> false
      else -> null
    }

  private fun resultMetadataTruncated(metadata: Map<String, String>): Boolean =
    metadataBoolean(metadata, "resultTruncated")
      ?: metadataBoolean(metadata, "truncated")
      ?: false

  private fun toolResultDetailedContentSnapshot(result: AgentToolResult): String? =
    if (result.status == AgentToolResultStatus.SUCCESS) {
      null
    } else {
      result.content.trim().takeIf(String::isNotBlank)?.take(MAX_RUNTIME_EVENT_FAILURE_CONTENT_CHARS)
    }

  private fun JsonObject.replayObjectArray(key: String): List<JsonObject>? =
    (this[key] as? JsonArray)?.mapNotNull { entry -> entry as? JsonObject }

  private fun JsonObject.replayArraySize(key: String): Int? =
    (this[key] as? JsonArray)?.size

  private data class TodoSnapshotSummary(
    val todoCount: Int,
    val pendingCount: Int,
    val inProgressCount: Int,
    val completedCount: Int,
    val activeTodoContent: String?,
  )

  private fun runtimeProjectedMessageId(event: OpenCrayAgentRunEvent): String = when (event) {
    is OpenCrayAssistantPhaseEvent -> "runtime-assistant-${event.phase.name.lowercase(Locale.US)}-${event.runId}-${event.emittedAtEpochMs}"
    is OpenCraySupplementEvent -> "runtime-supplement-${event.entryId}"
    is OpenCrayApprovalEvent -> "runtime-approval-${event.phase.name.lowercase(Locale.US)}-${event.runId}-${event.emittedAtEpochMs}"
    is OpenCrayToolCallEvent -> "runtime-tool-call-${event.runId}-${event.turn}-${event.emittedAtEpochMs}"
    is OpenCrayToolResultEvent -> "runtime-tool-result-${event.runId}-${event.turn}-${event.emittedAtEpochMs}"
    is OpenCrayCancellationEvent -> "runtime-interrupted-${event.runId}-${event.emittedAtEpochMs}"
    else -> "runtime-event-${event.runId}-${event.emittedAtEpochMs}"
  }

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
      text = cancellationTimelineText(toolName = toolName),
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

  private fun executionIdFromMetadata(metadata: Map<String, String>): String? =
    metadata[METADATA_EXECUTION_ID]?.trim()?.takeIf(String::isNotBlank)

  private fun executionOrdinalFromMetadata(metadata: Map<String, String>): Int? =
    metadata[METADATA_EXECUTION_ORDINAL]?.trim()?.toIntOrNull()

  private fun executionKindFromMetadata(metadata: Map<String, String>): String? =
    metadata[METADATA_EXECUTION_KIND]?.trim()?.takeIf(String::isNotBlank)

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

  private fun findRunSnapshotLocked(runId: String): AgentRunSnapshot? {
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

  private fun waitForRunSnapshot(runId: String, timeoutMs: Long): AgentRunSnapshot? {
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

  private fun runSubmissionToMap(submission: AgentRunSubmission): Map<String, Any?> = mapOf(
    "sessionId" to submission.sessionId,
    "runId" to submission.runId,
    "taskId" to submission.taskId,
    "acceptedAtEpochMs" to submission.acceptedAtEpochMs,
    "diagnostics" to submission.lifecycleDiagnostics.toMap(),
  )

  private fun runSnapshotToMap(run: AgentRunSnapshot): Map<String, Any?> = mapOf(
    "sessionId" to run.sessionId,
    "runId" to run.runId,
    "taskId" to run.taskId,
    "acceptedAtEpochMs" to run.acceptedAtEpochMs,
    "updatedAtEpochMs" to run.updatedAtEpochMs,
    "lifecycleState" to run.lifecycleState?.name?.lowercase(),
    "taskState" to run.taskState?.name?.lowercase(),
    "attempt" to run.attempt,
    "executionOrdinal" to run.executionOrdinal,
    "executionId" to run.executionId,
    "executionKind" to run.executionKind,
    "pendingExecutionKind" to run.pendingExecutionKind,
    "executionStatus" to run.executionStatus?.name?.lowercase(),
    "errorCode" to run.errorCode,
    "errorMessage" to run.errorMessage,
    "responseFormat" to run.responseFormat,
    "llmDiagnostics" to llmDiagnosticsFromMetadata(run.resultMetadata),
    "liveContext" to liveContextFromMetadata(run.resultMetadata),
    "contextBudget" to contextBudgetFromMetadata(run.resultMetadata),
    "memoryTrace" to memoryTraceFromMetadata(run.resultMetadata),
    "memoryFlush" to memoryFlushFromMetadata(run.resultMetadata),
    "bootstrap" to bootstrapFromMetadata(run.resultMetadata),
    "durableCompaction" to durableCompactionFromMetadata(run.resultMetadata),
    "skillInventory" to skillInventoryFromMetadata(run.resultMetadata),
    "activeSkill" to activeSkillFromMetadata(run.resultMetadata),
    "pendingMessageId" to run.pendingMessageId,
    "managedProcessIds" to run.managedProcessIds,
    "runningManagedProcessCount" to run.runningManagedProcessCount,
    "hasLiveManagedProcesses" to run.hasLiveManagedProcesses,
    "isActive" to run.isActive,
    "isTerminal" to run.isTerminal,
    "lastEvent" to run.lastEvent?.let(::runtimeEventToMap),
    "diagnostics" to run.lifecycleDiagnostics.toMap(),
    "recoveryPlan" to recoveryPlanForRunLocked(run)?.toMap(),
  )

  private fun liveAssistantDraftToMap(
    draft: LiveAssistantDraftSnapshot,
  ): Map<String, Any?> = mapOf(
    "runId" to draft.runId,
    "taskId" to draft.taskId,
    "pendingMessageId" to draft.pendingMessageId,
    "text" to draft.text,
    "updatedAtEpochMs" to draft.updatedAtEpochMs,
  )

  private fun recoveryPlanForRunLocked(run: AgentRunSnapshot): RunRecoveryPlan? {
    val approvalState = approvalStateForTaskLocked(run.sessionId, run.taskId)
    return loadStoredRunRecoveryPlan(
      run = run,
      checkpointStore = promptCheckpointStoreForSession(run.sessionId),
      journalStore = runEventJournalStoreForSession(run.sessionId),
      planner = recoveryPlanner,
      approvalStateOverride = approvalState,
    )
  }

  private fun llmDiagnosticsFromMetadata(metadata: Map<String, String>): Map<String, Any?>? {
    val nativeToolCallRequested = metadata[LiteLlmMetadataKeys.NATIVE_TOOL_CALL_REQUESTED]
      ?.toBooleanStrictOrNull()
    val responseShape = metadata[LiteLlmMetadataKeys.PROVIDER_RESPONSE_SHAPE]
      ?.takeIf(String::isNotBlank)
    val nativeToolCallObserved = metadata[LiteLlmMetadataKeys.NATIVE_TOOL_CALL_OBSERVED]
      ?.toBooleanStrictOrNull()
    val parsedToolCallObserved = metadata[LiteLlmMetadataKeys.PARSED_TOOL_CALL_OBSERVED]
      ?.toBooleanStrictOrNull()
    val fallbackParserAttempted = metadata[LiteLlmMetadataKeys.FALLBACK_PARSER_ATTEMPTED]
      ?.toBooleanStrictOrNull()
    val fallbackParserSucceeded = metadata[LiteLlmMetadataKeys.FALLBACK_PARSER_SUCCEEDED]
      ?.toBooleanStrictOrNull()
    val responsesContinuationRecoveryCount = metadata["responsesContinuationRecoveryCount"]
      ?.toIntOrNull()
    val responsesContinuationRecoveryLastReason = metadata["responsesContinuationRecoveryLastReason"]
      ?.takeIf(String::isNotBlank)
    val localContinuationUsedCount = metadata["localContinuationUsedCount"]?.toIntOrNull()
    val localContinuationFallbackCount = metadata["localContinuationFallbackCount"]?.toIntOrNull()
    val localContinuationLastMode = metadata["localContinuationLastMode"]
      ?.takeIf(String::isNotBlank)
    val localContinuationLastReason = metadata["localContinuationLastReason"]
      ?.takeIf(String::isNotBlank)
    val toolCallEventEmitted = metadata[LiteLlmMetadataKeys.TOOL_CALL_EVENT_EMITTED]
      ?.toBooleanStrictOrNull()
    val toolResultEventEmitted = metadata[LiteLlmMetadataKeys.TOOL_RESULT_EVENT_EMITTED]
      ?.toBooleanStrictOrNull()
    val contextCacheBreakReason = metadata[LiteLlmMetadataKeys.CONTEXT_CACHE_BREAK_REASON]
      ?.takeIf(String::isNotBlank)
    val lastSuccessfulToolName = metadata[LiteLlmMetadataKeys.LAST_SUCCESSFUL_TOOL_NAME]
      ?.takeIf(String::isNotBlank)
    if (
      nativeToolCallRequested == null &&
      responseShape == null &&
      nativeToolCallObserved == null &&
      parsedToolCallObserved == null &&
      fallbackParserAttempted == null &&
      fallbackParserSucceeded == null &&
      responsesContinuationRecoveryCount == null &&
      responsesContinuationRecoveryLastReason == null &&
      localContinuationUsedCount == null &&
      localContinuationFallbackCount == null &&
      localContinuationLastMode == null &&
      localContinuationLastReason == null &&
      toolCallEventEmitted == null &&
      toolResultEventEmitted == null &&
      contextCacheBreakReason == null &&
      lastSuccessfulToolName == null
    ) {
      return null
    }
    return buildMap {
      put("nativeToolCallRequested", nativeToolCallRequested)
      put("providerResponseShape", responseShape)
      put("nativeToolCallObserved", nativeToolCallObserved)
      put("parsedToolCallObserved", parsedToolCallObserved)
      put("fallbackParserAttempted", fallbackParserAttempted)
      put("fallbackParserSucceeded", fallbackParserSucceeded)
      put("responsesContinuationRecoveryCount", responsesContinuationRecoveryCount)
      put("responsesContinuationRecoveryLastReason", responsesContinuationRecoveryLastReason)
      put("localContinuationUsedCount", localContinuationUsedCount)
      put("localContinuationFallbackCount", localContinuationFallbackCount)
      put("localContinuationLastMode", localContinuationLastMode)
      put("localContinuationLastReason", localContinuationLastReason)
      put("toolCallEventEmitted", toolCallEventEmitted)
      put("toolResultEventEmitted", toolResultEventEmitted)
      put("contextCacheBreakReason", contextCacheBreakReason)
      put("lastSuccessfulToolName", lastSuccessfulToolName)
    }
  }

  private fun memoryTraceFromMetadata(metadata: Map<String, String>): Map<String, Any?>? {
    val matchedCount = metadata["contextMatchedMemoryCount"]?.toIntOrNull()
    val injectedCount = metadata["contextInjectedMemoryCount"]?.toIntOrNull()
    val omittedCount = metadata["contextOmittedMemoryCount"]?.toIntOrNull()
    val queryTerms = metadata["contextMemoryQueryTerms"]
      .orEmpty()
      .split(',')
      .map(String::trim)
      .filter(String::isNotBlank)
    val selected = parseSelectedMemoryTrace(metadata["contextMemorySelectedSummary"].orEmpty())
    val omitted = parseOmittedMemoryTrace(metadata["contextMemoryOmittedSummary"].orEmpty())
    val filteredCounts = parseFilteredMemoryCounts(metadata["contextMemoryFilteredCounts"].orEmpty())
    if (
      matchedCount == null &&
      injectedCount == null &&
      omittedCount == null &&
      queryTerms.isEmpty() &&
      selected.isEmpty() &&
      omitted.isEmpty() &&
      filteredCounts.isEmpty()
    ) {
      return null
    }
    return buildMap {
      matchedCount?.let { put("matchedRecordCount", it) }
      injectedCount?.let { put("injectedRecordCount", it) }
      omittedCount?.let { put("omittedRecordCount", it) }
      if (queryTerms.isNotEmpty()) {
        put("queryTerms", queryTerms)
      }
      if (selected.isNotEmpty()) {
        put("selected", selected)
      }
      if (omitted.isNotEmpty()) {
        put("omitted", omitted)
      }
      if (filteredCounts.isNotEmpty()) {
        put("filteredCounts", filteredCounts)
      }
    }
  }

  private fun liveContextFromMetadata(metadata: Map<String, String>): Map<String, Any?>? {
    val mode = metadata["contextLiveMode"]?.takeIf(String::isNotBlank)
    val soulEnabled = metadata["contextLiveSoulEnabled"]?.toBooleanStrictOrNull()
    val memoryRecallEnabled = metadata["contextLiveMemoryRecallEnabled"]?.toBooleanStrictOrNull()
    if (mode == null && soulEnabled == null && memoryRecallEnabled == null) {
      return null
    }
    return buildMap {
      mode?.let { put("mode", it) }
      soulEnabled?.let { put("soulEnabled", it) }
      memoryRecallEnabled?.let { put("memoryRecallEnabled", it) }
    }
  }

  private fun contextBudgetFromMetadata(metadata: Map<String, String>): Map<String, Any?>? {
    val applied = metadata["contextBudgetApplied"]?.toBooleanStrictOrNull()
    val pressureMode = metadata["contextBudgetPressureMode"]?.takeIf(String::isNotBlank)
    val selectedPreset = metadata["contextBudgetSelectedPreset"]?.takeIf(String::isNotBlank)
    val effectivePreset = metadata["contextBudgetEffectivePreset"]?.takeIf(String::isNotBlank)
    val presetSource = metadata["contextBudgetPresetSource"]?.takeIf(String::isNotBlank)
    val presetDiverged = metadata["contextBudgetPresetDiverged"]?.toBooleanStrictOrNull()
    val sourcePreset = metadata["contextBudgetSourcePreset"]?.takeIf(String::isNotBlank)
    val sourceTranscriptMaxMessages = metadata["contextBudgetSourceTranscriptMaxMessages"]?.toIntOrNull()
    val sourceInjectedMemoryMaxRecords = metadata["contextBudgetSourceInjectedMemoryMaxRecords"]?.toIntOrNull()
    val sourceMemoryRecallMaxRecords = metadata["contextBudgetSourceMemoryRecallMaxRecords"]?.toIntOrNull()
    val sourceBootstrapMaxChars = metadata["contextBudgetSourceBootstrapMaxChars"]?.toIntOrNull()
    val sourceSkillInventoryMaxSkills = metadata["contextBudgetSourceSkillInventoryMaxSkills"]?.toIntOrNull()
    val sourceActiveSkillMaxChars = metadata["contextBudgetSourceActiveSkillMaxChars"]?.toIntOrNull()
    val sourceRecentObservationMaxEntries = metadata["contextBudgetSourceRecentObservationMaxEntries"]?.toIntOrNull()
    val sourceMemoryFlushMaxToolObservations = metadata["contextBudgetSourceMemoryFlushMaxToolObservations"]?.toIntOrNull()
    val contextWindowTokens = metadata["contextBudgetContextWindowTokens"]?.toIntOrNull()
    val reservedOutputTokens = metadata["contextBudgetReservedOutputTokens"]?.toIntOrNull()
    val safetyMarginTokens = metadata["contextBudgetSafetyMarginTokens"]?.toIntOrNull()
    val hardInputTokens = metadata["contextBudgetHardInputTokens"]?.toIntOrNull()
    val targetInputTokens = metadata["contextBudgetTargetInputTokens"]?.toIntOrNull()
    val emergencyInputTokens = metadata["contextBudgetEmergencyInputTokens"]?.toIntOrNull()
    val unresolvedOverflow = metadata["contextBudgetUnresolvedOverflow"]?.toBooleanStrictOrNull()
    val fullLayerCount = metadata["contextBudgetFullLayerCount"]?.toIntOrNull()
    val compactLayerCount = metadata["contextBudgetCompactLayerCount"]?.toIntOrNull()
    val minimalLayerCount = metadata["contextBudgetMinimalLayerCount"]?.toIntOrNull()
    val omittedLayerCount = metadata["contextBudgetOmittedLayerCount"]?.toIntOrNull()
    val reducedLayerNames = metadata["contextBudgetReducedLayerNames"]
      .orEmpty()
      .split(',')
      .map(String::trim)
      .filter(String::isNotBlank)
    val omittedLayerNames = metadata["contextBudgetOmittedLayerNames"]
      .orEmpty()
      .split(',')
      .map(String::trim)
      .filter(String::isNotBlank)
    val layers = parseContextBudgetLayers(metadata["contextBudgetLayerDetails"].orEmpty())
    val layerSummary = metadata["contextBudgetLayerSummary"]?.takeIf(String::isNotBlank)
    if (
      applied == null &&
      pressureMode == null &&
      selectedPreset == null &&
      effectivePreset == null &&
      presetSource == null &&
      presetDiverged == null &&
      sourcePreset == null &&
      sourceTranscriptMaxMessages == null &&
      sourceInjectedMemoryMaxRecords == null &&
      sourceMemoryRecallMaxRecords == null &&
      sourceBootstrapMaxChars == null &&
      sourceSkillInventoryMaxSkills == null &&
      sourceActiveSkillMaxChars == null &&
      sourceRecentObservationMaxEntries == null &&
      sourceMemoryFlushMaxToolObservations == null &&
      contextWindowTokens == null &&
      reservedOutputTokens == null &&
      safetyMarginTokens == null &&
      hardInputTokens == null &&
      targetInputTokens == null &&
      emergencyInputTokens == null &&
      unresolvedOverflow == null &&
      fullLayerCount == null &&
      compactLayerCount == null &&
      minimalLayerCount == null &&
      omittedLayerCount == null &&
      reducedLayerNames.isEmpty() &&
      omittedLayerNames.isEmpty() &&
      layers.isEmpty() &&
      layerSummary == null
    ) {
      return null
    }
    return buildMap {
      applied?.let { put("applied", it) }
      pressureMode?.let { put("pressureMode", it) }
      selectedPreset?.let { put("selectedPreset", it) }
      effectivePreset?.let { put("effectivePreset", it) }
      presetSource?.let { put("presetSource", it) }
      presetDiverged?.let { put("presetDiverged", it) }
      sourcePreset?.let { put("sourcePreset", it) }
      sourceTranscriptMaxMessages?.let { put("sourceTranscriptMaxMessages", it) }
      sourceInjectedMemoryMaxRecords?.let { put("sourceInjectedMemoryMaxRecords", it) }
      sourceMemoryRecallMaxRecords?.let { put("sourceMemoryRecallMaxRecords", it) }
      sourceBootstrapMaxChars?.let { put("sourceBootstrapMaxChars", it) }
      sourceSkillInventoryMaxSkills?.let { put("sourceSkillInventoryMaxSkills", it) }
      sourceActiveSkillMaxChars?.let { put("sourceActiveSkillMaxChars", it) }
      sourceRecentObservationMaxEntries?.let { put("sourceRecentObservationMaxEntries", it) }
      sourceMemoryFlushMaxToolObservations?.let { put("sourceMemoryFlushMaxToolObservations", it) }
      contextWindowTokens?.let { put("contextWindowTokens", it) }
      reservedOutputTokens?.let { put("reservedOutputTokens", it) }
      safetyMarginTokens?.let { put("safetyMarginTokens", it) }
      hardInputTokens?.let { put("hardInputTokens", it) }
      targetInputTokens?.let { put("targetInputTokens", it) }
      emergencyInputTokens?.let { put("emergencyInputTokens", it) }
      unresolvedOverflow?.let { put("unresolvedOverflow", it) }
      fullLayerCount?.let { put("fullLayerCount", it) }
      compactLayerCount?.let { put("compactLayerCount", it) }
      minimalLayerCount?.let { put("minimalLayerCount", it) }
      omittedLayerCount?.let { put("omittedLayerCount", it) }
      if (reducedLayerNames.isNotEmpty()) {
        put("reducedLayerNames", reducedLayerNames)
      }
      if (omittedLayerNames.isNotEmpty()) {
        put("omittedLayerNames", omittedLayerNames)
      }
      if (layers.isNotEmpty()) {
        put("layers", layers)
      }
      layerSummary?.let { put("layerSummary", it) }
    }
  }

  private fun parseContextBudgetLayers(summary: String): List<Map<String, Any?>> {
    val normalized = summary.trim()
    if (normalized.isEmpty()) {
      return emptyList()
    }
    val payload = runCatching { replayJson.parseToJsonElement(normalized) }.getOrNull() as? JsonArray
      ?: return emptyList()
    return payload.mapNotNull { element ->
      val layer = element as? JsonObject ?: return@mapNotNull null
      val id = layer.stringValue("id") ?: return@mapNotNull null
      val name = layer.stringValue("name") ?: id
      buildMap {
        put("id", id)
        put("name", name)
        layer.stringValue("priorityClass")?.let { put("priorityClass", it) }
        layer.intValue("retentionPriority")?.let { put("retentionPriority", it) }
        layer.intValue("estimatedTokensBefore")?.let { put("estimatedTokensBefore", it) }
        layer.intValue("estimatedTokensAfter")?.let { put("estimatedTokensAfter", it) }
        layer.stringValue("finalState")?.let { put("finalState", it) }
        layer.booleanValue("omitted")?.let { put("omitted", it) }
        layer.booleanValue("reduced")?.let { put("reduced", it) }
        val appliedOperators = layer.stringArray("appliedOperators")
        if (appliedOperators.isNotEmpty()) {
          put("appliedOperators", appliedOperators)
        }
      }
    }
  }

  private fun JsonObject.stringValue(key: String): String? = (this[key] as? JsonPrimitive)
    ?.content
    ?.trim()
    ?.takeIf(String::isNotBlank)

  private fun JsonObject.intValue(key: String): Int? = (this[key] as? JsonPrimitive)
    ?.content
    ?.trim()
    ?.toIntOrNull()

  private fun JsonObject.booleanValue(key: String): Boolean? = (this[key] as? JsonPrimitive)
    ?.content
    ?.trim()
    ?.toBooleanStrictOrNull()

  private fun JsonObject.stringArray(key: String): List<String> = (this[key] as? JsonArray)
    ?.mapNotNull { element ->
      (element as? JsonPrimitive)
        ?.content
        ?.trim()
        ?.takeIf(String::isNotBlank)
    }
    ?: emptyList()

  private fun memoryFlushFromMetadata(metadata: Map<String, String>): Map<String, Any?>? {
    val outcome = metadata["contextMemoryFlushOutcome"]?.takeIf(String::isNotBlank)
    val omittedMessageCount = metadata["contextMemoryFlushOmittedMessageCount"]?.toIntOrNull()
    val omittedCharCount = metadata["contextMemoryFlushOmittedCharCount"]?.toIntOrNull()
    val signature = metadata["contextMemoryFlushSignature"]?.takeIf(String::isNotBlank)
    val candidateCount = metadata["contextMemoryFlushCandidateCount"]?.toIntOrNull()
    val writtenRecordCount = metadata["contextMemoryFlushWrittenRecordCount"]?.toIntOrNull()
    val writtenKinds = metadata["contextMemoryFlushWrittenKinds"]
      .orEmpty()
      .split(',')
      .map(String::trim)
      .filter(String::isNotBlank)
    val writtenRecordIds = metadata["contextMemoryFlushWrittenRecordIds"]
      .orEmpty()
      .split(',')
      .map(String::trim)
      .filter(String::isNotBlank)
    if (
      outcome == null &&
      omittedMessageCount == null &&
      omittedCharCount == null &&
      signature == null &&
      candidateCount == null &&
      writtenRecordCount == null &&
      writtenKinds.isEmpty() &&
      writtenRecordIds.isEmpty()
    ) {
      return null
    }
    return buildMap {
      outcome?.let { put("outcome", it) }
      omittedMessageCount?.let { put("omittedMessageCount", it) }
      omittedCharCount?.let { put("omittedCharCount", it) }
      signature?.let { put("signature", it) }
      candidateCount?.let { put("candidateCount", it) }
      writtenRecordCount?.let { put("writtenRecordCount", it) }
      if (writtenKinds.isNotEmpty()) {
        put("writtenKinds", writtenKinds)
      }
      if (writtenRecordIds.isNotEmpty()) {
        put("writtenRecordIds", writtenRecordIds)
      }
    }
  }

  private fun bootstrapFromMetadata(metadata: Map<String, String>): Map<String, Any?>? {
    val mode = metadata["contextBootstrapMode"]?.takeIf(String::isNotBlank)
    val visibleFileCount = metadata["contextBootstrapVisibleFileCount"]?.toIntOrNull()
    val injectedFileCount = metadata["contextBootstrapInjectedFileCount"]?.toIntOrNull()
    val omittedFileCount = metadata["contextBootstrapOmittedFileCount"]?.toIntOrNull()
    val truncatedFileCount = metadata["contextBootstrapTruncatedFileCount"]?.toIntOrNull()
    val files = parseBootstrapFileTrace(metadata["contextBootstrapFileSummary"].orEmpty())
    if (
      mode == null &&
      visibleFileCount == null &&
      injectedFileCount == null &&
      omittedFileCount == null &&
      truncatedFileCount == null &&
      files.isEmpty()
    ) {
      return null
    }
    return buildMap {
      mode?.let { put("mode", it) }
      visibleFileCount?.let { put("visibleFileCount", it) }
      injectedFileCount?.let { put("injectedFileCount", it) }
      omittedFileCount?.let { put("omittedFileCount", it) }
      truncatedFileCount?.let { put("truncatedFileCount", it) }
      if (files.isNotEmpty()) {
        put("files", files)
      }
    }
  }

  private fun skillInventoryFromMetadata(metadata: Map<String, String>): Map<String, Any?>? {
    val visibleCount = metadata["contextVisibleSkillCount"]?.toIntOrNull()
    val injectedCount = metadata["contextInjectedSkillCount"]?.toIntOrNull()
    val omittedCount = metadata["contextOmittedSkillCount"]?.toIntOrNull()
    val implicitCount = metadata["contextImplicitSkillCount"]?.toIntOrNull()
    val invalidCount = metadata["contextInvalidSkillCount"]?.toIntOrNull()
    val omittedTraceCount = metadata["contextVisibleSkillTraceOmittedCount"]?.toIntOrNull()
    val skills = parseVisibleSkillTrace(metadata["contextVisibleSkillSummary"].orEmpty())
    if (
      visibleCount == null &&
      injectedCount == null &&
      omittedCount == null &&
      implicitCount == null &&
      invalidCount == null &&
      omittedTraceCount == null &&
      skills.isEmpty()
    ) {
      return null
    }
    return buildMap {
      visibleCount?.let { put("visibleSkillCount", it) }
      injectedCount?.let { put("injectedSkillCount", it) }
      omittedCount?.let { put("omittedSkillCount", it) }
      implicitCount?.let { put("implicitSkillCount", it) }
      invalidCount?.let { put("invalidSkillCount", it) }
      omittedTraceCount?.let { put("omittedTraceSkillCount", it) }
      if (skills.isNotEmpty()) {
        put("skills", skills)
      }
    }
  }

  private fun durableCompactionFromMetadata(metadata: Map<String, String>): Map<String, Any?>? {
    val compactedThisRun = metadata["contextDurableCompactionCompactedThisRun"]?.toBooleanStrictOrNull()
    val sourceTranscriptMessageCount =
      metadata["contextDurableCompactionSourceTranscriptMessageCount"]?.toIntOrNull()
    val retainedTranscriptMessageCount =
      metadata["contextDurableCompactionRetainedTranscriptMessageCount"]?.toIntOrNull()
    val latestCompactedMessageCount =
      metadata["contextDurableCompactionLatestMessageCount"]?.toIntOrNull()
    val includedSummaryCount =
      metadata["contextDurableCompactionIncludedSummaryCount"]?.toIntOrNull()
    val omittedSummaryCount =
      metadata["contextDurableCompactionOmittedSummaryCount"]?.toIntOrNull()
    val totalCompactedMessageCount =
      metadata["contextDurableCompactionTotalCompactedMessageCount"]?.toIntOrNull()
    val latestCompactedAtEpochMs =
      metadata["contextDurableCompactionLatestAtEpochMs"]?.toLongOrNull()
    val totalSummaryCount = if (includedSummaryCount != null || omittedSummaryCount != null) {
      (includedSummaryCount ?: 0) + (omittedSummaryCount ?: 0)
    } else {
      null
    }
    if (
      compactedThisRun == null &&
      sourceTranscriptMessageCount == null &&
      retainedTranscriptMessageCount == null &&
      latestCompactedMessageCount == null &&
      includedSummaryCount == null &&
      omittedSummaryCount == null &&
      totalCompactedMessageCount == null &&
      latestCompactedAtEpochMs == null
    ) {
      return null
    }
    return buildMap {
      compactedThisRun?.let { put("compactedThisRun", it) }
      sourceTranscriptMessageCount?.let { put("sourceTranscriptMessageCount", it) }
      retainedTranscriptMessageCount?.let { put("retainedTranscriptMessageCount", it) }
      latestCompactedMessageCount?.let { put("latestCompactedMessageCount", it) }
      includedSummaryCount?.let { put("includedSummaryCount", it) }
      omittedSummaryCount?.let { put("omittedSummaryCount", it) }
      totalCompactedMessageCount?.let { put("totalCompactedMessageCount", it) }
      totalSummaryCount?.let { put("totalSummaryCount", it) }
      latestCompactedAtEpochMs?.let { put("latestCompactedAtEpochMs", it) }
    }
  }

  private fun newMemoryDebugActionAuditEntry(
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

  private fun activeSkillFromMetadata(metadata: Map<String, String>): Map<String, Any?>? {
    val name = metadata["contextActiveSkillName"]?.takeIf(String::isNotBlank)
    val relativePath = metadata["contextActiveSkillRelativePath"]?.takeIf(String::isNotBlank)
    val invocationControl = metadata["contextActiveSkillInvocationControl"]?.takeIf(String::isNotBlank)
    val executionContext = metadata["contextActiveSkillExecutionContext"]?.takeIf(String::isNotBlank)
    val activationSource = metadata["contextActiveSkillActivationSource"]?.takeIf(String::isNotBlank)
    val toolRestrictionEnabled = metadata["contextActiveSkillToolRestrictionEnabled"]?.toBooleanStrictOrNull()
    val truncated = metadata["contextActiveSkillTruncated"]?.toBooleanStrictOrNull()
    val allowedToolKeys = metadata["contextActiveSkillAllowedTools"]
      .orEmpty()
      .split(',')
      .map(String::trim)
      .filter(String::isNotBlank)
    if (
      name == null &&
      relativePath == null &&
      invocationControl == null &&
      executionContext == null &&
      activationSource == null &&
      toolRestrictionEnabled == null &&
      truncated == null &&
      allowedToolKeys.isEmpty()
    ) {
      return null
    }
    return buildMap {
      name?.let { put("name", it) }
      relativePath?.let { put("relativePath", it) }
      invocationControl?.let { put("invocationControl", it) }
      executionContext?.let { put("executionContext", it) }
      activationSource?.let { put("activationSource", it) }
      toolRestrictionEnabled?.let { put("toolRestrictionEnabled", it) }
      truncated?.let { put("truncated", it) }
      if (allowedToolKeys.isNotEmpty()) {
        put("allowedToolKeys", allowedToolKeys)
      }
    }
  }

  private fun parseBootstrapFileTrace(raw: String): List<Map<String, Any?>> = raw
    .split(';')
    .map(String::trim)
    .filter(String::isNotBlank)
    .mapNotNull { token ->
      val match = BOOTSTRAP_FILE_TRACE_REGEX.matchEntire(token) ?: return@mapNotNull null
      mapOf(
        "name" to match.groupValues[1],
        "relativePath" to match.groupValues[2],
        "sourceCharCount" to match.groupValues[3].toIntOrNull(),
        "injectedCharCount" to match.groupValues[4].toIntOrNull(),
        "truncated" to match.groupValues[5].toBooleanStrictOrNull(),
      )
    }

  private fun parseSelectedMemoryTrace(raw: String): List<Map<String, Any?>> = raw
    .split(';')
    .map(String::trim)
    .filter(String::isNotBlank)
    .mapNotNull { token ->
      val match = MEMORY_SELECTED_TRACE_REGEX.matchEntire(token) ?: return@mapNotNull null
      val matchedTerms = match.groupValues[3]
        .split('|')
        .map(String::trim)
        .filter(String::isNotBlank)
      mapOf(
        "id" to match.groupValues[1],
        "score" to match.groupValues[2].toIntOrNull(),
        "matchedTerms" to matchedTerms,
      )
    }

  private fun parseOmittedMemoryTrace(raw: String): List<Map<String, Any?>> = raw
    .split(';')
    .map(String::trim)
    .filter(String::isNotBlank)
    .mapNotNull { token ->
      val id = token.substringBefore(':').trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
      val reason = token.substringAfter(':', missingDelimiterValue = "").trim().takeIf(String::isNotBlank)
        ?: return@mapNotNull null
      mapOf(
        "id" to id,
        "reason" to reason,
      )
    }

  private fun parseVisibleSkillTrace(raw: String): List<Map<String, Any?>> = raw
    .split(';')
    .map(String::trim)
    .filter(String::isNotBlank)
    .mapNotNull { token ->
      val match = VISIBLE_SKILL_TRACE_REGEX.matchEntire(token) ?: return@mapNotNull null
      mapOf(
        "name" to match.groupValues[1],
        "relativePath" to match.groupValues[2],
        "invocationControl" to match.groupValues[3],
        "userInvocable" to match.groupValues[4].toBooleanStrictOrNull(),
        "executionContext" to match.groupValues[5],
      )
    }

  private fun parseFilteredMemoryCounts(raw: String): Map<String, Int> = raw
    .split(',')
    .map(String::trim)
    .filter(String::isNotBlank)
    .mapNotNull { token ->
      val reason = token.substringBefore(':').trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
      val count = token.substringAfter(':', missingDelimiterValue = "").trim().toIntOrNull()
        ?: return@mapNotNull null
      reason to count
    }
    .toMap(linkedMapOf())

  private fun JsonObject.replayString(key: String): String? =
    (this[key] as? JsonPrimitive)
      ?.content
      ?.trim()
      ?.takeIf(String::isNotBlank)

  private fun JsonObject.replayInt(key: String): Int? =
    (this[key] as? JsonPrimitive)
      ?.content
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.toIntOrNull()

  private fun JsonObject.replayBoolean(key: String): Boolean? =
    (this[key] as? JsonPrimitive)
      ?.content
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.toBooleanStrictOrNull()

  private fun JsonObject.replayObject(key: String): JsonObject? =
    this[key] as? JsonObject

  private fun JsonObject.replayStringMap(key: String): Map<String, String> =
    replayObject(key)
      ?.mapNotNull { (entryKey, entryValue) ->
        (entryValue as? JsonPrimitive)
          ?.content
          ?.trim()
          ?.takeIf { value -> value.isNotBlank() }
          ?.let { value -> entryKey to value }
      }
      ?.toMap(linkedMapOf())
      .orEmpty()

  private fun runIdFor(task: AgentTask): String =
    task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID]
      ?.takeIf(String::isNotBlank)
      ?: task.id

  private fun runtimeEventToMap(event: OpenCrayAgentRunEvent): Map<String, Any?> = when (event) {
    is OpenCrayLifecycleEvent -> mapOf(
      "kind" to "lifecycle",
      "runId" to event.runId,
      "taskId" to event.taskId,
      "executionId" to event.executionId,
      "executionOrdinal" to event.executionOrdinal,
      "executionKind" to event.executionKind,
      "turn" to event.turn,
      "emittedAtEpochMs" to event.emittedAtEpochMs,
      "phase" to event.phase.name.lowercase(),
      "status" to event.status?.name?.lowercase(),
      "errorCode" to event.errorCode,
      "errorMessage" to event.errorMessage,
    )
    is OpenCrayAssistantPhaseEvent -> buildMap<String, Any?> {
      put("kind", "assistant_phase")
      put("runId", event.runId)
      put("taskId", event.taskId)
      put("executionId", event.executionId)
      put("executionOrdinal", event.executionOrdinal)
      put("executionKind", event.executionKind)
      put("turn", event.turn)
      put("emittedAtEpochMs", event.emittedAtEpochMs)
      put("phase", event.phase.name.lowercase())
      put("responseFormat", event.responseFormat)
      put("isFinal", event.isFinal)
      put("stage", event.stage)
      put("text", event.text)
      if (hasPromptResumeCheckpointMetadata(event.metadata)) {
        put("hasResumeCheckpointMetadata", true)
      }
    }
    is OpenCraySupplementEvent -> buildMap<String, Any?> {
      put("kind", "supplement")
      put("runId", event.runId)
      put("taskId", event.taskId)
      put("executionId", event.executionId)
      put("executionOrdinal", event.executionOrdinal)
      put("executionKind", event.executionKind)
      put("turn", event.turn)
      put("emittedAtEpochMs", event.emittedAtEpochMs)
      put("entryId", event.entryId)
      put("text", event.text)
      put("checkpoint", event.checkpoint)
      val metadataSnapshot = supplementMetadataSnapshot(event.metadata)
      if (metadataSnapshot.isNotEmpty()) {
        put("metadata", metadataSnapshot)
      }
      if (hasPromptResumeCheckpointMetadata(event.metadata)) {
        put("hasResumeCheckpointMetadata", true)
      }
    }
    is OpenCrayApprovalEvent -> mapOf(
      "kind" to if (event.phase == OpenCrayApprovalPhase.REQUIRED) "approval_wait" else "approval_result",
      "runId" to event.runId,
      "taskId" to event.taskId,
      "executionId" to event.executionId,
      "executionOrdinal" to event.executionOrdinal,
      "executionKind" to event.executionKind,
      "turn" to event.turn,
      "emittedAtEpochMs" to event.emittedAtEpochMs,
      "toolName" to event.toolName,
      "text" to event.text,
      "stage" to event.phase.name.lowercase(),
      "status" to event.phase.name.lowercase(),
      "isHighRisk" to event.isHighRisk,
    )
    is OpenCraySubAgentEvent -> mapOf(
      "kind" to "subagent",
      "runId" to event.runId,
      "taskId" to event.taskId,
      "executionId" to event.executionId,
      "executionOrdinal" to event.executionOrdinal,
      "executionKind" to event.executionKind,
      "turn" to event.turn,
      "emittedAtEpochMs" to event.emittedAtEpochMs,
      "phase" to event.phase.name.lowercase(),
      "status" to event.executionState?.wireValue,
      "childRunId" to event.childRunId,
      "childTaskId" to event.childTaskId,
      "label" to event.label,
      "subagentType" to event.subagentType,
      "contextMode" to event.contextMode,
      "depth" to event.depth,
      "executionState" to event.executionState?.wireValue,
      "continuationKind" to event.continuationKind?.wireValue,
      "resumable" to event.resumable,
      "requiresUserAction" to event.requiresUserAction,
      "isHighRisk" to event.isHighRisk,
      "text" to event.summary,
    )
    is OpenCrayToolCallEvent -> mapOf(
      "kind" to "tool_call",
      "runId" to event.runId,
      "taskId" to event.taskId,
      "executionId" to event.executionId,
      "executionOrdinal" to event.executionOrdinal,
      "executionKind" to event.executionKind,
      "turn" to event.turn,
      "emittedAtEpochMs" to event.emittedAtEpochMs,
      "toolName" to event.call.toolName,
      "toolReason" to event.call.reason,
      "argumentsJson" to event.call.arguments.toString(),
    )
    is OpenCrayToolResultEvent -> mapOf(
      "kind" to "tool_result",
      "runId" to event.runId,
      "taskId" to event.taskId,
      "executionId" to event.executionId,
      "executionOrdinal" to event.executionOrdinal,
      "executionKind" to event.executionKind,
      "turn" to event.turn,
      "emittedAtEpochMs" to event.emittedAtEpochMs,
      "toolName" to event.call.toolName,
      "toolStatus" to event.result.status.name.lowercase(),
      "errorCode" to event.result.errorCode,
      "errorMessage" to event.result.errorMessage,
      "content" to toolResultDetailedContentSnapshot(event.result),
      "contentPreview" to event.result.content.take(MAX_RUNTIME_EVENT_PREVIEW_CHARS),
      "resultMetadata" to toolResultMetadataSnapshot(event.result.metadata),
    )
    is OpenCrayMemoryRetrievalEvent -> buildMap<String, Any?> {
      put("kind", "memory_retrieval")
      put("runId", event.runId)
      put("taskId", event.taskId)
      put("executionId", event.executionId)
      put("executionOrdinal", event.executionOrdinal)
      put("executionKind", event.executionKind)
      put("turn", event.turn)
      put("emittedAtEpochMs", event.emittedAtEpochMs)
      put("toolName", event.toolName)
      put("operation", event.operation)
      event.query?.let { query -> put("query", query) }
      if (event.queryTerms.isNotEmpty()) {
        put("queryTerms", event.queryTerms)
      }
      event.resultCount?.let { resultCount -> put("resultCount", resultCount) }
      event.corpusFileCount?.let { corpusFileCount -> put("corpusFileCount", corpusFileCount) }
      if (event.recordIds.isNotEmpty()) {
        put("recordIds", event.recordIds)
      }
      if (event.paths.isNotEmpty()) {
        put("paths", event.paths)
      }
      if (event.lineRanges.isNotEmpty()) {
        put("lineRanges", event.lineRanges)
      }
      event.path?.let { path -> put("path", path) }
      event.fromLine?.let { fromLine -> put("fromLine", fromLine) }
      event.returnedLineCount?.let { returnedLineCount -> put("returnedLineCount", returnedLineCount) }
      event.totalLineCount?.let { totalLineCount -> put("totalLineCount", totalLineCount) }
    }
    is OpenCrayMemoryWriteEvent -> mapOf(
      "kind" to "memory_write",
      "runId" to event.runId,
      "taskId" to event.taskId,
      "executionId" to event.executionId,
      "executionOrdinal" to event.executionOrdinal,
      "executionKind" to event.executionKind,
      "turn" to event.turn,
      "emittedAtEpochMs" to event.emittedAtEpochMs,
      "writtenRecordIds" to event.writtenRecordIds,
      "writtenKinds" to event.writtenKinds,
      "resolvedRecordIds" to event.resolvedRecordIds,
      "suppressedRecordIds" to event.suppressedRecordIds,
      "reaffirmedRecordIds" to event.reaffirmedRecordIds,
      "expiredRecordIds" to event.expiredRecordIds,
    )
    is OpenCrayCancellationEvent -> mapOf(
      "kind" to "interrupted",
      "runId" to event.runId,
      "taskId" to event.taskId,
      "executionId" to event.executionId,
      "executionOrdinal" to event.executionOrdinal,
      "executionKind" to event.executionKind,
      "turn" to event.turn,
      "emittedAtEpochMs" to event.emittedAtEpochMs,
      "toolName" to event.toolName,
      "text" to event.text,
      "stage" to event.outcome,
      "status" to event.outcome,
    )
  }

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

  private fun finalTextForLocked(
    sessionId: String,
    task: AgentTask,
    result: ExecutionResult,
  ): String {
    if (isLlmRetryPausedResult(result)) {
      return llmRetryPausedMessage()
    }
    val toolSummaryFallback = successfulToolSummaryFallbackTextLocked(
      sessionId = sessionId,
      task = task,
      result = result,
    )
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
    searchMemoryDebug(
      query = query,
      maxResults = 4,
      minScore = 1,
    )

  private fun successfulToolSummaryFallbackTextLocked(
    sessionId: String,
    task: AgentTask,
    result: ExecutionResult,
  ): String? {
    if (hasFinalAttachments(result) || !shouldUseSuccessfulToolSummaryFallback(result)) {
      return null
    }
    val latestToolResult = latestSuccessfulToolResultEventForTaskLocked(
      sessionId = sessionId,
      task = task,
    ) ?: return null
    val orderedEvents = chatRuntimeEventState.eventsForSession(sessionId)
      .filter { event -> event.runId == runIdFor(task) }
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
  ): OpenCrayToolResultEvent? {
    val runId = runIdFor(task)
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

  private fun finalAttachmentsForResultLocked(
    sessionId: String,
    task: AgentTask,
    result: ExecutionResult,
    compatibilityAttachments: List<OpenCrayFinalAttachment> = emptyList(),
  ): List<ChatAttachmentEntry> {
    val explicitAttachments = finalAttachmentRequestsForResult(result)
    if (explicitAttachments.isEmpty() && compatibilityAttachments.isEmpty()) {
      return emptyList()
    }
    val workspaceRoot = workspaceRootProvider?.invoke() ?: return emptyList()
    val resolvedExplicitAttachments = resolveFinalChatAttachmentsLocked(
      sessionId = sessionId,
      attachments = resolveFinalAttachmentArtifactsLocked(
        sessionId = sessionId,
        runId = runIdFor(task),
        attachments = explicitAttachments,
      ),
    )
    val resolvedAttachments = dedupeFinalAttachments(
      attachments = resolvedExplicitAttachments + compatibilityAttachments,
    )
    return runCatching {
      AppChatAttachmentArchiver.archive(
        workspaceRoot = workspaceRoot,
        approvedReadRoots = approvedReadRootsProvider().roots,
        sessionId = sessionId,
        attachments = resolvedAttachments,
        voiceMetadataAnalyzer = NoOpVoiceMetadataAnalyzer,
      )
    }.getOrDefault(emptyList())
  }

  private fun finalizedAssistantText(
    text: String,
    attachments: List<ChatAttachmentEntry>,
  ): String = if (text.isBlank() && attachments.isNotEmpty()) {
    ""
  } else {
    text
  }

  private fun attachmentMarkdownCompatibilityLocked(
    sessionId: String,
    task: AgentTask,
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
      task = task,
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
      rewrittenText = text,
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
  ): List<AttachmentMarkdownCandidate> {
    val runCandidates = attachmentArtifactsForRunLocked(
      sessionId = sessionId,
      runId = runIdFor(task),
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

  private fun resolveAttachmentMarkdownReference(
    reference: AttachmentMarkdownReference,
    candidates: List<AttachmentMarkdownCandidate>,
  ): AttachmentMarkdownCandidate? {
    if (candidates.isEmpty()) {
      return null
    }
    val preferredCandidates = if (reference.isImage) {
      candidates.filter(AttachmentMarkdownCandidate::isImageLike).ifEmpty { candidates }
    } else {
      candidates
    }
    val targetToken = reference.targetToken
    if (targetToken.isNotBlank() && targetToken != "artifact") {
      preferredCandidates.firstOrNull { candidate -> candidate.matches(targetToken) }?.let { match ->
        return match
      }
    }
    val labelToken = normalizeAttachmentMarkdownToken(reference.label)
    if (labelToken.isNotBlank()) {
      preferredCandidates.firstOrNull { candidate ->
        candidate.matches(
          labelToken,
          includeArtifactId = false,
          includeAttachmentId = false,
        )
      }?.let { match ->
        return match
      }
    }
    return if ((targetToken.isBlank() || targetToken == "artifact") && preferredCandidates.size == 1) {
      preferredCandidates.first()
    } else {
      null
    }
  }

  private fun rewriteAttachmentMarkdownText(
    text: String,
    resolvedReferences: List<ResolvedAttachmentMarkdownReference>,
  ): String {
    if (resolvedReferences.isEmpty()) {
      return text
    }
    val resolvedTokens = resolvedReferences.filter { it.attachment != null }
    if (resolvedTokens.isNotEmpty()) {
      val unresolvedOnly = resolvedTokens.fold(text) { current, resolved ->
        current.replace(resolved.reference.raw, "")
      }
      if (cleanupAttachmentMarkdownText(unresolvedOnly).isBlank()) {
        return ""
      }
    }
    var rewritten = text
    resolvedReferences.forEach { resolved ->
      val replacement = when {
        resolved.attachment != null && resolved.reference.isImage -> ""
        else -> resolved.reference.fallbackLabel
      }
      rewritten = rewritten.replace(resolved.reference.raw, replacement)
    }
    return cleanupAttachmentMarkdownText(rewritten)
  }

  private fun cleanupAttachmentMarkdownText(text: String): String = text
    .replace(Regex("""[ \t]+\n"""), "\n")
    .replace(Regex("""\n[ \t]+"""), "\n")
    .replace(Regex("""\n{3,}"""), "\n\n")
    .trim()

  private fun parseAttachmentMarkdownReferences(
    text: String,
  ): List<AttachmentMarkdownReference> = ATTACHMENT_MARKDOWN_REFERENCE_REGEX.findAll(text)
    .map { match ->
      val href = match.groupValues[3]
        .trim()
        .substringBefore(' ')
        .trim()
        .removePrefix("attachment:")
        .removePrefix("//")
        .trim()
      AttachmentMarkdownReference(
        raw = match.value,
        label = match.groupValues[2].trim(),
        targetToken = normalizeAttachmentMarkdownToken(href),
        isImage = match.groupValues[1] == "!",
      )
    }
    .toList()

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
    )
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
    val events = chatRuntimeEventState.eventsForSession(sessionId)
      .asReversed()
    val resolved = linkedMapOf<String, ResolvedAttachmentArtifact>()
    events.forEach { event ->
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

  private fun sanitizeApprovalBody(body: String?, isHighRisk: Boolean): String {
    val fallback = approvalFallbackBody(isHighRisk = isHighRisk)
    val resolved = body?.takeIf(String::isNotBlank) ?: return fallback
    return sanitizePotentialInternalAgentText(
      text = resolved,
      fallback = fallback,
    )
  }

  private fun approvalFallbackBody(isHighRisk: Boolean): String = if (isHighRisk) {
    strings.chatHighRiskApprovalRequiredBody
  } else {
    strings.chatSummaryApprovalRequired
  }

  private fun sanitizeDrawerPreviewText(text: String): String {
    val restoredFallback = restoreKnownPreviewFallback(text.trim())
    if (restoredFallback != null) {
      return restoredFallback
    }
    return sanitizePotentialInternalAgentText(
      text = text,
      fallback = strings.agentInternalPayloadHidden,
    )
  }

  private fun restoreKnownPreviewFallback(text: String): String? {
    val knownFallbacks = listOf(
      strings.agentInternalPayloadHidden,
      strings.chatSummaryAwaitingDirection,
      strings.chatSummaryApprovalRequired,
      strings.chatHighRiskApprovalRequiredBody,
    )
    return knownFallbacks.firstOrNull { fallback ->
      text == fallback.take(DRAWER_PREVIEW_MAX_CHARS).trimEnd()
    }
  }

  private fun drawerPreviewTextLocked(
    sessionId: String,
    fallbackPreview: String,
  ): String {
    val normalizedFallback = snapshotDrawerPreviewText(fallbackPreview)
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
    )
    val latestRun = latestRunForSnapshot(displayedRuns) ?: return normalizedFallback
    val pendingApproval = pendingApprovalsForSession(sessionId).firstOrNull { approval ->
      approval.runId == latestRun.runId || approval.taskId == latestRun.taskId
    }
    val shouldOverride = pendingApproval != null ||
      isAwaitingDirectionRun(latestRun) ||
      isDeferredApprovalDecisionAwaitingResumeRun(latestRun) ||
      isDrawerPlaceholderPreview(normalizedFallback)
    val runtimePreview = if (shouldOverride) {
      drawerRuntimePreviewText(
        run = latestRun,
        pendingApproval = pendingApproval,
        recentEvents = recentEvents,
      )
    } else {
      null
    }
    return snapshotDrawerPreviewText(runtimePreview ?: normalizedFallback)
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
      is OpenCrayToolCallEvent -> chatToolCallText(lastEvent).trim().takeIf(String::isNotBlank)
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

  private fun isDrawerPlaceholderPreview(text: String): Boolean {
    val normalized = normalizeDrawerPreviewWhitespace(text)
    return normalized.isBlank() ||
      normalized == strings.agentThinking.take(DRAWER_PREVIEW_MAX_CHARS).trimEnd() ||
      normalized == strings.chatSummaryApprovalRequired.take(DRAWER_PREVIEW_MAX_CHARS).trimEnd() ||
      normalized == strings.chatHighRiskApprovalRequiredBody.take(DRAWER_PREVIEW_MAX_CHARS).trimEnd()
  }

  private fun snapshotDrawerPreviewText(text: String): String =
    sanitizeDrawerPreviewText(
      normalizeDrawerPreviewWhitespace(text).take(DRAWER_PREVIEW_MAX_CHARS).trimEnd(),
    )

  private fun normalizeDrawerPreviewWhitespace(text: String): String =
    text.replace(Regex("""\s+"""), " ").trim()

  private fun pendingApprovalSnapshot(
    runId: String,
    taskId: String,
    pendingMessageId: String?,
    isHighRisk: Boolean,
    metadata: Map<String, String>,
    errorBody: String,
    toolReason: String?,
  ): PendingApprovalSnapshot {
    val toolName = approvalMetadataToolName(metadata)
    val resumeToolName = approvalMetadataResumeToolName(metadata) ?: toolName
    val promptCheckpointBoundary = OpenCrayPromptResumeMetadata.decodeCheckpointBoundary(metadata)
    val promptResumeState = OpenCrayPromptResumeMetadata.decodeFromMetadata(
      metadata = metadata,
      json = replayJson,
    )
    val subAgentLifecycle = approvalMetadataSubAgentLifecycle(metadata)
      ?.toPendingApprovalSubAgentLifecycle()
    val subAgentApprovalResume = mergeApprovalResumeMetadata(
      decoded = SubAgentApprovalResumeMetadata.decodeFromMetadata(
        metadata = metadata,
        json = replayJson,
      ),
      metadata = metadata,
      lifecycle = subAgentLifecycle?.toApprovalDecisionSubAgentLifecycle(),
    )
    val requestSummary = approvalRequestSummary(metadata)
    val primaryDetail = approvalPrimaryDetailValue(metadata)
    val pathDetails = approvalPathDetailLines(metadata)
    val workingDirectory = approvalWorkingDirectoryValue(metadata)
    val reason = approvalReasonValue(toolReason)
    val supportsSessionApproval = approvalMetadataSupportsSessionScope(metadata)
    val executionId = executionIdFromMetadata(metadata)
    val executionOrdinal = executionOrdinalFromMetadata(metadata)
    val executionKind = executionKindFromMetadata(metadata)
    return PendingApprovalSnapshot(
      runId = runId,
      taskId = taskId,
      pendingMessageId = pendingMessageId,
      executionId = executionId,
      executionOrdinal = executionOrdinal,
      executionKind = executionKind,
      toolName = toolName,
      resumeToolName = resumeToolName,
      promptCheckpointBoundary = promptCheckpointBoundary,
      promptResumeState = promptResumeState,
      subAgentApprovalResume = subAgentApprovalResume,
      requestSummary = requestSummary,
      primaryDetail = primaryDetail,
      pathDetails = pathDetails,
      workingDirectory = workingDirectory,
      reason = reason,
      message = errorBody,
      isHighRisk = isHighRisk,
      supportsSessionApproval = supportsSessionApproval,
      approveForSessionLabel = if (supportsSessionApproval) {
        strings.chatApprovalApproveForSessionLabel
      } else {
        null
      },
      subAgentLifecycle = subAgentLifecycle,
      title = if (isHighRisk) {
        strings.chatHighRiskApprovalRequiredTitle
      } else {
        strings.chatApprovalRequiredTitle
      },
      body = composeApprovalBody(
        body = errorBody,
        toolReason = toolReason,
        metadata = metadata,
      ),
    )
  }

  private fun approvalIsHighRisk(
    errorCode: String?,
    metadata: Map<String, String>,
  ): Boolean = approvalMetadataIsHighRisk(
    errorCode = errorCode,
    highRiskErrorCode = ERROR_HIGH_RISK_APPROVAL_REQUIRED,
    metadata = metadata,
  )

  private fun toolReasonFromEvent(event: OpenCrayAgentRunEvent): String? = when (event) {
    is OpenCrayToolCallEvent -> event.call.reason
    else -> null
  }

  private fun composeApprovalBody(
    body: String,
    toolReason: String?,
    metadata: Map<String, String>,
  ): String = approvalSupportComposeBody(
    body = body,
    toolReason = toolReason,
    metadata = metadata,
    isChinese = isChineseHostLocale(),
  )

  private fun approvalRequiredRuntimeEvent(
    approval: PendingApprovalSnapshot,
    emittedAtEpochMs: Long,
  ): OpenCrayApprovalEvent = OpenCrayApprovalEvent(
    runId = approval.runId,
    taskId = approval.taskId,
    executionId = approval.executionId,
    executionOrdinal = approval.executionOrdinal,
    executionKind = approval.executionKind,
    phase = OpenCrayApprovalPhase.REQUIRED,
    toolName = approval.toolName,
    text = approvalTimelineText(approval),
    isHighRisk = approval.isHighRisk,
    emittedAtEpochMs = emittedAtEpochMs,
  )

  private fun cancellationRuntimeEvent(
    run: AgentRunSnapshot,
    approval: PendingApprovalSnapshot?,
    emittedAtEpochMs: Long,
  ): OpenCrayCancellationEvent = OpenCrayCancellationEvent(
    runId = run.runId,
    taskId = run.taskId,
    executionId = run.executionId,
    executionOrdinal = run.executionOrdinal,
    executionKind = run.executionKind,
    toolName = approval?.toolName,
    outcome = "user_interrupted",
    text = cancellationTimelineText(toolName = approval?.toolName),
    emittedAtEpochMs = emittedAtEpochMs,
  )

  private fun approvalTimelineText(approval: PendingApprovalSnapshot): String {
    val title = approval.title.trim()
    val body = approval.body.trim()
    return when {
      title.isEmpty() -> body
      body.isEmpty() -> title
      else -> "$title\n\n$body"
    }
  }

  private fun cancellationTimelineText(toolName: String?): String {
    val resolvedToolName = toolName?.trim()?.takeIf { value -> value.isNotBlank() }
    return if (isChineseHostLocale()) {
      if (resolvedToolName == null) {
        "本次运行已中断，等待你的下一步指示。"
      } else {
        "已中断待审批的 $resolvedToolName 请求，等待你的下一步指示。"
      }
    } else if (resolvedToolName == null) {
      "Run interrupted. The agent is waiting for your next instruction."
    } else {
      "Interrupted the pending $resolvedToolName request. The agent is waiting for your next instruction."
    }
  }

  private fun deferredApprovalRecordedText(): String = if (isChineseHostLocale()) {
    "审批已通过。此决定已记录；手动继续运行后才会生效。"
  } else {
    "Approval granted. The decision is recorded and will apply when you manually resume the run."
  }

  private fun deferredApprovalRecordedForSessionText(): String = if (isChineseHostLocale()) {
    "本会话审批已通过。此决定已记录；手动继续运行后才会生效。"
  } else {
    "Session approval granted. The decision is recorded and will apply when you manually resume the run."
  }

  private fun deferredApprovalRejectedText(): String = if (isChineseHostLocale()) {
    "审批已拒绝。此决定已记录；手动继续运行后才会生效。"
  } else {
    "Approval rejected. The decision is recorded and will apply when you manually resume the run."
  }

  private fun approvalRequestSummary(metadata: Map<String, String>): String? =
    approvalSupportRequestSummary(metadata)

  private fun approvalPrimaryDetailValue(metadata: Map<String, String>): String? =
    approvalSupportPrimaryDetailValue(metadata)

  private fun approvalPathDetailLines(metadata: Map<String, String>): List<String> =
    approvalSupportPathDetailLines(
      metadata = metadata,
      isChinese = isChineseHostLocale(),
    )

  private fun PendingApprovalSnapshot.toApprovalDecisionRecord(): ApprovalDecisionRecord =
    ApprovalDecisionRecord(
      runId = runId,
      taskId = taskId,
      pendingMessageId = pendingMessageId,
      executionId = executionId,
      executionOrdinal = executionOrdinal,
      executionKind = executionKind,
      toolName = toolName,
      resumeToolName = resumeToolName,
      promptCheckpointBoundary = promptCheckpointBoundary,
      promptResumeState = promptResumeState,
      subAgentApprovalResume = subAgentApprovalResume,
      isHighRisk = isHighRisk,
      subAgentLifecycle = subAgentLifecycle?.toApprovalDecisionSubAgentLifecycle(),
    )

  private fun ApprovalDecisionSubAgentLifecycle.toPendingApprovalSubAgentLifecycle():
    PendingApprovalSubAgentLifecycle = PendingApprovalSubAgentLifecycle(
    childRunId = childRunId,
    childTaskId = childTaskId,
    label = label,
    subagentType = subagentType,
    contextMode = contextMode,
    depth = depth,
  )

  private fun PendingApprovalSubAgentLifecycle.toApprovalDecisionSubAgentLifecycle():
    ApprovalDecisionSubAgentLifecycle = ApprovalDecisionSubAgentLifecycle(
    childRunId = childRunId,
    childTaskId = childTaskId,
    label = label,
    subagentType = subagentType,
    contextMode = contextMode,
    depth = depth,
  )

  private fun delegatedChildApprovalApprovedSummary(): String = if (isChineseHostLocale()) {
    "子任务审批已通过，将继续执行。"
  } else {
    "Delegated child approval granted. The child will continue."
  }

  private fun delegatedChildApprovalRejectedStopSummary(): String = if (isChineseHostLocale()) {
    "子任务审批被拒绝，子任务已停止。"
  } else {
    "Delegated child approval rejected. The child run was stopped."
  }

  private fun delegatedChildCancelledWhileWaitingSummary(): String = if (isChineseHostLocale()) {
    "子任务在等待审批时被取消。"
  } else {
    "Delegated child run was cancelled while waiting for approval."
  }

  private fun approvalWorkingDirectoryValue(metadata: Map<String, String>): String? =
    approvalSupportWorkingDirectoryValue(metadata)

  private fun approvalReasonValue(toolReason: String?): String? =
    approvalSupportReasonValue(toolReason)

  private fun sanitizePotentialInternalAgentText(text: String, fallback: String): String {
    val trimmed = text.trim()
    if (trimmed.isBlank()) {
      return text
    }
    return if (looksLikeInternalToolPayload(trimmed)) fallback else text
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

  private data class AttachmentMarkdownReference(
    val raw: String,
    val label: String,
    val targetToken: String,
    val isImage: Boolean,
  ) {
    val fallbackLabel: String
      get() = label.ifBlank { targetToken.substringAfterLast('/').trim() }
  }

  private data class ResolvedAttachmentMarkdownReference(
    val reference: AttachmentMarkdownReference,
    val attachment: AttachmentMarkdownCandidate?,
  )

  private data class AttachmentMarkdownCandidate(
    val attachmentId: String? = null,
    val relativePath: String,
    val displayName: String,
    val kindHint: String? = null,
    val mimeType: String? = null,
    val durationMs: Long? = null,
    val waveformBars: List<Int> = emptyList(),
    val transcriptText: String? = null,
    val artifactId: String? = null,
  ) {
    private val normalizedRelativePath: String = normalizeAttachmentMarkdownToken(relativePath)
    private val normalizedDisplayName: String = normalizeAttachmentMarkdownToken(displayName)
    private val normalizedBaseName: String = normalizedRelativePath.substringAfterLast('/')
    private val normalizedArtifactId: String? = artifactId
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.lowercase(Locale.US)
    private val normalizedAttachmentId: String? = attachmentId
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.lowercase(Locale.US)

    val isImageLike: Boolean
      get() = kindHint?.trim()?.lowercase(Locale.US) == "image" ||
        mimeType?.trim()?.lowercase(Locale.US)?.startsWith("image/") == true ||
        IMAGE_ATTACHMENT_EXTENSIONS.contains(normalizedDisplayName.substringAfterLast('.', ""))

    fun matches(
      token: String,
      includeArtifactId: Boolean = true,
      includeAttachmentId: Boolean = true,
    ): Boolean {
      val normalizedToken = normalizeAttachmentMarkdownToken(token)
      if (normalizedToken.isBlank()) {
        return false
      }
      return normalizedToken == normalizedRelativePath ||
        normalizedToken == normalizedDisplayName ||
        normalizedToken == normalizedBaseName ||
        (includeAttachmentId && normalizedToken == normalizedAttachmentId) ||
        (includeArtifactId && normalizedToken == normalizedArtifactId)
    }

    fun toFinalAttachment(forceImage: Boolean): OpenCrayFinalAttachment = OpenCrayFinalAttachment(
      kind = if (forceImage) "image" else kindHint,
      relativePath = relativePath,
      displayName = displayName,
      mimeType = mimeType,
      durationMs = durationMs,
      waveformBars = waveformBars,
      transcriptText = transcriptText,
    )
  }

  private data class AttachmentMarkdownCompatibility(
    val rewrittenText: String,
    val attachments: List<OpenCrayFinalAttachment> = emptyList(),
  )

  private fun chatModeLabelFor(mode: SafetyAutomationMode): String = when (mode) {
    SafetyAutomationMode.SAFE -> strings.chatModeSafeLabel
    SafetyAutomationMode.AUTO -> strings.chatModeLabel
    SafetyAutomationMode.DEV -> strings.chatModeDeveloperLabel
  }

  private fun safetyMetadataForTask(
    snapshot: SafetySettingsSnapshot,
  ): Map<String, String> = buildTaskSafetyMetadata(
    snapshot = snapshot,
    approvedReadRoots = approvedReadRootsProvider(),
  )

  private fun refreshLocalizedResourcesLocked() {
    val baseContext = appContext ?: return
    val localizedContext = OpenCrayLocaleManager.wrap(baseContext)
    settingsFacade = LocalSettingsFacade.fromContext(localizedContext)
    llmConfigFacade = LocalLlmConfigFacade.fromContext(localizedContext)
    personalizationFacade = LocalPersonalizationFacade.createForTest(
      context = localizedContext,
      store = PersonalizationLocalStore.fromContext(baseContext),
      soulProfileStore = workspaceSoulProfileStore,
      workspaceRootProvider = { AppAgentWorkspace.ensureRootForContext(baseContext) },
      queueIdleProvider = {
        val activeSessionId = chatSessionStore.loadState().activeSession.sessionId
        pendingTaskCount(activeSessionId) == 0
      },
    )
    mcpSettingsFacade = LocalMcpSettingsFacade.createForTest(
      context = localizedContext,
      settingsStore = McpSettingsStore.fromContext(baseContext),
      registryStore = AppMcpRegistryStore.fromContext(baseContext),
    )
    safetySettingsFacade = LocalSafetySettingsFacade.fromContext(baseContext)
    skillsFacade = LocalSkillsFacade.fromContext(localizedContext)
    strings = localizedHostRuntimeStrings(localizedContext)
  }

  private fun SettingsOverviewSnapshot.toMap(): Map<String, Any?> = mapOf(
    "eyebrow" to eyebrow,
    "title" to title,
    "subtitle" to subtitle,
    "deviceTitle" to deviceTitle,
    "deviceSummary" to deviceSummary,
    "entries" to entries.map { entry ->
      mapOf(
        "routeId" to entry.routeId.wireValue,
        "title" to entry.title,
      )
    },
  )

  private fun SettingsDetailSnapshot.toMap(): Map<String, Any?> = mapOf(
    "routeId" to routeId.wireValue,
    "title" to title,
    "subtitle" to subtitle,
    "sections" to sections.map { section -> section.toMap() },
  )

  private fun NetworkSearchConfigSnapshot.toMap(): Map<String, Any?> = mapOf(
    "localeTag" to localeTag,
    "title" to title,
    "subtitle" to subtitle,
    "slots" to slots.map { slot -> slot.toMap() },
  )

  private fun NetworkSearchSlotSnapshot.toMap(): Map<String, Any?> = mapOf(
  "id" to id,
  "providerId" to providerId,
  "label" to label,
  "baseUrl" to baseUrl,
  "model" to model,
  "apiKey" to apiKey,
  "enabled" to enabled,
)

  private fun MediaSpeechConfigSnapshot.toMap(): Map<String, Any?> = mapOf(
    "localeTag" to localeTag,
    "title" to title,
    "subtitle" to subtitle,
    "imageGeneration" to imageGeneration.toMap(),
    "voiceGeneration" to voiceGeneration.toMap(),
    "sttRouteId" to sttRouteId,
    "externalStt" to externalStt.toMap(),
    "onDeviceModel" to onDeviceModel.toMap(),
  )

  private fun MediaProviderSnapshot.toMap(): Map<String, Any?> = mapOf(
    "provider" to provider,
    "baseUrl" to baseUrl,
    "endpoint" to endpoint,
    "model" to model,
  )

  private fun VoiceProviderSnapshot.toMap(): Map<String, Any?> = mapOf(
    "provider" to provider,
    "baseUrl" to baseUrl,
    "endpoint" to endpoint,
    "voicePreset" to voicePreset,
  )

  private fun OnDeviceSttSnapshot.toMap(): Map<String, Any?> = mapOf(
    "modelPackage" to modelPackage,
    "downloadStatus" to downloadStatus,
  )

  private fun SettingsSectionSnapshot.toMap(): Map<String, Any?> = mapOf(
    "title" to title,
    "helperText" to helperText,
    "rows" to rows.map { row -> row.toMap() },
    "segmentedOptions" to segmentedOptions,
    "segmentedIndex" to segmentedIndex,
    "inlinePanelText" to inlinePanelText,
    "backgroundTone" to backgroundTone.wireValue,
  )

  private fun SettingsRowSnapshot.toMap(): Map<String, Any?> = mapOf(
    "title" to title,
    "subtitle" to subtitle,
    "trailingKind" to trailingKind.wireValue,
    "toggleValue" to toggleValue,
    "valueLabel" to valueLabel,
  )

  private fun LlmConfigSnapshot.toMap(): Map<String, Any?> = mapOf(
    "localeTag" to localeTag,
    "enabled" to enabled,
    "streamingEnabled" to streamingEnabled,
    "providerMode" to providerMode,
    "providerId" to providerId,
    "selectedProviderOptionId" to selectedProviderOptionId,
    "protocol" to protocol,
    "providerOptions" to providerOptions.map { option -> option.toMap() },
    "providerName" to providerName,
    "providerNotes" to providerNotes,
    "baseUrl" to baseUrl,
    "apiKey" to apiKey,
    "model" to model,
    "reasoningEffort" to reasoningEffort,
    "systemPrompt" to systemPrompt,
    "openAiPromptCacheKeyStrategy" to openAiPromptCacheKeyStrategy,
    "openAiPromptCacheRetention" to openAiPromptCacheRetention,
    "anthropicPromptCachingEnabled" to anthropicPromptCachingEnabled,
    "anthropicPromptCacheTtl" to anthropicPromptCacheTtl,
    "contextBudgetPreset" to contextBudgetPreset,
    "contextBudgetReservedOutputTokens" to contextBudgetReservedOutputTokens,
    "contextBudgetSafetyMarginTokens" to contextBudgetSafetyMarginTokens,
    "contextBudgetEffectiveInputPercent" to contextBudgetEffectiveInputPercent,
    "onDeviceModels" to onDeviceModels.map { option -> option.toMap() },
    "selectedOnDeviceModelId" to selectedOnDeviceModelId,
    "onDeviceMaxContextWindow" to onDeviceMaxContextWindow,
    "onDeviceMaxTokens" to onDeviceMaxTokens,
    "onDeviceTopK" to onDeviceTopK,
    "onDeviceTopP" to onDeviceTopP,
    "onDeviceTemperature" to onDeviceTemperature,
    "onDeviceAccelerator" to onDeviceAccelerator,
    "onDeviceThinkingEnabled" to onDeviceThinkingEnabled,
    "onDeviceLiteModeEnabled" to onDeviceLiteModeEnabled,
    "helperText" to helperText,
    "agentCapability" to agentCapability.toMap(),
  )

  private fun LlmProviderOptionSnapshot.toMap(): Map<String, Any?> = mapOf(
    "id" to id,
    "providerId" to providerId,
    "title" to title,
    "subtitle" to subtitle,
    "defaultBaseUrl" to defaultBaseUrl,
    "defaultModel" to defaultModel,
    "protocol" to protocol,
    "apiKey" to apiKey,
    "isCustom" to isCustom,
  )

  private fun OnDeviceLlmModelOptionSnapshot.toMap(): Map<String, Any?> = mapOf(
    "id" to id,
    "title" to title,
    "subtitle" to subtitle,
    "sizeLabel" to sizeLabel,
    "fileSizeBytes" to fileSizeBytes,
    "installState" to installState,
    "downloadState" to installState,
    "downloadedBytes" to downloadedBytes,
    "downloadBytesPerSecond" to downloadBytesPerSecond,
    "sha256Verified" to sha256Verified,
    "isSelected" to isSelected,
    "lastError" to lastError,
  )

  private fun LlmValidationResult.toMap(): Map<String, Any?> = mapOf(
    "isSuccess" to isSuccess,
    "message" to message,
    "agentCapability" to agentCapability?.toMap(),
  )

  private fun LlmAgentCapabilitySnapshot.toMap(): Map<String, Any?> = mapOf(
    "routeFingerprint" to routeFingerprint,
    "verifiedAtEpochMs" to verifiedAtEpochMs,
    "wasVerified" to wasVerified,
    "contextWindowTokens" to contextWindowTokens,
    "visionInputSupported" to visionInputSupported,
    "nativeToolCallingAvailable" to nativeToolCallingAvailable,
    "toolChoiceSupported" to toolChoiceSupported,
    "parallelToolCallsSupported" to parallelToolCallsSupported,
    "strictToolSchemaSupported" to strictToolSchemaSupported,
    "responsesContinuationSupported" to responsesContinuationSupported,
    "builtinWebSearchSupported" to builtinWebSearchSupported,
    "assistantPhaseSupported" to assistantPhaseSupported,
    "citationIncludeSupported" to citationIncludeSupported,
  )

  private fun PersonalizationConfigSnapshot.toMap(): Map<String, Any?> = mapOf(
    "title" to title,
    "subtitle" to subtitle,
    "introTitle" to introTitle,
    "introBody" to introBody,
    "introHelper" to introHelper,
    "presetsTitle" to presetsTitle,
    "presetsHelper" to presetsHelper,
    "presets" to presets.map { preset -> preset.toMap() },
    "selectedPresetId" to selectedPresetId,
    "customOverlayTitle" to customOverlayTitle,
    "customOverlayHelper" to customOverlayHelper,
    "customLabelHint" to customLabelHint,
    "customLabelHelper" to customLabelHelper,
    "customGuidanceHint" to customGuidanceHint,
    "customGuidanceHelper" to customGuidanceHelper,
    "customLabel" to customLabel,
    "customGuidance" to customGuidance,
    "behaviorDefaultsTitle" to behaviorDefaultsTitle,
    "appLanguageTitle" to appLanguageTitle,
    "appLanguageOptions" to appLanguageOptions.map { option -> option.toMap() },
    "selectedAppLanguageId" to selectedAppLanguageId,
    "livePreviewTitle" to livePreviewTitle,
    "livePreviewName" to livePreviewName,
    "livePreviewSummary" to livePreviewSummary,
    "queueTitle" to queueTitle,
    "queueBody" to queueBody,
    "queueIsIdle" to queueIsIdle,
    "lastResetTitle" to lastResetTitle,
    "lastResetMessage" to lastResetMessage,
    "resetActions" to resetActions.map { action -> action.toMap() },
  )

  private fun PersonalizationPresetSnapshot.toMap(): Map<String, Any?> = mapOf(
    "id" to id,
    "title" to title,
    "summary" to summary,
    "voice" to voice,
    "status" to status,
    "isSelected" to isSelected,
  )

  private fun PersonalizationLanguageOptionSnapshot.toMap(): Map<String, Any?> = mapOf(
    "id" to id,
    "title" to title,
    "isSelected" to isSelected,
  )

  private fun PersonalizationResetActionSnapshot.toMap(): Map<String, Any?> = mapOf(
    "scopeId" to scope.wireValue,
    "title" to title,
    "scopeBody" to scopeBody,
    "retainBody" to retainBody,
    "confirmationToken" to confirmationToken,
    "inputHint" to inputHint,
    "disabledGuidance" to disabledGuidance,
    "typeExactGuidance" to typeExactGuidance,
    "armedGuidance" to armedGuidance,
    "isInputEnabled" to isInputEnabled,
  )

  private fun McpSettingsSnapshot.toMap(): Map<String, Any?> = mapOf(
    "title" to title,
    "subtitle" to subtitle,
    "masterTitle" to masterTitle,
    "masterSummary" to masterSummary,
    "masterEnabled" to masterEnabled,
    "summaryLine" to summaryLine,
    "serversTitle" to serversTitle,
    "serversHelper" to serversHelper,
    "masterDisabledTitle" to masterDisabledTitle,
    "masterDisabledBody" to masterDisabledBody,
    "servers" to servers.map { server -> server.toMap() },
  )

  private fun McpServerSettingsSnapshot.toMap(): Map<String, Any?> = mapOf(
    "id" to id,
    "title" to title,
    "statusLabel" to statusLabel,
    "statusTone" to statusTone,
    "trustLine" to trustLine,
    "authLine" to authLine,
    "readinessLine" to readinessLine,
    "transportLine" to transportLine,
    "exposureLine" to exposureLine,
    "guidance" to guidance,
    "actionLabel" to actionLabel,
    "actionTurnsOn" to actionTurnsOn,
    "isActionEnabled" to isActionEnabled,
  )

  private fun SafetySettingsSnapshot.toMap(): Map<String, Any?> = mapOf(
    "automationModeId" to automationMode.wireValue,
    "rollbackJournalEnabled" to rollbackJournalEnabled,
    "maxFilesPerBatch" to maxFilesPerBatch,
    "maxAgentTurns" to maxAgentTurns,
    "maxToolCalls" to maxToolCalls,
    "undoWindowHours" to undoWindowHours,
    "fileChangesPolicyId" to fileChangesPolicy.wireValue,
    "fileDeletesPolicyId" to fileDeletesPolicy.wireValue,
    "shellCommandsPolicyId" to shellCommandsPolicy.wireValue,
    "externalAccessModeId" to externalAccessMode.wireValue,
    "locations" to locations.map { location -> location.toMap() },
    "workspaceAccessProfileId" to workspaceAccessProfile.wireValue,
    "readOnlyOutsideWorkspace" to readOnlyOutsideWorkspace,
    "liveContextModeId" to liveContextMode.wireValue,
    "memoryToolsEnabled" to memoryToolsEnabled,
  )

  private fun SafetySettingsLocationSnapshot.toMap(): Map<String, Any?> = mapOf(
    "id" to id,
    "enabled" to enabled,
  )

  private fun observeWithInitial(
    listeners: LinkedHashSet<(Map<String, Any?>) -> Unit>,
    initialPayload: Map<String, Any?>,
    listener: (Map<String, Any?>) -> Unit,
  ): () -> Unit {
    synchronized(lock) {
      listeners += listener
    }
    mainThreadPoster.post { listener(initialPayload) }
    return {
      synchronized(lock) {
        listeners -= listener
      }
    }
  }

  private fun emitChatSnapshot() {
    emitSnapshotLazy(chatListeners, ::loadChatSnapshot)
  }

  private fun emitChatRuntimeSnapshot() {
    emitSnapshotLazy(chatRuntimeListeners, ::loadChatRuntimeSnapshot)
  }

  private fun emitLiveAssistantDraftEvent(payload: Map<String, Any?>) {
    emitSnapshot(liveAssistantDraftEventListeners, payload)
  }

  private fun emitShellSnapshot() {
    val payload = loadShellSnapshot()
    emitSnapshot(shellListeners, payload)
  }

  private fun emitSettingsOverview() {
    val payload = loadSettingsOverview()
    emitSnapshot(settingsOverviewListeners, payload)
  }

  private fun emitSkillsSnapshot() {
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

  private fun liveAssistantDraftEventPayload(
    sessionId: String,
    runId: String,
    taskId: String,
    pendingMessageId: String,
    text: String,
    updatedAtEpochMs: Long,
    cleared: Boolean,
  ): Map<String, Any?> = mapOf(
    "sessionId" to sessionId,
    "runId" to runId,
    "taskId" to taskId,
    "pendingMessageId" to pendingMessageId,
    "text" to text,
    "updatedAtEpochMs" to updatedAtEpochMs,
    "cleared" to cleared,
  )

  private fun LiveAssistantDraftSnapshot.toLiveAssistantDraftEventPayload(
    sessionId: String,
    cleared: Boolean,
  ): Map<String, Any?> = liveAssistantDraftEventPayload(
    sessionId = sessionId,
    runId = runId,
    taskId = taskId,
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
    private const val ERROR_HIGH_RISK_APPROVAL_REQUIRED: String = "HIGH_RISK_APPROVAL_REQUIRED"
    private const val DEFAULT_RUN_WAIT_TIMEOUT_MS: Long = 15_000L
    private const val RUN_LOOKUP_POLL_INTERVAL_MS: Long = 50L
    private const val MAX_RUNTIME_EVENT_HISTORY: Int = 24
    private const val MAX_RUNTIME_EVENT_PREVIEW_CHARS: Int = 240
    private const val MAX_RUNTIME_EVENT_FAILURE_CONTENT_CHARS: Int = 16_384
    private const val INTERNAL_PROMPT_CHECKPOINT_MARKER: String = "internal_prompt_checkpoint"
    private val HIDDEN_ASSISTANT_CHAT_STAGES: Set<String> = setOf(
      "llm_retry",
      "responses_recovery",
    )
    private const val MEMORY_DEBUG_RUN_ID_PREFIX: String = "run-memory-debug-"
    private const val MEMORY_DEBUG_TASK_ID_PREFIX: String = "memory-debug-"
    private const val DRAWER_PREVIEW_MAX_CHARS: Int = 52
    private const val TODO_ARCHIVE_VISIBILITY_DURATION_MS: Long = 4_000L
    private val replayJson: Json = Json { ignoreUnknownKeys = true }
    private val MEMORY_SELECTED_TRACE_REGEX: Regex = Regex("""^(.+?)@(\d+)(?:\[(.*)])?$""")
    private val BOOTSTRAP_FILE_TRACE_REGEX: Regex =
      Regex("""^(.+?)@(.+)\[(\d+)\|(\d+)\|(true|false)]$""")
    private val VISIBLE_SKILL_TRACE_REGEX: Regex =
      Regex("""^([a-z0-9-]+)@(.+)\[([^\]|]+)\|(true|false)\|([^\]|]+)]$""")

    internal fun createForTest(
      stateStore: AppShellStateStore,
      chatSessionStore: ChatSessionLocalStore,
      settingsFacade: SettingsFacade,
      notificationSettingsFacade: NotificationSettingsFacade = EmptyNotificationSettingsFacade,
      networkSearchConfigFacade: NetworkSearchConfigFacade = EmptyNetworkSearchConfigFacade,
      mediaSpeechSettingsFacade: MediaSpeechSettingsFacade = EmptyMediaSpeechSettingsFacade,
      sandboxSettingsRepository: SandboxSettingsRepository? = null,
      llmConfigFacade: LlmConfigFacade = EmptyLlmConfigFacade,
      personalizationFacade: PersonalizationFacade = EmptyPersonalizationFacade,
      personalizationLocalStore: PersonalizationLocalStore? = null,
      workspaceSoulProfileStore: WorkspaceSoulProfileStore = WorkspaceSoulProfileStore(),
      mcpSettingsFacade: McpSettingsFacade = EmptyMcpSettingsFacade,
      safetySettingsFacade: SafetySettingsFacade = EmptySafetySettingsFacade,
      skillsFacade: SkillsFacade = EmptySkillsFacade,
      workspaceRootProvider: (() -> Path)? = null,
      workspaceEntryOpener: ((Path, String) -> Unit)? = null,
      externalUriOpener: ((String) -> Unit)? = null,
      approvedReadRootsProvider: () -> ApprovedReadRootsSnapshot = {
        workspaceRootProvider?.invoke()?.let { workspaceRoot ->
          val normalizedWorkspaceRoot = workspaceRoot.toAbsolutePath().normalize()
          ApprovedReadRootsSnapshot(
            roots = setOf(normalizedWorkspaceRoot),
            summary = "workspace=${normalizedWorkspaceRoot.toString().replace('\\', '/')}",
          )
        } ?: ApprovedReadRootsSnapshot(
          roots = emptySet(),
          summary = "workspace=unavailable",
        )
      },
      workspaceSnapshotProvider: () -> Map<String, Any?> = {
        WorkspaceTreeSnapshot(
          rootName = AppAgentWorkspace.DIRECTORY_NAME,
          rootPath = AppAgentWorkspace.DIRECTORY_NAME,
          availableBytes = 0L,
          directoryCount = 0,
          fileCount = 0,
          entryCount = 0,
          isTruncated = false,
          children = emptyList(),
        ).toMap()
      },
      strongBackgroundSettingsAccess: StrongBackgroundSettingsAccess =
        NoOpStrongBackgroundSettingsAccess,
      voiceMetadataAnalyzer: AppAgentWorkspaceVoiceMetadataAnalyzer = NoOpVoiceMetadataAnalyzer,
      voiceMetadataBackfillExecutor: Executor = InlineExecutor,
      voiceMetadataCacheStore: AppAgentWorkspaceVoiceMetadataCacheStore? = null,
      sessionRuntimeManager: AgentSessionRuntimeManager,
      runEventJournalStoreFactory: RunEventJournalStoreFactory = inMemoryRunEventJournalStoreFactory(),
      promptCheckpointStoreFactory: PromptCheckpointStoreFactory = inMemoryPromptCheckpointStoreFactory(),
      supplementStoreFactory: AgentSessionSupplementStoreFactory = inMemorySupplementStoreFactory(),
      todoSnapshotProvider: (String) -> ChatSessionTodoPresentation = {
        ChatSessionTodoPresentation.empty()
      },
      transcriptMessagesProvider: (String) -> List<RuntimeConversationMessage> = { emptyList() },
      approvalRegistry: AgentTaskApprovalRegistry = AgentTaskApprovalRegistry(),
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
      runtimeOwnerDescriptor: HostRuntimeLifecycleDescriptor = lifecycleDescriptor,
      runtimeServiceDescriptor: RuntimeServiceLifecycleDescriptor? = null,
      runtimeServiceWorkState: RuntimeServiceWorkState? = null,
      runtimeServiceWorkStateProvider: () -> RuntimeServiceWorkState? = {
        runtimeServiceWorkState
      },
      runtimeServiceKeepAliveState: RuntimeServiceKeepAliveState? = null,
      runtimeServiceKeepAliveStateProvider: () -> RuntimeServiceKeepAliveState? = {
        runtimeServiceKeepAliveState
      },
      runtimeServiceKeepAliveChangeRegistrar: RuntimeServiceKeepAliveChangeRegistrar? = null,
      runtimeServiceConnectionState: RuntimeServiceConnectionState? = null,
      runtimeServiceConnectionStateProvider: () -> RuntimeServiceConnectionState? = {
        runtimeServiceConnectionState
      },
      runtimeServiceConnectionChangeRegistrar: RuntimeServiceConnectionChangeRegistrar? = null,
      resumeActiveSessionOnInit: Boolean = true,
      onDeviceLlmWarmupController: OnDeviceLlmWarmupController? = null,
    ): OpenCrayHostRuntime {
      val resolvedRuntimeHostAccess = DefaultOpenCrayRuntimeHostAccess(
        lifecycleDescriptor = runtimeOwnerDescriptor,
        sessionRuntimeManager = sessionRuntimeManager,
        runEventJournalStoreFactory = runEventJournalStoreFactory,
        promptCheckpointStoreFactory = promptCheckpointStoreFactory,
        supplementStoreFactory = supplementStoreFactory,
        approvalRegistry = approvalRegistry,
      )
      val runtimeDiagnosticsBridge = HostRuntimeDiagnosticsBridge.create(
        runtimeOwnerDescriptor = runtimeOwnerDescriptor,
        runtimeServiceDescriptor = runtimeServiceDescriptor,
        runtimeServiceWorkState = runtimeServiceWorkState,
        runtimeServiceWorkStateProvider = runtimeServiceWorkStateProvider,
        runtimeServiceKeepAliveState = runtimeServiceKeepAliveState,
        runtimeServiceKeepAliveStateProvider = runtimeServiceKeepAliveStateProvider,
        runtimeServiceKeepAliveChangeRegistrar = runtimeServiceKeepAliveChangeRegistrar,
        runtimeServiceConnectionState = runtimeServiceConnectionState,
        runtimeServiceConnectionStateProvider = runtimeServiceConnectionStateProvider,
        runtimeServiceConnectionChangeRegistrar = runtimeServiceConnectionChangeRegistrar,
      )
      return OpenCrayHostRuntime(
        appContext = null,
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
        runtimeHostAccess = resolvedRuntimeHostAccess,
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
        providedOnDeviceLlmWarmupController = onDeviceLlmWarmupController,
        lifecycleDescriptor = lifecycleDescriptor,
        runtimeDiagnosticsBridge = runtimeDiagnosticsBridge,
        resumeActiveSessionOnInit = resumeActiveSessionOnInit,
      )
    }

    private fun inMemorySupplementStoreFactory(): AgentSessionSupplementStoreFactory =
      object : AgentSessionSupplementStoreFactory {
        private val stores = ConcurrentHashMap<String, SessionSupplementStore>()

        override fun forChatSession(sessionId: String): SessionSupplementStore =
          stores.computeIfAbsent(sessionId) { InMemorySessionSupplementStore() }
      }

    private fun inMemorySandboxSettingsRepository(): SandboxSettingsRepository {
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

    private val InlineExecutor: Executor = Executor { command ->
      command.run()
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

private val ATTACHMENT_MARKDOWN_REFERENCE_REGEX: Regex =
  Regex("""(!?)\[([^\]]*)]\((attachment:[^)]+)\)""")

private val IMAGE_ATTACHMENT_EXTENSIONS: Set<String> = setOf(
  "apng",
  "avif",
  "bmp",
  "gif",
  "jpeg",
  "jpg",
  "png",
  "svg",
  "webp",
)

private fun normalizeAttachmentMarkdownToken(value: String): String = value
  .trim()
  .removePrefix("/")
  .replace('\\', '/')
  .lowercase(Locale.US)

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

private data class PendingApprovalMatch(
  val sessionId: String,
  val approval: PendingApprovalSnapshot,
)

internal data class PendingApprovalSubAgentLifecycle(
  val childRunId: String,
  val childTaskId: String,
  val label: String,
  val subagentType: String,
  val contextMode: String,
  val depth: Int,
)

private data class ReplayedRuntimeEvent(
  val sourceIndex: Int,
  val event: OpenCrayAgentRunEvent,
)

private data class LiveAssistantDraftSnapshot(
  val runId: String,
  val taskId: String,
  val pendingMessageId: String,
  val text: String,
  val updatedAtEpochMs: Long,
)

private data class EventEmissionDecision(
  val shouldEmit: Boolean,
  val emitChatSnapshot: Boolean = true,
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

private data class ProjectedRuntimeChatMessage(
  val anchorMessageId: String?,
  val snapshot: Map<String, Any?>,
)

private data class SubAgentActivityAccumulator(
  val firstEvent: OpenCraySubAgentEvent,
  val latestEvent: OpenCraySubAgentEvent,
  val eventCount: Int,
)

private data class SubAgentActivitySnapshot(
  val parentRunId: String,
  val parentTaskId: String,
  val childRunId: String,
  val childTaskId: String,
  val label: String,
  val subagentType: String,
  val contextMode: String,
  val depth: Int,
  val phase: String,
  val status: String?,
  val executionState: String?,
  val continuationKind: String?,
  val resumable: Boolean,
  val requiresUserAction: Boolean,
  val isHighRisk: Boolean,
  val summary: String?,
  val startedAtEpochMs: Long,
  val updatedAtEpochMs: Long,
  val eventCount: Int,
  val mailboxMessageCount: Int,
  val mailboxPendingMessageCount: Int,
  val mailboxLastDeliveredMessageId: String?,
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
