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
