package com.opencray.runtime.context

import com.opencray.runtime.bootstrap.BootstrapPromptDetailMode
import com.opencray.runtime.bootstrap.BootstrapPromptLayer
import com.opencray.runtime.bootstrap.BootstrapSnippet
import com.opencray.runtime.compaction.DurableCompactionContext
import com.opencray.runtime.compaction.DurableCompactionPromptDetailMode
import com.opencray.runtime.compaction.DurableCompactionPromptLayer
import com.opencray.runtime.memory.MemoryPromptDetailMode
import com.opencray.runtime.memory.MemoryPromptLayer
import com.opencray.runtime.memory.MemoryRecallResult
import com.opencray.runtime.skills.ActiveSkillCapsule
import com.opencray.runtime.skills.ActiveSkillPromptDetailMode
import com.opencray.runtime.skills.ActiveSkillPromptLayer
import com.opencray.runtime.skills.SkillInventory
import com.opencray.runtime.skills.SkillInventoryPromptDetailMode
import com.opencray.runtime.skills.SkillInventoryPromptLayer
import com.opencray.runtime.workingstate.WorkingState
import com.opencray.runtime.workingstate.WorkingStatePromptDetailMode
import com.opencray.runtime.workingstate.WorkingStatePromptLayer
import kotlin.math.max

data class CoordinatedPromptLayers(
  val layers: List<PromptLayer>,
  val report: ContextBudgetReport,
)

class GlobalContextBudgetCoordinator(
  private val budgetPolicy: ModelContextBudgetPolicy = ModelContextBudgetPolicy(),
  private val activeSkillPromptLayer: ActiveSkillPromptLayer = ActiveSkillPromptLayer(),
  private val bootstrapPromptLayer: BootstrapPromptLayer = BootstrapPromptLayer(),
  private val compactionSummaryPromptLayer: CompactionSummaryPromptLayer = CompactionSummaryPromptLayer(),
  private val durableCompactionPromptLayer: DurableCompactionPromptLayer = DurableCompactionPromptLayer(),
  private val memoryPromptLayer: MemoryPromptLayer = MemoryPromptLayer(),
  private val pruningSummaryPromptLayer: TranscriptPruningSummaryPromptLayer = TranscriptPruningSummaryPromptLayer(),
  private val recentToolObservationSupport: RecentToolObservationSupport = RecentToolObservationSupport(),
  private val skillInventoryPromptLayer: SkillInventoryPromptLayer = SkillInventoryPromptLayer(),
  private val workingStatePromptLayer: WorkingStatePromptLayer = WorkingStatePromptLayer(),
) {
  fun rebalance(
    input: ManagedPromptContext,
    layers: List<PromptLayer>,
    estimateTokens: (String) -> Int,
    renderConversationLayer: (TranscriptWindow) -> String,
  ): CoordinatedPromptLayers {
    val envelope = budgetPolicy.resolve(input.llmMetadata)
    val states = layers.map { layer ->
      val estimatedTokens = estimateTokens(layer.content)
      LayerBudgetState(
        originalLayer = layer,
        currentLayer = layer,
        spec = specFor(
          layer = layer,
          input = input,
          estimateTokens = estimateTokens,
          renderConversationLayer = renderConversationLayer,
        ),
        estimatedTokensBefore = estimatedTokens,
        estimatedTokensAfter = estimatedTokens,
      )
    }
    val estimatedBefore = states.sumOf(LayerBudgetState::estimatedTokensBefore)

    if (estimatedBefore > envelope.targetInputBudgetTokens) {
      states
        .sortedByDescending { state -> state.spec.retentionPriority }
        .forEach { state ->
          if (states.sumOf(LayerBudgetState::estimatedTokensAfter) <= envelope.targetInputBudgetTokens) {
            return@forEach
          }
          when {
            state.currentLayer == null -> Unit
            state.originalLayer.id == PromptLayerId.RETRIEVED_MEMORY -> {
              reduceRetrievedMemoryLayer(
                state = state,
                recalledMemory = input.selectedMemory,
                estimatedTotalTokens = states.sumOf(LayerBudgetState::estimatedTokensAfter),
                targetInputBudgetTokens = envelope.targetInputBudgetTokens,
                estimateTokens = estimateTokens,
              )
            }

            state.originalLayer.id == PromptLayerId.ACTIVE_SKILL -> {
              reduceActiveSkillLayer(
                state = state,
                activeSkillCapsule = input.activeSkillCapsule,
                estimatedTotalTokens = states.sumOf(LayerBudgetState::estimatedTokensAfter),
                targetInputBudgetTokens = envelope.targetInputBudgetTokens,
                estimateTokens = estimateTokens,
              )
            }

            state.originalLayer.id == PromptLayerId.BOOTSTRAP -> {
              reduceBootstrapLayer(
                state = state,
                bootstrapFiles = input.bootstrapFiles,
                estimatedTotalTokens = states.sumOf(LayerBudgetState::estimatedTokensAfter),
                targetInputBudgetTokens = envelope.targetInputBudgetTokens,
                estimateTokens = estimateTokens,
              )
            }

            state.originalLayer.id == PromptLayerId.RECENT_TOOL_OBSERVATIONS -> {
              reduceRecentToolObservationLayer(
                state = state,
                recentToolObservationLayer = input.recentToolObservationLayer,
                estimatedTotalTokens = states.sumOf(LayerBudgetState::estimatedTokensAfter),
                targetInputBudgetTokens = envelope.targetInputBudgetTokens,
                estimateTokens = estimateTokens,
              )
            }

            state.originalLayer.id == PromptLayerId.DURABLE_COMPACTION -> {
              reduceDurableCompactionLayer(
                state = state,
                durableCompaction = input.durableCompaction,
                estimatedTotalTokens = states.sumOf(LayerBudgetState::estimatedTokensAfter),
                targetInputBudgetTokens = envelope.targetInputBudgetTokens,
                estimateTokens = estimateTokens,
              )
            }

            state.originalLayer.id == PromptLayerId.PRUNING_SUMMARY -> {
              reducePruningSummaryLayer(
                state = state,
                pruningSummary = input.pruningSummary,
                estimatedTotalTokens = states.sumOf(LayerBudgetState::estimatedTokensAfter),
                targetInputBudgetTokens = envelope.targetInputBudgetTokens,
                estimateTokens = estimateTokens,
              )
            }

            state.originalLayer.id == PromptLayerId.COMPACTION_SUMMARY -> {
              reduceCompactionSummaryLayer(
                state = state,
                compactionSummary = input.compactionSummary,
                estimatedTotalTokens = states.sumOf(LayerBudgetState::estimatedTokensAfter),
                targetInputBudgetTokens = envelope.targetInputBudgetTokens,
                estimateTokens = estimateTokens,
              )
            }

            state.originalLayer.id == PromptLayerId.SKILL_INVENTORY -> {
              reduceSkillInventoryLayer(
                state = state,
                skillInventory = input.skillInventory,
                estimatedTotalTokens = states.sumOf(LayerBudgetState::estimatedTokensAfter),
                targetInputBudgetTokens = envelope.targetInputBudgetTokens,
                estimateTokens = estimateTokens,
              )
            }

            state.originalLayer.id == PromptLayerId.WORKING_STATE -> {
              reduceWorkingStateLayer(
                state = state,
                workingState = input.workingState,
                estimatedTotalTokens = states.sumOf(LayerBudgetState::estimatedTokensAfter),
                targetInputBudgetTokens = envelope.targetInputBudgetTokens,
                estimateTokens = estimateTokens,
              )
            }

            state.originalLayer.id == PromptLayerId.CONVERSATION -> {
              reduceConversationLayer(
                state = state,
                input = input,
                estimatedTotalTokens = states.sumOf(LayerBudgetState::estimatedTokensAfter),
                targetInputBudgetTokens = envelope.targetInputBudgetTokens,
                estimateTokens = estimateTokens,
                renderConversationLayer = renderConversationLayer,
              )
            }

            state.spec.mayDrop -> {
              state.currentLayer = null
              state.estimatedTokensAfter = 0
              state.appliedOperators += OPERATOR_OMIT_LAYER
            }
          }
        }
    }

    val estimatedAfter = states.sumOf(LayerBudgetState::estimatedTokensAfter)
    val omittedLayerNames = states
      .filter { state -> state.currentLayer == null }
      .map { state -> state.originalLayer.name }
    val reducedLayerNames = states
      .filter { state ->
        state.currentLayer != null &&
          state.estimatedTokensAfter < state.estimatedTokensBefore
      }
      .map { state -> state.originalLayer.name }
    val unresolvedOverflow = estimatedAfter > envelope.hardInputBudgetTokens
    val pressureMode = when {
      unresolvedOverflow -> ContextBudgetPressureMode.EMERGENCY
      estimatedBefore > envelope.targetInputBudgetTokens || omittedLayerNames.isNotEmpty() || reducedLayerNames.isNotEmpty() ->
        ContextBudgetPressureMode.TIGHT

      else -> ContextBudgetPressureMode.NORMAL
    }

    return CoordinatedPromptLayers(
      layers = states.mapNotNull(LayerBudgetState::currentLayer),
      report = ContextBudgetReport(
        applied = true,
        pressureMode = pressureMode,
        contextWindowTokens = envelope.contextWindowTokens,
        reservedOutputTokens = envelope.reservedOutputTokens,
        safetyMarginTokens = envelope.safetyMarginTokens,
        hardInputBudgetTokens = envelope.hardInputBudgetTokens,
        targetInputBudgetTokens = envelope.targetInputBudgetTokens,
        emergencyInputBudgetTokens = envelope.emergencyInputBudgetTokens,
        effectiveInputPercent = envelope.effectiveInputPercent,
        estimatedInputTokensBefore = estimatedBefore,
        estimatedInputTokensAfter = estimatedAfter,
        omittedLayerCount = omittedLayerNames.size,
        reducedLayerCount = reducedLayerNames.size,
        omittedLayerNames = omittedLayerNames,
        reducedLayerNames = reducedLayerNames,
        unresolvedOverflow = unresolvedOverflow,
        layers = states.map { state ->
          ContextBudgetLayerReport(
            id = state.originalLayer.id,
            name = state.originalLayer.name,
            priorityClass = state.spec.priorityClass,
            retentionPriority = state.spec.retentionPriority,
            estimatedTokensBefore = state.estimatedTokensBefore,
            estimatedTokensAfter = state.estimatedTokensAfter,
            omitted = state.currentLayer == null,
            reduced = state.currentLayer != null && state.estimatedTokensAfter < state.estimatedTokensBefore,
            appliedOperators = state.appliedOperators.toList(),
          )
        },
      ),
    )
  }

  private fun specFor(
    layer: PromptLayer,
    input: ManagedPromptContext,
    estimateTokens: (String) -> Int,
    renderConversationLayer: (TranscriptWindow) -> String,
  ): LayerBudgetSpec {
    val estimatedTokens = estimateTokens(layer.content)
    val compactWorkingStateTokens = when (layer.id) {
      PromptLayerId.WORKING_STATE -> estimateTokens(
        workingStatePromptLayer.render(
          input.workingState,
          WorkingStatePromptDetailMode.COMPACT,
        ),
      )

      else -> 0
    }
    val minimumWorkingStateTokens = when (layer.id) {
      PromptLayerId.WORKING_STATE -> estimateTokens(
        workingStatePromptLayer.render(
          input.workingState,
          WorkingStatePromptDetailMode.MINIMAL,
        ),
      )

      else -> 0
    }
    val compactMemoryTokens = when (layer.id) {
      PromptLayerId.RETRIEVED_MEMORY -> estimateTokens(
        memoryPromptLayer.render(
          input.selectedMemory,
          MemoryPromptDetailMode.COMPACT,
        ),
      )

      else -> 0
    }
    val compactRecentToolObservationTokens = when (layer.id) {
      PromptLayerId.RECENT_TOOL_OBSERVATIONS -> renderRecentToolObservationLayer(
        input.recentToolObservationLayer,
        RecentToolObservationDetailMode.COMPACT,
      )?.let { rendered -> estimateTokens(rendered.text) } ?: 0

      else -> 0
    }
    val compactActiveSkillTokens = when (layer.id) {
      PromptLayerId.ACTIVE_SKILL -> activeSkillPromptLayer.render(
        input.activeSkillCapsule,
        ActiveSkillPromptDetailMode.COMPACT,
      ).text.takeIf(String::isNotBlank)?.let(estimateTokens) ?: 0

      else -> 0
    }
    val compactBootstrapTokens = when (layer.id) {
      PromptLayerId.BOOTSTRAP -> bootstrapSnippetForLayer(layer, input.bootstrapFiles)?.let { snippet ->
        estimateTokens(
          bootstrapPromptLayer.render(
            snippet,
            BootstrapPromptDetailMode.COMPACT,
          ).text,
        )
      } ?: 0

      else -> 0
    }
    val compactSkillInventoryTokens = when (layer.id) {
      PromptLayerId.SKILL_INVENTORY -> skillInventoryPromptLayer.render(
        input.skillInventory,
        SkillInventoryPromptDetailMode.COMPACT,
      ).text.takeIf(String::isNotBlank)?.let(estimateTokens) ?: 0

      else -> 0
    }
    val compactDurableCompactionTokens = when (layer.id) {
      PromptLayerId.DURABLE_COMPACTION -> durableCompactionPromptLayer.render(
        input.durableCompaction,
        DurableCompactionPromptDetailMode.COMPACT,
      ).takeIf(String::isNotBlank)?.let(estimateTokens) ?: 0

      else -> 0
    }
    val compactPruningSummaryTokens = when (layer.id) {
      PromptLayerId.PRUNING_SUMMARY -> pruningSummaryPromptLayer.render(
        input.pruningSummary,
        TranscriptPruningSummaryPromptDetailMode.COMPACT,
      ).takeIf(String::isNotBlank)?.let(estimateTokens) ?: 0

      else -> 0
    }
    val compactCompactionSummaryTokens = when (layer.id) {
      PromptLayerId.COMPACTION_SUMMARY -> compactionSummaryPromptLayer.render(
        input.compactionSummary,
        CompactionSummaryPromptDetailMode.COMPACT,
      ).takeIf(String::isNotBlank)?.let(estimateTokens) ?: 0

      else -> 0
    }
    val minimumConversationTokens = when (layer.id) {
      PromptLayerId.CONVERSATION -> estimateTokens(
        renderConversationLayer(minimumConversationWindow(input.transcriptWindow)),
      )

      else -> 0
    }
    return when (layer.id) {
      PromptLayerId.TOOL_PROTOCOL,
      PromptLayerId.TASK_METADATA,
      PromptLayerId.RUNTIME_RULES,
      PromptLayerId.IDENTITY -> LayerBudgetSpec(
        id = layer.id,
        priorityClass = PromptLayerBudgetClass.MANDATORY_LIVE_INSTRUCTION,
        retentionPriority = 10,
        mayDrop = false,
        minTokens = estimatedTokens,
        targetTokens = estimatedTokens,
        maxTokens = estimatedTokens,
      )

      PromptLayerId.SESSION_POLICY,
      PromptLayerId.PERSONALIZATION,
      PromptLayerId.TURN_RESPONSE_POLICY -> LayerBudgetSpec(
        id = layer.id,
        priorityClass = PromptLayerBudgetClass.PROTECTED_STABLE_IDENTITY,
        retentionPriority = 20,
        mayDrop = false,
        minTokens = estimatedTokens,
        targetTokens = estimatedTokens,
        maxTokens = estimatedTokens,
      )

      PromptLayerId.WORKING_STATE -> LayerBudgetSpec(
        id = layer.id,
        priorityClass = PromptLayerBudgetClass.PROTECTED_PROCEDURAL_CONTINUITY,
        retentionPriority = 30,
        mayDrop = false,
        minTokens = minimumWorkingStateTokens,
        targetTokens = compactWorkingStateTokens,
        maxTokens = estimatedTokens,
      )

      PromptLayerId.RETRIEVED_MEMORY -> LayerBudgetSpec(
        id = layer.id,
        priorityClass = PromptLayerBudgetClass.BOUNDED_DURABLE_RECALL,
        retentionPriority = 40,
        mayDrop = true,
        minTokens = 0,
        targetTokens = compactMemoryTokens,
        maxTokens = estimatedTokens,
      )

      PromptLayerId.CONVERSATION -> LayerBudgetSpec(
        id = layer.id,
        priorityClass = PromptLayerBudgetClass.RECENT_REPLAY,
        retentionPriority = 50,
        mayDrop = false,
        minTokens = minimumConversationTokens,
        targetTokens = estimatedTokens,
        maxTokens = estimatedTokens,
      )

      PromptLayerId.ACTIVE_SKILL -> LayerBudgetSpec(
        id = layer.id,
        priorityClass = PromptLayerBudgetClass.BOUNDED_DURABLE_RECALL,
        retentionPriority = 60,
        mayDrop = true,
        minTokens = 0,
        targetTokens = compactActiveSkillTokens,
        maxTokens = estimatedTokens,
      )

      PromptLayerId.BOOTSTRAP -> LayerBudgetSpec(
        id = layer.id,
        priorityClass = PromptLayerBudgetClass.BOUNDED_DURABLE_RECALL,
        retentionPriority = 70,
        mayDrop = true,
        minTokens = 0,
        targetTokens = compactBootstrapTokens,
        maxTokens = estimatedTokens,
      )

      PromptLayerId.RECENT_TOOL_OBSERVATIONS -> LayerBudgetSpec(
        id = layer.id,
        priorityClass = PromptLayerBudgetClass.OPTIONAL_SUPPORT_CONTEXT,
        retentionPriority = 80,
        mayDrop = true,
        minTokens = 0,
        targetTokens = compactRecentToolObservationTokens,
        maxTokens = estimatedTokens,
      )

      PromptLayerId.SKILL_INVENTORY -> LayerBudgetSpec(
        id = layer.id,
        priorityClass = PromptLayerBudgetClass.OPTIONAL_SUPPORT_CONTEXT,
        retentionPriority = 90,
        mayDrop = true,
        minTokens = 0,
        targetTokens = compactSkillInventoryTokens,
        maxTokens = estimatedTokens,
      )

      PromptLayerId.DURABLE_COMPACTION -> LayerBudgetSpec(
        id = layer.id,
        priorityClass = PromptLayerBudgetClass.ARCHIVED_HISTORY,
        retentionPriority = 100,
        mayDrop = true,
        minTokens = 0,
        targetTokens = compactDurableCompactionTokens,
        maxTokens = estimatedTokens,
      )

      PromptLayerId.PRUNING_SUMMARY -> LayerBudgetSpec(
        id = layer.id,
        priorityClass = PromptLayerBudgetClass.ARCHIVED_HISTORY,
        retentionPriority = 100,
        mayDrop = true,
        minTokens = 0,
        targetTokens = compactPruningSummaryTokens,
        maxTokens = estimatedTokens,
      )

      PromptLayerId.COMPACTION_SUMMARY -> LayerBudgetSpec(
        id = layer.id,
        priorityClass = PromptLayerBudgetClass.ARCHIVED_HISTORY,
        retentionPriority = 100,
        mayDrop = true,
        minTokens = 0,
        targetTokens = compactCompactionSummaryTokens,
        maxTokens = estimatedTokens,
      )
    }
  }

  private fun reduceConversationLayer(
    state: LayerBudgetState,
    input: ManagedPromptContext,
    estimatedTotalTokens: Int,
    targetInputBudgetTokens: Int,
    estimateTokens: (String) -> Int,
    renderConversationLayer: (TranscriptWindow) -> String,
  ) {
    val layer = state.currentLayer ?: return
    val overflowTokens = estimatedTotalTokens - targetInputBudgetTokens
    if (overflowTokens <= 0) {
      return
    }

    var reducedWindow = input.transcriptWindow
    val minimumWindow = minimumConversationWindow(input.transcriptWindow)
    while (
      reducedWindow.messages.size > minimumWindow.messages.size &&
      estimateTokens(renderConversationLayer(reducedWindow)) > max(state.spec.minTokens, state.estimatedTokensAfter - overflowTokens)
    ) {
      reducedWindow = reducedWindow.copy(
        messages = reducedWindow.messages.drop(1),
        omittedMessageCount = reducedWindow.omittedMessageCount + 1,
      )
    }

    var rendered = renderConversationLayer(reducedWindow)
    var operators = mutableListOf<String>()
    if (reducedWindow.messages.size != input.transcriptWindow.messages.size) {
      operators += OPERATOR_TRIM_OLDEST_CONVERSATION_MESSAGES
    }
    val availableTokens = max(state.spec.minTokens, state.estimatedTokensAfter - overflowTokens)
    if (estimateTokens(rendered) > availableTokens) {
      val truncated = truncateContentToTokenBudget(rendered, availableTokens, estimateTokens)
      if (truncated != rendered) {
        rendered = truncated
        operators += OPERATOR_TRUNCATE_LAYER_CONTENT
      }
    }
    if (rendered == layer.content) {
      return
    }
    state.currentLayer = layer.copy(content = rendered)
    state.estimatedTokensAfter = estimateTokens(rendered)
    state.appliedOperators += operators
  }

  private fun reduceDurableCompactionLayer(
    state: LayerBudgetState,
    durableCompaction: DurableCompactionContext,
    estimatedTotalTokens: Int,
    targetInputBudgetTokens: Int,
    estimateTokens: (String) -> Int,
  ) {
    val layer = state.currentLayer ?: return
    val overflowTokens = estimatedTotalTokens - targetInputBudgetTokens
    if (overflowTokens <= 0) {
      return
    }
    if (!durableCompaction.included) {
      state.currentLayer = null
      state.estimatedTokensAfter = 0
      state.appliedOperators += OPERATOR_OMIT_LAYER
      return
    }
    val availableTokens = max(state.spec.minTokens, state.estimatedTokensAfter - overflowTokens)
    val compact = durableCompactionPromptLayer.render(
      durableCompaction,
      DurableCompactionPromptDetailMode.COMPACT,
    )
    val minimal = durableCompactionPromptLayer.render(
      durableCompaction,
      DurableCompactionPromptDetailMode.MINIMAL,
    )
    val reduction = when {
      compact != layer.content && estimateTokens(compact) <= availableTokens -> ReducedLayerContent(
        text = compact,
        operator = OPERATOR_REDUCE_DURABLE_COMPACTION_COMPACT,
      )

      minimal != layer.content && estimateTokens(minimal) <= availableTokens -> ReducedLayerContent(
        text = minimal,
        operator = OPERATOR_REDUCE_DURABLE_COMPACTION_MINIMAL,
      )

      else -> null
    }
    if (reduction != null) {
      state.currentLayer = layer.copy(content = reduction.text)
      state.estimatedTokensAfter = estimateTokens(reduction.text)
      state.appliedOperators += reduction.operator
      return
    }
    state.currentLayer = null
    state.estimatedTokensAfter = 0
    state.appliedOperators += OPERATOR_OMIT_LAYER
  }

  private fun reducePruningSummaryLayer(
    state: LayerBudgetState,
    pruningSummary: TranscriptPruningSummary?,
    estimatedTotalTokens: Int,
    targetInputBudgetTokens: Int,
    estimateTokens: (String) -> Int,
  ) {
    val layer = state.currentLayer ?: return
    val current = pruningSummary ?: run {
      state.currentLayer = null
      state.estimatedTokensAfter = 0
      state.appliedOperators += OPERATOR_OMIT_LAYER
      return
    }
    val overflowTokens = estimatedTotalTokens - targetInputBudgetTokens
    if (overflowTokens <= 0) {
      return
    }
    val availableTokens = max(state.spec.minTokens, state.estimatedTokensAfter - overflowTokens)
    val compact = pruningSummaryPromptLayer.render(
      current,
      TranscriptPruningSummaryPromptDetailMode.COMPACT,
    )
    val minimal = pruningSummaryPromptLayer.render(
      current,
      TranscriptPruningSummaryPromptDetailMode.MINIMAL,
    )
    val reduction = when {
      compact != layer.content && estimateTokens(compact) <= availableTokens -> ReducedLayerContent(
        text = compact,
        operator = OPERATOR_REDUCE_PRUNING_SUMMARY_COMPACT,
      )

      minimal != layer.content && estimateTokens(minimal) <= availableTokens -> ReducedLayerContent(
        text = minimal,
        operator = OPERATOR_REDUCE_PRUNING_SUMMARY_MINIMAL,
      )

      else -> null
    }
    if (reduction != null) {
      state.currentLayer = layer.copy(content = reduction.text)
      state.estimatedTokensAfter = estimateTokens(reduction.text)
      state.appliedOperators += reduction.operator
      return
    }
    state.currentLayer = null
    state.estimatedTokensAfter = 0
    state.appliedOperators += OPERATOR_OMIT_LAYER
  }

  private fun reduceCompactionSummaryLayer(
    state: LayerBudgetState,
    compactionSummary: CompactionSummary?,
    estimatedTotalTokens: Int,
    targetInputBudgetTokens: Int,
    estimateTokens: (String) -> Int,
  ) {
    val layer = state.currentLayer ?: return
    val current = compactionSummary ?: run {
      state.currentLayer = null
      state.estimatedTokensAfter = 0
      state.appliedOperators += OPERATOR_OMIT_LAYER
      return
    }
    val overflowTokens = estimatedTotalTokens - targetInputBudgetTokens
    if (overflowTokens <= 0) {
      return
    }
    val availableTokens = max(state.spec.minTokens, state.estimatedTokensAfter - overflowTokens)
    val compact = compactionSummaryPromptLayer.render(
      current,
      CompactionSummaryPromptDetailMode.COMPACT,
    )
    val minimal = compactionSummaryPromptLayer.render(
      current,
      CompactionSummaryPromptDetailMode.MINIMAL,
    )
    val reduction = when {
      compact != layer.content && estimateTokens(compact) <= availableTokens -> ReducedLayerContent(
        text = compact,
        operator = OPERATOR_REDUCE_COMPACTION_SUMMARY_COMPACT,
      )

      minimal != layer.content && estimateTokens(minimal) <= availableTokens -> ReducedLayerContent(
        text = minimal,
        operator = OPERATOR_REDUCE_COMPACTION_SUMMARY_MINIMAL,
      )

      else -> null
    }
    if (reduction != null) {
      state.currentLayer = layer.copy(content = reduction.text)
      state.estimatedTokensAfter = estimateTokens(reduction.text)
      state.appliedOperators += reduction.operator
      return
    }
    state.currentLayer = null
    state.estimatedTokensAfter = 0
    state.appliedOperators += OPERATOR_OMIT_LAYER
  }

  private fun reduceBootstrapLayer(
    state: LayerBudgetState,
    bootstrapFiles: List<BootstrapSnippet>,
    estimatedTotalTokens: Int,
    targetInputBudgetTokens: Int,
    estimateTokens: (String) -> Int,
  ) {
    val layer = state.currentLayer ?: return
    val overflowTokens = estimatedTotalTokens - targetInputBudgetTokens
    if (overflowTokens <= 0) {
      return
    }
    val snippet = bootstrapSnippetForLayer(layer, bootstrapFiles)
    if (snippet == null) {
      state.currentLayer = null
      state.estimatedTokensAfter = 0
      state.appliedOperators += OPERATOR_OMIT_LAYER
      return
    }
    val availableTokens = max(state.spec.minTokens, state.estimatedTokensAfter - overflowTokens)
    val compact = bootstrapPromptLayer.render(
      snippet,
      BootstrapPromptDetailMode.COMPACT,
    )
    val minimal = bootstrapPromptLayer.render(
      snippet,
      BootstrapPromptDetailMode.MINIMAL,
    )
    val reduction = when {
      compact.text != layer.content &&
        estimateTokens(compact.text) <= availableTokens -> ReducedLayerContent(
        text = compact.text,
        operator = OPERATOR_REDUCE_BOOTSTRAP_COMPACT,
      )

      minimal.text != layer.content &&
        estimateTokens(minimal.text) <= availableTokens -> ReducedLayerContent(
        text = minimal.text,
        operator = OPERATOR_REDUCE_BOOTSTRAP_MINIMAL,
      )

      else -> null
    }
    if (reduction != null) {
      state.currentLayer = layer.copy(content = reduction.text)
      state.estimatedTokensAfter = estimateTokens(reduction.text)
      state.appliedOperators += reduction.operator
      return
    }
    state.currentLayer = null
    state.estimatedTokensAfter = 0
    state.appliedOperators += OPERATOR_OMIT_LAYER
  }

  private fun reduceWorkingStateLayer(
    state: LayerBudgetState,
    workingState: WorkingState,
    estimatedTotalTokens: Int,
    targetInputBudgetTokens: Int,
    estimateTokens: (String) -> Int,
  ) {
    val layer = state.currentLayer ?: return
    if (workingState.isEmpty) {
      return
    }
    val overflowTokens = estimatedTotalTokens - targetInputBudgetTokens
    if (overflowTokens <= 0) {
      return
    }
    val availableTokens = max(state.spec.minTokens, state.estimatedTokensAfter - overflowTokens)
    val compact = workingStatePromptLayer.render(
      workingState,
      WorkingStatePromptDetailMode.COMPACT,
    )
    val minimal = workingStatePromptLayer.render(
      workingState,
      WorkingStatePromptDetailMode.MINIMAL,
    )
    val reduction = when {
      compact != layer.content && estimateTokens(compact) <= availableTokens -> ReducedLayerContent(
        text = compact,
        operator = OPERATOR_REDUCE_WORKING_STATE_COMPACT,
      )

      minimal != layer.content && estimateTokens(minimal) <= availableTokens -> ReducedLayerContent(
        text = minimal,
        operator = OPERATOR_REDUCE_WORKING_STATE_MINIMAL,
      )

      minimal != layer.content -> ReducedLayerContent(
        text = minimal,
        operator = OPERATOR_REDUCE_WORKING_STATE_MINIMAL,
      )

      else -> null
    } ?: return
    state.currentLayer = layer.copy(content = reduction.text)
    state.estimatedTokensAfter = estimateTokens(reduction.text)
    state.appliedOperators += reduction.operator
  }

  private fun reduceSkillInventoryLayer(
    state: LayerBudgetState,
    skillInventory: SkillInventory,
    estimatedTotalTokens: Int,
    targetInputBudgetTokens: Int,
    estimateTokens: (String) -> Int,
  ) {
    val layer = state.currentLayer ?: return
    val overflowTokens = estimatedTotalTokens - targetInputBudgetTokens
    if (overflowTokens <= 0) {
      return
    }
    if (skillInventory.skills.isEmpty()) {
      state.currentLayer = null
      state.estimatedTokensAfter = 0
      state.appliedOperators += OPERATOR_OMIT_LAYER
      return
    }
    val availableTokens = max(state.spec.minTokens, state.estimatedTokensAfter - overflowTokens)
    val compact = skillInventoryPromptLayer.render(
      skillInventory,
      SkillInventoryPromptDetailMode.COMPACT,
    )
    val minimal = skillInventoryPromptLayer.render(
      skillInventory,
      SkillInventoryPromptDetailMode.MINIMAL,
    )
    val reduction = when {
      compact.text.isNotBlank() &&
        compact.text != layer.content &&
        estimateTokens(compact.text) <= availableTokens -> ReducedLayerContent(
        text = compact.text,
        operator = OPERATOR_REDUCE_SKILL_INVENTORY_COMPACT,
      )

      minimal.text.isNotBlank() &&
        minimal.text != layer.content &&
        estimateTokens(minimal.text) <= availableTokens -> ReducedLayerContent(
        text = minimal.text,
        operator = OPERATOR_REDUCE_SKILL_INVENTORY_MINIMAL,
      )

      else -> null
    }
    if (reduction != null) {
      state.currentLayer = layer.copy(content = reduction.text)
      state.estimatedTokensAfter = estimateTokens(reduction.text)
      state.appliedOperators += reduction.operator
      return
    }
    state.currentLayer = null
    state.estimatedTokensAfter = 0
    state.appliedOperators += OPERATOR_OMIT_LAYER
  }

  private fun reduceRecentToolObservationLayer(
    state: LayerBudgetState,
    recentToolObservationLayer: RecentToolObservationLayer?,
    estimatedTotalTokens: Int,
    targetInputBudgetTokens: Int,
    estimateTokens: (String) -> Int,
  ) {
    val layer = state.currentLayer ?: return
    val sourceLayer = recentToolObservationLayer ?: return
    val overflowTokens = estimatedTotalTokens - targetInputBudgetTokens
    if (overflowTokens <= 0) {
      return
    }
    val availableTokens = max(state.spec.minTokens, state.estimatedTokensAfter - overflowTokens)
    val compact = renderRecentToolObservationLayer(
      sourceLayer,
      RecentToolObservationDetailMode.COMPACT,
    )
    val minimal = renderRecentToolObservationLayer(
      sourceLayer,
      RecentToolObservationDetailMode.MINIMAL,
    )
    val reduction = when {
      compact != null &&
        compact.text != layer.content &&
        estimateTokens(compact.text) <= availableTokens -> ReducedLayerContent(
        text = compact.text,
        operator = OPERATOR_REDUCE_RECENT_TOOL_OBSERVATIONS_COMPACT,
      )

      minimal != null &&
        minimal.text != layer.content &&
        estimateTokens(minimal.text) <= availableTokens -> ReducedLayerContent(
        text = minimal.text,
        operator = OPERATOR_REDUCE_RECENT_TOOL_OBSERVATIONS_MINIMAL,
      )

      else -> null
    }
    if (reduction != null) {
      state.currentLayer = layer.copy(content = reduction.text)
      state.estimatedTokensAfter = estimateTokens(reduction.text)
      state.appliedOperators += reduction.operator
      return
    }
    state.currentLayer = null
    state.estimatedTokensAfter = 0
    state.appliedOperators += OPERATOR_OMIT_LAYER
  }

  private fun reduceActiveSkillLayer(
    state: LayerBudgetState,
    activeSkillCapsule: ActiveSkillCapsule?,
    estimatedTotalTokens: Int,
    targetInputBudgetTokens: Int,
    estimateTokens: (String) -> Int,
  ) {
    val layer = state.currentLayer ?: return
    activeSkillCapsule ?: return
    val overflowTokens = estimatedTotalTokens - targetInputBudgetTokens
    if (overflowTokens <= 0) {
      return
    }
    val availableTokens = max(state.spec.minTokens, state.estimatedTokensAfter - overflowTokens)
    val compact = activeSkillPromptLayer.render(
      activeSkillCapsule,
      ActiveSkillPromptDetailMode.COMPACT,
    )
    val minimal = activeSkillPromptLayer.render(
      activeSkillCapsule,
      ActiveSkillPromptDetailMode.MINIMAL,
    )
    val reduction = when {
      compact.text.isNotBlank() &&
        compact.text != layer.content &&
        estimateTokens(compact.text) <= availableTokens -> ReducedLayerContent(
        text = compact.text,
        operator = OPERATOR_REDUCE_ACTIVE_SKILL_COMPACT,
      )

      minimal.text.isNotBlank() &&
        minimal.text != layer.content &&
        estimateTokens(minimal.text) <= availableTokens -> ReducedLayerContent(
        text = minimal.text,
        operator = OPERATOR_REDUCE_ACTIVE_SKILL_MINIMAL,
      )

      else -> null
    }
    if (reduction != null) {
      state.currentLayer = layer.copy(content = reduction.text)
      state.estimatedTokensAfter = estimateTokens(reduction.text)
      state.appliedOperators += reduction.operator
      return
    }
    state.currentLayer = null
    state.estimatedTokensAfter = 0
    state.appliedOperators += OPERATOR_OMIT_LAYER
  }

  private fun reduceRetrievedMemoryLayer(
    state: LayerBudgetState,
    recalledMemory: MemoryRecallResult,
    estimatedTotalTokens: Int,
    targetInputBudgetTokens: Int,
    estimateTokens: (String) -> Int,
  ) {
    val layer = state.currentLayer ?: return
    if (recalledMemory.memories.isEmpty()) {
      return
    }
    val overflowTokens = estimatedTotalTokens - targetInputBudgetTokens
    if (overflowTokens <= 0) {
      return
    }
    val availableTokens = max(state.spec.minTokens, state.estimatedTokensAfter - overflowTokens)
    val compact = memoryPromptLayer.render(
      recalledMemory,
      MemoryPromptDetailMode.COMPACT,
    )
    val minimal = memoryPromptLayer.render(
      recalledMemory,
      MemoryPromptDetailMode.MINIMAL,
    )
    val reduction = when {
      compact != layer.content && estimateTokens(compact) <= availableTokens -> ReducedLayerContent(
        text = compact,
        operator = OPERATOR_REDUCE_RETRIEVED_MEMORY_COMPACT,
      )

      minimal != layer.content && estimateTokens(minimal) <= availableTokens -> ReducedLayerContent(
        text = minimal,
        operator = OPERATOR_REDUCE_RETRIEVED_MEMORY_MINIMAL,
      )

      else -> null
    }
    if (reduction != null) {
      state.currentLayer = layer.copy(content = reduction.text)
      state.estimatedTokensAfter = estimateTokens(reduction.text)
      state.appliedOperators += reduction.operator
      return
    }
    state.currentLayer = null
    state.estimatedTokensAfter = 0
    state.appliedOperators += OPERATOR_OMIT_LAYER
  }

  private fun renderRecentToolObservationLayer(
    layer: RecentToolObservationLayer?,
    detailMode: RecentToolObservationDetailMode,
  ): RecentToolObservationLayer? = layer?.let { current ->
    recentToolObservationSupport.renderLayer(
      layer = current,
      detailMode = detailMode,
    )
  }

  private fun bootstrapSnippetForLayer(
    layer: PromptLayer,
    bootstrapFiles: List<BootstrapSnippet>,
  ): BootstrapSnippet? = bootstrapFiles.firstOrNull { snippet ->
    bootstrapPromptLayer.layerName(snippet) == layer.name
  }

  private fun minimumConversationWindow(
    transcriptWindow: TranscriptWindow,
  ): TranscriptWindow {
    if (transcriptWindow.messages.isEmpty()) {
      return transcriptWindow
    }
    val minimumMessageCount = if (transcriptWindow.messages.size >= 2) {
      2
    } else {
      1
    }
    val keptMessages = transcriptWindow.messages.takeLast(minimumMessageCount)
    val extraOmittedCount = transcriptWindow.messages.size - keptMessages.size
    return transcriptWindow.copy(
      messages = keptMessages,
      omittedMessageCount = transcriptWindow.omittedMessageCount + extraOmittedCount,
    )
  }

  private fun truncateContentToTokenBudget(
    content: String,
    tokenBudget: Int,
    estimateTokens: (String) -> Int,
  ): String {
    if (tokenBudget <= 0 || estimateTokens(content) <= tokenBudget) {
      return content
    }
    val suffix = "\n[context_budget_truncated]"
    val characterBudget = max(0, tokenBudget * ESTIMATED_CHARS_PER_TOKEN - suffix.length)
    if (characterBudget <= 0) {
      return suffix.trim()
    }
    val builder = StringBuilder()
    content.lineSequence().forEach { line ->
      val candidate = if (builder.isEmpty()) {
        line
      } else {
        builder.toString() + "\n" + line
      }
      if (candidate.length <= characterBudget) {
        if (builder.isNotEmpty()) {
          builder.append('\n')
        }
        builder.append(line)
      }
    }
    if (builder.isEmpty()) {
      return content.take(characterBudget) + suffix
    }
    val truncated = builder.toString().trimEnd()
    return if (truncated == content) {
      content
    } else {
      truncated + suffix
    }
  }

  private data class LayerBudgetState(
    val originalLayer: PromptLayer,
    var currentLayer: PromptLayer?,
    val spec: LayerBudgetSpec,
    val estimatedTokensBefore: Int,
    var estimatedTokensAfter: Int,
    val appliedOperators: MutableList<String> = mutableListOf(),
  )

  private data class ReducedLayerContent(
    val text: String,
    val operator: String,
  )

  private companion object {
    const val ESTIMATED_CHARS_PER_TOKEN: Int = 4
    const val OPERATOR_OMIT_LAYER: String = "omit_layer"
    const val OPERATOR_REDUCE_ACTIVE_SKILL_COMPACT: String = "reduce_active_skill_compact"
    const val OPERATOR_REDUCE_ACTIVE_SKILL_MINIMAL: String = "reduce_active_skill_minimal"
    const val OPERATOR_REDUCE_BOOTSTRAP_COMPACT: String = "reduce_bootstrap_compact"
    const val OPERATOR_REDUCE_BOOTSTRAP_MINIMAL: String = "reduce_bootstrap_minimal"
    const val OPERATOR_REDUCE_COMPACTION_SUMMARY_COMPACT: String = "reduce_compaction_summary_compact"
    const val OPERATOR_REDUCE_COMPACTION_SUMMARY_MINIMAL: String = "reduce_compaction_summary_minimal"
    const val OPERATOR_REDUCE_DURABLE_COMPACTION_COMPACT: String = "reduce_durable_compaction_compact"
    const val OPERATOR_REDUCE_DURABLE_COMPACTION_MINIMAL: String = "reduce_durable_compaction_minimal"
    const val OPERATOR_REDUCE_PRUNING_SUMMARY_COMPACT: String = "reduce_pruning_summary_compact"
    const val OPERATOR_REDUCE_PRUNING_SUMMARY_MINIMAL: String = "reduce_pruning_summary_minimal"
    const val OPERATOR_REDUCE_RECENT_TOOL_OBSERVATIONS_COMPACT: String =
      "reduce_recent_tool_observations_compact"
    const val OPERATOR_REDUCE_RECENT_TOOL_OBSERVATIONS_MINIMAL: String =
      "reduce_recent_tool_observations_minimal"
    const val OPERATOR_REDUCE_RETRIEVED_MEMORY_COMPACT: String = "reduce_retrieved_memory_compact"
    const val OPERATOR_REDUCE_RETRIEVED_MEMORY_MINIMAL: String = "reduce_retrieved_memory_minimal"
    const val OPERATOR_REDUCE_SKILL_INVENTORY_COMPACT: String = "reduce_skill_inventory_compact"
    const val OPERATOR_REDUCE_SKILL_INVENTORY_MINIMAL: String = "reduce_skill_inventory_minimal"
    const val OPERATOR_REDUCE_WORKING_STATE_COMPACT: String = "reduce_working_state_compact"
    const val OPERATOR_REDUCE_WORKING_STATE_MINIMAL: String = "reduce_working_state_minimal"
    const val OPERATOR_TRIM_OLDEST_CONVERSATION_MESSAGES: String = "trim_oldest_conversation_messages"
    const val OPERATOR_TRUNCATE_LAYER_CONTENT: String = "truncate_layer_content"
  }
}
