package com.opencray.runtime.context

import com.opencray.runtime.bootstrap.BootstrapContextResolverConfig
import com.opencray.runtime.memory.MemoryFlushPolicy
import com.opencray.runtime.memory.MemoryPolicy
import com.opencray.runtime.memory.MemoryRecallBudget
import com.opencray.runtime.skills.ActiveSkillPromptLayerConfig
import com.opencray.runtime.skills.SkillInventoryPromptLayerConfig
import kotlin.math.abs

data class ContextSourceBudgetProfile(
  val sourcePreset: ModelContextBudgetPreset,
  val envelope: ModelContextBudgetEnvelope,
  val transcriptWindowConfig: TranscriptWindowConfig,
  val contextManagerConfig: ContextManagerConfig,
  val memoryPolicy: MemoryPolicy,
  val bootstrapContextResolverConfig: BootstrapContextResolverConfig,
  val skillInventoryPromptLayerConfig: SkillInventoryPromptLayerConfig,
  val activeSkillPromptLayerConfig: ActiveSkillPromptLayerConfig,
  val recentToolObservationConfig: RecentToolObservationConfig,
  val memoryFlushPolicy: MemoryFlushPolicy,
) {
  val sourcePresetWireValue: String
    get() = sourcePreset.wireValue
}

class ContextSourceBudgetPolicy(
  private val modelBudgetPolicy: ModelContextBudgetPolicy = ModelContextBudgetPolicy(),
) {
  fun resolve(metadata: Map<String, String>): ContextSourceBudgetProfile {
    val envelope = modelBudgetPolicy.resolve(metadata)
    val sourcePreset = resolveSourcePreset(metadata = metadata, envelope = envelope)
    return when (sourcePreset) {
      ModelContextBudgetPreset.COMPACT -> ContextSourceBudgetProfile(
        sourcePreset = sourcePreset,
        envelope = envelope,
        transcriptWindowConfig = TranscriptWindowConfig(
          maxMessages = 10,
          maxCharsPerMessage = 2_200,
        ),
        contextManagerConfig = ContextManagerConfig(
          maxInjectedMemoryRecords = 3,
        ),
        memoryPolicy = MemoryPolicy(
          recallBudget = MemoryRecallBudget(
            maxRecords = 5,
            maxChars = 780,
            maxRecordsPerKind = 2,
          ),
        ),
        bootstrapContextResolverConfig = BootstrapContextResolverConfig(
          maxCharsPerFile = 1_400,
          maxTotalChars = 2_800,
          minRemainingCharsToInject = 128,
        ),
        skillInventoryPromptLayerConfig = SkillInventoryPromptLayerConfig(
          maxSkills = 6,
          maxDescriptionChars = 108,
          maxCompactSkills = 3,
          maxCompactDescriptionChars = 64,
          maxMinimalSkills = 2,
          maxMinimalDescriptionChars = 48,
        ),
        activeSkillPromptLayerConfig = ActiveSkillPromptLayerConfig(
          maxBodyChars = 2_800,
          maxPermissionEntries = 6,
          maxCompactBodyChars = 1_000,
          maxCompactPermissionEntries = 4,
          maxMinimalBodyChars = 320,
          maxMinimalPermissionEntries = 2,
        ),
        recentToolObservationConfig = RecentToolObservationConfig(
          maxEntries = 3,
          maxCompactEntries = 2,
          maxMinimalEntries = 1,
          maxReadChars = 2_000,
          maxReadLines = 80,
          maxListChars = 1_400,
          maxListLines = 28,
          maxCompactBodyChars = 560,
          maxCompactBodyLines = 14,
          maxRenderedChars = 6_000,
          duplicateLookbackMessages = 20,
          duplicateExcerptChars = 960,
        ),
        memoryFlushPolicy = MemoryFlushPolicy(
          minOmittedMessages = 4,
          minOmittedChars = 480,
          maxMergedUserChars = 640,
          maxMergedAssistantChars = 640,
          maxToolObservations = 6,
        ),
      )

      ModelContextBudgetPreset.BALANCED -> ContextSourceBudgetProfile(
        sourcePreset = sourcePreset,
        envelope = envelope,
        transcriptWindowConfig = TranscriptWindowConfig(),
        contextManagerConfig = ContextManagerConfig(),
        memoryPolicy = MemoryPolicy(),
        bootstrapContextResolverConfig = BootstrapContextResolverConfig(),
        skillInventoryPromptLayerConfig = SkillInventoryPromptLayerConfig(),
        activeSkillPromptLayerConfig = ActiveSkillPromptLayerConfig(),
        recentToolObservationConfig = RecentToolObservationConfig(),
        memoryFlushPolicy = MemoryFlushPolicy(),
      )

      ModelContextBudgetPreset.EXPANDED -> ContextSourceBudgetProfile(
        sourcePreset = sourcePreset,
        envelope = envelope,
        transcriptWindowConfig = TranscriptWindowConfig(
          maxMessages = 16,
          maxCharsPerMessage = 3_200,
        ),
        contextManagerConfig = ContextManagerConfig(
          maxInjectedMemoryRecords = 6,
        ),
        memoryPolicy = MemoryPolicy(
          recallBudget = MemoryRecallBudget(
            maxRecords = 8,
            maxChars = 1_400,
            maxRecordsPerKind = 3,
          ),
        ),
        bootstrapContextResolverConfig = BootstrapContextResolverConfig(
          maxCharsPerFile = 2_400,
          maxTotalChars = 4_800,
          minRemainingCharsToInject = 128,
        ),
        skillInventoryPromptLayerConfig = SkillInventoryPromptLayerConfig(
          maxSkills = 12,
          maxDescriptionChars = 144,
          maxCompactSkills = 6,
          maxCompactDescriptionChars = 96,
          maxMinimalSkills = 3,
          maxMinimalDescriptionChars = 56,
        ),
        activeSkillPromptLayerConfig = ActiveSkillPromptLayerConfig(
          maxBodyChars = 4_800,
          maxPermissionEntries = 12,
          maxCompactBodyChars = 1_800,
          maxCompactPermissionEntries = 6,
          maxMinimalBodyChars = 480,
          maxMinimalPermissionEntries = 3,
        ),
        recentToolObservationConfig = RecentToolObservationConfig(
          maxEntries = 6,
          maxCompactEntries = 3,
          maxMinimalEntries = 1,
          maxReadChars = 3_200,
          maxReadLines = 128,
          maxListChars = 2_000,
          maxListLines = 40,
          maxCompactBodyChars = 960,
          maxCompactBodyLines = 24,
          maxRenderedChars = 9_600,
          duplicateLookbackMessages = 32,
          duplicateExcerptChars = 1_600,
        ),
        memoryFlushPolicy = MemoryFlushPolicy(
          minOmittedMessages = 4,
          minOmittedChars = 480,
          maxMergedUserChars = 960,
          maxMergedAssistantChars = 960,
          maxToolObservations = 12,
        ),
      )
    }
  }

  private fun resolveSourcePreset(
    metadata: Map<String, String>,
    envelope: ModelContextBudgetEnvelope,
  ): ModelContextBudgetPreset {
    if (!envelope.presetDiverged) {
      return ModelContextBudgetPreset.fromWireValue(envelope.effectivePreset)
        ?: ModelContextBudgetPreset.BALANCED
    }
    val selectedPreset = ModelContextBudgetPreset.fromWireValue(envelope.selectedPreset)
    return ModelContextBudgetPreset.entries
      .map { preset -> preset to syntheticEnvelope(metadata = metadata, preset = preset) }
      .minWithOrNull(
        compareBy<Pair<ModelContextBudgetPreset, ModelContextBudgetEnvelope>>(
          { abs(it.second.targetInputBudgetTokens - envelope.targetInputBudgetTokens) },
          { abs(it.second.hardInputBudgetTokens - envelope.hardInputBudgetTokens) },
          { if (it.first == selectedPreset) 0 else 1 },
          { abs(it.first.ordinal - (selectedPreset ?: ModelContextBudgetPreset.BALANCED).ordinal) },
        ),
      )
      ?.first
      ?: ModelContextBudgetPreset.BALANCED
  }

  private fun syntheticEnvelope(
    metadata: Map<String, String>,
    preset: ModelContextBudgetPreset,
  ): ModelContextBudgetEnvelope = modelBudgetPolicy.resolve(
    metadata
      .filterKeys { key -> key !in OVERRIDE_KEYS && key !in PRESET_KEYS }
      .plus("context_budget_preset" to preset.wireValue),
  )

  private companion object {
    val PRESET_KEYS: Set<String> = setOf(
      "contextBudgetPreset",
      "context_budget_preset",
    )

    val OVERRIDE_KEYS: Set<String> = setOf(
      "reservedOutputTokens",
      "reserved_output_tokens",
      "maxOutputTokens",
      "max_output_tokens",
      "maxTokens",
      "max_tokens",
      "promptSafetyMarginTokens",
      "prompt_safety_margin_tokens",
      "safetyMarginTokens",
      "safety_margin_tokens",
      "effectiveInputPercent",
      "effective_input_percent",
    )
  }
}
