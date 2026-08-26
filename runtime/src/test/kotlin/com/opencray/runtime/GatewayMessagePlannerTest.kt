package com.opencray.runtime

import com.opencray.llm.LiteLlmGatewayMessage
import com.opencray.llm.LiteLlmGatewayMessageRole
import com.opencray.llm.LiteLlmGatewayToolResult
import com.opencray.llm.LiteLlmStructuredToolCall
import com.opencray.runtime.OpenCrayAgentRuntime.GatewayMessagePlan
import com.opencray.runtime.OpenCrayAgentRuntime.LocalContinuationEnvelope
import com.opencray.runtime.OpenCrayAgentRuntime.LocalContinuationMode
import com.opencray.runtime.OpenCrayAgentRuntime.PromptTurnCursor
import com.opencray.runtime.context.AgentRuntimeSessionContext
import com.opencray.runtime.context.FrontContextZones
import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.context.RuntimeConversationMessageKind
import com.opencray.runtime.context.RuntimeConversationRole
import com.opencray.runtime.context.RuntimeConversationToolCall
import com.opencray.runtime.context.RuntimeConversationToolResult
import org.junit.Assert.assertEquals
import org.junit.Test

class GatewayMessagePlannerTest : OpenCrayAgentRuntimeTestBase() {
  @Test
  fun localDeltaContinuesSyntheticToolCallIdsAfterEnvelopeOccupiedSequence() {
    val runtime = plannerRuntime()
    val frontier = listOf(
      userEntry("Read a.txt and b.txt"),
      toolCallEntry(path = "a.txt"),
      toolResultEntry("a file"),
      toolCallEntry(path = "b.txt"),
      toolResultEntry("b file"),
    )
    val transcript = frontier +
      toolCallEntry(path = "c.txt") +
      toolResultEntry("c file")
    val envelope = localContinuationEnvelope(runtime, stableAnchor = "anchor", frontier = frontier)
    val cursor = promptTurnCursor(transcript = transcript, envelope = envelope)

    val plan = buildPlan(runtime = runtime, cursor = cursor, transcript = transcript)

    assertEquals("[ mode ]", LocalContinuationMode.LOCAL_DELTA, plan.mode)
    assertEquals("[ reason ]", "transcript_delta", plan.reason)
    assertEquals(
      "[ synthetic tool call ids ]",
      listOf("oc-call-1", "oc-call-2", "oc-call-3"),
      toolCallIds(plan.messages),
    )
    assertEquals(
      "[ tool result ids ]",
      listOf("oc-call-1", "oc-call-2", "oc-call-3"),
      toolResultIds(plan.messages),
    )
  }

  @Test
  fun localFrontPatchContinuesSyntheticToolCallIdsAfterEnvelopeOccupiedSequence() {
    val runtime = plannerRuntime()
    val frontier = listOf(
      userEntry("Read a.txt"),
      toolCallEntry(path = "a.txt"),
      toolResultEntry("a file"),
    )
    val transcript = frontier +
      toolCallEntry(path = "b.txt") +
      toolResultEntry("b file")
    val envelope = LocalContinuationEnvelope(
      stableAnchor = "anchor",
      frontContextZones = FrontContextZones(
        durableContextPrompt = "durable-a",
        dynamicContextPrompt = "dynamic-a",
      ),
      transcriptFrontier = frontier,
      gatewayMessages = runtime.buildGatewayMessages(
        frontContextPrompts = listOf("durable-a", "dynamic-a"),
        transcript = frontier,
      ),
    )
    val cursor = promptTurnCursor(transcript = transcript, envelope = envelope)

    val plan = buildPlan(
      runtime = runtime,
      cursor = cursor,
      transcript = transcript,
      frontContextPrompts = listOf("durable-a", "dynamic-b"),
    )

    assertEquals("[ mode ]", LocalContinuationMode.LOCAL_FRONT_PATCH, plan.mode)
    assertEquals("[ reason ]", "dynamic_context_changed", plan.reason)
    assertEquals("[ patched durable prompt ]", "durable-a", plan.messages.first().content)
    assertEquals("[ patched dynamic prompt ]", "dynamic-b", plan.messages[1].content)
    assertEquals(
      "[ synthetic tool call ids ]",
      listOf("oc-call-1", "oc-call-2"),
      toolCallIds(plan.messages),
    )
    assertEquals(
      "[ tool result ids ]",
      listOf("oc-call-1", "oc-call-2"),
      toolResultIds(plan.messages),
    )
  }

  @Test
  fun fullRebuildWithoutEnvelopeStillNumbersSyntheticToolCallIdsFromOne() {
    val runtime = plannerRuntime()
    val transcript = listOf(
      userEntry("Read a.txt"),
      toolCallEntry(path = "a.txt"),
      toolResultEntry("a file"),
      toolCallEntry(path = "b.txt"),
      toolResultEntry("b file"),
    )
    val cursor = promptTurnCursor(transcript = transcript, envelope = null)

    val plan = buildPlan(runtime = runtime, cursor = cursor, transcript = transcript)

    assertEquals("[ mode ]", LocalContinuationMode.FULL_REBUILD, plan.mode)
    assertEquals("[ reason ]", "no_envelope", plan.reason)
    assertEquals(
      "[ synthetic tool call ids ]",
      listOf("oc-call-1", "oc-call-2"),
      toolCallIds(plan.messages),
    )
    assertEquals(
      "[ tool result ids ]",
      listOf("oc-call-1", "oc-call-2"),
      toolResultIds(plan.messages),
    )
  }

  @Test
  fun nextSyntheticToolCallSequenceFromGatewayMessagesScansAssistantToolCallsOnly() {
    assertEquals(
      "[ empty messages ]",
      1,
      nextSyntheticToolCallSequenceFromGatewayMessages(emptyList()),
    )
    val messages = listOf(
      LiteLlmGatewayMessage(
        role = LiteLlmGatewayMessageRole.USER,
        content = "hello",
      ),
      LiteLlmGatewayMessage(
        role = LiteLlmGatewayMessageRole.ASSISTANT,
        toolCalls = listOf(
          LiteLlmStructuredToolCall(id = "oc-call-2", toolName = "a"),
          LiteLlmStructuredToolCall(id = "call_external", toolName = "b"),
          LiteLlmStructuredToolCall(id = null, toolName = "c"),
        ),
      ),
      LiteLlmGatewayMessage(
        role = LiteLlmGatewayMessageRole.TOOL,
        toolResult = LiteLlmGatewayToolResult(
          toolCallId = "oc-call-9",
          toolName = "a",
          content = "done",
        ),
      ),
    )
    assertEquals(
      "[ mixed messages ]",
      3,
      nextSyntheticToolCallSequenceFromGatewayMessages(messages),
    )
  }

  private fun plannerRuntime(): OpenCrayAgentRuntime = OpenCrayAgentRuntime(
    gateway = RecordingGateway(outputs = emptyList()),
    toolDispatcher = OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(workspaceRoots = setOf(temporaryFolder.root.toPath())),
    ),
    config = OpenCrayAgentRuntimeConfig(maxTurns = 4, maxToolCalls = 8),
    clock = IncrementingClock(start = 1_000L)::next,
  )

  private fun userEntry(text: String): RuntimeConversationMessage =
    RuntimeConversationMessage(role = RuntimeConversationRole.USER, content = text)

  private fun toolCallEntry(path: String): RuntimeConversationMessage = RuntimeConversationMessage(
    role = RuntimeConversationRole.ASSISTANT,
    content = """{"tool_name":"workspace_read_file","arguments":{"path":"$path"}}""",
    kind = RuntimeConversationMessageKind.TOOL_CALL,
    toolCall = RuntimeConversationToolCall(toolName = "workspace_read_file"),
  )

  private fun toolResultEntry(contentText: String): RuntimeConversationMessage =
    RuntimeConversationMessage(
      role = RuntimeConversationRole.TOOL,
      content = contentText,
      kind = RuntimeConversationMessageKind.TOOL_RESULT,
      toolResult = RuntimeConversationToolResult(toolName = "workspace_read_file"),
    )

  private fun localContinuationEnvelope(
    runtime: OpenCrayAgentRuntime,
    stableAnchor: String,
    frontier: List<RuntimeConversationMessage>,
  ): LocalContinuationEnvelope = LocalContinuationEnvelope(
    stableAnchor = stableAnchor,
    frontContextZones = normalizeFrontContextZones(emptyList()),
    transcriptFrontier = frontier,
    gatewayMessages = runtime.buildGatewayMessages(
      frontContextPrompts = emptyList(),
      transcript = frontier,
    ),
  )

  private fun promptTurnCursor(
    transcript: List<RuntimeConversationMessage>,
    envelope: LocalContinuationEnvelope?,
  ): PromptTurnCursor = PromptTurnCursor(
    transcript = transcript.toMutableList(),
    sessionContext = AgentRuntimeSessionContext(),
    turn = 4,
    toolCallCount = 2,
    todoWriteUsed = false,
    activeSkillName = null,
    activeSkillActivationSource = null,
    activeSkillPinned = false,
    nextSyntheticToolCallSequence = 1,
    legacyJsonFallbackEnabled = true,
    responsesPreviousResponseId = null,
    responsesProviderLineageId = null,
    responsesLineageTrusted = false,
    responsesFullReplayRequired = false,
    responsesContinuationShape = null,
    responsesPendingMessages = mutableListOf(),
    replayToolResultProjections = mutableMapOf(),
    localContinuationEnvelope = envelope,
    subAgentHandles = mutableMapOf(),
    subAgentExecutionLock = Any(),
  )

  private fun buildPlan(
    runtime: OpenCrayAgentRuntime,
    cursor: PromptTurnCursor,
    transcript: List<RuntimeConversationMessage>,
    frontContextPrompts: List<String> = emptyList(),
  ): GatewayMessagePlan = runtime.buildNonResponsesGatewayMessagePlan(
    cursor = cursor,
    transcript = transcript,
    turnAwareConversation = transcript,
    frontContextPrompts = frontContextPrompts,
    stableAnchor = "anchor",
    toolPoolFingerprint = "test-tool-pool",
    toolSchemaFingerprint = "test-tool-schema",
    requestSettingsFingerprint = "test-request-settings",
  )

  private fun toolCallIds(messages: List<LiteLlmGatewayMessage>): List<String> =
    messages.flatMap { message -> message.toolCalls.mapNotNull { toolCall -> toolCall.id } }

  private fun toolResultIds(messages: List<LiteLlmGatewayMessage>): List<String> =
    messages.mapNotNull { message -> message.toolResult?.toolCallId }
}
