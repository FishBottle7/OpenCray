package com.opencray.runtime.subagent

import com.opencray.runtime.OpenCrayPromptCheckpointBoundary
import com.opencray.runtime.OpenCrayPromptResumeState
import kotlinx.serialization.Serializable

@Serializable
data class SubAgentHandleState(
  val agentId: String,
  val childSessionId: String = newSubAgentChildSessionId(agentId),
  val childRunId: String,
  val childTaskId: String,
  val description: String,
  val prompt: String,
  val supplementalInputs: List<String> = emptyList(),
  val mailbox: SubAgentMailbox = SubAgentMailbox(),
  val subagentType: String,
  val contextMode: String,
  val contextModeSource: String = SubAgentContextModeResolutionSource.PROFILE_DEFAULT.wireValue,
  val parentRunId: String,
  val parentTaskId: String,
  val parentTurn: Int,
  val depth: Int,
  val activeSkillName: String? = null,
  val activeSkillActivationSource: String? = null,
  val activeSkillPinned: Boolean = false,
  val snapshot: SubAgentExecutionSnapshot,
  val pendingApprovalResume: SubAgentApprovalResume? = null,
  val pendingApprovalDecision: SubAgentPendingApprovalDecision? = null,
  val childPromptResumeState: OpenCrayPromptResumeState? = null,
  val childPromptCheckpointBoundary: OpenCrayPromptCheckpointBoundary? = null,
  val childPromptCheckpointAtEpochMs: Long? = null,
  val childLiveContext: SubAgentLiveContextSnapshot = SubAgentLiveContextSnapshot(),
  val childExecutionStatus: String? = null,
  val childTurnCount: Int? = null,
  val childToolCallCount: Int? = null,
  val createdAtEpochMs: Long,
  val updatedAtEpochMs: Long,
) {
  init {
    require(agentId.isNotBlank()) { "SubAgentHandleState agentId must not be blank." }
    require(childSessionId.isNotBlank()) { "SubAgentHandleState childSessionId must not be blank." }
    require(childRunId.isNotBlank()) { "SubAgentHandleState childRunId must not be blank." }
    require(childTaskId.isNotBlank()) { "SubAgentHandleState childTaskId must not be blank." }
    require(description.isNotBlank()) { "SubAgentHandleState description must not be blank." }
    require(prompt.isNotBlank()) { "SubAgentHandleState prompt must not be blank." }
    require(subagentType.isNotBlank()) { "SubAgentHandleState subagentType must not be blank." }
    require(contextMode.isNotBlank()) { "SubAgentHandleState contextMode must not be blank." }
    require(contextModeSource.isNotBlank()) {
      "SubAgentHandleState contextModeSource must not be blank."
    }
    require(parentRunId.isNotBlank()) { "SubAgentHandleState parentRunId must not be blank." }
    require(parentTaskId.isNotBlank()) { "SubAgentHandleState parentTaskId must not be blank." }
    require(parentTurn >= 0) { "SubAgentHandleState parentTurn must be >= 0." }
    require(depth >= 1) { "SubAgentHandleState depth must be >= 1." }
    require(childPromptCheckpointAtEpochMs == null || childPromptCheckpointAtEpochMs >= 0L) {
      "SubAgentHandleState childPromptCheckpointAtEpochMs must be >= 0 when present."
    }
    require(createdAtEpochMs >= 0) { "SubAgentHandleState createdAtEpochMs must be >= 0." }
    require(updatedAtEpochMs >= 0) { "SubAgentHandleState updatedAtEpochMs must be >= 0." }
  }

  val handleId: String
    get() = agentId

  fun isTerminalWithoutPendingApprovalResume(): Boolean =
    snapshot.state.isTerminal() && pendingApprovalResume == null

  fun isDetachedBackgroundQueued(): Boolean =
    snapshot.state == SubAgentExecutionState.BACKGROUND_QUEUED &&
      pendingApprovalResume == null

  fun shouldEnsureDetachedBackgroundExecution(): Boolean =
    pendingApprovalDecision != null || isDetachedBackgroundQueued()

  fun hasLiveBackgroundExecution(): Boolean = when (snapshot.state) {
    SubAgentExecutionState.BACKGROUND_QUEUED,
    SubAgentExecutionState.BACKGROUND_RUNNING,
    -> true

    else -> false
  }

  fun canAcceptMailboxInput(): Boolean = when (snapshot.state) {
    SubAgentExecutionState.BACKGROUND_QUEUED,
    SubAgentExecutionState.BACKGROUND_RUNNING,
    -> true

    SubAgentExecutionState.WAITING_APPROVAL,
    SubAgentExecutionState.WAITING_HIGH_RISK_APPROVAL,
    -> pendingApprovalResume != null

    else -> false
  }

  fun canContinueDetachedExecution(
    hasApprovalContinuation: Boolean,
  ): Boolean {
    if (isTerminalWithoutPendingApprovalResume()) {
      return false
    }
    if (pendingApprovalResume != null && !hasApprovalContinuation) {
      return false
    }
    return true
  }

  fun normalizedMailbox(): SubAgentMailbox = when {
    mailbox.messages.isNotEmpty() -> mailbox
    supplementalInputs.isEmpty() -> mailbox
    else -> {
      val legacyMessages = supplementalInputs.mapIndexed { index, input ->
        SubAgentMailboxMessage(
          messageId = "legacy-$agentId-${index + 1}",
          text = input.trim(),
          createdAtEpochMs = createdAtEpochMs + index,
        )
      }
      SubAgentMailbox(
        messages = legacyMessages,
        lastDeliveredMessageId = if (
          legacyMessages.isNotEmpty() &&
          (
            childPromptResumeState != null ||
              pendingApprovalResume != null ||
              snapshot.state != SubAgentExecutionState.BACKGROUND_QUEUED
            )
        ) {
          legacyMessages.last().messageId
        } else {
          null
        },
      )
    }
  }

  fun withNormalizedMailbox(
    updatedAtEpochMs: Long = this.updatedAtEpochMs,
  ): SubAgentHandleState {
    val normalizedMailbox = normalizedMailbox()
    if (supplementalInputs.isEmpty() && normalizedMailbox == mailbox) {
      return this
    }
    return copy(
      supplementalInputs = emptyList(),
      mailbox = normalizedMailbox,
      updatedAtEpochMs = maxOf(this.updatedAtEpochMs, updatedAtEpochMs),
    )
  }

  fun withQueuedMailboxInput(
    messageId: String,
    message: String,
    createdAtEpochMs: Long,
  ): SubAgentHandleState {
    val normalized = withNormalizedMailbox()
    return normalized.copy(
      supplementalInputs = emptyList(),
      mailbox = normalized.mailbox.enqueue(
        SubAgentMailboxMessage(
          messageId = messageId,
          text = message.trim(),
          createdAtEpochMs = createdAtEpochMs,
        ),
      ),
      updatedAtEpochMs = maxOf(normalized.updatedAtEpochMs, createdAtEpochMs),
    )
  }

  fun effectivePrompt(
    mailboxMessages: List<SubAgentMailboxMessage> = normalizedMailbox().messages,
  ): String {
    val normalizedPrompt = prompt.trim()
    val supplements = mailboxMessages
      .map(SubAgentMailboxMessage::text)
      .map(String::trim)
      .filter(String::isNotBlank)
    if (supplements.isEmpty()) {
      return normalizedPrompt
    }
    return buildString {
      append(normalizedPrompt)
      supplements.forEachIndexed { index, input ->
        appendLine()
        appendLine()
        appendLine("[Additional parent input ${index + 1}]")
        append(input)
      }
    }.trim()
  }

  fun resolvedContextMode(): SubAgentContextMode = requireNotNull(
    SubAgentContextMode.fromWireValue(contextMode),
  ) {
    "Unknown subagent context mode '$contextMode'."
  }

  fun resolvedContextModeSource(): SubAgentContextModeResolutionSource =
    SubAgentContextModeResolutionSource.fromWireValue(contextModeSource)
      ?: SubAgentContextModeResolutionSource.PROFILE_DEFAULT

  fun toTask(
    includeMailboxMessagesInPrompt: Boolean = true,
  ): SubAgentTask = SubAgentTask(
    description = description,
    prompt = if (includeMailboxMessagesInPrompt) {
      effectivePrompt()
    } else {
      prompt.trim()
    },
    subagentType = subagentType,
    contextMode = resolvedContextMode(),
    contextModeSource = resolvedContextModeSource(),
    parentRunId = parentRunId,
    parentTaskId = parentTaskId,
    parentTurn = parentTurn,
    depth = depth,
    activeSkillName = activeSkillName,
    activeSkillActivationSource = activeSkillActivationSource,
    activeSkillPinned = activeSkillPinned,
  )

  companion object {
    fun queued(
      agentId: String,
      childSessionId: String = newSubAgentChildSessionId(agentId),
      childRunId: String,
      childTaskId: String,
      description: String,
      prompt: String,
      subagentType: String,
      contextMode: String,
      contextModeSource: String = SubAgentContextModeResolutionSource.PROFILE_DEFAULT.wireValue,
      parentRunId: String,
      parentTaskId: String,
      parentTurn: Int,
      depth: Int,
      activeSkillName: String?,
      activeSkillActivationSource: String?,
      activeSkillPinned: Boolean = false,
      createdAtEpochMs: Long,
    ): SubAgentHandleState = SubAgentHandleState(
      agentId = agentId,
      childSessionId = childSessionId,
      childRunId = childRunId,
      childTaskId = childTaskId,
      description = description,
      prompt = prompt,
      subagentType = subagentType,
      contextMode = contextMode,
      contextModeSource = contextModeSource,
      parentRunId = parentRunId,
      parentTaskId = parentTaskId,
      parentTurn = parentTurn,
      depth = depth,
      activeSkillName = activeSkillName,
      activeSkillActivationSource = activeSkillActivationSource,
      activeSkillPinned = activeSkillPinned,
      snapshot = SubAgentExecutionSnapshot.backgroundQueued(
        headline = "Queued delegated child run '$description'.",
      ),
      createdAtEpochMs = createdAtEpochMs,
      updatedAtEpochMs = createdAtEpochMs,
    )
  }
}

internal fun newSubAgentChildSessionId(
  agentId: String,
): String = "child-session-${agentId.trim()}"
