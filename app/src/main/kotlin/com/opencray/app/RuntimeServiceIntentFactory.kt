package com.opencray.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent

internal fun interface RuntimeServiceComponentProvider {
  fun componentName(context: Context): ComponentName
}

internal fun interface RuntimeServiceIntentBuilder {
  fun create(
    context: Context,
    componentName: ComponentName,
  ): Intent
}

private object DefaultRuntimeServiceComponentProvider : RuntimeServiceComponentProvider {
  override fun componentName(context: Context): ComponentName =
    ComponentName(context, OpenCrayAgentRuntimeService::class.java)
}

private object DefaultRuntimeServiceIntentBuilder : RuntimeServiceIntentBuilder {
  override fun create(
    context: Context,
    componentName: ComponentName,
  ): Intent = Intent().setComponent(componentName)
}

internal class RuntimeServiceIntentFactory(
  private val componentProvider: RuntimeServiceComponentProvider =
    DefaultRuntimeServiceComponentProvider,
  private val intentBuilder: RuntimeServiceIntentBuilder =
    DefaultRuntimeServiceIntentBuilder,
) {
  fun baseIntent(
    context: Context,
    target: RuntimeServiceTarget = DEFAULT_RUNTIME_SERVICE_TARGET,
  ): Intent {
    val appContext = context.applicationContext
    return intentBuilder.create(
      context = appContext,
      componentName = componentProvider.componentName(appContext),
    ).putExtra(EXTRA_RUNTIME_SERVICE_TARGET, target.wireValue)
  }

  fun scheduledTaskIntent(
    context: Context,
    command: ScheduledTaskWakeCommand,
    target: RuntimeServiceTarget = DEFAULT_RUNTIME_SERVICE_TARGET,
  ): Intent = commandIntent(
    context = context,
    target = target,
    action = ACTION_RUN_SCHEDULED_TASK,
    commandKind = COMMAND_KIND_SCHEDULED_TASK,
  )
    .putExtra(EXTRA_SCHEDULE_ID, command.scheduleId)
    .putExtra(EXTRA_SCHEDULE_RUN_ID, command.scheduleRunId)
    .putExtra(EXTRA_TRIGGERED_AT_EPOCH_MS, command.triggeredAtEpochMs)
    .putExtra(EXTRA_TRIGGER_REASON, command.triggerReason)
    .apply {
      command.targetSessionId
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let { sessionId ->
          putExtra(EXTRA_TARGET_SESSION_ID, sessionId)
        }
    }

  fun scheduledRepairIntent(
    context: Context,
    repairReason: String,
    target: RuntimeServiceTarget = DEFAULT_RUNTIME_SERVICE_TARGET,
  ): Intent = commandIntent(
    context = context,
    target = target,
    action = ACTION_REPAIR_SCHEDULES,
    commandKind = COMMAND_KIND_REPAIR_SCHEDULES,
  )
    .putExtra(EXTRA_REPAIR_REASON, repairReason)

  fun resetRuntimeIntent(
    context: Context,
    repairReason: String,
    target: RuntimeServiceTarget = DEFAULT_RUNTIME_SERVICE_TARGET,
  ): Intent = commandIntent(
    context = context,
    target = target,
    action = ACTION_RESET_RUNTIME,
    commandKind = COMMAND_KIND_RESET_RUNTIME,
  )
    .putExtra(EXTRA_REPAIR_REASON, repairReason)
    .putExtra(EXTRA_FORCE_RUNTIME_RESET, true)

  fun resumeInterruptedRunsIntent(
    context: Context,
    repairReason: String,
    target: RuntimeServiceTarget = DEFAULT_RUNTIME_SERVICE_TARGET,
  ): Intent = commandIntent(
    context = context,
    target = target,
    action = ACTION_RESUME_INTERRUPTED_RUNS,
    commandKind = COMMAND_KIND_RESUME_INTERRUPTED_RUNS,
  )
    .putExtra(EXTRA_REPAIR_REASON, repairReason)

  fun chatWriteIntent(
    context: Context,
    command: OpenCrayChatWriteCommand,
    target: RuntimeServiceTarget = DEFAULT_RUNTIME_SERVICE_TARGET,
  ): Intent? {
    val commandKind = runtimeServiceCommandKindForChatWriteWake(command) ?: return null
    val identifier = runtimeServiceChatWriteWakeIdentifier(command) ?: return null
    return commandIntent(
      context = context,
      target = target,
      action = ACTION_DISPATCH_CHAT_WRITE,
      commandKind = commandKind,
    ).putExtra(EXTRA_CHAT_WRITE_IDENTIFIER, identifier)
  }

  fun approvalActionIntent(
    context: Context,
    action: String,
    sessionId: String,
    taskId: String,
    runId: String,
    target: RuntimeServiceTarget = DEFAULT_RUNTIME_SERVICE_TARGET,
  ): Intent = commandIntent(
    context = context,
    target = target,
    action = action,
    commandKind = runtimeServiceCommandKindForNotificationAction(action),
  ).apply {
    putExtra(RuntimeNotificationIntentExtras.EXTRA_NOTIFICATION_SESSION_ID, sessionId)
    putExtra(RuntimeNotificationIntentExtras.EXTRA_NOTIFICATION_TASK_ID, taskId)
    putExtra(RuntimeNotificationIntentExtras.EXTRA_NOTIFICATION_RUN_ID, runId)
  }

  fun scheduleNotificationActionIntent(
    context: Context,
    action: String,
    scheduleId: String,
    sessionId: String?,
    target: RuntimeServiceTarget = DEFAULT_RUNTIME_SERVICE_TARGET,
  ): Intent = commandIntent(
    context = context,
    target = target,
    action = action,
    commandKind = runtimeServiceCommandKindForNotificationAction(action),
  ).apply {
    putExtra(RuntimeNotificationIntentExtras.EXTRA_NOTIFICATION_SCHEDULE_ID, scheduleId)
    sessionId
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { resolvedSessionId ->
        putExtra(RuntimeNotificationIntentExtras.EXTRA_NOTIFICATION_SESSION_ID, resolvedSessionId)
      }
  }

  private fun commandIntent(
    context: Context,
    target: RuntimeServiceTarget,
    action: String,
    commandKind: String?,
  ): Intent = baseIntent(context, target = target)
    .setAction(action)
    .putExtra(EXTRA_RUNTIME_SERVICE_COMMAND_VERSION, RUNTIME_SERVICE_COMMAND_VERSION_CURRENT)
    .apply {
      commandKind
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let { normalizedKind ->
          putExtra(EXTRA_RUNTIME_SERVICE_COMMAND_KIND, normalizedKind)
        }
    }
}

private fun runtimeServiceCommandKindForChatWriteWake(
  command: OpenCrayChatWriteCommand,
): String? = when (command) {
  is OpenCrayChatWriteCommand.ApproveChatApproval -> COMMAND_KIND_CHAT_WRITE_APPROVE_APPROVAL
  is OpenCrayChatWriteCommand.ApproveChatApprovalForSession ->
    COMMAND_KIND_CHAT_WRITE_APPROVE_APPROVAL_FOR_SESSION
  is OpenCrayChatWriteCommand.RejectChatApproval -> COMMAND_KIND_CHAT_WRITE_REJECT_APPROVAL
  is OpenCrayChatWriteCommand.InterruptChatRun -> COMMAND_KIND_CHAT_WRITE_INTERRUPT_RUN
  is OpenCrayChatWriteCommand.RetryChatRun -> COMMAND_KIND_CHAT_WRITE_RETRY_RUN
  else -> null
}

private fun runtimeServiceChatWriteWakeIdentifier(
  command: OpenCrayChatWriteCommand,
): String? = when (command) {
  is OpenCrayChatWriteCommand.ApproveChatApproval -> command.taskIdOrRunId
  is OpenCrayChatWriteCommand.ApproveChatApprovalForSession -> command.taskIdOrRunId
  is OpenCrayChatWriteCommand.RejectChatApproval -> command.taskIdOrRunId
  is OpenCrayChatWriteCommand.InterruptChatRun -> command.taskIdOrRunId
  is OpenCrayChatWriteCommand.RetryChatRun -> command.taskIdOrRunId
  else -> null
}?.trim()?.takeIf(String::isNotBlank)
