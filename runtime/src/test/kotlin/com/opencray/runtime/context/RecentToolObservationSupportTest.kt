package com.opencray.runtime.context

import com.opencray.runtime.AgentToolCall
import com.opencray.runtime.AgentToolResult
import com.opencray.runtime.AgentToolResultStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentToolObservationSupportTest {
  private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

  @Test
  fun buildLayerKeepsLatestUniqueDiscoveryObservations() {
    val support = RecentToolObservationSupport(
      config = RecentToolObservationConfig(
        maxEntries = 2,
        maxReadChars = 512,
        maxReadLines = 24,
        maxListChars = 512,
        maxListLines = 16,
      ),
    )

    val layer = requireNotNull(
      support.buildLayer(
        listOf(
          toolResultMessage(
            toolName = "Read",
            content = "README intro",
            metadata = mapOf(
              "filePath" to "README.md",
              "offset" to "1",
              "returnedLineCount" to "4",
              "totalLineCount" to "20",
              "truncated" to "false",
            ),
          ),
          toolResultMessage(
            toolName = "Grep",
            content = "src/App.kt:12:needle",
            metadata = mapOf(
              "pattern" to "needle",
              "path" to "src",
              "matchCount" to "1",
            ),
          ),
          toolResultMessage(
            toolName = "Read",
            content = "NOTES body",
            metadata = mapOf(
              "filePath" to "NOTES.md",
              "offset" to "1",
              "returnedLineCount" to "3",
              "totalLineCount" to "8",
              "truncated" to "false",
            ),
          ),
        ),
      ),
    )

    assertEquals(2, layer.observationCount)
    assertEquals(1, layer.omittedObservationCount)
    assertTrue(layer.text.contains("Recent successful workspace observations from the current task are available below."))
    assertTrue(layer.text.contains("Grep pattern=needle path=src matches=1"))
    assertTrue(layer.text.contains("Read file_path=NOTES.md"))
    assertTrue(!layer.text.contains("Read file_path=README.md"))
  }

  @Test
  fun findDuplicateDiscoveryCallStopsAtMutationBarrier() {
    val support = RecentToolObservationSupport()
    val duplicateCall = AgentToolCall(
      toolName = "Read",
      arguments = buildJsonObject {
        put("file_path", "README.md")
      },
    )

    val duplicateHit = support.findDuplicateDiscoveryCall(
      messages = listOf(
        toolResultMessage(
          toolName = "Read",
          content = "README intro",
          metadata = mapOf(
            "filePath" to "README.md",
            "offset" to "1",
            "returnedLineCount" to "4",
            "totalLineCount" to "20",
            "truncated" to "false",
          ),
        ),
      ),
      call = duplicateCall,
    )

    assertNotNull(duplicateHit)
    assertTrue(requireNotNull(duplicateHit).summaryLine.contains("Read file_path=README.md"))

    val blockedByMutation = support.findDuplicateDiscoveryCall(
      messages = listOf(
        toolResultMessage(
          toolName = "Read",
          content = "README intro",
          metadata = mapOf(
            "filePath" to "README.md",
            "offset" to "1",
            "returnedLineCount" to "4",
            "totalLineCount" to "20",
            "truncated" to "false",
          ),
        ),
        toolResultMessage(
          toolName = "Write",
          content = "Wrote README.md successfully.",
          metadata = mapOf(
            "filePath" to "README.md",
          ),
        ),
      ),
      call = duplicateCall,
    )

    assertNull(blockedByMutation)
  }

  @Test
  fun buildLayerPrefersStableResultLimitMetadataOverLegacyTruncatedField() {
    val support = RecentToolObservationSupport()

    val layer = requireNotNull(
      support.buildLayer(
        listOf(
          toolResultMessage(
            toolName = "Read",
            content = "truncated body",
            metadata = mapOf(
              "filePath" to "README.md",
              "offset" to "1",
              "returnedLineCount" to "12",
              "totalLineCount" to "100",
              "resultLimitApplied" to "true",
              "resultTruncated" to "true",
              "resultLimitKind" to "read_byte_budget",
            ),
          ),
          toolResultMessage(
            toolName = "Grep",
            content = "src/App.kt:12:needle",
            metadata = mapOf(
              "pattern" to "needle",
              "path" to "src",
              "matchCount" to "25",
              "resultLimitApplied" to "true",
              "resultTruncated" to "true",
              "resultLimitKind" to "search_match_limit",
            ),
          ),
        ),
      ),
    )

    assertTrue(layer.text.contains("Read file_path=README.md"))
    assertTrue(layer.text.contains("truncated=true"))
    assertTrue(layer.text.contains("limit_kind=read_byte_budget"))
    assertTrue(layer.text.contains("Grep pattern=needle path=src matches=25 truncated=true limit_kind=search_match_limit"))
  }

  private fun toolResultMessage(
    toolName: String,
    content: String,
    metadata: Map<String, String>,
  ): RuntimeConversationMessage = RuntimeConversationMessage(
    role = RuntimeConversationRole.TOOL,
    content = AgentToolResult(
      toolName = toolName,
      status = AgentToolResultStatus.SUCCESS,
      content = content,
      metadata = metadata,
    ).toObservationText(json),
  )
}
