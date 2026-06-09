package com.opencray.app

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskState
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.QueueTaskLifecycleState
import com.opencray.core.orchestrator.SessionLifecycleState
import com.opencray.core.orchestrator.SessionQueueSnapshot
import com.opencray.core.orchestrator.SessionQueueTaskSnapshot
import com.opencray.llm.LiteLlmMetadataKeys
import com.opencray.persistence.model.ChatAttachmentEntry
import com.opencray.persistence.model.ChatAttachmentKind
import com.opencray.runtime.AgentToolCall
import com.opencray.runtime.AgentToolResult
import com.opencray.runtime.AgentToolResultStatus
import com.opencray.runtime.OpenCrayAssistantEvent
import com.opencray.runtime.OpenCrayPromptCheckpointBoundary
import com.opencray.runtime.OpenCrayPromptCheckpointEmission
import com.opencray.runtime.OpenCrayPromptResumeState
import com.opencray.runtime.OpenCrayToolCallEvent
import com.opencray.runtime.OpenCrayToolResultEvent
import com.opencray.runtime.process.AgentProcessRegistry
import com.opencray.runtime.process.ManagedProcessSnapshot
import com.opencray.runtime.process.ManagedProcessStartRequest
import com.opencray.runtime.process.ManagedProcessStatus
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectionOnlyOpenCrayChatRuntimeGatewayTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun projectionOnlyChatSnapshotDoesNotEmbedRuntimeActivity() {
    val chatRoot = temporaryFolder.newFolder("projection-chat-runtime-activity-store")
    val runtimeRoot = temporaryFolder.newFolder("projection-runtime-activity-store")
    val chatStore = ChatSessionLocalStore(chatRoot)
    val sessionId = chatStore.loadState().activeSession.sessionId
    val gateway = ProjectionOnlyOpenCrayChatRuntimeGateway(
      chatSessionStore = chatStore,
      queueSnapshotStoreFactory = FileBackedAgentQueueSnapshotStoreFactory(runtimeRoot),
      runRecordStoreFactory = FileBackedAgentRunRecordStoreFactory(runtimeRoot),
      runEventJournalStoreFactory = FileBackedRunEventJournalStoreFactory(runtimeRoot),
      promptCheckpointStoreFactory = FileBackedPromptCheckpointStoreFactory(runtimeRoot),
      processRegistryFactory = FileBackedAgentProcessRegistryFactory(runtimeRoot),
      supplementStoreFactory = FileBackedAgentSessionSupplementStoreFactory(runtimeRoot),
      strings = projectionTestStrings(),
      connectionStateProvider = { RuntimeServiceConnectionState.inProcessFallback() },
      mainThreadPoster = ImmediateMainThreadPoster,
      clock = { 2_000L },
    )

    val chatSnapshot = gateway.loadChatSnapshot()
    val runtimeSnapshot = gateway.loadChatRuntimeSnapshot()

    assertNull(chatSnapshot["runtimeActivity"])
    assertEquals(sessionId, runtimeSnapshot["sessionId"])
  }

  @Test
  fun projectionOnlyChatRuntimeGatewayProjectsTerminalAssistantTextOverThinkingPlaceholder() {
    val chatRoot = temporaryFolder.newFolder("projection-chat-final-text-store")
    val runtimeRoot = temporaryFolder.newFolder("projection-runtime-final-text-store")
    val chatStore = ChatSessionLocalStore(chatRoot)
    val sessionId = chatStore.loadState().activeSession.sessionId
    val pendingMessageId = chatStore.reserveMessageId(
      com.opencray.persistence.model.ChatTranscriptRole.ASSISTANT,
    )
    chatStore.appendSubmittedTurn(
      sessionId = sessionId,
      userText = "Hello",
      assistantMessageId = pendingMessageId,
      assistantPlaceholderText = "Thinking",
    )

    val queueFactory = FileBackedAgentQueueSnapshotStoreFactory(runtimeRoot)
    val runRecordFactory = FileBackedAgentRunRecordStoreFactory(runtimeRoot)
    val journalFactory = FileBackedRunEventJournalStoreFactory(runtimeRoot)
    val checkpointFactory = FileBackedPromptCheckpointStoreFactory(runtimeRoot)
    val processFactory = FileBackedAgentProcessRegistryFactory(runtimeRoot)
    val supplementFactory = FileBackedAgentSessionSupplementStoreFactory(runtimeRoot)

    runRecordFactory.forChatSession(sessionId).upsert(
      PersistedAgentRunRecord(
        runId = "run-terminal-success",
        taskId = "task-terminal-success",
        acceptedAtEpochMs = 1_000L,
        pendingMessageId = pendingMessageId,
        lastResult = ExecutionResult(
          taskId = "task-terminal-success",
          status = ExecutionStatus.SUCCESS,
          stdout = "Final answer",
          startedAtEpochMs = 1_000L,
          finishedAtEpochMs = 1_500L,
        ),
      ),
    )

    val gateway = ProjectionOnlyOpenCrayChatRuntimeGateway(
      chatSessionStore = chatStore,
      queueSnapshotStoreFactory = queueFactory,
      runRecordStoreFactory = runRecordFactory,
      runEventJournalStoreFactory = journalFactory,
      promptCheckpointStoreFactory = checkpointFactory,
      processRegistryFactory = processFactory,
      supplementStoreFactory = supplementFactory,
      strings = projectionTestStrings(),
      connectionStateProvider = { RuntimeServiceConnectionState.inProcessFallback() },
      mainThreadPoster = ImmediateMainThreadPoster,
      clock = { 2_000L },
    )

    @Suppress("UNCHECKED_CAST")
    val messages = gateway.loadChatSnapshot()["messages"] as List<Map<String, Any?>>
    val assistantMessage = messages.last()

    assertEquals(pendingMessageId, assistantMessage["messageId"])
    assertEquals("Final answer", assistantMessage["text"])
  }

  @Test
  fun projectionOnlyChatRuntimeGatewayExtractsVisibleAnswerFromStructuredProtocolOutput() {
    val chatRoot = temporaryFolder.newFolder("projection-chat-structured-text-store")
    val runtimeRoot = temporaryFolder.newFolder("projection-runtime-structured-text-store")
    val chatStore = ChatSessionLocalStore(chatRoot)
    val sessionId = chatStore.loadState().activeSession.sessionId
    val pendingMessageId = chatStore.reserveMessageId(
      com.opencray.persistence.model.ChatTranscriptRole.ASSISTANT,
    )
    chatStore.appendSubmittedTurn(
      sessionId = sessionId,
      userText = "Search OpenCray",
      assistantMessageId = pendingMessageId,
      assistantPlaceholderText = "Thinking",
    )

    val queueFactory = FileBackedAgentQueueSnapshotStoreFactory(runtimeRoot)
    val runRecordFactory = FileBackedAgentRunRecordStoreFactory(runtimeRoot)
    val journalFactory = FileBackedRunEventJournalStoreFactory(runtimeRoot)
    val checkpointFactory = FileBackedPromptCheckpointStoreFactory(runtimeRoot)
    val processFactory = FileBackedAgentProcessRegistryFactory(runtimeRoot)
    val supplementFactory = FileBackedAgentSessionSupplementStoreFactory(runtimeRoot)

    runRecordFactory.forChatSession(sessionId).upsert(
      PersistedAgentRunRecord(
        runId = "run-structured-success",
        taskId = "task-structured-success",
        acceptedAtEpochMs = 1_000L,
        pendingMessageId = pendingMessageId,
        lastResult = ExecutionResult(
          taskId = "task-structured-success",
          status = ExecutionStatus.SUCCESS,
          stdout =
            """{"type":"tool_call","tool_name":"WebSearch","arguments":{"query":"OpenCray"}}{"type":"final","answer":"OpenCray is an open-source mobile agent app."}""",
          startedAtEpochMs = 1_000L,
          finishedAtEpochMs = 1_500L,
        ),
      ),
    )

    val gateway = ProjectionOnlyOpenCrayChatRuntimeGateway(
      chatSessionStore = chatStore,
      queueSnapshotStoreFactory = queueFactory,
      runRecordStoreFactory = runRecordFactory,
      runEventJournalStoreFactory = journalFactory,
      promptCheckpointStoreFactory = checkpointFactory,
      processRegistryFactory = processFactory,
      supplementStoreFactory = supplementFactory,
      strings = projectionTestStrings(),
      connectionStateProvider = { RuntimeServiceConnectionState.inProcessFallback() },
      mainThreadPoster = ImmediateMainThreadPoster,
      clock = { 2_000L },
    )

    @Suppress("UNCHECKED_CAST")
    val messages = gateway.loadChatSnapshot()["messages"] as List<Map<String, Any?>>
    val assistantMessage = messages.last()

    assertEquals(pendingMessageId, assistantMessage["messageId"])
    assertEquals(
      "OpenCray is an open-source mobile agent app.",
      assistantMessage["text"],
    )
  }

  @Test
  fun projectionOnlyChatRuntimeGatewayIncludesTopLevelUpdatedAtForDrawerOnlySessionChanges() {
    val chatRoot = temporaryFolder.newFolder("projection-chat-top-level-updated-at-store")
    val runtimeRoot = temporaryFolder.newFolder("projection-runtime-top-level-updated-at-store")
    val chatStore = ChatSessionLocalStore(chatRoot)
    val sessionId = chatStore.loadState().activeSession.sessionId

    val queueFactory = FileBackedAgentQueueSnapshotStoreFactory(runtimeRoot)
    val runRecordFactory = FileBackedAgentRunRecordStoreFactory(runtimeRoot)
    val journalFactory = FileBackedRunEventJournalStoreFactory(runtimeRoot)
    val checkpointFactory = FileBackedPromptCheckpointStoreFactory(runtimeRoot)
    val processFactory = FileBackedAgentProcessRegistryFactory(runtimeRoot)
    val supplementFactory = FileBackedAgentSessionSupplementStoreFactory(runtimeRoot)
    val gateway = ProjectionOnlyOpenCrayChatRuntimeGateway(
      chatSessionStore = chatStore,
      queueSnapshotStoreFactory = queueFactory,
      runRecordStoreFactory = runRecordFactory,
      runEventJournalStoreFactory = journalFactory,
      promptCheckpointStoreFactory = checkpointFactory,
      processRegistryFactory = processFactory,
      supplementStoreFactory = supplementFactory,
      strings = projectionTestStrings(),
      connectionStateProvider = { RuntimeServiceConnectionState.inProcessFallback() },
      mainThreadPoster = ImmediateMainThreadPoster,
      clock = { 2_000L },
    )

    val initialSnapshotUpdatedAtEpochMs =
      (gateway.loadChatSnapshot()["updatedAtEpochMs"] as Number).toLong()
    chatStore.copySession(sessionId)
    chatStore.selectSession(sessionId)
    val refreshedSnapshotUpdatedAtEpochMs =
      (gateway.loadChatSnapshot()["updatedAtEpochMs"] as Number).toLong()

    assertTrue(initialSnapshotUpdatedAtEpochMs > 0L)
    assertTrue(refreshedSnapshotUpdatedAtEpochMs > initialSnapshotUpdatedAtEpochMs)
  }

  @Test
  fun projectionOnlyChatRuntimeGatewayIgnoresCheckpointJournalEntriesWhenLoadingRunSnapshots() {
    val chatRoot = temporaryFolder.newFolder("projection-chat-checkpoint-store")
    val runtimeRoot = temporaryFolder.newFolder("projection-runtime-checkpoint-store")
    val chatStore = ChatSessionLocalStore(chatRoot)
    val sessionId = chatStore.loadState().activeSession.sessionId
    val runId = "run-checkpoint-only"
    val taskId = "task-checkpoint-only"

    val queueFactory = FileBackedAgentQueueSnapshotStoreFactory(runtimeRoot)
    val runRecordFactory = FileBackedAgentRunRecordStoreFactory(runtimeRoot)
    val journalFactory = FileBackedRunEventJournalStoreFactory(runtimeRoot)
    val checkpointFactory = FileBackedPromptCheckpointStoreFactory(runtimeRoot)
    val processFactory = FileBackedAgentProcessRegistryFactory(runtimeRoot)
    val supplementFactory = FileBackedAgentSessionSupplementStoreFactory(runtimeRoot)

    runRecordFactory.forChatSession(sessionId).upsert(
      PersistedAgentRunRecord(
        runId = runId,
        taskId = taskId,
        acceptedAtEpochMs = 1_000L,
      ),
    )
    journalFactory.forChatSession(sessionId).appendCheckpoint(
      runId = runId,
      taskId = taskId,
      emission = OpenCrayPromptCheckpointEmission(
        boundary = OpenCrayPromptCheckpointBoundary.ACTION_BATCH_PARSED,
        state = OpenCrayPromptResumeState(
          turnIndex = 1,
          toolCallCount = 0,
        ),
        emittedAtEpochMs = 1_100L,
      ),
    )

    val gateway = ProjectionOnlyOpenCrayChatRuntimeGateway(
      chatSessionStore = chatStore,
      queueSnapshotStoreFactory = queueFactory,
      runRecordStoreFactory = runRecordFactory,
      runEventJournalStoreFactory = journalFactory,
      promptCheckpointStoreFactory = checkpointFactory,
      processRegistryFactory = processFactory,
      supplementStoreFactory = supplementFactory,
      strings = projectionTestStrings(),
      connectionStateProvider = { RuntimeServiceConnectionState.inProcessFallback() },
      mainThreadPoster = ImmediateMainThreadPoster,
      clock = { 1_500L },
    )

    val runtimeSnapshot = gateway.loadChatRuntimeSnapshot()
    @Suppress("UNCHECKED_CAST")
    val events = runtimeSnapshot["events"] as List<Map<String, Any?>>
    val runSnapshot = gateway.loadChatRunSnapshot(runId)

    assertEquals(sessionId, runtimeSnapshot["sessionId"])
    assertTrue(events.isEmpty())
    assertEquals(runId, runSnapshot?.get("runId"))
    assertEquals(null, runSnapshot?.get("lastEvent"))
  }

  @Test
  fun projectionOnlyChatRuntimeGatewayRetainsTerminalRunsAndFullJournalHistory() {
    val chatRoot = temporaryFolder.newFolder("projection-chat-runtime-history-store")
    val runtimeRoot = temporaryFolder.newFolder("projection-runtime-history-store")
    val chatStore = ChatSessionLocalStore(chatRoot)
    val sessionId = chatStore.loadState().activeSession.sessionId
    val runId = "run-terminal-history"
    val taskId = "task-terminal-history"
    val fullToolContent = "README full content from the durable projection journal."

    val queueFactory = FileBackedAgentQueueSnapshotStoreFactory(runtimeRoot)
    val runRecordFactory = FileBackedAgentRunRecordStoreFactory(runtimeRoot)
    val journalFactory = FileBackedRunEventJournalStoreFactory(runtimeRoot)
    val checkpointFactory = FileBackedPromptCheckpointStoreFactory(runtimeRoot)
    val processFactory = FileBackedAgentProcessRegistryFactory(runtimeRoot)
    val supplementFactory = FileBackedAgentSessionSupplementStoreFactory(runtimeRoot)

    runRecordFactory.forChatSession(sessionId).upsert(
      PersistedAgentRunRecord(
        runId = runId,
        taskId = taskId,
        acceptedAtEpochMs = 1_000L,
        lastResult = ExecutionResult(
          taskId = taskId,
          status = ExecutionStatus.SUCCESS,
          stdout = "Final answer",
          startedAtEpochMs = 1_000L,
          finishedAtEpochMs = 2_000L,
        ),
      ),
    )

    val journalStore = journalFactory.forChatSession(sessionId)
    journalStore.append(
      OpenCrayToolCallEvent(
        runId = runId,
        taskId = taskId,
        turn = 0,
        call = AgentToolCall(
          toolName = "Read",
          reason = "Inspect README",
        ),
        emittedAtEpochMs = 1_100L,
      ),
    )
    for (index in 0 until 28) {
      journalStore.append(
        OpenCrayAssistantEvent(
          runId = runId,
          taskId = taskId,
          turn = index + 1,
          text = "Planning step ${index + 1}",
          stage = "Planning",
          emittedAtEpochMs = 1_101L + index,
        ),
      )
    }
    journalStore.append(
      OpenCrayToolResultEvent(
        runId = runId,
        taskId = taskId,
        turn = 29,
        call = AgentToolCall(
          toolName = "Read",
          reason = "Inspect README",
        ),
        result = AgentToolResult(
          toolName = "Read",
          status = AgentToolResultStatus.SUCCESS,
          content = fullToolContent,
          metadata = mapOf("filePath" to "README.md"),
        ),
        emittedAtEpochMs = 1_200L,
      ),
    )

    val gateway = ProjectionOnlyOpenCrayChatRuntimeGateway(
      chatSessionStore = chatStore,
      queueSnapshotStoreFactory = queueFactory,
      runRecordStoreFactory = runRecordFactory,
      runEventJournalStoreFactory = journalFactory,
      promptCheckpointStoreFactory = checkpointFactory,
      processRegistryFactory = processFactory,
      supplementStoreFactory = supplementFactory,
      strings = projectionTestStrings(),
      connectionStateProvider = { RuntimeServiceConnectionState.inProcessFallback() },
      mainThreadPoster = ImmediateMainThreadPoster,
      clock = { 2_500L },
    )

    val runtimeSnapshot = gateway.loadChatRuntimeSnapshot()
    @Suppress("UNCHECKED_CAST")
    val activeRuns = runtimeSnapshot["activeRuns"] as List<Map<String, Any?>>
    @Suppress("UNCHECKED_CAST")
    val retainedRuns = runtimeSnapshot["retainedRuns"] as List<Map<String, Any?>>
    @Suppress("UNCHECKED_CAST")
    val events = runtimeSnapshot["events"] as List<Map<String, Any?>>
    val toolResult = events.last { event -> event["kind"] == "tool_result" }

    assertTrue(activeRuns.isEmpty())
    assertEquals(1, retainedRuns.size)
    assertEquals(runId, retainedRuns.single()["runId"])
    assertEquals(30, events.size)
    assertEquals("tool_result", toolResult["kind"])
    assertEquals(fullToolContent, toolResult["content"])
    assertEquals("README.md", (toolResult["resultMetadata"] as Map<*, *>)["filePath"])
  }

  @Test
  fun projectionOnlyChatRuntimeGatewayIncludesManagedProcessSnapshotsOnRunPayloads() {
    val chatRoot = temporaryFolder.newFolder("projection-chat-run-process-store")
    val runtimeRoot = temporaryFolder.newFolder("projection-runtime-run-process-store")
    val chatStore = ChatSessionLocalStore(chatRoot)
    val sessionId = chatStore.loadState().activeSession.sessionId
    val runId = "run-process"
    val taskId = "task-process"

    val queueFactory = FileBackedAgentQueueSnapshotStoreFactory(runtimeRoot)
    val runRecordFactory = FileBackedAgentRunRecordStoreFactory(runtimeRoot)
    val journalFactory = FileBackedRunEventJournalStoreFactory(runtimeRoot)
    val checkpointFactory = FileBackedPromptCheckpointStoreFactory(runtimeRoot)
    val supplementFactory = FileBackedAgentSessionSupplementStoreFactory(runtimeRoot)

    runRecordFactory.forChatSession(sessionId).upsert(
      PersistedAgentRunRecord(
        runId = runId,
        taskId = taskId,
        acceptedAtEpochMs = 1_000L,
        managedProcessIds = listOf("proc-live"),
      ),
    )

    val fullStdout = "booting\nready on http://localhost:3000"
    val fullStderr = "warn: deprecated dependency"
    val processSnapshot = ManagedProcessSnapshot(
      processId = "proc-live",
      taskId = taskId,
      command = "npm",
      args = listOf("run", "dev"),
      workingDirectory = ".",
      status = ManagedProcessStatus.RUNNING,
      processStarted = true,
      timeoutMs = 120_000L,
      startedAtEpochMs = 1_200L,
      updatedAtEpochMs = 2_300L,
      stdout = fullStdout,
      stderr = fullStderr,
    )
    val processRegistryFactory = object : AgentProcessRegistryFactory {
      override fun forChatSession(sessionId: String): AgentProcessRegistry =
        object : AgentProcessRegistry {
          override fun start(request: ManagedProcessStartRequest): ManagedProcessSnapshot =
            processSnapshot

          override fun list(): List<ManagedProcessSnapshot> = listOf(processSnapshot)

          override fun read(processId: String): ManagedProcessSnapshot? =
            processSnapshot.takeIf { it.processId == processId }

          override fun wait(processId: String, timeoutMs: Long): ManagedProcessSnapshot? =
            processSnapshot.takeIf { it.processId == processId }

          override fun terminate(processId: String): ManagedProcessSnapshot? =
            processSnapshot.takeIf { it.processId == processId }
        }
    }

    val gateway = ProjectionOnlyOpenCrayChatRuntimeGateway(
      chatSessionStore = chatStore,
      queueSnapshotStoreFactory = queueFactory,
      runRecordStoreFactory = runRecordFactory,
      runEventJournalStoreFactory = journalFactory,
      promptCheckpointStoreFactory = checkpointFactory,
      processRegistryFactory = processRegistryFactory,
      supplementStoreFactory = supplementFactory,
      strings = projectionTestStrings(),
      connectionStateProvider = { RuntimeServiceConnectionState.inProcessFallback() },
      mainThreadPoster = ImmediateMainThreadPoster,
      clock = { 2_500L },
    )

    val runtimeSnapshot = gateway.loadChatRuntimeSnapshot()
    @Suppress("UNCHECKED_CAST")
    val activeRuns = runtimeSnapshot["activeRuns"] as List<Map<String, Any?>>
    val activeRun = activeRuns.single()
    @Suppress("UNCHECKED_CAST")
    val managedProcesses = activeRun["managedProcesses"] as List<Map<String, Any?>>
    val managedProcess = managedProcesses.single()

    assertEquals(listOf("proc-live"), activeRun["managedProcessIds"])
    assertEquals(1, activeRun["runningManagedProcessCount"])
    assertEquals(true, activeRun["hasLiveManagedProcesses"])
    assertEquals(1, managedProcesses.size)
    assertEquals("proc-live", managedProcess["processId"])
    assertEquals("npm", managedProcess["command"])
    assertEquals(fullStdout, managedProcess["stdout"])
    assertEquals(fullStderr, managedProcess["stderr"])
    assertEquals(fullStdout, managedProcess["stdoutPreview"])
    assertEquals(fullStderr, managedProcess["stderrPreview"])
  }

  @Test
  fun projectionOnlyChatRuntimeGatewayReadsManagedProcessSnapshotsByPersistedIdWhenListIsTrimmed() {
    val chatRoot = temporaryFolder.newFolder("projection-chat-run-process-read-fallback-store")
    val runtimeRoot = temporaryFolder.newFolder("projection-runtime-run-process-read-fallback-store")
    val chatStore = ChatSessionLocalStore(chatRoot)
    val sessionId = chatStore.loadState().activeSession.sessionId
    val runId = "run-process-read-fallback"
    val taskId = "task-process-read-fallback"

    val queueFactory = FileBackedAgentQueueSnapshotStoreFactory(runtimeRoot)
    val runRecordFactory = FileBackedAgentRunRecordStoreFactory(runtimeRoot)
    val journalFactory = FileBackedRunEventJournalStoreFactory(runtimeRoot)
    val checkpointFactory = FileBackedPromptCheckpointStoreFactory(runtimeRoot)
    val supplementFactory = FileBackedAgentSessionSupplementStoreFactory(runtimeRoot)
    val readRequests = mutableListOf<String>()

    runRecordFactory.forChatSession(sessionId).upsert(
      PersistedAgentRunRecord(
        runId = runId,
        taskId = taskId,
        acceptedAtEpochMs = 1_000L,
        managedProcessIds = listOf("proc-archived"),
      ),
    )

    val archivedSnapshot = ManagedProcessSnapshot(
      processId = "proc-archived",
      taskId = taskId,
      command = "npm",
      args = listOf("run", "build"),
      workingDirectory = ".",
      status = ManagedProcessStatus.SUCCESS,
      processStarted = true,
      timeoutMs = 120_000L,
      stdout = "build complete",
      startedAtEpochMs = 1_200L,
      updatedAtEpochMs = 2_300L,
      finishedAtEpochMs = 2_300L,
      exitCode = 0,
    )
    val processRegistryFactory = object : AgentProcessRegistryFactory {
      override fun forChatSession(sessionId: String): AgentProcessRegistry =
        object : AgentProcessRegistry {
          override fun start(request: ManagedProcessStartRequest): ManagedProcessSnapshot =
            archivedSnapshot

          override fun list(): List<ManagedProcessSnapshot> = emptyList()

          override fun read(processId: String): ManagedProcessSnapshot? {
            readRequests += processId
            return archivedSnapshot.takeIf { it.processId == processId }
          }

          override fun wait(processId: String, timeoutMs: Long): ManagedProcessSnapshot? =
            archivedSnapshot.takeIf { it.processId == processId }

          override fun terminate(processId: String): ManagedProcessSnapshot? =
            archivedSnapshot.takeIf { it.processId == processId }
        }
    }

    val gateway = ProjectionOnlyOpenCrayChatRuntimeGateway(
      chatSessionStore = chatStore,
      queueSnapshotStoreFactory = queueFactory,
      runRecordStoreFactory = runRecordFactory,
      runEventJournalStoreFactory = journalFactory,
      promptCheckpointStoreFactory = checkpointFactory,
      processRegistryFactory = processRegistryFactory,
      supplementStoreFactory = supplementFactory,
      strings = projectionTestStrings(),
      connectionStateProvider = { RuntimeServiceConnectionState.inProcessFallback() },
      mainThreadPoster = ImmediateMainThreadPoster,
      clock = { 2_500L },
    )

    val runtimeSnapshot = gateway.loadChatRuntimeSnapshot()
    @Suppress("UNCHECKED_CAST")
    val activeRuns = runtimeSnapshot["activeRuns"] as List<Map<String, Any?>>
    val activeRun = activeRuns.single()
    @Suppress("UNCHECKED_CAST")
    val managedProcesses = activeRun["managedProcesses"] as List<Map<String, Any?>>

    assertEquals(listOf("proc-archived"), readRequests)
    assertEquals(listOf("proc-archived"), activeRun["managedProcessIds"])
    assertEquals(1, managedProcesses.size)
    assertEquals("proc-archived", managedProcesses.single()["processId"])
    assertEquals("build complete", managedProcesses.single()["stdout"])
  }

  @Test
  fun projectionOnlyChatRuntimeGatewayKeepsPersistedManagedProcessIdsWhenArchivedReadMisses() {
    val chatRoot = temporaryFolder.newFolder("projection-chat-run-process-id-retention-store")
    val runtimeRoot = temporaryFolder.newFolder("projection-runtime-run-process-id-retention-store")
    val chatStore = ChatSessionLocalStore(chatRoot)
    val sessionId = chatStore.loadState().activeSession.sessionId
    val runId = "run-process-id-retention"
    val taskId = "task-process-id-retention"
    val readRequests = mutableListOf<String>()

    val queueFactory = FileBackedAgentQueueSnapshotStoreFactory(runtimeRoot)
    val runRecordFactory = FileBackedAgentRunRecordStoreFactory(runtimeRoot)
    val journalFactory = FileBackedRunEventJournalStoreFactory(runtimeRoot)
    val checkpointFactory = FileBackedPromptCheckpointStoreFactory(runtimeRoot)
    val supplementFactory = FileBackedAgentSessionSupplementStoreFactory(runtimeRoot)

    runRecordFactory.forChatSession(sessionId).upsert(
      PersistedAgentRunRecord(
        runId = runId,
        taskId = taskId,
        acceptedAtEpochMs = 1_000L,
        managedProcessIds = listOf("proc-missing-archived"),
      ),
    )

    val processRegistryFactory = object : AgentProcessRegistryFactory {
      override fun forChatSession(sessionId: String): AgentProcessRegistry =
        object : AgentProcessRegistry {
          override fun start(request: ManagedProcessStartRequest): ManagedProcessSnapshot =
            error("unused in test")

          override fun list(): List<ManagedProcessSnapshot> = emptyList()

          override fun read(processId: String): ManagedProcessSnapshot? {
            readRequests += processId
            return null
          }

          override fun wait(processId: String, timeoutMs: Long): ManagedProcessSnapshot? = null

          override fun terminate(processId: String): ManagedProcessSnapshot? = null
        }
    }

    val gateway = ProjectionOnlyOpenCrayChatRuntimeGateway(
      chatSessionStore = chatStore,
      queueSnapshotStoreFactory = queueFactory,
      runRecordStoreFactory = runRecordFactory,
      runEventJournalStoreFactory = journalFactory,
      promptCheckpointStoreFactory = checkpointFactory,
      processRegistryFactory = processRegistryFactory,
      supplementStoreFactory = supplementFactory,
      strings = projectionTestStrings(),
      connectionStateProvider = { RuntimeServiceConnectionState.inProcessFallback() },
      mainThreadPoster = ImmediateMainThreadPoster,
      clock = { 2_500L },
    )

    val runtimeSnapshot = gateway.loadChatRuntimeSnapshot()
    @Suppress("UNCHECKED_CAST")
    val activeRuns = runtimeSnapshot["activeRuns"] as List<Map<String, Any?>>
    val activeRun = activeRuns.single()
    @Suppress("UNCHECKED_CAST")
    val managedProcesses = activeRun["managedProcesses"] as List<Map<String, Any?>>

    assertEquals(listOf("proc-missing-archived"), readRequests)
    assertEquals(listOf("proc-missing-archived"), activeRun["managedProcessIds"])
    assertTrue(managedProcesses.isEmpty())
  }

  @Test
  fun projectionOnlyChatRuntimeGatewayReplaysProcessAndCommentaryBubblesAndPollingDraftEvents() {
    val chatRoot = temporaryFolder.newFolder("projection-chat-live-draft-process-store")
    val runtimeRoot = temporaryFolder.newFolder("projection-runtime-live-draft-process-store")
    val chatStore = ChatSessionLocalStore(chatRoot)
    val sessionId = chatStore.loadState().activeSession.sessionId
    val pendingMessageId = chatStore.reserveMessageId(
      com.opencray.persistence.model.ChatTranscriptRole.ASSISTANT,
    )
    chatStore.appendSubmittedTurn(
      sessionId = sessionId,
      userText = "Start the dev server",
      assistantMessageId = pendingMessageId,
      assistantPlaceholderText = "Thinking",
    )

    val queueFactory = FileBackedAgentQueueSnapshotStoreFactory(runtimeRoot)
    val runRecordFactory = FileBackedAgentRunRecordStoreFactory(runtimeRoot)
    val journalFactory = FileBackedRunEventJournalStoreFactory(runtimeRoot)
    val checkpointFactory = FileBackedPromptCheckpointStoreFactory(runtimeRoot)
    val supplementFactory = FileBackedAgentSessionSupplementStoreFactory(runtimeRoot)
    val runId = "run-live-projection"
    val taskId = "task-live-projection"
    val task = AgentTask(
      id = taskId,
      type = AgentTaskType.PROMPT,
      input = "Start the dev server",
      state = AgentTaskState.RUNNING,
      policyDecision = PolicyDecision(
        outcome = PolicyDecisionOutcome.ALLOW,
        reasonCode = "test",
      ),
      createdAtEpochMs = 1_000L,
      updatedAtEpochMs = 1_200L,
      metadata = mapOf(
        AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID to runId,
        AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID to pendingMessageId,
      ),
    )
    queueFactory.forChatSession(sessionId).save(
      SessionQueueSnapshot(
        sessionId = sessionId,
        agentId = "test-agent",
        lifecycleState = SessionLifecycleState.RUNNING,
        updatedAtEpochMs = 1_200L,
        tasks = listOf(
          SessionQueueTaskSnapshot(
            enqueueOrder = 1L,
            task = task,
            lifecycleState = QueueTaskLifecycleState.RUNNING,
            attempt = 1,
          ),
        ),
      ),
    )
    runRecordFactory.forChatSession(sessionId).upsert(
      PersistedAgentRunRecord(
        runId = runId,
        taskId = taskId,
        acceptedAtEpochMs = 1_000L,
        pendingMessageId = pendingMessageId,
        managedProcessIds = listOf("proc-live"),
      ),
    )
    val processSnapshot = ManagedProcessSnapshot(
      processId = "proc-live",
      taskId = taskId,
      command = "npm",
      args = listOf("run", "dev"),
      workingDirectory = ".",
      status = ManagedProcessStatus.RUNNING,
      processStarted = true,
      timeoutMs = 120_000L,
      startedAtEpochMs = 1_240L,
      updatedAtEpochMs = 1_260L,
      stdout = "ready on http://localhost:3000",
    )
    val processRegistryFactory = object : AgentProcessRegistryFactory {
      override fun forChatSession(sessionId: String): AgentProcessRegistry =
        object : AgentProcessRegistry {
          override fun start(request: ManagedProcessStartRequest): ManagedProcessSnapshot =
            processSnapshot

          override fun list(): List<ManagedProcessSnapshot> = listOf(processSnapshot)

          override fun read(processId: String): ManagedProcessSnapshot? =
            processSnapshot.takeIf { it.processId == processId }

          override fun wait(processId: String, timeoutMs: Long): ManagedProcessSnapshot? =
            processSnapshot.takeIf { it.processId == processId }

          override fun terminate(processId: String): ManagedProcessSnapshot? =
            processSnapshot.takeIf { it.processId == processId }
        }
    }
    val gateway = ProjectionOnlyOpenCrayChatRuntimeGateway(
      chatSessionStore = chatStore,
      queueSnapshotStoreFactory = queueFactory,
      runRecordStoreFactory = runRecordFactory,
      runEventJournalStoreFactory = journalFactory,
      promptCheckpointStoreFactory = checkpointFactory,
      processRegistryFactory = processRegistryFactory,
      supplementStoreFactory = supplementFactory,
      strings = projectionTestStrings(),
      connectionStateProvider = { RuntimeServiceConnectionState.inProcessFallback() },
      mainThreadPoster = ImmediateMainThreadPoster,
      clock = { 2_500L },
      pollIntervalMs = 10L,
    )
    val observedDraftEvents = mutableListOf<Map<String, Any?>>()
    val draftDisposer = gateway.observeLiveAssistantDraftEvents { payload ->
      observedDraftEvents += payload
    }

    try {
      val journalStore = journalFactory.forChatSession(sessionId)
      journalStore.append(
        OpenCrayAssistantEvent(
          runId = runId,
          taskId = taskId,
          turn = 1,
          text = "Inspecting the startup logs",
          stage = "Planning",
          emittedAtEpochMs = 1_210L,
        ),
      )
      journalStore.append(
        OpenCrayAssistantEvent(
          runId = runId,
          taskId = taskId,
          turn = 1,
          text = "Streaming answer",
          stage = "Draft",
          emittedAtEpochMs = 1_230L,
        ),
      )

      waitForCondition {
        observedDraftEvents.size == 1
      }

      val chatSnapshot = gateway.loadChatSnapshot()
      @Suppress("UNCHECKED_CAST")
      val messages = chatSnapshot["messages"] as List<Map<String, Any?>>
      val runtimeSnapshot = gateway.loadChatRuntimeSnapshot()
      @Suppress("UNCHECKED_CAST")
      val liveDrafts = runtimeSnapshot["liveAssistantDrafts"] as List<Map<String, Any?>>

      assertEquals(
        listOf(
          "Start the dev server",
          "Planning\n\nInspecting the startup logs",
          "Process proc-live\n\nrunning: npm run dev\n\nready on http://localhost:3000",
          "Thinking",
        ),
        messages.map { message -> message["text"] },
      )
      assertEquals(true, messages[1]["isEphemeral"])
      assertEquals(true, messages[2]["isEphemeral"])
      assertEquals(1, liveDrafts.size)
      assertEquals("Streaming answer", liveDrafts.single()["text"])
      assertEquals(pendingMessageId, liveDrafts.single()["pendingMessageId"])
      assertEquals(1, observedDraftEvents.size)
      assertEquals("Streaming answer", observedDraftEvents.single()["text"])
      assertEquals(false, observedDraftEvents.single()["cleared"])

      journalStore.append(
        OpenCrayAssistantEvent(
          runId = runId,
          taskId = taskId,
          turn = 2,
          text = "Inspecting the startup logs",
          stage = "Planning",
          emittedAtEpochMs = 1_240L,
        ),
      )

      waitForCondition {
        observedDraftEvents.size == 2
      }

      val clearedRuntimeSnapshot = gateway.loadChatRuntimeSnapshot()
      @Suppress("UNCHECKED_CAST")
      val clearedLiveDrafts = clearedRuntimeSnapshot["liveAssistantDrafts"] as List<Map<String, Any?>>

      assertEquals("", observedDraftEvents[1]["text"])
      assertEquals(true, observedDraftEvents[1]["cleared"])
      assertEquals(pendingMessageId, observedDraftEvents[1]["pendingMessageId"])
      assertTrue(clearedLiveDrafts.isEmpty())
    } finally {
      draftDisposer()
    }
  }

  @Test
  fun projectionDraftObserverEmitsClearedEventWhenPersistedDraftDisappears() {
    val runtimePayload = AtomicReference<Map<String, Any?>>(
      mapOf(
        "sessionId" to "session-test",
        "liveAssistantDrafts" to emptyList<Map<String, Any?>>(),
      ),
    )
    val observedDraftEvents = mutableListOf<Map<String, Any?>>()
    val draftDisposer = observeLiveAssistantDraftsWithPollingSnapshot(
      mainThreadPoster = ImmediateMainThreadPoster,
      runtimePayloadProvider = { runtimePayload.get() },
      listener = { payload -> observedDraftEvents += payload },
      pollIntervalMs = 10L,
    )

    try {
      runtimePayload.set(
        mapOf(
          "sessionId" to "session-test",
          "liveAssistantDrafts" to listOf(
            mapOf(
              "runId" to "run-live-projection-clear",
              "taskId" to "task-live-projection-clear",
              "pendingMessageId" to "pending-message-clear",
              "text" to "Streaming answer",
              "updatedAtEpochMs" to 1_230L,
            ),
          ),
        ),
      )

      waitForCondition {
        observedDraftEvents.size == 1
      }

      runtimePayload.set(
        mapOf(
          "sessionId" to "session-test",
          "liveAssistantDrafts" to emptyList<Map<String, Any?>>(),
        ),
      )

      waitForCondition {
        observedDraftEvents.size == 2
      }

      assertEquals(2, observedDraftEvents.size)
      assertEquals("Streaming answer", observedDraftEvents[0]["text"])
      assertEquals(false, observedDraftEvents[0]["cleared"])
      assertEquals("", observedDraftEvents[1]["text"])
      assertEquals(true, observedDraftEvents[1]["cleared"])
      assertEquals("pending-message-clear", observedDraftEvents[1]["pendingMessageId"])
    } finally {
      draftDisposer()
    }
  }

  @Test
  fun projectionOnlyChatRuntimeGatewayInterleavesManagedProcessMessagesWithRuntimeProgressByTimestamp() {
    val chatRoot = temporaryFolder.newFolder("projection-chat-global-order-store")
    val runtimeRoot = temporaryFolder.newFolder("projection-runtime-global-order-store")
    val chatStore = ChatSessionLocalStore(chatRoot)
    val sessionId = chatStore.loadState().activeSession.sessionId
    val pendingMessageId = chatStore.reserveMessageId(
      com.opencray.persistence.model.ChatTranscriptRole.ASSISTANT,
    )
    chatStore.appendSubmittedTurn(
      sessionId = sessionId,
      userText = "Start the dev server",
      assistantMessageId = pendingMessageId,
      assistantPlaceholderText = "Thinking",
    )

    val queueFactory = FileBackedAgentQueueSnapshotStoreFactory(runtimeRoot)
    val runRecordFactory = FileBackedAgentRunRecordStoreFactory(runtimeRoot)
    val journalFactory = FileBackedRunEventJournalStoreFactory(runtimeRoot)
    val checkpointFactory = FileBackedPromptCheckpointStoreFactory(runtimeRoot)
    val supplementFactory = FileBackedAgentSessionSupplementStoreFactory(runtimeRoot)
    val runId = "run-projection-global-order"
    val taskId = "task-projection-global-order"
    val task = AgentTask(
      id = taskId,
      type = AgentTaskType.PROMPT,
      input = "Start the dev server",
      state = AgentTaskState.RUNNING,
      policyDecision = PolicyDecision(
        outcome = PolicyDecisionOutcome.ALLOW,
        reasonCode = "test",
      ),
      createdAtEpochMs = 1_000L,
      updatedAtEpochMs = 1_200L,
      metadata = mapOf(
        AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID to runId,
        AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID to pendingMessageId,
      ),
    )
    queueFactory.forChatSession(sessionId).save(
      SessionQueueSnapshot(
        sessionId = sessionId,
        agentId = "test-agent",
        lifecycleState = SessionLifecycleState.RUNNING,
        updatedAtEpochMs = 1_200L,
        tasks = listOf(
          SessionQueueTaskSnapshot(
            enqueueOrder = 1L,
            task = task,
            lifecycleState = QueueTaskLifecycleState.RUNNING,
            attempt = 1,
          ),
        ),
      ),
    )
    runRecordFactory.forChatSession(sessionId).upsert(
      PersistedAgentRunRecord(
        runId = runId,
        taskId = taskId,
        acceptedAtEpochMs = 1_000L,
        pendingMessageId = pendingMessageId,
        managedProcessIds = listOf("proc-live"),
      ),
    )
    val processSnapshot = ManagedProcessSnapshot(
      processId = "proc-live",
      taskId = taskId,
      command = "npm",
      args = listOf("run", "dev"),
      workingDirectory = ".",
      status = ManagedProcessStatus.RUNNING,
      processStarted = true,
      timeoutMs = 120_000L,
      startedAtEpochMs = 1_220L,
      updatedAtEpochMs = 1_260L,
      stdout = "ready on http://localhost:3000",
    )
    val processRegistryFactory = object : AgentProcessRegistryFactory {
      override fun forChatSession(sessionId: String): AgentProcessRegistry =
        object : AgentProcessRegistry {
          override fun start(request: ManagedProcessStartRequest): ManagedProcessSnapshot =
            processSnapshot

          override fun list(): List<ManagedProcessSnapshot> = listOf(processSnapshot)

          override fun read(processId: String): ManagedProcessSnapshot? =
            processSnapshot.takeIf { it.processId == processId }

          override fun wait(processId: String, timeoutMs: Long): ManagedProcessSnapshot? =
            processSnapshot.takeIf { it.processId == processId }

          override fun terminate(processId: String): ManagedProcessSnapshot? =
            processSnapshot.takeIf { it.processId == processId }
        }
    }
    val journalStore = journalFactory.forChatSession(sessionId)
    journalStore.append(
      OpenCrayAssistantEvent(
        runId = runId,
        taskId = taskId,
        turn = 1,
        text = "Inspecting the startup logs",
        stage = "Planning",
        emittedAtEpochMs = 1_210L,
      ),
    )
    journalStore.append(
      OpenCrayAssistantEvent(
        runId = runId,
        taskId = taskId,
        turn = 2,
        text = "Waiting for the server to finish booting.",
        stage = "Planning",
        emittedAtEpochMs = 1_230L,
      ),
    )

    val gateway = ProjectionOnlyOpenCrayChatRuntimeGateway(
      chatSessionStore = chatStore,
      queueSnapshotStoreFactory = queueFactory,
      runRecordStoreFactory = runRecordFactory,
      runEventJournalStoreFactory = journalFactory,
      promptCheckpointStoreFactory = checkpointFactory,
      processRegistryFactory = processRegistryFactory,
      supplementStoreFactory = supplementFactory,
      strings = projectionTestStrings(),
      connectionStateProvider = { RuntimeServiceConnectionState.inProcessFallback() },
      mainThreadPoster = ImmediateMainThreadPoster,
      clock = { 2_500L },
    )

    val chatSnapshot = gateway.loadChatSnapshot()
    @Suppress("UNCHECKED_CAST")
    val messages = chatSnapshot["messages"] as List<Map<String, Any?>>

    assertEquals(
      listOf(
        "Start the dev server",
        "Planning\n\nInspecting the startup logs",
        "Process proc-live\n\nrunning: npm run dev\n\nready on http://localhost:3000",
        "Planning\n\nWaiting for the server to finish booting.",
        "Thinking",
      ),
      messages.map { message -> message["text"] },
    )
  }

  @Test
  fun projectionOnlyChatRuntimeGatewayKeepsHostParityInspectorFieldsOnRunPayloads() {
    val chatRoot = temporaryFolder.newFolder("projection-chat-run-inspector-parity-store")
    val runtimeRoot = temporaryFolder.newFolder("projection-runtime-run-inspector-parity-store")
    val chatStore = ChatSessionLocalStore(chatRoot)
    val sessionId = chatStore.loadState().activeSession.sessionId
    val pendingMessageId = chatStore.reserveMessageId(
      com.opencray.persistence.model.ChatTranscriptRole.ASSISTANT,
    )
    chatStore.appendSubmittedTurn(
      sessionId = sessionId,
      userText = "Send the artifact summary",
      assistantMessageId = pendingMessageId,
      assistantPlaceholderText = "Thinking",
    )
    chatStore.replaceMessage(
      sessionId = sessionId,
      messageId = pendingMessageId,
      role = com.opencray.persistence.model.ChatTranscriptRole.ASSISTANT,
      text = "",
      attachments = listOf(
        ChatAttachmentEntry(
          attachmentId = "attachment-1",
          kind = ChatAttachmentKind.IMAGE,
          displayName = "diagram.png",
          localPath = "attachments/final/diagram.png",
          mimeType = "image/png",
        ),
      ),
    )

    val queueFactory = FileBackedAgentQueueSnapshotStoreFactory(runtimeRoot)
    val runRecordFactory = FileBackedAgentRunRecordStoreFactory(runtimeRoot)
    val journalFactory = FileBackedRunEventJournalStoreFactory(runtimeRoot)
    val checkpointFactory = FileBackedPromptCheckpointStoreFactory(runtimeRoot)
    val processFactory = FileBackedAgentProcessRegistryFactory(runtimeRoot)
    val supplementFactory = FileBackedAgentSessionSupplementStoreFactory(runtimeRoot)
    val runId = "run-inspector-parity"
    val taskId = "task-inspector-parity"
    runRecordFactory.forChatSession(sessionId).upsert(
      PersistedAgentRunRecord(
        runId = runId,
        taskId = taskId,
        acceptedAtEpochMs = 1_000L,
        pendingMessageId = pendingMessageId,
        lastResult = ExecutionResult(
          taskId = taskId,
          status = ExecutionStatus.SUCCESS,
          stdout = "",
          startedAtEpochMs = 1_000L,
          finishedAtEpochMs = 1_400L,
          metadata = mapOf(
            LiteLlmMetadataKeys.PROVIDER_RESPONSE_SHAPE to "openai_tool_calls",
            "responsesPendingContextUpdateCount" to "1",
            "responsesPendingContextUpdateHash" to "projection-context-update-hash",
            "contextLiveMode" to "full",
            "contextBudgetApplied" to "true",
            "contextMatchedMemoryCount" to "1",
            "contextMemoryFlushOutcome" to "written",
            "contextMemoryFlushTriggerStage" to "pre_compaction",
            "contextMemoryFlushExecutionMode" to "inline",
            "contextMemoryFlushContextWindowTokens" to "128000",
            "contextMemoryFlushAutoCompactTokenLimit" to "115200",
            "contextMemoryFlushEstimatedReplayTokens" to "116000",
            "contextMemoryFlushTokenThresholdTriggered" to "true",
            "contextBootstrapMode" to "workspace",
            "contextDurableCompactionCompactedThisRun" to "true",
            "contextDurableCompactionTriggerStage" to "pre_compaction",
            "contextDurableCompactionExecutionMode" to "inline",
            "contextDurableCompactionContextWindowTokens" to "128000",
            "contextDurableCompactionAutoCompactTokenLimit" to "115200",
            "contextDurableCompactionEstimatedReplayTokens" to "116000",
            "contextDurableCompactionTokenThresholdTriggered" to "true",
            LiteLlmMetadataKeys.RESPONSES_REMOTE_COMPACTION_REQUESTED to "true",
            LiteLlmMetadataKeys.RESPONSES_REMOTE_COMPACTION_SUPPORTED to "true",
            LiteLlmMetadataKeys.RESPONSES_REMOTE_COMPACTION_USED to "true",
            LiteLlmMetadataKeys.RESPONSES_REMOTE_COMPACTION_TRIGGER_STAGE to "pre_compaction",
            LiteLlmMetadataKeys.RESPONSES_REMOTE_COMPACTION_OUTPUT_ITEM_COUNT to "2",
            LiteLlmMetadataKeys.RESPONSES_REMOTE_COMPACTION_ITEM_COUNT to "1",
            LiteLlmMetadataKeys.RESPONSES_REMOTE_COMPACTION_ENCRYPTED_CONTENT_COUNT to "1",
            "contextVisibleSkillCount" to "1",
            "contextActiveSkillName" to "ui-ux-pro-max",
            "contextActiveSkillPinned" to "true",
          ),
        ),
      ),
    )

    val gateway = ProjectionOnlyOpenCrayChatRuntimeGateway(
      chatSessionStore = chatStore,
      queueSnapshotStoreFactory = queueFactory,
      runRecordStoreFactory = runRecordFactory,
      runEventJournalStoreFactory = journalFactory,
      promptCheckpointStoreFactory = checkpointFactory,
      processRegistryFactory = processFactory,
      supplementStoreFactory = supplementFactory,
      strings = projectionTestStrings(),
      connectionStateProvider = { RuntimeServiceConnectionState.inProcessFallback() },
      mainThreadPoster = ImmediateMainThreadPoster,
      clock = { 2_000L },
    )

    val runSnapshot = requireNotNull(gateway.loadChatRunSnapshot(runId))
    @Suppress("UNCHECKED_CAST")
    val finalAttachments = runSnapshot["finalAttachments"] as List<Map<String, Any?>>
    val llmDiagnostics = runSnapshot["llmDiagnostics"] as Map<*, *>
    val liveContext = runSnapshot["liveContext"] as Map<*, *>
    val contextBudget = runSnapshot["contextBudget"] as Map<*, *>
    val memoryTrace = runSnapshot["memoryTrace"] as Map<*, *>
    val memoryFlush = runSnapshot["memoryFlush"] as Map<*, *>
    val bootstrap = runSnapshot["bootstrap"] as Map<*, *>
    val durableCompaction = runSnapshot["durableCompaction"] as Map<*, *>
    val skillInventory = runSnapshot["skillInventory"] as Map<*, *>
    val activeSkill = runSnapshot["activeSkill"] as Map<*, *>

    assertEquals("diagram.png", finalAttachments.single()["displayName"])
    assertEquals("openai_tool_calls", llmDiagnostics["providerResponseShape"])
    assertEquals(1, llmDiagnostics["responsesPendingContextUpdateCount"])
    assertEquals(
      "projection-context-update-hash",
      llmDiagnostics["responsesPendingContextUpdateHash"],
    )
    assertEquals("full", liveContext["mode"])
    assertEquals(true, contextBudget["applied"])
    assertEquals(1, memoryTrace["matchedRecordCount"])
    assertEquals("written", memoryFlush["outcome"])
    assertEquals("pre_compaction", memoryFlush["triggerStage"])
    assertEquals("inline", memoryFlush["executionMode"])
    assertEquals(128000, memoryFlush["contextWindowTokens"])
    assertEquals(115200, memoryFlush["autoCompactTokenLimit"])
    assertEquals(116000, memoryFlush["estimatedReplayTokens"])
    assertEquals(true, memoryFlush["tokenThresholdTriggered"])
    assertEquals("workspace", bootstrap["mode"])
    assertEquals(true, durableCompaction["compactedThisRun"])
    assertEquals("pre_compaction", durableCompaction["triggerStage"])
    assertEquals("inline", durableCompaction["executionMode"])
    assertEquals(128000, durableCompaction["contextWindowTokens"])
    assertEquals(115200, durableCompaction["autoCompactTokenLimit"])
    assertEquals(116000, durableCompaction["estimatedReplayTokens"])
    assertEquals(true, durableCompaction["tokenThresholdTriggered"])
    val remoteCompaction = durableCompaction["remoteCompaction"] as Map<*, *>
    assertEquals(true, remoteCompaction["requested"])
    assertEquals(true, remoteCompaction["supported"])
    assertEquals(true, remoteCompaction["used"])
    assertEquals("pre_compaction", remoteCompaction["triggerStage"])
    assertEquals(2, remoteCompaction["outputItemCount"])
    assertEquals(1, remoteCompaction["compactionItemCount"])
    assertEquals(1, remoteCompaction["encryptedContentCount"])
    assertEquals(1, skillInventory["visibleSkillCount"])
    assertEquals("ui-ux-pro-max", activeSkill["name"])
    assertEquals(true, activeSkill["pinned"])
  }

  private fun projectionTestStrings(): ProjectionOnlyChatStrings = ProjectionOnlyChatStrings(
    localeTag = "en",
    screenTitle = "Chat",
    modeLabel = "AUTO",
    sessionButtonLabel = "Sessions",
    recentSessionsEyebrow = "Recent sessions",
    recentSessionsTitle = "Recent sessions",
    newSessionLabel = "New session",
    defaultSessionTitle = "New chat",
    messagesBadge = { count -> "$count messages" },
    summaryReplyInProgress = "Reply in progress",
    summaryStartNewSession = "Start a new session",
    summaryRestored = "Restored",
    summaryApprovalRequired = "Approval required before the agent can continue.",
    approvalRequiredTitle = "Approval required",
    highRiskApprovalRequiredTitle = "High-risk approval required",
    highRiskApprovalRequiredBody =
      "High-risk approval required. Review this request carefully before approving.",
    approvalApproveLabel = "Approve",
    approvalApproveForSessionLabel = "Allow session",
    approvalRejectLabel = "Reject",
    composerPlaceholder = "Message OpenCray",
    composerRejectedPlaceholder = "Message OpenCray differently",
  )

  private fun waitForCondition(timeoutMs: Long = 1_000L, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      if (condition()) {
        return
      }
      Thread.sleep(10L)
    }
    assertTrue("Condition was not met within ${timeoutMs}ms.", condition())
  }
}
