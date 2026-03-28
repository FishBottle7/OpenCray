package com.opencray.app

import com.opencray.runtime.OpenCrayFinalAttachment

internal interface OpenCrayChatRuntimeGateway {
  fun loadChatSnapshot(): Map<String, Any?>

  fun observeChat(listener: (Map<String, Any?>) -> Unit): () -> Unit

  fun loadChatRuntimeSnapshot(): Map<String, Any?>

  fun loadChatRunSnapshot(runId: String): Map<String, Any?>?

  fun waitForChatRun(
    runId: String,
    timeoutMs: Long,
  ): Map<String, Any?>?

  fun observeChatRuntime(listener: (Map<String, Any?>) -> Unit): () -> Unit

  fun loadMemoryDebugSnapshot(): Map<String, Any?>

  fun loadMemoryDebugLinksSnapshot(): Map<String, Any?>

  fun loadSoulDebugSnapshot(): Map<String, Any?>

  fun searchMemoryDebug(
    query: String,
    maxResults: Int,
    minScore: Int,
  ): Map<String, Any?>

  fun getMemoryDebugSlice(
    path: String,
    fromLine: Int?,
    lines: Int,
  ): Map<String, Any?>

  fun applyMemoryDebugAction(
    recordId: String,
    actionId: String,
  ): Map<String, Any?>

  fun createChatSession()

  fun copyChatSession(sessionId: String)

  fun deleteChatSession(sessionId: String)

  fun selectChatSession(sessionId: String)

  fun branchChatSessionFromMessage(
    sessionId: String,
    messageId: String,
  )

  fun deleteChatMessage(
    sessionId: String,
    messageId: String,
  )

  fun recallChatMessage(
    sessionId: String,
    messageId: String,
  )

  fun submitChatMessage(
    text: String,
    attachments: List<OpenCrayFinalAttachment>,
  ): Map<String, Any?>?

  fun approveChatApproval(taskIdOrRunId: String)

  fun approveChatApprovalForSession(taskIdOrRunId: String)

  fun rejectChatApproval(taskIdOrRunId: String)

  fun interruptChatRun(taskIdOrRunId: String)

  fun retryChatRun(taskIdOrRunId: String)
}
