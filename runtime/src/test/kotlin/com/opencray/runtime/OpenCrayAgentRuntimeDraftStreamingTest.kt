package com.opencray.runtime

import com.opencray.core.contracts.ExecutionStatus
import com.opencray.llm.LiteLlmAttemptOutcome
import com.opencray.llm.LiteLlmAttemptRecord
import com.opencray.llm.LiteLlmCompletionMode
import com.opencray.llm.LiteLlmGateway
import com.opencray.llm.LiteLlmGatewayRequest
import com.opencray.llm.LiteLlmGatewayResult
import com.opencray.llm.LiteLlmGatewayStatus
import com.opencray.llm.LiteLlmRouteSelectionMetadata
import com.opencray.llm.LiteLlmStructuredCompletion
import com.opencray.llm.LiteLlmStructuredToolCall
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class OpenCrayAgentRuntimeDraftStreamingTest : OpenCrayAgentRuntimeTestBase() {
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
}
