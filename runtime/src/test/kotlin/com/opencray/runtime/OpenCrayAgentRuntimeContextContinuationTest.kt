package com.opencray.runtime

import com.opencray.core.contracts.ExecutionStatus
import com.opencray.llm.LiteLlmAttemptOutcome
import com.opencray.llm.LiteLlmAttemptRecord
import com.opencray.llm.LiteLlmCompletionMode
import com.opencray.llm.LiteLlmGateway
import com.opencray.llm.LiteLlmGatewayMessageRole
import com.opencray.llm.LiteLlmGatewayRequest
import com.opencray.llm.LiteLlmGatewayResult
import com.opencray.llm.LiteLlmMetadataKeys
import com.opencray.llm.LiteLlmGatewayStatus
import com.opencray.llm.LiteLlmRouteSelectionMetadata
import com.opencray.llm.LiteLlmStructuredCompletion
import com.opencray.llm.LiteLlmStructuredToolCall
import com.opencray.runtime.context.AgentRuntimeSessionContext
import com.opencray.runtime.memory.MemoryKind
import com.opencray.runtime.memory.MemoryRecallResult
import com.opencray.runtime.memory.MemoryScope
import com.opencray.runtime.memory.MemoryStatus
import com.opencray.runtime.memory.MemoryToolContext
import com.opencray.runtime.memory.RetrievedMemory
import com.opencray.runtime.skills.SkillCatalogResolver
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray

class OpenCrayAgentRuntimeContextContinuationTest : OpenCrayAgentRuntimeTestBase() {
  @Test
  fun runPromptTaskFreezesProjectedToolReplayAcrossResumeAndFullRebuild() {
    val workspaceRoot = temporaryFolder.newFolder("agent-frozen-tool-replay")
    val attachmentPayload = "data:text/plain;base64," + "A".repeat(12_000)
    Files.write(
      workspaceRoot.toPath().resolve("blob.txt"),
      attachmentPayload.toByteArray(StandardCharsets.UTF_8),
    )
    val task = promptTask(input = "Read blob.txt and then answer.")
    val eventSink = RecordingEventSink()
    val initialGateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"workspace_read_file","arguments":{"path":"blob.txt"}}""",
        """{"type":"final","answer":"Read the blob."}""",
      ),
    )
    val initialRuntime = OpenCrayAgentRuntime(
      gateway = initialGateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(maxTurns = 4, maxToolCalls = 2),
      eventSink = eventSink,
      clock = IncrementingClock(start = 6_000L)::next,
    )

    val initialResult = initialRuntime.execute(
      task = task,
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, initialResult.status)
    assertEquals(2, initialGateway.requests.size)
    val initialProjectedToolResult = requireNotNull(
      initialGateway.requests[1].messages.firstOrNull { message ->
        message.role == LiteLlmGatewayMessageRole.TOOL &&
          message.toolResult?.toolName == "workspace_read_file"
      }?.toolResult,
    )
    assertTrue(initialProjectedToolResult.content.contains("[frozen replay preview]"))
    assertTrue(initialProjectedToolResult.content.contains("projection_reasons=attachment_like_content"))

    val checkpointState = requireNotNull(
      OpenCrayPromptResumeMetadata.decodeFromMetadata(
        metadata = eventSink.events
          .filterIsInstance<OpenCrayToolResultEvent>()
          .first()
          .result
          .metadata,
        json = Json { ignoreUnknownKeys = true },
      ),
    )
    assertEquals(1, checkpointState.replayToolResultProjections.size)
    assertTrue(checkpointState.transcript.last().content.contains(attachmentPayload.take(256)))
    assertEquals(
      initialProjectedToolResult.content,
      checkpointState.replayToolResultProjections.values.single().projectedToolResult.content,
    )

    val resumedGateway = RecordingGateway(
      outputs = listOf(
        """{"type":"final","answer":"Replayed the same frozen preview."}""",
      ),
    )
    val resumedRuntime = OpenCrayAgentRuntime(
      gateway = resumedGateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(
        maxTurns = 4,
        maxToolCalls = 2,
        promptResumeState = checkpointState.copy(localContinuationEnvelope = null),
      ),
      clock = IncrementingClock(start = 6_500L)::next,
    )

    val resumedResult = resumedRuntime.execute(
      task = task,
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, resumedResult.status)
    assertEquals("Replayed the same frozen preview.", resumedResult.stdout)
    assertEquals(1, resumedGateway.requests.size)
    assertEquals("full_rebuild", resumedGateway.requests.single().metadata["localContinuationMode"])
    assertEquals("no_envelope", resumedGateway.requests.single().metadata["localContinuationReason"])
    val resumedProjectedToolResult = requireNotNull(
      resumedGateway.requests.single().messages.firstOrNull { message ->
        message.role == LiteLlmGatewayMessageRole.TOOL &&
          message.toolResult?.toolName == "workspace_read_file"
      }?.toolResult,
    )
    assertEquals(initialProjectedToolResult.content, resumedProjectedToolResult.content)
  }

  @Test
  fun runPromptTaskFallsBackWhenLocalContinuationToolPoolFingerprintChanges() {
    assertLocalContinuationFingerprintFallback(
      expectedLocalContinuationReason = "tool_pool_changed",
      expectedContextCacheBreakReason = "tool_pool_changed",
    ) { envelope ->
      envelope.copy(toolPoolFingerprint = "mismatched-tool-pool")
    }
  }

  @Test
  fun runPromptTaskFallsBackWhenLocalContinuationToolSchemaFingerprintChanges() {
    assertLocalContinuationFingerprintFallback(
      expectedLocalContinuationReason = "tool_schema_changed",
      expectedContextCacheBreakReason = "tool_schema_changed",
    ) { envelope ->
      envelope.copy(toolSchemaFingerprint = "mismatched-tool-schema")
    }
  }

  @Test
  fun runPromptTaskFallsBackWhenLocalContinuationRequestSettingsFingerprintChanges() {
    assertLocalContinuationFingerprintFallback(
      expectedLocalContinuationReason = "user_setting_changed",
      expectedContextCacheBreakReason = "user_setting_changed",
    ) { envelope ->
      envelope.copy(requestSettingsFingerprint = "mismatched-request-settings")
    }
  }

  @Test
  fun runPromptTaskFallsBackWhenDurableContextZoneChanges() {
    assertLocalContinuationFingerprintFallback(
      expectedLocalContinuationReason = "durable_context_changed",
      expectedContextCacheBreakReason = "durable_context_changed",
    ) { envelope ->
      envelope.copy(
        frontContextPrompts = listOf("mismatched durable context") + envelope.frontContextPrompts.drop(1),
        durableContextPrompt = "mismatched durable context",
      )
    }
  }

  @Test
  fun runPromptTaskReplaysToolResultsAndSupplementsWithoutResponsesContinuation() {
    val workspaceRoot = temporaryFolder.newFolder("agent-responses-continuation-workspace")
    Files.write(
      workspaceRoot.toPath().resolve("README.md"),
      "hello from workspace".toByteArray(StandardCharsets.UTF_8),
    )

    val selection = LiteLlmRouteSelectionMetadata(
      profileId = "test-profile",
      routeId = "test-route",
      providerId = "openai",
      model = "gpt-5-mini",
      attemptIndex = 0,
    )
    val requests = mutableListOf<LiteLlmGatewayRequest>()
    var requestIndex = 0
    var supplementProviderCalls = 0
    val gateway = object : LiteLlmGateway {
      override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
        requests += request
        return if (requestIndex++ == 0) {
          LiteLlmGatewayResult(
            requestId = request.requestId,
            status = LiteLlmGatewayStatus.SUCCESS,
            completionMode = LiteLlmCompletionMode.PRIMARY,
            completion = LiteLlmStructuredCompletion(
              toolCalls = listOf(
                LiteLlmStructuredToolCall(
                  id = "call_1",
                  toolName = "workspace_read_file",
                  arguments = JsonObject(
                    mapOf("path" to JsonPrimitive("README.md")),
                  ),
                ),
              ),
            ),
            providerResponseId = "resp_1",
            selectedRoute = selection,
            attempts = listOf(
              LiteLlmAttemptRecord(
                route = selection,
                outcome = LiteLlmAttemptOutcome.SUCCESS,
                outputChars = 0,
                startedAtEpochMs = 15_000L,
                finishedAtEpochMs = 15_001L,
              ),
            ),
            startedAtEpochMs = 15_000L,
            finishedAtEpochMs = 15_001L,
          )
        } else {
          LiteLlmGatewayResult(
            requestId = request.requestId,
            status = LiteLlmGatewayStatus.SUCCESS,
            completionMode = LiteLlmCompletionMode.PRIMARY,
            completion = LiteLlmStructuredCompletion(
              finalText = "I saw the continuation.",
            ),
            providerResponseId = "resp_2",
            selectedRoute = selection,
            attempts = listOf(
              LiteLlmAttemptRecord(
                route = selection,
                outcome = LiteLlmAttemptOutcome.SUCCESS,
                outputChars = 0,
                startedAtEpochMs = 15_002L,
                finishedAtEpochMs = 15_003L,
              ),
            ),
            startedAtEpochMs = 15_002L,
            finishedAtEpochMs = 15_003L,
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
      config = OpenCrayAgentRuntimeConfig(
        maxTurns = 4,
        maxToolCalls = 2,
        supplementInputProvider = { _, _ ->
          supplementProviderCalls += 1
          if (supplementProviderCalls == 2) {
            listOf(
              OpenCraySupplementInput(
                entryId = "supplement-1",
                text = "Also verify the tests before you answer.",
                createdAtEpochMs = 1_500L,
              ),
            )
          } else {
            emptyList()
          }
        },
        llmMetadata = mapOf(
          "responseApiPreferred" to "true",
          "nativeToolCallingAvailable" to "true",
        ),
      ),
      clock = IncrementingClock(start = 15_500L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Read the README and then answer."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("I saw the continuation.", result.stdout)
    assertEquals(2, requests.size)
    assertEquals(null, requests[0].previousResponseId)
    assertEquals(null, requests[1].previousResponseId)
    assertTrue(requests[1].responseApiPreferred)
    val secondTurnUserMessages = requests[1].messages
      .filter { message -> message.role == LiteLlmGatewayMessageRole.USER }
      .mapNotNull { message -> message.content }
    assertTrue(secondTurnUserMessages.contains("Read the README and then answer."))
    assertTrue(secondTurnUserMessages.contains("Also verify the tests before you answer."))
    val replayedToolCall = requests[1].messages
      .firstOrNull { message ->
        message.role == LiteLlmGatewayMessageRole.ASSISTANT &&
          message.toolCalls.singleOrNull()?.id == "call_1"
      }
    assertNotNull(replayedToolCall)
    assertEquals(
      "workspace_read_file",
      replayedToolCall?.toolCalls?.singleOrNull()?.toolName,
    )
    val replayedToolResult = requests[1].messages
      .firstOrNull { message -> message.role == LiteLlmGatewayMessageRole.TOOL }
      ?.toolResult
    assertNotNull(replayedToolResult)
    assertEquals("call_1", replayedToolResult?.toolCallId)
    assertEquals("workspace_read_file", replayedToolResult?.toolName)
    assertTrue(replayedToolResult?.content?.contains("hello from workspace") == true)
  }

  @Test
  fun runPromptTaskApprovalResumeFallsBackToFullTranscriptReplay() {
    val workspaceRoot = temporaryFolder.newFolder("agent-responses-approval-resume")
    val selection = LiteLlmRouteSelectionMetadata(
      profileId = "test-profile",
      routeId = "responses-route",
      providerId = "openai",
      model = "gpt-5-mini",
      attemptIndex = 0,
    )
    val initialGatewayRequests = mutableListOf<LiteLlmGatewayRequest>()
    val initialGateway = object : LiteLlmGateway {
      override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
        initialGatewayRequests += request
        return LiteLlmGatewayResult(
          requestId = request.requestId,
          status = LiteLlmGatewayStatus.SUCCESS,
          completionMode = LiteLlmCompletionMode.PRIMARY,
          completion = LiteLlmStructuredCompletion(
            toolCalls = listOf(
              LiteLlmStructuredToolCall(
                id = "call_1",
                toolName = "Write",
                arguments = JsonObject(
                  mapOf(
                    "file_path" to JsonPrimitive("note.txt"),
                    "content" to JsonPrimitive("hello"),
                  ),
                ),
              ),
            ),
          ),
          providerResponseId = "resp_1",
          providerLineageId = "lineage_1",
          selectedRoute = selection,
          attempts = listOf(
            LiteLlmAttemptRecord(
              route = selection,
              outcome = LiteLlmAttemptOutcome.SUCCESS,
              outputChars = 0,
              startedAtEpochMs = 40_000L,
              finishedAtEpochMs = 40_001L,
            ),
          ),
          startedAtEpochMs = 40_000L,
          finishedAtEpochMs = 40_001L,
        )
      }
    }
    val task = promptTask(
      input = "Write note.txt in safe mode.",
      metadata = mapOf("chatMode" to "SAFE"),
    )
    val initialRuntime = OpenCrayAgentRuntime(
      gateway = initialGateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(
        maxTurns = 4,
        maxToolCalls = 2,
        llmMetadata = mapOf(
          "protocol" to "openai_responses",
          "responseApiPreferred" to "true",
          "nativeToolCallingAvailable" to "true",
          "responsesContinuationSupported" to "true",
          "nativeWebSearchEnabled" to "false",
        ),
      ),
      clock = IncrementingClock(start = 40_500L)::next,
    )

    val firstResult = initialRuntime.execute(
      task = task,
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.DENIED, firstResult.status)
    assertEquals(1, initialGatewayRequests.size)
    val resumeState = requireNotNull(
      OpenCrayPromptResumeMetadata.decodeFromMetadata(
        metadata = firstResult.metadata,
        json = Json { ignoreUnknownKeys = true },
      ),
    )
    assertEquals("resp_1", resumeState.responsesPreviousResponseId)
    assertEquals("lineage_1", resumeState.responsesProviderLineageId)
    assertEquals(true, resumeState.responsesLineageTrusted)
    assertTrue(resumeState.responsesPendingMessages.isEmpty())

    val resumedGatewayRequests = mutableListOf<LiteLlmGatewayRequest>()
    val resumedGateway = object : LiteLlmGateway {
      override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
        resumedGatewayRequests += request
        return LiteLlmGatewayResult(
          requestId = request.requestId,
          status = LiteLlmGatewayStatus.SUCCESS,
          completionMode = LiteLlmCompletionMode.PRIMARY,
          completion = LiteLlmStructuredCompletion(
            finalText = "Approved write completed.",
          ),
          providerResponseId = "resp_2",
          providerLineageId = "lineage_2",
          selectedRoute = selection,
          attempts = listOf(
            LiteLlmAttemptRecord(
              route = selection,
              outcome = LiteLlmAttemptOutcome.SUCCESS,
              outputChars = 0,
              startedAtEpochMs = 40_010L,
              finishedAtEpochMs = 40_011L,
            ),
          ),
          startedAtEpochMs = 40_010L,
          finishedAtEpochMs = 40_011L,
        )
      }
    }
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
        llmMetadata = mapOf(
          "protocol" to "openai_responses",
          "responseApiPreferred" to "true",
          "nativeToolCallingAvailable" to "true",
          "responsesContinuationSupported" to "true",
          "nativeWebSearchEnabled" to "false",
        ),
      ),
      clock = IncrementingClock(start = 40_600L)::next,
    )

    val resumedResult = resumedRuntime.execute(
      task = task,
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, resumedResult.status)
    assertEquals("Approved write completed.", resumedResult.stdout)
    assertEquals(1, resumedGatewayRequests.size)
    assertNull(resumedGatewayRequests.single().previousResponseId)
    assertEquals("full_rebuild", resumedGatewayRequests.single().metadata["localContinuationMode"])
    assertEquals(
      "responses_pending_tool_result_attachment_artifact",
      resumedGatewayRequests.single().metadata["localContinuationReason"],
    )
    assertTrue(
      resumedGatewayRequests.single().messages.any { message ->
        message.role == LiteLlmGatewayMessageRole.ASSISTANT &&
          message.toolCalls.any { toolCall ->
            toolCall.id == "call_1" && toolCall.toolName == "Write"
          }
      },
    )
    assertTrue(
      resumedGatewayRequests.single().messages.any { message ->
        val toolResult = message.toolResult
        message.role == LiteLlmGatewayMessageRole.TOOL &&
          toolResult?.toolCallId == "call_1" &&
          toolResult.toolName == "Write"
      },
    )
  }

  @Test
  fun responsesContinuationUsesNativeDeltaWhenPendingToolResultKeepsPromptShapeStable() {
    val workspaceRoot = temporaryFolder.newFolder("agent-responses-native-delta")
    val selection = LiteLlmRouteSelectionMetadata(
      profileId = "test-profile",
      routeId = "responses-route",
      providerId = "openai",
      model = "gpt-5-mini",
      attemptIndex = 0,
    )
    val gatewayRequests = mutableListOf<LiteLlmGatewayRequest>()
    var requestIndex = 0
    val gateway = object : LiteLlmGateway {
      override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
        gatewayRequests += request
        return if (requestIndex++ == 0) {
          LiteLlmGatewayResult(
            requestId = request.requestId,
            status = LiteLlmGatewayStatus.SUCCESS,
            completionMode = LiteLlmCompletionMode.PRIMARY,
            completion = LiteLlmStructuredCompletion(
              toolCalls = listOf(
                LiteLlmStructuredToolCall(
                  id = "call_1",
                  toolName = "memory_search",
                  arguments = JsonObject(
                    mapOf("query" to JsonPrimitive("repo root")),
                  ),
                ),
              ),
            ),
            providerResponseId = "resp_native_1",
            providerLineageId = "lineage_native_1",
            selectedRoute = selection,
            attempts = listOf(
              LiteLlmAttemptRecord(
                route = selection,
                outcome = LiteLlmAttemptOutcome.SUCCESS,
                outputChars = 0,
                startedAtEpochMs = 49_000L,
                finishedAtEpochMs = 49_001L,
              ),
            ),
            startedAtEpochMs = 49_000L,
            finishedAtEpochMs = 49_001L,
          )
        } else {
          LiteLlmGatewayResult(
            requestId = request.requestId,
            status = LiteLlmGatewayStatus.SUCCESS,
            completionMode = LiteLlmCompletionMode.PRIMARY,
            completion = LiteLlmStructuredCompletion(
              finalText = "Used provider-native continuation.",
            ),
            providerResponseId = "resp_native_2",
            providerLineageId = "lineage_native_2",
            selectedRoute = selection,
            attempts = listOf(
              LiteLlmAttemptRecord(
                route = selection,
                outcome = LiteLlmAttemptOutcome.SUCCESS,
                outputChars = 0,
                startedAtEpochMs = 49_002L,
                finishedAtEpochMs = 49_003L,
              ),
            ),
            startedAtEpochMs = 49_002L,
            finishedAtEpochMs = 49_003L,
          )
        }
      }
    }
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
          memoryToolContext = MemoryToolContext(
            sessionId = "session-main",
            workspaceId = "workspace-main",
          ),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(
        maxTurns = 4,
        maxToolCalls = 2,
        llmMetadata = mapOf(
          "protocol" to "openai_responses",
          "responseApiPreferred" to "true",
          "nativeToolCallingAvailable" to "true",
          "responsesContinuationSupported" to "true",
          "nativeWebSearchEnabled" to "false",
        ),
      ),
      clock = IncrementingClock(start = 49_500L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Search memory and then answer."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Used provider-native continuation.", result.stdout)
    assertEquals(2, gatewayRequests.size)
    assertNull(gatewayRequests[0].previousResponseId)
    assertEquals("resp_native_1", gatewayRequests[1].previousResponseId)
    assertEquals("responses_native", gatewayRequests[1].metadata["localContinuationMode"])
    assertEquals("responses_previous_response_id", gatewayRequests[1].metadata["localContinuationReason"])
    assertEquals("responses_native", result.metadata["localContinuationLastMode"])
    assertEquals("responses_previous_response_id", result.metadata["localContinuationLastReason"])
    assertEquals(1, gatewayRequests[1].messages.size)
    val toolResult = requireNotNull(gatewayRequests[1].messages.single().toolResult)
    assertEquals("call_1", toolResult.toolCallId)
    assertEquals("memory_search", toolResult.toolName)
    assertTrue(toolResult.content.contains("No matching projected memory snippets were found."))
  }

  @Test
  fun responsesContinuationUsesNativeDeltaForWorkspaceDiscoveryOwnedByReplay() {
    val workspaceRoot = temporaryFolder.newFolder("agent-responses-native-workspace-replay")
    Files.write(
      workspaceRoot.toPath().resolve("README.md"),
      "hello from workspace".toByteArray(StandardCharsets.UTF_8),
    )
    val selection = LiteLlmRouteSelectionMetadata(
      profileId = "test-profile",
      routeId = "responses-route",
      providerId = "openai",
      model = "gpt-5-mini",
      attemptIndex = 0,
    )
    val gatewayRequests = mutableListOf<LiteLlmGatewayRequest>()
    var requestIndex = 0
    val gateway = object : LiteLlmGateway {
      override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
        gatewayRequests += request
        return if (requestIndex++ == 0) {
          LiteLlmGatewayResult(
            requestId = request.requestId,
            status = LiteLlmGatewayStatus.SUCCESS,
            completionMode = LiteLlmCompletionMode.PRIMARY,
            completion = LiteLlmStructuredCompletion(
              toolCalls = listOf(
                LiteLlmStructuredToolCall(
                  id = "call_1",
                  toolName = "LS",
                  arguments = JsonObject(
                    mapOf("path" to JsonPrimitive(".")),
                  ),
                ),
              ),
            ),
            providerResponseId = "resp_native_workspace_1",
            providerLineageId = "lineage_native_workspace_1",
            selectedRoute = selection,
            attempts = listOf(
              LiteLlmAttemptRecord(
                route = selection,
                outcome = LiteLlmAttemptOutcome.SUCCESS,
                outputChars = 0,
                startedAtEpochMs = 49_500L,
                finishedAtEpochMs = 49_501L,
              ),
            ),
            startedAtEpochMs = 49_500L,
            finishedAtEpochMs = 49_501L,
          )
        } else {
          LiteLlmGatewayResult(
            requestId = request.requestId,
            status = LiteLlmGatewayStatus.SUCCESS,
            completionMode = LiteLlmCompletionMode.PRIMARY,
            completion = LiteLlmStructuredCompletion(
              finalText = "Reused provider-native continuation for LS.",
            ),
            providerResponseId = "resp_native_workspace_2",
            providerLineageId = "lineage_native_workspace_2",
            selectedRoute = selection,
            attempts = listOf(
              LiteLlmAttemptRecord(
                route = selection,
                outcome = LiteLlmAttemptOutcome.SUCCESS,
                outputChars = 0,
                startedAtEpochMs = 49_502L,
                finishedAtEpochMs = 49_503L,
              ),
            ),
            startedAtEpochMs = 49_502L,
            finishedAtEpochMs = 49_503L,
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
      config = OpenCrayAgentRuntimeConfig(
        maxTurns = 4,
        maxToolCalls = 2,
        llmMetadata = mapOf(
          "protocol" to "openai_responses",
          "responseApiPreferred" to "true",
          "nativeToolCallingAvailable" to "true",
          "responsesContinuationSupported" to "true",
          "nativeWebSearchEnabled" to "false",
        ),
      ),
      clock = IncrementingClock(start = 49_900L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "List the workspace and then answer."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Reused provider-native continuation for LS.", result.stdout)
    assertEquals(2, gatewayRequests.size)
    assertNull(gatewayRequests[0].previousResponseId)
    assertEquals("resp_native_workspace_1", gatewayRequests[1].previousResponseId)
    assertEquals("responses_native", gatewayRequests[1].metadata["localContinuationMode"])
    assertEquals("responses_previous_response_id", gatewayRequests[1].metadata["localContinuationReason"])
    assertEquals("responses_native", result.metadata["localContinuationLastMode"])
    assertEquals("responses_previous_response_id", result.metadata["localContinuationLastReason"])
    assertEquals(1, gatewayRequests[1].messages.size)
    val toolResult = requireNotNull(gatewayRequests[1].messages.single().toolResult)
    assertEquals("call_1", toolResult.toolCallId)
    assertEquals("LS", toolResult.toolName)
    assertTrue(toolResult.content.contains("README.md"))
  }

  @Test
  fun responsesContinuationFallsBackToTranscriptReplayWhenLegacyJsonFallbackIsEnabled() {
    val workspaceRoot = temporaryFolder.newFolder("agent-responses-continuation-replay")
    val selection = LiteLlmRouteSelectionMetadata(
      profileId = "test-profile",
      routeId = "responses-route",
      providerId = "openai",
      model = "gpt-5-mini",
      attemptIndex = 0,
    )
    val gatewayRequests = mutableListOf<LiteLlmGatewayRequest>()
    var requestIndex = 0
    val gateway = object : LiteLlmGateway {
      override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
        gatewayRequests += request
        return when (requestIndex++) {
          0 -> LiteLlmGatewayResult(
            requestId = request.requestId,
            status = LiteLlmGatewayStatus.SUCCESS,
            completionMode = LiteLlmCompletionMode.PRIMARY,
            outputText = """
            {
              "type": "tool_call",
              "id": "call_1",
              "tool_name": "LS",
              "arguments": {
                "path": "."
              }
            }
            """.trimIndent(),
            providerResponseId = "resp_cont_1",
            providerLineageId = "lineage_cont_1",
            selectedRoute = selection,
            attempts = listOf(
              LiteLlmAttemptRecord(
                route = selection,
                outcome = LiteLlmAttemptOutcome.SUCCESS,
                outputChars = 0,
                startedAtEpochMs = 50_000L,
                finishedAtEpochMs = 50_001L,
              ),
            ),
            startedAtEpochMs = 50_000L,
            finishedAtEpochMs = 50_001L,
          )

          else -> LiteLlmGatewayResult(
            requestId = request.requestId,
            status = LiteLlmGatewayStatus.SUCCESS,
            completionMode = LiteLlmCompletionMode.PRIMARY,
            completion = LiteLlmStructuredCompletion(
              finalText = "Replayed without provider-native continuation.",
            ),
            providerResponseId = "resp_cont_2",
            providerLineageId = "lineage_cont_2",
            selectedRoute = selection,
            attempts = listOf(
              LiteLlmAttemptRecord(
                route = selection,
                outcome = LiteLlmAttemptOutcome.SUCCESS,
                outputChars = 0,
                startedAtEpochMs = 50_004L,
                finishedAtEpochMs = 50_005L,
              ),
            ),
            startedAtEpochMs = 50_004L,
            finishedAtEpochMs = 50_005L,
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
      config = OpenCrayAgentRuntimeConfig(
        maxTurns = 4,
        maxToolCalls = 2,
        llmMetadata = mapOf(
          "protocol" to "openai_responses",
          "responseApiPreferred" to "true",
          "nativeToolCallingAvailable" to "true",
          "responsesContinuationSupported" to "true",
          "nativeWebSearchEnabled" to "false",
        ),
      ),
      eventSink = eventSink,
      clock = IncrementingClock(start = 50_500L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "List the workspace and answer."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Replayed without provider-native continuation.", result.stdout)
    assertEquals("0", result.metadata["responsesContinuationRecoveryCount"])
    assertEquals(null, result.metadata["responsesContinuationRecoveryLastReason"])
    assertEquals(2, gatewayRequests.size)
    assertNull(gatewayRequests[0].previousResponseId)
    assertNull(gatewayRequests[1].previousResponseId)
    assertEquals("full_rebuild", gatewayRequests[1].metadata["localContinuationMode"])
    assertEquals(
      "responses_legacy_json_fallback_enabled",
      gatewayRequests[1].metadata["localContinuationReason"],
    )
    assertEquals(
      "responses_legacy_json_fallback_enabled",
      result.metadata["localContinuationLastReason"],
    )
    assertTrue(
      gatewayRequests[1].messages.any { message ->
        message.role == LiteLlmGatewayMessageRole.ASSISTANT &&
          message.toolCalls.any { toolCall ->
            toolCall.id == "call_1" && toolCall.toolName == "LS"
          }
      },
    )
    assertTrue(
      gatewayRequests[1].messages.any { message ->
        val toolResult = message.toolResult
        message.role == LiteLlmGatewayMessageRole.TOOL &&
        toolResult?.toolCallId == "call_1" &&
          toolResult.toolName == "LS"
      },
    )
    assertTrue(
      eventSink.events.none { event ->
        event is OpenCrayAssistantEvent && event.stage == "responses_recovery"
      },
    )
  }

  @Test
  fun responsesContinuationAppendsContextUpdateWhenTodoWriteChangesWorkingState() {
    val workspaceRoot = temporaryFolder.newFolder("agent-responses-working-state-update")
    val todoStore = InMemoryAgentTodoStore()
    val selection = LiteLlmRouteSelectionMetadata(
      profileId = "test-profile",
      routeId = "responses-route",
      providerId = "openai",
      model = "gpt-5-mini",
      attemptIndex = 0,
    )
    val gatewayRequests = mutableListOf<LiteLlmGatewayRequest>()
    var requestIndex = 0
    val gateway = object : LiteLlmGateway {
      override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
        gatewayRequests += request
        return if (requestIndex++ == 0) {
          LiteLlmGatewayResult(
            requestId = request.requestId,
            status = LiteLlmGatewayStatus.SUCCESS,
            completionMode = LiteLlmCompletionMode.PRIMARY,
            completion = LiteLlmStructuredCompletion(
              toolCalls = listOf(
                LiteLlmStructuredToolCall(
                  id = "call_1",
                  toolName = "TodoWrite",
                  arguments = JsonObject(
                    mapOf(
                      "todos" to Json.parseToJsonElement(
                        """
                        [
                          {
                            "content": "Inspect README",
                            "status": "completed"
                          }
                        ]
                        """.trimIndent(),
                      ).jsonArray,
                    ),
                  ),
                ),
              ),
            ),
            providerResponseId = "resp_working_1",
            providerLineageId = "lineage_working_1",
            selectedRoute = selection,
            attempts = listOf(
              LiteLlmAttemptRecord(
                route = selection,
                outcome = LiteLlmAttemptOutcome.SUCCESS,
                outputChars = 0,
                startedAtEpochMs = 53_000L,
                finishedAtEpochMs = 53_001L,
              ),
            ),
            startedAtEpochMs = 53_000L,
            finishedAtEpochMs = 53_001L,
          )
        } else {
          LiteLlmGatewayResult(
            requestId = request.requestId,
            status = LiteLlmGatewayStatus.SUCCESS,
            completionMode = LiteLlmCompletionMode.PRIMARY,
            completion = LiteLlmStructuredCompletion(
              finalText = "Continued after working state changed.",
            ),
            providerResponseId = "resp_working_2",
            providerLineageId = "lineage_working_2",
            selectedRoute = selection,
            attempts = listOf(
              LiteLlmAttemptRecord(
                route = selection,
                outcome = LiteLlmAttemptOutcome.SUCCESS,
                outputChars = 0,
                startedAtEpochMs = 53_002L,
                finishedAtEpochMs = 53_003L,
              ),
            ),
            startedAtEpochMs = 53_002L,
            finishedAtEpochMs = 53_003L,
          )
        }
      }
    }
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
          todoStore = todoStore,
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(
        maxTurns = 4,
        maxToolCalls = 2,
        llmMetadata = mapOf(
          "protocol" to "openai_responses",
          "responseApiPreferred" to "true",
          "nativeToolCallingAvailable" to "true",
          "responsesContinuationSupported" to "true",
          "nativeWebSearchEnabled" to "false",
        ),
      ),
      clock = IncrementingClock(start = 53_500L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Keep the todo list up to date."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Continued after working state changed.", result.stdout)
    assertEquals(2, gatewayRequests.size)
    assertEquals("resp_working_1", gatewayRequests[1].previousResponseId)
    assertEquals("responses_native", gatewayRequests[1].metadata["localContinuationMode"])
    assertEquals("responses_previous_response_id", gatewayRequests[1].metadata["localContinuationReason"])
    assertNull(gatewayRequests[1].metadata[LiteLlmMetadataKeys.CONTEXT_CACHE_BREAK_REASON])
    assertEquals("1", gatewayRequests[1].metadata["responsesPendingContextUpdateCount"])
    assertNotNull(gatewayRequests[1].metadata["responsesPendingContextUpdateHash"])
    assertEquals("1", result.metadata["responsesPendingContextUpdateCount"])
    assertNotNull(result.metadata["responsesPendingContextUpdateHash"])
    assertEquals("1", result.metadata["localContinuationUsedCount"])
    assertEquals(2, gatewayRequests[1].messages.size)
    assertTrue(
      gatewayRequests[1].messages.any { message ->
        message.role == LiteLlmGatewayMessageRole.TOOL &&
          message.toolResult?.toolCallId == "call_1" &&
          message.toolResult?.toolName == "TodoWrite"
      },
    )
    val messageText = gatewayStructuredPayloadText(gatewayRequests[1])
    assertTrue(messageText.contains("[OpenCray Context Update]"))
    assertTrue(messageText.contains("zone=dynamic_operational"))
    assertTrue(messageText.contains("[Working State]"))
    assertTrue(messageText.contains("primary_goal=Keep the todo list up to date"))
    assertTrue(messageText.contains("TodoWrite todos=1 changed=true"))
    assertFalse(messageText.contains("[Recent Working Observations]"))
    assertEquals(
      listOf(
        AgentTodoEntry(
          content = "Inspect README",
          status = AgentTodoStatus.COMPLETED,
        ),
      ),
      todoStore.snapshot(),
    )
  }

  @Test
  fun responsesContinuationAppendsContextUpdateWhenOrdinaryMemoryRecallChanges() {
    val workspaceRoot = temporaryFolder.newFolder("agent-responses-memory-recall-update")
    Files.write(
      workspaceRoot.toPath().resolve("README.md"),
      "hello from workspace".toByteArray(StandardCharsets.UTF_8),
    )
    val selection = LiteLlmRouteSelectionMetadata(
      profileId = "test-profile",
      routeId = "responses-route",
      providerId = "openai",
      model = "gpt-5-mini",
      attemptIndex = 0,
    )
    val gatewayRequests = mutableListOf<LiteLlmGatewayRequest>()
    var requestIndex = 0
    val gateway = object : LiteLlmGateway {
      override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
        gatewayRequests += request
        return if (requestIndex++ == 0) {
          LiteLlmGatewayResult(
            requestId = request.requestId,
            status = LiteLlmGatewayStatus.SUCCESS,
            completionMode = LiteLlmCompletionMode.PRIMARY,
            completion = LiteLlmStructuredCompletion(
              toolCalls = listOf(
                LiteLlmStructuredToolCall(
                  id = "call_1",
                  toolName = "LS",
                  arguments = JsonObject(mapOf("path" to JsonPrimitive("."))),
                ),
              ),
            ),
            providerResponseId = "resp_memory_recall_1",
            providerLineageId = "lineage_memory_recall_1",
            selectedRoute = selection,
            attempts = listOf(
              LiteLlmAttemptRecord(
                route = selection,
                outcome = LiteLlmAttemptOutcome.SUCCESS,
                outputChars = 0,
                startedAtEpochMs = 53_600L,
                finishedAtEpochMs = 53_601L,
              ),
            ),
            startedAtEpochMs = 53_600L,
            finishedAtEpochMs = 53_601L,
          )
        } else {
          LiteLlmGatewayResult(
            requestId = request.requestId,
            status = LiteLlmGatewayStatus.SUCCESS,
            completionMode = LiteLlmCompletionMode.PRIMARY,
            completion = LiteLlmStructuredCompletion(
              finalText = "Continued after dynamic memory recall changed.",
            ),
            providerResponseId = "resp_memory_recall_2",
            providerLineageId = "lineage_memory_recall_2",
            selectedRoute = selection,
            attempts = listOf(
              LiteLlmAttemptRecord(
                route = selection,
                outcome = LiteLlmAttemptOutcome.SUCCESS,
                outputChars = 0,
                startedAtEpochMs = 53_602L,
                finishedAtEpochMs = 53_603L,
              ),
            ),
            startedAtEpochMs = 53_602L,
            finishedAtEpochMs = 53_603L,
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
      config = OpenCrayAgentRuntimeConfig(
        maxTurns = 4,
        maxToolCalls = 2,
        sessionContext = AgentRuntimeSessionContext(
          recalledMemory = MemoryRecallResult(
            memories = listOf(
              RetrievedMemory(
                id = "memory-initial",
                kind = MemoryKind.PROJECT_FACT,
                scope = MemoryScope.WORKSPACE,
                status = MemoryStatus.ACTIVE,
                content = "Initial dynamic recall should not become a Responses baseline.",
                lastConfirmedAtEpochMs = 1_000L,
                score = 100,
              ),
            ),
          ),
        ),
        midTurnMaintenance = { request ->
          OpenCrayMidTurnMaintenanceResult(
            sessionContext = request.sessionContext.copy(
              recalledMemory = MemoryRecallResult(
                memories = listOf(
                  RetrievedMemory(
                    id = "memory-updated",
                    kind = MemoryKind.PROJECT_FACT,
                    scope = MemoryScope.WORKSPACE,
                    status = MemoryStatus.ACTIVE,
                    content = "Updated ordinary dynamic recall travels as a context update.",
                    lastConfirmedAtEpochMs = 2_000L,
                    score = 200,
                  ),
                ),
              ),
            ),
          )
        },
        llmMetadata = mapOf(
          "protocol" to "openai_responses",
          "responseApiPreferred" to "true",
          "nativeToolCallingAvailable" to "true",
          "responsesContinuationSupported" to "true",
          "nativeWebSearchEnabled" to "false",
        ),
      ),
      clock = IncrementingClock(start = 53_700L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Use relevant memory, list the workspace, then answer."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Continued after dynamic memory recall changed.", result.stdout)
    assertEquals(2, gatewayRequests.size)
    assertEquals("resp_memory_recall_1", gatewayRequests[1].previousResponseId)
    assertEquals("responses_native", gatewayRequests[1].metadata["localContinuationMode"])
    assertEquals("responses_previous_response_id", gatewayRequests[1].metadata["localContinuationReason"])
    assertEquals("1", gatewayRequests[1].metadata["responsesPendingContextUpdateCount"])
    assertNull(gatewayRequests[1].metadata[LiteLlmMetadataKeys.CONTEXT_CACHE_BREAK_REASON])
    val messageText = gatewayStructuredPayloadText(gatewayRequests[1])
    assertTrue(messageText.contains("[OpenCray Context Update]"))
    assertTrue(messageText.contains("[Retrieved Memory]"))
    assertTrue(messageText.contains("Updated ordinary dynamic recall travels as a context update."))
    assertFalse(messageText.contains("Initial dynamic recall should not become a Responses baseline."))
  }

  @Test
  fun responsesContinuationFallsBackWhenDynamicContextUpdateWouldBeTruncated() {
    val workspaceRoot = temporaryFolder.newFolder("agent-responses-memory-recall-large-update")
    Files.write(
      workspaceRoot.toPath().resolve("README.md"),
      "hello from workspace".toByteArray(StandardCharsets.UTF_8),
    )
    val selection = LiteLlmRouteSelectionMetadata(
      profileId = "test-profile",
      routeId = "responses-route",
      providerId = "openai",
      model = "gpt-5-mini",
      attemptIndex = 0,
    )
    val gatewayRequests = mutableListOf<LiteLlmGatewayRequest>()
    var requestIndex = 0
    val gateway = object : LiteLlmGateway {
      override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
        gatewayRequests += request
        return if (requestIndex++ == 0) {
          LiteLlmGatewayResult(
            requestId = request.requestId,
            status = LiteLlmGatewayStatus.SUCCESS,
            completionMode = LiteLlmCompletionMode.PRIMARY,
            completion = LiteLlmStructuredCompletion(
              toolCalls = listOf(
                LiteLlmStructuredToolCall(
                  id = "call_1",
                  toolName = "LS",
                  arguments = JsonObject(mapOf("path" to JsonPrimitive("."))),
                ),
              ),
            ),
            providerResponseId = "resp_large_update_1",
            providerLineageId = "lineage_large_update_1",
            selectedRoute = selection,
            attempts = listOf(
              LiteLlmAttemptRecord(
                route = selection,
                outcome = LiteLlmAttemptOutcome.SUCCESS,
                outputChars = 0,
                startedAtEpochMs = 53_800L,
                finishedAtEpochMs = 53_801L,
              ),
            ),
            startedAtEpochMs = 53_800L,
            finishedAtEpochMs = 53_801L,
          )
        } else {
          LiteLlmGatewayResult(
            requestId = request.requestId,
            status = LiteLlmGatewayStatus.SUCCESS,
            completionMode = LiteLlmCompletionMode.PRIMARY,
            completion = LiteLlmStructuredCompletion(
              finalText = "Replayed after large dynamic context update.",
            ),
            providerResponseId = "resp_large_update_2",
            providerLineageId = "lineage_large_update_2",
            selectedRoute = selection,
            attempts = listOf(
              LiteLlmAttemptRecord(
                route = selection,
                outcome = LiteLlmAttemptOutcome.SUCCESS,
                outputChars = 0,
                startedAtEpochMs = 53_802L,
                finishedAtEpochMs = 53_803L,
              ),
            ),
            startedAtEpochMs = 53_802L,
            finishedAtEpochMs = 53_803L,
          )
        }
      }
    }
    val tailMarker = "large-dynamic-memory-tail-marker"
    val oversizedMemory = "Updated ordinary dynamic recall " +
      "x".repeat(6_200) +
      tailMarker
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
        sessionContext = AgentRuntimeSessionContext(
          recalledMemory = MemoryRecallResult(
            memories = listOf(
              RetrievedMemory(
                id = "memory-initial",
                kind = MemoryKind.PROJECT_FACT,
                scope = MemoryScope.WORKSPACE,
                status = MemoryStatus.ACTIVE,
                content = "Initial dynamic recall should not become a Responses baseline.",
                lastConfirmedAtEpochMs = 1_000L,
                score = 100,
              ),
            ),
          ),
        ),
        midTurnMaintenance = { request ->
          OpenCrayMidTurnMaintenanceResult(
            sessionContext = request.sessionContext.copy(
              recalledMemory = MemoryRecallResult(
                memories = listOf(
                  RetrievedMemory(
                    id = "memory-oversized",
                    kind = MemoryKind.PROJECT_FACT,
                    scope = MemoryScope.WORKSPACE,
                    status = MemoryStatus.ACTIVE,
                    content = oversizedMemory,
                    lastConfirmedAtEpochMs = 2_000L,
                    score = 200,
                  ),
                ),
              ),
            ),
          )
        },
        llmMetadata = mapOf(
          "protocol" to "openai_responses",
          "responseApiPreferred" to "true",
          "nativeToolCallingAvailable" to "true",
          "responsesContinuationSupported" to "true",
          "nativeWebSearchEnabled" to "false",
        ),
      ),
      clock = IncrementingClock(start = 53_900L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Use relevant memory, list the workspace, then answer."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Replayed after large dynamic context update.", result.stdout)
    assertEquals(2, gatewayRequests.size)
    assertNull(gatewayRequests[1].previousResponseId)
    assertEquals("full_rebuild", gatewayRequests[1].metadata["localContinuationMode"])
    assertEquals(
      "responses_context_update_too_large",
      gatewayRequests[1].metadata["localContinuationReason"],
    )
    assertEquals(
      "dynamic_context_changed",
      gatewayRequests[1].metadata[LiteLlmMetadataKeys.CONTEXT_CACHE_BREAK_REASON],
    )
    assertEquals("0", gatewayRequests[1].metadata["responsesPendingContextUpdateCount"])
    assertEquals("1", result.metadata["localContinuationFallbackCount"])
    assertEquals("0", result.metadata["responsesPendingContextUpdateCount"])
    val messageText = gatewayStructuredPayloadText(gatewayRequests[1])
    assertFalse(messageText.contains("[OpenCray Context Update]"))
    assertTrue(messageText.contains("[Retrieved Memory]"))
    assertTrue(messageText.contains(tailMarker))
  }

  @Test
  fun responsesContinuationFallsBackWhenContextUpdateChainLimitIsReached() {
    val workspaceRoot = temporaryFolder.newFolder("agent-responses-context-update-chain-limit")
    val selection = LiteLlmRouteSelectionMetadata(
      profileId = "test-profile",
      routeId = "responses-route",
      providerId = "openai",
      model = "gpt-5-mini",
      attemptIndex = 0,
    )
    val initialCheckpoints = mutableListOf<OpenCrayPromptCheckpointEmission>()
    val initialGateway = object : LiteLlmGateway {
      override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult =
        LiteLlmGatewayResult(
          requestId = request.requestId,
          status = LiteLlmGatewayStatus.SUCCESS,
          completionMode = LiteLlmCompletionMode.PRIMARY,
          completion = LiteLlmStructuredCompletion(
            finalText = "Initial answer.",
          ),
          providerResponseId = "resp_chain_1",
          providerLineageId = "lineage_chain_1",
          selectedRoute = selection,
          attempts = listOf(
            LiteLlmAttemptRecord(
              route = selection,
              outcome = LiteLlmAttemptOutcome.SUCCESS,
              outputChars = 0,
              startedAtEpochMs = 54_000L,
              finishedAtEpochMs = 54_001L,
            ),
          ),
          startedAtEpochMs = 54_000L,
          finishedAtEpochMs = 54_001L,
        )
    }
    val initialRuntime = OpenCrayAgentRuntime(
      gateway = initialGateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(
        maxTurns = 2,
        sessionContext = AgentRuntimeSessionContext(
          recalledMemory = MemoryRecallResult(
            memories = listOf(
              RetrievedMemory(
                id = "memory-initial",
                kind = MemoryKind.PROJECT_FACT,
                scope = MemoryScope.WORKSPACE,
                status = MemoryStatus.ACTIVE,
                content = "Initial dynamic recall.",
                lastConfirmedAtEpochMs = 1_000L,
                score = 100,
              ),
            ),
          ),
        ),
        promptCheckpointSink = initialCheckpoints::add,
        llmMetadata = mapOf(
          "protocol" to "openai_responses",
          "responseApiPreferred" to "true",
          "nativeToolCallingAvailable" to "true",
          "responsesContinuationSupported" to "true",
          "nativeWebSearchEnabled" to "false",
        ),
      ),
      clock = IncrementingClock(start = 54_100L)::next,
    )

    val initialResult = initialRuntime.execute(
      task = promptTask(input = "Use relevant memory and answer."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, initialResult.status)
    val initialShape = requireNotNull(
      initialCheckpoints
        .mapNotNull { checkpoint -> checkpoint.state.responsesContinuationShape }
        .lastOrNull(),
    )
    val resumeState = OpenCrayPromptResumeState(
      turnIndex = 1,
      toolCallCount = 0,
      responsesPreviousResponseId = "resp_chain_1",
      responsesProviderLineageId = "lineage_chain_1",
      responsesLineageTrusted = true,
      responsesContinuationShape = initialShape.copy(
        appliedContextUpdateCount = 8,
      ),
      responsesPendingMessages = listOf(
        OpenCraySerializableGatewayMessage(
          role = LiteLlmGatewayMessageRole.TOOL.name,
          toolResult = OpenCraySerializableGatewayToolResult(
            toolCallId = "call-chain",
            toolName = "LS",
            content = "README.md",
          ),
        ),
      ),
    )
    val resumedGatewayRequests = mutableListOf<LiteLlmGatewayRequest>()
    val resumedGateway = object : LiteLlmGateway {
      override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
        resumedGatewayRequests += request
        return LiteLlmGatewayResult(
          requestId = request.requestId,
          status = LiteLlmGatewayStatus.SUCCESS,
          completionMode = LiteLlmCompletionMode.PRIMARY,
          completion = LiteLlmStructuredCompletion(
            finalText = "Rebuilt after context update chain limit.",
          ),
          providerResponseId = "resp_chain_2",
          providerLineageId = "lineage_chain_2",
          selectedRoute = selection,
          attempts = listOf(
            LiteLlmAttemptRecord(
              route = selection,
              outcome = LiteLlmAttemptOutcome.SUCCESS,
              outputChars = 0,
              startedAtEpochMs = 54_002L,
              finishedAtEpochMs = 54_003L,
            ),
          ),
          startedAtEpochMs = 54_002L,
          finishedAtEpochMs = 54_003L,
        )
      }
    }
    val resumedRuntime = OpenCrayAgentRuntime(
      gateway = resumedGateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(
        maxTurns = 2,
        sessionContext = AgentRuntimeSessionContext(
          recalledMemory = MemoryRecallResult(
            memories = listOf(
              RetrievedMemory(
                id = "memory-updated",
                kind = MemoryKind.PROJECT_FACT,
                scope = MemoryScope.WORKSPACE,
                status = MemoryStatus.ACTIVE,
                content = "Updated dynamic recall after many Responses context updates.",
                lastConfirmedAtEpochMs = 2_000L,
                score = 200,
              ),
            ),
          ),
        ),
        promptResumeState = resumeState,
        llmMetadata = mapOf(
          "protocol" to "openai_responses",
          "responseApiPreferred" to "true",
          "nativeToolCallingAvailable" to "true",
          "responsesContinuationSupported" to "true",
          "nativeWebSearchEnabled" to "false",
        ),
      ),
      clock = IncrementingClock(start = 54_200L)::next,
    )

    val resumedResult = resumedRuntime.execute(
      task = promptTask(input = "Use relevant memory and answer."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, resumedResult.status)
    assertEquals("Rebuilt after context update chain limit.", resumedResult.stdout)
    assertEquals(1, resumedGatewayRequests.size)
    assertNull(resumedGatewayRequests.single().previousResponseId)
    assertEquals(
      "full_rebuild",
      resumedGatewayRequests.single().metadata["localContinuationMode"],
    )
    assertEquals(
      "responses_context_update_chain_limit",
      resumedGatewayRequests.single().metadata["localContinuationReason"],
    )
    assertEquals(
      "dynamic_context_changed",
      resumedGatewayRequests.single().metadata[LiteLlmMetadataKeys.CONTEXT_CACHE_BREAK_REASON],
    )
    assertEquals("1", resumedResult.metadata["localContinuationFallbackCount"])
    assertEquals("0", resumedResult.metadata["responsesPendingContextUpdateCount"])
  }

  @Test
  fun runPromptTaskEmitsNonResponsesContextCacheShapeMetadata() {
    val workspaceRoot = temporaryFolder.newFolder("agent-context-cache-shape-metadata")
    val gateway = RecordingGateway(
      outputs = listOf("""{"type":"final","answer":"Done."}"""),
    )
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(workspaceRoots = setOf(workspaceRoot.toPath())),
      ),
      config = OpenCrayAgentRuntimeConfig(maxTurns = 2),
      clock = IncrementingClock(start = 12_000L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Say done."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    val requestMetadata = gateway.requests.single().metadata
    assertEquals(
      "non_responses_front_zone_v1",
      requestMetadata[LiteLlmMetadataKeys.CONTEXT_CACHE_CONTRACT_VERSION],
    )
    assertEquals(
      requestMetadata[LiteLlmMetadataKeys.CONTEXT_CACHE_CONTRACT_VERSION],
      result.metadata[LiteLlmMetadataKeys.CONTEXT_CACHE_CONTRACT_VERSION],
    )
    assertEquals(
      requestMetadata[LiteLlmMetadataKeys.CONTEXT_CACHE_STABLE_ANCHOR_HASH],
      result.metadata[LiteLlmMetadataKeys.CONTEXT_CACHE_STABLE_ANCHOR_HASH],
    )
    assertEquals(
      requestMetadata[LiteLlmMetadataKeys.CONTEXT_CACHE_DURABLE_CONTEXT_HASH],
      result.metadata[LiteLlmMetadataKeys.CONTEXT_CACHE_DURABLE_CONTEXT_HASH],
    )
    assertEquals(
      requestMetadata[LiteLlmMetadataKeys.CONTEXT_CACHE_DYNAMIC_CONTEXT_HASH],
      result.metadata[LiteLlmMetadataKeys.CONTEXT_CACHE_DYNAMIC_CONTEXT_HASH],
    )
    assertEquals(
      requestMetadata[LiteLlmMetadataKeys.CONTEXT_CACHE_FRONT_CONTEXT_ZONE_MASK],
      result.metadata[LiteLlmMetadataKeys.CONTEXT_CACHE_FRONT_CONTEXT_ZONE_MASK],
    )
    assertEquals(
      requestMetadata[LiteLlmMetadataKeys.CONTEXT_CACHE_FRONT_CONTEXT_MESSAGE_COUNT],
      result.metadata[LiteLlmMetadataKeys.CONTEXT_CACHE_FRONT_CONTEXT_MESSAGE_COUNT],
    )
    assertNotNull(requestMetadata[LiteLlmMetadataKeys.CONTEXT_CACHE_STABLE_ANCHOR_HASH])
    assertNotNull(requestMetadata[LiteLlmMetadataKeys.CONTEXT_CACHE_DURABLE_CONTEXT_HASH])
    assertNotNull(requestMetadata[LiteLlmMetadataKeys.CONTEXT_CACHE_DYNAMIC_CONTEXT_HASH])
    assertEquals("durable", requestMetadata[LiteLlmMetadataKeys.CONTEXT_CACHE_FRONT_CONTEXT_ZONE_MASK])
    assertEquals("1", requestMetadata[LiteLlmMetadataKeys.CONTEXT_CACHE_FRONT_CONTEXT_MESSAGE_COUNT])
  }

  @Test
  fun responsesContinuationFallsBackToFullRebuildWhenActiveSkillChangesToolPool() {
    val workspaceRoot = temporaryFolder.newFolder("agent-responses-skill-rebuild")
    val skillsRoot = temporaryFolder.newFolder("agent-responses-skill-root")
    writeSkill(
      root = skillsRoot,
      relativeDirectory = "ui-ux-pro-max",
      frontMatter = """
        name: ui-ux-pro-max
        description: High-end UI review workflow.
        invocation-control: explicit-only
        user-invocable: true
        allowed-tools: [ read, write ]
      """.trimIndent(),
      body = """
        # UI UX Pro Max

        Audit the current interface first, then apply a concrete design system.
      """.trimIndent(),
    )
    val skillCatalog = SkillCatalogResolver().resolve(listOf(skillsRoot))
    val selection = LiteLlmRouteSelectionMetadata(
      profileId = "test-profile",
      routeId = "responses-route",
      providerId = "openai",
      model = "gpt-5-mini",
      attemptIndex = 0,
    )
    val gatewayRequests = mutableListOf<LiteLlmGatewayRequest>()
    var requestIndex = 0
    val gateway = object : LiteLlmGateway {
      override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
        gatewayRequests += request
        return if (requestIndex++ == 0) {
          LiteLlmGatewayResult(
            requestId = request.requestId,
            status = LiteLlmGatewayStatus.SUCCESS,
            completionMode = LiteLlmCompletionMode.PRIMARY,
            completion = LiteLlmStructuredCompletion(
              toolCalls = listOf(
                LiteLlmStructuredToolCall(
                  id = "call_1",
                  toolName = "skill_read",
                  arguments = JsonObject(
                    mapOf("name" to JsonPrimitive("ui-ux-pro-max")),
                  ),
                ),
              ),
            ),
            providerResponseId = "resp_skill_1",
            providerLineageId = "lineage_skill_1",
            selectedRoute = selection,
            attempts = listOf(
              LiteLlmAttemptRecord(
                route = selection,
                outcome = LiteLlmAttemptOutcome.SUCCESS,
                outputChars = 0,
                startedAtEpochMs = 54_000L,
                finishedAtEpochMs = 54_001L,
              ),
            ),
            startedAtEpochMs = 54_000L,
            finishedAtEpochMs = 54_001L,
          )
        } else {
          LiteLlmGatewayResult(
            requestId = request.requestId,
            status = LiteLlmGatewayStatus.SUCCESS,
            completionMode = LiteLlmCompletionMode.PRIMARY,
            completion = LiteLlmStructuredCompletion(
              finalText = "Rebuilt after the tool pool changed.",
            ),
            providerResponseId = "resp_skill_2",
            providerLineageId = "lineage_skill_2",
            selectedRoute = selection,
            attempts = listOf(
              LiteLlmAttemptRecord(
                route = selection,
                outcome = LiteLlmAttemptOutcome.SUCCESS,
                outputChars = 0,
                startedAtEpochMs = 54_002L,
                finishedAtEpochMs = 54_003L,
              ),
            ),
            startedAtEpochMs = 54_002L,
            finishedAtEpochMs = 54_003L,
          )
        }
      }
    }
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
          skillsRoots = listOf(skillsRoot),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(
        maxTurns = 4,
        maxToolCalls = 2,
        sessionContext = AgentRuntimeSessionContext(
          skillInventory = skillCatalog.inventory,
          skillCatalog = skillCatalog,
        ),
        llmMetadata = mapOf(
          "protocol" to "openai_responses",
          "responseApiPreferred" to "true",
          "nativeToolCallingAvailable" to "true",
          "responsesContinuationSupported" to "true",
          "nativeWebSearchEnabled" to "false",
        ),
      ),
      clock = IncrementingClock(start = 54_500L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Load the UI skill, then follow it."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Rebuilt after the tool pool changed.", result.stdout)
    assertEquals(2, gatewayRequests.size)
    assertNull(gatewayRequests[1].previousResponseId)
    assertEquals("full_rebuild", gatewayRequests[1].metadata["localContinuationMode"])
    assertEquals("tool_pool_changed", gatewayRequests[1].metadata["localContinuationReason"])
    assertEquals(
      "tool_pool_changed",
      gatewayRequests[1].metadata[LiteLlmMetadataKeys.CONTEXT_CACHE_BREAK_REASON],
    )
    val messageText = gatewayStructuredPayloadText(gatewayRequests[1])
    assertTrue(messageText.contains("[Active Skill]"))
    assertTrue(messageText.contains("name=ui-ux-pro-max"))
  }

  @Test
  fun responsesContinuationEmitsCacheBreakReasonWhenLineageIsUnavailable() {
    val workspaceRoot = temporaryFolder.newFolder("agent-responses-lineage-unavailable")
    val selection = LiteLlmRouteSelectionMetadata(
      profileId = "test-profile",
      routeId = "responses-route",
      providerId = "openai",
      model = "gpt-5-mini",
      attemptIndex = 0,
    )
    val gatewayRequests = mutableListOf<LiteLlmGatewayRequest>()
    var requestIndex = 0
    val gateway = object : LiteLlmGateway {
      override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
        gatewayRequests += request
        return if (requestIndex++ == 0) {
          LiteLlmGatewayResult(
            requestId = request.requestId,
            status = LiteLlmGatewayStatus.SUCCESS,
            completionMode = LiteLlmCompletionMode.PRIMARY,
            completion = LiteLlmStructuredCompletion(
              toolCalls = listOf(
                LiteLlmStructuredToolCall(
                  id = "call_1",
                  toolName = "LS",
                  arguments = JsonObject(mapOf("path" to JsonPrimitive("."))),
                ),
              ),
            ),
            selectedRoute = selection,
            attempts = listOf(
              LiteLlmAttemptRecord(
                route = selection,
                outcome = LiteLlmAttemptOutcome.SUCCESS,
                outputChars = 0,
                startedAtEpochMs = 52_100L,
                finishedAtEpochMs = 52_101L,
              ),
            ),
            startedAtEpochMs = 52_100L,
            finishedAtEpochMs = 52_101L,
          )
        } else {
          LiteLlmGatewayResult(
            requestId = request.requestId,
            status = LiteLlmGatewayStatus.SUCCESS,
            completionMode = LiteLlmCompletionMode.PRIMARY,
            completion = LiteLlmStructuredCompletion(
              finalText = "Recovered after lineage was unavailable.",
            ),
            selectedRoute = selection,
            attempts = listOf(
              LiteLlmAttemptRecord(
                route = selection,
                outcome = LiteLlmAttemptOutcome.SUCCESS,
                outputChars = 0,
                startedAtEpochMs = 52_102L,
                finishedAtEpochMs = 52_103L,
              ),
            ),
            startedAtEpochMs = 52_102L,
            finishedAtEpochMs = 52_103L,
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
      config = OpenCrayAgentRuntimeConfig(
        maxTurns = 4,
        maxToolCalls = 2,
        llmMetadata = mapOf(
          "protocol" to "openai_responses",
          "responseApiPreferred" to "true",
          "nativeToolCallingAvailable" to "true",
          "responsesContinuationSupported" to "true",
          "nativeWebSearchEnabled" to "false",
        ),
      ),
      clock = IncrementingClock(start = 52_600L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "List files safely and answer."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Recovered after lineage was unavailable.", result.stdout)
    assertEquals(2, gatewayRequests.size)
    assertNull(gatewayRequests[1].previousResponseId)
    assertEquals("full_rebuild", gatewayRequests[1].metadata["localContinuationMode"])
    assertEquals(
      "responses_lineage_unavailable",
      gatewayRequests[1].metadata["localContinuationReason"],
    )
    assertEquals(
      "continuation_lineage_untrusted",
      gatewayRequests[1].metadata[LiteLlmMetadataKeys.CONTEXT_CACHE_BREAK_REASON],
    )
    assertEquals(
      "continuation_lineage_untrusted",
      result.metadata[LiteLlmMetadataKeys.CONTEXT_CACHE_BREAK_REASON],
    )
  }

  @Test
  fun responsesContinuationFallsBackToFullRebuildWhenPendingDeltaIncludesSupplement() {
    val workspaceRoot = temporaryFolder.newFolder("agent-responses-supplement-rebuild")
    val selection = LiteLlmRouteSelectionMetadata(
      profileId = "test-profile",
      routeId = "responses-route",
      providerId = "openai",
      model = "gpt-5-mini",
      attemptIndex = 0,
    )
    val gatewayRequests = mutableListOf<LiteLlmGatewayRequest>()
    var requestIndex = 0
    var supplementProviderCalls = 0
    val gateway = object : LiteLlmGateway {
      override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
        gatewayRequests += request
        return if (requestIndex++ == 0) {
          LiteLlmGatewayResult(
            requestId = request.requestId,
            status = LiteLlmGatewayStatus.SUCCESS,
            completionMode = LiteLlmCompletionMode.PRIMARY,
            completion = LiteLlmStructuredCompletion(
              toolCalls = listOf(
                LiteLlmStructuredToolCall(
                  id = "call_1",
                  toolName = "LS",
                  arguments = JsonObject(
                    mapOf("path" to JsonPrimitive(".")),
                  ),
                ),
              ),
            ),
            providerResponseId = "resp_supp_1",
            providerLineageId = "lineage_supp_1",
            selectedRoute = selection,
            attempts = listOf(
              LiteLlmAttemptRecord(
                route = selection,
                outcome = LiteLlmAttemptOutcome.SUCCESS,
                outputChars = 0,
                startedAtEpochMs = 51_000L,
                finishedAtEpochMs = 51_001L,
              ),
            ),
            startedAtEpochMs = 51_000L,
            finishedAtEpochMs = 51_001L,
          )
        } else {
          LiteLlmGatewayResult(
            requestId = request.requestId,
            status = LiteLlmGatewayStatus.SUCCESS,
            completionMode = LiteLlmCompletionMode.PRIMARY,
            completion = LiteLlmStructuredCompletion(
              finalText = "Rebuilt after supplement.",
            ),
            providerResponseId = "resp_supp_2",
            providerLineageId = "lineage_supp_2",
            selectedRoute = selection,
            attempts = listOf(
              LiteLlmAttemptRecord(
                route = selection,
                outcome = LiteLlmAttemptOutcome.SUCCESS,
                outputChars = 0,
                startedAtEpochMs = 51_002L,
                finishedAtEpochMs = 51_003L,
              ),
            ),
            startedAtEpochMs = 51_002L,
            finishedAtEpochMs = 51_003L,
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
      config = OpenCrayAgentRuntimeConfig(
        maxTurns = 4,
        maxToolCalls = 2,
        supplementInputProvider = { _, _ ->
          supplementProviderCalls += 1
          if (supplementProviderCalls == 2) {
            listOf(
              OpenCraySupplementInput(
                entryId = "supplement-1",
                text = "Also explain what changed.",
                createdAtEpochMs = 51_500L,
              ),
            )
          } else {
            emptyList()
          }
        },
        llmMetadata = mapOf(
          "protocol" to "openai_responses",
          "responseApiPreferred" to "true",
          "nativeToolCallingAvailable" to "true",
          "responsesContinuationSupported" to "true",
          "nativeWebSearchEnabled" to "false",
        ),
      ),
      clock = IncrementingClock(start = 51_800L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "List the workspace and answer."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Rebuilt after supplement.", result.stdout)
    assertEquals(2, gatewayRequests.size)
    assertNull(gatewayRequests[0].previousResponseId)
    assertNull(gatewayRequests[1].previousResponseId)
    assertEquals("full_rebuild", gatewayRequests[1].metadata["localContinuationMode"])
    assertEquals(
      "responses_pending_user_message",
      gatewayRequests[1].metadata["localContinuationReason"],
    )
    assertEquals(
      "responses_pending_user_message",
      result.metadata["localContinuationLastReason"],
    )
    assertTrue(
      gatewayRequests[1].messages.any { message ->
        message.role == LiteLlmGatewayMessageRole.USER &&
          message.content == "Also explain what changed."
      },
    )
    assertTrue(
      gatewayRequests[1].messages.any { message ->
        message.role == LiteLlmGatewayMessageRole.ASSISTANT &&
          message.toolCalls.any { toolCall ->
            toolCall.id == "call_1" && toolCall.toolName == "LS"
          }
      },
    )
    assertTrue(
      gatewayRequests[1].messages.any { message ->
        val toolResult = message.toolResult
        message.role == LiteLlmGatewayMessageRole.TOOL &&
          toolResult?.toolCallId == "call_1" &&
          toolResult.toolName == "LS"
      },
    )
  }

  @Test
  fun responsesContinuationFallsBackToFullRebuildWhenToolResultPublishesAttachmentArtifact() {
    val workspaceRoot = temporaryFolder.newFolder("agent-responses-attachment-artifact")
    val selection = LiteLlmRouteSelectionMetadata(
      profileId = "test-profile",
      routeId = "responses-route",
      providerId = "openai",
      model = "gpt-5-mini",
      attemptIndex = 0,
    )
    val gatewayRequests = mutableListOf<LiteLlmGatewayRequest>()
    var requestIndex = 0
    var finalAttachmentArtifactId: String? = null
    val gateway = object : LiteLlmGateway {
      override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
        gatewayRequests += request
        return if (requestIndex++ == 0) {
          LiteLlmGatewayResult(
            requestId = request.requestId,
            status = LiteLlmGatewayStatus.SUCCESS,
            completionMode = LiteLlmCompletionMode.PRIMARY,
            completion = LiteLlmStructuredCompletion(
              toolCalls = listOf(
                LiteLlmStructuredToolCall(
                  id = "call_1",
                  toolName = "Write",
                  arguments = JsonObject(
                    mapOf(
                      "file_path" to JsonPrimitive("outputs/diagram.png"),
                      "content" to JsonPrimitive("png-placeholder"),
                    ),
                  ),
                ),
              ),
            ),
            providerResponseId = "resp_attach_1",
            providerLineageId = "lineage_attach_1",
            selectedRoute = selection,
            attempts = listOf(
              LiteLlmAttemptRecord(
                route = selection,
                outcome = LiteLlmAttemptOutcome.SUCCESS,
                outputChars = 0,
                startedAtEpochMs = 53_000L,
                finishedAtEpochMs = 53_001L,
              ),
            ),
            startedAtEpochMs = 53_000L,
            finishedAtEpochMs = 53_001L,
          )
        } else {
          finalAttachmentArtifactId = request.messages
            .mapNotNull { message ->
              message.toolResult?.metadata?.get(OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_ID)
            }
            .singleOrNull()
            ?: error("Expected the replayed tool result to include one attachment artifact id.")
          LiteLlmGatewayResult(
            requestId = request.requestId,
            status = LiteLlmGatewayStatus.SUCCESS,
            completionMode = LiteLlmCompletionMode.PRIMARY,
            outputText = """
            {
              "type": "final",
              "answer": "",
              "attachments": [
                {
                  "artifact_id": "$finalAttachmentArtifactId",
                  "kind": "image"
                }
              ]
            }
            """.trimIndent(),
            providerResponseId = "resp_attach_2",
            providerLineageId = "lineage_attach_2",
            selectedRoute = selection,
            attempts = listOf(
              LiteLlmAttemptRecord(
                route = selection,
                outcome = LiteLlmAttemptOutcome.SUCCESS,
                outputChars = 0,
                startedAtEpochMs = 53_002L,
                finishedAtEpochMs = 53_003L,
              ),
            ),
            startedAtEpochMs = 53_002L,
            finishedAtEpochMs = 53_003L,
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
      config = OpenCrayAgentRuntimeConfig(
        maxTurns = 4,
        maxToolCalls = 2,
        llmMetadata = mapOf(
          "protocol" to "openai_responses",
          "responseApiPreferred" to "true",
          "nativeToolCallingAvailable" to "true",
          "responsesContinuationSupported" to "true",
          "nativeWebSearchEnabled" to "false",
        ),
      ),
      clock = IncrementingClock(start = 53_500L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Create a diagram image and send it back as an attachment."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("", result.stdout)
    assertEquals(2, gatewayRequests.size)
    assertNull(gatewayRequests[0].previousResponseId)
    assertNull(gatewayRequests[1].previousResponseId)
    assertEquals("full_rebuild", gatewayRequests[1].metadata["localContinuationMode"])
    assertEquals(
      "responses_pending_tool_result_attachment_artifact",
      gatewayRequests[1].metadata["localContinuationReason"],
    )
    assertEquals(
      "responses_pending_tool_result_attachment_artifact",
      result.metadata["localContinuationLastReason"],
    )
    assertTrue(
      gatewayRequests[1].messages.any { message ->
        message.role == LiteLlmGatewayMessageRole.ASSISTANT &&
          message.toolCalls.singleOrNull()?.id == "call_1" &&
          message.toolCalls.singleOrNull()?.toolName == "Write"
      },
    )
    assertTrue(
      gatewayRequests[1].messages.any { message ->
        val toolResult = message.toolResult
        message.role == LiteLlmGatewayMessageRole.TOOL &&
          toolResult?.toolCallId == "call_1" &&
          toolResult.toolName == "Write" &&
          toolResult.metadata[OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_RELATIVE_PATH] ==
          "outputs/diagram.png"
      },
    )
    val attachmentsJson = result.metadata[OpenCrayExecutionMetadataKeys.FINAL_ATTACHMENTS_JSON].orEmpty()
    val attachments = Json.decodeFromString(
      ListSerializer(OpenCrayFinalAttachment.serializer()),
      attachmentsJson,
    )
    assertEquals(1, attachments.size)
    assertEquals(finalAttachmentArtifactId, attachments.single().artifactId)
    assertEquals("image", attachments.single().kind)
    assertTrue(Files.exists(workspaceRoot.toPath().resolve("outputs").resolve("diagram.png")))
  }

  @Test
  fun restoredGeneralResumeCheckpointUsesLocalFrontPatchWhenResumeWorkingStateChangesDynamicContext() {
    val workspaceRoot = temporaryFolder.newFolder("agent-restored-local-continuation")
    Files.write(
      workspaceRoot.toPath().resolve("README.md"),
      "hello from workspace".toByteArray(StandardCharsets.UTF_8),
    )

    val selection = LiteLlmRouteSelectionMetadata(
      profileId = "test-profile",
      routeId = "test-route",
      providerId = "openai",
      model = "gpt-5-mini",
      attemptIndex = 0,
    )
    val checkpointEventSink = RecordingEventSink()
    var initialRequestIndex = 0
    val initialGateway = object : LiteLlmGateway {
      override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
        return if (initialRequestIndex++ == 0) {
          LiteLlmGatewayResult(
            requestId = request.requestId,
            status = LiteLlmGatewayStatus.SUCCESS,
            completionMode = LiteLlmCompletionMode.PRIMARY,
            completion = LiteLlmStructuredCompletion(
              toolCalls = listOf(
                LiteLlmStructuredToolCall(
                  id = "call_1",
                  toolName = "workspace_read_file",
                  arguments = JsonObject(
                    mapOf("path" to JsonPrimitive("README.md")),
                  ),
                ),
              ),
            ),
            selectedRoute = selection,
            attempts = listOf(
              LiteLlmAttemptRecord(
                route = selection,
                outcome = LiteLlmAttemptOutcome.SUCCESS,
                outputChars = 0,
                startedAtEpochMs = 43_000L,
                finishedAtEpochMs = 43_001L,
              ),
            ),
            startedAtEpochMs = 43_000L,
            finishedAtEpochMs = 43_001L,
          )
        } else {
          LiteLlmGatewayResult(
            requestId = request.requestId,
            status = LiteLlmGatewayStatus.SUCCESS,
            completionMode = LiteLlmCompletionMode.PRIMARY,
            completion = LiteLlmStructuredCompletion(
              finalText = "Initial run finished.",
            ),
            selectedRoute = selection,
            attempts = listOf(
              LiteLlmAttemptRecord(
                route = selection,
                outcome = LiteLlmAttemptOutcome.SUCCESS,
                outputChars = 0,
                startedAtEpochMs = 43_002L,
                finishedAtEpochMs = 43_003L,
              ),
            ),
            startedAtEpochMs = 43_002L,
            finishedAtEpochMs = 43_003L,
          )
        }
      }
    }
    val initialRuntime = OpenCrayAgentRuntime(
      gateway = initialGateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(
        maxTurns = 4,
        maxToolCalls = 2,
        llmMetadata = mapOf(
          "protocol" to "openai",
          "nativeToolCallingAvailable" to "true",
        ),
      ),
      eventSink = checkpointEventSink,
      clock = IncrementingClock(start = 43_500L)::next,
    )

    val initialResult = initialRuntime.execute(
      task = promptTask(input = "Read the README and then answer."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, initialResult.status)
    val checkpointState = requireNotNull(
      OpenCrayPromptResumeMetadata.decodeFromMetadata(
        metadata = checkpointEventSink.events
          .filterIsInstance<OpenCrayToolResultEvent>()
          .first()
          .result
          .metadata,
        json = Json { ignoreUnknownKeys = true },
      ),
    )
    assertNotNull(checkpointState.localContinuationEnvelope)
    val checkpointEnvelope = requireNotNull(checkpointState.localContinuationEnvelope)
    assertTrue(checkpointEnvelope.durableContextPrompt.orEmpty().contains("[Tool Protocol]"))
    assertFalse(checkpointEnvelope.durableContextPrompt.orEmpty().contains("[Task Metadata]"))
    assertFalse(checkpointEnvelope.dynamicContextPrompt.orEmpty().contains("[Task Metadata]"))
    assertFalse(checkpointEnvelope.dynamicContextPrompt.orEmpty().contains("[Tool Protocol]"))
    val checkpointFrontUserMessages = checkpointEnvelope
      .gatewayMessages
      .filter { message -> message.role == LiteLlmGatewayMessageRole.USER.name }
      .mapNotNull { message -> message.content }
    assertTrue(checkpointFrontUserMessages[0].contains("[Tool Protocol]"))
    assertTrue(checkpointFrontUserMessages.none { message -> message.contains("[Task Metadata]") })

    val resumedRequests = mutableListOf<LiteLlmGatewayRequest>()
    val resumedGateway = object : LiteLlmGateway {
      override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
        resumedRequests += request
        return LiteLlmGatewayResult(
          requestId = request.requestId,
          status = LiteLlmGatewayStatus.SUCCESS,
          completionMode = LiteLlmCompletionMode.PRIMARY,
          completion = LiteLlmStructuredCompletion(
            finalText = "Resumed with durable local continuation.",
          ),
          selectedRoute = selection,
          attempts = listOf(
            LiteLlmAttemptRecord(
              route = selection,
              outcome = LiteLlmAttemptOutcome.SUCCESS,
              outputChars = 0,
              startedAtEpochMs = 43_010L,
              finishedAtEpochMs = 43_011L,
            ),
          ),
          startedAtEpochMs = 43_010L,
          finishedAtEpochMs = 43_011L,
        )
      }
    }
    val resumedRuntime = OpenCrayAgentRuntime(
      gateway = resumedGateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(
        maxTurns = 4,
        maxToolCalls = 2,
        promptResumeState = checkpointState,
        supplementInputProvider = { _, _ ->
          listOf(
            OpenCraySupplementInput(
              entryId = "supplement-restored",
              text = "Also check the durable resume path.",
              createdAtEpochMs = 44_000L,
            ),
          )
        },
        llmMetadata = mapOf(
          "protocol" to "openai",
          "nativeToolCallingAvailable" to "true",
        ),
      ),
      clock = IncrementingClock(start = 44_500L)::next,
    )

    val resumedResult = resumedRuntime.execute(
      task = promptTask(input = "Read the README and then answer."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, resumedResult.status)
    assertEquals("Resumed with durable local continuation.", resumedResult.stdout)
    assertEquals(1, resumedRequests.size)
    val resumedCacheMetadata = resumedRequests.single().metadata
    assertEquals("local_front_patch", resumedRequests.single().metadata["localContinuationMode"])
    assertEquals("dynamic_context_changed", resumedRequests.single().metadata["localContinuationReason"])
    assertEquals(
      "dynamic_context_changed",
      resumedRequests.single().metadata[LiteLlmMetadataKeys.CONTEXT_CACHE_BREAK_REASON],
    )
    assertEquals(
      "non_responses_front_zone_v1",
      resumedCacheMetadata[LiteLlmMetadataKeys.CONTEXT_CACHE_CONTRACT_VERSION],
    )
    assertEquals(
      promptCacheShapeHash(checkpointEnvelope.stableAnchor),
      resumedCacheMetadata[LiteLlmMetadataKeys.CONTEXT_CACHE_STABLE_ANCHOR_HASH],
    )
    assertEquals(
      promptCacheShapeHash(checkpointEnvelope.durableContextPrompt.orEmpty()),
      resumedCacheMetadata[LiteLlmMetadataKeys.CONTEXT_CACHE_DURABLE_CONTEXT_HASH],
    )
    assertNotEquals(
      promptCacheShapeHash(checkpointEnvelope.dynamicContextPrompt.orEmpty()),
      resumedCacheMetadata[LiteLlmMetadataKeys.CONTEXT_CACHE_DYNAMIC_CONTEXT_HASH],
    )
    assertEquals(
      resumedCacheMetadata[LiteLlmMetadataKeys.CONTEXT_CACHE_DYNAMIC_CONTEXT_HASH],
      resumedResult.metadata[LiteLlmMetadataKeys.CONTEXT_CACHE_DYNAMIC_CONTEXT_HASH],
    )
    assertEquals("1", resumedResult.metadata["localContinuationUsedCount"])
    assertEquals("0", resumedResult.metadata["localContinuationFallbackCount"])
    assertEquals("local_front_patch", resumedResult.metadata["localContinuationLastMode"])
    assertEquals(
      "dynamic_context_changed",
      resumedResult.metadata[LiteLlmMetadataKeys.CONTEXT_CACHE_BREAK_REASON],
    )
    val resumedUserMessages = resumedRequests.single().messages
      .filter { message -> message.role == LiteLlmGatewayMessageRole.USER }
      .mapNotNull { message -> message.content }
    assertTrue(resumedUserMessages.contains("Also check the durable resume path."))
    assertTrue(
      resumedRequests.single().prompt.contains(
        "Continue from the saved checkpoint state instead of restarting from the original task input.",
      ),
    )
    assertTrue(
      resumedRequests.single().messages.any { message ->
        message.role == LiteLlmGatewayMessageRole.TOOL &&
          message.toolResult?.toolName == "workspace_read_file"
      },
    )
  }

  @Test
  fun restoredGeneralResumeCheckpointFallsBackWhenDynamicFrontPatchCannotAlignStoredPrefix() {
    val workspaceRoot = temporaryFolder.newFolder("agent-restored-local-continuation-corrupt-prefix")
    Files.write(
      workspaceRoot.toPath().resolve("README.md"),
      "hello from workspace".toByteArray(StandardCharsets.UTF_8),
    )
    val task = promptTask(input = "Read the README and then answer.")
    val eventSink = RecordingEventSink()
    val initialGateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"workspace_read_file","arguments":{"path":"README.md"}}""",
        """{"type":"final","answer":"Initial run finished."}""",
      ),
    )
    val initialRuntime = OpenCrayAgentRuntime(
      gateway = initialGateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(
        maxTurns = 4,
        maxToolCalls = 2,
        llmMetadata = mapOf(
          "protocol" to "openai",
          "nativeToolCallingAvailable" to "true",
        ),
      ),
      eventSink = eventSink,
      clock = IncrementingClock(start = 45_000L)::next,
    )

    val initialResult = initialRuntime.execute(
      task = task,
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, initialResult.status)
    val checkpointState = checkpointStateFromFirstToolResult(eventSink)
    val checkpointEnvelope = requireNotNull(checkpointState.localContinuationEnvelope)
    val corruptedEnvelope = checkpointEnvelope.copy(
      gatewayMessages = checkpointEnvelope.gatewayMessages.drop(1),
    )
    val resumedGateway = RecordingGateway(
      outputs = listOf("""{"type":"final","answer":"Recovered after invalid front patch."}"""),
    )
    val resumedRuntime = OpenCrayAgentRuntime(
      gateway = resumedGateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(
        maxTurns = 4,
        maxToolCalls = 2,
        promptResumeState = checkpointState.copy(localContinuationEnvelope = corruptedEnvelope),
        supplementInputProvider = { _, _ ->
          listOf(
            OpenCraySupplementInput(
              entryId = "supplement-restored",
              text = "Also check the durable resume path.",
              createdAtEpochMs = 45_250L,
            ),
          )
        },
        llmMetadata = mapOf(
          "protocol" to "openai",
          "nativeToolCallingAvailable" to "true",
        ),
      ),
      clock = IncrementingClock(start = 45_500L)::next,
    )

    val resumedResult = resumedRuntime.execute(
      task = task,
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, resumedResult.status)
    assertEquals("Recovered after invalid front patch.", resumedResult.stdout)
    assertEquals(1, resumedGateway.requests.size)
    assertEquals("full_rebuild", resumedGateway.requests.single().metadata["localContinuationMode"])
    assertEquals("dynamic_context_changed", resumedGateway.requests.single().metadata["localContinuationReason"])
    assertEquals(
      "dynamic_context_changed",
      resumedGateway.requests.single().metadata[LiteLlmMetadataKeys.CONTEXT_CACHE_BREAK_REASON],
    )
    assertEquals("0", resumedResult.metadata["localContinuationUsedCount"])
    assertEquals("1", resumedResult.metadata["localContinuationFallbackCount"])
  }

  @Test
  fun deriveContextCacheBreakReasonMapsZoneAwareLocalContinuationReasons() {
    assertEquals(
      "tool_pool_changed",
      deriveContextCacheBreakReason(localContinuationReason = "tool_pool_changed"),
    )
    assertEquals(
      "tool_schema_changed",
      deriveContextCacheBreakReason(localContinuationReason = "tool_schema_changed"),
    )
    assertEquals(
      "user_setting_changed",
      deriveContextCacheBreakReason(localContinuationReason = "user_setting_changed"),
    )
    assertEquals(
      "durable_context_changed",
      deriveContextCacheBreakReason(localContinuationReason = "durable_context_changed"),
    )
    assertEquals(
      "dynamic_context_changed",
      deriveContextCacheBreakReason(localContinuationReason = "dynamic_context_changed"),
    )
    assertEquals(
      "dynamic_context_changed",
      deriveContextCacheBreakReason(localContinuationReason = "responses_context_update_chain_limit"),
    )
    assertEquals(
      "dynamic_context_changed",
      deriveContextCacheBreakReason(localContinuationReason = "responses_context_update_too_large"),
    )
    assertEquals(
      "continuation_lineage_untrusted",
      deriveContextCacheBreakReason(
        localContinuationReason = "responses_legacy_json_fallback_enabled",
        hasHistoricalResponsesContinuation = true,
      ),
    )
    assertNull(
      deriveContextCacheBreakReason(
        localContinuationReason = "responses_lineage_unavailable",
        hasHistoricalResponsesContinuation = false,
      ),
    )
  }

  @Test
  fun deriveContextCacheBreakReasonMapsFrontContextChangeToDynamicContextCategory() {
    assertEquals(
      "dynamic_context_changed",
      deriveContextCacheBreakReason(localContinuationReason = "front_context_changed"),
    )
    assertEquals(
      "continuation_lineage_untrusted",
      deriveContextCacheBreakReason(
        localContinuationReason = "responses_legacy_json_fallback_enabled",
        hasHistoricalResponsesContinuation = true,
      ),
    )
    assertNull(
      deriveContextCacheBreakReason(
        localContinuationReason = "responses_lineage_unavailable",
        hasHistoricalResponsesContinuation = false,
      ),
    )
  }

  @Test
  fun runPromptTaskUsesLocalDeltaContinuationForSupplementsOnNonResponsesRoute() {
    val workspaceRoot = temporaryFolder.newFolder("agent-local-continuation-workspace")
    Files.write(
      workspaceRoot.toPath().resolve("README.md"),
      "hello from workspace".toByteArray(StandardCharsets.UTF_8),
    )

    val selection = LiteLlmRouteSelectionMetadata(
      profileId = "test-profile",
      routeId = "test-route",
      providerId = "openai",
      model = "gpt-5-mini",
      attemptIndex = 0,
    )
    val requests = mutableListOf<LiteLlmGatewayRequest>()
    var requestIndex = 0
    var supplementProviderCalls = 0
    val gateway = object : LiteLlmGateway {
      override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
        requests += request
        return if (requestIndex++ == 0) {
          LiteLlmGatewayResult(
            requestId = request.requestId,
            status = LiteLlmGatewayStatus.SUCCESS,
            completionMode = LiteLlmCompletionMode.PRIMARY,
            completion = LiteLlmStructuredCompletion(
              toolCalls = listOf(
                LiteLlmStructuredToolCall(
                  id = "call_1",
                  toolName = "workspace_read_file",
                  arguments = JsonObject(
                    mapOf("path" to JsonPrimitive("README.md")),
                  ),
                ),
              ),
            ),
            selectedRoute = selection,
            attempts = listOf(
              LiteLlmAttemptRecord(
                route = selection,
                outcome = LiteLlmAttemptOutcome.SUCCESS,
                outputChars = 0,
                startedAtEpochMs = 31_000L,
                finishedAtEpochMs = 31_001L,
              ),
            ),
            startedAtEpochMs = 31_000L,
            finishedAtEpochMs = 31_001L,
          )
        } else {
          LiteLlmGatewayResult(
            requestId = request.requestId,
            status = LiteLlmGatewayStatus.SUCCESS,
            completionMode = LiteLlmCompletionMode.PRIMARY,
            completion = LiteLlmStructuredCompletion(
              finalText = "I saw the local continuation.",
            ),
            selectedRoute = selection,
            attempts = listOf(
              LiteLlmAttemptRecord(
                route = selection,
                outcome = LiteLlmAttemptOutcome.SUCCESS,
                outputChars = 0,
                startedAtEpochMs = 31_002L,
                finishedAtEpochMs = 31_003L,
              ),
            ),
            startedAtEpochMs = 31_002L,
            finishedAtEpochMs = 31_003L,
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
      config = OpenCrayAgentRuntimeConfig(
        maxTurns = 4,
        maxToolCalls = 2,
        supplementInputProvider = { _, _ ->
          supplementProviderCalls += 1
          if (supplementProviderCalls == 2) {
            listOf(
              OpenCraySupplementInput(
                entryId = "supplement-1",
                text = "Also verify the tests before you answer.",
                createdAtEpochMs = 1_500L,
              ),
            )
          } else {
            emptyList()
          }
        },
        llmMetadata = mapOf(
          "protocol" to "openai",
          "nativeToolCallingAvailable" to "true",
        ),
      ),
      clock = IncrementingClock(start = 31_500L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Read the README and then answer."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("I saw the local continuation.", result.stdout)
    assertEquals(2, requests.size)
    assertEquals("full_rebuild", requests[0].metadata["localContinuationMode"])
    assertEquals("local_delta", requests[1].metadata["localContinuationMode"])
    assertEquals("transcript_delta", requests[1].metadata["localContinuationReason"])
    assertEquals("1", result.metadata["localContinuationUsedCount"])
    assertEquals("0", result.metadata["localContinuationFallbackCount"])
    val firstTurnUserMessages = requests[0].messages
      .filter { message -> message.role == LiteLlmGatewayMessageRole.USER }
      .mapNotNull { message -> message.content }
    assertTrue(firstTurnUserMessages[0].contains("[Tool Protocol]"))
    assertTrue(firstTurnUserMessages.none { message -> message.contains("[Task Metadata]") })
    assertEquals("Read the README and then answer.", firstTurnUserMessages.last())
    val secondTurnUserMessages = requests[1].messages
      .filter { message -> message.role == LiteLlmGatewayMessageRole.USER }
      .mapNotNull { message -> message.content }
    assertTrue(secondTurnUserMessages[0].contains("[Tool Protocol]"))
    assertTrue(secondTurnUserMessages.none { message -> message.contains("[Task Metadata]") })
    assertTrue(secondTurnUserMessages.contains("Read the README and then answer."))
    assertTrue(secondTurnUserMessages.contains("Also verify the tests before you answer."))
    val replayedToolResult = requests[1].messages
      .firstOrNull { message -> message.role == LiteLlmGatewayMessageRole.TOOL }
      ?.toolResult
    assertEquals("call_1", replayedToolResult?.toolCallId)
    assertEquals("workspace_read_file", replayedToolResult?.toolName)
  }

  @Test
  fun runPromptTaskFallsBackToFullRebuildWhenActiveSkillChangesToolPoolFingerprint() {
    val workspaceRoot = temporaryFolder.newFolder("agent-local-continuation-skill-workspace")
    val skillsRoot = temporaryFolder.newFolder("agent-local-continuation-skill-root")
    writeSkill(
      root = skillsRoot,
      relativeDirectory = "ui-ux-pro-max",
      frontMatter = """
        name: ui-ux-pro-max
        description: High-end UI review workflow.
        invocation-control: explicit-only
        user-invocable: true
        allowed-tools: [ read, write ]
      """.trimIndent(),
      body = """
        # UI UX Pro Max

        Audit the current interface first, then apply a concrete design system.
      """.trimIndent(),
    )
    val skillCatalog = SkillCatalogResolver().resolve(listOf(skillsRoot))
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"skill_read","arguments":{"name":"ui-ux-pro-max"}}""",
        """{"type":"final","answer":"Used the rebuilt continuation after the skill activation."}""",
      ),
    )
    var supplementProviderCalls = 0
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
          skillsRoots = listOf(skillsRoot),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(
        maxTurns = 4,
        maxToolCalls = 2,
        sessionContext = AgentRuntimeSessionContext(
          skillInventory = skillCatalog.inventory,
          skillCatalog = skillCatalog,
        ),
        supplementInputProvider = { _, _ ->
          supplementProviderCalls += 1
          if (supplementProviderCalls == 2) {
            listOf(
              OpenCraySupplementInput(
                entryId = "supplement-1",
                text = "Apply the UI workflow to this answer too.",
                createdAtEpochMs = 2_500L,
              ),
            )
          } else {
            emptyList()
          }
        },
      ),
      clock = IncrementingClock(start = 32_500L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Load the UI skill, then follow it."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals(2, gateway.requests.size)
    assertEquals("full_rebuild", gateway.requests[1].metadata["localContinuationMode"])
    assertEquals("tool_pool_changed", gateway.requests[1].metadata["localContinuationReason"])
    assertEquals(
      "tool_pool_changed",
      gateway.requests[1].metadata[LiteLlmMetadataKeys.CONTEXT_CACHE_BREAK_REASON],
    )
    assertEquals("1", result.metadata["localContinuationFallbackCount"])
    assertEquals("full_rebuild", result.metadata["localContinuationLastMode"])
    assertEquals("tool_pool_changed", result.metadata["localContinuationLastReason"])
    assertEquals(
      "tool_pool_changed",
      result.metadata[LiteLlmMetadataKeys.CONTEXT_CACHE_BREAK_REASON],
    )
    assertTrue(gateway.requests[1].prompt.contains("[Active Skill]"))
    assertTrue(gateway.requests[1].prompt.contains("name=ui-ux-pro-max"))
    val secondTurnUserMessages = gateway.requests[1].messages
      .filter { message -> message.role == LiteLlmGatewayMessageRole.USER }
      .mapNotNull { message -> message.content }
    assertTrue(secondTurnUserMessages.contains("Apply the UI workflow to this answer too."))
  }
}
