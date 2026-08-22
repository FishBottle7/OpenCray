package com.opencray.runtime.policy

internal sealed interface ToolRuntimeIntent {
  val categoryWireValue: String

  fun metadata(): Map<String, String>
}

internal enum class ExecutionIntentKind(val wireValue: String) {
  SHELL_COMMAND("shell_command"),
  HOST_COMMAND("host_command"),
  PYTHON_SCRIPT("python_script"),
  MANAGED_COMMAND("managed_command"),
  MANAGED_PYTHON_SCRIPT("managed_python_script"),
}

internal enum class ExecutionTransport(val wireValue: String) {
  FOREGROUND("foreground"),
  MANAGED_PROCESS("managed_process"),
  PYTHON_RUNTIME("python_runtime"),
}

internal data class ExecutionIntent(
  val kind: ExecutionIntentKind,
  val transport: ExecutionTransport,
  val commandPreview: String? = null,
  val scriptPath: String? = null,
  val workingDirectory: String? = null,
) : ToolRuntimeIntent {
  override val categoryWireValue: String = "execution"

  override fun metadata(): Map<String, String> = buildMap {
    put("intentCategory", categoryWireValue)
    put("executionIntentKind", kind.wireValue)
    put("executionTransport", transport.wireValue)
    commandPreview?.takeIf(String::isNotBlank)?.let { put("executionCommandPreview", it) }
    scriptPath?.takeIf(String::isNotBlank)?.let { put("executionScriptPath", it) }
    workingDirectory?.takeIf(String::isNotBlank)?.let { put("executionWorkingDirectory", it) }
  }
}

internal enum class ProcessLifecycleIntentKind(val wireValue: String) {
  TERMINATE("terminate"),
}

internal data class ProcessLifecycleIntent(
  val kind: ProcessLifecycleIntentKind,
  val processId: String,
  val workingDirectory: String? = null,
) : ToolRuntimeIntent {
  override val categoryWireValue: String = "process_lifecycle"

  override fun metadata(): Map<String, String> = buildMap {
    put("intentCategory", categoryWireValue)
    put("processLifecycleIntentKind", kind.wireValue)
    put("intentProcessId", processId)
    workingDirectory?.takeIf(String::isNotBlank)?.let { put("intentWorkingDirectory", it) }
  }
}

internal enum class DelegationIntentKind(val wireValue: String) {
  SUBAGENT_TASK("subagent_task"),
}

internal data class DelegationIntent(
  val kind: DelegationIntentKind,
  val subagentType: String,
  val contextMode: String,
  val contextModeSource: String? = null,
  val description: String? = null,
  val promptPreview: String? = null,
  val allowedToolNames: Set<String> = emptySet(),
) : ToolRuntimeIntent {
  override val categoryWireValue: String = "delegation"

  override fun metadata(): Map<String, String> = buildMap {
    put("intentCategory", categoryWireValue)
    put("delegationIntentKind", kind.wireValue)
    put("delegationSubagentType", subagentType)
    put("delegationContextMode", contextMode)
    contextModeSource
      ?.takeIf(String::isNotBlank)
      ?.let { put("delegationContextModeSource", it) }
    description?.takeIf(String::isNotBlank)?.let { put("delegationDescription", it) }
    promptPreview?.takeIf(String::isNotBlank)?.let { put("delegationPromptPreview", it) }
    if (allowedToolNames.isNotEmpty()) {
      put(
        "delegationAllowedTools",
        allowedToolNames
          .map(String::trim)
          .filter(String::isNotBlank)
          .sorted()
          .joinToString(separator = ","),
      )
    }
  }
}

internal enum class SchedulingIntentKind(val wireValue: String) {
  CREATE_SCHEDULED_TASK("create_scheduled_task"),
  LIST_SCHEDULED_TASKS("list_scheduled_tasks"),
  GET_SCHEDULED_TASK("get_scheduled_task"),
  UPDATE_SCHEDULED_TASK("update_scheduled_task"),
  RUN_SCHEDULED_TASK_NOW("run_scheduled_task_now"),
  SNOOZE_SCHEDULED_TASK("snooze_scheduled_task"),
  DELETE_SCHEDULED_TASK("delete_scheduled_task"),
}

internal data class SchedulingIntent(
  val kind: SchedulingIntentKind,
  val triggerKind: String? = null,
  val sessionMode: String? = null,
  val targetSessionId: String? = null,
  val targetScheduleId: String? = null,
  val title: String? = null,
) : ToolRuntimeIntent {
  override val categoryWireValue: String = "scheduling"

  override fun metadata(): Map<String, String> = buildMap {
    put("intentCategory", categoryWireValue)
    put("schedulingIntentKind", kind.wireValue)
    triggerKind?.takeIf(String::isNotBlank)?.let { put("scheduleTriggerKind", it) }
    sessionMode?.takeIf(String::isNotBlank)?.let { put("scheduleSessionMode", it) }
    targetSessionId?.takeIf(String::isNotBlank)?.let { put("scheduleTargetSessionId", it) }
    targetScheduleId?.takeIf(String::isNotBlank)?.let { put("scheduleTargetScheduleId", it) }
    title?.takeIf(String::isNotBlank)?.let { put("scheduleTitle", it) }
  }
}
