package com.opencray.app

import com.opencray.core.contracts.AgentTaskState
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.orchestrator.QueueTaskLifecycleState
import com.opencray.persistence.model.ChatTranscriptRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostApprovalFlowTest : HostRuntimeTestBase() {
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
      "Agent reason: Need to update notes.txt before answering.\n\nApproval is required before Write can run.",
      pendingApproval["body"],
    )
    assertEquals("Need to update notes.txt before answering.", pendingApproval["reason"])
    assertEquals("Approval is required before Write can run.", pendingApproval["message"])
  }

  @Test
  fun approvalRequiredFailureIncludesToolNameAndConcreteRequestDetailsInPendingApprovalBody() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-approval-details"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Need shell approval")
    val task = handle.submittedTasks.single()
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.DENIED,
        errorCode = "APPROVAL_REQUIRED",
        errorMessage = "Approval is required before Bash can run.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata + mapOf(
          "normalizedToolName" to "Bash",
          "shellCommand" to "git status --short",
          "workingDirectory" to ".",
          "toolReason" to "Check repository state before editing.",
        ),
      ),
    )

    val snapshot = hostRuntime.loadChatSnapshot()
    val pendingApprovals = snapshot["pendingApprovals"] as List<*>
    val pendingApproval = pendingApprovals.single() as Map<*, *>
    val body = pendingApproval["body"] as String

    assertEquals("Bash", pendingApproval["toolName"])
    assertEquals("git status --short", pendingApproval["requestSummary"])
    assertEquals("git status --short", pendingApproval["primaryDetail"])
    assertEquals(".", pendingApproval["workingDirectory"])
    assertEquals("Check repository state before editing.", pendingApproval["reason"])
    assertEquals("Approval is required before Bash can run.", pendingApproval["message"])
    assertTrue(body.contains("Command: git status --short"))
    assertTrue(body.contains("Working directory: ."))
    assertTrue(body.contains("Agent reason: Check repository state before editing."))
    assertTrue(body.contains("Approval is required before Bash can run."))
  }

  @Test
  fun approvalRequiredFailureIncludesDelegationDetailsInPendingApprovalBody() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-approval-delegation"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Need delegation approval")
    val task = handle.submittedTasks.single()
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.DENIED,
        errorCode = "APPROVAL_REQUIRED",
        errorMessage = "Approval is required before Task can delegate this work.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata + mapOf(
          "normalizedToolName" to "Task",
          "targetSummary" to "Inspect README",
          "delegationDescription" to "Inspect README",
          "delegationPromptPreview" to "Read README.md and summarize it.",
          "delegationAllowedTools" to "Glob,Grep,LS,Read",
          "toolReason" to "Use a child researcher to inspect the repo first.",
        ),
      ),
    )

    val snapshot = hostRuntime.loadChatSnapshot()
    val pendingApprovals = snapshot["pendingApprovals"] as List<*>
    val pendingApproval = pendingApprovals.single() as Map<*, *>
    val body = pendingApproval["body"] as String

    assertEquals("Task", pendingApproval["toolName"])
    assertEquals("Inspect README", pendingApproval["requestSummary"])
    assertEquals("Inspect README", pendingApproval["primaryDetail"])
    assertEquals("Use a child researcher to inspect the repo first.", pendingApproval["reason"])
    assertEquals("Approval is required before Task can delegate this work.", pendingApproval["message"])
    assertTrue(body.contains("Request: Inspect README"))
    assertTrue(body.contains("Prompt: Read README.md and summarize it."))
    assertTrue(body.contains("Allowed tools: Glob,Grep,LS,Read"))
    assertTrue(body.contains("Agent reason: Use a child researcher to inspect the repo first."))
  }

  @Test
  fun approvalRequiredFailureAddsApprovalWaitEventToRuntimeActivity() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-approval-runtime-event"))
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
        status = ExecutionStatus.DENIED,
        errorCode = "APPROVAL_REQUIRED",
        errorMessage = "Approval is required before Write can run.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata + mapOf(
          "normalizedToolName" to "Write",
          "toolReason" to "Update the notes before answering.",
        ),
      ),
    )

    val runtimeActivity = hostRuntime.loadChatSnapshot()["runtimeActivity"] as Map<*, *>
    val approvalEvent = (runtimeActivity["events"] as List<*>).single() as Map<*, *>
    val eventText = approvalEvent["text"] as String

    assertEquals(activeSessionId, runtimeActivity["sessionId"])
    assertEquals(run.runId, approvalEvent["runId"])
    assertEquals(task.id, approvalEvent["taskId"])
    assertEquals("approval_wait", approvalEvent["kind"])
    assertEquals("required", approvalEvent["status"])
    assertEquals("Write", approvalEvent["toolName"])
    assertEquals(false, approvalEvent["isHighRisk"])
    assertTrue(eventText.contains("Approval required"))
    assertTrue(eventText.contains("Update the notes before answering."))
    assertTrue(eventText.contains("Approval is required before Write can run."))
  }

  @Test
  fun submitChatMessageWhileApprovalPendingQueuesDeferredFollowUp() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-approval-supplement"))
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
        status = ExecutionStatus.DENIED,
        errorCode = "APPROVAL_REQUIRED",
        errorMessage = "Approval is required before Write can run.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata + mapOf(
          "normalizedToolName" to "Write",
        ),
      ),
    )

    val secondRun = hostRuntime.submitChatMessage("Use a safer approach")
    val queuedInputs = chatStore.loadPendingUserInputs(activeSessionId)
    val renderedMessages = (hostRuntime.loadChatSnapshot()["messages"] as List<*>)
      .map { message -> (message as Map<*, *>)["text"] }

    assertEquals(null, secondRun)
    assertEquals(listOf("Use a safer approach"), queuedInputs.map { it.text })
    assertEquals(listOf("Need approval"), handle.submittedInputs)
    assertEquals(
      listOf(
        "Need approval",
        "Approval is required before Write can run.",
        "Use a safer approach",
      ),
      renderedMessages,
    )
  }

  @Test
  fun chatSnapshotSummaryShowsApprovalFollowUpRecordedWhileApprovalIsPending() {
    val chatStore = ChatSessionLocalStore(
      temporaryFolder.newFolder("chat-store-summary-approval-follow-up"),
    )
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
        status = ExecutionStatus.DENIED,
        errorCode = "APPROVAL_REQUIRED",
        errorMessage = "Approval is required before Write can run.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata + mapOf(
          "normalizedToolName" to "Write",
        ),
      ),
    )
    assertEquals(null, hostRuntime.submitChatMessage("Use a safer approach"))

    val summary = hostRuntime.loadChatSnapshot()["summary"] as Map<*, *>

    assertEquals(
      "Recorded. The current run is waiting for approval, so this message will be handled after that decision.",
      summary["body"],
    )
  }

  @Test
  fun loadChatSnapshotRepairsStaleSupplementsWithoutTouchingLiveRunEntries() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-stale-supplement-repair"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val supplementStore = InMemorySessionSupplementStore()
    val supplementStoreFactory = object : AgentSessionSupplementStoreFactory {
      override fun forChatSession(sessionId: String): SessionSupplementStore = supplementStore
    }
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      supplementStoreFactory = supplementStoreFactory,
    )

    hostRuntime.submitChatMessage("Inspect the repo")
    val liveTask = handle.submittedTasks.single()
    val liveRun = handle.submissions.single()
    supplementStore.append(
      runId = "stale-run",
      taskId = "stale-task",
      text = "Stale queued follow-up",
    )
    supplementStore.append(
      runId = liveRun.runId,
      taskId = liveTask.id,
      text = "Live supplement",
    )

    val renderedMessages = (hostRuntime.loadChatSnapshot()["messages"] as List<*>)
      .map { message -> (message as Map<*, *>)["text"] }

    assertEquals(
      listOf("Stale queued follow-up"),
      chatStore.loadPendingUserInputs(activeSessionId).map { it.text },
    )
    assertEquals(listOf("Live supplement"), supplementStore.snapshot().map { it.text })
    assertEquals(
      listOf(
        "Inspect the repo",
        "Thinking",
        "Stale queued follow-up",
        "Live supplement",
      ),
      renderedMessages,
    )
  }

  @Test
  fun loadChatSnapshotKeepsSupplementsForTerminalLiveProcessRun() {
    val chatStore = ChatSessionLocalStore(
      temporaryFolder.newFolder("chat-store-live-process-stale-supplement-repair"),
    )
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val supplementStore = InMemorySessionSupplementStore()
    val supplementStoreFactory = object : AgentSessionSupplementStoreFactory {
      override fun forChatSession(sessionId: String): SessionSupplementStore = supplementStore
    }
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      supplementStoreFactory = supplementStoreFactory,
    )

    handle.putRunSnapshot(
      AgentRunSnapshot(
        sessionId = activeSessionId,
        runId = "run-live-terminal",
        taskId = "task-live-terminal",
        acceptedAtEpochMs = 1_000L,
        updatedAtEpochMs = 1_001L,
        lifecycleState = QueueTaskLifecycleState.COMPLETED,
        taskState = AgentTaskState.COMPLETED,
        executionStatus = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
      ),
    )
    handle.putManagedProcess(
      com.opencray.runtime.process.ManagedProcessSnapshot(
        processId = "proc-live-terminal",
        taskId = "task-live-terminal",
        command = "npm",
        args = listOf("run", "dev"),
        workingDirectory = ".",
        status = com.opencray.runtime.process.ManagedProcessStatus.RUNNING,
        processStarted = true,
        timeoutMs = 120_000L,
        startedAtEpochMs = 1_000L,
        updatedAtEpochMs = 1_001L,
      ),
    )
    supplementStore.append(
      runId = "stale-run",
      taskId = "stale-task",
      text = "Stale queued follow-up",
    )
    supplementStore.append(
      runId = "run-live-terminal",
      taskId = "task-live-terminal",
      text = "Keep following the logs",
    )

    val renderedMessages = (hostRuntime.loadChatSnapshot()["messages"] as List<*>)
      .map { message -> (message as Map<*, *>)["text"] }

    assertEquals(
      listOf("Stale queued follow-up"),
      chatStore.loadPendingUserInputs(activeSessionId).map { it.text },
    )
    assertEquals(listOf("Keep following the logs"), supplementStore.snapshot().map { it.text })
    assertEquals(
      listOf(
        "Stale queued follow-up",
        "Keep following the logs",
      ),
      renderedMessages,
    )
  }
}
