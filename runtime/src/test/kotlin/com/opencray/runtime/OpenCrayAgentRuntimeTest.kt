package com.opencray.runtime

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.RetryRequest
import com.opencray.core.orchestrator.RuntimeExecutionHooks
import com.opencray.llm.LiteLlmAttemptOutcome
import com.opencray.llm.LiteLlmAttemptRecord
import com.opencray.llm.LiteLlmCompletionMode
import com.opencray.llm.LiteLlmGateway
import com.opencray.llm.LiteLlmGatewayRequest
import com.opencray.llm.LiteLlmGatewayResult
import com.opencray.llm.LiteLlmGatewayStatus
import com.opencray.llm.LiteLlmRouteSelectionMetadata
import com.opencray.runtime.context.AgentRuntimeSessionContext
import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.context.RuntimeConversationRole
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

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
    assertTrue(result.metadata["contextLayerNames"].orEmpty().contains("Task Context"))
    assertTrue(gateway.requests[0].systemPrompt.orEmpty().contains("[Session Policy]"))
    assertTrue(gateway.requests[0].prompt.contains("Earlier question."))
    assertTrue(gateway.requests[0].prompt.contains("Earlier answer."))
    assertTrue(gateway.requests[0].prompt.contains("What changed since then?"))
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

  private fun runtimeHooks(): RuntimeExecutionHooks = RuntimeExecutionHooks(
    isCancellationRequested = { false },
    requestRetry = { _: RetryRequest -> error("Retry not expected in OpenCrayAgentRuntimeTest.") },
  )

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
}
