package com.opencray.app

import kotlin.math.absoluteValue

internal data class RuntimeNotificationKey(
  val tag: String,
  val id: Int,
)

internal object RuntimeNotificationKeys {
  private const val APPROVAL_ID_BASE: Int = 52_100
  private const val APPROVAL_ID_MODULO: Int = 5_000
  private const val TERMINAL_COMPLETED_ID_BASE: Int = 52_300
  private const val TERMINAL_INTERRUPTED_ID_BASE: Int = 52_700
  private const val TERMINAL_ID_MODULO: Int = 4_000
  private const val SCHEDULE_ID_BASE: Int = 53_100
  private const val SCHEDULE_ID_MODULO: Int = 4_000
  private const val RECOVERED_ID_BASE: Int = 53_800
  private const val RECOVERED_ID_MODULO: Int = 1_000

  fun notificationStableHash(key: String, modulo: Int): Int =
    (key.hashCode().absoluteValue % modulo).coerceAtLeast(0)

  fun stableRequestCode(key: String): Int =
    60_000 + notificationStableHash(key, modulo = 30_000)

  fun approvalActionRequestKey(
    action: String,
    sessionId: String,
    runId: String,
    taskId: String,
    executionBinding: RuntimeApprovalExecutionBinding,
  ): String =
    "$action:$sessionId:$runId:$taskId:${executionBinding.identityToken()}"

  fun approvalTag(
    sessionId: String,
    runId: String,
    taskId: String,
    executionBinding: RuntimeApprovalExecutionBinding,
  ): String =
    "approval:$sessionId:$runId:$taskId:${executionBinding.identityToken()}"

  fun approvalId(taskId: String): Int =
    APPROVAL_ID_BASE + notificationStableHash(taskId, modulo = APPROVAL_ID_MODULO)

  fun approvalKey(
    sessionId: String,
    runId: String,
    taskId: String,
    executionBinding: RuntimeApprovalExecutionBinding,
  ): RuntimeNotificationKey = RuntimeNotificationKey(
    tag = approvalTag(
      sessionId = sessionId,
      runId = runId,
      taskId = taskId,
      executionBinding = executionBinding,
    ),
    id = approvalId(taskId),
  )

  fun legacyApprovalId(taskId: String): Int = approvalId(taskId)

  fun terminalTag(
    runId: String,
    taskId: String,
    interrupted: Boolean,
  ): String = "terminal:$runId:$taskId:${if (interrupted) "interrupted" else "completed"}"

  fun terminalId(taskId: String, interrupted: Boolean): Int =
    (if (interrupted) TERMINAL_INTERRUPTED_ID_BASE else TERMINAL_COMPLETED_ID_BASE) +
      notificationStableHash(taskId, modulo = TERMINAL_ID_MODULO)

  fun terminalKey(
    runId: String,
    taskId: String,
    interrupted: Boolean,
  ): RuntimeNotificationKey = RuntimeNotificationKey(
    tag = terminalTag(runId = runId, taskId = taskId, interrupted = interrupted),
    id = terminalId(taskId = taskId, interrupted = interrupted),
  )

  fun legacyTerminalCompletedId(taskId: String): Int = terminalId(taskId, interrupted = false)

  fun legacyTerminalInterruptedId(taskId: String): Int = terminalId(taskId, interrupted = true)

  fun scheduleTag(scheduleId: String, outcome: String): String =
    "schedule:$scheduleId:$outcome"

  fun scheduleIdForOutcome(scheduleId: String, outcome: String): Int =
    SCHEDULE_ID_BASE + notificationStableHash("$scheduleId:$outcome", modulo = SCHEDULE_ID_MODULO)

  fun scheduleKey(scheduleId: String, outcome: String): RuntimeNotificationKey =
    RuntimeNotificationKey(
      tag = scheduleTag(scheduleId, outcome),
      id = scheduleIdForOutcome(scheduleId, outcome),
    )

  fun legacyScheduleIdsForAllOutcomes(scheduleId: String): List<Int> =
    ScheduledTaskRunResult.entries.map { outcome -> scheduleIdForOutcome(scheduleId, outcome.name) }

  fun recoveredTag(processStartId: String): String = "recovered:$processStartId"

  fun recoveredId(processStartId: String): Int =
    RECOVERED_ID_BASE + notificationStableHash(processStartId, modulo = RECOVERED_ID_MODULO)

  fun recoveredKey(processStartId: String): RuntimeNotificationKey =
    RuntimeNotificationKey(
      tag = recoveredTag(processStartId),
      id = recoveredId(processStartId),
    )
}
