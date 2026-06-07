package com.opencray.app

internal enum class RuntimeServiceTarget(val wireValue: String) {
  DETACHED_BACKGROUND("detached_background"),
  INTERACTIVE("interactive");

  companion object {
    fun fromWireValue(rawValue: String?): RuntimeServiceTarget? {
      val normalizedValue = rawValue
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: return null
      return entries.firstOrNull { target ->
        target.wireValue.equals(normalizedValue, ignoreCase = true)
      }
    }
  }
}

internal val DEFAULT_RUNTIME_SERVICE_TARGET: RuntimeServiceTarget =
  RuntimeServiceTarget.DETACHED_BACKGROUND

internal const val RUNTIME_SERVICE_COMMAND_VERSION_CURRENT: Int = 1
internal const val EXTRA_RUNTIME_SERVICE_COMMAND_VERSION: String = "runtimeServiceCommandVersion"
internal const val EXTRA_RUNTIME_SERVICE_COMMAND_KIND: String = "runtimeServiceCommandKind"
internal const val EXTRA_RUNTIME_SERVICE_TARGET: String = "runtimeServiceTarget"
internal const val COMMAND_KIND_SCHEDULED_TASK: String = "scheduled_task"
internal const val COMMAND_KIND_REPAIR_SCHEDULES: String = "repair_schedules"
internal const val COMMAND_KIND_RESET_RUNTIME: String = "reset_runtime"
internal const val COMMAND_KIND_RESUME_INTERRUPTED_RUNS: String = "resume_interrupted_runs"
internal const val COMMAND_KIND_APPROVE_APPROVAL: String = "approve_approval"
internal const val COMMAND_KIND_REJECT_APPROVAL: String = "reject_approval"
internal const val COMMAND_KIND_RUN_SCHEDULE_NOW: String = "run_schedule_now"
internal const val COMMAND_KIND_DISABLE_SCHEDULE: String = "disable_schedule"
internal const val COMMAND_KIND_CHAT_WRITE_APPROVE_APPROVAL: String = "chat_write_approve_approval"
internal const val COMMAND_KIND_CHAT_WRITE_APPROVE_APPROVAL_FOR_SESSION: String =
  "chat_write_approve_approval_for_session"
internal const val COMMAND_KIND_CHAT_WRITE_REJECT_APPROVAL: String = "chat_write_reject_approval"
internal const val COMMAND_KIND_CHAT_WRITE_INTERRUPT_RUN: String = "chat_write_interrupt_run"
internal const val COMMAND_KIND_CHAT_WRITE_RETRY_RUN: String = "chat_write_retry_run"
internal const val EXTRA_CHAT_WRITE_IDENTIFIER: String = "chatWriteIdentifier"

internal const val ACTION_RESET_RUNTIME: String =
  "com.opencray.app.action.RESET_RUNTIME"
internal const val ACTION_RESUME_INTERRUPTED_RUNS: String =
  "com.opencray.app.action.RESUME_INTERRUPTED_RUNS"
internal const val ACTION_DISPATCH_CHAT_WRITE: String =
  "com.opencray.app.action.DISPATCH_CHAT_WRITE"
internal const val EXTRA_FORCE_RUNTIME_RESET: String = "forceRuntimeReset"
