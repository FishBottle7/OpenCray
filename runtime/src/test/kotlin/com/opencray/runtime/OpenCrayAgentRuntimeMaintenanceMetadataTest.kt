package com.opencray.runtime

import com.opencray.core.contracts.ExecutionStatus
import com.opencray.llm.LiteLlmAttemptOutcome
import com.opencray.llm.LiteLlmAttemptRecord
import com.opencray.llm.LiteLlmCompletionMode
import com.opencray.llm.LiteLlmGatewayResult
import com.opencray.llm.LiteLlmGatewayStatus
import com.opencray.llm.LiteLlmRouteSelectionMetadata
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCrayAgentRuntimeMaintenanceMetadataTest : OpenCrayAgentRuntimeTestBase() {

  private fun scriptedSuccess(
    outputText: String,
    metadata: Map<String, String> = emptyMap(),
  ): LiteLlmGatewayResult {
    val selection = LiteLlmRouteSelectionMetadata(
      profileId = "test-profile",
      routeId = "test-route",
      providerId = "fake",
      model = "fake-model",
      attemptIndex = 0,
    )
    return LiteLlmGatewayResult(
      requestId = "scripted-metadata-success",
      status = LiteLlmGatewayStatus.SUCCESS,
      completionMode = LiteLlmCompletionMode.PRIMARY,
      outputText = outputText,
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
      metadata = metadata,
    )
  }

  @Test
  fun approvalResumeCountsResumedToolCallExactlyOnce() {
    val workspaceRoot = temporaryFolder.newFolder("approval-resume-count")
    val initialGateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"Write","arguments":{"file_path":"note.txt","content":"hello"}}""",
      ),
    )
    val task = promptTask(
      input = "Write a note in safe mode.",
      metadata = mapOf("chatMode" to "SAFE"),
    )
    val firstRuntime = OpenCrayAgentRuntime(
      gateway = initialGateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(maxTurns = 4, maxToolCalls = 2),
      clock = IncrementingClock(start = 9_000L)::next,
    )

    val firstResult = firstRuntime.execute(task = task, hooks = runtimeHooks())
    val resumeState = requireNotNull(
      OpenCrayPromptResumeMetadata.decodeFromMetadata(
        metadata = firstResult.metadata,
        json = Json { ignoreUnknownKeys = true },
      ),
    )

    assertEquals(ExecutionStatus.DENIED, firstResult.status)
    assertEquals("APPROVAL_REQUIRED", firstResult.errorCode)
    assertEquals(1, firstResult.metadata["toolCallCount"]?.toIntOrNull())
    assertEquals(0, resumeState.toolCallCount)

    val resumedGateway = RecordingGateway(
      outputs = listOf(
        """{"type":"final","answer":"Approved write completed."}""",
      ),
    )
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
      clock = IncrementingClock(start = 9_100L)::next,
    )

    val resumedResult = resumedRuntime.execute(task = task, hooks = runtimeHooks())

    assertEquals(ExecutionStatus.SUCCESS, resumedResult.status)
    assertEquals("Approved write completed.", resumedResult.stdout)
    assertEquals("1", resumedResult.metadata["toolCallCount"])
    assertTrue(Files.exists(workspaceRoot.toPath().resolve("note.txt")))
  }

  @Test
  fun providerMetadataCannotOverrideRuntimeComputedResultKeys() {
    val workspaceRoot = temporaryFolder.newFolder("provider-metadata-collision")
    val gateway = ScriptedGateway(
      results = listOf(
        scriptedSuccess(
          outputText = """{"type":"final","answer":"Provider metadata stays in its lane."}""",
          metadata = mapOf(
            "turnCount" to "999",
            "toolCallCount" to "999",
            "responseFormat" to "provider-hijacked",
            "customProviderTelemetry" to "provider-value",
          ),
        ),
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
      clock = IncrementingClock(start = 9_200L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Answer without tools."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Provider metadata stays in its lane.", result.stdout)
    assertEquals("1", result.metadata["turnCount"])
    assertEquals("0", result.metadata["toolCallCount"])
    assertEquals("json_final", result.metadata["responseFormat"])
    assertEquals("provider-value", result.metadata["customProviderTelemetry"])
    val endEvent = eventSink.events.filterIsInstance<OpenCrayLifecycleEvent>().last()
    assertEquals(OpenCrayRunLifecyclePhase.END, endEvent.phase)
    assertEquals(0, endEvent.turn)
  }

  @Test
  fun midTurnMaintenanceFailureIsObservableWithoutInterruptingRun() {
    val workspaceRoot = temporaryFolder.newFolder("maintenance-failure").toPath()
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"LS","arguments":{}}""",
        """{"type":"final","answer":"Recovered despite maintenance failure."}""",
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
      config = OpenCrayAgentRuntimeConfig(
        maxTurns = 4,
        maxToolCalls = 2,
        midTurnMaintenance = { _ ->
          throw IllegalStateException("maintenance exploded")
        },
      ),
      eventSink = eventSink,
      clock = IncrementingClock(start = 9_300L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "List the workspace and answer."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Recovered despite maintenance failure.", result.stdout)
    assertEquals("maintenance exploded", result.metadata["midTurnMaintenanceError"])
    val maintenanceErrorEvents = eventSink.events
      .filterIsInstance<OpenCraySupplementEvent>()
      .filter { event -> event.checkpoint == "mid_turn_maintenance_error" }
    assertEquals(1, maintenanceErrorEvents.size)
    assertEquals(
      "maintenance exploded",
      maintenanceErrorEvents.single().metadata["midTurnMaintenanceError"],
    )
  }

  @Test
  fun childRunDoesNotOverwriteParentWorkingStateStore() {
    val workspaceRoot = temporaryFolder.newFolder("child-working-state-isolation")
    Files.write(
      workspaceRoot.toPath().resolve("README.md"),
      "hello".toByteArray(StandardCharsets.UTF_8),
    )
    val workingStateStore = RecordingWorkingStateStore()
    val task = promptTask(input = "Please delegate README inspection and then answer.")
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"Task","arguments":{"description":"inspect readme","prompt":"Read README.md and summarize it.","subagent_type":"researcher"}}""",
        """{"type":"final","answer":"Child summary: README says hello."}""",
        """{"type":"final","answer":"Parent accepted the child summary."}""",
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
        maxToolCalls = 0,
        workingStateStore = workingStateStore,
      ),
      clock = IncrementingClock(start = 9_400L)::next,
    )

    val result = runtime.execute(task = task, hooks = runtimeHooks())

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Parent accepted the child summary.", result.stdout)
    assertTrue(workingStateStore.history.isNotEmpty())
    assertTrue(
      workingStateStore.history.all { state ->
        val historyTaskId = state.objective?.taskId
        historyTaskId == null || historyTaskId == task.id
      },
    )
    assertTrue(
      workingStateStore.history.none { state ->
        val historyRunId = state.objective?.runId
        historyRunId != null && historyRunId != runIdForParentPromptTask(task)
      },
    )
    val finalObjective = workingStateStore.snapshot().objective
    assertTrue(finalObjective?.taskId == null || finalObjective?.taskId == task.id)
  }

  private fun runIdForParentPromptTask(task: com.opencray.core.contracts.AgentTask): String = task.id
}
