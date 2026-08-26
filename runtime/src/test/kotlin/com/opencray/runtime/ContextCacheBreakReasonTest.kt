package com.opencray.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContextCacheBreakReasonTest {
  @Test
  fun mapsResponsesPendingStateRebuildReasonsToContinuationLineageUntrusted() {
    listOf(
      "responses_no_pending_messages",
      "responses_pending_tool_result_duplicate_call_id",
      "responses_pending_user_message",
      "responses_pending_system_message",
      "responses_pending_assistant_message",
      "responses_pending_tool_result_missing_payload",
      "responses_pending_tool_result_missing_call_id",
      "responses_pending_tool_result_missing_name",
      "responses_pending_tool_result_blank_content",
      "responses_pending_tool_result_attachment_artifact",
      "responses_pending_tool_result_invalid",
    ).forEach { reason ->
      assertEquals(
        "[ $reason ]",
        "continuation_lineage_untrusted",
        deriveContextCacheBreakReason(localContinuationReason = reason),
      )
    }
  }

  @Test
  fun mapsResponsesContinuationDisabledToContinuationLineageUntrustedOnlyWithHistory() {
    assertEquals(
      "[ responses_continuation_disabled with history ]",
      "continuation_lineage_untrusted",
      deriveContextCacheBreakReason(
        localContinuationReason = "responses_continuation_disabled",
        hasHistoricalResponsesContinuation = true,
      ),
    )
    assertNull(
      "[ responses_continuation_disabled without history ]",
      deriveContextCacheBreakReason(
        localContinuationReason = "responses_continuation_disabled",
        hasHistoricalResponsesContinuation = false,
      ),
    )
  }

  @Test
  fun keepsKnownNonBreakPlanReasonsUnmapped() {
    listOf(
      "no_envelope",
      "steady_turn",
      "transcript_delta",
      "responses_previous_response_id",
    ).forEach { reason ->
      assertNull(
        "[ $reason ]",
        deriveContextCacheBreakReason(localContinuationReason = reason),
      )
    }
  }
}
