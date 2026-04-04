package com.opencray.runtime.context

data class LayerBudgetSpec(
  val id: PromptLayerId,
  val priorityClass: PromptLayerBudgetClass,
  val retentionPriority: Int,
  val mayDrop: Boolean,
  val minTokens: Int = 0,
  val targetTokens: Int = 0,
  val maxTokens: Int = 0,
)

enum class PromptLayerBudgetClass {
  MANDATORY_LIVE_INSTRUCTION,
  PROTECTED_STABLE_IDENTITY,
  PROTECTED_PROCEDURAL_CONTINUITY,
  RECENT_REPLAY,
  BOUNDED_DURABLE_RECALL,
  OPTIONAL_SUPPORT_CONTEXT,
  ARCHIVED_HISTORY,
}
