package com.opencray.runtime.memory

import com.opencray.persistence.model.MemoryRecord
import com.opencray.runtime.context.ContextPruner
import com.opencray.runtime.context.ContextSourceBudgetPolicy
import com.opencray.runtime.context.ReplayPressureEvaluator
import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.context.RuntimeConversationRole
import com.opencray.runtime.context.TranscriptWindowBuilder
import com.opencray.runtime.context.isAssistantToolCallMessage
import com.opencray.runtime.context.toolResultContentOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

enum class MemoryFlushOutcome {
  NO_PRESSURE,
  ALREADY_FLUSHED,
  NO_CANDIDATES,
  WRITTEN,
}

data class MemoryFlushTrace(
  val outcome: MemoryFlushOutcome? = null,
  val omittedMessageCount: Int = 0,
  val omittedCharCount: Int = 0,
  val signature: String? = null,
  val candidateCount: Int = 0,
  val writtenRecordCount: Int = 0,
  val writtenKinds: List<String> = emptyList(),
  val writtenRecordIds: List<String> = emptyList(),
) {
  val isEmpty: Boolean
    get() = outcome == null &&
      omittedMessageCount == 0 &&
      omittedCharCount == 0 &&
      signature == null &&
      candidateCount == 0 &&
      writtenRecordCount == 0 &&
      writtenKinds.isEmpty() &&
      writtenRecordIds.isEmpty()
}

data class MemoryFlushSummary(
  val writtenRecords: List<MemoryRecord> = emptyList(),
  val trace: MemoryFlushTrace = MemoryFlushTrace(),
) {
  val wasWritten: Boolean
    get() = writtenRecords.isNotEmpty()
}

class MemoryFlushCoordinator(
  private val contextPruner: ContextPruner = ContextPruner(),
  private val transcriptWindowBuilder: TranscriptWindowBuilder = TranscriptWindowBuilder(),
  private val policy: MemoryFlushPolicy = MemoryFlushPolicy(),
  private val replayPressureEvaluator: ReplayPressureEvaluator = ReplayPressureEvaluator(),
  private val candidateExtractor: MemoryCandidateExtractor = MemoryCandidateExtractor(),
  private val writer: MemoryWriter,
  private val existingRecordIdsProvider: (() -> Set<String>)? = null,
  private val sourceBudgetPolicy: ContextSourceBudgetPolicy? = null,
  private val lastFlushedSignatureBySession: ConcurrentMap<String, String> = ConcurrentHashMap(),
  private val flushedCandidateRecordIdsBySession: ConcurrentMap<String, MutableSet<String>> = ConcurrentHashMap(),
) {
  fun flushBeforeCompaction(
    sessionId: String,
    workspaceId: String?,
    conversation: List<RuntimeConversationMessage>,
    llmMetadata: Map<String, String> = emptyMap(),
    taskId: String? = null,
  ): MemoryFlushSummary {
    val effectiveProfile = sourceBudgetPolicy?.resolve(llmMetadata)
    val effectiveTranscriptWindowBuilder = effectiveProfile
      ?.let { profile -> TranscriptWindowBuilder(profile.transcriptWindowConfig) }
      ?: transcriptWindowBuilder
    val effectivePolicy = effectiveProfile?.memoryFlushPolicy ?: policy
    val prunedConversation = contextPruner.prune(conversation).messages
    val replayPressure = replayPressureEvaluator.evaluate(
      conversation = prunedConversation,
      llmMetadata = llmMetadata,
    )
    val omittedMessages = effectiveTranscriptWindowBuilder
      .buildSelection(prunedConversation)
      .omittedMessages
    val omittedCharCount = omittedMessages.sumOf { message -> message.content.length }
    if (!effectivePolicy.shouldFlush(omittedMessages, replayPressure)) {
      return MemoryFlushSummary(
        trace = MemoryFlushTrace(
          outcome = MemoryFlushOutcome.NO_PRESSURE,
          omittedMessageCount = omittedMessages.size,
          omittedCharCount = omittedCharCount,
        ),
      )
    }

    val signature = policy.signatureFor(omittedMessages)
    val flushedCandidateRecordIds = flushedCandidateRecordIdsBySession.computeIfAbsent(sessionId) {
      ConcurrentHashMap.newKeySet<String>()
    }
    syncFlushedCandidateRecordIds(flushedCandidateRecordIds)
    if (lastFlushedSignatureBySession[sessionId] == signature && flushedCandidateRecordIds.isNotEmpty()) {
      return MemoryFlushSummary(
        trace = MemoryFlushTrace(
          outcome = MemoryFlushOutcome.ALREADY_FLUSHED,
          omittedMessageCount = omittedMessages.size,
          omittedCharCount = omittedCharCount,
          signature = signature,
        ),
      )
    }

    val evidence = MemoryTurnEvidence(
      sessionId = sessionId,
      taskId = taskId,
      workspaceId = workspaceId,
      userInput = effectivePolicy.mergeUserInput(
        omittedMessages.filter { message -> message.role == RuntimeConversationRole.USER },
      ),
      assistantOutput = effectivePolicy.mergeAssistantOutput(
        omittedMessages.filter { message ->
          message.role == RuntimeConversationRole.ASSISTANT &&
            !message.isAssistantToolCallMessage()
        },
      ),
      toolObservations = omittedMessages
        .asSequence()
        .mapNotNull(RuntimeConversationMessage::toolResultContentOrNull)
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
        .take(effectivePolicy.maxToolObservations)
        .toList(),
    )
    val candidates = candidateExtractor.extract(evidence)
    if (candidates.isEmpty()) {
      lastFlushedSignatureBySession[sessionId] = signature
      return MemoryFlushSummary(
        trace = MemoryFlushTrace(
          outcome = MemoryFlushOutcome.NO_CANDIDATES,
          omittedMessageCount = omittedMessages.size,
          omittedCharCount = omittedCharCount,
          signature = signature,
        ),
      )
    }
    val candidateEntries = candidates
      .map { candidate -> stableMemoryRecordId(candidate) to candidate }
      .distinctBy { (recordId, _) -> recordId }
    val pendingCandidateEntries = candidateEntries.filter { (recordId, _) ->
      recordId !in flushedCandidateRecordIds
    }
    if (pendingCandidateEntries.isEmpty()) {
      lastFlushedSignatureBySession[sessionId] = signature
      return MemoryFlushSummary(
        trace = MemoryFlushTrace(
          outcome = MemoryFlushOutcome.ALREADY_FLUSHED,
          omittedMessageCount = omittedMessages.size,
          omittedCharCount = omittedCharCount,
          signature = signature,
          candidateCount = candidateEntries.size,
        ),
      )
    }

    val writeSummary = writer.write(pendingCandidateEntries.map { (_, candidate) -> candidate })
    flushedCandidateRecordIds += pendingCandidateEntries.map { (recordId, _) -> recordId }
    lastFlushedSignatureBySession[sessionId] = signature
    return MemoryFlushSummary(
      writtenRecords = writeSummary.writtenRecords,
      trace = MemoryFlushTrace(
        outcome = MemoryFlushOutcome.WRITTEN,
        omittedMessageCount = omittedMessages.size,
        omittedCharCount = omittedCharCount,
        signature = signature,
        candidateCount = candidateEntries.size,
        writtenRecordCount = writeSummary.writtenRecords.size,
        writtenKinds = writeSummary.writtenRecords.mapNotNull { record ->
          record.extensions[MemoryRecordExtensionKeys.KIND]
        }.distinct().sorted(),
        writtenRecordIds = writeSummary.writtenRecords.map(MemoryRecord::id),
      ),
    )
  }

  private fun syncFlushedCandidateRecordIds(flushedCandidateRecordIds: MutableSet<String>) {
    val existingRecordIds = existingRecordIdsProvider?.invoke() ?: return
    flushedCandidateRecordIds.retainAll(existingRecordIds)
  }
}
