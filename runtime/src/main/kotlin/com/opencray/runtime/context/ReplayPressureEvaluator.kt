package com.opencray.runtime.context

import kotlin.math.min

data class ReplayPressureSnapshot(
  val contextWindowTokens: Int,
  val autoCompactTokenLimit: Int,
  val estimatedReplayTokens: Int,
  val tokenThresholdTriggered: Boolean,
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
    val configuredLimit = llmMetadata.intValue(
      "autoCompactTokenLimit",
      "auto_compact_token_limit",
    )
    val autoCompactTokenLimit = configuredLimit?.let { limit ->
      min(limit, contextWindowLimit)
    } ?: contextWindowLimit
    val estimatedReplayTokens = estimateReplayTokens(conversation)
    return ReplayPressureSnapshot(
      contextWindowTokens = envelope.contextWindowTokens,
      autoCompactTokenLimit = autoCompactTokenLimit,
      estimatedReplayTokens = estimatedReplayTokens,
      tokenThresholdTriggered = estimatedReplayTokens >= autoCompactTokenLimit,
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
  }
}
