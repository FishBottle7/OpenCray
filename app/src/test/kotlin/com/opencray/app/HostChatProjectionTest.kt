package com.opencray.app

import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.persistence.model.ChatTranscriptRole
import com.opencray.runtime.AgentToolCall
import com.opencray.runtime.AgentToolResult
import com.opencray.runtime.AgentToolResultStatus
import com.opencray.runtime.OpenCrayAssistantEvent
import com.opencray.runtime.OpenCrayLifecycleEvent
import com.opencray.runtime.OpenCrayPromptResumeMetadata
import com.opencray.runtime.OpenCrayRunLifecyclePhase
import com.opencray.runtime.OpenCrayToolResultEvent
import com.opencray.runtime.TodoWriteMetadataKeys
import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.context.RuntimeConversationRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostChatProjectionTest : HostRuntimeTestBase() {
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
  fun chatSnapshotIncludesStructuredToolResultMetadata() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-tool-result-metadata"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Read README with detail")
    val task = handle.submittedTasks.single()
    val run = handle.submissions.single()
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = OpenCrayToolResultEvent(
        runId = run.runId,
        taskId = task.id,
        turn = 0,
        call = AgentToolCall(
          toolName = "Read",
        ),
        result = AgentToolResult(
          toolName = "Read",
          status = AgentToolResultStatus.SUCCESS,
          content = "alpha\nbeta",
          metadata = mapOf(
            "filePath" to "README.md",
            "offset" to "5",
            "limit" to "2",
            "returnedLineCount" to "2",
            "totalLineCount" to "12",
            "truncated" to "false",
            "resultLimitApplied" to "true",
            "resultTruncated" to "false",
            "resultLimitKind" to "read_byte_budget",
            "checkpointId" to "hidden-checkpoint",
            OpenCrayPromptResumeMetadata.KEY_PROMPT_RESUME_JSON to """{"turnIndex":1,"toolCallCount":1}""",
          ),
        ),
        emittedAtEpochMs = 1_200L,
      ),
    )

    val runtimeActivity = hostRuntime.loadChatSnapshot()["runtimeActivity"] as Map<*, *>
    val firstEvent = (runtimeActivity["events"] as List<*>).single() as Map<*, *>
    val resultMetadata = firstEvent["resultMetadata"] as Map<*, *>

    assertEquals("tool_result", firstEvent["kind"])
    assertEquals("README.md", resultMetadata["filePath"])
    assertEquals("5", resultMetadata["offset"])
    assertEquals("2", resultMetadata["limit"])
    assertEquals("2", resultMetadata["returnedLineCount"])
    assertEquals("12", resultMetadata["totalLineCount"])
    assertEquals("false", resultMetadata["truncated"])
    assertEquals("true", resultMetadata["resultLimitApplied"])
    assertEquals("false", resultMetadata["resultTruncated"])
    assertEquals("read_byte_budget", resultMetadata["resultLimitKind"])
    assertFalse(resultMetadata.containsKey("checkpointId"))
    assertFalse(resultMetadata.containsKey(OpenCrayPromptResumeMetadata.KEY_PROMPT_RESUME_JSON))
  }

  @Test
  fun chatSnapshotIncludesTodoPlanResultMetadata() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-todo-result-metadata"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Track the current plan")
    val task = handle.submittedTasks.single()
    val run = handle.submissions.single()
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = OpenCrayToolResultEvent(
        runId = run.runId,
        taskId = task.id,
        turn = 0,
        call = AgentToolCall(toolName = "TodoWrite"),
        result = AgentToolResult(
          toolName = "TodoWrite",
          status = AgentToolResultStatus.SUCCESS,
          content = """
            [completed] Inspect runtime continuation
            [in_progress] Prepare final answer | active: Preparing final answer
          """.trimIndent(),
          metadata = mapOf(
            TodoWriteMetadataKeys.TODO_COUNT to "2",
            TodoWriteMetadataKeys.MUTATED to "true",
            TodoWriteMetadataKeys.PLAN_CHANGED to "true",
            TodoWriteMetadataKeys.PENDING_TODO_COUNT to "0",
            TodoWriteMetadataKeys.IN_PROGRESS_TODO_COUNT to "1",
            TodoWriteMetadataKeys.COMPLETED_TODO_COUNT to "1",
            TodoWriteMetadataKeys.ADDED_TODO_COUNT to "1",
            TodoWriteMetadataKeys.REMOVED_TODO_COUNT to "1",
            TodoWriteMetadataKeys.STATUS_CHANGED_TODO_COUNT to "1",
            TodoWriteMetadataKeys.COMPLETED_TODO_DELTA_COUNT to "1",
            TodoWriteMetadataKeys.ACTIVE_TODO_CHANGED to "true",
            TodoWriteMetadataKeys.ACTIVE_TODO_CONTENT to "Prepare final answer",
            "checkpointId" to "hidden-checkpoint",
          ),
        ),
        emittedAtEpochMs = 1_210L,
      ),
    )

    val runtimeActivity = hostRuntime.loadChatSnapshot()["runtimeActivity"] as Map<*, *>
    val firstEvent = (runtimeActivity["events"] as List<*>).single() as Map<*, *>
    val resultMetadata = firstEvent["resultMetadata"] as Map<*, *>

    assertEquals("tool_result", firstEvent["kind"])
    assertEquals("2", resultMetadata[TodoWriteMetadataKeys.TODO_COUNT])
    assertEquals("true", resultMetadata[TodoWriteMetadataKeys.MUTATED])
    assertEquals("true", resultMetadata[TodoWriteMetadataKeys.PLAN_CHANGED])
    assertEquals("0", resultMetadata[TodoWriteMetadataKeys.PENDING_TODO_COUNT])
    assertEquals("1", resultMetadata[TodoWriteMetadataKeys.IN_PROGRESS_TODO_COUNT])
    assertEquals("1", resultMetadata[TodoWriteMetadataKeys.COMPLETED_TODO_COUNT])
    assertEquals("1", resultMetadata[TodoWriteMetadataKeys.ADDED_TODO_COUNT])
    assertEquals("1", resultMetadata[TodoWriteMetadataKeys.REMOVED_TODO_COUNT])
    assertEquals("1", resultMetadata[TodoWriteMetadataKeys.STATUS_CHANGED_TODO_COUNT])
    assertEquals("1", resultMetadata[TodoWriteMetadataKeys.COMPLETED_TODO_DELTA_COUNT])
    assertEquals("true", resultMetadata[TodoWriteMetadataKeys.ACTIVE_TODO_CHANGED])
    assertEquals("Prepare final answer", resultMetadata[TodoWriteMetadataKeys.ACTIVE_TODO_CONTENT])
    assertFalse(resultMetadata.containsKey("checkpointId"))
  }

  @Test
  fun chatSnapshotIncludesPublicProgressEvents() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-progress-event"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Keep me updated while you inspect the workspace")
    val task = handle.submittedTasks.single()
    val run = handle.submissions.single()
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = OpenCrayAssistantEvent(
        runId = run.runId,
        taskId = task.id,
        turn = 0,
        text = "Scanning README and Gradle files before choosing the next tool.",
        isFinal = false,
        stage = "Planning",
        emittedAtEpochMs = 1_150L,
      ),
    )

    val runtimeActivity = hostRuntime.loadChatSnapshot()["runtimeActivity"] as Map<*, *>
    val firstEvent = (runtimeActivity["events"] as List<*>).single() as Map<*, *>

    assertEquals(activeSessionId, runtimeActivity["sessionId"])
    assertEquals("assistant_phase", firstEvent["kind"])
    assertEquals("commentary", firstEvent["phase"])
    assertEquals("Planning", firstEvent["stage"])
    assertEquals(
      "Scanning README and Gradle files before choosing the next tool.",
      firstEvent["text"],
    )
    assertEquals(run.runId, firstEvent["runId"])
  }

  @Test
  fun chatSnapshotKeepsRetryEventsInRuntimeActivityButHidesTheirChatBubbles() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-hidden-retry-progress"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Try again if the provider disconnects")
    val task = handle.submittedTasks.single()
    val run = handle.submissions.single()
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = OpenCrayAssistantEvent(
        runId = run.runId,
        taskId = task.id,
        turn = 0,
        text = "Provider timed out. Retrying after 15000ms.",
        isFinal = false,
        stage = "llm_retry",
        emittedAtEpochMs = 1_160L,
      ),
    )

    val chatSnapshot = hostRuntime.loadChatSnapshot()
    val runtimeActivity = chatSnapshot["runtimeActivity"] as Map<*, *>
    val runtimeEvent = (runtimeActivity["events"] as List<*>).single() as Map<*, *>
    val messages = (chatSnapshot["messages"] as List<*>)
      .map { message -> message as Map<*, *> }

    assertEquals("assistant_phase", runtimeEvent["kind"])
    assertEquals("llm_retry", runtimeEvent["stage"])
    assertEquals("Provider timed out. Retrying after 15000ms.", runtimeEvent["text"])
    assertEquals(
      listOf("Try again if the provider disconnects", "Thinking"),
      messages.map { message -> message["text"] },
    )
  }

  @Test
  fun chatSnapshotProjectsProgressMessagesBeforeCompletedAssistantReply() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-progress-messages-live"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Keep me updated while you inspect the workspace")
    val task = handle.submittedTasks.single()
    val run = handle.submissions.single()
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = OpenCrayAssistantEvent(
        runId = run.runId,
        taskId = task.id,
        turn = 0,
        text = "Scanning README and Gradle files before choosing the next tool.",
        isFinal = false,
        stage = "Planning",
        emittedAtEpochMs = 1_150L,
      ),
    )
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = ExecutionStatus.SUCCESS,
        stdout = "README and Gradle files look consistent.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_200L,
        metadata = task.metadata,
      ),
    )

    val messages = (hostRuntime.loadChatSnapshot()["messages"] as List<*>)
      .map { message -> message as Map<*, *> }

    assertEquals(
      listOf(
        "Keep me updated while you inspect the workspace",
        "Planning\n\nScanning README and Gradle files before choosing the next tool.",
        "README and Gradle files look consistent.",
      ),
      messages.map { message -> message["text"] },
    )
    assertEquals(false, messages[1]["isEphemeral"])
    assertEquals(1_150L, messages[1]["createdAtEpochMs"])
  }

  @Test
  fun chatSnapshotDeduplicatesRepeatedLiveProgressEvents() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-progress-dedupe-live"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Keep me updated while you inspect the workspace")
    val task = handle.submittedTasks.single()
    val run = handle.submissions.single()
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = OpenCrayAssistantEvent(
        runId = run.runId,
        taskId = task.id,
        turn = 0,
        text = "Scanning README and Gradle files before choosing the next tool.",
        isFinal = false,
        stage = "Planning",
        emittedAtEpochMs = 1_150L,
      ),
    )
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = OpenCrayAssistantEvent(
        runId = run.runId,
        taskId = task.id,
        turn = 0,
        text = "  Scanning README and  Gradle files before choosing the next tool.\n",
        isFinal = false,
        stage = " Planning ",
        emittedAtEpochMs = 1_151L,
      ),
    )

    val chatSnapshot = hostRuntime.loadChatSnapshot()
    val runtimeActivity = chatSnapshot["runtimeActivity"] as Map<*, *>
    val events = (runtimeActivity["events"] as List<*>)
      .map { event -> event as Map<*, *> }
    val messages = (chatSnapshot["messages"] as List<*>)
      .map { message -> message as Map<*, *> }

    assertEquals(1, events.count { event -> event["kind"] == "assistant_phase" })
    assertEquals(
      "Scanning README and Gradle files before choosing the next tool.",
      events.single()["text"],
    )
    assertEquals(
      1,
      messages.count { message ->
        message["text"] == "Planning\n\nScanning README and Gradle files before choosing the next tool."
      },
    )
  }

  @Test
  fun chatSnapshotIgnoresPreviousExecutionProgressMessagesForChatBubbles() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-progress-execution-scope"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Resume after approval and keep me posted")
    val task = handle.submittedTasks.single()
    val run = handle.submissions.single()
    handle.updateRunSnapshot(run.runId) { snapshot ->
      snapshot.copy(
        executionId = "exec-2",
        executionOrdinal = 2,
        executionKind = "approval_resume",
      )
    }
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = OpenCrayAssistantEvent(
        runId = run.runId,
        taskId = task.id,
        executionId = "exec-1",
        executionOrdinal = 1,
        executionKind = "initial",
        turn = 0,
        text = "Checking the workspace before the next step.",
        isFinal = false,
        stage = "Planning",
        emittedAtEpochMs = 1_150L,
      ),
    )
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = OpenCrayAssistantEvent(
        runId = run.runId,
        taskId = task.id,
        executionId = "exec-2",
        executionOrdinal = 2,
        executionKind = "approval_resume",
        turn = 0,
        text = "Checking the workspace before the next step.",
        isFinal = false,
        stage = "Planning",
        emittedAtEpochMs = 1_250L,
      ),
    )

    val messages = (hostRuntime.loadChatSnapshot()["messages"] as List<*>)
      .map { message -> message as Map<*, *> }

    assertEquals(
      listOf(
        "Resume after approval and keep me posted",
        "Planning\n\nChecking the workspace before the next step.",
        "Thinking",
      ),
      messages.map { message -> message["text"] },
    )
    assertEquals(
      1,
      messages.count { message ->
        message["text"] == "Planning\n\nChecking the workspace before the next step."
      },
    )
  }

  @Test
  fun chatSnapshotProjectsPendingExecutionProgressMessagesBeforeExecutionIdIsAssigned() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-pending-progress-execution"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Start the dev server and keep me posted")
    val task = handle.submittedTasks.single()
    val run = handle.submissions.single()
    handle.updateRunSnapshot(run.runId) { snapshot ->
      snapshot.copy(
        pendingExecutionKind = "initial",
      )
    }
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = OpenCrayAssistantEvent(
        runId = run.runId,
        taskId = task.id,
        turn = 0,
        text = "Checking the workspace before the next step.",
        isFinal = false,
        stage = "Planning",
        emittedAtEpochMs = 1_150L,
      ),
    )

    val messages = (hostRuntime.loadChatSnapshot()["messages"] as List<*>)
      .map { message -> message as Map<*, *> }
    println(
      messages.joinToString(separator = " | ") { message ->
        "${message["text"]} @${message["createdAtEpochMs"]}"
      },
    )

    assertEquals(
      listOf(
        "Start the dev server and keep me posted",
        "Planning\n\nChecking the workspace before the next step.",
        "Thinking",
      ),
      messages.map { message -> message["text"] },
    )
    assertEquals(
      1,
      messages.count { message ->
        message["text"] == "Planning\n\nChecking the workspace before the next step."
      },
    )
  }

  @Test
  fun chatSnapshotPersistsMultipleProgressMessagesBeforePendingAssistantReply() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-progress-messages-persisted"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Keep me updated while you inspect the workspace")
    val task = handle.submittedTasks.single()
    val run = handle.submissions.single()
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = OpenCrayAssistantEvent(
        runId = run.runId,
        taskId = task.id,
        turn = 0,
        text = "Scanning README and Gradle files before choosing the next tool.",
        isFinal = false,
        stage = "Planning",
        emittedAtEpochMs = 1_150L,
      ),
    )
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = OpenCrayAssistantEvent(
        runId = run.runId,
        taskId = task.id,
        turn = 1,
        text = "Checking the tests after the first pass.",
        isFinal = false,
        stage = "Planning",
        emittedAtEpochMs = 1_175L,
      ),
    )

    val messages = (hostRuntime.loadChatSnapshot()["messages"] as List<*>)
      .map { message -> message as Map<*, *> }

    assertEquals(
      listOf(
        "Keep me updated while you inspect the workspace",
        "Planning\n\nScanning README and Gradle files before choosing the next tool.",
        "Planning\n\nChecking the tests after the first pass.",
        "Thinking",
      ),
      messages.map { message -> message["text"] },
    )
    assertTrue(
      messages
        .drop(1)
        .dropLast(1)
        .all { message -> message["isEphemeral"] == false },
    )
  }

  @Test
  fun chatSnapshotProjectsManagedProcessMessagesBeforePendingAssistantReply() {
    val chatStore = ChatSessionLocalStore(
      temporaryFolder.newFolder("chat-store-managed-process-bubble"),
    )
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Start the dev server and keep me posted")
    val task = handle.submittedTasks.single()
    handle.putManagedProcess(
      com.opencray.runtime.process.ManagedProcessSnapshot(
        processId = "proc-live",
        taskId = task.id,
        command = "npm",
        args = listOf("run", "dev"),
        workingDirectory = ".",
        status = com.opencray.runtime.process.ManagedProcessStatus.RUNNING,
        processStarted = true,
        timeoutMs = 120_000L,
        stdout = "ready on http://localhost:3000",
        startedAtEpochMs = 1_100L,
        updatedAtEpochMs = 1_200L,
      ),
    )

    val messages = (hostRuntime.loadChatSnapshot()["messages"] as List<*>)
      .map { message -> message as Map<*, *> }

    assertEquals(
      listOf(
        "Start the dev server and keep me posted",
        "Process proc-live\n\nrunning: npm run dev\n\nready on http://localhost:3000",
        "Thinking",
      ),
      messages.map { message -> message["text"] },
    )
    assertEquals("inbound", messages[1]["kind"])
    assertEquals(true, messages[1]["isEphemeral"])
  }

  @Test
  fun chatSnapshotInterleavesManagedProcessMessagesWithRuntimeProgressByTimestamp() {
    val chatStore = ChatSessionLocalStore(
      temporaryFolder.newFolder("chat-store-managed-process-global-order"),
    )
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Start the dev server and keep me posted")
    val task = handle.submittedTasks.single()
    val run = handle.submissions.single()
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = OpenCrayAssistantEvent(
        runId = run.runId,
        taskId = task.id,
        turn = 0,
        text = "Checking the workspace before the next step.",
        isFinal = false,
        stage = "Planning",
        emittedAtEpochMs = 1_150L,
      ),
    )
    handle.putManagedProcess(
      com.opencray.runtime.process.ManagedProcessSnapshot(
        processId = "proc-live",
        taskId = task.id,
        command = "npm",
        args = listOf("run", "dev"),
        workingDirectory = ".",
        status = com.opencray.runtime.process.ManagedProcessStatus.RUNNING,
        processStarted = true,
        timeoutMs = 120_000L,
        stdout = "ready on http://localhost:3000",
        startedAtEpochMs = 1_160L,
        updatedAtEpochMs = 1_200L,
      ),
    )
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = OpenCrayAssistantEvent(
        runId = run.runId,
        taskId = task.id,
        turn = 1,
        text = "Waiting for the server to finish booting.",
        isFinal = false,
        stage = "Planning",
        emittedAtEpochMs = 1_175L,
      ),
    )

    val messages = (hostRuntime.loadChatSnapshot()["messages"] as List<*>)
      .map { message -> message as Map<*, *> }

    assertEquals(
      listOf(
        "Start the dev server and keep me posted",
        "Planning\n\nChecking the workspace before the next step.",
        "Process proc-live\n\nrunning: npm run dev\n\nready on http://localhost:3000",
        "Planning\n\nWaiting for the server to finish booting.",
        "Thinking",
      ),
      messages.map { message -> message["text"] },
    )
  }

  @Test
  fun chatSnapshotIncludesPersistedMessageTimestamps() {
    var nowEpochMs = 1_000L
    val chatStore = ChatSessionLocalStore(
      temporaryFolder.newFolder("chat-store-message-timestamps"),
      nowEpochMs = { nowEpochMs },
    )
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = NoOpRuntimeManager())

    chatStore.appendUserMessage(
      sessionId = activeSessionId,
      text = "First persisted message",
      commandLabel = null,
      attachments = emptyList(),
    )
    nowEpochMs = 2_000L
    chatStore.appendMessage(
      sessionId = activeSessionId,
      role = ChatTranscriptRole.ASSISTANT,
      text = "Second persisted message",
    )

    val messages = (hostRuntime.loadChatSnapshot()["messages"] as List<*>)
      .map { message -> message as Map<*, *> }

    assertEquals(
      listOf("First persisted message", "Second persisted message"),
      messages.map { message -> message["text"] },
    )
    assertEquals(
      listOf(1_000L, 2_000L),
      messages.map { message -> message["createdAtEpochMs"] },
    )
  }

  @Test
  fun chatSnapshotDrawerIncludesLastMessageTimestamp() {
    var nowEpochMs = 1_000L
    val chatStore = ChatSessionLocalStore(
      temporaryFolder.newFolder("chat-store-drawer-message-timestamps"),
      nowEpochMs = { nowEpochMs },
    )
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = NoOpRuntimeManager())

    chatStore.appendUserMessage(
      sessionId = activeSessionId,
      text = "First persisted message",
      commandLabel = null,
      attachments = emptyList(),
    )
    nowEpochMs = 2_000L
    chatStore.appendMessage(
      sessionId = activeSessionId,
      role = ChatTranscriptRole.ASSISTANT,
      text = "Second persisted message",
    )

    val drawer = hostRuntime.loadChatSnapshot()["drawer"] as Map<*, *>
    val session = ((drawer["sessions"] as List<*>).single() as Map<*, *>)

    assertEquals("2 messages", session["meta"])
    assertEquals(2_000L, session["lastMessageAtEpochMs"])
  }

  @Test
  fun recallChatMessageRemovesProjectedProgressMessagesForRecalledTurn() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-progress-recall"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Keep me updated while you inspect the workspace")
    val task = handle.submittedTasks.single()
    val run = handle.submissions.single()
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = OpenCrayAssistantEvent(
        runId = run.runId,
        taskId = task.id,
        turn = 0,
        text = "Scanning README and Gradle files before choosing the next tool.",
        isFinal = false,
        stage = "Planning",
        emittedAtEpochMs = 1_150L,
      ),
    )
    val userMessageId = checkNotNull(chatStore.loadSession(activeSessionId)).messages
      .last { message -> message.role == ChatTranscriptRole.USER }
      .messageId

    hostRuntime.recallChatMessage(activeSessionId, userMessageId)

    val messages = (hostRuntime.loadChatSnapshot()["messages"] as List<*>)
      .map { message -> message as Map<*, *> }

    assertTrue(messages.isEmpty())
  }

  @Test
  fun chatSnapshotProjectsReplayedProgressMessagesBeforeCompletedAssistantReply() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-progress-messages-replay"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    var transcriptMessages: List<RuntimeConversationMessage> = emptyList()
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      transcriptMessagesProvider = { sessionId ->
        if (sessionId == activeSessionId) {
          transcriptMessages
        } else {
          emptyList()
        }
      },
    )

    hostRuntime.submitChatMessage("Keep me updated while you inspect the workspace")
    val task = handle.submittedTasks.single()
    val run = handle.submissions.single()
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = ExecutionStatus.SUCCESS,
        stdout = "README and Gradle files look consistent.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_200L,
        metadata = task.metadata,
      ),
    )
    transcriptMessages = listOf(
      RuntimeConversationMessage(
        role = RuntimeConversationRole.TOOL,
        content = """{"event_kind":"assistant_phase","phase":"commentary","run_id":"${run.runId}","task_id":"${task.id}","turn":0,"text":"Scanning README and Gradle files before choosing the next tool.","stage":"Planning"}""",
      ),
    )

    val messages = (hostRuntime.loadChatSnapshot()["messages"] as List<*>)
      .map { message -> message as Map<*, *> }

    assertEquals(
      listOf(
        "Keep me updated while you inspect the workspace",
        "Planning\n\nScanning README and Gradle files before choosing the next tool.",
        "README and Gradle files look consistent.",
      ),
      messages.map { message -> message["text"] },
    )
    assertEquals(true, messages[1]["isEphemeral"])
  }
}
