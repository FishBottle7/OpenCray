package com.opencray.runtime.subagent

import com.opencray.runtime.OpenCrayPromptResumeState
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SubAgentApprovalResumeMetadataTest {
  private val json = Json

  @Test
  fun encodeWritesHandleIdAlongsideLegacyAgentIdKey() {
    val metadata = SubAgentApprovalResumeMetadata.encodeToMetadata(
      resume = SubAgentApprovalResume(
        approvedToolName = "Read",
        promptResumeState = promptResumeState(),
        agentId = "child-1",
        childRunId = "run-child",
        childTaskId = "task-child",
      ),
      json = json,
    )

    assertEquals("child-1", metadata[SubAgentApprovalResumeMetadata.KEY_HANDLE_ID])
    assertEquals("child-1", metadata[SubAgentApprovalResumeMetadata.KEY_AGENT_ID])
  }

  @Test
  fun decodeAcceptsLegacyAgentIdOnlyMetadata() {
    val resumeMetadata = SubAgentApprovalResumeMetadata.encodeToMetadata(
      resume = SubAgentApprovalResume(
        approvedToolName = "Read",
        promptResumeState = promptResumeState(),
        agentId = "child-legacy",
      ),
      json = json,
    ).toMutableMap().apply {
      remove(SubAgentApprovalResumeMetadata.KEY_HANDLE_ID)
    }

    val decoded = SubAgentApprovalResumeMetadata.decodeFromMetadata(
      metadata = resumeMetadata,
      json = json,
    )

    assertNotNull(decoded)
    assertEquals("child-legacy", decoded?.handleId)
    assertEquals("child-legacy", decoded?.agentId)
  }

  private fun promptResumeState(): OpenCrayPromptResumeState = OpenCrayPromptResumeState(
    turnIndex = 0,
    toolCallCount = 0,
  )
}
