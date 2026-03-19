package com.opencray.runtime.soul

class SoulTurnResponsePolicyRenderer {
  fun render(policy: SoulTurnResponsePolicy?): String {
    if (policy == null) {
      return ""
    }

    return buildString {
      appendLine("task_priority=${policy.taskPriority.name.lowercase()}")
      appendLine("response_shape=${policy.responseShape.name.lowercase()}")
      appendLine("clarification_mode=${policy.clarificationMode.name.lowercase()}")
      appendLine("reassurance_mode=${policy.reassuranceMode.name.lowercase()}")
      appendLine("relational_check_in_mode=${policy.relationalCheckInMode.name.lowercase()}")
      appendLine("playfulness_mode=${policy.playfulnessMode.name.lowercase()}")
      appendLine("intimacy_mode=${policy.intimacyMode.name.lowercase()}")
      appendList("directives", policy.directives)
    }.trim()
  }

  private fun StringBuilder.appendList(
    name: String,
    values: List<String>,
  ) {
    val normalized = values.map(String::trim).filter(String::isNotBlank)
    if (normalized.isEmpty()) {
      return
    }
    appendLine("$name:")
    normalized.forEach { value ->
      appendLine("- $value")
    }
  }
}
