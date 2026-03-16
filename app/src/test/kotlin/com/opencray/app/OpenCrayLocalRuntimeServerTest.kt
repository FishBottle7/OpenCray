package com.opencray.app

import com.opencray.app.facade.llm.EmptyLlmConfigFacade
import com.opencray.app.facade.llm.LlmConfigFacade
import com.opencray.app.facade.llm.LlmConfigSnapshot
import com.opencray.app.facade.llm.LlmValidationResult
import com.opencray.app.facade.llm.SaveCustomLlmProviderRequest
import com.opencray.app.facade.llm.SaveLlmConfigRequest
import com.opencray.app.facade.llm.ValidateLlmConfigRequest
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
import com.opencray.core.orchestrator.SessionLifecycleState
import com.opencray.core.orchestrator.SessionQueueSnapshot
import com.opencray.runtime.OpenCrayLifecycleEvent
import com.opencray.runtime.OpenCrayMemoryRetrievalEvent
import com.opencray.runtime.OpenCrayRunLifecyclePhase
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Path
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

  private fun localRuntimeServer(
    llmConfigFacade: LlmConfigFacade = EmptyLlmConfigFacade,
    workspaceRootProvider: (() -> Path)? = null,
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
        llmConfigFacade = llmConfigFacade,
        workspaceRootProvider = workspaceRootProvider,
        workspaceSnapshotProvider = workspaceSnapshotProvider,
        sessionRuntimeManager = NoOpRuntimeManager(),
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
  ) : AgentSessionHandle {
    val cancelledTaskIds = mutableListOf<String>()
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

    override fun ensureProcessing() = Unit

    override fun requestCancel(taskId: String): Boolean {
      cancelledTaskIds += taskId
      return true
    }

    override fun requestRetry(taskId: String): Boolean {
      return false
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
}
