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
