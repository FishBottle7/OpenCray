package com.opencray.app

import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.persistence.model.ChatTranscriptRole
import com.opencray.runtime.OpenCrayAssistantEvent
import com.opencray.runtime.OpenCrayFinalAttachment
import com.opencray.runtime.OpenCraySupplementEvent
import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.context.RuntimeConversationRole
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostSupplementQueueTest : HostRuntimeTestBase() {
  @Test
  fun submitChatMessageWhileRunActiveStoresSupplementInsteadOfQueueingFollowUp() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-mid-loop-supplement"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Inspect the repo")
    val secondRun = hostRuntime.submitChatMessage("Also check the tests")

    val messages = chatStore.loadState().activeSession.messages
      .filter { message -> message.role != ChatTranscriptRole.SYSTEM }
    val queuedInputs = chatStore.loadPendingUserInputs(activeSessionId)
    val renderedMessages = (hostRuntime.loadChatSnapshot()["messages"] as List<*>)
      .map { message -> (message as Map<*, *>)["text"] }

    assertEquals(null, secondRun)
    assertEquals(listOf("Inspect the repo", "Thinking"), messages.map { it.text })
    assertTrue(queuedInputs.isEmpty())
    assertEquals(
      listOf("Inspect the repo", "Thinking", "Also check the tests"),
      renderedMessages,
    )
    assertEquals(listOf("Inspect the repo"), handle.submittedInputs)
    assertTrue(handle.cancelledTaskIds.isEmpty())
  }

  @Test
  fun submitChatMessageAfterLiveProcessRunCompletesStillTargetsSameRun() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-live-process-follow-up"))
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

    hostRuntime.submitChatMessage("Start the dev server")
    val task = handle.submittedTasks.single()
    handle.putManagedProcess(
      com.opencray.runtime.process.ManagedProcessSnapshot(
        processId = "proc-live-follow-up",
        taskId = task.id,
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
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
        stdout = "Server is running in the background.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_002L,
        metadata = task.metadata,
      ),
    )

    val secondRun = hostRuntime.submitChatMessage("Also check the logs")
    val queuedInputs = chatStore.loadPendingUserInputs(activeSessionId)
    val renderedMessages = (hostRuntime.loadChatSnapshot()["messages"] as List<*>)
      .map { message -> (message as Map<*, *>)["text"] }

    assertEquals(null, secondRun)
    assertEquals(listOf("Start the dev server"), handle.submittedInputs)
    assertTrue(queuedInputs.isEmpty())
    assertEquals(listOf("Also check the logs"), supplementStore.snapshot().map { it.text })
    assertEquals(
      listOf(
        "Start the dev server",
        "Server is running in the background.",
        "Also check the logs",
      ),
      renderedMessages,
    )
  }

  @Test
  fun activeRunDrawerPreviewUsesRuntimeProgressInsteadOfThinkingPlaceholder() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-drawer-progress-preview"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    val run = hostRuntime.submitChatMessage("Inspect the repo")!!
    val task = handle.submittedTasks.single()
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = OpenCrayAssistantEvent(
        runId = run["runId"] as String,
        taskId = task.id,
        turn = 1,
        isFinal = false,
        stage = "Planning",
        text = "Inspecting README and Gradle files before the next tool call.",
        emittedAtEpochMs = 1_100L,
      ),
    )

    val drawer = hostRuntime.loadChatSnapshot()["drawer"] as Map<*, *>
    val session = ((drawer["sessions"] as List<*>).single()) as Map<*, *>
    val preview = session["preview"] as String

    assertTrue(preview.contains("Inspecting README and Gradle files"))
    assertFalse(preview.startsWith("Thinking"))
  }

  @Test
  fun submitChatMessageWhileRunActiveQueuesAttachmentsAsFollowUp() {
    val chatStore = ChatSessionLocalStore(
      temporaryFolder.newFolder("chat-store-queued-attachment-follow-up"),
    )
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val workspaceRoot = temporaryFolder.newFolder("chat-queued-attachments-workspace").toPath()
    Files.createDirectories(workspaceRoot.resolve("imports"))
    Files.write(
      workspaceRoot.resolve("imports").resolve("workspace-shot.png"),
      byteArrayOf(1, 2, 3, 4),
    )
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      workspaceRootProvider = { workspaceRoot },
    )

    hostRuntime.submitChatMessage("Inspect the repo")
    val secondRun = hostRuntime.submitChatMessage(
      text = "",
      attachments = listOf(
        OpenCrayFinalAttachment(
          kind = "image",
          relativePath = "imports/workspace-shot.png",
          displayName = "workspace-shot.png",
        ),
      ),
    )

    val queuedInputs = chatStore.loadPendingUserInputs(activeSessionId)
    val renderedMessages = (hostRuntime.loadChatSnapshot()["messages"] as List<*>)
      .map { it as Map<*, *> }
    val queuedMessage = renderedMessages.last()
    val queuedAttachments = queuedMessage["attachments"] as List<*>

    assertEquals(null, secondRun)
    assertEquals(listOf("Inspect the repo"), handle.submittedInputs)
    assertEquals(listOf(""), queuedInputs.map { it.text })
    assertEquals(1, queuedInputs.single().attachments.size)
    assertEquals(1, queuedAttachments.size)
    assertEquals("workspace-shot.png", (queuedAttachments.single() as Map<*, *>)["displayName"])
  }

  @Test
  fun replayedSupplementEventProjectsOutboundBubbleForCurrentRun() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-replayed-supplement"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    var replayMessages: List<RuntimeConversationMessage> = emptyList()
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      transcriptMessagesProvider = { replayMessages },
    )

    hostRuntime.submitChatMessage("Inspect the repo")
    val submittedTask = handle.submittedTasks.single()
    val submittedRun = handle.submissions.single()
    replayMessages = listOf(
      RuntimeConversationMessage(
        role = RuntimeConversationRole.TOOL,
        content = """
        {"event_kind":"supplement","run_id":"${submittedRun.runId}","task_id":"${submittedTask.id}","turn":1,"entry_id":"supplement-1","text":"Also check the tests","checkpoint":"turn_start"}
        """.trimIndent(),
      ),
    )

    val renderedMessages = (hostRuntime.loadChatSnapshot()["messages"] as List<*>)
      .map { it as Map<*, *> }

    assertEquals(
      listOf("Inspect the repo", "Also check the tests", "Thinking"),
      renderedMessages.map { message -> message["text"] },
    )
    assertEquals("outbound", renderedMessages[1]["kind"])
  }

  @Test
  fun toolGeneratedSupplementEventStaysInRunTraceButNotChatBubble() {
    val chatStore = ChatSessionLocalStore(
      temporaryFolder.newFolder("chat-store-tool-generated-supplement"),
    )
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Search the latest OpenCray updates")
    val task = handle.submittedTasks.single()
    val run = handle.submissions.single()
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = OpenCraySupplementEvent(
        runId = run.runId,
        taskId = task.id,
        turn = 1,
        entryId = "tool-supplement-web-search-1",
        text = "Provider-native web search ran for \"OpenCray\" within opencray.com.",
        checkpoint = "post_tool_pre_model",
        emittedAtEpochMs = 1_150L,
      ),
    )

    val chatSnapshot = hostRuntime.loadChatSnapshot()
    val renderedMessages = (chatSnapshot["messages"] as List<*>)
      .map { it as Map<*, *> }
    val runtimeActivity = chatSnapshot["runtimeActivity"] as Map<*, *>
    val supplementEvents = (runtimeActivity["events"] as List<*>)
      .map { it as Map<*, *> }
      .filter { event -> event["kind"] == "supplement" }

    assertEquals(
      listOf("Search the latest OpenCray updates", "Thinking"),
      renderedMessages.map { message -> message["text"] },
    )
    assertEquals(1, supplementEvents.size)
    assertEquals(
      "Provider-native web search ran for \"OpenCray\" within opencray.com.",
      supplementEvents.single()["text"],
    )
  }

  @Test
  fun liveProcessRunCompletionDoesNotPromoteExistingSupplementsIntoNewRun() {
    val chatStore = ChatSessionLocalStore(
      temporaryFolder.newFolder("chat-store-live-process-supplement-retention"),
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

    hostRuntime.submitChatMessage("Start the dev server")
    val task = handle.submittedTasks.single()
    assertEquals(null, hostRuntime.submitChatMessage("Also inspect the logs"))
    handle.putManagedProcess(
      com.opencray.runtime.process.ManagedProcessSnapshot(
        processId = "proc-live-retained",
        taskId = task.id,
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

    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
        stdout = "Server is running in the background.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_002L,
        metadata = task.metadata,
      ),
    )

    val renderedMessages = (hostRuntime.loadChatSnapshot()["messages"] as List<*>)
      .map { message -> (message as Map<*, *>)["text"] }

    assertEquals(listOf("Start the dev server"), handle.submittedInputs)
    assertTrue(chatStore.loadPendingUserInputs(activeSessionId).isEmpty())
    assertEquals(listOf("Also inspect the logs"), supplementStore.snapshot().map { it.text })
    assertEquals(
      listOf(
        "Start the dev server",
        "Server is running in the background.",
        "Also inspect the logs",
      ),
      renderedMessages,
    )
  }

  @Test
  fun unconsumedSupplementsPromoteIntoNextQueuedRunAfterCurrentRunFinishes() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-supplement-promote"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Inspect the workspace")
    val firstTask = handle.submittedTasks.first()
    assertEquals(null, hostRuntime.submitChatMessage("Also inspect the tests"))

    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = firstTask,
      result = ExecutionResult(
        taskId = firstTask.id,
        status = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
        stdout = "First done.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = firstTask.metadata,
      ),
    )

    val messages = chatStore.loadState().activeSession.messages
      .filter { message -> message.role != ChatTranscriptRole.SYSTEM }

    assertEquals(
      listOf("Inspect the workspace", "First done.", "Also inspect the tests", "Thinking"),
      messages.map { it.text },
    )
    assertEquals(listOf("Inspect the workspace", "Also inspect the tests"), handle.submittedInputs)
    assertTrue(chatStore.loadPendingUserInputs(activeSessionId).isEmpty())
  }

  @Test
  fun submitChatMessageWhileRunActiveKeepsDeferredQueueAsFollowUps() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-queued-follow-up"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Inspect the repo")
    chatStore.enqueuePendingUserInput(activeSessionId, "Already queued")
    val secondRun = hostRuntime.submitChatMessage("Also check the tests")

    val messages = chatStore.loadState().activeSession.messages
      .filter { message -> message.role != ChatTranscriptRole.SYSTEM }
    val queuedInputs = chatStore.loadPendingUserInputs(activeSessionId)
    val renderedMessages = (hostRuntime.loadChatSnapshot()["messages"] as List<*>)
      .map { message -> (message as Map<*, *>)["text"] }
    val runtimeActivity = hostRuntime.loadChatSnapshot()["runtimeActivity"] as Map<*, *>
    val activeRuns = (runtimeActivity["activeRuns"] as List<*>).map { it as Map<*, *> }

    assertEquals(null, secondRun)
    assertEquals(listOf("Inspect the repo", "Thinking"), messages.map { it.text })
    assertEquals(
      listOf("Already queued", "Also check the tests"),
      queuedInputs.map { it.text },
    )
    assertEquals(
      listOf("Inspect the repo", "Thinking", "Already queued", "Also check the tests"),
      renderedMessages,
    )
    assertEquals(listOf("Inspect the repo"), handle.submittedInputs)
    assertTrue(handle.cancelledTaskIds.isEmpty())
    assertTrue(handle.cancelledPendingMessageIdSets.isEmpty())
    assertEquals(1, activeRuns.size)
    assertEquals(handle.submissions.first().runId, activeRuns.single()["runId"])
  }

  @Test
  fun approvalRequiredResultDemotesExistingSupplementsIntoDeferredFollowUps() {
    val chatStore = ChatSessionLocalStore(
      temporaryFolder.newFolder("chat-store-approval-demote-supplement"),
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
    assertEquals(null, hostRuntime.submitChatMessage("Use a safer approach"))
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

    val queuedInputs = chatStore.loadPendingUserInputs(activeSessionId)
    val renderedMessages = (hostRuntime.loadChatSnapshot()["messages"] as List<*>)
      .map { message -> (message as Map<*, *>)["text"] }

    assertEquals(listOf("Use a safer approach"), queuedInputs.map { it.text })
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
  fun chatSnapshotSummaryShowsSupplementRecordedWhileRunIsStillActive() {
    val chatStore = ChatSessionLocalStore(
      temporaryFolder.newFolder("chat-store-summary-supplement"),
    )
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Inspect the repo")
    assertEquals(null, hostRuntime.submitChatMessage("Also inspect the tests"))

    val summary = hostRuntime.loadChatSnapshot()["summary"] as Map<*, *>

    assertEquals(
      "Recorded. This will be applied to the current run when it reaches the next safe checkpoint.",
      summary["body"],
    )
  }

  @Test
  fun queuedFollowUpsDrainInOrderAfterEachRunCompletes() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-drain-follow-ups"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Inspect the workspace")
    val firstTask = handle.submittedTasks.first()
    chatStore.enqueuePendingUserInput(activeSessionId, "Also inspect the tests")
    assertEquals(null, hostRuntime.submitChatMessage("Then summarize"))

    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = firstTask,
      result = ExecutionResult(
        taskId = firstTask.id,
        status = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
        stdout = "First done.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = firstTask.metadata,
      ),
    )

    val secondTask = handle.submittedTasks.last()
    val afterFirstCompletionMessages = chatStore.loadState().activeSession.messages
      .filter { message -> message.role != ChatTranscriptRole.SYSTEM }
    val afterFirstRuntime = hostRuntime.loadChatSnapshot()["runtimeActivity"] as Map<*, *>
    val afterFirstActiveRuns = (afterFirstRuntime["activeRuns"] as List<*>)
      .map { it as Map<*, *> }

    assertEquals(
      listOf("Inspect the workspace", "First done.", "Also inspect the tests", "Thinking"),
      afterFirstCompletionMessages.map { it.text },
    )
    assertEquals(1, afterFirstActiveRuns.size)
    assertEquals(handle.submissions.last().runId, afterFirstActiveRuns.single()["runId"])
    assertEquals(listOf("Inspect the workspace", "Also inspect the tests"), handle.submittedInputs)
    assertEquals(listOf("Then summarize"), chatStore.loadPendingUserInputs(activeSessionId).map { it.text })

    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = secondTask,
      result = ExecutionResult(
        taskId = secondTask.id,
        status = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
        stdout = "Second done.",
        startedAtEpochMs = 1_002L,
        finishedAtEpochMs = 1_003L,
        metadata = secondTask.metadata,
      ),
    )

    val thirdTask = handle.submittedTasks.last()
    val afterSecondCompletionMessages = chatStore.loadState().activeSession.messages
      .filter { message -> message.role != ChatTranscriptRole.SYSTEM }
    val afterSecondRuntime = hostRuntime.loadChatSnapshot()["runtimeActivity"] as Map<*, *>
    val afterSecondActiveRuns = afterSecondRuntime["activeRuns"] as List<*>

    assertEquals(
      listOf(
        "Inspect the workspace",
        "First done.",
        "Also inspect the tests",
        "Second done.",
        "Then summarize",
        "Thinking",
      ),
      afterSecondCompletionMessages.map { it.text },
    )
    assertEquals(listOf("Inspect the workspace", "Also inspect the tests", "Then summarize"), handle.submittedInputs)
    assertEquals(1, afterSecondActiveRuns.size)
    assertTrue(chatStore.loadPendingUserInputs(activeSessionId).isEmpty())

    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = thirdTask,
      result = ExecutionResult(
        taskId = thirdTask.id,
        status = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
        stdout = "All set.",
        startedAtEpochMs = 1_004L,
        finishedAtEpochMs = 1_005L,
        metadata = thirdTask.metadata,
      ),
    )

    val finalMessages = chatStore.loadState().activeSession.messages
      .filter { message -> message.role != ChatTranscriptRole.SYSTEM }
    val finalRuntime = hostRuntime.loadChatSnapshot()["runtimeActivity"] as Map<*, *>
    val finalActiveRuns = finalRuntime["activeRuns"] as List<*>

    assertEquals(
      listOf(
        "Inspect the workspace",
        "First done.",
        "Also inspect the tests",
        "Second done.",
        "Then summarize",
        "All set.",
      ),
      finalMessages.map { it.text },
    )
    assertTrue(finalActiveRuns.isEmpty())
  }
}
