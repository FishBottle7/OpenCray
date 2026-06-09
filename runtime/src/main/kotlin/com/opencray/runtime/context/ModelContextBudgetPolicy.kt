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
  val selectedPreset: String,
  val effectivePreset: String,
  val presetSource: String,
  val presetDiverged: Boolean,
)

enum class ModelContextBudgetPreset(
  val wireValue: String,
  val defaultReservedOutputTokens: Int,
  val defaultSafetyMarginTokens: Int,
  val defaultEffectiveInputPercent: Double,
) {
  COMPACT(
    wireValue = "compact",
    defaultReservedOutputTokens = 2_560,
    defaultSafetyMarginTokens = 1_536,
    defaultEffectiveInputPercent = 0.75,
  ),
  BALANCED(
    wireValue = "balanced",
    defaultReservedOutputTokens = 2_048,
    defaultSafetyMarginTokens = 1_024,
    defaultEffectiveInputPercent = 0.85,
  ),
  EXPANDED(
    wireValue = "expanded",
    defaultReservedOutputTokens = 1_536,
    defaultSafetyMarginTokens = 768,
    defaultEffectiveInputPercent = 0.90,
  );

  companion object {
    fun fromWireValue(raw: String?): ModelContextBudgetPreset? = raw
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { normalized -> entries.firstOrNull { preset -> preset.wireValue.equals(normalized, ignoreCase = true) } }

    fun matchingPreset(
      reservedOutputTokens: Int,
      safetyMarginTokens: Int,
      effectiveInputPercent: Double,
    ): ModelContextBudgetPreset? = entries.firstOrNull { preset ->
      preset.defaultReservedOutputTokens == reservedOutputTokens &&
        preset.defaultSafetyMarginTokens == safetyMarginTokens &&
        preset.defaultEffectiveInputPercent == effectiveInputPercent
    }
  }
}

data class ModelContextBudgetPolicyConfig(
  val defaultContextWindowTokens: Int = 128_000,
  val defaultPreset: ModelContextBudgetPreset = ModelContextBudgetPreset.BALANCED,
) {
  init {
    require(defaultContextWindowTokens >= 2_048) {
      "ModelContextBudgetPolicyConfig defaultContextWindowTokens must be >= 2048."
    }
  }
}

class ModelContextBudgetPolicy(
  private val config: ModelContextBudgetPolicyConfig = ModelContextBudgetPolicyConfig(),
) {
  fun resolve(metadata: Map<String, String>): ModelContextBudgetEnvelope {
    val explicitPreset = ModelContextBudgetPreset.fromWireValue(
      metadata.stringValue(
        "contextBudgetPreset",
        "context_budget_preset",
      ),
    )
    val rawReservedOutputTokens = metadata.intValue(
      "reservedOutputTokens",
      "reserved_output_tokens",
      "maxOutputTokens",
      "max_output_tokens",
      "maxTokens",
      "max_tokens",
    )
    val rawSafetyMarginTokens = metadata.intValue(
      "promptSafetyMarginTokens",
      "prompt_safety_margin_tokens",
      "safetyMarginTokens",
      "safety_margin_tokens",
    )
    val rawEffectiveInputPercent = metadata.doubleValue(
      "effectiveInputPercent",
      "effective_input_percent",
    )?.coerceIn(0.1, 1.0)
    val preset = explicitPreset ?: config.defaultPreset
    val contextWindowTokens = metadata.intValue(
      "contextWindowTokens",
      "context_window_tokens",
      "maxInputTokens",
      "max_input_tokens",
    ) ?: config.defaultContextWindowTokens
    val configuredReservedOutputTokens = rawReservedOutputTokens ?: preset.defaultReservedOutputTokens
    val thinkingBudgetTokens = metadata.intValue(
      "thinkingBudgetTokens",
      "thinking_budget_tokens",
    ) ?: 0
    val safetyMarginTokens = rawSafetyMarginTokens ?: preset.defaultSafetyMarginTokens
    val effectiveInputPercent = rawEffectiveInputPercent ?: preset.defaultEffectiveInputPercent

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
    val matchedPreset = ModelContextBudgetPreset.matchingPreset(
      reservedOutputTokens = configuredReservedOutputTokens,
      safetyMarginTokens = safetyMarginTokens,
      effectiveInputPercent = effectiveInputPercent,
    )
    val presetSource = when {
      explicitPreset != null -> "explicit"
      rawReservedOutputTokens != null || rawSafetyMarginTokens != null || rawEffectiveInputPercent != null -> "raw"
      else -> "default"
    }
    val selectedPreset = explicitPreset ?: matchedPreset ?: config.defaultPreset
    val effectivePreset = matchedPreset?.wireValue ?: DEV_PRESET_WIRE_VALUE

    return ModelContextBudgetEnvelope(
      contextWindowTokens = contextWindowTokens,
      reservedOutputTokens = reservedOutputTokens,
      safetyMarginTokens = safetyMarginTokens,
      hardInputBudgetTokens = rawInputBudgetTokens,
      targetInputBudgetTokens = targetInputBudgetTokens,
      emergencyInputBudgetTokens = emergencyInputBudgetTokens,
      effectiveInputPercent = effectiveInputPercent,
      selectedPreset = selectedPreset.wireValue,
      effectivePreset = effectivePreset,
      presetSource = presetSource,
      presetDiverged = effectivePreset == DEV_PRESET_WIRE_VALUE,
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

  private fun Map<String, String>.stringValue(vararg keys: String): String? = keys
    .asSequence()
    .mapNotNull { key -> this[key] }
    .map { value -> value.trim() }
    .firstOrNull(String::isNotBlank)

  private companion object {
    const val DEV_PRESET_WIRE_VALUE: String = "dev"
    const val MINIMUM_INPUT_BUDGET_TOKENS: Int = 512
  }
}
