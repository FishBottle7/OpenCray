package com.opencray.policy

enum class SafetyAutomationMode(
  val wireValue: String,
  val chatMetadataLabel: String,
  val executionMode: ExecutionMode,
) {
  SAFE(
    wireValue = "safe",
    chatMetadataLabel = "SAFE",
    executionMode = ExecutionMode.SAFE,
  ),
  AUTO(
    wireValue = "auto",
    chatMetadataLabel = "AUTO",
    executionMode = ExecutionMode.AUTO,
  ),
  DEV(
    wireValue = "dev",
    chatMetadataLabel = "DEV",
    executionMode = ExecutionMode.DEVELOPER,
  ),
  ;

  companion object {
    fun fromWireValue(rawValue: String?): SafetyAutomationMode = entries.firstOrNull { mode ->
      mode.wireValue.equals(rawValue?.trim(), ignoreCase = true)
    } ?: AUTO
  }
}

enum class ToolPolicyOverride(
  val wireValue: String,
) {
  INHERIT("inherit"),
  ASK("ask"),
  ALLOW("allow"),
  BLOCK("block"),
  ;

  companion object {
    fun fromWireValue(rawValue: String?): ToolPolicyOverride = entries.firstOrNull { mode ->
      mode.wireValue.equals(rawValue?.trim(), ignoreCase = true)
    } ?: INHERIT
  }
}

enum class WorkspaceAccessProfile(
  val wireValue: String,
) {
  WORK("work"),
  ASK("ask"),
  OPEN("open"),
  ;

  companion object {
    fun fromWireValue(rawValue: String?): WorkspaceAccessProfile = entries.firstOrNull { profile ->
      profile.wireValue.equals(rawValue?.trim(), ignoreCase = true)
    } ?: WORK
  }
}

enum class ExternalAccessMode(
  val wireValue: String,
) {
  BLOCK_ALL("block_all"),
  SELECT_PATHS("select_paths"),
  ;

  companion object {
    fun fromWireValue(rawValue: String?): ExternalAccessMode = entries.firstOrNull { mode ->
      mode.wireValue.equals(rawValue?.trim(), ignoreCase = true)
    } ?: SELECT_PATHS
  }
}

object SafetySettingsMetadataKeys {
  const val EXECUTION_MODE = "executionMode"
  const val CHAT_MODE = "chatMode"
  const val FILE_CHANGES_POLICY_ID = "fileChangesPolicyId"
  const val FILE_DELETES_POLICY_ID = "fileDeletesPolicyId"
  const val SHELL_COMMANDS_POLICY_ID = "shellCommandsPolicyId"
}
