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
import com.opencray.runtime.memory.MemoryToolContext
import com.opencray.runtime.session.SessionSearchCompactionSummary
import com.opencray.runtime.session.SessionSearchSession
import com.opencray.runtime.session.SessionSearchToolContext
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OpenCrayAgentRuntimeSessionToolTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun runPromptTaskGuidesModelTowardPriorSessionHistoryAndEmitsRetrievalEvents() {
    val workspaceRoot = temporaryFolder.newFolder("agent-session-tool-workspace").toPath()
    Files.createDirectories(workspaceRoot)
    val expectedPath = "sessions/session-archive.md"
    val eventSink = RecordingEventSink()
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"session_search","arguments":{"query":"gradle wrapper repo root"}}""",
        """{"type":"tool_call","tool_name":"session_get","arguments":{"path":"$expectedPath","from":8,"lines":4}}""",
        """{"type":"final","answer":"In the earlier session, we kept the Gradle wrapper at the repo root."}""",
      ),
    )
    val runtime = runtimeWithSessionTools(
      workspaceRoot = workspaceRoot,
      gateway = gateway,
      eventSink = eventSink,
      clockStart = 20_000L,
    )

    val result = runtime.execute(
      task = promptTask("What happened in the earlier build setup chat?"),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("In the earlier session, we kept the Gradle wrapper at the repo root.", result.stdout)
    assertEquals(3, gateway.requests.size)
    assertTrue(gateway.requests[0].prompt.contains("Durable memory tools expose long-lived remembered records."))
    assertTrue(gateway.requests[0].prompt.contains("memory_search"))
    assertTrue(gateway.requests[0].prompt.contains("session_search"))
    assertTrue(gateway.requests[0].prompt.contains("past_session_search"))
    assertTrue(gateway.requests[0].prompt.contains("session_search excludes the current session by default."))
    assertTrue(gateway.requests[1].prompt.contains("Found 1 projected session match(es)."))
    assertTrue(
      gateway.requests[1].prompt.contains(expectedPath) ||
        gateway.requests[1].prompt.contains("SESSIONS.md"),
    )
    assertTrue(gateway.requests[1].prompt.contains("\"tool_name\": \"session_search\""))
    assertTrue(gateway.requests[2].prompt.contains("\"tool_name\": \"session_get\""))
    assertTrue(gateway.requests[2].prompt.contains("## Summary 1"))
    assertTrue(gateway.requests[2].prompt.contains("We decided to keep the Gradle wrapper at the repo root."))

    val retrievalEvents = eventSink.events.filterIsInstance<OpenCrayMemoryRetrievalEvent>()
    assertEquals(2, retrievalEvents.size)
    assertEquals("search", retrievalEvents[0].operation)
    assertEquals("session_search", retrievalEvents[0].toolName)
    assertEquals("session_history", retrievalEvents[0].surface)
    assertEquals(listOf("gradle", "wrapper", "repo", "root"), retrievalEvents[0].queryTerms)
    assertEquals(listOf("session-archive"), retrievalEvents[0].recordIds)
    assertEquals(listOf(expectedPath), retrievalEvents[0].paths)
    assertEquals(listOf("L8-L11"), retrievalEvents[0].lineRanges)
    assertEquals("get", retrievalEvents[1].operation)
    assertEquals("session_get", retrievalEvents[1].toolName)
    assertEquals("session_history", retrievalEvents[1].surface)
    assertEquals(listOf("session-archive"), retrievalEvents[1].recordIds)
    assertEquals(expectedPath, retrievalEvents[1].path)
    assertEquals(8, retrievalEvents[1].fromLine)
    assertEquals(4, retrievalEvents[1].returnedLineCount)
    assertTrue(retrievalEvents[1].totalLineCount != null)
  }

  @Test
  fun runPromptTaskSupportsPastSessionArchiveSurfaceAndEmitsTrace() {
    val workspaceRoot = temporaryFolder.newFolder("agent-past-session-tool-workspace").toPath()
    Files.createDirectories(workspaceRoot)
    val expectedPath = "sessions/session-archive.md"
    val eventSink = RecordingEventSink()
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"past_session_search","arguments":{"query":"gradle wrapper repo root"}}""",
        """{"type":"tool_call","tool_name":"past_session_get","arguments":{"path":"$expectedPath","from":8,"lines":4}}""",
        """{"type":"final","answer":"Past session archive confirms we kept the Gradle wrapper at the repo root."}""",
      ),
    )
    val runtime = runtimeWithSessionTools(
      workspaceRoot = workspaceRoot,
      gateway = gateway,
      eventSink = eventSink,
      clockStart = 25_000L,
    )

    val result = runtime.execute(
      task = promptTask("Find the build setup decision from past sessions."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Past session archive confirms we kept the Gradle wrapper at the repo root.", result.stdout)
    assertEquals(3, gateway.requests.size)
    assertTrue(gateway.requests[0].prompt.contains("past_session_search"))
    assertTrue(gateway.requests[0].prompt.contains("tool-driven and not auto-injected"))
    assertTrue(gateway.requests[1].prompt.contains("Found 1 past-session archive match(es)."))
    assertTrue(gateway.requests[1].prompt.contains("reference=$expectedPath#L8-L11"))
    assertTrue(gateway.requests[2].prompt.contains("\"tool_name\": \"past_session_get\""))

    val retrievalEvents = eventSink.events.filterIsInstance<OpenCrayMemoryRetrievalEvent>()
    assertEquals(2, retrievalEvents.size)
    assertEquals("past_session_search", retrievalEvents[0].toolName)
    assertEquals("search", retrievalEvents[0].operation)
    assertEquals("session_archive", retrievalEvents[0].surface)
    assertEquals(listOf("session-archive"), retrievalEvents[0].recordIds)
    assertEquals(listOf(expectedPath), retrievalEvents[0].paths)
    assertEquals("past_session_get", retrievalEvents[1].toolName)
    assertEquals("get", retrievalEvents[1].operation)
    assertEquals("session_archive", retrievalEvents[1].surface)
    assertEquals(expectedPath, retrievalEvents[1].path)
  }

  @Test
  fun memoryToolsDisabledAlsoHidesAndDeniesPastSessionTools() {
    val workspaceRoot = temporaryFolder.newFolder("agent-past-session-tool-disabled").toPath()
    Files.createDirectories(workspaceRoot)
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"final","answer":"memory/session continuity tools should be hidden."}""",
      ),
    )
    val runtime = runtimeWithSessionTools(
      workspaceRoot = workspaceRoot,
      gateway = gateway,
      runtimeConfig = OpenCrayAgentRuntimeConfig(
        sessionContext = AgentRuntimeSessionContext(
          memoryToolsEnabled = false,
        ),
      ),
      clockStart = 26_000L,
    )

    val promptResult = runtime.execute(
      task = promptTask("look up earlier session decisions"),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, promptResult.status)
    assertFalse(gateway.requests.single().prompt.contains("past_session_search"))
    assertFalse(gateway.requests.single().prompt.contains("past_session_get"))
    assertFalse(gateway.requests.single().prompt.contains("session_search"))
    assertFalse(gateway.requests.single().prompt.contains("session_get"))

    val toolResult = runtime.execute(
      task = toolCallTask(
        """{"type":"tool_call","tool_name":"past_session_search","arguments":{"query":"gradle wrapper repo root"}}""",
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.DENIED, toolResult.status)
    assertEquals("MEMORY_TOOL_DISABLED", toolResult.errorCode)
    assertEquals("past_session_search", toolResult.metadata["toolName"])
  }

  private fun promptTask(input: String): AgentTask = AgentTask(
    id = "task-session-runtime",
    type = AgentTaskType.PROMPT,
    input = input,
    policyDecision = PolicyDecision(
      outcome = PolicyDecisionOutcome.ALLOW,
      reasonCode = "TEST_ALLOW",
    ),
    createdAtEpochMs = 1L,
  )

  private fun toolCallTask(input: String): AgentTask = AgentTask(
    id = "task-session-runtime-tool",
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
    requestRetry = { _: RetryRequest -> error("Retry not expected in OpenCrayAgentRuntimeSessionToolTest.") },
  )

  private fun runtimeWithSessionTools(
    workspaceRoot: java.nio.file.Path,
    gateway: LiteLlmGateway,
    eventSink: OpenCrayAgentRuntimeEventSink = NoOpOpenCrayAgentRuntimeEventSink,
    runtimeConfig: OpenCrayAgentRuntimeConfig = OpenCrayAgentRuntimeConfig(),
    clockStart: Long,
  ): OpenCrayAgentRuntime = OpenCrayAgentRuntime(
    gateway = gateway,
    toolDispatcher = OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(workspaceRoot),
        memoryToolContext = MemoryToolContext(
          sessionId = "session-main",
          records = emptyList(),
        ),
        sessionSearchToolContext = sessionSearchToolContext(),
      ),
    ),
    config = runtimeConfig,
    eventSink = eventSink,
    clock = IncrementingClock(start = clockStart)::next,
  )

  private fun sessionSearchToolContext(): SessionSearchToolContext = SessionSearchToolContext(
    sessionId = "session-main",
    sessions = listOf(
      SessionSearchSession(
        sessionId = "session-main",
        title = "Current session",
        createdAtEpochMs = CURRENT_SESSION_EPOCH_MS,
        updatedAtEpochMs = CURRENT_SESSION_EPOCH_MS,
        messages = listOf(
          RuntimeConversationMessage(
            role = RuntimeConversationRole.ASSISTANT,
            content = "Current-session note about the Gradle wrapper.",
          ),
        ),
        compactionSummaries = listOf(
          SessionSearchCompactionSummary(
            text = "Current session summary that should be excluded by default.",
            compactedAtEpochMs = CURRENT_SESSION_EPOCH_MS,
          ),
        ),
      ),
      SessionSearchSession(
        sessionId = "session-archive",
        title = "Build setup follow-up",
        createdAtEpochMs = ARCHIVE_SESSION_EPOCH_MS,
        updatedAtEpochMs = ARCHIVE_SESSION_EPOCH_MS,
        messages = listOf(
          RuntimeConversationMessage(
            role = RuntimeConversationRole.USER,
            content = "What did we decide about the build setup?",
          ),
          RuntimeConversationMessage(
            role = RuntimeConversationRole.ASSISTANT,
            content = "We use the Gradle wrapper from the repo root.",
          ),
        ),
        compactionSummaries = listOf(
          SessionSearchCompactionSummary(
            text = "We decided to keep the Gradle wrapper at the repo root.",
            compactedAtEpochMs = ARCHIVE_SESSION_EPOCH_MS,
          ),
        ),
      ),
    ),
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
    private var now = 30_000L

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
    const val CURRENT_SESSION_EPOCH_MS: Long = 1_710_172_800_000L
    const val ARCHIVE_SESSION_EPOCH_MS: Long = 1_710_086_400_000L
  }
}
