package com.opencray.runtime

internal fun deriveContextCacheBreakReason(
  localContinuationReason: String?,
  hasHistoricalResponsesContinuation: Boolean = false,
): String? {
  val reason = localContinuationReason?.trim()?.takeIf(String::isNotBlank) ?: return null
  return when (reason) {
    "tool_pool_changed" -> "tool_pool_changed"
    "tool_schema_changed" -> "tool_schema_changed"
    "user_setting_changed" -> "user_setting_changed"
    "anchor_changed" -> "system_prefix_changed"
    "durable_context_changed" -> "durable_context_changed"
    "dynamic_context_changed" -> "dynamic_context_changed"
    "front_context_changed" -> "dynamic_context_changed"
    "responses_context_update_chain_limit" -> "dynamic_context_changed"
    "responses_context_update_too_large" -> "dynamic_context_changed"
    "transcript_mismatch" -> "replay_projection_changed"
    "responses_restored_replay_required" -> "continuation_lineage_untrusted"
    "responses_shape_unavailable" -> "continuation_lineage_untrusted"
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
    -> "continuation_lineage_untrusted"
    "responses_continuation_disabled",
    "responses_lineage_unavailable",
    "responses_legacy_json_fallback_enabled"
    -> if (hasHistoricalResponsesContinuation) "continuation_lineage_untrusted" else null
    "no_envelope",
    "steady_turn",
    "transcript_delta",
    "responses_previous_response_id"
    -> null
    else -> null
  }
}
