package com.opencray.runtime.context

import kotlin.math.min

data class ReplayPressureSnapshot(
  val contextWindowTokens: Int,
  val previousContextWindowTokens: Int? = null,
  val autoCompactTokenLimit: Int,
  val estimatedReplayTokens: Int,
  val tokenThresholdTriggered: Boolean,
  val smallerWindowModelSwitchDetected: Boolean = false,
)

class ReplayPressureEvaluator(
  private val budgetPolicy: ModelContextBudgetPolicy = ModelContextBudgetPolicy(),
) {
  fun evaluate(
    conversation: List<RuntimeConversationMessage>,
    llmMetadata: Map<String, String>,
  ): ReplayPressureSnapshot {
    val envelope = budgetPolicy.resolve(llmMetadata)
    val contextWindowLimit = (envelope.contextWindowTokens * AUTO_COMPACT_CONTEXT_WINDOW_PERCENT) / 100
    val previousContextWindowTokens = llmMetadata.intValue(
      "previousContextWindowTokens",
      "previous_context_window_tokens",
    )
    val smallerWindowModelSwitchDetected = previousContextWindowTokens?.let { previous ->
      previous > envelope.contextWindowTokens
    } ?: false
    val configuredLimit = llmMetadata.intValue(
      "autoCompactTokenLimit",
      "auto_compact_token_limit",
    )
    val baselineAutoCompactTokenLimit = configuredLimit?.let { limit ->
      min(limit, contextWindowLimit)
    } ?: contextWindowLimit
    val modelSwitchAutoCompactTokenLimit = if (smallerWindowModelSwitchDetected) {
      (envelope.contextWindowTokens * MODEL_SWITCH_AUTO_COMPACT_CONTEXT_WINDOW_PERCENT) / 100
    } else {
      null
    }
    val autoCompactTokenLimit = modelSwitchAutoCompactTokenLimit?.let { switchLimit ->
      min(baselineAutoCompactTokenLimit, switchLimit)
    } ?: baselineAutoCompactTokenLimit
    val estimatedReplayTokens = estimateReplayTokens(conversation)
    return ReplayPressureSnapshot(
      contextWindowTokens = envelope.contextWindowTokens,
      previousContextWindowTokens = previousContextWindowTokens,
      autoCompactTokenLimit = autoCompactTokenLimit,
      estimatedReplayTokens = estimatedReplayTokens,
      tokenThresholdTriggered = estimatedReplayTokens >= autoCompactTokenLimit,
      smallerWindowModelSwitchDetected = smallerWindowModelSwitchDetected,
    )
  }

  internal fun estimateReplayTokens(
    conversation: List<RuntimeConversationMessage>,
  ): Int = estimateTokenCount(
    buildString {
      appendLine("Conversation:")
      if (conversation.isEmpty()) {
        appendLine("[system]")
        append("No prior conversation context.")
        return@buildString
      }
      conversation.forEach { entry ->
        appendLine("[${entry.role.name.lowercase()}]")
        appendLine(entry.content)
        appendLine()
      }
    }.trim(),
  )

  private fun estimateTokenCount(content: String): Int = (content.length + 3) / 4

  private fun Map<String, String>.intValue(vararg keys: String): Int? = keys
    .asSequence()
    .mapNotNull { key -> this[key] }
    .map(String::trim)
    .firstNotNullOfOrNull { value -> value.toIntOrNull()?.takeIf { it > 0 } }

  private companion object {
    const val AUTO_COMPACT_CONTEXT_WINDOW_PERCENT: Int = 90
    const val MODEL_SWITCH_AUTO_COMPACT_CONTEXT_WINDOW_PERCENT: Int = 85
  }
}
