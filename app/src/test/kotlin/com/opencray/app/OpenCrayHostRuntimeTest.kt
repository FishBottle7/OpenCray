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
import com.opencray.persistence.model.MemoryRecord
import com.opencray.persistence.store.MemoryStore
import com.opencray.runtime.AgentToolCall
import com.opencray.runtime.AgentToolResult
import com.opencray.runtime.AgentToolResultStatus
import com.opencray.runtime.OpenCrayLifecycleEvent
import com.opencray.runtime.OpenCrayRunLifecyclePhase
import com.opencray.runtime.memory.MemoryRecordExtensionKeys
import com.opencray.runtime.memory.MemoryWriter
import com.opencray.runtime.memory.TaskCommitmentResolver
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
    assertEquals(listOf(handle.submissions.single().taskId), handle.cancelledTaskIds)
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
    assertEquals(listOf(handle.submissions.single().taskId), handle.ensureProcessingTaskIds)
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
  fun taskSuccessRedactsInternalToolPayloadFromChatAndDrawerPreview() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-tool-payload-success"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Need a clean answer")
    val task = handle.submittedTasks.single()
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
        stdout = """{"type":"tool_call","tool_name":"Read","arguments":{"file_path":"README.md"}}""",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata,
      ),
    )

    val messages = chatStore.loadState().activeSession.messages
      .filter { message -> message.role != ChatTranscriptRole.SYSTEM }
    val snapshot = hostRuntime.loadChatSnapshot()
    val drawer = snapshot["drawer"] as Map<*, *>
    val sessions = drawer["sessions"] as List<*>
    val firstSession = sessions.first() as Map<*, *>

    assertEquals(
      "The agent produced an internal tool payload instead of a user-facing reply.",
      messages.last().text,
    )
    assertEquals(
      "The agent produced an internal tool payload instead of a user-facing reply.",
      firstSession["preview"],
    )
  }

  @Test
  fun taskSuccessWritesDeterministicMemoryAfterCompletion() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-memory-write"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val memoryStore = InMemoryMemoryStore()
    val workspaceRoot = temporaryFolder.newFolder("workspace-root").toPath()
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      memoryIngestionCoordinator = ChatMemoryIngestionCoordinator(
        memoryStore = memoryStore,
        workspaceIdProvider = { AppWorkspaceIdentity.fromRoots(setOf(workspaceRoot)) },
      ),
    )

    hostRuntime.submitChatMessage(
      """
        Please default to Simplified Chinese for explanations.
        Do not use git reset --hard in this repo.
      """.trimIndent(),
    )
    val task = handle.submittedTasks.single()
    val run = handle.submissions.single()
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = com.opencray.runtime.OpenCrayToolResultEvent(
        runId = run.runId,
        taskId = task.id,
        turn = 0,
        call = AgentToolCall(toolName = "Read"),
        result = AgentToolResult(
          toolName = "Read",
          status = AgentToolResultStatus.SUCCESS,
          content = "Project uses the Gradle wrapper from the repo root.",
        ),
        emittedAtEpochMs = 1_000L,
      ),
    )
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
        stdout = "Next I will run the targeted runtime tests.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata,
      ),
    )

    val writtenKinds = memoryStore.list().mapNotNull { record -> record.extensions["kind"] }.sorted()
    val runtimeActivity = hostRuntime.loadChatSnapshot()["runtimeActivity"] as Map<*, *>
    val events = runtimeActivity["events"] as List<*>
    val memoryEvent = events.last() as Map<*, *>

    assertEquals(
      listOf("durable_instruction", "project_fact", "task_commitment", "user_preference"),
      writtenKinds,
    )
    assertEquals("memory_write", memoryEvent["kind"])
    assertEquals(run.runId, memoryEvent["runId"])
    assertEquals(listOf("durable_instruction", "project_fact", "task_commitment", "user_preference"), memoryEvent["writtenKinds"])
  }

  @Test
  fun approvalRequiredTaskDoesNotWriteMemory() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-memory-approval"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val memoryStore = InMemoryMemoryStore()
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      memoryIngestionCoordinator = ChatMemoryIngestionCoordinator(memoryStore = memoryStore),
    )

    hostRuntime.submitChatMessage("Please default to PowerShell commands.")
    val task = handle.submittedTasks.single()
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.DENIED,
        errorCode = "APPROVAL_REQUIRED",
        errorMessage = "Approval is required before Write can run.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata,
      ),
    )

    assertTrue(memoryStore.list().isEmpty())
  }

  @Test
  fun taskSuccessReportsResolvedAndExpiredCommitmentsInMemoryWriteEvent() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-memory-maintenance"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val memoryStore = InMemoryMemoryStore().apply {
      upsert(
        taskCommitmentRecord(
          id = "commitment-open",
          content = "run the targeted runtime tests",
          sourceSessionId = activeSessionId,
          updatedAtEpochMs = 1_000L,
        ),
      )
      upsert(
        taskCommitmentRecord(
          id = "commitment-expired",
          content = "clean up the temporary transcript snapshot",
          sourceSessionId = activeSessionId,
          updatedAtEpochMs = 1_000L,
          ttlMs = 100L,
          lastConfirmedAtEpochMs = 1_050L,
        ),
      )
    }
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      memoryIngestionCoordinator = ChatMemoryIngestionCoordinator(
        memoryStore = memoryStore,
        writer = MemoryWriter(store = memoryStore, clock = { 2_000L }),
        taskCommitmentResolver = TaskCommitmentResolver(store = memoryStore, clock = { 2_000L }),
      ),
    )

    hostRuntime.submitChatMessage("Please continue.")
    val task = handle.submittedTasks.single()
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
        stdout = "I ran the targeted runtime tests and updated the docs.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata,
      ),
    )

    val runtimeActivity = hostRuntime.loadChatSnapshot()["runtimeActivity"] as Map<*, *>
    val events = runtimeActivity["events"] as List<*>
    val memoryEvent = events.last() as Map<*, *>

    assertEquals("memory_write", memoryEvent["kind"])
    assertEquals(listOf("commitment-open"), memoryEvent["resolvedRecordIds"])
    assertEquals(listOf("commitment-expired"), memoryEvent["expiredRecordIds"])
    assertEquals("resolved", memoryStore.list().single { record -> record.id == "commitment-open" }.extensions["status"])
    assertTrue(memoryStore.list().none { record -> record.id == "commitment-expired" })
  }

  @Test
  fun memoryWriteFailureDoesNotBreakTaskCompletionPath() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-memory-failure"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      memoryIngestionCoordinator = ChatMemoryIngestionCoordinator(memoryStore = FailingMemoryStore()),
    )

    hostRuntime.submitChatMessage("Please default to Chinese replies.")
    val task = handle.submittedTasks.single()
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
        stdout = "All good.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata,
      ),
    )

    val messages = chatStore.loadState().activeSession.messages
      .filter { message -> message.role != ChatTranscriptRole.SYSTEM }

    assertEquals("All good.", messages.last().text)
  }

  @Test
  fun approvalRequiredFailureAppearsInPendingApprovals() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-approval-pending"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Need approval")
    val task = handle.submittedTasks.single()
    val run = handle.submissions.single()
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.DENIED,
        errorCode = "APPROVAL_REQUIRED",
        errorMessage = "Approval is required before Write can run.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata,
      ),
    )

    val snapshot = hostRuntime.loadChatSnapshot()
    val pendingApprovals = snapshot["pendingApprovals"] as List<*>
    val pendingApproval = pendingApprovals.single() as Map<*, *>
    val messages = chatStore.loadState().activeSession.messages
      .filter { message -> message.role != ChatTranscriptRole.SYSTEM }

    assertEquals("Approval is required before Write can run.", messages.last().text)
    assertEquals(run.runId, pendingApproval["runId"])
    assertEquals(task.id, pendingApproval["taskId"])
    assertEquals("standard", pendingApproval["risk"])
    assertEquals("Approval is required before Write can run.", pendingApproval["body"])
  }

  @Test
  fun approvalRequiredFailureRedactsInternalToolPayloadFromBubbleAndApprovalBody() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-approval-redaction"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Need approval")
    val task = handle.submittedTasks.single()
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.DENIED,
        errorCode = "APPROVAL_REQUIRED",
        errorMessage = """{"type":"tool_call","tool_name":"Write","arguments":{"file_path":"note.txt","content":"secret"}}""",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata,
      ),
    )

    val snapshot = hostRuntime.loadChatSnapshot()
    val pendingApprovals = snapshot["pendingApprovals"] as List<*>
    val pendingApproval = pendingApprovals.single() as Map<*, *>
    val messages = chatStore.loadState().activeSession.messages
      .filter { message -> message.role != ChatTranscriptRole.SYSTEM }

    assertEquals("Approval required before the agent can continue.", messages.last().text)
    assertEquals("Approval required before the agent can continue.", pendingApproval["body"])
  }

  @Test
  fun approvalRequiredFailureIncludesToolReasonInPendingApprovalBody() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-approval-reason"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Need approval")
    val task = handle.submittedTasks.single()
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.DENIED,
        errorCode = "APPROVAL_REQUIRED",
        errorMessage = "Approval is required before Write can run.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata + mapOf(
          "toolReason" to "Need to update notes.txt before answering.",
        ),
      ),
    )

    val snapshot = hostRuntime.loadChatSnapshot()
    val pendingApprovals = snapshot["pendingApprovals"] as List<*>
    val pendingApproval = pendingApprovals.single() as Map<*, *>

    assertEquals(
      "Approval is required before Write can run.\nReason: Need to update notes.txt before answering.",
      pendingApproval["body"],
    )
  }

  @Test
  fun chatSnapshotIncludesRuntimeActivityEvents() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-runtime-activity"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Need runtime events")
    val task = handle.submittedTasks.single()
    val run = handle.submissions.single()
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = OpenCrayLifecycleEvent(
        runId = run.runId,
        taskId = task.id,
        phase = OpenCrayRunLifecyclePhase.START,
        emittedAtEpochMs = 1_100L,
      ),
    )

    val snapshot = hostRuntime.loadChatSnapshot()
    val runtimeActivity = snapshot["runtimeActivity"] as Map<*, *>
    val events = runtimeActivity["events"] as List<*>
    val firstEvent = events.single() as Map<*, *>

    assertEquals(activeSessionId, runtimeActivity["sessionId"])
    assertEquals("lifecycle", firstEvent["kind"])
    assertEquals("start", firstEvent["phase"])
    assertEquals(run.runId, firstEvent["runId"])
  }

  @Test
  fun submitChatMessageReturnsRunSubmissionAndRunSnapshot() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-run-submission"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    val submission = hostRuntime.submitChatMessage("Need a run id")!!
    val runId = submission["runId"] as String
    val runSnapshot = hostRuntime.loadChatRunSnapshot(runId)

    assertEquals(activeSessionId, submission["sessionId"])
    assertEquals(handle.submissions.single().taskId, submission["taskId"])
    assertEquals(runId, runSnapshot?.get("runId"))
    assertEquals(handle.submissions.single().taskId, runSnapshot?.get("taskId"))
  }

  @Test
  fun approveChatApprovalResumesTaskAndRestoresThinkingPlaceholder() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-approval-resume"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
      resumeResult = true,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    val run = hostRuntime.submitChatMessage("Need approval")!!
    val task = handle.submittedTasks.single()
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.DENIED,
        errorCode = "HIGH_RISK_APPROVAL_REQUIRED",
        errorMessage = "High-risk approval required. Review this request carefully before approving. Approval is required before command_exec can run.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata,
      ),
    )

    hostRuntime.approveChatApproval(run["runId"] as String)

    val snapshot = hostRuntime.loadChatSnapshot()
    val pendingApprovals = snapshot["pendingApprovals"] as List<*>
    val messages = chatStore.loadState().activeSession.messages
      .filter { message -> message.role != ChatTranscriptRole.SYSTEM }

    assertEquals(listOf(task.id), handle.resumedTaskIds)
    assertTrue(pendingApprovals.isEmpty())
    assertEquals("Thinking", messages.last().text)
  }

  @Test
  fun rejectChatApprovalHidesPendingApprovalWithoutRetry() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-approval-reject"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
      resumeResult = true,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    val run = hostRuntime.submitChatMessage("Need approval")!!
    val task = handle.submittedTasks.single()
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.DENIED,
        errorCode = "APPROVAL_REQUIRED",
        errorMessage = "Approval is required before Write can run.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata,
      ),
    )

    hostRuntime.rejectChatApproval(run["runId"] as String)

    val snapshot = hostRuntime.loadChatSnapshot()
    val pendingApprovals = snapshot["pendingApprovals"] as List<*>

    assertEquals(listOf(task.id), handle.cancelledTaskIds)
    assertTrue(handle.resumedTaskIds.isEmpty())
    assertTrue(pendingApprovals.isEmpty())
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
    memoryIngestionCoordinator: ChatMemoryIngestionCoordinator? = null,
  ): OpenCrayHostRuntime = OpenCrayHostRuntime.createForTest(
    stateStore = AppShellStateStore(InMemoryAppShellKeyValueStore()),
    chatSessionStore = chatStore,
    settingsFacade = NoOpSettingsFacade,
    llmConfigFacade = llmConfigFacade,
    personalizationFacade = personalizationFacade,
    mcpSettingsFacade = mcpSettingsFacade,
    sessionRuntimeManager = runtimeManager,
    memoryIngestionCoordinator = memoryIngestionCoordinator,
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

    fun emitRunEvent(
      sessionId: String,
      task: AgentTask,
      event: com.opencray.runtime.OpenCrayAgentRunEvent,
    ) {
      listeners.forEach { listener ->
        listener.onRunEvent(sessionId, task, event)
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
    private val resumeResult: Boolean = false,
  ) : AgentSessionHandle {
    val submittedInputs = mutableListOf<String>()
    val submittedTasks = mutableListOf<AgentTask>()
    val submissions = mutableListOf<AgentRunSubmission>()
    val ensureProcessingTaskIds = mutableListOf<String>()
    val cancelledTaskIds = mutableListOf<String>()
    val resumedTaskIds = mutableListOf<String>()
    private var lastSubmittedTaskId: String? = null
    private val runSnapshotsById = linkedMapOf<String, AgentRunSnapshot>()

    override fun submitPrompt(
      userText: String,
      pendingMessageId: String,
      visibleThroughMessageId: String,
      policyDecision: PolicyDecision,
      metadata: Map<String, String>,
    ): AgentRunSubmission {
      submitFailure?.let { throw it }
      submittedInputs += userText
      val taskId = "task-${submittedTasks.size + 1}"
      val runId = "run-${submittedTasks.size + 1}"
      lastSubmittedTaskId = taskId
      val task = AgentTask(
        id = taskId,
        type = AgentTaskType.PROMPT,
        input = userText,
        policyDecision = PolicyDecision(
          outcome = PolicyDecisionOutcome.ALLOW,
          reasonCode = "TEST_ALLOW",
        ),
        metadata = metadata + mapOf(
          AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID to runId,
          AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID to pendingMessageId,
          AppAgentSessionTaskRuntimeFactory.METADATA_VISIBLE_THROUGH_MESSAGE_ID to visibleThroughMessageId,
        ),
        createdAtEpochMs = 1_000L,
      )
      val submission = AgentRunSubmission(
        sessionId = sessionId,
        runId = runId,
        taskId = taskId,
        acceptedAtEpochMs = 1_000L,
      )
      submittedTasks += task
      submissions += submission
      runSnapshotsById[runId] = AgentRunSnapshot(
        sessionId = sessionId,
        runId = runId,
        taskId = taskId,
        acceptedAtEpochMs = 1_000L,
        updatedAtEpochMs = 1_000L,
        lifecycleState = null,
        taskState = null,
        pendingMessageId = pendingMessageId,
      )
      return submission
    }

    override fun ensureProcessing() {
      lastSubmittedTaskId?.let(ensureProcessingTaskIds::add)
    }

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

    override fun listRuns(): List<AgentRunSnapshot> = runSnapshotsById.values.toList()

    override fun findRun(runId: String): AgentRunSnapshot? = runSnapshotsById[runId]

    override fun waitForRun(runId: String, timeoutMs: Long): AgentRunSnapshot? = findRun(runId)

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

  private fun taskCommitmentRecord(
    id: String,
    content: String,
    sourceSessionId: String,
    updatedAtEpochMs: Long,
    ttlMs: Long = 14L * 24L * 60L * 60L * 1000L,
    lastConfirmedAtEpochMs: Long = updatedAtEpochMs,
  ): MemoryRecord = MemoryRecord(
    id = id,
    content = content,
    createdAtEpochMs = updatedAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs,
    tags = listOf(
      "kind:task_commitment",
      "scope:session",
      "status:open",
    ),
    extensions = mapOf(
      MemoryRecordExtensionKeys.KIND to "task_commitment",
      MemoryRecordExtensionKeys.SCOPE to "session",
      MemoryRecordExtensionKeys.STATUS to "open",
      MemoryRecordExtensionKeys.SOURCE_SESSION_ID to sourceSessionId,
      MemoryRecordExtensionKeys.TTL_MS to ttlMs.toString(),
      MemoryRecordExtensionKeys.LAST_CONFIRMED_AT_EPOCH_MS to lastConfirmedAtEpochMs.toString(),
    ),
  )

  private class FailingMemoryStore : MemoryStore {
    override fun list(): List<MemoryRecord> = emptyList()

    override fun upsert(record: MemoryRecord) {
      error("memory store unavailable")
    }

    override fun delete(id: String): Boolean = false

    override fun clear(): Boolean = false
  }
}
