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
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OpenCrayAgentRuntimeRecentObservationTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun secondTurnPromptIncludesRecentWorkspaceObservationLayer() {
    val workspaceRoot = temporaryFolder.newFolder("recent-observation-layer").toPath()
    Files.write(
      workspaceRoot.resolve("README.md"),
      """
      line 1
      line 2
      line 3
      line 4
      """.trimIndent().toByteArray(StandardCharsets.UTF_8),
    )
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"Read","arguments":{"file_path":"README.md"}}""",
        """{"type":"final","answer":"README captured."}""",
      ),
    )
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(maxTurns = 4, maxToolCalls = 2),
      clock = IncrementingClock(start = 30_000L)::next,
    )

    val result = runtime.execute(
      task = promptTask("Read README and summarize it."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals(2, gateway.requests.size)
    assertTrue(gateway.requests[1].prompt.contains("[Recent Working Observations]"))
    assertTrue(gateway.requests[1].prompt.contains("Read file_path=README.md"))
    assertTrue(gateway.requests[1].prompt.contains("line 1"))
    assertEquals("1", gateway.requests[1].metadata["contextRecentObservationCount"])
    assertEquals("true", gateway.requests[1].metadata["contextRecentObservationLayerIncluded"])
  }

  @Test
  fun repeatedIdenticalReadIsSoftGuardedWithPriorObservationExcerpt() {
    val workspaceRoot = temporaryFolder.newFolder("recent-observation-duplicate").toPath()
    Files.write(
      workspaceRoot.resolve("README.md"),
      """
      duplicate guard
      keeps the earlier observation visible
      """.trimIndent().toByteArray(StandardCharsets.UTF_8),
    )
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"Read","arguments":{"file_path":"README.md"}}""",
        """{"type":"tool_call","tool_name":"Read","arguments":{"file_path":"README.md"}}""",
        """{"type":"final","answer":"Duplicate read avoided."}""",
      ),
    )
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(maxTurns = 6, maxToolCalls = 3),
      clock = IncrementingClock(start = 31_000L)::next,
    )

    val result = runtime.execute(
      task = promptTask("Read README and then keep going."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Duplicate read avoided.", result.stdout)
    assertEquals(3, gateway.requests.size)
    assertTrue(
      gateway.requests[2].prompt.contains(
        "Identical discovery call already succeeded earlier in this task.",
      ),
    )
    assertTrue(
      gateway.requests[2].prompt.contains(
        "previous_observation=Read file_path=README.md",
      ),
    )
  }

  private fun promptTask(input: String): AgentTask = AgentTask(
    id = "task-recent-observation",
    type = AgentTaskType.PROMPT,
    input = input,
    policyDecision = PolicyDecision(
      outcome = PolicyDecisionOutcome.ALLOW,
      reasonCode = "TEST_ALLOW",
    ),
    createdAtEpochMs = 1L,
  )

  private fun runtimeHooks(): RuntimeExecutionHooks = RuntimeExecutionHooks(
    isCancellationRequested = { false },
    requestRetry = { _: RetryRequest ->
      error("Retry not expected in OpenCrayAgentRuntimeRecentObservationTest.")
    },
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
    private var now = 40_000L

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
}
