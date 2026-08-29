package com.opencray.app

internal class ServiceOwnedChatApprovalAccess(
  private val approveChatApprovalHandler: (String) -> Unit,
  private val approveChatApprovalForSessionHandler: (String) -> Unit,
  private val approveChatApprovalAsBatchHandler: (String) -> Unit,
  private val rejectChatApprovalHandler: (String) -> Unit,
) {
  fun approveChatApproval(taskIdOrRunId: String) {
    approveChatApprovalHandler(taskIdOrRunId)
  }

  fun approveChatApprovalForSession(taskIdOrRunId: String) {
    approveChatApprovalForSessionHandler(taskIdOrRunId)
  }

  fun approveChatApprovalAsBatch(taskIdOrRunId: String) {
    approveChatApprovalAsBatchHandler(taskIdOrRunId)
  }

  fun rejectChatApproval(taskIdOrRunId: String) {
    rejectChatApprovalHandler(taskIdOrRunId)
  }
}
