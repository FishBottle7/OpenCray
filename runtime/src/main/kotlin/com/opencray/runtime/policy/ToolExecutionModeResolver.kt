package com.opencray.runtime.policy

import com.opencray.core.contracts.AgentTask
import com.opencray.policy.ExecutionMode
import com.opencray.policy.SafetySettingsMetadataKeys

internal object ToolExecutionModeResolver {
  fun infer(task: AgentTask): ExecutionMode =
    listOf(
      SafetySettingsMetadataKeys.EXECUTION_MODE,
      SafetySettingsMetadataKeys.CHAT_MODE,
      "mode",
      "modeLabel",
    )
      .firstNotNullOfOrNull { key -> ExecutionMode.fromLabelOrNull(task.metadata[key]) }
      ?: ExecutionMode.AUTO
}
