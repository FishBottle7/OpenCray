package com.opencray.ui.chat

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.Layout
import android.text.TextUtils
import android.text.TextWatcher
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import com.opencray.ui.design.OpenCrayButtonTone
import com.opencray.ui.design.OpenCraySurfaceTone
import com.opencray.ui.design.OpenCrayUiTokens
import com.opencray.ui.design.ocButtonBackground
import com.opencray.ui.design.ocBottomNavBackground
import com.opencray.ui.design.ocCardBackground
import com.opencray.ui.design.ocDp
import com.opencray.ui.design.ocInputBackground
import com.opencray.ui.design.ocLinearBlockParams
import com.opencray.ui.design.ocPillBackground
import com.opencray.ui.design.ocSurfaceBackground
import com.opencray.ui.design.ocTopBarBackground
import org.opencray.ui.R
import kotlin.math.min

private const val DEFAULT_CONVERSATION_EMPTY_MESSAGE_TEXT =
  "No messages yet. Start a request below to begin a local session."

enum class ChatMode(
  val displayName: String,
  val helperText: String,
) {
  SAFE("Safe", "Requests approval before sensitive actions."),
  AUTO("Auto", "Keeps the flow moving with fewer interruptions."),
  DEVELOPER("Developer", "Shows deeper control surfaces for debugging and power use."),
}

enum class ApprovalDecision {
  APPROVE,
  DENY,
}

enum class ApprovalPromptStatus {
  HIDDEN,
  REQUIRED,
  APPROVED,
  DENIED,
}

data class ApprovalPromptState(
  val status: ApprovalPromptStatus = ApprovalPromptStatus.HIDDEN,
  val title: String = "",
  val message: String = "",
  val decisionNote: String = "",
  val approveLabel: String = "Approve",
  val denyLabel: String = "Deny",
)

data class ConversationHeaderState(
  val title: String = "OpenCray Workspace",
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
) {
  init {
    require(sessionId.isNotBlank()) { "sessionId must not be blank." }
    require(title.isNotBlank()) { "title must not be blank." }
  }
}

enum class ChatMessageDisplayRole {
  SYSTEM,
  USER,
  ASSISTANT,
  TOOL,
}

enum class ConversationMessageRole {
  SYSTEM,
  USER,
  ASSISTANT,
  TOOL,
}

enum class ChatMessageAction {
  REGENERATE,
  RECALL,
  DELETE,
  EDIT,
  BRANCH,
  SHARE,
}

data class ChatMessageItemState(
  val messageId: String,
  val role: ChatMessageDisplayRole,
  val body: String,
  val meta: String = "",
) {
  init {
    require(messageId.isNotBlank()) { "messageId must not be blank." }
    require(body.isNotBlank()) { "body must not be blank." }
  }
}

data class ConversationMessageState(
  val messageId: String,
  val role: ConversationMessageRole,
  val text: String,
  val supportingText: String = "",
) {
  init {
    require(messageId.isNotBlank()) { "messageId must not be blank." }
    require(text.isNotBlank()) { "text must not be blank." }
  }
}

data class ChatScreenState(
  val title: String = "OpenCray Workspace",
  val subtitle: String = "",
  val statusLabel: String = "",
  val statusDetail: String = "",
  val drawerSummary: String = "",
  val sessions: List<ChatSessionListItemState> = emptyList(),
  val messages: List<ChatMessageItemState> = emptyList(),
  val emptyMessage: String = DEFAULT_CONVERSATION_EMPTY_MESSAGE_TEXT,
  val draftText: String = "",
  val draftHint: String = "Message OpenCray",
  val composerAssistiveText: String = "Use a file reference or a slash command when the request needs more context.",
) {
  init {
    require(title.isNotBlank()) { "title must not be blank." }
    require(emptyMessage.isNotBlank()) { "emptyMessage must not be blank." }
    require(draftHint.isNotBlank()) { "draftHint must not be blank." }
  }
}

private data class MessageActionSheetItem(
  val action: ChatMessageAction,
  val title: String,
  val subtitle: String,
  val iconResId: Int,
  val isDestructive: Boolean = false,
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

    fun onMessageActionRequested(
      messageId: String,
      action: ChatMessageAction,
    ) = Unit

    fun onQueueVisibilityChanged(isVisible: Boolean) = Unit

    fun onModeSelected(mode: ChatMode) = Unit

    fun onApprovalDecision(decision: ApprovalDecision) = Unit

    fun onResetAgentIdentity() = Unit
  }

  companion object {
    const val DEFAULT_CONVERSATION_EMPTY_MESSAGE = DEFAULT_CONVERSATION_EMPTY_MESSAGE_TEXT
  }

  private val shellBackground = OpenCrayUiTokens.shellBackground
  private val surfaceColor = OpenCrayUiTokens.surface
  private val borderColor = OpenCrayUiTokens.border
  private val dividerColor = OpenCrayUiTokens.border
  private val textPrimary = OpenCrayUiTokens.textPrimary
  private val textSecondary = OpenCrayUiTokens.textSecondary
  private val accentColor = OpenCrayUiTokens.primary
  private val accentSoftColor = OpenCrayUiTokens.surfaceInfo
  private val assistantBubbleColor = OpenCrayUiTokens.surface
  private val userBubbleColor = OpenCrayUiTokens.primary
  private val userTextColor = Color.WHITE
  private val systemBubbleColor = OpenCrayUiTokens.surfaceMuted
  private val systemAccentColor = OpenCrayUiTokens.borderStrong
  private val assistantAccentColor = OpenCrayUiTokens.border
  private val bubbleTextMaxWidth = (resources.displayMetrics.widthPixels * 0.74f).toInt()
  private val drawerWidthPx = min(dp(320), (resources.displayMetrics.widthPixels * 0.84f).toInt())

  private var listener: Listener? = null
  private var state: ChatScreenState = ChatScreenState()
  private var isDrawerOpen: Boolean = false
  private var isComposerActionsOpen: Boolean = false
  private var activeMessageActionItem: ChatMessageItemState? = null
  private var localDraftText: String = ""
  private var suppressDraftCallback: Boolean = false

  private val shellContainer = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    setBackgroundColor(shellBackground)
  }

  private val topBar = LinearLayout(context).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    background = topBarBackground()
    minimumHeight = dp(56)
    setPadding(dp(10), dp(8), dp(10), dp(8))
  }
  private val sessionToggleButton = iconButton(
    iconResId = R.drawable.ic_chat_menu,
    description = "Sessions",
  )
  private val topBarTitleView = TextView(context).apply {
    setTextColor(textPrimary)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
    setTypeface(typeface, Typeface.BOLD)
    includeFontPadding = false
    gravity = Gravity.CENTER_VERTICAL
    maxLines = 1
  }
  private val topBarSubtitleView = TextView(context).apply {
    setTextColor(textSecondary)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
    includeFontPadding = false
    maxLines = 1
  }
  private val newSessionTopButton = iconButton(
    iconResId = R.drawable.ic_chat_plus,
    description = "New session",
  )

  private val messageScrollView = ScrollView(context).apply {
    isFillViewport = true
    isVerticalScrollBarEnabled = false
  }
  private val messageContainer = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(16), dp(20), dp(16), dp(12))
  }
  private val conversationHeaderCard = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    background = actionSheetBackground()
    setPadding(dp(18), dp(18), dp(18), dp(18))
  }
  private val conversationHeaderEyebrowView = TextView(context).apply {
    text = "ACTIVE SESSION"
    setTextColor(accentColor)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
    setTypeface(typeface, Typeface.BOLD)
    includeFontPadding = false
  }
  private val conversationHeaderTitleView = TextView(context).apply {
    setTextColor(textPrimary)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
    setTypeface(typeface, Typeface.BOLD)
    includeFontPadding = false
    maxLines = 2
    ellipsize = TextUtils.TruncateAt.END
  }
  private val conversationHeaderSubtitleView = TextView(context).apply {
    setTextColor(textSecondary)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
    setLineSpacing(0f, 1.12f)
    includeFontPadding = false
  }
  private val conversationHeaderMetaRow = LinearLayout(context).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
  }
  private val conversationHeaderHintView = TextView(context).apply {
    setTextColor(textSecondary)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
    includeFontPadding = false
  }
  private val conversationStatusCard = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    background = quietButtonBackground()
    setPadding(dp(14), dp(14), dp(14), dp(14))
  }
  private val conversationStatusLabelView = TextView(context).apply {
    setTextColor(textPrimary)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
    setTypeface(typeface, Typeface.BOLD)
    includeFontPadding = false
    maxLines = 1
    ellipsize = TextUtils.TruncateAt.END
  }
  private val conversationStatusDetailView = TextView(context).apply {
    setTextColor(textSecondary)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
    setLineSpacing(0f, 1.12f)
    includeFontPadding = false
  }

  private val composerCard = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    background = composerBackground()
    setPadding(dp(12), dp(10), dp(12), dp(12))
  }
  private val composerActionSheet = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    background = actionSheetBackground()
    setPadding(dp(10), dp(10), dp(10), dp(10))
    visibility = View.GONE
  }
  private val composerActionsRow = LinearLayout(context).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.START
  }
  private val composerHelperView = TextView(context).apply {
    text = ""
    setTextColor(textSecondary)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
    includeFontPadding = false
    visibility = View.GONE
  }
  private val insertFileButton = iconLabelButton(
    iconResId = R.drawable.ic_chat_attach,
    label = "File",
  )
  private val insertCommandButton = iconLabelButton(
    iconResId = R.drawable.ic_chat_terminal,
    label = "Command",
  )
  private val draftInput = EditText(context).apply {
    setTextColor(textPrimary)
    setHintTextColor(Color.parseColor("#8A97A6"))
    background = inputBackground()
    minHeight = dp(46)
    setPadding(dp(14), dp(11), dp(14), dp(11))
    isSingleLine = false
    maxLines = 4
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
  }
  private val composerActionsToggleButton = iconButton(
    iconResId = R.drawable.ic_chat_plus,
    description = "More actions",
  )
  private val sendButton = iconButton(
    iconResId = R.drawable.ic_chat_send,
    description = "Send",
    emphasized = true,
  )

  private val drawerScrim = View(context).apply {
    setBackgroundColor(Color.parseColor("#660B1220"))
    visibility = View.GONE
    setOnClickListener {
      isDrawerOpen = false
      renderDrawer()
    }
  }
  private val drawerPanel = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    background = drawerBackground()
    setPadding(dp(20), dp(18), dp(20), dp(20))
    visibility = View.GONE
  }
  private val drawerScrollView = ScrollView(context).apply {
    isVerticalScrollBarEnabled = false
    isFillViewport = true
  }
  private val drawerTitleView = TextView(context).apply {
    text = "Sessions"
    setTextColor(textPrimary)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
    setTypeface(typeface, Typeface.BOLD)
  }
  private val drawerSubtitleView = TextView(context).apply {
    setTextColor(textSecondary)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
    includeFontPadding = false
  }
  private val drawerCurrentCard = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    background = actionSheetBackground()
    setPadding(dp(16), dp(16), dp(16), dp(16))
  }
  private val drawerCurrentEyebrowView = TextView(context).apply {
    text = "CURRENT SESSION"
    setTextColor(accentColor)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
    setTypeface(typeface, Typeface.BOLD)
    includeFontPadding = false
  }
  private val drawerCurrentTitleView = TextView(context).apply {
    setTextColor(textPrimary)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
    setTypeface(typeface, Typeface.BOLD)
    includeFontPadding = false
    maxLines = 2
    ellipsize = TextUtils.TruncateAt.END
  }
  private val drawerCurrentDetailView = TextView(context).apply {
    setTextColor(textSecondary)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
    setLineSpacing(0f, 1.1f)
    includeFontPadding = false
  }
  private val createSessionButton = iconButton(
    iconResId = R.drawable.ic_chat_plus,
    description = "New session",
    emphasized = true,
  )
  private val sessionListContainer = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
  }
  private val messageActionScrim = View(context).apply {
    setBackgroundColor(Color.parseColor("#520B1220"))
    visibility = View.GONE
    setOnClickListener { dismissMessageActionSheet() }
  }
  private val messageActionSheet = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    background = messageActionSheetBackground()
    setPadding(dp(16), dp(10), dp(16), dp(20))
    visibility = View.GONE
  }
  private val messageActionHandle = View(context).apply {
    background = handleBackground()
  }
  private val messageActionTitleView = TextView(context).apply {
    setTextColor(textPrimary)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
    setTypeface(typeface, Typeface.BOLD)
    includeFontPadding = false
    maxLines = 1
    ellipsize = TextUtils.TruncateAt.END
  }
  private val messageActionSubtitleView = TextView(context).apply {
    setTextColor(textSecondary)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
    includeFontPadding = false
    maxLines = 2
    ellipsize = TextUtils.TruncateAt.END
  }
  private val messageActionListContainer = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
  }

  init {
    setBackgroundColor(shellBackground)

    addView(
      shellContainer,
      LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
      ),
    )
    addView(
      drawerScrim,
      LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
      ),
    )
    addView(
      drawerPanel,
      LayoutParams(
        drawerWidthPx,
        ViewGroup.LayoutParams.MATCH_PARENT,
        Gravity.START,
      ),
    )
    addView(
      messageActionScrim,
      LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
      ),
    )
    addView(
      messageActionSheet,
      LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        Gravity.BOTTOM,
      ),
    )

    setupShell()
    setupDrawer()
    setupComposer()
    setupMessageActionSheet()
    submitState(state)
  }

  fun setListener(listener: Listener?) {
    this.listener = listener
  }

  fun submitState(newState: ChatScreenState) {
    state = newState.copy(
      sessions = newState.sessions.toList(),
      messages = newState.messages.toList(),
    )
    localDraftText = newState.draftText
    renderTopBar()
    renderMessages()
    renderComposer()
    renderDrawer()
    renderMessageActionSheet()
  }

  fun snapshotState(): ChatScreenState = state.copy(
    sessions = state.sessions.toList(),
    messages = state.messages.toList(),
    draftText = localDraftText,
  )

  private fun setupShell() {
    topBarTitleView.ellipsize = TextUtils.TruncateAt.END
    topBarSubtitleView.ellipsize = TextUtils.TruncateAt.END
    topBar.addView(
      sessionToggleButton,
      LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
      ),
    )
    topBar.addView(
      LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(topBarTitleView)
        addView(topBarSubtitleView, sectionParams(topDp = 2))
      },
      LinearLayout.LayoutParams(
        0,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        1f,
      ).apply {
        marginStart = dp(8)
        marginEnd = dp(8)
      },
    )
    topBar.addView(
      newSessionTopButton,
      LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
      ),
    )

    messageScrollView.addView(
      messageContainer,
      LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
      ),
    )
    conversationHeaderCard.addView(conversationHeaderEyebrowView)
    conversationHeaderCard.addView(conversationHeaderTitleView, sectionParams(topDp = 8))
    conversationHeaderCard.addView(conversationHeaderSubtitleView, sectionParams(topDp = 8))
    conversationHeaderCard.addView(conversationHeaderMetaRow, sectionParams(topDp = 14))
    conversationStatusCard.addView(conversationStatusLabelView)
    conversationStatusCard.addView(conversationStatusDetailView, sectionParams(topDp = 6))
    conversationHeaderCard.addView(conversationStatusCard, sectionParams(topDp = 14))
    conversationHeaderCard.addView(conversationHeaderHintView, sectionParams(topDp = 14))

    shellContainer.addView(
      topBar,
      LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
      ),
    )
    shellContainer.addView(
      drawerDivider(),
      LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        dp(1),
      ),
    )
    shellContainer.addView(
      messageScrollView,
      LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        0,
        1f,
      ),
    )
    shellContainer.addView(
      composerCard,
      LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
      ).apply {
        bottomMargin = dp(2)
      },
    )

    sessionToggleButton.setOnClickListener {
      if (!isDrawerOpen) {
        dismissMessageActionSheet()
      }
      isDrawerOpen = !isDrawerOpen
      renderDrawer()
    }
    newSessionTopButton.setOnClickListener {
      dismissMessageActionSheet()
      listener?.onNewSessionRequested()
    }
  }

  private fun setupDrawer() {
    drawerPanel.addView(
      LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(
          LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
              drawerTitleView,
              LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(createSessionButton)
          },
        )
        addView(drawerSubtitleView, sectionParams(topDp = 6))
        drawerCurrentCard.addView(drawerCurrentEyebrowView)
        drawerCurrentCard.addView(drawerCurrentTitleView, sectionParams(topDp = 8))
        drawerCurrentCard.addView(drawerCurrentDetailView, sectionParams(topDp = 6))
        addView(drawerCurrentCard, sectionParams(topDp = 14))
      },
      LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
      ),
    )
    drawerPanel.addView(drawerDivider(), sectionParams(topDp = 14, bottomDp = 8))
    drawerScrollView.addView(
      sessionListContainer,
      LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
      ),
    )
    drawerPanel.addView(
      drawerScrollView,
      LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        0,
        1f,
      ),
    )

    createSessionButton.setOnClickListener {
      isDrawerOpen = false
      renderDrawer()
      listener?.onNewSessionRequested()
    }
  }

  private fun setupComposer() {
    composerActionsRow.addView(
      insertFileButton,
      LinearLayout.LayoutParams(
        0,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        1f,
      ),
    )
    composerActionsRow.addView(
      insertCommandButton,
      LinearLayout.LayoutParams(
        0,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        1f,
      ).apply {
        marginStart = dp(8)
      },
    )

    composerCard.addView(
      composerActionSheet,
      LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
      ),
    )
    composerCard.addView(
      composerHelperView,
      sectionParams(topDp = 8),
    )
    composerActionSheet.addView(
      composerActionsRow,
      sectionParams(topDp = 8),
    )
    composerCard.addView(
      LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.BOTTOM
        addView(
          draftInput,
          LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f,
          ),
        )
        addView(
          composerActionsToggleButton,
          LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
          ).apply {
            marginStart = dp(10)
          },
        )
        addView(
          sendButton,
          LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
          ).apply {
            marginStart = dp(10)
          },
        )
      },
      sectionParams(topDp = 10),
    )

    isComposerActionsOpen = true

    draftInput.addTextChangedListener(
      object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

        override fun afterTextChanged(s: Editable?) {
          if (!suppressDraftCallback) {
            localDraftText = s?.toString().orEmpty()
            sendButton.isEnabled = localDraftText.isNotBlank()
          }
        }
      },
    )
    draftInput.setOnFocusChangeListener { _, hasFocus ->
      if (hasFocus) {
        isComposerActionsOpen = false
        renderComposer()
        scrollConversationToBottom()
      }
      listener?.onComposerFocusChanged(hasFocus)
    }
    draftInput.setOnClickListener {
      scrollConversationToBottom()
    }

    composerActionsToggleButton.setOnClickListener {
      isComposerActionsOpen = !isComposerActionsOpen
      renderComposer()
      scrollConversationToBottom()
    }
    insertFileButton.setOnClickListener {
      insertIntoDraft("@file ")
    }
    insertCommandButton.setOnClickListener {
      insertIntoDraft("/command ")
    }
    sendButton.setOnClickListener {
      val submittedText = localDraftText.trim()
      if (submittedText.isBlank()) {
        return@setOnClickListener
      }
      localDraftText = ""
      isComposerActionsOpen = false
      renderComposer()
      scrollConversationToBottom()
      listener?.onMessageSubmitted(submittedText)
    }
  }

  private fun setupMessageActionSheet() {
    messageActionSheet.addView(
      messageActionHandle,
      LinearLayout.LayoutParams(
        dp(40),
        dp(5),
      ).apply {
        gravity = Gravity.CENTER_HORIZONTAL
      },
    )
    messageActionSheet.addView(
      messageActionTitleView,
      sectionParams(topDp = 14),
    )
    messageActionSheet.addView(
      messageActionSubtitleView,
      sectionParams(topDp = 4, bottomDp = 12),
    )
    messageActionSheet.addView(
      drawerDivider(),
      sectionParams(bottomDp = 8),
    )
    messageActionSheet.addView(
      messageActionListContainer,
      LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
      ),
    )
  }

  private fun renderTopBar() {
    topBarTitleView.text = "OpenCray"
    topBarSubtitleView.text = state.title
    topBarSubtitleView.visibility = View.VISIBLE
  }

  private fun renderMessages() {
    messageContainer.removeAllViews()
    renderConversationHeader()
    messageContainer.addView(conversationHeaderCard)
    val activeMessageId = activeMessageActionItem?.messageId
    if (activeMessageId != null && state.messages.none { message -> message.messageId == activeMessageId }) {
      dismissMessageActionSheet()
    }

    if (state.messages.isEmpty()) {
      messageContainer.addView(
        View(context),
        sectionParams(topDp = 8),
      )
      scrollConversationToBottom()
      return
    }

    state.messages.forEachIndexed { index, item ->
      messageContainer.addView(
        messageBubble(item),
        sectionParams(topDp = if (index == 0) 24 else 12),
      )
    }

    scrollConversationToBottom()
  }

  private fun renderComposer() {
    composerActionSheet.visibility = if (isComposerActionsOpen) View.VISIBLE else View.GONE
    composerHelperView.visibility = View.GONE
    composerHelperView.text = state.composerAssistiveText
    draftInput.hint = state.draftHint
    if (draftInput.text.toString() != localDraftText) {
      suppressDraftCallback = true
      draftInput.setText(localDraftText)
      draftInput.setSelection(draftInput.text.length)
      suppressDraftCallback = false
    }
    sendButton.isEnabled = localDraftText.isNotBlank()
    sendButton.alpha = if (sendButton.isEnabled) 1f else 0.42f
    composerActionsToggleButton.alpha = if (isComposerActionsOpen) 0.7f else 1f
  }

  private fun renderDrawer() {
    drawerScrim.visibility = if (isDrawerOpen) View.VISIBLE else View.GONE
    drawerPanel.visibility = if (isDrawerOpen) View.VISIBLE else View.GONE
    sessionListContainer.removeAllViews()
    drawerSubtitleView.text = "${state.sessions.size} saved sessions"
    drawerCurrentTitleView.text = state.title
    drawerCurrentDetailView.text = buildList {
      if (state.statusLabel.isNotBlank()) {
        add(state.statusLabel)
      }
      if (state.drawerSummary.isNotBlank()) {
        add(state.drawerSummary)
      }
    }.joinToString(" • ")
    drawerCurrentCard.visibility =
      if (state.title.isBlank() && drawerCurrentDetailView.text.isBlank()) View.GONE else View.VISIBLE

    if (state.sessions.isEmpty()) {
      sessionListContainer.addView(
        drawerEmptyStateCard(),
        LinearLayout.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT,
          ViewGroup.LayoutParams.WRAP_CONTENT,
        ),
      )
      return
    }

    state.sessions.forEachIndexed { index, session ->
      sessionListContainer.addView(
        sessionRow(session),
        LinearLayout.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT,
          ViewGroup.LayoutParams.WRAP_CONTENT,
        ),
      )
      if (index != state.sessions.lastIndex) {
        sessionListContainer.addView(drawerDivider(), sectionParams(topDp = 4, bottomDp = 4))
      }
    }
  }

  private fun renderConversationHeader() {
    conversationHeaderTitleView.text = state.title
    conversationHeaderSubtitleView.text = state.subtitle
    conversationHeaderSubtitleView.visibility = if (state.subtitle.isBlank()) View.GONE else View.VISIBLE
    conversationHeaderMetaRow.removeAllViews()
    conversationHeaderMetaRow.addView(summaryPill("${state.sessions.size} sessions"))
    conversationHeaderMetaRow.addView(summaryPill("${state.messages.size} messages"), pillParams())
    conversationHeaderMetaRow.addView(summaryPill("Local transcript"), pillParams())
    conversationStatusLabelView.text = state.statusLabel
    conversationStatusDetailView.text = state.statusDetail
    conversationStatusCard.visibility = View.GONE
    conversationHeaderHintView.visibility = View.GONE
  }

  private fun renderMessageActionSheet() {
    val item = activeMessageActionItem
    val isVisible = item != null
    messageActionScrim.visibility = if (isVisible) View.VISIBLE else View.GONE
    messageActionSheet.visibility = if (isVisible) View.VISIBLE else View.GONE
    messageActionListContainer.removeAllViews()
    if (item == null) {
      return
    }

    messageActionTitleView.text = roleLabel(item.role)
    messageActionSubtitleView.text = item.meta.ifBlank { item.body.take(140) }
    messageActionSubtitleView.maxLines = if (item.meta.isBlank()) 3 else 2

    val actionItems = messageActionsFor(item)
    actionItems.forEachIndexed { index, actionItem ->
      messageActionListContainer.addView(messageActionRow(actionItem))
      if (index != actionItems.lastIndex) {
        messageActionListContainer.addView(drawerDivider(), sectionParams(topDp = 2, bottomDp = 2))
      }
    }
  }

  private fun sessionRow(session: ChatSessionListItemState): View = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    layoutParams = LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT,
      ViewGroup.LayoutParams.WRAP_CONTENT,
    )
    background = sessionRowBackground(session.isSelected)
    setPadding(dp(16), dp(14), dp(16), dp(14))
    isClickable = true
    isFocusable = true
    setOnClickListener {
      isDrawerOpen = false
      renderDrawer()
      listener?.onSessionSelected(session.sessionId)
    }

    addView(
      LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
          TextView(context).apply {
            text = session.title
            setTextColor(textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
          },
          LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        if (session.isSelected) {
          addView(
            summaryPill("Current"),
          )
        }
      },
    )

    if (session.preview.isNotBlank()) {
      addView(
        TextView(context).apply {
          text = session.preview
          setTextColor(textSecondary)
          setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
          setLineSpacing(0f, 1.1f)
          maxLines = 2
          ellipsize = TextUtils.TruncateAt.END
        },
        sectionParams(topDp = 8),
      )
    }

    if (session.meta.isNotBlank()) {
      addView(
        TextView(context).apply {
          text = session.meta
          setTextColor(OpenCrayUiTokens.textTertiary)
          setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
          maxLines = 1
          ellipsize = TextUtils.TruncateAt.END
        },
        sectionParams(topDp = 10),
      )
    }
  }

  private fun messageBubble(item: ChatMessageItemState): View = LinearLayout(context).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = bubbleGravity(item.role)
    layoutParams = LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT,
      ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    addView(
      LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = bubbleBackground(item.role)
        setPadding(
          if (item.role == ChatMessageDisplayRole.SYSTEM) dp(14) else dp(16),
          if (item.role == ChatMessageDisplayRole.SYSTEM) dp(10) else dp(12),
          if (item.role == ChatMessageDisplayRole.SYSTEM) dp(14) else dp(16),
          if (item.role == ChatMessageDisplayRole.SYSTEM) dp(10) else dp(12),
        )

        addView(
          TextView(context).apply {
            text = roleLabel(item.role)
            setTextColor(roleLabelColor(item.role))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (item.role == ChatMessageDisplayRole.SYSTEM) 11f else 12f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = if (item.role == ChatMessageDisplayRole.SYSTEM) Gravity.CENTER_HORIZONTAL else Gravity.START
          },
        )
        addView(
          TextView(context).apply {
            text = item.body
            maxWidth = bubbleTextMaxWidth
            setTextColor(messageTextColor(item.role))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (item.role == ChatMessageDisplayRole.TOOL) 13f else 15f)
            setLineSpacing(0f, 1.14f)
            breakStrategy = Layout.BREAK_STRATEGY_HIGH_QUALITY
            hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NORMAL
            gravity = if (item.role == ChatMessageDisplayRole.SYSTEM) Gravity.CENTER_HORIZONTAL else Gravity.START
          },
          sectionParams(topDp = 6),
        )
        if (item.meta.isNotBlank()) {
          addView(
            TextView(context).apply {
              text = item.meta
              setTextColor(roleMetaColor(item.role))
              setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
              gravity = if (item.role == ChatMessageDisplayRole.SYSTEM) Gravity.CENTER_HORIZONTAL else Gravity.START
            },
            sectionParams(topDp = 10),
          )
        }
        setOnLongClickListener {
          openMessageActionSheet(item)
          true
        }
      },
      LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
      ),
    )
  }

  private fun emptyStateCard(message: String): View = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    gravity = Gravity.CENTER_HORIZONTAL
    background = actionSheetBackground()
    setPadding(dp(20), dp(20), dp(20), dp(20))
    addView(
      TextView(context).apply {
        text = "No messages yet"
        setTextColor(textPrimary)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTypeface(typeface, Typeface.BOLD)
      },
    )
    addView(
      TextView(context).apply {
        text = message
        setTextColor(textSecondary)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        gravity = Gravity.CENTER_HORIZONTAL
      },
      sectionParams(topDp = 8),
    )
  }

  private fun drawerEmptyStateCard(): View = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    background = quietButtonBackground()
    setPadding(dp(16), dp(16), dp(16), dp(16))
    addView(
      TextView(context).apply {
        text = "No sessions yet"
        setTextColor(textPrimary)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTypeface(typeface, Typeface.BOLD)
      },
    )
    addView(
      TextView(context).apply {
        text = "Start a new chat to create the first local session."
        setTextColor(textSecondary)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
      },
      sectionParams(topDp = 6),
    )
  }

  private fun insertIntoDraft(token: String) {
    localDraftText = when {
      localDraftText.isBlank() -> token
      localDraftText.endsWith(" ") -> localDraftText + token
      else -> "$localDraftText $token"
    }
    renderComposer()
    scrollConversationToBottom()
  }

  private fun openMessageActionSheet(item: ChatMessageItemState) {
    activeMessageActionItem = item
    isDrawerOpen = false
    renderDrawer()
    renderMessageActionSheet()
  }

  private fun dismissMessageActionSheet() {
    activeMessageActionItem = null
    renderMessageActionSheet()
  }

  private fun iconButton(
    iconResId: Int,
    description: String,
    emphasized: Boolean = false,
  ): FrameLayout = FrameLayout(context).apply {
    contentDescription = description
    minimumHeight = dp(42)
    minimumWidth = dp(42)
    isClickable = true
    isFocusable = true
    background = if (emphasized) {
      primaryButtonBackground()
    } else {
      quietButtonBackground()
    }
    addView(
      ImageView(context).apply {
        setImageDrawable(tintedDrawable(iconResId, if (emphasized) Color.WHITE else textPrimary))
      },
      LayoutParams(
        dp(20),
        dp(20),
        Gravity.CENTER,
      ),
    )
  }

  private fun messageActionsFor(item: ChatMessageItemState): List<MessageActionSheetItem> {
    val sharedItems = listOf(
      MessageActionSheetItem(
        action = ChatMessageAction.BRANCH,
        title = "Branch",
        subtitle = "Start a new session from this point",
        iconResId = R.drawable.ic_chat_branch,
      ),
      MessageActionSheetItem(
        action = ChatMessageAction.SHARE,
        title = "Share",
        subtitle = "Send this message elsewhere",
        iconResId = R.drawable.ic_chat_share,
      ),
    )
    return when (item.role) {
      ChatMessageDisplayRole.USER -> listOf(
        MessageActionSheetItem(
          action = ChatMessageAction.EDIT,
          title = "Edit",
          subtitle = "Update this prompt and clear later replies",
          iconResId = R.drawable.ic_chat_edit,
        ),
        MessageActionSheetItem(
          action = ChatMessageAction.RECALL,
          title = "Recall",
          subtitle = "Remove this message and the replies after it",
          iconResId = R.drawable.ic_chat_undo,
          isDestructive = true,
        ),
      ) + sharedItems

      ChatMessageDisplayRole.ASSISTANT -> listOf(
        MessageActionSheetItem(
          action = ChatMessageAction.REGENERATE,
          title = "Regenerate",
          subtitle = "Ask for a fresh answer from the last user prompt",
          iconResId = R.drawable.ic_chat_refresh,
        ),
        MessageActionSheetItem(
          action = ChatMessageAction.DELETE,
          title = "Delete",
          subtitle = "Remove this reply from the local transcript",
          iconResId = R.drawable.ic_chat_trash,
          isDestructive = true,
        ),
      ) + sharedItems

      ChatMessageDisplayRole.TOOL,
      ChatMessageDisplayRole.SYSTEM -> listOf(
        MessageActionSheetItem(
          action = ChatMessageAction.DELETE,
          title = "Delete",
          subtitle = "Remove this message from the local transcript",
          iconResId = R.drawable.ic_chat_trash,
          isDestructive = true,
        ),
      ) + sharedItems
    }
  }

  private fun messageActionRow(item: MessageActionSheetItem): View = LinearLayout(context).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    minimumHeight = dp(58)
    setPadding(dp(2), dp(12), dp(2), dp(12))
    isClickable = true
    isFocusable = true
    setOnClickListener {
      val activeMessageId = activeMessageActionItem?.messageId ?: return@setOnClickListener
      dismissMessageActionSheet()
      listener?.onMessageActionRequested(activeMessageId, item.action)
    }

    addView(
      FrameLayout(context).apply {
        background = actionIconContainerBackground(item.isDestructive)
        addView(
          ImageView(context).apply {
            setImageDrawable(
              tintedDrawable(
                item.iconResId,
                if (item.isDestructive) Color.parseColor("#C94747") else textPrimary,
              ),
            )
          },
          LayoutParams(
            dp(18),
            dp(18),
            Gravity.CENTER,
          ),
        )
      },
      LinearLayout.LayoutParams(
        dp(36),
        dp(36),
      ),
    )
    addView(
      LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(
          TextView(context).apply {
            text = item.title
            setTextColor(if (item.isDestructive) Color.parseColor("#A63C3C") else textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
          },
        )
        addView(
          TextView(context).apply {
            text = item.subtitle
            setTextColor(textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
          },
          sectionParams(topDp = 3),
        )
      },
      LinearLayout.LayoutParams(
        0,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        1f,
      ).apply {
        marginStart = dp(12)
      },
    )
  }

  private fun iconLabelButton(
    iconResId: Int,
    label: String,
  ): LinearLayout = LinearLayout(context).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    contentDescription = label
    minimumHeight = dp(40)
    isClickable = true
    isFocusable = true
    background = secondaryButtonBackground()
    setPadding(dp(12), dp(9), dp(12), dp(9))
    addView(
      ImageView(context).apply {
        setImageDrawable(tintedDrawable(iconResId, textPrimary))
      },
      LinearLayout.LayoutParams(
        dp(16),
        dp(16),
      ),
    )
    addView(
      TextView(context).apply {
        text = label
        setTextColor(textPrimary)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTypeface(typeface, Typeface.BOLD)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
      },
      LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
      ).apply {
        marginStart = dp(8)
      },
    )
  }

  private fun summaryPill(text: String): TextView = TextView(context).apply {
    this.text = text
    setTextColor(textPrimary)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
    setTypeface(typeface, Typeface.BOLD)
    background = context.ocPillBackground(
      fillColor = OpenCrayUiTokens.surface,
      strokeColor = OpenCrayUiTokens.border,
      strokeWidthDp = 1,
    )
    setPadding(dp(10), dp(6), dp(10), dp(6))
  }

  private fun pillParams(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
    ViewGroup.LayoutParams.WRAP_CONTENT,
    ViewGroup.LayoutParams.WRAP_CONTENT,
  ).apply {
    marginStart = dp(8)
  }

  private fun topBarBackground(): GradientDrawable = GradientDrawable().apply {
    val drawable = context.ocTopBarBackground()
    shape = drawable.shape
    cornerRadius = drawable.cornerRadius
    color = drawable.color
    setStroke(dp(1), OpenCrayUiTokens.border)
  }

  private fun drawerBackground(): GradientDrawable = GradientDrawable().apply {
    val drawable = context.ocSurfaceBackground(surfaceColor)
    shape = drawable.shape
    cornerRadius = drawable.cornerRadius
    color = drawable.color
  }

  private fun composerBackground(): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    cornerRadii = floatArrayOf(
      dp(OpenCrayUiTokens.radiusLargeCard).toFloat(),
      dp(OpenCrayUiTokens.radiusLargeCard).toFloat(),
      dp(OpenCrayUiTokens.radiusLargeCard).toFloat(),
      dp(OpenCrayUiTokens.radiusLargeCard).toFloat(),
      0f,
      0f,
      0f,
      0f,
    )
    setColor(surfaceColor)
    setStroke(dp(1), borderColor)
  }

  private fun messageActionSheetBackground(): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    cornerRadii = floatArrayOf(
      dp(OpenCrayUiTokens.radiusLargeCard).toFloat(),
      dp(OpenCrayUiTokens.radiusLargeCard).toFloat(),
      dp(OpenCrayUiTokens.radiusLargeCard).toFloat(),
      dp(OpenCrayUiTokens.radiusLargeCard).toFloat(),
      0f,
      0f,
      0f,
      0f,
    )
    setColor(surfaceColor)
    setStroke(dp(1), borderColor)
  }

  private fun actionSheetBackground(): GradientDrawable = GradientDrawable().apply {
    val drawable = context.ocCardBackground(OpenCraySurfaceTone.INFO, radiusDp = OpenCrayUiTokens.radiusCard)
    shape = drawable.shape
    cornerRadius = drawable.cornerRadius
    color = drawable.color
    setStroke(dp(1), OpenCrayUiTokens.border)
  }

  private fun inputBackground(): GradientDrawable = GradientDrawable().apply {
    val drawable = context.ocInputBackground(fillColor = OpenCrayUiTokens.surface)
    shape = drawable.shape
    cornerRadius = drawable.cornerRadius
    color = drawable.color
    setStroke(dp(1), OpenCrayUiTokens.border)
  }

  private fun quietButtonBackground(): GradientDrawable = GradientDrawable().apply {
    val drawable = context.ocButtonBackground(OpenCrayButtonTone.QUIET)
    shape = drawable.shape
    cornerRadius = drawable.cornerRadius
    color = drawable.color
    setStroke(dp(1), OpenCrayUiTokens.border)
  }

  private fun secondaryButtonBackground(): GradientDrawable = GradientDrawable().apply {
    val drawable = context.ocCardBackground(OpenCraySurfaceTone.INFO, radiusDp = OpenCrayUiTokens.radiusButton)
    shape = drawable.shape
    cornerRadius = drawable.cornerRadius
    color = drawable.color
    setStroke(dp(1), OpenCrayUiTokens.border)
  }

  private fun primaryButtonBackground(): GradientDrawable = GradientDrawable().apply {
    val drawable = context.ocButtonBackground(OpenCrayButtonTone.PRIMARY)
    shape = drawable.shape
    cornerRadius = drawable.cornerRadius
    color = drawable.color
    setStroke(dp(1), OpenCrayUiTokens.primary)
  }

  private fun handleBackground(): GradientDrawable = GradientDrawable().apply {
    val drawable = context.ocPillBackground(OpenCrayUiTokens.borderStrong)
    shape = drawable.shape
    cornerRadius = drawable.cornerRadius
    color = drawable.color
  }

  private fun actionIconContainerBackground(isDestructive: Boolean): GradientDrawable = GradientDrawable().apply {
    val tone = if (isDestructive) OpenCraySurfaceTone.DANGER else OpenCraySurfaceTone.INFO
    val drawable = context.ocCardBackground(tone, radiusDp = OpenCrayUiTokens.radiusInput)
    shape = drawable.shape
    cornerRadius = drawable.cornerRadius
    color = drawable.color
    setStroke(dp(1), OpenCrayUiTokens.border)
  }

  private fun sessionRowBackground(isSelected: Boolean): GradientDrawable = GradientDrawable().apply {
    val drawable = context.ocCardBackground(
      tone = if (isSelected) OpenCraySurfaceTone.INFO else OpenCraySurfaceTone.NEUTRAL,
      radiusDp = OpenCrayUiTokens.radiusCard,
      stroked = true,
    )
    shape = drawable.shape
    cornerRadius = drawable.cornerRadius
    color = drawable.color
    setStroke(dp(1), if (isSelected) OpenCrayUiTokens.borderStrong else OpenCrayUiTokens.border)
  }

  private fun bubbleBackground(role: ChatMessageDisplayRole): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    cornerRadius = if (role == ChatMessageDisplayRole.SYSTEM) dp(16).toFloat() else dp(16).toFloat()
    when (role) {
      ChatMessageDisplayRole.USER -> {
        setColor(userBubbleColor)
        setStroke(dp(1), userBubbleColor)
      }

      ChatMessageDisplayRole.SYSTEM -> {
        setColor(systemBubbleColor)
        setStroke(dp(1), systemAccentColor)
      }

      ChatMessageDisplayRole.ASSISTANT -> {
        setColor(assistantBubbleColor)
        setStroke(dp(1), assistantAccentColor)
      }

      ChatMessageDisplayRole.TOOL -> {
        setColor(OpenCrayUiTokens.surfaceMuted)
        setStroke(dp(1), OpenCrayUiTokens.border)
      }
    }
  }

  private fun drawerDivider(): View = View(context).apply {
    setBackgroundColor(dividerColor)
    layoutParams = LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT,
      dp(1),
    )
  }

  private fun scrollConversationToBottom() {
    messageScrollView.post {
      messageScrollView.fullScroll(View.FOCUS_DOWN)
    }
  }

  private fun roleLabel(role: ChatMessageDisplayRole): String = when (role) {
    ChatMessageDisplayRole.SYSTEM -> "System"
    ChatMessageDisplayRole.USER -> "You"
    ChatMessageDisplayRole.ASSISTANT -> "OpenCray"
    ChatMessageDisplayRole.TOOL -> "Tool"
  }

  private fun messageTextColor(role: ChatMessageDisplayRole): Int = when (role) {
    ChatMessageDisplayRole.USER -> userTextColor
    ChatMessageDisplayRole.SYSTEM -> textPrimary
    ChatMessageDisplayRole.ASSISTANT -> textPrimary
    ChatMessageDisplayRole.TOOL -> textPrimary
  }

  private fun roleLabelColor(role: ChatMessageDisplayRole): Int = when (role) {
    ChatMessageDisplayRole.USER -> Color.parseColor("#DDEBFF")
    ChatMessageDisplayRole.SYSTEM -> OpenCrayUiTokens.textSecondary
    ChatMessageDisplayRole.ASSISTANT -> OpenCrayUiTokens.textSecondary
    ChatMessageDisplayRole.TOOL -> OpenCrayUiTokens.textSecondary
  }

  private fun roleMetaColor(role: ChatMessageDisplayRole): Int = when (role) {
    ChatMessageDisplayRole.USER -> Color.parseColor("#CFE1FF")
    ChatMessageDisplayRole.SYSTEM -> OpenCrayUiTokens.textTertiary
    ChatMessageDisplayRole.ASSISTANT -> OpenCrayUiTokens.textTertiary
    ChatMessageDisplayRole.TOOL -> OpenCrayUiTokens.textTertiary
  }

  private fun bubbleGravity(role: ChatMessageDisplayRole): Int = when (role) {
    ChatMessageDisplayRole.USER -> Gravity.END
    ChatMessageDisplayRole.SYSTEM -> Gravity.CENTER_HORIZONTAL
    ChatMessageDisplayRole.ASSISTANT -> Gravity.START
    ChatMessageDisplayRole.TOOL -> Gravity.START
  }

  private fun tintedDrawable(
    iconResId: Int,
    tintColor: Int,
  ) = checkNotNull(ContextCompat.getDrawable(context, iconResId)) { "Missing icon resource: $iconResId" }.mutate().let {
    DrawableCompat.setTint(it, tintColor)
    it
  }

  private fun sectionParams(
    topDp: Int = 0,
    bottomDp: Int = 0,
  ): LinearLayout.LayoutParams = context.ocLinearBlockParams(topDp = topDp, bottomDp = bottomDp)

  private fun dp(value: Int): Int = context.ocDp(value)
}
