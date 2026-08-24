package com.opencray.app

import com.opencray.core.contracts.AgentTaskState
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.orchestrator.QueueTaskLifecycleState
import com.opencray.llm.LiteLlmMetadataKeys
import com.opencray.persistence.model.ChatAttachmentKind
import com.opencray.persistence.model.ChatTranscriptRole
import com.opencray.runtime.AgentToolCall
import com.opencray.runtime.AgentToolResult
import com.opencray.runtime.AgentToolResultStatus
import com.opencray.runtime.OpenCrayAttachmentArtifactMetadataKeys
import com.opencray.runtime.OpenCrayAssistantEvent
import com.opencray.runtime.OpenCrayPromptCheckpointBoundary
import com.opencray.runtime.OpenCrayPromptResumeMetadata
import com.opencray.runtime.OpenCrayPromptResumeState
import com.opencray.runtime.OpenCrayToolResultEvent
import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.context.RuntimeConversationRole
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostRuntimeLifecycleTest : HostRuntimeTestBase() {
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
    val runtimeSnapshot = hostRuntime.loadChatRuntimeSnapshot()
    val hostLifecycle = runtimeSnapshot["hostLifecycle"] as Map<*, *>

    assertEquals(activeSessionId, submission["sessionId"])
    assertEquals(handle.submissions.single().taskId, submission["taskId"])
    assertEquals(runId, runSnapshot?.get("runId"))
    assertEquals(handle.submissions.single().taskId, runSnapshot?.get("taskId"))
    assertTrue((hostLifecycle["processStartId"] as String).isNotBlank())
    assertTrue((hostLifecycle["hostInstanceId"] as String).isNotBlank())
    assertTrue((hostLifecycle["runtimeOwnerId"] as String).isNotBlank())
  }

  @Test
  fun refreshSandboxSessionInfoSubmitsPreapprovedHostToolCallWithoutTranscriptMessage() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-sandbox-session-refresh"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.refreshSandboxSessionInfo()

    val submittedTask = handle.submittedTasks.single()
    val taskPayload = jsonObject(submittedTask.input)
    val chatMessages = hostRuntime.loadChatSnapshot()["messages"] as List<*>
    val runtimeSnapshot = hostRuntime.loadChatRuntimeSnapshot()
    val activeRun = ((runtimeSnapshot["activeRuns"] as List<*>).single()) as Map<*, *>

    assertEquals(AgentTaskType.TOOL_CALL, submittedTask.type)
    assertEquals("tool_call", taskPayload["type"].toString().trim('"'))
    assertEquals("sandbox_session_info", taskPayload["tool_name"].toString().trim('"'))
    assertEquals(
      RunSubmissionSources.HOST_UI_TOOL_ACTION,
      submittedTask.metadata[RunLifecycleMetadataKeys.SUBMISSION_SOURCE],
    )
    assertEquals(
      "sandbox_session_info",
      submittedTask.metadata[RunLifecycleMetadataKeys.PREAPPROVED_TOOL_NAME],
    )
    assertEquals(
      activeSessionId,
      submittedTask.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_HOST_SESSION_ID],
    )
    assertEquals(listOf(submittedTask.id), handle.ensureProcessingTaskIds)
    assertTrue(chatMessages.isEmpty())
    assertEquals(handle.submissions.single().runId, activeRun["runId"])
  }

  @Test
  fun chatRuntimeSnapshotRestoresJournaledEventsAfterHostRecreation() {
    val runtimeRoot = temporaryFolder.newFolder("runtime-journal-host-recreation")
    val firstFactory = FileBackedRunEventJournalStoreFactory(runtimeRoot)
    val secondFactory = FileBackedRunEventJournalStoreFactory(runtimeRoot)
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-journal-recreation"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)

    val firstHost = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      runEventJournalStoreFactory = firstFactory,
    )

    val submission = firstHost.submitChatMessage("persist runtime events")!!
    val task = handle.submittedTasks.single()
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = OpenCrayAssistantEvent(
        runId = submission["runId"] as String,
        taskId = task.id,
        turn = 0,
        text = "Scanning workspace",
        isFinal = false,
        stage = "workspace_scan",
        emittedAtEpochMs = 1_234L,
      ),
    )

    val recreatedHost = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      runEventJournalStoreFactory = secondFactory,
    )
    val runtimeActivity = recreatedHost.loadChatRuntimeSnapshot()
    val events = runtimeActivity["events"] as List<*>
    val event = events.single() as Map<*, *>

    assertEquals(activeSessionId, runtimeActivity["sessionId"])
    assertEquals("assistant_phase", event["kind"])
    assertEquals("commentary", event["phase"])
    assertEquals("Scanning workspace", event["text"])
    assertEquals("workspace_scan", event["stage"])
    assertEquals(submission["runId"], event["runId"])
  }

  @Test
  fun recreatedHostRepairsPendingAssistantMessageFromDurableFinalizationEvent() {
    val runtimeRoot = temporaryFolder.newFolder("runtime-finalization-host-repair")
    val runEventJournalStoreFactory = FileBackedRunEventJournalStoreFactory(runtimeRoot)
    val chatStore = ChatSessionLocalStore(
      temporaryFolder.newFolder("chat-store-finalization-host-repair"),
    )
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    chatStore.appendSubmittedTurn(
      sessionId = activeSessionId,
      userText = "Finish the task",
      assistantMessageId = "message-assistant-1",
      assistantPlaceholderText = "Thinking",
    )
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    handle.putRunSnapshot(
      AgentRunSnapshot(
        sessionId = activeSessionId,
        runId = "run-restored-final",
        taskId = "task-restored-final",
        acceptedAtEpochMs = 1_000L,
        updatedAtEpochMs = 2_000L,
        lifecycleState = QueueTaskLifecycleState.COMPLETED,
        taskState = AgentTaskState.COMPLETED,
        attempt = 1,
        executionStatus = ExecutionStatus.SUCCESS,
        pendingMessageId = "message-assistant-1",
      ),
    )
    manager.putHandle(handle)
    val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; prettyPrint = true }
    val resumeState = OpenCrayPromptResumeState(
      transcript = listOf(
        RuntimeConversationMessage(
          role = RuntimeConversationRole.USER,
          content = "Finish the task",
        ),
        RuntimeConversationMessage(
          role = RuntimeConversationRole.ASSISTANT,
          content = "Recovered final answer",
        ),
      ),
      turnIndex = 1,
      toolCallCount = 0,
    )
    runEventJournalStoreFactory.forChatSession(activeSessionId).append(
      OpenCrayAssistantEvent(
        runId = "run-restored-final",
        taskId = "task-restored-final",
        turn = 1,
        text = "Recovered final answer",
        responseFormat = "text",
        isFinal = true,
        metadata = OpenCrayPromptResumeMetadata.encodeToMetadata(
          state = resumeState,
          json = json,
          checkpointBoundary = OpenCrayPromptCheckpointBoundary.FINALIZATION_COMPLETE,
        ),
        emittedAtEpochMs = 2_000L,
      ),
    )

    hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      runEventJournalStoreFactory = runEventJournalStoreFactory,
    )

    val repairedMessage = chatStore.loadSession(activeSessionId)
      ?.messages
      ?.lastOrNull { message -> message.messageId == "message-assistant-1" }

    assertEquals(listOf(activeSessionId), manager.resumedSessionIds)
    assertEquals(ChatTranscriptRole.ASSISTANT, repairedMessage?.role)
    assertEquals("Recovered final answer", repairedMessage?.text)
    assertTrue(repairedMessage?.attachments.orEmpty().isEmpty())
  }

  @Test
  fun recreatedHostResolvesAttachmentMarkdownFromDurableRunArtifactsDuringTerminalRepair() {
    val runtimeRoot = temporaryFolder.newFolder("runtime-finalization-artifact-host-repair")
    val runEventJournalStoreFactory = FileBackedRunEventJournalStoreFactory(runtimeRoot)
    val chatStore = ChatSessionLocalStore(
      temporaryFolder.newFolder("chat-store-finalization-artifact-host-repair"),
    )
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val workspaceRoot = temporaryFolder.newFolder("finalization-artifact-host-repair-workspace").toPath()
    Files.createDirectories(workspaceRoot.resolve("outputs"))
    Files.write(workspaceRoot.resolve("outputs").resolve("diagram.png"), byteArrayOf(1, 2, 3, 4))
    chatStore.appendSubmittedTurn(
      sessionId = activeSessionId,
      userText = "Send the artifact image back.",
      assistantMessageId = "message-assistant-1",
      assistantPlaceholderText = "Thinking",
    )
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    handle.putRunSnapshot(
      AgentRunSnapshot(
        sessionId = activeSessionId,
        runId = "run-restored-artifact-final",
        taskId = "task-restored-artifact-final",
        acceptedAtEpochMs = 1_000L,
        updatedAtEpochMs = 2_000L,
        lifecycleState = QueueTaskLifecycleState.COMPLETED,
        taskState = AgentTaskState.COMPLETED,
        attempt = 1,
        executionStatus = ExecutionStatus.SUCCESS,
        pendingMessageId = "message-assistant-1",
      ),
    )
    manager.putHandle(handle)
    val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; prettyPrint = true }
    val resumeState = OpenCrayPromptResumeState(
      transcript = listOf(
        RuntimeConversationMessage(
          role = RuntimeConversationRole.USER,
          content = "Send the artifact image back.",
        ),
        RuntimeConversationMessage(
          role = RuntimeConversationRole.ASSISTANT,
          content = "![diagram.png](attachment:artifact)",
        ),
      ),
      turnIndex = 1,
      toolCallCount = 1,
    )
    val journalStore = runEventJournalStoreFactory.forChatSession(activeSessionId)
    journalStore.append(
      OpenCrayToolResultEvent(
        runId = "run-restored-artifact-final",
        taskId = "task-restored-artifact-final",
        turn = 0,
        call = AgentToolCall(toolName = "workspace_write_file"),
        result = AgentToolResult(
          toolName = "workspace_write_file",
          status = AgentToolResultStatus.SUCCESS,
          content = "Wrote outputs/diagram.png successfully.",
          metadata = mapOf(
            OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_ID to "artifact-diagram-1234abcd",
            OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_RELATIVE_PATH to "outputs/diagram.png",
            OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_DISPLAY_NAME to "diagram.png",
            OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_KIND_HINT to "image",
            OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_MIME_TYPE to "image/png",
          ),
        ),
        emittedAtEpochMs = 1_000L,
      ),
    )
    journalStore.append(
      OpenCrayAssistantEvent(
        runId = "run-restored-artifact-final",
        taskId = "task-restored-artifact-final",
        turn = 1,
        text = "![diagram.png](attachment:artifact)",
        responseFormat = "text",
        isFinal = true,
        metadata = OpenCrayPromptResumeMetadata.encodeToMetadata(
          state = resumeState,
          json = json,
          checkpointBoundary = OpenCrayPromptCheckpointBoundary.FINALIZATION_COMPLETE,
        ),
        emittedAtEpochMs = 2_000L,
      ),
    )

    hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      workspaceRootProvider = { workspaceRoot },
      runEventJournalStoreFactory = runEventJournalStoreFactory,
    )

    val repairedMessage = chatStore.loadSession(activeSessionId)
      ?.messages
      ?.lastOrNull { message -> message.messageId == "message-assistant-1" }
    val attachment = repairedMessage?.attachments?.singleOrNull()

    assertNull(repairedMessage?.text)
    assertEquals(ChatAttachmentKind.IMAGE, attachment?.kind)
    assertEquals("diagram.png", attachment?.displayName)
    assertTrue(attachment?.localPath?.startsWith(".opencray/chat-media/$activeSessionId/") == true)
    assertTrue(attachment?.localPath?.let { localPath -> Files.exists(workspaceRoot.resolve(localPath)) } == true)
  }

  @Test
  fun recreatedHostsExposeStableRuntimeOwnerLifecycle() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-owner-lifecycle"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val runtimeOwnerDescriptor = HostRuntimeLifecycleDescriptor()
    val firstHost = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      lifecycleDescriptor = HostRuntimeLifecycleDescriptor(
        processStartId = runtimeOwnerDescriptor.processStartId,
        processStartedAtEpochMs = runtimeOwnerDescriptor.processStartedAtEpochMs,
        runtimeOwnerId = runtimeOwnerDescriptor.runtimeOwnerId,
      ),
      runtimeOwnerDescriptor = runtimeOwnerDescriptor,
    )
    val secondHost = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      lifecycleDescriptor = HostRuntimeLifecycleDescriptor(
        processStartId = runtimeOwnerDescriptor.processStartId,
        processStartedAtEpochMs = runtimeOwnerDescriptor.processStartedAtEpochMs,
        runtimeOwnerId = runtimeOwnerDescriptor.runtimeOwnerId,
      ),
      runtimeOwnerDescriptor = runtimeOwnerDescriptor,
    )

    val firstRuntimeSnapshot = firstHost.loadChatRuntimeSnapshot()
    val secondRuntimeSnapshot = secondHost.loadChatRuntimeSnapshot()
    val firstHostLifecycle = firstRuntimeSnapshot["hostLifecycle"] as Map<*, *>
    val secondHostLifecycle = secondRuntimeSnapshot["hostLifecycle"] as Map<*, *>
    val firstOwnerLifecycle = firstRuntimeSnapshot["runtimeOwnerLifecycle"] as Map<*, *>
    val secondOwnerLifecycle = secondRuntimeSnapshot["runtimeOwnerLifecycle"] as Map<*, *>

    assertTrue(firstHostLifecycle["hostInstanceId"] != secondHostLifecycle["hostInstanceId"])
    assertEquals(
      firstHostLifecycle["runtimeOwnerId"],
      firstOwnerLifecycle["runtimeOwnerId"],
    )
    assertEquals(
      secondHostLifecycle["runtimeOwnerId"],
      secondOwnerLifecycle["runtimeOwnerId"],
    )
    assertEquals(firstOwnerLifecycle, secondOwnerLifecycle)
  }

  @Test
  fun recreatedHostsExposeStableRuntimeServiceLifecycle() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-service-lifecycle"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val runtimeOwnerDescriptor = HostRuntimeLifecycleDescriptor()
    val runtimeServiceDescriptor = RuntimeServiceLifecycleDescriptor(
      processStartId = runtimeOwnerDescriptor.processStartId,
      processStartedAtEpochMs = runtimeOwnerDescriptor.processStartedAtEpochMs,
    )
    val firstHost = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      lifecycleDescriptor = HostRuntimeLifecycleDescriptor(
        processStartId = runtimeOwnerDescriptor.processStartId,
        processStartedAtEpochMs = runtimeOwnerDescriptor.processStartedAtEpochMs,
        runtimeOwnerId = runtimeOwnerDescriptor.runtimeOwnerId,
      ),
      runtimeOwnerDescriptor = runtimeOwnerDescriptor,
      runtimeServiceDescriptor = runtimeServiceDescriptor,
    )
    val secondHost = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      lifecycleDescriptor = HostRuntimeLifecycleDescriptor(
        processStartId = runtimeOwnerDescriptor.processStartId,
        processStartedAtEpochMs = runtimeOwnerDescriptor.processStartedAtEpochMs,
        runtimeOwnerId = runtimeOwnerDescriptor.runtimeOwnerId,
      ),
      runtimeOwnerDescriptor = runtimeOwnerDescriptor,
      runtimeServiceDescriptor = runtimeServiceDescriptor,
    )

    val firstRuntimeSnapshot = firstHost.loadChatRuntimeSnapshot()
    val secondRuntimeSnapshot = secondHost.loadChatRuntimeSnapshot()
    val firstServiceLifecycle = firstRuntimeSnapshot["runtimeServiceLifecycle"] as Map<*, *>
    val secondServiceLifecycle = secondRuntimeSnapshot["runtimeServiceLifecycle"] as Map<*, *>

    assertEquals(
      runtimeServiceDescriptor.serviceInstanceId,
      firstServiceLifecycle["serviceInstanceId"],
    )
    assertEquals(firstServiceLifecycle, secondServiceLifecycle)
  }

  @Test
  fun recreatedHostsExposeStableRuntimeControllerLifecycle() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-controller-lifecycle"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val runtimeOwnerDescriptor = HostRuntimeLifecycleDescriptor(
      runtimeControllerId = "controller-a",
      durableRuntimeControllerId = "controller-durable-a",
    )
    val runtimeControllerDescriptor = RuntimeControllerLifecycleDescriptor(
      controllerInstanceId = "controller-a",
      durableControllerId = "controller-durable-a",
    )
    val firstHost = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      lifecycleDescriptor = HostRuntimeLifecycleDescriptor(
        processStartId = runtimeOwnerDescriptor.processStartId,
        processStartedAtEpochMs = runtimeOwnerDescriptor.processStartedAtEpochMs,
        runtimeOwnerId = runtimeOwnerDescriptor.runtimeOwnerId,
        runtimeControllerId = runtimeOwnerDescriptor.runtimeControllerId,
        durableRuntimeControllerId = runtimeOwnerDescriptor.durableRuntimeControllerId,
      ),
      runtimeOwnerDescriptor = runtimeOwnerDescriptor,
      runtimeControllerDescriptor = runtimeControllerDescriptor,
    )
    val secondHost = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      lifecycleDescriptor = HostRuntimeLifecycleDescriptor(
        processStartId = runtimeOwnerDescriptor.processStartId,
        processStartedAtEpochMs = runtimeOwnerDescriptor.processStartedAtEpochMs,
        runtimeOwnerId = runtimeOwnerDescriptor.runtimeOwnerId,
        runtimeControllerId = runtimeOwnerDescriptor.runtimeControllerId,
        durableRuntimeControllerId = runtimeOwnerDescriptor.durableRuntimeControllerId,
      ),
      runtimeOwnerDescriptor = runtimeOwnerDescriptor,
      runtimeControllerDescriptor = runtimeControllerDescriptor,
    )

    val firstRuntimeSnapshot = firstHost.loadChatRuntimeSnapshot()
    val secondRuntimeSnapshot = secondHost.loadChatRuntimeSnapshot()
    val firstControllerLifecycle = firstRuntimeSnapshot["runtimeControllerLifecycle"] as Map<*, *>
    val secondControllerLifecycle = secondRuntimeSnapshot["runtimeControllerLifecycle"] as Map<*, *>

    assertEquals("controller-a", firstControllerLifecycle["controllerInstanceId"])
    assertEquals("controller-durable-a", firstControllerLifecycle["durableControllerId"])
    assertEquals(firstControllerLifecycle, secondControllerLifecycle)
  }

  @Test
  fun runtimeSnapshotsProjectRuntimeServiceConnectionState() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-service-connection"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val connectionState = RuntimeServiceConnectionState.inProcessFallback(
      serviceStartRequested = true,
    )
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      runtimeServiceDescriptor = RuntimeServiceLifecycleDescriptor(),
      runtimeServiceConnectionState = connectionState,
    )

    val runtimeSnapshot = hostRuntime.loadChatRuntimeSnapshot()
    val shellSnapshot = hostRuntime.loadShellSnapshot()
    val runtimeConnection = runtimeSnapshot["runtimeServiceConnectionState"] as Map<*, *>
    val shellConnection = shellSnapshot["runtimeServiceConnectionState"] as Map<*, *>

    assertEquals("fallback", runtimeConnection["phase"])
    assertEquals("in_process", runtimeConnection["transport"])
    assertEquals(true, runtimeConnection["serviceStartRequested"])
    assertEquals(false, runtimeConnection["bindingRequested"])
    assertEquals(false, runtimeConnection["binderAvailable"])
    assertEquals("binder_unavailable", runtimeConnection["fallbackReason"])
    assertEquals(runtimeConnection, shellConnection)
  }

  @Test
  fun runtimeSnapshotsProjectRuntimeOwnerWorkSummaryAcrossTrackedSessions() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-owner-work-summary"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    chatStore.appendUserMessage(activeSessionId, "Keep the original session")
    val backgroundSessionId = chatStore.createSession().activeSession.sessionId
    chatStore.selectSession(activeSessionId)
    val manager = RecordingRuntimeManager()
    val activeHandle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    ).apply {
      putRunSnapshot(
        AgentRunSnapshot(
          sessionId = activeSessionId,
          runId = "run-active",
          taskId = "task-active",
          acceptedAtEpochMs = 1_000L,
          updatedAtEpochMs = 1_100L,
          lifecycleState = QueueTaskLifecycleState.RUNNING,
          taskState = AgentTaskState.RUNNING,
        ),
      )
    }
    val backgroundHandle = RecordingSessionHandle(
      sessionId = backgroundSessionId,
      onResume = manager.resumedSessionIds::add,
    ).apply {
      putRunSnapshot(
        AgentRunSnapshot(
          sessionId = backgroundSessionId,
          runId = "run-background",
          taskId = "task-background",
          acceptedAtEpochMs = 2_000L,
          updatedAtEpochMs = 2_100L,
          lifecycleState = QueueTaskLifecycleState.RUNNING,
          taskState = AgentTaskState.RUNNING,
          managedProcessIds = listOf("proc-1"),
          runningManagedProcessCount = 1,
          hasLiveManagedProcesses = true,
        ),
      )
      putManagedProcess(
        com.opencray.runtime.process.ManagedProcessSnapshot(
          processId = "proc-1",
          taskId = "task-background",
          command = "npm",
          args = listOf("run", "dev"),
          workingDirectory = ".",
          status = com.opencray.runtime.process.ManagedProcessStatus.RUNNING,
          processStarted = true,
          timeoutMs = 120_000L,
          startedAtEpochMs = 2_000L,
          updatedAtEpochMs = 2_100L,
        ),
      )
    }
    manager.putHandle(activeHandle)
    manager.putHandle(backgroundHandle)
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      runtimeServiceDescriptor = RuntimeServiceLifecycleDescriptor(),
      runtimeServiceConnectionState = RuntimeServiceConnectionState.binderConnected(),
    )

    val runtimeSnapshot = hostRuntime.loadChatRuntimeSnapshot()
    val shellSnapshot = hostRuntime.loadShellSnapshot()
    val runtimeSummary = runtimeSnapshot["runtimeOwnerWorkSummary"] as Map<*, *>
    val shellSummary = shellSnapshot["runtimeOwnerWorkSummary"] as Map<*, *>

    assertEquals(true, runtimeSummary["hasActiveWork"])
    assertEquals(2, runtimeSummary["trackedSessionCount"])
    assertEquals(2, runtimeSummary["activeRunCount"])
    assertEquals(2, runtimeSummary["activeSessionCount"])
    assertEquals(
      setOf(activeSessionId, backgroundSessionId),
      (runtimeSummary["activeSessionIds"] as List<*>).toSet(),
    )
    assertEquals(
      setOf(activeSessionId, backgroundSessionId),
      (runtimeSummary["pendingWorkSessionIds"] as List<*>).toSet(),
    )
    assertEquals(
      listOf(backgroundSessionId),
      runtimeSummary["liveManagedProcessSessionIds"],
    )
    assertEquals(runtimeSummary, shellSummary)
  }

  @Test
  fun runtimeSnapshotsProjectRuntimeServiceWorkState() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-service-work-state"))
    val manager = RecordingRuntimeManager()
    val sessionId = chatStore.loadState().activeSession.sessionId
    manager.putHandle(
      RecordingSessionHandle(
        sessionId = sessionId,
        onResume = manager.resumedSessionIds::add,
      ),
    )
    val workState = RuntimeServiceWorkState(
      phase = RuntimeServiceWorkState.PHASE_ACTIVE_WORK,
      hasActiveWork = true,
      keepAliveRequired = true,
      keepAliveReason = RuntimeServiceWorkState.KEEP_ALIVE_REASON_ACTIVE_RUN,
      changedAtEpochMs = 9_000L,
      activeSinceEpochMs = 8_500L,
      idleSinceEpochMs = null,
    )
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      runtimeServiceDescriptor = RuntimeServiceLifecycleDescriptor(),
      runtimeServiceWorkState = workState,
      runtimeServiceConnectionState = RuntimeServiceConnectionState.binderConnected(),
    )

    val runtimeSnapshot = hostRuntime.loadChatRuntimeSnapshot()
    val shellSnapshot = hostRuntime.loadShellSnapshot()
    val runtimeWorkState = runtimeSnapshot["runtimeServiceWorkState"] as Map<*, *>
    val shellWorkState = shellSnapshot["runtimeServiceWorkState"] as Map<*, *>

    assertEquals("active_work", runtimeWorkState["phase"])
    assertEquals(true, runtimeWorkState["hasActiveWork"])
    assertEquals(true, runtimeWorkState["keepAliveRequired"])
    assertEquals("active_run", runtimeWorkState["keepAliveReason"])
    assertEquals(9_000L, runtimeWorkState["changedAtEpochMs"])
    assertEquals(8_500L, runtimeWorkState["activeSinceEpochMs"])
    assertEquals(null, runtimeWorkState["idleSinceEpochMs"])
    assertEquals(runtimeWorkState, shellWorkState)
  }

  @Test
  fun runtimeSnapshotsProjectRuntimeServiceKeepAliveState() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-service-keepalive-state"))
    val manager = RecordingRuntimeManager()
    val sessionId = chatStore.loadState().activeSession.sessionId
    manager.putHandle(
      RecordingSessionHandle(
        sessionId = sessionId,
        onResume = manager.resumedSessionIds::add,
      ),
    )
    val keepAliveState = RuntimeServiceKeepAliveState(
      phase = RuntimeServiceKeepAliveState.PHASE_IDLE_GRACE,
      idleGraceMs = 30_000L,
      stopScheduled = true,
      stopDeadlineEpochMs = 31_000L,
      lastStartId = 7,
      lastStartCommandAtEpochMs = 1_000L,
      lastStopRequestAtEpochMs = null,
      lastStopSucceeded = null,
      changedAtEpochMs = 1_500L,
    )
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      runtimeServiceDescriptor = RuntimeServiceLifecycleDescriptor(),
      runtimeServiceKeepAliveState = keepAliveState,
      runtimeServiceConnectionState = RuntimeServiceConnectionState.binderConnected(),
    )

    val runtimeSnapshot = hostRuntime.loadChatRuntimeSnapshot()
    val shellSnapshot = hostRuntime.loadShellSnapshot()
    val runtimeKeepAliveState = runtimeSnapshot["runtimeServiceKeepAliveState"] as Map<*, *>
    val shellKeepAliveState = shellSnapshot["runtimeServiceKeepAliveState"] as Map<*, *>

    assertEquals("idle_grace", runtimeKeepAliveState["phase"])
    assertEquals(30_000L, runtimeKeepAliveState["idleGraceMs"])
    assertEquals(true, runtimeKeepAliveState["stopScheduled"])
    assertEquals(31_000L, runtimeKeepAliveState["stopDeadlineEpochMs"])
    assertEquals(true, runtimeKeepAliveState["hasSeenStartCommand"])
    assertEquals(7, runtimeKeepAliveState["lastStartId"])
    assertEquals(1_000L, runtimeKeepAliveState["lastStartCommandAtEpochMs"])
    assertEquals(1_500L, runtimeKeepAliveState["changedAtEpochMs"])
    assertEquals(runtimeKeepAliveState, shellKeepAliveState)
  }

  @Test
  fun shellSnapshotProjectsLocalRuntimeServerStateFromDiagnosticsBridge() {
    OpenCrayLocalRuntimeServerRegistry.clearForTest()
    try {
      val chatStore = ChatSessionLocalStore(
        temporaryFolder.newFolder("chat-store-local-runtime-server-state"),
      )
      val manager = RecordingRuntimeManager()
      val sessionId = chatStore.loadState().activeSession.sessionId
      manager.putHandle(
        RecordingSessionHandle(
          sessionId = sessionId,
          onResume = manager.resumedSessionIds::add,
        ),
      )
      val projectedServerState = LocalRuntimeServerState(
        phase = LocalRuntimeServerState.PHASE_LISTENING,
        bindAddress = "127.0.0.1",
        requestedPort = 42_617,
        listeningPort = 48_210,
        lastStartedAtEpochMs = 1_200L,
        changedAtEpochMs = 1_250L,
      )
      val hostRuntime = hostRuntime(
        chatStore = chatStore,
        runtimeManager = manager,
        runtimeServiceDescriptor = RuntimeServiceLifecycleDescriptor(),
        localRuntimeServerStateProvider = { projectedServerState },
      )

      val shellSnapshot = hostRuntime.loadShellSnapshot()
      @Suppress("UNCHECKED_CAST")
      val localRuntimeServerState = shellSnapshot["localRuntimeServerState"] as Map<String, Any?>

      assertEquals(LocalRuntimeServerState.PHASE_LISTENING, localRuntimeServerState["phase"])
      assertEquals(48_210, localRuntimeServerState["listeningPort"])
    } finally {
      OpenCrayLocalRuntimeServerRegistry.clearForTest()
    }
  }

  @Test
  fun runtimeServiceConnectionChangesEmitShellAndRuntimeSnapshots() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-service-connection-observer"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val mainThreadPoster = QueuedMainThreadPoster()
    var connectionState = RuntimeServiceConnectionState.bindingPending()
    var connectionChangeListener: (() -> Unit)? = null
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      mainThreadPoster = mainThreadPoster,
      runtimeServiceDescriptor = RuntimeServiceLifecycleDescriptor(),
      runtimeServiceConnectionStateProvider = { connectionState },
      runtimeServiceConnectionChangeRegistrar = RuntimeServiceConnectionChangeRegistrar { listener ->
        connectionChangeListener = listener
        { if (connectionChangeListener === listener) connectionChangeListener = null }
      },
    )
    val observedShellSnapshots = mutableListOf<Map<String, Any?>>()
    val observedRuntimeSnapshots = mutableListOf<Map<String, Any?>>()
    val disposeShell = hostRuntime.observeShell { snapshot ->
      observedShellSnapshots += snapshot
    }
    val disposeRuntime = hostRuntime.observeChatRuntime { snapshot ->
      observedRuntimeSnapshots += snapshot
    }
    mainThreadPoster.flush()
    observedShellSnapshots.clear()
    observedRuntimeSnapshots.clear()

    connectionState = RuntimeServiceConnectionState.binderConnected()
    checkNotNull(connectionChangeListener).invoke()
    mainThreadPoster.flush()

    val shellConnection =
      observedShellSnapshots.last()["runtimeServiceConnectionState"] as Map<*, *>
    val runtimeConnection =
      observedRuntimeSnapshots.last()["runtimeServiceConnectionState"] as Map<*, *>

    assertEquals("bound", shellConnection["phase"])
    assertEquals("binder", shellConnection["transport"])
    assertEquals(true, shellConnection["bindingRequested"])
    assertEquals(true, shellConnection["binderAvailable"])
    assertEquals(shellConnection, runtimeConnection)

    disposeShell()
    disposeRuntime()
  }

  @Test
  fun runtimeServiceKeepAliveChangesEmitShellAndRuntimeSnapshots() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-service-keepalive-observer"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val mainThreadPoster = QueuedMainThreadPoster()
    var keepAliveState = RuntimeServiceKeepAliveState(
      phase = RuntimeServiceKeepAliveState.PHASE_CREATED,
      changedAtEpochMs = 1_000L,
    )
    var keepAliveChangeListener: (() -> Unit)? = null
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      mainThreadPoster = mainThreadPoster,
      runtimeServiceDescriptor = RuntimeServiceLifecycleDescriptor(),
      runtimeServiceKeepAliveStateProvider = { keepAliveState },
      runtimeServiceKeepAliveChangeRegistrar = RuntimeServiceKeepAliveChangeRegistrar { listener ->
        keepAliveChangeListener = listener
        { if (keepAliveChangeListener === listener) keepAliveChangeListener = null }
      },
    )
    val observedShellSnapshots = mutableListOf<Map<String, Any?>>()
    val observedRuntimeSnapshots = mutableListOf<Map<String, Any?>>()
    val disposeShell = hostRuntime.observeShell { snapshot ->
      observedShellSnapshots += snapshot
    }
    val disposeRuntime = hostRuntime.observeChatRuntime { snapshot ->
      observedRuntimeSnapshots += snapshot
    }
    mainThreadPoster.flush()
    observedShellSnapshots.clear()
    observedRuntimeSnapshots.clear()

    keepAliveState = RuntimeServiceKeepAliveState(
      phase = RuntimeServiceKeepAliveState.PHASE_IDLE_GRACE,
      idleGraceMs = 30_000L,
      stopScheduled = true,
      stopDeadlineEpochMs = 31_000L,
      lastStartId = 5,
      lastStartCommandAtEpochMs = 1_000L,
      changedAtEpochMs = 1_500L,
    )
    checkNotNull(keepAliveChangeListener).invoke()
    mainThreadPoster.flush()

    val shellKeepAlive =
      observedShellSnapshots.last()["runtimeServiceKeepAliveState"] as Map<*, *>
    val runtimeKeepAlive =
      observedRuntimeSnapshots.last()["runtimeServiceKeepAliveState"] as Map<*, *>

    assertEquals("idle_grace", shellKeepAlive["phase"])
    assertEquals(true, shellKeepAlive["stopScheduled"])
    assertEquals(31_000L, shellKeepAlive["stopDeadlineEpochMs"])
    assertEquals(5, shellKeepAlive["lastStartId"])
    assertEquals(shellKeepAlive, runtimeKeepAlive)

    disposeShell()
    disposeRuntime()
  }

  @Test
  fun hostRuntimeDisposeUnregistersRuntimeAndDiagnosticsObservers() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-host-runtime-dispose"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    manager.putHandle(
      RecordingSessionHandle(
        sessionId = activeSessionId,
        onResume = manager.resumedSessionIds::add,
      ),
    )
    val mainThreadPoster = QueuedMainThreadPoster()
    var connectionState = RuntimeServiceConnectionState.bindingPending()
    var keepAliveState = RuntimeServiceKeepAliveState(
      phase = RuntimeServiceKeepAliveState.PHASE_CREATED,
      changedAtEpochMs = 1_000L,
    )
    var connectionChangeListener: (() -> Unit)? = null
    var keepAliveChangeListener: (() -> Unit)? = null
    var connectionObserverDisposeCount = 0
    var keepAliveObserverDisposeCount = 0
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      mainThreadPoster = mainThreadPoster,
      runtimeServiceDescriptor = RuntimeServiceLifecycleDescriptor(),
      runtimeServiceConnectionStateProvider = { connectionState },
      runtimeServiceConnectionChangeRegistrar = RuntimeServiceConnectionChangeRegistrar { listener ->
        connectionChangeListener = listener
        {
          if (connectionChangeListener === listener) {
            connectionChangeListener = null
          }
          connectionObserverDisposeCount += 1
        }
      },
      runtimeServiceKeepAliveStateProvider = { keepAliveState },
      runtimeServiceKeepAliveChangeRegistrar = RuntimeServiceKeepAliveChangeRegistrar { listener ->
        keepAliveChangeListener = listener
        {
          if (keepAliveChangeListener === listener) {
            keepAliveChangeListener = null
          }
          keepAliveObserverDisposeCount += 1
        }
      },
    )
    val observedShellSnapshots = mutableListOf<Map<String, Any?>>()
    val observedRuntimeSnapshots = mutableListOf<Map<String, Any?>>()
    hostRuntime.observeShell { snapshot ->
      observedShellSnapshots += snapshot
    }
    hostRuntime.observeChatRuntime { snapshot ->
      observedRuntimeSnapshots += snapshot
    }
    mainThreadPoster.flush()
    observedShellSnapshots.clear()
    observedRuntimeSnapshots.clear()

    val capturedConnectionListener = checkNotNull(connectionChangeListener)
    val capturedKeepAliveListener = checkNotNull(keepAliveChangeListener)
    assertEquals(1, manager.observerCount)

    hostRuntime.dispose()
    hostRuntime.dispose()

    assertEquals(0, manager.observerCount)
    assertNull(connectionChangeListener)
    assertNull(keepAliveChangeListener)
    assertEquals(1, connectionObserverDisposeCount)
    assertEquals(1, keepAliveObserverDisposeCount)

    connectionState = RuntimeServiceConnectionState.binderConnected()
    keepAliveState = keepAliveState.copy(
      phase = RuntimeServiceKeepAliveState.PHASE_IDLE_GRACE,
      changedAtEpochMs = 1_500L,
    )
    capturedConnectionListener.invoke()
    capturedKeepAliveListener.invoke()
    mainThreadPoster.flush()

    assertTrue(observedShellSnapshots.isEmpty())
    assertTrue(observedRuntimeSnapshots.isEmpty())
  }

  @Test
  fun completedRunSnapshotIncludesStructuredMemoryTrace() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-run-memory-trace"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    val submission = hostRuntime.submitChatMessage("Need run memory trace")!!
    val task = handle.submittedTasks.single()
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
        stdout = "Applied the recalled memory.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata + mapOf(
          "responseFormat" to "json_final",
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

    val runSnapshot = hostRuntime.loadChatRunSnapshot(submission["runId"] as String)!!
    val memoryTrace = runSnapshot["memoryTrace"] as Map<*, *>
    val selected = memoryTrace["selected"] as List<*>
    val omitted = memoryTrace["omitted"] as List<*>
    val filteredCounts = memoryTrace["filteredCounts"] as Map<*, *>

    assertEquals(2, memoryTrace["matchedRecordCount"])
    assertEquals(1, memoryTrace["injectedRecordCount"])
    assertEquals(1, memoryTrace["omittedRecordCount"])
    assertEquals(listOf("chinese", "gradle"), memoryTrace["queryTerms"])
    assertEquals("memory-user", (selected.single() as Map<*, *>)["id"])
    assertEquals("max_records", (omitted.single() as Map<*, *>)["reason"])
    assertEquals(1, filteredCounts["scope_mismatch"])
    assertEquals(2, filteredCounts["expired"])
  }

  @Test
  fun completedRunSnapshotIncludesStructuredMemoryFlushTrace() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-run-memory-flush"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    val submission = hostRuntime.submitChatMessage("Need run memory flush trace")!!
    val task = handle.submittedTasks.single()
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
        stdout = "Applied the flushed memory.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata + mapOf(
          "responseFormat" to "json_final",
          "contextMemoryFlushOutcome" to "written",
          "contextMemoryFlushExecutionMode" to "inline",
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

    val runSnapshot = hostRuntime.loadChatRunSnapshot(submission["runId"] as String)!!
    val memoryFlush = runSnapshot["memoryFlush"] as Map<*, *>

    assertEquals("written", memoryFlush["outcome"])
    assertEquals("inline", memoryFlush["executionMode"])
    assertEquals(4, memoryFlush["omittedMessageCount"])
    assertEquals(512, memoryFlush["omittedCharCount"])
    assertEquals("flush-signature-123", memoryFlush["signature"])
    assertEquals(3, memoryFlush["candidateCount"])
    assertEquals(2, memoryFlush["writtenRecordCount"])
    assertEquals(listOf("project_fact", "user_preference"), memoryFlush["writtenKinds"])
    assertEquals(listOf("mem-a", "mem-b"), memoryFlush["writtenRecordIds"])
  }

  @Test
  fun completedRunSnapshotIncludesStructuredSkillInventory() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-run-skill-inventory"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    val submission = hostRuntime.submitChatMessage("Need run skill inventory trace")!!
    val task = handle.submittedTasks.single()
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
        stdout = "Applied the visible skills.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata + mapOf(
          "responseFormat" to "json_final",
          "contextVisibleSkillCount" to "2",
          "contextInjectedSkillCount" to "2",
          "contextOmittedSkillCount" to "0",
          "contextImplicitSkillCount" to "1",
          "contextInvalidSkillCount" to "1",
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

    val runSnapshot = hostRuntime.loadChatRunSnapshot(submission["runId"] as String)!!
    val skillInventory = runSnapshot["skillInventory"] as Map<*, *>
    val activeSkill = runSnapshot["activeSkill"] as Map<*, *>
    val skills = skillInventory["skills"] as List<*>
    val firstSkill = skills[0] as Map<*, *>
    val secondSkill = skills[1] as Map<*, *>

    assertEquals(2, skillInventory["visibleSkillCount"])
    assertEquals(2, skillInventory["injectedSkillCount"])
    assertEquals(0, skillInventory["omittedSkillCount"])
    assertEquals(1, skillInventory["implicitSkillCount"])
    assertEquals(1, skillInventory["invalidSkillCount"])
    assertEquals("ui-ux-pro-max", firstSkill["name"])
    assertEquals(".codex/skills/ui-ux-pro-max/SKILL.md", firstSkill["relativePath"])
    assertEquals("explicit-only", firstSkill["invocationControl"])
    assertEquals(true, firstSkill["userInvocable"])
    assertEquals("fork", secondSkill["executionContext"])
    assertEquals("ui-ux-pro-max", activeSkill["name"])
    assertEquals("skill_read", activeSkill["activationSource"])
    assertEquals(true, activeSkill["toolRestrictionEnabled"])
    assertEquals(listOf("read", "write"), activeSkill["allowedToolKeys"])
  }

  @Test
  fun completedRunSnapshotIncludesStructuredDurableCompaction() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-run-durable-compaction"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    val submission = hostRuntime.submitChatMessage("Need run durable compaction trace")!!
    val task = handle.submittedTasks.single()
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
        stdout = "Applied durable compaction.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata + mapOf(
          "responseFormat" to "json_final",
          "contextDurableCompactionCompactedThisRun" to "true",
          "contextDurableCompactionExecutionMode" to "inline",
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

    val runSnapshot = hostRuntime.loadChatRunSnapshot(submission["runId"] as String)!!
    val durableCompaction = runSnapshot["durableCompaction"] as Map<*, *>

    assertEquals(true, durableCompaction["compactedThisRun"])
    assertEquals("inline", durableCompaction["executionMode"])
    assertEquals(18, durableCompaction["sourceTranscriptMessageCount"])
    assertEquals(12, durableCompaction["retainedTranscriptMessageCount"])
    assertEquals(6, durableCompaction["latestCompactedMessageCount"])
    assertEquals(1, durableCompaction["includedSummaryCount"])
    assertEquals(0, durableCompaction["omittedSummaryCount"])
    assertEquals(1, durableCompaction["totalSummaryCount"])
    assertEquals(6, durableCompaction["totalCompactedMessageCount"])
    assertEquals(4200L, durableCompaction["latestCompactedAtEpochMs"])
  }

  @Test
  fun completedRunSnapshotIncludesStructuredBootstrap() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-run-bootstrap"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    val submission = hostRuntime.submitChatMessage("Need run bootstrap trace")!!
    val task = handle.submittedTasks.single()
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
        stdout = "Applied bootstrap files.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata + mapOf(
          "responseFormat" to "json_final",
          "contextLiveMode" to "no_soul",
          "contextLiveSoulEnabled" to "false",
          "contextLiveMemoryRecallEnabled" to "true",
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

    val runSnapshot = hostRuntime.loadChatRunSnapshot(submission["runId"] as String)!!
    val liveContext = runSnapshot["liveContext"] as Map<*, *>
    val bootstrap = runSnapshot["bootstrap"] as Map<*, *>
    val files = bootstrap["files"] as List<*>
    val firstFile = files[0] as Map<*, *>
    val secondFile = files[1] as Map<*, *>

    assertEquals("no_soul", liveContext["mode"])
    assertEquals(false, liveContext["soulEnabled"])
    assertEquals(true, liveContext["memoryRecallEnabled"])
    assertEquals("full", bootstrap["mode"])
    assertEquals(2, bootstrap["visibleFileCount"])
    assertEquals(2, bootstrap["injectedFileCount"])
    assertEquals(0, bootstrap["omittedFileCount"])
    assertEquals(1, bootstrap["truncatedFileCount"])
    assertEquals("AGENTS.md", firstFile["name"])
    assertEquals("AGENTS.md", firstFile["relativePath"])
    assertEquals(42, firstFile["sourceCharCount"])
    assertEquals(42, firstFile["injectedCharCount"])
    assertEquals(false, firstFile["truncated"])
    assertEquals(true, secondFile["truncated"])
  }

  @Test
  fun completedRunSnapshotIncludesStructuredContextBudget() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-run-context-budget"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    val submission = hostRuntime.submitChatMessage("Need run context budget trace")!!
    val task = handle.submittedTasks.single()
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
        stdout = "Applied context budget.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata + mapOf(
          "responseFormat" to "json_final",
          "contextBudgetApplied" to "true",
          "contextBudgetPressureMode" to "EMERGENCY",
          "contextBudgetContextWindowTokens" to "900",
          "contextBudgetReservedOutputTokens" to "256",
          "contextBudgetSafetyMarginTokens" to "96",
          "contextBudgetSelectedPreset" to "balanced",
          "contextBudgetEffectivePreset" to "dev",
          "contextBudgetPresetSource" to "raw",
          "contextBudgetPresetDiverged" to "true",
          "contextBudgetSourcePreset" to "expanded",
          "contextBudgetSourceTranscriptMaxMessages" to "16",
          "contextBudgetSourceInjectedMemoryMaxRecords" to "6",
          "contextBudgetSourceMemoryRecallMaxRecords" to "8",
          "contextBudgetSourceBootstrapMaxChars" to "4800",
          "contextBudgetSourceSkillInventoryMaxSkills" to "12",
          "contextBudgetSourceActiveSkillMaxChars" to "4800",
          "contextBudgetSourceRecentObservationMaxEntries" to "6",
          "contextBudgetSourceMemoryFlushMaxToolObservations" to "12",
          "contextBudgetHardInputTokens" to "548",
          "contextBudgetTargetInputTokens" to "512",
          "contextBudgetEmergencyInputTokens" to "548",
          "contextBudgetUnresolvedOverflow" to "true",
          "contextBudgetFullLayerCount" to "4",
          "contextBudgetCompactLayerCount" to "2",
          "contextBudgetMinimalLayerCount" to "1",
          "contextBudgetOmittedLayerCount" to "1",
          "contextBudgetReducedLayerNames" to "Working State,Conversation",
          "contextBudgetOmittedLayerNames" to "Retrieved Memory",
          "contextBudgetLayerDetails" to """
            [
              {
                "id": "WORKING_STATE",
                "name": "Working State",
                "priorityClass": "OPTIONAL_SUPPORT_CONTEXT",
                "retentionPriority": 70,
                "estimatedTokensBefore": 220,
                "estimatedTokensAfter": 120,
                "finalState": "compact",
                "omitted": false,
                "reduced": true,
                "appliedOperators": ["reduce_working_state_compact"]
              },
              {
                "id": "CONVERSATION",
                "name": "Conversation",
                "priorityClass": "RECENT_REPLAY",
                "retentionPriority": 110,
                "estimatedTokensBefore": 420,
                "estimatedTokensAfter": 180,
                "finalState": "minimal",
                "omitted": false,
                "reduced": true,
                "appliedOperators": ["reduce_conversation_window_minimal"]
              },
              {
                "id": "RETRIEVED_MEMORY",
                "name": "Retrieved Memory",
                "priorityClass": "BOUNDED_DURABLE_RECALL",
                "retentionPriority": 90,
                "estimatedTokensBefore": 48,
                "estimatedTokensAfter": 0,
                "finalState": "omitted",
                "omitted": true,
                "reduced": false,
                "appliedOperators": ["omit_layer"]
              }
            ]
          """.trimIndent(),
          "contextBudgetLayerSummary" to
            "WORKING_STATE:compact:20;CONVERSATION:minimal:120;RETRIEVED_MEMORY:omitted:48",
        ),
      ),
    )

    val runSnapshot = hostRuntime.loadChatRunSnapshot(submission["runId"] as String)!!
    val contextBudget = runSnapshot["contextBudget"] as Map<*, *>

    assertEquals(true, contextBudget["applied"])
    assertEquals("EMERGENCY", contextBudget["pressureMode"])
    assertEquals("balanced", contextBudget["selectedPreset"])
    assertEquals("dev", contextBudget["effectivePreset"])
    assertEquals("raw", contextBudget["presetSource"])
    assertEquals(true, contextBudget["presetDiverged"])
    assertEquals("expanded", contextBudget["sourcePreset"])
    assertEquals(16, contextBudget["sourceTranscriptMaxMessages"])
    assertEquals(6, contextBudget["sourceInjectedMemoryMaxRecords"])
    assertEquals(8, contextBudget["sourceMemoryRecallMaxRecords"])
    assertEquals(4800, contextBudget["sourceBootstrapMaxChars"])
    assertEquals(12, contextBudget["sourceSkillInventoryMaxSkills"])
    assertEquals(4800, contextBudget["sourceActiveSkillMaxChars"])
    assertEquals(6, contextBudget["sourceRecentObservationMaxEntries"])
    assertEquals(12, contextBudget["sourceMemoryFlushMaxToolObservations"])
    assertEquals(900, contextBudget["contextWindowTokens"])
    assertEquals(256, contextBudget["reservedOutputTokens"])
    assertEquals(96, contextBudget["safetyMarginTokens"])
    assertEquals(548, contextBudget["hardInputTokens"])
    assertEquals(512, contextBudget["targetInputTokens"])
    assertEquals(548, contextBudget["emergencyInputTokens"])
    assertEquals(true, contextBudget["unresolvedOverflow"])
    assertEquals(4, contextBudget["fullLayerCount"])
    assertEquals(2, contextBudget["compactLayerCount"])
    assertEquals(1, contextBudget["minimalLayerCount"])
    assertEquals(1, contextBudget["omittedLayerCount"])
    assertEquals(
      listOf("Working State", "Conversation"),
      contextBudget["reducedLayerNames"],
    )
    assertEquals(listOf("Retrieved Memory"), contextBudget["omittedLayerNames"])
    assertEquals(
      "WORKING_STATE:compact:20;CONVERSATION:minimal:120;RETRIEVED_MEMORY:omitted:48",
      contextBudget["layerSummary"],
    )
    val layers = contextBudget["layers"] as List<*>
    assertEquals(3, layers.size)
    val workingStateLayer = layers[0] as Map<*, *>
    assertEquals("WORKING_STATE", workingStateLayer["id"])
    assertEquals("Working State", workingStateLayer["name"])
    assertEquals("OPTIONAL_SUPPORT_CONTEXT", workingStateLayer["priorityClass"])
    assertEquals(70, workingStateLayer["retentionPriority"])
    assertEquals(220, workingStateLayer["estimatedTokensBefore"])
    assertEquals(120, workingStateLayer["estimatedTokensAfter"])
    assertEquals("compact", workingStateLayer["finalState"])
    assertEquals(false, workingStateLayer["omitted"])
    assertEquals(true, workingStateLayer["reduced"])
    assertEquals(
      listOf("reduce_working_state_compact"),
      workingStateLayer["appliedOperators"],
    )
  }

  @Test
  fun completedRunSnapshotIncludesLlmDiagnosticsCacheBreakReason() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-run-llm-diagnostics"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    val submission = hostRuntime.submitChatMessage("Need cache-break diagnostics")!!
    val task = handle.submittedTasks.single()
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
        stdout = "Captured LLM diagnostics.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata + mapOf(
          LiteLlmMetadataKeys.PROVIDER_RESPONSE_SHAPE to "openai_tool_calls",
          LiteLlmMetadataKeys.NATIVE_TOOL_CALL_REQUESTED to "true",
          LiteLlmMetadataKeys.NATIVE_TOOL_CALL_OBSERVED to "true",
          LiteLlmMetadataKeys.PARSED_TOOL_CALL_OBSERVED to "true",
          LiteLlmMetadataKeys.FALLBACK_PARSER_ATTEMPTED to "false",
          LiteLlmMetadataKeys.FALLBACK_PARSER_SUCCEEDED to "false",
          "responsesContinuationRecoveryCount" to "1",
          "responsesContinuationRecoveryLastReason" to "responses_restored_replay_required",
          "localContinuationUsedCount" to "0",
          "localContinuationFallbackCount" to "1",
          "localContinuationLastMode" to "full_rebuild",
          "localContinuationLastReason" to "user_setting_changed",
          "responsesPendingContextUpdateCount" to "2",
          "responsesPendingContextUpdateHash" to "ctx-update-hash",
          LiteLlmMetadataKeys.TOOL_CALL_EVENT_EMITTED to "true",
          LiteLlmMetadataKeys.TOOL_RESULT_EVENT_EMITTED to "true",
          LiteLlmMetadataKeys.CONTEXT_CACHE_BREAK_REASON to "user_setting_changed",
          LiteLlmMetadataKeys.LAST_SUCCESSFUL_TOOL_NAME to "EchoProbe",
        ),
      ),
    )

    val runSnapshot = hostRuntime.loadChatRunSnapshot(submission["runId"] as String)!!
    val llmDiagnostics = runSnapshot["llmDiagnostics"] as Map<*, *>

    assertEquals("openai_tool_calls", llmDiagnostics["providerResponseShape"])
    assertEquals(true, llmDiagnostics["nativeToolCallRequested"])
    assertEquals(true, llmDiagnostics["nativeToolCallObserved"])
    assertEquals(true, llmDiagnostics["parsedToolCallObserved"])
    assertEquals(false, llmDiagnostics["fallbackParserAttempted"])
    assertEquals(false, llmDiagnostics["fallbackParserSucceeded"])
    assertEquals(1, llmDiagnostics["responsesContinuationRecoveryCount"])
    assertEquals(
      "responses_restored_replay_required",
      llmDiagnostics["responsesContinuationRecoveryLastReason"],
    )
    assertEquals(0, llmDiagnostics["localContinuationUsedCount"])
    assertEquals(1, llmDiagnostics["localContinuationFallbackCount"])
    assertEquals("full_rebuild", llmDiagnostics["localContinuationLastMode"])
    assertEquals("user_setting_changed", llmDiagnostics["localContinuationLastReason"])
    assertEquals(2, llmDiagnostics["responsesPendingContextUpdateCount"])
    assertEquals("ctx-update-hash", llmDiagnostics["responsesPendingContextUpdateHash"])
    assertEquals(true, llmDiagnostics["toolCallEventEmitted"])
    assertEquals(true, llmDiagnostics["toolResultEventEmitted"])
    assertEquals("user_setting_changed", llmDiagnostics["contextCacheBreakReason"])
    assertEquals("EchoProbe", llmDiagnostics["lastSuccessfulToolName"])
  }

  @Test
  fun runSnapshotIncludesManagedProcessLinkageAndKeepsLiveProcessRunVisible() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-run-process-linkage"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    val submission = hostRuntime.submitChatMessage("Start the dev server")!!
    val task = handle.submittedTasks.single()
    val fullStdout = "booting\nready on http://localhost:3000"
    val fullStderr = "warn: deprecated dependency"
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
        stdout = fullStdout,
        stderr = fullStderr,
        startedAtEpochMs = 1_000L,
        updatedAtEpochMs = 1_001L,
      ),
    )
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = com.opencray.runtime.OpenCrayToolResultEvent(
        runId = submission["runId"] as String,
        taskId = task.id,
        turn = 1,
        call = AgentToolCall(
          toolName = "ProcessStart",
        ),
        result = AgentToolResult(
          toolName = "ProcessStart",
          status = AgentToolResultStatus.SUCCESS,
          content = "Started dev server",
          metadata = mapOf("processId" to "proc-live"),
        ),
        emittedAtEpochMs = 1_001L,
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

    val runSnapshot = hostRuntime.loadChatRunSnapshot(submission["runId"] as String)!!
    val runtimeActivity = hostRuntime.loadChatSnapshot()["runtimeActivity"] as Map<*, *>
    val activeRuns = runtimeActivity["activeRuns"] as List<*>
    val activeRun = activeRuns.single() as Map<*, *>
    val runManagedProcesses = runSnapshot["managedProcesses"] as List<*>
    val activeManagedProcesses = activeRun["managedProcesses"] as List<*>
    val runManagedProcess = runManagedProcesses.single() as Map<*, *>
    val activeManagedProcess = activeManagedProcesses.single() as Map<*, *>

    assertEquals(listOf("proc-live"), runSnapshot["managedProcessIds"])
    assertEquals(1, runManagedProcesses.size)
    assertEquals("proc-live", runManagedProcess["processId"])
    assertEquals("npm", runManagedProcess["command"])
    assertEquals(fullStdout, runManagedProcess["stdout"])
    assertEquals(fullStderr, runManagedProcess["stderr"])
    assertEquals(fullStdout, runManagedProcess["stdoutPreview"])
    assertEquals(fullStderr, runManagedProcess["stderrPreview"])
    assertEquals(1, runSnapshot["runningManagedProcessCount"])
    assertEquals(true, runSnapshot["hasLiveManagedProcesses"])
    assertEquals(true, runSnapshot["isTerminal"])
    assertEquals(true, runSnapshot["isActive"])
    assertEquals(submission["runId"], activeRun["runId"])
    assertEquals(1, activeManagedProcesses.size)
    assertEquals("proc-live", activeManagedProcess["processId"])
    assertEquals(fullStdout, activeManagedProcess["stdout"])
    assertEquals(fullStderr, activeManagedProcess["stderr"])
    assertEquals(true, activeRun["hasLiveManagedProcesses"])
  }

  @Test
  fun loadChatRuntimeSnapshotRefreshesManagedProcessSnapshotsWhenOwnerSummaryIsIdle() {
    val chatStore = ChatSessionLocalStore(
      temporaryFolder.newFolder("chat-store-runtime-live-process-refresh"),
    )
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager().apply {
      forceIdleWorkSummary = true
    }
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
    )

    val submission = hostRuntime.submitChatMessage("Start the dev server")!!
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
        startedAtEpochMs = 1_000L,
        updatedAtEpochMs = 1_100L,
      ),
    )

    val firstManagedProcess = managedProcessSnapshotForRun(
      runtimeSnapshot = hostRuntime.loadChatRuntimeSnapshot(),
      runId = submission["runId"] as String,
    )
    assertEquals("proc-live", firstManagedProcess["processId"])
    assertEquals(
      "ready on http://localhost:3000",
      firstManagedProcess["stdout"],
    )
    assertEquals(
      "ready on http://localhost:3000",
      firstManagedProcess["stdoutPreview"],
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
        stdout = "ready on http://localhost:3000\ncompiled successfully",
        startedAtEpochMs = 1_000L,
        updatedAtEpochMs = 1_200L,
      ),
    )

    val refreshedManagedProcess = managedProcessSnapshotForRun(
      runtimeSnapshot = hostRuntime.loadChatRuntimeSnapshot(),
      runId = submission["runId"] as String,
      stdoutPreview = "compiled successfully",
    )
    assertEquals("proc-live", refreshedManagedProcess["processId"])
    assertTrue(
      (refreshedManagedProcess["stdout"] as String).contains(
        "compiled successfully",
      ),
    )
    assertTrue(
      (refreshedManagedProcess["stdoutPreview"] as String).contains(
        "compiled successfully",
      ),
    )
  }
}
