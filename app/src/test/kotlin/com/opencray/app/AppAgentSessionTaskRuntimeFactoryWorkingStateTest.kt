package com.opencray.app

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.runtime.AgentTodoEntry
import com.opencray.runtime.AgentTodoStatus
import com.opencray.runtime.workingstate.WorkingState
import com.opencray.runtime.workingstate.WorkingStateObjective
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppAgentSessionTaskRuntimeFactoryWorkingStateTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun workingStateStoreForSessionReusesStoreForSameSessionIdAndSeparatesDifferentSessions() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-working-state"))
    val workspaceRoot = temporaryFolder.newFolder("workspace-root-working-state").toPath()
    val factory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
    )

    val first = factory.workingStateStoreForSession("session-1")
    val second = factory.workingStateStoreForSession("session-1")
    val third = factory.workingStateStoreForSession("session-2")

    assertSame(first, second)
    assertNotSame(first, third)
  }

  @Test
  fun workingStateStoreForSessionRestoresPersistedStateAcrossFactoryRecreation() {
    val chatDirectory = temporaryFolder.newFolder("chat-store-persistent-working-state")
    val workspaceRoot = temporaryFolder.newFolder("workspace-root-persistent-working-state").toPath()
    val initialChatStore = ChatSessionLocalStore(chatDirectory)
    val sessionId = initialChatStore.loadState().activeSession.sessionId
    val workingState = WorkingState(
      objective = WorkingStateObjective(
        primaryGoal = "Persist working state.",
        currentSubgoal = "Restore the store after factory recreation.",
        status = "in_progress",
      ),
    )

    fun createFactory(chatStore: ChatSessionLocalStore): AppAgentSessionTaskRuntimeFactory =
      AppAgentSessionTaskRuntimeFactory(
        llmSettingsProvider = { LlmSettingsState() },
        sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
        soulProfileProvider = { null },
        workspaceRootsProvider = { setOf(workspaceRoot) },
        skillsRootsProvider = { emptyList() },
        mcpReportProvider = { null },
        workingStateStoreProvider = { requestedSessionId ->
          ChatSessionBackedWorkingStateStore(
            chatSessionStore = chatStore,
            sessionId = requestedSessionId,
          )
        },
      )

    createFactory(initialChatStore).workingStateStoreForSession(sessionId).replace(workingState)

    val restoredFactory = createFactory(ChatSessionLocalStore(chatDirectory))

    assertEquals(workingState, restoredFactory.workingStateStoreForSession(sessionId).snapshot())
  }

  @Test
  fun prepareSessionContextLoadsPersistedWorkingStateIntoSessionContext() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-working-state-context"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val workspaceRoot = temporaryFolder.newFolder("workspace-root-working-state-context").toPath()
    val persistedWorkingState = WorkingState(
      objective = WorkingStateObjective(
        primaryGoal = "Keep the current implementation thread warm.",
        currentSubgoal = "Load persisted working state before prompt assembly.",
        status = "in_progress",
      ),
    )
    chatStore.replaceWorkingState(
      sessionId = sessionId,
      workingState = persistedWorkingState,
    )
    val factory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
      workingStateStoreProvider = { requestedSessionId ->
        ChatSessionBackedWorkingStateStore(
          chatSessionStore = chatStore,
          sessionId = requestedSessionId,
        )
      },
    )

    val prepared = factory.prepareSessionContext(
      sessionId = sessionId,
      workspaceId = "workspace-working-state-context",
      visibleThroughMessageId = null,
      excludedMessageIds = emptySet(),
      soulProfile = null,
      taskType = AgentTaskType.PROMPT,
      taskId = "task-working-state-context",
      taskInput = "Continue the rollout.",
      transcriptStore = factory.transcriptStoreForSession(sessionId),
      memoryRecords = emptyList(),
    )

    assertEquals(persistedWorkingState, prepared.sessionContext.workingState)
  }

  @Test
  fun finalizeWorkingStateAfterTaskClearsStateWhenPromptSucceedsWithoutOpenTodo() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-working-state-finalize-clear"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val workspaceRoot = temporaryFolder.newFolder("workspace-root-working-state-finalize-clear").toPath()
    val factory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
    )
    factory.workingStateStoreForSession(sessionId).replace(
      WorkingState(
        objective = WorkingStateObjective(
          primaryGoal = "Clear stale working state.",
          status = "active",
        ),
      ),
    )

    factory.finalizeWorkingStateAfterTask(
      sessionId = sessionId,
      task = promptTask("Wrap up the rollout."),
      result = successResult(taskId = "task-clear"),
    )

    assertTrue(factory.workingStateStoreForSession(sessionId).snapshot().isEmpty)
  }

  @Test
  fun finalizeWorkingStateAfterTaskKeepsStateWhenPromptSucceedsWithOpenTodo() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-working-state-finalize-keep"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val workspaceRoot = temporaryFolder.newFolder("workspace-root-working-state-finalize-keep").toPath()
    val factory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
    )
    val workingState = WorkingState(
      objective = WorkingStateObjective(
        primaryGoal = "Keep active working state.",
        status = "in_progress",
      ),
    )
    factory.workingStateStoreForSession(sessionId).replace(workingState)
    factory.todoStoreForSession(sessionId).replaceAll(
      listOf(
        AgentTodoEntry(
          content = "Continue runtime rollout",
          status = AgentTodoStatus.IN_PROGRESS,
          activeForm = "Continuing runtime rollout",
        ),
      ),
    )

    factory.finalizeWorkingStateAfterTask(
      sessionId = sessionId,
      task = promptTask("Continue the rollout."),
      result = successResult(taskId = "task-keep"),
    )

    assertEquals(workingState, factory.workingStateStoreForSession(sessionId).snapshot())
  }

  private fun promptTask(input: String): AgentTask = AgentTask(
    id = "task-working-state",
    type = AgentTaskType.PROMPT,
    input = input,
    policyDecision = PolicyDecision(
      outcome = PolicyDecisionOutcome.ALLOW,
      reasonCode = "TEST_ALLOW",
    ),
    createdAtEpochMs = 1_000L,
  )

  private fun successResult(taskId: String): ExecutionResult = ExecutionResult(
    taskId = taskId,
    status = ExecutionStatus.SUCCESS,
    stdout = "done",
    startedAtEpochMs = 1_100L,
    finishedAtEpochMs = 1_200L,
  )
}
