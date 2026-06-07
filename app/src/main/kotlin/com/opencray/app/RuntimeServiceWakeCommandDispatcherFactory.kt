package com.opencray.app

import android.content.Context
import android.content.Intent

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
  val resumeInterruptedRuns: () -> Unit,
  val approvalDecisionAccess: RuntimeServiceApprovalDecisionAccess,
  val refreshServiceWorkState: () -> RuntimeServiceWorkState,
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
  private val nowEpochMsProvider: () -> Long = System::currentTimeMillis,
) : RuntimeServiceWakeCommandDispatcher {
  override fun dispatch(intent: Intent?) {
    when (val command = wakeIntentParser.parse(intent)) {
      is RuntimeServiceWakeIntentCommand.ChatWrite -> {
        gatewayBundle.dispatchChatWriteCommand(command.command)
        refreshAndPersist()
      }

      is RuntimeServiceWakeIntentCommand.Notification -> {
        handleNotificationCommand(command.command)
      }

      is RuntimeServiceWakeIntentCommand.ScheduledTask -> {
        dispatchScheduledTask(command.command)
        refreshAndPersist()
      }

      RuntimeServiceWakeIntentCommand.ResumeInterruptedRuns -> {
        dispatcherDependencies.resumeInterruptedRuns()
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
    when (command) {
      is RuntimeServiceNotificationCommand.ApproveApproval ->
        dispatcherDependencies.approvalDecisionAccess.approve(
          command.runId ?: command.taskId.orEmpty(),
        )

      is RuntimeServiceNotificationCommand.RejectApproval ->
        dispatcherDependencies.approvalDecisionAccess.reject(
          command.runId ?: command.taskId.orEmpty(),
        )

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
          )
        }

      is RuntimeServiceNotificationCommand.DisableSchedule ->
        dispatcherDependencies.scheduledTaskRepairDependencies.disableScheduledTask(
          scheduleId = command.scheduleId,
          nowEpochMs = nowEpochMsProvider(),
        )
    }
    gatewayBundle.notifyChatSnapshotsChanged()
    approvalNotificationDismisser(appContext, command.taskId)
    refreshAndPersist()
  }

  private fun dispatchScheduledTask(command: ScheduledTaskWakeCommand) {
    val outcome = dispatcherDependencies
      .scheduledTaskDispatcherDependencies
      .createScheduledTaskDispatcher()
      .dispatch(command)
    projectionCoordinator.onScheduledDispatchOutcome(outcome)
  }

  private fun refreshAndPersist() {
    dispatcherDependencies.refreshServiceWorkState()
    projectionCoordinator.persistProjectionSnapshot()
  }
}
