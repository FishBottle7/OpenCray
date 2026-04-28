package com.opencray.runtime

import com.opencray.runtime.context.FrozenToolResultReplayProjection
import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.context.RuntimeConversationMessageKind
import com.opencray.runtime.context.RuntimeConversationRole
import com.opencray.runtime.context.RuntimeConversationToolResult
import com.opencray.runtime.subagent.SubAgentApprovalResumeMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlinx.serialization.json.Json

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

  private fun legacyToolResultMetadata(): Map<String, String> = mapOf(
    "sourceUrls" to "https://example.com",
    OpenCrayPromptResumeMetadata.KEY_PROMPT_RESUME_JSON to
      """{"turnIndex":1,"toolCallCount":1}""",
    OpenCrayPromptResumeMetadata.KEY_PROMPT_CHECKPOINT_BOUNDARY to "tool_result_committed",
  )

  private fun legacyToolResultPayload(): String = """
    {"run_id":"run-1","task_id":"task-1","turn":1,"tool_name":"WebSearch","status":"success","content":"done","metadata":{"sourceUrls":"https://example.com","${OpenCrayPromptResumeMetadata.KEY_PROMPT_RESUME_JSON}":"{\"turnIndex\":1,\"toolCallCount\":1}","${OpenCrayPromptResumeMetadata.KEY_PROMPT_CHECKPOINT_BOUNDARY}":"tool_result_committed"}}
  """.trimIndent()
}
