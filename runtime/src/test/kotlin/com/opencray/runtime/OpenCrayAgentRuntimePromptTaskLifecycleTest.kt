package com.opencray.runtime

import com.opencray.core.contracts.ExecutionStatus
import com.opencray.llm.LiteLlmAttemptOutcome
import com.opencray.llm.LiteLlmAttemptRecord
import com.opencray.llm.LiteLlmCompletionMode
import com.opencray.llm.LiteLlmGateway
import com.opencray.llm.LiteLlmGatewayMessageRole
import com.opencray.llm.LiteLlmGatewayRequest
import com.opencray.llm.LiteLlmGatewayResult
import com.opencray.llm.LiteLlmMetadataKeys
import com.opencray.llm.LiteLlmGatewayStatus
import com.opencray.llm.LiteLlmRouteSelectionMetadata
import com.opencray.llm.LiteLlmStructuredCompletion
import com.opencray.llm.LiteLlmStructuredToolCall
import com.opencray.runtime.context.RuntimeConversationMessageKind
import com.opencray.runtime.context.RuntimeConversationRole
import com.opencray.runtime.workingstate.InMemoryWorkingStateStore
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class OpenCrayAgentRuntimePromptTaskLifecycleTest : OpenCrayAgentRuntimeTestBase() {
  @Test
  fun runPromptTaskFeedsToolObservationIntoNextLlmTurn() {
    val workspaceRoot = temporaryFolder.newFolder("agent-workspace")
    Files.write(
      workspaceRoot.toPath().resolve("README.md"),
      "hello from workspace".toByteArray(StandardCharsets.UTF_8),
    )

    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"workspace_read_file","arguments":{"path":"README.md"}}""",
        """{"type":"final","answer":"README 确认内容是 hello from workspace"}""",
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
      clock = IncrementingClock(start = 1_000L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "读一下 README 然后告诉我内容"),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("README 确认内容是 hello from workspace", result.stdout)
    assertEquals("2", result.metadata["turnCount"])
    assertEquals("1", result.metadata["toolCallCount"])
    assertEquals(2, gateway.requests.size)
    assertTrue(gateway.requests[0].systemPrompt.orEmpty().contains("[Identity]"))
    assertTrue(gateway.requests[0].prompt.contains("[Tool Protocol]"))
    assertTrue(gateway.requests[1].prompt.contains("workspace_read_file"))
    assertTrue(gateway.requests[1].prompt.contains("hello from workspace"))
  }

  @Test
  fun runPromptTaskRequiresClosingActiveTodoBeforeFinalAnswer() {
    val workspaceRoot = temporaryFolder.newFolder("agent-todo-plan-closure")
    val todoStore = InMemoryAgentTodoStore()
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"TodoWrite","arguments":{"todos":[{"content":"Inspect README","status":"in_progress","activeForm":"Inspecting README"}]}}""",
        """{"type":"final","answer":"Done too early."}""",
        """{"type":"tool_call","tool_name":"TodoWrite","arguments":{"todos":[{"content":"Inspect README","status":"completed"}]}}""",
        """{"type":"final","answer":"Closed the plan before finishing."}""",
      ),
    )
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
          todoStore = todoStore,
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(maxTurns = 6, maxToolCalls = 0),
      clock = IncrementingClock(start = 1_000L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Inspect the README task and then answer."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Closed the plan before finishing.", result.stdout)
    assertEquals(4, gateway.requests.size)
    assertEquals("local_front_patch", gateway.requests[1].metadata["localContinuationMode"])
    assertEquals("dynamic_context_changed", gateway.requests[1].metadata["localContinuationReason"])
    assertEquals(
      "dynamic_context_changed",
      gateway.requests[1].metadata[LiteLlmMetadataKeys.CONTEXT_CACHE_BREAK_REASON],
    )
    assertTrue(gateway.requests[1].prompt.contains("[Working State]"))
    assertTrue(
      gateway.requests[1].messages.any { message ->
        message.role == LiteLlmGatewayMessageRole.TOOL &&
          message.toolResult?.toolName == "TodoWrite"
      },
    )
    assertTrue(gateway.requests[2].prompt.contains("TodoWrite still has an in_progress item"))
    assertTrue(gateway.requests[2].prompt.contains("Active todo: Inspect README"))
    assertEquals(
      listOf(
        AgentTodoEntry(
          content = "Inspect README",
          status = AgentTodoStatus.COMPLETED,
        ),
      ),
      todoStore.snapshot(),
    )
  }

  @Test
  fun runPromptTaskInjectsTodoDerivedWorkingStateIntoGatewayMessages() {
    val workspaceRoot = temporaryFolder.newFolder("agent-working-state-todos")
    val todoStore = InMemoryAgentTodoStore(
      initialEntries = listOf(
        AgentTodoEntry(
          content = "Wire working state todo projection",
          status = AgentTodoStatus.IN_PROGRESS,
          activeForm = "Wiring working state todo projection",
        ),
        AgentTodoEntry(
          content = "Add context-manager assertions",
          status = AgentTodoStatus.PENDING,
        ),
        AgentTodoEntry(
          content = "Run runtime unit tests",
          status = AgentTodoStatus.PENDING,
        ),
      ),
    )
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"final","answer":"Observed the injected working state."}""",
      ),
    )
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
          todoStore = todoStore,
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(maxTurns = 2, maxToolCalls = 0),
      clock = IncrementingClock(start = 1_000L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Continue the runtime rollout."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Observed the injected working state.", result.stdout)
    assertEquals(1, gateway.requests.size)
    val request = gateway.requests.single()
    val messageText = gatewayStructuredPayloadText(request)
    assertTrue(request.messages.isNotEmpty())
    assertEquals("messages_primary", request.metadata["gatewayTransportMode"])
    assertEquals("fallback_debug_only", request.metadata["gatewayPromptFieldRole"])
    assertEquals("full_rebuild", request.metadata["localContinuationMode"])
    assertTrue(messageText.contains("[Working State]"))
    assertTrue(messageText.contains("primary_goal=Continue the runtime rollout."))
    assertTrue(messageText.contains("current_subgoal=Wiring working state todo projection"))
    assertTrue(messageText.contains("Add context-manager assertions"))
    assertTrue(messageText.contains("Run runtime unit tests"))
    assertEquals("true", request.metadata["contextWorkingStateSynthesizedFromTodos"])
    assertEquals("2", request.metadata["contextWorkingStateNextActionCount"])
  }

  @Test
  fun runPromptTaskUsesStructuredMessagesWithoutToolsOnResponsesProtocol() {
    val workspaceRoot = temporaryFolder.newFolder("agent-responses-no-tools-messages")
    val requests = mutableListOf<LiteLlmGatewayRequest>()
    val selection = LiteLlmRouteSelectionMetadata(
      profileId = "test-profile",
      routeId = "responses-route",
      providerId = "openai",
      model = "gpt-5-mini",
      attemptIndex = 0,
    )
    val gateway = object : LiteLlmGateway {
      override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
        requests += request
        return LiteLlmGatewayResult(
          requestId = request.requestId,
          status = LiteLlmGatewayStatus.SUCCESS,
          completionMode = LiteLlmCompletionMode.PRIMARY,
          outputText = "Structured responses path stayed on messages.",
          completion = LiteLlmStructuredCompletion(
            finalText = "Structured responses path stayed on messages.",
          ),
          providerResponseId = "resp_no_tools_1",
          providerLineageId = "lineage_no_tools_1",
          selectedRoute = selection,
          attempts = listOf(
            LiteLlmAttemptRecord(
              route = selection,
              outcome = LiteLlmAttemptOutcome.SUCCESS,
              outputChars = 42,
              startedAtEpochMs = 19_000L,
              finishedAtEpochMs = 19_001L,
            ),
          ),
          startedAtEpochMs = 19_000L,
          finishedAtEpochMs = 19_001L,
        )
      }
    }
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(
        llmMetadata = mapOf(
          "protocol" to "openai_responses",
          "responseApiPreferred" to "true",
          "responsesContinuationSupported" to "true",
        ),
      ),
      clock = IncrementingClock(start = 19_500L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Answer directly without calling tools."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Structured responses path stayed on messages.", result.stdout)
    assertEquals(1, requests.size)
    val request = requests.single()
    val messageText = gatewayStructuredPayloadText(request)
    assertTrue(request.responseApiPreferred)
    assertTrue(request.messages.isNotEmpty())
    assertEquals("messages_primary", request.metadata["gatewayTransportMode"])
    assertEquals("fallback_debug_only", request.metadata["gatewayPromptFieldRole"])
    assertEquals("full_rebuild", request.metadata["localContinuationMode"])
    assertTrue(messageText.contains("[Tool Protocol]"))
    assertTrue(messageText.contains("Answer directly without calling tools."))
  }

  @Test
  fun runPromptTaskInjectsStructuredMutationActionIntoNextGatewayPrompt() {
    val workspaceRoot = temporaryFolder.newFolder("agent-working-state-write")
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"Write","arguments":{"file_path":"notes.txt","content":"hello from working state"}}""",
        """{"type":"final","answer":"Wrote the note."}""",
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
      clock = IncrementingClock(start = 1_400L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Write a short note and then answer."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Wrote the note.", result.stdout)
    assertEquals(2, gateway.requests.size)
    assertTrue(gateway.requests[1].prompt.contains("[Working State]"))
    assertTrue(gateway.requests[1].prompt.contains("Write file_path=notes.txt"))
    assertTrue(gateway.requests[1].prompt.contains("source=workspace_mutation"))
    assertEquals("1", gateway.requests[1].metadata["contextWorkingStateRecentActionCount"])
  }

  @Test
  fun runPromptTaskPersistsResolvedWorkingStateIntoConfiguredStore() {
    val workspaceRoot = temporaryFolder.newFolder("agent-working-state-store")
    val todoStore = InMemoryAgentTodoStore(
      initialEntries = listOf(
        AgentTodoEntry(
          content = "Wire working state persistence",
          status = AgentTodoStatus.IN_PROGRESS,
          activeForm = "Wiring working state persistence",
        ),
        AgentTodoEntry(
          content = "Add persistence tests",
          status = AgentTodoStatus.PENDING,
        ),
      ),
    )
    val workingStateStore = InMemoryWorkingStateStore()
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"final","answer":"Stored the working state."}""",
      ),
    )
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
          todoStore = todoStore,
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(
        maxTurns = 2,
        maxToolCalls = 0,
        workingStateStore = workingStateStore,
      ),
      clock = IncrementingClock(start = 1_800L)::next,
    )

    val task = promptTask(input = "Continue the runtime rollout.")
    val result = runtime.execute(
      task = task,
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals(task.id, workingStateStore.snapshot().objective?.taskId)
    assertEquals(task.id, workingStateStore.snapshot().objective?.runId)
    assertEquals("Continue the runtime rollout.", workingStateStore.snapshot().objective?.primaryGoal)
    assertEquals("Wiring working state persistence", workingStateStore.snapshot().objective?.currentSubgoal)
    assertEquals(
      listOf("Add persistence tests"),
      workingStateStore.snapshot().nextActions.map { entry -> entry.text },
    )
  }

  @Test
  fun runPromptTaskInjectsResumeCheckpointWorkingStateIntoPrompt() {
    val workspaceRoot = temporaryFolder.newFolder("agent-working-state-resume-prompt")
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"final","answer":"Resumed from the saved checkpoint."}""",
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
        maxTurns = 2,
        maxToolCalls = 0,
        promptResumeCheckpointBoundary = OpenCrayPromptCheckpointBoundary.TOOL_RESULT_COMMITTED,
        promptResumeState = OpenCrayPromptResumeState(
          turnIndex = 1,
          toolCallCount = 1,
        ),
      ),
      clock = IncrementingClock(start = 1_820L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Continue after checkpoint restore."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Resumed from the saved checkpoint.", result.stdout)
    assertEquals(1, gateway.requests.size)
    assertTrue(gateway.requests.single().prompt.contains("[Working State]"))
    assertTrue(
      gateway.requests.single().prompt.contains(
        "Resume checkpoint turn=1 tool_calls=1 pending_actions=0 [source=resume_checkpoint; why=tool_result_committed]",
      ),
    )
    assertTrue(
      gateway.requests.single().prompt.contains(
        "Continue from the saved checkpoint state instead of restarting from the original task input.",
      ),
    )
    assertEquals(
      "true",
      gateway.requests.single().metadata["contextWorkingStateSynthesizedFromResumeContext"],
    )
  }

  @Test
  fun runPromptTaskInjectsSupplementsAtTurnStartBeforeNextLlmRequest() {
    val workspaceRoot = temporaryFolder.newFolder("agent-supplement-workspace")
    Files.write(
      workspaceRoot.toPath().resolve("README.md"),
      "hello from workspace".toByteArray(StandardCharsets.UTF_8),
    )

    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"workspace_read_file","arguments":{"path":"README.md"}}""",
        """{"type":"final","answer":"I saw the supplement."}""",
      ),
    )
    val eventSink = RecordingEventSink()
    var supplementProviderCalls = 0
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
        supplementInputProvider = { _, _ ->
          supplementProviderCalls += 1
          if (supplementProviderCalls == 2) {
            listOf(
              OpenCraySupplementInput(
                entryId = "supplement-1",
                text = "Also verify the tests before you answer.",
                createdAtEpochMs = 1_500L,
              ),
            )
          } else {
            emptyList()
          }
        },
      ),
      eventSink = eventSink,
      clock = IncrementingClock(start = 1_000L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Read the README and then answer."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("I saw the supplement.", result.stdout)
    assertEquals(2, gateway.requests.size)
    assertFalse(gateway.requests[0].prompt.contains("Also verify the tests before you answer."))
    assertTrue(gateway.requests[1].prompt.contains("Also verify the tests before you answer."))
    val supplementEvent = visibleSupplementEvents(eventSink.events).single()
    assertEquals("supplement-1", supplementEvent.entryId)
    assertEquals(1, supplementEvent.turn)
    assertEquals("Also verify the tests before you answer.", supplementEvent.text)
    assertEquals("post_tool_pre_model", supplementEvent.checkpoint)
    val resumeState = requireNotNull(
      OpenCrayPromptResumeMetadata.decodeFromMetadata(
        metadata = supplementEvent.metadata,
        json = Json { ignoreUnknownKeys = true },
      ),
    )
    assertEquals(1, resumeState.turnIndex)
    assertEquals(1, resumeState.toolCallCount)
    assertEquals(RuntimeConversationRole.USER, resumeState.transcript.lastOrNull()?.role)
    assertEquals("Also verify the tests before you answer.", resumeState.transcript.lastOrNull()?.content)
  }

  @Test
  fun runPromptTaskMarksInitialSupplementsWithTurnStartCheckpoint() {
    val workspaceRoot = temporaryFolder.newFolder("agent-initial-supplement-workspace")
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"final","answer":"I saw the initial supplement."}""",
      ),
    )
    val eventSink = RecordingEventSink()
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(
        maxTurns = 2,
        supplementInputProvider = { _, _ ->
          listOf(
            OpenCraySupplementInput(
              entryId = "supplement-initial-1",
              text = "Start from the workspace root.",
              createdAtEpochMs = 900L,
            ),
          )
        },
      ),
      eventSink = eventSink,
      clock = IncrementingClock(start = 950L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Answer directly."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("I saw the initial supplement.", result.stdout)
    assertEquals(1, gateway.requests.size)
    assertTrue(gateway.requests.single().prompt.contains("Start from the workspace root."))
    val supplementEvent = visibleSupplementEvents(eventSink.events).single()
    assertEquals("supplement-initial-1", supplementEvent.entryId)
    assertEquals("turn_start", supplementEvent.checkpoint)
    val resumeState = requireNotNull(
      OpenCrayPromptResumeMetadata.decodeFromMetadata(
        metadata = supplementEvent.metadata,
        json = Json { ignoreUnknownKeys = true },
      ),
    )
    assertEquals(0, resumeState.turnIndex)
    assertEquals(0, resumeState.toolCallCount)
    assertEquals(RuntimeConversationRole.USER, resumeState.transcript.lastOrNull()?.role)
    assertEquals("Start from the workspace root.", resumeState.transcript.lastOrNull()?.content)
  }

  @Test
  fun runPromptTaskEmitsJournalCheckpointMarkersForNonJournalBoundaries() {
    val workspaceRoot = temporaryFolder.newFolder("agent-hidden-checkpoint-markers")
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"final","answer":"No tools needed."}""",
      ),
    )
    val eventSink = RecordingEventSink()
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(maxTurns = 2),
      eventSink = eventSink,
      clock = IncrementingClock(start = 1_300L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Answer directly without tools."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    val checkpointMarkers = eventSink.events
      .filterIsInstance<OpenCraySupplementEvent>()
      .filter { event -> event.text.isBlank() && event.checkpoint == "internal_prompt_checkpoint" }
    assertEquals(2, checkpointMarkers.size)
    assertEquals(
      listOf(
        OpenCrayPromptCheckpointBoundary.PRE_MODEL_REQUEST,
        OpenCrayPromptCheckpointBoundary.ACTION_BATCH_PARSED,
      ),
      checkpointMarkers.map { event ->
        OpenCrayPromptResumeMetadata.decodeCheckpointBoundary(event.metadata)
      },
    )
    val actionBatchResumeState = requireNotNull(
      OpenCrayPromptResumeMetadata.decodeFromMetadata(
        metadata = checkpointMarkers.last().metadata,
        json = Json { ignoreUnknownKeys = true },
      ),
    )
    assertEquals(0, actionBatchResumeState.turnIndex)
    assertEquals(1, actionBatchResumeState.pendingActions.size)
    val actionBatchEnvelope = requireNotNull(actionBatchResumeState.localContinuationEnvelope)
    assertTrue(actionBatchEnvelope.gatewayMessages.isNotEmpty())
    assertTrue(actionBatchEnvelope.durableContextPrompt.orEmpty().contains("[Tool Protocol]"))
    assertFalse(actionBatchEnvelope.dynamicContextPrompt.orEmpty().contains("[Task Metadata]"))
  }

  @Test
  fun runPromptTaskMarksFinalAssistantAndResultWithFinalizationCheckpoint() {
    val workspaceRoot = temporaryFolder.newFolder("agent-finalization-checkpoint")
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"final","answer":"No tools needed."}""",
      ),
    )
    val eventSink = RecordingEventSink()
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(maxTurns = 2),
      eventSink = eventSink,
      clock = IncrementingClock(start = 1_360L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Answer directly without tools."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    val finalAssistantEvent = eventSink.events
      .filterIsInstance<OpenCrayAssistantEvent>()
      .single { event -> event.isFinal }
    assertEquals(
      OpenCrayPromptCheckpointBoundary.FINALIZATION_COMPLETE,
      OpenCrayPromptResumeMetadata.decodeCheckpointBoundary(finalAssistantEvent.metadata),
    )
    assertEquals(
      OpenCrayPromptCheckpointBoundary.FINALIZATION_COMPLETE,
      OpenCrayPromptResumeMetadata.decodeCheckpointBoundary(result.metadata),
    )
    val finalizationState = requireNotNull(
      OpenCrayPromptResumeMetadata.decodeFromMetadata(
        metadata = finalAssistantEvent.metadata,
        json = Json { ignoreUnknownKeys = true },
      ),
    )
    assertEquals(1, finalizationState.turnIndex)
    assertEquals(0, finalizationState.pendingActions.size)
  }

  @Test
  fun failedNonTerminalToolResultStillEmitsGeneralResumeCheckpoint() {
    val workspaceRoot = temporaryFolder.newFolder("agent-failed-tool-general-resume")
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"Read","arguments":{"file_path":"missing.txt"}}""",
        """{"type":"final","answer":"Handled the missing file."}""",
      ),
    )
    val eventSink = RecordingEventSink()
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(maxTurns = 4, maxToolCalls = 2),
      eventSink = eventSink,
      clock = IncrementingClock(start = 1_600L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Try to read the missing file and then recover."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Handled the missing file.", result.stdout)
    val toolResultEvent = eventSink.events.filterIsInstance<OpenCrayToolResultEvent>().single()
    assertEquals(AgentToolResultStatus.FAILED, toolResultEvent.result.status)
    val resumeState = requireNotNull(
      OpenCrayPromptResumeMetadata.decodeFromMetadata(
        metadata = toolResultEvent.result.metadata,
        json = Json { ignoreUnknownKeys = true },
      ),
    )
    assertEquals(1, resumeState.turnIndex)
    assertEquals(1, resumeState.toolCallCount)
    assertEquals(RuntimeConversationMessageKind.TOOL_RESULT, resumeState.transcript.lastOrNull()?.kind)
    assertEquals(RuntimeConversationRole.TOOL, resumeState.transcript.lastOrNull()?.role)
  }

  @Test
  fun runPromptTaskMarksAnthropicToolBoundarySupplementsWithPostToolCheckpoint() {
    val workspaceRoot = temporaryFolder.newFolder("agent-anthropic-supplement-workspace")
    Files.write(
      workspaceRoot.toPath().resolve("README.md"),
      "anthropic tool boundary".toByteArray(StandardCharsets.UTF_8),
    )

    val selection = LiteLlmRouteSelectionMetadata(
      profileId = "test-profile",
      routeId = "test-route",
      providerId = "anthropic",
      model = "claude-test",
      attemptIndex = 0,
    )
    val requests = mutableListOf<LiteLlmGatewayRequest>()
    var requestIndex = 0
    val gateway = object : LiteLlmGateway {
      override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
        requests += request
        return if (requestIndex++ == 0) {
          LiteLlmGatewayResult(
            requestId = request.requestId,
            status = LiteLlmGatewayStatus.SUCCESS,
            completionMode = LiteLlmCompletionMode.PRIMARY,
            completion = LiteLlmStructuredCompletion(
              toolCalls = listOf(
                LiteLlmStructuredToolCall(
                  id = "toolu_1",
                  toolName = "Read",
                  arguments = JsonObject(
                    mapOf("file_path" to JsonPrimitive("README.md")),
                  ),
                ),
              ),
            ),
            selectedRoute = selection,
            attempts = listOf(
              LiteLlmAttemptRecord(
                route = selection,
                outcome = LiteLlmAttemptOutcome.SUCCESS,
                outputChars = 0,
                startedAtEpochMs = 12_000L,
                finishedAtEpochMs = 12_001L,
              ),
            ),
            startedAtEpochMs = 12_000L,
            finishedAtEpochMs = 12_001L,
            metadata = mapOf(
              LiteLlmMetadataKeys.PROVIDER_RESPONSE_SHAPE to "anthropic_tool_use",
              LiteLlmMetadataKeys.NATIVE_TOOL_CALL_OBSERVED to "true",
            ),
          )
        } else {
          LiteLlmGatewayResult(
            requestId = request.requestId,
            status = LiteLlmGatewayStatus.SUCCESS,
            completionMode = LiteLlmCompletionMode.PRIMARY,
            completion = LiteLlmStructuredCompletion(
              finalText = "Anthropic tool-boundary supplement applied.",
              rawText = "Anthropic tool-boundary supplement applied.",
            ),
            selectedRoute = selection,
            attempts = listOf(
              LiteLlmAttemptRecord(
                route = selection,
                outcome = LiteLlmAttemptOutcome.SUCCESS,
                outputChars = 41,
                startedAtEpochMs = 12_002L,
                finishedAtEpochMs = 12_003L,
              ),
            ),
            startedAtEpochMs = 12_002L,
            finishedAtEpochMs = 12_003L,
            metadata = mapOf(
              LiteLlmMetadataKeys.PROVIDER_RESPONSE_SHAPE to "anthropic_text",
              LiteLlmMetadataKeys.NATIVE_TOOL_CALL_OBSERVED to "false",
            ),
          )
        }
      }
    }
    val eventSink = RecordingEventSink()
    var supplementProviderCalls = 0
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
        supplementInputProvider = { _, _ ->
          supplementProviderCalls += 1
          if (supplementProviderCalls == 2) {
            listOf(
              OpenCraySupplementInput(
                entryId = "supplement-anthropic-1",
                text = "Use the repository root as the workspace.",
                createdAtEpochMs = 12_500L,
              ),
            )
          } else {
            emptyList()
          }
        },
        llmMetadata = mapOf(
          "protocol" to "anthropic",
          "nativeToolCallingAvailable" to "true",
        ),
      ),
      eventSink = eventSink,
      clock = IncrementingClock(start = 12_600L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Read the README and then answer."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Anthropic tool-boundary supplement applied.", result.stdout)
    assertEquals(2, requests.size)
    assertEquals("full_rebuild", requests[0].metadata["localContinuationMode"])
    assertEquals("local_delta", requests[1].metadata["localContinuationMode"])
    assertEquals("transcript_delta", requests[1].metadata["localContinuationReason"])
    val tailMessages = requests[1].messages.takeLast(3)
    assertEquals(
      listOf(
        LiteLlmGatewayMessageRole.ASSISTANT,
        LiteLlmGatewayMessageRole.TOOL,
        LiteLlmGatewayMessageRole.USER,
      ),
      tailMessages.map { message -> message.role },
    )
    assertEquals("toolu_1", tailMessages[0].toolCalls.single().id)
    assertEquals("toolu_1", tailMessages[1].toolResult?.toolCallId)
    assertEquals("Read", tailMessages[1].toolResult?.toolName)
    assertEquals(
      "Use the repository root as the workspace.",
      tailMessages[2].content,
    )
    val supplementEvent = visibleSupplementEvents(eventSink.events).single()
    assertEquals("supplement-anthropic-1", supplementEvent.entryId)
    assertEquals("post_tool_pre_model", supplementEvent.checkpoint)
  }

  @Test
  fun successfulToolResultEventCarriesGeneralResumeCheckpointMetadata() {
    val workspaceRoot = temporaryFolder.newFolder("agent-responses-general-checkpoint")
    val selection = LiteLlmRouteSelectionMetadata(
      profileId = "test-profile",
      routeId = "responses-route",
      providerId = "openai",
      model = "gpt-5-mini",
      attemptIndex = 0,
    )
    val eventSink = RecordingEventSink()
    var requestIndex = 0
    val gateway = object : LiteLlmGateway {
      override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
        return if (requestIndex++ == 0) {
          LiteLlmGatewayResult(
            requestId = request.requestId,
            status = LiteLlmGatewayStatus.SUCCESS,
            completionMode = LiteLlmCompletionMode.PRIMARY,
            completion = LiteLlmStructuredCompletion(
              toolCalls = listOf(
                LiteLlmStructuredToolCall(
                  id = "call_1",
                  toolName = "LS",
                  arguments = JsonObject(
                    mapOf("path" to JsonPrimitive(".")),
                  ),
                ),
              ),
            ),
            providerResponseId = "resp_general_1",
            providerLineageId = "lineage_general_1",
            selectedRoute = selection,
            attempts = listOf(
              LiteLlmAttemptRecord(
                route = selection,
                outcome = LiteLlmAttemptOutcome.SUCCESS,
                outputChars = 0,
                startedAtEpochMs = 42_000L,
                finishedAtEpochMs = 42_001L,
              ),
            ),
            startedAtEpochMs = 42_000L,
            finishedAtEpochMs = 42_001L,
          )
        } else {
          LiteLlmGatewayResult(
            requestId = request.requestId,
            status = LiteLlmGatewayStatus.SUCCESS,
            completionMode = LiteLlmCompletionMode.PRIMARY,
            completion = LiteLlmStructuredCompletion(
              finalText = "Checkpointed continuation finished.",
            ),
            providerResponseId = "resp_general_2",
            providerLineageId = "lineage_general_2",
            selectedRoute = selection,
            attempts = listOf(
              LiteLlmAttemptRecord(
                route = selection,
                outcome = LiteLlmAttemptOutcome.SUCCESS,
                outputChars = 0,
                startedAtEpochMs = 42_002L,
                finishedAtEpochMs = 42_003L,
              ),
            ),
            startedAtEpochMs = 42_002L,
            finishedAtEpochMs = 42_003L,
          )
        }
      }
    }
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
        llmMetadata = mapOf(
          "protocol" to "openai_responses",
          "responseApiPreferred" to "true",
          "nativeToolCallingAvailable" to "true",
          "responsesContinuationSupported" to "true",
          "nativeWebSearchEnabled" to "false",
        ),
      ),
      eventSink = eventSink,
      clock = IncrementingClock(start = 42_500L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "List the workspace and then answer."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    val toolResultEvent = eventSink.events.filterIsInstance<OpenCrayToolResultEvent>().first()
    val resumeState = requireNotNull(
      OpenCrayPromptResumeMetadata.decodeFromMetadata(
        metadata = toolResultEvent.result.metadata,
        json = Json { ignoreUnknownKeys = true },
      ),
    )
    assertEquals(1, resumeState.turnIndex)
    assertEquals(1, resumeState.toolCallCount)
    assertEquals("resp_general_1", resumeState.responsesPreviousResponseId)
    assertEquals("lineage_general_1", resumeState.responsesProviderLineageId)
    assertEquals(true, resumeState.responsesLineageTrusted)
    assertNull(resumeState.localContinuationEnvelope)
    assertEquals(1, resumeState.responsesPendingMessages.size)
    assertEquals("TOOL", resumeState.responsesPendingMessages.single().role)
    assertEquals("call_1", resumeState.responsesPendingMessages.single().toolResult?.toolCallId)
    assertEquals("LS", resumeState.responsesPendingMessages.single().toolResult?.toolName)
  }
}
