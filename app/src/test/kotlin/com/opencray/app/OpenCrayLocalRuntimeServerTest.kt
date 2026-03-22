package com.opencray.app

import com.opencray.app.facade.llm.EmptyLlmConfigFacade
import com.opencray.app.facade.llm.LlmConfigFacade
import com.opencray.app.facade.llm.LlmConfigSnapshot
import com.opencray.app.facade.llm.LlmValidationResult
import com.opencray.app.facade.llm.SaveCustomLlmProviderRequest
import com.opencray.app.facade.llm.SaveLlmConfigRequest
import com.opencray.app.facade.llm.ValidateLlmConfigRequest
import com.opencray.app.facade.search.EmptyNetworkSearchConfigFacade
import com.opencray.app.facade.search.LocalNetworkSearchConfigFacade
import com.opencray.app.facade.search.NetworkSearchConfigFacade
import com.opencray.app.facade.safety.LocalSafetySettingsFacade
import com.opencray.app.facade.skills.InstallSourceSnapshot
import com.opencray.app.facade.skills.InstalledSkillSnapshot
import com.opencray.app.facade.skills.SkillInstallRequestResult
import com.opencray.app.facade.skills.SkillInstructionsSnapshot
import com.opencray.app.facade.skills.SkillsFacade
import com.opencray.app.facade.skills.SkillsSnapshot
import com.opencray.app.facade.skills.SuggestedSkillSnapshot
import com.opencray.app.facade.settings.SettingsDetailSnapshot
import com.opencray.app.facade.settings.SettingsFacade
import com.opencray.app.facade.settings.SettingsOverviewSnapshot
import com.opencray.app.facade.settings.SettingsRouteId
import com.opencray.app.shell.AppShellStateStore
import com.opencray.app.shell.InMemoryAppShellKeyValueStore
import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.SessionTaskRuntime
import com.opencray.core.orchestrator.SessionLifecycleState
import com.opencray.core.orchestrator.SessionQueueSnapshot
import com.opencray.persistence.model.ChatTranscriptRole
import com.opencray.persistence.model.MemoryRecord
import com.opencray.runtime.AgentToolCall
import com.opencray.runtime.AgentToolResult
import com.opencray.runtime.AgentToolResultStatus
import com.opencray.runtime.OpenCrayLifecycleEvent
import com.opencray.runtime.OpenCrayMemoryRetrievalEvent
import com.opencray.runtime.OpenCrayMemoryWriteEvent
import com.opencray.runtime.OpenCrayRunLifecyclePhase
import com.opencray.runtime.OpenCrayToolResultEvent
import com.opencray.runtime.memory.MemoryPreferenceKeys
import com.opencray.runtime.memory.MemoryRecordExtensionKeys
import com.opencray.runtime.memory.MemorySoulExtensionKeys
import com.opencray.runtime.soul.InteractionPreferenceState
import com.opencray.runtime.soul.PreferenceAxisState
import com.opencray.runtime.soul.PreferredAddressState
import com.opencray.runtime.soul.PreferredAddressStyle
import com.opencray.runtime.soul.RelationshipState
import com.opencray.runtime.soul.SoulMemoryExtensionKeys
import com.opencray.runtime.soul.SoulMemoryObjectTypes
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OpenCrayLocalRuntimeServerTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun exposesShellSnapshotOverLoopbackHttp() {
    val server = localRuntimeServer()
    server.ensureStarted()

    try {
      val response = request(server, "GET", "/v1/shell_snapshot")
      val payload = JSONObject(response.body)

      assertEquals(200, response.statusCode)
      assertEquals("HOST CONNECTED", payload.getString("hostLabel"))
      assertTrue(payload.getBoolean("isHostConnected"))
    } finally {
      server.close()
    }
  }

  @Test
  fun exposesFilesSnapshotOverLoopbackHttp() {
    val server = localRuntimeServer(
      workspaceSnapshotProvider = {
        WorkspaceTreeSnapshot(
          rootName = "agent-workspace",
          rootPath = "/tmp/agent-workspace",
          availableBytes = 2_048L,
          directoryCount = 1,
          fileCount = 1,
          entryCount = 2,
          isTruncated = false,
          children = listOf(
            WorkspaceTreeNodeSnapshot(
              name = "docs",
              relativePath = "docs",
              isDirectory = true,
              childCount = 1,
              sizeBytes = null,
              isTruncated = false,
              children = listOf(
                WorkspaceTreeNodeSnapshot(
                  name = "report.md",
                  relativePath = "docs/report.md",
                  isDirectory = false,
                  childCount = 0,
                  sizeBytes = 512L,
                  isTruncated = false,
                  children = emptyList(),
                ),
              ),
            ),
          ),
        ).toMap()
      },
    )
    server.ensureStarted()

    try {
      val response = request(server, "GET", "/v1/files_snapshot")
      val payload = JSONObject(response.body)
      val children = payload.getJSONArray("children")
      val firstChild = children.getJSONObject(0)

      assertEquals(200, response.statusCode)
      assertEquals("agent-workspace", payload.getString("rootName"))
      assertEquals("/tmp/agent-workspace", payload.getString("rootPath"))
      assertEquals("docs", firstChild.getString("name"))
      assertTrue(firstChild.getBoolean("isDirectory"))
    } finally {
      server.close()
    }
  }

  @Test
  fun forwardsValidationRequestsToHostRuntime() {
    val llmConfigFacade = RecordingLlmConfigFacade()
    val server = localRuntimeServer(llmConfigFacade = llmConfigFacade)
    server.ensureStarted()

    try {
      val response = request(
        server,
        "POST",
        "/v1/validate_llm_config",
        body = JSONObject().apply {
          put("providerId", "openai")
          put("protocol", "openai")
          put("baseUrl", "https://api.openai.com/v1")
          put("apiKey", "secret")
          put("model", "gpt-4o-mini")
          put("reasoningEffort", "medium")
        }.toString(),
      )
      val payload = JSONObject(response.body)

      assertEquals(200, response.statusCode)
      assertTrue(payload.getBoolean("isSuccess"))
      assertEquals("Validation succeeded.", payload.getString("message"))
      assertEquals(
        ValidateLlmConfigRequest(
          providerId = "openai",
          protocol = LlmProviderProtocols.OPENAI,
          baseUrl = "https://api.openai.com/v1",
          apiKey = "secret",
          model = "gpt-4o-mini",
          reasoningEffort = "medium",
        ),
        llmConfigFacade.lastValidationRequest,
      )
    } finally {
      server.close()
    }
  }

  @Test
  fun forwardsSaveCustomProviderRequestsToHostRuntime() {
    val llmConfigFacade = RecordingLlmConfigFacade()
    val server = localRuntimeServer(llmConfigFacade = llmConfigFacade)
    server.ensureStarted()

    try {
      val response = request(
        server,
        "POST",
        "/v1/save_custom_llm_provider",
        body = JSONObject().apply {
          put("selectedProviderOptionId", "custom")
          put("protocol", "anthropic")
          put("providerName", "Acme")
          put("providerNotes", "Regional fallback")
          put("baseUrl", "https://api.acme.example/v1")
          put("apiKey", "secret")
          put("model", "claude-3-7-sonnet")
          put("reasoningEffort", "high")
          put("systemPrompt", "Be concise.")
        }.toString(),
      )
      val payload = JSONObject(response.body)

      assertEquals(200, response.statusCode)
      assertEquals("saved-custom", payload.getString("selectedProviderOptionId"))
      assertEquals("custom", payload.getString("providerId"))
      assertEquals("Acme", payload.getString("providerName"))
      assertEquals("Regional fallback", payload.getString("providerNotes"))
      assertEquals(
        SaveCustomLlmProviderRequest(
          selectedProviderOptionId = "custom",
          protocol = LlmProviderProtocols.ANTHROPIC,
          providerName = "Acme",
          providerNotes = "Regional fallback",
          baseUrl = "https://api.acme.example/v1",
          apiKey = "secret",
          model = "claude-3-7-sonnet",
          reasoningEffort = "high",
          systemPrompt = "Be concise.",
        ),
        llmConfigFacade.lastSavedCustomRequest,
      )
    } finally {
      server.close()
    }
  }

  @Test
  fun forwardsNetworkSearchConfigRequestsToHostRuntime() {
    val networkSearchConfigFacade = LocalNetworkSearchConfigFacade.createForTest(
      WebSearchSettingsStore(InMemoryWebSearchSettingsKeyValueStore()),
    )
    val server = localRuntimeServer(networkSearchConfigFacade = networkSearchConfigFacade)
    server.ensureStarted()

    try {
      val initialResponse = request(server, "GET", "/v1/network_search_config")
      val initialPayload = JSONObject(initialResponse.body)

      assertEquals(200, initialResponse.statusCode)
      assertEquals("Network & Search", initialPayload.getString("title"))
      assertEquals(0, initialPayload.getJSONArray("slots").length())

      val saveResponse = request(
        server,
        "POST",
        "/v1/save_network_search_config",
        body = JSONObject().apply {
          put(
            "slots",
            JSONArray().put(
              JSONObject().apply {
                put("id", "slot-primary")
                put("providerId", "exa")
                put("label", "Primary Exa")
                put("apiKey", "exa-secret")
                put("enabled", true)
              },
            ).put(
              JSONObject().apply {
                put("id", "slot-backup")
                put("providerId", "brave")
                put("label", "Backup Brave")
                put("apiKey", "brave-secret")
                put("enabled", false)
              },
            ),
          )
        }.toString(),
      )
      val savedPayload = JSONObject(saveResponse.body)
      val savedSlots = savedPayload.getJSONArray("slots")

      assertEquals(200, saveResponse.statusCode)
      assertEquals(2, savedSlots.length())
      assertEquals("exa", savedSlots.getJSONObject(0).getString("providerId"))
      assertEquals("Primary Exa", savedSlots.getJSONObject(0).getString("label"))
      assertEquals(true, savedSlots.getJSONObject(0).getBoolean("enabled"))
      assertEquals("brave", savedSlots.getJSONObject(1).getString("providerId"))
      assertEquals(false, savedSlots.getJSONObject(1).getBoolean("enabled"))
    } finally {
      server.close()
    }
  }

  @Test
  fun createWorkspaceFolderRouteMutatesWorkspace() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-route-create").toPath()
    val server = localRuntimeServer(
      workspaceRootProvider = { workspaceRoot },
      workspaceSnapshotProvider = {
        AppAgentWorkspaceSnapshotFactory.createSnapshot(workspaceRoot).toMap()
      },
    )
    server.ensureStarted()

    try {
      val response = request(
        server,
        "POST",
        "/v1/create_workspace_folder",
        body = JSONObject().apply {
          put("parentRelativePath", "")
          put("name", "drafts")
        }.toString(),
      )
      val payload = JSONObject(response.body)
      val children = payload.getJSONArray("children")

      assertEquals(200, response.statusCode)
      assertTrue(workspaceRoot.resolve("drafts").toFile().isDirectory)
      assertEquals("drafts", children.getJSONObject(0).getString("name"))
    } finally {
      server.close()
    }
  }

  @Test
  fun forwardsApprovalRequestsToHostRuntime() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-approval-route"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val runtimeManager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      resumeResult = true,
    )
    runtimeManager.handle = handle
    val hostRuntime = OpenCrayHostRuntime.createForTest(
      stateStore = AppShellStateStore(InMemoryAppShellKeyValueStore()),
      chatSessionStore = chatStore,
      settingsFacade = NoOpSettingsFacade,
      llmConfigFacade = EmptyLlmConfigFacade,
      sessionRuntimeManager = runtimeManager,
      strings = hostRuntimeStrings(),
    )
    val task = AgentTask(
      id = "task-approval",
      type = AgentTaskType.PROMPT,
      input = "Need approval",
      policyDecision = PolicyDecision(
        outcome = PolicyDecisionOutcome.ALLOW,
        reasonCode = "TEST_ALLOW",
      ),
      metadata = mapOf(
        AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID to "run-approval",
        AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID to "assistant-1",
      ),
      createdAtEpochMs = 1_000L,
    )
    runtimeManager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = ExecutionStatus.DENIED,
        errorCode = "APPROVAL_REQUIRED",
        errorMessage = "Approval is required before Write can run.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata,
      ),
    )
    val server = OpenCrayLocalRuntimeServer(
      hostRuntimeProvider = { hostRuntime },
      requestedPort = 0,
      shutdownExecutorOnClose = true,
    )
    server.ensureStarted()

    try {
      val response = request(
        server,
        "POST",
        "/v1/approve_chat_approval",
        body = JSONObject().apply {
          put("runId", "run-approval")
        }.toString(),
      )

      assertEquals(200, response.statusCode)
      assertEquals(listOf(task.id), handle.resumedTaskIds)
    } finally {
      server.close()
    }
  }

  @Test
  fun copyAndDeleteChatSessionRoutesMutateStoredSessions() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-session-routes"))
    val originalSessionId = chatStore.loadState().activeSession.sessionId
    chatStore.appendUserMessage(originalSessionId, "Copy this session")
    val hostRuntime = OpenCrayHostRuntime.createForTest(
      stateStore = AppShellStateStore(InMemoryAppShellKeyValueStore()),
      chatSessionStore = chatStore,
      settingsFacade = NoOpSettingsFacade,
      llmConfigFacade = EmptyLlmConfigFacade,
      sessionRuntimeManager = NoOpRuntimeManager(),
      strings = hostRuntimeStrings(),
    )
    val server = OpenCrayLocalRuntimeServer(
      hostRuntimeProvider = { hostRuntime },
      requestedPort = 0,
      shutdownExecutorOnClose = true,
    )
    server.ensureStarted()

    try {
      val copyResponse = request(
        server,
        "POST",
        "/v1/copy_chat_session",
        body = JSONObject().apply {
          put("sessionId", originalSessionId)
        }.toString(),
      )
      val copiedSessionId = chatStore.loadState().activeSession.sessionId
      val deleteResponse = request(
        server,
        "POST",
        "/v1/delete_chat_session",
        body = JSONObject().apply {
          put("sessionId", originalSessionId)
        }.toString(),
      )
      val sessions = chatStore.loadState().sessions

      assertEquals(200, copyResponse.statusCode)
      assertEquals(200, deleteResponse.statusCode)
      assertTrue(sessions.none { it.sessionId == originalSessionId })
      assertTrue(sessions.any { it.sessionId == copiedSessionId })
    } finally {
      server.close()
    }
  }

  @Test
  fun saveSafetySettingsRoutePersistsLiveContextMode() {
    val safetyFacade = LocalSafetySettingsFacade(
      store = SafetySettingsStore(InMemorySafetySettingsKeyValueStore()),
      liveContextModeStore = LiveContextModeStore(
        InMemoryLiveContextModeKeyValueStore(),
      ),
    )
    val hostRuntime = OpenCrayHostRuntime.createForTest(
      stateStore = AppShellStateStore(InMemoryAppShellKeyValueStore()),
      chatSessionStore = ChatSessionLocalStore(
        temporaryFolder.newFolder("chat-store-safety-route"),
      ),
      settingsFacade = NoOpSettingsFacade,
      llmConfigFacade = EmptyLlmConfigFacade,
      safetySettingsFacade = safetyFacade,
      sessionRuntimeManager = NoOpRuntimeManager(),
      strings = hostRuntimeStrings(),
    )
    val server = OpenCrayLocalRuntimeServer(
      hostRuntimeProvider = { hostRuntime },
      requestedPort = 0,
      shutdownExecutorOnClose = true,
    )
    server.ensureStarted()

    try {
      val response = request(
        server,
        "POST",
        "/v1/save_safety_settings",
        body = JSONObject().apply {
          put("automationModeId", "auto")
          put("rollbackJournalEnabled", true)
          put("maxFilesPerBatch", 20)
          put("maxAgentTurns", 0)
          put("maxToolCalls", 0)
          put("undoWindowHours", 24)
          put("fileChangesPolicyId", "inherit")
          put("fileDeletesPolicyId", "inherit")
          put("shellCommandsPolicyId", "inherit")
          put("externalAccessModeId", "select_paths")
          put("photoLibraryEnabled", true)
          put("downloadsEnabled", true)
          put("documentsEnabled", false)
          put("recordingsEnabled", false)
          put("workspaceAccessProfileId", "work")
          put("readOnlyOutsideWorkspace", true)
          put("liveContextModeId", LiveContextMode.NO_SOUL.wireValue)
          put("memoryToolsEnabled", false)
        }.toString(),
      )

      assertEquals(200, response.statusCode)
      assertEquals(
        LiveContextMode.NO_SOUL.wireValue,
        JSONObject(response.body).getString("liveContextModeId"),
      )
      assertEquals(false, JSONObject(response.body).getBoolean("memoryToolsEnabled"))
      assertEquals(LiveContextMode.NO_SOUL, safetyFacade.load().liveContextMode)
      assertEquals(false, safetyFacade.load().memoryToolsEnabled)
    } finally {
      server.close()
    }
  }

  @Test
  fun deleteAndRecallChatMessageRoutesMutateStoredTranscript() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-message-routes"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    chatStore.appendUserMessage(sessionId, "Delete this bubble")
    chatStore.appendUserMessage(sessionId, "Recall this turn")
    chatStore.appendMessage(
      sessionId = sessionId,
      role = ChatTranscriptRole.ASSISTANT,
      text = "Trailing assistant reply",
    )
    val seededSession = checkNotNull(chatStore.loadSession(sessionId))
    val deleteMessageId = seededSession.messages
      .first { message -> message.role == ChatTranscriptRole.USER && message.text == "Delete this bubble" }
      .messageId
    val recallMessageId = seededSession.messages
      .first { message -> message.role == ChatTranscriptRole.USER && message.text == "Recall this turn" }
      .messageId
    val hostRuntime = OpenCrayHostRuntime.createForTest(
      stateStore = AppShellStateStore(InMemoryAppShellKeyValueStore()),
      chatSessionStore = chatStore,
      settingsFacade = NoOpSettingsFacade,
      llmConfigFacade = EmptyLlmConfigFacade,
      sessionRuntimeManager = NoOpRuntimeManager(),
      strings = hostRuntimeStrings(),
    )
    val server = OpenCrayLocalRuntimeServer(
      hostRuntimeProvider = { hostRuntime },
      requestedPort = 0,
      shutdownExecutorOnClose = true,
    )
    server.ensureStarted()

    try {
      val deleteResponse = request(
        server,
        "POST",
        "/v1/delete_chat_message",
        body = JSONObject().apply {
          put("sessionId", sessionId)
          put("messageId", deleteMessageId)
        }.toString(),
      )
      val recallResponse = request(
        server,
        "POST",
        "/v1/recall_chat_message",
        body = JSONObject().apply {
          put("sessionId", sessionId)
          put("messageId", recallMessageId)
        }.toString(),
      )
      val remainingMessages = checkNotNull(chatStore.loadSession(sessionId)).messages

      assertEquals(200, deleteResponse.statusCode)
      assertEquals(200, recallResponse.statusCode)
      assertTrue(remainingMessages.none { message -> message.messageId == deleteMessageId })
      assertTrue(remainingMessages.none { message -> message.messageId == recallMessageId })
      assertTrue(
        remainingMessages.none { message ->
          message.role == ChatTranscriptRole.ASSISTANT &&
            message.text == "Trailing assistant reply"
        },
      )
    } finally {
      server.close()
    }
  }

  @Test
  fun branchChatSessionFromMessageRouteCreatesSelectedBranchSession() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-branch-route"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    chatStore.appendUserMessage(sessionId, "Keep this turn")
    chatStore.appendMessage(
      sessionId = sessionId,
      role = ChatTranscriptRole.ASSISTANT,
      text = "Branch here",
    )
    chatStore.appendUserMessage(sessionId, "Drop this turn")
    val originalMessages = checkNotNull(chatStore.loadSession(sessionId)).messages
    val branchMessageId = originalMessages
      .first { message ->
        message.role == ChatTranscriptRole.ASSISTANT && message.text == "Branch here"
      }
      .messageId
    val hostRuntime = OpenCrayHostRuntime.createForTest(
      stateStore = AppShellStateStore(InMemoryAppShellKeyValueStore()),
      chatSessionStore = chatStore,
      settingsFacade = NoOpSettingsFacade,
      llmConfigFacade = EmptyLlmConfigFacade,
      sessionRuntimeManager = NoOpRuntimeManager(),
      strings = hostRuntimeStrings(),
    )
    val server = OpenCrayLocalRuntimeServer(
      hostRuntimeProvider = { hostRuntime },
      requestedPort = 0,
      shutdownExecutorOnClose = true,
    )
    server.ensureStarted()

    try {
      val response = request(
        server,
        "POST",
        "/v1/branch_chat_session_from_message",
        body = JSONObject().apply {
          put("sessionId", sessionId)
          put("messageId", branchMessageId)
        }.toString(),
      )
      val activeSession = chatStore.loadState().activeSession

      assertEquals(200, response.statusCode)
      assertTrue(activeSession.sessionId != sessionId)
      assertEquals("Branch here", activeSession.messages.last().text)
      assertTrue(activeSession.messages.none { message -> message.text == "Drop this turn" })
    } finally {
      server.close()
    }
  }

  @Test
  fun exposesChatRuntimeSnapshotOverLoopbackHttp() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-runtime-route"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val runtimeManager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      resumeResult = false,
    )
    runtimeManager.handle = handle
    val hostRuntime = OpenCrayHostRuntime.createForTest(
      stateStore = AppShellStateStore(InMemoryAppShellKeyValueStore()),
      chatSessionStore = chatStore,
      settingsFacade = NoOpSettingsFacade,
      llmConfigFacade = EmptyLlmConfigFacade,
      sessionRuntimeManager = runtimeManager,
      strings = hostRuntimeStrings(),
    )
    hostRuntime.submitChatMessage("Need runtime route")
    val task = handle.submittedTasks.single()
    val run = handle.submissions.single()
    runtimeManager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = OpenCrayLifecycleEvent(
        runId = run.runId,
        taskId = task.id,
        phase = OpenCrayRunLifecyclePhase.START,
        emittedAtEpochMs = 1_100L,
      ),
    )
    val server = OpenCrayLocalRuntimeServer(
      hostRuntimeProvider = { hostRuntime },
      requestedPort = 0,
      shutdownExecutorOnClose = true,
    )
    server.ensureStarted()

    try {
      val response = request(server, "GET", "/v1/chat_runtime_snapshot")
      val payload = JSONObject(response.body)
      val events = payload.getJSONArray("events")

      assertEquals(200, response.statusCode)
      assertEquals(activeSessionId, payload.getString("sessionId"))
      assertEquals(1, events.length())
      assertEquals("lifecycle", events.getJSONObject(0).getString("kind"))
      } finally {
        server.close()
      }
    }

  @Test
  fun exposesMemoryRetrievalEventsOverChatRuntimeSnapshotRoute() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-memory-runtime-route"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val runtimeManager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      resumeResult = false,
    )
    runtimeManager.handle = handle
    val hostRuntime = OpenCrayHostRuntime.createForTest(
      stateStore = AppShellStateStore(InMemoryAppShellKeyValueStore()),
      chatSessionStore = chatStore,
      settingsFacade = NoOpSettingsFacade,
      llmConfigFacade = EmptyLlmConfigFacade,
      sessionRuntimeManager = runtimeManager,
      strings = hostRuntimeStrings(),
    )
    hostRuntime.submitChatMessage("Need memory retrieval route")
    val task = handle.submittedTasks.single()
    val run = handle.submissions.single()
    runtimeManager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = OpenCrayMemoryRetrievalEvent(
        runId = run.runId,
        taskId = task.id,
        turn = 0,
        toolName = "memory_search",
        operation = "search",
        query = "gradle wrapper repo root",
        queryTerms = listOf("gradle", "wrapper", "repo", "root"),
        resultCount = 1,
        corpusFileCount = 1,
        recordIds = listOf("memory-user"),
        paths = listOf("memory/2024-03-11.md"),
        lineRanges = listOf("5-8"),
        emittedAtEpochMs = 1_100L,
      ),
    )
    val server = OpenCrayLocalRuntimeServer(
      hostRuntimeProvider = { hostRuntime },
      requestedPort = 0,
      shutdownExecutorOnClose = true,
    )
    server.ensureStarted()

    try {
      val response = request(server, "GET", "/v1/chat_runtime_snapshot")
      val payload = JSONObject(response.body)
      val event = payload.getJSONArray("events").getJSONObject(0)

      assertEquals(200, response.statusCode)
      assertEquals(activeSessionId, payload.getString("sessionId"))
      assertEquals("memory_retrieval", event.getString("kind"))
      assertEquals("memory_search", event.getString("toolName"))
      assertEquals("search", event.getString("operation"))
      assertEquals("gradle wrapper repo root", event.getString("query"))
      assertEquals("gradle", event.getJSONArray("queryTerms").getString(0))
      assertEquals(1, event.getInt("resultCount"))
      assertEquals("memory-user", event.getJSONArray("recordIds").getString(0))
      assertEquals("memory/2024-03-11.md", event.getJSONArray("paths").getString(0))
      assertEquals("5-8", event.getJSONArray("lineRanges").getString(0))
    } finally {
      server.close()
    }
  }

  @Test
  fun cancelsChatRunOverLoopbackHttp() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-cancel-run-route"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val runtimeManager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      resumeResult = false,
    )
    runtimeManager.handle = handle
    val hostRuntime = OpenCrayHostRuntime.createForTest(
      stateStore = AppShellStateStore(InMemoryAppShellKeyValueStore()),
      chatSessionStore = chatStore,
      settingsFacade = NoOpSettingsFacade,
      llmConfigFacade = EmptyLlmConfigFacade,
      sessionRuntimeManager = runtimeManager,
      strings = hostRuntimeStrings(),
    )
    val submission = hostRuntime.submitChatMessage("Cancel me")!!
    val server = OpenCrayLocalRuntimeServer(
      hostRuntimeProvider = { hostRuntime },
      requestedPort = 0,
      shutdownExecutorOnClose = true,
    )
    server.ensureStarted()

    try {
      val response = request(
        server,
        "POST",
        "/v1/cancel_chat_run",
        body = JSONObject().apply {
          put("runId", submission["runId"])
        }.toString(),
      )

      assertEquals(200, response.statusCode)
      assertEquals(listOf(handle.submissions.single().taskId), handle.cancelledTaskIds)
    } finally {
      server.close()
    }
  }

  @Test
  fun retriesChatRunOverLoopbackHttp() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-retry-run-route"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val runtimeManager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      resumeResult = false,
      retryResult = true,
    )
    runtimeManager.handle = handle
    val hostRuntime = OpenCrayHostRuntime.createForTest(
      stateStore = AppShellStateStore(InMemoryAppShellKeyValueStore()),
      chatSessionStore = chatStore,
      settingsFacade = NoOpSettingsFacade,
      llmConfigFacade = EmptyLlmConfigFacade,
      sessionRuntimeManager = runtimeManager,
      strings = hostRuntimeStrings(),
    )
    val submission = hostRuntime.submitChatMessage("Retry me")!!
    val server = OpenCrayLocalRuntimeServer(
      hostRuntimeProvider = { hostRuntime },
      requestedPort = 0,
      shutdownExecutorOnClose = true,
    )
    server.ensureStarted()

    try {
      val response = request(
        server,
        "POST",
        "/v1/retry_chat_run",
        body = JSONObject().apply {
          put("runId", submission["runId"])
        }.toString(),
      )

      assertEquals(200, response.statusCode)
      assertEquals(listOf(handle.submissions.single().taskId), handle.retriedTaskIds)
    } finally {
      server.close()
    }
  }

  @Test
  fun submitChatMessageReturnsRunSubmissionPayload() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-submit-run-route"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val runtimeManager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      resumeResult = false,
    )
    runtimeManager.handle = handle
    val hostRuntime = OpenCrayHostRuntime.createForTest(
      stateStore = AppShellStateStore(InMemoryAppShellKeyValueStore()),
      chatSessionStore = chatStore,
      settingsFacade = NoOpSettingsFacade,
      llmConfigFacade = EmptyLlmConfigFacade,
      sessionRuntimeManager = runtimeManager,
      strings = hostRuntimeStrings(),
    )
    val server = OpenCrayLocalRuntimeServer(
      hostRuntimeProvider = { hostRuntime },
      requestedPort = 0,
      shutdownExecutorOnClose = true,
    )
    server.ensureStarted()

    try {
      val response = request(
        server,
        "POST",
        "/v1/submit_chat_message",
        body = JSONObject().apply {
          put("text", "Need a run id")
        }.toString(),
      )
      val payload = JSONObject(response.body)

      assertEquals(200, response.statusCode)
      assertEquals(activeSessionId, payload.getString("sessionId"))
      assertTrue(payload.getString("runId").startsWith("run-"))
      assertTrue(payload.getString("taskId").startsWith("task-"))
    } finally {
      server.close()
    }
  }

  @Test
  fun exposesChatRunSnapshotOverLoopbackHttp() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-run-route"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val runtimeManager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      resumeResult = false,
    )
    runtimeManager.handle = handle
    val hostRuntime = OpenCrayHostRuntime.createForTest(
      stateStore = AppShellStateStore(InMemoryAppShellKeyValueStore()),
      chatSessionStore = chatStore,
      settingsFacade = NoOpSettingsFacade,
      llmConfigFacade = EmptyLlmConfigFacade,
      sessionRuntimeManager = runtimeManager,
      strings = hostRuntimeStrings(),
    )
    val submission = hostRuntime.submitChatMessage("Need a run snapshot")!!
    val task = handle.submittedTasks.single()
    runtimeManager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = ExecutionStatus.SUCCESS,
        stdout = "Completed with memory trace.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata + mapOf(
          "contextMatchedMemoryCount" to "2",
          "contextInjectedMemoryCount" to "1",
          "contextOmittedMemoryCount" to "1",
          "contextMemoryQueryTerms" to "chinese,gradle",
          "contextMemorySelectedSummary" to "memory-user@420[chinese]",
          "contextMemoryOmittedSummary" to "memory-project:max_records",
          "contextMemoryFilteredCounts" to "scope_mismatch:1,expired:2",
        ),
      ),
    )
    val server = OpenCrayLocalRuntimeServer(
      hostRuntimeProvider = { hostRuntime },
      requestedPort = 0,
      shutdownExecutorOnClose = true,
    )
    server.ensureStarted()

    try {
      val response = request(
        server,
        "GET",
        "/v1/chat_run_snapshot?runId=${submission["runId"]}",
      )
      val payload = JSONObject(response.body)
      val memoryTrace = payload.getJSONObject("memoryTrace")
      val selected = memoryTrace.getJSONArray("selected")
      val omitted = memoryTrace.getJSONArray("omitted")
      val filteredCounts = memoryTrace.getJSONObject("filteredCounts")

      assertEquals(200, response.statusCode)
      assertEquals(submission["runId"], payload.getString("runId"))
      assertEquals(submission["taskId"], payload.getString("taskId"))
      assertEquals(2, memoryTrace.getInt("matchedRecordCount"))
      assertEquals(1, memoryTrace.getInt("injectedRecordCount"))
      assertEquals(1, memoryTrace.getInt("omittedRecordCount"))
      assertEquals("chinese", memoryTrace.getJSONArray("queryTerms").getString(0))
      assertEquals("memory-user", selected.getJSONObject(0).getString("id"))
      assertEquals("max_records", omitted.getJSONObject(0).getString("reason"))
      assertEquals(1, filteredCounts.getInt("scope_mismatch"))
      assertEquals(2, filteredCounts.getInt("expired"))
    } finally {
      server.close()
    }
  }

  @Test
  fun exposesMemoryFlushOverChatRunSnapshotRoute() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-run-memory-flush-route"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val runtimeManager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      resumeResult = false,
    )
    runtimeManager.handle = handle
    val hostRuntime = OpenCrayHostRuntime.createForTest(
      stateStore = AppShellStateStore(InMemoryAppShellKeyValueStore()),
      chatSessionStore = chatStore,
      settingsFacade = NoOpSettingsFacade,
      llmConfigFacade = EmptyLlmConfigFacade,
      sessionRuntimeManager = runtimeManager,
      strings = hostRuntimeStrings(),
    )
    val submission = hostRuntime.submitChatMessage("Need a run memory flush snapshot")!!
    val task = handle.submittedTasks.single()
    runtimeManager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = ExecutionStatus.SUCCESS,
        stdout = "Completed with memory flush trace.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata + mapOf(
          "contextMemoryFlushOutcome" to "written",
          "contextMemoryFlushOmittedMessageCount" to "4",
          "contextMemoryFlushOmittedCharCount" to "512",
          "contextMemoryFlushSignature" to "flush-signature-123",
          "contextMemoryFlushCandidateCount" to "3",
          "contextMemoryFlushWrittenRecordCount" to "2",
          "contextMemoryFlushWrittenKinds" to "project_fact,user_preference",
          "contextMemoryFlushWrittenRecordIds" to "mem-a,mem-b",
        ),
      ),
    )
    val server = OpenCrayLocalRuntimeServer(
      hostRuntimeProvider = { hostRuntime },
      requestedPort = 0,
      shutdownExecutorOnClose = true,
    )
    server.ensureStarted()

    try {
      val response = request(
        server,
        "GET",
        "/v1/chat_run_snapshot?runId=${submission["runId"]}",
      )
      val payload = JSONObject(response.body)
      val memoryFlush = payload.getJSONObject("memoryFlush")

      assertEquals(200, response.statusCode)
      assertEquals("written", memoryFlush.getString("outcome"))
      assertEquals(4, memoryFlush.getInt("omittedMessageCount"))
      assertEquals(512, memoryFlush.getInt("omittedCharCount"))
      assertEquals("flush-signature-123", memoryFlush.getString("signature"))
      assertEquals(3, memoryFlush.getInt("candidateCount"))
      assertEquals(2, memoryFlush.getInt("writtenRecordCount"))
      assertEquals("project_fact", memoryFlush.getJSONArray("writtenKinds").getString(0))
      assertEquals("mem-a", memoryFlush.getJSONArray("writtenRecordIds").getString(0))
    } finally {
      server.close()
    }
  }

  @Test
  fun exposesSkillInventoryOverChatRunSnapshotRoute() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-run-skill-route"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val runtimeManager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      resumeResult = false,
    )
    runtimeManager.handle = handle
    val hostRuntime = OpenCrayHostRuntime.createForTest(
      stateStore = AppShellStateStore(InMemoryAppShellKeyValueStore()),
      chatSessionStore = chatStore,
      settingsFacade = NoOpSettingsFacade,
      llmConfigFacade = EmptyLlmConfigFacade,
      sessionRuntimeManager = runtimeManager,
      strings = hostRuntimeStrings(),
    )
    val submission = hostRuntime.submitChatMessage("Need a run skill snapshot")!!
    val task = handle.submittedTasks.single()
    runtimeManager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = ExecutionStatus.SUCCESS,
        stdout = "Completed with skill inventory.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata + mapOf(
          "contextVisibleSkillCount" to "2",
          "contextInjectedSkillCount" to "2",
          "contextOmittedSkillCount" to "0",
          "contextImplicitSkillCount" to "1",
          "contextInvalidSkillCount" to "1",
          "contextVisibleSkillTraceOmittedCount" to "3",
          "contextActiveSkillName" to "ui-ux-pro-max",
          "contextActiveSkillRelativePath" to ".codex/skills/ui-ux-pro-max/SKILL.md",
          "contextActiveSkillInvocationControl" to "explicit-only",
          "contextActiveSkillExecutionContext" to "inline",
          "contextActiveSkillActivationSource" to "skill_read",
          "contextActiveSkillToolRestrictionEnabled" to "true",
          "contextActiveSkillAllowedTools" to "read,write",
          "contextActiveSkillTruncated" to "false",
          "contextVisibleSkillSummary" to
            "ui-ux-pro-max@.codex/skills/ui-ux-pro-max/SKILL.md[explicit-only|true|inline];" +
            "fun-brainstorming@.codex/skills/fun-brainstorming/SKILL.md[explicit-and-implicit|true|fork]",
        ),
      ),
    )
    val server = OpenCrayLocalRuntimeServer(
      hostRuntimeProvider = { hostRuntime },
      requestedPort = 0,
      shutdownExecutorOnClose = true,
    )
    server.ensureStarted()

    try {
      val response = request(
        server,
        "GET",
        "/v1/chat_run_snapshot?runId=${submission["runId"]}",
      )
      val payload = JSONObject(response.body)
      val skillInventory = payload.getJSONObject("skillInventory")
      val activeSkill = payload.getJSONObject("activeSkill")
      val skills = skillInventory.getJSONArray("skills")

      assertEquals(200, response.statusCode)
      assertEquals(2, skillInventory.getInt("visibleSkillCount"))
      assertEquals(2, skillInventory.getInt("injectedSkillCount"))
      assertEquals(1, skillInventory.getInt("implicitSkillCount"))
      assertEquals(1, skillInventory.getInt("invalidSkillCount"))
      assertEquals(3, skillInventory.getInt("omittedTraceSkillCount"))
      assertEquals("ui-ux-pro-max", skills.getJSONObject(0).getString("name"))
      assertEquals(".codex/skills/ui-ux-pro-max/SKILL.md", skills.getJSONObject(0).getString("relativePath"))
      assertTrue(skills.getJSONObject(0).getBoolean("userInvocable"))
      assertEquals("fork", skills.getJSONObject(1).getString("executionContext"))
      assertEquals("ui-ux-pro-max", activeSkill.getString("name"))
      assertEquals("skill_read", activeSkill.getString("activationSource"))
      assertTrue(activeSkill.getBoolean("toolRestrictionEnabled"))
      assertEquals("read", activeSkill.getJSONArray("allowedToolKeys").getString(0))
    } finally {
      server.close()
    }
  }

  @Test
  fun exposesDurableCompactionOverChatRunSnapshotRoute() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-run-durable-route"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val runtimeManager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      resumeResult = false,
    )
    runtimeManager.handle = handle
    val hostRuntime = OpenCrayHostRuntime.createForTest(
      stateStore = AppShellStateStore(InMemoryAppShellKeyValueStore()),
      chatSessionStore = chatStore,
      settingsFacade = NoOpSettingsFacade,
      llmConfigFacade = EmptyLlmConfigFacade,
      sessionRuntimeManager = runtimeManager,
      strings = hostRuntimeStrings(),
    )
    val submission = hostRuntime.submitChatMessage("Need a run durable compaction snapshot")!!
    val task = handle.submittedTasks.single()
    runtimeManager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = ExecutionStatus.SUCCESS,
        stdout = "Completed with durable compaction.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata + mapOf(
          "contextDurableCompactionCompactedThisRun" to "true",
          "contextDurableCompactionSourceTranscriptMessageCount" to "18",
          "contextDurableCompactionRetainedTranscriptMessageCount" to "12",
          "contextDurableCompactionLatestMessageCount" to "6",
          "contextDurableCompactionIncludedSummaryCount" to "1",
          "contextDurableCompactionOmittedSummaryCount" to "0",
          "contextDurableCompactionTotalCompactedMessageCount" to "6",
          "contextDurableCompactionLatestAtEpochMs" to "4200",
        ),
      ),
    )
    val server = OpenCrayLocalRuntimeServer(
      hostRuntimeProvider = { hostRuntime },
      requestedPort = 0,
      shutdownExecutorOnClose = true,
    )
    server.ensureStarted()

    try {
      val response = request(
        server,
        "GET",
        "/v1/chat_run_snapshot?runId=${submission["runId"]}",
      )
      val payload = JSONObject(response.body)
      val durableCompaction = payload.getJSONObject("durableCompaction")

      assertEquals(200, response.statusCode)
      assertTrue(durableCompaction.getBoolean("compactedThisRun"))
      assertEquals(18, durableCompaction.getInt("sourceTranscriptMessageCount"))
      assertEquals(12, durableCompaction.getInt("retainedTranscriptMessageCount"))
      assertEquals(6, durableCompaction.getInt("latestCompactedMessageCount"))
      assertEquals(1, durableCompaction.getInt("includedSummaryCount"))
      assertEquals(0, durableCompaction.getInt("omittedSummaryCount"))
      assertEquals(1, durableCompaction.getInt("totalSummaryCount"))
      assertEquals(6, durableCompaction.getInt("totalCompactedMessageCount"))
      assertEquals(4200L, durableCompaction.getLong("latestCompactedAtEpochMs"))
    } finally {
      server.close()
    }
  }

  @Test
  fun exposesBootstrapOverChatRunSnapshotRoute() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-run-bootstrap-route"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val runtimeManager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      resumeResult = false,
    )
    runtimeManager.handle = handle
    val hostRuntime = OpenCrayHostRuntime.createForTest(
      stateStore = AppShellStateStore(InMemoryAppShellKeyValueStore()),
      chatSessionStore = chatStore,
      settingsFacade = NoOpSettingsFacade,
      llmConfigFacade = EmptyLlmConfigFacade,
      sessionRuntimeManager = runtimeManager,
      strings = hostRuntimeStrings(),
    )
    val submission = hostRuntime.submitChatMessage("Need a run bootstrap snapshot")!!
    val task = handle.submittedTasks.single()
    runtimeManager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = ExecutionStatus.SUCCESS,
        stdout = "Completed with bootstrap context.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata + mapOf(
          "contextLiveMode" to "no_memory_or_soul",
          "contextLiveSoulEnabled" to "false",
          "contextLiveMemoryRecallEnabled" to "false",
          "contextBootstrapMode" to "full",
          "contextBootstrapVisibleFileCount" to "2",
          "contextBootstrapInjectedFileCount" to "2",
          "contextBootstrapOmittedFileCount" to "0",
          "contextBootstrapTruncatedFileCount" to "1",
          "contextBootstrapFileSummary" to
            "AGENTS.md@AGENTS.md[42|42|false];PROJECT.md@PROJECT.md[80|31|true]",
        ),
      ),
    )
    val server = OpenCrayLocalRuntimeServer(
      hostRuntimeProvider = { hostRuntime },
      requestedPort = 0,
      shutdownExecutorOnClose = true,
    )
    server.ensureStarted()

    try {
      val response = request(
        server,
        "GET",
        "/v1/chat_run_snapshot?runId=${submission["runId"]}",
      )
      val payload = JSONObject(response.body)
      val liveContext = payload.getJSONObject("liveContext")
      val bootstrap = payload.getJSONObject("bootstrap")
      val files = bootstrap.getJSONArray("files")

      assertEquals(200, response.statusCode)
      assertEquals("no_memory_or_soul", liveContext.getString("mode"))
      assertEquals(false, liveContext.getBoolean("soulEnabled"))
      assertEquals(false, liveContext.getBoolean("memoryRecallEnabled"))
      assertEquals("full", bootstrap.getString("mode"))
      assertEquals(2, bootstrap.getInt("visibleFileCount"))
      assertEquals(2, bootstrap.getInt("injectedFileCount"))
      assertEquals(0, bootstrap.getInt("omittedFileCount"))
      assertEquals(1, bootstrap.getInt("truncatedFileCount"))
      assertEquals("AGENTS.md", files.getJSONObject(0).getString("name"))
      assertEquals("AGENTS.md", files.getJSONObject(0).getString("relativePath"))
      assertEquals(42, files.getJSONObject(0).getInt("sourceCharCount"))
      assertTrue(files.getJSONObject(1).getBoolean("truncated"))
    } finally {
      server.close()
    }
  }

  @Test
  fun memoryDebugSnapshotRouteReturnsStructuredStoreState() {
    val personalizationStore = PersonalizationLocalStore(
      temporaryFolder.newFolder("server-memory-debug"),
    )
    personalizationStore.upsertMemoryRecord(
      MemoryRecord(
        id = "memory-user",
        content = "Call the agent Xiao Bai.",
        createdAtEpochMs = 2_000L,
        updatedAtEpochMs = 2_100L,
        tags = listOf("kind:user_preference", "scope:user"),
        extensions = mapOf(
          MemoryRecordExtensionKeys.KIND to "user_preference",
          MemoryRecordExtensionKeys.SCOPE to "user",
          MemoryRecordExtensionKeys.STATUS to "active",
          MemoryRecordExtensionKeys.PREFERENCE_KEY to MemoryPreferenceKeys.AGENT_DISPLAY_NAME,
          MemoryRecordExtensionKeys.PREFERENCE_VALUE to "Xiao Bai",
          MemorySoulExtensionKeys.DISPLAY_NAME to "Xiao Bai",
        ),
      ),
    )
    val hostRuntime = OpenCrayHostRuntime.createForTest(
      stateStore = AppShellStateStore(InMemoryAppShellKeyValueStore()),
      chatSessionStore = ChatSessionLocalStore(
        temporaryFolder.newFolder("chat-store-memory-debug"),
      ),
      settingsFacade = NoOpSettingsFacade,
      personalizationLocalStore = personalizationStore,
      sessionRuntimeManager = NoOpRuntimeManager(),
      strings = hostRuntimeStrings(),
    )
    val server = OpenCrayLocalRuntimeServer(
      hostRuntimeProvider = { hostRuntime },
      requestedPort = 0,
      shutdownExecutorOnClose = true,
    )
    server.ensureStarted()

    try {
      val response = request(server, "GET", "/v1/memory_debug_snapshot")

      assertEquals(200, response.statusCode)
      val payload = JSONObject(response.body)
      val records = payload.getJSONArray("records")
      assertEquals("memory-user", records.getJSONObject(0).getString("id"))
      assertEquals("Xiao Bai", records.getJSONObject(0).getString("preferenceValue"))
    } finally {
      server.close()
    }
  }

  @Test
  fun memoryDebugLinksSnapshotRouteReturnsDeterministicRecordLinks() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-memory-links-route"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val personalizationStore = PersonalizationLocalStore(
      temporaryFolder.newFolder("server-memory-links"),
    )
    val runtimeManager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(sessionId = sessionId, resumeResult = false)
    runtimeManager.handle = handle
    val hostRuntime = OpenCrayHostRuntime.createForTest(
      stateStore = AppShellStateStore(InMemoryAppShellKeyValueStore()),
      chatSessionStore = chatStore,
      settingsFacade = NoOpSettingsFacade,
      personalizationLocalStore = personalizationStore,
      sessionRuntimeManager = runtimeManager,
      strings = hostRuntimeStrings(),
    )

    val sourceSubmission = handle.submitPrompt(
      userText = "Remember my preferred agent name.",
      pendingMessageId = "pending-memory-source",
      visibleThroughMessageId = "pending-memory-source",
      policyDecision = PolicyDecision(
        outcome = PolicyDecisionOutcome.ALLOW,
        reasonCode = "TEST_ALLOW",
      ),
      metadata = emptyMap(),
    )
    val sourceTask = handle.submittedTasks.last()
    handle.recordResult(
      task = sourceTask,
      result = ExecutionResult(
        taskId = sourceTask.id,
        status = ExecutionStatus.SUCCESS,
        stdout = "",
        startedAtEpochMs = 2_100L,
        finishedAtEpochMs = 2_200L,
        metadata = mapOf("responseFormat" to "json_final"),
      ),
    )
    personalizationStore.upsertMemoryRecord(
      MemoryRecord(
        id = "memory-user",
        content = "Call the agent Xiao Bai.",
        createdAtEpochMs = 2_000L,
        updatedAtEpochMs = 2_200L,
        tags = listOf("kind:user_preference", "scope:user"),
        extensions = mapOf(
          MemoryRecordExtensionKeys.KIND to "user_preference",
          MemoryRecordExtensionKeys.SCOPE to "user",
          MemoryRecordExtensionKeys.STATUS to "active",
          MemoryRecordExtensionKeys.SOURCE to "user_input",
          MemoryRecordExtensionKeys.SOURCE_SESSION_ID to sessionId,
          MemoryRecordExtensionKeys.SOURCE_TASK_ID to sourceTask.id,
          MemoryRecordExtensionKeys.PREFERENCE_KEY to MemoryPreferenceKeys.AGENT_DISPLAY_NAME,
          MemoryRecordExtensionKeys.PREFERENCE_VALUE to "Xiao Bai",
          MemorySoulExtensionKeys.DISPLAY_NAME to "Xiao Bai",
        ),
      ),
    )
    runtimeManager.emitRunEvent(
      sessionId = sessionId,
      task = sourceTask,
      event = OpenCrayMemoryWriteEvent(
        runId = sourceSubmission.runId,
        taskId = sourceTask.id,
        writtenRecordIds = listOf("memory-user"),
        writtenKinds = listOf("user_preference"),
        emittedAtEpochMs = 2_200L,
      ),
    )

    val recallSubmission = handle.submitPrompt(
      userText = "What name should I use for the agent?",
      pendingMessageId = "pending-memory-recall",
      visibleThroughMessageId = "pending-memory-recall",
      policyDecision = PolicyDecision(
        outcome = PolicyDecisionOutcome.ALLOW,
        reasonCode = "TEST_ALLOW",
      ),
      metadata = emptyMap(),
    )
    val recallTask = handle.submittedTasks.last()
    handle.recordResult(
      task = recallTask,
      result = ExecutionResult(
        taskId = recallTask.id,
        status = ExecutionStatus.SUCCESS,
        stdout = "",
        startedAtEpochMs = 2_400L,
        finishedAtEpochMs = 2_500L,
        metadata = mapOf(
          "responseFormat" to "json_final",
          "contextMemorySelectedSummary" to "memory-user@420[chinese|name]",
          "contextMemoryFlushWrittenRecordIds" to "memory-user",
        ),
      ),
    )
    runtimeManager.emitRunEvent(
      sessionId = sessionId,
      task = recallTask,
      event = OpenCrayMemoryRetrievalEvent(
        runId = recallSubmission.runId,
        taskId = recallTask.id,
        turn = 0,
        toolName = "memory_search",
        operation = "search",
        query = "what name should I call the agent",
        queryTerms = listOf("name", "agent"),
        resultCount = 1,
        corpusFileCount = 1,
        recordIds = listOf("memory-user"),
        paths = listOf("memory/2024-03-11.md"),
        lineRanges = listOf("5-8"),
        emittedAtEpochMs = 2_400L,
      ),
    )

    val server = OpenCrayLocalRuntimeServer(
      hostRuntimeProvider = { hostRuntime },
      requestedPort = 0,
      shutdownExecutorOnClose = true,
    )
    server.ensureStarted()

    try {
      val response = request(server, "GET", "/v1/memory_debug_links_snapshot")

      assertEquals(200, response.statusCode)
      val payload = JSONObject(response.body)
      val record = payload.getJSONArray("records").getJSONObject(0)
      assertEquals("memory-user", record.getString("recordId"))
      assertEquals(sessionId, record.getString("sourceSessionId"))
      assertEquals(sourceTask.id, record.getString("sourceTaskId"))
      assertEquals(
        sourceSubmission.runId,
        record.getJSONObject("sourceRun").getString("runId"),
      )
      assertEquals(
        420,
        record.getJSONArray("promptRecalls")
          .getJSONObject(0)
          .getInt("score"),
      )
      assertEquals(
        "memory_search",
        record.getJSONArray("toolRetrievals")
          .getJSONObject(0)
          .getString("toolName"),
      )
      val maintenanceActions = (0 until record.getJSONArray("maintenanceActions").length())
        .map { index -> record.getJSONArray("maintenanceActions").getJSONObject(index) }
      assertTrue(maintenanceActions.any { action -> action.getString("action") == "written" })
      assertTrue(
        maintenanceActions.any { action -> action.getString("action") == "flush_written" },
      )
    } finally {
      server.close()
    }
  }

  @Test
  fun memoryDebugActionRouteSuppressesRecordAndEmitsMaintenanceLink() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-memory-debug-action-route"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val personalizationStore = PersonalizationLocalStore(
      temporaryFolder.newFolder("server-memory-debug-action"),
    )
    personalizationStore.upsertMemoryRecord(
      MemoryRecord(
        id = "memory-user",
        content = "Call the agent Xiao Bai.",
        createdAtEpochMs = 2_000L,
        updatedAtEpochMs = 2_100L,
        tags = listOf("kind:user_preference", "scope:user", "status:active"),
        extensions = mapOf(
          MemoryRecordExtensionKeys.KIND to "user_preference",
          MemoryRecordExtensionKeys.SCOPE to "user",
          MemoryRecordExtensionKeys.STATUS to "active",
          MemoryRecordExtensionKeys.SOURCE_SESSION_ID to sessionId,
          MemoryRecordExtensionKeys.PREFERENCE_KEY to MemoryPreferenceKeys.AGENT_DISPLAY_NAME,
          MemoryRecordExtensionKeys.PREFERENCE_VALUE to "Xiao Bai",
          MemorySoulExtensionKeys.DISPLAY_NAME to "Xiao Bai",
        ),
      ),
    )
    val hostRuntime = OpenCrayHostRuntime.createForTest(
      stateStore = AppShellStateStore(InMemoryAppShellKeyValueStore()),
      chatSessionStore = chatStore,
      settingsFacade = NoOpSettingsFacade,
      llmConfigFacade = EmptyLlmConfigFacade,
      personalizationLocalStore = personalizationStore,
      sessionRuntimeManager = NoOpRuntimeManager(),
      strings = hostRuntimeStrings(),
    )
    var server = OpenCrayLocalRuntimeServer(
      hostRuntimeProvider = { hostRuntime },
      requestedPort = 0,
      shutdownExecutorOnClose = true,
    )
    server.ensureStarted()

    try {
      val response = request(
        server,
        "POST",
        "/v1/memory_debug_action",
        JSONObject(
          mapOf(
            "recordId" to "memory-user",
            "actionId" to "suppress",
          ),
        ).toString(),
      )

      assertEquals(200, response.statusCode)
      val payload = JSONObject(response.body)
      assertEquals("memory-user", payload.getString("recordId"))
      assertEquals("suppress", payload.getString("action"))
      assertTrue(payload.getBoolean("applied"))

      val updatedRecord = personalizationStore.listMemoryRecords()
        .single { record -> record.id == "memory-user" }
      assertEquals(
        "resolved",
        updatedRecord.extensions[MemoryRecordExtensionKeys.STATUS],
      )
      assertEquals(
        "operator_suppressed",
        updatedRecord.extensions[MemoryRecordExtensionKeys.RESOLUTION_REASON],
      )

      server.close()
      val reloadedHostRuntime = OpenCrayHostRuntime.createForTest(
        stateStore = AppShellStateStore(InMemoryAppShellKeyValueStore()),
        chatSessionStore = chatStore,
        settingsFacade = NoOpSettingsFacade,
        llmConfigFacade = EmptyLlmConfigFacade,
        personalizationLocalStore = personalizationStore,
        sessionRuntimeManager = NoOpRuntimeManager(),
        strings = hostRuntimeStrings(),
      )
      server = OpenCrayLocalRuntimeServer(
        hostRuntimeProvider = { reloadedHostRuntime },
        requestedPort = 0,
        shutdownExecutorOnClose = true,
      )
      server.ensureStarted()

      val linksResponse = request(server, "GET", "/v1/memory_debug_links_snapshot")
      assertEquals(200, linksResponse.statusCode)
      val linksPayload = JSONObject(linksResponse.body)
      val record = linksPayload.getJSONArray("records").getJSONObject(0)
      val maintenanceActions = (0 until record.getJSONArray("maintenanceActions").length())
        .map { index -> record.getJSONArray("maintenanceActions").getJSONObject(index) }
      assertTrue(
        maintenanceActions.any { action -> action.getString("action") == "suppressed" },
      )
    } finally {
      server.close()
    }
  }

  @Test
  fun soulDebugSnapshotRouteReturnsStoredAndEffectiveSoulState() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-soul-debug"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val personalizationStore = PersonalizationLocalStore(
      temporaryFolder.newFolder("server-soul-debug"),
    )
    val workspaceRoot = temporaryFolder.newFolder("server-workspace-soul-debug").toPath()
    WorkspaceSoulProfileStore().saveSoulProfile(
      workspaceRoot,
      WorkspaceSoulProfile(
        presetName = "STEADY",
        customLabel = "Night Shift",
        customGuidance = "Keep replies calm and concrete.",
      ),
    )
    personalizationStore.upsertMemoryRecord(
      MemoryRecord(
        id = "memory-user",
        content = "Call the agent Xiao Bai.",
        createdAtEpochMs = 2_000L,
        updatedAtEpochMs = 2_100L,
        tags = listOf("kind:user_preference", "scope:user"),
        extensions = mapOf(
          MemoryRecordExtensionKeys.KIND to "user_preference",
          MemoryRecordExtensionKeys.SCOPE to "user",
          MemoryRecordExtensionKeys.STATUS to "active",
          MemoryRecordExtensionKeys.SOURCE_SESSION_ID to sessionId,
          MemoryRecordExtensionKeys.PREFERENCE_KEY to MemoryPreferenceKeys.AGENT_DISPLAY_NAME,
          MemoryRecordExtensionKeys.PREFERENCE_VALUE to "Xiao Bai",
          MemorySoulExtensionKeys.DISPLAY_NAME to "Xiao Bai",
        ),
      ),
    )
    personalizationStore.upsertMemoryRecord(
      MemoryRecord(
        id = "interaction-state",
        content = "internal interaction preference snapshot",
        createdAtEpochMs = 2_300L,
        updatedAtEpochMs = 2_300L,
        extensions = mapOf(
          MemoryRecordExtensionKeys.SCOPE to "user",
          MemoryRecordExtensionKeys.STATUS to "active",
          MemoryRecordExtensionKeys.LAST_CONFIRMED_AT_EPOCH_MS to "2300",
          SoulMemoryExtensionKeys.OBJECT_TYPE to SoulMemoryObjectTypes.INTERACTION_PREFERENCE_STATE,
          SoulMemoryExtensionKeys.OBJECT_SCHEMA_VERSION to "1",
          SoulMemoryExtensionKeys.OBJECT_PAYLOAD_JSON to Json.encodeToString(
            InteractionPreferenceState.serializer(),
            InteractionPreferenceState(
              warmth = PreferenceAxisState(offset = 1, higherSupport = 2),
              formality = PreferenceAxisState(offset = -1, lowerSupport = 2),
              initiative = PreferenceAxisState(offset = 1, higherSupport = 2),
              playfulness = PreferenceAxisState(offset = 1, higherSupport = 2),
              reassurance = PreferenceAxisState(offset = 1, higherSupport = 2),
              addressStyle = PreferredAddressState(
                selectedStyle = PreferredAddressStyle.FRIENDLY,
                friendlySupport = 2,
              ),
              preferredNaming = "A-Cheng",
              preferredNamingSupport = 2,
            ),
          ),
        ),
      ),
    )
    personalizationStore.upsertMemoryRecord(
      MemoryRecord(
        id = "relationship-state",
        content = "internal relationship snapshot",
        createdAtEpochMs = 2_400L,
        updatedAtEpochMs = 2_400L,
        extensions = mapOf(
          MemoryRecordExtensionKeys.SCOPE to "user",
          MemoryRecordExtensionKeys.STATUS to "active",
          MemoryRecordExtensionKeys.SOURCE_SESSION_ID to sessionId,
          MemoryRecordExtensionKeys.LAST_CONFIRMED_AT_EPOCH_MS to "2400",
          SoulMemoryExtensionKeys.OBJECT_TYPE to SoulMemoryObjectTypes.RELATIONSHIP_STATE,
          SoulMemoryExtensionKeys.OBJECT_SCHEMA_VERSION to "1",
          SoulMemoryExtensionKeys.OBJECT_PAYLOAD_JSON to Json.encodeToString(
            RelationshipState.serializer(),
            RelationshipState(
              familiarity = 66,
              trust = 74,
              safety = 76,
              intimacyPermission = 61,
              playfulnessPermission = 44,
              affectionTendency = 34,
              reciprocity = 49,
            ),
          ),
        ),
      ),
    )
    val hostRuntime = OpenCrayHostRuntime.createForTest(
      stateStore = AppShellStateStore(InMemoryAppShellKeyValueStore()),
      chatSessionStore = chatStore,
      settingsFacade = NoOpSettingsFacade,
      personalizationLocalStore = personalizationStore,
      workspaceRootProvider = { workspaceRoot },
      sessionRuntimeManager = NoOpRuntimeManager(),
      strings = hostRuntimeStrings(),
    )
    val server = OpenCrayLocalRuntimeServer(
      hostRuntimeProvider = { hostRuntime },
      requestedPort = 0,
      shutdownExecutorOnClose = true,
    )
    server.ensureStarted()

    try {
      val response = request(server, "GET", "/v1/soul_debug_snapshot")

      assertEquals(200, response.statusCode)
      val payload = JSONObject(response.body)
      assertEquals("STEADY", payload.getJSONObject("storedSoul").getString("presetName"))
      val effectiveSoul = payload.getJSONObject("effectiveSoul")
      assertEquals("Xiao Bai", effectiveSoul.getString("displayName"))
      assertEquals("1", effectiveSoul.getString("warmthPreferenceOffset"))
      assertEquals("-1", effectiveSoul.getString("formalityPreferenceOffset"))
      assertEquals("1", effectiveSoul.getString("initiativePreferenceOffset"))
      assertEquals("1", effectiveSoul.getString("playfulnessPreferenceOffset"))
      assertEquals("1", effectiveSoul.getString("reassurancePreferenceOffset"))
      assertEquals("true", effectiveSoul.getString("supportiveReassuranceAllowed"))
      assertEquals("true", effectiveSoul.getString("proactiveRelationalCheckInAllowed"))
      assertEquals("true", effectiveSoul.getString("lightPlayfulnessAllowed"))
      assertEquals("true", effectiveSoul.getString("playfulTeasingAllowed"))
      val interactionPreferenceDebug = payload.getJSONObject("interactionPreferenceDebug")
      assertEquals("user", interactionPreferenceDebug.getString("scope"))
      assertEquals("A-Cheng", interactionPreferenceDebug.getString("preferredNaming"))
      assertEquals("friendly", interactionPreferenceDebug.getString("preferredAddressStyle"))
      val relationshipStateDebug = payload.getJSONObject("relationshipStateDebug")
      assertEquals("user", relationshipStateDebug.getString("scope"))
      assertEquals("intimate", relationshipStateDebug.getString("derivedAddressStyle"))
      assertEquals(false, relationshipStateDebug.getBoolean("recentNegativeGuardActive"))
      assertEquals(true, relationshipStateDebug.getBoolean("supportiveReassuranceAllowed"))
      assertEquals(true, relationshipStateDebug.getBoolean("proactiveRelationalCheckInAllowed"))
      assertEquals(true, relationshipStateDebug.getBoolean("lightPlayfulnessAllowed"))
      assertEquals(true, relationshipStateDebug.getBoolean("playfulTeasingAllowed"))
      assertEquals(true, relationshipStateDebug.getBoolean("highIntimacyBehaviorAllowed"))
      val fieldSources = payload.getJSONArray("fieldSources")
      fun fieldSource(field: String): JSONObject = (0 until fieldSources.length())
        .map { index -> fieldSources.getJSONObject(index) }
        .first { source -> source.getString("field") == field }

      val displayNameSource = fieldSource("displayName")
      val warmthOffsetSource = fieldSource("warmthPreferenceOffset")
      val playfulnessOffsetSource = fieldSource("playfulnessPreferenceOffset")
      val reassuranceOffsetSource = fieldSource("reassurancePreferenceOffset")
      val supportiveReassuranceSource = fieldSource("supportiveReassuranceAllowed")
      val proactiveCheckInSource = fieldSource("proactiveRelationalCheckInAllowed")
      val lightPlayfulnessSource = fieldSource("lightPlayfulnessAllowed")
      val playfulTeasingSource = fieldSource("playfulTeasingAllowed")
      assertEquals("memory_overlay", displayNameSource.getString("sourceType"))
      assertEquals("memory-user", displayNameSource.getString("recordId"))
      assertEquals("interaction_preference", warmthOffsetSource.getString("sourceType"))
      assertEquals("interaction-state", warmthOffsetSource.getString("recordId"))
      assertEquals("interaction_preference", playfulnessOffsetSource.getString("sourceType"))
      assertEquals("interaction-state", playfulnessOffsetSource.getString("recordId"))
      assertEquals("interaction_preference", reassuranceOffsetSource.getString("sourceType"))
      assertEquals("interaction-state", reassuranceOffsetSource.getString("recordId"))
      assertEquals("relationship_state", supportiveReassuranceSource.getString("sourceType"))
      assertEquals("relationship-state", supportiveReassuranceSource.getString("recordId"))
      assertEquals("relationship_state", proactiveCheckInSource.getString("sourceType"))
      assertEquals("relationship-state", proactiveCheckInSource.getString("recordId"))
      assertEquals("relationship_state", lightPlayfulnessSource.getString("sourceType"))
      assertEquals("relationship-state", lightPlayfulnessSource.getString("recordId"))
      assertEquals("relationship_state", playfulTeasingSource.getString("sourceType"))
      assertEquals("relationship-state", playfulTeasingSource.getString("recordId"))
    } finally {
      server.close()
    }
  }

  @Test
  fun skillsEndpointsSupportQueryAndDirectSourceInstall() {
    val skillsFacade = RecordingSkillsFacade().apply {
      snapshot = SkillsSnapshot(
        installedSkills = emptyList(),
        installSources = listOf(
          InstallSourceSnapshot(
            id = "github-url",
            title = "GitHub URL",
            subtitle = "Enter a source ref.",
            actionLabel = "Inspect",
            isAvailable = true,
          ),
        ),
        suggestedSkills = emptyList(),
      )
    }
    val runtimeManager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = "skills-session",
      resumeResult = false,
    ).apply {
      queuedToolCompletion = QueuedToolCompletion(
        toolName = "SkillsFind",
        content = "find-skills\tremote\tinstall_ref=roin-orca/skills@find-skills\tsource=roin-orca/skills\tinstalls=42",
      )
    }
    runtimeManager.handle = handle
    val server = localRuntimeServer(
      skillsFacade = skillsFacade,
      runtimeManager = runtimeManager,
    )
    server.ensureStarted()

    try {
      val queryResponse = request(server, "GET", "/v1/skills_snapshot?query=find")

      assertEquals(200, queryResponse.statusCode)
      assertEquals("", skillsFacade.lastLoadedQuery)
      assertTrue(queryResponse.body.contains("roin-orca/skills@find-skills"))
      assertTrue(handle.submittedTasks.first().input.contains("\"tool_name\":\"SkillsFind\""))
      assertTrue(handle.submittedTasks.first().input.contains("\"query\":\"find\""))

      handle.queuedToolCompletion = QueuedToolCompletion(
        toolName = "SkillsInspect",
        content = """
          inspection	remote_github	source_ref=roin-orca/skills	source_path=https://github.com/roin-orca/skills	resolved_revision=main	resolved_commit=deadbeef	candidate_count=2
          candidate	find-skills	description=Discover skills	relative_path=skills/find-skills/SKILL.md
          candidate	review-skills	description=Review changes	relative_path=skills/review-skills/SKILL.md
        """.trimIndent(),
        metadata = mapOf(
          "sourceType" to "remote_github",
          "sourceRef" to "roin-orca/skills",
          "candidateCount" to "2",
        ),
      )

      val inspectResponse = request(
        server,
        "POST",
        "/v1/inspect_skill_source",
        body = """{"sourceRef":"roin-orca/skills"}""",
      )

      assertEquals(200, inspectResponse.statusCode)
      assertTrue(inspectResponse.body.contains("\"sourceType\":\"remote_github\""))
      assertTrue(inspectResponse.body.contains("\"name\":\"find-skills\""))
      assertTrue(handle.submittedTasks.any { task ->
        task.input.contains("\"tool_name\":\"SkillsInspect\"") &&
          task.input.contains("\"sourceRef\":\"roin-orca/skills\"")
      })

      handle.queuedToolCompletion = QueuedToolCompletion(
        toolName = "SkillsAddBatch",
        content = """
          batch_install	remote_github	source_ref=roin-orca/skills	requested_count=2	installed_count=2	failed_count=0
          installed	find-skills	requested=find-skills	relative_path=skills/find-skills/SKILL.md
          installed	review-skills	requested=review-skills	relative_path=skills/review-skills/SKILL.md
        """.trimIndent(),
        metadata = mapOf(
          "sourceType" to "remote_github",
          "sourceRef" to "roin-orca/skills",
          "requestedCount" to "2",
          "installedCount" to "2",
          "failedCount" to "0",
        ),
      )

      val batchInstallResponse = request(
        server,
        "POST",
        "/v1/install_skill_source_batch",
        body = """{"sourceRef":"roin-orca/skills","selectedSkillNames":["find-skills","review-skills"]}""",
      )

      assertEquals(200, batchInstallResponse.statusCode)
      assertTrue(batchInstallResponse.body.contains("Installed 2 skills."))
      assertTrue(handle.submittedTasks.any { task ->
        task.input.contains("\"tool_name\":\"SkillsAddBatch\"") &&
          task.input.contains("\"sourceRef\":\"roin-orca/skills\"") &&
          task.input.contains("\"skills\":[\"find-skills\",\"review-skills\"]")
      })

      handle.queuedToolCompletion = QueuedToolCompletion(
        toolName = "SkillsAdd",
        content = "Installed skill 'review-skills' from remote source 'roin-orca/skills'.",
        metadata = mapOf("skillId" to "review-skills"),
      )

      val installResponse = request(
        server,
        "POST",
        "/v1/install_skill_source",
        body = """{"sourceRef":"roin-orca/skills","selectedSkillName":"review-skills"}""",
      )

      assertEquals(200, installResponse.statusCode)
      assertTrue(installResponse.body.contains("Installed review-skills."))
      assertTrue(handle.submittedTasks.any { task ->
        task.input.contains("\"tool_name\":\"SkillsAdd\"") &&
          task.input.contains("\"sourceRef\":\"roin-orca/skills\"") &&
          task.input.contains("\"skill\":\"review-skills\"")
      })
    } finally {
      server.close()
    }
  }

  @Test
  fun skillsUpdateEndpointUsesDirectToolRuntimeFactory() {
    val directTaskRuntimeFactory = RecordingDirectTaskRuntimeFactory(
      status = ExecutionStatus.SUCCESS,
      stdout = "find-skills: updated",
    )
    val server = localRuntimeServer(directTaskRuntimeFactory = directTaskRuntimeFactory)
    server.ensureStarted()

    try {
      val response = request(
        server,
        "POST",
        "/v1/update_installed_skill",
        body = """{"skillId":"find-skills"}""",
      )

      assertEquals(200, response.statusCode)
      assertTrue(response.body.contains("find-skills: updated"))
      assertTrue(directTaskRuntimeFactory.submittedTasks.any { task ->
        task.input.contains("\"tool_name\":\"SkillsUpdate\"") &&
          task.input.contains("\"skillId\":\"find-skills\"")
      })
    } finally {
      server.close()
    }
  }

  @Test
  fun deleteInstalledSkillEndpointUsesSessionPipeline() {
    val runtimeManager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = "skills-session",
      resumeResult = false,
    ).apply {
      queuedToolCompletion = QueuedToolCompletion(
        toolName = "SkillsRemove",
        content = "Removed skill 'find-skills' from the host-managed skills directory.",
        metadata = mapOf("skillId" to "find-skills"),
      )
    }
    runtimeManager.handle = handle
    val server = localRuntimeServer(runtimeManager = runtimeManager)
    server.ensureStarted()

    try {
      val response = request(
        server,
        "POST",
        "/v1/delete_installed_skill",
        body = """{"skillId":"find-skills"}""",
      )

      assertEquals(200, response.statusCode)
      assertTrue(response.body.contains("Removed find-skills."))
      assertTrue(handle.submittedTasks.any { task ->
        task.input.contains("\"tool_name\":\"SkillsRemove\"") &&
          task.input.contains("\"skillId\":\"find-skills\"")
      })
    } finally {
      server.close()
    }
  }

  @Test
  fun openWorkspaceEntryEndpointDelegatesToHostRuntime() {
    val workspaceRoot = temporaryFolder.newFolder("server-workspace-open-entry").toPath()
    val openedEntries = mutableListOf<Pair<Path, String>>()
    val server = localRuntimeServer(
      workspaceRootProvider = { workspaceRoot },
      workspaceEntryOpener = { root, relativePath ->
        openedEntries += root to relativePath
      },
    )
    server.ensureStarted()

    try {
      val response = request(
        server,
        "POST",
        "/v1/open_workspace_entry",
        body = """{"relativePath":".opencray/chat-media/session-1/hash/report.pdf"}""",
      )

      assertEquals(200, response.statusCode)
      assertEquals(
        listOf(
          workspaceRoot.toAbsolutePath().normalize() to
            ".opencray/chat-media/session-1/hash/report.pdf",
        ),
        openedEntries,
      )
    } finally {
      server.close()
    }
  }

  @Test
  fun voicePlaybackSourceEndpointLoadsResolvedWorkspaceVoiceSource() {
    val workspaceRoot = temporaryFolder.newFolder("server-workspace-voice-source").toPath()
    val voiceFile = workspaceRoot.resolve(".opencray/chat-media/session-1/hash/voice-note.m4a")
    Files.createDirectories(voiceFile.parent)
    Files.write(voiceFile, byteArrayOf(1, 2, 3, 4))
    val server = localRuntimeServer(
      workspaceRootProvider = { workspaceRoot },
    )
    server.ensureStarted()

    try {
      val response = request(
        server,
        "GET",
        "/v1/workspace_voice_playback_source?relativePath=.opencray/chat-media/session-1/hash/voice-note.m4a",
      )

      assertEquals(200, response.statusCode)
      assertTrue(response.body.contains("voice-note.m4a"))
      assertTrue(response.body.contains("\"mimeType\":\"audio/mp4\""))
      assertTrue(response.body.contains("\"sizeBytes\":4"))
    } finally {
      server.close()
    }
  }

  @Test
  fun exposesTwinImportSourceProbeOverLoopbackHttp() {
    val sourceFile = temporaryFolder.newFile("chatlab-probe.jsonl").toPath()
    sourceFile.writeText(
      listOf(
        "{\"_type\":\"header\",\"chatlab\":{\"version\":\"1\"},\"meta\":{\"name\":\"Lin x User\",\"groupId\":\"chatlab_lin_user\",\"type\":\"private\"}}",
        "{\"_type\":\"member\",\"platformId\":\"actor_lin\",\"accountName\":\"Lin\"}",
        "{\"_type\":\"message\",\"sender\":\"actor_user\",\"accountName\":\"User\",\"timestamp\":1735910400,\"type\":0,\"content\":\"我会补上。\"}",
      ).joinToString(separator = "\n", postfix = "\n"),
    )
    val server = localRuntimeServer()
    server.ensureStarted()

    try {
      val response = request(
        server,
        "POST",
        "/v1/probe_twin_import_source",
        body = JSONObject().apply {
          put("filePath", sourceFile.toString())
        }.toString(),
      )
      val payload = JSONObject(response.body)

      assertEquals(200, response.statusCode)
      assertEquals("chat_history", payload.getString("sourceMode"))
      assertEquals("chatlab_jsonl", payload.getString("formatKey"))
      assertEquals(true, payload.getBoolean("usesExistingImporter"))
      assertEquals(false, payload.getBoolean("needsManualSelection"))
    } finally {
      server.close()
    }
  }

  private fun localRuntimeServer(
    llmConfigFacade: LlmConfigFacade = EmptyLlmConfigFacade,
    networkSearchConfigFacade: NetworkSearchConfigFacade = EmptyNetworkSearchConfigFacade,
    skillsFacade: SkillsFacade = RecordingSkillsFacade(),
    runtimeManager: AgentSessionRuntimeManager = NoOpRuntimeManager(),
    directTaskRuntimeFactory: AgentSessionTaskRuntimeFactory? = null,
    workspaceRootProvider: (() -> Path)? = null,
    workspaceEntryOpener: ((Path, String) -> Unit)? = null,
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
  ): OpenCrayLocalRuntimeServer = OpenCrayLocalRuntimeServer(
    hostRuntimeProvider = {
      OpenCrayHostRuntime.createForTest(
        stateStore = AppShellStateStore(InMemoryAppShellKeyValueStore()),
        chatSessionStore = ChatSessionLocalStore(
          temporaryFolder.newFolder("chat-store-${System.nanoTime()}"),
        ),
        settingsFacade = NoOpSettingsFacade,
        networkSearchConfigFacade = networkSearchConfigFacade,
        llmConfigFacade = llmConfigFacade,
        skillsFacade = skillsFacade,
        directTaskRuntimeFactory = directTaskRuntimeFactory,
        workspaceRootProvider = workspaceRootProvider,
        workspaceEntryOpener = workspaceEntryOpener,
        workspaceSnapshotProvider = workspaceSnapshotProvider,
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
          composerRejectedPlaceholder = "Tell OpenCray differently",
          agentThinking = "Thinking",
          agentCancelled = "Cancelled",
          agentMissingLlm = "Missing LLM",
          agentEmptyAnswer = "The model returned an empty answer.",
          agentFailed = { detail -> "Failed: $detail" },
        ),
      )
    },
    requestedPort = 0,
    shutdownExecutorOnClose = true,
  )

  private fun request(
    server: OpenCrayLocalRuntimeServer,
    method: String,
    path: String,
    body: String? = null,
  ): HttpResponse {
    val connection = URL("http://127.0.0.1:${server.listeningPort}$path")
      .openConnection() as HttpURLConnection
    connection.requestMethod = method
    connection.setRequestProperty("Accept", "application/json")
    if (body != null) {
      connection.doOutput = true
      connection.setRequestProperty("Content-Type", "application/json")
      connection.outputStream.use { output ->
        output.write(body.toByteArray(Charsets.UTF_8))
      }
    }
    val statusCode = connection.responseCode
    val bodyText = (if (statusCode >= 400) {
      connection.errorStream
    } else {
      connection.inputStream
    })?.bufferedReader()?.use { reader ->
      reader.readText()
    }.orEmpty()
    connection.disconnect()
    return HttpResponse(statusCode = statusCode, body = bodyText)
  }

  private data class HttpResponse(
    val statusCode: Int,
    val body: String,
  )

  private fun hostRuntimeStrings(): HostRuntimeStrings = HostRuntimeStrings(
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
    composerRejectedPlaceholder = "Tell OpenCray differently",
    agentThinking = "Thinking",
    agentCancelled = "Cancelled",
    agentMissingLlm = "Missing LLM",
    agentEmptyAnswer = "The model returned an empty answer.",
    agentFailed = { detail -> "Failed: $detail" },
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

  private class RecordingSkillsFacade : SkillsFacade {
    var lastLoadedQuery: String? = null
    var lastInstalledSourceRef: String? = null
    var snapshot: SkillsSnapshot = SkillsSnapshot(
      installedSkills = emptyList(),
      installSources = emptyList(),
      suggestedSkills = emptyList(),
    )
    var installResult: SkillInstallRequestResult = SkillInstallRequestResult(
      errorMessage = "Not configured.",
    )

    override fun loadSnapshot(query: String): SkillsSnapshot {
      lastLoadedQuery = query
      return snapshot
    }

    override fun setSkillEnabled(skillId: String, enabled: Boolean): Boolean = true

    override fun installSkillSource(sourceRef: String): SkillInstallRequestResult {
      lastInstalledSourceRef = sourceRef
      return installResult
    }

    override fun installSuggestedSkill(skillId: String): Boolean =
      installSkillSource(skillId).succeeded

    override fun deleteInstalledSkill(skillId: String): Boolean = true

    override fun refresh() = Unit

    override fun loadInstructions(skillId: String): SkillInstructionsSnapshot? = null

    override fun enabledSkillRoots(): List<java.io.File> = emptyList()

    override fun activateInstallSource(sourceId: String): String = sourceId
  }

  private class RecordingDirectTaskRuntimeFactory(
    private val status: ExecutionStatus,
    private val stdout: String = "",
    private val errorMessage: String? = null,
  ) : AgentSessionTaskRuntimeFactory {
    val submittedTasks = mutableListOf<AgentTask>()
    var lastTask: AgentTask? = null

    override fun create(
      sessionId: String,
      eventSink: com.opencray.runtime.OpenCrayAgentRuntimeEventSink,
    ): SessionTaskRuntime = SessionTaskRuntime { task, _ ->
      submittedTasks += task
      lastTask = task
      ExecutionResult(
        taskId = task.id,
        status = status,
        stdout = if (status == ExecutionStatus.SUCCESS) stdout else "",
        stderr = if (status == ExecutionStatus.SUCCESS) "" else (errorMessage ?: stdout),
        errorMessage = if (status == ExecutionStatus.SUCCESS) null else errorMessage,
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_000L,
      )
    }
  }

  private class RecordingLlmConfigFacade : LlmConfigFacade {
    var lastValidationRequest: ValidateLlmConfigRequest? = null
    var lastSavedCustomRequest: SaveCustomLlmProviderRequest? = null

    override fun load(): LlmConfigSnapshot = EmptyLlmConfigFacade.load()

    override fun save(request: SaveLlmConfigRequest): LlmConfigSnapshot =
      throw UnsupportedOperationException("save is not used in this test")

    override fun saveCustomProvider(request: SaveCustomLlmProviderRequest): LlmConfigSnapshot {
      lastSavedCustomRequest = request
      return EmptyLlmConfigFacade.load().copy(
        providerId = "custom",
        selectedProviderOptionId = "saved-custom",
        protocol = request.protocol,
        providerName = request.providerName,
        providerNotes = request.providerNotes,
        baseUrl = request.baseUrl,
        apiKey = request.apiKey,
        model = request.model,
        reasoningEffort = request.reasoningEffort,
        systemPrompt = request.systemPrompt,
      )
    }

    override fun validate(request: ValidateLlmConfigRequest): LlmValidationResult {
      lastValidationRequest = request
      return LlmValidationResult(
        isSuccess = true,
        message = "Validation succeeded.",
      )
    }
  }

  private class NoOpRuntimeManager : AgentSessionRuntimeManager {
    override fun forSession(sessionId: String): AgentSessionHandle = NoOpSessionHandle(sessionId)

    override fun observe(listener: AgentSessionRuntimeListener): () -> Unit = {}

    override fun release(sessionId: String) = Unit

    override fun releaseIdleSessions() = Unit
  }

  private class RecordingRuntimeManager : AgentSessionRuntimeManager {
    val listeners = mutableListOf<AgentSessionRuntimeListener>()
    var handle: RecordingSessionHandle? = null

    override fun forSession(sessionId: String): AgentSessionHandle =
      handle ?: error("No recording handle configured for $sessionId.")

    override fun observe(listener: AgentSessionRuntimeListener): () -> Unit {
      listeners += listener
      return {
        listeners -= listener
      }
    }

    override fun release(sessionId: String) = Unit

    override fun releaseIdleSessions() = Unit

    fun emitTaskFinished(
      sessionId: String,
      task: AgentTask,
      result: ExecutionResult,
    ) {
      handle?.recordResult(task = task, result = result)
      listeners.forEach { listener ->
        listener.onTaskFinished(sessionId, task, result)
      }
    }

    fun emitRunEvent(
      sessionId: String,
      task: AgentTask,
      event: com.opencray.runtime.OpenCrayAgentRunEvent,
    ) {
      handle?.recordEvent(event)
      listeners.forEach { listener ->
        listener.onRunEvent(sessionId, task, event)
      }
    }
  }

  private class RecordingSessionHandle(
    override val sessionId: String,
    private val resumeResult: Boolean,
    private val retryResult: Boolean = false,
  ) : AgentSessionHandle {
    var queuedToolCompletion: QueuedToolCompletion? = null
    val queuedToolCompletions = mutableListOf<QueuedToolCompletion>()
    val cancelledTaskIds = mutableListOf<String>()
    val retriedTaskIds = mutableListOf<String>()
    val resumedTaskIds = mutableListOf<String>()
    val submittedTasks = mutableListOf<AgentTask>()
    val submissions = mutableListOf<AgentRunSubmission>()
    private val runSnapshotsById = linkedMapOf<String, AgentRunSnapshot>()
    private var nextTaskIndex: Int = 1

    override fun submitPrompt(
      userText: String,
      pendingMessageId: String,
      visibleThroughMessageId: String,
      policyDecision: PolicyDecision,
      metadata: Map<String, String>,
    ): AgentRunSubmission {
      val index = nextTaskIndex++
      val task = AgentTask(
        id = "task-$index",
        type = AgentTaskType.PROMPT,
        input = userText,
        policyDecision = policyDecision,
        metadata = metadata + mapOf(
          AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID to "run-$index",
          AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID to pendingMessageId,
          AppAgentSessionTaskRuntimeFactory.METADATA_VISIBLE_THROUGH_MESSAGE_ID to visibleThroughMessageId,
        ),
        createdAtEpochMs = 1_000L,
      )
      val submission = AgentRunSubmission(
        sessionId = sessionId,
        runId = "run-$index",
        taskId = task.id,
        acceptedAtEpochMs = 1_000L,
      )
      submittedTasks += task
      submissions += submission
      runSnapshotsById[submission.runId] = AgentRunSnapshot(
        sessionId = sessionId,
        runId = submission.runId,
        taskId = task.id,
        acceptedAtEpochMs = 1_000L,
        updatedAtEpochMs = 1_000L,
        lifecycleState = null,
        taskState = null,
        pendingMessageId = pendingMessageId,
      )
      return submission
    }

    override fun submitTask(task: AgentTask): AgentRunSubmission {
      val runId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID]
        ?.takeIf(String::isNotBlank)
        ?: "run-${nextTaskIndex++}"
      val submission = AgentRunSubmission(
        sessionId = sessionId,
        runId = runId,
        taskId = task.id,
        acceptedAtEpochMs = task.createdAtEpochMs,
      )
      submittedTasks += task
      submissions += submission
      runSnapshotsById[submission.runId] = AgentRunSnapshot(
        sessionId = sessionId,
        runId = submission.runId,
        taskId = task.id,
        acceptedAtEpochMs = task.createdAtEpochMs,
        updatedAtEpochMs = task.createdAtEpochMs,
        lifecycleState = null,
        taskState = null,
        pendingMessageId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID],
      )
      val completion = queuedToolCompletion?.also {
        queuedToolCompletion = null
      } ?: if (queuedToolCompletions.isNotEmpty()) {
        queuedToolCompletions.removeAt(0)
      } else {
        null
      }
      completion?.also {
        completeQueuedToolCall(
          task = task,
          submission = submission,
          completion = it,
        )
      }
      return submission
    }

    override fun ensureProcessing() = Unit

    override fun requestCancel(taskId: String): Boolean {
      cancelledTaskIds += taskId
      return true
    }

    override fun requestRetry(taskId: String): Boolean {
      retriedTaskIds += taskId
      return retryResult
    }

    override fun requestResumeTask(taskId: String): Boolean {
      resumedTaskIds += taskId
      return resumeResult
    }

    fun recordResult(
      task: AgentTask,
      result: ExecutionResult,
    ) {
      val runId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID].orEmpty()
      val existing = runSnapshotsById[runId] ?: return
      runSnapshotsById[runId] = existing.copy(
        updatedAtEpochMs = result.finishedAtEpochMs,
        executionStatus = result.status,
        errorCode = result.errorCode,
        errorMessage = result.errorMessage,
        responseFormat = result.metadata["responseFormat"],
        resultMetadata = result.metadata,
      )
    }

    fun recordEvent(event: com.opencray.runtime.OpenCrayAgentRunEvent) {
      val existing = runSnapshotsById[event.runId] ?: return
      runSnapshotsById[event.runId] = existing.copy(
        updatedAtEpochMs = event.emittedAtEpochMs,
        lastEvent = event,
      )
    }

    private fun completeQueuedToolCall(
      task: AgentTask,
      submission: AgentRunSubmission,
      completion: QueuedToolCompletion,
    ) {
      val resolvedToolName = completion.toolName
        ?: TOOL_NAME_REGEX.find(task.input)?.groupValues?.getOrNull(1)
        ?: "UnknownTool"
      val toolResult = AgentToolResult(
        toolName = resolvedToolName,
        status = when (completion.status) {
          ExecutionStatus.SUCCESS -> AgentToolResultStatus.SUCCESS
          ExecutionStatus.DENIED -> AgentToolResultStatus.DENIED
          ExecutionStatus.CANCELLED -> AgentToolResultStatus.CANCELLED
          ExecutionStatus.TIMEOUT -> AgentToolResultStatus.TIMEOUT
          ExecutionStatus.FAILED -> AgentToolResultStatus.FAILED
        },
        content = completion.content,
        errorCode = completion.errorCode,
        errorMessage = completion.errorMessage,
        metadata = completion.metadata,
      )
      recordEvent(
        OpenCrayToolResultEvent(
          runId = submission.runId,
          taskId = task.id,
          turn = 0,
          call = AgentToolCall(toolName = resolvedToolName),
          result = toolResult,
          emittedAtEpochMs = task.createdAtEpochMs + 1L,
        ),
      )
      recordResult(
        task = task,
        result = ExecutionResult(
          taskId = task.id,
          status = completion.status,
          stdout = if (completion.status == ExecutionStatus.SUCCESS) completion.content else "",
          errorCode = completion.errorCode,
          errorMessage = completion.errorMessage,
          startedAtEpochMs = task.createdAtEpochMs,
          finishedAtEpochMs = task.createdAtEpochMs + 1L,
        ),
      )
    }

    override fun listRuns(): List<AgentRunSnapshot> = runSnapshotsById.values.toList()

    override fun findRun(runId: String): AgentRunSnapshot? = runSnapshotsById[runId]

    override fun waitForRun(runId: String, timeoutMs: Long): AgentRunSnapshot? = findRun(runId)

    override fun requestCancelForPendingMessageIds(pendingMessageIds: Set<String>): Int = 0

    override fun resume(): SessionLifecycleState = SessionLifecycleState.IDLE

    override fun snapshot(): SessionQueueSnapshot = SessionQueueSnapshot(
      sessionId = sessionId,
      agentId = "test-agent",
      updatedAtEpochMs = 0L,
      tasks = emptyList(),
    )

    override fun hasPendingWork(): Boolean = false
  }

  private class NoOpSessionHandle(
    override val sessionId: String,
  ) : AgentSessionHandle {
    override fun submitPrompt(
      userText: String,
      pendingMessageId: String,
      visibleThroughMessageId: String,
      policyDecision: PolicyDecision,
      metadata: Map<String, String>,
    ): AgentRunSubmission = throw UnsupportedOperationException("submitPrompt is not used in this test")

    override fun ensureProcessing() = Unit

    override fun requestCancel(taskId: String): Boolean = false

    override fun requestRetry(taskId: String): Boolean = false

    override fun requestResumeTask(taskId: String): Boolean = false

    override fun listRuns(): List<AgentRunSnapshot> = emptyList()

    override fun findRun(runId: String): AgentRunSnapshot? = null

    override fun waitForRun(runId: String, timeoutMs: Long): AgentRunSnapshot? = null

    override fun requestCancelForPendingMessageIds(pendingMessageIds: Set<String>): Int = 0

    override fun resume(): SessionLifecycleState = SessionLifecycleState.IDLE

    override fun snapshot(): SessionQueueSnapshot = SessionQueueSnapshot(
      sessionId = sessionId,
      agentId = "test-agent",
      updatedAtEpochMs = 0L,
      tasks = emptyList(),
    )

    override fun hasPendingWork(): Boolean = false
  }

  private data class QueuedToolCompletion(
    val toolName: String? = null,
    val content: String,
    val metadata: Map<String, String> = emptyMap(),
    val status: ExecutionStatus = ExecutionStatus.SUCCESS,
    val errorCode: String? = null,
    val errorMessage: String? = null,
  )
}

private val TOOL_NAME_REGEX: Regex = Regex("""\"tool_name\"\s*:\s*\"([^\"]+)\"""")
