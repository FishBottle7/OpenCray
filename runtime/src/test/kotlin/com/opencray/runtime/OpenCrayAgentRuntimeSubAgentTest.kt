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
import com.opencray.runtime.subagent.SubAgentMetadataKeys
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OpenCrayAgentRuntimeSubAgentTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun taskToolRunsResearcherChildWithMinimalContext() {
    val workspaceRoot = temporaryFolder.newFolder("subagent-minimal").toPath()
    Files.write(
      workspaceRoot.resolve("README.md"),
      "hello".toByteArray(StandardCharsets.UTF_8),
    )
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"Task","arguments":{"description":"inspect readme","prompt":"Read README.md and summarize it.","subagent_type":"researcher"}}""",
        """{"type":"final","answer":"README says hello."}""",
        """{"type":"final","answer":"Child summary: README says hello."}""",
      ),
    )
    val eventSink = RecordingEventSink()
    val runtime = runtime(
      workspaceRoot = workspaceRoot,
      gateway = gateway,
      eventSink = eventSink,
    )

    val result = runtime.execute(
      task = promptTask("Please delegate README inspection and then answer."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Child summary: README says hello.", result.stdout)
    assertEquals(3, gateway.requests.size)
    val taskResultMetadata = eventSink.events
      .filterIsInstance<OpenCrayToolResultEvent>()
      .single()
      .result
      .metadata
    assertEquals("delegate_task", taskResultMetadata["capabilityKind"])
    assertEquals("delegation", taskResultMetadata["intentCategory"])
    assertEquals("subagent_task", taskResultMetadata["delegationIntentKind"])
    assertEquals("researcher", taskResultMetadata["delegationSubagentType"])
    assertEquals("minimal", taskResultMetadata["delegationContextMode"])
    assertEquals("Glob,Grep,LS,Read", taskResultMetadata["delegationAllowedTools"])
    assertFalse(gateway.requests[1].prompt.contains("Please delegate README inspection and then answer."))
    assertTrue(gateway.requests[1].prompt.contains("Read README.md and summarize it."))
    assertTrue(gateway.requests[2].prompt.contains("README says hello."))
  }

  @Test
  fun taskToolRunsGeneralPurposeChildWithDelegatedParentSummary() {
    val workspaceRoot = temporaryFolder.newFolder("subagent-delegated").toPath()
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"Task","arguments":{"description":"continue investigation","prompt":"Check the repo layout and report back.","subagent_type":"general-purpose"}}""",
        """{"type":"final","answer":"Layout inspected."}""",
        """{"type":"final","answer":"Delegated result received."}""",
      ),
    )
    val runtime = runtime(
      workspaceRoot = workspaceRoot,
      gateway = gateway,
    )

    val result = runtime.execute(
      task = promptTask("Investigate the codebase and continue carefully."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Delegated result received.", result.stdout)
    assertEquals(3, gateway.requests.size)
    assertTrue(gateway.requests[1].prompt.contains("Delegated parent context for this child run."))
    assertTrue(gateway.requests[1].prompt.contains("user_goal=Investigate the codebase and continue carefully."))
    assertTrue(gateway.requests[1].prompt.contains("Check the repo layout and report back."))
  }

  @Test
  fun taskToolAllowsExplicitMirroredContextOverride() {
    val workspaceRoot = temporaryFolder.newFolder("subagent-mirrored-override").toPath()
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"Task","arguments":{"description":"reuse prior context","prompt":"Continue from the preserved conversation context.","subagent_type":"general-purpose","context_mode":"mirrored"}}""",
        """{"type":"final","answer":"Mirrored child answer."}""",
        """{"type":"final","answer":"Parent received mirrored answer."}""",
      ),
    )
    val eventSink = RecordingEventSink()
    val runtime = runtime(
      workspaceRoot = workspaceRoot,
      gateway = gateway,
      eventSink = eventSink,
      sessionContext = AgentRuntimeSessionContext(
        conversation = listOf(
          RuntimeConversationMessage(
            role = RuntimeConversationRole.USER,
            content = "Prior parent context that mirrored child should receive.",
          ),
        ),
      ),
    )

    val result = runtime.execute(
      task = promptTask("Delegate follow-up work and keep the prior context."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Parent received mirrored answer.", result.stdout)
    val taskResultMetadata = eventSink.events
      .filterIsInstance<OpenCrayToolResultEvent>()
      .single()
      .result
      .metadata
    assertEquals("mirrored", taskResultMetadata["delegationContextMode"])
    assertTrue(gateway.requests[1].prompt.contains("Delegate follow-up work and keep the prior context."))
    assertTrue(gateway.requests[1].prompt.contains("Prior parent context that mirrored child should receive."))
    assertFalse(gateway.requests[1].prompt.contains("Delegated parent context for this child run."))
  }

  @Test
  fun delegatedChildReceivesRecentWorkspaceObservationSummaries() {
    val workspaceRoot = temporaryFolder.newFolder("subagent-delegated-observations").toPath()
    Files.write(
      workspaceRoot.resolve("README.md"),
      "hello".toByteArray(StandardCharsets.UTF_8),
    )
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"Read","arguments":{"file_path":"README.md"}}""",
        """{"type":"tool_call","tool_name":"Task","arguments":{"description":"continue investigation","prompt":"Check the repo layout and report back.","subagent_type":"general-purpose"}}""",
        """{"type":"final","answer":"Layout inspected."}""",
        """{"type":"final","answer":"Delegated result received."}""",
      ),
    )
    val runtime = runtime(
      workspaceRoot = workspaceRoot,
      gateway = gateway,
    )

    val result = runtime.execute(
      task = promptTask("Investigate the codebase and continue carefully."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Delegated result received.", result.stdout)
    assertEquals(4, gateway.requests.size)
    assertTrue(gateway.requests[2].prompt.contains("recent_observations:"))
    assertTrue(gateway.requests[2].prompt.contains("Read file_path=README.md"))
    assertFalse(gateway.requests[2].prompt.contains("tool_result Read"))
    assertFalse(gateway.requests[2].prompt.contains("hello"))
  }

  @Test
  fun taskToolRejectsUnknownExplicitContextMode() {
    val workspaceRoot = temporaryFolder.newFolder("subagent-invalid-context-mode").toPath()
    val gateway = RecordingGateway(outputs = emptyList())
    val runtime = runtime(
      workspaceRoot = workspaceRoot,
      gateway = gateway,
    )

    val result = runtime.execute(
      task = directToolCallTask(
        """{"type":"tool_call","tool_name":"Task","arguments":{"description":"nested","prompt":"delegate again","subagent_type":"researcher","context_mode":"full"}}""",
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.FAILED, result.status)
    assertEquals("INVALID_SUBAGENT_TASK", result.errorCode)
    assertEquals(
      "Unknown Task context_mode 'full'. Expected one of: minimal, delegated, mirrored.",
      result.errorMessage,
    )
    assertEquals(0, gateway.requests.size)
  }

  @Test
  fun nestedTaskDelegationStopsAtConfiguredDepth() {
    val workspaceRoot = temporaryFolder.newFolder("subagent-depth").toPath()
    val gateway = RecordingGateway(outputs = emptyList())
    val runtime = runtime(
      workspaceRoot = workspaceRoot,
      gateway = gateway,
    )

    val result = runtime.execute(
      task = directToolCallTask(
        """{"type":"tool_call","tool_name":"Task","arguments":{"description":"nested","prompt":"delegate again","subagent_type":"researcher"}}""",
        metadata = mapOf(SubAgentMetadataKeys.DEPTH to "1"),
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.FAILED, result.status)
    assertEquals("SUBAGENT_DEPTH_EXCEEDED", result.errorCode)
    assertEquals(0, gateway.requests.size)
  }

  @Test
  fun taskToolEmitsParentAnchoredSubagentLifecycleEvents() {
    val workspaceRoot = temporaryFolder.newFolder("subagent-events").toPath()
    Files.write(
      workspaceRoot.resolve("README.md"),
      "hello".toByteArray(StandardCharsets.UTF_8),
    )
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"Task","arguments":{"description":"inspect readme","prompt":"Read README.md and summarize it.","subagent_type":"researcher"}}""",
        """{"type":"tool_call","tool_name":"Read","arguments":{"file_path":"README.md"}}""",
        """{"type":"final","answer":"README says hello."}""",
        """{"type":"final","answer":"Child summary: README says hello."}""",
      ),
    )
    val eventSink = RecordingEventSink()
    val runtime = runtime(
      workspaceRoot = workspaceRoot,
      gateway = gateway,
      eventSink = eventSink,
    )

    val result = runtime.execute(
      task = promptTask("Delegate the README inspection."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Child summary: README says hello.", result.stdout)
    assertEquals(
      listOf(OpenCraySubAgentPhase.STARTED, OpenCraySubAgentPhase.COMPLETED),
      eventSink.events
        .filterIsInstance<OpenCraySubAgentEvent>()
        .map(OpenCraySubAgentEvent::phase),
    )
    assertEquals(
      listOf("Task"),
      eventSink.events
        .filterIsInstance<OpenCrayToolCallEvent>()
        .map { event -> event.call.toolName },
    )
    assertEquals(
      listOf("Task"),
      eventSink.events
        .filterIsInstance<OpenCrayToolResultEvent>()
        .map { event -> event.result.toolName },
    )
    assertTrue(
      eventSink.events.none { event ->
        event is OpenCrayToolCallEvent && event.call.toolName == "Read"
      },
    )
  }

  private fun runtime(
    workspaceRoot: java.nio.file.Path,
    gateway: LiteLlmGateway,
    eventSink: OpenCrayAgentRuntimeEventSink = NoOpOpenCrayAgentRuntimeEventSink,
    sessionContext: AgentRuntimeSessionContext = AgentRuntimeSessionContext(),
  ): OpenCrayAgentRuntime = OpenCrayAgentRuntime(
    gateway = gateway,
    toolDispatcher = OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(workspaceRoot),
      ),
    ),
    config = OpenCrayAgentRuntimeConfig(
      maxTurns = 6,
      maxToolCalls = 0,
      sessionContext = sessionContext,
    ),
    eventSink = eventSink,
    clock = IncrementingClock(start = 10_000L)::next,
  )

  private fun promptTask(input: String): AgentTask = AgentTask(
    id = "prompt-task",
    type = AgentTaskType.PROMPT,
    input = input,
    policyDecision = PolicyDecision(
      outcome = PolicyDecisionOutcome.ALLOW,
      reasonCode = "ALLOW_ALL",
    ),
    createdAtEpochMs = 1_000L,
  )

  private fun directToolCallTask(
    input: String,
    metadata: Map<String, String> = emptyMap(),
  ): AgentTask = AgentTask(
    id = "tool-task",
    type = AgentTaskType.TOOL_CALL,
    input = input,
    policyDecision = PolicyDecision(
      outcome = PolicyDecisionOutcome.ALLOW,
      reasonCode = "ALLOW_ALL",
    ),
    createdAtEpochMs = 1_000L,
    metadata = metadata,
  )

  private fun runtimeHooks(): RuntimeExecutionHooks = RuntimeExecutionHooks(
    isCancellationRequested = { false },
    requestRetry = { _: RetryRequest -> error("Retry not expected in subagent runtime test.") },
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
}
