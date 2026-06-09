package com.opencray.runtime.context

import com.opencray.llm.LiteLlmGatewayToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolResultReplayProjectorTest {
  @Test
  fun maybeProjectSkipsLargePlainReadLikeContent() {
    val projector = ToolResultReplayProjector()
    val prose = List(320) { index ->
      "Paragraph ${index + 1}: this is a readable implementation note that should remain intact for later reasoning."
    }.joinToString(separator = "\n")

    val projection = projector.maybeProject(
      entry = toolResultEntry(
        toolCallId = "call-read",
        toolName = "Read",
        content = prose,
      ),
      toolResult = LiteLlmGatewayToolResult(
        toolCallId = "call-read",
        toolName = "Read",
        content = prose,
      ),
    )

    assertNull(projection)
  }

  @Test
  fun maybeProjectFreezesLargeStdoutIntoStablePreview() {
    val projector = ToolResultReplayProjector()
    val stdout = (1..900).joinToString(separator = "\n") { index ->
      "stdout-line-${index.toString().padStart(4, '0')} value=${"x".repeat(12)}"
    }

    val projection = projector.maybeProject(
      entry = toolResultEntry(
        toolCallId = "call-process",
        toolName = "ProcessWait",
        content = "Process finished successfully.",
      ),
      toolResult = LiteLlmGatewayToolResult(
        toolCallId = "call-process",
        toolName = "ProcessWait",
        content = "Process finished successfully.",
        stdout = stdout,
        exitCode = 0,
      ),
    )

    assertNotNull(projection)
    val frozen = requireNotNull(projection)
    assertEquals("call-process", frozen.toolCallId)
    assertTrue(frozen.reasons.contains("large_stdout"))
    val replay = frozen.restoredToolResult()
    assertTrue(replay.content.contains("[frozen replay preview]"))
    assertTrue(replay.content.contains("projection_reasons=large_stdout"))
    assertTrue(replay.content.contains("stdout-line-0001"))
    assertTrue(replay.content.contains("stdout-line-0900"))
    assertNull(replay.stdout)
    assertEquals(0, replay.exitCode)
  }

  @Test
  fun maybeProjectRewritesAttachmentLikeContent() {
    val projector = ToolResultReplayProjector()
    val attachmentLike = "data:text/plain;base64," + "A".repeat(4_096)

    val projection = projector.maybeProject(
      entry = toolResultEntry(
        toolCallId = "call-attachment",
        toolName = "workspace_read_file",
        content = attachmentLike,
      ),
      toolResult = LiteLlmGatewayToolResult(
        toolCallId = "call-attachment",
        toolName = "workspace_read_file",
        content = attachmentLike,
      ),
    )

    assertNotNull(projection)
    val replay = requireNotNull(projection).restoredToolResult()
    assertTrue(replay.content.contains("projection_reasons=attachment_like_content"))
    assertNotEquals(attachmentLike, replay.content)
    assertTrue(replay.content.contains("content (attachment-like excerpt):"))
  }

  private fun toolResultEntry(
    toolCallId: String,
    toolName: String,
    content: String,
  ): RuntimeConversationMessage = RuntimeConversationMessage(
    role = RuntimeConversationRole.TOOL,
    content = content,
    kind = RuntimeConversationMessageKind.TOOL_RESULT,
    toolResult = RuntimeConversationToolResult(
      toolCallId = toolCallId,
      toolName = toolName,
      status = "success",
      isError = false,
    ),
  )
}
