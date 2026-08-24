package com.opencray.runtime

import com.opencray.core.contracts.ExecutionStatus
import com.opencray.llm.LiteLlmGateway
import com.opencray.llm.LiteLlmGatewayRequest
import com.opencray.llm.LiteLlmGatewayResult
import com.opencray.runtime.subagent.InMemorySubAgentExecutionCoordinator
import com.opencray.runtime.subagent.SubAgentActiveExecution
import com.opencray.runtime.subagent.SubAgentContinuationKind
import com.opencray.runtime.subagent.SubAgentExecutionSnapshot
import com.opencray.runtime.subagent.SubAgentExecutionState
import com.opencray.runtime.subagent.SubAgentHandleState
import com.opencray.runtime.subagent.withClearedChildPromptCheckpoint
import com.opencray.runtime.subagent.withUpdatedChildPromptCheckpoint
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCrayAgentRuntimeRunEventsAndProcessesTest : OpenCrayAgentRuntimeTestBase() {
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
  fun interruptedGatewayExecutionEmitsCancelledLifecycleBeforeRethrow() {
    Thread.interrupted()
    val eventSink = RecordingEventSink()
    val runtime = OpenCrayAgentRuntime(
      gateway = object : LiteLlmGateway {
        override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
          throw InterruptedException("cancel requested")
        }
      },
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(temporaryFolder.newFolder("agent-interrupted-gateway").toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(maxTurns = 2),
      eventSink = eventSink,
      clock = IncrementingClock(start = 13_500L)::next,
    )

    try {
      var interruptedThrown = false
      try {
        runtime.execute(
          task = promptTask(input = "Wait for cancellation."),
          hooks = runtimeHooks(),
        )
      } catch (_: InterruptedException) {
        interruptedThrown = true
      }

      assertTrue(interruptedThrown)
      assertTrue(Thread.currentThread().isInterrupted)
      val terminalLifecycle = eventSink.events
        .filterIsInstance<OpenCrayLifecycleEvent>()
        .last()
      assertEquals(OpenCrayRunLifecyclePhase.CANCELLED, terminalLifecycle.phase)
      assertEquals("RUNTIME_INTERRUPTED", terminalLifecycle.errorCode)
      assertTrue(
        eventSink.events
          .filterIsInstance<OpenCrayLifecycleEvent>()
          .none { event -> event.phase == OpenCrayRunLifecyclePhase.ERROR },
      )
    } finally {
      Thread.interrupted()
    }
  }
}
