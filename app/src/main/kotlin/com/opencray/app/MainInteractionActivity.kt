package com.opencray.app

import android.app.Activity
import android.os.Bundle
import com.opencray.ui.chat.ApprovalDecision
import com.opencray.ui.chat.ApprovalPromptState
import com.opencray.ui.chat.ApprovalPromptStatus
import com.opencray.ui.chat.ChatMode
import com.opencray.ui.chat.ChatScreen
import com.opencray.ui.chat.ChatScreenState
import com.opencray.ui.chat.ConversationHeaderState
import com.opencray.ui.chat.ModeState
import com.opencray.ui.timeline.ActionApprovalState
import com.opencray.ui.timeline.ActionPolicyDecision
import com.opencray.ui.timeline.ActionResultStatus
import com.opencray.ui.timeline.ActionTimelineItem
import java.util.Locale

class MainInteractionActivity : Activity(), ChatScreen.Listener {
  companion object {
    const val EXTRA_SCENARIO = "com.opencray.app.MainInteractionActivity.extra.SCENARIO"
    const val SCENARIO_DEFAULT_APPROVAL = "default_approval"
    const val SCENARIO_DENIED_POLICY = "denied_policy"
  }

  private lateinit var chatScreen: ChatScreen
  private var seedScenario: SeedScenario = SeedScenario.DEFAULT_APPROVAL
  private var selectedMode: ChatMode = ChatMode.SAFE
  private var isQueueVisible: Boolean = true
  private var approvalOutcome: ApprovalOutcome = ApprovalOutcome.PENDING

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    title = "Main Interaction"
    seedScenario = scenarioFromIntent()
    resetToSeededState()

    chatScreen = ChatScreen(context = this).apply {
      setListener(this@MainInteractionActivity)
      submitState(buildChatState())
    }
    setContentView(chatScreen)
  }

  override fun onQueueVisibilityChanged(isVisible: Boolean) {
    isQueueVisible = isVisible
    renderState()
  }

  override fun onModeSelected(mode: ChatMode) {
    selectedMode = mode
    renderState()
  }

  override fun onApprovalDecision(decision: ApprovalDecision) {
    approvalOutcome = when (decision) {
      ApprovalDecision.APPROVE -> ApprovalOutcome.APPROVED
      ApprovalDecision.DENY -> ApprovalOutcome.DENIED
    }
    renderState()
  }

  override fun onResetAgentIdentity() {
    resetToSeededState()
    renderState()
  }

  private fun renderState() {
    if (::chatScreen.isInitialized) {
      chatScreen.submitState(buildChatState())
    }
  }

  private fun resetToSeededState() {
    selectedMode = ChatMode.SAFE
    isQueueVisible = true
    approvalOutcome = when (seedScenario) {
      SeedScenario.DEFAULT_APPROVAL -> ApprovalOutcome.PENDING
      SeedScenario.DENIED_POLICY -> ApprovalOutcome.POLICY_DENIED
    }
  }

  private fun scenarioFromIntent(): SeedScenario = when (
    intent.getStringExtra(EXTRA_SCENARIO)
      ?.trim()
      ?.lowercase(Locale.ROOT)
  ) {
    SCENARIO_DENIED_POLICY -> SeedScenario.DENIED_POLICY
    SCENARIO_DEFAULT_APPROVAL -> SeedScenario.DEFAULT_APPROVAL
    else -> SeedScenario.DEFAULT_APPROVAL
  }

  private fun buildChatState(): ChatScreenState = ChatScreenState(
    headerState = ConversationHeaderState(
      title = "OpenCray Chat",
      subtitle = headerSubtitle(),
      queuedActionCount = queuedActionCount(),
      isQueueVisible = isQueueVisible,
    ),
    modeState = ModeState(
      selectedMode = selectedMode,
    ),
    approvalPromptState = approvalPromptState(),
    conversationLines = conversationLines(),
    timelineItems = timelineItems(),
  )

  private fun queuedActionCount(): Int = when (approvalOutcome) {
    ApprovalOutcome.PENDING -> 1
    ApprovalOutcome.APPROVED, ApprovalOutcome.DENIED, ApprovalOutcome.POLICY_DENIED -> 0
  }

  private fun headerSubtitle(): String = when (approvalOutcome) {
    ApprovalOutcome.PENDING -> when (selectedMode) {
      ChatMode.SAFE -> "Safe mode is holding one queued workspace write for explicit approval."
      ChatMode.AUTO -> "Auto mode would normally keep moving, but this seeded request stays paused so approval behavior remains visible."
      ChatMode.DEVELOPER -> "Developer mode exposes the queued write directly, while this seeded request still waits for a deliberate decision."
    }

    ApprovalOutcome.APPROVED -> when (selectedMode) {
      ChatMode.SAFE -> "Safe mode recorded the approval and completed the queued write in a visible success state."
      ChatMode.AUTO -> "Auto mode now reflects a completed write, with the approval result left visible for deterministic follow-up."
      ChatMode.DEVELOPER -> "Developer mode shows that the queued write was approved and completed without any async delay."
    }

    ApprovalOutcome.DENIED -> when (selectedMode) {
      ChatMode.SAFE -> "Safe mode kept the workspace unchanged and left the denial reason visible for transparency."
      ChatMode.AUTO -> "Auto mode stays blocked here because the user denied the queued write and the workspace must remain unchanged."
      ChatMode.DEVELOPER -> "Developer mode surfaces the denial outcome explicitly so later requests inherit a blocked-write mental model."
    }

    ApprovalOutcome.POLICY_DENIED -> when (selectedMode) {
      ChatMode.SAFE -> "Safe mode surfaces the request, but protected-policy denial still blocks the write before approval can continue."
      ChatMode.AUTO -> "Auto mode can reduce interruptions, but protected-policy denial still wins before any sensitive write runs."
      ChatMode.DEVELOPER -> "Developer mode exposes the blocked request clearly, yet protected-policy denial still overrides the write."
    }
  }

  private fun approvalPromptState(): ApprovalPromptState = when (approvalOutcome) {
    ApprovalOutcome.PENDING -> ApprovalPromptState(
      status = ApprovalPromptStatus.REQUIRED,
      title = "Approval required",
      message = when (selectedMode) {
        ChatMode.SAFE -> "Allow the queued workspace write so the prepared summary update can be applied."
        ChatMode.AUTO -> "This seeded Auto-mode request stays paused on purpose so the approve path remains observable in tests and demos."
        ChatMode.DEVELOPER -> "Developer mode keeps the prepared write visible; approve it to complete the change deterministically."
      },
      approveLabel = "Approve write",
      denyLabel = "Keep blocked",
    )

    ApprovalOutcome.APPROVED -> ApprovalPromptState(
      status = ApprovalPromptStatus.APPROVED,
      title = "Write approved",
      message = "The queued workspace write was marked successful immediately after approval so the progression stays deterministic.",
      decisionNote = "Approved explicitly, then advanced to a visible success state with no hidden async work.",
      approveLabel = "Approve write",
      denyLabel = "Keep blocked",
    )

    ApprovalOutcome.DENIED -> ApprovalPromptState(
      status = ApprovalPromptStatus.DENIED,
      title = "Write denied",
      message = "The workspace write remains blocked and the denial reason stays visible so the next request inherits the same safety context.",
      decisionNote = "Denied explicitly to keep the workspace unchanged. Reason: user chose to keep the queued write blocked.",
      approveLabel = "Approve write",
      denyLabel = "Keep blocked",
    )

    ApprovalOutcome.POLICY_DENIED -> ApprovalPromptState(
      status = ApprovalPromptStatus.DENIED,
      title = "Blocked by policy",
      message = "This alternate scenario seeds a protected-file denial before approval can open, which makes the denial path easy to cover in androidTest later.",
      decisionNote = "Denied transparently because protected-policy rules block the write before execution.",
      approveLabel = "Approve write",
      denyLabel = "Keep blocked",
    )
  }

  private fun conversationLines(): List<String> = listOf(
    "User: Prepare the summary update for the workspace.",
    when (approvalOutcome) {
      ApprovalOutcome.PENDING -> "Agent: ${selectedMode.displayName} mode drafted the change and paused the final write for your review."
      ApprovalOutcome.APPROVED -> "Agent: ${selectedMode.displayName} mode recorded the approval and marked the final write as successful."
      ApprovalOutcome.DENIED -> "Agent: ${selectedMode.displayName} mode kept the write blocked and preserved the denial reason in the visible state."
      ApprovalOutcome.POLICY_DENIED -> "Agent: ${selectedMode.displayName} mode surfaced the request, but protected-policy denial blocked the write before approval was needed."
    },
    "System: Subsequent requests now follow ${selectedMode.displayName.lowercase(Locale.ROOT)} mode — ${selectedMode.helperText}",
  )

  private fun timelineItems(): List<ActionTimelineItem> = buildList {
    add(
      ActionTimelineItem(
        sequenceNumber = 1,
        operationLabel = "Review requested summary change",
        policyDecision = ActionPolicyDecision.ALLOW,
        resultStatus = ActionResultStatus.SUCCESS,
        reasonText = "Read-only analysis completed immediately so the visible state can focus on the write decision.",
        approvalState = ActionApprovalState.NOT_REQUIRED,
      ),
    )
    add(
      ActionTimelineItem(
        sequenceNumber = 2,
        operationLabel = "Draft workspace patch",
        policyDecision = ActionPolicyDecision.ALLOW,
        resultStatus = ActionResultStatus.SUCCESS,
        reasonText = "The patch was prepared in memory first, which keeps the final write decision deterministic and easy to inspect.",
        approvalState = ActionApprovalState.NOT_REQUIRED,
      ),
    )

    when (approvalOutcome) {
      ApprovalOutcome.PENDING -> Unit

      ApprovalOutcome.APPROVED -> add(
        ActionTimelineItem(
          sequenceNumber = 3,
          operationLabel = "Apply workspace write",
          policyDecision = ActionPolicyDecision.ASK,
          resultStatus = ActionResultStatus.SUCCESS,
          reasonText = "Approval was granted, so the prepared write advanced immediately to a visible success state.",
          approvalState = ActionApprovalState.GRANTED,
        ),
      )

      ApprovalOutcome.DENIED -> add(
        ActionTimelineItem(
          sequenceNumber = 3,
          operationLabel = "Apply workspace write",
          policyDecision = ActionPolicyDecision.DENY,
          resultStatus = ActionResultStatus.CANCELLED,
          reasonText = "Denied explicitly to keep the workspace unchanged. Transparency note: the user chose to keep the queued write blocked.",
          approvalState = ActionApprovalState.REQUIRED,
        ),
      )

      ApprovalOutcome.POLICY_DENIED -> add(
        ActionTimelineItem(
          sequenceNumber = 3,
          operationLabel = "Apply protected workspace write",
          policyDecision = ActionPolicyDecision.DENY,
          resultStatus = ActionResultStatus.FAILED,
          reasonText = "Protected-policy rules denied the write before approval because this seeded scenario targets a blocked path for transparent coverage.",
          approvalState = ActionApprovalState.NOT_REQUIRED,
        ),
      )
    }
  }

  private enum class SeedScenario {
    DEFAULT_APPROVAL,
    DENIED_POLICY,
  }

  private enum class ApprovalOutcome {
    PENDING,
    APPROVED,
    DENIED,
    POLICY_DENIED,
  }
}

// Learning: A plain Activity host keeps the demo state easy to wire later without coupling this slice to launcher changes.
// Issue: Timeline items do not model a live pending result yet, so the seeded prompt represents the current approval gate.
// Learning: Host-owned listener state keeps approve, deny, and mode changes deterministic for future androidTest coverage.
// Issue: The timeline enums do not include a dedicated denied-approval chip, so denial stays explicit through DENY/CANCELLED plus reason text.
