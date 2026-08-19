package com.opencray.app

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.runtime.OpenCrayMidTurnMaintenanceRequest
import com.opencray.runtime.context.AgentRuntimeSessionContext
import com.opencray.runtime.context.ContextSourceBudgetPolicy
import com.opencray.runtime.context.LiveContextTrace
import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.context.RuntimeConversationRole
import com.opencray.runtime.compaction.NoOpRemoteCompactionProvider
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppAgentSessionTaskRuntimeFactoryContextBudgetTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun buildRuntimeLlmMetadataSurfacesPersistedPreviousWindowWhenCurrentWindowShrinks() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-context-budget-metadata"))
    val workspaceRoot = temporaryFolder.newFolder("workspace-root-context-budget-metadata").toPath()
    val sessionId = chatStore.loadState().activeSession.sessionId
    chatStore.replaceMaintainedContextWindowTokens(
      sessionId = sessionId,
      contextWindowTokens = 4096,
    )
    val factory = createFactory(
      chatStore = chatStore,
      workspaceRoot = workspaceRoot,
    )

    val llmMetadata = factory.buildRuntimeLlmMetadata(
      requiresLlmConfig = true,
      taskMetadata = emptyMap(),
      sessionId = sessionId,
      nativeWebSearchRunApproved = false,
      nativeWebSearchSessionApproved = false,
      llmSettings = LlmSettingsState(),
      routeMetadata = mapOf("context_window_tokens" to "1000"),
    )

    assertEquals("1000", llmMetadata["context_window_tokens"])
    assertEquals("4096", llmMetadata["previous_context_window_tokens"])
  }

  @Test
  fun buildRuntimeLlmMetadataSkipsPersistedPreviousWindowWhenItIsNotLarger() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-context-budget-stable"))
    val workspaceRoot = temporaryFolder.newFolder("workspace-root-context-budget-stable").toPath()
    val sessionId = chatStore.loadState().activeSession.sessionId
    chatStore.replaceMaintainedContextWindowTokens(
      sessionId = sessionId,
      contextWindowTokens = 1000,
    )
    val factory = createFactory(
      chatStore = chatStore,
      workspaceRoot = workspaceRoot,
    )

    val llmMetadata = factory.buildRuntimeLlmMetadata(
      requiresLlmConfig = true,
      taskMetadata = emptyMap(),
      sessionId = sessionId,
      nativeWebSearchRunApproved = false,
      nativeWebSearchSessionApproved = false,
      llmSettings = LlmSettingsState(),
      routeMetadata = mapOf("context_window_tokens" to "1000"),
    )

    assertEquals("1000", llmMetadata["context_window_tokens"])
    assertNull(llmMetadata["previous_context_window_tokens"])
  }

  @Test
  fun buildRuntimeLlmMetadataKeepsExplicitPreviousWindowMetadata() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-context-budget-explicit"))
    val workspaceRoot = temporaryFolder.newFolder("workspace-root-context-budget-explicit").toPath()
    val sessionId = chatStore.loadState().activeSession.sessionId
    chatStore.replaceMaintainedContextWindowTokens(
      sessionId = sessionId,
      contextWindowTokens = 8192,
    )
    val factory = createFactory(
      chatStore = chatStore,
      workspaceRoot = workspaceRoot,
    )

    val llmMetadata = factory.buildRuntimeLlmMetadata(
      requiresLlmConfig = true,
      taskMetadata = emptyMap(),
      sessionId = sessionId,
      nativeWebSearchRunApproved = false,
      nativeWebSearchSessionApproved = false,
      llmSettings = LlmSettingsState(),
      routeMetadata = mapOf(
        "context_window_tokens" to "1000",
        "previous_context_window_tokens" to "2048",
      ),
    )

    assertEquals("2048", llmMetadata["previous_context_window_tokens"])
  }

  @Test
  fun prepareSessionContextRecordsMaintainedWindowForTheNextPrompt() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-context-budget-prepare"))
    val workspaceRoot = temporaryFolder.newFolder("workspace-root-context-budget-prepare").toPath()
    val sessionId = chatStore.loadState().activeSession.sessionId
    val factory = createFactory(
      chatStore = chatStore,
      workspaceRoot = workspaceRoot,
    )

    factory.prepareSessionContext(
      sessionId = sessionId,
      workspaceId = "workspace-context-budget-prepare",
      visibleThroughMessageId = null,
      excludedMessageIds = emptySet(),
      soulProfile = null,
      taskType = AgentTaskType.PROMPT,
      taskId = "task-context-budget-prepare",
      taskInput = "Continue with the rollout.",
      transcriptStore = factory.transcriptStoreForSession(sessionId),
      memoryRecords = emptyList(),
      llmMetadata = mapOf("context_window_tokens" to "4096"),
    )

    assertEquals(4096, chatStore.loadMaintainedContextWindowTokens(sessionId))

    val nextLlmMetadata = factory.buildRuntimeLlmMetadata(
      requiresLlmConfig = true,
      taskMetadata = emptyMap(),
      sessionId = sessionId,
      nativeWebSearchRunApproved = false,
      nativeWebSearchSessionApproved = false,
      llmSettings = LlmSettingsState(),
      routeMetadata = mapOf("context_window_tokens" to "1000"),
    )

    assertEquals("4096", nextLlmMetadata["previous_context_window_tokens"])
  }

  @Test
  fun midTurnContextMaintenanceRecordsMaintainedWindow() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-context-budget-refresh"))
    val workspaceRoot = temporaryFolder.newFolder("workspace-root-context-budget-refresh").toPath()
    val sessionId = chatStore.loadState().activeSession.sessionId
    val factory = createFactory(
      chatStore = chatStore,
      workspaceRoot = workspaceRoot,
    )
    val transcriptStore = factory.transcriptStoreForSession(sessionId)
    transcriptStore.seedIfEmpty(
      listOf(
        RuntimeConversationMessage(
          role = RuntimeConversationRole.USER,
          content = "Initial prompt for replay maintenance.",
        ),
      ),
    )
    val conversation = transcriptStore.snapshot()
    val llmMetadata = mapOf("context_window_tokens" to "2048")

    factory.runMidTurnContextMaintenance(
      sessionId = sessionId,
      workspaceId = "workspace-context-budget-refresh",
      request = OpenCrayMidTurnMaintenanceRequest(
        task = promptTask(
          id = "task-context-budget-refresh",
          input = "Continue after tools.",
        ),
        runId = "run-context-budget-refresh",
        turn = 1,
        conversation = conversation,
        sessionContext = AgentRuntimeSessionContext(
          liveContextTrace = LiveContextTrace(
            mode = LiveContextMode.FULL.wireValue,
            soulEnabled = true,
            memoryRecallEnabled = true,
            replaySource = "runtime_replay_transcript",
            replayMessageCount = conversation.size,
            canonicalSource = "canonical_chat_history",
            canonicalMessageCount = conversation.size,
            canonicalHistoryPreserved = true,
          ),
          conversation = conversation,
        ),
        llmMetadata = llmMetadata,
      ),
      transcriptStore = transcriptStore,
      llmMetadata = llmMetadata,
      remoteCompactionProvider = NoOpRemoteCompactionProvider,
      sourceBudgetProfile = ContextSourceBudgetPolicy().resolve(llmMetadata),
      liveContextMode = LiveContextMode.FULL,
      liveContextPolicy = factory.liveContextPolicyFor(LiveContextMode.FULL),
      memoryToolsEnabled = false,
      enabled = true,
    )

    assertEquals(2048, chatStore.loadMaintainedContextWindowTokens(sessionId))
  }

  private fun createFactory(
    chatStore: ChatSessionLocalStore,
    workspaceRoot: Path,
  ): AppAgentSessionTaskRuntimeFactory = AppAgentSessionTaskRuntimeFactory(
    llmSettingsProvider = { LlmSettingsState() },
    sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
    soulProfileProvider = { null },
    workspaceRootsProvider = { setOf(workspaceRoot) },
    skillsRootsProvider = { emptyList() },
    mcpReportProvider = { null },
    maintainedContextWindowTokensProvider = chatStore::loadMaintainedContextWindowTokens,
    maintainedContextWindowTokensRecorder = chatStore::replaceMaintainedContextWindowTokens,
  )

  private fun promptTask(
    id: String,
    input: String,
  ): AgentTask = AgentTask(
    id = id,
    type = AgentTaskType.PROMPT,
    input = input,
    policyDecision = PolicyDecision(
      outcome = PolicyDecisionOutcome.ALLOW,
      reasonCode = "TEST_ALLOW",
    ),
    createdAtEpochMs = 1_000L,
  )
}
