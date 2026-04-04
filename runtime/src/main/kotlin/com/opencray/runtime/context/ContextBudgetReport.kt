package com.opencray.runtime.context

data class ContextBudgetReport(
  val applied: Boolean = false,
  val pressureMode: ContextBudgetPressureMode = ContextBudgetPressureMode.NORMAL,
  val contextWindowTokens: Int = 0,
  val reservedOutputTokens: Int = 0,
  val safetyMarginTokens: Int = 0,
  val hardInputBudgetTokens: Int = 0,
  val targetInputBudgetTokens: Int = 0,
  val emergencyInputBudgetTokens: Int = 0,
  val effectiveInputPercent: Double = 0.0,
  val estimatedInputTokensBefore: Int = 0,
  val estimatedInputTokensAfter: Int = 0,
  val omittedLayerCount: Int = 0,
  val reducedLayerCount: Int = 0,
  val omittedLayerNames: List<String> = emptyList(),
  val reducedLayerNames: List<String> = emptyList(),
  val unresolvedOverflow: Boolean = false,
  val layers: List<ContextBudgetLayerReport> = emptyList(),
)

enum class ContextBudgetPressureMode {
  NORMAL,
  TIGHT,
  EMERGENCY,
}

data class ContextBudgetLayerReport(
  val id: PromptLayerId,
  val name: String,
  val priorityClass: PromptLayerBudgetClass,
  val retentionPriority: Int,
  val estimatedTokensBefore: Int,
  val estimatedTokensAfter: Int,
  val omitted: Boolean = false,
  val reduced: Boolean = false,
  val appliedOperators: List<String> = emptyList(),
)
