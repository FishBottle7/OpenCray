package com.opencray.ui.chat

import android.content.Context
import android.animation.LayoutTransition
import android.animation.ValueAnimator
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import com.opencray.ui.design.OpenCrayUiTokens
import com.opencray.ui.design.ocDp
import org.opencray.ui.R
import java.io.File
import kotlin.math.min

private const val DEFAULT_CONVERSATION_EMPTY_MESSAGE_TEXT =
  "Start with a message, an image, a file, or a command."

enum class ChatMode(
  val displayName: String,
  val helperText: String,
) {
  SAFE("Safe", "Requests approval before sensitive actions."),
  AUTO("Auto", "Keeps the flow moving with fewer interruptions."),
  DEVELOPER("Developer", "Shows deeper control surfaces for debugging and power use."),
}

enum class ApprovalDecision { APPROVE, DENY }

enum class ApprovalPromptStatus { HIDDEN, REQUIRED, APPROVED, DENIED }

data class ApprovalPromptState(
  val status: ApprovalPromptStatus = ApprovalPromptStatus.HIDDEN,
  val title: String = "",
  val message: String = "",
  val decisionNote: String = "",
  val approveLabel: String = "Approve",
  val denyLabel: String = "Deny",
)

data class ConversationHeaderState(
  val title: String = "New session",
  val subtitle: String = "",
  val queuedActionCount: Int = 0,
  val isQueueVisible: Boolean = false,
)

data class ChatComposerState(
  val draftText: String = "",
  val inputHint: String = "Message OpenCray",
  val sendEnabled: Boolean = true,
)

data class ModeState(
  val selectedMode: ChatMode = ChatMode.SAFE,
  val availableModes: List<ChatMode> = ChatMode.entries.toList(),
)

data class ChatSessionListItemState(
  val sessionId: String,
  val title: String,
  val preview: String,
  val meta: String,
  val isSelected: Boolean,
)

enum class ChatMessageDisplayRole { SYSTEM, USER, ASSISTANT, TOOL }

enum class ConversationMessageRole { SYSTEM, USER, ASSISTANT, TOOL }

enum class ChatMessageAction { REGENERATE, RECALL, DELETE, EDIT, BRANCH, SHARE }

enum class ChatAttachmentVisualKind { IMAGE, FILE }

data class ChatAttachmentVisualState(
  val attachmentId: String,
  val kind: ChatAttachmentVisualKind,
  val displayName: String,
  val detail: String = "",
  val localPath: String? = null,
)

data class ChatCommandOptionState(
  val id: String,
  val label: String,
)

data class ChatSessionSummaryState(
  val title: String,
  val meta: String = "",
  val detail: String = "",
)

data class ChatMessageItemState(
  val messageId: String,
  val role: ChatMessageDisplayRole,
  val body: String = "",
  val meta: String = "",
  val commandLabel: String? = null,
  val attachments: List<ChatAttachmentVisualState> = emptyList(),
)

data class ConversationMessageState(
  val messageId: String,
  val role: ConversationMessageRole,
  val text: String,
  val supportingText: String = "",
)

data class ChatScreenState(
  val title: String = "New session",
  val subtitle: String = "",
  val statusLabel: String = "",
  val statusDetail: String = "",
  val drawerSummary: String = "",
  val sessions: List<ChatSessionListItemState> = emptyList(),
  val messages: List<ChatMessageItemState> = emptyList(),
  val emptyMessage: String = DEFAULT_CONVERSATION_EMPTY_MESSAGE_TEXT,
  val draftText: String = "",
  val draftHint: String = "Message OpenCray",
  val composerAssistiveText: String = "",
  val screenTitle: String = "Chat",
  val modeLabel: String = "SAFE",
  val activeSessionTitle: String = "New session",
  val activeSessionDetail: String = "",
  val sessionSummary: ChatSessionSummaryState? = null,
  val composerAttachments: List<ChatAttachmentVisualState> = emptyList(),
  val availableCommands: List<ChatCommandOptionState> = emptyList(),
  val selectedCommandLabel: String? = null,
)

class ChatScreen @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
  interface Listener {
    fun onSessionSelected(sessionId: String) = Unit
    fun onNewSessionRequested() = Unit
    fun onMessageSubmitted(text: String) = Unit
    fun onComposerFocusChanged(hasFocus: Boolean) = Unit
    fun onMessageActionRequested(messageId: String, action: ChatMessageAction) = Unit
    fun onQueueVisibilityChanged(isVisible: Boolean) = Unit
    fun onModeSelected(mode: ChatMode) = Unit
    fun onApprovalDecision(decision: ApprovalDecision) = Unit
    fun onResetAgentIdentity() = Unit
    fun onImageAttachmentRequested() = Unit
    fun onFileAttachmentRequested() = Unit
    fun onComposerAttachmentRemoved(attachmentId: String) = Unit
    fun onCommandSelected(commandLabel: String) = Unit
  }

  companion object {
    const val DEFAULT_CONVERSATION_EMPTY_MESSAGE = DEFAULT_CONVERSATION_EMPTY_MESSAGE_TEXT
  }

  private val textPrimary = OpenCrayUiTokens.textPrimary
  private val textSecondary = OpenCrayUiTokens.textSecondary
  private val borderColor = OpenCrayUiTokens.border
  private val accentColor = OpenCrayUiTokens.primary
  private val shellBackground = OpenCrayUiTokens.shellBackground
  private val bubbleTextMaxWidth = (resources.displayMetrics.widthPixels * 0.74f).toInt()
  private val drawerWidthPx = min(dp(296), (resources.displayMetrics.widthPixels * 0.82f).toInt())

  private var listener: Listener? = null
  private var state: ChatScreenState = ChatScreenState()
  private var localDraftText: String = ""
  private var suppressDraftCallback = false
  private var isDrawerOpen = false
  private var isAddMenuOpen = false
  private var isCommandMenuOpen = false
  private var lastRenderedMessages: List<ChatMessageItemState> = emptyList()
  private var lastComposerAttachmentIds: Set<String> = emptySet()
  private var lastSelectedSessionId: String? = null
  private var lastRenderedSummary: ChatSessionSummaryState? = null
  private var lastEmptyStateMessage: String = ""
  private var hasAttachedMotion = false

  private val shell = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(shellBackground) }
  private val topBar = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), dp(10), dp(20), dp(8)) }
  private val sessionButton = pillButton(R.drawable.ic_chat_menu, "Sessions")
  private val modeView = TextView(context).apply { setTextColor(accentColor); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); setTypeface(typeface, Typeface.BOLD) }
  private val scrollView = ScrollView(context).apply { isFillViewport = true; isVerticalScrollBarEnabled = false }
  private val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, dp(20), dp(24)) }
  private val titleView = TextView(context).apply { setTextColor(textPrimary); setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f); setTypeface(typeface, Typeface.BOLD); includeFontPadding = false }
  private val emptyStateContainer = FrameLayout(context).apply { visibility = View.GONE }
  private val summaryContainer = FrameLayout(context).apply { visibility = View.GONE }
  private val messageList = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
  private val composer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; background = composerBackground(); setPadding(dp(10), dp(10), dp(10), dp(10)) }
  private val selectedCommandContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
  private val attachmentScroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
  private val attachmentRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
  private val commandList = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; background = subtleCard(); setPadding(dp(12), dp(12), dp(12), dp(12)) }
  private val addMenu = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
  private val addMenuContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
  private val addMenuLabel = TextView(context).apply {
    text = "Add to message"
    setTextColor(OpenCrayUiTokens.textTertiary)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
    setTypeface(typeface, Typeface.BOLD)
    includeFontPadding = false
  }
  private val imageButton = smallAction(R.drawable.ic_chat_image, "Image")
  private val fileButton = smallAction(R.drawable.ic_chat_attach, "File")
  private val commandButton = smallAction(R.drawable.ic_chat_terminal, "Command")
  private val input = EditText(context).apply {
    setTextColor(textPrimary)
    setHintTextColor(OpenCrayUiTokens.textTertiary)
    background = inputBackground()
    minHeight = dp(44)
    setPadding(dp(14), dp(12), dp(14), dp(12))
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
    maxLines = 4
    includeFontPadding = false
  }
  private val plusButton = iconButton(R.drawable.ic_chat_plus, false, "Add to message")
  private val sendButton = iconButton(R.drawable.ic_chat_send, true, "Send")
  private val drawerScrim = View(context).apply { setBackgroundColor(Color.parseColor("#660B1220")); visibility = View.GONE }
  private val drawer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; background = surfaceCard(0); setPadding(dp(20), dp(18), dp(20), dp(20)); visibility = View.GONE }
  private val drawerEyebrow = TextView(context).apply { text = "SESSION HISTORY"; setTextColor(OpenCrayUiTokens.textTertiary); setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f); setTypeface(typeface, Typeface.BOLD) }
  private val drawerTitle = TextView(context).apply { text = "Sessions"; setTextColor(textPrimary); setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); setTypeface(typeface, Typeface.BOLD) }
  private val newSessionButton = filledLabelButton("New session")
  private val drawerScroll = ScrollView(context).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; setBackgroundColor(Color.WHITE) }
  private val drawerSessions = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }

  init {
    setBackgroundColor(shellBackground)
    clipChildren = false
    clipToPadding = false
    addView(shell, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    addView(drawerScrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    addView(drawer, LayoutParams(drawerWidthPx, LayoutParams.MATCH_PARENT, Gravity.START))
    drawer.translationX = -drawerWidthPx.toFloat()
    drawer.alpha = 0f
    drawerScrim.alpha = 0f
    setup()
    setupMotion()
    submitState(state)
  }

  fun setListener(listener: Listener?) { this.listener = listener }

  fun submitState(newState: ChatScreenState) {
    state = newState.copy(
      sessions = newState.sessions.toList(),
      messages = newState.messages.toList(),
      composerAttachments = newState.composerAttachments.toList(),
      availableCommands = newState.availableCommands.toList(),
    )
    localDraftText = newState.draftText
    renderTopBar()
    renderMessages()
    renderComposer()
    renderDrawer()
  }

  fun snapshotState(): ChatScreenState = state.copy(draftText = localDraftText)

  private fun setup() {
    topBar.addView(sessionButton)
    topBar.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
    topBar.addView(modeView)
    content.addView(titleView)
    content.addView(emptyStateContainer, sectionLp(16))
    content.addView(summaryContainer, sectionLp(16))
    content.addView(messageList)
    scrollView.addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    shell.addView(topBar, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    shell.addView(scrollView, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    shell.addView(composer, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

    attachmentScroll.addView(attachmentRow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    addMenu.addView(imageButton, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
    addMenu.addView(fileButton, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
    addMenu.addView(commandButton, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
    addMenuContainer.addView(addMenuLabel)
    addMenuContainer.addView(addMenu, sectionLp(8))
    composer.addView(selectedCommandContainer)
    composer.addView(attachmentScroll, sectionLp(10))
    composer.addView(commandList, sectionLp(10))
    composer.addView(addMenuContainer, sectionLp(10))
    composer.addView(composerInputRow(), sectionLp(10))

    drawer.addView(drawerEyebrow)
    drawer.addView(drawerTitle, sectionLp(8))
    drawer.addView(newSessionButton, sectionLp(12))
    drawerScroll.addView(drawerSessions, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    drawer.addView(drawerScroll, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(16) })

    sessionButton.setOnClickListener { isDrawerOpen = !isDrawerOpen; renderDrawer() }
    drawerScrim.setOnClickListener { isDrawerOpen = false; renderDrawer() }
    newSessionButton.setOnClickListener { isDrawerOpen = false; renderDrawer(); listener?.onNewSessionRequested() }
    plusButton.setOnClickListener {
      isAddMenuOpen = !isAddMenuOpen
      if (isAddMenuOpen) isCommandMenuOpen = false
      renderComposer()
    }
    sendButton.setOnClickListener {
      if (localDraftText.trim().isBlank() && state.composerAttachments.isEmpty() && state.selectedCommandLabel.isNullOrBlank()) {
        return@setOnClickListener
      }
      val text = localDraftText.trim()
      localDraftText = ""
      isAddMenuOpen = false
      isCommandMenuOpen = false
      renderComposer()
      listener?.onMessageSubmitted(text)
    }
    imageButton.setOnClickListener { isAddMenuOpen = false; isCommandMenuOpen = false; renderComposer(); listener?.onImageAttachmentRequested() }
    fileButton.setOnClickListener { isAddMenuOpen = false; isCommandMenuOpen = false; renderComposer(); listener?.onFileAttachmentRequested() }
    commandButton.setOnClickListener { isAddMenuOpen = false; isCommandMenuOpen = !isCommandMenuOpen; renderComposer() }
    input.addTextChangedListener(object : TextWatcher {
      override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
      override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
      override fun afterTextChanged(s: Editable?) {
        if (suppressDraftCallback) return
        localDraftText = s?.toString().orEmpty()
        renderSendState()
      }
    })
    input.setOnFocusChangeListener { _, hasFocus ->
      if (hasFocus) {
        isAddMenuOpen = false
        isCommandMenuOpen = false
        renderComposer()
      }
      listener?.onComposerFocusChanged(hasFocus)
    }

    attachPressMotion(sessionButton)
    attachPressMotion(newSessionButton)
    attachPressMotion(imageButton)
    attachPressMotion(fileButton)
    attachPressMotion(commandButton)
    attachPressMotion(plusButton, pressedScale = 0.92f)
    attachPressMotion(sendButton, pressedScale = 0.92f)
  }

  private fun setupMotion() {
    if (hasAttachedMotion) {
      return
    }
    hasAttachedMotion = true
    content.layoutTransition = chatLayoutTransition()
    messageList.layoutTransition = chatLayoutTransition()
    composer.layoutTransition = chatLayoutTransition()
    drawerSessions.layoutTransition = chatLayoutTransition()
  }

  private fun renderTopBar() { modeView.text = state.modeLabel }

  private fun renderMessages() {
    titleView.text = state.screenTitle
    if (state.messages.isEmpty()) {
      renderSummarySection(null)
      renderEmptyState(state.emptyMessage)
      if (lastRenderedMessages.isNotEmpty()) {
        messageList.removeAllViews()
      }
      lastRenderedMessages = emptyList()
      scrollBottom(animated = false)
      return
    }
    renderEmptyState(null)
    renderSummarySection(state.sessionSummary)
    renderMessageThread(state.messages)
  }

  private fun renderComposer() {
    if (input.text.toString() != localDraftText) {
      suppressDraftCallback = true
      input.setText(localDraftText)
      input.setSelection(input.text.length)
      suppressDraftCallback = false
    }
    input.hint = state.draftHint
    selectedCommandContainer.removeAllViews()
    selectedCommandContainer.visibility = if (state.selectedCommandLabel.isNullOrBlank()) View.GONE else View.VISIBLE
    state.selectedCommandLabel?.takeIf { it.isNotBlank() }?.let { label ->
      selectedCommandContainer.addView(TextView(context).apply {
        text = label
        setTextColor(textPrimary)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTypeface(typeface, Typeface.BOLD)
        background = subtleCard()
        setPadding(dp(12), dp(9), dp(12), dp(9))
      }, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }
    animateSectionVisibility(selectedCommandContainer, !state.selectedCommandLabel.isNullOrBlank())

    attachmentRow.removeAllViews()
    val currentAttachmentIds = state.composerAttachments.map { it.attachmentId }.toSet()
    val animateAttachmentCards = currentAttachmentIds != lastComposerAttachmentIds
    attachmentScroll.visibility = if (state.composerAttachments.isEmpty()) View.GONE else View.VISIBLE
    state.composerAttachments.forEachIndexed { index, attachment ->
      val card = attachmentCard(attachment, true, ChatMessageDisplayRole.ASSISTANT)
      attachmentRow.addView(
        card,
        LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
          if (index > 0) marginStart = dp(8)
        },
      )
      if (animateAttachmentCards) {
        card.scaleX = 0.96f
        card.scaleY = 0.96f
        card.alpha = 0f
        card.animate()
          .alpha(1f)
          .scaleX(1f)
          .scaleY(1f)
          .setDuration(motionDurationMedium())
          .setStartDelay((index * 28L).coerceAtMost(84L))
          .start()
      }
    }
    lastComposerAttachmentIds = currentAttachmentIds
    animateSectionVisibility(attachmentScroll, state.composerAttachments.isNotEmpty())

    commandList.removeAllViews()
    val showCommandList = isCommandMenuOpen && state.availableCommands.isNotEmpty()
    commandList.visibility = if (showCommandList) View.VISIBLE else View.GONE
    if (showCommandList) {
      commandList.addView(TextView(context).apply {
        text = "Commands"
        setTextColor(textSecondary)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTypeface(typeface, Typeface.BOLD)
      })
      state.availableCommands.forEachIndexed { index, command ->
        commandList.addView(commandRow(command), sectionLp(if (index == 0) 8 else 6))
      }
    }
    animateSectionVisibility(commandList, showCommandList)

    addMenuContainer.visibility = if (isAddMenuOpen) View.VISIBLE else View.GONE
    animateSectionVisibility(addMenuContainer, isAddMenuOpen)
    plusButton.animate()
      .rotation(if (isAddMenuOpen || isCommandMenuOpen) 45f else 0f)
      .alpha(if (isAddMenuOpen || isCommandMenuOpen) 0.82f else 1f)
      .setDuration(motionDurationShort())
      .start()
    renderSendState()
  }

  private fun renderSendState() {
    val enabled = localDraftText.isNotBlank() || state.composerAttachments.isNotEmpty() || !state.selectedCommandLabel.isNullOrBlank()
    sendButton.isEnabled = enabled
    sendButton.animate()
      .alpha(if (enabled) 1f else 0.42f)
      .scaleX(if (enabled) 1f else 0.94f)
      .scaleY(if (enabled) 1f else 0.94f)
      .setDuration(motionDurationShort())
      .start()
  }

  private fun renderDrawer() {
    updateDrawerVisibility(isDrawerOpen)
    drawerTitle.text = "Recent sessions"
    drawerSessions.removeAllViews()
    if (state.sessions.isEmpty()) {
      drawerSessions.addView(card("No sessions yet", "Start a new chat to create the first local session."))
      return
    }
    state.sessions.forEachIndexed { index, session ->
      val row = sessionRow(session)
      drawerSessions.addView(row)
      if (session.isSelected && session.sessionId != lastSelectedSessionId) {
        animateSelectionPulse(row)
      }
      if (index != state.sessions.lastIndex) drawerSessions.addView(View(context), sectionLp(12))
    }
    lastSelectedSessionId = state.sessions.firstOrNull { it.isSelected }?.sessionId
  }

  private fun renderEmptyState(message: String?) {
    val shouldShow = !message.isNullOrBlank()
    val safeMessage = message.orEmpty()
    if (shouldShow && (emptyStateContainer.childCount == 0 || lastEmptyStateMessage != safeMessage)) {
      emptyStateContainer.removeAllViews()
      emptyStateContainer.addView(card("New session ready", safeMessage))
      lastEmptyStateMessage = safeMessage
    }
    if (!shouldShow) {
      lastEmptyStateMessage = ""
    }
    animateSectionVisibility(emptyStateContainer, shouldShow)
  }

  private fun renderSummarySection(summary: ChatSessionSummaryState?) {
    val shouldShow = summary != null
    if (shouldShow && (summaryContainer.childCount == 0 || lastRenderedSummary != summary)) {
      summaryContainer.removeAllViews()
      summaryContainer.addView(summaryCard(checkNotNull(summary)))
      lastRenderedSummary = summary
    }
    if (!shouldShow) {
      summaryContainer.removeAllViews()
      lastRenderedSummary = null
    }
    animateSectionVisibility(summaryContainer, shouldShow)
  }

  private fun renderMessageThread(messages: List<ChatMessageItemState>) {
    val previousMessages = lastRenderedMessages
    val canAppend =
      previousMessages.size <= messages.size &&
        previousMessages == messages.take(previousMessages.size) &&
        messageList.childCount == previousMessages.size

    if (canAppend) {
      messages.drop(previousMessages.size).forEachIndexed { offset, message ->
        val index = previousMessages.size + offset
        val bubble = bubble(message)
        messageList.addView(bubble, messageLp(index))
        animateBubbleEntrance(bubble)
      }
      val appended = messages.size > previousMessages.size
      lastRenderedMessages = messages.toList()
      if (appended) {
        scrollBottom(animated = previousMessages.isNotEmpty())
      }
      return
    }

    val previousIds = previousMessages.map { it.messageId }.toSet()
    messageList.removeAllViews()
    messages.forEachIndexed { index, message ->
      val bubble = bubble(message)
      messageList.addView(bubble, messageLp(index))
      if (!previousIds.contains(message.messageId)) {
        animateBubbleEntrance(bubble)
      }
    }
    lastRenderedMessages = messages.toList()
    scrollBottom(animated = false)
  }

  private fun summaryCard(summary: ChatSessionSummaryState): View = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    background = surfaceCard(16)
    setPadding(dp(14), dp(12), dp(14), dp(12))
    addView(LinearLayout(context).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      addView(TextView(context).apply {
        text = summary.title
        setTextColor(textPrimary)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTypeface(typeface, Typeface.BOLD)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
      }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
      if (summary.meta.isNotBlank()) {
        addView(TextView(context).apply {
          text = summary.meta
          setTextColor(OpenCrayUiTokens.textTertiary)
          setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        })
      }
    })
    if (summary.detail.isNotBlank()) {
      addView(TextView(context).apply {
        text = summary.detail
        setTextColor(textSecondary)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
      }, sectionLp(6))
    }
  }

  private fun bubble(item: ChatMessageItemState): View = LinearLayout(context).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = when (item.role) {
      ChatMessageDisplayRole.USER -> Gravity.END
      ChatMessageDisplayRole.SYSTEM -> Gravity.CENTER_HORIZONTAL
      else -> Gravity.START
    }
    addView(LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      background = bubbleBackground(item.role)
      setPadding(dp(14), dp(12), dp(14), dp(12))
      if (item.body.isNotBlank()) {
        addView(TextView(context).apply {
          text = item.body
          maxWidth = bubbleTextMaxWidth
          setTextColor(if (item.role == ChatMessageDisplayRole.USER) Color.WHITE else textPrimary)
          setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
          includeFontPadding = false
        })
      }
      if (item.body.isBlank() && !item.commandLabel.isNullOrBlank()) {
        addView(TextView(context).apply {
          text = item.commandLabel
          setTextColor(if (item.role == ChatMessageDisplayRole.USER) Color.WHITE else textPrimary)
          setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
          setTypeface(typeface, Typeface.BOLD)
          includeFontPadding = false
        })
      }
      if (item.attachments.isNotEmpty()) {
        val hs = HorizontalScrollView(context).apply {
          isHorizontalScrollBarEnabled = false
          overScrollMode = View.OVER_SCROLL_NEVER
        }
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        item.attachments.forEachIndexed { index, attachment ->
          row.addView(
            attachmentCard(attachment, false, item.role),
            LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
              if (index > 0) marginStart = dp(8)
            },
          )
        }
        hs.addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(hs, sectionLp(if (item.body.isNotBlank()) 10 else 0))
      }
      attachPressMotion(this, pressedScale = 0.985f, pressedAlpha = 0.97f)
    })
  }

  private fun attachmentCard(
    attachment: ChatAttachmentVisualState,
    removable: Boolean,
    role: ChatMessageDisplayRole,
  ): View = if (attachment.kind == ChatAttachmentVisualKind.IMAGE) {
    FrameLayout(context).apply {
      val size = dp(if (removable) 84 else 76)
      layoutParams = LinearLayout.LayoutParams(size, size)
      background = attachmentBg(role)
      val preview = FrameLayout(context).apply { background = imagePlaceholder() }
      addView(preview, LayoutParams(size - dp(12), size - dp(12), Gravity.CENTER))
      val file = attachment.localPath?.let(::File)
      val bitmap = if (file != null && file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
      if (bitmap == null) {
        preview.addView(ImageView(context).apply {
          setImageDrawable(tint(R.drawable.ic_chat_image, Color.parseColor("#4B5F7A")))
          scaleType = ImageView.ScaleType.CENTER_INSIDE
          alpha = 0.88f
        }, LayoutParams(dp(22), dp(22), Gravity.CENTER))
      } else {
        preview.addView(ImageView(context).apply {
          setImageBitmap(bitmap)
          scaleType = ImageView.ScaleType.CENTER_CROP
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
      }
      if (removable) {
        addView(removeBadge(attachment.attachmentId), LayoutParams(dp(18), dp(18), Gravity.TOP or Gravity.END).apply {
          topMargin = dp(6)
          marginEnd = dp(6)
        })
      }
      attachPressMotion(this, pressedScale = 0.975f, pressedAlpha = 0.98f)
    }
  } else {
    FrameLayout(context).apply {
      val width = dp(if (removable) 228 else 176)
      val height = dp(if (removable) 84 else 76)
      layoutParams = LinearLayout.LayoutParams(width, height)
      background = attachmentBg(role)
      addView(FrameLayout(context).apply {
        background = fileBadge()
        addView(TextView(context).apply {
          text = attachment.displayName.substringAfterLast('.', "FILE").take(4).uppercase()
          setTextColor(accentColor)
          setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
          setTypeface(typeface, Typeface.BOLD)
          gravity = Gravity.CENTER
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
      }, LayoutParams(dp(32), dp(32), Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = dp(14) })
      addView(LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(context).apply {
          text = attachment.displayName
          setTextColor(textPrimary)
          setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
          setTypeface(typeface, Typeface.BOLD)
          maxLines = 1
          ellipsize = TextUtils.TruncateAt.END
        })
        if (attachment.detail.isNotBlank()) {
          addView(TextView(context).apply {
            text = attachment.detail
            setTextColor(textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
          }, sectionLp(4))
        }
      }, LayoutParams(width - dp(88), LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL).apply {
        marginStart = dp(58)
        marginEnd = if (removable) dp(24) else dp(14)
      })
      if (removable) {
        addView(removeBadge(attachment.attachmentId), LayoutParams(dp(18), dp(18), Gravity.TOP or Gravity.END).apply {
          topMargin = dp(6)
          marginEnd = dp(6)
        })
      }
      attachPressMotion(this, pressedScale = 0.98f, pressedAlpha = 0.98f)
    }
  }

  private fun commandRow(command: ChatCommandOptionState): View = LinearLayout(context).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    background = whiteBorderCard(12, Color.parseColor("#E3E3E8"), Color.WHITE)
    minimumHeight = dp(40)
    setPadding(dp(12), dp(9), dp(12), dp(9))
    isClickable = true
    setOnClickListener {
      performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
      isCommandMenuOpen = false
      renderComposer()
      listener?.onCommandSelected(command.label)
    }
    addView(TextView(context).apply {
      text = command.label
      setTextColor(textPrimary)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
      setTypeface(typeface, Typeface.NORMAL)
      includeFontPadding = false
    })
    attachPressMotion(this)
  }

  private fun sessionRow(session: ChatSessionListItemState): View = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    background = if (session.isSelected) {
      whiteBorderCard(16, Color.TRANSPARENT, Color.parseColor("#EEF5FF"))
    } else {
      whiteBorderCard(16, Color.TRANSPARENT, Color.parseColor("#F7F7FA"))
    }
    elevation = if (session.isSelected) dp(1).toFloat() else 0f
    setPadding(dp(16), dp(14), dp(16), dp(14))
    isClickable = true
    setOnClickListener {
      performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
      isDrawerOpen = false
      renderDrawer()
      listener?.onSessionSelected(session.sessionId)
    }
    addView(LinearLayout(context).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      addView(TextView(context).apply {
        text = session.title
        setTextColor(textPrimary)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTypeface(typeface, Typeface.BOLD)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
      }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
      if (session.isSelected) {
        addView(TextView(context).apply {
          text = "Active now"
          setTextColor(accentColor)
          setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
          setTypeface(typeface, Typeface.BOLD)
        })
      }
    })
    if (session.preview.isNotBlank()) {
      addView(TextView(context).apply {
        text = session.preview
        setTextColor(textSecondary)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
      }, sectionLp(8))
    }
    if (session.meta.isNotBlank()) {
      addView(TextView(context).apply {
        text = session.meta
        setTextColor(OpenCrayUiTokens.textTertiary)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
      }, sectionLp(8))
    }
    attachPressMotion(this, pressedScale = 0.985f, pressedAlpha = 0.96f)
  }

  private fun card(title: String, detail: String): View = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    background = surfaceCard(16)
    setPadding(dp(16), dp(16), dp(16), dp(16))
    addView(TextView(context).apply {
      text = title
      setTextColor(textPrimary)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
      setTypeface(typeface, Typeface.BOLD)
    })
    addView(TextView(context).apply {
      text = detail
      setTextColor(textSecondary)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
    }, sectionLp(8))
  }

  private fun filledLabelButton(label: String): TextView = TextView(context).apply {
    text = label
    setTextColor(Color.WHITE)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
    setTypeface(typeface, Typeface.BOLD)
    gravity = Gravity.CENTER
    background = emphasizedButton(12)
    minHeight = dp(36)
    setPadding(dp(16), dp(9), dp(16), dp(9))
  }

  private fun pillButton(iconResId: Int, label: String): LinearLayout = LinearLayout(context).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    background = surfaceCard(999)
    minimumHeight = dp(30)
    setPadding(dp(10), dp(7), dp(10), dp(7))
    addView(ImageView(context).apply {
      setImageDrawable(tint(iconResId, OpenCrayUiTokens.textTertiary))
    }, LinearLayout.LayoutParams(dp(12), dp(12)))
    addView(TextView(context).apply {
      text = label
      setTextColor(textPrimary)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
    }, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
      marginStart = dp(6)
    })
    addView(TextView(context).apply {
      text = "›"
      setTextColor(OpenCrayUiTokens.textTertiary)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
    }, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
      marginStart = dp(4)
    })
  }

  private fun smallAction(iconResId: Int, label: String): LinearLayout = LinearLayout(context).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER
    background = actionTileBackground()
    minimumHeight = dp(40)
    setPadding(dp(12), dp(10), dp(12), dp(10))
    addView(ImageView(context).apply {
      setImageDrawable(tint(iconResId, textPrimary))
    }, LinearLayout.LayoutParams(dp(16), dp(16)))
    addView(TextView(context).apply {
      text = label
      setTextColor(textPrimary)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
      setTypeface(typeface, Typeface.NORMAL)
      includeFontPadding = false
    }, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
      marginStart = dp(6)
    })
  }

  private fun composerInputRow(): View = LinearLayout(context).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    addView(input, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
    addView(plusButton, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
    addView(sendButton, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
  }

  private fun iconButton(iconResId: Int, emphasized: Boolean, description: String): FrameLayout = FrameLayout(context).apply {
    contentDescription = description
    minimumHeight = dp(40)
    minimumWidth = dp(40)
    background = if (emphasized) emphasizedButton(14) else whiteBorderCard(12, Color.parseColor("#D7D7DC"))
    addView(ImageView(context).apply {
      setImageDrawable(tint(iconResId, if (emphasized) Color.WHITE else textPrimary))
      scaleType = ImageView.ScaleType.CENTER_INSIDE
    }, LayoutParams(dp(16), dp(16), Gravity.CENTER))
  }

  private fun removeBadge(id: String): View = FrameLayout(context).apply {
    background = surfaceCard(999, Color.parseColor("#F5F5F7"))
    isClickable = true
    setOnClickListener {
      performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
      listener?.onComposerAttachmentRemoved(id)
    }
    addView(TextView(context).apply {
      text = "×"
      setTextColor(textPrimary)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
      setTypeface(typeface, Typeface.BOLD)
      gravity = Gravity.CENTER
    }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    attachPressMotion(this, pressedScale = 0.88f, pressedAlpha = 0.82f)
  }

  private fun bubbleBackground(role: ChatMessageDisplayRole): GradientDrawable = when (role) {
    ChatMessageDisplayRole.USER -> emphasizedButton(18)
    ChatMessageDisplayRole.SYSTEM -> whiteBorderCard(18, Color.parseColor("#D8D8DE"), Color.parseColor("#ECECEF"))
    ChatMessageDisplayRole.ASSISTANT -> whiteBorderCard(18)
    ChatMessageDisplayRole.TOOL -> whiteBorderCard(18, borderColor, Color.parseColor("#F0F1F5"))
  }

  private fun attachmentBg(role: ChatMessageDisplayRole): GradientDrawable =
    whiteBorderCard(16, Color.parseColor("#D7D7DC"), if (role == ChatMessageDisplayRole.USER) Color.parseColor("#F6F9FF") else Color.WHITE)

  private fun composerBackground(): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    cornerRadius = dp(16).toFloat()
    setColor(Color.WHITE)
  }

  private fun inputBackground(): GradientDrawable = whiteBorderCard(14, Color.parseColor("#D7D7DC"))
  private fun actionTileBackground(): GradientDrawable = surfaceCard(12, Color.parseColor("#F7F7FA"))
  private fun subtleCard(): GradientDrawable = whiteBorderCard(14, Color.parseColor("#E3E3E8"), Color.parseColor("#F7F7FA"))
  private fun imagePlaceholder(): GradientDrawable = surfaceCard(12, Color.parseColor("#DDE7F4"))
  private fun fileBadge(): GradientDrawable = surfaceCard(10, Color.parseColor("#EEF5FF"))
  private fun emphasizedButton(radius: Int = 14): GradientDrawable = whiteBorderCard(radius, accentColor, accentColor)
  private fun surfaceCard(radius: Int, fill: Int = Color.WHITE): GradientDrawable = whiteBorderCard(radius, Color.TRANSPARENT, fill)

  private fun whiteBorderCard(radius: Int, stroke: Int = borderColor, fill: Int = Color.WHITE): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    cornerRadius = dp(radius).toFloat()
    setColor(fill)
    if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
  }

  private fun divider(): View = View(context).apply {
    setBackgroundColor(borderColor)
    layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(1))
  }

  private fun chatLayoutTransition(): LayoutTransition = LayoutTransition().apply {
    setAnimateParentHierarchy(false)
    setDuration(LayoutTransition.CHANGING, motionDurationMedium())
    setDuration(LayoutTransition.APPEARING, motionDurationShort())
    setDuration(LayoutTransition.DISAPPEARING, motionDurationShort())
  }

  private fun updateDrawerVisibility(show: Boolean) {
    if (!animationsEnabled()) {
      drawerScrim.visibility = if (show) View.VISIBLE else View.GONE
      drawerScrim.alpha = if (show) 1f else 0f
      drawer.visibility = if (show) View.VISIBLE else View.GONE
      drawer.translationX = if (show) 0f else -drawerWidthPx.toFloat()
      drawer.alpha = if (show) 1f else 0f
      return
    }

    drawer.animate().cancel()
    drawerScrim.animate().cancel()
    if (show) {
      drawer.visibility = View.VISIBLE
      drawerScrim.visibility = View.VISIBLE
      drawer.animate()
        .translationX(0f)
        .alpha(1f)
        .setDuration(motionDurationMedium())
        .start()
      drawerScrim.animate()
        .alpha(1f)
        .setDuration(motionDurationMedium())
        .start()
    } else {
      drawer.animate()
        .translationX(-drawerWidthPx.toFloat())
        .alpha(0f)
        .setDuration(motionDurationMedium())
        .withEndAction {
          if (!isDrawerOpen) {
            drawer.visibility = View.GONE
          }
        }
        .start()
      drawerScrim.animate()
        .alpha(0f)
        .setDuration(motionDurationMedium())
        .withEndAction {
          if (!isDrawerOpen) {
            drawerScrim.visibility = View.GONE
          }
        }
        .start()
    }
  }

  private fun animateSectionVisibility(
    view: View,
    show: Boolean,
  ) {
    if (!animationsEnabled()) {
      view.visibility = if (show) View.VISIBLE else View.GONE
      view.alpha = 1f
      view.translationY = 0f
      return
    }

    view.animate().cancel()
    if (show) {
      if (view.visibility != View.VISIBLE) {
        view.alpha = 0f
        view.translationY = dp(10).toFloat()
        view.visibility = View.VISIBLE
      }
      view.animate()
        .alpha(1f)
        .translationY(0f)
        .setDuration(motionDurationShort())
        .start()
    } else if (view.visibility == View.VISIBLE) {
      view.animate()
        .alpha(0f)
        .translationY(dp(8).toFloat())
        .setDuration(motionDurationShort())
        .withEndAction {
          if (!show) {
            view.visibility = View.GONE
            view.translationY = 0f
          }
        }
        .start()
    }
  }

  private fun animateBubbleEntrance(view: View) {
    if (!animationsEnabled()) {
      return
    }
    view.alpha = 0f
    view.translationY = dp(10).toFloat()
    view.scaleX = 0.985f
    view.scaleY = 0.985f
    view.animate()
      .alpha(1f)
      .translationY(0f)
      .scaleX(1f)
      .scaleY(1f)
      .setDuration(motionDurationMedium())
      .start()
  }

  private fun animateSelectionPulse(view: View) {
    if (!animationsEnabled()) {
      return
    }
    view.scaleX = 0.985f
    view.scaleY = 0.985f
    view.animate()
      .scaleX(1f)
      .scaleY(1f)
      .setDuration(motionDurationMedium())
      .start()
  }

  private fun attachPressMotion(
    view: View,
    pressedScale: Float = 0.97f,
    pressedAlpha: Float = 0.92f,
  ) {
    view.setOnTouchListener { touchedView, event ->
      when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
          if (animationsEnabled()) {
            touchedView.animate()
              .scaleX(pressedScale)
              .scaleY(pressedScale)
              .alpha(pressedAlpha)
              .setDuration(motionDurationShort())
              .start()
          }
        }

        MotionEvent.ACTION_CANCEL,
        MotionEvent.ACTION_UP -> {
          if (animationsEnabled()) {
            touchedView.animate()
              .scaleX(1f)
              .scaleY(1f)
              .alpha(1f)
              .setDuration(motionDurationShort())
              .start()
          } else {
            touchedView.scaleX = 1f
            touchedView.scaleY = 1f
            touchedView.alpha = 1f
          }
        }
      }
      false
    }
  }

  private fun animationsEnabled(): Boolean = ValueAnimator.areAnimatorsEnabled()

  private fun motionDurationShort(): Long = if (animationsEnabled()) 160L else 0L

  private fun motionDurationMedium(): Long = if (animationsEnabled()) 220L else 0L

  private fun tint(iconResId: Int, color: Int) =
    checkNotNull(ContextCompat.getDrawable(context, iconResId)).mutate().also {
      DrawableCompat.setTint(it, color)
    }

  private fun scrollBottom(animated: Boolean) {
    scrollView.post {
      val targetY = (content.bottom - scrollView.height).coerceAtLeast(0)
      if (animated && animationsEnabled()) {
        scrollView.smoothScrollTo(0, targetY)
      } else {
        scrollView.scrollTo(0, targetY)
      }
    }
  }

  private fun messageLp(index: Int) = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
    topMargin = dp(if (index == 0) 18 else 12)
  }

  private fun sectionLp(top: Int) = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
    topMargin = dp(top)
  }

  private fun dp(value: Int): Int = context.ocDp(value)
}
