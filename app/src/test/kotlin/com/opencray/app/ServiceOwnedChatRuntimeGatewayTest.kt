package com.opencray.app

import com.opencray.app.facade.safety.EmptySafetySettingsFacade
import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskState
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.orchestrator.EXECUTION_KIND_CHECKPOINT_RESUME
import com.opencray.core.orchestrator.EXECUTION_KIND_RETRY
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.QueueTaskLifecycleState
import com.opencray.core.orchestrator.SessionLifecycleState
import com.opencray.core.orchestrator.SessionQueueSnapshot
import com.opencray.core.orchestrator.SessionQueueTaskSnapshot
import com.opencray.persistence.model.ChatTranscriptMessageEntry
import com.opencray.persistence.model.ChatTranscriptRole
import com.opencray.runtime.ERROR_LLM_RETRY_EXHAUSTED_AWAITING_RESUME
import com.opencray.runtime.OpenCrayFinalAttachment
import com.opencray.runtime.OpenCrayCancellationEvent
import com.opencray.runtime.OpenCraySubAgentEvent
import org.junit.Assert.assertFalse
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
  fun serviceOwnedChatRuntimeGatewayExposesChatSnapshotWithoutEmbeddedRuntimeActivity() {
    val readGateway = RecordingChatGateway("projection").apply {
      chatPayload = mapOf(
        "source" to "projection-chat",
        "runtimeActivity" to mapOf(
          "sessionId" to "session-runtime",
          "updatedAtEpochMs" to 1_000L,
          "activeRuns" to listOf(
            mapOf(
              "runId" to "run-runtime",
              "taskId" to "task-runtime",
              "isTerminal" to false,
            ),
          ),
        ),
      )
      chatRuntimePayload = mapOf(
        "source" to "projection-runtime",
        "sessionId" to "session-runtime",
        "updatedAtEpochMs" to 1_000L,
        "activeRuns" to listOf(
          mapOf(
            "runId" to "run-runtime",
            "taskId" to "task-runtime",
            "isTerminal" to false,
          ),
        ),
      )
    }
    val gateway = ServiceOwnedChatRuntimeGateway(
      readGateway = readGateway,
      mainThreadPoster = ImmediateMainThreadPoster,
    )
    val observedPayloads = mutableListOf<Map<String, Any?>>()
    val disposer = gateway.observeChat { payload ->
      observedPayloads += payload
    }

    try {
      val loadedPayload = gateway.loadChatSnapshot()
      val runtimePayload = gateway.loadChatRuntimeSnapshot()

      assertEquals("projection-chat", loadedPayload["source"])
      assertNull(loadedPayload["runtimeActivity"])
      assertTrue(observedPayloads.isNotEmpty())
      assertNull(observedPayloads.first()["runtimeActivity"])
      assertEquals("projection-runtime", runtimePayload["source"])
      assertEquals("session-runtime", runtimePayload["sessionId"])
    } finally {
      disposer()
    }
  }

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
  fun deleteChatMessageCascadesFinalAgentBubbleButNotProcessBubble() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("service-owned-chat-delete-cascade-store"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val finalMessageId = "assistant-final-delete"
    val processMessageId = "runtime-process-task-delete-proc-1"
    val commentaryMessageId = "runtime-assistant-commentary-run-delete-1-Plan-100-42"
    chatStore.appendSubmittedTurn(
      sessionId = sessionId,
      userText = "Run the build",
      assistantMessageId = finalMessageId,
      assistantPlaceholderText = "Thinking",
    )
    chatStore.insertMessageBefore(
      sessionId = sessionId,
      anchorMessageId = finalMessageId,
      role = ChatTranscriptRole.ASSISTANT,
      text = "Process proc-1\n\nRunning: ./gradlew test",
      messageId = processMessageId,
    )
    chatStore.insertMessageBefore(
      sessionId = sessionId,
      anchorMessageId = finalMessageId,
      role = ChatTranscriptRole.ASSISTANT,
      text = "Plan\n\nChecking the failing test.",
      messageId = commentaryMessageId,
    )
    val runtimeHostAccess = RecordingRuntimeHostAccess()
    val delegate = RecordingChatGateway("delegate")
    val gateway = ServiceOwnedChatRuntimeGateway(
      delegate = delegate,
      readGateway = RecordingChatGateway("projection"),
      snapshotNotifier = delegate::notifyChatSnapshotsChanged,
      chatSessionMutationAccess = ServiceOwnedChatSessionMutationAccess(
        chatSessionStore = chatStore,
        runtimeHostAccess = runtimeHostAccess,
        chatUnreadMessageState = ChatUnreadMessageState(),
      ),
      mainThreadPoster = ImmediateMainThreadPoster,
    )

    gateway.deleteChatMessage(sessionId, finalMessageId)

    val remainingAfterFinalDelete = chatStore.loadSession(sessionId)
      ?.messages
      ?.map(ChatTranscriptMessageEntry::messageId)
      .orEmpty()
    assertTrue(finalMessageId !in remainingAfterFinalDelete)
    assertTrue(processMessageId !in remainingAfterFinalDelete)
    assertTrue(commentaryMessageId !in remainingAfterFinalDelete)

    val keptFinalMessageId = "assistant-final-keep"
    val deletedProcessMessageId = "runtime-process-task-keep-proc-2"
    chatStore.appendSubmittedTurn(
      sessionId = sessionId,
      userText = "Run a second command",
      assistantMessageId = keptFinalMessageId,
      assistantPlaceholderText = "Thinking",
    )
    chatStore.insertMessageBefore(
      sessionId = sessionId,
      anchorMessageId = keptFinalMessageId,
      role = ChatTranscriptRole.ASSISTANT,
      text = "Process proc-2\n\nRunning: npm test",
      messageId = deletedProcessMessageId,
    )

    gateway.deleteChatMessage(sessionId, deletedProcessMessageId)

    val remainingAfterProcessDelete = chatStore.loadSession(sessionId)
      ?.messages
      ?.map(ChatTranscriptMessageEntry::messageId)
      .orEmpty()
    assertTrue(deletedProcessMessageId !in remainingAfterProcessDelete)
    assertTrue(keptFinalMessageId in remainingAfterProcessDelete)
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
  fun serviceOwnedChatRuntimeGatewayDecoratesSnapshotWhileOnDeviceWarmupIsRunning() {
    val warmupAccess = RecordingOnDeviceWarmupAccess(
      state = OnDeviceLlmWarmupState(phase = OnDeviceLlmWarmupPhase.WARMING),
    )
    val readGateway = RecordingChatGateway("projection").apply {
      chatPayload = mapOf(
        "source" to "projection-chat",
        "composerPlaceholder" to "Message OpenCray",
        "isInputEnabled" to true,
      )
    }
    val gateway = ServiceOwnedChatRuntimeGateway(
      readGateway = readGateway,
      onDeviceWarmupAccess = warmupAccess,
      onDevicePreparingPlaceholder = "Preparing on-device model",
      mainThreadPoster = ImmediateMainThreadPoster,
    )

    val payload = gateway.loadChatSnapshot()

    assertEquals(false, payload["isInputEnabled"])
    assertEquals("Preparing on-device model", payload["composerPlaceholder"])
    assertTrue(warmupAccess.ensureActiveSessionCallCount >= 1)
  }

  @Test
  fun serviceOwnedChatRuntimeGatewayAdvancesChatSnapshotUpdatedAtWhenWarmupDecorationChanges() {
    val warmupAccess = object : OnDeviceLlmWarmupAccess {
      var state: OnDeviceLlmWarmupState = OnDeviceLlmWarmupState()

      override fun ensureWarmForSession(sessionId: String): OnDeviceLlmWarmupState = state

      override fun ensureWarmForActiveSession(): OnDeviceLlmWarmupState = state

      override fun clear(): OnDeviceLlmWarmupState {
        state = OnDeviceLlmWarmupState()
        return state
      }
    }
    val readGateway = RecordingChatGateway("projection").apply {
      chatPayload = mapOf(
        "source" to "projection-chat",
        "updatedAtEpochMs" to 1_000L,
        "composerPlaceholder" to "Message OpenCray",
        "isInputEnabled" to true,
      )
    }
    val gateway = ServiceOwnedChatRuntimeGateway(
      readGateway = readGateway,
      onDeviceWarmupAccess = warmupAccess,
      onDevicePreparingPlaceholder = "Preparing on-device model",
      mainThreadPoster = ImmediateMainThreadPoster,
    )

    val initialPayload = gateway.loadChatSnapshot()
    warmupAccess.state = OnDeviceLlmWarmupState(phase = OnDeviceLlmWarmupPhase.WARMING)
    val warmingPayload = gateway.loadChatSnapshot()

    assertEquals(true, initialPayload["isInputEnabled"])
    assertEquals(false, warmingPayload["isInputEnabled"])
    assertEquals("Preparing on-device model", warmingPayload["composerPlaceholder"])
    assertTrue((warmingPayload["updatedAtEpochMs"] as Number).toLong() > 1_000L)
  }

  @Test
  fun serviceOwnedChatRuntimeGatewaySkipsSubmitWhileOnDeviceWarmupIsRunning() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("service-owned-chat-warmup-block-store"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val runtimeHostAccess = RecordingRuntimeHostAccess()
    val delegate = RecordingChatGateway("delegate")
    val warmupAccess = RecordingOnDeviceWarmupAccess(
      state = OnDeviceLlmWarmupState(phase = OnDeviceLlmWarmupPhase.WARMING),
    )
    val gateway = ServiceOwnedChatRuntimeGateway(
      delegate = delegate,
      readGateway = RecordingChatGateway("projection"),
      snapshotNotifier = delegate::notifyChatSnapshotsChanged,
      onDeviceWarmupAccess = warmupAccess,
      chatSubmissionAccess = ServiceOwnedChatSubmissionAccess(
        chatSessionStore = chatStore,
        runtimeHostAccess = runtimeHostAccess,
        safetySettingsFacade = EmptySafetySettingsFacade,
        workspaceRootProvider = null,
      ),
      mainThreadPoster = ImmediateMainThreadPoster,
    )

    val payload = gateway.submitChatMessage("hello", emptyList())

    assertNull(payload)
    assertEquals(1, delegate.notifiedChatSnapshotCount)
    assertTrue(runtimeHostAccess.promptSubmissionRequestsOrEmpty(sessionId).isEmpty())
  }

  @Test
  fun serviceOwnedChatRuntimeGatewayUsesDelegateRuntimeSnapshotsSoLiveDraftsArePreserved() {
    val delegate = RecordingChatGateway("delegate").apply {
      chatRuntimePayload = mapOf(
        "source" to "delegate-runtime",
        "liveAssistantDrafts" to listOf(
          mapOf(
            "runId" to "run-stream",
            "taskId" to "task-stream",
            "pendingMessageId" to "pending-stream",
            "text" to "hello",
            "updatedAtEpochMs" to 1_234L,
          ),
        ),
      )
    }
    val readGateway = RecordingChatGateway("projection").apply {
      chatRuntimePayload = mapOf(
        "source" to "projection-runtime",
        "liveAssistantDrafts" to emptyList<Map<String, Any?>>(),
      )
    }
    val gateway = ServiceOwnedChatRuntimeGateway(
      delegate = delegate,
      readGateway = readGateway,
      snapshotNotifier = delegate::notifyChatSnapshotsChanged,
      mainThreadPoster = ImmediateMainThreadPoster,
    )
    val observedDraftCounts = mutableListOf<Int>()

    val disposer = gateway.observeChatRuntime { payload ->
      val drafts = payload["liveAssistantDrafts"] as? List<*>
      observedDraftCounts += drafts?.size ?: 0
    }

    try {
      val runtimePayload = gateway.loadChatRuntimeSnapshot()
      assertEquals("delegate-runtime", runtimePayload["source"])
      assertEquals(1, (runtimePayload["liveAssistantDrafts"] as List<*>).size)
      assertEquals(listOf(1), observedDraftCounts)
      assertFalse(readGateway.observedChatRuntime)
    } finally {
      disposer()
    }
  }

  @Test
  fun serviceOwnedChatRuntimeGatewayAugmentsProjectionRuntimeSnapshotsWithLiveDraftsWhenDelegateMissing() {
    val runtimeHostAccess = RecordingRuntimeHostAccess()
    val readGateway = RecordingChatGateway("projection").apply {
      chatRuntimePayload = mapOf(
        "source" to "projection-runtime",
        "sessionId" to "session-stream",
        "updatedAtEpochMs" to 1_000L,
        "liveAssistantDrafts" to emptyList<Map<String, Any?>>(),
      )
    }
    val gateway = ServiceOwnedChatRuntimeGateway(
      readGateway = readGateway,
      runtimeHostAccess = runtimeHostAccess,
      mainThreadPoster = ImmediateMainThreadPoster,
    )
    val observedDraftCounts = mutableListOf<Int>()
    val task = AgentTask(
      id = "task-stream",
      type = com.opencray.core.contracts.AgentTaskType.PROMPT,
      input = "hello",
      state = AgentTaskState.RUNNING,
      policyDecision = PolicyDecision(
        outcome = PolicyDecisionOutcome.ALLOW,
        reasonCode = "test",
      ),
      createdAtEpochMs = 1_000L,
      updatedAtEpochMs = 1_000L,
      metadata = mapOf(
        AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID to "run-stream",
        AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID to "pending-stream",
      ),
    )

    val disposer = gateway.observeChatRuntime { payload ->
      val drafts = payload["liveAssistantDrafts"] as? List<*>
      observedDraftCounts += drafts?.size ?: 0
    }

    try {
      runtimeHostAccess.emitAssistantDraftUpdated(
        sessionId = "session-stream",
        task = task,
        text = "hello",
        emittedAtEpochMs = 1_234L,
      )
      val runtimePayload = gateway.loadChatRuntimeSnapshot()
      val runtimeUpdatedAtEpochMs = (runtimePayload["updatedAtEpochMs"] as Number).toLong()
      assertEquals("projection-runtime", runtimePayload["source"])
      assertEquals(1, (runtimePayload["liveAssistantDrafts"] as List<*>).size)
      assertTrue(runtimeUpdatedAtEpochMs > 1_000L)

      runtimeHostAccess.emitAssistantDraftCleared(
        sessionId = "session-stream",
        task = task,
        emittedAtEpochMs = 1_235L,
      )
      val clearedPayload = gateway.loadChatRuntimeSnapshot()
      val clearedUpdatedAtEpochMs = (clearedPayload["updatedAtEpochMs"] as Number).toLong()
      assertEquals(0, (clearedPayload["liveAssistantDrafts"] as List<*>).size)
      assertTrue(clearedUpdatedAtEpochMs > runtimeUpdatedAtEpochMs)
      assertEquals(listOf(0, 1, 0), observedDraftCounts)
    } finally {
      disposer()
    }
  }

  @Test
  fun serviceOwnedChatRuntimeGatewayEmitsRuntimeSnapshotsForTaskStartImmediately() {
    val runtimeHostAccess = RecordingRuntimeHostAccess()
    val readGateway = RecordingChatGateway("projection").apply {
      chatPayload = mapOf("source" to "projection-chat-idle")
      chatRuntimePayload = mapOf(
        "source" to "projection-runtime-idle",
        "sessionId" to "session-start-events",
        "updatedAtEpochMs" to 1_000L,
        "activeRuns" to emptyList<Map<String, Any?>>(),
      )
    }
    val gateway = ServiceOwnedChatRuntimeGateway(
      readGateway = readGateway,
      runtimeHostAccess = runtimeHostAccess,
      mainThreadPoster = ImmediateMainThreadPoster,
    )
    val observedRuntimeSources = mutableListOf<String>()
    val observedChatSources = mutableListOf<String>()
    val task = AgentTask(
      id = "task-start-events",
      type = com.opencray.core.contracts.AgentTaskType.PROMPT,
      input = "hello",
      state = AgentTaskState.RUNNING,
      policyDecision = PolicyDecision(
        outcome = PolicyDecisionOutcome.ALLOW,
        reasonCode = "test",
      ),
      createdAtEpochMs = 1_000L,
      updatedAtEpochMs = 1_000L,
      metadata = mapOf(
        AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID to "run-start-events",
        AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID to "pending-start-events",
      ),
    )

    val chatDisposer = gateway.observeChat { payload ->
      observedChatSources += payload["source"] as String
    }
    val runtimeDisposer = gateway.observeChatRuntime { payload ->
      observedRuntimeSources += payload["source"] as String
    }

    try {
      readGateway.chatPayload = mapOf("source" to "projection-chat-running")
      readGateway.chatRuntimePayload = mapOf(
        "source" to "projection-runtime-running",
        "sessionId" to "session-start-events",
        "updatedAtEpochMs" to 2_000L,
        "activeRuns" to listOf(
          mapOf(
            "runId" to "run-start-events",
            "taskId" to "task-start-events",
            "pendingMessageId" to "pending-start-events",
            "isTerminal" to false,
          ),
        ),
      )
      runtimeHostAccess.emitTaskStarted(
        sessionId = "session-start-events",
        task = task,
      )

      assertEquals(
        listOf("projection-runtime-idle", "projection-runtime-running"),
        observedRuntimeSources,
      )
      assertEquals(
        listOf("projection-chat-idle", "projection-chat-running"),
        observedChatSources,
      )
    } finally {
      runtimeDisposer()
      chatDisposer()
    }
  }

  @Test
  fun serviceOwnedChatRuntimeGatewayEmitsRuntimeSnapshotsForRunEventsImmediately() {
    val runtimeHostAccess = RecordingRuntimeHostAccess()
    val readGateway = RecordingChatGateway("projection").apply {
      chatPayload = mapOf("source" to "projection-chat-initial")
      chatRuntimePayload = mapOf(
        "source" to "projection-runtime-initial",
        "sessionId" to "session-runtime-events",
        "updatedAtEpochMs" to 1_000L,
        "events" to emptyList<Map<String, Any?>>(),
      )
    }
    val gateway = ServiceOwnedChatRuntimeGateway(
      readGateway = readGateway,
      runtimeHostAccess = runtimeHostAccess,
      mainThreadPoster = ImmediateMainThreadPoster,
    )
    val observedRuntimeSources = mutableListOf<String>()
    val observedChatSources = mutableListOf<String>()
    val task = AgentTask(
      id = "task-runtime-events",
      type = com.opencray.core.contracts.AgentTaskType.PROMPT,
      input = "hello",
      state = AgentTaskState.RUNNING,
      policyDecision = PolicyDecision(
        outcome = PolicyDecisionOutcome.ALLOW,
        reasonCode = "test",
      ),
      createdAtEpochMs = 1_000L,
      updatedAtEpochMs = 1_000L,
      metadata = mapOf(
        AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID to "run-runtime-events",
        AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID to "pending-runtime-events",
      ),
    )

    val chatDisposer = gateway.observeChat { payload ->
      observedChatSources += payload["source"] as String
    }
    val runtimeDisposer = gateway.observeChatRuntime { payload ->
      observedRuntimeSources += payload["source"] as String
    }

    try {
      readGateway.chatPayload = mapOf("source" to "projection-chat-event")
      readGateway.chatRuntimePayload = mapOf(
        "source" to "projection-runtime-event",
        "sessionId" to "session-runtime-events",
        "updatedAtEpochMs" to 2_000L,
        "events" to listOf(
          mapOf(
            "kind" to "interrupted",
            "runId" to "run-runtime-events",
            "taskId" to "task-runtime-events",
            "emittedAtEpochMs" to 2_000L,
          ),
        ),
      )
      runtimeHostAccess.emitRunEvent(
        sessionId = "session-runtime-events",
        task = task,
        event = OpenCrayCancellationEvent(
          runId = "run-runtime-events",
          taskId = "task-runtime-events",
          outcome = "user_interrupted",
          text = "Interrupted.",
          emittedAtEpochMs = 2_000L,
        ),
      )

      assertEquals(
        listOf("projection-runtime-initial", "projection-runtime-event"),
        observedRuntimeSources,
      )
      assertEquals(
        listOf("projection-chat-initial", "projection-chat-event"),
        observedChatSources,
      )
    } finally {
      runtimeDisposer()
      chatDisposer()
    }
  }

  @Test
  fun serviceOwnedChatRuntimeGatewayKeepsPollingLiveProcessPayloadWhenOwnerSummaryIsIdle() {
    val runtimeHostAccess = RecordingRuntimeHostAccess()
    val readGateway = RecordingChatGateway("projection").apply {
      chatRuntimePayload = runningProcessRuntimePayload(
        source = "projection-runtime-process-first",
        stdout = "step 1",
        updatedAtEpochMs = 1_000L,
      )
    }
    val gateway = ServiceOwnedChatRuntimeGateway(
      readGateway = readGateway,
      runtimeHostAccess = runtimeHostAccess,
      mainThreadPoster = ImmediateMainThreadPoster,
    )
    val observedLock = Any()
    val observedStdouts = mutableListOf<String>()
    val disposer = gateway.observeChatRuntime { payload ->
      firstManagedProcessStdout(payload)?.let { stdout ->
        synchronized(observedLock) {
          observedStdouts += stdout
        }
      }
    }

    try {
      readGateway.chatRuntimePayload = runningProcessRuntimePayload(
        source = "projection-runtime-process-second",
        stdout = "step 1\nstep 2",
        updatedAtEpochMs = 1_400L,
      )

      waitUntil(timeoutMs = 2_000L) {
        synchronized(observedLock) {
          observedStdouts.contains("step 1\nstep 2")
        }
      }
      assertTrue(
        synchronized(observedLock) {
          observedStdouts.contains("step 1")
        },
      )
    } finally {
      disposer()
    }
  }

  @Test
  fun serviceOwnedChatRuntimeGatewayEmitsTerminalRuntimeSnapshotEvenWithoutDraftToClear() {
    val runtimeHostAccess = RecordingRuntimeHostAccess()
    val readGateway = RecordingChatGateway("projection").apply {
      chatPayload = mapOf("source" to "projection-chat-running")
      chatRuntimePayload = mapOf(
        "source" to "projection-runtime-running",
        "sessionId" to "session-terminal-runtime",
        "updatedAtEpochMs" to 1_000L,
        "activeRuns" to listOf(
          mapOf(
            "runId" to "run-terminal-runtime",
            "taskId" to "task-terminal-runtime",
            "isTerminal" to false,
          ),
        ),
      )
    }
    val gateway = ServiceOwnedChatRuntimeGateway(
      readGateway = readGateway,
      runtimeHostAccess = runtimeHostAccess,
      mainThreadPoster = ImmediateMainThreadPoster,
    )
    val observedRuntimeSources = mutableListOf<String>()
    val task = AgentTask(
      id = "task-terminal-runtime",
      type = com.opencray.core.contracts.AgentTaskType.PROMPT,
      input = "hello",
      state = AgentTaskState.RUNNING,
      policyDecision = PolicyDecision(
        outcome = PolicyDecisionOutcome.ALLOW,
        reasonCode = "test",
      ),
      createdAtEpochMs = 1_000L,
      updatedAtEpochMs = 1_000L,
      metadata = mapOf(
        AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID to "run-terminal-runtime",
      ),
    )

    val disposer = gateway.observeChatRuntime { payload ->
      observedRuntimeSources += payload["source"] as String
    }

    try {
      readGateway.chatPayload = mapOf("source" to "projection-chat-terminal")
      readGateway.chatRuntimePayload = mapOf(
        "source" to "projection-runtime-terminal",
        "sessionId" to "session-terminal-runtime",
        "updatedAtEpochMs" to 2_000L,
        "activeRuns" to emptyList<Map<String, Any?>>(),
        "retainedRuns" to listOf(
          mapOf(
            "runId" to "run-terminal-runtime",
            "taskId" to "task-terminal-runtime",
            "isTerminal" to true,
          ),
        ),
      )
      runtimeHostAccess.emitTaskFinished(
        sessionId = "session-terminal-runtime",
        task = task,
        result = ExecutionResult(
          taskId = "task-terminal-runtime",
          status = ExecutionStatus.SUCCESS,
          stdout = "done",
          startedAtEpochMs = 1_000L,
          finishedAtEpochMs = 2_000L,
        ),
      )

      assertEquals(
        listOf("projection-runtime-running", "projection-runtime-terminal"),
        observedRuntimeSources,
      )
    } finally {
      disposer()
    }
  }

  @Test
  fun serviceOwnedChatRuntimeGatewayEmitsLiveAssistantDraftEvents() {
    val runtimeHostAccess = RecordingRuntimeHostAccess()
    val readGateway = RecordingChatGateway("projection").apply {
      chatRuntimePayload = mapOf(
        "source" to "projection-runtime",
        "sessionId" to "session-stream",
        "liveAssistantDrafts" to emptyList<Map<String, Any?>>(),
      )
    }
    val gateway = ServiceOwnedChatRuntimeGateway(
      readGateway = readGateway,
      runtimeHostAccess = runtimeHostAccess,
      mainThreadPoster = ImmediateMainThreadPoster,
    )
    val observedEvents = mutableListOf<Map<String, Any?>>()
    val task = AgentTask(
      id = "task-stream",
      type = com.opencray.core.contracts.AgentTaskType.PROMPT,
      input = "hello",
      state = AgentTaskState.RUNNING,
      policyDecision = PolicyDecision(
        outcome = PolicyDecisionOutcome.ALLOW,
        reasonCode = "test",
      ),
      createdAtEpochMs = 1_000L,
      updatedAtEpochMs = 1_000L,
      metadata = mapOf(
        AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID to "run-stream",
        AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID to "pending-stream",
      ),
    )
    val disposer = gateway.observeLiveAssistantDraftEvents { payload ->
      observedEvents += payload
    }

    try {
      runtimeHostAccess.emitAssistantDraftUpdated(
        sessionId = "session-stream",
        task = task,
        text = "hello",
        emittedAtEpochMs = 1_234L,
      )
      runtimeHostAccess.emitAssistantDraftCleared(
        sessionId = "session-stream",
        task = task,
        emittedAtEpochMs = 1_235L,
      )

      assertEquals(2, observedEvents.size)
      assertEquals("session-stream", observedEvents[0]["sessionId"])
      assertEquals("hello", observedEvents[0]["text"])
      assertEquals(false, observedEvents[0]["cleared"])
      assertEquals(true, observedEvents[1]["cleared"])
    } finally {
      disposer()
    }
  }

  @Test
  fun serviceOwnedChatRuntimeGatewayEmitsRuntimeDeltasForLiveAssistantDraftChanges() {
    val runtimeHostAccess = RecordingRuntimeHostAccess()
    val readGateway = RecordingChatGateway("projection").apply {
      chatRuntimePayload = mapOf(
        "source" to "projection-runtime",
        "sessionId" to "session-stream",
        "updatedAtEpochMs" to 1_000L,
        "activeRuns" to listOf(
          mapOf(
            "runId" to "run-stream",
            "taskId" to "task-stream",
            "pendingMessageId" to "pending-stream",
            "updatedAtEpochMs" to 1_000L,
            "isTerminal" to false,
          ),
        ),
        "events" to emptyList<Map<String, Any?>>(),
        "liveAssistantDrafts" to emptyList<Map<String, Any?>>(),
      )
    }
    val gateway = ServiceOwnedChatRuntimeGateway(
      readGateway = readGateway,
      runtimeHostAccess = runtimeHostAccess,
      mainThreadPoster = ImmediateMainThreadPoster,
    )
    val observedDeltas = mutableListOf<Map<String, Any?>>()
    val task = AgentTask(
      id = "task-stream",
      type = com.opencray.core.contracts.AgentTaskType.PROMPT,
      input = "hello",
      state = AgentTaskState.RUNNING,
      policyDecision = PolicyDecision(
        outcome = PolicyDecisionOutcome.ALLOW,
        reasonCode = "test",
      ),
      createdAtEpochMs = 1_000L,
      updatedAtEpochMs = 1_000L,
      metadata = mapOf(
        AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID to "run-stream",
        AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID to "pending-stream",
      ),
    )
    val disposer = gateway.observeRuntimeEventDeltas { payload ->
      observedDeltas += payload
    }

    try {
      runtimeHostAccess.emitAssistantDraftUpdated(
        sessionId = "session-stream",
        task = task,
        text = "hello",
        emittedAtEpochMs = 1_234L,
      )
      runtimeHostAccess.emitAssistantDraftCleared(
        sessionId = "session-stream",
        task = task,
        emittedAtEpochMs = 1_235L,
      )

      assertEquals(2, observedDeltas.size)
      assertEquals(1L, observedDeltas[0]["sequence"])
      assertEquals(2L, observedDeltas[1]["sequence"])
      val updatedDrafts = observedDeltas[0]["liveAssistantDrafts"] as List<*>
      val updatedDraft = updatedDrafts.single() as Map<*, *>
      assertEquals("pending-stream", updatedDraft["pendingMessageId"])
      assertEquals("hello", updatedDraft["text"])
      assertTrue((observedDeltas[1]["liveAssistantDrafts"] as List<*>).isEmpty())
      assertTrue((observedDeltas[0]["events"] as List<*>).isEmpty())
      assertTrue((observedDeltas[1]["events"] as List<*>).isEmpty())
    } finally {
      disposer()
    }
  }

  @Test
  fun serviceOwnedChatRuntimeGatewayEmitsRuntimeDeltaWhenEventContentChanges() {
    val readGateway = RecordingChatGateway("projection").apply {
      chatRuntimePayload = runtimeEventContentPayload(
        source = "projection-runtime-first",
        contentPreview = "Searching...",
        status = "running",
      )
    }
    val gateway = ServiceOwnedChatRuntimeGateway(
      readGateway = readGateway,
      mainThreadPoster = ImmediateMainThreadPoster,
    )
    val observedDeltas = mutableListOf<Map<String, Any?>>()
    val disposer = gateway.observeRuntimeEventDeltas { payload ->
      observedDeltas += payload
    }

    try {
      readGateway.chatRuntimePayload = runtimeEventContentPayload(
        source = "projection-runtime-second",
        contentPreview = "Found 3 results.",
        status = "completed",
      )

      gateway.notifyChatSnapshotsChanged()

      assertEquals(1, observedDeltas.size)
      assertEquals("replace", observedDeltas.single()["runPatchMode"])
      val events = observedDeltas.single()["events"] as List<*>
      val event = events.single() as Map<*, *>
      assertEquals("Found 3 results.", event["contentPreview"])
      assertEquals(
        mapOf("status" to "completed"),
        event["resultMetadata"],
      )
    } finally {
      disposer()
    }
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
  fun serviceOwnedChatRuntimeGatewayTreatsAlreadyStartedRetryAsSuccess() {
    val chatStore = ChatSessionLocalStore(
      temporaryFolder.newFolder("service-owned-chat-retry-idempotent-store"),
    )
    val queuedRetrySessionId = chatStore.loadState().activeSession.sessionId
    val runningRetrySessionId = chatStore.copySession(
      queuedRetrySessionId,
    ).activeSession.sessionId
    val queuedRetryRun = AgentRunSnapshot(
      sessionId = queuedRetrySessionId,
      runId = "run-queued-retry",
      taskId = "task-queued-retry",
      acceptedAtEpochMs = 1_000L,
      updatedAtEpochMs = 1_100L,
      lifecycleState = QueueTaskLifecycleState.QUEUED,
      taskState = AgentTaskState.QUEUED,
      pendingExecutionKind = EXECUTION_KIND_RETRY,
    )
    val runningRetryRun = AgentRunSnapshot(
      sessionId = runningRetrySessionId,
      runId = "run-running-retry",
      taskId = "task-running-retry",
      acceptedAtEpochMs = 2_000L,
      updatedAtEpochMs = 2_100L,
      lifecycleState = QueueTaskLifecycleState.RUNNING,
      taskState = AgentTaskState.RUNNING,
      executionKind = EXECUTION_KIND_RETRY,
    )
    val runtimeHostAccess = RecordingRuntimeHostAccess().apply {
      putSession(
        RecordingRuntimeSessionAccess(
          sessionId = queuedRetrySessionId,
          resumeHistory = resumeHistory,
          runs = listOf(queuedRetryRun),
          retryResult = false,
        ),
      )
      putSession(
        RecordingRuntimeSessionAccess(
          sessionId = runningRetrySessionId,
          resumeHistory = resumeHistory,
          runs = listOf(runningRetryRun),
          retryResult = false,
        ),
      )
    }
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

    gateway.retryChatRun(queuedRetryRun.runId)
    gateway.retryChatRun(runningRetryRun.runId)

    assertTrue(delegate.retriedRunIds.isEmpty())
    assertEquals(2, delegate.notifiedChatSnapshotCount)
    assertTrue(runtimeHostAccess.retriedTaskIds(queuedRetrySessionId).isEmpty())
    assertTrue(runtimeHostAccess.retriedTaskIds(runningRetrySessionId).isEmpty())
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

  private fun runningProcessRuntimePayload(
    source: String,
    stdout: String,
    updatedAtEpochMs: Long,
  ): Map<String, Any?> = mapOf(
    "source" to source,
    "sessionId" to "session-live-process-refresh",
    "updatedAtEpochMs" to updatedAtEpochMs,
    "activeRuns" to listOf(
      mapOf(
        "runId" to "run-live-process-refresh",
        "taskId" to "task-live-process-refresh",
        "pendingMessageId" to "pending-live-process-refresh",
        "updatedAtEpochMs" to updatedAtEpochMs,
        "isTerminal" to false,
        "runningManagedProcessCount" to 1,
        "hasLiveManagedProcesses" to true,
        "managedProcesses" to listOf(
          mapOf(
            "processId" to "process-live-refresh",
            "status" to "running",
            "stdout" to stdout,
            "updatedAtEpochMs" to updatedAtEpochMs,
          ),
        ),
      ),
    ),
    "retainedRuns" to emptyList<Map<String, Any?>>(),
    "events" to emptyList<Map<String, Any?>>(),
    "liveAssistantDrafts" to emptyList<Map<String, Any?>>(),
  )

  private fun runtimeEventContentPayload(
    source: String,
    contentPreview: String,
    status: String,
  ): Map<String, Any?> = mapOf(
    "source" to source,
    "sessionId" to "session-runtime-event-content",
    "updatedAtEpochMs" to 1_000L,
    "events" to listOf(
      mapOf(
        "kind" to "tool_result",
        "runId" to "run-runtime-event-content",
        "taskId" to "task-runtime-event-content",
        "toolName" to "WebSearch",
        "emittedAtEpochMs" to 1_000L,
        "contentPreview" to contentPreview,
        "resultMetadata" to mapOf("status" to status),
      ),
    ),
  )

  private fun firstManagedProcessStdout(payload: Map<String, Any?>): String? {
    val run = (payload["activeRuns"] as? List<*>)
      ?.firstOrNull() as? Map<*, *>
      ?: return null
    val process = (run["managedProcesses"] as? List<*>)
      ?.firstOrNull() as? Map<*, *>
      ?: return null
    return process["stdout"] as? String
  }

  private fun waitUntil(timeoutMs: Long, predicate: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      if (predicate()) {
        return
      }
      Thread.sleep(10L)
    }
    assertTrue("Condition not satisfied within ${timeoutMs}ms.", predicate())
  }

  private class RecordingChatGateway(
    private val label: String,
  ) : OpenCrayRuntimeServiceChatGateway {
    var chatPayload: Map<String, Any?> = mapOf("source" to "$label-chat")
    var chatRuntimePayload: Map<String, Any?> = mapOf("source" to "$label-runtime")
    var observedChatRuntime: Boolean = false
      private set
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

    override fun loadChatSnapshot(): Map<String, Any?> = chatPayload

    override fun observeChat(listener: (Map<String, Any?>) -> Unit): () -> Unit {
      listener(loadChatSnapshot())
      return { }
    }

    override fun observeLiveAssistantDraftEvents(listener: (Map<String, Any?>) -> Unit): () -> Unit =
      { }

    override fun loadChatRuntimeSnapshot(): Map<String, Any?> = chatRuntimePayload

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
      observedChatRuntime = true
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

  private class RecordingOnDeviceWarmupAccess(
    private val state: OnDeviceLlmWarmupState,
  ) : OnDeviceLlmWarmupAccess {
    var ensureActiveSessionCallCount: Int = 0
      private set

    val ensuredSessionIds = mutableListOf<String>()

    override fun ensureWarmForSession(sessionId: String): OnDeviceLlmWarmupState {
      ensuredSessionIds += sessionId
      return state
    }

    override fun ensureWarmForActiveSession(): OnDeviceLlmWarmupState {
      ensureActiveSessionCallCount += 1
      return state
    }

    override fun clear(): OnDeviceLlmWarmupState = OnDeviceLlmWarmupState()
  }

  private class RecordingRuntimeHostAccess : OpenCrayRuntimeHostAccess {
    private val sessions = linkedMapOf<String, RecordingRuntimeSessionAccess>()
    private val runEventJournalStoreFactory = inMemoryRunEventJournalStoreFactory()
    private val promptCheckpointStoreFactory = inMemoryPromptCheckpointStoreFactory()
    private val supplementStores = linkedMapOf<String, SessionSupplementStore>()
    private val listeners = linkedSetOf<AgentSessionRuntimeListener>()

    val resumeHistory = mutableListOf<String>()
    val clearedApprovals = mutableListOf<Pair<String, String>>()

    override val lifecycleDescriptor: HostRuntimeLifecycleDescriptor =
      HostRuntimeLifecycleDescriptor()

    override fun observe(listener: AgentSessionRuntimeListener): () -> Unit {
      listeners += listener
      return {
        listeners -= listener
      }
    }

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

    fun emitAssistantDraftUpdated(
      sessionId: String,
      task: AgentTask,
      text: String,
      emittedAtEpochMs: Long,
    ) {
      listeners.toList().forEach { listener ->
        listener.onAssistantDraftUpdated(
          sessionId = sessionId,
          task = task,
          text = text,
          emittedAtEpochMs = emittedAtEpochMs,
        )
      }
    }

    fun emitAssistantDraftCleared(
      sessionId: String,
      task: AgentTask,
      emittedAtEpochMs: Long,
    ) {
      listeners.toList().forEach { listener ->
        listener.onAssistantDraftCleared(
          sessionId = sessionId,
          task = task,
          emittedAtEpochMs = emittedAtEpochMs,
        )
      }
    }

    fun emitTaskStarted(
      sessionId: String,
      task: AgentTask,
    ) {
      listeners.toList().forEach { listener ->
        listener.onTaskStarted(
          sessionId = sessionId,
          task = task,
        )
      }
    }

    fun emitRunEvent(
      sessionId: String,
      task: AgentTask,
      event: com.opencray.runtime.OpenCrayAgentRunEvent,
    ) {
      listeners.toList().forEach { listener ->
        listener.onRunEvent(
          sessionId = sessionId,
          task = task,
          event = event,
        )
      }
    }

    fun emitTaskFinished(
      sessionId: String,
      task: AgentTask,
      result: ExecutionResult,
    ) {
      listeners.toList().forEach { listener ->
        listener.onTaskFinished(
          sessionId = sessionId,
          task = task,
          result = result,
        )
      }
    }
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
