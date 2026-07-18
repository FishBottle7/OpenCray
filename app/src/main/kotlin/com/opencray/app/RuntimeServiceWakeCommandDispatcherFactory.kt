package com.opencray.app

import android.content.Context
import android.content.Intent
import android.util.Log

internal interface RuntimeServiceWakeCommandDispatcher {
  fun dispatch(intent: Intent?)
}

internal fun interface RuntimeServiceWakeCommandDispatcherFactory {
  fun create(
    appContext: Context,
    dispatcherDependencies: RuntimeServiceWakeCommandDispatcherDependencies,
    gatewayBundle: OpenCrayRuntimeServiceGatewayBundle,
    projectionCoordinator: RuntimeServiceProjectionCoordinator,
  ): RuntimeServiceWakeCommandDispatcher
}

internal object DefaultRuntimeServiceWakeCommandDispatcherFactory :
  RuntimeServiceWakeCommandDispatcherFactory {
  override fun create(
    appContext: Context,
    dispatcherDependencies: RuntimeServiceWakeCommandDispatcherDependencies,
    gatewayBundle: OpenCrayRuntimeServiceGatewayBundle,
    projectionCoordinator: RuntimeServiceProjectionCoordinator,
  ): RuntimeServiceWakeCommandDispatcher = DefaultRuntimeServiceWakeCommandDispatcher(
    appContext = appContext,
    dispatcherDependencies = dispatcherDependencies,
    gatewayBundle = gatewayBundle,
    projectionCoordinator = projectionCoordinator,
  )
}

internal data class RuntimeServiceWakeCommandDispatcherDependencies(
  val scheduledTaskDispatcherDependencies: ScheduledTaskDispatcherDependencies,
  val scheduledTaskRepairDependencies: ScheduledTaskRepairDependencies,
  val resumeInterruptedRuns: (String) -> RuntimeServiceInterruptedRunRepairResult,
  val approvalDecisionAccess: RuntimeServiceApprovalDecisionAccess,
  val refreshServiceWorkState: () -> RuntimeServiceWorkState,
  val runtimeTarget: RuntimeServiceTarget = DEFAULT_RUNTIME_SERVICE_TARGET,
  val scheduledTaskForwarder: (ScheduledTaskWakeCommand, RuntimeServiceTarget) -> Boolean =
    { _, _ -> false },
)

internal class DefaultRuntimeServiceWakeCommandDispatcher(
  private val appContext: Context,
  private val dispatcherDependencies: RuntimeServiceWakeCommandDispatcherDependencies,
  private val gatewayBundle: OpenCrayRuntimeServiceGatewayBundle,
  private val projectionCoordinator: RuntimeServiceProjectionCoordinator,
  private val wakeIntentParser: RuntimeServiceWakeIntentParser =
    DefaultRuntimeServiceWakeIntentParser(),
  private val approvalNotificationDismisser: (Context, String?) -> Unit =
    { context, taskId -> RuntimeNotificationCoordinator.dismissApprovalNotification(context, taskId) },
  private val terminalNotificationDismisser: (Context, String?) -> Unit =
    { context, taskId -> RuntimeNotificationCoordinator.dismissTerminalInterruptedNotification(context, taskId) },
  private val scheduleNotificationDismisser: (Context, String?) -> Unit =
    { context, scheduleId -> RuntimeNotificationCoordinator.dismissScheduleNotifications(context, scheduleId) },
  private val notificationActionFailureReporter:
    (RuntimeServiceNotificationCommand, Throwable) -> Unit = ::reportNotificationActionFailure,
  private val nowEpochMsProvider: () -> Long = System::currentTimeMillis,
) : RuntimeServiceWakeCommandDispatcher {
  override fun dispatch(intent: Intent?) {
    if (!projectionCoordinator.tryAcquireOwnerLease()) {
      return
    }
    when (val command = wakeIntentParser.parse(intent)) {
      is RuntimeServiceWakeIntentCommand.ChatWrite -> {
        try {
          gatewayBundle.dispatchChatWriteCommand(command.command)
          terminalNotificationDismisser(appContext, command.terminalNotificationTaskId)
        } finally {
          refreshAndPersist()
        }
      }

      is RuntimeServiceWakeIntentCommand.Notification -> {
        try {
          handleNotificationCommand(command.command)
        } finally {
          refreshAndPersist()
        }
      }

      is RuntimeServiceWakeIntentCommand.ScheduledTask -> {
        dispatchScheduledTask(command.command)
        refreshAndPersist()
      }

      is RuntimeServiceWakeIntentCommand.ResumeInterruptedRuns -> {
        val result = dispatcherDependencies.resumeInterruptedRuns(command.repairReason)
        projectionCoordinator.onInterruptedRunRepairResult(result)
        refreshAndPersist()
      }

      is RuntimeServiceWakeIntentCommand.RepairSchedules -> {
        dispatcherDependencies
          .scheduledTaskRepairDependencies
          .repairScheduledTasks(command.repairReason)
        refreshAndPersist()
      }

      null -> Unit
    }
  }

  private fun handleNotificationCommand(
    command: RuntimeServiceNotificationCommand,
  ) {
    val succeeded = try {
      when (command) {
        is RuntimeServiceNotificationCommand.ApproveApproval -> {
          dispatcherDependencies.approvalDecisionAccess.approve(
            command.runId ?: command.taskId.orEmpty(),
          )
          true
        }

        is RuntimeServiceNotificationCommand.RejectApproval -> {
          dispatcherDependencies.approvalDecisionAccess.reject(
            command.runId ?: command.taskId.orEmpty(),
          )
          true
        }

        is RuntimeServiceNotificationCommand.RunScheduleNow ->
          nowEpochMsProvider().let { nowEpochMs ->
            dispatchScheduledTask(
              ScheduledTaskWakeCommand(
                scheduleId = command.scheduleId,
                scheduleRunId = scheduledTaskRunId(command.scheduleId, nowEpochMs),
                triggeredAtEpochMs = nowEpochMs,
                triggerReason = ScheduledTaskTriggerReasons.MANUAL,
                targetSessionId = command.sessionId,
              ),
            ).isSuccessfulNotificationActionOutcome()
          }

        is RuntimeServiceNotificationCommand.DisableSchedule -> {
          val dependencies = dispatcherDependencies.scheduledTaskRepairDependencies
          val existing = dependencies.specStore.get(command.scheduleId)
          existing == null || !existing.enabled || dependencies.disableScheduledTask(
            scheduleId = command.scheduleId,
            nowEpochMs = nowEpochMsProvider(),
          )
        }

        is RuntimeServiceNotificationCommand.SnoozeSchedule -> {
          val dependencies = dispatcherDependencies.scheduledTaskRepairDependencies
          val existing = dependencies.specStore.get(command.scheduleId)
          if (existing == null || !existing.enabled) {
            true
          } else {
            nowEpochMsProvider().let { nowEpochMs ->
              dependencies.snoozeScheduledTask(
                scheduleId = command.scheduleId,
                snoozedUntilEpochMs = nowEpochMs + SCHEDULED_TASK_NOTIFICATION_SNOOZE_DELAY_MS,
                nowEpochMs = nowEpochMs,
              )
            }
          }
        }
      }
    } catch (failure: Throwable) {
      notificationActionFailureReporter(command, failure)
      throw failure
    }
    gatewayBundle.notifyChatSnapshotsChanged()
    if (!succeeded) {
      notificationActionFailureReporter(
        command,
        IllegalStateException("Runtime notification action did not complete."),
      )
      return
    }
    when (command) {
      is RuntimeServiceNotificationCommand.ApproveApproval,
      is RuntimeServiceNotificationCommand.RejectApproval,
      -> approvalNotificationDismisser(appContext, command.taskId)

      is RuntimeServiceNotificationCommand.RunScheduleNow ->
        scheduleNotificationDismisser(appContext, command.scheduleId)

      is RuntimeServiceNotificationCommand.DisableSchedule ->
        scheduleNotificationDismisser(appContext, command.scheduleId)

      is RuntimeServiceNotificationCommand.SnoozeSchedule ->
        scheduleNotificationDismisser(appContext, command.scheduleId)
    }
  }

  private fun ScheduledTaskDispatchOutcome?.isSuccessfulNotificationActionOutcome(): Boolean =
    this == null || result == ScheduledTaskRunResult.ACCEPTED ||
      result == ScheduledTaskRunResult.SKIPPED_DUPLICATE

  private fun dispatchScheduledTask(
    command: ScheduledTaskWakeCommand,
  ): ScheduledTaskDispatchOutcome? {
    val scheduledTaskDependencies = dispatcherDependencies.scheduledTaskDispatcherDependencies
    val sessionOwnerTarget = scheduledTaskDependencies.specStore
      .get(command.scheduleId)
      ?.sessionId
      ?.let(scheduledTaskDependencies.hostAccess::sessionOwnerTarget)
    if (
      sessionOwnerTarget != null &&
      sessionOwnerTarget != dispatcherDependencies.runtimeTarget
    ) {
      check(dispatcherDependencies.scheduledTaskForwarder(command, sessionOwnerTarget)) {
        "Unable to forward scheduled task '${command.scheduleId}' to live session owner " +
          "'${sessionOwnerTarget.wireValue}'."
      }
      return null
    }
    val outcome = dispatcherDependencies
      .scheduledTaskDispatcherDependencies
      .createScheduledTaskDispatcher()
      .dispatch(command)
    projectionCoordinator.onScheduledDispatchOutcome(outcome)
    return outcome
  }

  private fun refreshAndPersist() {
    dispatcherDependencies.refreshServiceWorkState()
    projectionCoordinator.persistProjectionSnapshot()
  }
}

private fun reportNotificationActionFailure(
  command: RuntimeServiceNotificationCommand,
  failure: Throwable,
) {
  runCatching {
    Log.e(
      "OpenCrayNotification",
      "Runtime notification action '${command::class.java.simpleName}' failed.",
      failure,
    )
  }
}
