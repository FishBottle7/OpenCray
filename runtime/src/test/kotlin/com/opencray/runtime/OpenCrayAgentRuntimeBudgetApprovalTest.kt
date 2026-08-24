package com.opencray.runtime

import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.orchestrator.SuspensionRequest
import com.opencray.llm.LiteLlmGatewayMessageRole
import com.opencray.runtime.workingstate.InMemoryWorkingStateStore
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.Json

class OpenCrayAgentRuntimeBudgetApprovalTest : OpenCrayAgentRuntimeTestBase() {
  @Test
  fun runPromptTaskFailsWhenToolBudgetIsExceeded() {
    val workspaceRoot = temporaryFolder.newFolder("agent-tool-budget")
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"workspace_list_files","arguments":{}}""",
        """{"type":"tool_call","tool_name":"workspace_list_files","arguments":{}}""",
      ),
    )
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(
        maxTurns = 3,
        maxToolCalls = 1,
      ),
      clock = IncrementingClock(start = 3_000L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Inspect the workspace twice."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.FAILED, result.status)
    assertEquals("MAX_TOOL_CALLS_EXCEEDED", result.errorCode)
    assertEquals("tool_budget_exceeded", result.metadata["responseFormat"])
  }

  @Test
  fun runPromptTaskAddsTurnBudgetReminderBeforeLastTurn() {
    val workspaceRoot = temporaryFolder.newFolder("agent-turn-budget-reminder")
    Files.write(
      workspaceRoot.toPath().resolve("README.md"),
      "turn budget".toByteArray(StandardCharsets.UTF_8),
    )
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"Read","arguments":{"file_path":"README.md"}}""",
        """{"type":"final","answer":"Returning the final answer in time."}""",
      ),
    )
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(maxTurns = 3, maxToolCalls = 2),
      clock = IncrementingClock(start = 3_250L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Read README and answer before the turn budget runs out."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Returning the final answer in time.", result.stdout)
    assertEquals("2", result.metadata["turnCount"])
    assertEquals(2, gateway.requests.size)
    assertEquals("3", gateway.requests[0].metadata["remainingTurnCount"])
    assertEquals("2", gateway.requests[1].metadata["remainingTurnCount"])
    assertTrue(
      gateway.requests[1].systemPrompt.orEmpty().contains(
        "You have two model turns left including this one.",
      ),
    )
    assertTrue(
      gateway.requests[1].prompt.contains(
        "Turn budget note: after this turn, only one model turn remains.",
      ),
    )
  }

  @Test
  fun runPromptTaskFinalAnswerOnlyTurnRejectsAnotherToolCall() {
    val workspaceRoot = temporaryFolder.newFolder("agent-final-turn-only")
    Files.write(
      workspaceRoot.toPath().resolve("README.md"),
      "final turn only".toByteArray(StandardCharsets.UTF_8),
    )
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"Read","arguments":{"file_path":"README.md"}}""",
        """{"type":"tool_call","tool_name":"Read","arguments":{"file_path":"README.md"}}""",
      ),
    )
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(maxTurns = 2, maxToolCalls = 2),
      clock = IncrementingClock(start = 3_375L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Use one tool, then answer on the final turn."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.FAILED, result.status)
    assertEquals("MAX_TURNS_EXCEEDED", result.errorCode)
    assertEquals("turn_limit_final_answer_required", result.metadata["responseFormat"])
    assertEquals("true", result.metadata["finalAnswerRequired"])
    assertEquals("2", result.metadata["turnCount"])
    assertEquals("1", result.metadata["toolCallCount"])
    assertEquals("1", gateway.requests[1].metadata["remainingTurnCount"])
    assertTrue(gateway.requests[0].tools.isNotEmpty())
    assertTrue(
      gateway.requests[1].systemPrompt.orEmpty().contains(
        "This is the last allowed model turn. Do not call any more tools. Return the final user-facing answer now.",
      ),
    )
    assertTrue(
      gateway.requests[1].prompt.contains(
        "Turn budget note: this is the last allowed model turn.",
      ),
    )
  }

  @Test
  fun runPromptTaskWithoutHardBudgetsSkipsTurnAndToolBudgetEnforcement() {
    val workspaceRoot = temporaryFolder.newFolder("agent-no-turn-cap")
    Files.write(
      workspaceRoot.toPath().resolve("README.md"),
      "first".toByteArray(StandardCharsets.UTF_8),
    )
    Files.write(
      workspaceRoot.toPath().resolve("NOTES.md"),
      "second".toByteArray(StandardCharsets.UTF_8),
    )
    Files.write(
      workspaceRoot.toPath().resolve("TODO.md"),
      "third".toByteArray(StandardCharsets.UTF_8),
    )
    Files.write(
      workspaceRoot.toPath().resolve("PLAN.md"),
      "fourth".toByteArray(StandardCharsets.UTF_8),
    )
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"Read","arguments":{"file_path":"README.md"}}""",
        """{"type":"tool_call","tool_name":"Read","arguments":{"file_path":"NOTES.md"}}""",
        """{"type":"tool_call","tool_name":"Read","arguments":{"file_path":"TODO.md"}}""",
        """{"type":"tool_call","tool_name":"Read","arguments":{"file_path":"PLAN.md"}}""",
        """{"type":"final","answer":"Unlimited budgets completed."}""",
      ),
    )
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(maxTurns = 0, maxToolCalls = 0),
      clock = IncrementingClock(start = 3_450L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Keep using tools until you have enough context."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Unlimited budgets completed.", result.stdout)
    assertEquals("5", result.metadata["turnCount"])
    assertEquals("4", result.metadata["toolCallCount"])
    assertEquals(5, gateway.requests.size)
    assertTrue(
      gateway.requests.all { request ->
        request.metadata["remainingTurnCount"] == null &&
          request.metadata["maxTurnCount"] == null &&
          !request.systemPrompt.orEmpty().contains("[Turn Budget]") &&
          !request.prompt.contains("Turn budget note:")
      },
    )
  }

  @Test
  fun runPromptTaskStopsImmediatelyWhenToolRequiresApproval() {
    val workspaceRoot = temporaryFolder.newFolder("agent-approval-stop")
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"Write","arguments":{"file_path":"note.txt","content":"hello"}}""",
        """{"type":"final","answer":"should not be reached"}""",
      ),
    )
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(maxTurns = 4, maxToolCalls = 2),
      clock = IncrementingClock(start = 3_500L)::next,
    )

    val suspensionRequests = mutableListOf<SuspensionRequest>()
    val result = runtime.execute(
      task = promptTask(
        input = "Write a note in safe mode.",
        metadata = mapOf("chatMode" to "SAFE"),
      ),
      hooks = runtimeHooks(
        onSuspend = suspensionRequests::add,
      ),
    )

    assertEquals(ExecutionStatus.DENIED, result.status)
    assertEquals("APPROVAL_REQUIRED", result.errorCode)
    assertEquals("tool_approval_required", result.metadata["responseFormat"])
    assertEquals("1", result.metadata["toolCallCount"])
    assertEquals(1, gateway.requests.size)
    assertEquals(listOf("APPROVAL_REQUIRED"), suspensionRequests.map(SuspensionRequest::reasonCode))
    assertTrue(!Files.exists(workspaceRoot.toPath().resolve("note.txt")))
  }

  @Test
  fun runPromptTaskPersistsApprovalBlockerIntoWorkingStateStoreBeforeSuspending() {
    val workspaceRoot = temporaryFolder.newFolder("agent-approval-working-state")
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"Write","arguments":{"file_path":"note.txt","content":"hello"}}""",
      ),
    )
    val workingStateStore = InMemoryWorkingStateStore()
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(
        maxTurns = 4,
        maxToolCalls = 2,
        workingStateStore = workingStateStore,
      ),
      clock = IncrementingClock(start = 3_550L)::next,
    )

    val result = runtime.execute(
      task = promptTask(
        input = "Write a note in safe mode.",
        metadata = mapOf("chatMode" to "SAFE"),
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.DENIED, result.status)
    assertEquals(
      listOf("Approval required for Write before continuing."),
      workingStateStore.snapshot().blockers.map { entry -> entry.text },
    )
    assertEquals(
      "Write a note in safe mode.",
      workingStateStore.snapshot().objective?.primaryGoal,
    )
  }

  @Test
  fun runPromptTaskPersistsLatestToolResultIntoWorkingStateStoreImmediatelyAfterToolCommit() {
    val workspaceRoot = temporaryFolder.newFolder("agent-tool-commit-working-state")
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"Write","arguments":{"file_path":"note.txt","content":"working state after tool result"}}""",
        """{"type":"final","answer":"Done."}""",
      ),
    )
    val workingStateStore = RecordingWorkingStateStore()
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(
        maxTurns = 4,
        maxToolCalls = 2,
        workingStateStore = workingStateStore,
      ),
      clock = IncrementingClock(start = 3_575L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Write a note and then answer."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertTrue(Files.exists(workspaceRoot.toPath().resolve("note.txt")))
    assertTrue(workingStateStore.history.size >= 3)
    assertEquals(
      listOf("Write file_path=note.txt"),
      workingStateStore.history[1].recentActions.map { entry -> entry.text },
    )
    assertEquals(
      listOf("workspace_mutation"),
      workingStateStore.history[1].recentActions.map { entry -> entry.sourceType },
    )
  }

  @Test
  fun runPromptTaskApprovalResumeContinuesFromSavedTurnWithoutReissuingPromptToolCall() {
    val workspaceRoot = temporaryFolder.newFolder("agent-approval-resume")
    val initialGateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"Write","arguments":{"file_path":"note.txt","content":"hello"}}""",
      ),
    )
    val firstRuntime = OpenCrayAgentRuntime(
      gateway = initialGateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(maxTurns = 4, maxToolCalls = 2),
      clock = IncrementingClock(start = 3_600L)::next,
    )
    val task = promptTask(
      input = "Write a note in safe mode.",
      metadata = mapOf("chatMode" to "SAFE"),
    )

    val firstResult = firstRuntime.execute(
      task = task,
      hooks = runtimeHooks(),
    )
    val resumeState = requireNotNull(
      OpenCrayPromptResumeMetadata.decodeFromMetadata(
        metadata = firstResult.metadata,
        json = Json { ignoreUnknownKeys = true },
      ),
    )

    assertEquals(ExecutionStatus.DENIED, firstResult.status)
    assertEquals("APPROVAL_REQUIRED", firstResult.errorCode)
    assertEquals(1, initialGateway.requests.size)
    assertEquals(0, resumeState.turnIndex)
    assertEquals(1, resumeState.toolCallCount)
    assertEquals(0, resumeState.nextActionIndex)
    assertEquals(1, resumeState.pendingActions.size)
    val resumedPendingCall = (resumeState.pendingActions.single() as OpenCraySerializableModelAction.ToolCall).call
    assertEquals("Write", resumedPendingCall.toolName)
    assertEquals("oc-call-1", resumedPendingCall.id)

    val resumedGateway = RecordingGateway(
      outputs = listOf(
        """{"type":"final","answer":"Approved write completed."}""",
      ),
    )
    val resumedEventSink = RecordingEventSink()
    val resumedRuntime = OpenCrayAgentRuntime(
      gateway = resumedGateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
          approvedTaskId = task.id,
          approvedToolName = "Write",
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(
        maxTurns = 4,
        maxToolCalls = 2,
        promptResumeState = resumeState,
      ),
      eventSink = resumedEventSink,
      clock = IncrementingClock(start = 3_700L)::next,
    )

    val resumedResult = resumedRuntime.execute(
      task = task,
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, resumedResult.status)
    assertEquals("Approved write completed.", resumedResult.stdout)
    assertEquals(1, resumedGateway.requests.size)
    assertEquals("1", resumedGateway.requests.single().metadata["turnIndex"])
    assertTrue(resumedGateway.requests.single().prompt.contains("note.txt"))
    assertTrue(
      resumedGateway.requests.single().messages.any { message ->
        message.role == LiteLlmGatewayMessageRole.ASSISTANT &&
          message.toolCalls.singleOrNull()?.id == "oc-call-1" &&
          message.toolCalls.singleOrNull()?.toolName == "Write"
      },
    )
    assertTrue(
      resumedGateway.requests.single().messages.any { message ->
        message.role == LiteLlmGatewayMessageRole.TOOL &&
          message.toolResult?.toolCallId == "oc-call-1" &&
          message.toolResult?.toolName == "Write"
      },
    )
    assertTrue(Files.exists(workspaceRoot.toPath().resolve("note.txt")))
    assertEquals(
      listOf("tool_result", "supplement", "supplement", "assistant", "lifecycle"),
      resumedEventSink.events.drop(1).map { event ->
        when (event) {
          is OpenCrayToolResultEvent -> "tool_result"
          is OpenCraySupplementEvent -> "supplement"
          is OpenCrayAssistantEvent -> "assistant"
          is OpenCrayLifecycleEvent -> "lifecycle"
          else -> "other"
        }
      },
    )
    assertEquals(
      listOf("internal_prompt_checkpoint", "internal_prompt_checkpoint"),
      resumedEventSink.events.filterIsInstance<OpenCraySupplementEvent>().map(OpenCraySupplementEvent::checkpoint),
    )
    assertTrue(resumedEventSink.events.none { event -> event is OpenCrayToolCallEvent })
  }

  @Test
  fun runPromptTaskRejectedApprovalResumeContinuesWithoutReaskingApproval() {
    val workspaceRoot = temporaryFolder.newFolder("agent-approval-reject-resume")
    val initialGateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"Write","arguments":{"file_path":"note.txt","content":"hello"}}""",
      ),
    )
    val firstRuntime = OpenCrayAgentRuntime(
      gateway = initialGateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(maxTurns = 4, maxToolCalls = 2),
      clock = IncrementingClock(start = 3_750L)::next,
    )
    val task = promptTask(
      input = "Write a note in safe mode.",
      metadata = mapOf("chatMode" to "SAFE"),
    )

    val firstResult = firstRuntime.execute(
      task = task,
      hooks = runtimeHooks(),
    )
    val resumeState = requireNotNull(
      OpenCrayPromptResumeMetadata.decodeFromMetadata(
        metadata = firstResult.metadata,
        json = Json { ignoreUnknownKeys = true },
      ),
    )

    val resumedGateway = RecordingGateway(
      outputs = listOf(
        """{"type":"final","answer":"I skipped the blocked write and did not change the file."}""",
      ),
    )
    val resumedEventSink = RecordingEventSink()
    val resumedRuntime = OpenCrayAgentRuntime(
      gateway = resumedGateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
          rejectedTaskId = task.id,
          rejectedToolName = "Write",
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(
        maxTurns = 4,
        maxToolCalls = 2,
        promptResumeState = resumeState,
      ),
      eventSink = resumedEventSink,
      clock = IncrementingClock(start = 3_760L)::next,
    )

    val resumedResult = resumedRuntime.execute(
      task = task,
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, resumedResult.status)
    assertEquals("I skipped the blocked write and did not change the file.", resumedResult.stdout)
    assertEquals(1, resumedGateway.requests.size)
    assertEquals("1", resumedGateway.requests.single().metadata["turnIndex"])
    assertEquals(
      listOf("tool_result", "assistant", "lifecycle"),
      externalEventKinds(resumedEventSink.events.drop(1)),
    )
    assertEquals(
      listOf("Write:DENIED"),
      resumedEventSink.events
        .filterIsInstance<OpenCrayToolResultEvent>()
        .map { event -> "${event.call.toolName}:${event.result.status.name}" },
    )
    assertEquals(
      listOf("internal_prompt_checkpoint", "internal_prompt_checkpoint"),
      resumedEventSink.events
        .filterIsInstance<OpenCraySupplementEvent>()
        .map(OpenCraySupplementEvent::checkpoint),
    )
    assertTrue(resumedEventSink.events.none { event -> event is OpenCrayToolCallEvent })
    assertTrue(!Files.exists(workspaceRoot.toPath().resolve("note.txt")))
  }

  @Test
  fun runPromptTaskApprovalResumeContinuesRemainingActionsInSameTurnInOriginalOrder() {
    val workspaceRoot = temporaryFolder.newFolder("agent-approval-batch-resume")
    Files.write(
      workspaceRoot.toPath().resolve("input.txt"),
      "seed".toByteArray(StandardCharsets.UTF_8),
    )
    val initialGateway = RecordingGateway(
      outputs = listOf(
        """{"actions":[
          {"type":"progress","text":"Inspecting workspace","stage":"inspect"},
          {"type":"tool_call","tool_name":"Read","arguments":{"file_path":"input.txt"}},
          {"type":"tool_call","tool_name":"Write","arguments":{"file_path":"note.txt","content":"hello"}},
          {"type":"progress","text":"Verifying saved note","stage":"verify"},
          {"type":"tool_call","tool_name":"Read","arguments":{"file_path":"note.txt"}}
        ]}""",
      ),
    )
    val initialEventSink = RecordingEventSink()
    val task = promptTask(
      input = "Inspect the workspace, write a note, then verify it in safe mode.",
      metadata = mapOf("chatMode" to "SAFE"),
    )
    val firstRuntime = OpenCrayAgentRuntime(
      gateway = initialGateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(maxTurns = 4, maxToolCalls = 4),
      eventSink = initialEventSink,
      clock = IncrementingClock(start = 3_800L)::next,
    )

    val firstResult = firstRuntime.execute(
      task = task,
      hooks = runtimeHooks(),
    )
    val resumeState = requireNotNull(
      OpenCrayPromptResumeMetadata.decodeFromMetadata(
        metadata = firstResult.metadata,
        json = Json { ignoreUnknownKeys = true },
      ),
    )

    assertEquals(ExecutionStatus.DENIED, firstResult.status)
    assertEquals(
      listOf("Inspecting workspace"),
      initialEventSink.events
        .filterIsInstance<OpenCrayAssistantEvent>()
        .filterNot(OpenCrayAssistantEvent::isFinal)
        .map(OpenCrayAssistantEvent::text),
    )
    assertEquals(listOf("Read", "Write"), initialEventSink.events.filterIsInstance<OpenCrayToolCallEvent>().map { it.call.toolName })
    assertEquals(
      listOf("Read:SUCCESS", "Write:DENIED"),
      initialEventSink.events.filterIsInstance<OpenCrayToolResultEvent>().map { "${it.call.toolName}:${it.result.status.name}" },
    )
    assertEquals(0, resumeState.turnIndex)
    assertEquals(2, resumeState.toolCallCount)
    assertEquals(2, resumeState.nextActionIndex)
    assertEquals(5, resumeState.pendingActions.size)

    val resumedGateway = RecordingGateway(
      outputs = listOf(
        """{"type":"final","answer":"Verified the approved note."}""",
      ),
    )
    val resumedEventSink = RecordingEventSink()
    val resumedRuntime = OpenCrayAgentRuntime(
      gateway = resumedGateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
          approvedTaskId = task.id,
          approvedToolName = "Write",
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(
        maxTurns = 4,
        maxToolCalls = 4,
        promptResumeState = resumeState,
      ),
      eventSink = resumedEventSink,
      clock = IncrementingClock(start = 3_900L)::next,
    )

    val resumedResult = resumedRuntime.execute(
      task = task,
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, resumedResult.status)
    assertEquals("Verified the approved note.", resumedResult.stdout)
    assertEquals(
      listOf("Verifying saved note"),
      resumedEventSink.events
        .filterIsInstance<OpenCrayAssistantEvent>()
        .filterNot(OpenCrayAssistantEvent::isFinal)
        .map(OpenCrayAssistantEvent::text),
    )
    assertEquals(listOf("Read"), resumedEventSink.events.filterIsInstance<OpenCrayToolCallEvent>().map { it.call.toolName })
    assertEquals(
      listOf("Write:SUCCESS", "Read:SUCCESS"),
      resumedEventSink.events.filterIsInstance<OpenCrayToolResultEvent>().map { "${it.call.toolName}:${it.result.status.name}" },
    )
    assertEquals(1, resumedGateway.requests.size)
    assertEquals("1", resumedGateway.requests.single().metadata["turnIndex"])
    assertTrue(resumedGateway.requests.single().prompt.contains("Protocol note: return only the next step on each turn."))
    assertTrue(resumedGateway.requests.single().prompt.contains("Use native tool calling for the next tool action."))
    assertTrue(resumedGateway.requests.single().prompt.contains("note.txt"))
    assertEquals(
      "hello",
      String(
        Files.readAllBytes(workspaceRoot.toPath().resolve("note.txt")),
        StandardCharsets.UTF_8,
      ),
    )
  }
}
