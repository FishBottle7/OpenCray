package com.opencray.app

import android.content.Context
import org.opencray.app.R

internal fun localizedHostRuntimeStrings(context: Context): HostRuntimeStrings = HostRuntimeStrings(
  localeTag = LocaleSettingsStore.fromContext(context).loadLanguage().tag,
  shellHostLabel = context.getString(R.string.flutter_host_label_android),
  shellHostSummary = context.getString(R.string.flutter_host_summary_android),
  chatScreenTitle = context.getString(R.string.shell_tab_chat),
  chatModeLabel = context.getString(R.string.chat_mode_auto),
  chatModeSafeLabel = context.getStringByNameOrFallback(
    resourceName = "chat_mode_safe",
    fallback = "SAFE",
  ),
  chatModeDeveloperLabel = context.getStringByNameOrFallback(
    resourceName = "chat_mode_dev",
    fallback = "DEV",
  ),
  chatSessionButtonLabel = context.getString(R.string.chat_sessions_button),
  chatRecentSessionsEyebrow = context.getString(R.string.chat_recent_sessions_eyebrow),
  chatRecentSessionsTitle = context.getString(R.string.chat_recent_sessions_title),
  chatNewSessionLabel = context.getString(R.string.chat_new_session),
  chatDefaultSessionTitle = context.getString(R.string.chat_default_session_title),
  chatMessagesBadge = { count ->
    context.getString(R.string.chat_messages_badge, count)
  },
  chatSummaryReplyInProgress = context.getString(R.string.chat_summary_reply_in_progress),
  chatSummaryAwaitingDirection = context.getString(R.string.chat_summary_awaiting_direction),
  chatSummarySupplementRecorded = context.getString(R.string.chat_summary_supplement_recorded),
  chatSummaryApprovalFollowUpRecorded = context.getString(
    R.string.chat_summary_approval_follow_up_recorded,
  ),
  chatSummaryStartNewSession = context.getString(R.string.chat_summary_start_new_session),
  chatSummaryRestored = context.getString(R.string.chat_summary_restored),
  skillInstalled = { skillId ->
    context.getString(R.string.skills_message_installed, skillId)
  },
  skillRemoved = { skillId ->
    context.getString(R.string.skills_message_removed, skillId)
  },
  skillsReloaded = context.getString(R.string.skills_message_reloaded),
  composerPlaceholder = context.getString(R.string.chat_message_opencray),
  composerRejectedPlaceholder = context.getString(
    R.string.chat_message_opencray_do_differently,
  ),
  agentThinking = context.getString(R.string.chat_agent_thinking),
  agentCancelled = context.getString(R.string.chat_agent_cancelled),
  agentMissingLlm = context.getString(R.string.chat_agent_missing_llm),
  agentEmptyAnswer = context.getString(
    R.string.chat_agent_failed,
    "The model returned an empty answer.",
  ),
  agentFailed = { detail ->
    context.getString(R.string.chat_agent_failed, detail)
  },
  agentAttachmentSaveFailed = { detail ->
    context.getString(R.string.chat_agent_attachment_save_failed, detail)
  },
  chatApprovalApproveLabel = context.getStringByNameOrFallback(
    resourceName = "chat_approval_approve_label",
    fallback = "Approve",
  ),
  chatApprovalApproveForSessionLabel = context.getString(
    R.string.chat_approval_approve_for_session_label,
  ),
  chatApprovalRejectLabel = context.getStringByNameOrFallback(
    resourceName = "chat_approval_reject_label",
    fallback = "Reject",
  ),
  chatApprovalApproved = context.getString(R.string.chat_approval_approved),
  chatApprovalApprovedForSession = context.getString(
    R.string.chat_approval_approved_for_session,
  ),
  chatApprovalRejected = context.getString(R.string.chat_approval_rejected),
)

private fun Context.getStringByNameOrFallback(
  resourceName: String,
  fallback: String,
): String {
  val resourceId = resources.getIdentifier(resourceName, "string", packageName)
  return if (resourceId == 0) {
    fallback
  } else {
    getString(resourceId)
  }
}
