package com.opencray.app

import android.content.Intent

internal sealed interface RuntimeServiceWakeIntentCommand {
  data class ChatWrite(
    val command: OpenCrayChatWriteCommand,
  ) : RuntimeServiceWakeIntentCommand

  data class Notification(
    val command: RuntimeServiceNotificationCommand,
  ) : RuntimeServiceWakeIntentCommand

  data class ScheduledTask(
    val command: ScheduledTaskWakeCommand,
  ) : RuntimeServiceWakeIntentCommand

  data class RepairSchedules(
    val repairReason: String,
  ) : RuntimeServiceWakeIntentCommand

  data object ResumeInterruptedRuns : RuntimeServiceWakeIntentCommand
}

internal data class RuntimeServiceIntentDescriptor(
  val runtimeTarget: RuntimeServiceTarget = DEFAULT_RUNTIME_SERVICE_TARGET,
  val wakeCommand: RuntimeServiceWakeIntentCommand? = null,
  val requestsRuntimeReset: Boolean = false,
  val requiresBootstrapForeground: Boolean = false,
)

internal fun interface RuntimeServiceIntentDescriptorParser {
  fun parse(intent: Intent?): RuntimeServiceIntentDescriptor
}

internal fun interface RuntimeServiceWakeIntentParser {
  fun parse(intent: Intent?): RuntimeServiceWakeIntentCommand?
}

internal class DefaultRuntimeServiceIntentDescriptorParser(
  private val notificationCommandParser: (Intent?) -> RuntimeServiceNotificationCommand? =
    ::parseRuntimeNotificationCommand,
  private val scheduledTaskWakeCommandParser: (Intent?) -> ScheduledTaskWakeCommand? =
    ::parseScheduledTaskWakeCommand,
  private val commandKindReader: (Intent?) -> String? = { intent ->
    safeStringExtra(intent, EXTRA_RUNTIME_SERVICE_COMMAND_KIND)
      ?.trim()
      ?.takeIf(String::isNotBlank)
  },
  private val commandVersionReader: (Intent?) -> Int = { intent ->
    safeIntExtra(intent, EXTRA_RUNTIME_SERVICE_COMMAND_VERSION, 0)
  },
  private val runtimeTargetReader: (Intent?) -> RuntimeServiceTarget = { intent ->
    RuntimeServiceTarget.fromWireValue(
      safeStringExtra(intent, EXTRA_RUNTIME_SERVICE_TARGET),
    ) ?: DEFAULT_RUNTIME_SERVICE_TARGET
  },
  private val actionReader: (Intent?) -> String? = { intent ->
    runCatching { intent?.action }.getOrNull()
  },
  private val repairReasonReader: (Intent?) -> String? = { intent ->
    safeStringExtra(intent, EXTRA_REPAIR_REASON)
  },
  private val scheduleIdReader: (Intent?) -> String? = { intent ->
    safeStringExtra(intent, EXTRA_SCHEDULE_ID)
  },
  private val scheduleRunIdReader: (Intent?) -> String? = { intent ->
    safeStringExtra(intent, EXTRA_SCHEDULE_RUN_ID)
  },
  private val triggeredAtEpochMsReader: (Intent?) -> Long? = { intent ->
    safeLongExtra(intent, EXTRA_TRIGGERED_AT_EPOCH_MS, -1L)
      ?.takeIf { value -> value >= 0L }
  },
  private val triggerReasonReader: (Intent?) -> String? = { intent ->
    safeStringExtra(intent, EXTRA_TRIGGER_REASON)
  },
  private val targetSessionIdReader: (Intent?) -> String? = { intent ->
    safeStringExtra(intent, EXTRA_TARGET_SESSION_ID)
  },
  private val notificationSessionIdReader: (Intent?) -> String? = { intent ->
    safeStringExtra(intent, RuntimeNotificationIntentExtras.EXTRA_NOTIFICATION_SESSION_ID)
      ?.trim()
      ?.takeIf(String::isNotBlank)
  },
  private val notificationTaskIdReader: (Intent?) -> String? = { intent ->
    safeStringExtra(intent, RuntimeNotificationIntentExtras.EXTRA_NOTIFICATION_TASK_ID)
      ?.trim()
      ?.takeIf(String::isNotBlank)
  },
  private val notificationRunIdReader: (Intent?) -> String? = { intent ->
    safeStringExtra(intent, RuntimeNotificationIntentExtras.EXTRA_NOTIFICATION_RUN_ID)
      ?.trim()
      ?.takeIf(String::isNotBlank)
  },
  private val chatWriteIdentifierReader: (Intent?) -> String? = { intent ->
    safeStringExtra(intent, EXTRA_CHAT_WRITE_IDENTIFIER)
      ?.trim()
      ?.takeIf(String::isNotBlank)
  },
  private val forceRuntimeResetReader: (Intent?) -> Boolean = { intent ->
    safeBooleanExtra(intent, EXTRA_FORCE_RUNTIME_RESET, false)
  },
) : RuntimeServiceIntentDescriptorParser {
  override fun parse(intent: Intent?): RuntimeServiceIntentDescriptor {
    val action = actionReader(intent)
    val commandKind = runtimeServiceCommandKind(
      intent = intent,
      commandKindReader = commandKindReader,
      commandVersionReader = commandVersionReader,
      action = action,
    )
    val wakeCommand = wakeCommandFrom(
      intent = intent,
      action = action,
      commandKind = commandKind,
    )
    return RuntimeServiceIntentDescriptor(
      runtimeTarget = runtimeTargetReader(intent),
      wakeCommand = wakeCommand,
      requestsRuntimeReset = commandKind == COMMAND_KIND_RESET_RUNTIME || forceRuntimeResetReader(intent),
      requiresBootstrapForeground = when (commandKind) {
        COMMAND_KIND_CHAT_WRITE_APPROVE_APPROVAL,
        COMMAND_KIND_CHAT_WRITE_APPROVE_APPROVAL_FOR_SESSION,
        COMMAND_KIND_CHAT_WRITE_REJECT_APPROVAL,
        COMMAND_KIND_CHAT_WRITE_INTERRUPT_RUN,
        COMMAND_KIND_CHAT_WRITE_RETRY_RUN,
        COMMAND_KIND_SCHEDULED_TASK,
        COMMAND_KIND_REPAIR_SCHEDULES,
        COMMAND_KIND_RESET_RUNTIME,
        COMMAND_KIND_RESUME_INTERRUPTED_RUNS,
        -> true

        else -> false
      },
    )
  }

  private fun wakeCommandFrom(
    intent: Intent?,
    action: String?,
    commandKind: String?,
  ): RuntimeServiceWakeIntentCommand? = when (commandKind) {
    COMMAND_KIND_CHAT_WRITE_APPROVE_APPROVAL,
    COMMAND_KIND_CHAT_WRITE_APPROVE_APPROVAL_FOR_SESSION,
    COMMAND_KIND_CHAT_WRITE_REJECT_APPROVAL,
    COMMAND_KIND_CHAT_WRITE_INTERRUPT_RUN,
    COMMAND_KIND_CHAT_WRITE_RETRY_RUN,
    -> parseRuntimeServiceChatWriteWakeCommand(
      commandKind = commandKind,
      identifier = chatWriteIdentifierReader(intent),
    )?.let(RuntimeServiceWakeIntentCommand::ChatWrite)

    COMMAND_KIND_APPROVE_APPROVAL,
    COMMAND_KIND_REJECT_APPROVAL,
    -> parseRuntimeNotificationCommand(
      action = when (commandKind) {
        COMMAND_KIND_APPROVE_APPROVAL -> RuntimeNotificationIntentActions.ACTION_APPROVE_RUNTIME_APPROVAL
        COMMAND_KIND_REJECT_APPROVAL -> RuntimeNotificationIntentActions.ACTION_REJECT_RUNTIME_APPROVAL
        else -> action
      },
      sessionId = notificationSessionIdReader(intent),
      taskId = notificationTaskIdReader(intent),
      runId = notificationRunIdReader(intent),
    )?.let(RuntimeServiceWakeIntentCommand::Notification)

    COMMAND_KIND_SCHEDULED_TASK ->
      parseScheduledTaskWakeCommand(
        action = ACTION_RUN_SCHEDULED_TASK,
        scheduleId = scheduleIdReader(intent),
        scheduleRunId = scheduleRunIdReader(intent),
        triggeredAtEpochMs = triggeredAtEpochMsReader(intent),
        triggerReason = triggerReasonReader(intent),
        targetSessionId = targetSessionIdReader(intent),
      )?.let(RuntimeServiceWakeIntentCommand::ScheduledTask)

    COMMAND_KIND_REPAIR_SCHEDULES ->
      RuntimeServiceWakeIntentCommand.RepairSchedules(normalizedRepairReason(repairReasonReader(intent)))

    COMMAND_KIND_RESUME_INTERRUPTED_RUNS ->
      RuntimeServiceWakeIntentCommand.ResumeInterruptedRuns

    COMMAND_KIND_RESET_RUNTIME,
    null,
    -> notificationCommandParser(intent)
      ?.let(RuntimeServiceWakeIntentCommand::Notification)
      ?: scheduledTaskWakeCommandParser(intent)
        ?.let(RuntimeServiceWakeIntentCommand::ScheduledTask)
      ?: when (action) {
        ACTION_RESUME_INTERRUPTED_RUNS -> RuntimeServiceWakeIntentCommand.ResumeInterruptedRuns
        ACTION_REPAIR_SCHEDULES -> RuntimeServiceWakeIntentCommand.RepairSchedules(
          normalizedRepairReason(repairReasonReader(intent)),
        )

        else -> null
      }

    else -> null
  }
}

internal class DefaultRuntimeServiceWakeIntentParser(
  private val descriptorParser: RuntimeServiceIntentDescriptorParser =
    DefaultRuntimeServiceIntentDescriptorParser(),
) : RuntimeServiceWakeIntentParser {
  override fun parse(intent: Intent?): RuntimeServiceWakeIntentCommand? =
    descriptorParser.parse(intent).wakeCommand
}

private fun normalizedRepairReason(
  repairReason: String?,
): String = repairReason
  ?.trim()
  ?.takeIf(String::isNotBlank)
  ?: ScheduledTaskRepairReasons.WORK_MANAGER

private fun runtimeServiceCommandKindForAction(
  action: String?,
): String? = when (action) {
  ACTION_DISPATCH_CHAT_WRITE -> null
  ACTION_RUN_SCHEDULED_TASK -> COMMAND_KIND_SCHEDULED_TASK
  ACTION_REPAIR_SCHEDULES -> COMMAND_KIND_REPAIR_SCHEDULES
  ACTION_RESET_RUNTIME -> COMMAND_KIND_RESET_RUNTIME
  ACTION_RESUME_INTERRUPTED_RUNS -> COMMAND_KIND_RESUME_INTERRUPTED_RUNS
  RuntimeNotificationIntentActions.ACTION_APPROVE_RUNTIME_APPROVAL -> COMMAND_KIND_APPROVE_APPROVAL
  RuntimeNotificationIntentActions.ACTION_REJECT_RUNTIME_APPROVAL -> COMMAND_KIND_REJECT_APPROVAL
  else -> null
}

private fun runtimeServiceCommandKind(
  intent: Intent?,
  commandKindReader: (Intent?) -> String?,
  commandVersionReader: (Intent?) -> Int,
  action: String?,
): String? = commandKindReader(intent)
  ?.takeIf { commandVersionReader(intent) == RUNTIME_SERVICE_COMMAND_VERSION_CURRENT }
  ?: runtimeServiceCommandKindForAction(action)

private fun parseRuntimeServiceChatWriteWakeCommand(
  commandKind: String?,
  identifier: String?,
): OpenCrayChatWriteCommand? {
  val resolvedIdentifier = identifier?.trim()?.takeIf(String::isNotBlank) ?: return null
  return when (commandKind) {
    COMMAND_KIND_CHAT_WRITE_APPROVE_APPROVAL ->
      OpenCrayChatWriteCommand.ApproveChatApproval(resolvedIdentifier)
    COMMAND_KIND_CHAT_WRITE_APPROVE_APPROVAL_FOR_SESSION ->
      OpenCrayChatWriteCommand.ApproveChatApprovalForSession(resolvedIdentifier)
    COMMAND_KIND_CHAT_WRITE_REJECT_APPROVAL ->
      OpenCrayChatWriteCommand.RejectChatApproval(resolvedIdentifier)
    COMMAND_KIND_CHAT_WRITE_INTERRUPT_RUN ->
      OpenCrayChatWriteCommand.InterruptChatRun(resolvedIdentifier)
    COMMAND_KIND_CHAT_WRITE_RETRY_RUN ->
      OpenCrayChatWriteCommand.RetryChatRun(resolvedIdentifier)
    else -> null
  }
}

internal fun runtimeServiceCommandKindForNotificationAction(
  action: String,
): String? = runtimeServiceCommandKindForAction(action)

private fun safeStringExtra(
  intent: Intent?,
  key: String,
): String? = runCatching {
  intent?.getStringExtra(key)
}.getOrNull()

private fun safeIntExtra(
  intent: Intent?,
  key: String,
  defaultValue: Int,
): Int = runCatching {
  intent?.getIntExtra(key, defaultValue) ?: defaultValue
}.getOrDefault(defaultValue)

private fun safeLongExtra(
  intent: Intent?,
  key: String,
  defaultValue: Long,
): Long? = runCatching {
  intent?.getLongExtra(key, defaultValue)
}.getOrNull()

private fun safeBooleanExtra(
  intent: Intent?,
  key: String,
  defaultValue: Boolean,
): Boolean = runCatching {
  intent?.getBooleanExtra(key, defaultValue) == true
}.getOrDefault(defaultValue)
