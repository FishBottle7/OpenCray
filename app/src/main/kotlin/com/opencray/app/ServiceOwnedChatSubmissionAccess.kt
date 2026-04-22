package com.opencray.app

import android.util.Log
import com.opencray.app.facade.safety.SafetySettingsFacade
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.ERROR_RESTART_REQUIRES_EXPLICIT_RETRY
import com.opencray.core.orchestrator.EXECUTION_KIND_CHECKPOINT_RESUME
import com.opencray.core.orchestrator.QueueTaskLifecycleState
import com.opencray.persistence.model.ChatAttachmentEntry
import com.opencray.persistence.model.ChatAttachmentKind
import com.opencray.persistence.model.ChatTranscriptRole
import com.opencray.runtime.ERROR_LLM_RETRY_EXHAUSTED_AWAITING_RESUME
import com.opencray.runtime.OpenCrayAttachmentArtifact
import com.opencray.runtime.OpenCrayAttachmentArtifacts
import com.opencray.runtime.OpenCrayFinalAttachment
import com.opencray.runtime.OpenCrayToolResultEvent
import com.opencray.runtime.context.RuntimeConversationAttachment
import com.opencray.runtime.context.RuntimeConversationAttachmentKind
import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.context.RuntimeConversationRole
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val CHAT_ERROR_APPROVAL_REQUIRED: String = "APPROVAL_REQUIRED"
private const val CHAT_ERROR_HIGH_RISK_APPROVAL_REQUIRED: String = "HIGH_RISK_APPROVAL_REQUIRED"
private const val CHAT_FLOW_DEBUG_TAG: String = "OpenCrayDiag"

private fun chatFlowDebug(message: String) {
  runCatching { Log.d(CHAT_FLOW_DEBUG_TAG, message) }
}

internal fun latestChatRunForSnapshot(runs: List<AgentRunSnapshot>): AgentRunSnapshot? {
  var latest: AgentRunSnapshot? = null
  runs.forEach { candidate ->
    val current = latest
    latest = when {
      current == null -> candidate
      candidate.acceptedAtEpochMs > current.acceptedAtEpochMs -> candidate
      candidate.acceptedAtEpochMs == current.acceptedAtEpochMs &&
        candidate.updatedAtEpochMs >= current.updatedAtEpochMs -> candidate
      else -> current
    }
  }
  return latest
}

internal fun chatRunIsLlmRetryPausedAwaitingResume(run: AgentRunSnapshot): Boolean =
  run.lifecycleState == QueueTaskLifecycleState.SUSPENDED &&
    run.errorCode == ERROR_LLM_RETRY_EXHAUSTED_AWAITING_RESUME

internal fun chatRunIsDeferredApprovalAwaitingResume(
  run: AgentRunSnapshot,
  runtimeHostAccess: OpenCrayRuntimeHostAccess,
): Boolean = run.lifecycleState == QueueTaskLifecycleState.SUSPENDED &&
  runtimeHostAccess.promptCheckpointStore(run.sessionId)
    .get(run.taskId)
    ?.checkpointKind in setOf(
    PromptCheckpointKind.APPROVED_PENDING_RESUME,
    PromptCheckpointKind.REJECTED_PENDING_RESUME,
  )

internal fun chatRunBlocksMidLoopSupplements(run: AgentRunSnapshot): Boolean =
  run.executionStatus == ExecutionStatus.DENIED &&
    (
      run.errorCode == CHAT_ERROR_APPROVAL_REQUIRED ||
        run.errorCode == CHAT_ERROR_HIGH_RISK_APPROVAL_REQUIRED
      )

internal fun chatRunIsInterruptedOnRestore(run: AgentRunSnapshot): Boolean =
  run.errorCode == ERROR_RESTART_REQUIRES_EXPLICIT_RETRY ||
    run.errorCode == ERROR_MANAGED_PROCESS_INTERRUPTED_ON_RESTORE

internal data class ChatSubmissionResult(
  val submission: AgentRunSubmission? = null,
  val didMutate: Boolean = false,
)

internal data class ServiceOwnedChatSubmissionResult(
  val payload: Map<String, Any?>? = null,
  val didMutate: Boolean = false,
)

internal class ChatSubmissionCoordinator(
  private val chatSessionStore: ChatSessionLocalStore,
  private val runtimeHostAccess: OpenCrayRuntimeHostAccess,
  private val taskSafetyMetadataProvider: () -> Map<String, String>,
  private val taskMetadataProvider: (String) -> Map<String, String>,
  private val workspaceRootProvider: (() -> Path)?,
  private val approvedReadRootsProvider: () -> ApprovedReadRootsSnapshot = {
    ApprovedReadRootsSnapshot(
      roots = emptySet(),
      summary = "workspace=unavailable",
    )
  },
  private val voiceMetadataAnalyzer: AppAgentWorkspaceVoiceMetadataAnalyzer =
    AppAgentWorkspaceVoiceMetadataAnalyzer { _, _ -> null },
  private val agentThinkingTextProvider: () -> String = { "Thinking" },
) {
  fun submitChatMessage(
    text: String,
    attachments: List<OpenCrayFinalAttachment>,
  ): ChatSubmissionResult {
    val trimmed = text.trim()
    val normalizedAttachments = attachments.mapNotNull(::normalizeSubmittedChatAttachment)
    if (trimmed.isEmpty() && normalizedAttachments.isEmpty()) {
      return ChatSubmissionResult()
    }
    val sessionId = chatSessionStore.loadState().activeSession.sessionId
    val archivedAttachments = archiveDraftChatAttachments(
      sessionId = sessionId,
      attachments = normalizedAttachments,
    )
    if (trimmed.isEmpty() && archivedAttachments.isEmpty()) {
      return ChatSubmissionResult()
    }
    repairStaleSupplements(sessionId)
    val liveRun = supplementTargetRun(sessionId)
    val queuedBefore = chatSessionStore.loadPendingUserInputs(sessionId)
    chatFlowDebug(
      "chat.submit session=$sessionId textLen=${trimmed.length} attachments=${archivedAttachments.size} liveRun=${liveRun?.runId ?: "-"} queued=${queuedBefore.size} pendingTasks=${pendingTaskCount(sessionId)}",
    )
    val submission = if (liveRun != null) {
      when {
        isLlmRetryPausedAwaitingResumeRun(liveRun) -> resumePausedRunWithUserInput(
          sessionId = sessionId,
          run = liveRun,
          userText = trimmed,
          attachments = archivedAttachments,
        )

        isDeferredApprovalDecisionAwaitingResumeRun(liveRun) -> {
          chatSessionStore.enqueuePendingUserInput(
            sessionId = sessionId,
            text = trimmed,
            attachments = archivedAttachments,
          )
          val resumed = runtimeSession(sessionId).requestResumeTask(liveRun.taskId)
          check(resumed) {
            "Unable to resume interrupted approval run '${liveRun.runId}'."
          }
          null
        }

        archivedAttachments.isNotEmpty() ||
          queuedBefore.isNotEmpty() ||
          runBlocksMidLoopSupplements(liveRun) -> {
          chatSessionStore.enqueuePendingUserInput(
            sessionId = sessionId,
            text = trimmed,
            attachments = archivedAttachments,
          )
          null
        }

        else -> {
          appendRunSupplement(
            sessionId = sessionId,
            run = liveRun,
            text = trimmed,
          )
          null
        }
      }
    } else if (pendingTaskCount(sessionId) > 0) {
      chatSessionStore.enqueuePendingUserInput(
        sessionId = sessionId,
        text = trimmed,
        attachments = archivedAttachments,
      )
      null
    } else if (queuedBefore.isNotEmpty()) {
      chatSessionStore.enqueuePendingUserInput(
        sessionId = sessionId,
        text = trimmed,
        attachments = archivedAttachments,
      )
      startNextQueuedChatRun(sessionId)
      null
    } else {
      submitPromptRun(
        sessionId = sessionId,
        userText = trimmed,
        attachments = archivedAttachments,
      )
    }
    return ChatSubmissionResult(
      submission = submission,
      didMutate = true,
    )
  }

  fun repairStaleSupplements(sessionId: String) {
    val liveRuns = runtimeSession(sessionId)
      .listRuns()
      .filter(AgentRunSnapshot::isActive)
    val staleEntries = if (liveRuns.isEmpty()) {
      supplementStore(sessionId).consumeAll()
    } else {
      val liveRunIds = liveRuns.mapTo(linkedSetOf(), AgentRunSnapshot::runId)
      val liveTaskIds = liveRuns.mapTo(linkedSetOf(), AgentRunSnapshot::taskId)
      supplementStore(sessionId).consumeMatching { entry ->
        entry.runId !in liveRunIds && entry.taskId !in liveTaskIds
      }
    }
    if (staleEntries.isEmpty()) {
      return
    }
    staleEntries
      .sortedBy(MidLoopSupplementEntry::createdAtEpochMs)
      .forEach { entry ->
        chatSessionStore.enqueuePendingUserInput(
          sessionId = sessionId,
          text = entry.text,
        )
      }
  }

  fun supplementTargetRun(sessionId: String): AgentRunSnapshot? = runtimeSession(sessionId)
    .listRuns()
    .filter(AgentRunSnapshot::isActive)
    .maxByOrNull(AgentRunSnapshot::acceptedAtEpochMs)

  fun startNextQueuedChatRun(sessionId: String): AgentRunSubmission? {
    if (!hasSession(sessionId)) {
      return null
    }
    if (pendingTaskCount(sessionId) > 0) {
      return null
    }
    if (requiresExplicitRetryAfterRestore(sessionId)) {
      return null
    }
    val queuedInput = chatSessionStore.loadPendingUserInputs(sessionId).firstOrNull() ?: return null
    val handle = runtimeSession(sessionId)
    val pendingMessageId = chatSessionStore.reserveMessageId(ChatTranscriptRole.ASSISTANT)
    val submittedRun = handle.submitPrompt(
      userText = ChatRuntimeTextFormatter.format(
        text = queuedInput.text,
        commandLabel = null,
        attachments = queuedInput.attachments,
      ),
      pendingMessageId = pendingMessageId,
      visibleThroughMessageId = pendingMessageId,
      policyDecision = PolicyDecision(
        outcome = PolicyDecisionOutcome.ALLOW,
        reasonCode = "FLUTTER_CHAT_ALLOW",
      ),
      metadata = taskSafetyMetadataProvider() +
        promptRuntimeMetadata(
          userText = queuedInput.text,
          attachments = queuedInput.attachments,
        ) +
        taskMetadataProvider(RunSubmissionSources.CHAT_QUEUED_FOLLOW_UP),
    )
    try {
      checkNotNull(
        chatSessionStore.appendPendingUserInputAsSubmittedTurn(
          sessionId = sessionId,
          queueId = queuedInput.queueId,
          assistantMessageId = pendingMessageId,
          assistantPlaceholderText = agentThinkingTextProvider(),
        ),
      ) {
        "Queued chat input '${queuedInput.queueId}' is unavailable."
      }
    } catch (throwable: Throwable) {
      handle.requestCancel(submittedRun.taskId)
      throw throwable
    }
    handle.ensureProcessing()
    return submittedRun
  }

  fun resumePausedRunWithUserInput(
    sessionId: String,
    run: AgentRunSnapshot,
    userText: String,
    attachments: List<ChatAttachmentEntry>,
  ): AgentRunSubmission {
    val checkpoint = requireNotNull(runtimeHostAccess.promptCheckpointStore(sessionId).get(run.taskId)) {
      "Run '${run.runId}' is paused but has no resumable checkpoint."
    }
    val promptResumeState = requireNotNull(checkpoint.promptResumeState) {
      "Run '${run.runId}' is paused but its checkpoint is missing prompt resume state."
    }
    val pendingMessageId = chatSessionStore.reserveMessageId(ChatTranscriptRole.ASSISTANT)
    runtimeHostAccess.promptCheckpointStore(sessionId).upsert(
      checkpoint.copy(
        updatedAtEpochMs = System.currentTimeMillis(),
        pendingMessageId = pendingMessageId,
        promptResumeState = promptResumeState.withAppendedUserMessage(
          RuntimeConversationMessage(
            role = RuntimeConversationRole.USER,
            content = userText.trim(),
            attachments = attachments.map(::toPromptRuntimeAttachment),
          ),
        ),
      ),
    )
    chatSessionStore.appendSubmittedTurn(
      sessionId = sessionId,
      userText = userText,
      assistantMessageId = pendingMessageId,
      assistantPlaceholderText = agentThinkingTextProvider(),
      attachments = attachments,
    )
    val resumed = runtimeSession(sessionId).requestResumeTask(
      taskId = run.taskId,
      executionKind = EXECUTION_KIND_CHECKPOINT_RESUME,
      taskMetadataUpdates = mapOf(
        AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID to pendingMessageId,
        AppAgentSessionTaskRuntimeFactory.METADATA_VISIBLE_THROUGH_MESSAGE_ID to pendingMessageId,
      ),
    )
    check(resumed) {
      "Unable to resume paused run '${run.runId}'."
    }
    return AgentRunSubmission(
      sessionId = run.sessionId,
      runId = run.runId,
      taskId = run.taskId,
      acceptedAtEpochMs = run.acceptedAtEpochMs,
      lifecycleDiagnostics = run.lifecycleDiagnostics,
    )
  }

  private fun normalizeSubmittedChatAttachment(
    attachment: OpenCrayFinalAttachment,
  ): OpenCrayFinalAttachment? {
    val relativePath = attachment.relativePath?.trim()?.takeIf(String::isNotBlank)
    val path = attachment.path?.trim()?.takeIf(String::isNotBlank)
    val artifactId = attachment.artifactId?.trim()?.takeIf(String::isNotBlank)
    val chatAttachmentId = attachment.chatAttachmentId?.trim()?.takeIf(String::isNotBlank)
    if (relativePath == null && path == null && artifactId == null && chatAttachmentId == null) {
      return null
    }
    return attachment.copy(
      kind = attachment.kind?.trim()?.takeIf(String::isNotBlank),
      relativePath = relativePath,
      path = path,
      artifactId = artifactId,
      chatAttachmentId = chatAttachmentId,
      displayName = attachment.displayName?.trim()?.takeIf(String::isNotBlank),
      mimeType = attachment.mimeType?.trim()?.takeIf(String::isNotBlank),
      transcriptText = attachment.transcriptText?.trim()?.takeIf(String::isNotBlank),
    )
  }

  private fun archiveDraftChatAttachments(
    sessionId: String,
    attachments: List<OpenCrayFinalAttachment>,
  ): List<ChatAttachmentEntry> {
    if (attachments.isEmpty()) {
      return emptyList()
    }
    val resolvedAttachments = resolveSubmittedChatAttachments(
      sessionId = sessionId,
      attachments = attachments,
    )
    val workspaceRoot = workspaceRootProvider?.invoke() ?: return emptyList()
    return AppChatAttachmentArchiver.archive(
      workspaceRoot = workspaceRoot,
      approvedReadRoots = approvedReadRootsProvider().roots,
      sessionId = sessionId,
      attachments = resolvedAttachments,
      voiceMetadataAnalyzer = voiceMetadataAnalyzer,
    )
  }

  private fun resolveSubmittedChatAttachments(
    sessionId: String,
    attachments: List<OpenCrayFinalAttachment>,
  ): List<OpenCrayFinalAttachment> {
    val resolvedArtifacts = resolveSubmittedAttachmentArtifacts(
      sessionId = sessionId,
      attachments = attachments,
    )
    val resolvedChatAttachments = resolveSubmittedChatAttachmentReferences(
      sessionId = sessionId,
      attachments = resolvedArtifacts,
    )
    val unresolvedReference = resolvedChatAttachments.firstOrNull { attachment ->
      attachment.relativePath.isNullOrBlank() &&
        attachment.path.isNullOrBlank() &&
        (
          !attachment.artifactId.isNullOrBlank() ||
            !attachment.chatAttachmentId.isNullOrBlank()
          )
    }
    if (unresolvedReference != null) {
      val referenceId = unresolvedReference.chatAttachmentId
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: unresolvedReference.artifactId
          ?.trim()
          ?.takeIf(String::isNotBlank)
        ?: "unknown"
      throw IllegalArgumentException(
        "Attachment reference '$referenceId' could not be resolved.",
      )
    }
    return resolvedChatAttachments
  }

  private fun resolveSubmittedAttachmentArtifacts(
    sessionId: String,
    attachments: List<OpenCrayFinalAttachment>,
  ): List<OpenCrayFinalAttachment> {
    val requestedArtifactIds = attachments
      .mapNotNull { attachment ->
        attachment.artifactId?.trim()?.takeIf(String::isNotBlank)
      }
      .toSet()
    if (requestedArtifactIds.isEmpty()) {
      return attachments
    }
    val artifactsById = linkedMapOf<String, OpenCrayAttachmentArtifact>()
    runtimeHostAccess.runEventJournalStore(sessionId)
      .listRuntimeEvents()
      .asReversed()
      .forEach { event ->
        val toolEvent = event as? OpenCrayToolResultEvent ?: return@forEach
        OpenCrayAttachmentArtifacts.decodeMetadata(
          json = Json,
          metadata = toolEvent.result.metadata,
        ).forEach artifactLoop@ { artifact ->
          if (artifact.artifactId !in requestedArtifactIds || artifact.artifactId in artifactsById) {
            return@artifactLoop
          }
          artifactsById[artifact.artifactId] = artifact
        }
    }
    return attachments.map { attachment ->
      val artifactId = attachment.artifactId?.trim()?.takeIf(String::isNotBlank)
        ?: return@map attachment
      val artifact = artifactsById[artifactId] ?: return@map attachment
      attachment.copy(
        kind = artifact.kindHint ?: attachment.kind,
        relativePath = artifact.relativePath,
        displayName = attachment.displayName ?: artifact.displayName,
        mimeType = attachment.mimeType ?: artifact.mimeType,
        durationMs = attachment.durationMs ?: artifact.durationMs,
        waveformBars = attachment.waveformBars.ifEmpty { artifact.waveformBars },
        transcriptText = attachment.transcriptText ?: artifact.transcriptText,
      )
    }
  }

  private fun resolveSubmittedChatAttachmentReferences(
    sessionId: String,
    attachments: List<OpenCrayFinalAttachment>,
  ): List<OpenCrayFinalAttachment> {
    val requestedAttachmentIds = attachments
      .mapNotNull { attachment ->
        if (!attachment.artifactId.isNullOrBlank()) {
          return@mapNotNull null
        }
        attachment.chatAttachmentId?.trim()?.takeIf(String::isNotBlank)
      }
      .toSet()
    if (requestedAttachmentIds.isEmpty()) {
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
              if (attachmentId.isNotEmpty() && attachmentId !in this) {
                put(attachmentId, attachment)
              }
            }
        }
    }
    return attachments.map { attachment ->
      if (!attachment.artifactId.isNullOrBlank()) {
        return@map attachment
      }
      val chatAttachmentId = attachment.chatAttachmentId?.trim()?.takeIf(String::isNotBlank)
        ?: return@map attachment
      val sessionAttachment = attachmentsById[chatAttachmentId] ?: return@map attachment
      attachment.copy(
        kind = sessionAttachment.kind.toWireKind(),
        relativePath = sessionAttachment.localPath,
        displayName = attachment.displayName ?: sessionAttachment.displayName,
        mimeType = attachment.mimeType ?: sessionAttachment.mimeType,
        durationMs = attachment.durationMs ?: sessionAttachment.durationMs,
        waveformBars = attachment.waveformBars.ifEmpty { sessionAttachment.waveformBars },
        transcriptText = attachment.transcriptText ?: sessionAttachment.transcriptText,
      )
    }
  }

  private fun isLlmRetryPausedAwaitingResumeRun(run: AgentRunSnapshot): Boolean =
    chatRunIsLlmRetryPausedAwaitingResume(run)

  private fun isDeferredApprovalDecisionAwaitingResumeRun(run: AgentRunSnapshot): Boolean =
    chatRunIsDeferredApprovalAwaitingResume(
      run = run,
      runtimeHostAccess = runtimeHostAccess,
    )

  private fun runBlocksMidLoopSupplements(run: AgentRunSnapshot): Boolean =
    chatRunBlocksMidLoopSupplements(run)

  private fun isApprovalRequiredError(errorCode: String?): Boolean =
    errorCode == CHAT_ERROR_APPROVAL_REQUIRED ||
      errorCode == CHAT_ERROR_HIGH_RISK_APPROVAL_REQUIRED

  private fun pendingTaskCount(sessionId: String): Int = runtimeSession(sessionId)
    .listRuns()
    .count { run -> !run.isTerminal }

  private fun appendRunSupplement(
    sessionId: String,
    run: AgentRunSnapshot,
    text: String,
  ) {
    supplementStore(sessionId).append(
      runId = run.runId,
      taskId = run.taskId,
      text = text,
    )
  }

  fun submitPromptRun(
    sessionId: String,
    userText: String,
    attachments: List<ChatAttachmentEntry>,
  ): AgentRunSubmission {
    val handle = runtimeSession(sessionId)
    val pendingMessageId = chatSessionStore.reserveMessageId(ChatTranscriptRole.ASSISTANT)
    val submittedRun = handle.submitPrompt(
      userText = ChatRuntimeTextFormatter.format(
        text = userText,
        commandLabel = null,
        attachments = attachments,
      ),
      pendingMessageId = pendingMessageId,
      visibleThroughMessageId = pendingMessageId,
      policyDecision = PolicyDecision(
        outcome = PolicyDecisionOutcome.ALLOW,
        reasonCode = "FLUTTER_CHAT_ALLOW",
      ),
      metadata = taskSafetyMetadataProvider() +
        promptRuntimeMetadata(
          userText = userText,
          attachments = attachments,
        ) +
        taskMetadataProvider(RunSubmissionSources.CHAT_USER_MESSAGE),
    )
    chatFlowDebug(
      "chat.submitPromptRun session=$sessionId run=${submittedRun.runId} task=${submittedRun.taskId} pending=$pendingMessageId textLen=${userText.length} attachments=${attachments.size}",
    )
    try {
      chatSessionStore.appendSubmittedTurn(
        sessionId = sessionId,
        userText = userText,
        assistantMessageId = pendingMessageId,
        assistantPlaceholderText = agentThinkingTextProvider(),
        attachments = attachments,
      )
    } catch (throwable: Throwable) {
      handle.requestCancel(submittedRun.taskId)
      throw throwable
    }
    handle.ensureProcessing()
    return submittedRun
  }

  private fun promptRuntimeMetadata(
    userText: String,
    attachments: List<ChatAttachmentEntry>,
  ): Map<String, String> = buildMap {
    put(AppAgentSessionTaskRuntimeFactory.METADATA_PROMPT_USER_TEXT, userText.trim())
    if (attachments.isNotEmpty()) {
      val runtimeAttachments = attachments.map(::toPromptRuntimeAttachment)
      if (runtimeAttachments.isNotEmpty()) {
        put(
          AppAgentSessionTaskRuntimeFactory.METADATA_PROMPT_RUNTIME_ATTACHMENTS_JSON,
          Json.encodeToString(
            ListSerializer(RuntimeConversationAttachment.serializer()),
            runtimeAttachments,
          ),
        )
      }
    }
  }

  private fun toPromptRuntimeAttachment(
    attachment: ChatAttachmentEntry,
  ): RuntimeConversationAttachment = RuntimeConversationAttachment(
    attachmentId = attachment.attachmentId,
    kind = attachment.kind.toRuntimeConversationAttachmentKind(),
    displayName = attachment.displayName,
    filePath = resolvedPromptAttachmentPath(attachment.localPath)
      ?.toString()
      ?.replace('\\', '/'),
    mimeType = attachment.mimeType?.trim()?.takeIf(String::isNotBlank),
    transcriptText = attachment.transcriptText?.trim()?.takeIf(String::isNotBlank),
  )

  private fun resolvedPromptAttachmentPath(localPath: String): Path? {
    val normalizedLocalPath = localPath.trim().takeIf(String::isNotBlank) ?: return null
    val candidate = runCatching { Path.of(normalizedLocalPath) }.getOrNull() ?: return null
    val resolved = if (candidate.isAbsolute) {
      candidate
    } else {
      val workspaceRoot = workspaceRootProvider?.invoke() ?: return null
      workspaceRoot.resolve(candidate)
    }.toAbsolutePath().normalize()
    return resolved.takeIf { path -> Files.exists(path) && Files.isRegularFile(path) }
  }

  private fun ChatAttachmentKind.toRuntimeConversationAttachmentKind():
    RuntimeConversationAttachmentKind = when (this) {
    ChatAttachmentKind.IMAGE -> RuntimeConversationAttachmentKind.IMAGE
    ChatAttachmentKind.VOICE -> RuntimeConversationAttachmentKind.VOICE
    ChatAttachmentKind.AUDIO -> RuntimeConversationAttachmentKind.AUDIO
    ChatAttachmentKind.FILE -> RuntimeConversationAttachmentKind.FILE
  }

  private fun ChatAttachmentKind.toWireKind(): String = when (this) {
    ChatAttachmentKind.IMAGE -> "image"
    ChatAttachmentKind.VOICE -> "voice"
    ChatAttachmentKind.AUDIO -> "audio"
    ChatAttachmentKind.FILE -> "file"
  }

  private fun hasSession(sessionId: String): Boolean = chatSessionStore.loadState().sessions
    .any { session -> session.sessionId == sessionId }

  private fun requiresExplicitRetryAfterRestore(sessionId: String): Boolean =
    latestChatRunForSnapshot(runtimeSession(sessionId).listRuns())
      ?.let { run -> !run.isActive && chatRunIsInterruptedOnRestore(run) }
      ?: false

  private fun runSubmissionToMap(submission: AgentRunSubmission): Map<String, Any?> = mapOf(
    "sessionId" to submission.sessionId,
    "runId" to submission.runId,
    "taskId" to submission.taskId,
    "acceptedAtEpochMs" to submission.acceptedAtEpochMs,
    "diagnostics" to submission.lifecycleDiagnostics.toMap(),
  )

  private fun runtimeSession(sessionId: String): OpenCrayRuntimeSessionAccess =
    runtimeHostAccess.session(sessionId)

  private fun supplementStore(sessionId: String): SessionSupplementStore =
    runtimeHostAccess.supplementStore(sessionId)

}

internal class ServiceOwnedChatSubmissionAccess(
  chatSessionStore: ChatSessionLocalStore,
  runtimeHostAccess: OpenCrayRuntimeHostAccess,
  safetySettingsFacade: SafetySettingsFacade,
  workspaceRootProvider: (() -> Path)?,
  approvedReadRootsProvider: () -> ApprovedReadRootsSnapshot = {
    ApprovedReadRootsSnapshot(
      roots = emptySet(),
      summary = "workspace=unavailable",
    )
  },
  voiceMetadataAnalyzer: AppAgentWorkspaceVoiceMetadataAnalyzer =
    AppAgentWorkspaceVoiceMetadataAnalyzer { _, _ -> null },
  agentThinkingText: String = "Thinking",
) {
  private val lock = Any()
  private val coordinator = ChatSubmissionCoordinator(
    chatSessionStore = chatSessionStore,
    runtimeHostAccess = runtimeHostAccess,
    taskSafetyMetadataProvider = {
      buildTaskSafetyMetadata(
        snapshot = safetySettingsFacade.load(),
        approvedReadRoots = approvedReadRootsProvider(),
      )
    },
    taskMetadataProvider = { submissionSource ->
      runtimeHostAccess.lifecycleDescriptor.taskMetadata(
        submissionSource = submissionSource,
      )
    },
    workspaceRootProvider = workspaceRootProvider,
    approvedReadRootsProvider = approvedReadRootsProvider,
    voiceMetadataAnalyzer = voiceMetadataAnalyzer,
    agentThinkingTextProvider = { agentThinkingText },
  )

  fun submitChatMessage(
    text: String,
    attachments: List<OpenCrayFinalAttachment>,
  ): ServiceOwnedChatSubmissionResult = synchronized(lock) {
    val result = coordinator.submitChatMessage(text, attachments)
    ServiceOwnedChatSubmissionResult(
      payload = result.submission?.let(::runSubmissionToMap),
      didMutate = result.didMutate,
    )
  }

  private fun runSubmissionToMap(submission: AgentRunSubmission): Map<String, Any?> = mapOf(
    "sessionId" to submission.sessionId,
    "runId" to submission.runId,
    "taskId" to submission.taskId,
    "acceptedAtEpochMs" to submission.acceptedAtEpochMs,
    "diagnostics" to submission.lifecycleDiagnostics.toMap(),
  )
}
