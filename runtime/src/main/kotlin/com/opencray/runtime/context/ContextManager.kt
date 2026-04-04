package com.opencray.runtime.context

import com.opencray.runtime.memory.MemoryPromptLayer
import com.opencray.runtime.memory.MemoryRecallOmissionReason
import com.opencray.runtime.memory.MemoryRecallOmittedTrace
import com.opencray.runtime.memory.MemoryRecallResult
import com.opencray.runtime.memory.MemoryRecallSelectedTrace
import com.opencray.runtime.memory.RetrievedMemory
import com.opencray.runtime.compaction.DurableCompactionPromptLayer
import com.opencray.runtime.skills.ActiveSkillPromptLayer
import com.opencray.runtime.skills.SkillInventoryPromptLayer
import com.opencray.runtime.soul.RuntimeSoulPromptComposer
import com.opencray.runtime.soul.RuntimeSoulTurnPolicyComposer
import com.opencray.runtime.workingstate.WorkingStatePromptLayer
import com.opencray.runtime.workingstate.WorkingStateSupport

data class ContextManagerConfig(
  val maxInjectedMemoryRecords: Int = 4,
) {
  init {
    require(maxInjectedMemoryRecords >= 1) {
      "ContextManagerConfig maxInjectedMemoryRecords must be >= 1."
    }
  }
}

class ContextManager(
  private val contextPruner: ContextPruner = ContextPruner(),
  private val transcriptWindowBuilder: TranscriptWindowBuilder = TranscriptWindowBuilder(),
  private val compactionPolicy: CompactionPolicy = CompactionPolicy(),
  private val soulPromptComposer: RuntimeSoulPromptComposer = RuntimeSoulPromptComposer(),
  private val soulTurnPolicyComposer: RuntimeSoulTurnPolicyComposer = RuntimeSoulTurnPolicyComposer(),
  private val memoryPromptLayer: MemoryPromptLayer = MemoryPromptLayer(),
  private val durableCompactionPromptLayer: DurableCompactionPromptLayer = DurableCompactionPromptLayer(),
  private val workingStateSupport: WorkingStateSupport = WorkingStateSupport(),
  private val workingStatePromptLayer: WorkingStatePromptLayer = WorkingStatePromptLayer(),
  private val skillInventoryPromptLayer: SkillInventoryPromptLayer = SkillInventoryPromptLayer(),
  private val activeSkillPromptLayer: ActiveSkillPromptLayer = ActiveSkillPromptLayer(),
  private val recentToolObservationSupport: RecentToolObservationSupport = RecentToolObservationSupport(),
  private val config: ContextManagerConfig = ContextManagerConfig(),
) {
  fun prepare(input: PromptAssemblyInput): ManagedPromptContext {
    val injectionPolicy = input.sessionContext.injectionPolicy
    val recentToolObservationLayer = recentToolObservationSupport.buildLayer(input.liveConversation)
    val recentToolObservationLines = recentToolObservationSupport.summaryLines(input.liveConversation)
    val recentWorkingStateActions = recentToolObservationSupport.workingStateEntries(
      messages = input.liveConversation,
    )
    val recentDecisionEntries = recentToolObservationSupport.decisionEntries(
      messages = input.liveConversation,
    )
    val recentBlockerEntries = recentToolObservationSupport.blockerEntries(
      messages = input.liveConversation,
    )
    val workingStateResolution = workingStateSupport.resolve(
      task = input.task,
      runId = input.runId,
      seededState = input.sessionContext.workingState,
      resumeContext = input.resumeContext,
      recentActionEntries = recentWorkingStateActions,
      decisionEntries = recentDecisionEntries,
      blockerEntries = recentBlockerEntries,
      recentObservationLines = recentToolObservationLines,
      todoSnapshot = input.todoSnapshot,
    )
    val prunedTranscript = contextPruner.prune(input.liveConversation)
    val transcriptSelection = transcriptWindowBuilder.buildSelection(prunedTranscript.messages)
    val selectedMemory = if (injectionPolicy.automaticMemoryInjectionEnabled) {
      selectMemory(input.sessionContext.recalledMemory)
    } else {
      MemoryRecallResult()
    }
    val renderedSkillInventory = skillInventoryPromptLayer.render(input.sessionContext.skillInventory)
    val renderedActiveSkill = activeSkillPromptLayer.render(input.activeSkillCapsule)
    val compactionSummary = compactionPolicy.summarize(transcriptSelection.omittedMessages)

    return ManagedPromptContext(
      task = input.task,
      baseSystemPrompt = input.baseSystemPrompt.trim(),
      sessionPolicyText = input.sessionContext.sessionPolicyText.orEmpty().trim(),
      personalizationText = if (injectionPolicy.soulContractEnabled) {
        soulPromptComposer.compose(input.sessionContext.soulProfile).trim()
      } else {
        ""
      },
      turnResponsePolicyText = if (injectionPolicy.soulTurnPolicyEnabled) {
        soulTurnPolicyComposer.compose(
          profile = input.sessionContext.soulProfile,
          signal = input.sessionContext.turnSemanticSignal,
        ).trim()
      } else {
        ""
      },
      bootstrapFiles = input.sessionContext.bootstrapContext.files,
      workingState = workingStateResolution.state,
      selectedMemory = selectedMemory,
      durableCompaction = input.sessionContext.durableCompaction,
      skillInventory = input.sessionContext.skillInventory,
      activeSkillCapsule = input.activeSkillCapsule,
      recentToolObservationLayer = recentToolObservationLayer,
      workingStateText = workingStatePromptLayer.render(workingStateResolution.state),
      memoryText = memoryPromptLayer.render(selectedMemory),
      durableCompactionText = durableCompactionPromptLayer.render(input.sessionContext.durableCompaction),
      skillInventoryText = renderedSkillInventory.text,
      activeSkillText = renderedActiveSkill.text,
      recentToolObservationsText = recentToolObservationLayer?.text.orEmpty(),
      pruningSummary = prunedTranscript.summary,
      compactionSummary = compactionSummary,
      nativeToolCallingEnabled = input.nativeToolCallingEnabled,
      parallelToolCallsEnabled = input.parallelToolCallsEnabled,
      legacyJsonFallbackEnabled = input.legacyJsonFallbackEnabled,
      toolDefinitions = input.toolDefinitions,
      transcriptWindow = transcriptSelection.window,
      llmMetadata = input.llmMetadata,
      report = ContextSelectionReport(
        sourceTranscriptMessageCount = input.liveConversation.count { message -> message.content.isNotBlank() },
        windowedTranscriptMessageCount = transcriptSelection.window.messages.size,
        omittedTranscriptMessageCount = transcriptSelection.window.omittedMessageCount,
        truncatedTranscriptMessageCount = transcriptSelection.window.truncatedMessageCount,
        prunedTranscriptMessageCount = prunedTranscript.summary?.removedMessageCount ?: 0,
        rewrittenTranscriptMessageCount = prunedTranscript.summary?.rewrittenMessageCount ?: 0,
        duplicateBackgroundTranscriptMessageCount = prunedTranscript.summary?.duplicateBackgroundMessageCount ?: 0,
        bulkyToolTranscriptRewriteCount = prunedTranscript.summary?.bulkyToolMessageCount ?: 0,
        attachmentLikeTranscriptRewriteCount = prunedTranscript.summary?.attachmentLikeMessageCount ?: 0,
        pruningSummaryIncluded = prunedTranscript.summary != null,
        compactedTranscriptMessageCount = compactionSummary?.compactedMessageCount ?: 0,
        compactionSummaryIncluded = compactionSummary != null,
        matchedMemoryRecordCount = selectedMemory.matchedRecordCount,
        injectedMemoryRecordCount = selectedMemory.memories.size,
        omittedMemoryRecordCount = selectedMemory.omittedRecordCount,
        memoryRecallTrace = selectedMemory.trace,
        memoryFlushTrace = input.sessionContext.memoryFlushTrace,
        durableCompactionTrace = input.sessionContext.durableCompaction.trace,
        workingStateTrace = workingStateResolution.trace,
        liveContextTrace = input.sessionContext.liveContextTrace,
        bootstrapTrace = input.sessionContext.bootstrapContext.trace,
        visibleSkillCount = input.sessionContext.skillInventory.visibleSkillCount,
        injectedSkillCount = renderedSkillInventory.injectedSkillCount,
        omittedSkillCount = renderedSkillInventory.omittedSkillCount,
        invalidSkillCount = input.sessionContext.skillInventory.invalidSkillCount,
        skillInventoryTrace = input.sessionContext.skillInventory.trace,
        activeSkillTrace = renderedActiveSkill.trace,
        recentToolObservationCount = recentToolObservationLayer?.observationCount ?: 0,
        omittedRecentToolObservationCount = recentToolObservationLayer?.omittedObservationCount ?: 0,
        recentToolObservationLayerIncluded = recentToolObservationLayer != null,
      ),
    )
  }

  private fun selectMemory(result: MemoryRecallResult): MemoryRecallResult {
    if (result.memories.size <= config.maxInjectedMemoryRecords) {
      return result
    }

    val keptMemories = result.memories.take(config.maxInjectedMemoryRecords)
    val droppedMemories = result.memories.drop(config.maxInjectedMemoryRecords)
    val keptSelected = if (result.trace.selected.isEmpty()) {
      keptMemories.map(::toSelectedTrace)
    } else {
      result.trace.selected.take(config.maxInjectedMemoryRecords)
    }
    val omitted = droppedMemories.map(::toOmittedTrace) + result.trace.omitted

    return result.copy(
      memories = keptMemories,
      omittedRecordCount = result.omittedRecordCount + droppedMemories.size,
      trace = result.trace.copy(
        selected = keptSelected,
        omitted = omitted.take(MAX_OMITTED_TRACE_ENTRIES),
      ),
    )
  }

  private fun toSelectedTrace(memory: RetrievedMemory): MemoryRecallSelectedTrace = MemoryRecallSelectedTrace(
    id = memory.id,
    kind = memory.kind,
    scope = memory.scope,
    score = memory.score,
    matchedTerms = memory.matchedTerms,
    contentPreview = memory.content.take(MAX_TRACE_CONTENT_CHARS),
  )

  private fun toOmittedTrace(memory: RetrievedMemory): MemoryRecallOmittedTrace = MemoryRecallOmittedTrace(
    id = memory.id,
    kind = memory.kind,
    scope = memory.scope,
    score = memory.score,
    matchedTerms = memory.matchedTerms,
    omissionReason = MemoryRecallOmissionReason.MAX_RECORDS,
    contentPreview = memory.content.take(MAX_TRACE_CONTENT_CHARS),
  )

  private companion object {
    const val MAX_TRACE_CONTENT_CHARS: Int = 160
    const val MAX_OMITTED_TRACE_ENTRIES: Int = 8
  }
}
