package com.opencray.app

import com.opencray.app.facade.llm.LlmConfigFacade
import com.opencray.app.facade.llm.LlmConfigSnapshot
import com.opencray.app.facade.llm.LlmValidationResult
import com.opencray.app.facade.llm.EmptyLlmConfigFacade
import com.opencray.app.facade.llm.SaveLlmConfigRequest
import com.opencray.app.facade.llm.ValidateLlmConfigRequest
import com.opencray.app.facade.mcp.McpServerSettingsSnapshot
import com.opencray.app.facade.mcp.McpSettingsFacade
import com.opencray.app.facade.mcp.McpSettingsSnapshot
import com.opencray.app.facade.personalization.PersonalizationConfigSnapshot
import com.opencray.app.facade.personalization.PersonalizationFacade
import com.opencray.app.facade.personalization.PersonalizationLanguageOptionSnapshot
import com.opencray.app.facade.personalization.PersonalizationPresetSnapshot
import com.opencray.app.facade.personalization.PersonalizationResetActionSnapshot
import com.opencray.app.facade.personalization.PersonalizationResetScope
import com.opencray.app.facade.personalization.SavePersonalizationConfigRequest
import com.opencray.app.facade.settings.SettingsDetailSnapshot
import com.opencray.app.facade.settings.SettingsFacade
import com.opencray.app.facade.settings.SettingsOverviewSnapshot
import com.opencray.app.facade.settings.SettingsRouteId
import com.opencray.app.shell.AppShellStateStore
import com.opencray.app.shell.InMemoryAppShellKeyValueStore
import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.SessionLifecycleState
import com.opencray.core.orchestrator.SessionQueueSnapshot
import com.opencray.core.orchestrator.SessionQueueTaskSnapshot
import com.opencray.persistence.model.ChatTranscriptRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OpenCrayHostRuntimeTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun submitChatMessageLeavesTranscriptUntouchedWhenQueueSubmitFails() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-submit-fail"))
    val initialSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager().apply {
      putHandle(
        RecordingSessionHandle(
          sessionId = initialSessionId,
          onResume = resumedSessionIds::add,
          submitFailure = IllegalStateException("queue persistence failed"),
        ),
      )
    }
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    runCatching {
      hostRuntime.submitChatMessage("Ship the patch")
    }

    val messages = chatStore.loadState().activeSession.messages

    assertEquals(1, messages.size)
    assertEquals(listOf(initialSessionId), manager.resumedSessionIds)
    assertTrue(messages.none { message -> message.role == ChatTranscriptRole.USER })
    assertTrue(messages.none { message -> message.role == ChatTranscriptRole.ASSISTANT })
  }

  @Test
  fun selectChatSessionResumesResolvedActiveSessionInsteadOfInvalidInputId() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-select"))
    val createdState = chatStore.createSession()
    val expectedSessionId = createdState.activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.selectChatSession("missing-session-id")

    assertEquals(listOf(expectedSessionId, expectedSessionId), manager.resumedSessionIds)
    assertTrue("missing-session-id" !in manager.requestedSessionIds)
  }

  @Test
  fun submitChatMessageCancelsQueuedTaskWhenTranscriptPersistenceFails() {
    val chatStore = FailingChatSessionLocalStore(temporaryFolder.newFolder("chat-store-persist-fail"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    runCatching {
      hostRuntime.submitChatMessage("This write will fail")
    }

    val messages = chatStore.loadState().activeSession.messages

    assertEquals(1, messages.size)
    assertEquals(listOf(handle.submittedTaskIds.single()), handle.cancelledTaskIds)
    assertTrue(handle.ensureProcessingTaskIds.isEmpty())
  }

  @Test
  fun submitChatMessageQueuesBeforePersistingTranscript() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-submit-order"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Need a durable owner path")

    val messages = chatStore.loadState().activeSession.messages
      .filter { message -> message.role != ChatTranscriptRole.SYSTEM }

    assertEquals(listOf("Need a durable owner path", "Thinking"), messages.map { it.text })
    assertEquals(listOf("Need a durable owner path"), handle.submittedInputs)
    assertEquals(listOf(handle.submittedTaskIds.single()), handle.ensureProcessingTaskIds)
  }

  @Test
  fun taskFailureUsesSetupHintWhenLlmConfigIsMissing() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-missing-llm"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Need live output")
    val task = handle.submittedTasks.single()
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.FAILED,
        errorCode = AppAgentSessionTaskRuntimeFactory.ERROR_CODE_MISSING_LLM_CONFIG,
        errorMessage = "LLM configuration is incomplete.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata,
      ),
    )

    val messages = chatStore.loadState().activeSession.messages
      .filter { message -> message.role != ChatTranscriptRole.SYSTEM }

    assertEquals("Missing LLM", messages.last().text)
  }

  @Test
  fun taskFailureUsesProviderErrorWhenLlmConfigExists() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-provider-failure"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Need live output")
    val task = handle.submittedTasks.single()
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.FAILED,
        errorCode = "HTTP_401",
        errorMessage = "Invalid API key.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata,
      ),
    )

    val messages = chatStore.loadState().activeSession.messages
      .filter { message -> message.role != ChatTranscriptRole.SYSTEM }

    assertEquals("Failed: Invalid API key.", messages.last().text)
  }

  @Test
  fun validateLlmConfigReturnsFacadePayloadForFlutterBridge() {
    val hostRuntime = hostRuntime(
      chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-validation")),
      runtimeManager = RecordingRuntimeManager(),
      llmConfigFacade = RecordingLlmConfigFacade(
        validationResult = LlmValidationResult(
          isSuccess = true,
          message = "Connection verified for gpt-4o-mini.",
        ),
      ),
    )

    val payload = hostRuntime.validateLlmConfig(
      providerId = "openai",
      protocol = LlmProviderProtocols.OPENAI,
      baseUrl = "https://api.openai.com/v1",
      apiKey = "secret",
      model = "gpt-4o-mini",
      reasoningEffort = "medium",
    )

    assertEquals(true, payload["isSuccess"])
    assertEquals("Connection verified for gpt-4o-mini.", payload["message"])
  }

  @Test
  fun savePersonalizationConfigReturnsFacadePayloadForFlutterBridge() {
    val personalizationFacade = RecordingPersonalizationFacade()
    val hostRuntime = hostRuntime(
      chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-personalization")),
      runtimeManager = RecordingRuntimeManager(),
      personalizationFacade = personalizationFacade,
    )

    val payload = hostRuntime.savePersonalizationConfig(
      presetId = "warm",
      customLabel = "Night Shift",
      customGuidance = "Stay calm.",
    )

    assertEquals("Night Shift", payload["livePreviewName"])
    assertEquals("warm", payload["selectedPresetId"])
    assertEquals("Stay calm.", payload["customGuidance"])
    assertEquals(
      SavePersonalizationConfigRequest(
        presetId = "warm",
        customLabel = "Night Shift",
        customGuidance = "Stay calm.",
      ),
      personalizationFacade.lastSaveRequest,
    )
  }

  @Test
  fun setAppLanguageReturnsUpdatedPersonalizationPayload() {
    val hostRuntime = hostRuntime(
      chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-language")),
      runtimeManager = RecordingRuntimeManager(),
      personalizationFacade = RecordingPersonalizationFacade(),
    )

    val payload = hostRuntime.setAppLanguage("zh-CN")

    assertEquals("zh-CN", payload["selectedAppLanguageId"])
    val options = payload["appLanguageOptions"] as List<*>
    val selectedOption = options.map { it as Map<*, *> }
      .first { option -> option["isSelected"] == true }
    assertEquals("zh-CN", selectedOption["id"])
  }

  @Test
  fun setMcpServerEnabledReturnsFacadePayloadForFlutterBridge() {
    val mcpSettingsFacade = RecordingMcpSettingsFacade()
    val hostRuntime = hostRuntime(
      chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-mcp")),
      runtimeManager = RecordingRuntimeManager(),
      mcpSettingsFacade = mcpSettingsFacade,
    )

    val payload = hostRuntime.setMcpServerEnabled(
      serverId = "community-bridge",
      enabled = true,
    )

    assertEquals("Enabled 3 • Blocked 0 • Attention 1", payload["summaryLine"])
    assertEquals(
      "This page lists per-server status and actions. Today the runtime only exposes server visibility through mcp_list_servers; remote MCP tools are not proxied into the agent yet.",
      payload["serversHelper"],
    )
    val servers = payload["servers"] as List<*>
    val firstServer = servers.first() as Map<*, *>
    assertEquals("community-bridge", firstServer["id"])
    assertEquals("Exposure: Blocked", firstServer["exposureLine"])
    assertEquals(
      "Blocked until you enable this server manually. Exposure stays hidden until you consent here, and remote MCP tools are not proxied yet.",
      firstServer["guidance"],
    )
    assertEquals("Enable server", firstServer["actionLabel"])
    assertEquals("community-bridge" to true, mcpSettingsFacade.lastServerToggle)
  }

  private fun hostRuntime(
    chatStore: ChatSessionLocalStore,
    runtimeManager: RecordingRuntimeManager,
    llmConfigFacade: LlmConfigFacade = RecordingLlmConfigFacade(),
    personalizationFacade: PersonalizationFacade = RecordingPersonalizationFacade(),
    mcpSettingsFacade: McpSettingsFacade = RecordingMcpSettingsFacade(),
  ): OpenCrayHostRuntime = OpenCrayHostRuntime.createForTest(
    stateStore = AppShellStateStore(InMemoryAppShellKeyValueStore()),
    chatSessionStore = chatStore,
    settingsFacade = NoOpSettingsFacade,
    llmConfigFacade = llmConfigFacade,
    personalizationFacade = personalizationFacade,
    mcpSettingsFacade = mcpSettingsFacade,
    sessionRuntimeManager = runtimeManager,
    strings = HostRuntimeStrings(
      localeTag = "en",
      shellHostLabel = "HOST CONNECTED",
      shellHostSummary = "Android host bridge is attached to the live app runtime.",
      chatScreenTitle = "Chat",
      chatModeLabel = "AUTO",
      chatSessionButtonLabel = "Sessions",
      chatRecentSessionsEyebrow = "Recent sessions",
      chatRecentSessionsTitle = "Recent sessions",
      chatNewSessionLabel = "New session",
      chatDefaultSessionTitle = "New chat",
      chatMessagesBadge = { count -> "$count messages" },
      chatSummaryReplyInProgress = "Reply in progress",
      chatSummaryStartNewSession = "Start a new session",
      chatSummaryRestored = "Local transcript is restored into the runtime window for each task.",
      skillInstalled = { skillId -> "Installed $skillId." },
      skillRemoved = { skillId -> "Removed $skillId." },
      skillsReloaded = "Reloaded skills from local storage.",
      composerPlaceholder = "Message OpenCray",
      agentThinking = "Thinking",
      agentCancelled = "Cancelled",
      agentMissingLlm = "Missing LLM",
      agentEmptyAnswer = "The model returned an empty answer.",
      agentFailed = { detail -> "Failed: $detail" },
    ),
  )

  private object NoOpSettingsFacade : SettingsFacade {
    override fun loadOverview(): SettingsOverviewSnapshot = SettingsOverviewSnapshot(
      eyebrow = "",
      title = "",
      subtitle = "",
      deviceTitle = "",
      deviceSummary = "",
      entries = emptyList(),
    )

    override fun loadDetail(routeId: SettingsRouteId): SettingsDetailSnapshot = SettingsDetailSnapshot(
      routeId = routeId,
      title = "",
      subtitle = "",
      sections = emptyList(),
    )
  }

  private class RecordingLlmConfigFacade(
    private val validationResult: LlmValidationResult = LlmValidationResult(
      isSuccess = false,
      message = "Not configured.",
    ),
  ) : LlmConfigFacade {
    override fun load(): LlmConfigSnapshot = EmptyLlmConfigFacade.load()

    override fun save(request: SaveLlmConfigRequest): LlmConfigSnapshot =
      throw UnsupportedOperationException("save is not used in this test")

    override fun validate(request: ValidateLlmConfigRequest): LlmValidationResult =
      validationResult
  }

  private class RecordingPersonalizationFacade : PersonalizationFacade {
    var lastSaveRequest: SavePersonalizationConfigRequest? = null

    override fun load(): PersonalizationConfigSnapshot = snapshot()

    override fun save(request: SavePersonalizationConfigRequest): PersonalizationConfigSnapshot {
      lastSaveRequest = request
      return snapshot(
        selectedPresetId = request.presetId,
        customLabel = request.customLabel,
        customGuidance = request.customGuidance,
      )
    }

    override fun setAppLanguage(languageId: String): PersonalizationConfigSnapshot =
      snapshot(selectedAppLanguageId = languageId)

    override fun reset(scope: PersonalizationResetScope): PersonalizationConfigSnapshot =
      snapshot(lastResetMessage = scope.wireValue)

    private fun snapshot(
      selectedPresetId: String = "steady",
      customLabel: String = "",
      customGuidance: String = "",
      selectedAppLanguageId: String = "en",
      lastResetMessage: String? = null,
    ): PersonalizationConfigSnapshot = PersonalizationConfigSnapshot(
      title = "Personalization",
      subtitle = "Tune voice",
      introTitle = "Shape how OpenCray sounds",
      introBody = "Body",
      introHelper = "Helper",
      presetsTitle = "Presets",
      presetsHelper = "Presets helper",
      presets = listOf(
        PersonalizationPresetSnapshot(
          id = selectedPresetId,
          title = "Preset",
          summary = "Summary",
          voice = "Voice",
          status = "Selected",
          isSelected = true,
        ),
      ),
      selectedPresetId = selectedPresetId,
      customOverlayTitle = "Overlay",
      customOverlayHelper = "Overlay helper",
      customLabelHint = "Label hint",
      customLabelHelper = "Label helper",
      customGuidanceHint = "Guidance hint",
      customGuidanceHelper = "Guidance helper",
      customLabel = customLabel,
      customGuidance = customGuidance,
      behaviorDefaultsTitle = "Behavior defaults",
      appLanguageTitle = "App language",
      appLanguageOptions = listOf(
        PersonalizationLanguageOptionSnapshot(
          id = "en",
          title = "English",
          isSelected = selectedAppLanguageId == "en",
        ),
        PersonalizationLanguageOptionSnapshot(
          id = "zh-CN",
          title = "中文",
          isSelected = selectedAppLanguageId == "zh-CN",
        ),
      ),
      selectedAppLanguageId = selectedAppLanguageId,
      livePreviewTitle = "Preview",
      livePreviewName = customLabel.ifBlank { "Preset" },
      livePreviewSummary = customGuidance.ifBlank { "Preview summary" },
      queueTitle = "Idle",
      queueBody = "Queue body",
      queueIsIdle = true,
      lastResetTitle = "Latest reset result",
      lastResetMessage = lastResetMessage,
      resetActions = listOf(
        PersonalizationResetActionSnapshot(
          scope = PersonalizationResetScope.MEMORY,
          title = "Reset memory",
          scopeBody = "Memory scope",
          retainBody = "Retain",
          confirmationToken = "RESET MEMORY",
          inputHint = "Type RESET MEMORY",
          disabledGuidance = "Disabled",
          typeExactGuidance = "Type exact",
          armedGuidance = "Armed",
          isInputEnabled = true,
        ),
      ),
    )
  }

  private class RecordingMcpSettingsFacade : McpSettingsFacade {
    var lastServerToggle: Pair<String, Boolean>? = null

    override fun load(): McpSettingsSnapshot = snapshot()

    override fun setMasterEnabled(enabled: Boolean): McpSettingsSnapshot = snapshot(
      masterEnabled = enabled,
    )

    override fun setServerEnabled(serverId: String, enabled: Boolean): McpSettingsSnapshot {
      lastServerToggle = serverId to enabled
      return snapshot(
        summaryLine = "Enabled 3 • Blocked 0 • Attention 1",
      )
    }

    override fun currentExposureReport() =
      com.opencray.mcp.McpClientExposureReport(
        activeClients = emptyList(),
        blockedClients = emptyList(),
      )

    private fun snapshot(
      masterEnabled: Boolean = true,
      summaryLine: String = "Enabled 2 • Blocked 1 • Attention 2",
    ): McpSettingsSnapshot = McpSettingsSnapshot(
      title = "MCP",
      subtitle = "Control server discovery",
      masterTitle = "Enable MCP integrations",
      masterSummary = "Trusted servers follow this master switch.",
      masterEnabled = masterEnabled,
      summaryLine = summaryLine,
      serversTitle = "Per-server controls live here",
      serversHelper = "This page lists per-server status and actions. Today the runtime only exposes server visibility through mcp_list_servers; remote MCP tools are not proxied into the agent yet.",
      masterDisabledTitle = null,
      masterDisabledBody = null,
      servers = listOf(
        McpServerSettingsSnapshot(
          id = "community-bridge",
          title = "Community Bridge",
          statusLabel = "Blocked",
          statusTone = "blocked",
          trustLine = "Trust: Requires manual enable",
          authLine = "Auth: Credential configured",
          readinessLine = "Readiness: Ready",
          transportLine = "Transport: Remote SSE",
          exposureLine = "Exposure: Blocked",
          guidance = "Blocked until you enable this server manually. Exposure stays hidden until you consent here, and remote MCP tools are not proxied yet.",
          actionLabel = "Enable server",
          actionTurnsOn = true,
          isActionEnabled = true,
        ),
      ),
    )
  }

  private class RecordingRuntimeManager : AgentSessionRuntimeManager {
    private val handlesBySession = linkedMapOf<String, RecordingSessionHandle>()
    private val listeners = mutableListOf<AgentSessionRuntimeListener>()
    val resumedSessionIds = mutableListOf<String>()
    val requestedSessionIds = mutableListOf<String>()

    fun putHandle(handle: RecordingSessionHandle) {
      handlesBySession[handle.sessionId] = handle
    }

    override fun forSession(sessionId: String): AgentSessionHandle {
      requestedSessionIds += sessionId
      return handlesBySession.getOrPut(sessionId) {
        RecordingSessionHandle(
          sessionId = sessionId,
          onResume = resumedSessionIds::add,
        )
      }
    }

    fun emitTaskFinished(
      sessionId: String,
      task: AgentTask,
      result: ExecutionResult,
    ) {
      listeners.forEach { listener ->
        listener.onTaskFinished(sessionId, task, result)
      }
    }

    override fun observe(listener: AgentSessionRuntimeListener): () -> Unit {
      listeners += listener
      return {
        listeners -= listener
      }
    }

    override fun release(sessionId: String) = Unit

    override fun releaseIdleSessions() = Unit
  }

  private class RecordingSessionHandle(
    override val sessionId: String,
    private val onResume: ((String) -> Unit)? = null,
    private val submitFailure: Throwable? = null,
  ) : AgentSessionHandle {
    val submittedInputs = mutableListOf<String>()
    val submittedTaskIds = mutableListOf<String>()
    val submittedTasks = mutableListOf<AgentTask>()
    val ensureProcessingTaskIds = mutableListOf<String>()
    val cancelledTaskIds = mutableListOf<String>()
    private var lastSubmittedTaskId: String? = null

    override fun submitPrompt(
      userText: String,
      pendingMessageId: String,
      visibleThroughMessageId: String,
      policyDecision: PolicyDecision,
      metadata: Map<String, String>,
    ): AgentTask {
      submitFailure?.let { throw it }
      submittedInputs += userText
      val taskId = "task-${submittedTaskIds.size + 1}"
      submittedTaskIds += taskId
      lastSubmittedTaskId = taskId
      return AgentTask(
        id = taskId,
        type = AgentTaskType.PROMPT,
        input = userText,
        policyDecision = PolicyDecision(
          outcome = PolicyDecisionOutcome.ALLOW,
          reasonCode = "TEST_ALLOW",
        ),
        metadata = metadata + mapOf(
          AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID to pendingMessageId,
          AppAgentSessionTaskRuntimeFactory.METADATA_VISIBLE_THROUGH_MESSAGE_ID to visibleThroughMessageId,
        ),
        createdAtEpochMs = 1_000L,
      ).also(submittedTasks::add)
    }

    override fun ensureProcessing() {
      lastSubmittedTaskId?.let(ensureProcessingTaskIds::add)
    }

    override fun requestCancel(taskId: String): Boolean {
      cancelledTaskIds += taskId
      return true
    }

    override fun requestRetry(taskId: String): Boolean = false

    override fun requestCancelForPendingMessageIds(pendingMessageIds: Set<String>): Int = 0

    override fun resume(): SessionLifecycleState {
      onResume?.invoke(sessionId)
      return SessionLifecycleState.IDLE
    }

    override fun snapshot(): SessionQueueSnapshot = SessionQueueSnapshot(
      sessionId = sessionId,
      agentId = "test-agent",
      updatedAtEpochMs = 1_000L,
      tasks = emptyList<SessionQueueTaskSnapshot>(),
    )

    override fun hasPendingWork(): Boolean = false
  }

  private class FailingChatSessionLocalStore(
    directory: java.io.File,
  ) : ChatSessionLocalStore(directory) {
    override fun appendSubmittedTurn(
      sessionId: String,
      userText: String,
      assistantMessageId: String,
      assistantPlaceholderText: String,
    ): ChatSessionsState = error("transcript persistence failed")
  }
}
