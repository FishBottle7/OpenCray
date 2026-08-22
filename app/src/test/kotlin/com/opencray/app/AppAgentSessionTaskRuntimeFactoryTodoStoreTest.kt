package com.opencray.app

import com.opencray.persistence.model.MemoryRecord
import com.opencray.persistence.model.ChatAttachmentEntry
import com.opencray.persistence.model.ChatAttachmentKind
import com.opencray.persistence.store.MemoryStore
import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.llm.LiteLlmMetadataKeys
import com.opencray.runtime.AgentTodoEntry
import com.opencray.runtime.AgentTodoStatus
import com.opencray.runtime.AgentToolCall
import com.opencray.runtime.AgentToolResult
import com.opencray.runtime.AgentToolResultStatus
import com.opencray.runtime.ERROR_LLM_RETRY_EXHAUSTED_AWAITING_RESUME
import com.opencray.runtime.NoOpOpenCrayAgentRuntimeEventSink
import com.opencray.runtime.OpenCrayExecutionMetadataKeys
import com.opencray.runtime.OpenCrayFinalAttachment
import com.opencray.runtime.OpenCrayAgentRuntimeEventSink
import com.opencray.runtime.OpenCrayAssistantEvent
import com.opencray.runtime.OpenCrayPromptResumeMetadata
import com.opencray.runtime.OpenCrayPromptResumeState
import com.opencray.runtime.OpenCrayPromptSupplementMetadata
import com.opencray.runtime.OpenCraySubAgentEvent
import com.opencray.runtime.OpenCraySubAgentPhase
import com.opencray.runtime.OpenCraySupplementEvent
import com.opencray.runtime.OpenCrayToolResultEvent
import com.opencray.runtime.bootstrap.BootstrapMode
import com.opencray.runtime.compaction.RemoteCompactionProvider
import com.opencray.runtime.compaction.RemoteCompactionRequest
import com.opencray.runtime.compaction.RemoteCompactionResult
import com.opencray.runtime.context.CompactionSummary
import com.opencray.runtime.memory.MemoryCandidateExtractor
import com.opencray.runtime.memory.MemoryFlushOutcome
import com.opencray.runtime.memory.MemoryInteractionPreferenceExtensionKeys
import com.opencray.runtime.memory.MemoryKind
import com.opencray.runtime.memory.MemoryPreferenceKeys
import com.opencray.runtime.memory.MemoryScope
import com.opencray.runtime.memory.MemorySoulExtensionKeys
import com.opencray.runtime.subagent.SubAgentContinuationKind
import com.opencray.runtime.subagent.SubAgentExecutionState
import com.opencray.runtime.memory.UserMemoryIntent
import com.opencray.runtime.memory.UserMemoryIntentInterpretation
import com.opencray.runtime.memory.UserMemoryIntentInterpreter
import com.opencray.runtime.memory.UserMemoryIntentRequest
import com.opencray.runtime.context.ContextManager
import com.opencray.runtime.context.PromptAssembler
import com.opencray.runtime.context.RuntimeConversationAttachment
import com.opencray.runtime.context.RuntimeConversationAttachmentKind
import com.opencray.runtime.context.RuntimeConversationAssistantPhase
import com.opencray.runtime.context.RuntimeConversationMessageKind
import com.opencray.runtime.soul.SoulProfileExtensionKeys
import com.opencray.runtime.soul.SoulMemoryObjectTypes
import com.opencray.runtime.process.InMemoryAgentProcessRegistry
import com.opencray.runtime.context.RuntimeConversationRole
import com.opencray.runtime.session.SessionTranscriptStore
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private const val ERROR_MANAGED_PROCESS_INTERRUPTED_ON_RESTORE: String = "PROCESS_INTERRUPTED_ON_RESTORE"
private const val METADATA_RESTORED_TERMINAL_STATE: String = "restoredTerminalState"
private const val RESTORED_TERMINAL_STATE_INTERRUPTED: String = "interrupted"

class AppAgentSessionTaskRuntimeFactoryTodoStoreTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun todoStoreForSessionReusesStoreForSameSessionIdAndSeparatesDifferentSessions() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store"))
    val workspaceRoot = temporaryFolder.newFolder("workspace-root").toPath()
    val factory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
    )

    val first = factory.todoStoreForSession("session-1")
    val second = factory.todoStoreForSession("session-1")
    val third = factory.todoStoreForSession("session-2")

    assertSame(first, second)
    assertNotSame(first, third)
  }

  @Test
  fun todoStoreForSessionRestoresPersistedEntriesAcrossFactoryRecreation() {
    val chatDirectory = temporaryFolder.newFolder("chat-store-persistent-todos")
    val workspaceRoot = temporaryFolder.newFolder("workspace-root-persistent-todos").toPath()
    val initialChatStore = ChatSessionLocalStore(chatDirectory)
    val sessionId = initialChatStore.loadState().activeSession.sessionId

    fun createFactory(chatStore: ChatSessionLocalStore): AppAgentSessionTaskRuntimeFactory =
      AppAgentSessionTaskRuntimeFactory(
        llmSettingsProvider = { LlmSettingsState() },
        sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
        soulProfileProvider = { null },
        workspaceRootsProvider = { setOf(workspaceRoot) },
        skillsRootsProvider = { emptyList() },
        mcpReportProvider = { null },
        todoStoreProvider = { requestedSessionId ->
          ChatSessionBackedAgentTodoStore(
            chatSessionStore = chatStore,
            sessionId = requestedSessionId,
          )
        },
      )

    createFactory(initialChatStore).todoStoreForSession(sessionId).replaceAll(
      listOf(
        AgentTodoEntry(
          content = "Persist todo state",
          status = AgentTodoStatus.IN_PROGRESS,
          activeForm = "Persisting todo state",
        ),
      ),
    )

    val restoredFactory = createFactory(ChatSessionLocalStore(chatDirectory))
    val restoredTodos = restoredFactory.todoStoreForSession(sessionId).snapshot()

    assertEquals(1, restoredTodos.size)
    assertEquals("Persist todo state", restoredTodos.single().content)
    assertEquals(AgentTodoStatus.IN_PROGRESS, restoredTodos.single().status)
    assertEquals("Persisting todo state", restoredTodos.single().activeForm)
  }

  @Test
  fun todoStoreForSessionRejectsInvalidReplacementAndKeepsPersistedPlan() {
    val chatDirectory = temporaryFolder.newFolder("chat-store-invalid-persistent-todos")
    val workspaceRoot = temporaryFolder.newFolder("workspace-root-invalid-persistent-todos").toPath()
    val chatStore = ChatSessionLocalStore(chatDirectory)
    val sessionId = chatStore.loadState().activeSession.sessionId
    val factory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
      todoStoreProvider = { requestedSessionId ->
        ChatSessionBackedAgentTodoStore(
          chatSessionStore = chatStore,
          sessionId = requestedSessionId,
        )
      },
    )
    val todoStore = factory.todoStoreForSession(sessionId)
    todoStore.replaceAll(
      listOf(
        AgentTodoEntry(
          content = "Inspect runtime continuation",
          status = AgentTodoStatus.IN_PROGRESS,
          activeForm = "Inspecting runtime continuation",
        ),
      ),
    )

    val error = runCatching {
      todoStore.replaceAll(
        listOf(
          AgentTodoEntry(
            content = "Inspect runtime continuation",
            status = AgentTodoStatus.IN_PROGRESS,
            activeForm = "Inspecting runtime continuation",
          ),
          AgentTodoEntry(
            content = "Write follow-up tests",
            status = AgentTodoStatus.IN_PROGRESS,
            activeForm = "Writing follow-up tests",
          ),
        ),
      )
    }.exceptionOrNull()
    val restoredTodos = ChatSessionLocalStore(chatDirectory).loadTodos(sessionId)

    assertTrue(error?.message.orEmpty().contains("at most one in_progress"))
    assertEquals(1, restoredTodos.size)
    assertEquals("Inspect runtime continuation", restoredTodos.single().content)
    assertEquals(AgentTodoStatus.IN_PROGRESS, restoredTodos.single().status)
    assertEquals("Inspecting runtime continuation", restoredTodos.single().activeForm)
  }

  @Test
  fun transcriptStoreForSessionReusesStoreForSameSessionIdAndSeparatesDifferentSessions() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-transcript"))
    val workspaceRoot = temporaryFolder.newFolder("workspace-root-transcript").toPath()
    val factory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
    )

    val first = factory.transcriptStoreForSession("session-1")
    val second = factory.transcriptStoreForSession("session-1")
    val third = factory.transcriptStoreForSession("session-2")

    assertSame(first, second)
    assertNotSame(first, third)
  }

  @Test
  fun compactionStoreForSessionReusesStoreForSameSessionIdAndSeparatesDifferentSessions() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-compaction"))
    val workspaceRoot = temporaryFolder.newFolder("workspace-root-compaction").toPath()
    val factory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
    )

    val first = factory.compactionStoreForSession("session-1")
    val second = factory.compactionStoreForSession("session-1")
    val third = factory.compactionStoreForSession("session-2")

    assertSame(first, second)
    assertNotSame(first, third)
  }

  @Test
  fun processRegistryForSessionReusesRegistryForSameSessionIdAndSeparatesDifferentSessions() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-process-registry"))
    val workspaceRoot = temporaryFolder.newFolder("workspace-root-process-registry").toPath()
    val createdRegistries = mutableListOf<InMemoryAgentProcessRegistry>()
    val factory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
      processRegistryProvider = {
        InMemoryAgentProcessRegistry().also(createdRegistries::add)
      },
    )

    val first = factory.processRegistryForSession("session-1")
    val second = factory.processRegistryForSession("session-1")
    val third = factory.processRegistryForSession("session-2")

    assertSame(first, second)
    assertNotSame(first, third)
    assertEquals(2, createdRegistries.size)
  }

  @Test
  fun createSessionDoesNotReuseSeedSessionOnceSessionScopedStateExists() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-session-state-reuse"))
    val seedSessionId = chatStore.loadState().activeSession.sessionId

    chatStore.setSessionScopedStatePresent(
      sessionId = seedSessionId,
      present = true,
    )

    val created = chatStore.createSession().activeSession

    assertTrue(created.sessionId != seedSessionId)
    assertTrue(chatStore.isReusableEmptySession(created.sessionId))
    assertFalse(chatStore.isReusableEmptySession(seedSessionId))
  }

  @Test
  fun recordApprovalRejectionAppendsReplayToolObservation() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-approval-rejection"))
    val workspaceRoot = temporaryFolder.newFolder("workspace-root-approval-rejection").toPath()
    val factory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
    )

    factory.recordApprovalRejection(
      sessionId = "session-1",
      taskId = "task-1",
      runId = "run-1",
      toolName = "Write",
      isHighRisk = true,
    )

    val snapshot = factory.transcriptStoreForSession("session-1").snapshot()

    assertEquals(1, snapshot.size)
    assertEquals(RuntimeConversationRole.TOOL, snapshot.single().role)
    assertTrue(snapshot.single().content.contains("approval_rejected"))
    assertTrue(snapshot.single().content.contains("task_id=task-1"))
    assertTrue(snapshot.single().content.contains("run_id=run-1"))
    assertTrue(snapshot.single().content.contains("tool_name=Write"))
    assertTrue(snapshot.single().content.contains("executed=false"))
    assertTrue(snapshot.single().content.contains("risk=high_risk"))
  }

  @Test
  fun recordApprovalApprovedAppendsReplayToolObservation() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-approval-approved"))
    val workspaceRoot = temporaryFolder.newFolder("workspace-root-approval-approved").toPath()
    val factory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
    )

    factory.recordApprovalApproved(
      sessionId = "session-1",
      taskId = "task-1",
      runId = "run-1",
      toolName = "Write",
      isHighRisk = false,
    )

    val snapshot = factory.transcriptStoreForSession("session-1").snapshot()

    assertEquals(1, snapshot.size)
    assertEquals(RuntimeConversationRole.TOOL, snapshot.single().role)
    assertTrue(snapshot.single().content.contains("approval_approved"))
    assertTrue(snapshot.single().content.contains("task_id=task-1"))
    assertTrue(snapshot.single().content.contains("run_id=run-1"))
    assertTrue(snapshot.single().content.contains("tool_name=Write"))
    assertTrue(snapshot.single().content.contains("outcome=user_approved"))
  }

  @Test
  fun recordRunCancellationAppendsReplayToolObservation() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-run-cancelled"))
    val workspaceRoot = temporaryFolder.newFolder("workspace-root-run-cancelled").toPath()
    val factory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
    )

    factory.recordRunCancellation(
      sessionId = "session-1",
      taskId = "task-1",
      runId = "run-1",
      toolName = "Write",
    )

    val snapshot = factory.transcriptStoreForSession("session-1").snapshot()

    assertEquals(1, snapshot.size)
    assertEquals(RuntimeConversationRole.TOOL, snapshot.single().role)
    assertTrue(snapshot.single().content.contains("run_interrupted"))
    assertTrue(snapshot.single().content.contains("task_id=task-1"))
    assertTrue(snapshot.single().content.contains("run_id=run-1"))
    assertTrue(snapshot.single().content.contains("tool_name=Write"))
    assertTrue(snapshot.single().content.contains("outcome=user_interrupted"))
  }

  @Test
  fun repairTerminalReplayFromRunSnapshotsBackfillsInterruptedAndRetryAbandoned() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-terminal-repair"))
    val workspaceRoot = temporaryFolder.newFolder("workspace-root-terminal-repair").toPath()
    val factory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
    )

    factory.repairTerminalReplayFromRunSnapshots(
      sessionId = "session-1",
      runs = listOf(
        AgentRunSnapshot(
          sessionId = "session-1",
          runId = "run-failed-1",
          taskId = "task-failed-1",
          acceptedAtEpochMs = 1_000L,
          updatedAtEpochMs = 1_100L,
          lifecycleState = com.opencray.core.orchestrator.QueueTaskLifecycleState.FAILED,
          taskState = com.opencray.core.contracts.AgentTaskState.FAILED,
          attempt = 1,
          executionStatus = com.opencray.core.contracts.ExecutionStatus.FAILED,
          errorCode = "RUNTIME_EXCEPTION",
        ),
        AgentRunSnapshot(
          sessionId = "session-1",
          runId = "run-failed-2",
          taskId = "task-failed-2",
          acceptedAtEpochMs = 2_000L,
          updatedAtEpochMs = 2_100L,
          lifecycleState = com.opencray.core.orchestrator.QueueTaskLifecycleState.FAILED,
          taskState = com.opencray.core.contracts.AgentTaskState.FAILED,
          attempt = 2,
          executionStatus = com.opencray.core.contracts.ExecutionStatus.FAILED,
          errorCode = "TOOL_EXECUTION_FAILED",
        ),
      ),
    )
    factory.repairTerminalReplayFromRunSnapshots(
      sessionId = "session-1",
      runs = listOf(
        AgentRunSnapshot(
          sessionId = "session-1",
          runId = "run-failed-1",
          taskId = "task-failed-1",
          acceptedAtEpochMs = 1_000L,
          updatedAtEpochMs = 1_100L,
          lifecycleState = com.opencray.core.orchestrator.QueueTaskLifecycleState.FAILED,
          taskState = com.opencray.core.contracts.AgentTaskState.FAILED,
          attempt = 1,
          executionStatus = com.opencray.core.contracts.ExecutionStatus.FAILED,
          errorCode = "RUNTIME_EXCEPTION",
        ),
        AgentRunSnapshot(
          sessionId = "session-1",
          runId = "run-failed-2",
          taskId = "task-failed-2",
          acceptedAtEpochMs = 2_000L,
          updatedAtEpochMs = 2_100L,
          lifecycleState = com.opencray.core.orchestrator.QueueTaskLifecycleState.FAILED,
          taskState = com.opencray.core.contracts.AgentTaskState.FAILED,
          attempt = 2,
          executionStatus = com.opencray.core.contracts.ExecutionStatus.FAILED,
          errorCode = "TOOL_EXECUTION_FAILED",
        ),
      ),
    )

    val snapshot = factory.transcriptStoreForSession("session-1").snapshot()

    assertEquals(2, snapshot.size)
    assertTrue(snapshot[0].content.startsWith("run_interrupted"))
    assertTrue(snapshot[0].content.contains("task_id=task-failed-1"))
    assertTrue(snapshot[1].content.startsWith("retry_abandoned"))
    assertTrue(snapshot[1].content.contains("attempt=2"))
  }

  @Test
  fun repairTerminalReplayFromRunSnapshotsMarksRestoredInterruptedProcessRuns() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-process-restore-repair"))
    val workspaceRoot = temporaryFolder.newFolder("workspace-root-process-restore-repair").toPath()
    val factory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
    )

    factory.repairTerminalReplayFromRunSnapshots(
      sessionId = "session-1",
      runs = listOf(
        AgentRunSnapshot(
          sessionId = "session-1",
          runId = "run-restored-process",
          taskId = "task-restored-process",
          acceptedAtEpochMs = 1_000L,
          updatedAtEpochMs = 1_100L,
          lifecycleState = com.opencray.core.orchestrator.QueueTaskLifecycleState.FAILED,
          taskState = com.opencray.core.contracts.AgentTaskState.FAILED,
          attempt = 1,
          executionStatus = com.opencray.core.contracts.ExecutionStatus.FAILED,
          errorCode = errorManagedProcessInterruptedOnRestoreForTest,
          resultMetadata = mapOf(
            metadataRestoredTerminalStateForTest to restoredTerminalStateInterruptedForTest,
          ),
        ),
      ),
    )

    val snapshot = factory.transcriptStoreForSession("session-1").snapshot()

    assertEquals(1, snapshot.size)
    assertTrue(snapshot.single().content.startsWith("run_interrupted"))
    assertTrue(snapshot.single().content.contains("outcome=restored_process_interrupted"))
    assertTrue(snapshot.single().content.contains("error_code=$errorManagedProcessInterruptedOnRestoreForTest"))
  }

  @Test
  fun recordSuccessfulToolInteractionAppendsToolCallAndResultReplaySummaries() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-tool-replay"))
    val workspaceRoot = temporaryFolder.newFolder("workspace-root-tool-replay").toPath()
    val factory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
    )

    factory.recordSuccessfulToolInteraction(
      sessionId = "session-1",
      event = OpenCrayToolResultEvent(
        runId = "run-1",
        taskId = "task-1",
        turn = 2,
        call = AgentToolCall(
          id = "call-1",
          toolName = "Read",
          arguments = buildJsonObject {
            put("file_path", "README.md")
            put("offset", 5)
            put("limit", 2)
          },
          reason = "Inspect the install section before answering.",
        ),
        result = AgentToolResult(
          toolName = "Read",
          status = AgentToolResultStatus.SUCCESS,
          content = "Line five\nLine six\n",
          metadata = mapOf(
            "filePath" to "README.md",
            "offset" to "5",
            "returnedLineCount" to "2",
          ),
        ),
        emittedAtEpochMs = 1_000L,
      ),
    )

    val snapshot = factory.transcriptStoreForSession("session-1").snapshot()

    assertEquals(2, snapshot.size)
    assertEquals(RuntimeConversationRole.ASSISTANT, snapshot[0].role)
    assertEquals(RuntimeConversationMessageKind.TOOL_CALL, snapshot[0].kind)
    assertFalse(snapshot[0].content.startsWith("tool_call "))
    assertTrue(snapshot[0].content.contains("\"run_id\":\"run-1\""))
    assertTrue(snapshot[0].content.contains("\"turn\":2"))
    assertTrue(snapshot[0].content.contains("\"tool_call_id\":\"call-1\""))
    assertTrue(snapshot[0].content.contains("\"tool_name\":\"Read\""))
    assertTrue(snapshot[0].content.contains("\"file_path\":\"README.md\""))
    assertEquals(RuntimeConversationMessageKind.TOOL_RESULT, snapshot[1].kind)
    assertFalse(snapshot[1].content.startsWith("tool_result "))
    assertTrue(snapshot[1].content.contains("\"status\":\"success\""))
    assertTrue(snapshot[1].content.contains("\"content\":\"Line five\\nLine six\\n\""))
    assertTrue(snapshot[1].content.contains("\"filePath\":\"README.md\""))
  }

  @Test
  fun supplementReplayContentOmitsHiddenPromptResumeMetadata() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-supplement-replay"))
    val workspaceRoot = temporaryFolder.newFolder("workspace-root-supplement-replay").toPath()
    val factory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
    )
    val method = AppAgentSessionTaskRuntimeFactory::class.java.getDeclaredMethod(
      "buildSupplementReplayContent",
      OpenCraySupplementEvent::class.java,
    )
    method.isAccessible = true

    val payload = method.invoke(
      factory,
      OpenCraySupplementEvent(
        runId = "run-1",
        taskId = "task-1",
        turn = 1,
        entryId = "supplement-1",
        text = "Also inspect the logs",
        checkpoint = "turn_start",
        metadata = mapOf(
          "source" to "manual",
          OpenCrayPromptResumeMetadata.KEY_PROMPT_RESUME_JSON to
            """{"turnIndex":1,"toolCallCount":1}""",
        ),
        emittedAtEpochMs = 1_000L,
      ),
    ) as String

    val decoded = Json.parseToJsonElement(payload).jsonObject
    val metadata = requireNotNull(decoded["metadata"]).jsonObject

    assertEquals("supplement", decoded.getValue("event_kind").jsonPrimitive.content)
    assertEquals("manual", metadata.getValue("source").jsonPrimitive.content)
    assertFalse(metadata.containsKey(OpenCrayPromptResumeMetadata.KEY_PROMPT_RESUME_JSON))
  }

  @Test
  fun recordSuccessfulToolInteractionSkipsDuplicateReplayEntriesForSameRunTurn() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-tool-replay-dedupe"))
    val workspaceRoot = temporaryFolder.newFolder("workspace-root-tool-replay-dedupe").toPath()
    val factory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
    )
    val event = OpenCrayToolResultEvent(
      runId = "run-1",
      taskId = "task-1",
      turn = 1,
      call = AgentToolCall(
        toolName = "Grep",
        arguments = buildJsonObject {
          put("pattern", "TODO")
          put("path", ".")
        },
      ),
      result = AgentToolResult(
        toolName = "Grep",
        status = AgentToolResultStatus.SUCCESS,
        content = "src/Main.kt:10:// TODO",
        metadata = mapOf(
          "path" to ".",
          "pattern" to "TODO",
          "matchCount" to "1",
        ),
      ),
      emittedAtEpochMs = 2_000L,
    )

    factory.recordSuccessfulToolInteraction(sessionId = "session-1", event = event)
    factory.recordSuccessfulToolInteraction(sessionId = "session-1", event = event)

    val snapshot = factory.transcriptStoreForSession("session-1").snapshot()

    assertEquals(2, snapshot.size)
    assertTrue(snapshot[0].content.contains("\"run_id\":\"run-1\""))
    assertTrue(snapshot[1].content.contains("\"matchCount\":\"1\""))
  }

  @Test
  fun transcriptAwareEventSinkPersistsFailedToolResultReplayMessages() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-failed-tool-replay"))
    val workspaceRoot = temporaryFolder.newFolder("workspace-root-failed-tool-replay").toPath()
    val factory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
    )
    val sessionId = "session-1"
    val transcriptStore = factory.transcriptStoreForSession(sessionId)
    val eventSink = factory.transcriptAwareEventSinkForTest(
      sessionId = sessionId,
      transcriptStore = transcriptStore,
    )

    eventSink.onRunEvent(
      promptTask("Inspect the missing file."),
      OpenCrayToolResultEvent(
        runId = "run-1",
        taskId = "task-live-context",
        turn = 2,
        call = AgentToolCall(
          id = "call-missing-read",
          toolName = "Read",
          arguments = buildJsonObject {
            put("file_path", "missing.txt")
          },
          reason = "Inspect the missing file before retrying.",
        ),
        result = AgentToolResult(
          toolName = "Read",
          status = AgentToolResultStatus.FAILED,
          content = "missing.txt was not found.",
          errorCode = "FILE_NOT_FOUND",
          errorMessage = "missing.txt was not found.",
          metadata = mapOf("filePath" to "missing.txt"),
        ),
        emittedAtEpochMs = 1_000L,
      ),
    )

    val snapshot = transcriptStore.snapshot()

    assertEquals(2, snapshot.size)
    assertEquals(RuntimeConversationMessageKind.TOOL_CALL, snapshot[0].kind)
    assertEquals(RuntimeConversationMessageKind.TOOL_RESULT, snapshot[1].kind)
    assertTrue(snapshot[0].content.contains("\"tool_name\":\"Read\""))
    assertTrue(snapshot[0].content.contains("\"file_path\":\"missing.txt\""))
    assertTrue(snapshot[1].content.contains("\"status\":\"failed\""))
    assertTrue(snapshot[1].content.contains("\"error_code\":\"FILE_NOT_FOUND\""))
    assertTrue(snapshot[1].content.contains("\"error_message\":\"missing.txt was not found.\""))
    assertEquals("failed", snapshot[1].toolResult?.status)
    assertEquals(true, snapshot[1].toolResult?.isError)
  }

  @Test
  fun transcriptAwareEventSinkFiltersResumePayloadFromSupplementReplayMetadata() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-supplement-replay"))
    val workspaceRoot = temporaryFolder.newFolder("workspace-root-supplement-replay").toPath()
    val factory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
    )
    val sessionId = "session-1"
    val transcriptStore = factory.transcriptStoreForSession(sessionId)
    val eventSink = factory.transcriptAwareEventSinkForTest(
      sessionId = sessionId,
      transcriptStore = transcriptStore,
    )
    val resumeState = OpenCrayPromptResumeState(turnIndex = 1, toolCallCount = 1)

    eventSink.onRunEvent(
      promptTask("Inspect the logs."),
      OpenCraySupplementEvent(
        runId = "run-1",
        taskId = "task-live-context",
        turn = 1,
        entryId = "supplement-1",
        text = "Also inspect the logs",
        metadata = OpenCrayPromptResumeMetadata.encodeToMetadata(resumeState, Json) +
          mapOf("source" to "manual"),
        emittedAtEpochMs = 1_000L,
      ),
    )

    val snapshot = transcriptStore.snapshot()
    val replay = Json.parseToJsonElement(snapshot[1].content).jsonObject
    val replayMetadata = replay["metadata"]?.jsonObject

    assertEquals(2, snapshot.size)
    assertEquals(RuntimeConversationRole.USER, snapshot[0].role)
    assertEquals("Also inspect the logs", snapshot[0].content)
    assertEquals(RuntimeConversationRole.TOOL, snapshot[1].role)
    assertEquals("supplement", replay["event_kind"]?.jsonPrimitive?.content)
    assertEquals("supplement-1", replay["entry_id"]?.jsonPrimitive?.content)
    assertEquals("Also inspect the logs", replay["text"]?.jsonPrimitive?.content)
    assertEquals("manual", replayMetadata?.get("source")?.jsonPrimitive?.content)
    assertEquals(1, replayMetadata?.size)
    assertFalse(replayMetadata?.containsKey(OpenCrayPromptResumeMetadata.KEY_PROMPT_RESUME_JSON) == true)
  }

  @Test
  fun recordFinalAssistantTurnPersistsNonSuccessTerminalRepliesIntoTranscript() {
    val chatStore = ChatSessionLocalStore(
      temporaryFolder.newFolder("chat-store-non-success-final-transcript"),
    )
    val workspaceRoot = temporaryFolder.newFolder("workspace-root-non-success-final-transcript").toPath()
    val factory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
    )
    val task = AgentTask(
      id = "task-non-success-final-transcript",
      type = AgentTaskType.PROMPT,
      input = "Continue.",
      policyDecision = PolicyDecision(
        outcome = PolicyDecisionOutcome.ALLOW,
        reasonCode = "TEST_ALLOW",
      ),
      createdAtEpochMs = 1_000L,
    )
    val results = listOf(
      Triple(
        "session-cancelled",
        ExecutionResult(
          taskId = task.id,
          status = ExecutionStatus.CANCELLED,
          startedAtEpochMs = 1_000L,
          finishedAtEpochMs = 1_001L,
        ),
        "Interrupted",
      ),
      Triple(
        "session-denied",
        ExecutionResult(
          taskId = task.id,
          status = ExecutionStatus.DENIED,
          errorCode = "APPROVAL_REQUIRED",
          startedAtEpochMs = 1_000L,
          finishedAtEpochMs = 1_001L,
        ),
        "Failed: APPROVAL_REQUIRED",
      ),
      Triple(
        "session-missing-llm",
        ExecutionResult(
          taskId = task.id,
          status = ExecutionStatus.FAILED,
          errorCode = AppAgentSessionTaskRuntimeFactory.ERROR_CODE_MISSING_LLM_CONFIG,
          startedAtEpochMs = 1_000L,
          finishedAtEpochMs = 1_001L,
        ),
        "Missing LLM",
      ),
    )

    results.forEach { (sessionId, result, expectedText) ->
      factory.recordFinalAssistantTurnForTest(
        sessionId = sessionId,
        task = task,
        result = result,
      )

      val snapshot = factory.transcriptStoreForSession(sessionId).snapshot()
      val assistant = snapshot.single()

      assertEquals(1, snapshot.size)
      assertEquals(RuntimeConversationRole.ASSISTANT, assistant.role)
      assertEquals(RuntimeConversationAssistantPhase.FINAL_ANSWER, assistant.assistantPhase)
      assertEquals(expectedText, assistant.content)
      assertTrue(assistant.attachments.isEmpty())
    }
  }

  @Test
  fun recordSuccessfulAssistantTurnPersistsAttachmentOnlyFinalAnswerIntoTranscript() {
    val chatStore = ChatSessionLocalStore(
      temporaryFolder.newFolder("chat-store-final-attachment-transcript"),
    )
    val workspaceRoot = temporaryFolder.newFolder("workspace-root-final-attachment-transcript").toPath()
    Files.createDirectories(workspaceRoot.resolve("outputs"))
    Files.write(
      workspaceRoot.resolve("outputs").resolve("diagram.png"),
      byteArrayOf(1, 2, 3, 4),
    )
    val factory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
    )
    val attachmentsJson = Json.encodeToString(
      ListSerializer(OpenCrayFinalAttachment.serializer()),
      listOf(
        OpenCrayFinalAttachment(
          kind = "image",
          relativePath = "outputs/diagram.png",
          displayName = "diagram.png",
          mimeType = "image/png",
        ),
      ),
    )
    val task = AgentTask(
      id = "task-final-attachment-transcript",
      type = AgentTaskType.PROMPT,
      input = "Send the diagram.",
      policyDecision = PolicyDecision(
        outcome = PolicyDecisionOutcome.ALLOW,
        reasonCode = "TEST_ALLOW",
      ),
      createdAtEpochMs = 1_000L,
    )

    factory.recordFinalAssistantTurnForTest(
      sessionId = "session-1",
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = ExecutionStatus.SUCCESS,
        stdout = "",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = mapOf(
          OpenCrayExecutionMetadataKeys.FINAL_ATTACHMENTS_JSON to attachmentsJson,
        ),
      ),
    )

    val snapshot = factory.transcriptStoreForSession("session-1").snapshot()
    val assistant = snapshot.single()
    val absoluteAttachmentPath = workspaceRoot
      .resolve("outputs")
      .resolve("diagram.png")
      .toAbsolutePath()
      .normalize()
      .toString()
      .replace('\\', '/')

    assertEquals(1, snapshot.size)
    assertEquals(RuntimeConversationRole.ASSISTANT, assistant.role)
    assertEquals(RuntimeConversationAssistantPhase.FINAL_ANSWER, assistant.assistantPhase)
    assertEquals("", assistant.content)
    assertEquals(1, assistant.attachments.size)
    assertEquals("diagram.png", assistant.attachments.single().displayName)
    assertEquals(RuntimeConversationAttachmentKind.IMAGE, assistant.attachments.single().kind)
    assertEquals("image/png", assistant.attachments.single().mimeType)
    assertEquals(absoluteAttachmentPath, assistant.attachments.single().filePath)
  }

  @Test
  fun transcriptAwareEventSinkPersistsFinalAssistantReplayEventAndMergesAttachmentsIntoSingleTurn() {
    val chatStore = ChatSessionLocalStore(
      temporaryFolder.newFolder("chat-store-final-event-transcript"),
    )
    val workspaceRoot = temporaryFolder.newFolder("workspace-root-final-event-transcript").toPath()
    Files.createDirectories(workspaceRoot.resolve("outputs"))
    Files.write(
      workspaceRoot.resolve("outputs").resolve("diagram.png"),
      byteArrayOf(1, 2, 3, 4),
    )
    val factory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
    )
    val sessionId = "session-final-event"
    val transcriptStore = factory.transcriptStoreForSession(sessionId)
    val eventSink = factory.transcriptAwareEventSinkForTest(
      sessionId = sessionId,
      transcriptStore = transcriptStore,
    )
    val task = AgentTask(
      id = "task-final-event-transcript",
      type = AgentTaskType.PROMPT,
      input = "Send the diagram.",
      policyDecision = PolicyDecision(
        outcome = PolicyDecisionOutcome.ALLOW,
        reasonCode = "TEST_ALLOW",
      ),
      createdAtEpochMs = 1_000L,
    )
    val finalText = "See the attached diagram."
    val attachmentsJson = Json.encodeToString(
      ListSerializer(OpenCrayFinalAttachment.serializer()),
      listOf(
        OpenCrayFinalAttachment(
          kind = "image",
          relativePath = "outputs/diagram.png",
          displayName = "diagram.png",
          mimeType = "image/png",
        ),
      ),
    )

    eventSink.onRunEvent(
      task,
      OpenCrayAssistantEvent(
        runId = "run-1",
        taskId = task.id,
        turn = 1,
        text = finalText,
        isFinal = true,
        emittedAtEpochMs = 1_000L,
      ),
    )

    val replaySnapshot = transcriptStore.snapshot()

    assertEquals(1, replaySnapshot.size)
    assertEquals(RuntimeConversationRole.ASSISTANT, replaySnapshot.single().role)
    assertEquals(RuntimeConversationAssistantPhase.FINAL_ANSWER, replaySnapshot.single().assistantPhase)
    assertEquals(finalText, replaySnapshot.single().content)
    assertTrue(replaySnapshot.single().attachments.isEmpty())

    factory.recordFinalAssistantTurnForTest(
      sessionId = sessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = ExecutionStatus.SUCCESS,
        stdout = finalText,
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = mapOf(
          OpenCrayExecutionMetadataKeys.FINAL_ATTACHMENTS_JSON to attachmentsJson,
        ),
      ),
    )

    val snapshot = transcriptStore.snapshot()
    val assistant = snapshot.single()
    val absoluteAttachmentPath = workspaceRoot
      .resolve("outputs")
      .resolve("diagram.png")
      .toAbsolutePath()
      .normalize()
      .toString()
      .replace('\\', '/')

    assertEquals(1, snapshot.size)
    assertEquals(RuntimeConversationAssistantPhase.FINAL_ANSWER, assistant.assistantPhase)
    assertEquals(finalText, assistant.content)
    assertEquals(1, assistant.attachments.size)
    assertEquals(absoluteAttachmentPath, assistant.attachments.single().filePath)
  }

  @Test
  fun replayedMarkdownOnlyFinalEventIsCollapsedIntoSingleDurableFinalTurnAfterReload() {
    val transcriptRoot = temporaryFolder.newFolder("transcript-store-markdown-replay-durable")
    val firstTranscriptFactory = FileBackedAgentSessionTranscriptStoreFactory(transcriptRoot)
    val secondTranscriptFactory = FileBackedAgentSessionTranscriptStoreFactory(transcriptRoot)
    val chatStore = ChatSessionLocalStore(
      temporaryFolder.newFolder("chat-store-markdown-replay-durable"),
    )
    val workspaceRoot = temporaryFolder.newFolder("workspace-root-markdown-replay-durable").toPath()
    Files.createDirectories(workspaceRoot.resolve("attachments").resolve("final"))
    Files.write(
      workspaceRoot.resolve("attachments").resolve("final").resolve("diagram.png"),
      byteArrayOf(1, 2, 3),
    )
    val sessionId = chatStore.loadState().activeSession.sessionId
    chatStore.appendUserMessage(
      sessionId = sessionId,
      text = "Previous upload",
      commandLabel = null,
      attachments = listOf(
        ChatAttachmentEntry(
          attachmentId = "attachment-existing",
          kind = ChatAttachmentKind.IMAGE,
          displayName = "diagram.png",
          localPath = "attachments/final/diagram.png",
          mimeType = "image/png",
        ),
      ),
    )
    val firstFactory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
      transcriptStoreProvider = firstTranscriptFactory::forChatSession,
    )
    val transcriptStore = firstFactory.transcriptStoreForSession(sessionId)
    val eventSink = firstFactory.transcriptAwareEventSinkForTest(
      sessionId = sessionId,
      transcriptStore = transcriptStore,
    )
    val task = AgentTask(
      id = "task-markdown-replay-durable",
      type = AgentTaskType.PROMPT,
      input = "Re-send the uploaded diagram.",
      policyDecision = PolicyDecision(
        outcome = PolicyDecisionOutcome.ALLOW,
        reasonCode = "TEST_ALLOW",
      ),
      createdAtEpochMs = 1_000L,
    )
    val markdownOnlyFinal = "![diagram](attachments/final/diagram.png)"

    eventSink.onRunEvent(
      task,
      OpenCrayAssistantEvent(
        runId = "run-markdown-replay-durable",
        taskId = task.id,
        turn = 1,
        text = markdownOnlyFinal,
        isFinal = true,
        emittedAtEpochMs = 1_000L,
      ),
    )
    firstFactory.recordFinalAssistantTurnForTest(
      sessionId = sessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = ExecutionStatus.SUCCESS,
        stdout = markdownOnlyFinal,
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
      ),
    )

    val liveSnapshot = transcriptStore.snapshot()
    val recreatedFactory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
      transcriptStoreProvider = secondTranscriptFactory::forChatSession,
    )
    val reloadedSnapshot = recreatedFactory.transcriptStoreForSession(sessionId).snapshot()
    val absoluteAttachmentPath = workspaceRoot
      .resolve("attachments")
      .resolve("final")
      .resolve("diagram.png")
      .toAbsolutePath()
      .normalize()
      .toString()
      .replace('\\', '/')

    listOf(liveSnapshot, reloadedSnapshot).forEach { snapshot ->
      val assistant = snapshot.single()
      assertEquals(1, snapshot.size)
      assertEquals(RuntimeConversationRole.ASSISTANT, assistant.role)
      assertEquals(RuntimeConversationAssistantPhase.FINAL_ANSWER, assistant.assistantPhase)
      assertEquals("", assistant.content)
      assertEquals(1, assistant.attachments.size)
      assertEquals(absoluteAttachmentPath, assistant.attachments.single().filePath)
    }
  }

  @Test
  fun recordFinalAssistantTurnSkipsPausedRetryAwaitingResumeFailures() {
    val chatStore = ChatSessionLocalStore(
      temporaryFolder.newFolder("chat-store-paused-retry-final-transcript"),
    )
    val workspaceRoot = temporaryFolder.newFolder("workspace-root-paused-retry-final-transcript").toPath()
    val factory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
    )
    val task = AgentTask(
      id = "task-paused-retry-final-transcript",
      type = AgentTaskType.PROMPT,
      input = "Continue after the checkpoint.",
      policyDecision = PolicyDecision(
        outcome = PolicyDecisionOutcome.ALLOW,
        reasonCode = "TEST_ALLOW",
      ),
      createdAtEpochMs = 1_000L,
    )

    factory.recordFinalAssistantTurnForTest(
      sessionId = "session-paused-retry",
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = ExecutionStatus.FAILED,
        errorCode = ERROR_LLM_RETRY_EXHAUSTED_AWAITING_RESUME,
        errorMessage = "Retry budget exhausted.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
      ),
    )

    assertTrue(factory.transcriptStoreForSession("session-paused-retry").snapshot().isEmpty())
  }

  @Test
  fun recordSuccessfulAssistantTurnPersistsMarkdownCompatibilityAttachmentsIntoTranscript() {
    val chatStore = ChatSessionLocalStore(
      temporaryFolder.newFolder("chat-store-markdown-compat-transcript"),
    )
    val workspaceRoot = temporaryFolder.newFolder("workspace-root-markdown-compat-transcript").toPath()
    Files.createDirectories(workspaceRoot.resolve("attachments").resolve("final"))
    Files.write(
      workspaceRoot.resolve("attachments").resolve("final").resolve("diagram.png"),
      byteArrayOf(1, 2, 3),
    )
    val factory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
    )
    val sessionId = chatStore.loadState().activeSession.sessionId
    chatStore.appendUserMessage(
      sessionId = sessionId,
      text = "Previous upload",
      commandLabel = null,
      attachments = listOf(
        ChatAttachmentEntry(
          attachmentId = "attachment-existing",
          kind = ChatAttachmentKind.IMAGE,
          displayName = "diagram.png",
          localPath = "attachments/final/diagram.png",
          mimeType = "image/png",
        ),
      ),
    )
    val task = AgentTask(
      id = "task-markdown-compat-transcript",
      type = AgentTaskType.PROMPT,
      input = "Re-send the uploaded diagram.",
      policyDecision = PolicyDecision(
        outcome = PolicyDecisionOutcome.ALLOW,
        reasonCode = "TEST_ALLOW",
      ),
      createdAtEpochMs = 1_000L,
    )

    factory.recordFinalAssistantTurnForTest(
      sessionId = sessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = ExecutionStatus.SUCCESS,
        stdout = "![diagram](attachments/final/diagram.png)",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
      ),
    )

    val snapshot = factory.transcriptStoreForSession(sessionId).snapshot()
    val assistant = snapshot.single()
    val attachment = assistant.attachments.single()
    val absoluteAttachmentPath = workspaceRoot
      .resolve("attachments")
      .resolve("final")
      .resolve("diagram.png")
      .toAbsolutePath()
      .normalize()
      .toString()
      .replace('\\', '/')

    assertEquals(1, snapshot.size)
    assertEquals(RuntimeConversationAssistantPhase.FINAL_ANSWER, assistant.assistantPhase)
    assertEquals("", assistant.content)
    assertEquals(1, assistant.attachments.size)
    assertEquals("diagram.png", attachment.displayName)
    assertEquals(RuntimeConversationAttachmentKind.IMAGE, attachment.kind)
    assertEquals("image/png", attachment.mimeType)
    assertEquals(absoluteAttachmentPath, attachment.filePath)
  }

  @Test
  fun transcriptAwareEventSinkRestoresSupplementAttachmentsIntoUserReplayMessage() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-supplement-replay-attachments"))
    val workspaceRoot = temporaryFolder.newFolder("workspace-root-supplement-replay-attachments").toPath()
    val imagePath = workspaceRoot.resolve("screens").resolve("camera-first.png")
    Files.createDirectories(imagePath.parent)
    Files.write(imagePath, byteArrayOf(1, 2, 3, 4))
    val factory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
    )
    val sessionId = "session-1"
    val transcriptStore = factory.transcriptStoreForSession(sessionId)
    val eventSink = factory.transcriptAwareEventSinkForTest(
      sessionId = sessionId,
      transcriptStore = transcriptStore,
    )
    val resumeState = OpenCrayPromptResumeState(turnIndex = 2, toolCallCount = 1)

    eventSink.onRunEvent(
      promptTask("Inspect the image."),
      OpenCraySupplementEvent(
        runId = "run-1",
        taskId = "task-live-context",
        turn = 1,
        entryId = "supplement-image-1",
        text = "Inspect the attached workspace image directly.",
        metadata = OpenCrayPromptResumeMetadata.encodeToMetadata(resumeState, Json) +
          OpenCrayPromptSupplementMetadata.encodeMetadata(
            json = Json,
            attachments = listOf(
              RuntimeConversationAttachment(
                attachmentId = "workspace-image-test",
                kind = RuntimeConversationAttachmentKind.IMAGE,
                displayName = "camera-first.png",
                filePath = imagePath.toString().replace('\\', '/'),
                mimeType = "image/png",
              ),
            ),
          ),
        emittedAtEpochMs = 1_003L,
      ),
    )

    val snapshot = transcriptStore.snapshot()

    assertEquals(2, snapshot.size)
    assertEquals(RuntimeConversationRole.USER, snapshot[0].role)
    assertEquals("Inspect the attached workspace image directly.", snapshot[0].content)
    assertEquals(1, snapshot[0].attachments.size)
    assertEquals("camera-first.png", snapshot[0].attachments.single().displayName)
    assertEquals("image/png", snapshot[0].attachments.single().mimeType)
    assertTrue(
      Files.isSameFile(
        imagePath,
        java.nio.file.Paths.get(requireNotNull(snapshot[0].attachments.single().filePath)),
      ),
    )
  }

  @Test
  fun transcriptAwareEventSinkOmitsSupplementMetadataObjectWhenOnlyHiddenResumePayloadExists() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-supplement-replay-hidden-only"))
    val workspaceRoot = temporaryFolder.newFolder("workspace-root-supplement-replay-hidden-only").toPath()
    val factory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
    )
    val sessionId = "session-1"
    val transcriptStore = factory.transcriptStoreForSession(sessionId)
    val eventSink = factory.transcriptAwareEventSinkForTest(
      sessionId = sessionId,
      transcriptStore = transcriptStore,
    )
    val resumeState = OpenCrayPromptResumeState(turnIndex = 1, toolCallCount = 2)

    eventSink.onRunEvent(
      promptTask("Inspect the logs."),
      OpenCraySupplementEvent(
        runId = "run-1",
        taskId = "task-live-context",
        turn = 1,
        entryId = "supplement-1",
        text = "Also inspect the logs",
        metadata = OpenCrayPromptResumeMetadata.encodeToMetadata(resumeState, Json),
        emittedAtEpochMs = 1_001L,
      ),
    )

    val snapshot = transcriptStore.snapshot()
    val replay = Json.parseToJsonElement(snapshot[1].content).jsonObject

    assertEquals(2, snapshot.size)
    assertEquals(RuntimeConversationRole.USER, snapshot[0].role)
    assertEquals(RuntimeConversationRole.TOOL, snapshot[1].role)
    assertEquals("supplement", replay["event_kind"]?.jsonPrimitive?.content)
    assertEquals("supplement-1", replay["entry_id"]?.jsonPrimitive?.content)
    assertEquals("turn_start", replay["checkpoint"]?.jsonPrimitive?.content)
    assertFalse(replay.containsKey("metadata"))
  }

  @Test
  fun transcriptAwareEventSinkSkipsDuplicateSupplementReplayEntriesForSameEvent() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-supplement-replay-dedupe"))
    val workspaceRoot = temporaryFolder.newFolder("workspace-root-supplement-replay-dedupe").toPath()
    val factory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
    )
    val sessionId = "session-1"
    val transcriptStore = factory.transcriptStoreForSession(sessionId)
    val eventSink = factory.transcriptAwareEventSinkForTest(
      sessionId = sessionId,
      transcriptStore = transcriptStore,
    )
    val event = OpenCraySupplementEvent(
      runId = "run-1",
      taskId = "task-live-context",
      turn = 1,
      entryId = "supplement-1",
      text = "Also inspect the logs",
      metadata = mapOf("source" to "manual"),
      emittedAtEpochMs = 1_002L,
    )

    eventSink.onRunEvent(promptTask("Inspect the logs."), event)
    eventSink.onRunEvent(promptTask("Inspect the logs."), event)

    val snapshot = transcriptStore.snapshot()
    val replay = Json.parseToJsonElement(snapshot[1].content).jsonObject

    assertEquals(2, snapshot.size)
    assertEquals(1, snapshot.count { message -> message.role == RuntimeConversationRole.USER })
    assertEquals(1, snapshot.count { message -> message.role == RuntimeConversationRole.TOOL })
    assertEquals("Also inspect the logs", snapshot[0].content)
    assertEquals("supplement", replay["event_kind"]?.jsonPrimitive?.content)
    assertEquals("supplement-1", replay["entry_id"]?.jsonPrimitive?.content)
  }

  @Test
  fun transcriptStoreRetainsStartedAndCompletedSubagentReplayEntries() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-subagent-replay"))
    val workspaceRoot = temporaryFolder.newFolder("workspace-root-subagent-replay").toPath()
    val factory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
    )
    factory.recordSubAgentReplayEvent(
      sessionId = "session-1",
      event = OpenCraySubAgentEvent(
        runId = "run-parent",
        taskId = "task-parent",
        phase = OpenCraySubAgentPhase.STARTED,
        childRunId = "run-child",
        childTaskId = "task-child",
        label = "Inspect README",
        subagentType = "researcher",
        contextMode = "minimal",
        depth = 1,
        executionState = SubAgentExecutionState.RUNNING,
        continuationKind = SubAgentContinuationKind.NONE,
        emittedAtEpochMs = 1_000L,
      ),
    )
    factory.recordSubAgentReplayEvent(
      sessionId = "session-1",
      event = OpenCraySubAgentEvent(
        runId = "run-parent",
        taskId = "task-parent",
        phase = OpenCraySubAgentPhase.COMPLETED,
        childRunId = "run-child",
        childTaskId = "task-child",
        label = "Inspect README",
        subagentType = "researcher",
        contextMode = "minimal",
        depth = 1,
        summary = "README inspection finished.",
        executionState = SubAgentExecutionState.COMPLETED,
        continuationKind = SubAgentContinuationKind.NONE,
        emittedAtEpochMs = 1_001L,
      ),
    )

    val snapshot = factory.transcriptStoreForSession("session-1").snapshot()

    assertEquals(2, snapshot.size)
    assertTrue(snapshot[0].content.contains("\"phase\":\"started\""))
    assertTrue(snapshot[1].content.contains("\"phase\":\"completed\""))
    assertTrue(snapshot[1].content.contains("\"summary\":\"README inspection finished.\""))
  }

  @Test
  fun transcriptStorePersistsClosedSubagentReplayField() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-subagent-replay-closed"))
    val workspaceRoot = temporaryFolder.newFolder("workspace-root-subagent-replay-closed").toPath()
    val factory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
    )
    factory.recordSubAgentReplayEvent(
      sessionId = "session-closed",
      event = OpenCraySubAgentEvent(
        runId = "run-parent",
        taskId = "task-parent",
        agentId = "child-handle-1",
        phase = OpenCraySubAgentPhase.CANCELLED,
        childRunId = "run-child",
        childTaskId = "task-child",
        label = "Inspect README",
        subagentType = "researcher",
        contextMode = "minimal",
        depth = 1,
        summary = "Delegated child handle closed.",
        executionState = SubAgentExecutionState.CANCELLED,
        continuationKind = SubAgentContinuationKind.NONE,
        emittedAtEpochMs = 1_002L,
        closed = true,
      ),
    )

    val snapshot = factory.transcriptStoreForSession("session-closed").snapshot()

    assertEquals(1, snapshot.size)
    assertTrue(snapshot.single().content.contains("\"agent_id\":\"child-handle-1\""))
    assertTrue(snapshot.single().content.contains("\"closed\":true"))
    assertTrue(snapshot.single().content.contains("\"phase\":\"cancelled\""))
  }

  @Test
  fun recalledMemoryForUsesPersistedRecordsAndSessionAndWorkspaceFiltering() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-memory"))
    val personalizationStore = PersonalizationLocalStore(temporaryFolder.newFolder("personalization-memory"))
    val workspaceRoot = temporaryFolder.newFolder("workspace-root").toPath()
    val workspaceId = requireNotNull(AppWorkspaceIdentity.fromRoots(setOf(workspaceRoot)))
    personalizationStore.upsertMemoryRecord(
      memoryRecord(
        id = "pref-user",
        content = "Default to concise Chinese replies.",
        kind = "user_preference",
        scope = "user",
        sourceSessionId = "session-source",
      ),
    )
    personalizationStore.upsertMemoryRecord(
      memoryRecord(
        id = "fact-workspace",
        content = "Project uses the Gradle wrapper from the repo root.",
        kind = "project_fact",
        scope = "workspace",
        sourceSessionId = "session-source",
        workspaceId = workspaceId,
      ),
    )
    personalizationStore.upsertMemoryRecord(
      memoryRecord(
        id = "fact-other-workspace",
        content = "Project uses pnpm workspaces for package management.",
        kind = "project_fact",
        scope = "workspace",
        sourceSessionId = "session-source",
        workspaceId = "workspace-other",
      ),
    )
    personalizationStore.upsertMemoryRecord(
      memoryRecord(
        id = "commitment-other-session",
        content = "Finish the docs pass after the queue repair.",
        kind = "task_commitment",
        scope = "session",
        status = "open",
        sourceSessionId = "session-other",
      ),
    )
    val factory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
      memoryRecordsProvider = personalizationStore::listMemoryRecords,
    )

    val recalled = factory.recalledMemoryFor(
      sessionId = "session-main",
      taskInput = "Please keep using Chinese and verify the Gradle setup.",
    )

    assertEquals(2, recalled.memories.size)
    assertTrue(recalled.memories.any { memory -> memory.id == "pref-user" })
    assertTrue(recalled.memories.any { memory -> memory.id == "fact-workspace" })
    assertTrue(recalled.memories.none { memory -> memory.id == "fact-other-workspace" })
    assertTrue(recalled.memories.none { memory -> memory.id == "commitment-other-session" })
  }

  @Test
  fun effectiveSoulProfileForOverlaysUserIdentityPreferenceAndSessionStylePreference() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-soul-memory"))
    val workspaceRoot = temporaryFolder.newFolder("workspace-root-soul-memory").toPath()
    val sessionId = chatStore.loadState().activeSession.sessionId
    val factory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
    )

    val effective = factory.effectiveSoulProfileFor(
      sessionId = sessionId,
      soulProfile = WorkspaceSoulProfile(
        presetName = "BUILDER",
        customLabel = "",
        customGuidance = "Stay direct.",
      ),
      memoryRecords = listOf(
        memoryRecord(
          id = "agent-name",
          content = "Agent display name is Xiao Bai",
          kind = "user_preference",
          scope = "user",
          sourceSessionId = "session-source",
          preferenceKey = "agent_display_name",
          preferenceValue = "Xiao Bai",
        ),
        memoryRecord(
          id = "session-style",
          content = "Agent style profile should be warm",
          kind = "user_preference",
          scope = "session",
          sourceSessionId = sessionId,
          preferenceKey = "agent_style_profile",
          preferenceValue = "warm",
        ),
      ),
    )

    assertEquals("Xiao Bai", effective?.displayName)
    assertEquals("BUILDER", effective?.presetName)
    assertEquals("Stay direct.", effective?.customGuidance)
    assertEquals("warm and gentle", effective?.voice)
    assertEquals("warm", effective?.extensions?.get("tone"))
  }

  @Test
  fun prepareSessionContextAppliesDurablePreferenceStateAcrossSessionsWhileKeepingSessionStyleLocal() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-cross-session-soul"))
    val workspaceRoot = temporaryFolder.newFolder("workspace-root-cross-session-soul").toPath()
    val workspaceId = "workspace-cross-session-soul"
    val sessionOneId = chatStore.loadState().activeSession.sessionId
    val memoryStore = InMemoryMemoryStore()
    val coordinator = ChatMemoryIngestionCoordinator(
      memoryStore = memoryStore,
      workspaceIdProvider = { workspaceId },
      candidateExtractor = semanticUserCandidateExtractor(),
      sessionScopedStateMarker = { sessionId ->
        chatStore.setSessionScopedStatePresent(
          sessionId = sessionId,
          present = true,
        )
      },
    )
    val factory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
      memoryRecordsProvider = memoryStore::list,
      memoryIngestionCoordinator = coordinator,
    )

    coordinator.ingestCompletedTurn(
      sessionId = sessionOneId,
      task = AgentTask(
        id = "task-cross-session-soul-1",
        type = AgentTaskType.PROMPT,
        input = "以后对我温柔一点。以后叫我阿澄。以后称呼我亲切一点。",
        policyDecision = PolicyDecision(
          outcome = PolicyDecisionOutcome.ALLOW,
          reasonCode = "TEST_ALLOW",
        ),
        createdAtEpochMs = 1_000L,
      ),
      result = ExecutionResult(
        taskId = "task-cross-session-soul-1",
        status = ExecutionStatus.SUCCESS,
        stdout = "知道了。",
        startedAtEpochMs = 1_100L,
        finishedAtEpochMs = 1_200L,
      ),
      assistantOutput = "知道了。",
      toolObservations = emptyList(),
    )
    coordinator.ingestCompletedTurn(
      sessionId = sessionOneId,
      task = AgentTask(
        id = "task-cross-session-soul-2",
        type = AgentTaskType.PROMPT,
        input = "以后对我温柔一点。以后称呼我亲切一点。这次严肃一点。",
        policyDecision = PolicyDecision(
          outcome = PolicyDecisionOutcome.ALLOW,
          reasonCode = "TEST_ALLOW",
        ),
        createdAtEpochMs = 1_300L,
      ),
      result = ExecutionResult(
        taskId = "task-cross-session-soul-2",
        status = ExecutionStatus.SUCCESS,
        stdout = "知道了。",
        startedAtEpochMs = 1_400L,
        finishedAtEpochMs = 1_500L,
      ),
      assistantOutput = "知道了。",
      toolObservations = emptyList(),
    )

    val sessionOnePrepared = factory.prepareSessionContext(
      sessionId = sessionOneId,
      workspaceId = workspaceId,
      visibleThroughMessageId = null,
      excludedMessageIds = emptySet(),
      soulProfile = WorkspaceSoulProfile(
        presetName = "BUILDER",
        customLabel = "",
        customGuidance = "Stay direct.",
      ),
      taskType = AgentTaskType.PROMPT,
      taskId = "task-follow-up-session-one",
      taskInput = "继续。",
      transcriptStore = factory.transcriptStoreForSession(sessionOneId),
      memoryRecords = memoryStore.list(),
    )

    val sessionTwoId = chatStore.createSession().activeSession.sessionId
    assertTrue(sessionTwoId != sessionOneId)
    val sessionTwoPrepared = factory.prepareSessionContext(
      sessionId = sessionTwoId,
      workspaceId = workspaceId,
      visibleThroughMessageId = null,
      excludedMessageIds = emptySet(),
      soulProfile = WorkspaceSoulProfile(
        presetName = "BUILDER",
        customLabel = "",
        customGuidance = "Stay direct.",
      ),
      taskType = AgentTaskType.PROMPT,
      taskId = "task-follow-up-session-two",
      taskInput = "继续。",
      transcriptStore = factory.transcriptStoreForSession(sessionTwoId),
      memoryRecords = memoryStore.list(),
    )

    val sessionOneSoul = checkNotNull(sessionOnePrepared.sessionContext.soulProfile)
    val sessionTwoSoul = checkNotNull(sessionTwoPrepared.sessionContext.soulProfile)

    assertEquals("阿澄", sessionOneSoul.extensions[SoulProfileExtensionKeys.PREFERRED_NAMING])
    assertEquals("friendly", sessionOneSoul.extensions[SoulProfileExtensionKeys.PREFERRED_ADDRESS_STYLE])
    assertEquals("serious and formal", sessionOneSoul.voice)
    assertEquals("steady", sessionOneSoul.extensions[SoulProfileExtensionKeys.TONE])
    assertEquals("direct", sessionOneSoul.extensions[SoulProfileExtensionKeys.USER_RELATIONSHIP_STYLE])

    assertEquals("阿澄", sessionTwoSoul.extensions[SoulProfileExtensionKeys.PREFERRED_NAMING])
    assertEquals("friendly", sessionTwoSoul.extensions[SoulProfileExtensionKeys.PREFERRED_ADDRESS_STYLE])
    assertEquals("warm and gentle", sessionTwoSoul.voice)
    assertEquals("warm", sessionTwoSoul.extensions[SoulProfileExtensionKeys.TONE])
    assertEquals("supportive", sessionTwoSoul.extensions[SoulProfileExtensionKeys.USER_RELATIONSHIP_STYLE])
    assertEquals("Stay direct.", sessionTwoSoul.customGuidance)

    assertTrue(memoryStore.list().any { record ->
      record.extensions["soul_object_type"] == SoulMemoryObjectTypes.INTERACTION_PREFERENCE_STATE
    })
    assertTrue(memoryStore.list().any { record ->
      record.extensions["preference_key"] == "agent_style_profile" &&
        record.extensions["scope"] == "session" &&
        record.extensions["source_session_id"] == sessionOneId
    })
  }

  @Test
  fun visibleSkillInventoryForLoadsVisibleSkillsAndTracksInvalidEntries() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-skill-inventory"))
    val workspaceRoot = temporaryFolder.newFolder("workspace-root-skill-inventory").toPath()
    val skillsRoot = temporaryFolder.newFolder("skills-root-skill-inventory")
    writeSkill(
      root = skillsRoot,
      relativeDirectory = "ui-ux-pro-max",
      frontMatter = """
        name: ui-ux-pro-max
        description: High-end UI review workflow.
        invocation-control: explicit-only
        user-invocable: true
      """.trimIndent(),
      body = "# UI UX Pro Max",
    )
    writeSkill(
      root = skillsRoot,
      relativeDirectory = "broken-skill",
      frontMatter = """
        name: broken-skill
        description: Invalid because it is unreachable.
        invocation-control: explicit-only
        user-invocable: false
      """.trimIndent(),
      body = "# Broken",
    )
    val factory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { listOf(skillsRoot) },
      mcpReportProvider = { null },
    )

    val inventory = factory.visibleSkillInventoryFor()
    val skillCatalog = factory.skillCatalogFor()

    assertEquals(1, inventory.visibleSkillCount)
    assertEquals(1, inventory.invalidSkillCount)
    assertEquals(listOf("ui-ux-pro-max"), inventory.skills.map { skill -> skill.name })
    assertEquals(listOf("ui-ux-pro-max"), inventory.trace.visible.map { trace -> trace.name })
    assertEquals(0, inventory.trace.implicitSkillCount)
    assertEquals(listOf("ui-ux-pro-max"), skillCatalog.skillsByName.keys.toList())
    assertEquals("# UI UX Pro Max", skillCatalog.skillsByName["ui-ux-pro-max"]?.markdownBody?.trim())
  }

  @Test
  fun prepareSessionContextFlushesOmittedHistoryAndReloadsFreshMemoryRecords() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-memory-flush"))
    val workspaceRoot = temporaryFolder.newFolder("workspace-root-memory-flush").toPath()
    val workspaceId = "workspace-main"
    val sessionId = chatStore.loadState().activeSession.sessionId
    listOf(
      "Please default to Simplified Chinese for explanations.",
      "Do not use git reset --hard in this repo.",
      "Project uses the Gradle wrapper from the repo root.",
      "以后都用 PowerShell 命令。",
    ).forEach { text ->
      chatStore.appendMessage(
        sessionId = sessionId,
        role = com.opencray.persistence.model.ChatTranscriptRole.USER,
        text = text,
      )
    }
    (1..11).forEach { index ->
      chatStore.appendMessage(
        sessionId = sessionId,
        role = com.opencray.persistence.model.ChatTranscriptRole.USER,
        text = "Padding user message $index to keep the active transcript window bounded.",
      )
    }
    val memoryStore = InMemoryMemoryStore()
    val factory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
      memoryRecordsProvider = memoryStore::list,
      memoryIngestionCoordinator = ChatMemoryIngestionCoordinator(
        memoryStore = memoryStore,
        workspaceIdProvider = { workspaceId },
        candidateExtractor = semanticUserCandidateExtractor(),
      ),
    )

    val prepared = factory.prepareSessionContext(
      sessionId = sessionId,
      workspaceId = workspaceId,
      visibleThroughMessageId = null,
      excludedMessageIds = emptySet(),
      soulProfile = null,
      taskType = AgentTaskType.PROMPT,
      taskId = "task-memory-flush",
      taskInput = "Please keep using Chinese while continuing.",
      transcriptStore = factory.transcriptStoreForSession(sessionId),
      memoryRecords = memoryStore.list(),
      llmMetadata = mapOf("context_window_tokens" to "64"),
    )

    assertEquals(MemoryFlushOutcome.WRITTEN, prepared.sessionContext.memoryFlushTrace.outcome)
    assertTrue(prepared.effectiveMemoryRecords.isNotEmpty())
    assertTrue(prepared.effectiveMemoryRecords.any { record ->
      record.content.contains("Chinese") || record.content.contains("PowerShell")
    })
    assertTrue(prepared.sessionContext.recalledMemory.memories.any { memory ->
      memory.content.contains("Chinese")
    })
  }

  @Test
  fun prepareSessionContextAppendsPromptToSeededTranscriptWhenHistoryAlreadyExists() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-seeded-prompt-append"))
    val workspaceRoot = temporaryFolder.newFolder("workspace-root-seeded-prompt-append").toPath()
    val sessionId = chatStore.loadState().activeSession.sessionId
    chatStore.appendMessage(
      sessionId = sessionId,
      role = com.opencray.persistence.model.ChatTranscriptRole.USER,
      text = "Earlier message kept from chat history.",
    )
    val factory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
    )

    val prepared = factory.prepareSessionContext(
      sessionId = sessionId,
      workspaceId = "workspace-seeded-prompt-append",
      visibleThroughMessageId = null,
      excludedMessageIds = emptySet(),
      soulProfile = null,
      taskType = AgentTaskType.PROMPT,
      taskId = "task-seeded-prompt-append",
      taskInput = "New prompt should be appended to the seeded runtime transcript.",
      transcriptStore = factory.transcriptStoreForSession(sessionId),
      memoryRecords = emptyList(),
    )

    assertEquals(
      listOf(
        "Earlier message kept from chat history.",
        "New prompt should be appended to the seeded runtime transcript.",
      ),
      prepared.sessionContext.conversation.map { message -> message.content },
    )
    assertEquals(
      listOf(
        "Earlier message kept from chat history.",
        "New prompt should be appended to the seeded runtime transcript.",
      ),
      factory.transcriptStoreForSession(sessionId).snapshot().map { message -> message.content },
    )
  }

  @Test
  fun bootstrapContextForLoadsWorkspaceBootstrapFilesAndSupportsLightweightMode() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-bootstrap"))
    val workspaceRoot = temporaryFolder.newFolder("workspace-root-bootstrap")
    Files.write(
      workspaceRoot.toPath().resolve("AGENTS.md"),
      "# Agents\nFollow the repo instructions.".toByteArray(StandardCharsets.UTF_8),
    )
    Files.write(
      workspaceRoot.toPath().resolve("SOUL.md"),
      "# Soul\nStay terse.".toByteArray(StandardCharsets.UTF_8),
    )
    Files.write(
      workspaceRoot.toPath().resolve("PROJECT.md"),
      "# Project\nThis repo uses Gradle.".toByteArray(StandardCharsets.UTF_8),
    )
    val factory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot.toPath()) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
    )

    val fullContext = factory.bootstrapContextFor(BootstrapMode.FULL)
    val lightweightContext = factory.bootstrapContextFor(BootstrapMode.LIGHTWEIGHT)

    assertEquals(listOf("AGENTS.md", "SOUL.md", "PROJECT.md"), fullContext.files.map { file -> file.name })
    assertEquals(listOf("AGENTS.md", "PROJECT.md"), lightweightContext.files.map { file -> file.name })
    assertEquals("full", fullContext.trace.mode)
    assertEquals("lightweight", lightweightContext.trace.mode)
  }

  @Test
  fun prepareSessionContextInjectsBootstrapContextFromWorkspaceRoots() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-bootstrap-session"))
    val workspaceRoot = temporaryFolder.newFolder("workspace-root-bootstrap-session")
    val sessionId = chatStore.loadState().activeSession.sessionId
    Files.write(
      workspaceRoot.toPath().resolve("AGENTS.md"),
      "# Agents\nFollow the repo instructions.".toByteArray(StandardCharsets.UTF_8),
    )
    Files.write(
      workspaceRoot.toPath().resolve("PROJECT.md"),
      "# Project\nThis repo uses Gradle.".toByteArray(StandardCharsets.UTF_8),
    )
    val factory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot.toPath()) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
    )

    val prepared = factory.prepareSessionContext(
      sessionId = sessionId,
      workspaceId = "workspace-bootstrap",
      visibleThroughMessageId = null,
      excludedMessageIds = emptySet(),
      soulProfile = null,
      taskType = AgentTaskType.PROMPT,
      taskId = "task-bootstrap",
      taskInput = "Continue with workspace guidance.",
      transcriptStore = factory.transcriptStoreForSession(sessionId),
      memoryRecords = emptyList(),
    )

    assertEquals("full", prepared.sessionContext.bootstrapContext.trace.mode)
    assertEquals(listOf("AGENTS.md", "PROJECT.md"), prepared.sessionContext.bootstrapContext.files.map { file -> file.name })
    assertTrue(prepared.sessionContext.bootstrapContext.files.first().content.contains("Follow the repo instructions."))
  }

  @Test
  fun prepareSessionContextUsesConfiguredLiveContextBootstrapModes() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-root-live-bootstrap")
    Files.write(
      workspaceRoot.toPath().resolve("AGENTS.md"),
      "# Agents\nFollow the repo instructions.".toByteArray(StandardCharsets.UTF_8),
    )
    Files.write(
      workspaceRoot.toPath().resolve("SOUL.md"),
      "# Soul\nStay terse.".toByteArray(StandardCharsets.UTF_8),
    )
    Files.write(
      workspaceRoot.toPath().resolve("PROJECT.md"),
      "# Project\nThis repo uses Gradle.".toByteArray(StandardCharsets.UTF_8),
    )

    fun bootstrapFor(mode: LiveContextMode) = run {
      val chatStore = ChatSessionLocalStore(
        temporaryFolder.newFolder("chat-store-live-bootstrap-${mode.wireValue}"),
      )
      val sessionId = chatStore.loadState().activeSession.sessionId
      val factory = AppAgentSessionTaskRuntimeFactory(
        llmSettingsProvider = { LlmSettingsState() },
        sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
        soulProfileProvider = { null },
        workspaceRootsProvider = { setOf(workspaceRoot.toPath()) },
        skillsRootsProvider = { emptyList() },
        mcpReportProvider = { null },
      )
      factory.prepareSessionContext(
        sessionId = sessionId,
        workspaceId = "workspace-live-bootstrap",
        visibleThroughMessageId = null,
        excludedMessageIds = emptySet(),
        soulProfile = null,
        taskType = AgentTaskType.PROMPT,
        taskId = "task-live-bootstrap-${mode.wireValue}",
        taskInput = "Continue with workspace guidance.",
        transcriptStore = factory.transcriptStoreForSession(sessionId),
        memoryRecords = emptyList(),
        liveContextMode = mode,
      ).sessionContext.bootstrapContext
    }

    val full = bootstrapFor(LiveContextMode.FULL)
    val lightweight = bootstrapFor(LiveContextMode.LIGHTWEIGHT)
    val none = bootstrapFor(LiveContextMode.NONE)

    assertEquals("full", full.trace.mode)
    assertEquals(listOf("AGENTS.md", "SOUL.md", "PROJECT.md"), full.files.map { file -> file.name })
    assertEquals("lightweight", lightweight.trace.mode)
    assertEquals(listOf("AGENTS.md", "PROJECT.md"), lightweight.files.map { file -> file.name })
    assertEquals("none", none.trace.mode)
    assertTrue(none.files.isEmpty())
  }

  @Test
  fun prepareSessionContextSuppressesSoulForNoSoulModes() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-root-live-soul").toPath()
    val memoryRecords = listOf(
      memoryRecord(
        id = "agent-name",
        content = "Agent display name is Xiao Bai",
        kind = "user_preference",
        scope = "user",
        sourceSessionId = "session-source",
        preferenceKey = "agent_display_name",
        preferenceValue = "Xiao Bai",
      ),
    )

    fun soulFor(mode: LiveContextMode) = run {
      val chatStore = ChatSessionLocalStore(
        temporaryFolder.newFolder("chat-store-live-soul-${mode.wireValue}"),
      )
      val sessionId = chatStore.loadState().activeSession.sessionId
      val factory = AppAgentSessionTaskRuntimeFactory(
        llmSettingsProvider = { LlmSettingsState() },
        sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
        soulProfileProvider = { null },
        workspaceRootsProvider = { setOf(workspaceRoot) },
        skillsRootsProvider = { emptyList() },
        mcpReportProvider = { null },
      )
      factory.prepareSessionContext(
        sessionId = sessionId,
        workspaceId = "workspace-live-soul",
        visibleThroughMessageId = null,
        excludedMessageIds = emptySet(),
        soulProfile = WorkspaceSoulProfile(
          presetName = "BUILDER",
          customLabel = "",
          customGuidance = "Stay direct.",
        ),
        taskType = AgentTaskType.PROMPT,
        taskId = "task-live-soul-${mode.wireValue}",
        taskInput = "Keep going.",
        transcriptStore = factory.transcriptStoreForSession(sessionId),
        memoryRecords = memoryRecords,
        liveContextMode = mode,
      ).sessionContext.soulProfile
    }

    val fullSoul = soulFor(LiveContextMode.FULL)
    val noSoul = soulFor(LiveContextMode.NO_SOUL)
    val noMemoryOrSoul = soulFor(LiveContextMode.NO_MEMORY_OR_SOUL)

    assertEquals("BUILDER", fullSoul?.presetName)
    assertEquals("Stay direct.", fullSoul?.customGuidance)
    assertEquals("Xiao Bai", fullSoul?.displayName)
    assertNull(noSoul)
    assertNull(noMemoryOrSoul)
  }

  @Test
  fun prepareSessionContextDisablesAutomaticMemoryRecallForNoMemoryOrSoulMode() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-root-live-memory").toPath()
    val memoryRecords = listOf(
      memoryRecord(
        id = "pref-user",
        content = "Please default to Simplified Chinese for explanations.",
        kind = "user_preference",
        scope = "user",
        sourceSessionId = "session-source",
      ),
    )

    fun prepared(mode: LiveContextMode) = run {
      val chatStore = ChatSessionLocalStore(
        temporaryFolder.newFolder("chat-store-live-memory-${mode.wireValue}"),
      )
      val sessionId = chatStore.loadState().activeSession.sessionId
      val factory = AppAgentSessionTaskRuntimeFactory(
        llmSettingsProvider = { LlmSettingsState() },
        sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
        soulProfileProvider = { null },
        workspaceRootsProvider = { setOf(workspaceRoot) },
        skillsRootsProvider = { emptyList() },
        mcpReportProvider = { null },
      )
      factory.prepareSessionContext(
        sessionId = sessionId,
        workspaceId = "workspace-live-memory",
        visibleThroughMessageId = null,
        excludedMessageIds = emptySet(),
        soulProfile = null,
        taskType = AgentTaskType.PROMPT,
        taskId = "task-live-memory-${mode.wireValue}",
        taskInput = "Please keep using Chinese while continuing.",
        transcriptStore = factory.transcriptStoreForSession(sessionId),
        memoryRecords = memoryRecords,
        liveContextMode = mode,
      )
    }

    val fullPrepared = prepared(LiveContextMode.FULL)
    val noMemoryOrSoulPrepared = prepared(LiveContextMode.NO_MEMORY_OR_SOUL)

    assertTrue(fullPrepared.sessionContext.recalledMemory.memories.isNotEmpty())
    assertTrue(fullPrepared.sessionContext.injectionPolicy.soulContractEnabled)
    assertTrue(fullPrepared.sessionContext.injectionPolicy.automaticMemoryInjectionEnabled)
    assertFalse(noMemoryOrSoulPrepared.sessionContext.injectionPolicy.soulContractEnabled)
    assertFalse(noMemoryOrSoulPrepared.sessionContext.injectionPolicy.soulTurnPolicyEnabled)
    assertFalse(noMemoryOrSoulPrepared.sessionContext.injectionPolicy.automaticMemoryInjectionEnabled)
    assertFalse(noMemoryOrSoulPrepared.sessionContext.injectionPolicy.memoryDerivedPolicyEnabled)
    assertEquals("lightweight", noMemoryOrSoulPrepared.sessionContext.bootstrapContext.trace.mode)
    assertTrue(noMemoryOrSoulPrepared.effectiveMemoryRecords.isNotEmpty())
    assertTrue(noMemoryOrSoulPrepared.sessionContext.recalledMemory.memories.isEmpty())
  }

  @Test
  fun prepareSessionContextLiveModesFailCloseSoulAndMemoryPromptLayers() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-root-live-prompt").toPath()
    Files.write(
      workspaceRoot.resolve("AGENTS.md"),
      "# Agents\nFollow workspace instructions.".toByteArray(StandardCharsets.UTF_8),
    )
    Files.write(
      workspaceRoot.resolve("SOUL.md"),
      "# Soul\nStay warm.".toByteArray(StandardCharsets.UTF_8),
    )
    val memoryRecords = listOf(
      memoryRecord(
        id = "pref-language",
        content = "Default to concise Chinese replies.",
        kind = "user_preference",
        scope = "user",
        sourceSessionId = "session-source",
      ),
    )

    fun assembledFor(mode: LiveContextMode) = run {
      val chatStore = ChatSessionLocalStore(
        temporaryFolder.newFolder("chat-store-live-prompt-${mode.wireValue}"),
      )
      val sessionId = chatStore.loadState().activeSession.sessionId
      val factory = AppAgentSessionTaskRuntimeFactory(
        llmSettingsProvider = { LlmSettingsState() },
        sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
        soulProfileProvider = { null },
        workspaceRootsProvider = { setOf(workspaceRoot) },
        skillsRootsProvider = { emptyList() },
        mcpReportProvider = { null },
      )
      val prepared = factory.prepareSessionContext(
        sessionId = sessionId,
        workspaceId = "workspace-live-prompt",
        visibleThroughMessageId = null,
        excludedMessageIds = emptySet(),
        soulProfile = WorkspaceSoulProfile(
          presetName = "BUILDER",
          customLabel = "",
          customGuidance = "Stay direct.",
        ),
        taskType = AgentTaskType.PROMPT,
        taskId = "task-live-prompt-${mode.wireValue}",
        taskInput = "Please keep using Chinese while continuing.",
        transcriptStore = factory.transcriptStoreForSession(sessionId),
        memoryRecords = memoryRecords,
        liveContextMode = mode,
      )
      PromptAssembler().assemble(
        ContextManager().prepare(
          com.opencray.runtime.context.PromptAssemblyInput(
            task = promptTask(input = "Please keep using Chinese while continuing."),
            baseSystemPrompt = "Base identity.",
            sessionContext = prepared.sessionContext,
            toolDefinitions = emptyList(),
            liveConversation = prepared.sessionContext.conversation,
          ),
        ),
      )
    }

    val noSoulPrompt = assembledFor(LiveContextMode.NO_SOUL)
    val noMemoryOrSoulPrompt = assembledFor(LiveContextMode.NO_MEMORY_OR_SOUL)

    assertFalse(noSoulPrompt.systemPrompt.contains("[Personalization]"))
    assertFalse(noSoulPrompt.systemPrompt.contains("behavior_guidance:"))
    assertFalse(noSoulPrompt.systemPrompt.contains("[Bootstrap SOUL.md]"))
    assertTrue(noSoulPrompt.taskPrompt.contains("[Retrieved Memory]"))
    assertTrue(noSoulPrompt.taskPrompt.contains("Use recalled durable context"))

    assertFalse(noMemoryOrSoulPrompt.systemPrompt.contains("[Personalization]"))
    assertFalse(noMemoryOrSoulPrompt.systemPrompt.contains("behavior_guidance:"))
    assertFalse(noMemoryOrSoulPrompt.systemPrompt.contains("[Bootstrap SOUL.md]"))
    assertFalse(noMemoryOrSoulPrompt.taskPrompt.contains("[Retrieved Memory]"))
  }

  @Test
  fun prepareSessionContextCompactsOlderTranscriptIntoDurableSummaries() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-durable-compaction"))
    val workspaceRoot = temporaryFolder.newFolder("workspace-root-durable-compaction").toPath()
    val sessionId = chatStore.loadState().activeSession.sessionId
    (1..15).forEach { index ->
      chatStore.appendMessage(
        sessionId = sessionId,
        role = com.opencray.persistence.model.ChatTranscriptRole.USER,
        text = "Conversation message $index that should eventually move into durable compaction.",
      )
    }
    val factory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
    )

    val prepared = factory.prepareSessionContext(
      sessionId = sessionId,
      workspaceId = "workspace-durable",
      visibleThroughMessageId = null,
      excludedMessageIds = emptySet(),
      soulProfile = null,
      taskType = AgentTaskType.PROMPT,
      taskId = "task-durable-compaction",
      taskInput = "Continue after durable compaction.",
      transcriptStore = factory.transcriptStoreForSession(sessionId),
      memoryRecords = emptyList(),
      llmMetadata = mapOf("context_window_tokens" to "256"),
    )

    assertTrue(prepared.sessionContext.durableCompaction.included)
    assertTrue(prepared.sessionContext.durableCompaction.text.contains("Older session history has been durably compacted into summaries."))
    assertTrue(prepared.sessionContext.durableCompaction.trace.compactedThisRun)
    assertEquals(16, prepared.sessionContext.durableCompaction.trace.sourceTranscriptMessageCount)
    assertEquals(11, prepared.sessionContext.durableCompaction.trace.retainedTranscriptMessageCount)
    assertEquals(5, prepared.sessionContext.durableCompaction.trace.latestCompactedMessageCount)
    assertEquals(1, prepared.sessionContext.durableCompaction.trace.includedSummaryCount)
    assertEquals(5, prepared.sessionContext.durableCompaction.trace.totalCompactedMessageCount)
    assertEquals(11, prepared.sessionContext.conversation.size)
    assertEquals(11, factory.transcriptStoreForSession(sessionId).snapshot().size)
    assertFalse(prepared.sessionContext.conversation.first().content.contains("Conversation message 1"))
  }

  @Test
  fun prepareSessionContextUsesRemoteDurableCompactionWhenAvailable() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-remote-durable-compaction"))
    val workspaceRoot = temporaryFolder.newFolder("workspace-root-remote-durable-compaction").toPath()
    val sessionId = chatStore.loadState().activeSession.sessionId
    (1..15).forEach { index ->
      chatStore.appendMessage(
        sessionId = sessionId,
        role = com.opencray.persistence.model.ChatTranscriptRole.USER,
        text = "Remote compact candidate message $index that should be summarized by provider.",
      )
    }
    val remoteProvider = RecordingRemoteCompactionProvider(
      RemoteCompactionResult.Success(
        summary = CompactionSummary(
          text = "Provider-side compacted history capsule.",
          compactedMessageCount = 4,
          omittedUserMessageCount = 4,
        ),
        metadata = mapOf(
          LiteLlmMetadataKeys.RESPONSES_REMOTE_COMPACTION_REQUESTED to "true",
          LiteLlmMetadataKeys.RESPONSES_REMOTE_COMPACTION_USED to "true",
          LiteLlmMetadataKeys.RESPONSES_REMOTE_COMPACTION_TRIGGER_STAGE to "pre_compaction",
        ),
      ),
    )
    val factory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
    )

    val prepared = factory.prepareSessionContext(
      sessionId = sessionId,
      workspaceId = "workspace-remote-durable",
      visibleThroughMessageId = null,
      excludedMessageIds = emptySet(),
      soulProfile = null,
      taskType = AgentTaskType.PROMPT,
      taskId = "task-remote-durable-compaction",
      taskInput = "Continue after remote durable compaction.",
      transcriptStore = factory.transcriptStoreForSession(sessionId),
      memoryRecords = emptyList(),
      llmMetadata = mapOf("context_window_tokens" to "256"),
      remoteCompactionProvider = remoteProvider,
    )

    assertTrue(prepared.sessionContext.durableCompaction.included)
    assertTrue(prepared.sessionContext.durableCompaction.text.contains("Provider-side compacted history capsule."))
    assertFalse(
      prepared.sessionContext.durableCompaction.text.contains(
        "Compacted 5 older message",
        ignoreCase = true,
      ),
    )
    assertEquals(1, remoteProvider.requests.size)
    assertEquals("pre_compaction", remoteProvider.requests.single().triggerStage)
    assertEquals(5, remoteProvider.requests.single().omittedMessages.size)
    assertTrue(prepared.sessionContext.durableCompaction.trace.compactedThisRun)
    assertEquals(11, prepared.sessionContext.conversation.size)
    assertEquals(
      "true",
      prepared.sessionContext.durableCompaction.trace
        .remoteCompactionMetadata[LiteLlmMetadataKeys.RESPONSES_REMOTE_COMPACTION_USED],
    )
    assertEquals(
      "pre_compaction",
      prepared.sessionContext.durableCompaction.trace
        .remoteCompactionMetadata[LiteLlmMetadataKeys.RESPONSES_REMOTE_COMPACTION_TRIGGER_STAGE],
    )
  }

  private fun memoryRecord(
    id: String,
    content: String,
    kind: String,
    scope: String,
    sourceSessionId: String,
    status: String = "active",
    workspaceId: String? = null,
    preferenceKey: String? = null,
    preferenceValue: String? = null,
  ): MemoryRecord = MemoryRecord(
    id = id,
    content = content,
    createdAtEpochMs = 1_000L,
    updatedAtEpochMs = 1_001L,
    tags = listOf(
      "kind:$kind",
      "scope:$scope",
      "status:$status",
    ),
    extensions = mapOf(
      "kind" to kind,
      "scope" to scope,
      "status" to status,
      "source_session_id" to sourceSessionId,
    ) + listOfNotNull(
      workspaceId?.let { "workspace_id" to it },
      preferenceKey?.let { "preference_key" to it },
      preferenceValue?.let { "preference_value" to it },
    ).toMap(),
  )

  private fun writeSkill(
    root: File,
    relativeDirectory: String,
    frontMatter: String,
    body: String,
  ): File {
    val skillDirectory = root.resolve(relativeDirectory)
    Files.createDirectories(skillDirectory.toPath())
    val skillFile = skillDirectory.resolve("SKILL.md")
    val content = buildString {
      appendLine("---")
      appendLine(frontMatter)
      appendLine("---")
      appendLine(body)
    }
    Files.write(skillFile.toPath(), content.toByteArray(StandardCharsets.UTF_8))
    return skillFile
  }

  private fun semanticUserCandidateExtractor(): MemoryCandidateExtractor =
    MemoryCandidateExtractor(
      userIntentInterpreter = object : UserMemoryIntentInterpreter {
        override fun interpret(
          request: UserMemoryIntentRequest,
        ): UserMemoryIntentInterpretation {
          val input = request.userInput
          val intents = buildList {
            if (input.contains("Simplified Chinese", ignoreCase = true)) {
              add(
                UserMemoryIntent(
                  kind = MemoryKind.USER_PREFERENCE,
                  scope = MemoryScope.USER,
                  content = "Default to Simplified Chinese for explanations",
                ),
              )
            }
            if (input.contains("PowerShell", ignoreCase = true)) {
              add(
                UserMemoryIntent(
                  kind = MemoryKind.USER_PREFERENCE,
                  scope = MemoryScope.USER,
                  content = "Default to PowerShell commands",
                ),
              )
            }
            if (input.contains("git reset --hard", ignoreCase = true)) {
              add(
                UserMemoryIntent(
                  kind = MemoryKind.DURABLE_INSTRUCTION,
                  scope = MemoryScope.WORKSPACE,
                  content = "Do not use git reset --hard in this repo",
                ),
              )
            }
            if (input.contains("Gradle wrapper", ignoreCase = true)) {
              add(
                UserMemoryIntent(
                  kind = MemoryKind.PROJECT_FACT,
                  scope = MemoryScope.WORKSPACE,
                  content = "Project uses the Gradle wrapper from the repo root",
                ),
              )
            }
            if (input.contains("以后对我温柔一点")) {
              add(
                UserMemoryIntent(
                  kind = MemoryKind.USER_PREFERENCE,
                  scope = MemoryScope.USER,
                  preferenceKey = MemoryPreferenceKeys.INTERACTION_PREFERENCE_SIGNAL,
                  preferenceValue = "adaptive",
                  preferenceExtensions = mapOf(
                    MemoryInteractionPreferenceExtensionKeys.WARMTH_DIRECTION to "higher",
                    MemoryInteractionPreferenceExtensionKeys.FORMALITY_DIRECTION to "lower",
                  ),
                ),
              )
            }
            if (input.contains("以后叫我阿澄")) {
              add(
                UserMemoryIntent(
                  kind = MemoryKind.USER_PREFERENCE,
                  scope = MemoryScope.USER,
                  preferenceKey = MemoryPreferenceKeys.USER_PREFERRED_NAME,
                  preferenceValue = "阿澄",
                  soulExtensions = mapOf(
                    MemorySoulExtensionKeys.PREFERRED_NAMING to "阿澄",
                  ),
                ),
              )
            }
            if (input.contains("以后称呼我亲切一点")) {
              add(
                UserMemoryIntent(
                  kind = MemoryKind.USER_PREFERENCE,
                  scope = MemoryScope.USER,
                  preferenceKey = MemoryPreferenceKeys.USER_ADDRESS_STYLE,
                  preferenceValue = "friendly",
                  soulExtensions = mapOf(
                    MemorySoulExtensionKeys.PREFERRED_ADDRESS_STYLE to "friendly",
                  ),
                ),
              )
            }
            if (input.contains("这次严肃一点")) {
              add(
                UserMemoryIntent(
                  kind = MemoryKind.USER_PREFERENCE,
                  scope = MemoryScope.SESSION,
                  preferenceKey = MemoryPreferenceKeys.AGENT_STYLE_PROFILE,
                  preferenceValue = "serious",
                  soulExtensions = mapOf(
                    MemorySoulExtensionKeys.TONE to "steady",
                    MemorySoulExtensionKeys.VOICE to "serious and formal",
                    MemorySoulExtensionKeys.USER_RELATIONSHIP_STYLE to "direct",
                  ),
                ),
              )
            }
          }
          return UserMemoryIntentInterpretation.Success(intents = intents)
        }
      },
    )

  private fun promptTask(input: String): AgentTask = AgentTask(
    id = "task-live-context",
    type = AgentTaskType.PROMPT,
    input = input,
    policyDecision = PolicyDecision(
      outcome = PolicyDecisionOutcome.ALLOW,
      reasonCode = "TEST_ALLOW",
    ),
    createdAtEpochMs = 100L,
  )

  private fun AppAgentSessionTaskRuntimeFactory.transcriptAwareEventSinkForTest(
    sessionId: String,
    transcriptStore: SessionTranscriptStore,
  ): OpenCrayAgentRuntimeEventSink {
    val method = AppAgentSessionTaskRuntimeFactory::class.java.getDeclaredMethod(
      "transcriptAwareEventSink",
      String::class.java,
      SessionTranscriptStore::class.java,
      OpenCrayAgentRuntimeEventSink::class.java,
    )
    method.isAccessible = true
    return method.invoke(this, sessionId, transcriptStore, NoOpOpenCrayAgentRuntimeEventSink)
      as OpenCrayAgentRuntimeEventSink
  }

  private fun AppAgentSessionTaskRuntimeFactory.recordFinalAssistantTurnForTest(
    sessionId: String,
    task: AgentTask,
    result: ExecutionResult,
  ) {
    val method = AppAgentSessionTaskRuntimeFactory::class.java.getDeclaredMethod(
      "recordFinalAssistantTurn",
      String::class.java,
      AgentTask::class.java,
      ExecutionResult::class.java,
    )
    method.isAccessible = true
    method.invoke(this, sessionId, task, result)
  }

  private class InMemoryMemoryStore : MemoryStore {
    private val records = linkedMapOf<String, MemoryRecord>()

    override fun list(): List<MemoryRecord> = records.values.toList()

    override fun upsert(record: MemoryRecord) {
      records[record.id] = record
    }

    override fun delete(id: String): Boolean = records.remove(id) != null

    override fun clear(): Boolean {
      val hadRecords = records.isNotEmpty()
      records.clear()
      return hadRecords
    }
  }

  private class RecordingRemoteCompactionProvider(
    private val result: RemoteCompactionResult,
  ) : RemoteCompactionProvider {
    val requests = mutableListOf<RemoteCompactionRequest>()

    override fun compact(request: RemoteCompactionRequest): RemoteCompactionResult {
      requests += request
      return result
    }
  }
}
