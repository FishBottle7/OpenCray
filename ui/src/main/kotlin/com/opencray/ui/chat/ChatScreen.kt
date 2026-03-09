package com.opencray.ui.chat

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import com.opencray.ui.timeline.ActionTimeline
import com.opencray.ui.timeline.ActionTimelineItem

private const val DEFAULT_CONVERSATION_EMPTY_MESSAGE_TEXT =
  "No conversation yet. A future host can stream user and agent messages into this view."

data class ConversationHeaderState(
  val title: String = "OpenCray Chat",
  val subtitle: String = "Ready for a new request.",
  val queuedActionCount: Int = 0,
  val isQueueVisible: Boolean = false,
) {
  init {
    require(title.isNotBlank()) { "title must not be blank." }
    require(subtitle.isNotBlank()) { "subtitle must not be blank." }
    require(queuedActionCount >= 0) { "queuedActionCount must be non-negative." }
  }
}

enum class ChatMode(
  val displayName: String,
  val helperText: String,
) {
  SAFE("Safe", "Requests approval before sensitive actions."),
  AUTO("Auto", "Keeps the flow moving with fewer interruptions."),
  DEVELOPER("Developer", "Shows deeper control surfaces for debugging and power use."),
}

data class ModeState(
  val selectedMode: ChatMode = ChatMode.SAFE,
  val availableModes: List<ChatMode> = ChatMode.values().toList(),
) {
  init {
    require(availableModes.isNotEmpty()) { "availableModes must not be empty." }
    require(availableModes.distinct().size == availableModes.size) {
      "availableModes must not contain duplicates."
    }
    require(availableModes.contains(selectedMode)) {
      "selectedMode must exist in availableModes."
    }
  }
}

enum class ApprovalPromptStatus(
  val displayName: String,
) {
  HIDDEN("Hidden"),
  REQUIRED("Approval required"),
  APPROVED("Approved"),
  DENIED("Denied"),
}

data class ApprovalPromptState(
  val status: ApprovalPromptStatus = ApprovalPromptStatus.HIDDEN,
  val title: String = "Approval required",
  val message: String = "Review the queued action before continuing.",
  val decisionNote: String = "",
  val approveLabel: String = "Approve",
  val denyLabel: String = "Deny",
) {
  init {
    if (status != ApprovalPromptStatus.HIDDEN) {
      require(title.isNotBlank()) { "title must not be blank when the prompt is visible." }
      require(message.isNotBlank()) { "message must not be blank when the prompt is visible." }
    }
    require(approveLabel.isNotBlank()) { "approveLabel must not be blank." }
    require(denyLabel.isNotBlank()) { "denyLabel must not be blank." }
  }
}

enum class ApprovalDecision {
  APPROVE,
  DENY,
}

enum class ResetAgentIdentityConfirmationStatus(
  val displayName: String,
) {
  HIDDEN("Hidden"),
  CONFIRMING("Confirm reset"),
  CONFIRMED("Reset confirmed"),
}

data class ResetAgentIdentityConfirmationState(
  val status: ResetAgentIdentityConfirmationStatus = ResetAgentIdentityConfirmationStatus.HIDDEN,
  val title: String = "Confirm agent identity reset",
  val message: String = "Resetting here clears the active identity before a future host wires the next session state.",
  val statusNote: String = "",
  val openLabel: String = "Reset agent identity",
  val confirmLabel: String = "Confirm reset",
  val cancelLabel: String = "Cancel",
  val dismissLabel: String = "Close",
) {
  init {
    if (status != ResetAgentIdentityConfirmationStatus.HIDDEN) {
      require(title.isNotBlank()) { "title must not be blank when reset confirmation is visible." }
      require(message.isNotBlank()) { "message must not be blank when reset confirmation is visible." }
    }
    require(openLabel.isNotBlank()) { "openLabel must not be blank." }
    require(confirmLabel.isNotBlank()) { "confirmLabel must not be blank." }
    require(cancelLabel.isNotBlank()) { "cancelLabel must not be blank." }
    require(dismissLabel.isNotBlank()) { "dismissLabel must not be blank." }
  }
}

data class ChatScreenState(
  val headerState: ConversationHeaderState = ConversationHeaderState(),
  val modeState: ModeState = ModeState(),
  val approvalPromptState: ApprovalPromptState = ApprovalPromptState(),
  val resetConfirmationState: ResetAgentIdentityConfirmationState = ResetAgentIdentityConfirmationState(),
  val conversationLines: List<String> = emptyList(),
  val conversationEmptyMessage: String = DEFAULT_CONVERSATION_EMPTY_MESSAGE_TEXT,
  val timelineItems: List<ActionTimelineItem> = emptyList(),
  val timelineEmptyMessage: String = ActionTimeline.DEFAULT_EMPTY_MESSAGE,
) {
  init {
    require(conversationEmptyMessage.isNotBlank()) { "conversationEmptyMessage must not be blank." }
    require(timelineEmptyMessage.isNotBlank()) { "timelineEmptyMessage must not be blank." }
  }
}

class ChatScreen @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null,
) : ScrollView(context, attrs) {
  interface Listener {
    fun onQueueVisibilityChanged(isVisible: Boolean)

    fun onModeSelected(mode: ChatMode)

    fun onApprovalDecision(decision: ApprovalDecision)

    fun onResetAgentIdentityConfirmationChanged(status: ResetAgentIdentityConfirmationStatus) = Unit

    fun onResetAgentIdentity()
  }

  companion object {
    const val DEFAULT_CONVERSATION_EMPTY_MESSAGE = DEFAULT_CONVERSATION_EMPTY_MESSAGE_TEXT

    private const val MODE_SAFE_ID = 2001
    private const val MODE_AUTO_ID = 2002
    private const val MODE_DEVELOPER_ID = 2003
  }

  private val surfaceColor = Color.WHITE
  private val backgroundColor = Color.parseColor("#F4F7FB")
  private val borderColor = Color.parseColor("#D7E1ED")
  private val textPrimary = Color.parseColor("#152538")
  private val textSecondary = Color.parseColor("#5D6B7B")
  private val accentColor = Color.parseColor("#2353B6")
  private val successColor = Color.parseColor("#1F7A44")
  private val warningColor = Color.parseColor("#9A6700")
  private val dangerColor = Color.parseColor("#8E1C1C")

  private var listener: Listener? = null
  private var state: ChatScreenState = ChatScreenState()

  private val contentContainer = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(16), dp(16), dp(16), dp(24))
  }

  private val headerTitleView = titleText(20f)
  private val headerSubtitleView = helperText()
  private val queueSummaryView = helperText()
  private val queueToggleButton = actionButton()

  private val approvalCard = sectionCard()
  private val approvalTitleView = titleText(18f)
  private val approvalMessageView = bodyText()
  private val approvalStatusView = helperText()
  private val approveButton = actionButton()
  private val denyButton = secondaryButton()

  private val modeSummaryView = helperText()
  private val modeSwitchGroup = RadioGroup(context).apply {
    orientation = LinearLayout.VERTICAL
  }

  private val resetButton = secondaryButton().apply {
    text = "Reset agent identity"
  }
  private val resetConfirmationCard = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    background = sectionBackground(warningColor)
    setPadding(dp(14), dp(14), dp(14), dp(14))
  }
  private val resetConfirmationTitleView = titleText(16f)
  private val resetConfirmationMessageView = bodyText()
  private val resetConfirmationStatusView = helperText()
  private val confirmResetButton = actionButton().apply {
    text = "Confirm reset"
  }
  private val cancelResetButton = secondaryButton().apply {
    text = "Cancel"
  }

  private val conversationContainer = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
  }

  private val actionTimeline = ActionTimeline(context)

  init {
    isFillViewport = true
    setBackgroundColor(backgroundColor)

    addView(
      contentContainer,
      LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
      ),
    )

    contentContainer.addView(buildHeaderCard())
    contentContainer.addView(approvalCard, blockParams(topDp = 16))
    contentContainer.addView(buildModeCard(), blockParams(topDp = 16))
    contentContainer.addView(buildIdentityCard(), blockParams(topDp = 16))
    contentContainer.addView(buildConversationCard(), blockParams(topDp = 16))
    contentContainer.addView(buildTimelineCard(), blockParams(topDp = 16))

    queueToggleButton.setOnClickListener {
      val updatedHeader = state.headerState.copy(isQueueVisible = !state.headerState.isQueueVisible)
      submitState(state.copy(headerState = updatedHeader))
      listener?.onQueueVisibilityChanged(updatedHeader.isQueueVisible)
    }

    approveButton.setOnClickListener {
      val updatedPrompt = state.approvalPromptState.copy(
        status = ApprovalPromptStatus.APPROVED,
        decisionNote = state.approvalPromptState.decisionNote.ifBlank {
          "Approved from the chat review surface."
        },
      )
      submitState(state.copy(approvalPromptState = updatedPrompt))
      listener?.onApprovalDecision(ApprovalDecision.APPROVE)
    }

    denyButton.setOnClickListener {
      val updatedPrompt = state.approvalPromptState.copy(
        status = ApprovalPromptStatus.DENIED,
        decisionNote = state.approvalPromptState.decisionNote.ifBlank {
          "Denied here so the reason stays visible to the next host state update."
        },
      )
      submitState(state.copy(approvalPromptState = updatedPrompt))
      listener?.onApprovalDecision(ApprovalDecision.DENY)
    }

    resetButton.setOnClickListener {
      if (state.resetConfirmationState.status != ResetAgentIdentityConfirmationStatus.HIDDEN) {
        return@setOnClickListener
      }

      updateResetConfirmationState(
        state.resetConfirmationState.copy(
          status = ResetAgentIdentityConfirmationStatus.CONFIRMING,
          statusNote = "Confirm the reset before the active identity is cleared.",
        ),
      )
    }

    confirmResetButton.setOnClickListener {
      updateResetConfirmationState(
        state.resetConfirmationState.copy(
          status = ResetAgentIdentityConfirmationStatus.CONFIRMED,
          statusNote = "Reset confirmed here so the next host update can react without guessing about user intent.",
        ),
      )
      listener?.onResetAgentIdentity()
    }

    cancelResetButton.setOnClickListener {
      if (state.resetConfirmationState.status == ResetAgentIdentityConfirmationStatus.HIDDEN) {
        return@setOnClickListener
      }

      updateResetConfirmationState(
        state.resetConfirmationState.copy(
          status = ResetAgentIdentityConfirmationStatus.HIDDEN,
          statusNote = "",
        ),
      )
    }

    setupApprovalCard()
    setupResetConfirmationCard()
    submitState(state)
  }

  fun setListener(listener: Listener?) {
    this.listener = listener
  }

  fun submitState(newState: ChatScreenState) {
    state = newState.copy(
      conversationLines = newState.conversationLines.toList(),
      timelineItems = newState.timelineItems.toList(),
    )
    renderHeader()
    renderApprovalPrompt()
    renderModeControls()
    renderResetConfirmation()
    renderConversationBody()
    renderTimeline()
  }

  fun snapshotState(): ChatScreenState = state.copy(
    conversationLines = state.conversationLines.toList(),
    timelineItems = state.timelineItems.toList(),
  )

  private fun buildHeaderCard(): View = sectionCard().apply {
    addView(headerTitleView)
    addView(headerSubtitleView, blockParams(topDp = 6))
    addView(
      LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL

        addView(
          queueSummaryView,
          LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(
          queueToggleButton,
          LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
          ).apply {
            marginStart = dp(12)
          },
        )
      },
      blockParams(topDp = 14),
    )
  }

  private fun buildModeCard(): View = sectionCard().apply {
    addView(titleText("Execution mode", 18f))
    addView(
      helperText("Safe, Auto, and Developer stay explicit so a future host can swap behavior without changing this view."),
      blockParams(topDp = 6),
    )
    addView(modeSummaryView, blockParams(topDp = 12))
    addView(modeSwitchGroup, blockParams(topDp = 12))
  }

  private fun buildIdentityCard(): View = sectionCard().apply {
    addView(titleText("Agent identity", 18f))
    addView(
      helperText("Resetting the active identity is kept separate from mode changes so the host can wire it to session controls later."),
      blockParams(topDp = 6),
    )
    addView(resetButton, blockParams(topDp = 12))
    addView(resetConfirmationCard, blockParams(topDp = 12))
  }

  private fun buildConversationCard(): View = sectionCard().apply {
    addView(titleText("Conversation body", 18f))
    addView(
      helperText("This placeholder body keeps empty and populated states deterministic until message threading arrives."),
      blockParams(topDp = 6),
    )
    addView(conversationContainer, blockParams(topDp = 12))
  }

  private fun buildTimelineCard(): View = sectionCard().apply {
    addView(titleText("Action timeline", 18f))
    addView(
      helperText("Policy decisions and action results stay delegated to ActionTimeline so the host only needs to pass state."),
      blockParams(topDp = 6),
    )
    addView(actionTimeline, blockParams(topDp = 12))
  }

  private fun setupApprovalCard() {
    approvalCard.addView(approvalTitleView)
    approvalCard.addView(approvalMessageView, blockParams(topDp = 6))
    approvalCard.addView(approvalStatusView, blockParams(topDp = 10))
    approvalCard.addView(
      LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(approveButton)
        addView(denyButton, buttonRowParams())
      },
      blockParams(topDp = 14),
    )
  }

  private fun setupResetConfirmationCard() {
    resetConfirmationCard.addView(resetConfirmationTitleView)
    resetConfirmationCard.addView(resetConfirmationMessageView, blockParams(topDp = 6))
    resetConfirmationCard.addView(resetConfirmationStatusView, blockParams(topDp = 10))
    resetConfirmationCard.addView(
      LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(confirmResetButton)
        addView(cancelResetButton, buttonRowParams())
      },
      blockParams(topDp = 14),
    )
  }

  private fun renderHeader() {
    headerTitleView.text = state.headerState.title
    headerSubtitleView.text = state.headerState.subtitle
    queueSummaryView.text = buildString {
      append("Queue ")
      append(if (state.headerState.isQueueVisible) "visible" else "hidden")
      append(" • ")
      append(state.headerState.queuedActionCount)
      append(" waiting")
    }
    queueToggleButton.text = if (state.headerState.isQueueVisible) "Hide queue" else "Show queue"
  }

  private fun renderApprovalPrompt() {
    val promptState = state.approvalPromptState
    approvalCard.visibility = if (promptState.status == ApprovalPromptStatus.HIDDEN) View.GONE else View.VISIBLE

    if (promptState.status == ApprovalPromptStatus.HIDDEN) {
      return
    }

    approvalTitleView.text = promptState.title
    approvalMessageView.text = promptState.message
    approvalStatusView.text = when (promptState.status) {
      ApprovalPromptStatus.REQUIRED -> "Waiting for a decision before the queued action can continue."
      ApprovalPromptStatus.APPROVED -> promptState.decisionNote.ifBlank { "Approved and ready for the host to continue." }
      ApprovalPromptStatus.DENIED -> promptState.decisionNote.ifBlank { "Denied and left visible for transparent follow-up." }
      ApprovalPromptStatus.HIDDEN -> ""
    }

    val buttonVisibility = if (promptState.status == ApprovalPromptStatus.REQUIRED) View.VISIBLE else View.GONE
    approveButton.visibility = buttonVisibility
    denyButton.visibility = buttonVisibility
    approveButton.text = promptState.approveLabel
    denyButton.text = promptState.denyLabel

    approvalCard.background = sectionBackground(statusAccentColor(promptState.status))
  }

  private fun renderModeControls() {
    modeSummaryView.text = state.modeState.selectedMode.helperText
    modeSwitchGroup.setOnCheckedChangeListener(null)
    modeSwitchGroup.removeAllViews()

    state.modeState.availableModes.forEach { mode ->
      modeSwitchGroup.addView(
        RadioButton(context).apply {
          id = modeButtonId(mode)
          text = mode.displayName
          textSize = 15f
          setTextColor(textPrimary)
          isChecked = mode == state.modeState.selectedMode
        },
        LinearLayout.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT,
          ViewGroup.LayoutParams.WRAP_CONTENT,
        ),
      )
    }

    modeSwitchGroup.setOnCheckedChangeListener { _, checkedId ->
      val selectedMode = when (checkedId) {
        MODE_SAFE_ID -> ChatMode.SAFE
        MODE_AUTO_ID -> ChatMode.AUTO
        MODE_DEVELOPER_ID -> ChatMode.DEVELOPER
        else -> null
      } ?: return@setOnCheckedChangeListener

      if (selectedMode == state.modeState.selectedMode) {
        return@setOnCheckedChangeListener
      }

      val updatedModeState = state.modeState.copy(selectedMode = selectedMode)
      submitState(state.copy(modeState = updatedModeState))
      listener?.onModeSelected(selectedMode)
    }
  }

  private fun renderResetConfirmation() {
    val confirmationState = state.resetConfirmationState
    val isConfirmationVisible = confirmationState.status != ResetAgentIdentityConfirmationStatus.HIDDEN

    resetButton.text = confirmationState.openLabel
    resetButton.isEnabled = !isConfirmationVisible
    resetConfirmationCard.visibility = if (isConfirmationVisible) View.VISIBLE else View.GONE

    if (!isConfirmationVisible) {
      return
    }

    resetConfirmationTitleView.text = confirmationState.title
    resetConfirmationMessageView.text = confirmationState.message
    resetConfirmationStatusView.text = when (confirmationState.status) {
      ResetAgentIdentityConfirmationStatus.HIDDEN -> ""
      ResetAgentIdentityConfirmationStatus.CONFIRMING -> confirmationState.statusNote.ifBlank {
        "This reset stays local until someone explicitly confirms it here."
      }
      ResetAgentIdentityConfirmationStatus.CONFIRMED -> confirmationState.statusNote.ifBlank {
        "Reset confirmed and left visible so the next host update can resolve it clearly."
      }
    }

    when (confirmationState.status) {
      ResetAgentIdentityConfirmationStatus.HIDDEN -> {
        confirmResetButton.visibility = View.GONE
        cancelResetButton.visibility = View.GONE
      }
      ResetAgentIdentityConfirmationStatus.CONFIRMING -> {
        confirmResetButton.visibility = View.VISIBLE
        cancelResetButton.visibility = View.VISIBLE
        confirmResetButton.text = confirmationState.confirmLabel
        cancelResetButton.text = confirmationState.cancelLabel
      }
      ResetAgentIdentityConfirmationStatus.CONFIRMED -> {
        confirmResetButton.visibility = View.GONE
        cancelResetButton.visibility = View.VISIBLE
        cancelResetButton.text = confirmationState.dismissLabel
      }
    }

    resetConfirmationCard.background = sectionBackground(resetConfirmationAccentColor(confirmationState.status))
  }

  private fun updateResetConfirmationState(newConfirmationState: ResetAgentIdentityConfirmationState) {
    val previousStatus = state.resetConfirmationState.status
    submitState(state.copy(resetConfirmationState = newConfirmationState))
    if (previousStatus != newConfirmationState.status) {
      listener?.onResetAgentIdentityConfirmationChanged(newConfirmationState.status)
    }
  }

  private fun renderConversationBody() {
    conversationContainer.removeAllViews()

    if (state.conversationLines.isEmpty()) {
      conversationContainer.addView(emptyBodyCard(state.conversationEmptyMessage))
      return
    }

    state.conversationLines.forEachIndexed { index, line ->
      conversationContainer.addView(
        conversationLineCard(line),
        blockParams(bottomDp = if (index == state.conversationLines.lastIndex) 0 else 10),
      )
    }
  }

  private fun renderTimeline() {
    actionTimeline.setEmptyStateMessage(state.timelineEmptyMessage)
    actionTimeline.submitItems(state.timelineItems)
  }

  private fun emptyBodyCard(message: String): View = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    background = mutedSurfaceBackground()
    setPadding(dp(14), dp(14), dp(14), dp(14))
    addView(titleText("Nothing to show yet", 16f))
    addView(helperText(message), blockParams(topDp = 6))
  }

  private fun conversationLineCard(line: String): View = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    background = mutedSurfaceBackground()
    setPadding(dp(14), dp(14), dp(14), dp(14))
    addView(bodyText(line))
  }

  private fun sectionCard(): LinearLayout = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    background = sectionBackground(borderColor)
    setPadding(dp(16), dp(16), dp(16), dp(16))
  }

  private fun titleText(value: String, textSizeSp: Float): TextView = TextView(context).apply {
    text = value
    textSize = textSizeSp
    setTextColor(textPrimary)
    setTypeface(typeface, Typeface.BOLD)
  }

  private fun titleText(textSizeSp: Float): TextView = TextView(context).apply {
    textSize = textSizeSp
    setTextColor(textPrimary)
    setTypeface(typeface, Typeface.BOLD)
  }

  private fun bodyText(value: String = ""): TextView = TextView(context).apply {
    text = value
    textSize = 14f
    setTextColor(textPrimary)
    setLineSpacing(0f, 1.12f)
  }

  private fun helperText(value: String = ""): TextView = TextView(context).apply {
    text = value
    textSize = 13f
    setTextColor(textSecondary)
    setLineSpacing(0f, 1.1f)
  }

  private fun actionButton(): Button = Button(context).apply {
    isAllCaps = false
    text = "Show queue"
  }

  private fun secondaryButton(): Button = Button(context).apply {
    isAllCaps = false
    text = "Deny"
  }

  private fun sectionBackground(strokeColor: Int): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    cornerRadius = dp(18).toFloat()
    setColor(surfaceColor)
    setStroke(dp(1), strokeColor)
  }

  private fun mutedSurfaceBackground(): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    cornerRadius = dp(16).toFloat()
    setColor(Color.parseColor("#F8FAFC"))
    setStroke(dp(1), Color.parseColor("#D9E2EC"))
  }

  private fun statusAccentColor(status: ApprovalPromptStatus): Int = when (status) {
    ApprovalPromptStatus.HIDDEN -> borderColor
    ApprovalPromptStatus.REQUIRED -> warningColor
    ApprovalPromptStatus.APPROVED -> successColor
    ApprovalPromptStatus.DENIED -> dangerColor
  }

  private fun resetConfirmationAccentColor(status: ResetAgentIdentityConfirmationStatus): Int = when (status) {
    ResetAgentIdentityConfirmationStatus.HIDDEN -> borderColor
    ResetAgentIdentityConfirmationStatus.CONFIRMING -> warningColor
    ResetAgentIdentityConfirmationStatus.CONFIRMED -> successColor
  }

  private fun modeButtonId(mode: ChatMode): Int = when (mode) {
    ChatMode.SAFE -> MODE_SAFE_ID
    ChatMode.AUTO -> MODE_AUTO_ID
    ChatMode.DEVELOPER -> MODE_DEVELOPER_ID
  }

  private fun blockParams(
    topDp: Int = 0,
    bottomDp: Int = 0,
  ): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
    ViewGroup.LayoutParams.MATCH_PARENT,
    ViewGroup.LayoutParams.WRAP_CONTENT,
  ).apply {
    topMargin = dp(topDp)
    bottomMargin = dp(bottomDp)
  }

  private fun buttonRowParams(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
    ViewGroup.LayoutParams.WRAP_CONTENT,
    ViewGroup.LayoutParams.WRAP_CONTENT,
  ).apply {
    marginStart = dp(10)
  }

  private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}

// Learning: A state-driven custom view keeps future host wiring simple because all screen surfaces render from one deterministic model.
// Issue: This slice uses framework Buttons without shared theming helpers yet, so final polish depends on later host styling decisions.
// Learning: A nested confirmation surface adds destructive-action safety without forcing host changes in the same slice.
// Issue: Until host wiring owns reset confirmation state, external screen submissions can still replace the in-view reset status.
