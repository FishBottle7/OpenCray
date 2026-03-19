package com.opencray.app

import com.opencray.app.facade.llm.LlmConfigFacade
import com.opencray.app.facade.llm.LlmConfigSnapshot
import com.opencray.app.facade.llm.LlmValidationResult
import com.opencray.app.facade.llm.EmptyLlmConfigFacade
import com.opencray.app.facade.llm.SaveCustomLlmProviderRequest
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
import com.opencray.app.facade.search.EmptyNetworkSearchConfigFacade
import com.opencray.app.facade.search.LocalNetworkSearchConfigFacade
import com.opencray.app.facade.search.NetworkSearchConfigFacade
import com.opencray.app.facade.safety.SafetySettingsFacade
import com.opencray.app.facade.safety.SafetySettingsLocationSnapshot
import com.opencray.app.facade.safety.SafetySettingsSnapshot
import com.opencray.app.facade.safety.SaveSafetySettingsRequest
import com.opencray.app.facade.skills.InstallSourceSnapshot
import com.opencray.app.facade.skills.InstalledSkillSnapshot
import com.opencray.app.facade.skills.SkillInstallRequestResult
import com.opencray.app.facade.skills.SkillInstructionsSnapshot
import com.opencray.app.facade.skills.SkillsFacade
import com.opencray.app.facade.skills.SkillsSnapshot
import com.opencray.app.facade.skills.SuggestedSkillSnapshot
import com.opencray.app.facade.settings.SettingsDetailSnapshot
import com.opencray.app.facade.settings.SettingsFacade
import com.opencray.app.facade.settings.SettingsOverviewSnapshot
import com.opencray.app.facade.settings.SettingsRouteId
import com.opencray.app.shell.AppShellStateStore
import com.opencray.app.shell.InMemoryAppShellKeyValueStore
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
import com.opencray.core.orchestrator.SessionTaskRuntime
import com.opencray.core.orchestrator.SessionQueueTaskSnapshot
import com.opencray.persistence.model.ChatAttachmentKind
import com.opencray.persistence.model.ChatTranscriptRole
import com.opencray.persistence.model.MemoryRecord
import com.opencray.persistence.store.MemoryStore
import com.opencray.runtime.AgentToolCall
import com.opencray.runtime.AgentToolResult
import com.opencray.runtime.AgentToolResultStatus
import com.opencray.runtime.AgentTodoEntry
import com.opencray.runtime.AgentTodoStatus
import com.opencray.runtime.OpenCrayAttachmentArtifactMetadataKeys
import com.opencray.runtime.OpenCrayExecutionMetadataKeys
import com.opencray.runtime.OpenCrayFinalAttachment
import com.opencray.runtime.OpenCrayLifecycleEvent
import com.opencray.runtime.OpenCrayMemoryRetrievalEvent
import com.opencray.runtime.OpenCrayMemoryWriteEvent
import com.opencray.runtime.OpenCrayProgressEvent
import com.opencray.runtime.OpenCrayRunLifecyclePhase
import com.opencray.runtime.OpenCrayToolCallEvent
import com.opencray.runtime.OpenCrayToolResultEvent
import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.context.RuntimeConversationRole
import com.opencray.runtime.memory.MemoryCandidateExtractor
import com.opencray.runtime.memory.MemoryKind
import com.opencray.runtime.memory.MemoryRecordExtensionKeys
import com.opencray.runtime.memory.MemoryPreferenceKeys
import com.opencray.runtime.memory.MemoryScope
import com.opencray.runtime.memory.MemorySoulExtensionKeys
import com.opencray.runtime.memory.TaskCommitmentIntentAction
import com.opencray.runtime.memory.TaskCommitmentIntentDecision
import com.opencray.runtime.memory.TaskCommitmentIntentInterpretation
import com.opencray.runtime.memory.TaskCommitmentIntentInterpreter
import com.opencray.runtime.memory.TaskCommitmentIntentRequest
import com.opencray.runtime.memory.UserMemoryIntent
import com.opencray.runtime.memory.UserMemoryIntentInterpretation
import com.opencray.runtime.memory.UserMemoryIntentInterpreter
import com.opencray.runtime.memory.UserMemoryIntentRequest
import com.opencray.runtime.memory.MemoryWriter
import com.opencray.runtime.memory.TaskCommitmentResolver
import com.opencray.policy.ExternalAccessMode
import com.opencray.policy.SafetyAutomationMode
import com.opencray.policy.ToolPolicyOverride
import com.opencray.policy.WorkspaceAccessProfile
import com.opencray.runtime.soul.InteractionPreferenceState
import com.opencray.runtime.soul.PreferenceAxisState
import com.opencray.runtime.soul.PreferredAddressState
import com.opencray.runtime.soul.PreferredAddressStyle
import com.opencray.runtime.soul.RelationshipState
import com.opencray.runtime.soul.SoulMemoryExtensionKeys
import com.opencray.runtime.soul.SoulMemoryObjectTypes
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executor
import kotlinx.serialization.encodeToString
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
  fun backgroundSessionReplyDoesNotSwitchActiveSessionAndMarksUnread() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-background-unread"))
    val sessionAId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handleA = RecordingSessionHandle(
      sessionId = sessionAId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handleA)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Reply later")
    val task = handleA.submittedTasks.single()

    hostRuntime.createChatSession()
    val sessionBId = chatStore.loadState().activeSession.sessionId

    manager.emitTaskFinished(
      sessionId = sessionAId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
        stdout = "Background reply finished.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata,
      ),
    )

    val snapshot = hostRuntime.loadChatSnapshot()
    val drawer = snapshot["drawer"] as Map<*, *>
    val sessions = (drawer["sessions"] as List<*>).map { it as Map<*, *> }
    val sessionA = sessions.first { session -> session["sessionId"] == sessionAId }
    val sessionB = sessions.first { session -> session["sessionId"] == sessionBId }

    assertEquals(sessionBId, chatStore.loadState().activeSession.sessionId)
    assertEquals(true, sessionB["isSelected"])
    assertEquals(1, sessionA["unreadCount"])
    assertEquals(0, sessionB["unreadCount"])
  }

  @Test
  fun selectingSessionClearsUnreadCountForThatSession() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-clear-unread"))
    val sessionAId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handleA = RecordingSessionHandle(
      sessionId = sessionAId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handleA)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Reply later")
    val task = handleA.submittedTasks.single()
    hostRuntime.createChatSession()

    manager.emitTaskFinished(
      sessionId = sessionAId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
        stdout = "Background reply finished.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata,
      ),
    )

    hostRuntime.selectChatSession(sessionAId)

    val snapshot = hostRuntime.loadChatSnapshot()
    val drawer = snapshot["drawer"] as Map<*, *>
    val sessions = (drawer["sessions"] as List<*>).map { it as Map<*, *> }
    val selectedSession = sessions.first { session -> session["sessionId"] == sessionAId }

    assertEquals(sessionAId, chatStore.loadState().activeSession.sessionId)
    assertEquals(true, selectedSession["isSelected"])
    assertEquals(0, selectedSession["unreadCount"])
  }

  @Test
  fun loadChatSnapshotIncludesTodosForActiveSession() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-todos"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = RecordingRuntimeManager(),
      todoSnapshotProvider = { requestedSessionId ->
        if (requestedSessionId != sessionId) {
          emptyList()
        } else {
          listOf(
            AgentTodoEntry(
              content = "Review chat composer layout",
              status = AgentTodoStatus.PENDING,
            ),
            AgentTodoEntry(
              content = "Highlight active todo text",
              status = AgentTodoStatus.IN_PROGRESS,
              activeForm = "Highlighting active todo text",
            ),
            AgentTodoEntry(
              content = "Approve Pencil prototype",
              status = AgentTodoStatus.COMPLETED,
            ),
          )
        }
      },
    )

    val snapshot = hostRuntime.loadChatSnapshot()

    val todos = snapshot["todos"] as List<*>
    val pendingTodo = todos[0] as Map<*, *>
    val activeTodo = todos[1] as Map<*, *>
    val completedTodo = todos[2] as Map<*, *>

    assertEquals(3, todos.size)
    assertEquals("Review chat composer layout", pendingTodo["content"])
    assertEquals("pending", pendingTodo["status"])
    assertEquals("Highlight active todo text", activeTodo["content"])
    assertEquals("in_progress", activeTodo["status"])
    assertEquals("Highlighting active todo text", activeTodo["activeForm"])
    assertEquals("Approve Pencil prototype", completedTodo["content"])
    assertEquals("completed", completedTodo["status"])
  }

  @Test
  fun chatObserverPublishesTodoSnapshotWhenTodoWriteResultArrives() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-todo-observer"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val runtimeManager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = sessionId,
      onResume = runtimeManager.resumedSessionIds::add,
    )
    runtimeManager.putHandle(handle)
    val mainThreadPoster = QueuedMainThreadPoster()
    var currentTodos = emptyList<AgentTodoEntry>()
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = runtimeManager,
      todoSnapshotProvider = { requestedSessionId ->
        if (requestedSessionId == sessionId) currentTodos else emptyList()
      },
      mainThreadPoster = mainThreadPoster,
    )
    val observedSnapshots = mutableListOf<Map<String, Any?>>()
    val dispose = hostRuntime.observeChat { snapshot ->
      observedSnapshots += snapshot
    }
    mainThreadPoster.flush()
    observedSnapshots.clear()

    hostRuntime.submitChatMessage("Track active todo changes")
    val task = handle.submittedTasks.single()
    val run = handle.submissions.single()
    mainThreadPoster.flush()
    observedSnapshots.clear()

    currentTodos = listOf(
      AgentTodoEntry(
        content = "Review chat composer layout",
        status = AgentTodoStatus.IN_PROGRESS,
        activeForm = "Reviewing chat composer layout",
      ),
    )
    runtimeManager.emitRunEvent(
      sessionId = sessionId,
      task = task,
      event = OpenCrayToolResultEvent(
        runId = run.runId,
        taskId = task.id,
        turn = 0,
        call = AgentToolCall(toolName = "TodoWrite"),
        result = AgentToolResult(
          toolName = "TodoWrite",
          status = AgentToolResultStatus.SUCCESS,
          content = "[in_progress] Review chat composer layout | active: Reviewing chat composer layout",
          metadata = mapOf(
            "todoCount" to "1",
            "mutated" to "true",
          ),
        ),
        emittedAtEpochMs = 1_001L,
      ),
    )
    mainThreadPoster.flush()

    val addedTodos = observedSnapshots.last()["todos"] as List<*>
    val addedTodo = addedTodos.single() as Map<*, *>
    assertEquals("Review chat composer layout", addedTodo["content"])
    assertEquals("in_progress", addedTodo["status"])
    assertEquals("Reviewing chat composer layout", addedTodo["activeForm"])

    observedSnapshots.clear()
    currentTodos = emptyList()
    runtimeManager.emitRunEvent(
      sessionId = sessionId,
      task = task,
      event = OpenCrayToolResultEvent(
        runId = run.runId,
        taskId = task.id,
        turn = 1,
        call = AgentToolCall(toolName = "TodoWrite"),
        result = AgentToolResult(
          toolName = "TodoWrite",
          status = AgentToolResultStatus.SUCCESS,
          content = "Todo list is empty.",
          metadata = mapOf(
            "todoCount" to "0",
            "mutated" to "true",
          ),
        ),
        emittedAtEpochMs = 1_002L,
      ),
    )
    mainThreadPoster.flush()
    dispose()

    val clearedTodos = observedSnapshots.last()["todos"] as List<*>
    assertTrue(clearedTodos.isEmpty())
  }

  @Test
  fun copyChatSessionDuplicatesTranscriptAndSelectsCopy() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-copy"))
    val originalSessionId = chatStore.loadState().activeSession.sessionId
    chatStore.appendUserMessage(originalSessionId, "Clone this thread")
    chatStore.appendMessage(
      sessionId = originalSessionId,
      role = ChatTranscriptRole.ASSISTANT,
      text = "Copied reply.",
    )
    val manager = RecordingRuntimeManager()
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.copyChatSession(originalSessionId)

    val copiedSessionId = chatStore.loadState().activeSession.sessionId
    val originalMessages = checkNotNull(chatStore.loadSession(originalSessionId)).messages
    val copiedMessages = checkNotNull(chatStore.loadSession(copiedSessionId)).messages

    assertTrue(copiedSessionId != originalSessionId)
    assertEquals(listOf(originalSessionId, copiedSessionId), manager.resumedSessionIds)
    assertEquals(originalMessages, copiedMessages)
  }

  @Test
  fun branchChatSessionFromMessageClonesTranscriptPrefixIntoNewSession() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-branch"))
    val originalSessionId = chatStore.loadState().activeSession.sessionId
    chatStore.appendUserMessage(originalSessionId, "Keep the first turn")
    chatStore.appendMessage(
      sessionId = originalSessionId,
      role = ChatTranscriptRole.ASSISTANT,
      text = "First assistant reply.",
    )
    chatStore.appendUserMessage(originalSessionId, "Drop the later turn")
    val originalMessages = checkNotNull(chatStore.loadSession(originalSessionId)).messages
    val branchMessageId = originalMessages
      .first { message ->
        message.role == ChatTranscriptRole.ASSISTANT &&
          message.text == "First assistant reply."
      }
      .messageId
    val branchIndex = originalMessages.indexOfFirst { message -> message.messageId == branchMessageId }
    val manager = RecordingRuntimeManager()
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.branchChatSessionFromMessage(originalSessionId, branchMessageId)

    val branchedSessionId = chatStore.loadState().activeSession.sessionId
    val branchedMessages = checkNotNull(chatStore.loadSession(branchedSessionId)).messages

    assertTrue(branchedSessionId != originalSessionId)
    assertEquals(listOf(originalSessionId, branchedSessionId), manager.resumedSessionIds)
    assertEquals(originalMessages.take(branchIndex + 1), branchedMessages)
    assertEquals(originalMessages, checkNotNull(chatStore.loadSession(originalSessionId)).messages)
  }

  @Test
  fun deleteChatSessionCreatesReplacementWhenRemovingLastSession() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-delete-last"))
    val originalSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.deleteChatSession(originalSessionId)

    val state = chatStore.loadState()

    assertEquals(1, state.sessions.size)
    assertTrue(state.activeSession.sessionId != originalSessionId)
    assertEquals(listOf(originalSessionId), manager.releasedSessionIds)
  }

  @Test
  fun deleteChatSessionTerminatesLiveManagedProcessesBeforeRelease() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-delete-processes"))
    val originalSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(sessionId = originalSessionId)
    handle.putManagedProcess(
      com.opencray.runtime.process.ManagedProcessSnapshot(
        processId = "proc-running",
        taskId = "task-running",
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
    handle.putManagedProcess(
      com.opencray.runtime.process.ManagedProcessSnapshot(
        processId = "proc-success",
        taskId = "task-success",
        command = "npm",
        args = listOf("run", "dev"),
        workingDirectory = ".",
        status = com.opencray.runtime.process.ManagedProcessStatus.SUCCESS,
        processStarted = true,
        timeoutMs = 120_000L,
        startedAtEpochMs = 1_000L,
        updatedAtEpochMs = 1_001L,
        finishedAtEpochMs = 1_001L,
      ),
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.deleteChatSession(originalSessionId)

    assertEquals(listOf("proc-running"), handle.terminatedProcessIds)
    assertEquals(listOf(originalSessionId), manager.releasedSessionIds)
  }

  @Test
  fun deleteChatSessionIgnoresLateRuntimeEventsFromRemovedSession() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-delete-late-events"))
    val sessionAId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handleA = RecordingSessionHandle(
      sessionId = sessionAId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handleA)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Reply later")
    val task = handleA.submittedTasks.single()
    hostRuntime.createChatSession()
    val sessionBId = chatStore.loadState().activeSession.sessionId

    hostRuntime.deleteChatSession(sessionAId)

    manager.emitTaskFinished(
      sessionId = sessionAId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
        stdout = "Late reply ignored.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata,
      ),
    )

    val snapshot = hostRuntime.loadChatSnapshot()
    val drawer = snapshot["drawer"] as Map<*, *>
    val sessions = (drawer["sessions"] as List<*>).map { it as Map<*, *> }

    assertEquals(listOf(sessionAId), manager.releasedSessionIds)
    assertEquals(sessionBId, chatStore.loadState().activeSession.sessionId)
    assertEquals(1, sessions.size)
    assertTrue(sessions.none { session -> session["sessionId"] == sessionAId })
  }

  @Test
  fun deleteChatMessageRemovesPendingAssistantMessageAndCancelsMatchingRun() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-delete-message"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = sessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Delete the pending bubble")
    val sessionBeforeDelete = checkNotNull(chatStore.loadSession(sessionId))
    val assistantMessageId = sessionBeforeDelete.messages
      .last { message -> message.role == ChatTranscriptRole.ASSISTANT }
      .messageId

    hostRuntime.deleteChatMessage(sessionId, assistantMessageId)

    val remainingMessages = checkNotNull(chatStore.loadSession(sessionId)).messages

    assertTrue(remainingMessages.none { message -> message.messageId == assistantMessageId })
    assertEquals(listOf(handle.submissions.single().taskId), handle.cancelledTaskIds)
    assertEquals(listOf(setOf(assistantMessageId)), handle.cancelledPendingMessageIdSets)
  }

  @Test
  fun recallChatMessageDropsPromptTailAndCancelsPendingRun() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-recall-message"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = sessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Recall this turn")
    val sessionBeforeRecall = checkNotNull(chatStore.loadSession(sessionId))
    val userMessageId = sessionBeforeRecall.messages
      .last { message -> message.role == ChatTranscriptRole.USER }
      .messageId
    val assistantMessageId = sessionBeforeRecall.messages
      .last { message -> message.role == ChatTranscriptRole.ASSISTANT }
      .messageId

    hostRuntime.recallChatMessage(sessionId, userMessageId)

    val remainingMessages = checkNotNull(chatStore.loadSession(sessionId)).messages

    assertTrue(remainingMessages.none { message -> message.messageId == userMessageId })
    assertTrue(remainingMessages.none { message -> message.messageId == assistantMessageId })
    assertEquals(listOf(handle.submissions.single().taskId), handle.cancelledTaskIds)
    assertEquals(
      listOf(linkedSetOf(userMessageId, assistantMessageId)),
      handle.cancelledPendingMessageIdSets,
    )
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
        supplement {"run_id":"${submittedRun.runId}","task_id":"${submittedTask.id}","turn":1,"entry_id":"supplement-1","text":"Also check the tests","checkpoint":"turn_start"}
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
  fun submitChatMessageWhileRunActiveQueuesFollowUpWhenDeferredQueueAlreadyExists() {
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
      queuedInputs.map(PendingUserInputEntry::text),
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

  @Test
  fun submitChatMessageDoesNotAttachHostOnlyPolicyDetailToPromptTask() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-policy-detail"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Check approval behavior")

    val submittedTask = handle.submittedTasks.single()

    assertEquals(PolicyDecisionOutcome.ALLOW, submittedTask.policyDecision.outcome)
    assertEquals("FLUTTER_CHAT_ALLOW", submittedTask.policyDecision.reasonCode)
    assertEquals(null, submittedTask.policyDecision.detail)
  }

  @Test
  fun submitChatMessageIncludesCurrentSafetyMetadataOverrides() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-safety-metadata"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val safetyFacade = RecordingSafetySettingsFacade(
      snapshot = defaultSafetySettingsSnapshot().copy(
        automationMode = SafetyAutomationMode.DEV,
        fileChangesPolicy = ToolPolicyOverride.ALLOW,
        fileDeletesPolicy = ToolPolicyOverride.BLOCK,
        shellCommandsPolicy = ToolPolicyOverride.ASK,
      ),
    )
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      safetySettingsFacade = safetyFacade,
    )

    hostRuntime.submitChatMessage("Check the current guardrails")

    val submittedTask = handle.submittedTasks.single()

    assertEquals("DEV", submittedTask.metadata["chatMode"])
    assertEquals("DEVELOPER", submittedTask.metadata["executionMode"])
    assertEquals("allow", submittedTask.metadata["fileChangesPolicyId"])
    assertEquals("block", submittedTask.metadata["fileDeletesPolicyId"])
    assertEquals("ask", submittedTask.metadata["shellCommandsPolicyId"])
  }

  @Test
  fun submitChatMessageIncludesApprovedReadRootsMetadata() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-approved-read-roots"))
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
      approvedReadRootsProvider = {
        ApprovedReadRootsSnapshot(
          roots = emptySet(),
          summary = "workspace=/workspace | photo_library=/storage/emulated/0/DCIM,/storage/emulated/0/Pictures",
        )
      },
    )

    hostRuntime.submitChatMessage("Check external read roots")

    val submittedTask = handle.submittedTasks.single()

    assertEquals("select_paths", submittedTask.metadata["externalAccessModeId"])
    assertEquals("true", submittedTask.metadata["readOnlyOutsideWorkspace"])
    assertEquals(
      "workspace=/workspace | photo_library=/storage/emulated/0/DCIM,/storage/emulated/0/Pictures",
      submittedTask.metadata["approvedReadRoots"],
    )
  }

  @Test
  fun chatSnapshotReflectsCurrentSafetyModeLabel() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-safety-mode"))
    val manager = RecordingRuntimeManager()
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      safetySettingsFacade = RecordingSafetySettingsFacade(
        snapshot = defaultSafetySettingsSnapshot().copy(
          automationMode = SafetyAutomationMode.SAFE,
        ),
      ),
    )

    val snapshot = hostRuntime.loadChatSnapshot()

    assertEquals("SAFE", snapshot["modeLabel"])
  }

  @Test
  fun saveSafetySettingsPersistsLiveContextMode() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-live-context-mode"))
    val safetyFacade = RecordingSafetySettingsFacade()
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = RecordingRuntimeManager(),
      safetySettingsFacade = safetyFacade,
    )

    val payload = hostRuntime.saveSafetySettings(
      automationModeId = SafetyAutomationMode.AUTO.wireValue,
      rollbackJournalEnabled = true,
      maxFilesPerBatch = 20,
      maxAgentTurns = 0,
      maxToolCalls = 0,
      undoWindowHours = 24,
      fileChangesPolicyId = ToolPolicyOverride.INHERIT.wireValue,
      fileDeletesPolicyId = ToolPolicyOverride.INHERIT.wireValue,
      shellCommandsPolicyId = ToolPolicyOverride.INHERIT.wireValue,
      externalAccessModeId = ExternalAccessMode.SELECT_PATHS.wireValue,
      photoLibraryEnabled = true,
      downloadsEnabled = true,
      documentsEnabled = false,
      recordingsEnabled = false,
      workspaceAccessProfileId = WorkspaceAccessProfile.WORK.wireValue,
      readOnlyOutsideWorkspace = true,
      liveContextModeId = LiveContextMode.NO_SOUL.wireValue,
      memoryToolsEnabled = false,
    )

    assertEquals(LiveContextMode.NO_SOUL.wireValue, payload["liveContextModeId"])
    assertEquals(false, payload["memoryToolsEnabled"])
    assertEquals(LiveContextMode.NO_SOUL.wireValue, safetyFacade.lastSavedRequest?.liveContextModeId)
    assertEquals(false, safetyFacade.lastSavedRequest?.memoryToolsEnabled)
    assertEquals(LiveContextMode.NO_SOUL, safetyFacade.snapshot.liveContextMode)
    assertEquals(false, safetyFacade.snapshot.memoryToolsEnabled)
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
  fun taskSuccessArchivesAssistantAttachmentsIntoWorkspaceMediaStore() {
    val chatStore = ChatSessionLocalStore(
      temporaryFolder.newFolder("chat-store-assistant-attachments"),
    )
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val workspaceRoot = temporaryFolder.newFolder("chat-media-workspace").toPath()
    val approvedExternalRoot = temporaryFolder.newFolder("chat-media-approved").toPath()
    Files.createDirectories(workspaceRoot.resolve("outputs"))
    Files.write(workspaceRoot.resolve("outputs").resolve("diagram.png"), byteArrayOf(1, 2, 3, 4))
    Files.write(approvedExternalRoot.resolve("voice-note.m4a"), byteArrayOf(5, 6, 7, 8))
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
      approvedReadRootsProvider = {
        ApprovedReadRootsSnapshot(
          roots = setOf(approvedExternalRoot),
          summary = approvedExternalRoot.toString(),
        )
      },
    )

    hostRuntime.submitChatMessage("Send the generated media.")
    val task = handle.submittedTasks.single()
    val attachmentsJson = Json.encodeToString(
      ListSerializer(OpenCrayFinalAttachment.serializer()),
      listOf(
        OpenCrayFinalAttachment(
          kind = "image",
          relativePath = "outputs/diagram.png",
          displayName = "diagram.png",
        ),
        OpenCrayFinalAttachment(
          kind = "audio",
          path = approvedExternalRoot.resolve("voice-note.m4a").toString(),
          displayName = "voice-note.m4a",
          durationMs = 4_200L,
          waveformBars = listOf(12, 40, 88),
          transcriptText = "Voice summary",
        ),
      ),
    )
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
        stdout = "",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata + mapOf(
          OpenCrayExecutionMetadataKeys.FINAL_ATTACHMENTS_JSON to attachmentsJson,
        ),
      ),
    )

    val assistantMessage = chatStore.loadState().activeSession.messages
      .filter { message -> message.role != ChatTranscriptRole.SYSTEM }
      .last()
    val snapshot = hostRuntime.loadChatSnapshot()
    val snapshotMessages = (snapshot["messages"] as List<*>).map { it as Map<*, *> }
    val snapshotAttachments =
      (snapshotMessages.last()["attachments"] as List<*>).map { it as Map<*, *> }

    assertEquals(null, assistantMessage.text)
    assertEquals(2, assistantMessage.attachments.size)
    assertEquals(ChatAttachmentKind.IMAGE, assistantMessage.attachments.first().kind)
    assertEquals(ChatAttachmentKind.VOICE, assistantMessage.attachments.last().kind)
    assertEquals(4_200L, assistantMessage.attachments.last().durationMs)
    assertEquals(listOf(12, 40, 88), assistantMessage.attachments.last().waveformBars)
    assertEquals("Voice summary", assistantMessage.attachments.last().transcriptText)
    assistantMessage.attachments.forEach { attachment ->
      assertTrue(attachment.localPath.startsWith(".opencray/chat-media/$activeSessionId/"))
      assertTrue(Files.exists(workspaceRoot.resolve(attachment.localPath)))
    }
    assertEquals(2, snapshotAttachments.size)
    assertEquals("image", snapshotAttachments.first()["kind"])
    assertEquals("voice", snapshotAttachments.last()["kind"])
    assertEquals(4_200L, snapshotAttachments.last()["durationMs"])
    assertEquals(listOf(12, 40, 88), snapshotAttachments.last()["waveformBars"])
    assertEquals("Voice summary", snapshotAttachments.last()["transcriptText"])
  }

  @Test
  fun taskSuccessResolvesArtifactOnlyAttachmentsIntoWorkspaceMediaStore() {
    val chatStore = ChatSessionLocalStore(
      temporaryFolder.newFolder("chat-store-assistant-artifact-attachments"),
    )
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val workspaceRoot = temporaryFolder.newFolder("chat-artifact-workspace").toPath()
    Files.createDirectories(workspaceRoot.resolve("outputs"))
    Files.write(workspaceRoot.resolve("outputs").resolve("diagram.png"), byteArrayOf(1, 2, 3, 4))
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

    hostRuntime.submitChatMessage("Send the generated image by artifact id.")
    val task = handle.submittedTasks.single()
    val run = handle.submissions.single()
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = OpenCrayToolResultEvent(
        runId = run.runId,
        taskId = task.id,
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
    val attachmentsJson = Json.encodeToString(
      ListSerializer(OpenCrayFinalAttachment.serializer()),
      listOf(
        OpenCrayFinalAttachment(
          artifactId = "artifact-diagram-1234abcd",
        ),
      ),
    )
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
        stdout = "",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata + mapOf(
          OpenCrayExecutionMetadataKeys.FINAL_ATTACHMENTS_JSON to attachmentsJson,
        ),
      ),
    )

    val assistantMessage = chatStore.loadState().activeSession.messages
      .filter { message -> message.role != ChatTranscriptRole.SYSTEM }
      .last()
    val attachment = assistantMessage.attachments.single()

    assertEquals(null, assistantMessage.text)
    assertEquals(ChatAttachmentKind.IMAGE, attachment.kind)
    assertEquals("diagram.png", attachment.displayName)
    assertEquals("image/png", attachment.mimeType)
    assertTrue(attachment.localPath.startsWith(".opencray/chat-media/$activeSessionId/"))
    assertTrue(Files.exists(workspaceRoot.resolve(attachment.localPath)))
  }

  @Test
  fun taskSuccessBackfillsMissingVoiceMetadataAsynchronously() {
    val chatStore = ChatSessionLocalStore(
      temporaryFolder.newFolder("chat-store-voice-metadata-backfill"),
    )
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val workspaceRoot = temporaryFolder.newFolder("voice-metadata-backfill-workspace").toPath()
    val approvedExternalRoot = temporaryFolder.newFolder("voice-metadata-backfill-approved").toPath()
    val sourceVoice = approvedExternalRoot.resolve("voice-note.m4a")
    Files.write(sourceVoice, byteArrayOf(5, 6, 7, 8))
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val queuedExecutor = QueuedExecutor()
    var analyzerCallCount = 0
    val cacheStore = AppAgentWorkspaceVoiceMetadataCacheStore.fromWorkspaceRoot(workspaceRoot)
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      workspaceRootProvider = { workspaceRoot },
      approvedReadRootsProvider = {
        ApprovedReadRootsSnapshot(
          roots = setOf(approvedExternalRoot),
          summary = approvedExternalRoot.toString(),
        )
      },
      voiceMetadataAnalyzer = AppAgentWorkspaceVoiceMetadataAnalyzer { path, mimeType ->
        analyzerCallCount += 1
        assertTrue(path.startsWith(workspaceRoot.resolve(".opencray").normalize()))
        assertEquals("audio/mp4", mimeType)
        AppAgentWorkspaceVoiceMetadata(
          durationMs = 4_200L,
          waveformBars = listOf(12, 40, 88),
          transcriptText = "Backfilled transcript",
        )
      },
      voiceMetadataBackfillExecutor = queuedExecutor,
      voiceMetadataCacheStore = cacheStore,
    )

    hostRuntime.submitChatMessage("Send the generated voice.")
    val task = handle.submittedTasks.single()
    val attachmentsJson = Json.encodeToString(
      ListSerializer(OpenCrayFinalAttachment.serializer()),
      listOf(
        OpenCrayFinalAttachment(
          kind = "voice",
          path = sourceVoice.toString(),
          displayName = "voice-note.m4a",
        ),
      ),
    )
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
        stdout = "",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata + mapOf(
          OpenCrayExecutionMetadataKeys.FINAL_ATTACHMENTS_JSON to attachmentsJson,
        ),
      ),
    )

    val initialAttachment = chatStore.loadState().activeSession.messages
      .filter { message -> message.role != ChatTranscriptRole.SYSTEM }
      .last()
      .attachments
      .single()
    assertEquals(null, initialAttachment.durationMs)
    assertTrue(initialAttachment.waveformBars.isEmpty())
    assertEquals(null, initialAttachment.transcriptText)
    assertEquals(1, queuedExecutor.pendingCount())
    assertEquals(0, analyzerCallCount)

    queuedExecutor.runAll()

    val updatedAttachment = chatStore.loadState().activeSession.messages
      .filter { message -> message.role != ChatTranscriptRole.SYSTEM }
      .last()
      .attachments
      .single()
    val snapshot = hostRuntime.loadChatSnapshot()
    val snapshotAttachments = ((snapshot["messages"] as List<*>).last() as Map<*, *>)["attachments"] as List<*>
    val snapshotVoice = snapshotAttachments.single() as Map<*, *>
    val cachedMetadata = cacheStore.get(updatedAttachment.contentSha256 ?: error("Expected voice hash."))

    assertEquals(1, analyzerCallCount)
    assertEquals(0, queuedExecutor.pendingCount())
    assertEquals(4_200L, updatedAttachment.durationMs)
    assertEquals(listOf(12, 40, 88), updatedAttachment.waveformBars)
    assertEquals("Backfilled transcript", updatedAttachment.transcriptText)
    assertEquals(4_200L, snapshotVoice["durationMs"])
    assertEquals(listOf(12, 40, 88), snapshotVoice["waveformBars"])
    assertEquals("Backfilled transcript", snapshotVoice["transcriptText"])
    assertEquals(
      AppAgentWorkspaceVoiceMetadata(
        durationMs = 4_200L,
        waveformBars = listOf(12, 40, 88),
        transcriptText = "Backfilled transcript",
      ),
      cachedMetadata,
    )
  }

  @Test
  fun voiceMetadataBackfillReusesCacheForSameContentAcrossSessions() {
    val chatStore = ChatSessionLocalStore(
      temporaryFolder.newFolder("chat-store-voice-metadata-cache-reuse"),
    )
    val firstSessionId = chatStore.loadState().activeSession.sessionId
    val workspaceRoot = temporaryFolder.newFolder("voice-metadata-cache-workspace").toPath()
    val approvedExternalRoot = temporaryFolder.newFolder("voice-metadata-cache-approved").toPath()
    val sourceVoice = approvedExternalRoot.resolve("shared-voice.m4a")
    Files.write(sourceVoice, byteArrayOf(9, 10, 11, 12))
    val manager = RecordingRuntimeManager()
    val firstHandle = RecordingSessionHandle(
      sessionId = firstSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(firstHandle)
    val queuedExecutor = QueuedExecutor()
    var analyzerCallCount = 0
    val cacheStore = AppAgentWorkspaceVoiceMetadataCacheStore.fromWorkspaceRoot(workspaceRoot)
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      workspaceRootProvider = { workspaceRoot },
      approvedReadRootsProvider = {
        ApprovedReadRootsSnapshot(
          roots = setOf(approvedExternalRoot),
          summary = approvedExternalRoot.toString(),
        )
      },
      voiceMetadataAnalyzer = AppAgentWorkspaceVoiceMetadataAnalyzer { _, _ ->
        analyzerCallCount += 1
        AppAgentWorkspaceVoiceMetadata(
          durationMs = 8_100L,
          waveformBars = listOf(18, 36, 72),
          transcriptText = "Cached transcript",
        )
      },
      voiceMetadataBackfillExecutor = queuedExecutor,
      voiceMetadataCacheStore = cacheStore,
    )
    val attachmentsJson = Json.encodeToString(
      ListSerializer(OpenCrayFinalAttachment.serializer()),
      listOf(
        OpenCrayFinalAttachment(
          kind = "voice",
          path = sourceVoice.toString(),
          displayName = "shared-voice.m4a",
        ),
      ),
    )

    hostRuntime.submitChatMessage("Send the first shared voice.")
    val firstTask = firstHandle.submittedTasks.single()
    manager.emitTaskFinished(
      sessionId = firstSessionId,
      task = firstTask,
      result = ExecutionResult(
        taskId = firstTask.id,
        status = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
        stdout = "",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = firstTask.metadata + mapOf(
          OpenCrayExecutionMetadataKeys.FINAL_ATTACHMENTS_JSON to attachmentsJson,
        ),
      ),
    )
    assertEquals(1, queuedExecutor.pendingCount())

    queuedExecutor.runAll()

    assertEquals(1, analyzerCallCount)
    assertEquals(0, queuedExecutor.pendingCount())

    hostRuntime.createChatSession()
    val secondSessionId = chatStore.loadState().activeSession.sessionId
    val secondHandle = RecordingSessionHandle(
      sessionId = secondSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(secondHandle)

    hostRuntime.submitChatMessage("Send the same shared voice again.")
    val secondTask = secondHandle.submittedTasks.single()
    manager.emitTaskFinished(
      sessionId = secondSessionId,
      task = secondTask,
      result = ExecutionResult(
        taskId = secondTask.id,
        status = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
        stdout = "",
        startedAtEpochMs = 2_000L,
        finishedAtEpochMs = 2_001L,
        metadata = secondTask.metadata + mapOf(
          OpenCrayExecutionMetadataKeys.FINAL_ATTACHMENTS_JSON to attachmentsJson,
        ),
      ),
    )

    val secondAttachment = chatStore.loadState().activeSession.messages
      .filter { message -> message.role != ChatTranscriptRole.SYSTEM }
      .last()
      .attachments
      .single()

    assertEquals(1, analyzerCallCount)
    assertEquals(0, queuedExecutor.pendingCount())
    assertEquals(8_100L, secondAttachment.durationMs)
    assertEquals(listOf(18, 36, 72), secondAttachment.waveformBars)
    assertEquals("Cached transcript", secondAttachment.transcriptText)
  }

  @Test
  fun loadWorkspaceVoicePlaybackSourceResolvesSupportedVoiceFiles() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-voice-playback-source"))
    val workspaceRoot = temporaryFolder.newFolder("workspace-voice-playback-source").toPath()
    val voiceFile = workspaceRoot.resolve(".opencray/chat-media/session-1/hash/voice-note.m4a")
    Files.createDirectories(voiceFile.parent)
    Files.write(voiceFile, byteArrayOf(1, 2, 3, 4))
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = NoOpRuntimeManager(),
      workspaceRootProvider = { workspaceRoot },
    )

    val payload = hostRuntime.loadWorkspaceVoicePlaybackSource(
      ".opencray/chat-media/session-1/hash/voice-note.m4a",
    )

    assertEquals("voice-note.m4a", payload["name"])
    assertEquals(".opencray/chat-media/session-1/hash/voice-note.m4a", payload["relativePath"])
    assertEquals(voiceFile.toAbsolutePath().normalize().toString(), payload["localFilePath"])
    assertEquals("audio/mp4", payload["mimeType"])
    assertEquals(4L, payload["sizeBytes"])
  }

  @Test
  fun openWorkspaceEntryDelegatesToInjectedOpener() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-open-entry"))
    val workspaceRoot = temporaryFolder.newFolder("workspace-open-entry").toPath()
    val openedEntries = mutableListOf<Pair<Path, String>>()
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = NoOpRuntimeManager(),
      workspaceRootProvider = { workspaceRoot },
      workspaceEntryOpener = { root, relativePath ->
        openedEntries += root to relativePath
      },
    )

    hostRuntime.openWorkspaceEntry(".opencray/chat-media/session-1/hash/report.pdf")

    assertEquals(
      listOf(
        workspaceRoot.toAbsolutePath().normalize() to
          ".opencray/chat-media/session-1/hash/report.pdf",
      ),
      openedEntries,
    )
  }

  @Test
  fun chatObserverReceivesSettledSnapshotAfterTaskFinish() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-settled-observer"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val runtimeManager = SettlingRuntimeManager(sessionId = activeSessionId)
    val mainThreadPoster = QueuedMainThreadPoster()
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = runtimeManager,
      mainThreadPoster = mainThreadPoster,
    )
    val observedSnapshots = mutableListOf<Map<String, Any?>>()
    val dispose = hostRuntime.observeChat { snapshot ->
      observedSnapshots += snapshot
    }
    mainThreadPoster.flush()
    observedSnapshots.clear()

    hostRuntime.submitChatMessage("Need a settled final reply")
    mainThreadPoster.flush()
    observedSnapshots.clear()

    runtimeManager.emitTaskFinished(
      ExecutionResult(
        taskId = runtimeManager.handle.requireSubmittedTask().id,
        status = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
        stdout = "Settled final reply",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = mapOf("responseFormat" to "json_final"),
      ),
    )
    mainThreadPoster.flush()
    dispose()

    val snapshot = observedSnapshots.last()
    val messages = (snapshot["messages"] as List<*>).map { it as Map<*, *> }
    val summary = snapshot["summary"] as Map<*, *>
    val runtimeActivity = snapshot["runtimeActivity"] as Map<*, *>
    val activeRuns = runtimeActivity["activeRuns"] as List<*>

    assertEquals("Settled final reply", messages.last()["text"])
    assertEquals("Local transcript is restored into the runtime window for each task.", summary["body"])
    assertTrue(activeRuns.isEmpty())
  }

  @Test
  fun chatObserverPublishesBackgroundReplyPreviewAndUnreadCountAfterTaskFinish() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-background-observer"))
    val sessionAId = chatStore.loadState().activeSession.sessionId
    val runtimeManager = RecordingRuntimeManager()
    val handleA = RecordingSessionHandle(
      sessionId = sessionAId,
      onResume = runtimeManager.resumedSessionIds::add,
    )
    runtimeManager.putHandle(handleA)
    val mainThreadPoster = QueuedMainThreadPoster()
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = runtimeManager,
      mainThreadPoster = mainThreadPoster,
    )
    val observedSnapshots = mutableListOf<Map<String, Any?>>()
    val dispose = hostRuntime.observeChat { snapshot ->
      observedSnapshots += snapshot
    }
    mainThreadPoster.flush()
    observedSnapshots.clear()

    hostRuntime.submitChatMessage("Reply later")
    mainThreadPoster.flush()
    observedSnapshots.clear()

    hostRuntime.createChatSession()
    val sessionBId = chatStore.loadState().activeSession.sessionId
    mainThreadPoster.flush()
    observedSnapshots.clear()

    runtimeManager.emitTaskFinished(
      sessionId = sessionAId,
      task = handleA.submittedTasks.single(),
      result = ExecutionResult(
        taskId = handleA.submittedTasks.single().id,
        status = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
        stdout = "Background reply finished.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = handleA.submittedTasks.single().metadata + mapOf("responseFormat" to "json_final"),
      ),
    )
    mainThreadPoster.flush()
    dispose()

    val snapshot = observedSnapshots.last()
    val drawer = snapshot["drawer"] as Map<*, *>
    val sessions = (drawer["sessions"] as List<*>).map { it as Map<*, *> }
    val sessionA = sessions.first { session -> session["sessionId"] == sessionAId }
    val sessionB = sessions.first { session -> session["sessionId"] == sessionBId }

    assertEquals("Background reply finished.", sessionA["preview"])
    assertEquals(1, sessionA["unreadCount"])
    assertEquals(0, sessionB["unreadCount"])
    assertEquals(true, sessionB["isSelected"])
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
        candidateExtractor = semanticUserCandidateExtractor(),
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
    assertEquals(emptyList<String>(), memoryEvent["reaffirmedRecordIds"])
    assertEquals(listOf("commitment-expired"), memoryEvent["expiredRecordIds"])
    assertEquals("resolved", memoryStore.list().single { record -> record.id == "commitment-open" }.extensions["status"])
    assertTrue(memoryStore.list().none { record -> record.id == "commitment-expired" })
  }

  @Test
  fun taskSuccessReportsReaffirmedCommitmentsInMemoryWriteEvent() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-memory-reaffirm"))
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
          id = "commitment-reaffirm",
          content = "stabilize the flaky runtime test",
          sourceSessionId = activeSessionId,
          updatedAtEpochMs = 1_000L,
        ),
      )
    }
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      memoryIngestionCoordinator = ChatMemoryIngestionCoordinator(
        memoryStore = memoryStore,
        writer = MemoryWriter(store = memoryStore, clock = { 2_000L }),
        taskCommitmentResolver = TaskCommitmentResolver(
          store = memoryStore,
          clock = { 2_000L },
          intentInterpreter = FixedTaskCommitmentIntentInterpreter(
            TaskCommitmentIntentInterpretation.Success(
              decisions = listOf(
                TaskCommitmentIntentDecision(
                  commitmentId = "commitment-reaffirm",
                  action = TaskCommitmentIntentAction.REAFFIRM,
                ),
              ),
            ),
          ),
        ),
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
        stdout = "The flaky runtime test still needs work; I am continuing on it next.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata,
      ),
    )

    val runtimeActivity = hostRuntime.loadChatSnapshot()["runtimeActivity"] as Map<*, *>
    val events = runtimeActivity["events"] as List<*>
    val memoryEvent = events.last() as Map<*, *>

    assertEquals("memory_write", memoryEvent["kind"])
    assertEquals(emptyList<String>(), memoryEvent["resolvedRecordIds"])
    assertEquals(listOf("commitment-reaffirm"), memoryEvent["reaffirmedRecordIds"])
    assertEquals("open", memoryStore.list().single { record -> record.id == "commitment-reaffirm" }.extensions["status"])
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
      event = OpenCrayProgressEvent(
        runId = run.runId,
        taskId = task.id,
        turn = 0,
        text = "Scanning README and Gradle files before choosing the next tool.",
        stage = "Planning",
        emittedAtEpochMs = 1_150L,
      ),
    )

    val runtimeActivity = hostRuntime.loadChatSnapshot()["runtimeActivity"] as Map<*, *>
    val firstEvent = (runtimeActivity["events"] as List<*>).single() as Map<*, *>

    assertEquals(activeSessionId, runtimeActivity["sessionId"])
    assertEquals("progress", firstEvent["kind"])
    assertEquals("Planning", firstEvent["stage"])
    assertEquals(
      "Scanning README and Gradle files before choosing the next tool.",
      firstEvent["text"],
    )
    assertEquals(run.runId, firstEvent["runId"])
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
      event = OpenCrayProgressEvent(
        runId = run.runId,
        taskId = task.id,
        turn = 0,
        text = "Scanning README and Gradle files before choosing the next tool.",
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
    assertEquals(true, messages[1]["isEphemeral"])
    assertEquals(1_150L, messages[1]["createdAtEpochMs"])
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
      event = OpenCrayProgressEvent(
        runId = run.runId,
        taskId = task.id,
        turn = 0,
        text = "Scanning README and Gradle files before choosing the next tool.",
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
        content = """progress {"run_id":"${run.runId}","task_id":"${task.id}","turn":0,"text":"Scanning README and Gradle files before choosing the next tool.","stage":"Planning"}""",
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

  @Test
  fun chatSnapshotKeepsToolMessagesOutOfChatHistory() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-tool-messages-live"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Inspect README before editing")
    val task = handle.submittedTasks.single()
    val run = handle.submissions.single()
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = OpenCrayToolCallEvent(
        runId = run.runId,
        taskId = task.id,
        turn = 0,
        call = AgentToolCall(
          toolName = "Read",
          arguments = jsonObject("""{"file_path":"README.md","offset":5,"limit":2}"""),
          reason = "Inspect README before editing.",
        ),
        emittedAtEpochMs = 1_050L,
      ),
    )
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = OpenCrayToolResultEvent(
        runId = run.runId,
        taskId = task.id,
        turn = 0,
        call = AgentToolCall(
          toolName = "Read",
          arguments = jsonObject("""{"file_path":"README.md","offset":5,"limit":2}"""),
        ),
        result = AgentToolResult(
          toolName = "Read",
          status = AgentToolResultStatus.SUCCESS,
          content = "README preview",
          metadata = mapOf(
            "filePath" to "README.md",
            "offset" to "5",
            "limit" to "2",
            "returnedLineCount" to "2",
            "totalLineCount" to "12",
            "truncated" to "false",
          ),
        ),
        emittedAtEpochMs = 1_100L,
      ),
    )
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = ExecutionStatus.SUCCESS,
        stdout = "README is ready for the next step.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_200L,
        metadata = task.metadata,
      ),
    )

    val messages = (hostRuntime.loadChatSnapshot()["messages"] as List<*>)
      .map { message -> message as Map<*, *> }

    assertEquals(
      listOf(
        "Inspect README before editing",
        "README is ready for the next step.",
      ),
      messages.map { message -> message["text"] },
    )
  }

  @Test
  fun chatSnapshotKeepsReplayedToolMessagesOutOfChatHistory() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-tool-messages-replay"))
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

    hostRuntime.submitChatMessage("Inspect README before editing")
    val task = handle.submittedTasks.single()
    val run = handle.submissions.single()
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = ExecutionStatus.SUCCESS,
        stdout = "README is ready for the next step.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_200L,
        metadata = task.metadata,
      ),
    )
    transcriptMessages = listOf(
      RuntimeConversationMessage(
        role = RuntimeConversationRole.TOOL,
        content = """tool_call {"run_id":"${run.runId}","task_id":"${task.id}","turn":0,"tool_name":"Read","reason":"Inspect README before editing.","arguments":{"file_path":"README.md","offset":5,"limit":2}}""",
      ),
      RuntimeConversationMessage(
        role = RuntimeConversationRole.TOOL,
        content = """tool_result {"run_id":"${run.runId}","task_id":"${task.id}","turn":0,"tool_name":"Read","status":"success","content_preview":"README preview","metadata":{"filePath":"README.md","offset":"5","limit":"2","returnedLineCount":"2","totalLineCount":"12","truncated":"false"}}""",
      ),
    )

    val messages = (hostRuntime.loadChatSnapshot()["messages"] as List<*>)
      .map { message -> message as Map<*, *> }

    assertEquals(
      listOf(
        "Inspect README before editing",
        "README is ready for the next step.",
      ),
      messages.map { message -> message["text"] },
    )
  }

  @Test
  fun chatRuntimeSnapshotReplaysDurableTranscriptEventsWhenLiveHistoryIsEmpty() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-runtime-replay"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val transcriptMessages = listOf(
      RuntimeConversationMessage(
        role = RuntimeConversationRole.TOOL,
        content = """tool_call {"run_id":"replay-run","task_id":"replay-task","turn":0,"tool_name":"Read","reason":"Inspect README before editing.","arguments":{"file_path":"README.md","offset":5,"limit":2}}""",
      ),
      RuntimeConversationMessage(
        role = RuntimeConversationRole.TOOL,
        content = """tool_result {"run_id":"replay-run","task_id":"replay-task","turn":0,"tool_name":"Read","status":"success","content_preview":"README preview","metadata":{"filePath":"README.md","offset":"5","limit":"2","returnedLineCount":"2","totalLineCount":"12","truncated":"false","checkpointId":"hidden"}}""",
      ),
      RuntimeConversationMessage(
        role = RuntimeConversationRole.TOOL,
        content = """progress {"run_id":"replay-run","task_id":"replay-task","turn":1,"text":"Planning the next edit after reading README.","stage":"Planning"}""",
      ),
    )
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = RecordingRuntimeManager(),
      transcriptMessagesProvider = { sessionId ->
        if (sessionId == activeSessionId) {
          transcriptMessages
        } else {
          emptyList()
        }
      },
    )

    val runtimeActivity = hostRuntime.loadChatRuntimeSnapshot()
    val events = runtimeActivity["events"] as List<*>
    val toolCall = events[0] as Map<*, *>
    val toolResult = events[1] as Map<*, *>
    val progress = events[2] as Map<*, *>
    val resultMetadata = toolResult["resultMetadata"] as Map<*, *>

    assertEquals(activeSessionId, runtimeActivity["sessionId"])
    assertEquals(3, events.size)
    assertEquals("tool_call", toolCall["kind"])
    assertEquals("Read", toolCall["toolName"])
    assertEquals("Inspect README before editing.", toolCall["toolReason"])
    assertTrue((toolCall["argumentsJson"] as String).contains("README.md"))
    assertEquals("tool_result", toolResult["kind"])
    assertEquals("README preview", toolResult["contentPreview"])
    assertEquals("README.md", resultMetadata["filePath"])
    assertEquals("5", resultMetadata["offset"])
    assertEquals("2", resultMetadata["limit"])
    assertFalse(resultMetadata.containsKey("checkpointId"))
    assertEquals("progress", progress["kind"])
    assertEquals("Planning", progress["stage"])
    assertEquals(
      "Planning the next edit after reading README.",
      progress["text"],
    )
  }

  @Test
  fun activeRunUsesReplayedTranscriptEventAsLastEventWhenLiveHistoryIsEmpty() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-runtime-replay-last-event"))
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

    hostRuntime.submitChatMessage("Recover my timeline after restart")
    val task = handle.submittedTasks.single()
    val run = handle.submissions.single()
    transcriptMessages = listOf(
      RuntimeConversationMessage(
        role = RuntimeConversationRole.TOOL,
        content = """progress {"run_id":"${run.runId}","task_id":"${task.id}","turn":0,"text":"Restored progress from transcript.","stage":"Planning"}""",
      ),
    )

    val runtimeActivity = hostRuntime.loadChatSnapshot()["runtimeActivity"] as Map<*, *>
    val activeRun = ((runtimeActivity["activeRuns"] as List<*>).single()) as Map<*, *>
    val lastEvent = activeRun["lastEvent"] as Map<*, *>

    assertEquals(run.runId, activeRun["runId"])
    assertEquals("progress", lastEvent["kind"])
    assertEquals("Planning", lastEvent["stage"])
    assertEquals("Restored progress from transcript.", lastEvent["text"])
  }

  @Test
  fun chatRuntimeSnapshotDedupesReplayEventsAgainstLiveEvents() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-runtime-replay-dedupe"))
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

    hostRuntime.submitChatMessage("Read README and keep the timeline clean")
    val task = handle.submittedTasks.single()
    val run = handle.submissions.single()
    transcriptMessages = listOf(
      RuntimeConversationMessage(
        role = RuntimeConversationRole.TOOL,
        content = """tool_call {"run_id":"${run.runId}","task_id":"${task.id}","turn":0,"tool_name":"Read","arguments":{"file_path":"README.md"}}""",
      ),
      RuntimeConversationMessage(
        role = RuntimeConversationRole.TOOL,
        content = """tool_result {"run_id":"${run.runId}","task_id":"${task.id}","turn":0,"tool_name":"Read","status":"success","content_preview":"README preview","metadata":{"filePath":"README.md"}}""",
      ),
      RuntimeConversationMessage(
        role = RuntimeConversationRole.TOOL,
        content = """progress {"run_id":"${run.runId}","task_id":"${task.id}","turn":1,"text":"Evaluating the next step.","stage":"Planning"}""",
      ),
    )
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = OpenCrayToolResultEvent(
        runId = run.runId,
        taskId = task.id,
        turn = 0,
        call = AgentToolCall(toolName = "Read"),
        result = AgentToolResult(
          toolName = "Read",
          status = AgentToolResultStatus.SUCCESS,
          content = "README preview",
          metadata = mapOf("filePath" to "README.md"),
        ),
        emittedAtEpochMs = 1_200L,
      ),
    )
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = OpenCrayProgressEvent(
        runId = run.runId,
        taskId = task.id,
        turn = 1,
        text = "Evaluating the next step.",
        stage = "Planning",
        emittedAtEpochMs = 1_250L,
      ),
    )

    val runtimeActivity = hostRuntime.loadChatRuntimeSnapshot()
    val events = runtimeActivity["events"] as List<*>
    val kinds = events.map { event -> (event as Map<*, *>)["kind"] }

    assertEquals(listOf("tool_call", "tool_result", "progress"), kinds)
    assertEquals(1, kinds.count { kind -> kind == "tool_result" })
    assertEquals(1, kinds.count { kind -> kind == "progress" })
  }

  @Test
  fun chatSnapshotIncludesStructuredMemoryRetrievalEvents() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-memory-retrieval-event"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Recall the previous build decision")
    val task = handle.submittedTasks.single()
    val run = handle.submissions.single()
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = OpenCrayMemoryRetrievalEvent(
        runId = run.runId,
        taskId = task.id,
        turn = 0,
        toolName = "memory_search",
        operation = "search",
        query = "gradle wrapper repo root",
        queryTerms = listOf("gradle", "wrapper", "repo", "root"),
        resultCount = 1,
        corpusFileCount = 1,
        recordIds = listOf("memory-user"),
        paths = listOf("memory/2024-03-11.md"),
        lineRanges = listOf("5-8"),
        emittedAtEpochMs = 1_100L,
      ),
    )

    val runtimeActivity = hostRuntime.loadChatSnapshot()["runtimeActivity"] as Map<*, *>
    val firstEvent = (runtimeActivity["events"] as List<*>).single() as Map<*, *>

    assertEquals(activeSessionId, runtimeActivity["sessionId"])
    assertEquals("memory_retrieval", firstEvent["kind"])
    assertEquals("memory_search", firstEvent["toolName"])
    assertEquals("search", firstEvent["operation"])
    assertEquals("gradle wrapper repo root", firstEvent["query"])
    assertEquals(listOf("gradle", "wrapper", "repo", "root"), firstEvent["queryTerms"])
    assertEquals(1, firstEvent["resultCount"])
    assertEquals(1, firstEvent["corpusFileCount"])
    assertEquals(listOf("memory-user"), firstEvent["recordIds"])
    assertEquals(listOf("memory/2024-03-11.md"), firstEvent["paths"])
    assertEquals(listOf("5-8"), firstEvent["lineRanges"])
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

    assertEquals(listOf("proc-live"), runSnapshot["managedProcessIds"])
    assertEquals(1, runSnapshot["runningManagedProcessCount"])
    assertEquals(true, runSnapshot["hasLiveManagedProcesses"])
    assertEquals(true, runSnapshot["isTerminal"])
    assertEquals(true, runSnapshot["isActive"])
    assertEquals(submission["runId"], activeRun["runId"])
    assertEquals(true, activeRun["hasLiveManagedProcesses"])
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
    val replayCalls = mutableListOf<Map<String, Any?>>()
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      approvalApprovedReplayRecorder = { sessionId, taskId, runId, toolName, isHighRisk ->
        replayCalls += mapOf(
          "sessionId" to sessionId,
          "taskId" to taskId,
          "runId" to runId,
          "toolName" to toolName,
          "isHighRisk" to isHighRisk,
        )
      },
    )

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
        metadata = task.metadata + mapOf(
          "normalizedToolName" to "Bash",
        ),
      ),
    )

    hostRuntime.approveChatApproval(run["runId"] as String)

    val snapshot = hostRuntime.loadChatSnapshot()
    val pendingApprovals = snapshot["pendingApprovals"] as List<*>
    val runtimeActivity = snapshot["runtimeActivity"] as Map<*, *>
    val events = (runtimeActivity["events"] as List<*>).map { event -> event as Map<*, *> }
    val approvalResultEvent = events.last()
    val composerPlaceholder = snapshot["composerPlaceholder"]
    val messages = chatStore.loadState().activeSession.messages
      .filter { message -> message.role != ChatTranscriptRole.SYSTEM }

    assertEquals(listOf(task.id), handle.resumedTaskIds)
    assertTrue(pendingApprovals.isEmpty())
    assertEquals(
      listOf(
        mapOf(
          "sessionId" to activeSessionId,
          "taskId" to task.id,
          "runId" to (run["runId"] as String),
          "toolName" to "Bash",
          "isHighRisk" to true,
        ),
      ),
      replayCalls,
    )
    assertEquals(listOf("approval_wait", "approval_result"), events.map { it["kind"] })
    assertEquals("approved", approvalResultEvent["status"])
    assertEquals("Bash", approvalResultEvent["toolName"])
    assertEquals(true, approvalResultEvent["isHighRisk"])
    assertEquals("Approval granted. The agent is resuming.", approvalResultEvent["text"])
    assertEquals("Message OpenCray", composerPlaceholder)
    assertEquals(
      listOf(
        "Need approval",
        "Thinking",
        "Approval granted. The agent is resuming.",
      ),
      messages.map { it.text },
    )
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
    val replayCalls = mutableListOf<Map<String, Any?>>()
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      approvalReplayRecorder = { sessionId, taskId, runId, toolName, isHighRisk ->
        replayCalls += mapOf(
          "sessionId" to sessionId,
          "taskId" to taskId,
          "runId" to runId,
          "toolName" to toolName,
          "isHighRisk" to isHighRisk,
        )
      },
    )

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
        metadata = task.metadata + mapOf(
          "normalizedToolName" to "Write",
        ),
      ),
    )

    hostRuntime.rejectChatApproval(run["runId"] as String)

    val snapshot = hostRuntime.loadChatSnapshot()
    val pendingApprovals = snapshot["pendingApprovals"] as List<*>
    val runtimeActivity = snapshot["runtimeActivity"] as Map<*, *>
    val events = (runtimeActivity["events"] as List<*>).map { event -> event as Map<*, *> }
    val approvalResultEvent = events.last()
    val composerPlaceholder = snapshot["composerPlaceholder"]
    val messages = chatStore.loadState().activeSession.messages
      .filter { message -> message.role != ChatTranscriptRole.SYSTEM }

    assertEquals(listOf(task.id), handle.cancelledTaskIds)
    assertTrue(handle.resumedTaskIds.isEmpty())
    assertTrue(pendingApprovals.isEmpty())
    assertEquals(
      listOf(
        mapOf(
          "sessionId" to activeSessionId,
          "taskId" to task.id,
          "runId" to (run["runId"] as String),
          "toolName" to "Write",
          "isHighRisk" to false,
        ),
      ),
      replayCalls,
    )
    assertEquals(listOf("approval_wait", "approval_result"), events.map { it["kind"] })
    assertEquals("rejected", approvalResultEvent["status"])
    assertEquals("Write", approvalResultEvent["toolName"])
    assertEquals(false, approvalResultEvent["isHighRisk"])
    assertEquals("Approval rejected. The requested action was not run.", approvalResultEvent["text"])
    assertEquals("Tell OpenCray differently", composerPlaceholder)
    assertEquals(
      listOf(
        "Need approval",
        "Approval is required before Write can run.",
        "Approval rejected. The requested action was not run.",
      ),
      messages.map { it.text },
    )
  }

  @Test
  fun rejectChatApprovalRetainsRunAfterCancellationSettles() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-approval-retained"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
      resumeResult = true,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
    )

    val run = hostRuntime.submitChatMessage("Need approval")!!
    val task = handle.submittedTasks.single()
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = OpenCrayToolResultEvent(
        runId = run["runId"] as String,
        taskId = task.id,
        turn = 0,
        call = AgentToolCall(toolName = "Read"),
        result = AgentToolResult(
          toolName = "Read",
          status = AgentToolResultStatus.SUCCESS,
          content = "Read README.md:1-10",
        ),
        emittedAtEpochMs = 1_000L,
      ),
    )
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
          "normalizedToolName" to "Write",
        ),
      ),
    )

    hostRuntime.rejectChatApproval(run["runId"] as String)

    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.CANCELLED,
        errorCode = "USER_CANCELLED",
        errorMessage = "Cancelled after approval rejection.",
        startedAtEpochMs = 1_002L,
        finishedAtEpochMs = 1_003L,
        metadata = task.metadata + mapOf(
          "normalizedToolName" to "Write",
        ),
      ),
    )

    val runtimeActivity = hostRuntime.loadChatRuntimeSnapshot()
    val activeRuns = runtimeActivity["activeRuns"] as List<*>
    val retainedRuns = runtimeActivity["retainedRuns"] as List<*>
    val retainedRun = retainedRuns.single() as Map<*, *>
    val lastEvent = retainedRun["lastEvent"] as Map<*, *>
    val composerPlaceholder = hostRuntime.loadChatSnapshot()["composerPlaceholder"]

    assertTrue(activeRuns.isEmpty())
    assertEquals(1, retainedRuns.size)
    assertEquals(run["runId"], retainedRun["runId"])
    assertEquals(task.id, retainedRun["taskId"])
    assertEquals(false, retainedRun["isActive"])
    assertEquals(true, retainedRun["isTerminal"])
    assertEquals("approval_result", lastEvent["kind"])
    assertEquals("rejected", lastEvent["status"])
    assertEquals("Write", lastEvent["toolName"])
    assertEquals(false, lastEvent["isHighRisk"])
    assertEquals("Tell OpenCray differently", composerPlaceholder)
  }

  @Test
  fun cancelChatRunCancelsTaskAndRecordsReplayObservation() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-run-cancel"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val replayCalls = mutableListOf<Map<String, String?>>()
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      runCancellationReplayRecorder = { sessionId, taskId, runId, toolName ->
        replayCalls += mapOf(
          "sessionId" to sessionId,
          "taskId" to taskId,
          "runId" to runId,
          "toolName" to toolName,
        )
      },
    )

    val run = hostRuntime.submitChatMessage("Cancel this run")!!

    hostRuntime.cancelChatRun(run["runId"] as String)

    val runtimeActivity = hostRuntime.loadChatSnapshot()["runtimeActivity"] as Map<*, *>
    val cancelledEvent = ((runtimeActivity["events"] as List<*>).single()) as Map<*, *>

    assertEquals(listOf(handle.submissions.single().taskId), handle.cancelledTaskIds)
    assertEquals(
      listOf(
        mapOf(
          "sessionId" to activeSessionId,
          "taskId" to handle.submissions.single().taskId,
          "runId" to (run["runId"] as String),
          "toolName" to null,
        ),
      ),
      replayCalls,
    )
    assertEquals("cancelled", cancelledEvent["kind"])
    assertEquals(run["runId"], cancelledEvent["runId"])
    assertEquals(handle.submissions.single().taskId, cancelledEvent["taskId"])
    assertEquals("user_cancelled", cancelledEvent["status"])
    assertEquals(
      "Run cancelled. The agent is waiting for your next instruction.",
      cancelledEvent["text"],
    )
  }

  @Test
  fun chatRuntimeSnapshotParsesReplayedApprovalAndCancellationEvents() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-replayed-approval-cancel"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val transcriptMessages = listOf(
      RuntimeConversationMessage(
        role = RuntimeConversationRole.TOOL,
        content = "approval_approved task_id=task-approved run_id=run-approved tool_name=Write outcome=user_approved executed=false next_step=agent_resumed",
      ),
      RuntimeConversationMessage(
        role = RuntimeConversationRole.TOOL,
        content = "approval_rejected task_id=task-rejected run_id=run-rejected tool_name=Bash risk=high_risk outcome=user_rejected executed=false next_step=await_user_instruction",
      ),
      RuntimeConversationMessage(
        role = RuntimeConversationRole.TOOL,
        content = "run_cancelled task_id=task-cancelled run_id=run-cancelled tool_name=Read outcome=user_cancelled executed=false next_step=await_user_instruction",
      ),
    )
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = RecordingRuntimeManager(),
      transcriptMessagesProvider = { sessionId ->
        if (sessionId == activeSessionId) {
          transcriptMessages
        } else {
          emptyList()
        }
      },
    )

    val runtimeActivity = hostRuntime.loadChatRuntimeSnapshot()
    val events = (runtimeActivity["events"] as List<*>).map { event -> event as Map<*, *> }
    val approved = events[0]
    val rejected = events[1]
    val cancelled = events[2]

    assertEquals(activeSessionId, runtimeActivity["sessionId"])
    assertEquals(listOf("approval_result", "approval_result", "cancelled"), events.map { it["kind"] })
    assertEquals("approved", approved["status"])
    assertEquals("Write", approved["toolName"])
    assertEquals(false, approved["isHighRisk"])
    assertEquals("Approval granted. The agent is resuming.", approved["text"])
    assertEquals("rejected", rejected["status"])
    assertEquals("Bash", rejected["toolName"])
    assertEquals(true, rejected["isHighRisk"])
    assertEquals("Approval rejected. The requested action was not run.", rejected["text"])
    assertEquals("user_cancelled", cancelled["status"])
    assertEquals("Read", cancelled["toolName"])
    assertEquals(
      "Cancelled the pending Read request. The agent is waiting for your next instruction.",
      cancelled["text"],
    )
  }

  @Test
  fun taskFailureRepairsTerminalReplayObservation() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-terminal-repair"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val repairCalls = mutableListOf<Pair<String, List<AgentRunSnapshot>>>()
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      terminalReplayRepairer = { sessionId, runs ->
        repairCalls += sessionId to runs
      },
    )

    hostRuntime.submitChatMessage("Will fail")
    val task = handle.submittedTasks.single()
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.FAILED,
        errorCode = "RUNTIME_EXCEPTION",
        errorMessage = "boom",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata,
      ),
    )

    assertEquals(activeSessionId, repairCalls.last().first)
    assertEquals(task.id, repairCalls.last().second.single().taskId)
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
  fun saveCustomLlmProviderReturnsFacadePayloadForFlutterBridge() {
    val facade = RecordingLlmConfigFacade()
    val hostRuntime = hostRuntime(
      chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-save-custom-provider")),
      runtimeManager = RecordingRuntimeManager(),
      llmConfigFacade = facade,
    )

    val payload = hostRuntime.saveCustomLlmProvider(
      selectedProviderOptionId = "custom",
      protocol = LlmProviderProtocols.ANTHROPIC,
      providerName = "Acme",
      providerNotes = "Regional fallback",
      baseUrl = "https://api.acme.example/v1",
      apiKey = "secret",
      model = "claude-3-7-sonnet",
      reasoningEffort = "high",
      systemPrompt = "Be concise.",
    )

    assertEquals("custom-saved", payload["selectedProviderOptionId"])
    assertEquals("custom", payload["providerId"])
    assertEquals("Regional fallback", payload["providerNotes"])
    assertEquals("Acme", facade.lastSavedCustomRequest?.providerName)
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
  fun networkSearchConfigRoundTripsForFlutterBridge() {
    val facade = LocalNetworkSearchConfigFacade.createForTest(
      WebSearchSettingsStore(InMemoryWebSearchSettingsKeyValueStore()),
    )
    val hostRuntime = hostRuntime(
      chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-network-search")),
      runtimeManager = RecordingRuntimeManager(),
      networkSearchConfigFacade = facade,
    )

    val initialPayload = hostRuntime.loadNetworkSearchConfig()
    assertEquals("Network & Search", initialPayload["title"])
    assertEquals(0, (initialPayload["slots"] as List<*>).size)

    val savedPayload = hostRuntime.saveNetworkSearchConfig(
      slots = listOf(
        mapOf(
          "id" to "slot-primary",
          "providerId" to "brave",
          "label" to "Primary Brave",
          "apiKey" to "brave-secret",
          "enabled" to true,
        ),
        mapOf(
          "id" to "slot-backup",
          "providerId" to "tavily",
          "label" to "Backup Tavily",
          "apiKey" to "",
          "enabled" to false,
        ),
      ),
    )

    val savedSlots = (savedPayload["slots"] as List<*>).map { it as Map<*, *> }
    assertEquals(2, savedSlots.size)
    assertEquals("brave", savedSlots[0]["providerId"])
    assertEquals("Primary Brave", savedSlots[0]["label"])
    assertEquals(true, savedSlots[0]["enabled"])
    assertEquals("tavily", savedSlots[1]["providerId"])
    assertEquals(false, savedSlots[1]["enabled"])
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

  @Test
  fun loadMemoryDebugSnapshotReturnsStructuredStoreRecords() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-memory-debug"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val personalizationStore = PersonalizationLocalStore(
      temporaryFolder.newFolder("personalization-memory-debug"),
    )
    personalizationStore.upsertMemoryRecord(
      MemoryRecord(
        id = "memory-old",
        content = "Old workspace preference.",
        createdAtEpochMs = 1_000L,
        updatedAtEpochMs = 1_000L,
        tags = listOf("kind:user_preference", "scope:workspace"),
        extensions = mapOf(
          MemoryRecordExtensionKeys.KIND to "user_preference",
          MemoryRecordExtensionKeys.SCOPE to "workspace",
          MemoryRecordExtensionKeys.STATUS to "active",
          MemoryRecordExtensionKeys.WORKSPACE_ID to "workspace=unavailable",
          MemoryRecordExtensionKeys.SOURCE_SESSION_ID to "session-old",
          MemoryRecordExtensionKeys.TTL_MS to "1000",
          MemoryRecordExtensionKeys.LAST_CONFIRMED_AT_EPOCH_MS to "1000",
        ),
      ),
    )
    personalizationStore.upsertMemoryRecord(
      MemoryRecord(
        id = "memory-user",
        content = "Call the agent Xiao Bai.",
        createdAtEpochMs = 2_000L,
        updatedAtEpochMs = 2_100L,
        tags = listOf("kind:user_preference", "scope:user"),
        extensions = mapOf(
          MemoryRecordExtensionKeys.KIND to "user_preference",
          MemoryRecordExtensionKeys.SCOPE to "user",
          MemoryRecordExtensionKeys.STATUS to "active",
          MemoryRecordExtensionKeys.SOURCE to "user_input",
          MemoryRecordExtensionKeys.SOURCE_SESSION_ID to sessionId,
          MemoryRecordExtensionKeys.PREFERENCE_KEY to MemoryPreferenceKeys.AGENT_DISPLAY_NAME,
          MemoryRecordExtensionKeys.PREFERENCE_VALUE to "Xiao Bai",
          MemoryRecordExtensionKeys.PREFERENCE_TEMPORALITY to "durable",
          MemorySoulExtensionKeys.DISPLAY_NAME to "Xiao Bai",
        ),
      ),
    )

    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = RecordingRuntimeManager(),
      personalizationLocalStore = personalizationStore,
    )

    val payload = hostRuntime.loadMemoryDebugSnapshot()

    assertEquals(sessionId, payload["sessionId"])
    val records = payload["records"] as List<*>
    val firstRecord = records[0] as Map<*, *>
    val secondRecord = records[1] as Map<*, *>
    assertEquals("memory-user", firstRecord["id"])
    assertEquals("agent_display_name", firstRecord["preferenceKey"])
    assertEquals("Xiao Bai", firstRecord["preferenceValue"])
    assertEquals(false, firstRecord["isExpired"])
    assertEquals("memory-old", secondRecord["id"])
    assertEquals(true, secondRecord["isExpired"])
  }

  @Test
  fun loadMemoryDebugLinksSnapshotReturnsSourceRecallRetrievalAndMaintenanceLinks() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-memory-links"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val personalizationStore = PersonalizationLocalStore(
      temporaryFolder.newFolder("personalization-memory-links"),
    )
    val runtimeManager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(sessionId = sessionId)
    runtimeManager.putHandle(handle)
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = runtimeManager,
      personalizationLocalStore = personalizationStore,
    )

    val sourceSubmission = handle.submitPrompt(
      userText = "Remember my preferred agent name.",
      pendingMessageId = "pending-memory-source",
      visibleThroughMessageId = "pending-memory-source",
      policyDecision = PolicyDecision(
        outcome = PolicyDecisionOutcome.ALLOW,
        reasonCode = "TEST_ALLOW",
      ),
      metadata = emptyMap(),
    )
    val sourceTask = handle.submittedTasks.last()
    handle.recordResult(
      task = sourceTask,
      result = ExecutionResult(
        taskId = sourceTask.id,
        status = ExecutionStatus.SUCCESS,
        stdout = "",
        startedAtEpochMs = 2_100L,
        finishedAtEpochMs = 2_200L,
        metadata = mapOf("responseFormat" to "json_final"),
      ),
    )
    personalizationStore.upsertMemoryRecord(
      MemoryRecord(
        id = "memory-user",
        content = "Call the agent Xiao Bai.",
        createdAtEpochMs = 2_000L,
        updatedAtEpochMs = 2_200L,
        tags = listOf("kind:user_preference", "scope:user"),
        extensions = mapOf(
          MemoryRecordExtensionKeys.KIND to "user_preference",
          MemoryRecordExtensionKeys.SCOPE to "user",
          MemoryRecordExtensionKeys.STATUS to "active",
          MemoryRecordExtensionKeys.SOURCE to "user_input",
          MemoryRecordExtensionKeys.SOURCE_SESSION_ID to sessionId,
          MemoryRecordExtensionKeys.SOURCE_TASK_ID to sourceTask.id,
          MemoryRecordExtensionKeys.PREFERENCE_KEY to MemoryPreferenceKeys.AGENT_DISPLAY_NAME,
          MemoryRecordExtensionKeys.PREFERENCE_VALUE to "Xiao Bai",
          MemoryRecordExtensionKeys.PREFERENCE_TEMPORALITY to "durable",
          MemorySoulExtensionKeys.DISPLAY_NAME to "Xiao Bai",
        ),
      ),
    )
    runtimeManager.emitRunEvent(
      sessionId = sessionId,
      task = sourceTask,
      event = OpenCrayMemoryWriteEvent(
        runId = sourceSubmission.runId,
        taskId = sourceTask.id,
        writtenRecordIds = listOf("memory-user"),
        writtenKinds = listOf("user_preference"),
        emittedAtEpochMs = 2_200L,
      ),
    )

    val recallSubmission = handle.submitPrompt(
      userText = "What name should I use for the agent?",
      pendingMessageId = "pending-memory-recall",
      visibleThroughMessageId = "pending-memory-recall",
      policyDecision = PolicyDecision(
        outcome = PolicyDecisionOutcome.ALLOW,
        reasonCode = "TEST_ALLOW",
      ),
      metadata = emptyMap(),
    )
    val recallTask = handle.submittedTasks.last()
    handle.recordResult(
      task = recallTask,
      result = ExecutionResult(
        taskId = recallTask.id,
        status = ExecutionStatus.SUCCESS,
        stdout = "",
        startedAtEpochMs = 2_400L,
        finishedAtEpochMs = 2_500L,
        metadata = mapOf(
          "responseFormat" to "json_final",
          "contextMemorySelectedSummary" to "memory-user@420[chinese|name]",
          "contextMemoryFlushWrittenRecordIds" to "memory-user",
        ),
      ),
    )
    runtimeManager.emitRunEvent(
      sessionId = sessionId,
      task = recallTask,
      event = OpenCrayMemoryRetrievalEvent(
        runId = recallSubmission.runId,
        taskId = recallTask.id,
        turn = 0,
        toolName = "memory_search",
        operation = "search",
        query = "what name should I call the agent",
        queryTerms = listOf("name", "agent"),
        resultCount = 1,
        corpusFileCount = 1,
        recordIds = listOf("memory-user"),
        paths = listOf("memory/2024-03-11.md"),
        lineRanges = listOf("5-8"),
        emittedAtEpochMs = 2_400L,
      ),
    )

    val payload = hostRuntime.loadMemoryDebugLinksSnapshot()

    assertEquals(sessionId, payload["sessionId"])
    val records = payload["records"] as List<*>
    val userLinks = records
      .map { entry -> entry as Map<*, *> }
      .first { entry -> entry["recordId"] == "memory-user" }
    assertEquals(sessionId, userLinks["sourceSessionId"])
    assertEquals(sourceTask.id, userLinks["sourceTaskId"])
    val sourceRun = userLinks["sourceRun"] as Map<*, *>
    assertEquals(sourceSubmission.runId, sourceRun["runId"])
    val promptRecall = (userLinks["promptRecalls"] as List<*>)
      .single() as Map<*, *>
    assertEquals(420, promptRecall["score"])
    val retrieval = (userLinks["toolRetrievals"] as List<*>)
      .single() as Map<*, *>
    assertEquals("memory_search", retrieval["toolName"])
    val maintenanceActions = (userLinks["maintenanceActions"] as List<*>)
      .map { entry -> entry as Map<*, *> }
    assertTrue(maintenanceActions.any { entry -> entry["action"] == "written" })
    assertTrue(maintenanceActions.any { entry -> entry["action"] == "flush_written" })
  }

  @Test
  fun loadSoulDebugSnapshotReturnsStoredEffectiveSoulAndFieldSources() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-soul-debug"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val personalizationStore = PersonalizationLocalStore(
      temporaryFolder.newFolder("personalization-soul-debug"),
    )
    val workspaceRoot = temporaryFolder.newFolder("workspace-soul-debug").toPath()
    WorkspaceSoulProfileStore().saveSoulProfile(
      workspaceRoot,
      WorkspaceSoulProfile(
        presetName = "STEADY",
        customLabel = "Night Shift",
        customGuidance = "Keep replies calm and concrete.",
      ),
    )
    personalizationStore.upsertMemoryRecord(
      MemoryRecord(
        id = "memory-user",
        content = "Call the agent Xiao Bai.",
        createdAtEpochMs = 2_000L,
        updatedAtEpochMs = 2_100L,
        tags = listOf("kind:user_preference", "scope:user"),
        extensions = mapOf(
          MemoryRecordExtensionKeys.KIND to "user_preference",
          MemoryRecordExtensionKeys.SCOPE to "user",
          MemoryRecordExtensionKeys.STATUS to "active",
          MemoryRecordExtensionKeys.SOURCE_SESSION_ID to sessionId,
          MemoryRecordExtensionKeys.PREFERENCE_KEY to MemoryPreferenceKeys.AGENT_DISPLAY_NAME,
          MemoryRecordExtensionKeys.PREFERENCE_VALUE to "Xiao Bai",
          MemorySoulExtensionKeys.DISPLAY_NAME to "Xiao Bai",
        ),
      ),
    )
    personalizationStore.upsertMemoryRecord(
      MemoryRecord(
        id = "memory-style",
        content = "Keep the tone warmer.",
        createdAtEpochMs = 2_200L,
        updatedAtEpochMs = 2_250L,
        tags = listOf("kind:user_preference", "scope:session"),
        extensions = mapOf(
          MemoryRecordExtensionKeys.KIND to "user_preference",
          MemoryRecordExtensionKeys.SCOPE to "session",
          MemoryRecordExtensionKeys.STATUS to "active",
          MemoryRecordExtensionKeys.SOURCE_SESSION_ID to sessionId,
          MemoryRecordExtensionKeys.PREFERENCE_KEY to MemoryPreferenceKeys.AGENT_STYLE_PROFILE,
          MemoryRecordExtensionKeys.PREFERENCE_VALUE to "warm",
          MemorySoulExtensionKeys.TONE to "warm",
          MemorySoulExtensionKeys.VOICE to "warm and gentle",
          MemorySoulExtensionKeys.USER_RELATIONSHIP_STYLE to "supportive",
        ),
      ),
    )
    personalizationStore.upsertMemoryRecord(
      MemoryRecord(
        id = "interaction-state",
        content = "internal interaction preference snapshot",
        createdAtEpochMs = 2_300L,
        updatedAtEpochMs = 2_300L,
        extensions = mapOf(
          MemoryRecordExtensionKeys.SCOPE to "user",
          MemoryRecordExtensionKeys.STATUS to "active",
          MemoryRecordExtensionKeys.LAST_CONFIRMED_AT_EPOCH_MS to "2300",
          SoulMemoryExtensionKeys.OBJECT_TYPE to SoulMemoryObjectTypes.INTERACTION_PREFERENCE_STATE,
          SoulMemoryExtensionKeys.OBJECT_SCHEMA_VERSION to "1",
          SoulMemoryExtensionKeys.OBJECT_PAYLOAD_JSON to Json.encodeToString(
            InteractionPreferenceState.serializer(),
            InteractionPreferenceState(
              warmth = PreferenceAxisState(offset = 1, higherSupport = 2),
              formality = PreferenceAxisState(offset = -1, lowerSupport = 2),
              initiative = PreferenceAxisState(offset = 1, higherSupport = 2),
              playfulness = PreferenceAxisState(offset = 1, higherSupport = 2),
              reassurance = PreferenceAxisState(offset = 1, higherSupport = 2),
              addressStyle = PreferredAddressState(
                selectedStyle = PreferredAddressStyle.FRIENDLY,
                friendlySupport = 2,
              ),
              preferredNaming = "A-Cheng",
              preferredNamingSupport = 2,
            ),
          ),
        ),
      ),
    )
    personalizationStore.upsertMemoryRecord(
      MemoryRecord(
        id = "relationship-state",
        content = "internal relationship snapshot",
        createdAtEpochMs = 2_400L,
        updatedAtEpochMs = 2_400L,
        extensions = mapOf(
          MemoryRecordExtensionKeys.SCOPE to "user",
          MemoryRecordExtensionKeys.STATUS to "active",
          MemoryRecordExtensionKeys.SOURCE_SESSION_ID to sessionId,
          MemoryRecordExtensionKeys.LAST_CONFIRMED_AT_EPOCH_MS to "2400",
          SoulMemoryExtensionKeys.OBJECT_TYPE to SoulMemoryObjectTypes.RELATIONSHIP_STATE,
          SoulMemoryExtensionKeys.OBJECT_SCHEMA_VERSION to "1",
          SoulMemoryExtensionKeys.OBJECT_PAYLOAD_JSON to Json.encodeToString(
            RelationshipState.serializer(),
            RelationshipState(
              familiarity = 66,
              trust = 74,
              safety = 76,
              intimacyPermission = 61,
              playfulnessPermission = 44,
              affectionTendency = 34,
              reciprocity = 49,
            ),
          ),
        ),
      ),
    )

    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = RecordingRuntimeManager(),
      personalizationLocalStore = personalizationStore,
      workspaceRootProvider = { workspaceRoot },
    )

    val payload = hostRuntime.loadSoulDebugSnapshot()

    val storedSoul = payload["storedSoul"] as Map<*, *>
    val effectiveSoul = payload["effectiveSoul"] as Map<*, *>
    val interactionPreferenceDebug = payload["interactionPreferenceDebug"] as Map<*, *>
    val relationshipStateDebug = payload["relationshipStateDebug"] as Map<*, *>
    val fieldSources = payload["fieldSources"] as List<*>

    assertEquals("STEADY", storedSoul["presetName"])
    assertEquals("Xiao Bai", effectiveSoul["displayName"])
    assertEquals("warm", effectiveSoul["tone"])
    assertEquals("warm and gentle", effectiveSoul["voice"])
    assertEquals("1", effectiveSoul["warmthPreferenceOffset"])
    assertEquals("-1", effectiveSoul["formalityPreferenceOffset"])
    assertEquals("1", effectiveSoul["initiativePreferenceOffset"])
    assertEquals("1", effectiveSoul["playfulnessPreferenceOffset"])
    assertEquals("1", effectiveSoul["reassurancePreferenceOffset"])
    assertEquals("true", effectiveSoul["supportiveReassuranceAllowed"])
    assertEquals("true", effectiveSoul["proactiveRelationalCheckInAllowed"])
    assertEquals("true", effectiveSoul["lightPlayfulnessAllowed"])
    assertEquals("true", effectiveSoul["playfulTeasingAllowed"])
    assertEquals("user", interactionPreferenceDebug["scope"])
    assertEquals("A-Cheng", interactionPreferenceDebug["preferredNaming"])
    assertEquals("friendly", interactionPreferenceDebug["preferredAddressStyle"])
    assertEquals("user", relationshipStateDebug["scope"])
    assertEquals("intimate", relationshipStateDebug["derivedAddressStyle"])
    assertEquals(false, relationshipStateDebug["recentNegativeGuardActive"])
    assertEquals(true, relationshipStateDebug["supportiveReassuranceAllowed"])
    assertEquals(true, relationshipStateDebug["proactiveRelationalCheckInAllowed"])
    assertEquals(true, relationshipStateDebug["lightPlayfulnessAllowed"])
    assertEquals(true, relationshipStateDebug["playfulTeasingAllowed"])
    assertEquals(true, relationshipStateDebug["highIntimacyBehaviorAllowed"])
    val mappedFieldSources = fieldSources.map { item -> item as Map<*, *> }
    fun fieldSource(field: String): Map<*, *> =
      mappedFieldSources.first { source -> source["field"] == field }

    val displayNameSource = fieldSource("displayName")
    val preferredNamingSource = fieldSource("preferredNaming")
    val warmthOffsetSource = fieldSource("warmthPreferenceOffset")
    val playfulnessOffsetSource = fieldSource("playfulnessPreferenceOffset")
    val reassuranceOffsetSource = fieldSource("reassurancePreferenceOffset")
    val supportiveReassuranceSource = fieldSource("supportiveReassuranceAllowed")
    val proactiveCheckInSource = fieldSource("proactiveRelationalCheckInAllowed")
    val lightPlayfulnessSource = fieldSource("lightPlayfulnessAllowed")
    val playfulTeasingSource = fieldSource("playfulTeasingAllowed")
    val highIntimacySource = fieldSource("highIntimacyBehaviorAllowed")
    assertEquals("memory_overlay", displayNameSource["sourceType"])
    assertEquals("memory-user", displayNameSource["recordId"])
    assertEquals("interaction_preference", preferredNamingSource["sourceType"])
    assertEquals("interaction-state", preferredNamingSource["recordId"])
    assertEquals("interaction_preference", warmthOffsetSource["sourceType"])
    assertEquals("interaction-state", warmthOffsetSource["recordId"])
    assertEquals("interaction_preference", playfulnessOffsetSource["sourceType"])
    assertEquals("interaction-state", playfulnessOffsetSource["recordId"])
    assertEquals("interaction_preference", reassuranceOffsetSource["sourceType"])
    assertEquals("interaction-state", reassuranceOffsetSource["recordId"])
    assertEquals("relationship_state", supportiveReassuranceSource["sourceType"])
    assertEquals("relationship-state", supportiveReassuranceSource["recordId"])
    assertEquals("relationship_state", proactiveCheckInSource["sourceType"])
    assertEquals("relationship-state", proactiveCheckInSource["recordId"])
    assertEquals("relationship_state", lightPlayfulnessSource["sourceType"])
    assertEquals("relationship-state", lightPlayfulnessSource["recordId"])
    assertEquals("relationship_state", playfulTeasingSource["sourceType"])
    assertEquals("relationship-state", playfulTeasingSource["recordId"])
    assertEquals("relationship_state", highIntimacySource["sourceType"])
    assertEquals("relationship-state", highIntimacySource["recordId"])
  }

  @Test
  fun loadSkillsSnapshotQueryUsesSessionPipelineAndParsesToolResult() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-skills-query"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val skillsFacade = TestSkillsFacade().apply {
      snapshot = SkillsSnapshot(
        installedSkills = emptyList(),
        installSources = listOf(
          InstallSourceSnapshot(
            id = "github-url",
            title = "GitHub URL",
            subtitle = "Enter a source ref.",
            actionLabel = "Inspect",
            isAvailable = true,
          ),
        ),
        suggestedSkills = emptyList(),
      )
    }
    val handle = RecordingSessionHandle(sessionId = sessionId).apply {
      queuedToolCompletion = QueuedToolCompletion(
        toolName = "SkillsFind",
        content = "find-skills\tremote\tinstall_ref=roin-orca/skills@find-skills\tsource=roin-orca/skills\tinstalls=42",
      )
    }
    val runtimeManager = RecordingRuntimeManager().apply {
      putHandle(handle)
    }
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = runtimeManager,
      skillsFacade = skillsFacade,
    )

    val payload = hostRuntime.loadSkillsSnapshot(query = "find")

    assertEquals("", skillsFacade.lastLoadedQuery)
    assertEquals(AgentTaskType.TOOL_CALL, handle.submittedTasks.single().type)
    assertTrue(handle.submittedTasks.single().input.contains("\"tool_name\":\"SkillsFind\""))
    assertTrue(handle.submittedTasks.single().input.contains("\"query\":\"find\""))
    val suggestedSkills = payload["suggestedSkills"] as List<*>
    val firstResult = suggestedSkills.first() as Map<*, *>
    assertEquals("roin-orca/skills@find-skills", firstResult["sourceRef"])
    assertEquals("skills.sh", firstResult["sourceLabel"])
  }

  @Test
  fun installSkillSourceUsesSessionPipelineAndReturnsInstalledSkillMessage() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-install-source"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val handle = RecordingSessionHandle(sessionId = sessionId).apply {
      queuedToolCompletion = QueuedToolCompletion(
        toolName = "SkillsAdd",
        content = "Installed skill 'find-skills' from remote source 'roin-orca/skills@find-skills'.",
        metadata = mapOf("skillId" to "find-skills"),
      )
    }
    val runtimeManager = RecordingRuntimeManager().apply {
      putHandle(handle)
    }
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = runtimeManager,
    )

    val message = hostRuntime.installSkillSource("roin-orca/skills@find-skills")

    assertTrue(handle.submittedTasks.any { task ->
      task.type == AgentTaskType.TOOL_CALL &&
        task.input.contains("\"tool_name\":\"SkillsAdd\"") &&
        task.input.contains("\"sourceRef\":\"roin-orca/skills@find-skills\"")
    })
    assertEquals("Installed find-skills.", message)
  }

  @Test
  fun installSkillSourcePassesSelectedSkillNameThroughSessionPipeline() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-install-source-selected"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val handle = RecordingSessionHandle(sessionId = sessionId).apply {
      queuedToolCompletion = QueuedToolCompletion(
        toolName = "SkillsAdd",
        content = "Installed skill 'review-skills' from remote source 'roin-orca/skills'.",
        metadata = mapOf("skillId" to "review-skills"),
      )
    }
    val runtimeManager = RecordingRuntimeManager().apply {
      putHandle(handle)
    }
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = runtimeManager,
    )

    val message = hostRuntime.installSkillSource(
      sourceRef = "roin-orca/skills",
      selectedSkillName = "review-skills",
    )

    assertTrue(handle.submittedTasks.any { task ->
      task.type == AgentTaskType.TOOL_CALL &&
        task.input.contains("\"tool_name\":\"SkillsAdd\"") &&
        task.input.contains("\"sourceRef\":\"roin-orca/skills\"") &&
        task.input.contains("\"skill\":\"review-skills\"")
    })
    assertEquals("Installed review-skills.", message)
  }

  @Test
  fun installSkillSourceBatchPassesSelectedSkillNamesThroughSessionPipeline() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-install-source-batch"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val handle = RecordingSessionHandle(sessionId = sessionId).apply {
      queuedToolCompletion = QueuedToolCompletion(
        toolName = "SkillsAddBatch",
        content = """
          batch_install	remote_github	source_ref=roin-orca/skills	requested_count=2	installed_count=2	failed_count=0
          installed	find-skills	requested=find-skills	relative_path=skills/find-skills/SKILL.md
          installed	review-skills	requested=review-skills	relative_path=skills/review-skills/SKILL.md
        """.trimIndent(),
        metadata = mapOf(
          "sourceType" to "remote_github",
          "sourceRef" to "roin-orca/skills",
          "requestedCount" to "2",
          "installedCount" to "2",
          "failedCount" to "0",
        ),
      )
    }
    val runtimeManager = RecordingRuntimeManager().apply {
      putHandle(handle)
    }
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = runtimeManager,
    )

    val message = hostRuntime.installSkillSourceBatch(
      sourceRef = "roin-orca/skills",
      selectedSkillNames = listOf("find-skills", "review-skills"),
    )

    assertTrue(handle.submittedTasks.any { task ->
      task.type == AgentTaskType.TOOL_CALL &&
        task.input.contains("\"tool_name\":\"SkillsAddBatch\"") &&
        task.input.contains("\"sourceRef\":\"roin-orca/skills\"") &&
        task.input.contains("\"skills\":[\"find-skills\",\"review-skills\"]")
    })
    assertEquals("Installed 2 skills.", message)
  }

  @Test
  fun inspectSkillSourceUsesSessionPipelineAndParsesToolResult() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-inspect-source"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val handle = RecordingSessionHandle(sessionId = sessionId).apply {
      queuedToolCompletion = QueuedToolCompletion(
        toolName = "SkillsInspect",
        content = """
          inspection	remote_github	source_ref=roin-orca/skills	source_path=https://github.com/roin-orca/skills	resolved_revision=main	resolved_commit=deadbeef	candidate_count=2
          candidate	find-skills	description=Discover skills	relative_path=skills/find-skills/SKILL.md
          candidate	review-skills	description=Review changes	relative_path=skills/review-skills/SKILL.md
        """.trimIndent(),
        metadata = mapOf(
          "sourceType" to "remote_github",
          "sourceRef" to "roin-orca/skills",
          "candidateCount" to "2",
        ),
      )
    }
    val runtimeManager = RecordingRuntimeManager().apply {
      putHandle(handle)
    }
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = runtimeManager,
    )

    val payload = hostRuntime.inspectSkillSource("roin-orca/skills")

    assertTrue(handle.submittedTasks.any { task ->
      task.type == AgentTaskType.TOOL_CALL &&
        task.input.contains("\"tool_name\":\"SkillsInspect\"") &&
        task.input.contains("\"sourceRef\":\"roin-orca/skills\"")
    })
    assertEquals("remote_github", payload["sourceType"])
    assertEquals("roin-orca/skills", payload["sourceRef"])
    val candidates = payload["candidates"] as List<*>
    assertEquals(2, candidates.size)
    assertEquals("find-skills", (candidates[0] as Map<*, *>)["name"])
    assertEquals("review-skills", (candidates[1] as Map<*, *>)["name"])
  }

  @Test
  fun deleteInstalledSkillUsesSessionPipelineAndReturnsRemovedSkillMessage() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-delete-skill"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val handle = RecordingSessionHandle(sessionId = sessionId).apply {
      queuedToolCompletion = QueuedToolCompletion(
        toolName = "SkillsRemove",
        content = "Removed skill 'find-skills' from the host-managed skills directory.",
        metadata = mapOf("skillId" to "find-skills"),
      )
    }
    val runtimeManager = RecordingRuntimeManager().apply {
      putHandle(handle)
    }
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = runtimeManager,
    )

    val message = hostRuntime.deleteInstalledSkill("find-skills")

    assertTrue(handle.submittedTasks.any { task ->
      task.type == AgentTaskType.TOOL_CALL &&
        task.input.contains("\"tool_name\":\"SkillsRemove\"") &&
        task.input.contains("\"skillId\":\"find-skills\"")
    })
    assertEquals("Removed find-skills.", message)
  }

  @Test
  fun loadSkillsSnapshotWithoutQueryUsesSkillsListAndSkillsFindPipelines() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-skills-default"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val skillsFacade = TestSkillsFacade().apply {
      snapshot = SkillsSnapshot(
        installedSkills = listOf(
          InstalledSkillSnapshot(
            id = "find-skills",
            name = "find-skills",
            description = "Fallback description",
            isEnabled = false,
            sourceDirectoryPath = "/managed/find-skills",
            canDelete = true,
          ),
        ),
        installSources = listOf(
          InstallSourceSnapshot(
            id = "github-url",
            title = "GitHub URL",
            subtitle = "Enter a source ref.",
            actionLabel = "Inspect",
            isAvailable = true,
          ),
        ),
        suggestedSkills = emptyList(),
      )
    }
    val handle = RecordingSessionHandle(sessionId = sessionId).apply {
      queuedToolCompletions += QueuedToolCompletion(
        toolName = "SkillsList",
        content = "find-skills\tgithub\tupdated_at=123\tdescription=Managed description",
      )
      queuedToolCompletions += QueuedToolCompletion(
        toolName = "SkillsFind",
        content = "remote-skill\tremote\tinstall_ref=acme/skills@remote-skill\tsource=acme/skills\tinstalls=7",
      )
    }
    val runtimeManager = RecordingRuntimeManager().apply {
      putHandle(handle)
    }
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = runtimeManager,
      skillsFacade = skillsFacade,
    )

    val payload = hostRuntime.loadSkillsSnapshot()

    assertEquals(listOf("SkillsList", "SkillsFind"), handle.submittedTasks.map { task ->
      TOOL_NAME_REGEX.find(task.input)?.groupValues?.getOrNull(1).orEmpty()
    })
    val installedSkills = payload["installedSkills"] as List<*>
    val installed = installedSkills.single() as Map<*, *>
    assertEquals("find-skills", installed["id"])
    assertEquals("Managed description", installed["description"])
    assertEquals(false, installed["isEnabled"])
    assertEquals("/managed/find-skills", installed["sourceDirectoryPath"])
    val suggestedSkills = payload["suggestedSkills"] as List<*>
    val suggested = suggestedSkills.single() as Map<*, *>
    assertEquals("acme/skills@remote-skill", suggested["sourceRef"])
    assertEquals("skills.sh", suggested["sourceLabel"])
  }

  @Test
  fun checkInstalledSkillUpdatesUsesDirectToolRuntimeFactory() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-skills-check"))
    val directTaskRuntimeFactory = RecordingDirectTaskRuntimeFactory(
      status = ExecutionStatus.SUCCESS,
      stdout = "find-skills: up_to_date",
    )
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = NoOpRuntimeManager(),
      directTaskRuntimeFactory = directTaskRuntimeFactory,
    )

    val message = hostRuntime.checkInstalledSkillUpdates("find-skills")

    assertEquals("find-skills: up_to_date", message)
    assertEquals(chatStore.loadState().activeSession.sessionId, directTaskRuntimeFactory.lastSessionId)
    assertEquals(AgentTaskType.TOOL_CALL, directTaskRuntimeFactory.lastTask?.type)
    assertTrue(directTaskRuntimeFactory.lastTask?.input.orEmpty().contains("\"tool_name\":\"SkillsCheck\""))
    assertTrue(directTaskRuntimeFactory.lastTask?.input.orEmpty().contains("\"skillId\":\"find-skills\""))
  }

  @Test
  fun updateInstalledSkillUsesDirectToolRuntimeFactory() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-skills-update"))
    val directTaskRuntimeFactory = RecordingDirectTaskRuntimeFactory(
      status = ExecutionStatus.SUCCESS,
      stdout = "find-skills: updated",
    )
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = NoOpRuntimeManager(),
      directTaskRuntimeFactory = directTaskRuntimeFactory,
    )

    val message = hostRuntime.updateInstalledSkill("find-skills")

    assertEquals("find-skills: updated", message)
    assertTrue(directTaskRuntimeFactory.submittedTasks.any { task ->
      task.type == AgentTaskType.TOOL_CALL &&
        task.input.contains("\"tool_name\":\"SkillsUpdate\"") &&
        task.input.contains("\"skillId\":\"find-skills\"")
    })
  }

  private fun semanticUserCandidateExtractor(): MemoryCandidateExtractor =
    MemoryCandidateExtractor(
      userIntentInterpreter = object : UserMemoryIntentInterpreter {
        override fun interpret(
          request: UserMemoryIntentRequest,
        ): UserMemoryIntentInterpretation = UserMemoryIntentInterpretation.Success(
          intents = buildList {
            if (request.userInput.contains("Simplified Chinese", ignoreCase = true)) {
              add(
                UserMemoryIntent(
                  kind = MemoryKind.USER_PREFERENCE,
                  scope = MemoryScope.USER,
                  content = "Default to Simplified Chinese for explanations",
                ),
              )
            }
            if (request.userInput.contains("git reset --hard", ignoreCase = true)) {
              add(
                UserMemoryIntent(
                  kind = MemoryKind.DURABLE_INSTRUCTION,
                  scope = MemoryScope.WORKSPACE,
                  content = "Do not use git reset --hard in this repo",
                ),
              )
            }
          },
        )
      },
    )

  private fun hostRuntime(
    chatStore: ChatSessionLocalStore,
    runtimeManager: AgentSessionRuntimeManager,
    networkSearchConfigFacade: NetworkSearchConfigFacade = EmptyNetworkSearchConfigFacade,
    llmConfigFacade: LlmConfigFacade = RecordingLlmConfigFacade(),
    personalizationFacade: PersonalizationFacade = RecordingPersonalizationFacade(),
    personalizationLocalStore: PersonalizationLocalStore? = null,
    mcpSettingsFacade: McpSettingsFacade = RecordingMcpSettingsFacade(),
    safetySettingsFacade: SafetySettingsFacade = RecordingSafetySettingsFacade(),
    skillsFacade: SkillsFacade = TestSkillsFacade(),
    directTaskRuntimeFactory: AgentSessionTaskRuntimeFactory? = null,
    memoryIngestionCoordinator: ChatMemoryIngestionCoordinator? = null,
    workspaceRootProvider: (() -> Path)? = null,
    approvedReadRootsProvider: () -> ApprovedReadRootsSnapshot = {
      ApprovedReadRootsSnapshot(roots = emptySet(), summary = "workspace=unavailable")
    },
    todoSnapshotProvider: (String) -> List<AgentTodoEntry> = { emptyList() },
    transcriptMessagesProvider: (String) -> List<RuntimeConversationMessage> = { emptyList() },
    approvalReplayRecorder: (String, String, String, String?, Boolean) -> Unit = { _, _, _, _, _ -> },
    approvalApprovedReplayRecorder: (String, String, String, String?, Boolean) -> Unit = { _, _, _, _, _ -> },
    runCancellationReplayRecorder: (String, String, String, String?) -> Unit = { _, _, _, _ -> },
    terminalReplayRepairer: (String, List<AgentRunSnapshot>) -> Unit = { _, _ -> },
    mainThreadPoster: MainThreadPoster = ImmediateMainThreadPoster,
    workspaceEntryOpener: ((Path, String) -> Unit)? = null,
    voiceMetadataAnalyzer: AppAgentWorkspaceVoiceMetadataAnalyzer =
      AppAgentWorkspaceVoiceMetadataAnalyzer { _, _ -> null },
    voiceMetadataBackfillExecutor: Executor = Executor { command -> command.run() },
    voiceMetadataCacheStore: AppAgentWorkspaceVoiceMetadataCacheStore? = null,
  ): OpenCrayHostRuntime = OpenCrayHostRuntime.createForTest(
    stateStore = AppShellStateStore(InMemoryAppShellKeyValueStore()),
    chatSessionStore = chatStore,
    settingsFacade = NoOpSettingsFacade,
    networkSearchConfigFacade = networkSearchConfigFacade,
    llmConfigFacade = llmConfigFacade,
    personalizationFacade = personalizationFacade,
    personalizationLocalStore = personalizationLocalStore,
    mcpSettingsFacade = mcpSettingsFacade,
    safetySettingsFacade = safetySettingsFacade,
    skillsFacade = skillsFacade,
    sessionRuntimeManager = runtimeManager,
    directTaskRuntimeFactory = directTaskRuntimeFactory,
    workspaceRootProvider = workspaceRootProvider,
    workspaceEntryOpener = workspaceEntryOpener,
    approvedReadRootsProvider = approvedReadRootsProvider,
    voiceMetadataAnalyzer = voiceMetadataAnalyzer,
    voiceMetadataBackfillExecutor = voiceMetadataBackfillExecutor,
    voiceMetadataCacheStore = voiceMetadataCacheStore,
    todoSnapshotProvider = todoSnapshotProvider,
    transcriptMessagesProvider = transcriptMessagesProvider,
    memoryIngestionCoordinator = memoryIngestionCoordinator,
    approvalReplayRecorder = approvalReplayRecorder,
    approvalApprovedReplayRecorder = approvalApprovedReplayRecorder,
    runCancellationReplayRecorder = runCancellationReplayRecorder,
    terminalReplayRepairer = terminalReplayRepairer,
    mainThreadPoster = mainThreadPoster,
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
      composerRejectedPlaceholder = "Tell OpenCray differently",
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

  private class TestSkillsFacade : SkillsFacade {
    var lastLoadedQuery: String? = null
    var lastInstalledSourceRef: String? = null
    var snapshot: SkillsSnapshot = SkillsSnapshot(
      installedSkills = emptyList(),
      installSources = emptyList(),
      suggestedSkills = emptyList(),
    )
    var installResult: SkillInstallRequestResult = SkillInstallRequestResult(
      errorMessage = "Not configured.",
    )

    override fun loadSnapshot(query: String): SkillsSnapshot {
      lastLoadedQuery = query
      return snapshot
    }

    override fun setSkillEnabled(skillId: String, enabled: Boolean): Boolean = true

    override fun installSkillSource(sourceRef: String): SkillInstallRequestResult {
      lastInstalledSourceRef = sourceRef
      return installResult
    }

    override fun installSuggestedSkill(skillId: String): Boolean =
      installSkillSource(skillId).succeeded

    override fun deleteInstalledSkill(skillId: String): Boolean = true

    override fun refresh() = Unit

    override fun loadInstructions(skillId: String): SkillInstructionsSnapshot? = null

    override fun enabledSkillRoots(): List<java.io.File> = emptyList()

    override fun activateInstallSource(sourceId: String): String = sourceId
  }

  private class NoOpRuntimeManager : AgentSessionRuntimeManager {
    override fun forSession(sessionId: String): AgentSessionHandle = RecordingSessionHandle(
      sessionId = sessionId,
    )

    override fun observe(listener: AgentSessionRuntimeListener): () -> Unit = {}

    override fun release(sessionId: String) = Unit

    override fun releaseIdleSessions() = Unit
  }

  private class RecordingDirectTaskRuntimeFactory(
    private val status: ExecutionStatus,
    private val stdout: String = "",
    private val errorMessage: String? = null,
  ) : AgentSessionTaskRuntimeFactory {
    var lastSessionId: String? = null
    val submittedTasks = mutableListOf<AgentTask>()
    var lastTask: AgentTask? = null

    override fun create(
      sessionId: String,
      eventSink: com.opencray.runtime.OpenCrayAgentRuntimeEventSink,
    ): SessionTaskRuntime = SessionTaskRuntime { task, _ ->
      lastSessionId = sessionId
      submittedTasks += task
      lastTask = task
      ExecutionResult(
        taskId = task.id,
        status = status,
        stdout = if (status == ExecutionStatus.SUCCESS) stdout else "",
        stderr = if (status == ExecutionStatus.SUCCESS) "" else (errorMessage ?: stdout),
        errorMessage = if (status == ExecutionStatus.SUCCESS) null else errorMessage,
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_000L,
      )
    }
  }

  private class RecordingLlmConfigFacade(
    private val validationResult: LlmValidationResult = LlmValidationResult(
      isSuccess = false,
      message = "Not configured.",
    ),
  ) : LlmConfigFacade {
    var lastSavedCustomRequest: SaveCustomLlmProviderRequest? = null

    override fun load(): LlmConfigSnapshot = EmptyLlmConfigFacade.load()

    override fun save(request: SaveLlmConfigRequest): LlmConfigSnapshot =
      throw UnsupportedOperationException("save is not used in this test")

    override fun saveCustomProvider(request: SaveCustomLlmProviderRequest): LlmConfigSnapshot {
      lastSavedCustomRequest = request
      return LlmConfigSnapshot(
        localeTag = "en",
        enabled = true,
        providerId = "custom",
        selectedProviderOptionId = "custom-saved",
        protocol = request.protocol,
        providerOptions = emptyList(),
        providerName = request.providerName,
        providerNotes = request.providerNotes,
        baseUrl = request.baseUrl,
        apiKey = request.apiKey,
        model = request.model,
        reasoningEffort = request.reasoningEffort,
        systemPrompt = request.systemPrompt,
        helperText = "Helper",
      )
    }

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

  private class RecordingSafetySettingsFacade(
    var snapshot: SafetySettingsSnapshot = defaultSafetySettingsSnapshot(),
  ) : SafetySettingsFacade {
    var lastSavedRequest: SaveSafetySettingsRequest? = null

    override fun load(): SafetySettingsSnapshot = snapshot

    override fun save(request: SaveSafetySettingsRequest): SafetySettingsSnapshot {
      lastSavedRequest = request
      snapshot = defaultSafetySettingsSnapshot().copy(
        automationMode = SafetyAutomationMode.fromWireValue(request.automationModeId),
        rollbackJournalEnabled = request.rollbackJournalEnabled,
        maxFilesPerBatch = request.maxFilesPerBatch,
        maxAgentTurns = request.maxAgentTurns,
        maxToolCalls = request.maxToolCalls,
        undoWindowHours = request.undoWindowHours,
        fileChangesPolicy = ToolPolicyOverride.fromWireValue(request.fileChangesPolicyId),
        fileDeletesPolicy = ToolPolicyOverride.fromWireValue(request.fileDeletesPolicyId),
        shellCommandsPolicy = ToolPolicyOverride.fromWireValue(request.shellCommandsPolicyId),
        externalAccessMode = ExternalAccessMode.fromWireValue(request.externalAccessModeId),
        locations = listOf(
          SafetySettingsLocationSnapshot("photo_library", request.photoLibraryEnabled),
          SafetySettingsLocationSnapshot("downloads", request.downloadsEnabled),
          SafetySettingsLocationSnapshot("documents", request.documentsEnabled),
          SafetySettingsLocationSnapshot("recordings", request.recordingsEnabled),
        ),
        workspaceAccessProfile = WorkspaceAccessProfile.fromWireValue(request.workspaceAccessProfileId),
        readOnlyOutsideWorkspace = request.readOnlyOutsideWorkspace,
        liveContextMode = LiveContextMode.fromWireValue(request.liveContextModeId),
        memoryToolsEnabled = request.memoryToolsEnabled,
      )
      return snapshot
    }
  }

  private companion object {
    private val TOOL_NAME_REGEX: Regex = Regex("""\"tool_name\"\s*:\s*\"([^\"]+)\"""")

    fun defaultSafetySettingsSnapshot(): SafetySettingsSnapshot = SafetySettingsSnapshot(
      automationMode = SafetyAutomationMode.AUTO,
      rollbackJournalEnabled = true,
      maxFilesPerBatch = 20,
      maxAgentTurns = SafetySettingsState.DEFAULT_MAX_AGENT_TURNS,
      maxToolCalls = SafetySettingsState.DEFAULT_MAX_TOOL_CALLS,
      undoWindowHours = 24,
      fileChangesPolicy = ToolPolicyOverride.INHERIT,
      fileDeletesPolicy = ToolPolicyOverride.INHERIT,
      shellCommandsPolicy = ToolPolicyOverride.INHERIT,
      externalAccessMode = ExternalAccessMode.SELECT_PATHS,
      locations = listOf(
        SafetySettingsLocationSnapshot(id = "photo_library", enabled = true),
        SafetySettingsLocationSnapshot(id = "downloads", enabled = true),
        SafetySettingsLocationSnapshot(id = "documents", enabled = false),
        SafetySettingsLocationSnapshot(id = "recordings", enabled = false),
      ),
      workspaceAccessProfile = WorkspaceAccessProfile.WORK,
      readOnlyOutsideWorkspace = true,
      memoryToolsEnabled = true,
    )
  }

  private class RecordingRuntimeManager : AgentSessionRuntimeManager {
    private val handlesBySession = linkedMapOf<String, RecordingSessionHandle>()
    private val listeners = mutableListOf<AgentSessionRuntimeListener>()
    val resumedSessionIds = mutableListOf<String>()
    val requestedSessionIds = mutableListOf<String>()
    val releasedSessionIds = mutableListOf<String>()

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
      handlesBySession[sessionId]?.recordResult(task = task, result = result)
      listeners.forEach { listener ->
        listener.onTaskFinished(sessionId, task, result)
      }
    }

    fun emitRunEvent(
      sessionId: String,
      task: AgentTask,
      event: com.opencray.runtime.OpenCrayAgentRunEvent,
    ) {
      handlesBySession[sessionId]?.recordEvent(event)
      listeners.forEach { listener ->
        listener.onRunEvent(sessionId, task, event)
        when (event) {
          is OpenCrayToolCallEvent -> listener.onToolCall(
            sessionId = sessionId,
            task = task,
            turn = event.turn,
            call = event.call,
          )
          is OpenCrayToolResultEvent -> listener.onToolResult(
            sessionId = sessionId,
            task = task,
            turn = event.turn,
            call = event.call,
            result = event.result,
          )
          else -> Unit
        }
      }
    }

    override fun observe(listener: AgentSessionRuntimeListener): () -> Unit {
      listeners += listener
      return {
        listeners -= listener
      }
    }

    override fun release(sessionId: String) {
      releasedSessionIds += sessionId
      handlesBySession.remove(sessionId)
    }

    override fun releaseIdleSessions() = Unit
  }

  private class SettlingRuntimeManager(
    private val sessionId: String,
  ) : AgentSessionRuntimeManager {
    private val listeners = mutableListOf<AgentSessionRuntimeListener>()
    val handle = SettlingSessionHandle(sessionId = sessionId)

    override fun forSession(sessionId: String): AgentSessionHandle {
      require(sessionId == this.sessionId) {
        "Unexpected sessionId: $sessionId"
      }
      return handle
    }

    override fun observe(listener: AgentSessionRuntimeListener): () -> Unit {
      listeners += listener
      return {
        listeners -= listener
      }
    }

    override fun release(sessionId: String) = Unit

    override fun releaseIdleSessions() = Unit

    fun emitTaskFinished(result: ExecutionResult) {
      val task = handle.requireSubmittedTask()
      handle.recordResult(result)
      listeners.forEach { listener ->
        listener.onTaskFinished(sessionId = sessionId, task = task, result = result)
      }
      handle.settleTerminalState()
    }
  }

  private class SettlingSessionHandle(
    override val sessionId: String,
  ) : AgentSessionHandle {
    private var submittedTask: AgentTask? = null
    private var submission: AgentRunSubmission? = null
    private var lifecycleState: QueueTaskLifecycleState = QueueTaskLifecycleState.QUEUED
    private var result: ExecutionResult? = null

    override fun submitPrompt(
      userText: String,
      pendingMessageId: String,
      visibleThroughMessageId: String,
      policyDecision: PolicyDecision,
      metadata: Map<String, String>,
    ): AgentRunSubmission {
      val runId = "settling-run"
      val task = AgentTask(
        id = "settling-task",
        type = AgentTaskType.PROMPT,
        input = userText,
        policyDecision = policyDecision,
        metadata = metadata + mapOf(
          AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID to runId,
          AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID to pendingMessageId,
          AppAgentSessionTaskRuntimeFactory.METADATA_VISIBLE_THROUGH_MESSAGE_ID to visibleThroughMessageId,
        ),
        createdAtEpochMs = 1_000L,
      )
      val createdSubmission = AgentRunSubmission(
        sessionId = sessionId,
        runId = runId,
        taskId = task.id,
        acceptedAtEpochMs = 1_000L,
      )
      submittedTask = task
      submission = createdSubmission
      lifecycleState = QueueTaskLifecycleState.QUEUED
      result = null
      return createdSubmission
    }

    override fun ensureProcessing() {
      lifecycleState = QueueTaskLifecycleState.RUNNING
    }

    override fun requestCancel(taskId: String): Boolean = false

    override fun requestRetry(taskId: String): Boolean = false

    override fun requestResumeTask(taskId: String): Boolean = false

    override fun listRuns(): List<AgentRunSnapshot> {
      val task = submittedTask ?: return emptyList()
      val createdSubmission = submission ?: return emptyList()
      return listOf(
        AgentRunSnapshot(
          sessionId = sessionId,
          runId = createdSubmission.runId,
          taskId = task.id,
          acceptedAtEpochMs = createdSubmission.acceptedAtEpochMs,
          updatedAtEpochMs = result?.finishedAtEpochMs ?: task.updatedAtEpochMs,
          lifecycleState = lifecycleState,
          taskState = taskStateFor(lifecycleState),
          attempt = 1,
          executionStatus = result?.status,
          errorCode = result?.errorCode,
          errorMessage = result?.errorMessage,
          responseFormat = result?.metadata?.get("responseFormat"),
          resultMetadata = result?.metadata.orEmpty(),
          pendingMessageId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID],
        ),
      )
    }

    override fun findRun(runId: String): AgentRunSnapshot? =
      listRuns().firstOrNull { snapshot -> snapshot.runId == runId }

    override fun waitForRun(runId: String, timeoutMs: Long): AgentRunSnapshot? =
      findRun(runId)

    override fun requestCancelForPendingMessageIds(pendingMessageIds: Set<String>): Int = 0

    override fun resume(): SessionLifecycleState = SessionLifecycleState.IDLE

    override fun snapshot(): SessionQueueSnapshot {
      val task = submittedTask
      val updatedAtEpochMs = result?.finishedAtEpochMs ?: 1_000L
      if (task == null) {
        return SessionQueueSnapshot(
          sessionId = sessionId,
          agentId = "test-agent",
          updatedAtEpochMs = updatedAtEpochMs,
        )
      }
      return SessionQueueSnapshot(
        sessionId = sessionId,
        agentId = "test-agent",
        tasks = listOf(
          SessionQueueTaskSnapshot(
            enqueueOrder = 0,
            task = task.copy(
              state = taskStateFor(lifecycleState),
              updatedAtEpochMs = updatedAtEpochMs,
            ),
            lifecycleState = lifecycleState,
            attempt = 1,
            lastErrorCode = result?.errorCode,
            lastErrorMessage = result?.errorMessage,
          ),
        ),
        updatedAtEpochMs = updatedAtEpochMs,
      )
    }

    override fun hasPendingWork(): Boolean =
      lifecycleState == QueueTaskLifecycleState.QUEUED ||
        lifecycleState == QueueTaskLifecycleState.RUNNING

    fun requireSubmittedTask(): AgentTask =
      checkNotNull(submittedTask) { "Expected task to be submitted." }

    fun recordResult(result: ExecutionResult) {
      this.result = result
    }

    fun settleTerminalState() {
      lifecycleState = when (result?.status) {
        com.opencray.core.contracts.ExecutionStatus.SUCCESS -> QueueTaskLifecycleState.COMPLETED
        com.opencray.core.contracts.ExecutionStatus.CANCELLED -> QueueTaskLifecycleState.CANCELLED
        else -> QueueTaskLifecycleState.FAILED
      }
    }

    private fun taskStateFor(
      lifecycleState: QueueTaskLifecycleState,
    ): AgentTaskState = when (lifecycleState) {
      QueueTaskLifecycleState.QUEUED,
      QueueTaskLifecycleState.RETRY_PENDING,
      -> AgentTaskState.QUEUED

      QueueTaskLifecycleState.RUNNING,
      QueueTaskLifecycleState.CANCEL_REQUESTED,
      -> AgentTaskState.RUNNING

      QueueTaskLifecycleState.SUSPENDED -> AgentTaskState.SUSPENDED
      QueueTaskLifecycleState.COMPLETED -> AgentTaskState.COMPLETED
      QueueTaskLifecycleState.FAILED -> AgentTaskState.FAILED
      QueueTaskLifecycleState.CANCELLED -> AgentTaskState.CANCELLED
    }
  }

  private class QueuedMainThreadPoster : MainThreadPoster {
    private val actions = ArrayDeque<() -> Unit>()

    override fun post(action: () -> Unit) {
      actions += action
    }

    fun flush() {
      while (actions.isNotEmpty()) {
        actions.removeFirst().invoke()
      }
    }
  }

  private class QueuedExecutor : Executor {
    private val commands = ArrayDeque<Runnable>()

    override fun execute(command: Runnable) {
      commands += command
    }

    fun pendingCount(): Int = commands.size

    fun runAll() {
      while (commands.isNotEmpty()) {
        commands.removeFirst().run()
      }
    }
  }

  private class FixedTaskCommitmentIntentInterpreter(
    private val interpretation: TaskCommitmentIntentInterpretation,
  ) : TaskCommitmentIntentInterpreter {
    override fun interpret(
      request: TaskCommitmentIntentRequest,
    ): TaskCommitmentIntentInterpretation = interpretation
  }

  private class RecordingSessionHandle(
    override val sessionId: String,
    private val onResume: ((String) -> Unit)? = null,
    private val submitFailure: Throwable? = null,
    private val resumeResult: Boolean = false,
  ) : AgentSessionHandle {
    var queuedToolCompletion: QueuedToolCompletion? = null
    val queuedToolCompletions = mutableListOf<QueuedToolCompletion>()
    val submittedInputs = mutableListOf<String>()
    val submittedTasks = mutableListOf<AgentTask>()
    val submissions = mutableListOf<AgentRunSubmission>()
    val ensureProcessingTaskIds = mutableListOf<String>()
    val cancelledTaskIds = mutableListOf<String>()
    val cancelledPendingMessageIdSets = mutableListOf<Set<String>>()
    val resumedTaskIds = mutableListOf<String>()
    val terminatedProcessIds = mutableListOf<String>()
    private var lastSubmittedTaskId: String? = null
    private val runSnapshotsById = linkedMapOf<String, AgentRunSnapshot>()
    private val managedProcessesById =
      linkedMapOf<String, com.opencray.runtime.process.ManagedProcessSnapshot>()

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
        policyDecision = policyDecision,
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

    override fun submitTask(task: AgentTask): AgentRunSubmission {
      submitFailure?.let { throw it }
      val runId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID]
        ?.takeIf(String::isNotBlank)
        ?: "run-${submittedTasks.size + 1}"
      val submission = AgentRunSubmission(
        sessionId = sessionId,
        runId = runId,
        taskId = task.id,
        acceptedAtEpochMs = task.createdAtEpochMs,
      )
      lastSubmittedTaskId = task.id
      submittedTasks += task
      submissions += submission
      runSnapshotsById[runId] = AgentRunSnapshot(
        sessionId = sessionId,
        runId = runId,
        taskId = task.id,
        acceptedAtEpochMs = task.createdAtEpochMs,
        updatedAtEpochMs = task.createdAtEpochMs,
        lifecycleState = null,
        taskState = null,
        pendingMessageId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID],
      )
      val completion = queuedToolCompletion?.also {
        queuedToolCompletion = null
      } ?: if (queuedToolCompletions.isNotEmpty()) {
        queuedToolCompletions.removeAt(0)
      } else {
        null
      }
      completion?.also {
        completeQueuedToolCall(
          task = task,
          submission = submission,
          completion = it,
        )
      }
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

    fun recordResult(
      task: AgentTask,
      result: ExecutionResult,
    ) {
      val runId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID].orEmpty()
      val existing = runSnapshotsById[runId] ?: return
      runSnapshotsById[runId] = existing.copy(
        updatedAtEpochMs = result.finishedAtEpochMs,
        executionStatus = result.status,
        errorCode = result.errorCode,
        errorMessage = result.errorMessage,
        responseFormat = result.metadata["responseFormat"],
        resultMetadata = result.metadata,
      )
    }

    fun recordEvent(event: com.opencray.runtime.OpenCrayAgentRunEvent) {
      val existing = runSnapshotsById[event.runId] ?: return
      runSnapshotsById[event.runId] = existing.copy(
        updatedAtEpochMs = event.emittedAtEpochMs,
        managedProcessIds = mergeManagedProcessIds(
          existing = existing.managedProcessIds,
          candidate = (event as? com.opencray.runtime.OpenCrayToolResultEvent)
            ?.result
            ?.metadata
            ?.get("processId"),
        ),
        lastEvent = event,
      )
    }

    private fun completeQueuedToolCall(
      task: AgentTask,
      submission: AgentRunSubmission,
      completion: QueuedToolCompletion,
    ) {
      val resolvedToolName = completion.toolName
        ?: TOOL_NAME_REGEX.find(task.input)?.groupValues?.getOrNull(1)
        ?: "UnknownTool"
      val toolResult = AgentToolResult(
        toolName = resolvedToolName,
        status = when (completion.status) {
          ExecutionStatus.SUCCESS -> AgentToolResultStatus.SUCCESS
          ExecutionStatus.DENIED -> AgentToolResultStatus.DENIED
          ExecutionStatus.CANCELLED -> AgentToolResultStatus.CANCELLED
          ExecutionStatus.TIMEOUT -> AgentToolResultStatus.TIMEOUT
          ExecutionStatus.FAILED -> AgentToolResultStatus.FAILED
        },
        content = completion.content,
        errorCode = completion.errorCode,
        errorMessage = completion.errorMessage,
        metadata = completion.metadata,
      )
      recordEvent(
        OpenCrayToolResultEvent(
          runId = submission.runId,
          taskId = task.id,
          turn = 0,
          call = AgentToolCall(toolName = resolvedToolName),
          result = toolResult,
          emittedAtEpochMs = task.createdAtEpochMs + 1L,
        ),
      )
      recordResult(
        task = task,
        result = ExecutionResult(
          taskId = task.id,
          status = completion.status,
          stdout = if (completion.status == ExecutionStatus.SUCCESS) completion.content else "",
          errorCode = completion.errorCode,
          errorMessage = completion.errorMessage,
          startedAtEpochMs = task.createdAtEpochMs,
          finishedAtEpochMs = task.createdAtEpochMs + 1L,
        ),
      )
    }

    override fun listRuns(): List<AgentRunSnapshot> = runSnapshotsById.values.map(::withManagedProcessState)

    override fun findRun(runId: String): AgentRunSnapshot? = runSnapshotsById[runId]?.let(::withManagedProcessState)

    override fun waitForRun(runId: String, timeoutMs: Long): AgentRunSnapshot? = findRun(runId)

    override fun requestCancelForPendingMessageIds(pendingMessageIds: Set<String>): Int {
      val normalizedIds = pendingMessageIds
        .filterTo(linkedSetOf()) { pendingMessageId -> pendingMessageId.isNotBlank() }
      if (normalizedIds.isEmpty()) {
        return 0
      }
      cancelledPendingMessageIdSets += normalizedIds
      val matchingTaskIds = runSnapshotsById.values
        .filter { run -> !run.isTerminal && run.pendingMessageId in normalizedIds }
        .map { run -> run.taskId }
      matchingTaskIds.forEach(::requestCancel)
      return matchingTaskIds.size
    }

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

    override fun listManagedProcesses(): List<com.opencray.runtime.process.ManagedProcessSnapshot> =
      managedProcessesById.values.toList()

    override fun terminateRunningManagedProcesses(): List<com.opencray.runtime.process.ManagedProcessSnapshot> =
      managedProcessesById.values
        .filter { snapshot ->
          snapshot.status == com.opencray.runtime.process.ManagedProcessStatus.RUNNING
        }
        .map { snapshot ->
          terminatedProcessIds += snapshot.processId
          snapshot.copy(
            status = com.opencray.runtime.process.ManagedProcessStatus.CANCELLED,
            cancelled = true,
            updatedAtEpochMs = snapshot.updatedAtEpochMs + 1L,
            finishedAtEpochMs = snapshot.updatedAtEpochMs + 1L,
          ).also { updated ->
            managedProcessesById[updated.processId] = updated
          }
        }

    fun putManagedProcess(snapshot: com.opencray.runtime.process.ManagedProcessSnapshot) {
      managedProcessesById[snapshot.processId] = snapshot
    }

    private fun withManagedProcessState(snapshot: AgentRunSnapshot): AgentRunSnapshot {
      val managedProcessIds = (
        snapshot.managedProcessIds +
          managedProcessesById.values
            .asSequence()
            .filter { process -> process.taskId == snapshot.taskId }
            .map { process -> process.processId }
            .toList()
        ).distinct()
      val runningManagedProcessCount = managedProcessIds.count { processId ->
        managedProcessesById[processId]?.status == com.opencray.runtime.process.ManagedProcessStatus.RUNNING
      }
      return snapshot.copy(
        managedProcessIds = managedProcessIds,
        runningManagedProcessCount = runningManagedProcessCount,
        hasLiveManagedProcesses = runningManagedProcessCount > 0,
      )
    }

    private fun mergeManagedProcessIds(
      existing: List<String>,
      candidate: String?,
    ): List<String> = candidate
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { processId -> if (processId in existing) existing else existing + processId }
      ?: existing
  }

  private data class QueuedToolCompletion(
    val toolName: String? = null,
    val content: String,
    val metadata: Map<String, String> = emptyMap(),
    val status: ExecutionStatus = ExecutionStatus.SUCCESS,
    val errorCode: String? = null,
    val errorMessage: String? = null,
  )

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

  private fun jsonObject(raw: String): JsonObject =
    Json.parseToJsonElement(raw).jsonObject
}
