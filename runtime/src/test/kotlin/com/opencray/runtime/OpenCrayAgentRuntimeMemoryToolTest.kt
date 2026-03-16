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
import com.opencray.persistence.model.MemoryRecord
import com.opencray.runtime.memory.MemoryToolContext
import com.opencray.runtime.memory.formatMemoryDateStamp
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OpenCrayAgentRuntimeMemoryToolTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun runPromptTaskGuidesModelToSearchProjectedMemoryAndFeedsToolObservationsForward() {
    val workspaceRoot = temporaryFolder.newFolder("agent-memory-tool-workspace").toPath()
    Files.createDirectories(workspaceRoot)
    val expectedPath = "memory/${formatMemoryDateStamp(DAY_2_EPOCH_MS)}.md"
    val eventSink = RecordingEventSink()
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"memory_search","arguments":{"query":"gradle wrapper repo root"}}""",
        """{"type":"tool_call","tool_name":"memory_get","arguments":{"path":"$expectedPath","from":5,"lines":4}}""",
        """{"type":"final","answer":"The repo uses the Gradle wrapper from the repo root."}""",
      ),
    )
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot),
          memoryToolContext = MemoryToolContext(
            sessionId = "session-main",
            workspaceId = "workspace-main",
            records = listOf(
              memoryRecord(
                id = "mem-workspace",
                content = "Project uses the Gradle wrapper from the repo root.",
                kind = "project_fact",
                scope = "workspace",
                sourceSessionId = "session-source",
                workspaceId = "workspace-main",
                confirmedAtEpochMs = DAY_2_EPOCH_MS,
                updatedAtEpochMs = DAY_2_EPOCH_MS,
              ),
            ),
          ),
        ),
      ),
      eventSink = eventSink,
      clock = IncrementingClock(start = 10_000L)::next,
    )

    val result = runtime.execute(
      task = promptTask("What did we decide about the build setup earlier?"),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("The repo uses the Gradle wrapper from the repo root.", result.stdout)
    assertEquals(3, gateway.requests.size)
    assertTrue(gateway.requests[0].prompt.contains("search projected memory first instead of guessing"))
    assertTrue(gateway.requests[0].prompt.contains("memory_search"))
    assertTrue(gateway.requests[1].prompt.contains(expectedPath))
    assertTrue(gateway.requests[1].prompt.contains("score="))
    assertTrue(gateway.requests[2].prompt.contains("## mem-workspace"))
    assertTrue(gateway.requests[2].prompt.contains("kind: project_fact"))
    val retrievalEvents = eventSink.events.filterIsInstance<OpenCrayMemoryRetrievalEvent>()
    assertEquals(2, retrievalEvents.size)
    assertEquals("search", retrievalEvents[0].operation)
    assertEquals("memory_search", retrievalEvents[0].toolName)
    assertEquals(listOf("gradle", "wrapper", "repo", "root"), retrievalEvents[0].queryTerms)
    assertEquals(listOf(expectedPath), retrievalEvents[0].paths)
    assertTrue(retrievalEvents[0].lineRanges.isNotEmpty())
    assertEquals("get", retrievalEvents[1].operation)
    assertEquals("memory_get", retrievalEvents[1].toolName)
    assertEquals(expectedPath, retrievalEvents[1].path)
    assertEquals(5, retrievalEvents[1].fromLine)
    assertEquals(4, retrievalEvents[1].returnedLineCount)
    assertTrue(retrievalEvents[1].totalLineCount != null)
  }

  @Test
  fun directToolCallTaskAlsoEmitsMemoryRetrievalEvent() {
    val workspaceRoot = temporaryFolder.newFolder("agent-memory-tool-direct").toPath()
    Files.createDirectories(workspaceRoot)
    val eventSink = RecordingEventSink()
    val runtime = OpenCrayAgentRuntime(
      gateway = RecordingGateway(outputs = emptyList()),
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot),
          memoryToolContext = MemoryToolContext(
            sessionId = "session-main",
            workspaceId = "workspace-main",
            records = listOf(
              memoryRecord(
                id = "mem-workspace",
                content = "Project uses the Gradle wrapper from the repo root.",
                kind = "project_fact",
                scope = "workspace",
                sourceSessionId = "session-source",
                workspaceId = "workspace-main",
                confirmedAtEpochMs = DAY_2_EPOCH_MS,
                updatedAtEpochMs = DAY_2_EPOCH_MS,
              ),
            ),
          ),
        ),
      ),
      eventSink = eventSink,
      clock = IncrementingClock(start = 11_000L)::next,
    )

    val result = runtime.execute(
      task = toolCallTask(
        """{"type":"tool_call","tool_name":"memory_search","arguments":{"query":"gradle wrapper repo root"}}""",
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals(
      listOf("lifecycle", "tool_call", "tool_result", "memory_retrieval", "lifecycle"),
      eventSink.events.map { event ->
        when (event) {
          is OpenCrayLifecycleEvent -> "lifecycle"
          is OpenCrayToolCallEvent -> "tool_call"
          is OpenCrayToolResultEvent -> "tool_result"
          is OpenCrayMemoryRetrievalEvent -> "memory_retrieval"
          is OpenCrayAssistantEvent -> "assistant"
          is OpenCrayMemoryWriteEvent -> "memory_write"
        }
      },
    )
    val retrievalEvent = eventSink.events.filterIsInstance<OpenCrayMemoryRetrievalEvent>().single()
    assertEquals("search", retrievalEvent.operation)
    assertEquals("memory_search", retrievalEvent.toolName)
    assertEquals(listOf("gradle", "wrapper", "repo", "root"), retrievalEvent.queryTerms)
    assertEquals(1, retrievalEvent.resultCount)
  }

  private fun promptTask(input: String): AgentTask = AgentTask(
    id = "task-memory-runtime",
    type = AgentTaskType.PROMPT,
    input = input,
    policyDecision = PolicyDecision(
      outcome = PolicyDecisionOutcome.ALLOW,
      reasonCode = "TEST_ALLOW",
    ),
    createdAtEpochMs = 1L,
  )

  private fun toolCallTask(input: String): AgentTask = AgentTask(
    id = "task-memory-direct",
    type = AgentTaskType.TOOL_CALL,
    input = input,
    policyDecision = PolicyDecision(
      outcome = PolicyDecisionOutcome.ALLOW,
      reasonCode = "TEST_ALLOW",
    ),
    createdAtEpochMs = 1L,
  )

  private fun runtimeHooks(): RuntimeExecutionHooks = RuntimeExecutionHooks(
    isCancellationRequested = { false },
    requestRetry = { _: RetryRequest -> error("Retry not expected in OpenCrayAgentRuntimeMemoryToolTest.") },
  )

  private fun memoryRecord(
    id: String,
    content: String,
    kind: String,
    scope: String,
    sourceSessionId: String,
    workspaceId: String? = null,
    confirmedAtEpochMs: Long,
    updatedAtEpochMs: Long,
  ): MemoryRecord = MemoryRecord(
    id = id,
    content = content,
    createdAtEpochMs = updatedAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs,
    tags = listOf(
      "kind:$kind",
      "scope:$scope",
      "status:active",
    ),
    extensions = mapOf(
      "kind" to kind,
      "scope" to scope,
      "status" to "active",
      "source_session_id" to sourceSessionId,
      "last_confirmed_at_epoch_ms" to confirmedAtEpochMs.toString(),
    ) + listOfNotNull(
      workspaceId?.let { "workspace_id" to it },
    ).toMap(),
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
    private var now = 20_000L

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

  private class RecordingEventSink : OpenCrayAgentRuntimeEventSink {
    val events = mutableListOf<OpenCrayAgentRunEvent>()

    override fun onRunEvent(task: AgentTask, event: OpenCrayAgentRunEvent) {
      events += event
    }
  }

  private companion object {
    const val DAY_2_EPOCH_MS: Long = 1_710_086_400_000L
  }
}
