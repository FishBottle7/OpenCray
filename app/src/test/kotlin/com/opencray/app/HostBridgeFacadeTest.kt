package com.opencray.app

import com.opencray.app.facade.llm.LlmValidationResult
import com.opencray.app.facade.personalization.SavePersonalizationConfigRequest
import com.opencray.app.facade.search.LocalNetworkSearchConfigFacade
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.persistence.model.MemoryRecord
import com.opencray.runtime.OpenCrayMemoryRetrievalEvent
import com.opencray.runtime.OpenCrayMemoryWriteEvent
import com.opencray.runtime.memory.MemoryRecordExtensionKeys
import com.opencray.runtime.memory.MemoryPreferenceKeys
import com.opencray.runtime.memory.MemorySoulExtensionKeys
import com.opencray.runtime.soul.InteractionPreferenceState
import com.opencray.runtime.soul.PreferenceAxisState
import com.opencray.runtime.soul.PreferredAddressState
import com.opencray.runtime.soul.PreferredAddressStyle
import com.opencray.runtime.soul.RelationshipState
import com.opencray.runtime.soul.SoulMemoryExtensionKeys
import com.opencray.runtime.soul.SoulMemoryObjectTypes
import com.opencray.runtime.soul.SoulProfileExtensionKeys
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostBridgeFacadeTest : HostRuntimeTestBase() {
  @Test
  fun validateLlmConfigReturnsFacadePayloadForFlutterBridge() {
    val hostRuntime = hostRuntime(
      chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-validation")),
      runtimeManager = RecordingRuntimeManager(),
      llmConfigFacade = RecordingLlmConfigFacade(
        validationResult = LlmValidationResult(
          isSuccess = true,
          message = "Connection verified for gpt-4o-mini.",
          agentCapability = LlmAgentCapabilitySnapshot(
            routeFingerprint = llmRouteFingerprint(
              protocol = LlmProviderProtocols.OPENAI,
              baseUrl = "https://api.openai.com/v1",
              model = "gpt-4o-mini",
            ),
            verifiedAtEpochMs = 1234L,
            visionInputSupported = true,
            pdfInputSupported = true,
            nativeToolCallingAvailable = true,
            toolChoiceSupported = true,
            parallelToolCallsSupported = true,
            strictToolSchemaSupported = true,
          ),
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
    val capability = payload["agentCapability"] as Map<*, *>
    assertEquals(true, capability["visionInputSupported"])
    assertEquals(true, capability["nativeToolCallingAvailable"])
    assertEquals(true, capability["strictToolSchemaSupported"])
  }

  @Test
  fun validateLlmConfigDoesNotBlockSettingsOverviewLoads() {
    val validationStarted = CountDownLatch(1)
    val allowValidationToFinish = CountDownLatch(1)
    val facade = RecordingLlmConfigFacade(
      onValidate = {
        validationStarted.countDown()
        assertTrue(allowValidationToFinish.await(3, TimeUnit.SECONDS))
      },
    )
    val hostRuntime = hostRuntime(
      chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-validation-lock")),
      runtimeManager = RecordingRuntimeManager(),
      llmConfigFacade = facade,
    )

    val validationThread = Thread {
      hostRuntime.validateLlmConfig(
        providerId = "custom",
        protocol = LlmProviderProtocols.ANTHROPIC,
        baseUrl = "https://api.anthropic.com",
        apiKey = "secret",
        model = "claude-3-7-sonnet",
        reasoningEffort = "medium",
      )
    }
    validationThread.start()
    assertTrue(validationStarted.await(1, TimeUnit.SECONDS))

    val overviewLoadedAtStartNs = System.nanoTime()
    val overview = hostRuntime.loadSettingsOverview()
    val overviewLoadDurationMs = TimeUnit.NANOSECONDS.toMillis(
      System.nanoTime() - overviewLoadedAtStartNs,
    )

    allowValidationToFinish.countDown()
    validationThread.join(3_000L)

    assertNotNull(overview)
    assertTrue(
      "Expected settings overview load to stay responsive during validation, but it took ${overviewLoadDurationMs}ms.",
      overviewLoadDurationMs < 500L,
    )
  }

  @Test
  fun saveCustomLlmProviderReturnsFacadePayloadForFlutterBridge() {
    val facade = RecordingLlmConfigFacade()
    val hostRuntime = hostRuntime(
      chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-save-custom-provider")),
      runtimeManager = RecordingRuntimeManager(),
      llmConfigFacade = facade,
    )

    val payload = hostRuntime.saveCustomLlmProvider(
      selectedProviderOptionId = "custom",
      protocol = LlmProviderProtocols.ANTHROPIC,
      providerName = "Acme",
      providerNotes = "Regional fallback",
      baseUrl = "https://api.acme.example/v1",
      apiKey = "secret",
      model = "claude-3-7-sonnet",
      reasoningEffort = "high",
      systemPrompt = "Be concise.",
    )

    assertEquals("custom-saved", payload["selectedProviderOptionId"])
    assertEquals("custom", payload["providerId"])
    assertEquals("Regional fallback", payload["providerNotes"])
    assertEquals("Acme", facade.lastSavedCustomRequest?.providerName)
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
  fun networkSearchConfigRoundTripsForFlutterBridge() {
    val facade = LocalNetworkSearchConfigFacade.create(
      WebSearchSettingsStore(InMemoryWebSearchSettingsKeyValueStore()),
    )
    val hostRuntime = hostRuntime(
      chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-network-search")),
      runtimeManager = RecordingRuntimeManager(),
      networkSearchConfigFacade = facade,
    )

    val initialPayload = hostRuntime.loadNetworkSearchConfig()
    assertEquals("Network & Search", initialPayload["title"])
    assertEquals(0, (initialPayload["slots"] as List<*>).size)

    val savedPayload = hostRuntime.saveNetworkSearchConfig(
      slots = listOf(
        mapOf(
          "id" to "slot-primary",
          "providerId" to "openai_web_search",
          "label" to "Primary OpenAI Search",
          "baseUrl" to "https://proxy.example.com/v1",
          "model" to "gpt-5-mini",
          "apiKey" to "openai-secret",
          "enabled" to true,
        ),
        mapOf(
          "id" to "slot-backup",
          "providerId" to "tavily",
          "label" to "Backup Tavily",
          "baseUrl" to "https://ignored.example.com",
          "model" to "ignored-model",
          "apiKey" to "",
          "enabled" to false,
        ),
      ),
    )

    val savedSlots = (savedPayload["slots"] as List<*>).map { it as Map<*, *> }
    assertEquals(2, savedSlots.size)
    assertEquals("openai_web_search", savedSlots[0]["providerId"])
    assertEquals("Primary OpenAI Search", savedSlots[0]["label"])
    assertEquals("https://proxy.example.com/v1", savedSlots[0]["baseUrl"])
    assertEquals("gpt-5-mini", savedSlots[0]["model"])
    assertEquals(true, savedSlots[0]["enabled"])
    assertEquals("tavily", savedSlots[1]["providerId"])
    assertEquals("", savedSlots[1]["baseUrl"])
    assertEquals("", savedSlots[1]["model"])
    assertEquals(false, savedSlots[1]["enabled"])
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

  @Test
  fun loadMemoryDebugSnapshotReturnsStructuredStoreRecords() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-memory-debug"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val personalizationStore = PersonalizationLocalStore(
      temporaryFolder.newFolder("personalization-memory-debug"),
    )
    personalizationStore.upsertMemoryRecord(
      MemoryRecord(
        id = "memory-old",
        content = "Old workspace preference.",
        createdAtEpochMs = 1_000L,
        updatedAtEpochMs = 1_000L,
        tags = listOf("kind:user_preference", "scope:workspace"),
        extensions = mapOf(
          MemoryRecordExtensionKeys.KIND to "user_preference",
          MemoryRecordExtensionKeys.SCOPE to "workspace",
          MemoryRecordExtensionKeys.STATUS to "active",
          MemoryRecordExtensionKeys.WORKSPACE_ID to "workspace=unavailable",
          MemoryRecordExtensionKeys.SOURCE_SESSION_ID to "session-old",
          MemoryRecordExtensionKeys.TTL_MS to "1000",
          MemoryRecordExtensionKeys.LAST_CONFIRMED_AT_EPOCH_MS to "1000",
        ),
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
          MemoryRecordExtensionKeys.SOURCE to "user_input",
          MemoryRecordExtensionKeys.SOURCE_SESSION_ID to sessionId,
          MemoryRecordExtensionKeys.PREFERENCE_KEY to MemoryPreferenceKeys.AGENT_DISPLAY_NAME,
          MemoryRecordExtensionKeys.PREFERENCE_VALUE to "Xiao Bai",
          MemoryRecordExtensionKeys.PREFERENCE_TEMPORALITY to "durable",
          MemorySoulExtensionKeys.DISPLAY_NAME to "Xiao Bai",
        ),
      ),
    )

    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = RecordingRuntimeManager(),
      personalizationLocalStore = personalizationStore,
    )

    val payload = hostRuntime.loadMemoryDebugSnapshot()

    assertEquals(sessionId, payload["sessionId"])
    val records = payload["records"] as List<*>
    val firstRecord = records[0] as Map<*, *>
    val secondRecord = records[1] as Map<*, *>
    assertEquals("memory-user", firstRecord["id"])
    assertEquals("agent_display_name", firstRecord["preferenceKey"])
    assertEquals("Xiao Bai", firstRecord["preferenceValue"])
    assertEquals(false, firstRecord["isExpired"])
    assertEquals("memory-old", secondRecord["id"])
    assertEquals(true, secondRecord["isExpired"])
  }

  @Test
  fun searchMemoryDebugReturnsProjectedMatchesForVisibleRecords() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-memory-search"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val personalizationStore = PersonalizationLocalStore(
      temporaryFolder.newFolder("personalization-memory-search"),
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

    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = RecordingRuntimeManager(),
      personalizationLocalStore = personalizationStore,
    )

    val payload = hostRuntime.searchMemoryDebug(query = "xiao bai")

    assertEquals(sessionId, payload["sessionId"])
    assertEquals("xiao bai", payload["query"])
    val queryTerms = payload["queryTerms"] as List<*>
    assertTrue(queryTerms.contains("xiao"))
    val results = payload["results"] as List<*>
    val firstResult = results.single() as Map<*, *>
    assertEquals("memory-user", firstResult["recordId"])
    assertEquals("user", firstResult["scope"])
    assertEquals("active", firstResult["status"])
    assertEquals(true, (firstResult["path"] as String).isNotBlank())
    assertEquals(true, (firstResult["snippet"] as String).contains("Xiao Bai"))
  }

  @Test
  fun getMemoryDebugSliceReturnsProjectedSnippetForRequestedPath() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-memory-slice"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val personalizationStore = PersonalizationLocalStore(
      temporaryFolder.newFolder("personalization-memory-slice"),
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

    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = RecordingRuntimeManager(),
      personalizationLocalStore = personalizationStore,
    )

    val searchPayload = hostRuntime.searchMemoryDebug(query = "xiao")
    val path = ((searchPayload["results"] as List<*>).single() as Map<*, *>)["path"] as String
    val startLine = ((searchPayload["results"] as List<*>).single() as Map<*, *>)["startLine"] as Int
    val endLine = ((searchPayload["results"] as List<*>).single() as Map<*, *>)["endLine"] as Int

    val payload = hostRuntime.getMemoryDebugSlice(
      path = path,
      fromLine = startLine,
      lines = endLine - startLine + 1,
    )

    assertEquals(sessionId, payload["sessionId"])
    assertEquals(path, payload["path"])
    assertEquals(startLine, payload["startLine"])
    assertEquals(endLine, payload["endLine"])
    val recordIds = payload["recordIds"] as List<*>
    assertEquals(listOf("memory-user"), recordIds)
    assertEquals(true, (payload["text"] as String).contains("content: Call the agent Xiao Bai"))
  }

  @Test
  fun loadMemoryDebugLinksSnapshotReturnsSourceRecallRetrievalAndMaintenanceLinks() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-memory-links"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val personalizationStore = PersonalizationLocalStore(
      temporaryFolder.newFolder("personalization-memory-links"),
    )
    val runtimeManager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(sessionId = sessionId)
    runtimeManager.putHandle(handle)
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = runtimeManager,
      personalizationLocalStore = personalizationStore,
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
          MemoryRecordExtensionKeys.PREFERENCE_TEMPORALITY to "durable",
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

    val payload = hostRuntime.loadMemoryDebugLinksSnapshot()

    assertEquals(sessionId, payload["sessionId"])
    val records = payload["records"] as List<*>
    val userLinks = records
      .map { entry -> entry as Map<*, *> }
      .first { entry -> entry["recordId"] == "memory-user" }
    assertEquals(sessionId, userLinks["sourceSessionId"])
    assertEquals(sourceTask.id, userLinks["sourceTaskId"])
    val sourceRun = userLinks["sourceRun"] as Map<*, *>
    assertEquals(sourceSubmission.runId, sourceRun["runId"])
    val promptRecall = (userLinks["promptRecalls"] as List<*>)
      .single() as Map<*, *>
    assertEquals(420, promptRecall["score"])
    val retrieval = (userLinks["toolRetrievals"] as List<*>)
      .single() as Map<*, *>
    assertEquals("memory_search", retrieval["toolName"])
    val maintenanceActions = (userLinks["maintenanceActions"] as List<*>)
      .map { entry -> entry as Map<*, *> }
    assertTrue(maintenanceActions.any { entry -> entry["action"] == "written" })
    assertTrue(maintenanceActions.any { entry -> entry["action"] == "flush_written" })
  }

  @Test
  fun applyMemoryDebugActionSuppressesRecordAndAddsMaintenanceLink() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-memory-debug-action"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val personalizationStore = PersonalizationLocalStore(
      temporaryFolder.newFolder("personalization-memory-debug-action"),
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
          MemoryRecordExtensionKeys.SOURCE to "user_input",
          MemoryRecordExtensionKeys.SOURCE_SESSION_ID to sessionId,
          MemoryRecordExtensionKeys.PREFERENCE_KEY to MemoryPreferenceKeys.AGENT_DISPLAY_NAME,
          MemoryRecordExtensionKeys.PREFERENCE_VALUE to "Xiao Bai",
          MemorySoulExtensionKeys.DISPLAY_NAME to "Xiao Bai",
        ),
      ),
    )
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = NoOpRuntimeManager(),
      personalizationLocalStore = personalizationStore,
    )

    val payload = hostRuntime.applyMemoryDebugAction(
      recordId = "memory-user",
      actionId = "suppress",
    )

    assertEquals("memory-user", payload["recordId"])
    assertEquals("suppress", payload["action"])
    assertEquals(true, payload["applied"])
    val updatedRecord = personalizationStore.listMemoryRecords()
      .single { record -> record.id == "memory-user" }
    assertEquals("resolved", updatedRecord.extensions[MemoryRecordExtensionKeys.STATUS])
    assertEquals(
      "operator_suppressed",
      updatedRecord.extensions[MemoryRecordExtensionKeys.RESOLUTION_REASON],
    )

    val runtimePayload = hostRuntime.loadChatRuntimeSnapshot()
    val runtimeEvents = runtimePayload["events"] as List<*>
    assertTrue(runtimeEvents.none { event ->
      (event as? Map<*, *>)?.get("kind") == "memory_write"
    })
    val runtimeActivity = (hostRuntime.loadChatSnapshot()["runtimeActivity"] as Map<*, *>)["events"] as List<*>
    assertTrue(runtimeActivity.none { event ->
      (event as? Map<*, *>)?.get("kind") == "memory_write"
    })

    val reloadedHostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = NoOpRuntimeManager(),
      personalizationLocalStore = personalizationStore,
    )
    val linksPayload = reloadedHostRuntime.loadMemoryDebugLinksSnapshot()
    val links = (linksPayload["records"] as List<*>)
      .map { entry -> entry as Map<*, *> }
      .first { entry -> entry["recordId"] == "memory-user" }
    val maintenanceActions = (links["maintenanceActions"] as List<*>)
      .map { entry -> entry as Map<*, *> }
    assertTrue(maintenanceActions.any { entry -> entry["action"] == "suppressed" })
  }

  @Test
  fun applyMemoryDebugActionReaffirmsSuppressedRecordAndAddsMaintenanceLink() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-memory-debug-reaffirm"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val personalizationStore = PersonalizationLocalStore(
      temporaryFolder.newFolder("personalization-memory-debug-reaffirm"),
    )
    personalizationStore.upsertMemoryRecord(
      MemoryRecord(
        id = "memory-user",
        content = "Call the agent Xiao Bai.",
        createdAtEpochMs = 2_000L,
        updatedAtEpochMs = 2_200L,
        tags = listOf("kind:user_preference", "scope:user", "status:resolved"),
        extensions = mapOf(
          MemoryRecordExtensionKeys.KIND to "user_preference",
          MemoryRecordExtensionKeys.SCOPE to "user",
          MemoryRecordExtensionKeys.STATUS to "resolved",
          MemoryRecordExtensionKeys.SOURCE to "user_input",
          MemoryRecordExtensionKeys.SOURCE_SESSION_ID to sessionId,
          MemoryRecordExtensionKeys.PREFERENCE_KEY to MemoryPreferenceKeys.AGENT_DISPLAY_NAME,
          MemoryRecordExtensionKeys.PREFERENCE_VALUE to "Xiao Bai",
          MemoryRecordExtensionKeys.RESOLUTION_REASON to "operator_suppressed",
          MemoryRecordExtensionKeys.RESOLVED_AT_EPOCH_MS to "2200",
          MemorySoulExtensionKeys.DISPLAY_NAME to "Xiao Bai",
        ),
      ),
    )
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = NoOpRuntimeManager(),
      personalizationLocalStore = personalizationStore,
    )

    val payload = hostRuntime.applyMemoryDebugAction(
      recordId = "memory-user",
      actionId = "reaffirm",
    )

    assertEquals("memory-user", payload["recordId"])
    assertEquals("reaffirm", payload["action"])
    assertEquals(true, payload["applied"])
    val updatedRecord = personalizationStore.listMemoryRecords()
      .single { record -> record.id == "memory-user" }
    assertEquals("active", updatedRecord.extensions[MemoryRecordExtensionKeys.STATUS])
    assertEquals(null, updatedRecord.extensions[MemoryRecordExtensionKeys.RESOLUTION_REASON])

    val runtimePayload = hostRuntime.loadChatRuntimeSnapshot()
    val runtimeEvents = runtimePayload["events"] as List<*>
    assertTrue(runtimeEvents.none { event ->
      (event as? Map<*, *>)?.get("kind") == "memory_write"
    })
    val runtimeActivity = (hostRuntime.loadChatSnapshot()["runtimeActivity"] as Map<*, *>)["events"] as List<*>
    assertTrue(runtimeActivity.none { event ->
      (event as? Map<*, *>)?.get("kind") == "memory_write"
    })

    val reloadedHostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = NoOpRuntimeManager(),
      personalizationLocalStore = personalizationStore,
    )
    val linksPayload = reloadedHostRuntime.loadMemoryDebugLinksSnapshot()
    val links = (linksPayload["records"] as List<*>)
      .map { entry -> entry as Map<*, *> }
      .first { entry -> entry["recordId"] == "memory-user" }
    val maintenanceActions = (links["maintenanceActions"] as List<*>)
      .map { entry -> entry as Map<*, *> }
    assertTrue(maintenanceActions.any { entry -> entry["action"] == "reaffirmed" })
  }

  @Test
  fun loadSoulDebugSnapshotReturnsStoredEffectiveSoulAndFieldSources() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-soul-debug"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val personalizationStore = PersonalizationLocalStore(
      temporaryFolder.newFolder("personalization-soul-debug"),
    )
    val workspaceRoot = temporaryFolder.newFolder("workspace-soul-debug").toPath()
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
        id = "memory-style",
        content = "Keep the tone warmer.",
        createdAtEpochMs = 2_200L,
        updatedAtEpochMs = 2_250L,
        tags = listOf("kind:user_preference", "scope:session"),
        extensions = mapOf(
          MemoryRecordExtensionKeys.KIND to "user_preference",
          MemoryRecordExtensionKeys.SCOPE to "session",
          MemoryRecordExtensionKeys.STATUS to "active",
          MemoryRecordExtensionKeys.SOURCE_SESSION_ID to sessionId,
          MemoryRecordExtensionKeys.PREFERENCE_KEY to MemoryPreferenceKeys.AGENT_STYLE_PROFILE,
          MemoryRecordExtensionKeys.PREFERENCE_VALUE to "warm",
          MemorySoulExtensionKeys.TONE to "warm",
          MemorySoulExtensionKeys.VOICE to "warm and gentle",
          MemorySoulExtensionKeys.USER_RELATIONSHIP_STYLE to "supportive",
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

    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = RecordingRuntimeManager(),
      personalizationLocalStore = personalizationStore,
      workspaceRootProvider = { workspaceRoot },
    )

    val payload = hostRuntime.loadSoulDebugSnapshot()

    val storedSoul = payload["storedSoul"] as Map<*, *>
    val effectiveSoul = payload["effectiveSoul"] as Map<*, *>
    val interactionPreferenceDebug = payload["interactionPreferenceDebug"] as Map<*, *>
    val relationshipStateDebug = payload["relationshipStateDebug"] as Map<*, *>
    val fieldSources = payload["fieldSources"] as List<*>

    assertEquals("STEADY", storedSoul["presetName"])
    assertEquals("Xiao Bai", effectiveSoul["displayName"])
    assertEquals("warm", effectiveSoul["tone"])
    assertEquals("warm and gentle", effectiveSoul["voice"])
    assertEquals("1", effectiveSoul["warmthPreferenceOffset"])
    assertEquals("-1", effectiveSoul["formalityPreferenceOffset"])
    assertEquals("1", effectiveSoul["initiativePreferenceOffset"])
    assertEquals("1", effectiveSoul["playfulnessPreferenceOffset"])
    assertEquals("1", effectiveSoul["reassurancePreferenceOffset"])
    assertEquals("true", effectiveSoul["supportiveReassuranceAllowed"])
    assertEquals("true", effectiveSoul["proactiveRelationalCheckInAllowed"])
    assertEquals("true", effectiveSoul["lightPlayfulnessAllowed"])
    assertEquals("true", effectiveSoul["playfulTeasingAllowed"])
    assertEquals("user", interactionPreferenceDebug["scope"])
    assertEquals("A-Cheng", interactionPreferenceDebug["preferredNaming"])
    assertEquals("friendly", interactionPreferenceDebug["preferredAddressStyle"])
    assertEquals("user", relationshipStateDebug["scope"])
    assertEquals("intimate", relationshipStateDebug["derivedAddressStyle"])
    assertEquals(false, relationshipStateDebug["recentNegativeGuardActive"])
    assertEquals(true, relationshipStateDebug["supportiveReassuranceAllowed"])
    assertEquals(true, relationshipStateDebug["proactiveRelationalCheckInAllowed"])
    assertEquals(true, relationshipStateDebug["lightPlayfulnessAllowed"])
    assertEquals(true, relationshipStateDebug["playfulTeasingAllowed"])
    assertEquals(true, relationshipStateDebug["highIntimacyBehaviorAllowed"])
    val mappedFieldSources = fieldSources.map { item -> item as Map<*, *> }
    fun fieldSource(field: String): Map<*, *> =
      mappedFieldSources.first { source -> source["field"] == field }

    val displayNameSource = fieldSource("displayName")
    val preferredNamingSource = fieldSource("preferredNaming")
    val warmthOffsetSource = fieldSource("warmthPreferenceOffset")
    val playfulnessOffsetSource = fieldSource("playfulnessPreferenceOffset")
    val reassuranceOffsetSource = fieldSource("reassurancePreferenceOffset")
    val supportiveReassuranceSource = fieldSource("supportiveReassuranceAllowed")
    val proactiveCheckInSource = fieldSource("proactiveRelationalCheckInAllowed")
    val lightPlayfulnessSource = fieldSource("lightPlayfulnessAllowed")
    val playfulTeasingSource = fieldSource("playfulTeasingAllowed")
    val highIntimacySource = fieldSource("highIntimacyBehaviorAllowed")
    assertEquals("memory_overlay", displayNameSource["sourceType"])
    assertEquals("memory-user", displayNameSource["recordId"])
    assertEquals("interaction_preference", preferredNamingSource["sourceType"])
    assertEquals("interaction-state", preferredNamingSource["recordId"])
    assertEquals("interaction_preference", warmthOffsetSource["sourceType"])
    assertEquals("interaction-state", warmthOffsetSource["recordId"])
    assertEquals("interaction_preference", playfulnessOffsetSource["sourceType"])
    assertEquals("interaction-state", playfulnessOffsetSource["recordId"])
    assertEquals("interaction_preference", reassuranceOffsetSource["sourceType"])
    assertEquals("interaction-state", reassuranceOffsetSource["recordId"])
    assertEquals("relationship_state", supportiveReassuranceSource["sourceType"])
    assertEquals("relationship-state", supportiveReassuranceSource["recordId"])
    assertEquals("relationship_state", proactiveCheckInSource["sourceType"])
    assertEquals("relationship-state", proactiveCheckInSource["recordId"])
    assertEquals("relationship_state", lightPlayfulnessSource["sourceType"])
    assertEquals("relationship-state", lightPlayfulnessSource["recordId"])
    assertEquals("relationship_state", playfulTeasingSource["sourceType"])
    assertEquals("relationship-state", playfulTeasingSource["recordId"])
    assertEquals("relationship_state", highIntimacySource["sourceType"])
    assertEquals("relationship-state", highIntimacySource["recordId"])
  }

  @Test
  fun loadSoulDebugSnapshotAttributesRelationshipDerivedAddressStyleOverBaseSoul() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-soul-debug-address"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val personalizationStore = PersonalizationLocalStore(
      temporaryFolder.newFolder("personalization-soul-debug-address"),
    )
    val workspaceRoot = temporaryFolder.newFolder("workspace-soul-debug-address").toPath()
    WorkspaceSoulProfileStore().saveSoulProfile(
      workspaceRoot,
      WorkspaceSoulProfile(
        presetName = "STEADY",
        customLabel = "Night Shift",
        customGuidance = "Keep replies calm and concrete.",
        extensions = mapOf(
          SoulProfileExtensionKeys.PREFERRED_ADDRESS_STYLE to "neutral",
          SoulProfileExtensionKeys.PLASTICITY to "medium",
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

    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = RecordingRuntimeManager(),
      personalizationLocalStore = personalizationStore,
      workspaceRootProvider = { workspaceRoot },
    )

    val payload = hostRuntime.loadSoulDebugSnapshot()

    val effectiveSoul = payload["effectiveSoul"] as Map<*, *>
    val relationshipStateDebug = payload["relationshipStateDebug"] as Map<*, *>
    val fieldSources = (payload["fieldSources"] as List<*>).map { item -> item as Map<*, *> }
    val preferredAddressSource = fieldSources.first { source ->
      source["field"] == "preferredAddressStyle"
    }

    assertEquals("intimate", effectiveSoul["preferredAddressStyle"])
    assertEquals("intimate", relationshipStateDebug["derivedAddressStyle"])
    assertEquals("relationship_state", preferredAddressSource["sourceType"])
    assertEquals("relationship-state", preferredAddressSource["recordId"])
  }
}
