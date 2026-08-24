package com.opencray.runtime

import com.opencray.core.contracts.ExecutionStatus
import com.opencray.llm.LiteLlmAttemptOutcome
import com.opencray.llm.LiteLlmAttemptRecord
import com.opencray.llm.LiteLlmBuiltinWebSearchObservation
import com.opencray.llm.LiteLlmBuiltinWebSearchSource
import com.opencray.llm.LiteLlmBuiltinToolType
import com.opencray.llm.LiteLlmCompletionMode
import com.opencray.llm.LiteLlmGateway
import com.opencray.llm.LiteLlmGatewayRequest
import com.opencray.llm.LiteLlmGatewayResult
import com.opencray.llm.LiteLlmMetadataKeys
import com.opencray.llm.LiteLlmGatewayStatus
import com.opencray.llm.LiteLlmRouteSelectionMetadata
import com.opencray.llm.LiteLlmStructuredCompletion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive

class OpenCrayAgentRuntimeWebSearchRoutingTest : OpenCrayAgentRuntimeTestBase() {
  @Test
  fun runPromptTaskUsesResponsesBuiltinWebSearchForOpenAiProvider() {
    val workspaceRoot = temporaryFolder.newFolder("agent-native-web-search-workspace")
    val requests = mutableListOf<LiteLlmGatewayRequest>()
    val selection = LiteLlmRouteSelectionMetadata(
      profileId = "test-profile",
      routeId = "test-route",
      providerId = "openai",
      model = "gpt-5-mini",
      attemptIndex = 0,
    )
    val gateway = object : LiteLlmGateway {
      override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
        requests += request
        return LiteLlmGatewayResult(
          requestId = request.requestId,
          status = LiteLlmGatewayStatus.SUCCESS,
          completionMode = LiteLlmCompletionMode.PRIMARY,
          outputText = "Found it.",
          completion = LiteLlmStructuredCompletion(finalText = "Found it."),
          selectedRoute = selection,
          attempts = listOf(
            LiteLlmAttemptRecord(
              route = selection,
              outcome = LiteLlmAttemptOutcome.SUCCESS,
              outputChars = 8,
              startedAtEpochMs = 21_000L,
              finishedAtEpochMs = 21_001L,
            ),
          ),
          startedAtEpochMs = 21_000L,
          finishedAtEpochMs = 21_001L,
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
          "responsesContinuationSupported" to "true",
          "_host.providerId" to "openai",
        ),
      ),
      clock = IncrementingClock(start = 21_500L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Find the latest OpenAI docs."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals(1, requests.size)
    assertEquals(LiteLlmBuiltinToolType.WEB_SEARCH, requests.single().builtinTools.single().type)
    assertTrue(requests.single().builtinTools.single().includeSources)
    assertFalse(requests.single().tools.any { tool -> tool.name == "WebSearch" })
  }

  @Test
  fun runPromptTaskKeepsHostWebSearchForCustomResponsesProviderByDefault() {
    val workspaceRoot = temporaryFolder.newFolder("agent-host-web-search-workspace")
    val requests = mutableListOf<LiteLlmGatewayRequest>()
    val selection = LiteLlmRouteSelectionMetadata(
      profileId = "test-profile",
      routeId = "test-route",
      providerId = "custom",
      model = "gpt-5-mini",
      attemptIndex = 0,
    )
    val gateway = object : LiteLlmGateway {
      override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
        requests += request
        return LiteLlmGatewayResult(
          requestId = request.requestId,
          status = LiteLlmGatewayStatus.SUCCESS,
          completionMode = LiteLlmCompletionMode.PRIMARY,
          outputText = "Done.",
          completion = LiteLlmStructuredCompletion(finalText = "Done."),
          selectedRoute = selection,
          attempts = listOf(
            LiteLlmAttemptRecord(
              route = selection,
              outcome = LiteLlmAttemptOutcome.SUCCESS,
              outputChars = 5,
              startedAtEpochMs = 22_000L,
              finishedAtEpochMs = 22_001L,
            ),
          ),
          startedAtEpochMs = 22_000L,
          finishedAtEpochMs = 22_001L,
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
          "responsesContinuationSupported" to "true",
          "_host.providerId" to "custom",
          "_host.baseUrl" to "https://third-party.example/v1",
        ),
      ),
      clock = IncrementingClock(start = 22_500L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Look something up."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals(1, requests.size)
    assertTrue(requests.single().builtinTools.isEmpty())
    assertTrue(requests.single().tools.any { tool -> tool.name == "WebSearch" })
  }

  @Test
  fun runPromptTaskDisablesAllWebSearchWhenExplicitlyDisabled() {
    val workspaceRoot = temporaryFolder.newFolder("agent-web-search-disabled")
    val requests = mutableListOf<LiteLlmGatewayRequest>()
    val gateway = object : LiteLlmGateway {
      override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
        requests += request
        return gatewaySuccessResult(
          outputText = "Search is unavailable for this run.",
          completion = LiteLlmStructuredCompletion(finalText = "Search is unavailable for this run."),
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
          "responsesContinuationSupported" to "true",
          "webSearchEnabled" to "false",
          "_host.providerId" to "openai",
        ),
      ),
      clock = IncrementingClock(start = 23_500L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Find the latest OpenAI docs."),
      hooks = runtimeHooks(),
    )
    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals(1, requests.size)
    assertTrue(requests.single().builtinTools.isEmpty())
    assertFalse(requests.single().tools.any { tool -> tool.name == "WebSearch" })
  }

  @Test
  fun runPromptTaskEmitsSyntheticEventsForProviderNativeWebSearch() {
    val workspaceRoot = temporaryFolder.newFolder("agent-native-web-search-events")
    val observationJson = Json.encodeToString(
      ListSerializer(LiteLlmBuiltinWebSearchObservation.serializer()),
      listOf(
        LiteLlmBuiltinWebSearchObservation(
          actionType = "search",
          status = "completed",
          queries = listOf("OpenAI docs"),
          sources = listOf(
            LiteLlmBuiltinWebSearchSource(
              title = "OpenAI Docs",
              url = "https://platform.openai.com/docs",
            ),
          ),
        ),
      ),
    )
    val selection = LiteLlmRouteSelectionMetadata(
      profileId = "test-profile",
      routeId = "test-route",
      providerId = "openai",
      model = "gpt-5-mini",
      attemptIndex = 0,
    )
    val eventSink = RecordingEventSink()
    val gateway = object : LiteLlmGateway {
      override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult =
        LiteLlmGatewayResult(
          requestId = request.requestId,
          status = LiteLlmGatewayStatus.SUCCESS,
          completionMode = LiteLlmCompletionMode.PRIMARY,
          outputText = "Found it.",
          completion = LiteLlmStructuredCompletion(finalText = "Found it."),
          selectedRoute = selection,
          attempts = listOf(
            LiteLlmAttemptRecord(
              route = selection,
              outcome = LiteLlmAttemptOutcome.SUCCESS,
              outputChars = 8,
              startedAtEpochMs = 24_000L,
              finishedAtEpochMs = 24_001L,
            ),
          ),
          startedAtEpochMs = 24_000L,
          finishedAtEpochMs = 24_001L,
          metadata = mapOf(
            LiteLlmMetadataKeys.BUILTIN_WEB_SEARCH_USED to "true",
            LiteLlmMetadataKeys.BUILTIN_WEB_SEARCH_OBSERVATIONS_JSON to observationJson,
          ),
        )
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
          "responsesContinuationSupported" to "true",
          "_host.providerId" to "openai",
        ),
      ),
      eventSink = eventSink,
      clock = IncrementingClock(start = 24_500L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Find the latest OpenAI docs."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    val toolCall = eventSink.events
      .filterIsInstance<OpenCrayToolCallEvent>()
      .single { event -> event.call.toolName == "WebSearch" }
    val toolResult = eventSink.events
      .filterIsInstance<OpenCrayToolResultEvent>()
      .single { event -> event.result.toolName == "WebSearch" }
    assertEquals("search", toolCall.call.arguments["operation"]?.jsonPrimitive?.content)
    assertEquals("OpenAI docs", toolCall.call.arguments["query"]?.jsonPrimitive?.content)
    assertEquals(
      "https://platform.openai.com/docs",
      toolResult.result.metadata["sourceUrls"],
    )
    assertEquals(
      "true",
      toolResult.result.metadata[ProviderNativeWebSearchSupport.RESULT_METADATA_PROVIDER_MANAGED],
    )
    val promptResumeState = OpenCrayPromptResumeMetadata.decodeFromMetadata(
      metadata = toolResult.result.metadata,
      json = Json,
    )
    assertNotNull(promptResumeState)
    assertEquals(1, promptResumeState?.turnIndex)
    assertTrue(toolResult.result.content.contains("OpenAI Docs"))
  }

  @Test
  fun runPromptTaskKeepsHostWebSearchWhenResponsesBuiltinSearchIsExplicitlyDisabled() {
    val workspaceRoot = temporaryFolder.newFolder("agent-host-web-search-disabled-workspace")
    val requests = mutableListOf<LiteLlmGatewayRequest>()
    val selection = LiteLlmRouteSelectionMetadata(
      profileId = "test-profile",
      routeId = "test-route",
      providerId = "custom",
      model = "gpt-5-mini",
      attemptIndex = 0,
    )
    val gateway = object : LiteLlmGateway {
      override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
        requests += request
        return LiteLlmGatewayResult(
          requestId = request.requestId,
          status = LiteLlmGatewayStatus.SUCCESS,
          completionMode = LiteLlmCompletionMode.PRIMARY,
          outputText = "Done.",
          completion = LiteLlmStructuredCompletion(finalText = "Done."),
          selectedRoute = selection,
          attempts = listOf(
            LiteLlmAttemptRecord(
              route = selection,
              outcome = LiteLlmAttemptOutcome.SUCCESS,
              outputChars = 5,
              startedAtEpochMs = 23_000L,
              finishedAtEpochMs = 23_001L,
            ),
          ),
          startedAtEpochMs = 23_000L,
          finishedAtEpochMs = 23_001L,
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
          "responsesContinuationSupported" to "true",
          "_host.providerId" to "custom",
          "nativeWebSearchEnabled" to "false",
        ),
      ),
      clock = IncrementingClock(start = 23_500L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Look something up."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals(1, requests.size)
    assertTrue(requests.single().builtinTools.isEmpty())
    assertTrue(requests.single().tools.any { tool -> tool.name == "WebSearch" })
  }

  @Test
  fun runPromptTaskRequestsBuiltinWebSearchForOpenAiCompatibleRouteWhenEnabled() {
    val workspaceRoot = temporaryFolder.newFolder("agent-openai-builtin-web-search-workspace")
    val requests = mutableListOf<LiteLlmGatewayRequest>()
    val selection = LiteLlmRouteSelectionMetadata(
      profileId = "test-profile",
      routeId = "test-route",
      providerId = "custom",
      model = "glm-4.6",
      attemptIndex = 0,
    )
    val gateway = object : LiteLlmGateway {
      override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
        requests += request
        return LiteLlmGatewayResult(
          requestId = request.requestId,
          status = LiteLlmGatewayStatus.SUCCESS,
          completionMode = LiteLlmCompletionMode.PRIMARY,
          outputText = "Done.",
          completion = LiteLlmStructuredCompletion(finalText = "Done."),
          selectedRoute = selection,
          attempts = listOf(
            LiteLlmAttemptRecord(
              route = selection,
              outcome = LiteLlmAttemptOutcome.SUCCESS,
              outputChars = 5,
              startedAtEpochMs = 24_000L,
              finishedAtEpochMs = 24_001L,
            ),
          ),
          startedAtEpochMs = 24_000L,
          finishedAtEpochMs = 24_001L,
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
          "protocol" to "openai",
          "_host.providerId" to "custom",
          "nativeWebSearchEnabled" to "true",
        ),
      ),
      clock = IncrementingClock(start = 24_500L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Look something up."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals(1, requests.size)
    assertEquals(LiteLlmBuiltinToolType.WEB_SEARCH, requests.single().builtinTools.single().type)
    assertTrue(requests.single().tools.none { tool -> tool.name == "WebSearch" })
  }
}
