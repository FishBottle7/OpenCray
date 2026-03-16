package com.opencray.runtime.context

import com.opencray.runtime.memory.MemoryPromptLayer
import com.opencray.runtime.memory.MemoryRecallOmissionReason
import com.opencray.runtime.memory.MemoryRecallOmittedTrace
import com.opencray.runtime.memory.MemoryRecallResult
import com.opencray.runtime.memory.MemoryRecallSelectedTrace
import com.opencray.runtime.memory.RetrievedMemory
import com.opencray.runtime.skills.ActiveSkillPromptLayer
import com.opencray.runtime.skills.SkillInventoryPromptLayer
import com.opencray.runtime.soul.RuntimeSoulPromptComposer

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
  private val memoryPromptLayer: MemoryPromptLayer = MemoryPromptLayer(),
  private val skillInventoryPromptLayer: SkillInventoryPromptLayer = SkillInventoryPromptLayer(),
  private val activeSkillPromptLayer: ActiveSkillPromptLayer = ActiveSkillPromptLayer(),
  private val config: ContextManagerConfig = ContextManagerConfig(),
) {
  fun prepare(input: PromptAssemblyInput): ManagedPromptContext {
    val prunedTranscript = contextPruner.prune(input.liveConversation)
    val transcriptSelection = transcriptWindowBuilder.buildSelection(prunedTranscript.messages)
    val selectedMemory = selectMemory(input.sessionContext.recalledMemory)
    val renderedSkillInventory = skillInventoryPromptLayer.render(input.sessionContext.skillInventory)
    val renderedActiveSkill = activeSkillPromptLayer.render(input.activeSkillCapsule)
    val compactionSummary = compactionPolicy.summarize(transcriptSelection.omittedMessages)

    return ManagedPromptContext(
      task = input.task,
      baseSystemPrompt = input.baseSystemPrompt.trim(),
      sessionPolicyText = input.sessionContext.sessionPolicyText.orEmpty().trim(),
      personalizationText = soulPromptComposer.compose(input.sessionContext.soulProfile).trim(),
      memoryText = memoryPromptLayer.render(selectedMemory),
      skillInventoryText = renderedSkillInventory.text,
      activeSkillText = renderedActiveSkill.text,
      pruningSummary = prunedTranscript.summary,
      compactionSummary = compactionSummary,
      toolDefinitions = input.toolDefinitions,
      transcriptWindow = transcriptSelection.window,
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
        visibleSkillCount = input.sessionContext.skillInventory.visibleSkillCount,
        injectedSkillCount = renderedSkillInventory.injectedSkillCount,
        omittedSkillCount = renderedSkillInventory.omittedSkillCount,
        invalidSkillCount = input.sessionContext.skillInventory.invalidSkillCount,
        skillInventoryTrace = input.sessionContext.skillInventory.trace,
        activeSkillTrace = renderedActiveSkill.trace,
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
