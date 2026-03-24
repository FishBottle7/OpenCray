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
    assertTrue(layer.text.contains("Recent successful workspace and delegation observations from the current task are available below."))
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

  @Test
  fun buildLayerIncludesSkillsDiscoveryObservations() {
    val support = RecentToolObservationSupport()

    val layer = requireNotNull(
      support.buildLayer(
        listOf(
          toolResultMessage(
            toolName = "SkillsFind",
            content = "ui-ux-pro-max\tremote\tinstall_ref=ui-ux-pro-max\tsource=skills.sh",
            metadata = mapOf(
              "query" to "ui",
              "providerName" to "skills.sh",
              "remoteResultCount" to "1",
              "localResultCount" to "0",
              "resultCount" to "1",
            ),
          ),
          toolResultMessage(
            toolName = "SkillsInspect",
            content =
              "inspection\tremote_github\tsource_ref=github:opencray/skills\tcandidate_count=2\n" +
                "candidate\tui-ux-pro-max\tdescription=UI helpers\trelative_path=skills/ui-ux-pro-max\n" +
                "candidate\thumanizer\tdescription=Writing polish\trelative_path=skills/humanizer",
            metadata = mapOf(
              "sourceRef" to "github:opencray/skills",
              "sourceType" to "remote_github",
              "candidateCount" to "2",
            ),
          ),
        ),
      ),
    )

    assertTrue(layer.text.contains("SkillsFind query=ui results=1 provider=skills.sh remote=1 local=0"))
    assertTrue(layer.text.contains("SkillsInspect source_ref=github:opencray/skills candidates=2 source_type=remote_github"))
    assertTrue(layer.text.contains("candidate\tui-ux-pro-max"))
  }

  @Test
  fun buildLayerIncludesDelegatedTaskObservations() {
    val support = RecentToolObservationSupport()

    val layer = requireNotNull(
      support.buildLayer(
        listOf(
          toolResultMessage(
            toolName = "Task",
            content = "Subagent completed: README says hello.",
            metadata = mapOf(
              "delegationDescription" to "inspect readme",
              "delegationSubagentType" to "researcher",
              "delegationContextMode" to "minimal",
              "childExecutionState" to "completed",
              "childTurnCount" to "1",
              "childToolCallCount" to "1",
              "childSummaryHeadline" to "README says hello.",
              "childSummaryDetails" to "Read README.md\nSummarized the intro.",
            ),
          ),
        ),
      ),
    )

    assertTrue(layer.text.contains("Task description=inspect readme subagent=researcher state=completed context=minimal turns=1 tool_calls=1"))
    assertTrue(layer.text.contains("Summary: README says hello."))
    assertTrue(layer.text.contains("Detail: Read README.md"))
    assertTrue(layer.text.contains("Detail: Summarized the intro."))
  }

  @Test
  fun findDuplicateDiscoveryCallSkipsDelegationObservationButStillStopsAtMutationBarrier() {
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
        toolResultMessage(
          toolName = "Task",
          content = "Subagent completed: README says hello.",
          metadata = mapOf(
            "delegationDescription" to "inspect readme",
            "delegationSubagentType" to "researcher",
            "childExecutionState" to "completed",
            "childSummaryHeadline" to "README says hello.",
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
          toolName = "Task",
          content = "Subagent completed: README says hello.",
          metadata = mapOf(
            "delegationDescription" to "inspect readme",
            "delegationSubagentType" to "researcher",
            "childExecutionState" to "completed",
            "childSummaryHeadline" to "README says hello.",
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
  fun findDuplicateSkillsInspectCallStopsAtSkillsMutationBarrier() {
    val support = RecentToolObservationSupport()
    val duplicateCall = AgentToolCall(
      toolName = "SkillsInspect",
      arguments = buildJsonObject {
        put("source_ref", "github:opencray/skills")
      },
    )

    val duplicateHit = support.findDuplicateDiscoveryCall(
      messages = listOf(
        toolResultMessage(
          toolName = "SkillsInspect",
          content = "inspection\tremote_github\tsource_ref=github:opencray/skills\tcandidate_count=2",
          metadata = mapOf(
            "sourceRef" to "github:opencray/skills",
            "sourceType" to "remote_github",
            "candidateCount" to "2",
          ),
        ),
      ),
      call = duplicateCall,
    )

    assertNotNull(duplicateHit)
    assertTrue(requireNotNull(duplicateHit).summaryLine.contains("SkillsInspect source_ref=github:opencray/skills"))

    val blockedByMutation = support.findDuplicateDiscoveryCall(
      messages = listOf(
        toolResultMessage(
          toolName = "SkillsInspect",
          content = "inspection\tremote_github\tsource_ref=github:opencray/skills\tcandidate_count=2",
          metadata = mapOf(
            "sourceRef" to "github:opencray/skills",
            "sourceType" to "remote_github",
            "candidateCount" to "2",
          ),
        ),
        toolResultMessage(
          toolName = "SkillsAdd",
          content = "Installed skill 'ui-ux-pro-max' from the host-managed catalog.",
          metadata = mapOf(
            "skillId" to "ui-ux-pro-max",
          ),
        ),
      ),
      call = duplicateCall,
    )

    assertNull(blockedByMutation)
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
    kind = RuntimeConversationMessageKind.TOOL_RESULT,
    toolResult = RuntimeConversationToolResult(
      toolName = toolName,
      status = AgentToolResultStatus.SUCCESS.name.lowercase(),
      isError = false,
    ),
  )
}
