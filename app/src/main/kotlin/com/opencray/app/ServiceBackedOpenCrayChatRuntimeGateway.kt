package com.opencray.app

import android.content.Context
import com.opencray.runtime.OpenCrayFinalAttachment

internal class ServiceBackedOpenCrayChatRuntimeGateway(
  private val serviceClient: OpenCrayRuntimeServiceClient,
  private val fallbackGateway: OpenCrayChatRuntimeGateway,
) : OpenCrayChatRuntimeGateway {
  override fun loadChatSnapshot(): Map<String, Any?> =
    currentLoadGateway().loadChatSnapshot()

  override fun observeChat(listener: (Map<String, Any?>) -> Unit): () -> Unit =
    observeWithRuntimeGateway(
      observe = { gateway, callback -> gateway.observeChat(callback) },
      listener = listener,
    )

  override fun loadChatRuntimeSnapshot(): Map<String, Any?> =
    currentLoadGateway().loadChatRuntimeSnapshot()

  override fun observeLiveAssistantDraftEvents(listener: (Map<String, Any?>) -> Unit): () -> Unit =
    observeWithStickyRuntimeGateway(
      observe = { gateway, callback -> gateway.observeLiveAssistantDraftEvents(callback) },
      listener = listener,
    )

  override fun loadChatRunSnapshot(runId: String): Map<String, Any?>? =
    currentLoadGateway().loadChatRunSnapshot(runId)

  override fun waitForChatRun(
    runId: String,
    timeoutMs: Long,
  ): Map<String, Any?>? {
    val waitStartedAtEpochMs = System.currentTimeMillis()
    val gateway = serviceClient.peekChatRuntimeGateway()
      ?: serviceClient.awaitChatRuntimeGateway(
        timeoutMs.coerceAtMost(SERVICE_GATEWAY_BIND_AWAIT_TIMEOUT_MS).coerceAtLeast(0L),
      )
      ?: fallbackGateway
    val remainingTimeoutMs = (timeoutMs - (System.currentTimeMillis() - waitStartedAtEpochMs))
      .coerceAtLeast(0L)
    return gateway.waitForChatRun(runId, remainingTimeoutMs)
  }

  override fun observeChatRuntime(listener: (Map<String, Any?>) -> Unit): () -> Unit =
    observeWithStickyRuntimeGateway(
      observe = { gateway, callback -> gateway.observeChatRuntime(callback) },
      listener = listener,
    )

  override fun refreshSandboxSessionInfo() {
    dispatchWriteCommand(
      operation = "refreshSandboxSessionInfo",
      command = OpenCrayChatWriteCommand.RefreshSandboxSessionInfo,
    )
  }

  override fun loadMemoryDebugSnapshot(): Map<String, Any?> =
    currentLoadGateway().loadMemoryDebugSnapshot()

  override fun loadMemoryDebugLinksSnapshot(): Map<String, Any?> =
    currentLoadGateway().loadMemoryDebugLinksSnapshot()

  override fun loadSoulDebugSnapshot(): Map<String, Any?> =
    currentLoadGateway().loadSoulDebugSnapshot()

  override fun searchMemoryDebug(
    query: String,
    maxResults: Int,
    minScore: Int,
  ): Map<String, Any?> = currentLoadGateway().searchMemoryDebug(query, maxResults, minScore)

  override fun getMemoryDebugSlice(
    path: String,
    fromLine: Int?,
    lines: Int,
  ): Map<String, Any?> = currentLoadGateway().getMemoryDebugSlice(path, fromLine, lines)

  override fun applyMemoryDebugAction(
    recordId: String,
    actionId: String,
  ): Map<String, Any?> = requireNotNull(
    dispatchWriteCommand(
      operation = "applyMemoryDebugAction",
      command = OpenCrayChatWriteCommand.ApplyMemoryDebugAction(
        recordId = recordId,
        actionId = actionId,
      ),
    ).payloadOrNull(),
  ) {
    "Chat runtime operation 'applyMemoryDebugAction' completed without a payload."
  }

  override fun createChatSession() {
    dispatchWriteCommand(
      operation = "createChatSession",
      command = OpenCrayChatWriteCommand.CreateChatSession,
    )
  }

  override fun copyChatSession(sessionId: String) {
    dispatchWriteCommand(
      operation = "copyChatSession",
      command = OpenCrayChatWriteCommand.CopyChatSession(sessionId),
    )
  }

  override fun deleteChatSession(sessionId: String) {
    dispatchWriteCommand(
      operation = "deleteChatSession",
      command = OpenCrayChatWriteCommand.DeleteChatSession(sessionId),
    )
  }

  override fun selectChatSession(sessionId: String) {
    dispatchWriteCommand(
      operation = "selectChatSession",
      command = OpenCrayChatWriteCommand.SelectChatSession(sessionId),
    )
  }

  override fun branchChatSessionFromMessage(
    sessionId: String,
    messageId: String,
  ) {
    dispatchWriteCommand(
      operation = "branchChatSessionFromMessage",
      command = OpenCrayChatWriteCommand.BranchChatSessionFromMessage(
        sessionId = sessionId,
        messageId = messageId,
      ),
    )
  }

  override fun deleteChatMessage(
    sessionId: String,
    messageId: String,
  ) {
    dispatchWriteCommand(
      operation = "deleteChatMessage",
      command = OpenCrayChatWriteCommand.DeleteChatMessage(
        sessionId = sessionId,
        messageId = messageId,
      ),
    )
  }

  override fun recallChatMessage(
    sessionId: String,
    messageId: String,
  ) {
    dispatchWriteCommand(
      operation = "recallChatMessage",
      command = OpenCrayChatWriteCommand.RecallChatMessage(
        sessionId = sessionId,
        messageId = messageId,
      ),
    )
  }

  override fun submitChatMessage(
    text: String,
    attachments: List<OpenCrayFinalAttachment>,
  ): Map<String, Any?>? = dispatchWriteCommand(
    operation = "submitChatMessage",
    command = OpenCrayChatWriteCommand.SubmitChatMessage(
      text = text,
      attachments = attachments,
    ),
  ).payloadOrNull()

  override fun approveChatApproval(taskIdOrRunId: String) {
    dispatchWriteCommand(
      operation = "approveChatApproval",
      command = OpenCrayChatWriteCommand.ApproveChatApproval(taskIdOrRunId),
    )
  }

  override fun approveChatApprovalForSession(taskIdOrRunId: String) {
    dispatchWriteCommand(
      operation = "approveChatApprovalForSession",
      command = OpenCrayChatWriteCommand.ApproveChatApprovalForSession(taskIdOrRunId),
    )
  }

  override fun rejectChatApproval(taskIdOrRunId: String) {
    dispatchWriteCommand(
      operation = "rejectChatApproval",
      command = OpenCrayChatWriteCommand.RejectChatApproval(taskIdOrRunId),
    )
  }

  override fun interruptChatRun(taskIdOrRunId: String) {
    dispatchWriteCommand(
      operation = "interruptChatRun",
      command = OpenCrayChatWriteCommand.InterruptChatRun(taskIdOrRunId),
    )
  }

  override fun retryChatRun(taskIdOrRunId: String) {
    dispatchWriteCommand(
      operation = "retryChatRun",
      command = OpenCrayChatWriteCommand.RetryChatRun(taskIdOrRunId),
    )
  }

  private fun currentLoadGateway(): OpenCrayChatRuntimeGateway =
    serviceClient.peekChatRuntimeGateway()
      ?: serviceClient.awaitChatRuntimeGateway(SERVICE_GATEWAY_BIND_AWAIT_TIMEOUT_MS)
      ?: fallbackGateway

  private fun currentObservedGateway(): OpenCrayChatRuntimeGateway =
    serviceClient.peekChatRuntimeGateway() ?: fallbackGateway

  private fun <TPayload> observeWithRuntimeGateway(
    observe: (OpenCrayChatRuntimeGateway, (TPayload) -> Unit) -> (() -> Unit),
    listener: (TPayload) -> Unit,
  ): () -> Unit =
    observeWithDynamicGateway(
      initialGateway = { fallbackGateway },
      currentGateway = ::currentObservedGateway,
      observeConnectionState = serviceClient::observeConnectionState,
      observe = observe,
      listener = listener,
    )

  private fun <TPayload> observeWithStickyRuntimeGateway(
    observe: (OpenCrayChatRuntimeGateway, (TPayload) -> Unit) -> (() -> Unit),
    listener: (TPayload) -> Unit,
  ): () -> Unit {
    val stickyGateway = stickyRuntimeObservedGatewaySelector()
    return observeWithDynamicGateway(
      initialGateway = { fallbackGateway },
      currentGateway = stickyGateway,
      observeConnectionState = serviceClient::observeConnectionState,
      observe = observe,
      listener = listener,
    )
  }

  private fun stickyRuntimeObservedGatewaySelector(): () -> OpenCrayChatRuntimeGateway {
    val lock = Any()
    var lastBinderGateway: OpenCrayChatRuntimeGateway? = null
    return {
      serviceClient.peekChatRuntimeGateway()?.also { gateway ->
        synchronized(lock) {
          lastBinderGateway = gateway
        }
      } ?: synchronized(lock) {
        lastBinderGateway
      } ?: fallbackGateway
    }
  }

  private fun dispatchWriteCommand(
    operation: String,
    command: OpenCrayChatWriteCommand,
  ): OpenCrayChatWriteDispatchResult =
    requireBinderBackedGateway(
      surface = "Chat runtime",
      operation = operation,
      gateway = serviceClient.dispatchChatWriteCommand(command),
      connectionState = serviceClient.loadConnectionState(),
    )
}

private fun OpenCrayChatWriteDispatchResult.payloadOrNull(): Map<String, Any?>? = when (this) {
  OpenCrayChatWriteDispatchResult.Completed -> null
  is OpenCrayChatWriteDispatchResult.Payload -> value
}

internal fun serviceBackedOpenCrayChatRuntimeGateway(
  context: Context,
): OpenCrayChatRuntimeGateway {
  val appContext = context.applicationContext
  val serviceClient = OpenCrayRuntimeServiceAccess.ensureClient(appContext)
  return serviceBackedOpenCrayChatRuntimeGateway(
    context = context,
    fallbackGateway = projectionOnlyOpenCrayChatRuntimeGateway(
      context = appContext,
      connectionStateProvider = serviceClient::loadConnectionState,
      projectionSnapshotProvider = serviceClient::peekProjectionSnapshot,
    ),
  )
}

internal fun serviceBackedOpenCrayChatRuntimeGateway(
  serviceClient: OpenCrayRuntimeServiceClient,
  fallbackGateway: OpenCrayChatRuntimeGateway,
): OpenCrayChatRuntimeGateway = ServiceBackedOpenCrayChatRuntimeGateway(
  serviceClient = serviceClient,
  fallbackGateway = fallbackGateway,
)

internal fun serviceBackedOpenCrayChatRuntimeGateway(
  context: Context,
  fallbackGateway: OpenCrayChatRuntimeGateway,
): OpenCrayChatRuntimeGateway = serviceBackedOpenCrayChatRuntimeGateway(
  serviceClient = OpenCrayRuntimeServiceAccess.ensureClient(context.applicationContext),
  fallbackGateway = fallbackGateway,
)
