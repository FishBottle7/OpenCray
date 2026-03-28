package com.opencray.app

import android.content.Context
import com.opencray.runtime.OpenCrayFinalAttachment

internal class ServiceBackedOpenCrayChatRuntimeGateway(
  private val serviceClient: OpenCrayRuntimeServiceClient,
  private val fallbackGateway: OpenCrayChatRuntimeGateway,
) : OpenCrayChatRuntimeGateway {
  override fun loadChatSnapshot(): Map<String, Any?> =
    currentReadGateway().loadChatSnapshot()

  override fun observeChat(listener: (Map<String, Any?>) -> Unit): () -> Unit =
    observeWithDynamicGateway(
      currentGateway = ::currentReadGateway,
      observeConnectionState = serviceClient::observePassiveConnectionState,
      observe = { gateway, callback -> gateway.observeChat(callback) },
      listener = listener,
    )

  override fun loadChatRuntimeSnapshot(): Map<String, Any?> =
    currentReadGateway().loadChatRuntimeSnapshot()

  override fun loadChatRunSnapshot(runId: String): Map<String, Any?>? =
    currentReadGateway().loadChatRunSnapshot(runId)

  override fun waitForChatRun(
    runId: String,
    timeoutMs: Long,
  ): Map<String, Any?>? = currentReadGateway().waitForChatRun(runId, timeoutMs)

  override fun observeChatRuntime(listener: (Map<String, Any?>) -> Unit): () -> Unit =
    observeWithDynamicGateway(
      currentGateway = ::currentReadGateway,
      observeConnectionState = serviceClient::observePassiveConnectionState,
      observe = { gateway, callback -> gateway.observeChatRuntime(callback) },
      listener = listener,
    )

  override fun loadMemoryDebugSnapshot(): Map<String, Any?> =
    currentReadGateway().loadMemoryDebugSnapshot()

  override fun loadMemoryDebugLinksSnapshot(): Map<String, Any?> =
    currentReadGateway().loadMemoryDebugLinksSnapshot()

  override fun loadSoulDebugSnapshot(): Map<String, Any?> =
    currentReadGateway().loadSoulDebugSnapshot()

  override fun searchMemoryDebug(
    query: String,
    maxResults: Int,
    minScore: Int,
  ): Map<String, Any?> = currentReadGateway().searchMemoryDebug(query, maxResults, minScore)

  override fun getMemoryDebugSlice(
    path: String,
    fromLine: Int?,
    lines: Int,
  ): Map<String, Any?> = currentReadGateway().getMemoryDebugSlice(path, fromLine, lines)

  override fun applyMemoryDebugAction(
    recordId: String,
    actionId: String,
  ): Map<String, Any?> = currentWriteGateway("applyMemoryDebugAction")
    .applyMemoryDebugAction(recordId, actionId)

  override fun createChatSession() {
    currentWriteGateway("createChatSession").createChatSession()
  }

  override fun copyChatSession(sessionId: String) {
    currentWriteGateway("copyChatSession").copyChatSession(sessionId)
  }

  override fun deleteChatSession(sessionId: String) {
    currentWriteGateway("deleteChatSession").deleteChatSession(sessionId)
  }

  override fun selectChatSession(sessionId: String) {
    currentWriteGateway("selectChatSession").selectChatSession(sessionId)
  }

  override fun branchChatSessionFromMessage(
    sessionId: String,
    messageId: String,
  ) {
    currentWriteGateway("branchChatSessionFromMessage").branchChatSessionFromMessage(sessionId, messageId)
  }

  override fun deleteChatMessage(
    sessionId: String,
    messageId: String,
  ) {
    currentWriteGateway("deleteChatMessage").deleteChatMessage(sessionId, messageId)
  }

  override fun recallChatMessage(
    sessionId: String,
    messageId: String,
  ) {
    currentWriteGateway("recallChatMessage").recallChatMessage(sessionId, messageId)
  }

  override fun submitChatMessage(
    text: String,
    attachments: List<OpenCrayFinalAttachment>,
  ): Map<String, Any?>? = currentWriteGateway("submitChatMessage").submitChatMessage(text, attachments)

  override fun approveChatApproval(taskIdOrRunId: String) {
    currentWriteGateway("approveChatApproval").approveChatApproval(taskIdOrRunId)
  }

  override fun approveChatApprovalForSession(taskIdOrRunId: String) {
    currentWriteGateway("approveChatApprovalForSession")
      .approveChatApprovalForSession(taskIdOrRunId)
  }

  override fun rejectChatApproval(taskIdOrRunId: String) {
    currentWriteGateway("rejectChatApproval").rejectChatApproval(taskIdOrRunId)
  }

  override fun interruptChatRun(taskIdOrRunId: String) {
    currentWriteGateway("interruptChatRun").interruptChatRun(taskIdOrRunId)
  }

  override fun retryChatRun(taskIdOrRunId: String) {
    currentWriteGateway("retryChatRun").retryChatRun(taskIdOrRunId)
  }

  private fun currentReadGateway(): OpenCrayChatRuntimeGateway =
    serviceClient.peekChatRuntimeGateway() ?: fallbackGateway

  private fun currentWriteGateway(operation: String): OpenCrayChatRuntimeGateway =
    requireBinderBackedGateway(
      surface = "Chat runtime",
      operation = operation,
      gateway = serviceClient.awaitChatRuntimeGateway(SERVICE_GATEWAY_BIND_AWAIT_TIMEOUT_MS),
      connectionState = serviceClient.loadConnectionState(),
    )
}

internal fun serviceBackedOpenCrayChatRuntimeGateway(
  context: Context,
): OpenCrayChatRuntimeGateway {
  val appContext = context.applicationContext
  val serviceClient = OpenCrayAgentRuntimeService.ensureClient(appContext)
  return serviceBackedOpenCrayChatRuntimeGateway(
    context = context,
    fallbackGateway = projectionOnlyOpenCrayChatRuntimeGateway(
      context = appContext,
      connectionStateProvider = serviceClient::loadConnectionState,
      bridgeSnapshotProvider = {
        serviceClient.peekSnapshot()?.bridgeSnapshot
      },
    ),
  )
}

internal fun serviceBackedOpenCrayChatRuntimeGateway(
  context: Context,
  fallbackGateway: OpenCrayChatRuntimeGateway,
): OpenCrayChatRuntimeGateway = ServiceBackedOpenCrayChatRuntimeGateway(
  serviceClient = OpenCrayAgentRuntimeService.ensureClient(context.applicationContext),
  fallbackGateway = fallbackGateway,
)
