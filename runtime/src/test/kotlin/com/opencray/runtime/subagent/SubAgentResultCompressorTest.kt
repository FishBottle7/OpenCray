package com.opencray.runtime.subagent

import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.runtime.OpenCrayPromptCheckpointBoundary
import com.opencray.runtime.OpenCrayPromptResumeMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentResultCompressorTest {
  @Test
  fun compressSuccessBuildsCompletedSummaryAndDetails() {
    val compressed = SubAgentResultCompressor.compress(
      result(
        status = ExecutionStatus.SUCCESS,
        stdout = """
          README says hello.
          Runtime module owns tool dispatch.
          Next inspect policy metadata.
        """.trimIndent(),
      ),
    )

    assertEquals(SubAgentExecutionState.COMPLETED, compressed.state)
    assertEquals(SubAgentContinuationKind.NONE, compressed.continuationKind)
    assertFalse(compressed.resumable)
    assertFalse(compressed.requiresUserAction)
    assertFalse(compressed.isHighRisk)
    assertEquals("README says hello.", compressed.headline)
    assertEquals(
      listOf(
        "Runtime module owns tool dispatch.",
        "Next inspect policy metadata.",
      ),
      compressed.detailLines,
    )
    assertTrue(compressed.summaryText().contains("Subagent completed: README says hello."))
    assertEquals("completed", compressed.metadata()[SubAgentResultMetadataKeys.EXECUTION_STATE])
    assertEquals("README says hello.", compressed.metadata()[SubAgentResultMetadataKeys.SUMMARY_HEADLINE])
    assertEquals("2", compressed.metadata()[SubAgentResultMetadataKeys.SUMMARY_DETAIL_COUNT])
  }

  @Test
  fun compressCompletedFinalizationCheckpointDoesNotExposeContinuation() {
    val compressed = SubAgentResultCompressor.compress(
      result(
        status = ExecutionStatus.SUCCESS,
        stdout = "README says hello.",
        metadata = mapOf(
          OpenCrayPromptResumeMetadata.KEY_PROMPT_RESUME_JSON to """{"turnIndex":1}""",
          OpenCrayPromptResumeMetadata.KEY_PROMPT_CHECKPOINT_BOUNDARY to
            OpenCrayPromptCheckpointBoundary.FINALIZATION_COMPLETE.wireValue,
        ),
      ),
    )

    assertEquals(SubAgentExecutionState.COMPLETED, compressed.state)
    assertEquals(SubAgentContinuationKind.NONE, compressed.continuationKind)
    assertFalse(compressed.resumable)
  }

  @Test
  fun compressApprovalDeniedMapsToWaitingHighRiskApprovalState() {
    val compressed = SubAgentResultCompressor.compress(
      result(
        status = ExecutionStatus.DENIED,
        errorCode = "HIGH_RISK_APPROVAL_REQUIRED",
        errorMessage = "High-risk approval required before command execution.",
      ),
    )

    assertEquals(SubAgentExecutionState.WAITING_HIGH_RISK_APPROVAL, compressed.state)
    assertEquals(SubAgentContinuationKind.NONE, compressed.continuationKind)
    assertFalse(compressed.resumable)
    assertTrue(compressed.requiresUserAction)
    assertTrue(compressed.isHighRisk)
    assertEquals("High-risk approval required before command execution.", compressed.headline)
    assertTrue(
      compressed.summaryText().contains("Subagent waiting for high-risk approval"),
    )
    assertEquals(
      "waiting_high_risk_approval",
      compressed.metadata()[SubAgentResultMetadataKeys.EXECUTION_STATE],
    )
    assertEquals("HIGH_RISK_APPROVAL_REQUIRED", compressed.metadata()[SubAgentResultMetadataKeys.ERROR_CODE])
  }

  @Test
  fun compressFailedWithoutOutputFallsBackToStableHeadline() {
    val compressed = SubAgentResultCompressor.compress(
      result(
        status = ExecutionStatus.FAILED,
        errorCode = "SUBAGENT_FAILED",
      ),
    )

    assertEquals(SubAgentExecutionState.FAILED, compressed.state)
    assertEquals("Delegated child run failed.", compressed.headline)
    assertEquals("failed", compressed.metadata()[SubAgentResultMetadataKeys.EXECUTION_STATE])
    assertEquals("0", compressed.metadata()[SubAgentResultMetadataKeys.SUMMARY_DETAIL_COUNT])
  }

  @Test
  fun compressApprovalDeniedWithPromptResumeMarksPromptContinuation() {
    val compressed = SubAgentResultCompressor.compress(
      result(
        status = ExecutionStatus.DENIED,
        errorCode = "APPROVAL_REQUIRED",
        errorMessage = "Approval required before reading outside the workspace.",
        metadata = mapOf(
          OpenCrayPromptResumeMetadata.KEY_PROMPT_RESUME_JSON to """{"turnIndex":1}""",
        ),
      ),
    )

    assertEquals(SubAgentExecutionState.WAITING_APPROVAL, compressed.state)
    assertEquals(SubAgentContinuationKind.PROMPT_RESUME, compressed.continuationKind)
    assertTrue(compressed.resumable)
    assertTrue(compressed.requiresUserAction)
    assertFalse(compressed.isHighRisk)
    assertEquals(
      "prompt_resume",
      compressed.metadata()[SubAgentResultMetadataKeys.CONTINUATION_KIND],
    )
    assertEquals(
      "true",
      compressed.metadata()[SubAgentResultMetadataKeys.CONTINUATION_RESUMABLE],
    )
    assertEquals(
      "true",
      compressed.metadata()[SubAgentResultMetadataKeys.CONTINUATION_REQUIRES_USER_ACTION],
    )
    assertEquals(
      "false",
      compressed.metadata()[SubAgentResultMetadataKeys.CONTINUATION_IS_HIGH_RISK],
    )
  }

  @Test
  fun compressMetadataDrivenBackgroundContinuationOverridesStatusInference() {
    val compressed = SubAgentResultCompressor.compress(
      result(
        status = ExecutionStatus.SUCCESS,
        stdout = "Child returned quickly, but should be treated as background queued.",
        metadata = mapOf(
          SubAgentResultMetadataKeys.EXECUTION_STATE to "background_queued",
          SubAgentResultMetadataKeys.CONTINUATION_KIND to "background_resume",
          SubAgentResultMetadataKeys.SUMMARY_HEADLINE to "Delegated child queued for background execution.",
          SubAgentResultMetadataKeys.SUMMARY_DETAILS to "Process id: process-42\nUse ProcessRead to inspect progress.",
        ),
      ),
    )

    assertEquals(SubAgentExecutionState.BACKGROUND_QUEUED, compressed.state)
    assertEquals(SubAgentContinuationKind.BACKGROUND_RESUME, compressed.continuationKind)
    assertTrue(compressed.resumable)
    assertFalse(compressed.requiresUserAction)
    assertFalse(compressed.isHighRisk)
    assertEquals("Delegated child queued for background execution.", compressed.headline)
    assertEquals(
      listOf(
        "Process id: process-42",
        "Use ProcessRead to inspect progress.",
      ),
      compressed.detailLines,
    )
    assertTrue(compressed.summaryText().contains("Subagent queued: Delegated child queued for background execution."))
    assertEquals("background_queued", compressed.metadata()[SubAgentResultMetadataKeys.EXECUTION_STATE])
    assertEquals("background_resume", compressed.metadata()[SubAgentResultMetadataKeys.CONTINUATION_KIND])
    assertEquals("true", compressed.metadata()[SubAgentResultMetadataKeys.CONTINUATION_RESUMABLE])
  }

  @Test
  fun compressMetadataDrivenPromptContinuationCanOverrideDerivedFlagsAndErrorCode() {
    val compressed = SubAgentResultCompressor.compress(
      result(
        status = ExecutionStatus.FAILED,
        errorCode = "SUBAGENT_FAILED",
        errorMessage = "Generic failure text that should be ignored.",
        metadata = mapOf(
          SubAgentResultMetadataKeys.EXECUTION_STATE to "waiting_high_risk_approval",
          SubAgentResultMetadataKeys.CONTINUATION_KIND to "prompt_resume",
          SubAgentResultMetadataKeys.CONTINUATION_RESUMABLE to "true",
          SubAgentResultMetadataKeys.CONTINUATION_REQUIRES_USER_ACTION to "true",
          SubAgentResultMetadataKeys.CONTINUATION_IS_HIGH_RISK to "true",
          SubAgentResultMetadataKeys.SUMMARY_HEADLINE to "High-risk child action needs review before continuing.",
          SubAgentResultMetadataKeys.SUMMARY_DETAILS to "Command: python scripts/deploy.py\nReason: publish release build",
          SubAgentResultMetadataKeys.ERROR_CODE to "HIGH_RISK_APPROVAL_REQUIRED",
        ),
      ),
    )

    assertEquals(SubAgentExecutionState.WAITING_HIGH_RISK_APPROVAL, compressed.state)
    assertEquals(SubAgentContinuationKind.PROMPT_RESUME, compressed.continuationKind)
    assertTrue(compressed.resumable)
    assertTrue(compressed.requiresUserAction)
    assertTrue(compressed.isHighRisk)
    assertEquals("High-risk child action needs review before continuing.", compressed.headline)
    assertEquals(
      listOf(
        "Command: python scripts/deploy.py",
        "Reason: publish release build",
      ),
      compressed.detailLines,
    )
    assertEquals("HIGH_RISK_APPROVAL_REQUIRED", compressed.childErrorCode)
    assertTrue(
      compressed.summaryText().contains(
        "Subagent waiting for high-risk approval: High-risk child action needs review before continuing.",
      ),
    )
    assertEquals(
      "HIGH_RISK_APPROVAL_REQUIRED",
      compressed.metadata()[SubAgentResultMetadataKeys.ERROR_CODE],
    )
  }

  private fun result(
    status: ExecutionStatus,
    stdout: String = "",
    errorCode: String? = null,
    errorMessage: String? = null,
    stderr: String = "",
    metadata: Map<String, String> = emptyMap(),
  ): ExecutionResult = ExecutionResult(
    taskId = "child-task",
    status = status,
    stdout = stdout,
    stderr = stderr,
    errorCode = errorCode,
    errorMessage = errorMessage,
    startedAtEpochMs = 10L,
    finishedAtEpochMs = 20L,
    metadata = metadata,
  )
}
