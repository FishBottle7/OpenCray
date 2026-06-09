package com.opencray.app

import com.opencray.persistence.model.ChatTranscriptRole
import com.opencray.runtime.OpenCrayApprovalEvent
import com.opencray.runtime.OpenCrayApprovalPhase
import com.opencray.runtime.OpenCraySubAgentEvent

internal data class ApprovalDecisionSubject(
  val sessionId: String,
  val supportsSessionApproval: Boolean,
  val decisionRecord: ApprovalDecisionRecord,
) {
  val runId: String
    get() = decisionRecord.runId

  val taskId: String
    get() = decisionRecord.taskId

  val pendingMessageId: String?
    get() = decisionRecord.pendingMessageId
}

internal data class ApprovalDecisionStrings(
  val agentThinking: String,
  val approvalApproved: String,
  val approvalApprovedForSession: String,
  val approvalRejected: String,
  val deferredApprovalApproved: String,
  val deferredApprovalApprovedForSession: String,
  val deferredApprovalRejected: String,
  val delegatedChildApprovalApprovedSummary: String,
  val delegatedChildApprovalApprovedText: String,
  val delegatedChildApprovalRejectedSummary: String,
)

internal class ChatApprovalDecisionCoordinator<TApproval>(
  private val resolveApproval: (String) -> TApproval?,
  private val approvalSubject: (TApproval) -> ApprovalDecisionSubject,
  private val shouldDeferDecisionUntilManualResume: (TApproval) -> Boolean,
  private val submitDetachedApprovedRecovery: (TApproval, Long) -> Boolean = { _, _ -> false },
  private val markApprovalApproved: (ApprovalDecisionSubject) -> Unit,
  private val markApprovalRejected: (ApprovalDecisionSubject) -> Unit,
  private val clearApproval: (String, String) -> Unit,
  private val upsertCheckpoint: (String, PersistedPromptCheckpoint) -> Unit,
  private val removeCheckpoint: (String, String) -> Unit,
  private val requestResumeTask: (String, String) -> Boolean,
  private val requestCancel: (String, String) -> Boolean,
  private val recordApprovalApprovedReplay: (ApprovalDecisionSubject) -> Unit,
  private val recordApprovalRejectedReplay: (ApprovalDecisionSubject) -> Unit,
  private val recordApprovalResultEvent: (String, OpenCrayApprovalEvent) -> Unit,
  private val recordSubAgentEvent: (String, OpenCraySubAgentEvent) -> Unit = { _, _ -> },
  private val clearPendingApproval: (String, String) -> Unit = { _, _ -> },
  private val setSessionApprovalGranted: (String, Boolean) -> Unit = { _, _ -> },
  private val replacePendingMessageWithThinking: (String, String, String) -> Unit = { _, _, _ -> },
  private val appendToolMessage: (String, String) -> Unit,
  private val stringsProvider: () -> ApprovalDecisionStrings,
  private val nowEpochMsProvider: () -> Long = System::currentTimeMillis,
) {
  fun approve(taskIdOrRunId: String) {
    approveInternal(
      taskIdOrRunId = taskIdOrRunId,
      sessionScoped = false,
    )
  }

  fun approveForSession(taskIdOrRunId: String) {
    approveInternal(
      taskIdOrRunId = taskIdOrRunId,
      sessionScoped = true,
    )
  }

  fun reject(taskIdOrRunId: String) {
    val approval = resolveApproval(taskIdOrRunId)
      ?: error("Pending approval '$taskIdOrRunId' is unavailable.")
    val subject = approvalSubject(approval)
    val strings = stringsProvider()
    val nowEpochMs = nowEpochMsProvider()
    val deferUntilManualResume = shouldDeferDecisionUntilManualResume(approval)
    if (!deferUntilManualResume) {
      val cancelled = requestCancel(subject.sessionId, subject.taskId)
      check(cancelled) {
        "Unable to stop pending approval '$taskIdOrRunId' after rejection."
      }
    }
    recordApprovalRejectedReplay(subject)
    if (!deferUntilManualResume) {
      markApprovalRejected(subject)
      clearApproval(subject.sessionId, subject.taskId)
      removeCheckpoint(subject.sessionId, subject.taskId)
      subject.decisionRecord.subAgentTerminalEvent(
        summary = strings.delegatedChildApprovalRejectedSummary,
        emittedAtEpochMs = nowEpochMs,
      )?.let { event ->
        recordSubAgentEvent(subject.sessionId, event)
      }
    } else {
      upsertCheckpoint(
        subject.sessionId,
        subject.decisionRecord.decisionCheckpoint(
          sessionId = subject.sessionId,
          checkpointKind = PromptCheckpointKind.REJECTED_PENDING_RESUME,
          nowEpochMs = nowEpochMs,
        ),
      )
    }
    clearPendingApproval(subject.sessionId, subject.taskId)
    val text = if (deferUntilManualResume) {
      strings.deferredApprovalRejected
    } else {
      strings.approvalRejected
    }
    recordApprovalResultEvent(
      subject.sessionId,
      subject.decisionRecord.resultEvent(
        phase = OpenCrayApprovalPhase.REJECTED,
        emittedAtEpochMs = nowEpochMs,
        text = text,
      ),
    )
    if (!deferUntilManualResume) {
      subject.pendingMessageId?.let { pendingMessageId ->
        replacePendingMessageWithThinking(
          subject.sessionId,
          pendingMessageId,
          strings.agentThinking,
        )
      }
    }
    appendToolMessage(subject.sessionId, text)
  }

  private fun approveInternal(
    taskIdOrRunId: String,
    sessionScoped: Boolean,
  ) {
    val approval = resolveApproval(taskIdOrRunId)
      ?: error("Pending approval '$taskIdOrRunId' is unavailable.")
    val subject = approvalSubject(approval)
    require(!sessionScoped || subject.supportsSessionApproval) {
      "Pending approval '$taskIdOrRunId' does not support session approval."
    }
    val strings = stringsProvider()
    val nowEpochMs = nowEpochMsProvider()
    val deferUntilManualResume = shouldDeferDecisionUntilManualResume(approval)
    if (sessionScoped) {
      setSessionApprovalGranted(subject.sessionId, true)
    }
    val detachedChildResumed = if (!deferUntilManualResume) {
      submitDetachedApprovedRecovery(approval, nowEpochMs)
    } else {
      false
    }
    if (!deferUntilManualResume && !detachedChildResumed) {
      markApprovalApproved(subject)
    }
    if (!detachedChildResumed) {
      upsertCheckpoint(
        subject.sessionId,
        subject.decisionRecord.decisionCheckpoint(
          sessionId = subject.sessionId,
          checkpointKind = PromptCheckpointKind.APPROVED_PENDING_RESUME,
          nowEpochMs = nowEpochMs,
        ),
      )
    }
    if (!deferUntilManualResume && !detachedChildResumed) {
      val resumed = requestResumeTask(subject.sessionId, subject.taskId)
      if (!resumed) {
        if (sessionScoped) {
          setSessionApprovalGranted(subject.sessionId, false)
        }
        clearApproval(subject.sessionId, subject.taskId)
        removeCheckpoint(subject.sessionId, subject.taskId)
        error("Unable to resume pending approval '$taskIdOrRunId'.")
      }
    }
    recordApprovalApprovedReplay(subject)
    clearPendingApproval(subject.sessionId, subject.taskId)
    if (!deferUntilManualResume) {
      subject.decisionRecord.subAgentResumedEvent(
        summary = strings.delegatedChildApprovalApprovedSummary,
        emittedAtEpochMs = nowEpochMs,
      )?.let { event ->
        recordSubAgentEvent(subject.sessionId, event)
      }
    }
    val text = when {
      deferUntilManualResume && sessionScoped -> strings.deferredApprovalApprovedForSession
      deferUntilManualResume -> strings.deferredApprovalApproved
      detachedChildResumed -> strings.delegatedChildApprovalApprovedText
      sessionScoped -> strings.approvalApprovedForSession
      else -> strings.approvalApproved
    }
    recordApprovalResultEvent(
      subject.sessionId,
      subject.decisionRecord.resultEvent(
        phase = OpenCrayApprovalPhase.APPROVED,
        emittedAtEpochMs = nowEpochMs,
        text = text,
      ),
    )
    if (!deferUntilManualResume && !detachedChildResumed) {
      subject.pendingMessageId?.let { pendingMessageId ->
        replacePendingMessageWithThinking(
          subject.sessionId,
          pendingMessageId,
          strings.agentThinking,
        )
      }
    }
    appendToolMessage(subject.sessionId, text)
  }
}

internal fun appendApprovalToolMessage(
  chatSessionStore: ChatSessionLocalStore,
  sessionId: String,
  text: String,
) {
  chatSessionStore.appendMessage(
    sessionId = sessionId,
    role = ChatTranscriptRole.TOOL,
    text = text,
  )
}
