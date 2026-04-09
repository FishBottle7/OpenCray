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
    serviceExecutionCoordinator: RuntimeServiceExecutionCoordinator,
  ): RuntimeServiceWakeCommandDispatcher
}

internal object DefaultRuntimeServiceWakeCommandDispatcherFactory :
  RuntimeServiceWakeCommandDispatcherFactory {
  override fun create(
    appContext: Context,
    dispatcherDependencies: RuntimeServiceWakeCommandDispatcherDependencies,
    gatewayBundle: OpenCrayRuntimeServiceGatewayBundle,
    serviceExecutionCoordinator: RuntimeServiceExecutionCoordinator,
  ): RuntimeServiceWakeCommandDispatcher = DefaultRuntimeServiceWakeCommandDispatcher(
    appContext = appContext,
    dispatcherDependencies = dispatcherDependencies,
    gatewayBundle = gatewayBundle,
    serviceExecutionCoordinator = serviceExecutionCoordinator,
  )
}

internal data class RuntimeServiceWakeCommandDispatcherDependencies(
  val scheduledTaskDispatcherDependencies: ScheduledTaskDispatcherDependencies,
  val scheduledTaskRepairDependencies: ScheduledTaskRepairDependencies,
  val resumeInterruptedRuns: () -> Unit,
  val approvePendingApproval: (String) -> Unit,
  val rejectPendingApproval: (String) -> Unit,
  val refreshServiceWorkState: () -> RuntimeServiceWorkState,
)

internal class DefaultRuntimeServiceWakeCommandDispatcher(
  private val appContext: Context,
  private val dispatcherDependencies: RuntimeServiceWakeCommandDispatcherDependencies,
  private val gatewayBundle: OpenCrayRuntimeServiceGatewayBundle,
  private val serviceExecutionCoordinator: RuntimeServiceExecutionCoordinator,
  private val notificationCommandParser: (Intent?) -> RuntimeServiceNotificationCommand? =
    ::parseRuntimeNotificationCommand,
  private val scheduledTaskWakeCommandParser: (Intent?) -> ScheduledTaskWakeCommand? =
    ::parseScheduledTaskWakeCommand,
  private val actionReader: (Intent?) -> String? = { intent -> intent?.action },
  private val repairReasonReader: (Intent?) -> String? = { intent ->
    intent?.getStringExtra(EXTRA_REPAIR_REASON)
  },
  private val approvalNotificationDismisser: (Context, String?) -> Unit =
    { context, taskId -> RuntimeNotificationCoordinator.dismissApprovalNotification(context, taskId) },
) : RuntimeServiceWakeCommandDispatcher {
  override fun dispatch(intent: Intent?) {
    notificationCommandParser(intent)?.let { command ->
      handleNotificationCommand(command)
      return
    }
    scheduledTaskWakeCommandParser(intent)?.let { scheduledTaskWakeCommand ->
      val outcome = dispatcherDependencies
        .scheduledTaskDispatcherDependencies
        .createScheduledTaskDispatcher()
        .dispatch(scheduledTaskWakeCommand)
      serviceExecutionCoordinator.onScheduledDispatchOutcome(outcome)
      refreshAndPersist()
      return
    }
    when (actionReader(intent)) {
      ACTION_RESUME_INTERRUPTED_RUNS -> {
        dispatcherDependencies.resumeInterruptedRuns()
        refreshAndPersist()
      }

      ACTION_REPAIR_SCHEDULES -> {
        val repairReason = repairReasonReader(intent)
          ?.trim()
          ?.takeIf(String::isNotBlank)
          ?: ScheduledTaskRepairReasons.WORK_MANAGER
        dispatcherDependencies.scheduledTaskRepairDependencies.repairScheduledTasks(repairReason)
        refreshAndPersist()
      }
    }
  }

  private fun handleNotificationCommand(
    command: RuntimeServiceNotificationCommand,
  ) {
    when (command) {
      is RuntimeServiceNotificationCommand.ApproveApproval ->
        dispatcherDependencies.approvePendingApproval(command.runId ?: command.taskId.orEmpty())

      is RuntimeServiceNotificationCommand.RejectApproval ->
        dispatcherDependencies.rejectPendingApproval(command.runId ?: command.taskId.orEmpty())
    }
    gatewayBundle.notifyChatSnapshotsChanged()
    approvalNotificationDismisser(appContext, command.taskId)
    refreshAndPersist()
  }

  private fun refreshAndPersist() {
    dispatcherDependencies.refreshServiceWorkState()
    serviceExecutionCoordinator.persistProjectionSnapshot()
  }
}
