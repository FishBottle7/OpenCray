package com.opencray.runtime

import com.opencray.core.contracts.ExecutionStatus
import com.opencray.llm.LiteLlmAttemptOutcome
import com.opencray.llm.LiteLlmAttemptRecord
import com.opencray.llm.LiteLlmAssistantPhase
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
import com.opencray.runtime.context.AgentRuntimeSessionContext
import com.opencray.runtime.context.RuntimeConversationAssistantPhase
import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.context.RuntimeConversationMessageKind
import com.opencray.runtime.context.RuntimeConversationCommentary
import com.opencray.runtime.context.RuntimeConversationRole
import com.opencray.runtime.web.WebSearchHit
import com.opencray.runtime.web.WebSearchProvider
import com.opencray.runtime.web.WebSearchRequest
import com.opencray.runtime.web.WebSearchResult
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class OpenCrayAgentRuntimeStructuredToolCallTest : OpenCrayAgentRuntimeTestBase() {
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
    val commentaryEvents = eventSink.events
      .filterIsInstance<OpenCrayAssistantEvent>()
      .filterNot(OpenCrayAssistantEvent::isFinal)
    assertEquals(
      listOf(
        "Scanning the README before reading it.",
        "Opening the relevant section now.",
      ),
      commentaryEvents.map(OpenCrayAssistantEvent::text),
    )
    assertTrue(commentaryEvents.all { event -> !event.eventId.isNullOrBlank() })
    assertEquals(2, commentaryEvents.map(OpenCrayAssistantEvent::eventId).distinct().size)
    val replayState = requireNotNull(
      OpenCrayPromptResumeMetadata.decodeFromMetadata(
        metadata = commentaryEvents.first().metadata,
        json = Json { ignoreUnknownKeys = true },
      ),
    )
    assertEquals(
      commentaryEvents.map(OpenCrayAssistantEvent::eventId),
      replayState.pendingActions
        .filterIsInstance<OpenCraySerializableModelAction.Commentary>()
        .map(OpenCraySerializableModelAction.Commentary::eventId),
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
}
