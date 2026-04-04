package com.opencray.runtime.context

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

data class ModelContextBudgetEnvelope(
  val contextWindowTokens: Int,
  val reservedOutputTokens: Int,
  val safetyMarginTokens: Int,
  val hardInputBudgetTokens: Int,
  val targetInputBudgetTokens: Int,
  val emergencyInputBudgetTokens: Int,
  val effectiveInputPercent: Double,
)

data class ModelContextBudgetPolicyConfig(
  val defaultContextWindowTokens: Int = 128_000,
  val defaultReservedOutputTokens: Int = 2_048,
  val defaultSafetyMarginTokens: Int = 1_024,
  val defaultEffectiveInputPercent: Double = 0.85,
) {
  init {
    require(defaultContextWindowTokens >= 2_048) {
      "ModelContextBudgetPolicyConfig defaultContextWindowTokens must be >= 2048."
    }
    require(defaultReservedOutputTokens >= 0) {
      "ModelContextBudgetPolicyConfig defaultReservedOutputTokens must be >= 0."
    }
    require(defaultSafetyMarginTokens >= 0) {
      "ModelContextBudgetPolicyConfig defaultSafetyMarginTokens must be >= 0."
    }
    require(defaultEffectiveInputPercent in 0.1..1.0) {
      "ModelContextBudgetPolicyConfig defaultEffectiveInputPercent must be within 0.1..1.0."
    }
  }
}

class ModelContextBudgetPolicy(
  private val config: ModelContextBudgetPolicyConfig = ModelContextBudgetPolicyConfig(),
) {
  fun resolve(metadata: Map<String, String>): ModelContextBudgetEnvelope {
    val contextWindowTokens = metadata.intValue(
      "contextWindowTokens",
      "context_window_tokens",
      "maxInputTokens",
      "max_input_tokens",
    ) ?: config.defaultContextWindowTokens
    val configuredReservedOutputTokens = metadata.intValue(
      "reservedOutputTokens",
      "reserved_output_tokens",
      "maxOutputTokens",
      "max_output_tokens",
      "maxTokens",
      "max_tokens",
    ) ?: config.defaultReservedOutputTokens
    val thinkingBudgetTokens = metadata.intValue(
      "thinkingBudgetTokens",
      "thinking_budget_tokens",
    ) ?: 0
    val safetyMarginTokens = metadata.intValue(
      "promptSafetyMarginTokens",
      "prompt_safety_margin_tokens",
      "safetyMarginTokens",
      "safety_margin_tokens",
    ) ?: config.defaultSafetyMarginTokens
    val effectiveInputPercent = metadata.doubleValue(
      "effectiveInputPercent",
      "effective_input_percent",
    )?.coerceIn(0.1, 1.0) ?: config.defaultEffectiveInputPercent

    val reservedOutputTokens = max(configuredReservedOutputTokens, thinkingBudgetTokens)
    val rawInputBudgetTokens = max(
      MINIMUM_INPUT_BUDGET_TOKENS,
      contextWindowTokens - reservedOutputTokens - safetyMarginTokens,
    )
    val targetInputBudgetTokens = max(
      MINIMUM_INPUT_BUDGET_TOKENS,
      floor(rawInputBudgetTokens * effectiveInputPercent).toInt(),
    )
    val emergencyInputBudgetTokens = max(
      targetInputBudgetTokens,
      min(
        rawInputBudgetTokens,
        targetInputBudgetTokens + max(512, (rawInputBudgetTokens - targetInputBudgetTokens) / 2),
      ),
    )

    return ModelContextBudgetEnvelope(
      contextWindowTokens = contextWindowTokens,
      reservedOutputTokens = reservedOutputTokens,
      safetyMarginTokens = safetyMarginTokens,
      hardInputBudgetTokens = rawInputBudgetTokens,
      targetInputBudgetTokens = targetInputBudgetTokens,
      emergencyInputBudgetTokens = emergencyInputBudgetTokens,
      effectiveInputPercent = effectiveInputPercent,
    )
  }

  private fun Map<String, String>.intValue(vararg keys: String): Int? = keys
    .asSequence()
    .mapNotNull { key -> this[key] }
    .map { value -> value.trim() }
    .firstNotNullOfOrNull { value -> value.toIntOrNull()?.takeIf { it > 0 } }

  private fun Map<String, String>.doubleValue(vararg keys: String): Double? = keys
    .asSequence()
    .mapNotNull { key -> this[key] }
    .map { value -> value.trim() }
    .firstNotNullOfOrNull { value -> value.toDoubleOrNull() }

  private companion object {
    const val MINIMUM_INPUT_BUDGET_TOKENS: Int = 512
  }
}
