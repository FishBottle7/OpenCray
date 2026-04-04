package com.opencray.app

import com.opencray.app.facade.safety.EmptySafetySettingsFacade
import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskState
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.orchestrator.EXECUTION_KIND_CHECKPOINT_RESUME
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.QueueTaskLifecycleState
import com.opencray.core.orchestrator.SessionLifecycleState
import com.opencray.core.orchestrator.SessionQueueSnapshot
import com.opencray.core.orchestrator.SessionQueueTaskSnapshot
import com.opencray.persistence.model.ChatTranscriptMessageEntry
import com.opencray.runtime.ERROR_LLM_RETRY_EXHAUSTED_AWAITING_RESUME
import com.opencray.runtime.OpenCrayFinalAttachment
import com.opencray.runtime.OpenCrayCancellationEvent
import com.opencray.runtime.OpenCraySubAgentEvent
import com.opencray.runtime.subagent.SubAgentHandleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ServiceOwnedChatRuntimeGatewayTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun serviceOwnedChatRuntimeGatewayRoutesSessionMutationsThroughServiceOwnedAccess() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("service-owned-chat-store"))
    val initialSessionId = chatStore.loadState().activeSession.sessionId
    val branchMessageId = chatStore
      .appendUserMessage(initialSessionId, "Need a branchable message")
      .activeSession
      .messages
      .last()
      .messageId
    val runtimeHostAccess = RecordingRuntimeHostAccess()
    val pendingApprovalState = ChatPendingApprovalState()
    val runtimeEventState = ChatRuntimeEventState()
    val repairEvents = mutableListOf<RepairEvent>()
    val delegate = RecordingChatGateway("delegate")
    val gateway = ServiceOwnedChatRuntimeGateway(
      delegate = delegate,
      readGateway = RecordingChatGateway("projection"),
      snapshotNotifier = delegate::notifyChatSnapshotsChanged,
      chatSessionMutationAccess = ServiceOwnedChatSessionMutationAccess(
        chatSessionStore = chatStore,
        runtimeHostAccess = runtimeHostAccess,
        chatUnreadMessageState = ChatUnreadMessageState(),
        pendingApprovalState = pendingApprovalState,
        runtimeEventState = runtimeEventState,
        terminalReplayRepairer = { sessionId, runs ->
          repairEvents += RepairEvent(
            sessionId = sessionId,
            runIds = runs.map(AgentRunSnapshot::runId),
            resumeCallCountAtRepair = runtimeHostAccess.resumeCallCount(sessionId),
          )
        },
      ),
      mainThreadPoster = ImmediateMainThreadPoster,
    )

    gateway.createChatSession()
    val createdSessionId = chatStore.loadState().activeSession.sessionId
    gateway.copyChatSession(initialSessionId)
    val copiedSessionId = chatStore.loadState().activeSession.sessionId
    gateway.selectChatSession(initialSessionId)
    gateway.deleteChatSession(copiedSessionId)
    gateway.branchChatSessionFromMessage(initialSessionId, branchMessageId)
    val branchedSessionId = chatStore.loadState().activeSession.sessionId
    val notificationCountAfterValidBranch = delegate.notifiedChatSnapshotCount
    gateway.branchChatSessionFromMessage("missing-session", branchMessageId)
    gateway.branchChatSessionFromMessage(initialSessionId, " ")

    assertTrue(createdSessionId != initialSessionId)
    assertTrue(copiedSessionId != initialSessionId)
    assertTrue(branchedSessionId != initialSessionId)
    assertEquals(0, delegate.createChatSessionCallCount)
    assertTrue(delegate.copiedSessionIds.isEmpty())
    assertTrue(delegate.selectedSessionIds.isEmpty())
    assertTrue(delegate.deletedSessionIds.isEmpty())
    assertTrue(delegate.branchedSessionRequests.isEmpty())
    assertEquals(5, delegate.notifiedChatSnapshotCount)
    assertEquals(notificationCountAfterValidBranch, delegate.notifiedChatSnapshotCount)
    assertEquals(
      listOf(
        createdSessionId,
        copiedSessionId,
        initialSessionId,
        initialSessionId,
        branchedSessionId,
      ),
      runtimeHostAccess.resumeHistory,
    )
    assertEquals(1, runtimeHostAccess.resumeCallCount(createdSessionId))
    assertEquals(1, runtimeHostAccess.resumeCallCount(copiedSessionId))
    assertEquals(2, runtimeHostAccess.resumeCallCount(initialSessionId))
    assertEquals(1, runtimeHostAccess.resumeCallCount(branchedSessionId))
    assertEquals(
      listOf(
        createdSessionId,
        copiedSessionId,
        initialSessionId,
        initialSessionId,
        branchedSessionId,
      ),
      repairEvents.map(RepairEvent::sessionId),
    )
    assertEquals(branchedSessionId, chatStore.loadState().activeSession.sessionId)
    assertEquals(
      listOf(
        listOf("run-$createdSessionId"),
        listOf("run-$copiedSessionId"),
        listOf("run-$initialSessionId"),
        listOf("run-$initialSessionId"),
        listOf("run-$branchedSessionId"),
      ),
      repairEvents.map(RepairEvent::runIds),
    )
    assertEquals(listOf(1, 1, 1, 2, 1), repairEvents.map(RepairEvent::resumeCallCountAtRepair))
  }

  @Test
  fun serviceOwnedChatRuntimeGatewayRoutesMessageMutationsThroughServiceOwnedAccessAndClearsApprovals() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("service-owned-chat-message-store"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val deleteMessageId = chatStore
      .appendUserMessage(sessionId, "Delete me")
      .activeSession
      .messages
      .last()
      .messageId
    val recallMessageId = chatStore
      .appendUserMessage(sessionId, "Recall me")
      .activeSession
      .messages
      .last()
      .messageId
    val assistantAfterRecallId = chatStore
      .appendAssistantPlaceholder(sessionId, "Assistant after recall")
      .activeSession
      .messages
      .last()
      .messageId
    val runtimeHostAccess = RecordingRuntimeHostAccess()
    val pendingApprovalState = ChatPendingApprovalState()
    val runtimeEventState = ChatRuntimeEventState()
    val repairEvents = mutableListOf<RepairEvent>()
    val delegate = RecordingChatGateway("delegate")
    val gateway = ServiceOwnedChatRuntimeGateway(
      delegate = delegate,
      readGateway = RecordingChatGateway("projection"),
      snapshotNotifier = delegate::notifyChatSnapshotsChanged,
      chatSessionMutationAccess = ServiceOwnedChatSessionMutationAccess(
        chatSessionStore = chatStore,
        runtimeHostAccess = runtimeHostAccess,
        chatUnreadMessageState = ChatUnreadMessageState(),
        pendingApprovalState = pendingApprovalState,
        runtimeEventState = runtimeEventState,
        terminalReplayRepairer = { repairedSessionId, runs ->
          repairEvents += RepairEvent(
            sessionId = repairedSessionId,
            runIds = runs.map(AgentRunSnapshot::runId),
            resumeCallCountAtRepair = runtimeHostAccess.resumeCallCount(repairedSessionId),
          )
        },
      ),
      mainThreadPoster = ImmediateMainThreadPoster,
    )
    pendingApprovalState.put(
      sessionId = sessionId,
      taskId = "task-delete",
      approval = approvalSnapshot(
        runId = "run-delete",
        taskId = "task-delete",
        pendingMessageId = deleteMessageId,
      ),
    )
    pendingApprovalState.put(
      sessionId = sessionId,
      taskId = "task-recall-user",
      approval = approvalSnapshot(
        runId = "run-recall-user",
        taskId = "task-recall-user",
        pendingMessageId = recallMessageId,
      ),
    )
    pendingApprovalState.put(
      sessionId = sessionId,
      taskId = "task-recall-assistant",
      approval = approvalSnapshot(
        runId = "run-recall-assistant",
        taskId = "task-recall-assistant",
        pendingMessageId = assistantAfterRecallId,
      ),
    )
    runtimeHostAccess.promptCheckpointStore(sessionId).upsert(
      approvalCheckpoint(
        sessionId = sessionId,
        runId = "run-delete",
        taskId = "task-delete",
        pendingMessageId = deleteMessageId,
      ),
    )
    runtimeHostAccess.promptCheckpointStore(sessionId).upsert(
      approvalCheckpoint(
        sessionId = sessionId,
        runId = "run-recall-user",
        taskId = "task-recall-user",
        pendingMessageId = recallMessageId,
      ),
    )
    runtimeHostAccess.promptCheckpointStore(sessionId).upsert(
      approvalCheckpoint(
        sessionId = sessionId,
        runId = "run-recall-assistant",
        taskId = "task-recall-assistant",
        pendingMessageId = assistantAfterRecallId,
      ),
    )

    gateway.deleteChatMessage(sessionId, deleteMessageId)
    gateway.recallChatMessage(sessionId, recallMessageId)
    val notificationCountAfterValidRecall = delegate.notifiedChatSnapshotCount
    gateway.recallChatMessage(sessionId, assistantAfterRecallId)
    gateway.deleteChatMessage(sessionId, " ")

    val remainingMessageIds = chatStore.loadSession(sessionId)
      ?.messages
      ?.map(ChatTranscriptMessageEntry::messageId)
      .orEmpty()
    assertTrue(delegate.deletedMessages.isEmpty())
    assertTrue(delegate.recalledMessages.isEmpty())
    assertEquals(2, delegate.notifiedChatSnapshotCount)
    assertEquals(notificationCountAfterValidRecall, delegate.notifiedChatSnapshotCount)
    assertEquals(
      listOf(
        setOf(deleteMessageId),
        linkedSetOf(recallMessageId, assistantAfterRecallId),
      ),
      runtimeHostAccess.cancelledPendingMessageIds(sessionId),
    )
    assertEquals(
      listOf(
        sessionId to "task-delete",
        sessionId to "task-recall-user",
        sessionId to "task-recall-assistant",
      ),
      runtimeHostAccess.clearedApprovals,
    )
    assertNull(runtimeHostAccess.promptCheckpointStore(sessionId).get("task-delete"))
    assertNull(runtimeHostAccess.promptCheckpointStore(sessionId).get("task-recall-user"))
    assertNull(runtimeHostAccess.promptCheckpointStore(sessionId).get("task-recall-assistant"))
    assertTrue(pendingApprovalState.approvalsForSession(sessionId).isEmpty())
    assertTrue(deleteMessageId !in remainingMessageIds)
    assertTrue(recallMessageId !in remainingMessageIds)
    assertTrue(assistantAfterRecallId !in remainingMessageIds)
    assertEquals(listOf(sessionId, sessionId), repairEvents.map(RepairEvent::sessionId))
    assertEquals(listOf(0, 0), repairEvents.map(RepairEvent::resumeCallCountAtRepair))
  }

  @Test
  fun serviceOwnedChatRuntimeGatewayFallsBackToDelegateSessionAndMessageMutationsWithoutServiceOwnedAccess() {
    val delegate = RecordingChatGateway("delegate")
    val gateway = ServiceOwnedChatRuntimeGateway(
      delegate = delegate,
      readGateway = RecordingChatGateway("projection"),
      snapshotNotifier = delegate::notifyChatSnapshotsChanged,
      mainThreadPoster = ImmediateMainThreadPoster,
    )

    gateway.createChatSession()
    gateway.copyChatSession("session-copy")
    gateway.selectChatSession("session-selected")
    gateway.deleteChatSession("session-deleted")
    gateway.branchChatSessionFromMessage("session-branch", "message-1")
    gateway.deleteChatMessage("session-delete-message", "message-2")
    gateway.recallChatMessage("session-recall-message", "message-3")
    gateway.approveChatApproval("run-approve")
    gateway.approveChatApprovalForSession("run-approve-session")
    gateway.rejectChatApproval("run-reject")
    gateway.interruptChatRun("run-interrupt")
    gateway.retryChatRun("run-retry")
    val submitPayload = gateway.submitChatMessage("hello", emptyList())

    assertEquals(1, delegate.createChatSessionCallCount)
    assertEquals(listOf("session-copy"), delegate.copiedSessionIds)
    assertEquals(listOf("session-selected"), delegate.selectedSessionIds)
    assertEquals(listOf("session-deleted"), delegate.deletedSessionIds)
    assertEquals(listOf("session-branch" to "message-1"), delegate.branchedSessionRequests)
    assertEquals(listOf("session-delete-message" to "message-2"), delegate.deletedMessages)
    assertEquals(listOf("session-recall-message" to "message-3"), delegate.recalledMessages)
    assertEquals(listOf("run-approve"), delegate.approvedRunIds)
    assertEquals(listOf("run-approve-session"), delegate.approvedForSessionRunIds)
    assertEquals(listOf("run-reject"), delegate.rejectedRunIds)
    assertEquals(listOf("run-interrupt"), delegate.interruptedRunIds)
    assertEquals(listOf("run-retry"), delegate.retriedRunIds)
    assertEquals("hello", submitPayload?.get("submittedText"))
    assertEquals("hello", delegate.submittedText)
    assertEquals(0, delegate.notifiedChatSnapshotCount)
  }

  @Test
  fun serviceOwnedChatRuntimeGatewayRoutesSubmitThroughServiceOwnedAccess() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("service-owned-chat-submit-store"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val runtimeHostAccess = RecordingRuntimeHostAccess().apply {
      putSession(
        RecordingRuntimeSessionAccess(
          sessionId = sessionId,
          resumeHistory = resumeHistory,
          runs = emptyList(),
        ),
      )
    }
    val delegate = RecordingChatGateway("delegate")
    val gateway = ServiceOwnedChatRuntimeGateway(
      delegate = delegate,
      readGateway = RecordingChatGateway("projection"),
      snapshotNotifier = delegate::notifyChatSnapshotsChanged,
      chatSubmissionAccess = ServiceOwnedChatSubmissionAccess(
        chatSessionStore = chatStore,
        runtimeHostAccess = runtimeHostAccess,
        safetySettingsFacade = EmptySafetySettingsFacade,
        workspaceRootProvider = null,
      ),
      mainThreadPoster = ImmediateMainThreadPoster,
    )

    val payload = gateway.submitChatMessage("hello", emptyList())
    val submissionRequest = runtimeHostAccess.promptSubmissionRequests(sessionId).single()
    val messages = requireNotNull(chatStore.loadSession(sessionId))
      .messages
      .takeLast(2)
      .map(ChatTranscriptMessageEntry::text)

    assertNull(delegate.submittedText)
    assertEquals(1, delegate.notifiedChatSnapshotCount)
    assertEquals(sessionId, payload?.get("sessionId"))
    assertEquals("run-submitted-$sessionId-1", payload?.get("runId"))
    assertEquals("task-submitted-$sessionId-1", payload?.get("taskId"))
    assertEquals("hello", submissionRequest.userText)
    assertEquals(PolicyDecisionOutcome.ALLOW, submissionRequest.policyDecision.outcome)
    assertEquals("FLUTTER_CHAT_ALLOW", submissionRequest.policyDecision.reasonCode)
    assertEquals("hello", submissionRequest.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_PROMPT_USER_TEXT])
    assertEquals(1, runtimeHostAccess.ensureProcessingCallCount(sessionId))
    assertEquals(listOf("hello", "Thinking"), messages)
  }

  @Test
  fun serviceOwnedChatRuntimeGatewaySubmitWithoutMutationSkipsNotification() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("service-owned-chat-submit-empty-store"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val initialMessages = requireNotNull(chatStore.loadSession(sessionId)).messages
    val runtimeHostAccess = RecordingRuntimeHostAccess()
    val delegate = RecordingChatGateway("delegate")
    val gateway = ServiceOwnedChatRuntimeGateway(
      delegate = delegate,
      readGateway = RecordingChatGateway("projection"),
      snapshotNotifier = delegate::notifyChatSnapshotsChanged,
      chatSubmissionAccess = ServiceOwnedChatSubmissionAccess(
        chatSessionStore = chatStore,
        runtimeHostAccess = runtimeHostAccess,
        safetySettingsFacade = EmptySafetySettingsFacade,
        workspaceRootProvider = null,
      ),
      mainThreadPoster = ImmediateMainThreadPoster,
    )

    val payload = gateway.submitChatMessage("   ", emptyList())

    assertNull(payload)
    assertNull(delegate.submittedText)
    assertEquals(0, delegate.notifiedChatSnapshotCount)
    assertTrue(runtimeHostAccess.promptSubmissionRequestsOrEmpty(sessionId).isEmpty())
    assertEquals(initialMessages, requireNotNull(chatStore.loadSession(sessionId)).messages)
  }

  @Test
  fun serviceOwnedChatRuntimeGatewayRoutesRetryThroughServiceOwnedRunControlAccess() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("service-owned-chat-retry-store"))
    val llmResumeSessionId = chatStore.loadState().activeSession.sessionId
    val deferredResumeSessionId = chatStore.copySession(llmResumeSessionId).activeSession.sessionId
    val retrySessionId = chatStore.copySession(deferredResumeSessionId).activeSession.sessionId
    chatStore.selectSession(llmResumeSessionId)
    val llmResumeRun = AgentRunSnapshot(
      sessionId = llmResumeSessionId,
      runId = "run-llm-resume",
      taskId = "task-llm-resume",
      acceptedAtEpochMs = 1_000L,
      updatedAtEpochMs = 1_100L,
      lifecycleState = QueueTaskLifecycleState.SUSPENDED,
      taskState = AgentTaskState.SUSPENDED,
      errorCode = ERROR_LLM_RETRY_EXHAUSTED_AWAITING_RESUME,
    )
    val deferredResumeRun = AgentRunSnapshot(
      sessionId = deferredResumeSessionId,
      runId = "run-deferred-resume",
      taskId = "task-deferred-resume",
      acceptedAtEpochMs = 2_000L,
      updatedAtEpochMs = 2_100L,
      lifecycleState = QueueTaskLifecycleState.SUSPENDED,
      taskState = AgentTaskState.SUSPENDED,
    )
    val retryRun = AgentRunSnapshot(
      sessionId = retrySessionId,
      runId = "run-retry",
      taskId = "task-retry",
      acceptedAtEpochMs = 3_000L,
      updatedAtEpochMs = 3_100L,
      lifecycleState = QueueTaskLifecycleState.FAILED,
      taskState = AgentTaskState.FAILED,
    )
    val runtimeHostAccess = RecordingRuntimeHostAccess().apply {
      putSession(
        RecordingRuntimeSessionAccess(
          sessionId = llmResumeSessionId,
          resumeHistory = resumeHistory,
          runs = listOf(llmResumeRun),
          resumeTaskResult = true,
        ),
      )
      putSession(
        RecordingRuntimeSessionAccess(
          sessionId = deferredResumeSessionId,
          resumeHistory = resumeHistory,
          runs = listOf(deferredResumeRun),
          resumeTaskResult = true,
        ),
      )
      putSession(
        RecordingRuntimeSessionAccess(
          sessionId = retrySessionId,
          resumeHistory = resumeHistory,
          runs = listOf(retryRun),
          retryResult = true,
        ),
      )
    }
    runtimeHostAccess.promptCheckpointStore(deferredResumeSessionId).upsert(
      approvalCheckpoint(
        sessionId = deferredResumeSessionId,
        runId = deferredResumeRun.runId,
        taskId = deferredResumeRun.taskId,
        pendingMessageId = "pending-deferred",
      ).copy(checkpointKind = PromptCheckpointKind.APPROVED_PENDING_RESUME),
    )
    val delegate = RecordingChatGateway("delegate")
    val gateway = ServiceOwnedChatRuntimeGateway(
      delegate = delegate,
      readGateway = RecordingChatGateway("projection"),
      snapshotNotifier = delegate::notifyChatSnapshotsChanged,
      chatRunControlAccess = ServiceOwnedChatRunControlAccess(
        chatSessionStore = chatStore,
        runtimeHostAccess = runtimeHostAccess,
      ),
      mainThreadPoster = ImmediateMainThreadPoster,
    )

    gateway.retryChatRun(llmResumeRun.runId)
    gateway.retryChatRun(deferredResumeRun.taskId)
    gateway.retryChatRun(retryRun.runId)

    assertTrue(delegate.retriedRunIds.isEmpty())
    assertEquals(3, delegate.notifiedChatSnapshotCount)
    assertEquals(
      listOf(
        ResumeRequest(
          taskId = llmResumeRun.taskId,
          executionKind = EXECUTION_KIND_CHECKPOINT_RESUME,
          taskMetadataUpdates = emptyMap(),
        ),
      ),
      runtimeHostAccess.resumeRequests(llmResumeSessionId),
    )
    assertEquals(
      listOf(
        ResumeRequest(
          taskId = deferredResumeRun.taskId,
          executionKind = null,
          taskMetadataUpdates = emptyMap(),
        ),
      ),
      runtimeHostAccess.resumeRequests(deferredResumeSessionId),
    )
    assertEquals(listOf(retryRun.taskId), runtimeHostAccess.retriedTaskIds(retrySessionId))
  }

  @Test
  fun serviceOwnedChatRuntimeGatewayRoutesApprovalCommandsThroughServiceOwnedAccess() {
    val delegate = RecordingChatGateway("delegate")
    val approved = mutableListOf<String>()
    val approvedForSession = mutableListOf<String>()
    val rejected = mutableListOf<String>()
    val gateway = ServiceOwnedChatRuntimeGateway(
      delegate = delegate,
      readGateway = RecordingChatGateway("projection"),
      snapshotNotifier = delegate::notifyChatSnapshotsChanged,
      chatApprovalAccess = ServiceOwnedChatApprovalAccess(
        approveChatApprovalHandler = approved::add,
        approveChatApprovalForSessionHandler = approvedForSession::add,
        rejectChatApprovalHandler = rejected::add,
      ),
      mainThreadPoster = ImmediateMainThreadPoster,
    )

    gateway.approveChatApproval("run-approve")
    gateway.approveChatApprovalForSession("run-approve-session")
    gateway.rejectChatApproval("run-reject")

    assertTrue(delegate.approvedRunIds.isEmpty())
    assertTrue(delegate.approvedForSessionRunIds.isEmpty())
    assertTrue(delegate.rejectedRunIds.isEmpty())
    assertEquals(listOf("run-approve"), approved)
    assertEquals(listOf("run-approve-session"), approvedForSession)
    assertEquals(listOf("run-reject"), rejected)
    assertEquals(3, delegate.notifiedChatSnapshotCount)
  }

  @Test
  fun serviceOwnedChatRuntimeGatewayRoutesInterruptThroughServiceOwnedRunControlAccess() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("service-owned-chat-interrupt-store"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val run = AgentRunSnapshot(
      sessionId = sessionId,
      runId = "run-interrupt",
      taskId = "task-interrupt",
      acceptedAtEpochMs = 1_000L,
      updatedAtEpochMs = 1_100L,
      lifecycleState = QueueTaskLifecycleState.RUNNING,
      taskState = AgentTaskState.RUNNING,
    )
    val runtimeHostAccess = RecordingRuntimeHostAccess().apply {
      putSession(
        RecordingRuntimeSessionAccess(
          sessionId = sessionId,
          resumeHistory = mutableListOf(),
          runs = listOf(run),
          cancelResult = true,
        ),
      )
    }
    val pendingApprovalState = ChatPendingApprovalState()
    val runtimeEventState = ChatRuntimeEventState()
    val replayCalls = mutableListOf<Map<String, String?>>()
    val replayedSubAgentEvents = mutableListOf<OpenCraySubAgentEvent>()
    pendingApprovalState.put(
      sessionId = sessionId,
      taskId = run.taskId,
      approval = approvalSnapshot(
        runId = run.runId,
        taskId = run.taskId,
        pendingMessageId = "pending-interrupt",
        toolName = "Write",
      ),
    )
    runtimeHostAccess.promptCheckpointStore(sessionId).upsert(
      approvalCheckpoint(
        sessionId = sessionId,
        runId = run.runId,
        taskId = run.taskId,
        pendingMessageId = "pending-interrupt",
      ),
    )
    val delegate = RecordingChatGateway("delegate")
    val gateway = ServiceOwnedChatRuntimeGateway(
      delegate = delegate,
      readGateway = RecordingChatGateway("projection"),
      snapshotNotifier = delegate::notifyChatSnapshotsChanged,
      chatRunControlAccess = ServiceOwnedChatRunControlAccess(
        chatSessionStore = chatStore,
        runtimeHostAccess = runtimeHostAccess,
        pendingApprovalState = pendingApprovalState,
        runtimeEventState = runtimeEventState,
        runCancellationReplayRecorder = { replaySessionId, taskId, runId, toolName, _ ->
          replayCalls += mapOf(
            "sessionId" to replaySessionId,
            "taskId" to taskId,
            "runId" to runId,
            "toolName" to toolName,
          )
        },
        subAgentReplayRecorder = { _, event ->
          replayedSubAgentEvents += event
        },
      ),
      mainThreadPoster = ImmediateMainThreadPoster,
    )

    gateway.interruptChatRun(run.runId)

    val cancellationEvent = runtimeEventState.eventsForSession(sessionId).single() as OpenCrayCancellationEvent
    assertTrue(delegate.interruptedRunIds.isEmpty())
    assertEquals(1, delegate.notifiedChatSnapshotCount)
    assertEquals(listOf(run.taskId), runtimeHostAccess.cancelledTaskIds(sessionId))
    assertEquals(listOf(sessionId to run.taskId), runtimeHostAccess.clearedApprovals)
    assertTrue(pendingApprovalState.approvalsForSession(sessionId).isEmpty())
    assertNull(runtimeHostAccess.promptCheckpointStore(sessionId).get(run.taskId))
    assertTrue(replayedSubAgentEvents.isEmpty())
    assertEquals(
      listOf(
        mapOf(
          "sessionId" to sessionId,
          "taskId" to run.taskId,
          "runId" to run.runId,
          "toolName" to "Write",
        ),
      ),
      replayCalls,
    )
    assertEquals(run.runId, cancellationEvent.runId)
    assertEquals(run.taskId, cancellationEvent.taskId)
    assertEquals("Write", cancellationEvent.toolName)
    assertEquals("user_interrupted", cancellationEvent.outcome)
  }

  @Test
  fun serviceOwnedChatRuntimeGatewayInterruptApprovalWaitingRunKeepsApprovalAvailable() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("service-owned-chat-interrupt-approval-store"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val run = AgentRunSnapshot(
      sessionId = sessionId,
      runId = "run-approval-interrupt",
      taskId = "task-approval-interrupt",
      acceptedAtEpochMs = 1_000L,
      updatedAtEpochMs = 1_100L,
      lifecycleState = QueueTaskLifecycleState.SUSPENDED,
      taskState = AgentTaskState.SUSPENDED,
      executionStatus = ExecutionStatus.DENIED,
      errorCode = "APPROVAL_REQUIRED",
      errorMessage = "Approval is required before Read can run.",
      resultMetadata = mapOf(
        "normalizedToolName" to "Read",
        "childRunId" to "child-run-1",
        "childTaskId" to "child-task-1",
        "subagentType" to "researcher",
        "subagentContextMode" to "minimal",
        "subagentDepth" to "1",
        "delegationDescription" to "Inspect external notes",
      ),
    )
    val runtimeHostAccess = RecordingRuntimeHostAccess().apply {
      putSession(
        RecordingRuntimeSessionAccess(
          sessionId = sessionId,
          resumeHistory = mutableListOf(),
          runs = listOf(run),
          cancelResult = true,
        ),
      )
    }
    val pendingApprovalState = ChatPendingApprovalState()
    val runtimeEventState = ChatRuntimeEventState()
    val replayCalls = mutableListOf<Map<String, String?>>()
    val replayedSubAgentEvents = mutableListOf<OpenCraySubAgentEvent>()
    pendingApprovalState.put(
      sessionId = sessionId,
      taskId = run.taskId,
      approval = approvalSnapshot(
        runId = run.runId,
        taskId = run.taskId,
        pendingMessageId = "pending-approval",
        toolName = "Read",
        subAgentLifecycle = PendingApprovalSubAgentLifecycle(
          childRunId = "child-run-1",
          childTaskId = "child-task-1",
          label = "Inspect external notes",
          subagentType = "researcher",
          contextMode = "minimal",
          depth = 1,
        ),
      ),
    )
    runtimeHostAccess.promptCheckpointStore(sessionId).upsert(
      approvalCheckpoint(
        sessionId = sessionId,
        runId = run.runId,
        taskId = run.taskId,
        pendingMessageId = "pending-approval",
      ),
    )
    val delegate = RecordingChatGateway("delegate")
    val gateway = ServiceOwnedChatRuntimeGateway(
      delegate = delegate,
      readGateway = RecordingChatGateway("projection"),
      snapshotNotifier = delegate::notifyChatSnapshotsChanged,
      chatRunControlAccess = ServiceOwnedChatRunControlAccess(
        chatSessionStore = chatStore,
        runtimeHostAccess = runtimeHostAccess,
        pendingApprovalState = pendingApprovalState,
        runtimeEventState = runtimeEventState,
        runCancellationReplayRecorder = { replaySessionId, taskId, runId, toolName, _ ->
          replayCalls += mapOf(
            "sessionId" to replaySessionId,
            "taskId" to taskId,
            "runId" to runId,
            "toolName" to toolName,
          )
        },
        subAgentReplayRecorder = { _, event ->
          replayedSubAgentEvents += event
        },
      ),
      mainThreadPoster = ImmediateMainThreadPoster,
    )

    gateway.interruptChatRun(run.runId)

    val cancellationEvent = runtimeEventState.eventsForSession(sessionId).single() as OpenCrayCancellationEvent
    assertTrue(delegate.interruptedRunIds.isEmpty())
    assertEquals(1, delegate.notifiedChatSnapshotCount)
    assertTrue(runtimeHostAccess.cancelledTaskIds(sessionId).isEmpty())
    assertTrue(runtimeHostAccess.clearedApprovals.isEmpty())
    assertEquals(1, pendingApprovalState.approvalsForSession(sessionId).size)
    assertTrue(replayedSubAgentEvents.isEmpty())
    assertEquals(
      listOf(
        mapOf(
          "sessionId" to sessionId,
          "taskId" to run.taskId,
          "runId" to run.runId,
          "toolName" to "Read",
        ),
      ),
      replayCalls,
    )
    assertEquals("Read", cancellationEvent.toolName)
    assertEquals(
      "Interrupted the pending Read request. The agent is waiting for your next instruction.",
      cancellationEvent.text,
    )
    assertEquals(PromptCheckpointKind.WAITING_APPROVAL, runtimeHostAccess.promptCheckpointStore(sessionId).get(run.taskId)?.checkpointKind)
  }

  private data class RepairEvent(
    val sessionId: String,
    val runIds: List<String>,
    val resumeCallCountAtRepair: Int,
  )

  private data class ResumeRequest(
    val taskId: String,
    val executionKind: String?,
    val taskMetadataUpdates: Map<String, String>,
  )

  private data class PromptSubmissionRequest(
    val userText: String,
    val pendingMessageId: String,
    val visibleThroughMessageId: String,
    val policyDecision: PolicyDecision,
    val metadata: Map<String, String>,
  )

  private class RecordingChatGateway(
    private val label: String,
  ) : OpenCrayRuntimeServiceChatGateway {
    var createChatSessionCallCount: Int = 0
      private set
    val copiedSessionIds = mutableListOf<String>()
    val selectedSessionIds = mutableListOf<String>()
    val deletedSessionIds = mutableListOf<String>()
    val branchedSessionRequests = mutableListOf<Pair<String, String>>()
    val deletedMessages = mutableListOf<Pair<String, String>>()
    val recalledMessages = mutableListOf<Pair<String, String>>()
    val approvedRunIds = mutableListOf<String>()
    val approvedForSessionRunIds = mutableListOf<String>()
    val rejectedRunIds = mutableListOf<String>()
    val interruptedRunIds = mutableListOf<String>()
    val retriedRunIds = mutableListOf<String>()
    var submittedText: String? = null
      private set
    var notifiedChatSnapshotCount: Int = 0
      private set

    override fun loadChatSnapshot(): Map<String, Any?> = mapOf("source" to "$label-chat")

    override fun observeChat(listener: (Map<String, Any?>) -> Unit): () -> Unit {
      listener(loadChatSnapshot())
      return { }
    }

    override fun loadChatRuntimeSnapshot(): Map<String, Any?> = mapOf("source" to "$label-runtime")

    override fun loadChatRunSnapshot(runId: String): Map<String, Any?>? = mapOf(
      "source" to "$label-run",
      "runId" to runId,
    )

    override fun waitForChatRun(runId: String, timeoutMs: Long): Map<String, Any?>? = mapOf(
      "source" to "$label-wait",
      "runId" to runId,
      "timeoutMs" to timeoutMs,
    )

    override fun observeChatRuntime(listener: (Map<String, Any?>) -> Unit): () -> Unit {
      listener(loadChatRuntimeSnapshot())
      return { }
    }

    override fun refreshSandboxSessionInfo() = Unit

    override fun loadMemoryDebugSnapshot(): Map<String, Any?> = emptyMap()

    override fun loadMemoryDebugLinksSnapshot(): Map<String, Any?> = emptyMap()

    override fun loadSoulDebugSnapshot(): Map<String, Any?> = emptyMap()

    override fun searchMemoryDebug(query: String, maxResults: Int, minScore: Int): Map<String, Any?> =
      emptyMap()

    override fun getMemoryDebugSlice(path: String, fromLine: Int?, lines: Int): Map<String, Any?> =
      emptyMap()

    override fun applyMemoryDebugAction(recordId: String, actionId: String): Map<String, Any?> =
      emptyMap()

    override fun createChatSession() {
      createChatSessionCallCount += 1
    }

    override fun copyChatSession(sessionId: String) {
      copiedSessionIds += sessionId
    }

    override fun deleteChatSession(sessionId: String) {
      deletedSessionIds += sessionId
    }

    override fun selectChatSession(sessionId: String) {
      selectedSessionIds += sessionId
    }

    override fun branchChatSessionFromMessage(sessionId: String, messageId: String) {
      branchedSessionRequests += sessionId to messageId
    }

    override fun deleteChatMessage(sessionId: String, messageId: String) {
      deletedMessages += sessionId to messageId
    }

    override fun recallChatMessage(sessionId: String, messageId: String) {
      recalledMessages += sessionId to messageId
    }

    override fun submitChatMessage(
      text: String,
      attachments: List<OpenCrayFinalAttachment>,
    ): Map<String, Any?>? {
      submittedText = text
      return mapOf(
        "submittedText" to text,
        "attachmentCount" to attachments.size,
      )
    }

    override fun approveChatApproval(taskIdOrRunId: String) {
      approvedRunIds += taskIdOrRunId
    }

    override fun approveChatApprovalForSession(taskIdOrRunId: String) {
      approvedForSessionRunIds += taskIdOrRunId
    }

    override fun rejectChatApproval(taskIdOrRunId: String) {
      rejectedRunIds += taskIdOrRunId
    }

    override fun interruptChatRun(taskIdOrRunId: String) {
      interruptedRunIds += taskIdOrRunId
    }

    override fun retryChatRun(taskIdOrRunId: String) {
      retriedRunIds += taskIdOrRunId
    }

    override fun notifyChatSnapshotsChanged() {
      notifiedChatSnapshotCount += 1
    }
  }

  private class RecordingRuntimeHostAccess : OpenCrayRuntimeHostAccess {
    private val sessions = linkedMapOf<String, RecordingRuntimeSessionAccess>()
    private val runEventJournalStoreFactory = inMemoryRunEventJournalStoreFactory()
    private val promptCheckpointStoreFactory = inMemoryPromptCheckpointStoreFactory()
    private val supplementStores = linkedMapOf<String, SessionSupplementStore>()

    val resumeHistory = mutableListOf<String>()
    val clearedApprovals = mutableListOf<Pair<String, String>>()

    override val lifecycleDescriptor: HostRuntimeLifecycleDescriptor =
      HostRuntimeLifecycleDescriptor()

    override fun observe(listener: AgentSessionRuntimeListener): () -> Unit = { }

    override fun activeWorkSummary(): RuntimeOwnerWorkSummary = RuntimeOwnerWorkSummary(
      trackedSessionCount = sessions.size,
      activeRunCount = 0,
    )

    override fun session(sessionId: String): OpenCrayRuntimeSessionAccess =
      sessions.getOrPut(sessionId) {
        RecordingRuntimeSessionAccess(
          sessionId = sessionId,
          resumeHistory = resumeHistory,
        )
      }

    override fun releaseSession(sessionId: String) = Unit

    override fun releaseIdleSessions() = Unit

    override fun runEventJournalStore(sessionId: String): RunEventJournalStore =
      runEventJournalStoreFactory.forChatSession(sessionId)

    override fun promptCheckpointStore(sessionId: String): PromptCheckpointStore =
      promptCheckpointStoreFactory.forChatSession(sessionId)

    override fun supplementStore(sessionId: String): SessionSupplementStore =
      supplementStores.getOrPut(sessionId) { InMemorySessionSupplementStore() }

    override fun markApprovalApproved(
      sessionId: String,
      taskId: String,
      toolName: String?,
      promptResumeState: com.opencray.runtime.OpenCrayPromptResumeState?,
      subAgentApprovalResume: com.opencray.runtime.subagent.SubAgentApprovalResume?,
    ) = Unit

    override fun markApprovalRejected(
      sessionId: String,
      taskId: String,
      toolName: String?,
      promptResumeState: com.opencray.runtime.OpenCrayPromptResumeState?,
      subAgentApprovalResume: com.opencray.runtime.subagent.SubAgentApprovalResume?,
    ) = Unit

    override fun clearApproval(sessionId: String, taskId: String) {
      clearedApprovals += sessionId to taskId
    }

    override fun retainKnownApprovalTasks(sessionId: String, taskIds: Set<String>) = Unit

    override fun isApprovalApproved(sessionId: String, taskId: String): Boolean = false

    override fun isApprovalRejected(sessionId: String, taskId: String): Boolean = false

    fun putSession(session: RecordingRuntimeSessionAccess) {
      sessions[session.sessionId] = session
    }

    fun resumeCallCount(sessionId: String): Int =
      requireNotNull(sessions[sessionId]) { "Missing recording session '$sessionId'." }.resumeCallCount

    fun cancelledPendingMessageIds(sessionId: String): List<Set<String>> =
      requireNotNull(sessions[sessionId]) { "Missing recording session '$sessionId'." }
        .cancelledPendingMessageIds

    fun cancelledTaskIds(sessionId: String): List<String> =
      requireNotNull(sessions[sessionId]) { "Missing recording session '$sessionId'." }
        .cancelledTaskIds

    fun resumeRequests(sessionId: String): List<ResumeRequest> =
      requireNotNull(sessions[sessionId]) { "Missing recording session '$sessionId'." }
        .resumeRequests

    fun retriedTaskIds(sessionId: String): List<String> =
      requireNotNull(sessions[sessionId]) { "Missing recording session '$sessionId'." }
        .retriedTaskIds

    fun promptSubmissionRequests(sessionId: String): List<PromptSubmissionRequest> =
      requireNotNull(sessions[sessionId]) { "Missing recording session '$sessionId'." }
        .promptSubmissionRequests

    fun promptSubmissionRequestsOrEmpty(sessionId: String): List<PromptSubmissionRequest> =
      sessions[sessionId]?.promptSubmissionRequests.orEmpty()

    fun ensureProcessingCallCount(sessionId: String): Int =
      requireNotNull(sessions[sessionId]) { "Missing recording session '$sessionId'." }
        .ensureProcessingCallCount
  }

  private class RecordingRuntimeSessionAccess(
    override val sessionId: String,
    private val resumeHistory: MutableList<String>,
    private val runs: List<AgentRunSnapshot> = listOf(
      AgentRunSnapshot(
        sessionId = sessionId,
        runId = "run-$sessionId",
        taskId = "task-$sessionId",
        acceptedAtEpochMs = 1_000L,
        updatedAtEpochMs = 1_100L,
        lifecycleState = QueueTaskLifecycleState.QUEUED,
        taskState = AgentTaskState.QUEUED,
      ),
    ),
    private val cancelResult: Boolean = false,
    private val retryResult: Boolean = false,
    private val resumeTaskResult: Boolean = false,
  ) : OpenCrayRuntimeSessionAccess {
    private val submittedRuns = mutableListOf<AgentRunSnapshot>()
    val cancelledTaskIds = mutableListOf<String>()
    val cancelledPendingMessageIds = mutableListOf<Set<String>>()
    val promptSubmissionRequests = mutableListOf<PromptSubmissionRequest>()
    val retriedTaskIds = mutableListOf<String>()
    val resumeRequests = mutableListOf<ResumeRequest>()
    var ensureProcessingCallCount: Int = 0
      private set
    var resumeCallCount: Int = 0
      private set

    override fun submitPrompt(
      userText: String,
      pendingMessageId: String,
      visibleThroughMessageId: String,
      policyDecision: PolicyDecision,
      metadata: Map<String, String>,
    ): AgentRunSubmission {
      promptSubmissionRequests += PromptSubmissionRequest(
        userText = userText,
        pendingMessageId = pendingMessageId,
        visibleThroughMessageId = visibleThroughMessageId,
        policyDecision = policyDecision,
        metadata = metadata,
      )
      val submissionOrdinal = promptSubmissionRequests.size
      val submission = AgentRunSubmission(
        sessionId = sessionId,
        runId = "run-submitted-$sessionId-$submissionOrdinal",
        taskId = "task-submitted-$sessionId-$submissionOrdinal",
        acceptedAtEpochMs = 2_000L + submissionOrdinal,
      )
      submittedRuns += AgentRunSnapshot(
        sessionId = sessionId,
        runId = submission.runId,
        taskId = submission.taskId,
        acceptedAtEpochMs = submission.acceptedAtEpochMs,
        updatedAtEpochMs = submission.acceptedAtEpochMs,
        lifecycleState = QueueTaskLifecycleState.QUEUED,
        taskState = AgentTaskState.QUEUED,
        pendingMessageId = pendingMessageId,
      )
      return submission
    }

    override fun submitTask(task: AgentTask): AgentRunSubmission = error("unused in test")

    override fun ensureProcessing() {
      ensureProcessingCallCount += 1
    }

    override fun requestCancel(taskId: String): Boolean {
      cancelledTaskIds += taskId
      return cancelResult
    }

    override fun requestRetry(taskId: String): Boolean {
      retriedTaskIds += taskId
      return retryResult
    }

    override fun requestResumeTask(taskId: String): Boolean {
      resumeRequests += ResumeRequest(
        taskId = taskId,
        executionKind = null,
        taskMetadataUpdates = emptyMap(),
      )
      return resumeTaskResult
    }

    override fun requestResumeTask(
      taskId: String,
      executionKind: String,
      taskMetadataUpdates: Map<String, String>,
    ): Boolean {
      resumeRequests += ResumeRequest(
        taskId = taskId,
        executionKind = executionKind,
        taskMetadataUpdates = taskMetadataUpdates,
      )
      return resumeTaskResult
    }

    override fun listRuns(): List<AgentRunSnapshot> = runs + submittedRuns

    override fun findRun(runId: String): AgentRunSnapshot? =
      runs.firstOrNull { run -> run.runId == runId }

    override fun waitForRun(runId: String, timeoutMs: Long): AgentRunSnapshot? = findRun(runId)

    override fun requestCancelForPendingMessageIds(pendingMessageIds: Set<String>): Int {
      cancelledPendingMessageIds += pendingMessageIds.toSet()
      return pendingMessageIds.size
    }

    override fun resume(): SessionLifecycleState {
      resumeCallCount += 1
      resumeHistory += sessionId
      return SessionLifecycleState.IDLE
    }

    override fun snapshot(): SessionQueueSnapshot = SessionQueueSnapshot(
      sessionId = sessionId,
      agentId = "test-agent",
      lifecycleState = SessionLifecycleState.IDLE,
      updatedAtEpochMs = 1_000L,
      tasks = runs.mapIndexed { index, run ->
        SessionQueueTaskSnapshot(
          enqueueOrder = index.toLong(),
          task = AgentTask(
            id = run.taskId,
            type = com.opencray.core.contracts.AgentTaskType.PROMPT,
            input = "test",
            state = run.taskState ?: AgentTaskState.QUEUED,
            policyDecision = PolicyDecision(
              outcome = PolicyDecisionOutcome.ALLOW,
              reasonCode = "test",
            ),
            createdAtEpochMs = run.acceptedAtEpochMs,
            updatedAtEpochMs = run.updatedAtEpochMs,
            metadata = mapOf(
              AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID to run.runId,
            ),
          ),
          lifecycleState = run.lifecycleState ?: QueueTaskLifecycleState.QUEUED,
        )
      },
    )

    override fun hasPendingWork(): Boolean = true

    override fun listManagedProcesses(): List<com.opencray.runtime.process.ManagedProcessSnapshot> =
      emptyList()

    override fun hasLiveManagedProcesses(): Boolean = false

    override fun terminateRunningManagedProcesses():
      List<com.opencray.runtime.process.ManagedProcessSnapshot> = emptyList()

    override fun listSubAgentHandles(): List<SubAgentHandleState> = emptyList()
  }

  private fun approvalSnapshot(
    runId: String,
    taskId: String,
    pendingMessageId: String,
    toolName: String? = null,
    subAgentLifecycle: PendingApprovalSubAgentLifecycle? = null,
  ): PendingApprovalSnapshot = PendingApprovalSnapshot(
    runId = runId,
    taskId = taskId,
    pendingMessageId = pendingMessageId,
    executionId = null,
    executionOrdinal = null,
    executionKind = null,
    toolName = toolName,
    resumeToolName = null,
    promptCheckpointBoundary = null,
    promptResumeState = null,
    subAgentApprovalResume = null,
    requestSummary = null,
    primaryDetail = null,
    pathDetails = emptyList(),
    workingDirectory = null,
    reason = null,
    message = null,
    isHighRisk = false,
    supportsSessionApproval = false,
    approveForSessionLabel = null,
    subAgentLifecycle = subAgentLifecycle,
    title = "Approval required",
    body = "Need approval",
  )

  private fun approvalCheckpoint(
    sessionId: String,
    runId: String,
    taskId: String,
    pendingMessageId: String,
  ): PersistedPromptCheckpoint = PersistedPromptCheckpoint(
    sessionId = sessionId,
    runId = runId,
    taskId = taskId,
    checkpointId = "checkpoint-$taskId",
    checkpointKind = PromptCheckpointKind.WAITING_APPROVAL,
    createdAtEpochMs = 1_000L,
    updatedAtEpochMs = 1_100L,
    pendingMessageId = pendingMessageId,
  )
}
