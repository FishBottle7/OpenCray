package com.opencray.runtime

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.RetryRequest
import com.opencray.core.orchestrator.RuntimeExecutionHooks
import com.opencray.core.orchestrator.SuspensionRequest
import com.opencray.llm.LiteLlmAttemptOutcome
import com.opencray.llm.LiteLlmAttemptRecord
import com.opencray.llm.LiteLlmAssistantPhase
import com.opencray.llm.LiteLlmBuiltinWebSearchObservation
import com.opencray.llm.LiteLlmBuiltinWebSearchSource
import com.opencray.llm.LiteLlmBuiltinToolType
import com.opencray.llm.LiteLlmCompletionMode
import com.opencray.llm.LiteLlmGateway
import com.opencray.llm.LiteLlmGatewayMessageRole
import com.opencray.llm.LiteLlmGatewayRequest
import com.opencray.llm.LiteLlmGatewayResult
import com.opencray.llm.LiteLlmMetadataKeys
import com.opencray.llm.LiteLlmGatewayStatus
import com.opencray.llm.LiteLlmRouteSelectionMetadata
import com.opencray.llm.LiteLlmStructuredCompletion
import com.opencray.llm.LiteLlmStructuredFinalAttachment
import com.opencray.llm.LiteLlmStructuredToolCall
import com.opencray.runtime.bootstrap.BootstrapContext
import com.opencray.runtime.bootstrap.BootstrapFileTrace
import com.opencray.runtime.bootstrap.BootstrapMode
import com.opencray.runtime.bootstrap.BootstrapSnippet
import com.opencray.runtime.bootstrap.BootstrapTrace
import com.opencray.runtime.compaction.DurableCompactionContext
import com.opencray.runtime.compaction.DurableCompactionTrace
import com.opencray.runtime.context.AgentRuntimeSessionContext
import com.opencray.runtime.context.LiveContextTrace
import com.opencray.runtime.context.RuntimeConversationAssistantPhase
import com.opencray.runtime.context.RuntimeConversationAttachment
import com.opencray.runtime.context.RuntimeConversationAttachmentKind
import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.context.RuntimeConversationMessageKind
import com.opencray.runtime.context.RuntimeConversationCommentary
import com.opencray.runtime.context.RuntimeConversationRole
import com.opencray.runtime.memory.MemoryFlushOutcome
import com.opencray.runtime.memory.MemoryFlushTrace
import com.opencray.runtime.memory.MemoryKind
import com.opencray.runtime.memory.MemoryRecallResult
import com.opencray.runtime.memory.MemoryRecallTrace
import com.opencray.runtime.memory.MemoryRecallSelectedTrace
import com.opencray.runtime.memory.MemoryScope
import com.opencray.runtime.memory.MemoryStatus
import com.opencray.runtime.memory.MemoryToolContext
import com.opencray.runtime.memory.RetrievedMemory
import com.opencray.runtime.process.AgentProcessRegistry
import com.opencray.runtime.process.ManagedProcessSnapshot
import com.opencray.runtime.process.ManagedProcessStartRequest
import com.opencray.runtime.process.ManagedProcessStatus
import com.opencray.runtime.subagent.InMemorySubAgentExecutionCoordinator
import com.opencray.runtime.subagent.SubAgentActiveExecution
import com.opencray.runtime.subagent.SubAgentContinuationKind
import com.opencray.runtime.subagent.SubAgentExecutionSnapshot
import com.opencray.runtime.subagent.SubAgentExecutionState
import com.opencray.runtime.subagent.SubAgentHandleState
import com.opencray.runtime.subagent.withClearedChildPromptCheckpoint
import com.opencray.runtime.subagent.withUpdatedChildPromptCheckpoint
import com.opencray.runtime.skills.SkillCatalogResolver
import com.opencray.runtime.skills.SkillInventory
import com.opencray.runtime.skills.SkillInventoryTrace
import com.opencray.runtime.skills.VisibleSkill
import com.opencray.runtime.skills.VisibleSkillTrace
import com.opencray.runtime.web.WebSearchHit
import com.opencray.runtime.web.WebSearchProvider
import com.opencray.runtime.web.WebSearchRequest
import com.opencray.runtime.web.WebSearchResult
import com.opencray.runtime.workingstate.InMemoryWorkingStateStore
import com.opencray.runtime.workingstate.WorkingState
import com.opencray.runtime.workingstate.WorkingStateEntry
import com.opencray.runtime.workingstate.WorkingStateObjective
import com.opencray.runtime.workingstate.WorkingStateStore
import com.opencray.skills.SkillExecutionContext
import com.opencray.skills.SkillInvocationControl
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class OpenCrayAgentRuntimeTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

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
  fun runPromptTaskFreezesProjectedToolReplayAcrossResumeAndFullRebuild() {
    val workspaceRoot = temporaryFolder.newFolder("agent-frozen-tool-replay")
    val attachmentPayload = "data:text/plain;base64," + "A".repeat(12_000)
    Files.write(
      workspaceRoot.toPath().resolve("blob.txt"),
      attachmentPayload.toByteArray(StandardCharsets.UTF_8),
    )
    val task = promptTask(input = "Read blob.txt and then answer.")
    val eventSink = RecordingEventSink()
    val initialGateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"workspace_read_file","arguments":{"path":"blob.txt"}}""",
        """{"type":"final","answer":"Read the blob."}""",
      ),
    )
    val initialRuntime = OpenCrayAgentRuntime(
      gateway = initialGateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(maxTurns = 4, maxToolCalls = 2),
      eventSink = eventSink,
      clock = IncrementingClock(start = 6_000L)::next,
    )

    val initialResult = initialRuntime.execute(
      task = task,
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, initialResult.status)
    assertEquals(2, initialGateway.requests.size)
    val initialProjectedToolResult = requireNotNull(
      initialGateway.requests[1].messages.firstOrNull { message ->
        message.role == LiteLlmGatewayMessageRole.TOOL &&
          message.toolResult?.toolName == "workspace_read_file"
      }?.toolResult,
    )
    assertTrue(initialProjectedToolResult.content.contains("[frozen replay preview]"))
    assertTrue(initialProjectedToolResult.content.contains("projection_reasons=attachment_like_content"))

    val checkpointState = requireNotNull(
      OpenCrayPromptResumeMetadata.decodeFromMetadata(
        metadata = eventSink.events
          .filterIsInstance<OpenCrayToolResultEvent>()
          .first()
          .result
          .metadata,
        json = Json { ignoreUnknownKeys = true },
      ),
    )
    assertEquals(1, checkpointState.replayToolResultProjections.size)
    assertTrue(checkpointState.transcript.last().content.contains(attachmentPayload.take(256)))
    assertEquals(
      initialProjectedToolResult.content,
      checkpointState.replayToolResultProjections.values.single().projectedToolResult.content,
    )

    val resumedGateway = RecordingGateway(
      outputs = listOf(
        """{"type":"final","answer":"Replayed the same frozen preview."}""",
      ),
    )
    val resumedRuntime = OpenCrayAgentRuntime(
      gateway = resumedGateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(
        maxTurns = 4,
        maxToolCalls = 2,
        promptResumeState = checkpointState.copy(localContinuationEnvelope = null),
      ),
      clock = IncrementingClock(start = 6_500L)::next,
    )

    val resumedResult = resumedRuntime.execute(
      task = task,
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, resumedResult.status)
    assertEquals("Replayed the same frozen preview.", resumedResult.stdout)
    assertEquals(1, resumedGateway.requests.size)
    assertEquals("full_rebuild", resumedGateway.requests.single().metadata["localContinuationMode"])
    assertEquals("no_envelope", resumedGateway.requests.single().metadata["localContinuationReason"])
    val resumedProjectedToolResult = requireNotNull(
      resumedGateway.requests.single().messages.firstOrNull { message ->
        message.role == LiteLlmGatewayMessageRole.TOOL &&
          message.toolResult?.toolName == "workspace_read_file"
      }?.toolResult,
    )
    assertEquals(initialProjectedToolResult.content, resumedProjectedToolResult.content)
  }

  @Test
  fun runPromptTaskFallsBackWhenLocalContinuationToolPoolFingerprintChanges() {
    assertLocalContinuationFingerprintFallback(
      expectedLocalContinuationReason = "tool_pool_changed",
      expectedContextCacheBreakReason = "tool_pool_changed",
    ) { envelope ->
      envelope.copy(toolPoolFingerprint = "mismatched-tool-pool")
    }
  }

  @Test
  fun runPromptTaskFallsBackWhenLocalContinuationToolSchemaFingerprintChanges() {
    assertLocalContinuationFingerprintFallback(
      expectedLocalContinuationReason = "tool_schema_changed",
      expectedContextCacheBreakReason = "tool_schema_changed",
    ) { envelope ->
      envelope.copy(toolSchemaFingerprint = "mismatched-tool-schema")
    }
  }

  @Test
  fun runPromptTaskFallsBackWhenLocalContinuationRequestSettingsFingerprintChanges() {
    assertLocalContinuationFingerprintFallback(
      expectedLocalContinuationReason = "user_setting_changed",
      expectedContextCacheBreakReason = "user_setting_changed",
    ) { envelope ->
      envelope.copy(requestSettingsFingerprint = "mismatched-request-settings")
    }
  }

  @Test
  fun runPromptTaskFallsBackWhenDurableContextZoneChanges() {
    assertLocalContinuationFingerprintFallback(
      expectedLocalContinuationReason = "durable_context_changed",
      expectedContextCacheBreakReason = "durable_context_changed",
    ) { envelope ->
      envelope.copy(
        frontContextPrompts = listOf("mismatched durable context") + envelope.frontContextPrompts.drop(1),
        durableContextPrompt = "mismatched durable context",
      )
    }
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
  fun runPromptTaskClearsLiveDraftBeforeContinuingToToolCall() {
    val workspaceRoot = temporaryFolder.newFolder("agent-live-draft-toolcall-workspace")
    Files.write(
      workspaceRoot.toPath().resolve("README.md"),
      "draft tool call".toByteArray(StandardCharsets.UTF_8),
    )

    val selection = LiteLlmRouteSelectionMetadata(
      profileId = "test-profile",
      routeId = "test-route",
      providerId = "openai",
      model = "gpt-test",
      attemptIndex = 0,
    )
    var requestIndex = 0
    val gateway = object : LiteLlmGateway {
      override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
        return if (requestIndex++ == 0) {
          request.streamObserver.onVisibleTextSnapshot("Inspecting the repository")
          LiteLlmGatewayResult(
            requestId = request.requestId,
            status = LiteLlmGatewayStatus.SUCCESS,
            completionMode = LiteLlmCompletionMode.PRIMARY,
            completion = LiteLlmStructuredCompletion(
              toolCalls = listOf(
                LiteLlmStructuredToolCall(
                  id = "call-readme-1",
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
                outputChars = 24,
                startedAtEpochMs = 2_000L,
                finishedAtEpochMs = 2_100L,
              ),
            ),
            startedAtEpochMs = 2_000L,
            finishedAtEpochMs = 2_100L,
          )
        } else {
          LiteLlmGatewayResult(
            requestId = request.requestId,
            status = LiteLlmGatewayStatus.SUCCESS,
            completionMode = LiteLlmCompletionMode.PRIMARY,
            completion = LiteLlmStructuredCompletion(
              finalText = "Done.",
              rawText = "Done.",
            ),
            selectedRoute = selection,
            attempts = listOf(
              LiteLlmAttemptRecord(
                route = selection,
                outcome = LiteLlmAttemptOutcome.SUCCESS,
                outputChars = 5,
                startedAtEpochMs = 2_200L,
                finishedAtEpochMs = 2_300L,
              ),
            ),
            startedAtEpochMs = 2_200L,
            finishedAtEpochMs = 2_300L,
          )
        }
      }
    }
    val eventSink = RecordingEventSink()
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(maxTurns = 4, maxToolCalls = 1),
      eventSink = eventSink,
      clock = IncrementingClock(start = 2_000L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Read README.md and answer."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Done.", result.stdout)
    assertEquals(listOf("Inspecting the repository"), eventSink.assistantDrafts)
    assertEquals(1, eventSink.assistantDraftClearCount)
  }

  @Test
  fun runPromptTaskStreamsOnlyStructuredFinalAnswerDraftText() {
    val workspaceRoot = temporaryFolder.newFolder("agent-structured-final-draft-workspace")
    val selection = LiteLlmRouteSelectionMetadata(
      profileId = "test-profile",
      routeId = "test-route",
      providerId = "openai",
      model = "gpt-test",
      attemptIndex = 0,
    )
    val gateway = object : LiteLlmGateway {
      override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
        request.streamObserver.onVisibleTextSnapshot("{\"type\":\"final\",\"answer\":\"Hel")
        request.streamObserver.onVisibleTextSnapshot("{\"type\":\"final\",\"answer\":\"Hello\"}")
        return LiteLlmGatewayResult(
          requestId = request.requestId,
          status = LiteLlmGatewayStatus.SUCCESS,
          completionMode = LiteLlmCompletionMode.PRIMARY,
          completion = LiteLlmStructuredCompletion(
            finalText = "Hello",
            rawText = "{\"type\":\"final\",\"answer\":\"Hello\"}",
          ),
          selectedRoute = selection,
          attempts = listOf(
            LiteLlmAttemptRecord(
              route = selection,
              outcome = LiteLlmAttemptOutcome.SUCCESS,
              outputChars = 33,
              startedAtEpochMs = 2_000L,
              finishedAtEpochMs = 2_100L,
            ),
          ),
          startedAtEpochMs = 2_000L,
          finishedAtEpochMs = 2_100L,
        )
      }
    }
    val eventSink = RecordingEventSink()
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(maxTurns = 2, maxToolCalls = 0),
      eventSink = eventSink,
      clock = IncrementingClock(start = 2_000L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Answer directly."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Hello", result.stdout)
    assertEquals(listOf("Hel", "Hello"), eventSink.assistantDrafts)
    assertEquals(0, eventSink.assistantDraftClearCount)
  }

  @Test
  fun runPromptTaskStreamsOnlyStructuredFinalDraftTextFromActionsBatch() {
    val workspaceRoot = temporaryFolder.newFolder("agent-structured-actions-draft-workspace")
    val selection = LiteLlmRouteSelectionMetadata(
      profileId = "test-profile",
      routeId = "test-route",
      providerId = "openai",
      model = "gpt-test",
      attemptIndex = 0,
    )
    val gateway = object : LiteLlmGateway {
      override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
        request.streamObserver.onVisibleTextSnapshot(
          "{\"actions\":[{\"type\":\"commentary\",\"text\":\"Checking the transcript first.\"}",
        )
        request.streamObserver.onVisibleTextSnapshot(
          "{\"actions\":[{\"type\":\"commentary\",\"text\":\"Checking the transcript first.\"},{\"type\":\"final\",\"answer\":\"Here is",
        )
        request.streamObserver.onVisibleTextSnapshot(
          "{\"actions\":[{\"type\":\"commentary\",\"text\":\"Checking the transcript first.\"},{\"type\":\"final\",\"answer\":\"Here is the final answer.\"}]}",
        )
        return LiteLlmGatewayResult(
          requestId = request.requestId,
          status = LiteLlmGatewayStatus.SUCCESS,
          completionMode = LiteLlmCompletionMode.PRIMARY,
          outputText = "{\"actions\":[{\"type\":\"commentary\",\"text\":\"Checking the transcript first.\"},{\"type\":\"final\",\"answer\":\"Here is the final answer.\"}]}",
          selectedRoute = selection,
          attempts = listOf(
            LiteLlmAttemptRecord(
              route = selection,
              outcome = LiteLlmAttemptOutcome.SUCCESS,
              outputChars = 132,
              startedAtEpochMs = 2_000L,
              finishedAtEpochMs = 2_100L,
            ),
          ),
          startedAtEpochMs = 2_000L,
          finishedAtEpochMs = 2_100L,
        )
      }
    }
    val eventSink = RecordingEventSink()
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(maxTurns = 2, maxToolCalls = 0),
      eventSink = eventSink,
      clock = IncrementingClock(start = 2_000L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Check first, then answer."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Here is the final answer.", result.stdout)
    assertEquals(
      listOf(
        "Here is",
        "Here is the final answer.",
      ),
      eventSink.assistantDrafts,
    )
    assertEquals(0, eventSink.assistantDraftClearCount)
  }

  @Test
  fun runPromptTaskStreamsIncompleteUserFacingJsonDrafts() {
    val workspaceRoot = temporaryFolder.newFolder("agent-json-draft-workspace")
    val selection = LiteLlmRouteSelectionMetadata(
      profileId = "test-profile",
      routeId = "json-draft-route",
      providerId = "openai",
      model = "gpt-test",
      attemptIndex = 0,
    )
    val gateway = object : LiteLlmGateway {
      override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
        request.streamObserver.onVisibleTextSnapshot("{\"status\":")
        request.streamObserver.onVisibleTextSnapshot("{\"status\":\"ok\"}")
        return LiteLlmGatewayResult(
          requestId = request.requestId,
          status = LiteLlmGatewayStatus.SUCCESS,
          completionMode = LiteLlmCompletionMode.PRIMARY,
          completion = LiteLlmStructuredCompletion(
            finalText = "{\"status\":\"ok\"}",
            rawText = "{\"status\":\"ok\"}",
          ),
          selectedRoute = selection,
          attempts = listOf(
            LiteLlmAttemptRecord(
              route = selection,
              outcome = LiteLlmAttemptOutcome.SUCCESS,
              outputChars = 15,
              startedAtEpochMs = 2_200L,
              finishedAtEpochMs = 2_300L,
            ),
          ),
          startedAtEpochMs = 2_200L,
          finishedAtEpochMs = 2_300L,
        )
      }
    }
    val eventSink = RecordingEventSink()
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(maxTurns = 2, maxToolCalls = 0),
      eventSink = eventSink,
      clock = IncrementingClock(start = 2_200L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Return a JSON status object."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("{\"status\":\"ok\"}", result.stdout)
    assertEquals(
      listOf(
        "{\"status\":",
        "{\"status\":\"ok\"}",
      ),
      eventSink.assistantDrafts,
    )
  }

  @Test
  fun runPromptTaskSuppressesStructuredFinalDraftWhenActionsBatchContainsToolCall() {
    val workspaceRoot = temporaryFolder.newFolder("agent-structured-actions-tool-final-draft-workspace")
    Files.write(
      workspaceRoot.toPath().resolve("README.md"),
      "tool batch draft".toByteArray(StandardCharsets.UTF_8),
    )
    val selection = LiteLlmRouteSelectionMetadata(
      profileId = "test-profile",
      routeId = "test-route",
      providerId = "openai",
      model = "gpt-test",
      attemptIndex = 0,
    )
    var requestIndex = 0
    val gateway = object : LiteLlmGateway {
      override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
        return if (requestIndex++ == 0) {
          request.streamObserver.onVisibleTextSnapshot(
            "{\"actions\":[{\"type\":\"commentary\",\"text\":\"Checking the transcript first.\"}",
          )
          request.streamObserver.onVisibleTextSnapshot(
            "{\"actions\":[{\"type\":\"commentary\",\"text\":\"Checking the transcript first.\"},{\"type\":\"tool_call\",\"tool_name\":\"Read\",\"arguments\":{\"file_path\":\"README.md\"}},{\"type\":\"final\",\"answer\":\"This final must stay hidden.\"}]}",
          )
          LiteLlmGatewayResult(
            requestId = request.requestId,
            status = LiteLlmGatewayStatus.SUCCESS,
            completionMode = LiteLlmCompletionMode.PRIMARY,
            outputText = "{\"actions\":[{\"type\":\"commentary\",\"text\":\"Checking the transcript first.\"},{\"type\":\"tool_call\",\"tool_name\":\"Read\",\"arguments\":{\"file_path\":\"README.md\"}},{\"type\":\"final\",\"answer\":\"This final must stay hidden.\"}]}",
            selectedRoute = selection,
            attempts = listOf(
              LiteLlmAttemptRecord(
                route = selection,
                outcome = LiteLlmAttemptOutcome.SUCCESS,
                outputChars = 200,
                startedAtEpochMs = 2_000L,
                finishedAtEpochMs = 2_100L,
              ),
            ),
            startedAtEpochMs = 2_000L,
            finishedAtEpochMs = 2_100L,
          )
        } else {
          LiteLlmGatewayResult(
            requestId = request.requestId,
            status = LiteLlmGatewayStatus.SUCCESS,
            completionMode = LiteLlmCompletionMode.PRIMARY,
            completion = LiteLlmStructuredCompletion(
              finalText = "Actual final after the tool call.",
              rawText = "Actual final after the tool call.",
            ),
            selectedRoute = selection,
            attempts = listOf(
              LiteLlmAttemptRecord(
                route = selection,
                outcome = LiteLlmAttemptOutcome.SUCCESS,
                outputChars = 33,
                startedAtEpochMs = 2_200L,
                finishedAtEpochMs = 2_300L,
              ),
            ),
            startedAtEpochMs = 2_200L,
            finishedAtEpochMs = 2_300L,
          )
        }
      }
    }
    val eventSink = RecordingEventSink()
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(maxTurns = 4, maxToolCalls = 1),
      eventSink = eventSink,
      clock = IncrementingClock(start = 2_000L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Check first, read README.md, then answer."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Actual final after the tool call.", result.stdout)
    assertTrue(eventSink.assistantDrafts.isEmpty())
    assertEquals(1, eventSink.assistantDraftClearCount)
  }

  @Test
  fun runPromptTaskSuppressesTopLevelStructuredCommentaryProgressAndStatusDraftText() {
    val workspaceRoot = temporaryFolder.newFolder("agent-top-level-structured-draft-workspace")
    val selection = LiteLlmRouteSelectionMetadata(
      profileId = "test-profile",
      routeId = "test-route",
      providerId = "openai",
      model = "gpt-test",
      attemptIndex = 0,
    )
    val gateway = object : LiteLlmGateway {
      override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
        request.streamObserver.onVisibleTextSnapshot(
          "{\"type\":\"commentary\",\"text\":\"Checking the transcript first.\"}",
        )
        request.streamObserver.onVisibleTextSnapshot(
          "{\"type\":\"progress\",\"text\":\"Still checking the workspace.\"}",
        )
        request.streamObserver.onVisibleTextSnapshot(
          "{\"type\":\"status\",\"text\":\"Ready to answer.\"}",
        )
        return LiteLlmGatewayResult(
          requestId = request.requestId,
          status = LiteLlmGatewayStatus.SUCCESS,
          completionMode = LiteLlmCompletionMode.PRIMARY,
          completion = LiteLlmStructuredCompletion(
            finalText = "Done.",
            rawText = "Done.",
          ),
          selectedRoute = selection,
          attempts = listOf(
            LiteLlmAttemptRecord(
              route = selection,
              outcome = LiteLlmAttemptOutcome.SUCCESS,
              outputChars = 5,
              startedAtEpochMs = 2_000L,
              finishedAtEpochMs = 2_100L,
            ),
          ),
          startedAtEpochMs = 2_000L,
          finishedAtEpochMs = 2_100L,
        )
      }
    }
    val eventSink = RecordingEventSink()
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(maxTurns = 2, maxToolCalls = 0),
      eventSink = eventSink,
      clock = IncrementingClock(start = 2_000L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Answer directly."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Done.", result.stdout)
    assertTrue(eventSink.assistantDrafts.isEmpty())
    assertEquals(0, eventSink.assistantDraftClearCount)
  }

  @Test
  fun runPromptTaskSuppressesPartialStructuredJsonPrefixesUntilAnswerAppears() {
    val workspaceRoot = temporaryFolder.newFolder("agent-structured-prefix-draft-workspace")
    val selection = LiteLlmRouteSelectionMetadata(
      profileId = "test-profile",
      routeId = "test-route",
      providerId = "openai",
      model = "gpt-test",
      attemptIndex = 0,
    )
    val gateway = object : LiteLlmGateway {
      override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
        request.streamObserver.onVisibleTextSnapshot("{")
        request.streamObserver.onVisibleTextSnapshot("{\"type\":\"final\",\"answer\":\"Hel")
        request.streamObserver.onVisibleTextSnapshot("{\"type\":\"final\",\"answer\":\"Hello\"}")
        return LiteLlmGatewayResult(
          requestId = request.requestId,
          status = LiteLlmGatewayStatus.SUCCESS,
          completionMode = LiteLlmCompletionMode.PRIMARY,
          completion = LiteLlmStructuredCompletion(
            finalText = "Hello",
            rawText = "{\"type\":\"final\",\"answer\":\"Hello\"}",
          ),
          selectedRoute = selection,
          attempts = listOf(
            LiteLlmAttemptRecord(
              route = selection,
              outcome = LiteLlmAttemptOutcome.SUCCESS,
              outputChars = 33,
              startedAtEpochMs = 2_000L,
              finishedAtEpochMs = 2_100L,
            ),
          ),
          startedAtEpochMs = 2_000L,
          finishedAtEpochMs = 2_100L,
        )
      }
    }
    val eventSink = RecordingEventSink()
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(maxTurns = 2, maxToolCalls = 0),
      eventSink = eventSink,
      clock = IncrementingClock(start = 2_000L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Answer directly."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Hello", result.stdout)
    assertEquals(listOf("Hel", "Hello"), eventSink.assistantDrafts)
    assertEquals(0, eventSink.assistantDraftClearCount)
  }

  @Test
  fun runPromptTaskSuppressesStructuredToolInternalAndCommentaryDraftPayloads() {
    val workspaceRoot = temporaryFolder.newFolder("agent-structured-hidden-draft-workspace")
    val selection = LiteLlmRouteSelectionMetadata(
      profileId = "test-profile",
      routeId = "test-route",
      providerId = "openai",
      model = "gpt-test",
      attemptIndex = 0,
    )
    val gateway = object : LiteLlmGateway {
      override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
        request.streamObserver.onVisibleTextSnapshot(
          "{\"tool_calls\":[{\"tool_name\":\"Read\",\"arguments\":{\"file_path\":\"README.md\"}}]}",
        )
        request.streamObserver.onVisibleTextSnapshot(
          "{\"is_task_bearing_request\":true,\"user_affect\":\"neutral\"}",
        )
        request.streamObserver.onVisibleTextSnapshot(
          "{\"type\":\"commentary\",\"text\":\"Inspecting files\"}",
        )
        return LiteLlmGatewayResult(
          requestId = request.requestId,
          status = LiteLlmGatewayStatus.SUCCESS,
          completionMode = LiteLlmCompletionMode.PRIMARY,
          completion = LiteLlmStructuredCompletion(
            finalText = "Done.",
            rawText = "Done.",
          ),
          selectedRoute = selection,
          attempts = listOf(
            LiteLlmAttemptRecord(
              route = selection,
              outcome = LiteLlmAttemptOutcome.SUCCESS,
              outputChars = 5,
              startedAtEpochMs = 2_000L,
              finishedAtEpochMs = 2_100L,
            ),
          ),
          startedAtEpochMs = 2_000L,
          finishedAtEpochMs = 2_100L,
        )
      }
    }
    val eventSink = RecordingEventSink()
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(maxTurns = 2, maxToolCalls = 0),
      eventSink = eventSink,
      clock = IncrementingClock(start = 2_000L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Answer directly."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Done.", result.stdout)
    assertTrue(eventSink.assistantDrafts.isEmpty())
    assertEquals(0, eventSink.assistantDraftClearCount)
  }

  @Test
  fun runPromptTaskSuppressesNestedActionsInsideStructuredToolDraftPayloads() {
    val workspaceRoot = temporaryFolder.newFolder("agent-structured-hidden-nested-actions-workspace")
    val selection = LiteLlmRouteSelectionMetadata(
      profileId = "test-profile",
      routeId = "test-route",
      providerId = "openai",
      model = "gpt-test",
      attemptIndex = 0,
    )
    val gateway = object : LiteLlmGateway {
      override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
        request.streamObserver.onVisibleTextSnapshot(
          "{\"tool_calls\":[{\"tool_name\":\"Read\",\"arguments\":{\"actions\":[{\"type\":\"final\",\"answer\":\"leak\"}],\"file_path\":\"README.md\"}}]}",
        )
        return LiteLlmGatewayResult(
          requestId = request.requestId,
          status = LiteLlmGatewayStatus.SUCCESS,
          completionMode = LiteLlmCompletionMode.PRIMARY,
          completion = LiteLlmStructuredCompletion(
            finalText = "Done.",
            rawText = "Done.",
          ),
          selectedRoute = selection,
          attempts = listOf(
            LiteLlmAttemptRecord(
              route = selection,
              outcome = LiteLlmAttemptOutcome.SUCCESS,
              outputChars = 5,
              startedAtEpochMs = 2_000L,
              finishedAtEpochMs = 2_100L,
            ),
          ),
          startedAtEpochMs = 2_000L,
          finishedAtEpochMs = 2_100L,
        )
      }
    }
    val eventSink = RecordingEventSink()
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(maxTurns = 2, maxToolCalls = 0),
      eventSink = eventSink,
      clock = IncrementingClock(start = 2_000L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Answer directly."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Done.", result.stdout)
    assertTrue(eventSink.assistantDrafts.isEmpty())
    assertEquals(0, eventSink.assistantDraftClearCount)
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
  fun runPromptTaskReplaysToolResultsAndSupplementsWithoutResponsesContinuation() {
    val workspaceRoot = temporaryFolder.newFolder("agent-responses-continuation-workspace")
    Files.write(
      workspaceRoot.toPath().resolve("README.md"),
      "hello from workspace".toByteArray(StandardCharsets.UTF_8),
    )

    val selection = LiteLlmRouteSelectionMetadata(
      profileId = "test-profile",
      routeId = "test-route",
      providerId = "openai",
      model = "gpt-5-mini",
      attemptIndex = 0,
    )
    val requests = mutableListOf<LiteLlmGatewayRequest>()
    var requestIndex = 0
    var supplementProviderCalls = 0
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
                  id = "call_1",
                  toolName = "workspace_read_file",
                  arguments = JsonObject(
                    mapOf("path" to JsonPrimitive("README.md")),
                  ),
                ),
              ),
            ),
            providerResponseId = "resp_1",
            selectedRoute = selection,
            attempts = listOf(
              LiteLlmAttemptRecord(
                route = selection,
                outcome = LiteLlmAttemptOutcome.SUCCESS,
                outputChars = 0,
                startedAtEpochMs = 15_000L,
                finishedAtEpochMs = 15_001L,
              ),
            ),
            startedAtEpochMs = 15_000L,
            finishedAtEpochMs = 15_001L,
          )
        } else {
          LiteLlmGatewayResult(
            requestId = request.requestId,
            status = LiteLlmGatewayStatus.SUCCESS,
            completionMode = LiteLlmCompletionMode.PRIMARY,
            completion = LiteLlmStructuredCompletion(
              finalText = "I saw the continuation.",
            ),
            providerResponseId = "resp_2",
            selectedRoute = selection,
            attempts = listOf(
              LiteLlmAttemptRecord(
                route = selection,
                outcome = LiteLlmAttemptOutcome.SUCCESS,
                outputChars = 0,
                startedAtEpochMs = 15_002L,
                finishedAtEpochMs = 15_003L,
              ),
            ),
            startedAtEpochMs = 15_002L,
            finishedAtEpochMs = 15_003L,
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
        llmMetadata = mapOf(
          "responseApiPreferred" to "true",
          "nativeToolCallingAvailable" to "true",
        ),
      ),
      clock = IncrementingClock(start = 15_500L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Read the README and then answer."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("I saw the continuation.", result.stdout)
    assertEquals(2, requests.size)
    assertEquals(null, requests[0].previousResponseId)
    assertEquals(null, requests[1].previousResponseId)
    assertTrue(requests[1].responseApiPreferred)
    val secondTurnUserMessages = requests[1].messages
      .filter { message -> message.role == LiteLlmGatewayMessageRole.USER }
      .mapNotNull { message -> message.content }
    assertTrue(secondTurnUserMessages.contains("Read the README and then answer."))
    assertTrue(secondTurnUserMessages.contains("Also verify the tests before you answer."))
    val replayedToolCall = requests[1].messages
      .firstOrNull { message ->
        message.role == LiteLlmGatewayMessageRole.ASSISTANT &&
          message.toolCalls.singleOrNull()?.id == "call_1"
      }
    assertNotNull(replayedToolCall)
    assertEquals(
      "workspace_read_file",
      replayedToolCall?.toolCalls?.singleOrNull()?.toolName,
    )
    val replayedToolResult = requests[1].messages
      .firstOrNull { message -> message.role == LiteLlmGatewayMessageRole.TOOL }
      ?.toolResult
    assertNotNull(replayedToolResult)
    assertEquals("call_1", replayedToolResult?.toolCallId)
    assertEquals("workspace_read_file", replayedToolResult?.toolName)
    assertTrue(replayedToolResult?.content?.contains("hello from workspace") == true)
  }

  @Test
  fun runPromptTaskApprovalResumeFallsBackToFullTranscriptReplay() {
    val workspaceRoot = temporaryFolder.newFolder("agent-responses-approval-resume")
    val selection = LiteLlmRouteSelectionMetadata(
      profileId = "test-profile",
      routeId = "responses-route",
      providerId = "openai",
      model = "gpt-5-mini",
      attemptIndex = 0,
    )
    val initialGatewayRequests = mutableListOf<LiteLlmGatewayRequest>()
    val initialGateway = object : LiteLlmGateway {
      override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
        initialGatewayRequests += request
        return LiteLlmGatewayResult(
          requestId = request.requestId,
          status = LiteLlmGatewayStatus.SUCCESS,
          completionMode = LiteLlmCompletionMode.PRIMARY,
          completion = LiteLlmStructuredCompletion(
            toolCalls = listOf(
              LiteLlmStructuredToolCall(
                id = "call_1",
                toolName = "Write",
                arguments = JsonObject(
                  mapOf(
                    "file_path" to JsonPrimitive("note.txt"),
                    "content" to JsonPrimitive("hello"),
                  ),
                ),
              ),
            ),
          ),
          providerResponseId = "resp_1",
          providerLineageId = "lineage_1",
          selectedRoute = selection,
          attempts = listOf(
            LiteLlmAttemptRecord(
              route = selection,
              outcome = LiteLlmAttemptOutcome.SUCCESS,
              outputChars = 0,
              startedAtEpochMs = 40_000L,
              finishedAtEpochMs = 40_001L,
            ),
          ),
          startedAtEpochMs = 40_000L,
          finishedAtEpochMs = 40_001L,
        )
      }
    }
    val task = promptTask(
      input = "Write note.txt in safe mode.",
      metadata = mapOf("chatMode" to "SAFE"),
    )
    val initialRuntime = OpenCrayAgentRuntime(
      gateway = initialGateway,
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
      clock = IncrementingClock(start = 40_500L)::next,
    )

    val firstResult = initialRuntime.execute(
      task = task,
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.DENIED, firstResult.status)
    assertEquals(1, initialGatewayRequests.size)
    val resumeState = requireNotNull(
      OpenCrayPromptResumeMetadata.decodeFromMetadata(
        metadata = firstResult.metadata,
        json = Json { ignoreUnknownKeys = true },
      ),
    )
    assertEquals("resp_1", resumeState.responsesPreviousResponseId)
    assertEquals("lineage_1", resumeState.responsesProviderLineageId)
    assertEquals(true, resumeState.responsesLineageTrusted)
    assertTrue(resumeState.responsesPendingMessages.isEmpty())

    val resumedGatewayRequests = mutableListOf<LiteLlmGatewayRequest>()
    val resumedGateway = object : LiteLlmGateway {
      override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
        resumedGatewayRequests += request
        return LiteLlmGatewayResult(
          requestId = request.requestId,
          status = LiteLlmGatewayStatus.SUCCESS,
          completionMode = LiteLlmCompletionMode.PRIMARY,
          completion = LiteLlmStructuredCompletion(
            finalText = "Approved write completed.",
          ),
          providerResponseId = "resp_2",
          providerLineageId = "lineage_2",
          selectedRoute = selection,
          attempts = listOf(
            LiteLlmAttemptRecord(
              route = selection,
              outcome = LiteLlmAttemptOutcome.SUCCESS,
              outputChars = 0,
              startedAtEpochMs = 40_010L,
              finishedAtEpochMs = 40_011L,
            ),
          ),
          startedAtEpochMs = 40_010L,
          finishedAtEpochMs = 40_011L,
        )
      }
    }
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
        llmMetadata = mapOf(
          "protocol" to "openai_responses",
          "responseApiPreferred" to "true",
          "nativeToolCallingAvailable" to "true",
          "responsesContinuationSupported" to "true",
          "nativeWebSearchEnabled" to "false",
        ),
      ),
      clock = IncrementingClock(start = 40_600L)::next,
    )

    val resumedResult = resumedRuntime.execute(
      task = task,
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, resumedResult.status)
    assertEquals("Approved write completed.", resumedResult.stdout)
    assertEquals(1, resumedGatewayRequests.size)
    assertNull(resumedGatewayRequests.single().previousResponseId)
    assertEquals("full_rebuild", resumedGatewayRequests.single().metadata["localContinuationMode"])
    assertEquals(
      "responses_pending_tool_result_attachment_artifact",
      resumedGatewayRequests.single().metadata["localContinuationReason"],
    )
    assertTrue(
      resumedGatewayRequests.single().messages.any { message ->
        message.role == LiteLlmGatewayMessageRole.ASSISTANT &&
          message.toolCalls.any { toolCall ->
            toolCall.id == "call_1" && toolCall.toolName == "Write"
          }
      },
    )
    assertTrue(
      resumedGatewayRequests.single().messages.any { message ->
        val toolResult = message.toolResult
        message.role == LiteLlmGatewayMessageRole.TOOL &&
          toolResult?.toolCallId == "call_1" &&
          toolResult.toolName == "Write"
      },
    )
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

  @Test
  fun responsesContinuationUsesNativeDeltaWhenPendingToolResultKeepsPromptShapeStable() {
    val workspaceRoot = temporaryFolder.newFolder("agent-responses-native-delta")
    val selection = LiteLlmRouteSelectionMetadata(
      profileId = "test-profile",
      routeId = "responses-route",
      providerId = "openai",
      model = "gpt-5-mini",
      attemptIndex = 0,
    )
    val gatewayRequests = mutableListOf<LiteLlmGatewayRequest>()
    var requestIndex = 0
    val gateway = object : LiteLlmGateway {
      override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
        gatewayRequests += request
        return if (requestIndex++ == 0) {
          LiteLlmGatewayResult(
            requestId = request.requestId,
            status = LiteLlmGatewayStatus.SUCCESS,
            completionMode = LiteLlmCompletionMode.PRIMARY,
            completion = LiteLlmStructuredCompletion(
              toolCalls = listOf(
                LiteLlmStructuredToolCall(
                  id = "call_1",
                  toolName = "memory_search",
                  arguments = JsonObject(
                    mapOf("query" to JsonPrimitive("repo root")),
                  ),
                ),
              ),
            ),
            providerResponseId = "resp_native_1",
            providerLineageId = "lineage_native_1",
            selectedRoute = selection,
            attempts = listOf(
              LiteLlmAttemptRecord(
                route = selection,
                outcome = LiteLlmAttemptOutcome.SUCCESS,
                outputChars = 0,
                startedAtEpochMs = 49_000L,
                finishedAtEpochMs = 49_001L,
              ),
            ),
            startedAtEpochMs = 49_000L,
            finishedAtEpochMs = 49_001L,
          )
        } else {
          LiteLlmGatewayResult(
            requestId = request.requestId,
            status = LiteLlmGatewayStatus.SUCCESS,
            completionMode = LiteLlmCompletionMode.PRIMARY,
            completion = LiteLlmStructuredCompletion(
              finalText = "Used provider-native continuation.",
            ),
            providerResponseId = "resp_native_2",
            providerLineageId = "lineage_native_2",
            selectedRoute = selection,
            attempts = listOf(
              LiteLlmAttemptRecord(
                route = selection,
                outcome = LiteLlmAttemptOutcome.SUCCESS,
                outputChars = 0,
                startedAtEpochMs = 49_002L,
                finishedAtEpochMs = 49_003L,
              ),
            ),
            startedAtEpochMs = 49_002L,
            finishedAtEpochMs = 49_003L,
          )
        }
      }
    }
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
          memoryToolContext = MemoryToolContext(
            sessionId = "session-main",
            workspaceId = "workspace-main",
          ),
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
      clock = IncrementingClock(start = 49_500L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Search memory and then answer."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Used provider-native continuation.", result.stdout)
    assertEquals(2, gatewayRequests.size)
    assertNull(gatewayRequests[0].previousResponseId)
    assertEquals("resp_native_1", gatewayRequests[1].previousResponseId)
    assertEquals("responses_native", gatewayRequests[1].metadata["localContinuationMode"])
    assertEquals("responses_previous_response_id", gatewayRequests[1].metadata["localContinuationReason"])
    assertEquals("responses_native", result.metadata["localContinuationLastMode"])
    assertEquals("responses_previous_response_id", result.metadata["localContinuationLastReason"])
    assertEquals(1, gatewayRequests[1].messages.size)
    val toolResult = requireNotNull(gatewayRequests[1].messages.single().toolResult)
    assertEquals("call_1", toolResult.toolCallId)
    assertEquals("memory_search", toolResult.toolName)
    assertTrue(toolResult.content.contains("No matching projected memory snippets were found."))
  }

  @Test
  fun responsesContinuationUsesNativeDeltaForWorkspaceDiscoveryOwnedByReplay() {
    val workspaceRoot = temporaryFolder.newFolder("agent-responses-native-workspace-replay")
    Files.write(
      workspaceRoot.toPath().resolve("README.md"),
      "hello from workspace".toByteArray(StandardCharsets.UTF_8),
    )
    val selection = LiteLlmRouteSelectionMetadata(
      profileId = "test-profile",
      routeId = "responses-route",
      providerId = "openai",
      model = "gpt-5-mini",
      attemptIndex = 0,
    )
    val gatewayRequests = mutableListOf<LiteLlmGatewayRequest>()
    var requestIndex = 0
    val gateway = object : LiteLlmGateway {
      override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
        gatewayRequests += request
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
            providerResponseId = "resp_native_workspace_1",
            providerLineageId = "lineage_native_workspace_1",
            selectedRoute = selection,
            attempts = listOf(
              LiteLlmAttemptRecord(
                route = selection,
                outcome = LiteLlmAttemptOutcome.SUCCESS,
                outputChars = 0,
                startedAtEpochMs = 49_500L,
                finishedAtEpochMs = 49_501L,
              ),
            ),
            startedAtEpochMs = 49_500L,
            finishedAtEpochMs = 49_501L,
          )
        } else {
          LiteLlmGatewayResult(
            requestId = request.requestId,
            status = LiteLlmGatewayStatus.SUCCESS,
            completionMode = LiteLlmCompletionMode.PRIMARY,
            completion = LiteLlmStructuredCompletion(
              finalText = "Reused provider-native continuation for LS.",
            ),
            providerResponseId = "resp_native_workspace_2",
            providerLineageId = "lineage_native_workspace_2",
            selectedRoute = selection,
            attempts = listOf(
              LiteLlmAttemptRecord(
                route = selection,
                outcome = LiteLlmAttemptOutcome.SUCCESS,
                outputChars = 0,
                startedAtEpochMs = 49_502L,
                finishedAtEpochMs = 49_503L,
              ),
            ),
            startedAtEpochMs = 49_502L,
            finishedAtEpochMs = 49_503L,
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
      clock = IncrementingClock(start = 49_900L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "List the workspace and then answer."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Reused provider-native continuation for LS.", result.stdout)
    assertEquals(2, gatewayRequests.size)
    assertNull(gatewayRequests[0].previousResponseId)
    assertEquals("resp_native_workspace_1", gatewayRequests[1].previousResponseId)
    assertEquals("responses_native", gatewayRequests[1].metadata["localContinuationMode"])
    assertEquals("responses_previous_response_id", gatewayRequests[1].metadata["localContinuationReason"])
    assertEquals("responses_native", result.metadata["localContinuationLastMode"])
    assertEquals("responses_previous_response_id", result.metadata["localContinuationLastReason"])
    assertEquals(1, gatewayRequests[1].messages.size)
    val toolResult = requireNotNull(gatewayRequests[1].messages.single().toolResult)
    assertEquals("call_1", toolResult.toolCallId)
    assertEquals("LS", toolResult.toolName)
    assertTrue(toolResult.content.contains("README.md"))
  }

  @Test
  fun responsesContinuationFallsBackToTranscriptReplayWhenLegacyJsonFallbackIsEnabled() {
    val workspaceRoot = temporaryFolder.newFolder("agent-responses-continuation-replay")
    val selection = LiteLlmRouteSelectionMetadata(
      profileId = "test-profile",
      routeId = "responses-route",
      providerId = "openai",
      model = "gpt-5-mini",
      attemptIndex = 0,
    )
    val gatewayRequests = mutableListOf<LiteLlmGatewayRequest>()
    var requestIndex = 0
    val gateway = object : LiteLlmGateway {
      override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
        gatewayRequests += request
        return when (requestIndex++) {
          0 -> LiteLlmGatewayResult(
            requestId = request.requestId,
            status = LiteLlmGatewayStatus.SUCCESS,
            completionMode = LiteLlmCompletionMode.PRIMARY,
            outputText = """
            {
              "type": "tool_call",
              "id": "call_1",
              "tool_name": "LS",
              "arguments": {
                "path": "."
              }
            }
            """.trimIndent(),
            providerResponseId = "resp_cont_1",
            providerLineageId = "lineage_cont_1",
            selectedRoute = selection,
            attempts = listOf(
              LiteLlmAttemptRecord(
                route = selection,
                outcome = LiteLlmAttemptOutcome.SUCCESS,
                outputChars = 0,
                startedAtEpochMs = 50_000L,
                finishedAtEpochMs = 50_001L,
              ),
            ),
            startedAtEpochMs = 50_000L,
            finishedAtEpochMs = 50_001L,
          )

          else -> LiteLlmGatewayResult(
            requestId = request.requestId,
            status = LiteLlmGatewayStatus.SUCCESS,
            completionMode = LiteLlmCompletionMode.PRIMARY,
            completion = LiteLlmStructuredCompletion(
              finalText = "Replayed without provider-native continuation.",
            ),
            providerResponseId = "resp_cont_2",
            providerLineageId = "lineage_cont_2",
            selectedRoute = selection,
            attempts = listOf(
              LiteLlmAttemptRecord(
                route = selection,
                outcome = LiteLlmAttemptOutcome.SUCCESS,
                outputChars = 0,
                startedAtEpochMs = 50_004L,
                finishedAtEpochMs = 50_005L,
              ),
            ),
            startedAtEpochMs = 50_004L,
            finishedAtEpochMs = 50_005L,
          )
        }
      }
    }
    val eventSink = RecordingEventSink()
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
      clock = IncrementingClock(start = 50_500L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "List the workspace and answer."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Replayed without provider-native continuation.", result.stdout)
    assertEquals("0", result.metadata["responsesContinuationRecoveryCount"])
    assertEquals(null, result.metadata["responsesContinuationRecoveryLastReason"])
    assertEquals(2, gatewayRequests.size)
    assertNull(gatewayRequests[0].previousResponseId)
    assertNull(gatewayRequests[1].previousResponseId)
    assertEquals("full_rebuild", gatewayRequests[1].metadata["localContinuationMode"])
    assertEquals(
      "responses_legacy_json_fallback_enabled",
      gatewayRequests[1].metadata["localContinuationReason"],
    )
    assertEquals(
      "responses_legacy_json_fallback_enabled",
      result.metadata["localContinuationLastReason"],
    )
    assertTrue(
      gatewayRequests[1].messages.any { message ->
        message.role == LiteLlmGatewayMessageRole.ASSISTANT &&
          message.toolCalls.any { toolCall ->
            toolCall.id == "call_1" && toolCall.toolName == "LS"
          }
      },
    )
    assertTrue(
      gatewayRequests[1].messages.any { message ->
        val toolResult = message.toolResult
        message.role == LiteLlmGatewayMessageRole.TOOL &&
        toolResult?.toolCallId == "call_1" &&
          toolResult.toolName == "LS"
      },
    )
    assertTrue(
      eventSink.events.none { event ->
        event is OpenCrayAssistantEvent && event.stage == "responses_recovery"
      },
    )
  }

  @Test
  fun responsesContinuationAppendsContextUpdateWhenTodoWriteChangesWorkingState() {
    val workspaceRoot = temporaryFolder.newFolder("agent-responses-working-state-update")
    val todoStore = InMemoryAgentTodoStore()
    val selection = LiteLlmRouteSelectionMetadata(
      profileId = "test-profile",
      routeId = "responses-route",
      providerId = "openai",
      model = "gpt-5-mini",
      attemptIndex = 0,
    )
    val gatewayRequests = mutableListOf<LiteLlmGatewayRequest>()
    var requestIndex = 0
    val gateway = object : LiteLlmGateway {
      override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
        gatewayRequests += request
        return if (requestIndex++ == 0) {
          LiteLlmGatewayResult(
            requestId = request.requestId,
            status = LiteLlmGatewayStatus.SUCCESS,
            completionMode = LiteLlmCompletionMode.PRIMARY,
            completion = LiteLlmStructuredCompletion(
              toolCalls = listOf(
                LiteLlmStructuredToolCall(
                  id = "call_1",
                  toolName = "TodoWrite",
                  arguments = JsonObject(
                    mapOf(
                      "todos" to Json.parseToJsonElement(
                        """
                        [
                          {
                            "content": "Inspect README",
                            "status": "completed"
                          }
                        ]
                        """.trimIndent(),
                      ).jsonArray,
                    ),
                  ),
                ),
              ),
            ),
            providerResponseId = "resp_working_1",
            providerLineageId = "lineage_working_1",
            selectedRoute = selection,
            attempts = listOf(
              LiteLlmAttemptRecord(
                route = selection,
                outcome = LiteLlmAttemptOutcome.SUCCESS,
                outputChars = 0,
                startedAtEpochMs = 53_000L,
                finishedAtEpochMs = 53_001L,
              ),
            ),
            startedAtEpochMs = 53_000L,
            finishedAtEpochMs = 53_001L,
          )
        } else {
          LiteLlmGatewayResult(
            requestId = request.requestId,
            status = LiteLlmGatewayStatus.SUCCESS,
            completionMode = LiteLlmCompletionMode.PRIMARY,
            completion = LiteLlmStructuredCompletion(
              finalText = "Continued after working state changed.",
            ),
            providerResponseId = "resp_working_2",
            providerLineageId = "lineage_working_2",
            selectedRoute = selection,
            attempts = listOf(
              LiteLlmAttemptRecord(
                route = selection,
                outcome = LiteLlmAttemptOutcome.SUCCESS,
                outputChars = 0,
                startedAtEpochMs = 53_002L,
                finishedAtEpochMs = 53_003L,
              ),
            ),
            startedAtEpochMs = 53_002L,
            finishedAtEpochMs = 53_003L,
          )
        }
      }
    }
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
          todoStore = todoStore,
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
      clock = IncrementingClock(start = 53_500L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Keep the todo list up to date."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Continued after working state changed.", result.stdout)
    assertEquals(2, gatewayRequests.size)
    assertEquals("resp_working_1", gatewayRequests[1].previousResponseId)
    assertEquals("responses_native", gatewayRequests[1].metadata["localContinuationMode"])
    assertEquals("responses_previous_response_id", gatewayRequests[1].metadata["localContinuationReason"])
    assertNull(gatewayRequests[1].metadata[LiteLlmMetadataKeys.CONTEXT_CACHE_BREAK_REASON])
    assertEquals("1", gatewayRequests[1].metadata["responsesPendingContextUpdateCount"])
    assertNotNull(gatewayRequests[1].metadata["responsesPendingContextUpdateHash"])
    assertEquals("1", result.metadata["responsesPendingContextUpdateCount"])
    assertNotNull(result.metadata["responsesPendingContextUpdateHash"])
    assertEquals("1", result.metadata["localContinuationUsedCount"])
    assertEquals(2, gatewayRequests[1].messages.size)
    assertTrue(
      gatewayRequests[1].messages.any { message ->
        message.role == LiteLlmGatewayMessageRole.TOOL &&
          message.toolResult?.toolCallId == "call_1" &&
          message.toolResult?.toolName == "TodoWrite"
      },
    )
    val messageText = gatewayStructuredPayloadText(gatewayRequests[1])
    assertTrue(messageText.contains("[OpenCray Context Update]"))
    assertTrue(messageText.contains("zone=dynamic_operational"))
    assertTrue(messageText.contains("[Working State]"))
    assertTrue(messageText.contains("primary_goal=Keep the todo list up to date"))
    assertTrue(messageText.contains("TodoWrite todos=1 changed=true"))
    assertFalse(messageText.contains("[Recent Working Observations]"))
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
  fun responsesContinuationAppendsContextUpdateWhenOrdinaryMemoryRecallChanges() {
    val workspaceRoot = temporaryFolder.newFolder("agent-responses-memory-recall-update")
    Files.write(
      workspaceRoot.toPath().resolve("README.md"),
      "hello from workspace".toByteArray(StandardCharsets.UTF_8),
    )
    val selection = LiteLlmRouteSelectionMetadata(
      profileId = "test-profile",
      routeId = "responses-route",
      providerId = "openai",
      model = "gpt-5-mini",
      attemptIndex = 0,
    )
    val gatewayRequests = mutableListOf<LiteLlmGatewayRequest>()
    var requestIndex = 0
    val gateway = object : LiteLlmGateway {
      override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
        gatewayRequests += request
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
                  arguments = JsonObject(mapOf("path" to JsonPrimitive("."))),
                ),
              ),
            ),
            providerResponseId = "resp_memory_recall_1",
            providerLineageId = "lineage_memory_recall_1",
            selectedRoute = selection,
            attempts = listOf(
              LiteLlmAttemptRecord(
                route = selection,
                outcome = LiteLlmAttemptOutcome.SUCCESS,
                outputChars = 0,
                startedAtEpochMs = 53_600L,
                finishedAtEpochMs = 53_601L,
              ),
            ),
            startedAtEpochMs = 53_600L,
            finishedAtEpochMs = 53_601L,
          )
        } else {
          LiteLlmGatewayResult(
            requestId = request.requestId,
            status = LiteLlmGatewayStatus.SUCCESS,
            completionMode = LiteLlmCompletionMode.PRIMARY,
            completion = LiteLlmStructuredCompletion(
              finalText = "Continued after dynamic memory recall changed.",
            ),
            providerResponseId = "resp_memory_recall_2",
            providerLineageId = "lineage_memory_recall_2",
            selectedRoute = selection,
            attempts = listOf(
              LiteLlmAttemptRecord(
                route = selection,
                outcome = LiteLlmAttemptOutcome.SUCCESS,
                outputChars = 0,
                startedAtEpochMs = 53_602L,
                finishedAtEpochMs = 53_603L,
              ),
            ),
            startedAtEpochMs = 53_602L,
            finishedAtEpochMs = 53_603L,
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
        sessionContext = AgentRuntimeSessionContext(
          recalledMemory = MemoryRecallResult(
            memories = listOf(
              RetrievedMemory(
                id = "memory-initial",
                kind = MemoryKind.PROJECT_FACT,
                scope = MemoryScope.WORKSPACE,
                status = MemoryStatus.ACTIVE,
                content = "Initial dynamic recall should not become a Responses baseline.",
                lastConfirmedAtEpochMs = 1_000L,
                score = 100,
              ),
            ),
          ),
        ),
        midTurnMaintenance = { request ->
          OpenCrayMidTurnMaintenanceResult(
            sessionContext = request.sessionContext.copy(
              recalledMemory = MemoryRecallResult(
                memories = listOf(
                  RetrievedMemory(
                    id = "memory-updated",
                    kind = MemoryKind.PROJECT_FACT,
                    scope = MemoryScope.WORKSPACE,
                    status = MemoryStatus.ACTIVE,
                    content = "Updated ordinary dynamic recall travels as a context update.",
                    lastConfirmedAtEpochMs = 2_000L,
                    score = 200,
                  ),
                ),
              ),
            ),
          )
        },
        llmMetadata = mapOf(
          "protocol" to "openai_responses",
          "responseApiPreferred" to "true",
          "nativeToolCallingAvailable" to "true",
          "responsesContinuationSupported" to "true",
          "nativeWebSearchEnabled" to "false",
        ),
      ),
      clock = IncrementingClock(start = 53_700L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Use relevant memory, list the workspace, then answer."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Continued after dynamic memory recall changed.", result.stdout)
    assertEquals(2, gatewayRequests.size)
    assertEquals("resp_memory_recall_1", gatewayRequests[1].previousResponseId)
    assertEquals("responses_native", gatewayRequests[1].metadata["localContinuationMode"])
    assertEquals("responses_previous_response_id", gatewayRequests[1].metadata["localContinuationReason"])
    assertEquals("1", gatewayRequests[1].metadata["responsesPendingContextUpdateCount"])
    assertNull(gatewayRequests[1].metadata[LiteLlmMetadataKeys.CONTEXT_CACHE_BREAK_REASON])
    val messageText = gatewayStructuredPayloadText(gatewayRequests[1])
    assertTrue(messageText.contains("[OpenCray Context Update]"))
    assertTrue(messageText.contains("[Retrieved Memory]"))
    assertTrue(messageText.contains("Updated ordinary dynamic recall travels as a context update."))
    assertFalse(messageText.contains("Initial dynamic recall should not become a Responses baseline."))
  }

  @Test
  fun responsesContinuationFallsBackWhenDynamicContextUpdateWouldBeTruncated() {
    val workspaceRoot = temporaryFolder.newFolder("agent-responses-memory-recall-large-update")
    Files.write(
      workspaceRoot.toPath().resolve("README.md"),
      "hello from workspace".toByteArray(StandardCharsets.UTF_8),
    )
    val selection = LiteLlmRouteSelectionMetadata(
      profileId = "test-profile",
      routeId = "responses-route",
      providerId = "openai",
      model = "gpt-5-mini",
      attemptIndex = 0,
    )
    val gatewayRequests = mutableListOf<LiteLlmGatewayRequest>()
    var requestIndex = 0
    val gateway = object : LiteLlmGateway {
      override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
        gatewayRequests += request
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
                  arguments = JsonObject(mapOf("path" to JsonPrimitive("."))),
                ),
              ),
            ),
            providerResponseId = "resp_large_update_1",
            providerLineageId = "lineage_large_update_1",
            selectedRoute = selection,
            attempts = listOf(
              LiteLlmAttemptRecord(
                route = selection,
                outcome = LiteLlmAttemptOutcome.SUCCESS,
                outputChars = 0,
                startedAtEpochMs = 53_800L,
                finishedAtEpochMs = 53_801L,
              ),
            ),
            startedAtEpochMs = 53_800L,
            finishedAtEpochMs = 53_801L,
          )
        } else {
          LiteLlmGatewayResult(
            requestId = request.requestId,
            status = LiteLlmGatewayStatus.SUCCESS,
            completionMode = LiteLlmCompletionMode.PRIMARY,
            completion = LiteLlmStructuredCompletion(
              finalText = "Replayed after large dynamic context update.",
            ),
            providerResponseId = "resp_large_update_2",
            providerLineageId = "lineage_large_update_2",
            selectedRoute = selection,
            attempts = listOf(
              LiteLlmAttemptRecord(
                route = selection,
                outcome = LiteLlmAttemptOutcome.SUCCESS,
                outputChars = 0,
                startedAtEpochMs = 53_802L,
                finishedAtEpochMs = 53_803L,
              ),
            ),
            startedAtEpochMs = 53_802L,
            finishedAtEpochMs = 53_803L,
          )
        }
      }
    }
    val tailMarker = "large-dynamic-memory-tail-marker"
    val oversizedMemory = "Updated ordinary dynamic recall " +
      "x".repeat(6_200) +
      tailMarker
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
        sessionContext = AgentRuntimeSessionContext(
          recalledMemory = MemoryRecallResult(
            memories = listOf(
              RetrievedMemory(
                id = "memory-initial",
                kind = MemoryKind.PROJECT_FACT,
                scope = MemoryScope.WORKSPACE,
                status = MemoryStatus.ACTIVE,
                content = "Initial dynamic recall should not become a Responses baseline.",
                lastConfirmedAtEpochMs = 1_000L,
                score = 100,
              ),
            ),
          ),
        ),
        midTurnMaintenance = { request ->
          OpenCrayMidTurnMaintenanceResult(
            sessionContext = request.sessionContext.copy(
              recalledMemory = MemoryRecallResult(
                memories = listOf(
                  RetrievedMemory(
                    id = "memory-oversized",
                    kind = MemoryKind.PROJECT_FACT,
                    scope = MemoryScope.WORKSPACE,
                    status = MemoryStatus.ACTIVE,
                    content = oversizedMemory,
                    lastConfirmedAtEpochMs = 2_000L,
                    score = 200,
                  ),
                ),
              ),
            ),
          )
        },
        llmMetadata = mapOf(
          "protocol" to "openai_responses",
          "responseApiPreferred" to "true",
          "nativeToolCallingAvailable" to "true",
          "responsesContinuationSupported" to "true",
          "nativeWebSearchEnabled" to "false",
        ),
      ),
      clock = IncrementingClock(start = 53_900L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Use relevant memory, list the workspace, then answer."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Replayed after large dynamic context update.", result.stdout)
    assertEquals(2, gatewayRequests.size)
    assertNull(gatewayRequests[1].previousResponseId)
    assertEquals("full_rebuild", gatewayRequests[1].metadata["localContinuationMode"])
    assertEquals(
      "responses_context_update_too_large",
      gatewayRequests[1].metadata["localContinuationReason"],
    )
    assertEquals(
      "dynamic_context_changed",
      gatewayRequests[1].metadata[LiteLlmMetadataKeys.CONTEXT_CACHE_BREAK_REASON],
    )
    assertEquals("0", gatewayRequests[1].metadata["responsesPendingContextUpdateCount"])
    assertEquals("1", result.metadata["localContinuationFallbackCount"])
    assertEquals("0", result.metadata["responsesPendingContextUpdateCount"])
    val messageText = gatewayStructuredPayloadText(gatewayRequests[1])
    assertFalse(messageText.contains("[OpenCray Context Update]"))
    assertTrue(messageText.contains("[Retrieved Memory]"))
    assertTrue(messageText.contains(tailMarker))
  }

  @Test
  fun responsesContinuationFallsBackWhenContextUpdateChainLimitIsReached() {
    val workspaceRoot = temporaryFolder.newFolder("agent-responses-context-update-chain-limit")
    val selection = LiteLlmRouteSelectionMetadata(
      profileId = "test-profile",
      routeId = "responses-route",
      providerId = "openai",
      model = "gpt-5-mini",
      attemptIndex = 0,
    )
    val initialCheckpoints = mutableListOf<OpenCrayPromptCheckpointEmission>()
    val initialGateway = object : LiteLlmGateway {
      override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult =
        LiteLlmGatewayResult(
          requestId = request.requestId,
          status = LiteLlmGatewayStatus.SUCCESS,
          completionMode = LiteLlmCompletionMode.PRIMARY,
          completion = LiteLlmStructuredCompletion(
            finalText = "Initial answer.",
          ),
          providerResponseId = "resp_chain_1",
          providerLineageId = "lineage_chain_1",
          selectedRoute = selection,
          attempts = listOf(
            LiteLlmAttemptRecord(
              route = selection,
              outcome = LiteLlmAttemptOutcome.SUCCESS,
              outputChars = 0,
              startedAtEpochMs = 54_000L,
              finishedAtEpochMs = 54_001L,
            ),
          ),
          startedAtEpochMs = 54_000L,
          finishedAtEpochMs = 54_001L,
        )
    }
    val initialRuntime = OpenCrayAgentRuntime(
      gateway = initialGateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(
        maxTurns = 2,
        sessionContext = AgentRuntimeSessionContext(
          recalledMemory = MemoryRecallResult(
            memories = listOf(
              RetrievedMemory(
                id = "memory-initial",
                kind = MemoryKind.PROJECT_FACT,
                scope = MemoryScope.WORKSPACE,
                status = MemoryStatus.ACTIVE,
                content = "Initial dynamic recall.",
                lastConfirmedAtEpochMs = 1_000L,
                score = 100,
              ),
            ),
          ),
        ),
        promptCheckpointSink = initialCheckpoints::add,
        llmMetadata = mapOf(
          "protocol" to "openai_responses",
          "responseApiPreferred" to "true",
          "nativeToolCallingAvailable" to "true",
          "responsesContinuationSupported" to "true",
          "nativeWebSearchEnabled" to "false",
        ),
      ),
      clock = IncrementingClock(start = 54_100L)::next,
    )

    val initialResult = initialRuntime.execute(
      task = promptTask(input = "Use relevant memory and answer."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, initialResult.status)
    val initialShape = requireNotNull(
      initialCheckpoints
        .mapNotNull { checkpoint -> checkpoint.state.responsesContinuationShape }
        .lastOrNull(),
    )
    val resumeState = OpenCrayPromptResumeState(
      turnIndex = 1,
      toolCallCount = 0,
      responsesPreviousResponseId = "resp_chain_1",
      responsesProviderLineageId = "lineage_chain_1",
      responsesLineageTrusted = true,
      responsesContinuationShape = initialShape.copy(
        appliedContextUpdateCount = 8,
      ),
      responsesPendingMessages = listOf(
        OpenCraySerializableGatewayMessage(
          role = LiteLlmGatewayMessageRole.TOOL.name,
          toolResult = OpenCraySerializableGatewayToolResult(
            toolCallId = "call-chain",
            toolName = "LS",
            content = "README.md",
          ),
        ),
      ),
    )
    val resumedGatewayRequests = mutableListOf<LiteLlmGatewayRequest>()
    val resumedGateway = object : LiteLlmGateway {
      override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
        resumedGatewayRequests += request
        return LiteLlmGatewayResult(
          requestId = request.requestId,
          status = LiteLlmGatewayStatus.SUCCESS,
          completionMode = LiteLlmCompletionMode.PRIMARY,
          completion = LiteLlmStructuredCompletion(
            finalText = "Rebuilt after context update chain limit.",
          ),
          providerResponseId = "resp_chain_2",
          providerLineageId = "lineage_chain_2",
          selectedRoute = selection,
          attempts = listOf(
            LiteLlmAttemptRecord(
              route = selection,
              outcome = LiteLlmAttemptOutcome.SUCCESS,
              outputChars = 0,
              startedAtEpochMs = 54_002L,
              finishedAtEpochMs = 54_003L,
            ),
          ),
          startedAtEpochMs = 54_002L,
          finishedAtEpochMs = 54_003L,
        )
      }
    }
    val resumedRuntime = OpenCrayAgentRuntime(
      gateway = resumedGateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(
        maxTurns = 2,
        sessionContext = AgentRuntimeSessionContext(
          recalledMemory = MemoryRecallResult(
            memories = listOf(
              RetrievedMemory(
                id = "memory-updated",
                kind = MemoryKind.PROJECT_FACT,
                scope = MemoryScope.WORKSPACE,
                status = MemoryStatus.ACTIVE,
                content = "Updated dynamic recall after many Responses context updates.",
                lastConfirmedAtEpochMs = 2_000L,
                score = 200,
              ),
            ),
          ),
        ),
        promptResumeState = resumeState,
        llmMetadata = mapOf(
          "protocol" to "openai_responses",
          "responseApiPreferred" to "true",
          "nativeToolCallingAvailable" to "true",
          "responsesContinuationSupported" to "true",
          "nativeWebSearchEnabled" to "false",
        ),
      ),
      clock = IncrementingClock(start = 54_200L)::next,
    )

    val resumedResult = resumedRuntime.execute(
      task = promptTask(input = "Use relevant memory and answer."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, resumedResult.status)
    assertEquals("Rebuilt after context update chain limit.", resumedResult.stdout)
    assertEquals(1, resumedGatewayRequests.size)
    assertNull(resumedGatewayRequests.single().previousResponseId)
    assertEquals(
      "full_rebuild",
      resumedGatewayRequests.single().metadata["localContinuationMode"],
    )
    assertEquals(
      "responses_context_update_chain_limit",
      resumedGatewayRequests.single().metadata["localContinuationReason"],
    )
    assertEquals(
      "dynamic_context_changed",
      resumedGatewayRequests.single().metadata[LiteLlmMetadataKeys.CONTEXT_CACHE_BREAK_REASON],
    )
    assertEquals("1", resumedResult.metadata["localContinuationFallbackCount"])
    assertEquals("0", resumedResult.metadata["responsesPendingContextUpdateCount"])
  }

  @Test
  fun runPromptTaskEmitsNonResponsesContextCacheShapeMetadata() {
    val workspaceRoot = temporaryFolder.newFolder("agent-context-cache-shape-metadata")
    val gateway = RecordingGateway(
      outputs = listOf("""{"type":"final","answer":"Done."}"""),
    )
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(workspaceRoots = setOf(workspaceRoot.toPath())),
      ),
      config = OpenCrayAgentRuntimeConfig(maxTurns = 2),
      clock = IncrementingClock(start = 12_000L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Say done."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    val requestMetadata = gateway.requests.single().metadata
    assertEquals(
      "non_responses_front_zone_v1",
      requestMetadata[LiteLlmMetadataKeys.CONTEXT_CACHE_CONTRACT_VERSION],
    )
    assertEquals(
      requestMetadata[LiteLlmMetadataKeys.CONTEXT_CACHE_CONTRACT_VERSION],
      result.metadata[LiteLlmMetadataKeys.CONTEXT_CACHE_CONTRACT_VERSION],
    )
    assertEquals(
      requestMetadata[LiteLlmMetadataKeys.CONTEXT_CACHE_STABLE_ANCHOR_HASH],
      result.metadata[LiteLlmMetadataKeys.CONTEXT_CACHE_STABLE_ANCHOR_HASH],
    )
    assertEquals(
      requestMetadata[LiteLlmMetadataKeys.CONTEXT_CACHE_DURABLE_CONTEXT_HASH],
      result.metadata[LiteLlmMetadataKeys.CONTEXT_CACHE_DURABLE_CONTEXT_HASH],
    )
    assertEquals(
      requestMetadata[LiteLlmMetadataKeys.CONTEXT_CACHE_DYNAMIC_CONTEXT_HASH],
      result.metadata[LiteLlmMetadataKeys.CONTEXT_CACHE_DYNAMIC_CONTEXT_HASH],
    )
    assertEquals(
      requestMetadata[LiteLlmMetadataKeys.CONTEXT_CACHE_FRONT_CONTEXT_ZONE_MASK],
      result.metadata[LiteLlmMetadataKeys.CONTEXT_CACHE_FRONT_CONTEXT_ZONE_MASK],
    )
    assertEquals(
      requestMetadata[LiteLlmMetadataKeys.CONTEXT_CACHE_FRONT_CONTEXT_MESSAGE_COUNT],
      result.metadata[LiteLlmMetadataKeys.CONTEXT_CACHE_FRONT_CONTEXT_MESSAGE_COUNT],
    )
    assertNotNull(requestMetadata[LiteLlmMetadataKeys.CONTEXT_CACHE_STABLE_ANCHOR_HASH])
    assertNotNull(requestMetadata[LiteLlmMetadataKeys.CONTEXT_CACHE_DURABLE_CONTEXT_HASH])
    assertNotNull(requestMetadata[LiteLlmMetadataKeys.CONTEXT_CACHE_DYNAMIC_CONTEXT_HASH])
    assertEquals("durable", requestMetadata[LiteLlmMetadataKeys.CONTEXT_CACHE_FRONT_CONTEXT_ZONE_MASK])
    assertEquals("1", requestMetadata[LiteLlmMetadataKeys.CONTEXT_CACHE_FRONT_CONTEXT_MESSAGE_COUNT])
  }

  @Test
  fun responsesContinuationFallsBackToFullRebuildWhenActiveSkillChangesToolPool() {
    val workspaceRoot = temporaryFolder.newFolder("agent-responses-skill-rebuild")
    val skillsRoot = temporaryFolder.newFolder("agent-responses-skill-root")
    writeSkill(
      root = skillsRoot,
      relativeDirectory = "ui-ux-pro-max",
      frontMatter = """
        name: ui-ux-pro-max
        description: High-end UI review workflow.
        invocation-control: explicit-only
        user-invocable: true
        allowed-tools: [ read, write ]
      """.trimIndent(),
      body = """
        # UI UX Pro Max

        Audit the current interface first, then apply a concrete design system.
      """.trimIndent(),
    )
    val skillCatalog = SkillCatalogResolver().resolve(listOf(skillsRoot))
    val selection = LiteLlmRouteSelectionMetadata(
      profileId = "test-profile",
      routeId = "responses-route",
      providerId = "openai",
      model = "gpt-5-mini",
      attemptIndex = 0,
    )
    val gatewayRequests = mutableListOf<LiteLlmGatewayRequest>()
    var requestIndex = 0
    val gateway = object : LiteLlmGateway {
      override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
        gatewayRequests += request
        return if (requestIndex++ == 0) {
          LiteLlmGatewayResult(
            requestId = request.requestId,
            status = LiteLlmGatewayStatus.SUCCESS,
            completionMode = LiteLlmCompletionMode.PRIMARY,
            completion = LiteLlmStructuredCompletion(
              toolCalls = listOf(
                LiteLlmStructuredToolCall(
                  id = "call_1",
                  toolName = "skill_read",
                  arguments = JsonObject(
                    mapOf("name" to JsonPrimitive("ui-ux-pro-max")),
                  ),
                ),
              ),
            ),
            providerResponseId = "resp_skill_1",
            providerLineageId = "lineage_skill_1",
            selectedRoute = selection,
            attempts = listOf(
              LiteLlmAttemptRecord(
                route = selection,
                outcome = LiteLlmAttemptOutcome.SUCCESS,
                outputChars = 0,
                startedAtEpochMs = 54_000L,
                finishedAtEpochMs = 54_001L,
              ),
            ),
            startedAtEpochMs = 54_000L,
            finishedAtEpochMs = 54_001L,
          )
        } else {
          LiteLlmGatewayResult(
            requestId = request.requestId,
            status = LiteLlmGatewayStatus.SUCCESS,
            completionMode = LiteLlmCompletionMode.PRIMARY,
            completion = LiteLlmStructuredCompletion(
              finalText = "Rebuilt after the tool pool changed.",
            ),
            providerResponseId = "resp_skill_2",
            providerLineageId = "lineage_skill_2",
            selectedRoute = selection,
            attempts = listOf(
              LiteLlmAttemptRecord(
                route = selection,
                outcome = LiteLlmAttemptOutcome.SUCCESS,
                outputChars = 0,
                startedAtEpochMs = 54_002L,
                finishedAtEpochMs = 54_003L,
              ),
            ),
            startedAtEpochMs = 54_002L,
            finishedAtEpochMs = 54_003L,
          )
        }
      }
    }
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
          skillsRoots = listOf(skillsRoot),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(
        maxTurns = 4,
        maxToolCalls = 2,
        sessionContext = AgentRuntimeSessionContext(
          skillInventory = skillCatalog.inventory,
          skillCatalog = skillCatalog,
        ),
        llmMetadata = mapOf(
          "protocol" to "openai_responses",
          "responseApiPreferred" to "true",
          "nativeToolCallingAvailable" to "true",
          "responsesContinuationSupported" to "true",
          "nativeWebSearchEnabled" to "false",
        ),
      ),
      clock = IncrementingClock(start = 54_500L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Load the UI skill, then follow it."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Rebuilt after the tool pool changed.", result.stdout)
    assertEquals(2, gatewayRequests.size)
    assertNull(gatewayRequests[1].previousResponseId)
    assertEquals("full_rebuild", gatewayRequests[1].metadata["localContinuationMode"])
    assertEquals("tool_pool_changed", gatewayRequests[1].metadata["localContinuationReason"])
    assertEquals(
      "tool_pool_changed",
      gatewayRequests[1].metadata[LiteLlmMetadataKeys.CONTEXT_CACHE_BREAK_REASON],
    )
    val messageText = gatewayStructuredPayloadText(gatewayRequests[1])
    assertTrue(messageText.contains("[Active Skill]"))
    assertTrue(messageText.contains("name=ui-ux-pro-max"))
  }

  @Test
  fun duplicateStructuredToolCallIdsAreTreatedAsProtocolErrorWithoutExecutingTools() {
    val workspaceRoot = temporaryFolder.newFolder("agent-duplicate-tool-id-workspace")
    val selection = LiteLlmRouteSelectionMetadata(
      profileId = "test-profile",
      routeId = "responses-route",
      providerId = "openai",
      model = "gpt-5-mini",
      attemptIndex = 0,
    )
    val gatewayRequests = mutableListOf<LiteLlmGatewayRequest>()
    var requestIndex = 0
    val gateway = object : LiteLlmGateway {
      override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
        gatewayRequests += request
        return if (requestIndex++ == 0) {
          LiteLlmGatewayResult(
            requestId = request.requestId,
            status = LiteLlmGatewayStatus.SUCCESS,
            completionMode = LiteLlmCompletionMode.PRIMARY,
            completion = LiteLlmStructuredCompletion(
              toolCalls = listOf(
                LiteLlmStructuredToolCall(
                  id = "call_dup",
                  toolName = "LS",
                  arguments = JsonObject(mapOf("path" to JsonPrimitive("."))),
                ),
                LiteLlmStructuredToolCall(
                  id = "call_dup",
                  toolName = "LS",
                  arguments = JsonObject(mapOf("path" to JsonPrimitive("src"))),
                ),
              ),
            ),
            selectedRoute = selection,
            attempts = listOf(
              LiteLlmAttemptRecord(
                route = selection,
                outcome = LiteLlmAttemptOutcome.SUCCESS,
                outputChars = 0,
                startedAtEpochMs = 52_000L,
                finishedAtEpochMs = 52_001L,
              ),
            ),
            startedAtEpochMs = 52_000L,
            finishedAtEpochMs = 52_001L,
          )
        } else {
          LiteLlmGatewayResult(
            requestId = request.requestId,
            status = LiteLlmGatewayStatus.SUCCESS,
            completionMode = LiteLlmCompletionMode.PRIMARY,
            completion = LiteLlmStructuredCompletion(
              finalText = "Recovered after duplicate tool id.",
            ),
            selectedRoute = selection,
            attempts = listOf(
              LiteLlmAttemptRecord(
                route = selection,
                outcome = LiteLlmAttemptOutcome.SUCCESS,
                outputChars = 0,
                startedAtEpochMs = 52_002L,
                finishedAtEpochMs = 52_003L,
              ),
            ),
            startedAtEpochMs = 52_002L,
            finishedAtEpochMs = 52_003L,
          )
        }
      }
    }
    val eventSink = RecordingEventSink()
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
      clock = IncrementingClock(start = 52_500L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "List files safely and answer."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Recovered after duplicate tool id.", result.stdout)
    assertEquals(2, gatewayRequests.size)
    assertTrue(eventSink.events.none { event -> event is OpenCrayToolCallEvent })
    assertTrue(eventSink.events.none { event -> event is OpenCrayToolResultEvent })
  }

  @Test
  fun responsesContinuationEmitsCacheBreakReasonWhenLineageIsUnavailable() {
    val workspaceRoot = temporaryFolder.newFolder("agent-responses-lineage-unavailable")
    val selection = LiteLlmRouteSelectionMetadata(
      profileId = "test-profile",
      routeId = "responses-route",
      providerId = "openai",
      model = "gpt-5-mini",
      attemptIndex = 0,
    )
    val gatewayRequests = mutableListOf<LiteLlmGatewayRequest>()
    var requestIndex = 0
    val gateway = object : LiteLlmGateway {
      override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
        gatewayRequests += request
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
                  arguments = JsonObject(mapOf("path" to JsonPrimitive("."))),
                ),
              ),
            ),
            selectedRoute = selection,
            attempts = listOf(
              LiteLlmAttemptRecord(
                route = selection,
                outcome = LiteLlmAttemptOutcome.SUCCESS,
                outputChars = 0,
                startedAtEpochMs = 52_100L,
                finishedAtEpochMs = 52_101L,
              ),
            ),
            startedAtEpochMs = 52_100L,
            finishedAtEpochMs = 52_101L,
          )
        } else {
          LiteLlmGatewayResult(
            requestId = request.requestId,
            status = LiteLlmGatewayStatus.SUCCESS,
            completionMode = LiteLlmCompletionMode.PRIMARY,
            completion = LiteLlmStructuredCompletion(
              finalText = "Recovered after lineage was unavailable.",
            ),
            selectedRoute = selection,
            attempts = listOf(
              LiteLlmAttemptRecord(
                route = selection,
                outcome = LiteLlmAttemptOutcome.SUCCESS,
                outputChars = 0,
                startedAtEpochMs = 52_102L,
                finishedAtEpochMs = 52_103L,
              ),
            ),
            startedAtEpochMs = 52_102L,
            finishedAtEpochMs = 52_103L,
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
      clock = IncrementingClock(start = 52_600L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "List files safely and answer."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Recovered after lineage was unavailable.", result.stdout)
    assertEquals(2, gatewayRequests.size)
    assertNull(gatewayRequests[1].previousResponseId)
    assertEquals("full_rebuild", gatewayRequests[1].metadata["localContinuationMode"])
    assertEquals(
      "responses_lineage_unavailable",
      gatewayRequests[1].metadata["localContinuationReason"],
    )
    assertEquals(
      "continuation_lineage_untrusted",
      gatewayRequests[1].metadata[LiteLlmMetadataKeys.CONTEXT_CACHE_BREAK_REASON],
    )
    assertEquals(
      "continuation_lineage_untrusted",
      result.metadata[LiteLlmMetadataKeys.CONTEXT_CACHE_BREAK_REASON],
    )
  }

  @Test
  fun responsesContinuationFallsBackToFullRebuildWhenPendingDeltaIncludesSupplement() {
    val workspaceRoot = temporaryFolder.newFolder("agent-responses-supplement-rebuild")
    val selection = LiteLlmRouteSelectionMetadata(
      profileId = "test-profile",
      routeId = "responses-route",
      providerId = "openai",
      model = "gpt-5-mini",
      attemptIndex = 0,
    )
    val gatewayRequests = mutableListOf<LiteLlmGatewayRequest>()
    var requestIndex = 0
    var supplementProviderCalls = 0
    val gateway = object : LiteLlmGateway {
      override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
        gatewayRequests += request
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
            providerResponseId = "resp_supp_1",
            providerLineageId = "lineage_supp_1",
            selectedRoute = selection,
            attempts = listOf(
              LiteLlmAttemptRecord(
                route = selection,
                outcome = LiteLlmAttemptOutcome.SUCCESS,
                outputChars = 0,
                startedAtEpochMs = 51_000L,
                finishedAtEpochMs = 51_001L,
              ),
            ),
            startedAtEpochMs = 51_000L,
            finishedAtEpochMs = 51_001L,
          )
        } else {
          LiteLlmGatewayResult(
            requestId = request.requestId,
            status = LiteLlmGatewayStatus.SUCCESS,
            completionMode = LiteLlmCompletionMode.PRIMARY,
            completion = LiteLlmStructuredCompletion(
              finalText = "Rebuilt after supplement.",
            ),
            providerResponseId = "resp_supp_2",
            providerLineageId = "lineage_supp_2",
            selectedRoute = selection,
            attempts = listOf(
              LiteLlmAttemptRecord(
                route = selection,
                outcome = LiteLlmAttemptOutcome.SUCCESS,
                outputChars = 0,
                startedAtEpochMs = 51_002L,
                finishedAtEpochMs = 51_003L,
              ),
            ),
            startedAtEpochMs = 51_002L,
            finishedAtEpochMs = 51_003L,
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
        supplementInputProvider = { _, _ ->
          supplementProviderCalls += 1
          if (supplementProviderCalls == 2) {
            listOf(
              OpenCraySupplementInput(
                entryId = "supplement-1",
                text = "Also explain what changed.",
                createdAtEpochMs = 51_500L,
              ),
            )
          } else {
            emptyList()
          }
        },
        llmMetadata = mapOf(
          "protocol" to "openai_responses",
          "responseApiPreferred" to "true",
          "nativeToolCallingAvailable" to "true",
          "responsesContinuationSupported" to "true",
          "nativeWebSearchEnabled" to "false",
        ),
      ),
      clock = IncrementingClock(start = 51_800L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "List the workspace and answer."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Rebuilt after supplement.", result.stdout)
    assertEquals(2, gatewayRequests.size)
    assertNull(gatewayRequests[0].previousResponseId)
    assertNull(gatewayRequests[1].previousResponseId)
    assertEquals("full_rebuild", gatewayRequests[1].metadata["localContinuationMode"])
    assertEquals(
      "responses_pending_user_message",
      gatewayRequests[1].metadata["localContinuationReason"],
    )
    assertEquals(
      "responses_pending_user_message",
      result.metadata["localContinuationLastReason"],
    )
    assertTrue(
      gatewayRequests[1].messages.any { message ->
        message.role == LiteLlmGatewayMessageRole.USER &&
          message.content == "Also explain what changed."
      },
    )
    assertTrue(
      gatewayRequests[1].messages.any { message ->
        message.role == LiteLlmGatewayMessageRole.ASSISTANT &&
          message.toolCalls.any { toolCall ->
            toolCall.id == "call_1" && toolCall.toolName == "LS"
          }
      },
    )
    assertTrue(
      gatewayRequests[1].messages.any { message ->
        val toolResult = message.toolResult
        message.role == LiteLlmGatewayMessageRole.TOOL &&
          toolResult?.toolCallId == "call_1" &&
          toolResult.toolName == "LS"
      },
    )
  }

  @Test
  fun responsesContinuationFallsBackToFullRebuildWhenToolResultPublishesAttachmentArtifact() {
    val workspaceRoot = temporaryFolder.newFolder("agent-responses-attachment-artifact")
    val selection = LiteLlmRouteSelectionMetadata(
      profileId = "test-profile",
      routeId = "responses-route",
      providerId = "openai",
      model = "gpt-5-mini",
      attemptIndex = 0,
    )
    val gatewayRequests = mutableListOf<LiteLlmGatewayRequest>()
    var requestIndex = 0
    var finalAttachmentArtifactId: String? = null
    val gateway = object : LiteLlmGateway {
      override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
        gatewayRequests += request
        return if (requestIndex++ == 0) {
          LiteLlmGatewayResult(
            requestId = request.requestId,
            status = LiteLlmGatewayStatus.SUCCESS,
            completionMode = LiteLlmCompletionMode.PRIMARY,
            completion = LiteLlmStructuredCompletion(
              toolCalls = listOf(
                LiteLlmStructuredToolCall(
                  id = "call_1",
                  toolName = "Write",
                  arguments = JsonObject(
                    mapOf(
                      "file_path" to JsonPrimitive("outputs/diagram.png"),
                      "content" to JsonPrimitive("png-placeholder"),
                    ),
                  ),
                ),
              ),
            ),
            providerResponseId = "resp_attach_1",
            providerLineageId = "lineage_attach_1",
            selectedRoute = selection,
            attempts = listOf(
              LiteLlmAttemptRecord(
                route = selection,
                outcome = LiteLlmAttemptOutcome.SUCCESS,
                outputChars = 0,
                startedAtEpochMs = 53_000L,
                finishedAtEpochMs = 53_001L,
              ),
            ),
            startedAtEpochMs = 53_000L,
            finishedAtEpochMs = 53_001L,
          )
        } else {
          finalAttachmentArtifactId = request.messages
            .mapNotNull { message ->
              message.toolResult?.metadata?.get(OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_ID)
            }
            .singleOrNull()
            ?: error("Expected the replayed tool result to include one attachment artifact id.")
          LiteLlmGatewayResult(
            requestId = request.requestId,
            status = LiteLlmGatewayStatus.SUCCESS,
            completionMode = LiteLlmCompletionMode.PRIMARY,
            outputText = """
            {
              "type": "final",
              "answer": "",
              "attachments": [
                {
                  "artifact_id": "$finalAttachmentArtifactId",
                  "kind": "image"
                }
              ]
            }
            """.trimIndent(),
            providerResponseId = "resp_attach_2",
            providerLineageId = "lineage_attach_2",
            selectedRoute = selection,
            attempts = listOf(
              LiteLlmAttemptRecord(
                route = selection,
                outcome = LiteLlmAttemptOutcome.SUCCESS,
                outputChars = 0,
                startedAtEpochMs = 53_002L,
                finishedAtEpochMs = 53_003L,
              ),
            ),
            startedAtEpochMs = 53_002L,
            finishedAtEpochMs = 53_003L,
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
      clock = IncrementingClock(start = 53_500L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Create a diagram image and send it back as an attachment."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("", result.stdout)
    assertEquals(2, gatewayRequests.size)
    assertNull(gatewayRequests[0].previousResponseId)
    assertNull(gatewayRequests[1].previousResponseId)
    assertEquals("full_rebuild", gatewayRequests[1].metadata["localContinuationMode"])
    assertEquals(
      "responses_pending_tool_result_attachment_artifact",
      gatewayRequests[1].metadata["localContinuationReason"],
    )
    assertEquals(
      "responses_pending_tool_result_attachment_artifact",
      result.metadata["localContinuationLastReason"],
    )
    assertTrue(
      gatewayRequests[1].messages.any { message ->
        message.role == LiteLlmGatewayMessageRole.ASSISTANT &&
          message.toolCalls.singleOrNull()?.id == "call_1" &&
          message.toolCalls.singleOrNull()?.toolName == "Write"
      },
    )
    assertTrue(
      gatewayRequests[1].messages.any { message ->
        val toolResult = message.toolResult
        message.role == LiteLlmGatewayMessageRole.TOOL &&
          toolResult?.toolCallId == "call_1" &&
          toolResult.toolName == "Write" &&
          toolResult.metadata[OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_RELATIVE_PATH] ==
          "outputs/diagram.png"
      },
    )
    val attachmentsJson = result.metadata[OpenCrayExecutionMetadataKeys.FINAL_ATTACHMENTS_JSON].orEmpty()
    val attachments = Json.decodeFromString(
      ListSerializer(OpenCrayFinalAttachment.serializer()),
      attachmentsJson,
    )
    assertEquals(1, attachments.size)
    assertEquals(finalAttachmentArtifactId, attachments.single().artifactId)
    assertEquals("image", attachments.single().kind)
    assertTrue(Files.exists(workspaceRoot.toPath().resolve("outputs").resolve("diagram.png")))
  }

  @Test
  fun restoredGeneralResumeCheckpointUsesLocalFrontPatchWhenResumeWorkingStateChangesDynamicContext() {
    val workspaceRoot = temporaryFolder.newFolder("agent-restored-local-continuation")
    Files.write(
      workspaceRoot.toPath().resolve("README.md"),
      "hello from workspace".toByteArray(StandardCharsets.UTF_8),
    )

    val selection = LiteLlmRouteSelectionMetadata(
      profileId = "test-profile",
      routeId = "test-route",
      providerId = "openai",
      model = "gpt-5-mini",
      attemptIndex = 0,
    )
    val checkpointEventSink = RecordingEventSink()
    var initialRequestIndex = 0
    val initialGateway = object : LiteLlmGateway {
      override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
        return if (initialRequestIndex++ == 0) {
          LiteLlmGatewayResult(
            requestId = request.requestId,
            status = LiteLlmGatewayStatus.SUCCESS,
            completionMode = LiteLlmCompletionMode.PRIMARY,
            completion = LiteLlmStructuredCompletion(
              toolCalls = listOf(
                LiteLlmStructuredToolCall(
                  id = "call_1",
                  toolName = "workspace_read_file",
                  arguments = JsonObject(
                    mapOf("path" to JsonPrimitive("README.md")),
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
                startedAtEpochMs = 43_000L,
                finishedAtEpochMs = 43_001L,
              ),
            ),
            startedAtEpochMs = 43_000L,
            finishedAtEpochMs = 43_001L,
          )
        } else {
          LiteLlmGatewayResult(
            requestId = request.requestId,
            status = LiteLlmGatewayStatus.SUCCESS,
            completionMode = LiteLlmCompletionMode.PRIMARY,
            completion = LiteLlmStructuredCompletion(
              finalText = "Initial run finished.",
            ),
            selectedRoute = selection,
            attempts = listOf(
              LiteLlmAttemptRecord(
                route = selection,
                outcome = LiteLlmAttemptOutcome.SUCCESS,
                outputChars = 0,
                startedAtEpochMs = 43_002L,
                finishedAtEpochMs = 43_003L,
              ),
            ),
            startedAtEpochMs = 43_002L,
            finishedAtEpochMs = 43_003L,
          )
        }
      }
    }
    val initialRuntime = OpenCrayAgentRuntime(
      gateway = initialGateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(
        maxTurns = 4,
        maxToolCalls = 2,
        llmMetadata = mapOf(
          "protocol" to "openai",
          "nativeToolCallingAvailable" to "true",
        ),
      ),
      eventSink = checkpointEventSink,
      clock = IncrementingClock(start = 43_500L)::next,
    )

    val initialResult = initialRuntime.execute(
      task = promptTask(input = "Read the README and then answer."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, initialResult.status)
    val checkpointState = requireNotNull(
      OpenCrayPromptResumeMetadata.decodeFromMetadata(
        metadata = checkpointEventSink.events
          .filterIsInstance<OpenCrayToolResultEvent>()
          .first()
          .result
          .metadata,
        json = Json { ignoreUnknownKeys = true },
      ),
    )
    assertNotNull(checkpointState.localContinuationEnvelope)
    val checkpointEnvelope = requireNotNull(checkpointState.localContinuationEnvelope)
    assertTrue(checkpointEnvelope.durableContextPrompt.orEmpty().contains("[Tool Protocol]"))
    assertFalse(checkpointEnvelope.durableContextPrompt.orEmpty().contains("[Task Metadata]"))
    assertFalse(checkpointEnvelope.dynamicContextPrompt.orEmpty().contains("[Task Metadata]"))
    assertFalse(checkpointEnvelope.dynamicContextPrompt.orEmpty().contains("[Tool Protocol]"))
    val checkpointFrontUserMessages = checkpointEnvelope
      .gatewayMessages
      .filter { message -> message.role == LiteLlmGatewayMessageRole.USER.name }
      .mapNotNull { message -> message.content }
    assertTrue(checkpointFrontUserMessages[0].contains("[Tool Protocol]"))
    assertTrue(checkpointFrontUserMessages.none { message -> message.contains("[Task Metadata]") })

    val resumedRequests = mutableListOf<LiteLlmGatewayRequest>()
    val resumedGateway = object : LiteLlmGateway {
      override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
        resumedRequests += request
        return LiteLlmGatewayResult(
          requestId = request.requestId,
          status = LiteLlmGatewayStatus.SUCCESS,
          completionMode = LiteLlmCompletionMode.PRIMARY,
          completion = LiteLlmStructuredCompletion(
            finalText = "Resumed with durable local continuation.",
          ),
          selectedRoute = selection,
          attempts = listOf(
            LiteLlmAttemptRecord(
              route = selection,
              outcome = LiteLlmAttemptOutcome.SUCCESS,
              outputChars = 0,
              startedAtEpochMs = 43_010L,
              finishedAtEpochMs = 43_011L,
            ),
          ),
          startedAtEpochMs = 43_010L,
          finishedAtEpochMs = 43_011L,
        )
      }
    }
    val resumedRuntime = OpenCrayAgentRuntime(
      gateway = resumedGateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(
        maxTurns = 4,
        maxToolCalls = 2,
        promptResumeState = checkpointState,
        supplementInputProvider = { _, _ ->
          listOf(
            OpenCraySupplementInput(
              entryId = "supplement-restored",
              text = "Also check the durable resume path.",
              createdAtEpochMs = 44_000L,
            ),
          )
        },
        llmMetadata = mapOf(
          "protocol" to "openai",
          "nativeToolCallingAvailable" to "true",
        ),
      ),
      clock = IncrementingClock(start = 44_500L)::next,
    )

    val resumedResult = resumedRuntime.execute(
      task = promptTask(input = "Read the README and then answer."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, resumedResult.status)
    assertEquals("Resumed with durable local continuation.", resumedResult.stdout)
    assertEquals(1, resumedRequests.size)
    val resumedCacheMetadata = resumedRequests.single().metadata
    assertEquals("local_front_patch", resumedRequests.single().metadata["localContinuationMode"])
    assertEquals("dynamic_context_changed", resumedRequests.single().metadata["localContinuationReason"])
    assertEquals(
      "dynamic_context_changed",
      resumedRequests.single().metadata[LiteLlmMetadataKeys.CONTEXT_CACHE_BREAK_REASON],
    )
    assertEquals(
      "non_responses_front_zone_v1",
      resumedCacheMetadata[LiteLlmMetadataKeys.CONTEXT_CACHE_CONTRACT_VERSION],
    )
    assertEquals(
      promptCacheShapeHash(checkpointEnvelope.stableAnchor),
      resumedCacheMetadata[LiteLlmMetadataKeys.CONTEXT_CACHE_STABLE_ANCHOR_HASH],
    )
    assertEquals(
      promptCacheShapeHash(checkpointEnvelope.durableContextPrompt.orEmpty()),
      resumedCacheMetadata[LiteLlmMetadataKeys.CONTEXT_CACHE_DURABLE_CONTEXT_HASH],
    )
    assertNotEquals(
      promptCacheShapeHash(checkpointEnvelope.dynamicContextPrompt.orEmpty()),
      resumedCacheMetadata[LiteLlmMetadataKeys.CONTEXT_CACHE_DYNAMIC_CONTEXT_HASH],
    )
    assertEquals(
      resumedCacheMetadata[LiteLlmMetadataKeys.CONTEXT_CACHE_DYNAMIC_CONTEXT_HASH],
      resumedResult.metadata[LiteLlmMetadataKeys.CONTEXT_CACHE_DYNAMIC_CONTEXT_HASH],
    )
    assertEquals("1", resumedResult.metadata["localContinuationUsedCount"])
    assertEquals("0", resumedResult.metadata["localContinuationFallbackCount"])
    assertEquals("local_front_patch", resumedResult.metadata["localContinuationLastMode"])
    assertEquals(
      "dynamic_context_changed",
      resumedResult.metadata[LiteLlmMetadataKeys.CONTEXT_CACHE_BREAK_REASON],
    )
    val resumedUserMessages = resumedRequests.single().messages
      .filter { message -> message.role == LiteLlmGatewayMessageRole.USER }
      .mapNotNull { message -> message.content }
    assertTrue(resumedUserMessages.contains("Also check the durable resume path."))
    assertTrue(
      resumedRequests.single().prompt.contains(
        "Continue from the saved checkpoint state instead of restarting from the original task input.",
      ),
    )
    assertTrue(
      resumedRequests.single().messages.any { message ->
        message.role == LiteLlmGatewayMessageRole.TOOL &&
          message.toolResult?.toolName == "workspace_read_file"
      },
    )
  }

  @Test
  fun restoredGeneralResumeCheckpointFallsBackWhenDynamicFrontPatchCannotAlignStoredPrefix() {
    val workspaceRoot = temporaryFolder.newFolder("agent-restored-local-continuation-corrupt-prefix")
    Files.write(
      workspaceRoot.toPath().resolve("README.md"),
      "hello from workspace".toByteArray(StandardCharsets.UTF_8),
    )
    val task = promptTask(input = "Read the README and then answer.")
    val eventSink = RecordingEventSink()
    val initialGateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"workspace_read_file","arguments":{"path":"README.md"}}""",
        """{"type":"final","answer":"Initial run finished."}""",
      ),
    )
    val initialRuntime = OpenCrayAgentRuntime(
      gateway = initialGateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(
        maxTurns = 4,
        maxToolCalls = 2,
        llmMetadata = mapOf(
          "protocol" to "openai",
          "nativeToolCallingAvailable" to "true",
        ),
      ),
      eventSink = eventSink,
      clock = IncrementingClock(start = 45_000L)::next,
    )

    val initialResult = initialRuntime.execute(
      task = task,
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, initialResult.status)
    val checkpointState = checkpointStateFromFirstToolResult(eventSink)
    val checkpointEnvelope = requireNotNull(checkpointState.localContinuationEnvelope)
    val corruptedEnvelope = checkpointEnvelope.copy(
      gatewayMessages = checkpointEnvelope.gatewayMessages.drop(1),
    )
    val resumedGateway = RecordingGateway(
      outputs = listOf("""{"type":"final","answer":"Recovered after invalid front patch."}"""),
    )
    val resumedRuntime = OpenCrayAgentRuntime(
      gateway = resumedGateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(
        maxTurns = 4,
        maxToolCalls = 2,
        promptResumeState = checkpointState.copy(localContinuationEnvelope = corruptedEnvelope),
        supplementInputProvider = { _, _ ->
          listOf(
            OpenCraySupplementInput(
              entryId = "supplement-restored",
              text = "Also check the durable resume path.",
              createdAtEpochMs = 45_250L,
            ),
          )
        },
        llmMetadata = mapOf(
          "protocol" to "openai",
          "nativeToolCallingAvailable" to "true",
        ),
      ),
      clock = IncrementingClock(start = 45_500L)::next,
    )

    val resumedResult = resumedRuntime.execute(
      task = task,
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, resumedResult.status)
    assertEquals("Recovered after invalid front patch.", resumedResult.stdout)
    assertEquals(1, resumedGateway.requests.size)
    assertEquals("full_rebuild", resumedGateway.requests.single().metadata["localContinuationMode"])
    assertEquals("dynamic_context_changed", resumedGateway.requests.single().metadata["localContinuationReason"])
    assertEquals(
      "dynamic_context_changed",
      resumedGateway.requests.single().metadata[LiteLlmMetadataKeys.CONTEXT_CACHE_BREAK_REASON],
    )
    assertEquals("0", resumedResult.metadata["localContinuationUsedCount"])
    assertEquals("1", resumedResult.metadata["localContinuationFallbackCount"])
  }

  @Test
  fun deriveContextCacheBreakReasonMapsZoneAwareLocalContinuationReasons() {
    assertEquals(
      "tool_pool_changed",
      deriveContextCacheBreakReason(localContinuationReason = "tool_pool_changed"),
    )
    assertEquals(
      "tool_schema_changed",
      deriveContextCacheBreakReason(localContinuationReason = "tool_schema_changed"),
    )
    assertEquals(
      "user_setting_changed",
      deriveContextCacheBreakReason(localContinuationReason = "user_setting_changed"),
    )
    assertEquals(
      "durable_context_changed",
      deriveContextCacheBreakReason(localContinuationReason = "durable_context_changed"),
    )
    assertEquals(
      "dynamic_context_changed",
      deriveContextCacheBreakReason(localContinuationReason = "dynamic_context_changed"),
    )
    assertEquals(
      "dynamic_context_changed",
      deriveContextCacheBreakReason(localContinuationReason = "responses_context_update_chain_limit"),
    )
    assertEquals(
      "dynamic_context_changed",
      deriveContextCacheBreakReason(localContinuationReason = "responses_context_update_too_large"),
    )
    assertEquals(
      "continuation_lineage_untrusted",
      deriveContextCacheBreakReason(
        localContinuationReason = "responses_legacy_json_fallback_enabled",
        hasHistoricalResponsesContinuation = true,
      ),
    )
    assertNull(
      deriveContextCacheBreakReason(
        localContinuationReason = "responses_lineage_unavailable",
        hasHistoricalResponsesContinuation = false,
      ),
    )
  }

  @Test
  fun deriveContextCacheBreakReasonMapsFrontContextChangeToDynamicContextCategory() {
    assertEquals(
      "dynamic_context_changed",
      deriveContextCacheBreakReason(localContinuationReason = "front_context_changed"),
    )
    assertEquals(
      "continuation_lineage_untrusted",
      deriveContextCacheBreakReason(
        localContinuationReason = "responses_legacy_json_fallback_enabled",
        hasHistoricalResponsesContinuation = true,
      ),
    )
    assertNull(
      deriveContextCacheBreakReason(
        localContinuationReason = "responses_lineage_unavailable",
        hasHistoricalResponsesContinuation = false,
      ),
    )
  }

  @Test
  fun runPromptTaskFullRebuildReplaysResponsesAssistantPhases() {
    val workspaceRoot = temporaryFolder.newFolder("agent-responses-full-rebuild")
    val selection = LiteLlmRouteSelectionMetadata(
      profileId = "test-profile",
      routeId = "responses-route",
      providerId = "openai",
      model = "gpt-5-mini",
      attemptIndex = 0,
    )
    val requests = mutableListOf<LiteLlmGatewayRequest>()
    val gateway = object : LiteLlmGateway {
      override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
        requests += request
        return LiteLlmGatewayResult(
          requestId = request.requestId,
          status = LiteLlmGatewayStatus.SUCCESS,
          completionMode = LiteLlmCompletionMode.PRIMARY,
          completion = LiteLlmStructuredCompletion(
            finalText = "Continuation rebuilt.",
          ),
          providerResponseId = "resp_9",
          providerLineageId = "lineage_9",
          selectedRoute = selection,
          attempts = listOf(
            LiteLlmAttemptRecord(
              route = selection,
              outcome = LiteLlmAttemptOutcome.SUCCESS,
              outputChars = 0,
              startedAtEpochMs = 41_000L,
              finishedAtEpochMs = 41_001L,
            ),
          ),
          startedAtEpochMs = 41_000L,
          finishedAtEpochMs = 41_001L,
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
          "nativeToolCallingAvailable" to "true",
          "responsesContinuationSupported" to "true",
          "nativeWebSearchEnabled" to "false",
        ),
        sessionContext = AgentRuntimeSessionContext(
          conversation = listOf(
            RuntimeConversationMessage(
              role = RuntimeConversationRole.USER,
              content = "Inspect the workspace.",
            ),
            RuntimeConversationMessage(
              role = RuntimeConversationRole.TOOL,
              content = """{"event_kind":"assistant_phase","phase":"commentary","run_id":"run-1","task_id":"task-1","turn":0,"text":"Inspecting README"}""",
              kind = RuntimeConversationMessageKind.COMMENTARY,
              commentary = RuntimeConversationCommentary(
                text = "Inspecting README",
                stage = "inspect",
              ),
            ),
            RuntimeConversationMessage(
              role = RuntimeConversationRole.ASSISTANT,
              content = "Previous final answer.",
              assistantPhase = RuntimeConversationAssistantPhase.FINAL_ANSWER,
            ),
          ),
        ),
      ),
      clock = IncrementingClock(start = 41_500L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Continue from the earlier analysis."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals(1, requests.size)
    val commentaryMessage = requests.single().messages.firstOrNull { message ->
      message.role == LiteLlmGatewayMessageRole.ASSISTANT &&
        message.assistantPhase == LiteLlmAssistantPhase.COMMENTARY
    }
    assertNotNull(commentaryMessage)
    assertEquals("Inspecting README", commentaryMessage?.content)
    val finalAnswerMessage = requests.single().messages.firstOrNull { message ->
      message.role == LiteLlmGatewayMessageRole.ASSISTANT &&
        message.assistantPhase == LiteLlmAssistantPhase.FINAL_ANSWER
    }
    assertNotNull(finalAnswerMessage)
    assertEquals("Previous final answer.", finalAnswerMessage?.content)
  }

  @Test
  fun runPromptTaskAllowsMultipleStructuredToolCallsWhenParallelToolCallsEnabled() {
    val workspaceRoot = temporaryFolder.newFolder("agent-parallel-tool-calls")
    Files.write(
      workspaceRoot.toPath().resolve("README.md"),
      "first".toByteArray(StandardCharsets.UTF_8),
    )
    Files.write(
      workspaceRoot.toPath().resolve("NOTES.md"),
      "second".toByteArray(StandardCharsets.UTF_8),
    )
    val selection = LiteLlmRouteSelectionMetadata(
      profileId = "test-profile",
      routeId = "parallel-route",
      providerId = "openai",
      model = "gpt-5-mini",
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
                  id = "call_1",
                  toolName = "workspace_read_file",
                  arguments = JsonObject(mapOf("path" to JsonPrimitive("README.md"))),
                ),
                LiteLlmStructuredToolCall(
                  id = "call_2",
                  toolName = "workspace_read_file",
                  arguments = JsonObject(mapOf("path" to JsonPrimitive("NOTES.md"))),
                ),
              ),
            ),
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
              finalText = "Read both files.",
            ),
            selectedRoute = selection,
            attempts = listOf(
              LiteLlmAttemptRecord(
                route = selection,
                outcome = LiteLlmAttemptOutcome.SUCCESS,
                outputChars = 0,
                startedAtEpochMs = 42_010L,
                finishedAtEpochMs = 42_011L,
              ),
            ),
            startedAtEpochMs = 42_010L,
            finishedAtEpochMs = 42_011L,
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
        llmMetadata = mapOf(
          "nativeToolCallingAvailable" to "true",
          "parallelToolCalls" to "true",
        ),
      ),
      clock = IncrementingClock(start = 42_500L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Read README.md and NOTES.md before answering."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Read both files.", result.stdout)
    assertEquals(2, requests.size)
    assertEquals(true, requests.first().parallelToolCalls)
    assertTrue(
      requests.first().prompt.contains(
        "you may return multiple tool calls in one response",
        ignoreCase = true,
      ),
    )
    assertFalse(requests.first().prompt.contains("Do not return multiple tool calls in one response."))
    val secondTurnToolResults = requests[1].messages
      .filter { message -> message.role == LiteLlmGatewayMessageRole.TOOL }
    assertEquals(2, secondTurnToolResults.size)
    assertTrue(secondTurnToolResults.any { message -> message.toolResult?.toolCallId == "call_1" })
    assertTrue(secondTurnToolResults.any { message -> message.toolResult?.toolCallId == "call_2" })
  }

  @Test
  fun runPromptTaskExecutesParallelSafeToolBatchConcurrently() {
    val workspaceRoot = temporaryFolder.newFolder("agent-parallel-safe-tool-batch")
    val selection = LiteLlmRouteSelectionMetadata(
      profileId = "test-profile",
      routeId = "test-route",
      providerId = "fake",
      model = "fake-model",
      attemptIndex = 0,
    )
    val requests = mutableListOf<LiteLlmGatewayRequest>()
    var requestIndex = 0
    val entered = CountDownLatch(2)
    val observedConcurrentStart = Collections.synchronizedList(mutableListOf<Boolean>())
    val webSearchProvider = object : WebSearchProvider {
      override val providerName: String = "test-search"

      override fun search(request: WebSearchRequest): WebSearchResult {
        entered.countDown()
        observedConcurrentStart += entered.await(1, TimeUnit.SECONDS)
        return WebSearchResult(
          providerName = providerName,
          results = listOf(
            WebSearchHit(
              title = "Result for ${request.query}",
              url = "https://example.com/${request.query}",
              snippet = "snippet",
            ),
          ),
        )
      }
    }
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
                  id = "call_1",
                  toolName = "WebSearch",
                  arguments = JsonObject(
                    mapOf("query" to JsonPrimitive("alpha")),
                  ),
                ),
                LiteLlmStructuredToolCall(
                  id = "call_2",
                  toolName = "WebSearch",
                  arguments = JsonObject(
                    mapOf("query" to JsonPrimitive("beta")),
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
                startedAtEpochMs = 52_000L,
                finishedAtEpochMs = 52_001L,
              ),
            ),
            startedAtEpochMs = 52_000L,
            finishedAtEpochMs = 52_001L,
          )
        } else {
          LiteLlmGatewayResult(
            requestId = request.requestId,
            status = LiteLlmGatewayStatus.SUCCESS,
            completionMode = LiteLlmCompletionMode.PRIMARY,
            completion = LiteLlmStructuredCompletion(
              finalText = "Parallel web searches completed.",
            ),
            selectedRoute = selection,
            attempts = listOf(
              LiteLlmAttemptRecord(
                route = selection,
                outcome = LiteLlmAttemptOutcome.SUCCESS,
                outputChars = 0,
                startedAtEpochMs = 52_010L,
                finishedAtEpochMs = 52_011L,
              ),
            ),
            startedAtEpochMs = 52_010L,
            finishedAtEpochMs = 52_011L,
          )
        }
      }
    }
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
          webSearchProvider = webSearchProvider,
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(
        llmMetadata = mapOf(
          "nativeToolCallingAvailable" to "true",
          "parallelToolCalls" to "true",
        ),
      ),
      clock = IncrementingClock(start = 52_500L)::next,
    )

    val result = runtime.execute(
      task = promptTask(
        input = "Search both queries before answering.",
        metadata = mapOf("chatMode" to "DEVELOPER"),
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Parallel web searches completed.", result.stdout)
    assertEquals(2, observedConcurrentStart.size)
    assertTrue(observedConcurrentStart.all { started -> started })
    val secondTurnToolResults = requests[1].messages
      .filter { message -> message.role == LiteLlmGatewayMessageRole.TOOL }
    assertEquals(2, secondTurnToolResults.size)
    assertTrue(secondTurnToolResults.any { message -> message.toolResult?.toolCallId == "call_1" })
    assertTrue(secondTurnToolResults.any { message -> message.toolResult?.toolCallId == "call_2" })
  }

  @Test
  fun runPromptTaskUsesLocalDeltaContinuationForSupplementsOnNonResponsesRoute() {
    val workspaceRoot = temporaryFolder.newFolder("agent-local-continuation-workspace")
    Files.write(
      workspaceRoot.toPath().resolve("README.md"),
      "hello from workspace".toByteArray(StandardCharsets.UTF_8),
    )

    val selection = LiteLlmRouteSelectionMetadata(
      profileId = "test-profile",
      routeId = "test-route",
      providerId = "openai",
      model = "gpt-5-mini",
      attemptIndex = 0,
    )
    val requests = mutableListOf<LiteLlmGatewayRequest>()
    var requestIndex = 0
    var supplementProviderCalls = 0
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
                  id = "call_1",
                  toolName = "workspace_read_file",
                  arguments = JsonObject(
                    mapOf("path" to JsonPrimitive("README.md")),
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
                startedAtEpochMs = 31_000L,
                finishedAtEpochMs = 31_001L,
              ),
            ),
            startedAtEpochMs = 31_000L,
            finishedAtEpochMs = 31_001L,
          )
        } else {
          LiteLlmGatewayResult(
            requestId = request.requestId,
            status = LiteLlmGatewayStatus.SUCCESS,
            completionMode = LiteLlmCompletionMode.PRIMARY,
            completion = LiteLlmStructuredCompletion(
              finalText = "I saw the local continuation.",
            ),
            selectedRoute = selection,
            attempts = listOf(
              LiteLlmAttemptRecord(
                route = selection,
                outcome = LiteLlmAttemptOutcome.SUCCESS,
                outputChars = 0,
                startedAtEpochMs = 31_002L,
                finishedAtEpochMs = 31_003L,
              ),
            ),
            startedAtEpochMs = 31_002L,
            finishedAtEpochMs = 31_003L,
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
        llmMetadata = mapOf(
          "protocol" to "openai",
          "nativeToolCallingAvailable" to "true",
        ),
      ),
      clock = IncrementingClock(start = 31_500L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Read the README and then answer."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("I saw the local continuation.", result.stdout)
    assertEquals(2, requests.size)
    assertEquals("full_rebuild", requests[0].metadata["localContinuationMode"])
    assertEquals("local_delta", requests[1].metadata["localContinuationMode"])
    assertEquals("transcript_delta", requests[1].metadata["localContinuationReason"])
    assertEquals("1", result.metadata["localContinuationUsedCount"])
    assertEquals("0", result.metadata["localContinuationFallbackCount"])
    val firstTurnUserMessages = requests[0].messages
      .filter { message -> message.role == LiteLlmGatewayMessageRole.USER }
      .mapNotNull { message -> message.content }
    assertTrue(firstTurnUserMessages[0].contains("[Tool Protocol]"))
    assertTrue(firstTurnUserMessages.none { message -> message.contains("[Task Metadata]") })
    assertEquals("Read the README and then answer.", firstTurnUserMessages.last())
    val secondTurnUserMessages = requests[1].messages
      .filter { message -> message.role == LiteLlmGatewayMessageRole.USER }
      .mapNotNull { message -> message.content }
    assertTrue(secondTurnUserMessages[0].contains("[Tool Protocol]"))
    assertTrue(secondTurnUserMessages.none { message -> message.contains("[Task Metadata]") })
    assertTrue(secondTurnUserMessages.contains("Read the README and then answer."))
    assertTrue(secondTurnUserMessages.contains("Also verify the tests before you answer."))
    val replayedToolResult = requests[1].messages
      .firstOrNull { message -> message.role == LiteLlmGatewayMessageRole.TOOL }
      ?.toolResult
    assertEquals("call_1", replayedToolResult?.toolCallId)
    assertEquals("workspace_read_file", replayedToolResult?.toolName)
  }

  @Test
  fun runPromptTaskFallsBackToFullRebuildWhenActiveSkillChangesToolPoolFingerprint() {
    val workspaceRoot = temporaryFolder.newFolder("agent-local-continuation-skill-workspace")
    val skillsRoot = temporaryFolder.newFolder("agent-local-continuation-skill-root")
    writeSkill(
      root = skillsRoot,
      relativeDirectory = "ui-ux-pro-max",
      frontMatter = """
        name: ui-ux-pro-max
        description: High-end UI review workflow.
        invocation-control: explicit-only
        user-invocable: true
        allowed-tools: [ read, write ]
      """.trimIndent(),
      body = """
        # UI UX Pro Max

        Audit the current interface first, then apply a concrete design system.
      """.trimIndent(),
    )
    val skillCatalog = SkillCatalogResolver().resolve(listOf(skillsRoot))
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"skill_read","arguments":{"name":"ui-ux-pro-max"}}""",
        """{"type":"final","answer":"Used the rebuilt continuation after the skill activation."}""",
      ),
    )
    var supplementProviderCalls = 0
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
          skillsRoots = listOf(skillsRoot),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(
        maxTurns = 4,
        maxToolCalls = 2,
        sessionContext = AgentRuntimeSessionContext(
          skillInventory = skillCatalog.inventory,
          skillCatalog = skillCatalog,
        ),
        supplementInputProvider = { _, _ ->
          supplementProviderCalls += 1
          if (supplementProviderCalls == 2) {
            listOf(
              OpenCraySupplementInput(
                entryId = "supplement-1",
                text = "Apply the UI workflow to this answer too.",
                createdAtEpochMs = 2_500L,
              ),
            )
          } else {
            emptyList()
          }
        },
      ),
      clock = IncrementingClock(start = 32_500L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Load the UI skill, then follow it."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals(2, gateway.requests.size)
    assertEquals("full_rebuild", gateway.requests[1].metadata["localContinuationMode"])
    assertEquals("tool_pool_changed", gateway.requests[1].metadata["localContinuationReason"])
    assertEquals(
      "tool_pool_changed",
      gateway.requests[1].metadata[LiteLlmMetadataKeys.CONTEXT_CACHE_BREAK_REASON],
    )
    assertEquals("1", result.metadata["localContinuationFallbackCount"])
    assertEquals("full_rebuild", result.metadata["localContinuationLastMode"])
    assertEquals("tool_pool_changed", result.metadata["localContinuationLastReason"])
    assertEquals(
      "tool_pool_changed",
      result.metadata[LiteLlmMetadataKeys.CONTEXT_CACHE_BREAK_REASON],
    )
    assertTrue(gateway.requests[1].prompt.contains("[Active Skill]"))
    assertTrue(gateway.requests[1].prompt.contains("name=ui-ux-pro-max"))
    val secondTurnUserMessages = gateway.requests[1].messages
      .filter { message -> message.role == LiteLlmGatewayMessageRole.USER }
      .mapNotNull { message -> message.content }
    assertTrue(secondTurnUserMessages.contains("Apply the UI workflow to this answer too."))
  }

  @Test
  fun runPromptTaskUsesResponsesBuiltinWebSearchForOpenAiProvider() {
    val workspaceRoot = temporaryFolder.newFolder("agent-native-web-search-workspace")
    val requests = mutableListOf<LiteLlmGatewayRequest>()
    val selection = LiteLlmRouteSelectionMetadata(
      profileId = "test-profile",
      routeId = "test-route",
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
          outputText = "Found it.",
          completion = LiteLlmStructuredCompletion(finalText = "Found it."),
          selectedRoute = selection,
          attempts = listOf(
            LiteLlmAttemptRecord(
              route = selection,
              outcome = LiteLlmAttemptOutcome.SUCCESS,
              outputChars = 8,
              startedAtEpochMs = 21_000L,
              finishedAtEpochMs = 21_001L,
            ),
          ),
          startedAtEpochMs = 21_000L,
          finishedAtEpochMs = 21_001L,
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
          "responsesContinuationSupported" to "true",
          "_host.providerId" to "openai",
        ),
      ),
      clock = IncrementingClock(start = 21_500L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Find the latest OpenAI docs."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals(1, requests.size)
    assertEquals(LiteLlmBuiltinToolType.WEB_SEARCH, requests.single().builtinTools.single().type)
    assertTrue(requests.single().builtinTools.single().includeSources)
    assertFalse(requests.single().tools.any { tool -> tool.name == "WebSearch" })
  }

  @Test
  fun runPromptTaskKeepsHostWebSearchForCustomResponsesProviderByDefault() {
    val workspaceRoot = temporaryFolder.newFolder("agent-host-web-search-workspace")
    val requests = mutableListOf<LiteLlmGatewayRequest>()
    val selection = LiteLlmRouteSelectionMetadata(
      profileId = "test-profile",
      routeId = "test-route",
      providerId = "custom",
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
          outputText = "Done.",
          completion = LiteLlmStructuredCompletion(finalText = "Done."),
          selectedRoute = selection,
          attempts = listOf(
            LiteLlmAttemptRecord(
              route = selection,
              outcome = LiteLlmAttemptOutcome.SUCCESS,
              outputChars = 5,
              startedAtEpochMs = 22_000L,
              finishedAtEpochMs = 22_001L,
            ),
          ),
          startedAtEpochMs = 22_000L,
          finishedAtEpochMs = 22_001L,
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
          "responsesContinuationSupported" to "true",
          "_host.providerId" to "custom",
          "_host.baseUrl" to "https://third-party.example/v1",
        ),
      ),
      clock = IncrementingClock(start = 22_500L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Look something up."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals(1, requests.size)
    assertTrue(requests.single().builtinTools.isEmpty())
    assertTrue(requests.single().tools.any { tool -> tool.name == "WebSearch" })
  }

  @Test
  fun runPromptTaskDisablesAllWebSearchWhenExplicitlyDisabled() {
    val workspaceRoot = temporaryFolder.newFolder("agent-web-search-disabled")
    val requests = mutableListOf<LiteLlmGatewayRequest>()
    val gateway = object : LiteLlmGateway {
      override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
        requests += request
        return gatewaySuccessResult(
          outputText = "Search is unavailable for this run.",
          completion = LiteLlmStructuredCompletion(finalText = "Search is unavailable for this run."),
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
          "responsesContinuationSupported" to "true",
          "webSearchEnabled" to "false",
          "_host.providerId" to "openai",
        ),
      ),
      clock = IncrementingClock(start = 23_500L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Find the latest OpenAI docs."),
      hooks = runtimeHooks(),
    )
    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals(1, requests.size)
    assertTrue(requests.single().builtinTools.isEmpty())
    assertFalse(requests.single().tools.any { tool -> tool.name == "WebSearch" })
  }

  @Test
  fun runPromptTaskEmitsSyntheticEventsForProviderNativeWebSearch() {
    val workspaceRoot = temporaryFolder.newFolder("agent-native-web-search-events")
    val observationJson = Json.encodeToString(
      ListSerializer(LiteLlmBuiltinWebSearchObservation.serializer()),
      listOf(
        LiteLlmBuiltinWebSearchObservation(
          actionType = "search",
          status = "completed",
          queries = listOf("OpenAI docs"),
          sources = listOf(
            LiteLlmBuiltinWebSearchSource(
              title = "OpenAI Docs",
              url = "https://platform.openai.com/docs",
            ),
          ),
        ),
      ),
    )
    val selection = LiteLlmRouteSelectionMetadata(
      profileId = "test-profile",
      routeId = "test-route",
      providerId = "openai",
      model = "gpt-5-mini",
      attemptIndex = 0,
    )
    val eventSink = RecordingEventSink()
    val gateway = object : LiteLlmGateway {
      override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult =
        LiteLlmGatewayResult(
          requestId = request.requestId,
          status = LiteLlmGatewayStatus.SUCCESS,
          completionMode = LiteLlmCompletionMode.PRIMARY,
          outputText = "Found it.",
          completion = LiteLlmStructuredCompletion(finalText = "Found it."),
          selectedRoute = selection,
          attempts = listOf(
            LiteLlmAttemptRecord(
              route = selection,
              outcome = LiteLlmAttemptOutcome.SUCCESS,
              outputChars = 8,
              startedAtEpochMs = 24_000L,
              finishedAtEpochMs = 24_001L,
            ),
          ),
          startedAtEpochMs = 24_000L,
          finishedAtEpochMs = 24_001L,
          metadata = mapOf(
            LiteLlmMetadataKeys.BUILTIN_WEB_SEARCH_USED to "true",
            LiteLlmMetadataKeys.BUILTIN_WEB_SEARCH_OBSERVATIONS_JSON to observationJson,
          ),
        )
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
          "responsesContinuationSupported" to "true",
          "_host.providerId" to "openai",
        ),
      ),
      eventSink = eventSink,
      clock = IncrementingClock(start = 24_500L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Find the latest OpenAI docs."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    val toolCall = eventSink.events
      .filterIsInstance<OpenCrayToolCallEvent>()
      .single { event -> event.call.toolName == "WebSearch" }
    val toolResult = eventSink.events
      .filterIsInstance<OpenCrayToolResultEvent>()
      .single { event -> event.result.toolName == "WebSearch" }
    assertEquals("search", toolCall.call.arguments["operation"]?.jsonPrimitive?.content)
    assertEquals("OpenAI docs", toolCall.call.arguments["query"]?.jsonPrimitive?.content)
    assertEquals(
      "https://platform.openai.com/docs",
      toolResult.result.metadata["sourceUrls"],
    )
    assertEquals(
      "true",
      toolResult.result.metadata[ProviderNativeWebSearchSupport.RESULT_METADATA_PROVIDER_MANAGED],
    )
    val promptResumeState = OpenCrayPromptResumeMetadata.decodeFromMetadata(
      metadata = toolResult.result.metadata,
      json = Json,
    )
    assertNotNull(promptResumeState)
    assertEquals(1, promptResumeState?.turnIndex)
    assertTrue(toolResult.result.content.contains("OpenAI Docs"))
  }

  @Test
  fun runPromptTaskKeepsHostWebSearchWhenResponsesBuiltinSearchIsExplicitlyDisabled() {
    val workspaceRoot = temporaryFolder.newFolder("agent-host-web-search-disabled-workspace")
    val requests = mutableListOf<LiteLlmGatewayRequest>()
    val selection = LiteLlmRouteSelectionMetadata(
      profileId = "test-profile",
      routeId = "test-route",
      providerId = "custom",
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
          outputText = "Done.",
          completion = LiteLlmStructuredCompletion(finalText = "Done."),
          selectedRoute = selection,
          attempts = listOf(
            LiteLlmAttemptRecord(
              route = selection,
              outcome = LiteLlmAttemptOutcome.SUCCESS,
              outputChars = 5,
              startedAtEpochMs = 23_000L,
              finishedAtEpochMs = 23_001L,
            ),
          ),
          startedAtEpochMs = 23_000L,
          finishedAtEpochMs = 23_001L,
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
          "responsesContinuationSupported" to "true",
          "_host.providerId" to "custom",
          "nativeWebSearchEnabled" to "false",
        ),
      ),
      clock = IncrementingClock(start = 23_500L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Look something up."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals(1, requests.size)
    assertTrue(requests.single().builtinTools.isEmpty())
    assertTrue(requests.single().tools.any { tool -> tool.name == "WebSearch" })
  }

  @Test
  fun runPromptTaskRequestsBuiltinWebSearchForOpenAiCompatibleRouteWhenEnabled() {
    val workspaceRoot = temporaryFolder.newFolder("agent-openai-builtin-web-search-workspace")
    val requests = mutableListOf<LiteLlmGatewayRequest>()
    val selection = LiteLlmRouteSelectionMetadata(
      profileId = "test-profile",
      routeId = "test-route",
      providerId = "custom",
      model = "glm-4.6",
      attemptIndex = 0,
    )
    val gateway = object : LiteLlmGateway {
      override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
        requests += request
        return LiteLlmGatewayResult(
          requestId = request.requestId,
          status = LiteLlmGatewayStatus.SUCCESS,
          completionMode = LiteLlmCompletionMode.PRIMARY,
          outputText = "Done.",
          completion = LiteLlmStructuredCompletion(finalText = "Done."),
          selectedRoute = selection,
          attempts = listOf(
            LiteLlmAttemptRecord(
              route = selection,
              outcome = LiteLlmAttemptOutcome.SUCCESS,
              outputChars = 5,
              startedAtEpochMs = 24_000L,
              finishedAtEpochMs = 24_001L,
            ),
          ),
          startedAtEpochMs = 24_000L,
          finishedAtEpochMs = 24_001L,
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
          "protocol" to "openai",
          "_host.providerId" to "custom",
          "nativeWebSearchEnabled" to "true",
        ),
      ),
      clock = IncrementingClock(start = 24_500L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Look something up."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals(1, requests.size)
    assertEquals(LiteLlmBuiltinToolType.WEB_SEARCH, requests.single().builtinTools.single().type)
    assertTrue(requests.single().tools.none { tool -> tool.name == "WebSearch" })
  }

  @Test
  fun runPromptTaskSeedsStoredConversationIntoFirstLlmTurn() {
    val workspaceRoot = temporaryFolder.newFolder("agent-history-workspace")
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"final","answer":"延续了之前的对话"}""",
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
        sessionContext = AgentRuntimeSessionContext(
          sessionPolicyText = "Keep the session coherent with earlier decisions.",
          conversation = listOf(
            RuntimeConversationMessage(RuntimeConversationRole.USER, "Earlier question."),
            RuntimeConversationMessage(RuntimeConversationRole.ASSISTANT, "Earlier answer."),
          ),
        ),
      ),
      clock = IncrementingClock(start = 2_000L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "What changed since then?"),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("延续了之前的对话", result.stdout)
    assertEquals("3", result.metadata["contextMessageCount"])
    assertFalse(result.metadata["contextLayerNames"].orEmpty().contains("Task Metadata"))
    assertTrue(result.metadata["contextLayerNames"].orEmpty().contains("Conversation"))
    assertTrue(gateway.requests[0].systemPrompt.orEmpty().contains("[Session Policy]"))
    assertTrue(gateway.requests[0].prompt.contains("Earlier question."))
    assertTrue(gateway.requests[0].prompt.contains("Earlier answer."))
    assertTrue(gateway.requests[0].prompt.contains("What changed since then?"))
  }

  @Test
  fun runPromptTaskCarriesFinalAttachmentsIntoResultMetadata() {
    val workspaceRoot = temporaryFolder.newFolder("agent-final-attachments-workspace")
    val gateway = RecordingGateway(
      outputs = listOf(
        """
        {
          "type": "final",
          "answer": "",
          "attachments": [
            {
              "kind": "image",
              "relative_path": "outputs/result.png",
              "display_name": "result.png",
              "mime_type": "image/png"
            },
            {
              "kind": "audio",
              "path": "outputs/voice-note.m4a",
              "duration_ms": 4200,
              "waveform_bars": [12, 48, 80],
              "transcript_text": "Voice summary"
            }
          ]
        }
        """.trimIndent(),
      ),
    )
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      clock = IncrementingClock(start = 2_200L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Send the generated media only."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("", result.stdout)
    val attachmentsJson = result.metadata[OpenCrayExecutionMetadataKeys.FINAL_ATTACHMENTS_JSON].orEmpty()
    val attachments = Json.decodeFromString(
      ListSerializer(OpenCrayFinalAttachment.serializer()),
      attachmentsJson,
    )
    assertEquals(2, attachments.size)
    assertEquals("outputs/result.png", attachments.first().relativePath)
    assertEquals("image", attachments.first().kind)
    assertEquals("outputs/voice-note.m4a", attachments.last().path)
    assertEquals("audio", attachments.last().kind)
    assertEquals(4_200L, attachments.last().durationMs)
    assertEquals(listOf(12, 48, 80), attachments.last().waveformBars)
    assertEquals("Voice summary", attachments.last().transcriptText)
  }

  @Test
  fun runPromptTaskCarriesNativeFinalAttachmentsIntoResultMetadata() {
    val workspaceRoot = temporaryFolder.newFolder("agent-native-final-attachments-workspace")
    val gateway = ScriptedGateway(
      results = listOf(
        gatewaySuccessResult(
          outputText = "",
          completion = LiteLlmStructuredCompletion(
            finalText = "Attached native media.",
            finalAttachments = listOf(
              LiteLlmStructuredFinalAttachment(
                kind = " image ",
                artifactId = " artifact-native-image-1 ",
                displayName = " native.png ",
                mimeType = " image/png ",
              ),
              LiteLlmStructuredFinalAttachment(
                kind = "voice",
                relativePath = "outputs/native-voice.m4a",
                durationMs = 3_200L,
                waveformBars = listOf(10, 20, 30),
                transcriptText = " Native voice summary ",
              ),
            ),
          ),
        ),
      ),
    )
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      clock = IncrementingClock(start = 2_350L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Send native media attachments."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Attached native media.", result.stdout)
    assertEquals("native_structured_final", result.metadata["responseFormat"])
    val attachmentsJson = result.metadata[OpenCrayExecutionMetadataKeys.FINAL_ATTACHMENTS_JSON].orEmpty()
    val attachments = Json.decodeFromString(
      ListSerializer(OpenCrayFinalAttachment.serializer()),
      attachmentsJson,
    )
    assertEquals(2, attachments.size)
    assertEquals("artifact-native-image-1", attachments.first().artifactId)
    assertEquals("image", attachments.first().kind)
    assertEquals("native.png", attachments.first().displayName)
    assertEquals("image/png", attachments.first().mimeType)
    assertEquals("outputs/native-voice.m4a", attachments.last().relativePath)
    assertEquals("voice", attachments.last().kind)
    assertEquals(3_200L, attachments.last().durationMs)
    assertEquals(listOf(10, 20, 30), attachments.last().waveformBars)
    assertEquals("Native voice summary", attachments.last().transcriptText)
  }

  @Test
  fun runPromptTaskExposesMemoryRecallTraceInResultMetadata() {
    val workspaceRoot = temporaryFolder.newFolder("agent-memory-trace-workspace")
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"final","answer":"Applied the recalled memory."}""",
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
        sessionContext = AgentRuntimeSessionContext(
          recalledMemory = MemoryRecallResult(
            memories = listOf(
              RetrievedMemory(
                id = "memory-user",
                kind = MemoryKind.USER_PREFERENCE,
                scope = MemoryScope.USER,
                status = MemoryStatus.ACTIVE,
                content = "Default to concise Chinese replies.",
                lastConfirmedAtEpochMs = 2_000L,
                matchedTerms = listOf("chinese"),
                score = 420,
              ),
            ),
            matchedRecordCount = 2,
            omittedRecordCount = 1,
            trace = MemoryRecallTrace(
              queryTerms = listOf("chinese", "gradle"),
              selected = listOf(
                MemoryRecallSelectedTrace(
                  id = "memory-user",
                  kind = MemoryKind.USER_PREFERENCE,
                  scope = MemoryScope.USER,
                  score = 420,
                  matchedTerms = listOf("chinese"),
                  contentPreview = "Default to concise Chinese replies.",
                ),
              ),
            ),
          ),
        ),
      ),
      clock = IncrementingClock(start = 2_500L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Keep using Chinese and verify Gradle."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("2", result.metadata["contextMatchedMemoryCount"])
    assertEquals("1", result.metadata["contextInjectedMemoryCount"])
    assertEquals("1", result.metadata["contextOmittedMemoryCount"])
    assertEquals("chinese,gradle", result.metadata["contextMemoryQueryTerms"])
    assertEquals("memory-user@420[chinese]", result.metadata["contextMemorySelectedSummary"])
    assertTrue(gateway.requests.single().prompt.contains("[Retrieved Memory]"))
  }

  @Test
  fun runPromptTaskParsesChatAttachmentIdsFromFinalAttachments() {
    val workspaceRoot = temporaryFolder.newFolder("agent-chat-attachment-id-workspace")
    val gateway = RecordingGateway(
      outputs = listOf(
        """
        {
          "type": "final",
          "answer": "Attached the uploaded image.",
          "attachments": [
            {
              "chat_attachment_id": "user-image-1",
              "kind": "image"
            }
          ]
        }
        """.trimIndent(),
      ),
    )
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      clock = IncrementingClock(start = 3_200L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Send the uploaded image back."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Attached the uploaded image.", result.stdout)
    val attachmentsJson = result.metadata[OpenCrayExecutionMetadataKeys.FINAL_ATTACHMENTS_JSON].orEmpty()
    val attachments = Json.decodeFromString(
      ListSerializer(OpenCrayFinalAttachment.serializer()),
      attachmentsJson,
    )
    assertEquals(1, attachments.size)
    assertEquals("user-image-1", attachments.single().chatAttachmentId)
    assertEquals("image", attachments.single().kind)
  }

  @Test
  fun runPromptTaskRebuildsGatewayAttachmentsFromHiddenPromptMetadata() {
    val workspaceRoot = temporaryFolder.newFolder("agent-hidden-attachment-metadata").toPath()
    val attachmentPath = workspaceRoot.resolve("imports").resolve("camera-first.png")
    Files.createDirectories(attachmentPath.parent)
    Files.write(attachmentPath, byteArrayOf(1, 2, 3, 4))
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"final","answer":"Saw the uploaded image."}""",
      ),
    )
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot),
        ),
      ),
      clock = IncrementingClock(start = 3_300L)::next,
    )
    val attachmentsJson = Json.encodeToString(
      ListSerializer(RuntimeConversationAttachment.serializer()),
      listOf(
        RuntimeConversationAttachment(
          attachmentId = "user-image-1",
          kind = RuntimeConversationAttachmentKind.IMAGE,
          displayName = "camera-first.png",
          filePath = attachmentPath.toString().replace('\\', '/'),
          mimeType = "image/png",
        ),
      ),
    )

    val result = runtime.execute(
      task = promptTask(
        input = "Attachment fallback placeholder",
        metadata = mapOf(
          "_host.promptUserText" to "",
          "_host.promptRuntimeAttachmentsJson" to attachmentsJson,
        ),
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    val userMessage = gateway.requests.single().messages.single { message ->
      message.role == LiteLlmGatewayMessageRole.USER && message.attachments.isNotEmpty()
    }
    assertEquals("", userMessage.content)
    assertEquals(1, userMessage.attachments.size)
    assertEquals("user-image-1", userMessage.attachments.single().attachmentId)
    assertEquals("camera-first.png", userMessage.attachments.single().displayName)
    assertEquals("image/png", userMessage.attachments.single().mimeType)
    assertEquals(attachmentPath.toString().replace('\\', '/'), userMessage.attachments.single().filePath)
  }

  @Test
  fun viewWorkspaceImageInjectsAttachmentIntoNextModelTurnAndInterruptsCurrentBatch() {
    val workspaceRoot = temporaryFolder.newFolder("agent-view-workspace-image").toPath()
    val imagePath = workspaceRoot.resolve("screens").resolve("camera-first.png")
    Files.createDirectories(imagePath.parent)
    Files.write(imagePath, byteArrayOf(1, 2, 3, 4))
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"actions":[{"type":"tool_call","tool_name":"view_workspace_image","arguments":{"path":"screens/camera-first.png"}},{"type":"final","answer":"I guessed from the filename."}]}""",
        """{"type":"final","answer":"I inspected the workspace image after it was attached."}""",
      ),
    )
    val eventSink = RecordingEventSink()
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot),
        ),
      ),
      eventSink = eventSink,
      clock = IncrementingClock(start = 3_600L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "What is in screens/camera-first.png?"),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("I inspected the workspace image after it was attached.", result.stdout)
    assertEquals(2, gateway.requests.size)
    val attachedUserMessage = gateway.requests[1].messages.single { message ->
      message.role == LiteLlmGatewayMessageRole.USER && message.attachments.isNotEmpty()
    }
    assertTrue(attachedUserMessage.content.orEmpty().contains("screens/camera-first.png"))
    assertEquals(1, attachedUserMessage.attachments.size)
    assertEquals("camera-first.png", attachedUserMessage.attachments.single().displayName)
    assertEquals("image/png", attachedUserMessage.attachments.single().mimeType)
    assertTrue(
      Files.isSameFile(
        imagePath,
        java.nio.file.Paths.get(requireNotNull(attachedUserMessage.attachments.single().filePath)),
      ),
    )
    val supplementEvent = visibleSupplementEvents(eventSink.events).single()
    assertEquals("post_tool_pre_model", supplementEvent.checkpoint)
    val resumeState = requireNotNull(
      OpenCrayPromptResumeMetadata.decodeFromMetadata(
        metadata = supplementEvent.metadata,
        json = Json { ignoreUnknownKeys = true },
      ),
    )
    val supplementMessage = resumeState.transcript.last()
    assertEquals(RuntimeConversationRole.USER, supplementMessage.role)
    assertEquals(1, supplementMessage.attachments.size)
    assertEquals("camera-first.png", supplementMessage.attachments.single().displayName)
    assertEquals("image/png", supplementMessage.attachments.single().mimeType)
    assertTrue(
      Files.isSameFile(
        imagePath,
        java.nio.file.Paths.get(requireNotNull(supplementMessage.attachments.single().filePath)),
      ),
    )
  }

  @Test
  fun viewWorkspacePdfInjectsAttachmentIntoNextModelTurnAndInterruptsCurrentBatch() {
    val workspaceRoot = temporaryFolder.newFolder("agent-view-workspace-pdf").toPath()
    val pdfPath = workspaceRoot.resolve("docs").resolve("report.pdf")
    Files.createDirectories(pdfPath.parent)
    Files.write(pdfPath, byteArrayOf(0x25, 0x50, 0x44, 0x46))
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"actions":[{"type":"tool_call","tool_name":"view_workspace_pdf","arguments":{"path":"docs/report.pdf"}},{"type":"final","answer":"I guessed from the filename."}]}""",
        """{"type":"final","answer":"I inspected the workspace PDF after it was attached."}""",
      ),
    )
    val eventSink = RecordingEventSink()
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot),
        ),
      ),
      eventSink = eventSink,
      clock = IncrementingClock(start = 4_200L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "What is in docs/report.pdf?"),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("I inspected the workspace PDF after it was attached.", result.stdout)
    assertEquals(2, gateway.requests.size)
    val attachedUserMessage = gateway.requests[1].messages.single { message ->
      message.role == LiteLlmGatewayMessageRole.USER && message.attachments.isNotEmpty()
    }
    assertTrue(attachedUserMessage.content.orEmpty().contains("docs/report.pdf"))
    assertEquals(1, attachedUserMessage.attachments.size)
    assertEquals("report.pdf", attachedUserMessage.attachments.single().displayName)
    assertEquals("application/pdf", attachedUserMessage.attachments.single().mimeType)
    assertTrue(
      Files.isSameFile(
        pdfPath,
        java.nio.file.Paths.get(requireNotNull(attachedUserMessage.attachments.single().filePath)),
      ),
    )
    val supplementEvent = visibleSupplementEvents(eventSink.events).single()
    assertEquals("post_tool_pre_model", supplementEvent.checkpoint)
    val resumeState = requireNotNull(
      OpenCrayPromptResumeMetadata.decodeFromMetadata(
        metadata = supplementEvent.metadata,
        json = Json { ignoreUnknownKeys = true },
      ),
    )
    val supplementMessage = resumeState.transcript.last()
    assertEquals(RuntimeConversationRole.USER, supplementMessage.role)
    assertEquals(1, supplementMessage.attachments.size)
    assertEquals("report.pdf", supplementMessage.attachments.single().displayName)
    assertEquals("application/pdf", supplementMessage.attachments.single().mimeType)
    assertTrue(
      Files.isSameFile(
        pdfPath,
        java.nio.file.Paths.get(requireNotNull(supplementMessage.attachments.single().filePath)),
      ),
    )
  }

  @Test
  fun viewWorkspaceDocumentInjectsImageIntoNextModelTurn() {
    val workspaceRoot = temporaryFolder.newFolder("agent-view-workspace-document-image").toPath()
    val imagePath = workspaceRoot.resolve("screens").resolve("camera-first.png")
    Files.createDirectories(imagePath.parent)
    Files.write(imagePath, byteArrayOf(1, 2, 3, 4))
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"actions":[{"type":"tool_call","tool_name":"view_workspace_document","arguments":{"path":"screens/camera-first.png"}},{"type":"final","answer":"I guessed from the filename."}]}""",
        """{"type":"final","answer":"I inspected the workspace document image after it was attached."}""",
      ),
    )
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot),
        ),
      ),
      clock = IncrementingClock(start = 4_800L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "What is in screens/camera-first.png?"),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("I inspected the workspace document image after it was attached.", result.stdout)
    assertEquals(2, gateway.requests.size)
    val attachedUserMessage = gateway.requests[1].messages.single { message ->
      message.role == LiteLlmGatewayMessageRole.USER && message.attachments.isNotEmpty()
    }
    assertEquals(1, attachedUserMessage.attachments.size)
    assertEquals("camera-first.png", attachedUserMessage.attachments.single().displayName)
    assertEquals("image/png", attachedUserMessage.attachments.single().mimeType)
    assertTrue(
      Files.isSameFile(
        imagePath,
        java.nio.file.Paths.get(requireNotNull(attachedUserMessage.attachments.single().filePath)),
      ),
    )
  }

  @Test
  fun viewWorkspaceDocumentInjectsPdfIntoNextModelTurn() {
    val workspaceRoot = temporaryFolder.newFolder("agent-view-workspace-document-pdf").toPath()
    val pdfPath = workspaceRoot.resolve("docs").resolve("report.pdf")
    Files.createDirectories(pdfPath.parent)
    Files.write(pdfPath, byteArrayOf(0x25, 0x50, 0x44, 0x46))
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"actions":[{"type":"tool_call","tool_name":"view_workspace_document","arguments":{"path":"docs/report.pdf"}},{"type":"final","answer":"I guessed from the filename."}]}""",
        """{"type":"final","answer":"I inspected the workspace document PDF after it was attached."}""",
      ),
    )
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot),
        ),
      ),
      clock = IncrementingClock(start = 5_200L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "What is in docs/report.pdf?"),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("I inspected the workspace document PDF after it was attached.", result.stdout)
    assertEquals(2, gateway.requests.size)
    val attachedUserMessage = gateway.requests[1].messages.single { message ->
      message.role == LiteLlmGatewayMessageRole.USER && message.attachments.isNotEmpty()
    }
    assertEquals(1, attachedUserMessage.attachments.size)
    assertEquals("report.pdf", attachedUserMessage.attachments.single().displayName)
    assertEquals("application/pdf", attachedUserMessage.attachments.single().mimeType)
    assertTrue(
      Files.isSameFile(
        pdfPath,
        java.nio.file.Paths.get(requireNotNull(attachedUserMessage.attachments.single().filePath)),
      ),
    )
  }

  @Test
  fun runPromptTaskExposesSkillInventoryMetadata() {
    val workspaceRoot = temporaryFolder.newFolder("agent-skill-inventory-metadata")
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"final","answer":"Used the visible skill inventory."}""",
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
        sessionContext = AgentRuntimeSessionContext(
          skillInventory = SkillInventory(
            skills = listOf(
              VisibleSkill(
                name = "ui-ux-pro-max",
                description = "High-end UI review workflow.",
                relativePath = ".codex/skills/ui-ux-pro-max/SKILL.md",
                invocationControl = SkillInvocationControl.EXPLICIT_ONLY,
                userInvocable = true,
                executionContext = SkillExecutionContext.INLINE,
              ),
              VisibleSkill(
                name = "fun-brainstorming",
                description = "Fast architectural brainstorming workflow.",
                relativePath = ".codex/skills/fun-brainstorming/SKILL.md",
                invocationControl = SkillInvocationControl.EXPLICIT_AND_IMPLICIT,
                userInvocable = true,
                executionContext = SkillExecutionContext.FORK,
              ),
            ),
            invalidSkillCount = 1,
            trace = SkillInventoryTrace(
              visible = listOf(
                VisibleSkillTrace(
                  name = "ui-ux-pro-max",
                  relativePath = ".codex/skills/ui-ux-pro-max/SKILL.md",
                  invocationControl = "explicit-only",
                  userInvocable = true,
                  executionContext = "inline",
                  descriptionPreview = "High-end UI review workflow.",
                ),
                VisibleSkillTrace(
                  name = "fun-brainstorming",
                  relativePath = ".codex/skills/fun-brainstorming/SKILL.md",
                  invocationControl = "explicit-and-implicit",
                  userInvocable = true,
                  executionContext = "fork",
                  descriptionPreview = "Fast architectural brainstorming workflow.",
                ),
              ),
              totalVisibleSkillCount = 2,
              implicitSkillCount = 1,
              invalidSkillCount = 1,
            ),
          ),
        ),
      ),
      clock = IncrementingClock(start = 2_650L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Use the right skill workflow before answering."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("2", result.metadata["contextVisibleSkillCount"])
    assertEquals("2", result.metadata["contextInjectedSkillCount"])
    assertEquals("0", result.metadata["contextOmittedSkillCount"])
    assertEquals("1", result.metadata["contextImplicitSkillCount"])
    assertEquals("1", result.metadata["contextInvalidSkillCount"])
    assertEquals(
      "ui-ux-pro-max@.codex/skills/ui-ux-pro-max/SKILL.md[explicit-only|true|inline];" +
        "fun-brainstorming@.codex/skills/fun-brainstorming/SKILL.md[explicit-and-implicit|true|fork]",
      result.metadata["contextVisibleSkillSummary"],
    )
    assertTrue(gateway.requests.single().prompt.contains("[Skill Inventory]"))
    assertTrue(gateway.requests.single().prompt.contains("name=ui-ux-pro-max"))
  }

  @Test
  fun runPromptTaskProjectsMemoryFlushTraceIntoResultMetadata() {
    val workspaceRoot = temporaryFolder.newFolder("agent-memory-flush-metadata")
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"final","answer":"Memory flush trace applied."}""",
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
        sessionContext = AgentRuntimeSessionContext(
          memoryFlushTrace = MemoryFlushTrace(
            outcome = MemoryFlushOutcome.WRITTEN,
            executionMode = "inline",
            omittedMessageCount = 4,
            omittedCharCount = 512,
            signature = "flush-signature-123",
            candidateCount = 3,
            writtenRecordCount = 2,
            writtenKinds = listOf("project_fact", "user_preference"),
            writtenRecordIds = listOf("mem-a", "mem-b"),
          ),
        ),
      ),
      clock = IncrementingClock(start = 2_675L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Continue from the flushed history."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("written", result.metadata["contextMemoryFlushOutcome"])
    assertEquals("inline", result.metadata["contextMemoryFlushExecutionMode"])
    assertEquals("4", result.metadata["contextMemoryFlushOmittedMessageCount"])
    assertEquals("512", result.metadata["contextMemoryFlushOmittedCharCount"])
    assertEquals("flush-signature-123", result.metadata["contextMemoryFlushSignature"])
    assertEquals("3", result.metadata["contextMemoryFlushCandidateCount"])
    assertEquals("2", result.metadata["contextMemoryFlushWrittenRecordCount"])
    assertEquals("project_fact,user_preference", result.metadata["contextMemoryFlushWrittenKinds"])
    assertEquals("mem-a,mem-b", result.metadata["contextMemoryFlushWrittenRecordIds"])
  }

  @Test
  fun runPromptTaskProjectsDurableCompactionIntoResultMetadata() {
    val workspaceRoot = temporaryFolder.newFolder("agent-durable-compaction-metadata")
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"final","answer":"Durable compaction trace applied."}""",
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
        sessionContext = AgentRuntimeSessionContext(
          durableCompaction = DurableCompactionContext(
            text = """
              Older session history has been durably compacted into summaries.
              [Compacted History]
              Compacted 6 older message(s) outside the active transcript window.
            """.trimIndent(),
            trace = DurableCompactionTrace(
              compactedThisRun = true,
              executionMode = "inline",
              sourceTranscriptMessageCount = 18,
              retainedTranscriptMessageCount = 12,
              latestCompactedMessageCount = 6,
              includedSummaryCount = 1,
              omittedSummaryCount = 0,
              totalCompactedMessageCount = 6,
              latestCompactedAtEpochMs = 4_200L,
            ),
          ),
        ),
      ),
      clock = IncrementingClock(start = 2_690L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Continue from the durable summaries."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("true", result.metadata["contextDurableCompactionCompactedThisRun"])
    assertEquals("inline", result.metadata["contextDurableCompactionExecutionMode"])
    assertEquals("18", result.metadata["contextDurableCompactionSourceTranscriptMessageCount"])
    assertEquals("12", result.metadata["contextDurableCompactionRetainedTranscriptMessageCount"])
    assertEquals("6", result.metadata["contextDurableCompactionLatestMessageCount"])
    assertEquals("1", result.metadata["contextDurableCompactionIncludedSummaryCount"])
    assertEquals("0", result.metadata["contextDurableCompactionOmittedSummaryCount"])
    assertEquals("6", result.metadata["contextDurableCompactionTotalCompactedMessageCount"])
    assertEquals("4200", result.metadata["contextDurableCompactionLatestAtEpochMs"])
    assertTrue(gateway.requests.single().prompt.contains("[Durable Compaction]"))
    assertTrue(gateway.requests.single().prompt.contains("Compacted 6 older message(s) outside the active transcript window."))
  }

  @Test
  fun runPromptTaskProjectsBootstrapMetadata() {
    val workspaceRoot = temporaryFolder.newFolder("agent-bootstrap-metadata")
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"final","answer":"Bootstrap context applied."}""",
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
        sessionContext = AgentRuntimeSessionContext(
          liveContextTrace = LiveContextTrace(
            mode = "full",
            soulEnabled = true,
            memoryRecallEnabled = true,
          ),
          bootstrapContext = BootstrapContext(
            mode = BootstrapMode.FULL,
            files = listOf(
              BootstrapSnippet(
                name = "AGENTS.md",
                relativePath = "AGENTS.md",
                content = "# Agents\nFollow the workspace instructions.",
                sourceCharCount = 44,
                truncated = false,
              ),
              BootstrapSnippet(
                name = "PROJECT.md",
                relativePath = "PROJECT.md",
                content = "# Project\nThis repo uses Gradle.",
                sourceCharCount = 80,
                truncated = true,
              ),
            ),
            trace = BootstrapTrace(
              mode = "full",
              visibleFileCount = 2,
              injectedFileCount = 2,
              omittedFileCount = 0,
              truncatedFileCount = 1,
              files = listOf(
                BootstrapFileTrace(
                  name = "AGENTS.md",
                  relativePath = "AGENTS.md",
                  sourceCharCount = 44,
                  injectedCharCount = 44,
                  truncated = false,
                ),
                BootstrapFileTrace(
                  name = "PROJECT.md",
                  relativePath = "PROJECT.md",
                  sourceCharCount = 80,
                  injectedCharCount = 31,
                  truncated = true,
                ),
              ),
            ),
          ),
        ),
      ),
      clock = IncrementingClock(start = 2_688L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Respect the workspace bootstrap files."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("full", result.metadata["contextLiveMode"])
    assertEquals("true", result.metadata["contextLiveSoulEnabled"])
    assertEquals("true", result.metadata["contextLiveMemoryRecallEnabled"])
    assertEquals("full", result.metadata["contextBootstrapMode"])
    assertEquals("2", result.metadata["contextBootstrapVisibleFileCount"])
    assertEquals("2", result.metadata["contextBootstrapInjectedFileCount"])
    assertEquals("0", result.metadata["contextBootstrapOmittedFileCount"])
    assertEquals("1", result.metadata["contextBootstrapTruncatedFileCount"])
    assertEquals(
      "AGENTS.md@AGENTS.md[44|44|false];PROJECT.md@PROJECT.md[80|31|true]",
      result.metadata["contextBootstrapFileSummary"],
    )
    val systemPrompt = requireNotNull(gateway.requests.single().systemPrompt)
    assertTrue(systemPrompt.contains("[Bootstrap AGENTS.md]"))
    assertTrue(systemPrompt.contains("Follow the workspace instructions."))
  }

  @Test
  fun runPromptTaskPromotesReadSkillIntoActiveCapsule() {
    val workspaceRoot = temporaryFolder.newFolder("agent-active-skill-workspace")
    val skillsRoot = temporaryFolder.newFolder("agent-active-skill-root")
    writeSkill(
      root = skillsRoot,
      relativeDirectory = "ui-ux-pro-max",
      frontMatter = """
        name: ui-ux-pro-max
        description: High-end UI review workflow.
        invocation-control: explicit-only
        user-invocable: true
        allowed-tools: [ read, write ]
      """.trimIndent(),
      body = """
        # UI UX Pro Max

        Audit the current interface first, then apply a concrete design system.
      """.trimIndent(),
    )
    val skillCatalog = SkillCatalogResolver().resolve(listOf(skillsRoot))
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"skill_read","arguments":{"name":"ui-ux-pro-max"}}""",
        """{"type":"final","answer":"Used the active skill capsule."}""",
      ),
    )
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
          skillsRoots = listOf(skillsRoot),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(
        sessionContext = AgentRuntimeSessionContext(
          skillInventory = skillCatalog.inventory,
          skillCatalog = skillCatalog,
        ),
      ),
      clock = IncrementingClock(start = 2_700L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Load the UI skill, then follow it."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("ui-ux-pro-max", result.metadata["contextActiveSkillName"])
    assertEquals("skill_read", result.metadata["contextActiveSkillActivationSource"])
    assertEquals("false", result.metadata["contextActiveSkillPinned"])
    assertEquals("true", result.metadata["contextActiveSkillToolRestrictionEnabled"])
    assertEquals("read,write", result.metadata["contextActiveSkillAllowedTools"])
    assertEquals(2, gateway.requests.size)
    assertTrue(gateway.requests[1].prompt.contains("[Active Skill]"))
    assertTrue(gateway.requests[1].prompt.contains("name=ui-ux-pro-max"))
    assertTrue(gateway.requests[1].prompt.contains("Audit the current interface first"))
    assertTrue(gateway.requests[1].prompt.contains("- Read:"))
    assertFalse(gateway.requests[1].prompt.contains("- Bash:"))
  }

  @Test
  fun runPromptTaskPromotesReadSkillAsPinnedOnlyWhenRequested() {
    val workspaceRoot = temporaryFolder.newFolder("agent-active-skill-pinned-workspace")
    val skillsRoot = temporaryFolder.newFolder("agent-active-skill-pinned-root")
    writeSkill(
      root = skillsRoot,
      relativeDirectory = "ui-ux-pro-max",
      frontMatter = """
        name: ui-ux-pro-max
        description: High-end UI review workflow.
        invocation-control: explicit-only
        user-invocable: true
        allowed-tools: [ read, write ]
      """.trimIndent(),
      body = "# UI UX Pro Max\n\nAudit the interface.",
    )
    val skillCatalog = SkillCatalogResolver().resolve(listOf(skillsRoot))
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"skill_read","arguments":{"name":"ui-ux-pro-max","pin":true}}""",
        """{"type":"final","answer":"Used pinned skill."}""",
      ),
    )
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
          skillsRoots = listOf(skillsRoot),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(
        sessionContext = AgentRuntimeSessionContext(
          skillInventory = skillCatalog.inventory,
          skillCatalog = skillCatalog,
        ),
      ),
      clock = IncrementingClock(start = 2_750L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Load the UI skill as pinned, then follow it."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("ui-ux-pro-max", result.metadata["contextActiveSkillName"])
    assertEquals("true", result.metadata["contextActiveSkillPinned"])
    assertTrue(gateway.requests[1].prompt.contains("[Active Skill]"))
    assertTrue(gateway.requests[1].prompt.contains("pinned=true"))
  }

  @Test
  fun runPromptTaskExecutesInlineSkillAsActiveCapsule() {
    val workspaceRoot = temporaryFolder.newFolder("agent-skill-execute-inline-workspace")
    val skillsRoot = temporaryFolder.newFolder("agent-skill-execute-inline-root")
    writeSkill(
      root = skillsRoot,
      relativeDirectory = "review-skill",
      frontMatter = """
        name: review-skill
        description: Review workflow.
        invocation-control: explicit-and-implicit
        execution-context: inline
        user-invocable: true
        allowed-tools: [ read ]
      """.trimIndent(),
      body = "# Review Skill\n\nRead first, then answer.",
    )
    val skillCatalog = SkillCatalogResolver().resolve(listOf(skillsRoot))
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"skill_execute","arguments":{"name":"review-skill"}}""",
        """{"type":"final","answer":"Used skill_execute."}""",
      ),
    )
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
          skillsRoots = listOf(skillsRoot),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(
        sessionContext = AgentRuntimeSessionContext(
          skillInventory = skillCatalog.inventory,
          skillCatalog = skillCatalog,
        ),
      ),
      clock = IncrementingClock(start = 2_760L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Execute the review skill."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("review-skill", result.metadata["contextActiveSkillName"])
    assertEquals("skill_execute", result.metadata["contextActiveSkillActivationSource"])
    assertEquals("false", result.metadata["contextActiveSkillPinned"])
    assertTrue(gateway.requests[1].prompt.contains("[Active Skill]"))
    assertTrue(gateway.requests[1].prompt.contains("name=review-skill"))
  }

  @Test
  fun runPromptTaskBlocksDisallowedToolWhenActiveSkillRestrictsTools() {
    val workspaceRoot = temporaryFolder.newFolder("agent-active-skill-policy-workspace")
    val skillsRoot = temporaryFolder.newFolder("agent-active-skill-policy-root")
    writeSkill(
      root = skillsRoot,
      relativeDirectory = "ui-ux-pro-max",
      frontMatter = """
        name: ui-ux-pro-max
        description: High-end UI review workflow.
        invocation-control: explicit-only
        user-invocable: true
        allowed-tools: [ read, write ]
      """.trimIndent(),
      body = """
        # UI UX Pro Max

        Stay within the read/write design workflow.
      """.trimIndent(),
    )
    val skillCatalog = SkillCatalogResolver().resolve(listOf(skillsRoot))
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"skill_read","arguments":{"name":"ui-ux-pro-max"}}""",
        """{"type":"tool_call","tool_name":"Bash","arguments":{"command":"git status"}}""",
        """{"type":"final","answer":"Stopped after the skill policy blocked Bash."}""",
      ),
    )
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
          skillsRoots = listOf(skillsRoot),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(
        sessionContext = AgentRuntimeSessionContext(
          skillInventory = skillCatalog.inventory,
          skillCatalog = skillCatalog,
        ),
      ),
      clock = IncrementingClock(start = 2_800L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Load the UI skill and then try Bash anyway."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("ui-ux-pro-max", result.metadata["contextActiveSkillName"])
    assertEquals(3, gateway.requests.size)
    assertTrue(gateway.requests[2].prompt.contains("SKILL_TOOL_POLICY_BLOCKED"))
    assertTrue(gateway.requests[2].prompt.contains("outside the active allowlist"))
  }

  @Test
  fun runPromptTaskExposesPruningMetadataWhenSeededConversationNeedsCleanup() {
    val workspaceRoot = temporaryFolder.newFolder("agent-pruning-metadata")
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"final","answer":"clean context applied"}""",
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
        sessionContext = AgentRuntimeSessionContext(
          conversation = listOf(
            RuntimeConversationMessage(RuntimeConversationRole.TOOL, "Protocol note."),
            RuntimeConversationMessage(RuntimeConversationRole.TOOL, "Protocol note."),
            RuntimeConversationMessage(
              RuntimeConversationRole.TOOL,
              "data:image/png;base64," + "A".repeat(160),
            ),
          ),
        ),
      ),
      clock = IncrementingClock(start = 2_750L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Continue from the cleaned transcript."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("0", result.metadata["contextPrunedMessageCount"])
    assertEquals("1", result.metadata["contextRewrittenMessageCount"])
    assertEquals("true", result.metadata["contextPruningSummaryIncluded"])
    assertTrue(gateway.requests.single().prompt.contains("[Pruning Summary]"))
    assertTrue(gateway.requests.single().prompt.contains("Attachment-like payload pruned by prompt guardrail."))
  }

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

  @Test
  fun runPromptTaskEmitsLifecycleAssistantAndToolEvents() {
    val workspaceRoot = temporaryFolder.newFolder("agent-event-stream")
    Files.write(
      workspaceRoot.toPath().resolve("README.md"),
      "event stream".toByteArray(StandardCharsets.UTF_8),
    )
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"Read","arguments":{"file_path":"README.md"}}""",
        """{"type":"final","answer":"done"}""",
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
      clock = IncrementingClock(start = 7_000L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Read README and finish."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals(
      listOf("lifecycle", "tool_call", "tool_result", "assistant", "lifecycle"),
      externalEventKinds(eventSink.events),
    )
    assertEquals(OpenCrayRunLifecyclePhase.START, (eventSink.events[0] as OpenCrayLifecycleEvent).phase)
    val visibleEvents = eventSink.events.filterNot(::isInternalCheckpointMarker)
    assertEquals("Read", (visibleEvents[1] as OpenCrayToolCallEvent).call.toolName)
    assertEquals(AgentToolResultStatus.SUCCESS, (visibleEvents[2] as OpenCrayToolResultEvent).result.status)
    assertEquals("done", (visibleEvents[3] as OpenCrayAssistantEvent).text)
    assertTrue((visibleEvents[3] as OpenCrayAssistantEvent).isFinal)
    assertEquals(OpenCrayRunLifecyclePhase.END, (visibleEvents[4] as OpenCrayLifecycleEvent).phase)
    assertEquals(4, internalCheckpointMarkers(eventSink.events).size)
    assertFalse(eventSink.events.any { event -> event.taskId.isBlank() || event.runId.isBlank() })
  }

  @Test
  fun runPromptTaskSupportsPublicProgressEventsBeforeToolAndFinal() {
    val workspaceRoot = temporaryFolder.newFolder("agent-progress-events")
    Files.write(
      workspaceRoot.toPath().resolve("README.md"),
      "progress-enabled".toByteArray(StandardCharsets.UTF_8),
    )
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"actions":[{"type":"progress","stage":"Planning","text":"Scanning README before reading it."},{"type":"tool_call","tool_name":"Read","arguments":{"file_path":"README.md"}}]}""",
        """{"actions":[{"type":"progress","stage":"Summarizing","text":"Read completed; preparing the final answer."},{"type":"final","answer":"README says progress-enabled."}]}""",
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
      clock = IncrementingClock(start = 7_100L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Read README and keep the user updated."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("README says progress-enabled.", result.stdout)
    assertEquals(
      listOf(
        "lifecycle",
        "assistant",
        "tool_call",
        "tool_result",
        "assistant",
        "assistant",
        "lifecycle",
      ),
      externalEventKinds(eventSink.events),
    )
    assertEquals(
      listOf(
        "Scanning README before reading it.",
        "Read completed; preparing the final answer.",
      ),
      eventSink.events
        .filterIsInstance<OpenCrayAssistantEvent>()
        .filterNot(OpenCrayAssistantEvent::isFinal)
        .map(OpenCrayAssistantEvent::text),
    )
    assertEquals(
      listOf("Planning", "Summarizing"),
      eventSink.events
        .filterIsInstance<OpenCrayAssistantEvent>()
        .filterNot(OpenCrayAssistantEvent::isFinal)
        .map(OpenCrayAssistantEvent::stage),
    )
    assertEquals(4, internalCheckpointMarkers(eventSink.events).size)
    assertTrue(gateway.requests[0].prompt.contains("short public status update"))
    assertTrue(gateway.requests[1].prompt.contains("Scanning README before reading it."))
  }

  @Test
  fun runPromptTaskCanAdvanceManagedProcessAcrossTurns() {
    val workspaceRoot = temporaryFolder.newFolder("agent-managed-process")
    val registry = ScriptedProcessRegistry()
    val gateway = DynamicGateway { index ->
      when (index) {
        0 -> """{"type":"tool_call","tool_name":"ProcessStart","arguments":{"command":"npm","args":["run","dev"],"working_directory":"."}}"""
        1 -> {
          val processId = registry.startedProcessId ?: error("ProcessStart should have run before ProcessWait.")
          """{"type":"tool_call","tool_name":"ProcessWait","arguments":{"process_id":"$processId","timeout_ms":250}}"""
        }

        2 -> """{"type":"final","answer":"Managed process is ready."}"""
        else -> error("Unexpected managed-process turn $index.")
      }
    }
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
          processRegistry = registry,
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(maxTurns = 5, maxToolCalls = 3),
      clock = IncrementingClock(start = 7_500L)::next,
    )

    val result = runtime.execute(
      task = promptTask(
        input = "Start the dev server and wait until it is ready.",
        metadata = mapOf("chatMode" to "DEVELOPER"),
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Managed process is ready.", result.stdout)
    assertEquals("3", result.metadata["turnCount"])
    assertEquals("2", result.metadata["toolCallCount"])
    assertEquals(listOf(250L), registry.waitTimeouts)
    assertTrue(gateway.requests[1].prompt.contains("ProcessStart"))
    assertTrue(gateway.requests[1].prompt.contains("process_id=${registry.startedProcessId}"))
    assertTrue(gateway.requests[1].prompt.contains("status=running"))
    assertTrue(gateway.requests[2].prompt.contains("ProcessWait"))
    assertTrue(gateway.requests[2].prompt.contains("server ready"))
    assertTrue(gateway.requests[2].prompt.contains("exit_code=0"))
  }

  @Test
  fun runPromptTaskCanStartManagedPythonScriptAcrossTurns() {
    val workspaceRoot = temporaryFolder.newFolder("agent-managed-python")
    Files.createDirectories(workspaceRoot.toPath().resolve("scripts"))
    Files.write(
      workspaceRoot.toPath().resolve("scripts").resolve("run.py"),
      "print('hello')".toByteArray(StandardCharsets.UTF_8),
    )
    val registry = ScriptedProcessRegistry()
    val gateway = DynamicGateway { index ->
      when (index) {
        0 -> """{"type":"tool_call","tool_name":"ProcessStart","arguments":{"script_path":"scripts/run.py","python_executable":"python3","args":["--flag"]}}"""
        1 -> {
          val processId = registry.startedProcessId ?: error("ProcessStart should have run before ProcessWait.")
          """{"type":"tool_call","tool_name":"ProcessWait","arguments":{"process_id":"$processId","timeout_ms":250}}"""
        }

        2 -> """{"type":"final","answer":"Managed python process finished."}"""
        else -> error("Unexpected managed-python turn $index.")
      }
    }
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
          processRegistry = registry,
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(maxTurns = 5, maxToolCalls = 3),
      clock = IncrementingClock(start = 7_750L)::next,
    )

    val result = runtime.execute(
      task = promptTask(
        input = "Run the Python script in the background and wait for it to finish.",
        metadata = mapOf("chatMode" to "DEVELOPER"),
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Managed python process finished.", result.stdout)
    assertTrue(gateway.requests[1].prompt.contains("python_exec"))
    assertTrue(gateway.requests[1].prompt.contains("scripts/run.py"))
    assertTrue(gateway.requests[1].prompt.contains("python3"))
  }

  @Test
  fun waitAgentEmitsSubagentProgressWhileJoiningForegroundExecution() {
    val workspaceRoot = temporaryFolder.newFolder("agent-wait-subagent-progress")
    val coordinator = InMemorySubAgentExecutionCoordinator()
    val handle = SubAgentHandleState(
      agentId = "agent-wait-1",
      childRunId = "child-run-1",
      childTaskId = "child-task-1",
      description = "Inspect README",
      prompt = "Read README.md and summarize it.",
      subagentType = "researcher",
      contextMode = "minimal",
      parentRunId = "parent-run-wait-1",
      parentTaskId = "parent-task-wait-1",
      parentTurn = 0,
      depth = 1,
      snapshot = SubAgentExecutionSnapshot.backgroundRunning(
        headline = "Delegated child run is running in the background.",
      ),
      createdAtEpochMs = 4_000L,
      updatedAtEpochMs = 4_000L,
    )
    val executor = Executors.newSingleThreadExecutor()
    val future = FutureTask<Unit> {
      Thread.sleep(600L)
      coordinator.upsertHandle(
        handle.withUpdatedChildPromptCheckpoint(
          checkpointState = OpenCrayPromptResumeState(
            turnIndex = 0,
            toolCallCount = 0,
          ),
          checkpointBoundary = OpenCrayPromptCheckpointBoundary.TOOL_RESULT_COMMITTED,
          emittedAtEpochMs = 4_100L,
        ),
      )
      Thread.sleep(600L)
      coordinator.finishExecution(
        handle
          .withClearedChildPromptCheckpoint(updatedAtEpochMs = 4_200L)
          .copy(
            snapshot = SubAgentExecutionSnapshot(
              state = SubAgentExecutionState.COMPLETED,
              continuationKind = SubAgentContinuationKind.NONE,
              resumable = false,
              requiresUserAction = false,
              isHighRisk = false,
              headline = "Delegated child run completed.",
            ),
            childExecutionStatus = ExecutionStatus.SUCCESS.name,
            updatedAtEpochMs = 4_200L,
          ),
      )
    }
    coordinator.beginExecution(
      handle = handle,
      execution = SubAgentActiveExecution(
        executor = executor,
        future = future,
        cancelRequested = AtomicBoolean(false),
        closed = AtomicBoolean(false),
      ),
    )
    executor.execute(future)

    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"wait_agent","arguments":{"agent_id":"agent-wait-1"}}""",
        """{"type":"final","answer":"Joined the delegated child run."}""",
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
        maxTurns = 4,
        maxToolCalls = 2,
        seededSubAgentHandles = listOf(handle),
        subAgentExecutionCoordinator = coordinator,
      ),
      eventSink = eventSink,
      clock = IncrementingClock(start = 7_900L)::next,
    )

    try {
      val result = runtime.execute(
        task = promptTask(input = "Wait for the delegated child run to finish."),
        hooks = runtimeHooks(),
      )

      assertEquals(ExecutionStatus.SUCCESS, result.status)
      assertEquals("Joined the delegated child run.", result.stdout)
      assertTrue(
        eventSink.events.joinToString(separator = "\n") { event ->
          when (event) {
            is OpenCraySubAgentEvent ->
              "subagent phase=${event.phase} state=${event.executionState} summary=${event.summary}"
            is OpenCrayToolCallEvent -> "tool_call ${event.call.toolName}"
            is OpenCrayToolResultEvent -> "tool_result ${event.call.toolName}:${event.result.status}"
            is OpenCrayAssistantEvent -> "assistant ${event.text}"
            is OpenCrayLifecycleEvent -> "lifecycle ${event.phase}"
            else -> event::class.java.simpleName
          }
        },
        eventSink.events
          .filterIsInstance<OpenCraySubAgentEvent>()
          .any { event ->
            event.childRunId == "child-run-1" &&
              event.summary.orEmpty().isNotBlank()
          },
      )
    } finally {
      executor.shutdownNow()
    }
  }

  @Test
  fun runPromptTaskRecoversFromProtocolErrorsWithoutSurfacingRawPayload() {
    val workspaceRoot = temporaryFolder.newFolder("agent-protocol-recovery")
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"unexpected":"shape"}""",
        """{"type":"final","answer":"clean answer after retry"}""",
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
      clock = IncrementingClock(start = 8_000L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Use tools when needed, then answer cleanly."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("clean answer after retry", result.stdout)
    assertEquals("2", result.metadata["turnCount"])
    assertEquals(
      listOf("clean answer after retry"),
      eventSink.events
        .filterIsInstance<OpenCrayAssistantEvent>()
        .map(OpenCrayAssistantEvent::text),
    )
    assertTrue(
      gateway.requests[1].prompt.contains(
        "Protocol error: use native tool calling for the next tool action, or return a plain final answer when you are done.",
      ),
    )
    assertTrue(gateway.requests[1].prompt.contains("""{"unexpected":"shape"}"""))
    assertFalse(result.stdout.contains("unexpected"))
  }

  @Test
  fun runPromptTaskExecutesSequentialToolCallsAndIgnoresMixedFinalContent() {
    val workspaceRoot = temporaryFolder.newFolder("agent-mixed-turn")
    Files.write(
      workspaceRoot.toPath().resolve("README.md"),
      "mixed turn".toByteArray(StandardCharsets.UTF_8),
    )
    Files.write(
      workspaceRoot.toPath().resolve("NOTES.md"),
      "second file".toByteArray(StandardCharsets.UTF_8),
    )
    val gateway = RecordingGateway(
      outputs = listOf(
        """
        {"type":"tool_call","tool_name":"Read","reason":"Need README contents first.","arguments":{"file_path":"README.md"}}
        {"type":"tool_call","tool_name":"Read","arguments":{"file_path":"NOTES.md"}}
        {"type":"final","answer":"premature answer that should be ignored"}
        """.trimIndent(),
        """{"type":"final","answer":"README says mixed turn and NOTES says second file"}""",
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
      clock = IncrementingClock(start = 8_250L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Read README and answer."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("README says mixed turn and NOTES says second file", result.stdout)
    assertTrue(gateway.requests[1].prompt.contains("mixed turn"))
    assertTrue(gateway.requests[1].prompt.contains("second file"))
    assertTrue(gateway.requests[1].prompt.contains("Protocol note: return only the next step on each turn."))
    assertFalse(gateway.requests[1].prompt.contains("legacy JSON fallback compatibility enabled"))
    assertEquals("2", result.metadata["toolCallCount"])
    assertEquals(
      listOf("README says mixed turn and NOTES says second file"),
      eventSink.events
        .filterIsInstance<OpenCrayAssistantEvent>()
        .map(OpenCrayAssistantEvent::text),
    )
    assertEquals(
      listOf("Read", "Read"),
      eventSink.events
        .filterIsInstance<OpenCrayToolCallEvent>()
        .map { event -> event.call.toolName },
    )
    assertEquals(
      "Need README contents first.",
      eventSink.events
        .filterIsInstance<OpenCrayToolCallEvent>()
        .first()
        .call
        .reason,
    )
    assertFalse(result.stdout.contains("premature answer"))
  }

  @Test
  fun runPromptTaskFailsAfterRepeatedProtocolErrorsWithoutAssistantLeak() {
    val workspaceRoot = temporaryFolder.newFolder("agent-protocol-failure")
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"unexpected":"first"}""",
        """not valid json at all""",
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
      config = OpenCrayAgentRuntimeConfig(maxTurns = 2, maxToolCalls = 2),
      eventSink = eventSink,
      clock = IncrementingClock(start = 8_500L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Return a valid JSON action."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.FAILED, result.status)
    assertEquals("MODEL_ACTION_FORMAT_ERROR", result.errorCode)
    assertEquals("protocol_error_exhausted", result.metadata["responseFormat"])
    assertEquals("2", result.metadata["protocolErrorCount"])
    assertTrue(eventSink.events.none { event -> event is OpenCrayAssistantEvent })
    assertTrue(result.stdout.isBlank())
    assertFalse(result.errorMessage.orEmpty().contains("unexpected"))
    assertFalse(result.metadata["lastProtocolError"].orEmpty().contains("unexpected"))
  }

  @Test
  fun runPromptTaskLlmFailurePreservesContextMetadata() {
    val workspaceRoot = temporaryFolder.newFolder("agent-llm-failure")
    val gateway = FailingGateway(
      status = LiteLlmGatewayStatus.FAILED,
      errorCode = "UPSTREAM_502",
      errorMessage = "Provider failure",
    )
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(
        sessionContext = AgentRuntimeSessionContext(
          conversation = listOf(
            RuntimeConversationMessage(RuntimeConversationRole.USER, "Earlier question."),
          ),
        ),
      ),
      clock = IncrementingClock(start = 4_000L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Need a fresh answer."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.FAILED, result.status)
    assertEquals("Provider failure", result.errorMessage)
    assertEquals("1", result.metadata["turnCount"])
    assertEquals("0", result.metadata["toolCallCount"])
    assertEquals("2", result.metadata["contextSourceMessageCount"])
    assertEquals("2", result.metadata["contextWindowMessageCount"])
    assertEquals("2", result.metadata["contextMessageCount"])
  }

  @Test
  fun runPromptTaskRecordsToolDiagnosticsWhenFinalReplyIsLostAfterSuccessfulTool() {
    val workspaceRoot = temporaryFolder.newFolder("agent-tool-diagnostics")
    Files.write(
      workspaceRoot.toPath().resolve("README.md"),
      "diagnostic preview".toByteArray(StandardCharsets.UTF_8),
    )
    val selection = LiteLlmRouteSelectionMetadata(
      profileId = "test-profile",
      routeId = "test-route",
      providerId = "fake",
      model = "fake-model",
      attemptIndex = 0,
    )
    var now = 9_000L
    var requestIndex = 0
    val gateway = object : LiteLlmGateway {
      override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
        val startedAt = now++
        val finishedAt = now++
        return if (requestIndex++ == 0) {
          LiteLlmGatewayResult(
            requestId = request.requestId,
            status = LiteLlmGatewayStatus.SUCCESS,
            completionMode = LiteLlmCompletionMode.PRIMARY,
            outputText = """{"type":"tool_call","tool_name":"Read","arguments":{"file_path":"README.md"}}""",
            selectedRoute = selection,
            attempts = listOf(
              LiteLlmAttemptRecord(
                route = selection,
                outcome = LiteLlmAttemptOutcome.SUCCESS,
                outputChars = 71,
                startedAtEpochMs = startedAt,
                finishedAtEpochMs = finishedAt,
              ),
            ),
            startedAtEpochMs = startedAt,
            finishedAtEpochMs = finishedAt,
          )
        } else {
          LiteLlmGatewayResult(
            requestId = request.requestId,
            status = LiteLlmGatewayStatus.FAILED,
            completionMode = LiteLlmCompletionMode.TERMINAL,
            selectedRoute = selection,
            attempts = listOf(
              LiteLlmAttemptRecord(
                route = selection,
                outcome = LiteLlmAttemptOutcome.FAILED,
                errorCode = "PROVIDER_EMPTY_RESPONSE",
                startedAtEpochMs = startedAt,
                finishedAtEpochMs = finishedAt,
              ),
            ),
            errorCode = "PROVIDER_EMPTY_RESPONSE",
            errorMessage = "Provider returned an empty completion payload.",
            startedAtEpochMs = startedAt,
            finishedAtEpochMs = finishedAt,
            metadata = mapOf(
              LiteLlmMetadataKeys.PROVIDER_RESPONSE_SHAPE to "openai_empty",
              LiteLlmMetadataKeys.NATIVE_TOOL_CALL_OBSERVED to "false",
            ),
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
      config = OpenCrayAgentRuntimeConfig(maxTurns = 4, maxToolCalls = 2),
      clock = IncrementingClock(start = 9_500L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Read the README and answer."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.FAILED, result.status)
    assertEquals("MAX_TURNS_EXCEEDED", result.errorCode)
    assertEquals("true", result.metadata[LiteLlmMetadataKeys.NATIVE_TOOL_CALL_REQUESTED])
    assertEquals("openai_empty", result.metadata[LiteLlmMetadataKeys.PROVIDER_RESPONSE_SHAPE])
    assertEquals("true", result.metadata[LiteLlmMetadataKeys.PARSED_TOOL_CALL_OBSERVED])
    assertEquals("true", result.metadata[LiteLlmMetadataKeys.FALLBACK_PARSER_ATTEMPTED])
    assertEquals("true", result.metadata[LiteLlmMetadataKeys.FALLBACK_PARSER_SUCCEEDED])
    assertEquals("true", result.metadata[LiteLlmMetadataKeys.TOOL_CALL_EVENT_EMITTED])
    assertEquals("true", result.metadata[LiteLlmMetadataKeys.TOOL_RESULT_EVENT_EMITTED])
    assertEquals("Read", result.metadata[LiteLlmMetadataKeys.LAST_SUCCESSFUL_TOOL_NAME])
    assertEquals("3", result.metadata["emptyResponseRecoveryCount"])
  }

  @Test
  fun runPromptTaskRetriesRecoverableLlmFailuresBeforeReturningFinalAnswer() {
    val workspaceRoot = temporaryFolder.newFolder("agent-llm-retry")
    val sleepDurations = mutableListOf<Long>()
    val gateway = ScriptedGateway(
      results = listOf(
        gatewayFailureResult(
          errorCode = "PROVIDER_TRANSPORT_ERROR",
          errorMessage = "Connection reset by peer.",
        ),
        gatewayFailureResult(
          errorCode = "HTTP_503",
          errorMessage = "Provider returned HTTP 503.",
        ),
        gatewaySuccessResult("""{"type":"final","answer":"answer after llm retry"}"""),
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
        maxTurns = 4,
        maxToolCalls = 2,
        maxRecoverableLlmRetries = 5,
        recoverableLlmRetryDelayMs = 10L,
        sleep = { durationMs -> sleepDurations += durationMs },
      ),
      clock = IncrementingClock(start = 10_000L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Answer after the transient LLM failures recover."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("answer after llm retry", result.stdout)
    assertEquals(3, gateway.requests.size)
    assertEquals(listOf(10L, 10L), sleepDurations)
    assertEquals("2", result.metadata["llmRetryCount"])
  }

  @Test
  fun runPromptTaskPausesSameRunWhenRecoverableLlmRetriesAreExhausted() {
    val workspaceRoot = temporaryFolder.newFolder("agent-llm-retry-paused")
    val suspendRequests = mutableListOf<SuspensionRequest>()
    val gateway = ScriptedGateway(
      results = listOf(
        gatewayFailureResult(
          errorCode = "HTTP_503",
          errorMessage = "Provider returned HTTP 503.",
        ),
        gatewayFailureResult(
          errorCode = "HTTP_503",
          errorMessage = "Provider returned HTTP 503 again.",
        ),
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
        maxTurns = 4,
        maxToolCalls = 2,
        maxRecoverableLlmRetries = 1,
        recoverableLlmRetryDelayMs = 10L,
        sleep = {},
      ),
      clock = IncrementingClock(start = 20_000L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Keep the same run paused if retries are exhausted."),
      hooks = runtimeHooks(onSuspend = suspendRequests::add),
    )

    assertEquals(ExecutionStatus.FAILED, result.status)
    assertEquals(ERROR_LLM_RETRY_EXHAUSTED_AWAITING_RESUME, result.errorCode)
    assertEquals(1, suspendRequests.size)
    assertEquals(
      ERROR_LLM_RETRY_EXHAUSTED_AWAITING_RESUME,
      suspendRequests.single().reasonCode,
    )
    assertEquals("true", result.metadata["llmRetryExhausted"])
    assertEquals("1", result.metadata["llmRetryCount"])
    val resumeState = requireNotNull(
      OpenCrayPromptResumeMetadata.decodeFromMetadata(
        metadata = result.metadata,
        json = Json { ignoreUnknownKeys = true },
      ),
    )
    assertEquals(0, resumeState.turnIndex)
    assertEquals(0, resumeState.toolCallCount)
    assertEquals(RuntimeConversationRole.USER, resumeState.transcript.lastOrNull()?.role)
    assertEquals(
      "Keep the same run paused if retries are exhausted.",
      resumeState.transcript.lastOrNull()?.content,
    )
  }

  @Test
  fun runPromptTaskDoesNotRetryTerminalProviderTimeoutStatuses() {
    val workspaceRoot = temporaryFolder.newFolder("agent-llm-terminal-timeout")
    val sleepDurations = mutableListOf<Long>()
    val selection = LiteLlmRouteSelectionMetadata(
      profileId = "test-profile",
      routeId = "test-route",
      providerId = "fake",
      model = "fake-model",
      attemptIndex = 0,
    )
    val gateway = ScriptedGateway(
      results = listOf(
        LiteLlmGatewayResult(
          requestId = "scripted-timeout-499",
          status = LiteLlmGatewayStatus.TIMEOUT,
          completionMode = LiteLlmCompletionMode.TERMINAL,
          selectedRoute = selection,
          attempts = listOf(
            LiteLlmAttemptRecord(
              route = selection,
              outcome = LiteLlmAttemptOutcome.TIMEOUT,
              startedAtEpochMs = 30_000L,
              finishedAtEpochMs = 30_001L,
              metadataKeys = listOf("statusCode"),
            ),
          ),
          errorMessage = "Upstream request timed out.",
          startedAtEpochMs = 30_000L,
          finishedAtEpochMs = 30_001L,
          metadata = mapOf("statusCode" to "499"),
        ),
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
        maxTurns = 4,
        maxToolCalls = 2,
        maxRecoverableLlmRetries = 5,
        recoverableLlmRetryDelayMs = 10L,
        sleep = { durationMs -> sleepDurations += durationMs },
      ),
      clock = IncrementingClock(start = 30_500L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Fail fast when the provider already timed out upstream."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.TIMEOUT, result.status)
    assertEquals("Upstream request timed out.", result.errorMessage)
    assertEquals("499", result.metadata["statusCode"])
    assertEquals(1, gateway.requests.size)
    assertTrue(sleepDurations.isEmpty())
    assertEquals("0", result.metadata["llmRetryCount"])
  }

  @Test
  fun runPromptTaskRecoversFromProviderEmptyResponse() {
    val workspaceRoot = temporaryFolder.newFolder("agent-empty-recovery")
    val gateway = ScriptedGateway(
      results = listOf(
        gatewayFailureResult(
          errorCode = "PROVIDER_EMPTY_RESPONSE",
          errorMessage = "Provider returned an empty completion payload.",
          completion = LiteLlmStructuredCompletion(
            reasoningText = "I should call Read next.",
          ),
        ),
        gatewaySuccessResult("""{"type":"final","answer":"recovered after empty response"}"""),
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
      clock = IncrementingClock(start = 11_000L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Recover if the model returns nothing."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("recovered after empty response", result.stdout)
    assertEquals("1", result.metadata["emptyResponseRecoveryCount"])
    assertTrue(gateway.requests[1].prompt.contains("Response error:"))
    assertTrue(gateway.requests[1].prompt.contains("Provider reasoning preview:"))
  }

  @Test
  fun runPromptTaskStopsRecoveringWhenProviderEmptyResponseBudgetIsExhausted() {
    val workspaceRoot = temporaryFolder.newFolder("agent-empty-recovery-exhausted")
    val gateway = ScriptedGateway(
      results = listOf(
        gatewayFailureResult(
          errorCode = "PROVIDER_EMPTY_RESPONSE",
          errorMessage = "Provider returned an empty completion payload.",
          completion = LiteLlmStructuredCompletion(
            reasoningText = "I should call Read next.",
          ),
        ),
        gatewayFailureResult(
          errorCode = "PROVIDER_EMPTY_RESPONSE",
          errorMessage = "Provider returned an empty completion payload again.",
          completion = LiteLlmStructuredCompletion(
            reasoningText = "I should call Read next.",
          ),
        ),
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
        maxTurns = 6,
        maxToolCalls = 2,
        maxRecoverableLlmRetries = 1,
      ),
      clock = IncrementingClock(start = 11_500L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Recover if the model returns nothing."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.FAILED, result.status)
    assertEquals(ERROR_EMPTY_RESPONSE_RECOVERY_EXHAUSTED, result.errorCode)
    assertEquals("1", result.metadata["emptyResponseRecoveryCount"])
    assertEquals("true", result.metadata["emptyResponseRecoveryExhausted"])
    assertEquals("1", result.metadata["emptyResponseRecoveryLimit"])
    assertEquals(2, gateway.requests.size)
  }

  @Test
  fun runPromptTaskRecoversFromMalformedStructuredToolCallWithDetailedDiagnostic() {
    val workspaceRoot = temporaryFolder.newFolder("agent-tool-parse-recovery")
    val gateway = ScriptedGateway(
      results = listOf(
        gatewaySuccessResult(
          outputText = "",
          completion = LiteLlmStructuredCompletion(
            toolCallErrors = listOf(
              "tool_calls[0].function.arguments must be a valid JSON object. Parser error: Unterminated array at character 33. Received: {\"todos\":[{\"content\":\"broken\"}",
            ),
            rawText = """[{"id":"call_1","type":"function","function":{"name":"TodoWrite","arguments":"{\"todos\":[{\"content\":\"broken\"}"}}]""",
          ),
        ),
        gatewaySuccessResult("""{"type":"final","answer":"recovered after malformed tool call"}"""),
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
      clock = IncrementingClock(start = 12_000L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Recover from malformed native tool calls."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("recovered after malformed tool call", result.stdout)
    assertTrue(gateway.requests[1].prompt.contains("tool_calls[0].function.arguments"))
    assertTrue(gateway.requests[1].prompt.contains("Parser error"))
  }

  @Test
  fun runPromptTaskExecutesStructuredToolCallWithoutTextProtocolPayload() {
    val workspaceRoot = temporaryFolder.newFolder("agent-structured-tool-call")
    Files.write(
      workspaceRoot.toPath().resolve("README.md"),
      "structured provider path".toByteArray(StandardCharsets.UTF_8),
    )
    val selection = LiteLlmRouteSelectionMetadata(
      profileId = "test-profile",
      routeId = "test-route",
      providerId = "anthropic",
      model = "claude-test",
      attemptIndex = 0,
    )
    var requestIndex = 0
    val requests = mutableListOf<LiteLlmGatewayRequest>()
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
                startedAtEpochMs = 10_000L,
                finishedAtEpochMs = 10_001L,
              ),
            ),
            startedAtEpochMs = 10_000L,
            finishedAtEpochMs = 10_001L,
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
              finalText = "README shows structured provider path.",
              rawText = "README shows structured provider path.",
            ),
            selectedRoute = selection,
            attempts = listOf(
              LiteLlmAttemptRecord(
                route = selection,
                outcome = LiteLlmAttemptOutcome.SUCCESS,
                outputChars = 31,
                startedAtEpochMs = 10_002L,
                finishedAtEpochMs = 10_003L,
              ),
            ),
            startedAtEpochMs = 10_002L,
            finishedAtEpochMs = 10_003L,
            metadata = mapOf(
              LiteLlmMetadataKeys.PROVIDER_RESPONSE_SHAPE to "anthropic_text",
              LiteLlmMetadataKeys.NATIVE_TOOL_CALL_OBSERVED to "false",
            ),
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
      config = OpenCrayAgentRuntimeConfig(maxTurns = 4, maxToolCalls = 2),
      clock = IncrementingClock(start = 10_500L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Read the README and answer."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("README shows structured provider path.", result.stdout)
    assertEquals("native_text_final", result.metadata["responseFormat"])
    assertEquals("true", result.metadata[LiteLlmMetadataKeys.NATIVE_TOOL_CALL_REQUESTED])
    assertEquals("false", result.metadata[LiteLlmMetadataKeys.FALLBACK_PARSER_ATTEMPTED])
    assertFalse(requests[0].prompt.contains("legacy JSON fallback compatibility enabled"))
    assertTrue(requests[0].tools.any { tool -> tool.name == "Read" })
    assertTrue(requests[0].messages.any { message -> message.content?.contains("[Tool Protocol]") == true })
    assertTrue(requests[0].messages.any { message -> message.content == "Read the README and answer." })
    val secondRequestMessages = requests[1].messages
    assertTrue(
      secondRequestMessages.any { message ->
        message.role == LiteLlmGatewayMessageRole.ASSISTANT &&
          message.toolCalls.singleOrNull()?.toolName == "Read" &&
          message.toolCalls.singleOrNull()?.id == "toolu_1"
      },
    )
    assertTrue(
      secondRequestMessages.any { message ->
        message.role == LiteLlmGatewayMessageRole.TOOL &&
          message.toolResult?.toolCallId == "toolu_1" &&
          message.toolResult?.toolName == "Read" &&
          message.toolResult?.content?.contains("structured provider path") == true &&
          message.toolResult?.content?.contains("\"tool_name\"") == false
      },
    )
    assertEquals("Read", result.metadata[LiteLlmMetadataKeys.LAST_SUCCESSFUL_TOOL_NAME])
  }

  @Test
  fun runPromptTaskCarriesRichToolResultPayloadIntoNextGatewayTurn() {
    val workspaceRoot = temporaryFolder.newFolder("agent-rich-tool-result")
    val registry = ScriptedProcessRegistry()
    val selection = LiteLlmRouteSelectionMetadata(
      profileId = "test-profile",
      routeId = "test-route",
      providerId = "openai-compatible",
      model = "gpt-4o-mini",
      attemptIndex = 0,
    )
    var requestIndex = 0
    val requests = mutableListOf<LiteLlmGatewayRequest>()
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
                  id = "call_1",
                  toolName = "ProcessStart",
                  arguments = JsonObject(
                    mapOf("command" to JsonPrimitive("server")),
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
                startedAtEpochMs = 13_000L,
                finishedAtEpochMs = 13_001L,
              ),
            ),
            startedAtEpochMs = 13_000L,
            finishedAtEpochMs = 13_001L,
          )
        } else if (requestIndex == 2) {
          val processId = registry.startedProcessId ?: error("ProcessStart should have run before ProcessWait.")
          LiteLlmGatewayResult(
            requestId = request.requestId,
            status = LiteLlmGatewayStatus.SUCCESS,
            completionMode = LiteLlmCompletionMode.PRIMARY,
            completion = LiteLlmStructuredCompletion(
              toolCalls = listOf(
                LiteLlmStructuredToolCall(
                  id = "call_2",
                  toolName = "ProcessWait",
                  arguments = JsonObject(
                    mapOf(
                      "process_id" to JsonPrimitive(processId),
                      "timeout_ms" to JsonPrimitive("250"),
                    ),
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
                startedAtEpochMs = 13_002L,
                finishedAtEpochMs = 13_003L,
              ),
            ),
            startedAtEpochMs = 13_002L,
            finishedAtEpochMs = 13_003L,
          )
        } else {
          LiteLlmGatewayResult(
            requestId = request.requestId,
            status = LiteLlmGatewayStatus.SUCCESS,
            completionMode = LiteLlmCompletionMode.PRIMARY,
            completion = LiteLlmStructuredCompletion(
              finalText = "Shell command completed.",
            ),
            selectedRoute = selection,
            attempts = listOf(
              LiteLlmAttemptRecord(
                route = selection,
                outcome = LiteLlmAttemptOutcome.SUCCESS,
                outputChars = 0,
                startedAtEpochMs = 13_004L,
                finishedAtEpochMs = 13_005L,
              ),
            ),
            startedAtEpochMs = 13_004L,
            finishedAtEpochMs = 13_005L,
          )
        }
      }
    }
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
          processRegistry = registry,
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(maxTurns = 5, maxToolCalls = 3),
      clock = IncrementingClock(start = 13_500L)::next,
    )

    val result = runtime.execute(
      task = promptTask(
        input = "Start the server, wait for it, and confirm it worked.",
        metadata = mapOf("chatMode" to "DEVELOPER"),
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Shell command completed.", result.stdout)
    val secondRequestToolResult = requests[2].messages.mapNotNull { message ->
      message.toolResult
    }.lastOrNull()
    assertNotNull(secondRequestToolResult)
    assertEquals("call_2", secondRequestToolResult?.toolCallId)
    assertEquals("ProcessWait", secondRequestToolResult?.toolName)
    assertEquals(0, secondRequestToolResult?.exitCode)
    assertEquals("server ready", secondRequestToolResult?.stdout?.trim())
    assertTrue(secondRequestToolResult?.content?.contains("exit_code=0") == true)
  }

  @Test
  fun runPromptTaskMarksNativeToolSchemasStrictWhenMetadataRequestsIt() {
    val workspaceRoot = temporaryFolder.newFolder("agent-strict-tool-schema")
    val selection = LiteLlmRouteSelectionMetadata(
      profileId = "test-profile",
      routeId = "test-route",
      providerId = "openai-compatible",
      model = "gpt-4o-mini",
      attemptIndex = 0,
    )
    val requests = mutableListOf<LiteLlmGatewayRequest>()
    val gateway = object : LiteLlmGateway {
      override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
        requests += request
        return LiteLlmGatewayResult(
          requestId = request.requestId,
          status = LiteLlmGatewayStatus.SUCCESS,
          completionMode = LiteLlmCompletionMode.PRIMARY,
          completion = LiteLlmStructuredCompletion(
            finalText = "All set.",
          ),
          selectedRoute = selection,
          attempts = listOf(
            LiteLlmAttemptRecord(
              route = selection,
              outcome = LiteLlmAttemptOutcome.SUCCESS,
              outputChars = 0,
              startedAtEpochMs = 14_000L,
              finishedAtEpochMs = 14_001L,
            ),
          ),
          startedAtEpochMs = 14_000L,
          finishedAtEpochMs = 14_001L,
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
        maxTurns = 1,
        llmMetadata = mapOf(
          "nativeToolCallingAvailable" to "true",
          "toolSchemaStrict" to "true",
        ),
      ),
      clock = IncrementingClock(start = 14_500L)::next,
    )

    val result = runtime.execute(
      task = promptTask(
        input = "Answer without using tools.",
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertTrue(requests.single().tools.isNotEmpty())
    assertTrue(requests.single().tools.all { tool -> tool.strict == true })
  }

  @Test
  fun runPromptTaskEmitsStructuredProgressFromNativeToolCallResponse() {
    val workspaceRoot = temporaryFolder.newFolder("agent-structured-progress")
    Files.write(
      workspaceRoot.toPath().resolve("README.md"),
      "structured progress".toByteArray(StandardCharsets.UTF_8),
    )
    val selection = LiteLlmRouteSelectionMetadata(
      profileId = "test-profile",
      routeId = "test-route",
      providerId = "openai-compatible",
      model = "gpt-4o-mini",
      attemptIndex = 0,
    )
    var requestIndex = 0
    val eventSink = RecordingEventSink()
    val gateway = object : LiteLlmGateway {
      override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
        return if (requestIndex++ == 0) {
          LiteLlmGatewayResult(
            requestId = request.requestId,
            status = LiteLlmGatewayStatus.SUCCESS,
            completionMode = LiteLlmCompletionMode.PRIMARY,
            completion = LiteLlmStructuredCompletion(
              commentaryText = "Scanning the README before reading it.",
              toolCalls = listOf(
                LiteLlmStructuredToolCall(
                  id = "call_1",
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
                startedAtEpochMs = 11_000L,
                finishedAtEpochMs = 11_001L,
              ),
            ),
            startedAtEpochMs = 11_000L,
            finishedAtEpochMs = 11_001L,
          )
        } else {
          LiteLlmGatewayResult(
            requestId = request.requestId,
            status = LiteLlmGatewayStatus.SUCCESS,
            completionMode = LiteLlmCompletionMode.PRIMARY,
            completion = LiteLlmStructuredCompletion(
              finalText = "README says structured progress.",
            ),
            selectedRoute = selection,
            attempts = listOf(
              LiteLlmAttemptRecord(
                route = selection,
                outcome = LiteLlmAttemptOutcome.SUCCESS,
                outputChars = 0,
                startedAtEpochMs = 11_002L,
                finishedAtEpochMs = 11_003L,
              ),
            ),
            startedAtEpochMs = 11_002L,
            finishedAtEpochMs = 11_003L,
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
      config = OpenCrayAgentRuntimeConfig(maxTurns = 4, maxToolCalls = 2),
      eventSink = eventSink,
      clock = IncrementingClock(start = 11_500L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Read the README and keep me updated."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("README says structured progress.", result.stdout)
    assertEquals(
      listOf("Scanning the README before reading it."),
      eventSink.events
        .filterIsInstance<OpenCrayAssistantEvent>()
        .filterNot(OpenCrayAssistantEvent::isFinal)
        .map(OpenCrayAssistantEvent::text),
    )
    assertTrue(eventSink.assistantDrafts.isEmpty())
    assertEquals("false", result.metadata[LiteLlmMetadataKeys.FALLBACK_PARSER_ATTEMPTED])
  }

  @Test
  fun runPromptTaskEmitsSeparateStructuredCommentaryEventsFromNativeCompletion() {
    val workspaceRoot = temporaryFolder.newFolder("agent-structured-multi-commentary")
    Files.write(
      workspaceRoot.toPath().resolve("README.md"),
      "multi commentary".toByteArray(StandardCharsets.UTF_8),
    )
    val selection = LiteLlmRouteSelectionMetadata(
      profileId = "test-profile",
      routeId = "multi-commentary-route",
      providerId = "openai-compatible",
      model = "gpt-4o-mini",
      attemptIndex = 0,
    )
    var requestIndex = 0
    val eventSink = RecordingEventSink()
    val gateway = object : LiteLlmGateway {
      override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
        return if (requestIndex++ == 0) {
          LiteLlmGatewayResult(
            requestId = request.requestId,
            status = LiteLlmGatewayStatus.SUCCESS,
            completionMode = LiteLlmCompletionMode.PRIMARY,
            completion = LiteLlmStructuredCompletion(
              commentaryText = "Scanning the README before reading it.\nOpening the relevant section now.",
              commentaryTexts = listOf(
                "Scanning the README before reading it.",
                "Opening the relevant section now.",
              ),
              toolCalls = listOf(
                LiteLlmStructuredToolCall(
                  id = "call_1",
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
                startedAtEpochMs = 11_100L,
                finishedAtEpochMs = 11_101L,
              ),
            ),
            startedAtEpochMs = 11_100L,
            finishedAtEpochMs = 11_101L,
          )
        } else {
          LiteLlmGatewayResult(
            requestId = request.requestId,
            status = LiteLlmGatewayStatus.SUCCESS,
            completionMode = LiteLlmCompletionMode.PRIMARY,
            completion = LiteLlmStructuredCompletion(
              finalText = "README says multi commentary works.",
            ),
            selectedRoute = selection,
            attempts = listOf(
              LiteLlmAttemptRecord(
                route = selection,
                outcome = LiteLlmAttemptOutcome.SUCCESS,
                outputChars = 0,
                startedAtEpochMs = 11_102L,
                finishedAtEpochMs = 11_103L,
              ),
            ),
            startedAtEpochMs = 11_102L,
            finishedAtEpochMs = 11_103L,
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
      config = OpenCrayAgentRuntimeConfig(maxTurns = 4, maxToolCalls = 2),
      eventSink = eventSink,
      clock = IncrementingClock(start = 11_600L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Read the README and keep me updated twice."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("README says multi commentary works.", result.stdout)
    assertEquals(
      listOf(
        "Scanning the README before reading it.",
        "Opening the relevant section now.",
      ),
      eventSink.events
        .filterIsInstance<OpenCrayAssistantEvent>()
        .filterNot(OpenCrayAssistantEvent::isFinal)
        .map(OpenCrayAssistantEvent::text),
    )
    assertTrue(eventSink.assistantDrafts.isEmpty())
  }

  @Test
  fun runPromptTaskFinalizesWhenStructuredCompletionCarriesCommentaryAndFinalTextTogether() {
    val selection = LiteLlmRouteSelectionMetadata(
      profileId = "test-profile",
      routeId = "responses-route",
      providerId = "openai",
      model = "gpt-5-mini",
      attemptIndex = 0,
    )
    val eventSink = RecordingEventSink()
    var requestCount = 0
    val runtime = OpenCrayAgentRuntime(
      gateway = object : LiteLlmGateway {
        override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
          requestCount += 1
          return LiteLlmGatewayResult(
            requestId = request.requestId,
            status = LiteLlmGatewayStatus.SUCCESS,
            completionMode = LiteLlmCompletionMode.PRIMARY,
            completion = LiteLlmStructuredCompletion(
              commentaryText = "Checking the transcript first.",
              finalText = "Here is the final answer.",
            ),
            selectedRoute = selection,
            attempts = listOf(
              LiteLlmAttemptRecord(
                route = selection,
                outcome = LiteLlmAttemptOutcome.SUCCESS,
                outputChars = 25,
                startedAtEpochMs = 12_500L,
                finishedAtEpochMs = 12_501L,
              ),
            ),
            startedAtEpochMs = 12_500L,
            finishedAtEpochMs = 12_501L,
          )
        }
      },
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(temporaryFolder.newFolder("agent-commentary-final-same-turn").toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(maxTurns = 4, maxToolCalls = 2),
      eventSink = eventSink,
      clock = IncrementingClock(start = 12_900L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Answer directly."),
      hooks = runtimeHooks(),
    )

    assertEquals(1, requestCount)
    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Here is the final answer.", result.stdout)
    assertEquals("native_text_final", result.metadata["responseFormat"])
    assertEquals(
      listOf("Checking the transcript first.", "Here is the final answer."),
      eventSink.events
        .filterIsInstance<OpenCrayAssistantEvent>()
        .map(OpenCrayAssistantEvent::text),
    )
    assertEquals(
      listOf(false, true),
      eventSink.events
        .filterIsInstance<OpenCrayAssistantEvent>()
        .map(OpenCrayAssistantEvent::isFinal),
    )
  }

  @Test
  fun runPromptTaskTracksProviderReasoningMetadataAcrossStructuredTurns() {
    val selection = LiteLlmRouteSelectionMetadata(
      profileId = "test-profile",
      routeId = "test-route",
      providerId = "openai-compatible",
      model = "gpt-4o-mini",
      attemptIndex = 0,
    )
    val runtime = OpenCrayAgentRuntime(
      gateway = object : LiteLlmGateway {
        override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult = LiteLlmGatewayResult(
          requestId = request.requestId,
          status = LiteLlmGatewayStatus.SUCCESS,
          completionMode = LiteLlmCompletionMode.PRIMARY,
          completion = LiteLlmStructuredCompletion(
            finalText = "Done.",
            reasoningText = "Need no more tools.",
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
        )
      },
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(temporaryFolder.newFolder("agent-structured-reasoning").toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(maxTurns = 2, maxToolCalls = 1),
      clock = IncrementingClock(start = 12_500L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Reply directly."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Done.", result.stdout)
    assertEquals("true", result.metadata[LiteLlmMetadataKeys.PROVIDER_REASONING_OBSERVED])
    assertEquals("1", result.metadata[LiteLlmMetadataKeys.PROVIDER_REASONING_TURN_COUNT])
    assertEquals("19", result.metadata[LiteLlmMetadataKeys.PROVIDER_REASONING_CHARS])
  }

  @Test
  fun runPromptTaskExposesContextBudgetDiagnosticsInGatewayAndResultMetadata() {
    val workspaceRoot = temporaryFolder.newFolder("agent-budget-metadata")
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"final","answer":"budget metadata captured"}""",
      ),
    )
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
          allowedToolNames = emptySet(),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(
        systemPrompt = "Budget diagnostics must stay visible to the runtime host. ".repeat(80).trim(),
        llmMetadata = mapOf(
          "context_window_tokens" to "900",
          "reserved_output_tokens" to "256",
          "prompt_safety_margin_tokens" to "96",
          "effective_input_percent" to "0.15",
        ),
        sessionContext = AgentRuntimeSessionContext(
          conversation = listOf(
            RuntimeConversationMessage(RuntimeConversationRole.USER, "Earlier question."),
            RuntimeConversationMessage(RuntimeConversationRole.ASSISTANT, "Earlier answer."),
          ),
          recalledMemory = MemoryRecallResult(
            memories = listOf(
              RetrievedMemory(
                id = "memory-budget-runtime",
                kind = MemoryKind.USER_PREFERENCE,
                scope = MemoryScope.USER,
                status = MemoryStatus.ACTIVE,
                content = "Keep budget diagnostics visible to the runtime host.",
                lastConfirmedAtEpochMs = 10L,
                score = 420,
              ),
            ),
            matchedRecordCount = 1,
          ),
          durableCompaction = DurableCompactionContext(
            text = "Older compacted history for budget diagnostics.",
          ),
        ),
      ),
      clock = IncrementingClock(start = 6_500L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Need budget diagnostics."),
      hooks = runtimeHooks(),
    )

    val requestMetadata = gateway.requests.single().metadata

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("budget metadata captured", result.stdout)
    assertEquals("true", requestMetadata["contextBudgetApplied"])
    assertEquals("EMERGENCY", requestMetadata["contextBudgetPressureMode"])
    assertEquals("900", requestMetadata["contextBudgetContextWindowTokens"])
    assertEquals("256", requestMetadata["contextBudgetReservedOutputTokens"])
    assertEquals("96", requestMetadata["contextBudgetSafetyMarginTokens"])
    assertEquals("balanced", requestMetadata["contextBudgetSelectedPreset"])
    assertEquals("dev", requestMetadata["contextBudgetEffectivePreset"])
    assertEquals("raw", requestMetadata["contextBudgetPresetSource"])
    assertEquals("true", requestMetadata["contextBudgetPresetDiverged"])
    assertEquals("balanced", requestMetadata["contextBudgetSourcePreset"])
    assertEquals("12", requestMetadata["contextBudgetSourceTranscriptMaxMessages"])
    assertEquals("4", requestMetadata["contextBudgetSourceInjectedMemoryMaxRecords"])
    assertEquals("6", requestMetadata["contextBudgetSourceMemoryRecallMaxRecords"])
    assertEquals("3200", requestMetadata["contextBudgetSourceBootstrapMaxChars"])
    assertEquals("8", requestMetadata["contextBudgetSourceSkillInventoryMaxSkills"])
    assertEquals("3200", requestMetadata["contextBudgetSourceActiveSkillMaxChars"])
    assertEquals("4", requestMetadata["contextBudgetSourceRecentObservationMaxEntries"])
    assertEquals("8", requestMetadata["contextBudgetSourceMemoryFlushMaxToolObservations"])
    assertEquals("548", requestMetadata["contextBudgetHardInputTokens"])
    assertEquals("512", requestMetadata["contextBudgetTargetInputTokens"])
    assertEquals("548", requestMetadata["contextBudgetEmergencyInputTokens"])
    assertEquals("true", requestMetadata["contextBudgetUnresolvedOverflow"])
    assertTrue(requestMetadata["contextBudgetFullLayerCount"].orEmpty().isNotBlank())
    assertTrue(requestMetadata["contextBudgetCompactLayerCount"].orEmpty().isNotBlank())
    assertTrue(requestMetadata["contextBudgetMinimalLayerCount"].orEmpty().isNotBlank())
    assertEquals("minimal", requestMetadata["contextToolProtocolDetailMode"])
    assertEquals("true", requestMetadata["contextToolProtocolReducedForBudget"])
    assertEquals("0", requestMetadata["contextToolProtocolAttachmentExampleCount"])
    assertTrue(requestMetadata["contextBudgetLayerSummary"].orEmpty().contains("RETRIEVED_MEMORY:"))
    assertTrue(requestMetadata["contextBudgetLayerSummary"].orEmpty().contains("CONVERSATION:"))
    assertFalse(requestMetadata["contextBudgetLayerSummary"].orEmpty().contains(":reduced"))
    assertFalse(requestMetadata["contextBudgetLayerSummary"].orEmpty().contains(":kept"))
    val layerDetails = Json.parseToJsonElement(
      requestMetadata["contextBudgetLayerDetails"].orEmpty(),
    ).jsonArray
    assertTrue(layerDetails.isNotEmpty())
    val conversationLayer = layerDetails.firstOrNull { layer ->
      (layer.jsonObject["id"] as? JsonPrimitive)?.content == "CONVERSATION"
    }?.jsonObject
    assertNotNull(conversationLayer)
    assertTrue(
      setOf("full", "compact", "minimal", "omitted").contains(
        (conversationLayer?.get("finalState") as? JsonPrimitive)?.content,
      ),
    )
    assertEquals(requestMetadata["contextBudgetPressureMode"], result.metadata["contextBudgetPressureMode"])
    assertEquals(requestMetadata["contextBudgetHardInputTokens"], result.metadata["contextBudgetHardInputTokens"])
    assertEquals(requestMetadata["contextBudgetTargetInputTokens"], result.metadata["contextBudgetTargetInputTokens"])
    assertEquals(requestMetadata["contextBudgetUnresolvedOverflow"], result.metadata["contextBudgetUnresolvedOverflow"])
    assertEquals(
      requestMetadata["contextBudgetEffectivePreset"],
      result.metadata["contextBudgetEffectivePreset"],
    )
    assertEquals(requestMetadata["contextBudgetPresetSource"], result.metadata["contextBudgetPresetSource"])
    assertEquals(requestMetadata["contextBudgetSourcePreset"], result.metadata["contextBudgetSourcePreset"])
    assertEquals(
      requestMetadata["contextBudgetSourceTranscriptMaxMessages"],
      result.metadata["contextBudgetSourceTranscriptMaxMessages"],
    )
    assertEquals(
      requestMetadata["contextBudgetSourceInjectedMemoryMaxRecords"],
      result.metadata["contextBudgetSourceInjectedMemoryMaxRecords"],
    )
    assertEquals(
      requestMetadata["contextBudgetSourceMemoryRecallMaxRecords"],
      result.metadata["contextBudgetSourceMemoryRecallMaxRecords"],
    )
    assertEquals(
      requestMetadata["contextBudgetSourceBootstrapMaxChars"],
      result.metadata["contextBudgetSourceBootstrapMaxChars"],
    )
    assertEquals(
      requestMetadata["contextBudgetSourceSkillInventoryMaxSkills"],
      result.metadata["contextBudgetSourceSkillInventoryMaxSkills"],
    )
    assertEquals(
      requestMetadata["contextBudgetSourceActiveSkillMaxChars"],
      result.metadata["contextBudgetSourceActiveSkillMaxChars"],
    )
    assertEquals(
      requestMetadata["contextBudgetSourceRecentObservationMaxEntries"],
      result.metadata["contextBudgetSourceRecentObservationMaxEntries"],
    )
    assertEquals(
      requestMetadata["contextBudgetSourceMemoryFlushMaxToolObservations"],
      result.metadata["contextBudgetSourceMemoryFlushMaxToolObservations"],
    )
    assertEquals(requestMetadata["contextBudgetFullLayerCount"], result.metadata["contextBudgetFullLayerCount"])
    assertEquals(
      requestMetadata["contextBudgetCompactLayerCount"],
      result.metadata["contextBudgetCompactLayerCount"],
    )
    assertEquals(
      requestMetadata["contextBudgetMinimalLayerCount"],
      result.metadata["contextBudgetMinimalLayerCount"],
    )
    assertEquals(requestMetadata["contextBudgetLayerSummary"], result.metadata["contextBudgetLayerSummary"])
    assertEquals(requestMetadata["contextToolProtocolDetailMode"], result.metadata["contextToolProtocolDetailMode"])
    assertEquals(requestMetadata["contextToolProtocolReducedForBudget"], result.metadata["contextToolProtocolReducedForBudget"])
    assertEquals(requestMetadata["contextToolProtocolAttachmentExampleCount"], result.metadata["contextToolProtocolAttachmentExampleCount"])
    assertTrue(
      result.metadata["contextBudgetOmittedLayerNames"].orEmpty().contains("Retrieved Memory") ||
        result.metadata["contextBudgetReducedLayerNames"].orEmpty().contains("Conversation"),
    )
  }

  @Test
  fun runPromptTaskReportsWorkingStateBudgetReductionInGatewayAndResultMetadata() {
    val workspaceRoot = temporaryFolder.newFolder("agent-working-state-budget-metadata")
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"final","answer":"working state budget metadata captured"}""",
      ),
    )
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
          allowedToolNames = emptySet(),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(
        systemPrompt = "Working state budget diagnostics must remain observable. ".repeat(80).trim(),
        llmMetadata = mapOf(
          "context_window_tokens" to "900",
          "reserved_output_tokens" to "256",
          "prompt_safety_margin_tokens" to "96",
          "effective_input_percent" to "0.15",
        ),
        sessionContext = AgentRuntimeSessionContext(
          workingState = WorkingState(
            objective = WorkingStateObjective(
              taskId = "task-working-state-budget-runtime",
              runId = "run-working-state-budget-runtime",
              primaryGoal = "Keep the latest operational state visible when prompt pressure spikes.",
              currentSubgoal = "Prove runtime metadata exposes the working-state reducer decision.",
              status = "in_progress",
            ),
            findings = (1..6).map { index ->
              WorkingStateEntry(
                text = "Finding $index " + "evidence ".repeat(12).trim(),
                sourceType = "code_inspection",
              )
            },
            recentActions = (1..8).map { index ->
              WorkingStateEntry(
                text = "Recent action $index " + "workspace mutation ".repeat(10).trim(),
                sourceType = "workspace_mutation",
              )
            },
            decisions = (1..4).map { index ->
              WorkingStateEntry(
                text = "Decision $index " + "branch rationale ".repeat(10).trim(),
                sourceType = "branch_control",
              )
            },
            blockers = (1..3).map { index ->
              WorkingStateEntry(
                text = "Blocker $index " + "approval wait ".repeat(10).trim(),
                sourceType = "approval_boundary",
              )
            },
            nextActions = (1..4).map { index ->
              WorkingStateEntry(
                text = "Next action $index " + "verify focused tests ".repeat(10).trim(),
                sourceType = "todo_snapshot",
              )
            },
            updatedAtEpochMs = 456_789L,
          ),
        ),
      ),
      clock = IncrementingClock(start = 6_800L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Need working state budget diagnostics."),
      hooks = runtimeHooks(),
    )

    val request = gateway.requests.single()
    val requestMetadata = request.metadata
    val layerSummary = requestMetadata["contextBudgetLayerSummary"].orEmpty()

    val messageText = gatewayStructuredPayloadText(request)

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("working state budget metadata captured", result.stdout)
    assertTrue(request.messages.isNotEmpty())
    assertEquals("messages_primary", request.metadata["gatewayTransportMode"])
    assertEquals("fallback_debug_only", request.metadata["gatewayPromptFieldRole"])
    assertTrue(messageText.contains("[Working State]"))
    assertTrue(messageText.contains("Recent action 8"))
    assertTrue(messageText.contains("Decision 4"))
    assertTrue(messageText.contains("Blocker 3"))
    assertTrue(messageText.contains("Next action 4"))
    assertFalse(messageText.contains("[Recent Findings]"))
    assertFalse(messageText.contains("Finding 1"))
    assertFalse(messageText.contains("Recent action 1"))
    assertFalse(messageText.contains("Decision 1"))
    assertFalse(messageText.contains("Blocker 1"))
    assertFalse(messageText.contains("Next action 1"))
    assertFalse(messageText.contains("updated_at_epoch_ms=456789"))
    assertTrue(layerSummary.contains("WORKING_STATE:"))
    assertTrue(layerSummary.contains(":minimal["))
    assertTrue(layerSummary.contains("reduce_working_state_minimal"))
    assertEquals("dev", requestMetadata["contextBudgetEffectivePreset"])
    assertEquals("raw", requestMetadata["contextBudgetPresetSource"])
    assertTrue(requestMetadata["contextBudgetMinimalLayerCount"].orEmpty().isNotBlank())
    assertTrue(requestMetadata["contextBudgetReducedLayerNames"].orEmpty().contains("Working State"))
    assertEquals(layerSummary, result.metadata["contextBudgetLayerSummary"])
    assertEquals(
      requestMetadata["contextBudgetReducedLayerNames"],
      result.metadata["contextBudgetReducedLayerNames"],
    )
  }

  @Test
  fun runPromptTaskOmitsHostOnlyMetadataFromGatewayRequestMetadata() {
    val workspaceRoot = temporaryFolder.newFolder("agent-host-metadata")
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"final","answer":"metadata sanitized"}""",
      ),
    )
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      clock = IncrementingClock(start = 6_000L)::next,
    )

    val result = runtime.execute(
      task = promptTask(
        input = "Check metadata visibility.",
        metadata = mapOf(
          "_host.pendingMessageId" to "assistant-1",
          "chatMode" to "AUTO",
        ),
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("AUTO", gateway.requests.single().metadata["chatMode"])
    assertTrue("_host.pendingMessageId" !in gateway.requests.single().metadata)
  }

  private fun promptTask(
    input: String,
    metadata: Map<String, String> = emptyMap(),
  ): AgentTask = AgentTask(
    id = "task-${System.nanoTime()}",
    type = AgentTaskType.PROMPT,
    input = input,
    policyDecision = PolicyDecision(
      outcome = PolicyDecisionOutcome.ALLOW,
      reasonCode = "ALLOW_TEST",
    ),
    metadata = metadata,
    createdAtEpochMs = 500L,
  )

  private fun writeSkill(
    root: File,
    relativeDirectory: String,
    frontMatter: String,
    body: String,
  ): File {
    val skillDirectory = root.resolve(relativeDirectory)
    Files.createDirectories(skillDirectory.toPath())
    val skillFile = skillDirectory.resolve("SKILL.md")
    val content = buildString {
      appendLine("---")
      appendLine(frontMatter)
      appendLine("---")
      appendLine(body)
    }
    Files.write(skillFile.toPath(), content.toByteArray(StandardCharsets.UTF_8))
    return skillFile
  }

  private fun runtimeHooks(
    onSuspend: (SuspensionRequest) -> Unit = {},
  ): RuntimeExecutionHooks = RuntimeExecutionHooks(
    isCancellationRequested = { false },
    requestRetry = { _: RetryRequest -> error("Retry not expected in OpenCrayAgentRuntimeTest.") },
    requestSuspend = onSuspend,
  )

  private fun assertLocalContinuationFingerprintFallback(
    expectedLocalContinuationReason: String,
    expectedContextCacheBreakReason: String,
    mutateEnvelope: (
      OpenCraySerializableLocalContinuationEnvelope,
    ) -> OpenCraySerializableLocalContinuationEnvelope,
  ) {
    val workspaceRoot = temporaryFolder.newFolder(
      "agent-local-continuation-$expectedLocalContinuationReason",
    )
    Files.write(
      workspaceRoot.toPath().resolve("README.md"),
      "hello from workspace".toByteArray(StandardCharsets.UTF_8),
    )
    val task = promptTask(input = "Read the README and then answer.")
    val eventSink = RecordingEventSink()
    val initialGateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"workspace_read_file","arguments":{"path":"README.md"}}""",
        """{"type":"final","answer":"Read the README."}""",
      ),
    )
    val initialRuntime = OpenCrayAgentRuntime(
      gateway = initialGateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(maxTurns = 4, maxToolCalls = 2),
      eventSink = eventSink,
      clock = IncrementingClock(start = 70_000L)::next,
    )

    val initialResult = initialRuntime.execute(
      task = task,
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, initialResult.status)
    val checkpointState = checkpointStateFromFirstToolResult(eventSink)
    val originalEnvelope = requireNotNull(checkpointState.localContinuationEnvelope)
    val mutatedState = checkpointState.copy(
      localContinuationEnvelope = mutateEnvelope(originalEnvelope),
    )

    val resumedGateway = RecordingGateway(
      outputs = listOf("""{"type":"final","answer":"Recovered after fingerprint mismatch."}"""),
    )
    val resumedRuntime = OpenCrayAgentRuntime(
      gateway = resumedGateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(
        maxTurns = 4,
        maxToolCalls = 2,
        promptResumeState = mutatedState,
      ),
      clock = IncrementingClock(start = 70_500L)::next,
    )

    val resumedResult = resumedRuntime.execute(
      task = task,
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, resumedResult.status)
    assertEquals("Recovered after fingerprint mismatch.", resumedResult.stdout)
    assertEquals(1, resumedGateway.requests.size)
    assertEquals("full_rebuild", resumedGateway.requests.single().metadata["localContinuationMode"])
    assertEquals(
      expectedLocalContinuationReason,
      resumedGateway.requests.single().metadata["localContinuationReason"],
    )
    assertEquals(
      expectedContextCacheBreakReason,
      resumedGateway.requests.single().metadata[LiteLlmMetadataKeys.CONTEXT_CACHE_BREAK_REASON],
    )
    assertEquals("0", resumedResult.metadata["localContinuationUsedCount"])
    assertEquals("1", resumedResult.metadata["localContinuationFallbackCount"])
    assertEquals(
      expectedContextCacheBreakReason,
      resumedResult.metadata[LiteLlmMetadataKeys.CONTEXT_CACHE_BREAK_REASON],
    )
    val resumedCacheMetadata = resumedGateway.requests.single().metadata
    assertEquals(
      "non_responses_front_zone_v1",
      resumedCacheMetadata[LiteLlmMetadataKeys.CONTEXT_CACHE_CONTRACT_VERSION],
    )
    val initialContinuationCacheMetadata = initialGateway.requests.last().metadata
    assertEquals(
      initialContinuationCacheMetadata[LiteLlmMetadataKeys.CONTEXT_CACHE_STABLE_ANCHOR_HASH],
      resumedCacheMetadata[LiteLlmMetadataKeys.CONTEXT_CACHE_STABLE_ANCHOR_HASH],
    )
    assertEquals(
      initialContinuationCacheMetadata[LiteLlmMetadataKeys.CONTEXT_CACHE_DURABLE_CONTEXT_HASH],
      resumedCacheMetadata[LiteLlmMetadataKeys.CONTEXT_CACHE_DURABLE_CONTEXT_HASH],
    )
  }

  private fun checkpointStateFromFirstToolResult(
    eventSink: RecordingEventSink,
  ): OpenCrayPromptResumeState = requireNotNull(
    OpenCrayPromptResumeMetadata.decodeFromMetadata(
      metadata = eventSink.events
        .filterIsInstance<OpenCrayToolResultEvent>()
        .first()
        .result
        .metadata,
      json = Json { ignoreUnknownKeys = true },
    ),
  )

  private fun promptCacheShapeHash(
    value: String,
  ): String = MessageDigest.getInstance("SHA-256")
    .digest(value.trim().toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte) }
    .take(24)

  private class IncrementingClock(
    start: Long,
  ) {
    private var current: Long = start

    fun next(): Long = current++
  }

  private class RecordingGateway(
    outputs: List<String>,
  ) : LiteLlmGateway {
    private val queuedOutputs = ArrayDeque(outputs)
    val requests = mutableListOf<LiteLlmGatewayRequest>()
    private var now = 2_000L

    override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
      requests += request
      val output = queuedOutputs.removeFirstOrNull()
        ?: error("No fake LLM output left for request ${request.requestId}.")
      val selection = LiteLlmRouteSelectionMetadata(
        profileId = "test-profile",
        routeId = "test-route",
        providerId = "fake",
        model = "fake-model",
        attemptIndex = 0,
      )
      val startedAt = now++
      val finishedAt = now++
      return LiteLlmGatewayResult(
        requestId = request.requestId,
        status = LiteLlmGatewayStatus.SUCCESS,
        completionMode = LiteLlmCompletionMode.PRIMARY,
        outputText = output,
        selectedRoute = selection,
        attempts = listOf(
          LiteLlmAttemptRecord(
            route = selection,
            outcome = LiteLlmAttemptOutcome.SUCCESS,
            outputChars = output.length,
            startedAtEpochMs = startedAt,
            finishedAtEpochMs = finishedAt,
          ),
        ),
        startedAtEpochMs = startedAt,
        finishedAtEpochMs = finishedAt,
      )
    }
  }

  private class RecordingWorkingStateStore : WorkingStateStore {
    private val snapshots = mutableListOf<WorkingState>()

    val history: List<WorkingState>
      get() = snapshots.toList()

    override fun snapshot(): WorkingState = snapshots.lastOrNull() ?: WorkingState()

    override fun replace(state: WorkingState) {
      snapshots += state
    }
  }

  private class DynamicGateway(
    private val outputProvider: (Int) -> String,
  ) : LiteLlmGateway {
    val requests = mutableListOf<LiteLlmGatewayRequest>()
    private var now = 8_000L

    override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
      requests += request
      val output = outputProvider(requests.lastIndex)
      val selection = LiteLlmRouteSelectionMetadata(
        profileId = "test-profile",
        routeId = "test-route",
        providerId = "fake",
        model = "fake-model",
        attemptIndex = 0,
      )
      val startedAt = now++
      val finishedAt = now++
      return LiteLlmGatewayResult(
        requestId = request.requestId,
        status = LiteLlmGatewayStatus.SUCCESS,
        completionMode = LiteLlmCompletionMode.PRIMARY,
        outputText = output,
        selectedRoute = selection,
        attempts = listOf(
          LiteLlmAttemptRecord(
            route = selection,
            outcome = LiteLlmAttemptOutcome.SUCCESS,
            outputChars = output.length,
            startedAtEpochMs = startedAt,
            finishedAtEpochMs = finishedAt,
          ),
        ),
        startedAtEpochMs = startedAt,
        finishedAtEpochMs = finishedAt,
      )
    }
  }

  private class FailingGateway(
    private val status: LiteLlmGatewayStatus,
    private val errorCode: String,
    private val errorMessage: String,
  ) : LiteLlmGateway {
    private var now = 5_000L

    override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
      val selection = LiteLlmRouteSelectionMetadata(
        profileId = "test-profile",
        routeId = "test-route",
        providerId = "fake",
        model = "fake-model",
        attemptIndex = 0,
      )
      val startedAt = now++
      val finishedAt = now++
      return LiteLlmGatewayResult(
        requestId = request.requestId,
        status = status,
        completionMode = LiteLlmCompletionMode.PRIMARY,
        outputText = null,
        errorCode = errorCode,
        errorMessage = errorMessage,
        selectedRoute = selection,
        attempts = listOf(
          LiteLlmAttemptRecord(
            route = selection,
            outcome = LiteLlmAttemptOutcome.FAILED,
            errorCode = errorCode,
            outputChars = 0,
            startedAtEpochMs = startedAt,
            finishedAtEpochMs = finishedAt,
          ),
        ),
        startedAtEpochMs = startedAt,
        finishedAtEpochMs = finishedAt,
      )
    }
  }

  private class ScriptedGateway(
    results: List<LiteLlmGatewayResult>,
  ) : LiteLlmGateway {
    private val queuedResults = ArrayDeque(results)
    val requests = mutableListOf<LiteLlmGatewayRequest>()

    override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
      requests += request
      return queuedResults.removeFirstOrNull()
        ?.copy(requestId = request.requestId)
        ?: error("No scripted LLM result left for request ${request.requestId}.")
    }
  }

  private class RecordingEventSink : OpenCrayAgentRuntimeEventSink {
    val events = mutableListOf<OpenCrayAgentRunEvent>()
    val assistantDrafts = mutableListOf<String>()
    var assistantDraftClearCount: Int = 0

    override fun onRunEvent(task: AgentTask, event: OpenCrayAgentRunEvent) {
      events += event
    }

    override fun onAssistantDraftUpdated(
      task: AgentTask,
      text: String,
      emittedAtEpochMs: Long,
    ) {
      assistantDrafts += text
    }

    override fun onAssistantDraftCleared(
      task: AgentTask,
      emittedAtEpochMs: Long,
    ) {
      assistantDraftClearCount += 1
    }
  }

  private fun visibleSupplementEvents(
    events: List<OpenCrayAgentRunEvent>,
  ): List<OpenCraySupplementEvent> = events
    .filterIsInstance<OpenCraySupplementEvent>()
    .filterNot(::isInternalCheckpointMarker)

  private fun internalCheckpointMarkers(
    events: List<OpenCrayAgentRunEvent>,
  ): List<OpenCraySupplementEvent> = events
    .filterIsInstance<OpenCraySupplementEvent>()
    .filter(::isInternalCheckpointMarker)

  private fun isInternalCheckpointMarker(
    event: OpenCrayAgentRunEvent,
  ): Boolean = event is OpenCraySupplementEvent &&
    event.text.isBlank() &&
    event.checkpoint == "internal_prompt_checkpoint"

  private fun externalEventKinds(
    events: List<OpenCrayAgentRunEvent>,
  ): List<String> = events
    .filterNot(::isInternalCheckpointMarker)
    .map { event ->
      when (event) {
        is OpenCrayLifecycleEvent -> "lifecycle"
        is OpenCrayAssistantPhaseEvent -> "assistant"
        is OpenCraySupplementEvent -> "supplement"
        is OpenCrayApprovalEvent -> "approval"
        is OpenCraySubAgentEvent -> "subagent"
        is OpenCrayToolCallEvent -> "tool_call"
        is OpenCrayToolResultEvent -> "tool_result"
        is OpenCrayMemoryRetrievalEvent -> "memory_retrieval"
        is OpenCrayMemoryWriteEvent -> "memory_write"
        is OpenCrayCancellationEvent -> "cancelled"
      }
    }

  private fun gatewayStructuredPayloadText(
    request: LiteLlmGatewayRequest,
  ): String = request.messages.joinToString(separator = "\n\n") { message ->
    buildString {
      message.content?.trim()?.takeIf(String::isNotBlank)?.let(::append)
      message.toolResult?.content?.trim()?.takeIf(String::isNotBlank)?.let { toolResultText ->
        if (isNotEmpty()) {
          append("\n")
        }
        append(toolResultText)
      }
    }.trim()
  }.trim()

  private class ScriptedProcessRegistry : AgentProcessRegistry {
    private val snapshotsById = linkedMapOf<String, ManagedProcessSnapshot>()
    val waitTimeouts = mutableListOf<Long>()
    var startedProcessId: String? = null
      private set

    override fun start(request: ManagedProcessStartRequest): ManagedProcessSnapshot {
      startedProcessId = request.processId
      return ManagedProcessSnapshot(
        processId = request.processId,
        taskId = request.taskId,
        command = request.command,
        args = request.args,
        workingDirectory = request.workingDirectory,
        status = ManagedProcessStatus.RUNNING,
        processStarted = true,
        timeoutMs = request.timeoutMs,
        startedAtEpochMs = 1_000L,
        updatedAtEpochMs = 1_000L,
        metadata = request.metadata,
      ).also { snapshot ->
        snapshotsById[request.processId] = snapshot
      }
    }

    override fun list(): List<ManagedProcessSnapshot> = snapshotsById.values.toList()

    override fun read(processId: String): ManagedProcessSnapshot? = snapshotsById[processId]

    override fun wait(processId: String, timeoutMs: Long): ManagedProcessSnapshot? {
      waitTimeouts += timeoutMs
      val existing = snapshotsById[processId] ?: return null
      return existing.copy(
        status = ManagedProcessStatus.SUCCESS,
        stdout = "server ready",
        exitCode = 0,
        updatedAtEpochMs = existing.updatedAtEpochMs + timeoutMs,
        finishedAtEpochMs = existing.updatedAtEpochMs + timeoutMs,
      ).also { snapshot ->
        snapshotsById[processId] = snapshot
      }
    }

    override fun terminate(processId: String): ManagedProcessSnapshot? = snapshotsById[processId]
  }

  private fun gatewaySuccessResult(
    outputText: String,
    completion: LiteLlmStructuredCompletion? = null,
  ): LiteLlmGatewayResult {
    val selection = LiteLlmRouteSelectionMetadata(
      profileId = "test-profile",
      routeId = "test-route",
      providerId = "fake",
      model = "fake-model",
      attemptIndex = 0,
    )
    return LiteLlmGatewayResult(
      requestId = "scripted-success",
      status = LiteLlmGatewayStatus.SUCCESS,
      completionMode = LiteLlmCompletionMode.PRIMARY,
      outputText = outputText,
      completion = completion,
      selectedRoute = selection,
      attempts = listOf(
        LiteLlmAttemptRecord(
          route = selection,
          outcome = LiteLlmAttemptOutcome.SUCCESS,
          outputChars = outputText.length,
          startedAtEpochMs = 1_000L,
          finishedAtEpochMs = 1_001L,
        ),
      ),
      startedAtEpochMs = 1_000L,
      finishedAtEpochMs = 1_001L,
    )
  }

  private fun gatewayFailureResult(
    errorCode: String,
    errorMessage: String,
    completion: LiteLlmStructuredCompletion? = null,
  ): LiteLlmGatewayResult {
    val selection = LiteLlmRouteSelectionMetadata(
      profileId = "test-profile",
      routeId = "test-route",
      providerId = "fake",
      model = "fake-model",
      attemptIndex = 0,
    )
    return LiteLlmGatewayResult(
      requestId = "scripted-failure",
      status = LiteLlmGatewayStatus.FAILED,
      completionMode = LiteLlmCompletionMode.TERMINAL,
      completion = completion,
      selectedRoute = selection,
      attempts = listOf(
        LiteLlmAttemptRecord(
          route = selection,
          outcome = LiteLlmAttemptOutcome.FAILED,
          errorCode = errorCode,
          startedAtEpochMs = 1_000L,
          finishedAtEpochMs = 1_001L,
        ),
      ),
      errorCode = errorCode,
      errorMessage = errorMessage,
      startedAtEpochMs = 1_000L,
      finishedAtEpochMs = 1_001L,
    )
  }
}
