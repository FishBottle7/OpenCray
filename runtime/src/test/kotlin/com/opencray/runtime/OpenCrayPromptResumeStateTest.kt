package com.opencray.runtime

import com.opencray.runtime.context.FrozenToolResultReplayProjection
import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.context.RuntimeConversationMessageKind
import com.opencray.runtime.context.RuntimeConversationRole
import com.opencray.runtime.context.RuntimeConversationToolResult
import com.opencray.runtime.subagent.SubAgentApprovalResumeMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

class OpenCrayPromptResumeStateTest {
  @Test
  fun sanitizeToolResultMetadataStripsInternalResumeKeys() {
    val sanitized = OpenCrayPromptResumeMetadata.sanitizeToolResultMetadata(
      mapOf(
        "sourceUrls" to "https://example.com",
        OpenCrayPromptResumeMetadata.KEY_PROMPT_RESUME_JSON to """{"turnIndex":1,"toolCallCount":1}""",
        OpenCrayPromptResumeMetadata.KEY_PROMPT_CHECKPOINT_BOUNDARY to "tool_result_committed",
        SubAgentApprovalResumeMetadata.KEY_PROMPT_RESUME_JSON to """{"approvedToolName":"Read"}""",
      ),
    )

    assertEquals(
      mapOf("sourceUrls" to "https://example.com"),
      sanitized,
    )
  }

  @Test
  fun normalizeStateStripsInternalResumeMetadataFromNestedToolResults() {
    val state = OpenCrayPromptResumeState(
      turnIndex = 2,
      toolCallCount = 1,
      transcript = listOf(
        RuntimeConversationMessage(
          role = RuntimeConversationRole.TOOL,
          kind = RuntimeConversationMessageKind.TOOL_RESULT,
          content = legacyToolResultPayload(),
          toolResult = RuntimeConversationToolResult(
            toolName = "WebSearch",
            status = "success",
            isError = false,
          ),
        ),
      ),
      responsesPendingMessages = listOf(
        OpenCraySerializableGatewayMessage(
          role = "TOOL",
          toolResult = OpenCraySerializableGatewayToolResult(
            toolCallId = "call-1",
            toolName = "WebSearch",
            content = """{"status":"success"}""",
            metadata = legacyToolResultMetadata(),
          ),
        ),
      ),
      replayToolResultProjections = mapOf(
        "projection-1" to FrozenToolResultReplayProjection(
          projectionKey = "projection-1",
          sourceDigest = "digest-1",
          toolCallId = "call-1",
          toolName = "WebSearch",
          reasons = listOf("preview"),
          originalContentChars = 12,
          originalStdoutChars = 0,
          originalStderrChars = 0,
          originalStructuredChars = 0,
          originalTotalChars = 12,
          projectedToolResult = OpenCraySerializableGatewayToolResult(
            toolCallId = "call-1",
            toolName = "WebSearch",
            content = """{"status":"success"}""",
            metadata = legacyToolResultMetadata(),
          ),
        ),
      ),
    )

    val normalized = OpenCrayPromptResumeMetadata.normalizeState(
      state = state,
      json = Json,
    )

    assertFalse(
      normalized.transcript.single().content.contains(
        OpenCrayPromptResumeMetadata.KEY_PROMPT_RESUME_JSON,
      ),
    )
    assertEquals(
      mapOf("sourceUrls" to "https://example.com"),
      normalized.responsesPendingMessages.single().toolResult?.metadata,
    )
    assertEquals(
      mapOf("sourceUrls" to "https://example.com"),
      normalized.replayToolResultProjections.values.single().projectedToolResult.metadata,
    )
  }

  @Test
  fun checkpointEncodeSkipsNonPrimitiveLegacyToolResultMetadataKeys() {
    val state = OpenCrayPromptResumeState(
      turnIndex = 3,
      toolCallCount = 2,
      transcript = listOf(
        RuntimeConversationMessage(
          role = RuntimeConversationRole.TOOL,
          kind = RuntimeConversationMessageKind.TOOL_RESULT,
          content = foreignToolResultPayload(),
          toolResult = RuntimeConversationToolResult(
            toolName = "WebFetch",
            status = "success",
            isError = false,
          ),
        ),
      ),
    )

    val metadata = OpenCrayPromptResumeMetadata.encodeToMetadata(
      state = state,
      json = Json,
      checkpointBoundary = OpenCrayPromptCheckpointBoundary.TOOL_RESULT_COMMITTED,
    )
    val restored = OpenCrayPromptResumeMetadata.decodeFromMetadata(metadata, Json)

    assertEquals(
      "tool_result_committed",
      metadata[OpenCrayPromptResumeMetadata.KEY_PROMPT_CHECKPOINT_BOUNDARY],
    )
    assertNotNull(restored)
    val restoredPayload = Json.parseToJsonElement(restored!!.transcript.single().content).jsonObject
    assertEquals(
      JsonObject(mapOf("sourceUrls" to JsonPrimitive("https://example.com"))),
      restoredPayload.getValue("metadata").jsonObject,
    )
  }

  @Test
  fun checkpointEncodeNormalizesBlankToolResultContentIntoPlaceholder() {
    val state = OpenCrayPromptResumeState(
      turnIndex = 1,
      toolCallCount = 1,
      responsesPendingMessages = listOf(
        OpenCraySerializableGatewayMessage(
          role = "TOOL",
          toolResult = OpenCraySerializableGatewayToolResult(
            toolCallId = "call-blank",
            toolName = "WebSearch",
            content = "   ",
          ),
        ),
      ),
    )

    val metadata = OpenCrayPromptResumeMetadata.encodeToMetadata(state = state, json = Json)
    val restored = OpenCrayPromptResumeMetadata.decodeFromMetadata(metadata, Json)

    assertEquals(
      "[empty tool result]",
      state.responsesPendingMessages.single().toolResult!!.toGatewayToolResult().content,
    )
    assertNotNull(restored)
    assertEquals(
      "[empty tool result]",
      restored!!.responsesPendingMessages.single().toolResult?.content,
    )
  }

  private fun legacyToolResultMetadata(): Map<String, String> = mapOf(
    "sourceUrls" to "https://example.com",
    OpenCrayPromptResumeMetadata.KEY_PROMPT_RESUME_JSON to
      """{"turnIndex":1,"toolCallCount":1}""",
    OpenCrayPromptResumeMetadata.KEY_PROMPT_CHECKPOINT_BOUNDARY to "tool_result_committed",
  )

  private fun foreignToolResultPayload(): String = """
    {"run_id":"run-2","task_id":"task-2","turn":3,"tool_name":"WebFetch","status":"success","content":"done","metadata":{"sourceUrls":"https://example.com","nestedConfig":{"depth":2},"scores":[1,2],"${OpenCrayPromptResumeMetadata.KEY_PROMPT_RESUME_JSON}":"{\"turnIndex\":9}"}}
  """.trimIndent()

  private fun legacyToolResultPayload(): String = """
    {"run_id":"run-1","task_id":"task-1","turn":1,"tool_name":"WebSearch","status":"success","content":"done","metadata":{"sourceUrls":"https://example.com","${OpenCrayPromptResumeMetadata.KEY_PROMPT_RESUME_JSON}":"{\"turnIndex\":1,\"toolCallCount\":1}","${OpenCrayPromptResumeMetadata.KEY_PROMPT_CHECKPOINT_BOUNDARY}":"tool_result_committed"}}
  """.trimIndent()
}
