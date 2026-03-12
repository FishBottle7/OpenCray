package com.opencray.app

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.provider.OpenableColumns
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.method.PasswordTransformationMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.opencray.ui.design.OpenCrayButtonTone
import com.opencray.ui.design.OpenCraySurfaceTone
import com.opencray.ui.design.OpenCrayUiTokens
import com.opencray.ui.design.ocButtonBackground
import com.opencray.ui.design.ocCardBackground
import com.opencray.ui.design.ocDp
import com.opencray.ui.design.ocInputBackground
import com.opencray.ui.design.ocPillBackground
import com.opencray.ui.design.ocSurfaceBackground
import com.opencray.app.shell.AppShellDestination
import com.opencray.app.shell.AppShellNavigationExtras
import com.opencray.app.shell.AppShellStateStore
import com.opencray.app.shell.AppShellTab
import com.opencray.app.shell.SettingsSubpage
import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.McpAuthSpec
import com.opencray.core.contracts.McpServerSpec
import com.opencray.core.contracts.McpServerTrustState
import com.opencray.core.contracts.McpTransportDescriptor
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.InMemorySessionQueueSnapshotStore
import com.opencray.llm.DefaultLiteLlmGateway
import com.opencray.llm.InMemoryLiteLlmRoutingSettingsStore
import com.opencray.llm.ModelProfile
import com.opencray.llm.ProviderRoute
import com.opencray.llm.ProviderRouting
import com.opencray.mcp.InMemoryMcpRegistryStore
import com.opencray.mcp.McpClientAuthDescriptor
import com.opencray.mcp.McpClientBlockReason
import com.opencray.mcp.McpBlockedClientDescriptor
import com.opencray.mcp.McpClientDescriptor
import com.opencray.mcp.McpClientExposureReport
import com.opencray.mcp.McpClientFactory
import com.opencray.mcp.McpClientTransportDescriptor
import com.opencray.mcp.McpRegistry
import com.opencray.mcp.McpRegistryRecord
import com.opencray.mcp.McpRegistryServerRecord
import com.opencray.mcp.McpServerAuthState
import com.opencray.mcp.McpServerAuthStatus
import com.opencray.persistence.security.CredentialRef
import com.opencray.persistence.model.ChatAttachmentEntry
import com.opencray.persistence.model.ChatAttachmentKind
import com.opencray.persistence.model.ChatTranscriptRole
import com.opencray.persistence.model.ChatTranscriptMessageEntry
import com.opencray.runtime.AgentToolCall
import com.opencray.runtime.AgentToolResult
import com.opencray.runtime.OpenCrayAgentEngine
import com.opencray.runtime.OpenCrayAgentRuntime
import com.opencray.runtime.OpenCrayAgentRuntimeConfig
import com.opencray.runtime.OpenCrayAgentRuntimeEventSink
import com.opencray.runtime.OpenCrayToolDispatcher
import com.opencray.runtime.OpenCrayToolDispatcherConfig
import com.opencray.ui.chat.ApprovalDecision
import com.opencray.ui.chat.ApprovalPromptState
import com.opencray.ui.chat.ApprovalPromptStatus
import com.opencray.ui.chat.ChatMessageAction
import com.opencray.ui.chat.ChatAttachmentVisualKind
import com.opencray.ui.chat.ChatAttachmentVisualState
import com.opencray.ui.chat.ChatCommandOptionState
import com.opencray.ui.chat.ChatMessageDisplayRole
import com.opencray.ui.chat.ChatMessageItemState
import com.opencray.ui.chat.ChatMode
import com.opencray.ui.chat.ChatSessionListItemState
import com.opencray.ui.chat.ChatSessionSummaryState
import com.opencray.ui.chat.ChatScreen
import com.opencray.ui.chat.ChatScreenState
import com.opencray.ui.chat.ConversationMessageRole
import com.opencray.ui.chat.ConversationMessageState
import com.opencray.ui.chat.ConversationHeaderState
import com.opencray.ui.chat.ModeState
import com.opencray.ui.files.WorkspacePickerScreen
import com.opencray.ui.help.DisclosureTone
import com.opencray.ui.help.SafetyAndLimitsScreen
import com.opencray.ui.help.SafetyAndLimitsScreenState
import com.opencray.ui.skills.SkillEditorViewModel
import com.opencray.ui.skills.SkillsScreen
import com.opencray.ui.settings.TelemetryToggles
import com.opencray.ui.settings.TelemetryTogglesState
import com.opencray.ui.timeline.ActionApprovalState
import com.opencray.ui.timeline.ActionPolicyDecision
import com.opencray.ui.timeline.ActionResultStatus
import com.opencray.ui.timeline.ActionTimelineItem
import org.opencray.app.R
import java.io.File
import java.io.FileOutputStream
import java.text.DateFormat
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val STATE_SELECTED_TAB = "selected_tab"
private const val STATE_SETTINGS_SUBPAGE = "settings_subpage"
private const val STATE_CHAT_SCENARIO = "chat_scenario"
private const val STATE_CHAT_SELECTED_MODE = "chat_selected_mode"
private const val STATE_CHAT_QUEUE_VISIBLE = "chat_queue_visible"
private const val STATE_CHAT_APPROVAL_OUTCOME = "chat_approval_outcome"
private const val REQUEST_PICK_CHAT_IMAGE = 4201
private const val REQUEST_PICK_CHAT_FILE = 4202
private const val STATE_FILES_SCENARIO = "files_scenario"
private const val STATE_PERSONALIZATION_PRESET = "personalization_preset"
private const val STATE_PERSONALIZATION_CUSTOM_LABEL = "personalization_custom_label"
private const val STATE_PERSONALIZATION_CUSTOM_GUIDANCE = "personalization_custom_guidance"
private const val STATE_PERSONALIZATION_MEMORY_CONFIRMATION = "personalization_memory_confirmation"
private const val STATE_PERSONALIZATION_SOUL_CONFIRMATION = "personalization_soul_confirmation"
private const val STATE_PERSONALIZATION_LAST_RESET_PREVIEW = "personalization_last_reset_preview"
private const val MCP_SETTINGS_SEED_EPOCH_MS = 1_710_000_200_000L

class AppShellActivity : LocalizedActivity(), ChatScreen.Listener, WorkspacePickerScreen.Listener {
  private lateinit var stateStore: AppShellStateStore
  private lateinit var localeSettingsStore: LocaleSettingsStore
  private lateinit var telemetrySettingsStore: TelemetrySettingsStore
  private lateinit var llmSettingsStore: LlmSettingsStore
  private lateinit var personalizationStore: PersonalizationLocalStore
  private lateinit var chatSessionStore: ChatSessionLocalStore
  private lateinit var mcpRegistry: McpRegistry
  private lateinit var chatScreen: ChatScreen
  private lateinit var bottomNavigationBar: LinearLayout
  private lateinit var rootShell: LinearLayout
  private lateinit var filesWorkbenchScreen: WorkspacePickerScreen
  private lateinit var settingsHostScrollView: ScrollView
  private lateinit var settingsContentContainer: LinearLayout
  private lateinit var skillsScreen: SkillsScreen
  private lateinit var skillsViewModel: SkillEditorViewModel

  private val navigationButtons = linkedMapOf<AppShellTab, Button>()
  private val tabContentViews = linkedMapOf<AppShellTab, View>()
  private val mcpClientFactory = McpClientFactory()
  private val chatExecutor: ExecutorService = Executors.newSingleThreadExecutor()
  private val inFlightChatSessions = linkedSetOf<String>()
  private val transientAgentTraces = linkedMapOf<String, AgentTraceSessionState>()

  private var currentDestination: AppShellDestination = AppShellDestination.default()
  private lateinit var chatSessionsState: ChatSessionLocalStore.ChatSessionsState
  private var chatSeedScenario: SeedScenario = SeedScenario.DEFAULT_APPROVAL
  private var selectedMode: ChatMode = ChatMode.SAFE
  private var isQueueVisible: Boolean = true
  private var approvalOutcome: ApprovalOutcome = ApprovalOutcome.PENDING
  private var filesWorkbenchScenario: FilesWorkbenchSeedScenario = FilesWorkbenchSeedScenario.NO_GRANT
  private var lastRenderedSettingsSubpage: SettingsSubpage? = null
  private var personalizationPreset: PersonalizationPreset = PersonalizationPreset.STEADY
  private var personalizationCustomLabel: String = ""
  private var personalizationCustomGuidance: String = ""
  private var personalizationMemoryConfirmation: String = ""
  private var personalizationSoulConfirmation: String = ""
  private var personalizationLastResetPreview: PersonalizationResetPreview = PersonalizationResetPreview.NONE
  private var suppressPersonalizationPersistence: Boolean = false
  private var isKeyboardVisible: Boolean = false
  private var pendingChatAttachments: List<ChatAttachmentEntry> = emptyList()
  private var pendingChatCommandLabel: String? = null

  private data class AgentTraceItemState(
    val id: String,
    val role: ChatMessageDisplayRole,
    val expandedBody: String,
    val expandedMeta: String = "",
    val collapsedBody: String,
    val collapsedMeta: String = "",
    val isCollapsed: Boolean = false,
  ) {
    fun toChatMessageItemState(): ChatMessageItemState = ChatMessageItemState(
      messageId = id,
      role = role,
      body = if (isCollapsed) collapsedBody else expandedBody,
      meta = if (isCollapsed) collapsedMeta else expandedMeta,
    )
  }

  private data class AgentTraceSessionState(
    val anchorMessageId: String,
    val items: List<AgentTraceItemState>,
    val nextOrdinal: Int,
  )

  private data class AttachmentMetadata(
    val displayName: String,
    val sizeBytes: Long?,
  )

  private fun settingsPageCard(
    fillColor: Int = OpenCrayUiTokens.surface,
    paddingDp: Int = 20,
  ): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = surfaceBackground(fillColor)
    setPadding(dp(paddingDp), dp(paddingDp), dp(paddingDp), dp(paddingDp))
  }

  private fun quietActionButton(label: String): Button = Button(this).apply {
    text = label
    isAllCaps = false
    setTextColor(OpenCrayUiTokens.textPrimary)
    minHeight = dp(OpenCrayUiTokens.buttonHeight)
    background = ocButtonBackground(OpenCrayButtonTone.SECONDARY)
    setPadding(dp(20), dp(12), dp(20), dp(12))
  }

  private fun destructiveActionButton(label: String): Button = Button(this).apply {
    text = label
    isAllCaps = false
    setTextColor(Color.WHITE)
    minHeight = dp(OpenCrayUiTokens.buttonHeight)
    background = ocButtonBackground(OpenCrayButtonTone.DANGER)
    setPadding(dp(20), dp(12), dp(20), dp(12))
  }

  private fun buildActionRow(
    leftButton: Button,
    rightButton: Button,
  ): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    addView(
      leftButton,
      LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
        marginEnd = dp(8)
      },
    )
    addView(
      rightButton,
      LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
    )
  }

  private data class SettingsHomeCard(
    val subpage: SettingsSubpage,
    val title: String,
    val detail: String,
    val summary: String,
    val badgeText: String,
    val accentColorHex: String,
  )

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.setSoftInputMode(
      WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
        WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN,
    )

    stateStore = AppShellStateStore.fromContext(this)
    localeSettingsStore = LocaleSettingsStore.fromContext(this)
    telemetrySettingsStore = TelemetrySettingsStore.fromContext(this)
    llmSettingsStore = LlmSettingsStore.fromContext(this)
    personalizationStore = PersonalizationLocalStore.fromContext(this)
    chatSessionStore = ChatSessionLocalStore.fromContext(this)
    skillsViewModel = SkillEditorViewModel.fromContext(this)
    currentDestination = AppShellLaunchStateResolver.resolve(
      restoredTabRaw = savedInstanceState?.getString(STATE_SELECTED_TAB),
      restoredSettingsSubpageRaw = savedInstanceState?.getString(STATE_SETTINGS_SUBPAGE),
      hasRestoredState = savedInstanceState != null,
      startTabRaw = intent.getStringExtra(AppShellNavigationExtras.EXTRA_START_TAB),
      startSettingsSubpageRaw = intent.getStringExtra(AppShellNavigationExtras.EXTRA_START_SETTINGS_PAGE),
      hasStartExtras =
        intent.hasExtra(AppShellNavigationExtras.EXTRA_START_TAB) ||
          intent.hasExtra(AppShellNavigationExtras.EXTRA_START_SETTINGS_PAGE),
      persistedDestination = stateStore.load(),
    )
    restoreChatState(savedInstanceState)
    chatSessionsState = chatSessionStore.loadState()
    filesWorkbenchScenario = parseFilesWorkbenchScenario(
      savedInstanceState?.getString(STATE_FILES_SCENARIO)
        ?: intent.getStringExtra(AppShellNavigationExtras.EXTRA_FILES_SCENARIO),
    )
    restorePersonalizationState(savedInstanceState)
    mcpRegistry = buildSeededMcpRegistry()

    val root = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setBackgroundColor(OpenCrayUiTokens.shellBackground)
      addView(buildContentHost(), contentHostParams())
      bottomNavigationBar = buildBottomNavigationBar()
      addView(bottomNavigationBar, bottomNavigationParams())
    }
    rootShell = root

    setContentView(root)
    observeKeyboardVisibility()
    renderDestination()
  }

  override fun onPause() {
    persistDestination()
    super.onPause()
  }

  override fun onDestroy() {
    chatExecutor.shutdownNow()
    super.onDestroy()
  }

  override fun onBackPressed() {
    if (
      currentDestination.selectedTab == AppShellTab.SETTINGS &&
      currentDestination.settingsSubpage != SettingsSubpage.HOME
    ) {
      currentDestination = currentDestination.copy(settingsSubpage = SettingsSubpage.HOME)
      renderDestination()
      return
    }

    super.onBackPressed()
  }

  override fun onSaveInstanceState(outState: Bundle) {
    outState.putString(STATE_SELECTED_TAB, currentDestination.selectedTab.name)
    outState.putString(STATE_SETTINGS_SUBPAGE, currentDestination.settingsSubpage.name)
    outState.putString(STATE_CHAT_SCENARIO, chatSeedScenario.rawValue)
    outState.putString(STATE_CHAT_SELECTED_MODE, selectedMode.name)
    outState.putBoolean(STATE_CHAT_QUEUE_VISIBLE, isQueueVisible)
    outState.putString(STATE_CHAT_APPROVAL_OUTCOME, approvalOutcome.name)
    outState.putString(STATE_FILES_SCENARIO, filesWorkbenchScenario.rawValue)
    outState.putString(STATE_PERSONALIZATION_PRESET, personalizationPreset.name)
    outState.putString(STATE_PERSONALIZATION_CUSTOM_LABEL, personalizationCustomLabel)
    outState.putString(STATE_PERSONALIZATION_CUSTOM_GUIDANCE, personalizationCustomGuidance)
    outState.putString(STATE_PERSONALIZATION_MEMORY_CONFIRMATION, personalizationMemoryConfirmation)
    outState.putString(STATE_PERSONALIZATION_SOUL_CONFIRMATION, personalizationSoulConfirmation)
    outState.putString(STATE_PERSONALIZATION_LAST_RESET_PREVIEW, personalizationLastResetPreview.name)
    super.onSaveInstanceState(outState)
  }

  override fun onQueueVisibilityChanged(isVisible: Boolean) {
    isQueueVisible = isVisible
    renderChatState()
  }

  override fun onModeSelected(mode: ChatMode) {
    selectedMode = mode
    renderChatState()
  }

  override fun onApprovalDecision(decision: ApprovalDecision) {
    approvalOutcome = when (decision) {
      ApprovalDecision.APPROVE -> ApprovalOutcome.APPROVED
      ApprovalDecision.DENY -> ApprovalOutcome.DENIED
    }
    renderChatState()
  }

  override fun onResetAgentIdentity() {
    resetToSeededState()
    renderChatState()
  }

  override fun onSessionSelected(sessionId: String) {
    chatSessionsState = chatSessionStore.selectSession(sessionId)
    clearPendingChatComposer()
    renderChatState()
  }

  override fun onNewSessionRequested() {
    chatSessionsState = chatSessionStore.createSession()
    clearPendingChatComposer()
    renderChatState()
  }

  override fun onMessageSubmitted(text: String) {
    val activeSessionId = chatSessionsState.activeSession.sessionId
    val submittedAttachments = pendingChatAttachments
    val submittedCommandLabel = pendingChatCommandLabel
    val runtimeInput = buildRuntimeInput(
      text = text,
      commandLabel = submittedCommandLabel,
      attachments = submittedAttachments,
    )
    chatSessionsState = chatSessionStore.appendUserMessage(
      sessionId = activeSessionId,
      text = text,
      commandLabel = submittedCommandLabel,
      attachments = submittedAttachments,
    )
    clearPendingChatComposer()
    if (isSessionInFlight(activeSessionId)) {
      chatSessionsState = chatSessionStore.appendMessage(
        sessionId = activeSessionId,
        role = ChatTranscriptRole.SYSTEM,
        text = getString(R.string.chat_agent_thinking),
      ).state
      renderChatState()
      return
    }

    val pendingResult = chatSessionStore.appendMessage(
      sessionId = chatSessionsState.activeSession.sessionId,
      role = ChatTranscriptRole.ASSISTANT,
      text = getString(R.string.chat_agent_thinking),
    )
    chatSessionsState = pendingResult.state
    markSessionInFlight(activeSessionId, inFlight = true)
    renderChatState()

    chatExecutor.execute {
      runAgentPrompt(
        sessionId = activeSessionId,
        userText = runtimeInput,
        pendingMessageId = pendingResult.messageId,
      )
    }
  }

  override fun onComposerFocusChanged(hasFocus: Boolean) {
    syncBottomNavigationVisibility()
  }

  override fun onImageAttachmentRequested() {
    startActivityForResult(
      Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "image/*"
      },
      REQUEST_PICK_CHAT_IMAGE,
    )
  }

  override fun onFileAttachmentRequested() {
    startActivityForResult(
      Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
      },
      REQUEST_PICK_CHAT_FILE,
    )
  }

  override fun onComposerAttachmentRemoved(attachmentId: String) {
    pendingChatAttachments = pendingChatAttachments.filterNot { it.attachmentId == attachmentId }
    renderChatState()
  }

  override fun onCommandSelected(commandLabel: String) {
    pendingChatCommandLabel = commandLabel
    renderChatState()
  }

  override fun onMessageActionRequested(
    messageId: String,
    action: ChatMessageAction,
  ) {
    when (action) {
      ChatMessageAction.REGENERATE -> regenerateMessage(messageId)
      ChatMessageAction.RECALL -> recallMessage(messageId)
      ChatMessageAction.DELETE -> deleteMessage(messageId)
      ChatMessageAction.EDIT -> editMessage(messageId)
      ChatMessageAction.BRANCH -> branchSessionFromMessage(messageId)
      ChatMessageAction.SHARE -> shareMessage(messageId)
    }
  }

  override fun onPickWorkspaceRequested(workspaceId: String) {
    activateWorkspaceGrant()
  }

  override fun onReauthorizeWorkspaceRequested(workspaceId: String) {
    activateWorkspaceGrant()
  }

  override fun onClearGrantRequested(workspaceId: String) {
    clearWorkspaceGrant()
  }

  override fun onManageWorkspaceAccessRequested() {
    currentDestination = currentDestination.copy(
      selectedTab = AppShellTab.SETTINGS,
      settingsSubpage = SettingsSubpage.WORKSPACE,
    )
    renderDestination()
  }

  override fun onActivityResult(
    requestCode: Int,
    resultCode: Int,
    data: Intent?,
  ) {
    super.onActivityResult(requestCode, resultCode, data)
    if (resultCode != RESULT_OK) {
      return
    }
    val uri = data?.data ?: return
    when (requestCode) {
      REQUEST_PICK_CHAT_IMAGE -> {
        copyChatAttachment(uri, preferredKind = ChatAttachmentKind.IMAGE)?.let { attachment ->
          pendingChatAttachments = pendingChatAttachments + attachment
          renderChatState()
        }
      }

      REQUEST_PICK_CHAT_FILE -> {
        copyChatAttachment(uri, preferredKind = null)?.let { attachment ->
          pendingChatAttachments = pendingChatAttachments + attachment
          renderChatState()
        }
      }
    }
  }

  private fun isSessionInFlight(sessionId: String): Boolean = synchronized(inFlightChatSessions) {
    inFlightChatSessions.contains(sessionId)
  }

  private fun clearPendingChatComposer() {
    pendingChatAttachments = emptyList()
    pendingChatCommandLabel = null
  }

  private fun copyChatAttachment(
    uri: Uri,
    preferredKind: ChatAttachmentKind?,
  ): ChatAttachmentEntry? = runCatching {
    val mimeType = contentResolver.getType(uri)
    val metadata = queryAttachmentMetadata(uri)
    val kind = preferredKind ?: if (mimeType?.startsWith("image/") == true) {
      ChatAttachmentKind.IMAGE
    } else {
      ChatAttachmentKind.FILE
    }
    val safeName = metadata.displayName.ifBlank { defaultAttachmentName(kind, mimeType) }
    val targetFile = File(chatAttachmentDirectory(), buildAttachmentFileName(safeName))
    contentResolver.openInputStream(uri)?.use { input ->
      FileOutputStream(targetFile).use { output -> input.copyTo(output) }
    } ?: return null
    ChatAttachmentEntry(
      attachmentId = "attachment-${UUID.randomUUID().toString().take(8)}",
      kind = kind,
      displayName = safeName,
      localPath = targetFile.absolutePath,
      mimeType = mimeType,
      sizeBytes = metadata.sizeBytes ?: targetFile.length(),
    )
  }.getOrElse {
    showActionToast("Attachment import failed")
    null
  }

  private fun chatAttachmentDirectory(): File = File(
    ChatSessionLocalStore.directoryForContext(this),
    "attachments",
  ).apply {
    if (!exists()) {
      mkdirs()
    }
  }

  private fun buildAttachmentFileName(displayName: String): String {
    val extension = displayName.substringAfterLast('.', "").trim()
    val sanitizedBase = displayName.substringBeforeLast('.', displayName)
      .replace(Regex("[^A-Za-z0-9._-]"), "_")
      .ifBlank { "attachment" }
      .take(40)
    val suffix = UUID.randomUUID().toString().take(8)
    return if (extension.isBlank()) {
      "$sanitizedBase-$suffix"
    } else {
      "$sanitizedBase-$suffix.$extension"
    }
  }

  private fun defaultAttachmentName(
    kind: ChatAttachmentKind,
    mimeType: String?,
  ): String = when (kind) {
    ChatAttachmentKind.IMAGE -> "image-${System.currentTimeMillis()}.${mimeType?.substringAfter('/') ?: "jpg"}"
    ChatAttachmentKind.FILE -> "file-${System.currentTimeMillis()}"
  }

  private fun queryAttachmentMetadata(uri: Uri): AttachmentMetadata {
    contentResolver.query(
      uri,
      arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
      null,
      null,
      null,
    )?.use { cursor ->
      val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
      val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
      if (cursor.moveToFirst()) {
        return AttachmentMetadata(
          displayName = if (nameIndex >= 0) cursor.getString(nameIndex).orEmpty() else "",
          sizeBytes = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null,
        )
      }
    }
    return AttachmentMetadata(displayName = "", sizeBytes = null)
  }

  private fun deleteMessage(messageId: String) {
    confirmDestructiveAction(
      title = "Delete message",
      message = "Remove this message from the local transcript?",
    ) {
      val activeSessionId = chatSessionsState.activeSession.sessionId
      chatSessionsState = chatSessionStore.deleteMessage(
        sessionId = activeSessionId,
        messageId = messageId,
      )
      renderChatState()
      showActionToast("Message deleted")
    }
  }

  private fun recallMessage(messageId: String) {
    val message = activeChatMessage(messageId) ?: return
    if (message.role != ChatTranscriptRole.USER) {
      return
    }
    confirmDestructiveAction(
      title = "Recall message",
      message = "This removes the message and every later reply from this chat.",
    ) {
      val activeSessionId = chatSessionsState.activeSession.sessionId
      chatSessionsState = chatSessionStore.recallMessageCascade(
        sessionId = activeSessionId,
        messageId = messageId,
      )
      rollbackAssistantSideEffectsForRecall(
        sessionId = activeSessionId,
        recalledMessageId = messageId,
      )
      markSessionInFlight(activeSessionId, inFlight = false)
      renderChatState()
      showActionToast("Message recalled")
    }
  }

  private fun editMessage(messageId: String) {
    val activeSessionId = chatSessionsState.activeSession.sessionId
    val message = activeChatMessage(messageId) ?: return
    val initialText = resolvedMessageBody(message)
    val hasLaterMessages = chatSessionsState.activeSession.messages.indexOfFirst { it.messageId == messageId }
      .let { selectedIndex -> selectedIndex >= 0 && selectedIndex < chatSessionsState.activeSession.messages.lastIndex }
    val input = EditText(this).apply {
      setText(initialText)
      setSelection(text.length)
      minLines = 3
      gravity = Gravity.TOP or Gravity.START
    }
    AlertDialog.Builder(this)
      .setTitle("Edit message")
      .setMessage(
        if (message.role == ChatTranscriptRole.USER && hasLaterMessages) {
          "Saving this edit clears the later replies so the conversation can continue from the updated prompt."
        } else {
          null
        },
      )
      .setView(input)
      .setNegativeButton("Cancel", null)
      .setPositiveButton("Save") { _, _ ->
        val updatedText = input.text?.toString().orEmpty().trim()
        if (updatedText.isBlank()) {
          return@setPositiveButton
        }
        chatSessionsState = if (message.role == ChatTranscriptRole.USER && hasLaterMessages) {
          chatSessionStore.replaceMessageAndPruneTail(
            sessionId = activeSessionId,
            messageId = messageId,
            role = message.role,
            text = updatedText,
          )
        } else {
          chatSessionStore.replaceMessage(
            sessionId = activeSessionId,
            messageId = messageId,
            role = message.role,
            text = updatedText,
          )
        }
        if (message.role == ChatTranscriptRole.USER && hasLaterMessages) {
          rollbackAssistantSideEffectsForRecall(
            sessionId = activeSessionId,
            recalledMessageId = messageId,
          )
          markSessionInFlight(activeSessionId, inFlight = false)
          showActionToast("Message updated. Later replies cleared")
        }
        renderChatState()
      }
      .show()
  }

  private fun branchSessionFromMessage(messageId: String) {
    chatSessionsState = chatSessionStore.branchSessionFromMessage(
      sessionId = chatSessionsState.activeSession.sessionId,
      messageId = messageId,
    )
    renderChatState()
  }

  private fun shareMessage(messageId: String) {
    val message = activeChatMessage(messageId) ?: return
    val shareText = runtimeInputFor(message)
    if (shareText.isBlank()) return
    startActivity(
      Intent.createChooser(
        Intent(Intent.ACTION_SEND).apply {
          type = "text/plain"
          putExtra(Intent.EXTRA_TEXT, shareText)
          putExtra(Intent.EXTRA_SUBJECT, chatSessionsState.activeSession.title)
        },
        "Share message",
      ),
    )
  }

  private fun regenerateMessage(messageId: String) {
    val activeSessionId = chatSessionsState.activeSession.sessionId
    if (isSessionInFlight(activeSessionId)) {
      return
    }

    val transcript = chatSessionsState.activeSession
    val selectedIndex = transcript.messages.indexOfFirst { it.messageId == messageId }
    if (selectedIndex < 0) {
      return
    }
    val selectedMessage = transcript.messages[selectedIndex]
    val sourceUserMessage = when (selectedMessage.role) {
      ChatTranscriptRole.USER -> selectedMessage
      else -> transcript.messages
        .take(selectedIndex)
        .lastOrNull { message -> message.role == ChatTranscriptRole.USER }
    } ?: return

    val userText = runtimeInputFor(sourceUserMessage)
    if (userText.isBlank()) {
      return
    }

    val pendingMessageId = if (selectedMessage.role == ChatTranscriptRole.ASSISTANT) {
      chatSessionsState = chatSessionStore.replaceMessage(
        sessionId = activeSessionId,
        messageId = selectedMessage.messageId,
        role = ChatTranscriptRole.ASSISTANT,
        text = getString(R.string.chat_agent_thinking),
      )
      selectedMessage.messageId
    } else {
      val pendingResult = chatSessionStore.appendMessage(
        sessionId = activeSessionId,
        role = ChatTranscriptRole.ASSISTANT,
        text = getString(R.string.chat_agent_thinking),
      )
      chatSessionsState = pendingResult.state
      pendingResult.messageId
    }

    markSessionInFlight(activeSessionId, inFlight = true)
    renderChatState()
    chatExecutor.execute {
      runAgentPrompt(
        sessionId = activeSessionId,
        userText = userText,
        pendingMessageId = pendingMessageId,
      )
    }
  }

  private fun activeChatMessage(messageId: String): ChatTranscriptMessageEntry? =
    chatSessionsState.activeSession.messages.firstOrNull { it.messageId == messageId }

  private fun resolvedMessageBody(message: ChatTranscriptMessageEntry): String =
    message.text ?: chatSessionStore.promptTemplateBody(message.promptTemplateRefId).orEmpty()

  private fun runtimeInputFor(message: ChatTranscriptMessageEntry): String = buildRuntimeInput(
    text = resolvedMessageBody(message),
    commandLabel = message.commandLabel,
    attachments = message.attachments,
  )

  private fun rollbackAssistantSideEffectsForRecall(
    sessionId: String,
    recalledMessageId: String,
  ) {
    // Stub for future workspace rollback wiring once agent-side effects are tracked per message.
  }

  private fun confirmDestructiveAction(
    title: String,
    message: String,
    onConfirm: () -> Unit,
  ) {
    AlertDialog.Builder(this)
      .setTitle(title)
      .setMessage(message)
      .setNegativeButton("Cancel", null)
      .setPositiveButton("Confirm") { _, _ -> onConfirm() }
      .show()
  }

  private fun showActionToast(message: String) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
  }

  private fun markSessionInFlight(sessionId: String, inFlight: Boolean) {
    synchronized(inFlightChatSessions) {
      if (inFlight) {
        inFlightChatSessions += sessionId
      } else {
        inFlightChatSessions -= sessionId
      }
    }
  }

  private fun startAgentTrace(
    sessionId: String,
    anchorMessageId: String,
    userText: String,
  ) {
    mutateAgentTrace(sessionId) {
      AgentTraceSessionState(
        anchorMessageId = anchorMessageId,
        items = listOf(
          thinkingTraceItem(
            sessionId = sessionId,
            ordinal = 1,
            body = getString(R.string.chat_trace_thinking_active),
            meta = summarizeTraceText(userText, maxLength = 120),
          ),
        ),
        nextOrdinal = 2,
      )
    }
  }

  private fun recordToolCallTrace(
    sessionId: String,
    call: AgentToolCall,
  ) {
    mutateAgentTrace(sessionId) { current ->
      val base = current ?: return@mutateAgentTrace null
      val collapsed = collapseLatestTraceItem(base)
      collapsed.copy(
        items = collapsed.items + toolTraceItem(
          sessionId = sessionId,
          ordinal = collapsed.nextOrdinal,
          toolName = call.toolName,
          argumentsSummary = summarizeToolArguments(call),
        ),
        nextOrdinal = collapsed.nextOrdinal + 1,
      )
    }
  }

  private fun recordToolResultTrace(
    sessionId: String,
    call: AgentToolCall,
    result: AgentToolResult,
  ) {
    mutateAgentTrace(sessionId) { current ->
      val base = current ?: return@mutateAgentTrace null
      val resultSummary = summarizeToolResult(result)
      val updatedItems = if (base.items.isEmpty()) {
        emptyList()
      } else {
        base.items.dropLast(1) + base.items.last().copy(
          isCollapsed = true,
          collapsedBody = getString(R.string.chat_trace_tool_finished, call.toolName),
          collapsedMeta = resultSummary,
        )
      }
      AgentTraceSessionState(
        anchorMessageId = base.anchorMessageId,
        items = updatedItems + thinkingTraceItem(
          sessionId = sessionId,
          ordinal = base.nextOrdinal,
          body = getString(R.string.chat_trace_tool_follow_up, call.toolName),
          meta = resultSummary,
        ),
        nextOrdinal = base.nextOrdinal + 1,
      )
    }
  }

  private fun clearAgentTrace(
    sessionId: String,
    renderAfterClear: Boolean = true,
  ) {
    synchronized(transientAgentTraces) {
      transientAgentTraces.remove(sessionId)
    }
    if (renderAfterClear) {
      renderTraceIfVisible(sessionId)
    }
  }

  private fun mutateAgentTrace(
    sessionId: String,
    update: (AgentTraceSessionState?) -> AgentTraceSessionState?,
  ) {
    synchronized(transientAgentTraces) {
      val updated = update(transientAgentTraces[sessionId])
      if (updated == null) {
        transientAgentTraces.remove(sessionId)
      } else {
        transientAgentTraces[sessionId] = updated
      }
    }
    renderTraceIfVisible(sessionId)
  }

  private fun renderTraceIfVisible(sessionId: String) {
    runOnUiThread {
      if (::chatScreen.isInitialized && chatSessionsState.activeSession.sessionId == sessionId) {
        renderChatState()
      }
    }
  }

  private fun collapseLatestTraceItem(state: AgentTraceSessionState): AgentTraceSessionState {
    if (state.items.isEmpty()) {
      return state
    }
    return state.copy(
      items = state.items.dropLast(1) + state.items.last().copy(isCollapsed = true),
    )
  }

  private fun thinkingTraceItem(
    sessionId: String,
    ordinal: Int,
    body: String,
    meta: String,
  ): AgentTraceItemState = AgentTraceItemState(
    id = "trace-$sessionId-thinking-$ordinal",
    role = ChatMessageDisplayRole.SYSTEM,
    expandedBody = body,
    expandedMeta = meta,
    collapsedBody = getString(R.string.chat_trace_thinking_collapsed),
  )

  private fun toolTraceItem(
    sessionId: String,
    ordinal: Int,
    toolName: String,
    argumentsSummary: String,
  ): AgentTraceItemState = AgentTraceItemState(
    id = "trace-$sessionId-tool-$ordinal",
    role = ChatMessageDisplayRole.TOOL,
    expandedBody = getString(R.string.chat_trace_tool_running, toolName),
    expandedMeta = argumentsSummary,
    collapsedBody = getString(R.string.chat_trace_tool_finished, toolName),
  )

  private fun summarizeToolArguments(call: AgentToolCall): String {
    val serializedArguments = summarizeTraceText(call.arguments.toString(), maxLength = 180)
    return if (serializedArguments == "{}") {
      ""
    } else {
      serializedArguments
    }
  }

  private fun summarizeToolResult(result: AgentToolResult): String {
    val rawSummary: String = when {
      result.content.isNotBlank() -> result.content
      result.stdout.isNotBlank() -> result.stdout
      result.stderr.isNotBlank() -> result.stderr
      !result.errorMessage.isNullOrBlank() -> result.errorMessage.orEmpty()
      else -> result.status.name
    }
    return summarizeTraceText(rawSummary, maxLength = 180)
  }

  private fun summarizeTraceText(
    text: String,
    maxLength: Int,
  ): String {
    val normalized = text.replace(Regex("\\s+"), " ").trim()
    if (normalized.length <= maxLength) {
      return normalized
    }
    return normalized.take(maxLength - 1).trimEnd() + "…"
  }

  private fun runAgentPrompt(
    sessionId: String,
    userText: String,
    pendingMessageId: String,
  ) {
    try {
      val llmSettings = llmSettingsStore.load()
      if (!llmSettings.isConfigured()) {
        completeAgentPrompt(
          sessionId = sessionId,
          pendingMessageId = pendingMessageId,
          finalText = getString(R.string.chat_agent_missing_llm),
        )
        return
      }

      startAgentTrace(
        sessionId = sessionId,
        anchorMessageId = pendingMessageId,
        userText = userText,
      )
      val engine = OpenCrayAgentEngine(
        runtime = buildAgentRuntime(
          sessionId = sessionId,
          llmSettings = llmSettings,
        ),
      )
      val loop = engine.create(
        sessionId = sessionId,
        agentId = "opencray-app",
        snapshotStore = InMemorySessionQueueSnapshotStore(),
      )
      val now = System.currentTimeMillis()
      val task = AgentTask(
        id = "prompt-$sessionId-${UUID.randomUUID().toString().take(8)}",
        type = AgentTaskType.PROMPT,
        input = userText,
        policyDecision = policyDecisionForSelectedMode(),
        createdAtEpochMs = now,
        metadata = mapOf(
          "sessionId" to sessionId,
          "chatMode" to selectedMode.name,
        ),
      )
      loop.submit(task)
      val result = loop.runUntilIdle().lastOrNull()
      val finalText = when {
        result == null -> getString(R.string.chat_agent_failed, "No runtime result was produced.")
        result.status == com.opencray.core.contracts.ExecutionStatus.SUCCESS -> result.stdout.ifBlank {
          getString(R.string.chat_agent_failed, "The model returned an empty answer.")
        }
        result.status == com.opencray.core.contracts.ExecutionStatus.CANCELLED -> getString(R.string.chat_agent_cancelled)
        else -> getString(
          R.string.chat_agent_failed,
          result.errorMessage ?: result.errorCode ?: result.status.name,
        )
      }
      completeAgentPrompt(
        sessionId = sessionId,
        pendingMessageId = pendingMessageId,
        finalText = finalText,
      )
    } catch (throwable: Throwable) {
      completeAgentPrompt(
        sessionId = sessionId,
        pendingMessageId = pendingMessageId,
        finalText = getString(
          R.string.chat_agent_failed,
          throwable.message ?: throwable::class.java.simpleName,
        ),
      )
    } finally {
      markSessionInFlight(sessionId, inFlight = false)
      runOnUiThread {
        renderChatState()
        renderSettingsSurface(force = currentDestination.settingsSubpage == SettingsSubpage.LLM)
      }
    }
  }

  private fun completeAgentPrompt(
    sessionId: String,
    pendingMessageId: String,
    finalText: String,
  ) {
    runOnUiThread {
      clearAgentTrace(sessionId, renderAfterClear = false)
      chatSessionsState = chatSessionStore.replaceMessage(
        sessionId = sessionId,
        messageId = pendingMessageId,
        role = ChatTranscriptRole.ASSISTANT,
        text = finalText,
      )
      if (chatSessionsState.activeSession.sessionId == sessionId) {
        renderChatState()
      }
    }
  }

  private fun buildAgentRuntime(
    sessionId: String,
    llmSettings: LlmSettingsState,
  ): OpenCrayAgentRuntime {
    val sanitized = llmSettings.sanitized()
    val route = ProviderRoute(
      id = "route-openai-compatible",
      providerId = "openai-compatible",
      baseUrl = sanitized.baseUrl,
      model = sanitized.model,
    )
    val gateway = DefaultLiteLlmGateway(
      routingStore = InMemoryLiteLlmRoutingSettingsStore(
        ProviderRouting(
          activeProfileId = "profile-default",
          profiles = listOf(
            ModelProfile(
              id = "profile-default",
              displayName = "Default",
              primaryRouteId = route.id,
              routes = listOf(route),
            ),
          ),
        ),
      ),
      providerClient = OpenAiCompatibleLiteLlmProviderClient(),
    )
    return OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(resolveAgentWorkspaceRoot(this, filesWorkbenchScenario)),
          mcpExposureReport = currentMcpReport(),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(
        systemPrompt = resolvedAgentSystemPrompt(sanitized),
        llmMetadata = mapOf(
          "sessionId" to sessionId,
          "chatMode" to selectedMode.name,
        ),
        llmAuthHeaders = mapOf(
          "Authorization" to "Bearer ${sanitized.apiKey}",
        ),
      ),
      eventSink = buildRuntimeEventSink(sessionId),
    )
  }

  private fun buildRuntimeEventSink(sessionId: String): OpenCrayAgentRuntimeEventSink =
    object : OpenCrayAgentRuntimeEventSink {
      override fun onToolCall(task: AgentTask, turn: Int, call: AgentToolCall) {
        recordToolCallTrace(
          sessionId = sessionId,
          call = call,
        )
      }

      override fun onToolResult(task: AgentTask, turn: Int, call: AgentToolCall, result: AgentToolResult) {
        recordToolResultTrace(
          sessionId = sessionId,
          call = call,
          result = result,
        )
      }
    }

  private fun resolvedAgentSystemPrompt(llmSettings: LlmSettingsState): String {
    val basePrompt = llmSettings.systemPrompt.ifBlank {
      OpenCrayAgentRuntimeConfig.DEFAULT_OPENCRAY_SYSTEM_PROMPT
    }
    val transcriptPrompt = chatSessionStore.promptTemplateBody(ChatSessionLocalStore.DEFAULT_SYSTEM_TEMPLATE_ID).orEmpty()
    val personalizationPrompt = currentPersonalizationProfileSummary()
    return buildString {
      append(basePrompt)
      if (transcriptPrompt.isNotBlank()) {
        append("\n\nSession policy:\n")
        append(transcriptPrompt)
      }
      if (personalizationPrompt.isNotBlank()) {
        append("\n\nPersonalization overlay:\n")
        append(personalizationPrompt)
      }
    }
  }

  private fun policyDecisionForSelectedMode(): PolicyDecision = when (selectedMode) {
    ChatMode.SAFE -> PolicyDecision(
      outcome = PolicyDecisionOutcome.ASK,
      reasonCode = "SAFE_MODE_APPROVAL_REQUIRED",
      detail = "Safe mode requires explicit approval before command execution.",
    )

    ChatMode.AUTO -> PolicyDecision(
      outcome = PolicyDecisionOutcome.ALLOW,
      reasonCode = "AUTO_MODE_ALLOW",
      detail = "Auto mode allows command execution inside the approved workspace.",
    )

    ChatMode.DEVELOPER -> PolicyDecision(
      outcome = PolicyDecisionOutcome.ALLOW,
      reasonCode = "DEVELOPER_MODE_ALLOW",
      detail = "Developer mode allows command execution inside the approved workspace.",
    )
  }

  private fun buildContentHost(): FrameLayout = FrameLayout(this).apply {
    addChatTabHost()
    addSkillsSurface(this)
    addView(
      buildFilesWorkbench(),
      FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
      ),
    )
    addView(
      buildSettingsHost(),
      FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
      ),
    )
  }

  private fun FrameLayout.addChatTabHost() {
    chatScreen = ChatScreen(this@AppShellActivity).apply {
      setListener(this@AppShellActivity)
      submitState(buildChatState())
    }
    tabContentViews[AppShellTab.CHAT] = chatScreen
    addView(
      chatScreen,
      FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
      ),
    )
  }

  private fun FrameLayout.addPlaceholder(
    tab: AppShellTab,
    title: String,
    accentColor: String,
    summary: String,
  ) {
    val placeholderView = buildPlaceholderCard(
      title = title,
      accentColor = accentColor,
      summary = summary,
    )
    tabContentViews[tab] = placeholderView
    this.addView(
      placeholderView,
      FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
      ),
    )
  }

  private fun addSkillsSurface(contentHost: FrameLayout) {
    skillsScreen = SkillsScreen(
      context = this@AppShellActivity,
      viewModel = skillsViewModel,
    )
    tabContentViews[AppShellTab.SKILLS] = skillsScreen
    contentHost.addView(
      skillsScreen,
      FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
      ),
    )
  }

  private fun buildFilesWorkbench(): View = WorkspacePickerScreen(this).apply {
    setListener(this@AppShellActivity)
    filesWorkbenchScreen = this
    renderFilesWorkbench()
  }.also { filesWorkbenchView ->
    tabContentViews[AppShellTab.FILES] = filesWorkbenchView
  }

  private fun buildSettingsHost(): View = ScrollView(this).apply {
    isFillViewport = true
    setBackgroundColor(OpenCrayUiTokens.shellBackground)
    settingsHostScrollView = this
    settingsContentContainer = LinearLayout(this@AppShellActivity).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(20), dp(12), dp(20), dp(32))
    }
    addView(
      settingsContentContainer,
      ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
      ),
    )
  }.also { settingsView ->
    tabContentViews[AppShellTab.SETTINGS] = settingsView
  }

  private fun buildPlaceholderCard(
    title: String,
    accentColor: String,
    summary: String,
  ): View = FrameLayout(this).apply {
    setPadding(0, dp(4), 0, dp(4))
    addView(
      LinearLayout(this@AppShellActivity).apply {
        orientation = LinearLayout.VERTICAL
        background = surfaceBackground(OpenCrayUiTokens.surface)
        setPadding(dp(20), dp(20), dp(20), dp(20))

        addView(sectionEyebrow(accentColor))
        addView(sectionTitle(title), textParams(topDp = 10))
        addView(bodyTextView(summary), textParams(topDp = 8))
      },
      FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        Gravity.CENTER_VERTICAL,
      ),
    )
  }

  private fun buildBottomNavigationBar(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = navigationBarBackground()

    addView(
      dividerLine(),
      LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        dp(1),
      ),
    )
    addView(
      LinearLayout(this@AppShellActivity).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(12), dp(5), dp(12), dp(7))

        addView(navigationButton(AppShellTab.CHAT, R.string.shell_tab_chat), navigationButtonParams())
        addView(
          navigationButton(AppShellTab.SKILLS, R.string.shell_tab_skills),
          navigationButtonParams(),
        )
        addView(
          navigationButton(AppShellTab.FILES, R.string.shell_tab_files),
          navigationButtonParams(),
        )
        addView(
          navigationButton(AppShellTab.SETTINGS, R.string.shell_tab_settings),
          navigationButtonParams(),
        )
      },
      LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
      ),
    )
  }

  private fun navigationButton(tab: AppShellTab, labelResId: Int): Button = Button(this).apply {
    text = getString(labelResId).uppercase(Locale.US)
    isAllCaps = false
    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    minHeight = dp(54)
    minimumHeight = dp(54)
    minWidth = 0
    minimumWidth = 0
    gravity = Gravity.CENTER
    includeFontPadding = false
    stateListAnimator = null
    letterSpacing = 0.035f
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
    compoundDrawablePadding = dp(2)
    background = navigationTabBackground(
      fillColor = Color.TRANSPARENT,
      strokeColor = Color.TRANSPARENT,
    )
    setPadding(0, dp(5), 0, dp(8))
    setOnClickListener {
      currentDestination = currentDestination.copy(selectedTab = tab)
      renderDestination()
    }
    navigationButtons[tab] = this
  }

  private fun renderDestination() {
    title = when (currentDestination.selectedTab) {
      AppShellTab.SETTINGS -> labelForSettingsTitle(currentDestination.settingsSubpage)
      else -> labelForTab(currentDestination.selectedTab)
    }

    tabContentViews.forEach { (tab, view) ->
      view.visibility = if (tab == currentDestination.selectedTab) View.VISIBLE else View.GONE
    }

    navigationButtons.forEach { (tab, button) ->
      val isSelected = tab == currentDestination.selectedTab
      applyNavigationButtonStyle(button = button, tab = tab, isSelected = isSelected)
    }
    syncBottomNavigationVisibility()
    if (currentDestination.selectedTab == AppShellTab.SKILLS) {
      skillsViewModel.refresh()
    }

    renderSettingsSurface(
      force =
        currentDestination.selectedTab == AppShellTab.SETTINGS &&
          (
            currentDestination.settingsSubpage == SettingsSubpage.PERSONALIZATION ||
              currentDestination.settingsSubpage == SettingsSubpage.LLM
            ),
    )

    persistDestination()
  }

  private fun renderSettingsSurface(force: Boolean = false) {
    if (!::settingsContentContainer.isInitialized) {
      return
    }

    val targetSubpage = currentDestination.settingsSubpage
    if (!force && lastRenderedSettingsSubpage == targetSubpage) {
      return
    }

    settingsContentContainer.removeAllViews()

    when (targetSubpage) {
      SettingsSubpage.HOME -> renderSettingsHome()
      else -> renderSettingsSubpage(targetSubpage)
    }

    lastRenderedSettingsSubpage = targetSubpage
    settingsHostScrollView.post { settingsHostScrollView.scrollTo(0, 0) }
  }

  private fun renderSettingsHome() {
    settingsContentContainer.addView(sectionEyebrow("#6E6E73"))
    settingsContentContainer.addView(sectionTitle(getString(R.string.shell_tab_settings)), textParams(topDp = 8))
    settingsContentContainer.addView(
      bodyTextView(getString(R.string.settings_home_intro)).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
      },
      textParams(topDp = 8),
    )
    settingsContentContainer.addView(
      buildSettingsOverviewCard(),
      textParams(topDp = 20),
    )

    settingsHomeCards().forEachIndexed { index, card ->
      settingsContentContainer.addView(
        buildSettingsHomeCard(card),
        textParams(topDp = if (index == 0) 12 else 0),
      )
    }
  }

  private fun renderSettingsSubpage(subpage: SettingsSubpage) {
    settingsContentContainer.addView(buildSettingsSubpageHeader(subpage))
    when (subpage) {
      SettingsSubpage.MCP -> renderMcpSettingsSubpage()
      else -> settingsContentContainer.addView(
        when (subpage) {
          SettingsSubpage.WORKSPACE -> buildWorkspaceSettingsSubpage()
          SettingsSubpage.LLM -> buildLlmSettingsSubpageContent()
          SettingsSubpage.PRIVACY -> buildPrivacySettingsSubpageContent()
          SettingsSubpage.SAFETY -> buildSafetySettingsSubpageContent()
          SettingsSubpage.ABOUT -> buildAboutVersionSubpage()
          SettingsSubpage.PERSONALIZATION -> buildPersonalizationSettingsContent()
          else -> buildSettingsSubpagePlaceholder(subpage)
        },
        textParams(topDp = 20),
      )
    }
  }

  private fun buildSettingsOverviewCard(): View = settingsPageCard(
    fillColor = Color.WHITE,
    paddingDp = 16,
  ).apply {
    val telemetryState = currentTelemetrySettingsState()
    addView(titleText(getString(R.string.settings_home_profile_title), textSizeSp = 17f))
    addView(
      bodyTextView(
        getString(
          R.string.settings_home_profile_meta,
          personalizationToneLabel(personalizationPreset),
          telemetryProfileLabel(telemetryState),
        ),
      ).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
      },
      textParams(topDp = 10),
    )
  }

  private fun buildPrivacySettingsSubpageContent(): View = TelemetryToggles(this).apply {
    isVerticalScrollBarEnabled = false
    overScrollMode = View.OVER_SCROLL_NEVER
    submitState(currentTelemetrySettingsState())
    setListener(
      object : TelemetryToggles.Listener {
        override fun onStateChanged(state: TelemetryTogglesState) {
          telemetrySettingsStore.save(state)
        }
      },
    )
  }

  private fun buildSafetySettingsSubpageContent(): View = SafetyAndLimitsScreen(this).apply {
    isVerticalScrollBarEnabled = false
    overScrollMode = View.OVER_SCROLL_NEVER
    submitState(currentSafetyState())
  }

  private fun buildWorkspaceSettingsSubpage(): View = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    val primaryAction = actionButton(
      label = workspacePrimaryActionLabel(),
      fillColor = Color.parseColor(accentColorForSettingsSubpage(SettingsSubpage.WORKSPACE)),
    ).apply {
      setOnClickListener {
        when (filesWorkbenchScenario) {
          FilesWorkbenchSeedScenario.REVOKED_GRANT -> reauthorizeWorkspaceGrant()
          else -> activateWorkspaceGrant()
        }
      }
    }
    val openFilesAction = quietActionButton(getString(R.string.workspace_settings_open_files_action)).apply {
      setOnClickListener {
        currentDestination = currentDestination.copy(selectedTab = AppShellTab.FILES)
        renderDestination()
      }
    }
    val clearAction = destructiveActionButton(getString(R.string.workspace_settings_clear_action)).apply {
      setOnClickListener { clearWorkspaceGrant() }
    }

    addView(
      settingsPageCard(fillColor = settingsSurfaceColor(SettingsSubpage.WORKSPACE)).apply {
        addView(
          LinearLayout(this@AppShellActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
              titleText(getString(R.string.workspace_settings_status_title), textSizeSp = 20f),
              LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
              statusBadgeText(
                text = workspaceStatusBadgeLabel(),
                accentColor = Color.parseColor(accentColorForSettingsSubpage(SettingsSubpage.WORKSPACE)),
                accentColorHex = accentColorForSettingsSubpage(SettingsSubpage.WORKSPACE),
              ),
            )
          },
        )
        addView(bodyTextView(workspaceAccessSummary()), textParams(topDp = 8))
        addView(helperTextView(workspaceAccessDetail()), textParams(topDp = 8))
      },
    )

    addView(
      settingsPageCard().apply {
        addView(titleText("Actions", textSizeSp = 17f))
        when (filesWorkbenchScenario) {
          FilesWorkbenchSeedScenario.NO_GRANT -> {
            addView(primaryAction, textParams(topDp = 12))
          }
          FilesWorkbenchSeedScenario.ACTIVE_GRANT -> {
            addView(buildActionRow(primaryAction, openFilesAction), textParams(topDp = 12))
            addView(clearAction, textParams(topDp = 10))
          }
          FilesWorkbenchSeedScenario.REVOKED_GRANT,
          FilesWorkbenchSeedScenario.OUTSIDE_ROOT_DENIAL -> {
            addView(primaryAction, textParams(topDp = 12))
            addView(clearAction, textParams(topDp = 10))
          }
        }
      },
      textParams(topDp = 20),
    )
  }

  private fun buildLlmSettingsSubpageContent(): View = LinearLayout(this).apply {
    val accentColor = Color.parseColor(accentColorForSettingsSubpage(SettingsSubpage.LLM))
    var draftState = llmSettingsStore.load()

    orientation = LinearLayout.VERTICAL

    val statusTitleView = titleText(llmStatusLabel(draftState), textSizeSp = 18f)
    val statusBodyView = bodyTextView(llmSettingsHomeSummary(draftState))

    fun persist(updatedState: LlmSettingsState) {
      draftState = updatedState.sanitized()
      llmSettingsStore.save(draftState)
      statusTitleView.text = llmStatusLabel(draftState)
      statusBodyView.text = llmSettingsHomeSummary(draftState)
    }

    addView(
      settingsPageCard(fillColor = settingsSurfaceColor(SettingsSubpage.LLM)).apply {
        addView(titleText(getString(R.string.llm_settings_section_status), textSizeSp = 16f))
        addView(statusTitleView, textParams(topDp = 8))
        addView(statusBodyView, textParams(topDp = 6))
        addView(helperTextView(getString(R.string.llm_settings_storage_note)), textParams(topDp = 8))
      },
    )

    addView(
      settingsPageCard().apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL

        addView(
          LinearLayout(this@AppShellActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(titleText(getString(R.string.llm_settings_enabled_title), textSizeSp = 16f))
            addView(helperTextView(getString(R.string.llm_settings_enabled_summary)), textParams(topDp = 6))
          },
          LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f,
          ),
        )
        addView(
          Switch(this@AppShellActivity).apply {
            isChecked = draftState.enabled
            setOnCheckedChangeListener { _, isChecked ->
              persist(draftState.copy(enabled = isChecked))
            }
          },
        )
      },
      textParams(topDp = 20),
    )

    addView(
      settingsPageCard().apply {
        orientation = LinearLayout.VERTICAL

        addView(titleText(getString(R.string.llm_settings_provider_title), textSizeSp = 16f))
        addView(bodyTextView(getString(R.string.llm_settings_provider_body)), textParams(topDp = 6))

        val baseUrlInput = buildTextInput(
          hint = getString(R.string.llm_settings_base_url_hint),
          singleLine = true,
          strokeColor = accentColor,
        ).apply {
          setText(draftState.baseUrl)
          addTextChangedListener(
            simpleTextWatcher { value ->
              persist(draftState.copy(baseUrl = value))
            },
          )
        }
        addView(baseUrlInput, textParams(topDp = 12))
        addView(helperTextView(getString(R.string.llm_settings_base_url_helper)), textParams(topDp = 6))

        val modelInput = buildTextInput(
          hint = getString(R.string.llm_settings_model_hint),
          singleLine = true,
          strokeColor = accentColor,
        ).apply {
          setText(draftState.model)
          addTextChangedListener(
            simpleTextWatcher { value ->
              persist(draftState.copy(model = value))
            },
          )
        }
        addView(modelInput, textParams(topDp = 12))
        addView(helperTextView(getString(R.string.llm_settings_model_helper)), textParams(topDp = 6))

        val apiKeyInput = buildTextInput(
          hint = getString(R.string.llm_settings_api_key_hint),
          singleLine = true,
          strokeColor = accentColor,
        ).apply {
          transformationMethod = PasswordTransformationMethod.getInstance()
          inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
          setText(draftState.apiKey)
          addTextChangedListener(
            simpleTextWatcher { value ->
              persist(draftState.copy(apiKey = value))
            },
          )
        }
        addView(apiKeyInput, textParams(topDp = 12))
        addView(helperTextView(getString(R.string.llm_settings_api_key_helper)), textParams(topDp = 6))
      },
      textParams(topDp = 20),
    )

    addView(
      settingsPageCard().apply {
        orientation = LinearLayout.VERTICAL
        addView(titleText(getString(R.string.llm_settings_system_prompt_title), textSizeSp = 16f))
        addView(helperTextView(getString(R.string.llm_settings_system_prompt_helper)), textParams(topDp = 6))
        addView(
          buildTextInput(
            hint = getString(R.string.llm_settings_system_prompt_title),
            singleLine = false,
            strokeColor = accentColor,
          ).apply {
            setText(draftState.systemPrompt)
            addTextChangedListener(
              simpleTextWatcher { value ->
                persist(draftState.copy(systemPrompt = value))
              },
            )
          },
          textParams(topDp = 12),
        )
      },
      textParams(topDp = 20),
    )
  }

  private fun buildAboutVersionSubpage(): View {
    val accentColorHex = accentColorForSettingsSubpage(SettingsSubpage.ABOUT)
    val installedBuild = installedBuildInfo()

    return LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      addView(buildAboutProductCard())
      addView(buildAboutBuildDetailsCard(installedBuild), textParams(topDp = 20))
      addView(buildAboutGuardrailsCard(accentColorHex, installedBuild), textParams(topDp = 20))
    }
  }

  private fun buildAboutProductCard(): View = settingsPageCard(fillColor = settingsSurfaceColor(SettingsSubpage.ABOUT)).apply {
    orientation = LinearLayout.VERTICAL

    addView(titleText(getString(R.string.shell_product_name), textSizeSp = 20f))
    addView(bodyTextView(getString(R.string.about_product_body)), textParams(topDp = 8))
    addView(helperTextView(getString(R.string.about_product_helper)), textParams(topDp = 8))
  }

  private fun buildAboutBuildDetailsCard(
    installedBuild: InstalledBuildInfo,
  ): View = settingsPageCard(fillColor = OpenCrayUiTokens.surfaceMuted).apply {
    orientation = LinearLayout.VERTICAL

    addView(titleText(getString(R.string.about_build_details_title), textSizeSp = 18f))
    addView(helperTextView(getString(R.string.about_build_details_helper)), textParams(topDp = 6))
    addView(
      buildAboutFactRow(
        label = getString(R.string.about_installed_version_label),
        value = getString(R.string.about_installed_version_value, installedBuild.versionName),
      ),
      textParams(topDp = 12),
    )
    addView(
      buildAboutFactRow(
        label = getString(R.string.about_build_number_label),
        value = getString(R.string.about_build_number_value, installedBuild.versionCode.toString()),
      ),
      textParams(topDp = 10),
    )
    addView(
      buildAboutFactRow(
        label = getString(R.string.about_min_android_label),
        value = minimumSupportedAndroidLabel(installedBuild.minSdk),
      ),
      textParams(topDp = 10),
    )
  }

  private fun buildAboutGuardrailsCard(
    accentColorHex: String,
    installedBuild: InstalledBuildInfo,
  ): View = settingsPageCard(fillColor = OpenCrayUiTokens.surfaceInfo).apply {
    orientation = LinearLayout.VERTICAL

    addView(titleText(getString(R.string.about_guardrails_title), textSizeSp = 18f))
    addView(
      helperTextView(
        getString(
          R.string.about_guardrails_snapshot,
          installedBuild.versionName,
          installedBuild.versionCode.toString(),
          minimumSupportedAndroidLabel(installedBuild.minSdk),
        ),
      ),
      textParams(topDp = 6),
    )
    addView(
      buildAboutBulletRow(
        accentColorHex = accentColorHex,
        text = getString(R.string.about_guardrails_bullet_protected_paths),
      ),
      textParams(topDp = 12),
    )
    addView(
      buildAboutBulletRow(
        accentColorHex = accentColorHex,
        text = getString(R.string.about_guardrails_bullet_sensitive_changes),
      ),
      textParams(topDp = 10),
    )
    addView(
      buildAboutBulletRow(
        accentColorHex = accentColorHex,
        text = getString(R.string.about_guardrails_bullet_rollback),
      ),
      textParams(topDp = 10),
    )
    addView(
      buildAboutBulletRow(
        accentColorHex = accentColorHex,
        text = getString(R.string.about_guardrails_bullet_v1_scope),
      ),
      textParams(topDp = 10),
    )
  }

  private fun buildAboutFactRow(
    label: String,
    value: String,
  ): View = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL

    addView(
      helperTextView(label),
      LinearLayout.LayoutParams(
        0,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        1f,
      ),
    )
    addView(titleText(value, textSizeSp = 16f))
  }

  private fun buildAboutBulletRow(
    accentColorHex: String,
    text: String,
  ): View = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.TOP

    addView(
      TextView(this@AppShellActivity).apply {
        this.text = "•"
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.parseColor(accentColorHex))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
      },
    )
    addView(
      bodyTextView(text),
      LinearLayout.LayoutParams(
        0,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        1f,
      ).apply {
        marginStart = dp(10)
      },
    )
  }

  private fun buildPersonalizationSettingsContent(): View {
    val accentColor = Color.parseColor(accentColorForSettingsSubpage(SettingsSubpage.PERSONALIZATION))
    val dangerColor = Color.parseColor("#B63A48")
    val resetsIdle = arePersonalizationResetsIdle()

    return LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL

      lateinit var freeEditInput: EditText
      val previewBodyView = bodyTextView().apply {
        setTextColor(OpenCrayUiTokens.textPrimary)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
      }
      val stagedResetCard = settingsPageCard(fillColor = Color.parseColor("#FFF4F5"), paddingDp = 12).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
      }
      val stagedResetBodyView = helperTextView("").apply {
        setTextColor(Color.parseColor("#7B2732"))
      }
      stagedResetCard.addView(titleText(getString(R.string.personalization_last_staged_reset_title), textSizeSp = 15f))
      stagedResetCard.addView(stagedResetBodyView, textParams(topDp = 6))

      fun syncProfilePreview() {
        previewBodyView.text = currentPersonalizationProfileSummary()
      }

      fun syncStagedResetNotice() {
        val statusMessage = personalizationResetPreviewMessage(personalizationLastResetPreview)
        stagedResetCard.visibility = if (statusMessage == null) View.GONE else View.VISIBLE
        stagedResetBodyView.text = statusMessage.orEmpty()
      }

      addView(
        settingsPageCard().apply {
          addView(titleText(getString(R.string.personalization_screen_tone_preset), textSizeSp = 17f))
          addView(
            buildSegmentedControl(
              options = listOf(
                PersonalizationPreset.STEADY to getString(R.string.settings_tone_quiet),
                PersonalizationPreset.BUILDER to getString(R.string.settings_tone_focus),
                PersonalizationPreset.WARM to getString(R.string.settings_tone_warm),
              ),
              selected = personalizationPreset,
            ) { preset ->
              personalizationPreset = preset
              persistPersonalizationSoulProfile()
              syncProfilePreview()
              renderSettingsSurface(force = true)
            },
            textParams(topDp = 12),
          )
          addView(
            helperTextView(getString(R.string.personalization_screen_tone_helper)),
            textParams(topDp = 12),
          )
        },
      )

      addView(
        settingsPageCard().apply {
          addView(titleText(getString(R.string.personalization_screen_free_editing), textSizeSp = 17f))
          addView(
            helperTextView(getString(R.string.personalization_screen_free_editing_helper)),
            textParams(topDp = 6),
          )
          addView(
            settingsPageCard(fillColor = Color.parseColor("#F7F7FA"), paddingDp = 12).apply {
              addView(helperTextView(getString(R.string.personalization_screen_guidance_label)))
              freeEditInput = buildTextInput(
                hint = getString(R.string.personalization_custom_guidance_hint),
                singleLine = false,
                strokeColor = Color.TRANSPARENT,
              ).apply {
                setText(personalizationCustomGuidance)
                background = ColorDrawable(Color.TRANSPARENT)
                setPadding(0, dp(4), 0, 0)
                minLines = 3
                addTextChangedListener(
                  simpleTextWatcher { value ->
                    personalizationCustomGuidance = value
                    persistPersonalizationSoulProfile()
                    syncProfilePreview()
                  },
                )
              }
              addView(freeEditInput, textParams(topDp = 4))
            },
            textParams(topDp = 12),
          )
          addView(
            settingsPageCard(fillColor = OpenCrayUiTokens.surfaceMuted, paddingDp = 12).apply {
              addView(helperTextView(getString(R.string.personalization_live_preview_title)))
              addView(previewBodyView, textParams(topDp = 6))
            },
            textParams(topDp = 12),
          )
        },
        textParams(topDp = 16),
      )

      addView(
        settingsPageCard().apply {
          addView(titleText(getString(R.string.personalization_screen_behavior_defaults), textSizeSp = 17f))
          addView(
            buildPrototypeToggleRow(
              title = getString(R.string.personalization_screen_personal_memory_title),
              detail = getString(R.string.personalization_screen_personal_memory_body),
              checked = true,
              onCheckedChanged = { },
            ),
            textParams(topDp = 12),
          )
          addView(dividerLine(), textParams(topDp = 12))
          addView(
            buildPrototypeValueRow(
              title = getString(R.string.personalization_screen_prompt_overlay_title),
              value = getString(R.string.shell_common_on),
            ),
            textParams(topDp = 12),
          )
          addView(dividerLine(), textParams(topDp = 12))
          addView(
            buildSegmentedValueRow(
              title = getString(R.string.personalization_screen_app_language_title),
              options = listOf(
                AppLanguage.ENGLISH to getString(R.string.settings_language_english),
                AppLanguage.SIMPLIFIED_CHINESE to getString(R.string.settings_language_simplified_chinese),
              ),
              selected = currentAppLanguage(),
            ) { language ->
              switchAppLanguage(language)
            },
            textParams(topDp = 12),
          )
        },
        textParams(topDp = 16),
      )

      addView(
        settingsPageCard(fillColor = Color.parseColor("#FFF4F5")).apply {
          addView(titleText(getString(R.string.personalization_danger_zone_title), textSizeSp = 17f).apply {
            setTextColor(Color.parseColor("#8F2431"))
          })
          addView(
            helperTextView(getString(R.string.personalization_screen_danger_zone_helper)).apply {
              setTextColor(Color.parseColor("#7B2732"))
            },
            textParams(topDp = 6),
          )
          addView(stagedResetCard, textParams(topDp = 12))
          addView(
            buildPersonalizationResetCard(
              title = getString(R.string.personalization_reset_memory_title),
              token = getString(R.string.reset_memory_phrase),
              scopeCopy = getString(R.string.personalization_reset_memory_scope),
              retainCopy = getString(R.string.personalization_reset_memory_retain),
              inputHint = getString(
                R.string.personalization_reset_input_hint,
                getString(R.string.reset_memory_phrase),
              ),
              currentValue = personalizationMemoryConfirmation,
              isResetIdle = resetsIdle,
              accentColor = dangerColor,
              onInputChanged = { value ->
                personalizationMemoryConfirmation = value
              },
              onResetTriggered = {
                personalizationStore.clearMemoryAndHistory()
                personalizationLastResetPreview = PersonalizationResetPreview.MEMORY
              },
              onVisualStateChanged = ::syncStagedResetNotice,
            ),
            textParams(topDp = 12),
          )
          addView(
            buildPersonalizationResetCard(
              title = getString(R.string.personalization_reset_soul_title),
              token = getString(R.string.reset_soul_phrase),
              scopeCopy = getString(R.string.personalization_reset_soul_scope),
              retainCopy = getString(R.string.personalization_reset_soul_retain),
              inputHint = getString(
                R.string.personalization_reset_input_hint,
                getString(R.string.reset_soul_phrase),
              ),
              currentValue = personalizationSoulConfirmation,
              isResetIdle = resetsIdle,
              accentColor = dangerColor,
              onInputChanged = { value ->
                personalizationSoulConfirmation = value
              },
              onResetTriggered = {
                personalizationStore.clearSoulProfile()
                applyPersonalizationStateWithoutPersistence {
                  personalizationPreset = PersonalizationPreset.STEADY
                  personalizationCustomLabel = ""
                  personalizationCustomGuidance = ""
                  personalizationLastResetPreview = PersonalizationResetPreview.SOUL
                  freeEditInput.setText("")
                }
                syncProfilePreview()
              },
              onVisualStateChanged = ::syncStagedResetNotice,
            ),
            textParams(topDp = 12),
          )
        },
        textParams(topDp = 16),
      )

      syncProfilePreview()
      syncStagedResetNotice()
    }
  }

  private fun buildPersonalizationResetCard(
    title: String,
    token: String,
    scopeCopy: String,
    retainCopy: String,
    inputHint: String,
    currentValue: String,
    isResetIdle: Boolean,
    accentColor: Int,
    onInputChanged: (String) -> Unit,
    onResetTriggered: () -> Unit,
    onVisualStateChanged: () -> Unit,
  ): View = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = surfaceBackground(Color.WHITE)
    setPadding(dp(12), dp(12), dp(12), dp(12))

    val guidanceView = helperTextView("")
    val resetButton = actionButton(label = title, fillColor = accentColor)
    val confirmationInput = buildTextInput(
      hint = inputHint,
      singleLine = true,
      strokeColor = accentColor,
    ).apply {
      setText(currentValue)
    }

    fun syncActionState() {
      val exactMatch = confirmationInput.text?.toString().orEmpty() == token
      confirmationInput.isEnabled = isResetIdle
      confirmationInput.alpha = if (isResetIdle) 1f else 0.7f
      resetButton.isEnabled = isResetIdle && exactMatch
      resetButton.alpha = if (resetButton.isEnabled) 1f else 0.55f
      guidanceView.text = when {
        !isResetIdle -> getString(R.string.personalization_reset_guidance_disabled)
        exactMatch -> getString(R.string.personalization_reset_guidance_armed)
        else -> getString(R.string.personalization_reset_guidance_type_exact, token)
      }
      guidanceView.setTextColor(
        when {
          !isResetIdle -> Color.parseColor("#7B2732")
          exactMatch -> Color.parseColor("#1B5E20")
          else -> Color.parseColor("#5D6B7B")
        },
      )
    }

    addView(titleText(title, textSizeSp = 15f))
    addView(bodyTextView(scopeCopy), textParams(topDp = 6))
    addView(helperTextView(retainCopy), textParams(topDp = 6))
    addView(confirmationInput, textParams(topDp = 12))
    addView(guidanceView, textParams(topDp = 6))
    addView(resetButton, textParams(topDp = 12))

    confirmationInput.addTextChangedListener(
      simpleTextWatcher { value ->
        onInputChanged(value)
        syncActionState()
      },
    )
    resetButton.setOnClickListener {
      onResetTriggered()
      confirmationInput.setText("")
      onInputChanged("")
      syncActionState()
      onVisualStateChanged()
    }

    syncActionState()
  }

  private fun buildSettingsHomeCard(card: SettingsHomeCard): View = LinearLayout(this).apply {
    val accentColor = if (card.subpage == SettingsSubpage.SAFETY) {
      OpenCrayUiTokens.primary
    } else {
      OpenCrayUiTokens.textPrimary
    }
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    background = ColorDrawable(Color.TRANSPARENT)
    minimumHeight = dp(44)
    setPadding(dp(12), dp(8), dp(12), dp(8))
    isClickable = true
    isFocusable = true
    setOnClickListener {
      currentDestination = currentDestination.copy(settingsSubpage = card.subpage)
      renderDestination()
    }
    addView(
      TextView(this@AppShellActivity).apply {
        text = card.title
        typeface = if (card.subpage == SettingsSubpage.SAFETY) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        setTextColor(accentColor)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
      },
      LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
    )
    addView(
      TextView(this@AppShellActivity).apply {
        text = getString(R.string.settings_chevron)
        setTextColor(if (card.subpage == SettingsSubpage.SAFETY) OpenCrayUiTokens.primary else Color.parseColor("#C7C7CC"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        typeface = Typeface.DEFAULT_BOLD
      },
    )
  }

  private fun buildMcpHomeToggleSurface(): View = LinearLayout(this).apply {
    val isMasterEnabled = isMcpMasterToggleEnabled()

    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    background = surfaceBackground(OpenCrayUiTokens.surface)
    setPadding(dp(14), dp(14), dp(14), dp(14))

    val copyColumn = LinearLayout(this@AppShellActivity).apply {
      orientation = LinearLayout.VERTICAL
      addView(titleText(getString(R.string.mcp_home_master_title), textSizeSp = 16f))
      addView(helperTextView(getString(R.string.mcp_home_master_summary)), textParams(topDp = 6))
    }

    addView(
      copyColumn,
      LinearLayout.LayoutParams(
        0,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        1f,
      ),
    )
    addView(
      Switch(this@AppShellActivity).apply {
        isChecked = isMasterEnabled
        text = onOffLabel(isMasterEnabled)
        contentDescription = getString(R.string.mcp_home_master_title)
        setOnCheckedChangeListener { buttonView, isChecked ->
          buttonView.text = onOffLabel(isChecked)
          toggleAllMcpIntegrations(isChecked)
        }
      },
    )
  }

  private fun buildSettingsSubpageHeader(subpage: SettingsSubpage): View = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(0), dp(0), dp(0), dp(0))

    addView(
      TextView(this@AppShellActivity).apply {
        text = getString(R.string.settings_subpage_back)
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(OpenCrayUiTokens.primary)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(0, dp(4), 0, dp(4))
        isClickable = true
        isFocusable = true
        setOnClickListener {
          currentDestination = currentDestination.copy(settingsSubpage = SettingsSubpage.HOME)
          renderDestination()
        }
      },
    )
    addView(sectionTitle(labelForSettingsSubpage(subpage)), textParams(topDp = 6))
    addView(
      bodyTextView(prototypeSubtitleForSettingsSubpage(subpage)).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
      },
      textParams(topDp = 8),
    )
  }

  private fun buildSettingsSubpagePlaceholder(subpage: SettingsSubpage): View = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = surfaceBackground(settingsSurfaceColor(subpage))
    setPadding(dp(18), dp(18), dp(18), dp(18))

    addView(titleText(labelForSettingsSubpage(subpage), textSizeSp = 20f))
    addView(
      bodyTextView(getString(R.string.settings_subpage_placeholder, labelForSettingsSubpage(subpage))),
      textParams(topDp = 8),
    )
  }

  private fun renderMcpSettingsSubpage() {
    settingsContentContainer.addView(
      buildMcpSettingsGuidanceCard(),
      textParams(topDp = 16),
    )

    currentMcpPresentations().forEachIndexed { index, server ->
      settingsContentContainer.addView(
        buildMcpServerCard(server),
        textParams(topDp = if (index == 0) 12 else 10),
      )
    }
  }

  private fun buildMcpSettingsGuidanceCard(): View = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = surfaceBackground(settingsSurfaceColor(SettingsSubpage.MCP))
    setPadding(dp(20), dp(20), dp(20), dp(20))

    addView(titleText(getString(R.string.mcp_settings_guidance_title), textSizeSp = 18f))
    addView(bodyTextView(getString(R.string.mcp_settings_guidance_body)), textParams(topDp = 8))
  }

  private fun buildMcpServerCard(server: McpServerPresentation): View = LinearLayout(this).apply {
    val accentColor = Color.parseColor(server.accentColorHex)

    orientation = LinearLayout.VERTICAL
    background = surfaceBackground(Color.WHITE)
    setPadding(dp(20), dp(20), dp(20), dp(20))

    addView(
      LinearLayout(this@AppShellActivity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL

        addView(
          LinearLayout(this@AppShellActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(titleText(server.displayName, textSizeSp = 18f))
            addView(helperTextView(server.transportLabel), textParams(topDp = 6))
          },
          LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f,
          ),
        )
        addView(statusBadgeText(server.statusLabel, accentColor, server.accentColorHex))
      },
    )
    addView(helperTextView(server.trustLabel), textParams(topDp = 12))
    addView(helperTextView(server.exposureLabel), textParams(topDp = 6))
    addView(helperTextView(server.authLabel), textParams(topDp = 6))
    addView(helperTextView(server.readinessLabel), textParams(topDp = 6))
    addView(helperTextView(server.guidanceLabel), textParams(topDp = 10))

    if (server.actionLabel != null && server.actionKind != null) {
      addView(
        actionButton(
          label = server.actionLabel,
          fillColor = accentColor,
        ).apply {
          setOnClickListener { handleMcpServerAction(server.id, server.actionKind) }
        },
        textParams(topDp = 12),
      )
    }
  }

  private fun currentMcpPresentations(): List<McpServerPresentation> {
    val report = currentMcpReport()
    val activeServers = report.activeClients.map(::activeMcpPresentation)
    val blockedServers = report.blockedClients.map(::blockedMcpPresentation)

    return (activeServers + blockedServers)
      .sortedBy { server -> server.displayName.lowercase(Locale.ROOT) }
  }

  private fun activeMcpPresentation(client: McpClientDescriptor): McpServerPresentation = McpServerPresentation(
    id = client.id,
    displayName = client.displayName,
    statusLabel = getString(R.string.mcp_status_active),
    transportLabel = transportSummary(client.transport),
    trustLabel = localizedTrustLine(client.trustState, client.manuallyEnabled),
    exposureLabel = getString(R.string.mcp_exposure_active),
    authLabel = localizedAuthLine(client.auth),
    readinessLabel = localizedReadinessLine(client.auth),
    guidanceLabel = if (client.auth.isReady) {
      getString(R.string.mcp_guidance_active_ready)
    } else {
      getString(R.string.mcp_guidance_active_needs_auth)
    },
    accentColorHex = if (client.auth.isReady) "#2353B6" else "#9A6700",
    actionLabel = getString(R.string.mcp_action_disable_server),
    actionKind = McpServerActionKind.DISABLE,
  )

  private fun blockedMcpPresentation(client: McpBlockedClientDescriptor): McpServerPresentation {
    val isManualEnable = client.blockReason == McpClientBlockReason.REQUIRES_MANUAL_ENABLE

    return McpServerPresentation(
      id = client.id,
      displayName = client.displayName,
      statusLabel = getString(R.string.mcp_status_blocked),
      transportLabel = transportSummary(client.transport),
      trustLabel = localizedTrustLine(client.trustState, client.manuallyEnabled),
      exposureLabel = getString(R.string.mcp_exposure_blocked),
      authLabel = localizedAuthLine(client.auth),
      readinessLabel = localizedReadinessLine(client.auth),
      guidanceLabel = when {
        isManualEnable -> getString(R.string.mcp_guidance_blocked_manual)

        client.auth.isReady -> getString(R.string.mcp_guidance_blocked_disabled_ready)

        else -> getString(R.string.mcp_guidance_blocked_disabled_needs_auth)
      },
      accentColorHex = if (isManualEnable) "#9A6700" else "#5D6B7B",
      actionLabel = getString(R.string.mcp_action_enable_server),
      actionKind = if (isManualEnable) {
        McpServerActionKind.MANUAL_ENABLE
      } else {
        McpServerActionKind.ENABLE
      },
    )
  }

  private fun toggleAllMcpIntegrations(enableAll: Boolean) {
    if (enableAll) {
      mcpRegistry.list().forEach { server ->
        if (
          server.trustState == McpServerTrustState.DISABLED &&
          (server.declaredTrustState == McpServerTrustState.ENABLED || server.manuallyEnabled)
        ) {
          mcpRegistry.enable(server.id)
        }
      }
    } else {
      currentMcpReport().activeClients.forEach { server ->
        mcpRegistry.disable(server.id)
      }
    }

    renderSettingsSurface(force = true)
  }

  private fun handleMcpServerAction(
    id: String,
    actionKind: McpServerActionKind,
  ) {
    when (actionKind) {
      McpServerActionKind.ENABLE -> mcpRegistry.enable(id)
      McpServerActionKind.DISABLE -> mcpRegistry.disable(id)
      McpServerActionKind.MANUAL_ENABLE -> mcpRegistry.manualEnable(id)
    }

    renderSettingsSurface(force = true)
  }

  private fun currentMcpReport(): McpClientExposureReport = mcpClientFactory.load(mcpRegistry)

  private fun buildSeededMcpRegistry(): McpRegistry {
    var now = MCP_SETTINGS_SEED_EPOCH_MS + 100

    val assistantLocalSpec = McpServerSpec(
      id = "assistant-local",
      displayName = getString(R.string.mcp_server_name_assistant_local),
      transport = McpTransportDescriptor.LocalStdio(
        command = "opencray-assistant-mcp",
        args = listOf("--stdio"),
        environment = mapOf("OPENCRAY_PROFILE" to "consumer"),
        workingDirectory = "/data/local/tmp/opencray",
      ),
      trustState = McpServerTrustState.ENABLED,
    )
    val docsProxySpec = McpServerSpec(
      id = "docs-proxy",
      displayName = getString(R.string.mcp_server_name_docs_proxy),
      transport = McpTransportDescriptor.RemoteHttp(
        url = "https://docs.opencray.dev/mcp",
        headers = mapOf("Authorization" to "Bearer hidden-docs-token"),
      ),
      trustState = McpServerTrustState.ENABLED,
      auth = McpAuthSpec(credentialRef = "secret://mcp/docs-proxy-token"),
    )
    val communityBridgeSpec = McpServerSpec(
      id = "community-bridge",
      displayName = getString(R.string.mcp_server_name_community_bridge),
      transport = McpTransportDescriptor.RemoteSse(
        eventsUrl = "https://community.opencray.dev/mcp/events",
        postUrl = "https://community.opencray.dev/mcp/messages",
        headers = mapOf("Authorization" to "Bearer hidden-community-token"),
      ),
      trustState = McpServerTrustState.REQUIRES_MANUAL_ENABLE,
      auth = McpAuthSpec(credentialRef = "secret://mcp/community-bridge-token"),
    )

    val seededRecord = McpRegistryRecord(
      servers = listOf(
        McpRegistryServerRecord(
          spec = assistantLocalSpec,
          declaredTrustState = assistantLocalSpec.trustState,
          trustState = McpServerTrustState.ENABLED,
          authState = McpServerAuthState.fromSpec(assistantLocalSpec.auth),
          registeredAtEpochMs = MCP_SETTINGS_SEED_EPOCH_MS,
          updatedAtEpochMs = MCP_SETTINGS_SEED_EPOCH_MS,
        ),
        McpRegistryServerRecord(
          spec = docsProxySpec,
          declaredTrustState = docsProxySpec.trustState,
          trustState = McpServerTrustState.ENABLED,
          authState = McpServerAuthState.missing(),
          registeredAtEpochMs = MCP_SETTINGS_SEED_EPOCH_MS + 1,
          updatedAtEpochMs = MCP_SETTINGS_SEED_EPOCH_MS + 1,
        ),
        McpRegistryServerRecord(
          spec = communityBridgeSpec,
          declaredTrustState = communityBridgeSpec.trustState,
          trustState = McpServerTrustState.REQUIRES_MANUAL_ENABLE,
          authState = McpServerAuthState.configured(
            credentialRef = CredentialRef("secret://mcp/community-bridge-token"),
          ),
          registeredAtEpochMs = MCP_SETTINGS_SEED_EPOCH_MS + 2,
          updatedAtEpochMs = MCP_SETTINGS_SEED_EPOCH_MS + 2,
        ),
      ),
      createdAtEpochMs = MCP_SETTINGS_SEED_EPOCH_MS,
      updatedAtEpochMs = MCP_SETTINGS_SEED_EPOCH_MS + 3,
    )

    return McpRegistry(
      store = InMemoryMcpRegistryStore(initialRecord = seededRecord),
      now = { now++ },
    )
  }

  private fun localizedTrustLine(
    trustState: McpServerTrustState,
    manuallyEnabled: Boolean,
  ): String = if (manuallyEnabled) {
    getString(
      R.string.mcp_trust_line_with_manual,
      trustStateLabel(trustState),
      getString(R.string.mcp_manual_consent_saved),
    )
  } else {
    getString(R.string.mcp_trust_line, trustStateLabel(trustState))
  }

  private fun localizedAuthLine(auth: McpClientAuthDescriptor): String = getString(
    R.string.mcp_auth_line,
    authStatusLabel(auth.status),
  )

  private fun localizedReadinessLine(auth: McpClientAuthDescriptor): String = getString(
    R.string.mcp_readiness_line,
    getString(
      if (auth.isReady) {
        R.string.mcp_readiness_ready
      } else {
        R.string.mcp_readiness_needs_attention
      },
    ),
  )

  private fun transportSummary(transport: McpClientTransportDescriptor): String = when (transport) {
    is McpClientTransportDescriptor.LocalStdio -> getString(R.string.mcp_transport_local_stdio)

    is McpClientTransportDescriptor.RemoteHttp -> getString(R.string.mcp_transport_remote_http)

    is McpClientTransportDescriptor.RemoteSse -> getString(R.string.mcp_transport_remote_sse)
  }

  private fun trustStateLabel(trustState: McpServerTrustState): String = when (trustState) {
    McpServerTrustState.ENABLED -> getString(R.string.mcp_trust_state_enabled)
    McpServerTrustState.DISABLED -> getString(R.string.mcp_trust_state_disabled)
    McpServerTrustState.REQUIRES_MANUAL_ENABLE -> getString(R.string.mcp_trust_state_requires_manual_enable)
  }

  private fun authStatusLabel(status: McpServerAuthStatus): String = when (status) {
    McpServerAuthStatus.NOT_REQUIRED -> getString(R.string.mcp_auth_status_not_required)

    McpServerAuthStatus.CONFIGURED -> getString(R.string.mcp_auth_status_configured)

    McpServerAuthStatus.MISSING -> getString(R.string.mcp_auth_status_missing)

    McpServerAuthStatus.ERROR -> getString(R.string.mcp_auth_status_error)
  }

  private fun buildMcpSummaryLine(report: McpClientExposureReport): String {
    val attentionCount = report.activeClients.count { client -> !client.auth.isReady } +
      report.blockedClients.count { client ->
        !client.auth.isReady || client.blockReason == McpClientBlockReason.REQUIRES_MANUAL_ENABLE
      }

    return getString(
      R.string.mcp_summary_line,
      report.activeClients.size,
      report.blockedClients.size,
      attentionCount,
    )
  }

  private fun isMcpMasterToggleEnabled(): Boolean {
    val toggleEligibleServers = mcpRegistry.list().filter { server ->
      server.declaredTrustState == McpServerTrustState.ENABLED || server.manuallyEnabled
    }

    return toggleEligibleServers.isNotEmpty() && toggleEligibleServers.all { server ->
      server.trustState == McpServerTrustState.ENABLED
    }
  }

  private fun statusBadgeText(
    text: String,
    accentColor: Int,
    accentColorHex: String,
  ): TextView = TextView(this).apply {
    this.text = text
    typeface = Typeface.DEFAULT_BOLD
    setTextColor(accentColor)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
    background = pillBackground(settingsSurfaceColorForAccent(accentColorHex))
    setPadding(dp(10), dp(8), dp(10), dp(8))
  }

  private fun persistDestination() {
    stateStore.save(currentDestination)
  }

  private fun restoreChatState(savedInstanceState: Bundle?) {
    chatSeedScenario =
      savedInstanceState?.getString(STATE_CHAT_SCENARIO)?.let(SeedScenario::fromRaw)
        ?: scenarioFromIntent()

    if (savedInstanceState == null) {
      resetToSeededState()
      return
    }

    selectedMode = savedInstanceState.getString(STATE_CHAT_SELECTED_MODE)?.let(::chatModeFromRaw) ?: ChatMode.SAFE
    isQueueVisible = savedInstanceState.getBoolean(STATE_CHAT_QUEUE_VISIBLE, true)
    approvalOutcome =
      savedInstanceState.getString(STATE_CHAT_APPROVAL_OUTCOME)?.let(ApprovalOutcome::fromRaw)
        ?: seededApprovalOutcome(chatSeedScenario)
  }

  private fun renderChatState() {
    if (::chatScreen.isInitialized) {
      chatScreen.submitState(buildChatState())
    }
  }

  private fun renderFilesWorkbench() {
    if (::filesWorkbenchScreen.isInitialized) {
      filesWorkbenchScreen.submitReflectiveState(
        buildFilesWorkbenchState(this, filesWorkbenchScenario),
      )
    }
  }

  private fun activateWorkspaceGrant() {
    filesWorkbenchScenario = FilesWorkbenchSeedScenario.ACTIVE_GRANT
    renderFilesWorkbench()
    renderSettingsSurface(force = true)
  }

  private fun reauthorizeWorkspaceGrant() {
    activateWorkspaceGrant()
  }

  private fun clearWorkspaceGrant() {
    filesWorkbenchScenario = FilesWorkbenchSeedScenario.NO_GRANT
    renderFilesWorkbench()
    renderSettingsSurface(force = true)
  }

  private fun restorePersonalizationState(savedInstanceState: Bundle?) {
    val persistedSoulProfile = if (savedInstanceState == null) personalizationStore.loadSoulProfile() else null
    personalizationPreset =
      savedInstanceState?.getString(STATE_PERSONALIZATION_PRESET)?.let(PersonalizationPreset::fromRaw)
        ?: persistedSoulProfile?.presetName?.let(PersonalizationPreset::fromRaw)
        ?: PersonalizationPreset.STEADY
    personalizationCustomLabel =
      savedInstanceState?.getString(STATE_PERSONALIZATION_CUSTOM_LABEL)
        ?: persistedSoulProfile?.customLabel
        ?: ""
    personalizationCustomGuidance =
      savedInstanceState?.getString(STATE_PERSONALIZATION_CUSTOM_GUIDANCE)
        ?: persistedSoulProfile?.customGuidance
        ?: ""
    personalizationMemoryConfirmation =
      savedInstanceState?.getString(STATE_PERSONALIZATION_MEMORY_CONFIRMATION).orEmpty()
    personalizationSoulConfirmation =
      savedInstanceState?.getString(STATE_PERSONALIZATION_SOUL_CONFIRMATION).orEmpty()
    personalizationLastResetPreview =
      savedInstanceState?.getString(STATE_PERSONALIZATION_LAST_RESET_PREVIEW)
        ?.let(PersonalizationResetPreview::fromRaw)
        ?: PersonalizationResetPreview.NONE
  }

  private fun resetToSeededState() {
    selectedMode = ChatMode.SAFE
    isQueueVisible = true
    approvalOutcome = seededApprovalOutcome(chatSeedScenario)
  }

  private fun scenarioFromIntent(): SeedScenario = SeedScenario.fromRaw(
    intent.getStringExtra(AppShellNavigationExtras.EXTRA_CHAT_SCENARIO),
  )

  private fun seededApprovalOutcome(seedScenario: SeedScenario): ApprovalOutcome = when (seedScenario) {
    SeedScenario.DEFAULT_APPROVAL -> ApprovalOutcome.PENDING
    SeedScenario.DENIED_POLICY -> ApprovalOutcome.POLICY_DENIED
  }

  private fun buildChatState(): ChatScreenState = ChatScreenState(
    title = chatSessionsState.activeSession.title,
    subtitle = buildChatSubtitle(),
    statusLabel = buildChatStatusLabel(),
    statusDetail = buildChatStatusDetail(),
    drawerSummary = buildChatDrawerSummary(),
    sessions = buildChatSessionItems(),
    messages = buildConversationMessages(),
    emptyMessage = "Start with a message, an image, a file, or a command.",
    draftHint = "Message OpenCray",
    composerAssistiveText = "",
    screenTitle = "Chat",
    modeLabel = chatModeBadgeLabel(selectedMode),
    activeSessionTitle = chatSessionsState.activeSession.title,
    activeSessionDetail = buildChatDrawerSummary(),
    sessionSummary = buildChatSessionSummary(),
    composerAttachments = pendingChatAttachments.map(::toAttachmentVisualState),
    availableCommands = listOf(
      ChatCommandOptionState(id = "summarize", label = "Summarize"),
      ChatCommandOptionState(id = "explain", label = "Explain"),
      ChatCommandOptionState(id = "plan", label = "Plan"),
    ),
    selectedCommandLabel = pendingChatCommandLabel,
  )

  private fun buildChatSubtitle(): String {
    val messageCount = visibleMessageCount(chatSessionsState.activeSession.messages)
    val sessionCount = chatSessionsState.sessions.size
    return "$messageCount messages stored locally • $sessionCount sessions"
  }

  private fun buildChatStatusLabel(): String = when (approvalOutcome) {
    ApprovalOutcome.PENDING -> "${selectedMode.displayName} mode • Approval pending"
    ApprovalOutcome.APPROVED -> "${selectedMode.displayName} mode • Write completed"
    ApprovalOutcome.DENIED -> "${selectedMode.displayName} mode • Write blocked"
    ApprovalOutcome.POLICY_DENIED -> "${selectedMode.displayName} mode • Policy blocked"
  }

  private fun buildChatStatusDetail(): String = when (approvalOutcome) {
    ApprovalOutcome.PENDING -> if (isQueueVisible) {
      "A seeded workspace write is still visible and waiting for explicit review before it can continue."
    } else {
      "A seeded workspace write is still waiting for explicit review before it can continue."
    }

    ApprovalOutcome.APPROVED -> "The queued write was approved and the latest result is now stored in the visible local transcript."
    ApprovalOutcome.DENIED -> "The queued write stayed blocked, so the workspace remains unchanged and the denial path stays visible."
    ApprovalOutcome.POLICY_DENIED -> "Policy denied the write before approval could continue, so the transcript keeps the blocked outcome explicit."
  }

  private fun buildChatDrawerSummary(): String {
    val activeSession = chatSessionsState.activeSession
    return when {
      isSessionInFlight(activeSession.sessionId) -> "Reply in progress"
      visibleMessageCount(activeSession.messages) == 0 -> "Empty local session"
      else -> "${visibleMessageCount(activeSession.messages)} stored messages"
    }
  }

  private fun buildChatSessionSummary(): ChatSessionSummaryState? {
    val activeSession = chatSessionsState.activeSession
    val visibleCount = visibleMessageCount(activeSession.messages)
    if (visibleCount == 0) {
      return null
    }
    return ChatSessionSummaryState(
      title = activeSession.title,
      meta = "$visibleCount messages",
      detail = when (selectedMode) {
        ChatMode.SAFE -> "Safe mode still asks before edits in this session."
        ChatMode.AUTO -> "Auto mode keeps the session moving with fewer interruptions."
        ChatMode.DEVELOPER -> "Dev mode exposes deeper control for this session."
      },
    )
  }

  private fun chatModeBadgeLabel(mode: ChatMode): String = when (mode) {
    ChatMode.SAFE -> "SAFE"
    ChatMode.AUTO -> "AUTO"
    ChatMode.DEVELOPER -> "DEV"
  }

  private fun buildChatComposerAssistiveText(): String {
    return ""
  }

  private fun buildChatSessionItems(): List<ChatSessionListItemState> = chatSessionsState.sessions.map { session ->
    ChatSessionListItemState(
      sessionId = session.sessionId,
      title = session.title,
      preview = session.lastMessagePreview,
      meta = "${session.messageCount} messages",
      isSelected = session.sessionId == chatSessionsState.activeSession.sessionId,
    )
  }

  private fun buildConversationMessages(): List<ChatMessageItemState> {
    val activeSession = chatSessionsState.activeSession
    val traceState = synchronized(transientAgentTraces) { transientAgentTraces[activeSession.sessionId] }
    val traceMessages = traceState?.items?.map { it.toChatMessageItemState() }.orEmpty()
    val renderedMessages = mutableListOf<ChatMessageItemState>()
    var insertedTrace = false

    activeSession.messages.forEach { message ->
      if (shouldHideChatMessage(message)) {
        return@forEach
      }
      if (!insertedTrace && traceState != null && message.messageId == traceState.anchorMessageId) {
        renderedMessages += traceMessages
        insertedTrace = true
      }
      val resolvedText = message.text ?: chatSessionStore.promptTemplateBody(message.promptTemplateRefId).orEmpty()
      renderedMessages += ChatMessageItemState(
        messageId = message.messageId,
        role = when (message.role) {
          com.opencray.persistence.model.ChatTranscriptRole.SYSTEM -> ChatMessageDisplayRole.SYSTEM
          com.opencray.persistence.model.ChatTranscriptRole.USER -> ChatMessageDisplayRole.USER
          com.opencray.persistence.model.ChatTranscriptRole.ASSISTANT -> ChatMessageDisplayRole.ASSISTANT
          com.opencray.persistence.model.ChatTranscriptRole.TOOL -> ChatMessageDisplayRole.TOOL
        },
        body = resolvedText,
        meta = "",
        commandLabel = message.commandLabel,
        attachments = message.attachments.map(::toAttachmentVisualState),
      )
    }
    if (!insertedTrace) {
      renderedMessages += traceMessages
    }
    return renderedMessages
  }

  private fun visibleMessageCount(messages: List<ChatTranscriptMessageEntry>): Int =
    messages.count { message -> !shouldHideChatMessage(message) }

  private fun toAttachmentVisualState(entry: ChatAttachmentEntry): ChatAttachmentVisualState = ChatAttachmentVisualState(
    attachmentId = entry.attachmentId,
    kind = when (entry.kind) {
      ChatAttachmentKind.IMAGE -> ChatAttachmentVisualKind.IMAGE
      ChatAttachmentKind.FILE -> ChatAttachmentVisualKind.FILE
    },
    displayName = entry.displayName,
    detail = entry.sizeBytes?.let(::formatAttachmentSize).orEmpty(),
    localPath = entry.localPath,
  )

  private fun formatAttachmentSize(sizeBytes: Long): String {
    val kb = 1024.0
    val mb = kb * 1024.0
    return when {
      sizeBytes >= mb -> String.format(Locale.US, "%.1f MB", sizeBytes / mb)
      sizeBytes >= kb -> String.format(Locale.US, "%.1f KB", sizeBytes / kb)
      else -> "$sizeBytes B"
    }
  }

  private fun buildRuntimeInput(
    text: String,
    commandLabel: String?,
    attachments: List<ChatAttachmentEntry>,
  ): String = buildString {
    if (!commandLabel.isNullOrBlank()) {
      append("Command: ")
      append(commandLabel)
      append('\n')
    }
    if (attachments.isNotEmpty()) {
      append("Attachments:\n")
      attachments.forEach { attachment ->
        append("- ")
        append(attachment.displayName)
        append('\n')
      }
    }
    if (text.isNotBlank()) {
      if (isNotEmpty()) {
        append('\n')
      }
      append(text)
    }
  }.trim()

  private fun shouldHideChatMessage(message: ChatTranscriptMessageEntry): Boolean =
    message.role == ChatTranscriptRole.SYSTEM &&
      message.promptTemplateRefId == ChatSessionLocalStore.DEFAULT_SYSTEM_TEMPLATE_ID

  private fun syncBottomNavigationVisibility() {
    if (!::bottomNavigationBar.isInitialized) {
      return
    }
    bottomNavigationBar.visibility =
      if (currentDestination.selectedTab == AppShellTab.CHAT && isKeyboardVisible) View.GONE else View.VISIBLE
  }

  private fun observeKeyboardVisibility() {
    if (!::rootShell.isInitialized) {
      return
    }
    rootShell.viewTreeObserver.addOnGlobalLayoutListener {
      val visibleFrame = Rect()
      rootShell.getWindowVisibleDisplayFrame(visibleFrame)
      val obscuredHeight = rootShell.rootView.height - visibleFrame.height()
      val keyboardVisibleNow = obscuredHeight > dp(140)
      if (keyboardVisibleNow != isKeyboardVisible) {
        isKeyboardVisible = keyboardVisibleNow
        syncBottomNavigationVisibility()
      }
    }
  }

  private fun assistantPlaceholderFor(text: String): String = when {
    text.startsWith("/") -> "The command was captured into this session. Command execution wiring can be attached next without losing transcript history."
    "@file" in text -> "The file reference was inserted into the transcript. A future picker can resolve it to a concrete workspace file while keeping this message immutable."
    else -> "This message is now persisted in the active session. The runtime reply pipeline can be connected later without changing the transcript format."
  }

  private fun queuedActionCount(): Int = synchronized(inFlightChatSessions) { inFlightChatSessions.size }

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

  private fun chatModeFromRaw(rawValue: String): ChatMode? = ChatMode.entries.firstOrNull { mode ->
    mode.name.equals(rawValue, ignoreCase = true)
  }

  private fun labelForTab(tab: AppShellTab): String = when (tab) {
    AppShellTab.CHAT -> getString(R.string.shell_tab_chat)
    AppShellTab.SKILLS -> getString(R.string.shell_tab_skills)
    AppShellTab.FILES -> getString(R.string.shell_tab_files)
    AppShellTab.SETTINGS -> getString(R.string.shell_tab_settings)
  }

  private fun labelForSettingsTitle(subpage: SettingsSubpage): String = when (subpage) {
    SettingsSubpage.HOME -> getString(R.string.shell_tab_settings)
    else -> labelForSettingsSubpage(subpage)
  }

  private fun labelForSettingsSubpage(subpage: SettingsSubpage): String = when (subpage) {
    SettingsSubpage.HOME -> getString(R.string.shell_tab_settings)
    SettingsSubpage.WORKSPACE -> getString(R.string.settings_card_workspace_access)
    SettingsSubpage.LLM -> getString(R.string.settings_card_llm)
    SettingsSubpage.MCP -> getString(R.string.settings_card_mcp)
    SettingsSubpage.PRIVACY -> getString(R.string.settings_card_privacy_telemetry)
    SettingsSubpage.SAFETY -> getString(R.string.settings_card_safety_limits)
    SettingsSubpage.ABOUT -> getString(R.string.settings_card_about_version)
    SettingsSubpage.PERSONALIZATION -> getString(R.string.settings_card_personalization)
  }

  private fun settingsHomeCards(): List<SettingsHomeCard> {
    return listOf(
      SettingsHomeCard(
        subpage = SettingsSubpage.WORKSPACE,
        title = labelForSettingsSubpage(SettingsSubpage.WORKSPACE),
        detail = "",
        summary = "",
        badgeText = "",
        accentColorHex = accentColorForSettingsSubpage(SettingsSubpage.WORKSPACE),
      ),
      SettingsHomeCard(
        subpage = SettingsSubpage.LLM,
        title = labelForSettingsSubpage(SettingsSubpage.LLM),
        detail = "",
        summary = "",
        badgeText = "",
        accentColorHex = accentColorForSettingsSubpage(SettingsSubpage.LLM),
      ),
      SettingsHomeCard(
        subpage = SettingsSubpage.MCP,
        title = labelForSettingsSubpage(SettingsSubpage.MCP),
        detail = "",
        summary = "",
        badgeText = "",
        accentColorHex = accentColorForSettingsSubpage(SettingsSubpage.MCP),
      ),
      SettingsHomeCard(
        subpage = SettingsSubpage.PRIVACY,
        title = labelForSettingsSubpage(SettingsSubpage.PRIVACY),
        detail = "",
        summary = "",
        badgeText = "",
        accentColorHex = accentColorForSettingsSubpage(SettingsSubpage.PRIVACY),
      ),
      SettingsHomeCard(
        subpage = SettingsSubpage.SAFETY,
        title = labelForSettingsSubpage(SettingsSubpage.SAFETY),
        detail = "",
        summary = "",
        badgeText = "",
        accentColorHex = accentColorForSettingsSubpage(SettingsSubpage.SAFETY),
      ),
      SettingsHomeCard(
        subpage = SettingsSubpage.ABOUT,
        title = labelForSettingsSubpage(SettingsSubpage.ABOUT),
        detail = "",
        summary = "",
        badgeText = "",
        accentColorHex = accentColorForSettingsSubpage(SettingsSubpage.ABOUT),
      ),
      SettingsHomeCard(
        subpage = SettingsSubpage.PERSONALIZATION,
        title = labelForSettingsSubpage(SettingsSubpage.PERSONALIZATION),
        detail = "",
        summary = "",
        badgeText = "",
        accentColorHex = accentColorForSettingsSubpage(SettingsSubpage.PERSONALIZATION),
      ),
    )
  }

  private fun currentTelemetrySettingsState(): TelemetryTogglesState = telemetrySettingsStore.load(
    TelemetryTogglesState.localized(this),
  )

  private fun personalizationToneLabel(preset: PersonalizationPreset): String = when (preset) {
    PersonalizationPreset.STEADY -> getString(R.string.settings_tone_quiet)
    PersonalizationPreset.BUILDER -> getString(R.string.settings_tone_focus)
    PersonalizationPreset.WARM -> getString(R.string.settings_tone_warm)
  }

  private fun telemetryProfileLabel(state: TelemetryTogglesState): String = if (state.telemetry.isChecked) {
    getString(R.string.settings_telemetry_profile_active)
  } else {
    getString(R.string.settings_telemetry_profile_minimal)
  }

  private fun currentAppLanguage(): AppLanguage = localeSettingsStore.loadLanguage()

  private fun currentAppLanguageLabel(): String = when (currentAppLanguage()) {
    AppLanguage.ENGLISH -> getString(R.string.settings_language_english)
    AppLanguage.SIMPLIFIED_CHINESE -> getString(R.string.settings_language_simplified_chinese)
  }

  private fun switchAppLanguage(language: AppLanguage) {
    if (currentAppLanguage() == language) {
      return
    }
    localeSettingsStore.saveLanguage(language)
    recreate()
  }

  private fun llmStatusLabel(state: LlmSettingsState): String = when {
    !state.enabled -> getString(R.string.llm_settings_status_disabled)
    !state.isConfigured() -> getString(R.string.llm_settings_status_incomplete)
    else -> getString(R.string.llm_settings_status_configured)
  }

  private fun llmSettingsHomeSummary(state: LlmSettingsState): String = when {
    !state.enabled -> getString(R.string.settings_home_llm_summary_disabled)
    !state.isConfigured() -> getString(R.string.settings_home_llm_summary_incomplete)
    else -> getString(R.string.settings_home_llm_summary_configured, state.model.ifBlank { LlmSettingsState.DEFAULT_MODEL })
  }

  private fun currentSafetyState(): SafetyAndLimitsScreenState = SafetyAndLimitsScreenState.localized(this)

  private fun summaryForSettingsSubpage(subpage: SettingsSubpage): String = when (subpage) {
    SettingsSubpage.LLM -> getString(R.string.settings_subpage_summary_llm)
    SettingsSubpage.MCP -> getString(R.string.settings_subpage_summary_mcp)

    else -> settingsHomeCards()
      .firstOrNull { it.subpage == subpage }
      ?.summary
      ?: labelForSettingsSubpage(subpage)
  }

  private fun prototypeSubtitleForSettingsSubpage(subpage: SettingsSubpage): String = when (subpage) {
    SettingsSubpage.WORKSPACE -> getString(R.string.settings_workspace_subtitle)
    SettingsSubpage.LLM -> getString(R.string.settings_llm_subtitle)
    SettingsSubpage.MCP -> getString(R.string.settings_mcp_subtitle)
    SettingsSubpage.PRIVACY -> getString(R.string.settings_privacy_subtitle)
    SettingsSubpage.SAFETY -> getString(R.string.settings_safety_subtitle)
    SettingsSubpage.ABOUT -> getString(R.string.settings_about_subtitle)
    SettingsSubpage.PERSONALIZATION -> getString(R.string.settings_personalization_subtitle)
    SettingsSubpage.HOME -> getString(R.string.settings_home_intro)
  }

  private fun highestRiskSafetyHeadline(state: SafetyAndLimitsScreenState): String {
    val highestRiskCard =
      (state.modeRiskCards + state.rollbackLimitCards + state.telemetryPrivacyCards + state.v1ScopeCards)
        .firstOrNull { it.tone == DisclosureTone.DANGER }

    return highestRiskCard?.title ?: state.title
  }

  private fun privacySummary(state: TelemetryTogglesState): String = getString(
    R.string.settings_home_privacy_summary,
    state.telemetry.switchLabel,
    onOffLabel(state.telemetry.isChecked),
    state.privacyGuard.switchLabel,
    onOffLabel(state.privacyGuard.isChecked),
  )

  private fun workspaceAccessSummary(): String = when (filesWorkbenchScenario) {
    FilesWorkbenchSeedScenario.NO_GRANT -> getString(R.string.workspace_settings_summary_no_grant)
    FilesWorkbenchSeedScenario.ACTIVE_GRANT -> getString(R.string.workspace_settings_summary_active)
    FilesWorkbenchSeedScenario.REVOKED_GRANT -> getString(R.string.workspace_settings_summary_revoked)
    FilesWorkbenchSeedScenario.OUTSIDE_ROOT_DENIAL -> getString(R.string.workspace_settings_summary_outside_root)
  }

  private fun workspaceAccessDetail(): String = when (filesWorkbenchScenario) {
    FilesWorkbenchSeedScenario.NO_GRANT -> getString(R.string.workspace_settings_detail_no_grant)
    FilesWorkbenchSeedScenario.ACTIVE_GRANT -> getString(R.string.workspace_settings_detail_active)
    FilesWorkbenchSeedScenario.REVOKED_GRANT -> getString(R.string.workspace_settings_detail_revoked)
    FilesWorkbenchSeedScenario.OUTSIDE_ROOT_DENIAL -> getString(R.string.workspace_settings_detail_outside_root)
  }

  private fun workspacePrimaryActionLabel(): String = when (filesWorkbenchScenario) {
    FilesWorkbenchSeedScenario.REVOKED_GRANT -> getString(R.string.workspace_settings_primary_reauthorize)
    else -> getString(R.string.workspace_settings_primary_pick)
  }

  private fun workspaceStatusBadgeLabel(): String = when (filesWorkbenchScenario) {
    FilesWorkbenchSeedScenario.NO_GRANT -> "No access"
    FilesWorkbenchSeedScenario.ACTIVE_GRANT -> "Ready"
    FilesWorkbenchSeedScenario.REVOKED_GRANT -> "Recovery"
    FilesWorkbenchSeedScenario.OUTSIDE_ROOT_DENIAL -> "Scoped"
  }

  private fun settingsHeaderBadgeText(subpage: SettingsSubpage): String = when (subpage) {
    SettingsSubpage.WORKSPACE -> workspaceStatusBadgeLabel()
    SettingsSubpage.LLM -> llmStatusLabel(llmSettingsStore.load())
    SettingsSubpage.MCP -> if (currentMcpReport().blockedClients.isEmpty()) "Ready" else "Attention"
    SettingsSubpage.PRIVACY -> if (currentTelemetrySettingsState().privacyGuard.isChecked) "Guard On" else "Guard Off"
    SettingsSubpage.SAFETY -> "Review"
    SettingsSubpage.ABOUT -> installedVersionLabel()
    SettingsSubpage.PERSONALIZATION -> personalizationPresetTitle(personalizationPreset)
    SettingsSubpage.HOME -> getString(R.string.shell_tab_settings)
  }

  private fun onOffLabel(value: Boolean): String = getString(
    if (value) {
      R.string.shell_common_on
    } else {
      R.string.shell_common_off
    },
  )

  private fun installedVersionLabel(): String = installedBuildInfo().versionName

  private fun installedBuildInfo(): InstalledBuildInfo = runCatching {
    val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
      @Suppress("DEPRECATION")
      packageManager.getPackageInfo(packageName, 0)
    }
    InstalledBuildInfo(
      versionName = packageInfo.versionName?.trim().orEmpty().ifBlank { "0" },
      versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        packageInfo.longVersionCode
      } else {
        @Suppress("DEPRECATION")
        packageInfo.versionCode.toLong()
      },
      minSdk = applicationInfo.minSdkVersion,
    )
  }.getOrElse {
    InstalledBuildInfo(
      versionName = "0",
      versionCode = 0L,
      minSdk = applicationInfo.minSdkVersion,
    )
  }

  private fun minimumSupportedAndroidLabel(minSdk: Int): String {
    val releaseLabel = when (minSdk) {
      26 -> getString(R.string.shell_android_release_26)
      27 -> getString(R.string.shell_android_release_27)
      28 -> getString(R.string.shell_android_release_28)
      29 -> getString(R.string.shell_android_release_29)
      30 -> getString(R.string.shell_android_release_30)
      31 -> getString(R.string.shell_android_release_31)
      32 -> getString(R.string.shell_android_release_32)
      33 -> getString(R.string.shell_android_release_33)
      34 -> getString(R.string.shell_android_release_34)
      else -> getString(R.string.shell_android_release_generic)
    }

    return getString(R.string.shell_android_api_label, releaseLabel, minSdk)
  }

  private fun accentColorForSettingsSubpage(subpage: SettingsSubpage): String = when (subpage) {
    SettingsSubpage.HOME -> "#2353B6"
    SettingsSubpage.WORKSPACE -> "#0F766E"
    SettingsSubpage.LLM -> "#0F5F8C"
    SettingsSubpage.MCP -> "#2353B6"
    SettingsSubpage.PRIVACY -> "#1D8A78"
    SettingsSubpage.SAFETY -> "#9A6700"
    SettingsSubpage.ABOUT -> "#4D5BD4"
    SettingsSubpage.PERSONALIZATION -> "#1F7A44"
  }

  private fun sectionEyebrow(accentColor: String): TextView = TextView(this).apply {
    text = getString(R.string.shell_product_name)
    typeface = Typeface.DEFAULT_BOLD
    setTextColor(Color.parseColor(accentColor))
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
    setPadding(dp(10), dp(6), dp(10), dp(6))
    background = pillBackground(OpenCrayUiTokens.surfaceMuted)
  }

  private fun sectionTitle(text: String): TextView = TextView(this).apply {
    this.text = text
    typeface = Typeface.DEFAULT_BOLD
    setTextColor(OpenCrayUiTokens.textPrimary)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
  }

  private fun bodyTextView(text: String? = null): TextView = TextView(this).apply {
    if (text != null) {
      this.text = text
    }
    setTextColor(OpenCrayUiTokens.textSecondary)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
    setLineSpacing(dp(2).toFloat(), 1f)
  }

  private fun helperTextView(text: String): TextView = bodyTextView(text).apply {
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
  }

  private fun titleText(
    text: String,
    textSizeSp: Float,
  ): TextView = TextView(this).apply {
    this.text = text
    typeface = Typeface.DEFAULT_BOLD
    setTextColor(OpenCrayUiTokens.textPrimary)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
  }

  private fun sectionBackground(strokeColor: Int): GradientDrawable = GradientDrawable().apply {
    val drawable = ocCardBackground(OpenCraySurfaceTone.NEUTRAL)
    shape = drawable.shape
    cornerRadius = drawable.cornerRadius
    color = drawable.color
    setStroke(0, strokeColor)
  }

  private fun detailBackground(strokeColor: Int): GradientDrawable = GradientDrawable().apply {
    val drawable = ocCardBackground(OpenCraySurfaceTone.SUBTLE, radiusDp = OpenCrayUiTokens.radiusInput)
    shape = drawable.shape
    cornerRadius = drawable.cornerRadius
    color = drawable.color
    setStroke(0, strokeColor)
  }

  private fun surfaceBackground(fillColor: Int): GradientDrawable = GradientDrawable().apply {
    val drawable = ocSurfaceBackground(fillColor = fillColor, radiusDp = OpenCrayUiTokens.radiusCard)
    shape = drawable.shape
    cornerRadius = drawable.cornerRadius
    color = drawable.color
  }

  private fun pillBackground(fillColor: Int): GradientDrawable = GradientDrawable().apply {
    val drawable = ocPillBackground(fillColor = fillColor)
    shape = drawable.shape
    cornerRadius = drawable.cornerRadius
    color = drawable.color
  }

  private fun dividerLine(): View = View(this).apply {
    setBackgroundColor(OpenCrayUiTokens.border)
    minimumHeight = dp(1)
  }

  private fun settingsSurfaceColor(subpage: SettingsSubpage): Int = when (subpage) {
    SettingsSubpage.HOME -> Color.WHITE
    SettingsSubpage.WORKSPACE -> Color.parseColor("#EAF7F6")
    SettingsSubpage.LLM -> Color.parseColor("#EAF4FA")
    SettingsSubpage.MCP -> Color.parseColor("#EFF7FA")
    SettingsSubpage.PRIVACY -> Color.parseColor("#ECF8F6")
    SettingsSubpage.SAFETY -> Color.parseColor("#FFF7E8")
    SettingsSubpage.ABOUT -> Color.parseColor("#F2F4FF")
    SettingsSubpage.PERSONALIZATION -> Color.parseColor("#EEF8F1")
  }

  private fun settingsSurfaceColorForAccent(accentColorHex: String): Int = when (accentColorHex) {
    "#2353B6" -> Color.parseColor("#EAF0FF")
    "#1D8A78" -> Color.parseColor("#E6F7F2")
    "#9A6700" -> Color.parseColor("#FFF4DD")
    "#4D5BD4" -> Color.parseColor("#EEF0FF")
    "#1F7A44" -> Color.parseColor("#E9F7EE")
    "#5D6B7B" -> Color.parseColor("#EEF2F6")
    else -> Color.parseColor("#F3F6FA")
  }

  private fun <T> buildSegmentedControl(
    options: List<Pair<T, String>>,
    selected: T,
    onSelected: (T) -> Unit,
  ): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    background = pillBackground(Color.parseColor("#ECEEF3"))
    setPadding(dp(4), dp(4), dp(4), dp(4))

    options.forEachIndexed { index, (value, label) ->
      addView(
        Button(this@AppShellActivity).apply {
          text = label.uppercase(Locale.US)
          isAllCaps = false
          minHeight = dp(34)
          minimumHeight = dp(34)
          minWidth = 0
          minimumWidth = 0
          stateListAnimator = null
          typeface = Typeface.DEFAULT_BOLD
          setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
          setPadding(dp(10), 0, dp(10), 0)
          val isSelected = selected == value
          background = pillBackground(if (isSelected) Color.WHITE else Color.parseColor("#ECEEF3"))
          setTextColor(if (isSelected) OpenCrayUiTokens.textPrimary else OpenCrayUiTokens.textSecondary)
          alpha = if (isSelected) 1f else 0.92f
          setOnClickListener { onSelected(value) }
        },
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
          if (index > 0) {
            marginStart = dp(6)
          }
        },
      )
    }
  }

  private fun buildPrototypeToggleRow(
    title: String,
    detail: String,
    checked: Boolean,
    onCheckedChanged: (Boolean) -> Unit,
  ): View = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    addView(
      LinearLayout(this@AppShellActivity).apply {
        orientation = LinearLayout.VERTICAL
        addView(titleText(title, textSizeSp = 15f))
        addView(helperTextView(detail), textParams(topDp = 4))
      },
      LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
    )
    addView(
      Switch(this@AppShellActivity).apply {
        isChecked = checked
        text = ""
        setOnCheckedChangeListener { _, isCheckedValue ->
          onCheckedChanged(isCheckedValue)
        }
      },
    )
  }

  private fun buildPrototypeValueRow(
    title: String,
    value: String,
  ): View = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    addView(titleText(title, textSizeSp = 15f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    addView(statusBadgeText(value, OpenCrayUiTokens.textPrimary, "#F7F7FA").apply {
      setTextColor(OpenCrayUiTokens.textPrimary)
    })
  }

  private fun <T> buildSegmentedValueRow(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelected: (T) -> Unit,
  ): View = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    addView(titleText(title, textSizeSp = 15f))
    addView(
      buildSegmentedControl(options, selected, onSelected),
      textParams(topDp = 10),
    )
  }

  private fun navigationBarBackground(): GradientDrawable = GradientDrawable().apply {
    val drawable = ocSurfaceBackground(
      fillColor = OpenCrayUiTokens.surface,
      radiusDp = 0,
      strokeColor = Color.TRANSPARENT,
      strokeWidthDp = 0,
    )
    shape = drawable.shape
    cornerRadius = drawable.cornerRadius
    color = drawable.color
  }

  private fun navigationTabBackground(
    fillColor: Int,
    strokeColor: Int,
  ): GradientDrawable = GradientDrawable().apply {
    val drawable = ocSurfaceBackground(
      fillColor = fillColor,
      radiusDp = OpenCrayUiTokens.radiusButton,
      strokeColor = strokeColor,
      strokeWidthDp = if (strokeColor == Color.TRANSPARENT) 0 else 1,
    )
    shape = drawable.shape
    cornerRadius = drawable.cornerRadius
    color = drawable.color
    setStroke(dp(1), strokeColor)
  }

  private fun applyNavigationButtonStyle(
    button: Button,
    tab: AppShellTab,
    isSelected: Boolean,
  ) {
    val selectedFillColor = Color.TRANSPARENT
    val selectedStrokeColor = Color.TRANSPARENT
    val selectedContentColor = OpenCrayUiTokens.primary
    val unselectedContentColor = Color.parseColor("#8E8E93")
    val contentColor = if (isSelected) selectedContentColor else unselectedContentColor
    button.background = navigationTabBackground(
      fillColor = selectedFillColor,
      strokeColor = selectedStrokeColor,
    )
    button.setTextColor(contentColor)
    button.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    button.alpha = 1f
    button.setCompoundDrawablesRelativeWithIntrinsicBounds(
      null,
      navigationTabIcon(navigationIconResId(tab), contentColor),
      null,
      null,
    )
  }

  private fun navigationTabIcon(iconResId: Int, tintColor: Int): Drawable? = ContextCompat
    .getDrawable(this, iconResId)
    ?.mutate()
    ?.apply {
      setTint(tintColor)
      setBounds(0, 0, dp(18), dp(18))
    }

  private fun navigationIconResId(tab: AppShellTab): Int = when (tab) {
    AppShellTab.CHAT -> R.drawable.ic_nav_chat
    AppShellTab.SKILLS -> R.drawable.ic_nav_skills
    AppShellTab.FILES -> R.drawable.ic_nav_files
    AppShellTab.SETTINGS -> R.drawable.ic_nav_settings
  }

  private fun filledSurfaceBackground(
    fillColor: Int,
    strokeColor: Int,
    strokeWidthDp: Int,
  ): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    cornerRadius = dp(16).toFloat()
    setColor(fillColor)
    setStroke(dp(strokeWidthDp), strokeColor)
  }

  private fun buildTextInput(
    hint: String,
    singleLine: Boolean,
    strokeColor: Int,
  ): EditText = EditText(this).apply {
    this.hint = hint
    setTextColor(OpenCrayUiTokens.textPrimary)
    setHintTextColor(OpenCrayUiTokens.textTertiary)
    background = ocInputBackground(fillColor = OpenCrayUiTokens.surface, strokeColor = strokeColor)
    setPadding(dp(14), dp(12), dp(14), dp(12))
    minimumHeight = dp(OpenCrayUiTokens.inputHeight)
    isSingleLine = singleLine
    inputType = if (singleLine) {
      InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    } else {
      minLines = 4
      gravity = Gravity.TOP or Gravity.START
      InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    }
  }

  private fun actionButton(
    label: String,
    fillColor: Int,
  ): Button = Button(this).apply {
    text = label
    isAllCaps = false
    minHeight = dp(OpenCrayUiTokens.buttonHeight)
    setTextColor(Color.WHITE)
    background = ocSurfaceBackground(
      fillColor = fillColor,
      radiusDp = OpenCrayUiTokens.radiusButton,
      strokeColor = fillColor,
      strokeWidthDp = 1,
    )
    setPadding(dp(20), dp(12), dp(20), dp(12))
  }

  private fun simpleTextWatcher(onAfterTextChanged: (String) -> Unit): TextWatcher = object : TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

    override fun afterTextChanged(s: Editable?) {
      onAfterTextChanged(s?.toString().orEmpty())
    }
  }

  private fun arePersonalizationResetsIdle(): Boolean = queuedActionCount() == 0

  private fun currentPersonalizationProfileTitle(): String = personalizationCustomLabel.trim()
    .ifBlank { personalizationPresetTitle(personalizationPreset) }

  private fun currentPersonalizationProfileSummary(): String {
    val baseVoice = personalizationPresetVoice(personalizationPreset)
    val customGuidance = personalizationCustomGuidance.trim()
    return if (customGuidance.isBlank()) {
      getString(R.string.personalization_profile_summary_default, baseVoice)
    } else {
      getString(R.string.personalization_profile_summary_custom, baseVoice, customGuidance)
    }
  }

  private fun persistPersonalizationSoulProfile() {
    if (suppressPersonalizationPersistence) {
      return
    }

    val normalizedLabel = personalizationCustomLabel.trim()
    val normalizedGuidance = personalizationCustomGuidance.trim()
    if (
      personalizationPreset == PersonalizationPreset.STEADY &&
        normalizedLabel.isEmpty() &&
        normalizedGuidance.isEmpty()
    ) {
      personalizationStore.clearSoulProfile()
      return
    }

    personalizationStore.saveSoulProfile(
      PersonalizationLocalStore.SoulProfile(
        presetName = personalizationPreset.name,
        customLabel = normalizedLabel,
        customGuidance = normalizedGuidance,
      ),
    )
  }

  private inline fun applyPersonalizationStateWithoutPersistence(block: () -> Unit) {
    val previousValue = suppressPersonalizationPersistence
    suppressPersonalizationPersistence = true
    try {
      block()
    } finally {
      suppressPersonalizationPersistence = previousValue
    }
  }

  private fun personalizationResetPreviewMessage(preview: PersonalizationResetPreview): String? = when (preview) {
    PersonalizationResetPreview.NONE -> null
    PersonalizationResetPreview.MEMORY -> getString(R.string.personalization_reset_preview_memory)

    PersonalizationResetPreview.SOUL -> getString(R.string.personalization_reset_preview_soul)
  }

  private fun personalizationPresetTitle(preset: PersonalizationPreset): String = when (preset) {
    PersonalizationPreset.STEADY -> getString(R.string.personalization_preset_steady_title)
    PersonalizationPreset.BUILDER -> getString(R.string.personalization_preset_builder_title)
    PersonalizationPreset.WARM -> getString(R.string.personalization_preset_warm_title)
  }

  private fun personalizationPresetSummary(preset: PersonalizationPreset): String = when (preset) {
    PersonalizationPreset.STEADY -> getString(R.string.personalization_preset_steady_summary)
    PersonalizationPreset.BUILDER -> getString(R.string.personalization_preset_builder_summary)
    PersonalizationPreset.WARM -> getString(R.string.personalization_preset_warm_summary)
  }

  private fun personalizationPresetVoice(preset: PersonalizationPreset): String = when (preset) {
    PersonalizationPreset.STEADY -> getString(R.string.personalization_preset_steady_voice)
    PersonalizationPreset.BUILDER -> getString(R.string.personalization_preset_builder_voice)
    PersonalizationPreset.WARM -> getString(R.string.personalization_preset_warm_voice)
  }

  private fun contentHostParams(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
    ViewGroup.LayoutParams.MATCH_PARENT,
    0,
  ).apply {
    weight = 1f
  }

  private fun bottomNavigationParams(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
    ViewGroup.LayoutParams.MATCH_PARENT,
    ViewGroup.LayoutParams.WRAP_CONTENT,
  )

  private fun navigationButtonParams(startDp: Int = 0): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
    0,
    ViewGroup.LayoutParams.WRAP_CONTENT,
    1f,
  ).apply {
    marginStart = dp(startDp)
  }

  private fun textParams(topDp: Int = 0): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
    ViewGroup.LayoutParams.MATCH_PARENT,
    ViewGroup.LayoutParams.WRAP_CONTENT,
  ).apply {
    topMargin = dp(topDp)
  }

  private fun dp(value: Int): Int = ocDp(value)

  private data class McpServerPresentation(
    val id: String,
    val displayName: String,
    val statusLabel: String,
    val transportLabel: String,
    val trustLabel: String,
    val exposureLabel: String,
    val authLabel: String,
    val readinessLabel: String,
    val guidanceLabel: String,
    val accentColorHex: String,
    val actionLabel: String? = null,
    val actionKind: McpServerActionKind? = null,
  )

  private enum class McpServerActionKind {
    ENABLE,
    DISABLE,
    MANUAL_ENABLE,
  }

  private data class InstalledBuildInfo(
    val versionName: String,
    val versionCode: Long,
    val minSdk: Int,
  )

  private enum class SeedScenario(
    val rawValue: String,
  ) {
    DEFAULT_APPROVAL(MainInteractionActivity.SCENARIO_DEFAULT_APPROVAL),
    DENIED_POLICY(MainInteractionActivity.SCENARIO_DENIED_POLICY),
    ;

    companion object {
      fun fromRaw(rawValue: String?): SeedScenario = entries.firstOrNull { scenario ->
        scenario.rawValue.equals(rawValue?.trim(), ignoreCase = true)
      } ?: DEFAULT_APPROVAL
    }
  }

  private enum class ApprovalOutcome {
    PENDING,
    APPROVED,
    DENIED,
    POLICY_DENIED,
    ;

    companion object {
      fun fromRaw(rawValue: String?): ApprovalOutcome? = entries.firstOrNull { outcome ->
        outcome.name.equals(rawValue, ignoreCase = true)
      }
    }
  }

  private enum class PersonalizationPreset {
    STEADY,
    BUILDER,
    WARM,
    ;

    companion object {
      fun fromRaw(rawValue: String?): PersonalizationPreset? = entries.firstOrNull { preset ->
        preset.name.equals(rawValue, ignoreCase = true)
      }
    }
  }

  private enum class PersonalizationResetPreview {
    NONE,
    MEMORY,
    SOUL,
    ;

    companion object {
      fun fromRaw(rawValue: String?): PersonalizationResetPreview? = entries.firstOrNull { preview ->
        preview.name.equals(rawValue, ignoreCase = true)
      }
    }
  }
}

internal object AppShellLaunchStateResolver {
  fun resolve(
    restoredTabRaw: String?,
    restoredSettingsSubpageRaw: String?,
    hasRestoredState: Boolean,
    startTabRaw: String?,
    startSettingsSubpageRaw: String?,
    hasStartExtras: Boolean,
    persistedDestination: AppShellDestination,
  ): AppShellDestination = when {
    hasRestoredState -> AppShellDestination.fromRaw(restoredTabRaw, restoredSettingsSubpageRaw)
    hasStartExtras -> AppShellDestination.fromRaw(startTabRaw, startSettingsSubpageRaw)
    else -> persistedDestination
  }
}
