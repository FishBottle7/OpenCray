package com.opencray.app

import com.opencray.runtime.OpenCrayFinalAttachment

internal interface OpenCrayChatRuntimeGateway {
  fun loadChatSnapshot(): Map<String, Any?>

  fun observeChat(listener: (Map<String, Any?>) -> Unit): () -> Unit

  fun loadChatRuntimeSnapshot(): Map<String, Any?>

  fun observeLiveAssistantDraftEvents(listener: (Map<String, Any?>) -> Unit): () -> Unit

  fun observeRuntimeEventDeltas(listener: (Map<String, Any?>) -> Unit): () -> Unit = {}

  fun loadChatRunSnapshot(runId: String): Map<String, Any?>?

  fun waitForChatRun(
    runId: String,
    timeoutMs: Long,
  ): Map<String, Any?>?

  fun observeChatRuntime(listener: (Map<String, Any?>) -> Unit): () -> Unit

  fun refreshSandboxSessionInfo()

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

internal interface OpenCrayRuntimeServiceChatGateway : OpenCrayChatRuntimeGateway {
  fun notifyChatSnapshotsChanged()
}

internal sealed interface OpenCrayChatWriteCommand {
  data object RefreshSandboxSessionInfo : OpenCrayChatWriteCommand

  data class ApplyMemoryDebugAction(
    val recordId: String,
    val actionId: String,
  ) : OpenCrayChatWriteCommand

  data object CreateChatSession : OpenCrayChatWriteCommand

  data class CopyChatSession(val sessionId: String) : OpenCrayChatWriteCommand

  data class DeleteChatSession(val sessionId: String) : OpenCrayChatWriteCommand

  data class SelectChatSession(val sessionId: String) : OpenCrayChatWriteCommand

  data class BranchChatSessionFromMessage(
    val sessionId: String,
    val messageId: String,
  ) : OpenCrayChatWriteCommand

  data class DeleteChatMessage(
    val sessionId: String,
    val messageId: String,
  ) : OpenCrayChatWriteCommand

  data class RecallChatMessage(
    val sessionId: String,
    val messageId: String,
  ) : OpenCrayChatWriteCommand

  data class SubmitChatMessage(
    val text: String,
    val attachments: List<OpenCrayFinalAttachment>,
  ) : OpenCrayChatWriteCommand

  data class ApproveChatApproval(val taskIdOrRunId: String) : OpenCrayChatWriteCommand

  data class ApproveChatApprovalForSession(val taskIdOrRunId: String) : OpenCrayChatWriteCommand

  data class RejectChatApproval(val taskIdOrRunId: String) : OpenCrayChatWriteCommand

  data class InterruptChatRun(val taskIdOrRunId: String) : OpenCrayChatWriteCommand

  data class RetryChatRun(val taskIdOrRunId: String) : OpenCrayChatWriteCommand
}

internal sealed interface OpenCrayChatWriteDispatchResult {
  data object Completed : OpenCrayChatWriteDispatchResult

  data class Payload(
    val value: Map<String, Any?>?,
  ) : OpenCrayChatWriteDispatchResult
}

internal fun OpenCrayChatRuntimeGateway.dispatchChatWriteCommand(
  command: OpenCrayChatWriteCommand,
): OpenCrayChatWriteDispatchResult = when (command) {
  OpenCrayChatWriteCommand.RefreshSandboxSessionInfo -> {
    refreshSandboxSessionInfo()
    OpenCrayChatWriteDispatchResult.Completed
  }

  is OpenCrayChatWriteCommand.ApplyMemoryDebugAction -> OpenCrayChatWriteDispatchResult.Payload(
    applyMemoryDebugAction(
      recordId = command.recordId,
      actionId = command.actionId,
    ),
  )

  OpenCrayChatWriteCommand.CreateChatSession -> {
    createChatSession()
    OpenCrayChatWriteDispatchResult.Completed
  }

  is OpenCrayChatWriteCommand.CopyChatSession -> {
    copyChatSession(command.sessionId)
    OpenCrayChatWriteDispatchResult.Completed
  }

  is OpenCrayChatWriteCommand.DeleteChatSession -> {
    deleteChatSession(command.sessionId)
    OpenCrayChatWriteDispatchResult.Completed
  }

  is OpenCrayChatWriteCommand.SelectChatSession -> {
    selectChatSession(command.sessionId)
    OpenCrayChatWriteDispatchResult.Completed
  }

  is OpenCrayChatWriteCommand.BranchChatSessionFromMessage -> {
    branchChatSessionFromMessage(command.sessionId, command.messageId)
    OpenCrayChatWriteDispatchResult.Completed
  }

  is OpenCrayChatWriteCommand.DeleteChatMessage -> {
    deleteChatMessage(command.sessionId, command.messageId)
    OpenCrayChatWriteDispatchResult.Completed
  }

  is OpenCrayChatWriteCommand.RecallChatMessage -> {
    recallChatMessage(command.sessionId, command.messageId)
    OpenCrayChatWriteDispatchResult.Completed
  }

  is OpenCrayChatWriteCommand.SubmitChatMessage -> OpenCrayChatWriteDispatchResult.Payload(
    submitChatMessage(
      text = command.text,
      attachments = command.attachments,
    ),
  )

  is OpenCrayChatWriteCommand.ApproveChatApproval -> {
    approveChatApproval(command.taskIdOrRunId)
    OpenCrayChatWriteDispatchResult.Completed
  }

  is OpenCrayChatWriteCommand.ApproveChatApprovalForSession -> {
    approveChatApprovalForSession(command.taskIdOrRunId)
    OpenCrayChatWriteDispatchResult.Completed
  }

  is OpenCrayChatWriteCommand.RejectChatApproval -> {
    rejectChatApproval(command.taskIdOrRunId)
    OpenCrayChatWriteDispatchResult.Completed
  }

  is OpenCrayChatWriteCommand.InterruptChatRun -> {
    interruptChatRun(command.taskIdOrRunId)
    OpenCrayChatWriteDispatchResult.Completed
  }

  is OpenCrayChatWriteCommand.RetryChatRun -> {
    retryChatRun(command.taskIdOrRunId)
    OpenCrayChatWriteDispatchResult.Completed
  }
}
