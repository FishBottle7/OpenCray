package com.opencray.runtime

import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.orchestrator.SuspensionRequest
import com.opencray.llm.LiteLlmAttemptOutcome
import com.opencray.llm.LiteLlmAttemptRecord
import com.opencray.llm.LiteLlmCompletionMode
import com.opencray.llm.LiteLlmGateway
import com.opencray.llm.LiteLlmGatewayRequest
import com.opencray.llm.LiteLlmGatewayResult
import com.opencray.llm.LiteLlmMetadataKeys
import com.opencray.llm.LiteLlmGatewayStatus
import com.opencray.llm.LiteLlmRouteSelectionMetadata
import com.opencray.llm.LiteLlmStructuredCompletion
import com.opencray.runtime.context.AgentRuntimeSessionContext
import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.context.RuntimeConversationRole
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.Json

class OpenCrayAgentRuntimeFailureRecoveryTest : OpenCrayAgentRuntimeTestBase() {
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
}
