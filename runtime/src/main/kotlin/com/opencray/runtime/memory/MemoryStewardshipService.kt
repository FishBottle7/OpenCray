package com.opencray.runtime.memory

import com.opencray.persistence.model.MemoryRecord
import com.opencray.runtime.soul.hasSoulObjectPayload
import java.util.Locale

data class MemoryStewardshipPlan(
  val acceptedCandidates: List<MemoryCandidate> = emptyList(),
  val resolvedRecords: List<MemoryRecord> = emptyList(),
  val reaffirmedRecords: List<MemoryRecord> = emptyList(),
  val droppedCandidates: List<MemoryCandidate> = emptyList(),
) {
  val isEmpty: Boolean
    get() = resolvedRecords.isEmpty() && reaffirmedRecords.isEmpty() && droppedCandidates.isEmpty()
}

class MemoryStewardshipService(
  private val policy: MemoryPolicy = MemoryPolicy(),
  private val clock: () -> Long = System::currentTimeMillis,
  private val interpreter: MemoryStewardshipInterpreter = NoOpMemoryStewardshipInterpreter,
  private val failClosedOnInterpreterUnavailable: Boolean = false,
  private val candidateOnlyReviewKinds: Set<MemoryKind> = emptySet(),
  private val recordOnlyReviewKinds: Set<MemoryKind> = emptySet(),
) {
  fun plan(
    existingRecords: List<MemoryRecord>,
    evidence: MemoryTurnEvidence,
    proposedCandidates: List<MemoryCandidate>,
  ): MemoryStewardshipPlan {
    val candidateViews = proposedCandidates
      .mapIndexedNotNull { index, candidate ->
        candidate.toStewardableCandidate(index = index)
      }
    val candidateRelatedRecords = if (candidateViews.isEmpty()) {
      emptyList()
    } else {
      shortlistRelatedRecords(
        existingRecords = existingRecords,
        evidence = evidence,
        candidates = candidateViews,
      )
    }
    val recordOnlyRelatedRecords = shortlistRecordOnlyRecords(
      existingRecords = existingRecords,
      evidence = evidence,
    )
    val relatedRecords = mergeRelatedRecords(
      primary = candidateRelatedRecords,
      secondary = recordOnlyRelatedRecords,
      maxCount = MAX_RELATED_RECORDS_TOTAL,
    )
    val shouldReview = if (candidateViews.isEmpty()) {
      relatedRecords.isNotEmpty()
    } else {
      relatedRecords.isNotEmpty() || shouldReviewCandidateOnly(candidateViews)
    }
    if (!shouldReview) {
      return MemoryStewardshipPlan(acceptedCandidates = proposedCandidates)
    }
    return when (
      val interpretation = interpreter.interpret(
        MemoryStewardshipRequest(
          sessionId = evidence.sessionId,
          workspaceId = evidence.workspaceId,
          userInput = evidence.userInput,
          assistantOutput = evidence.assistantOutput,
          toolObservations = evidence.toolObservations,
          activeRecords = relatedRecords.map { it.view },
          proposedCandidates = candidateViews,
        ),
      )
    ) {
      is MemoryStewardshipInterpretation.Success -> applyDecisions(
        relatedRecords = relatedRecords.associateBy { record -> record.view.id },
        candidateViews = candidateViews.associateBy(StewardableMemoryCandidate::index),
        proposedCandidates = proposedCandidates,
        decisions = interpretation.decisions,
      )
      is MemoryStewardshipInterpretation.Unavailable -> {
        unavailablePlan(
          proposedCandidates = proposedCandidates,
          stewardableCandidateIndexes = candidateViews.mapTo(linkedSetOf(), StewardableMemoryCandidate::index),
        )
      }
    }
  }

  private fun mergeRelatedRecords(
    primary: List<RelatedMemoryRecord>,
    secondary: List<RelatedMemoryRecord>,
    maxCount: Int,
  ): List<RelatedMemoryRecord> {
    val merged = linkedMapOf<String, RelatedMemoryRecord>()
    (primary + secondary).forEach { related ->
      val previous = merged[related.view.id]
      if (previous == null || related.score > previous.score) {
        merged[related.view.id] = related
      }
    }
    return merged.values
      .sortedWith(
        compareByDescending<RelatedMemoryRecord> { it.score }
          .thenByDescending { it.record.updatedAtEpochMs }
          .thenBy { it.view.id },
      )
      .take(maxCount)
  }

  private fun unavailablePlan(
    proposedCandidates: List<MemoryCandidate>,
    stewardableCandidateIndexes: Set<Int>,
  ): MemoryStewardshipPlan {
    if (!failClosedOnInterpreterUnavailable) {
      return MemoryStewardshipPlan(acceptedCandidates = proposedCandidates)
    }
    val acceptedCandidates = proposedCandidates.filterIndexed { index, _ ->
      index !in stewardableCandidateIndexes
    }
    val droppedCandidates = proposedCandidates.filterIndexed { index, _ ->
      index in stewardableCandidateIndexes
    }
    return MemoryStewardshipPlan(
      acceptedCandidates = acceptedCandidates,
      droppedCandidates = droppedCandidates,
    )
  }

  private fun applyDecisions(
    relatedRecords: Map<String, RelatedMemoryRecord>,
    candidateViews: Map<Int, StewardableMemoryCandidate>,
    proposedCandidates: List<MemoryCandidate>,
    decisions: List<MemoryStewardshipDecision>,
  ): MemoryStewardshipPlan {
    val now = clock()
    val acceptedCandidatesByIndex = proposedCandidates
      .mapIndexed { index, candidate -> index to candidate }
      .toMap(linkedMapOf())
    val droppedCandidateIndexes = linkedSetOf<Int>()
    val mergedCandidateIndexes = linkedSetOf<Int>()
    val reaffirmedRecords = linkedMapOf<String, MemoryRecord>()
    val resolvedRecords = linkedMapOf<String, MemoryRecord>()
    val supersededCountsByCandidateIndex = linkedMapOf<Int, Int>()

    decisions.forEach { decision ->
      when (decision.action) {
        MemoryStewardshipAction.REFRESH_RECORD_WITH_CANDIDATE -> {
          val relatedRecord = relatedRecords[decision.recordId] ?: return@forEach
          val candidateIndex = decision.candidateIndex ?: return@forEach
          val candidateView = candidateViews[candidateIndex] ?: return@forEach
          if (resolvedRecords.containsKey(relatedRecord.view.id)) {
            return@forEach
          }
          if (droppedCandidateIndexes.size >= MAX_DROPPED_CANDIDATES_PER_TURN) {
            return@forEach
          }
          if (droppedCandidateIndexes.contains(candidateIndex)) {
            return@forEach
          }
          if (
            mergedCandidateIndexes.contains(candidateIndex) ||
            supersededCountsByCandidateIndex[candidateIndex] != null
          ) {
            return@forEach
          }
          if (!canRefresh(record = relatedRecord, candidate = candidateView)) {
            return@forEach
          }
          droppedCandidateIndexes += candidateView.index
          reaffirmedRecords.putIfAbsent(
            relatedRecord.view.id,
            reaffirmRecord(relatedRecord.record, now),
          )
        }

        MemoryStewardshipAction.DROP_CANDIDATE -> {
          val candidateIndex = decision.candidateIndex ?: return@forEach
          val candidate = candidateViews[candidateIndex] ?: return@forEach
          if (droppedCandidateIndexes.size >= MAX_DROPPED_CANDIDATES_PER_TURN) {
            return@forEach
          }
          if (
            mergedCandidateIndexes.contains(candidateIndex) ||
            supersededCountsByCandidateIndex[candidateIndex] != null
          ) {
            return@forEach
          }
          droppedCandidateIndexes += candidate.index
        }

        MemoryStewardshipAction.REAFFIRM_RECORD -> {
          val relatedRecord = relatedRecords[decision.recordId] ?: return@forEach
          if (resolvedRecords.containsKey(relatedRecord.view.id)) {
            return@forEach
          }
          reaffirmedRecords.putIfAbsent(
            relatedRecord.view.id,
            reaffirmRecord(relatedRecord.record, now),
          )
        }

        MemoryStewardshipAction.RESOLVE_RECORD -> {
          val relatedRecord = relatedRecords[decision.recordId] ?: return@forEach
          if (resolvedRecords.size >= MAX_RESOLVED_RECORDS_PER_TURN) {
            return@forEach
          }
          if (reaffirmedRecords.containsKey(relatedRecord.view.id)) {
            return@forEach
          }
          val reason = decision.resolutionReason ?: return@forEach
          resolvedRecords.putIfAbsent(
            relatedRecord.view.id,
            resolveRecord(
              record = relatedRecord.record,
              nowEpochMs = now,
              resolutionReason = reason.wireValue,
              supersededBy = null,
            ),
          )
        }

        MemoryStewardshipAction.MERGE_RECORD_WITH_CANDIDATE -> {
          val relatedRecord = relatedRecords[decision.recordId] ?: return@forEach
          val candidateIndex = decision.candidateIndex ?: return@forEach
          val candidateView = candidateViews[candidateIndex] ?: return@forEach
          val candidate = acceptedCandidatesByIndex[candidateIndex] ?: return@forEach
          if (droppedCandidateIndexes.contains(candidateIndex)) {
            return@forEach
          }
          if (mergedCandidateIndexes.contains(candidateIndex)) {
            return@forEach
          }
          if (resolvedRecords.size >= MAX_RESOLVED_RECORDS_PER_TURN) {
            return@forEach
          }
          if (!canMerge(record = relatedRecord, candidate = candidateView)) {
            return@forEach
          }
          val mergedCandidate = mergeCandidate(
            record = relatedRecord,
            candidate = candidate,
          ) ?: return@forEach
          val inserted = resolvedRecords.putIfAbsent(
            relatedRecord.view.id,
            resolveRecord(
              record = relatedRecord.record,
              nowEpochMs = now,
              resolutionReason = RESOLUTION_REASON_MERGED,
              supersededBy = stableMemoryRecordId(mergedCandidate),
            ),
          ) == null
          if (!inserted) {
            return@forEach
          }
          acceptedCandidatesByIndex[candidateIndex] = mergedCandidate
          mergedCandidateIndexes += candidateIndex
          reaffirmedRecords.remove(relatedRecord.view.id)
        }

        MemoryStewardshipAction.SUPERSEDE_RECORD_WITH_CANDIDATE -> {
          val relatedRecord = relatedRecords[decision.recordId] ?: return@forEach
          val candidateIndex = decision.candidateIndex ?: return@forEach
          val candidateView = candidateViews[candidateIndex] ?: return@forEach
          if (droppedCandidateIndexes.contains(candidateIndex)) {
            return@forEach
          }
          if (mergedCandidateIndexes.contains(candidateIndex)) {
            return@forEach
          }
          if (resolvedRecords.size >= MAX_RESOLVED_RECORDS_PER_TURN) {
            return@forEach
          }
          val currentCount = supersededCountsByCandidateIndex[candidateIndex] ?: 0
          if (currentCount >= MAX_SUPERSEDED_RECORDS_PER_CANDIDATE) {
            return@forEach
          }
          if (!canSupersede(record = relatedRecord, candidate = candidateView)) {
            return@forEach
          }
          val inserted = resolvedRecords.putIfAbsent(
            relatedRecord.view.id,
            resolveRecord(
              record = relatedRecord.record,
              nowEpochMs = now,
              resolutionReason = RESOLUTION_REASON_SUPERSEDED,
              supersededBy = stableMemoryRecordId(proposedCandidates[candidateIndex]),
            ),
          ) == null
          if (inserted) {
            supersededCountsByCandidateIndex[candidateIndex] = currentCount + 1
          }
          reaffirmedRecords.remove(relatedRecord.view.id)
        }
      }
    }

    val acceptedCandidates = acceptedCandidatesByIndex
      .asSequence()
      .filterNot { (index, _) -> index in droppedCandidateIndexes }
      .sortedBy { (index, _) -> index }
      .map { (_, candidate) -> candidate }
      .toList()
    val droppedCandidates = proposedCandidates.filterIndexed { index, _ ->
      index in droppedCandidateIndexes
    }
    return MemoryStewardshipPlan(
      acceptedCandidates = acceptedCandidates,
      resolvedRecords = resolvedRecords.values.toList(),
      reaffirmedRecords = reaffirmedRecords
        .filterKeys { recordId -> recordId !in resolvedRecords }
        .values
        .toList(),
      droppedCandidates = droppedCandidates,
    )
  }

  private fun shortlistRelatedRecords(
    existingRecords: List<MemoryRecord>,
    evidence: MemoryTurnEvidence,
    candidates: List<StewardableMemoryCandidate>,
  ): List<RelatedMemoryRecord> {
    val shortlisted = linkedMapOf<String, RelatedMemoryRecord>()
    candidates.forEach { candidate ->
      existingRecords
        .mapNotNull { record ->
          val related = relatedRecordOrNull(
            record = record,
            candidate = candidate,
            evidence = evidence,
          ) ?: return@mapNotNull null
          related
        }
        .sortedWith(
          compareByDescending<RelatedMemoryRecord> { it.score }
            .thenByDescending { it.record.updatedAtEpochMs }
            .thenBy { it.view.id },
        )
        .take(MAX_RELATED_RECORDS_PER_CANDIDATE)
        .forEach { related ->
          val previous = shortlisted[related.view.id]
          if (previous == null || related.score > previous.score) {
            shortlisted[related.view.id] = related
          }
        }
    }
    return shortlisted.values
      .sortedWith(
        compareByDescending<RelatedMemoryRecord> { it.score }
          .thenByDescending { it.record.updatedAtEpochMs }
          .thenBy { it.view.id },
      )
      .take(MAX_RELATED_RECORDS_TOTAL)
  }

  private fun shortlistRecordOnlyRecords(
    existingRecords: List<MemoryRecord>,
    evidence: MemoryTurnEvidence,
  ): List<RelatedMemoryRecord> {
    if (recordOnlyReviewKinds.isEmpty()) {
      return emptyList()
    }
    val evidenceText = recordOnlyEvidenceText(evidence)
    if (evidenceText.isBlank()) {
      return emptyList()
    }
    val evidenceTerms = topicTerms(evidenceText)
    val evidenceLowered = evidenceText.lowercase(Locale.US)
    return existingRecords
      .mapNotNull { record ->
        recordOnlyRelatedRecordOrNull(
          record = record,
          evidence = evidence,
          evidenceTerms = evidenceTerms,
          evidenceLowered = evidenceLowered,
        )
      }
      .sortedWith(
        compareByDescending<RelatedMemoryRecord> { it.score }
          .thenByDescending { it.record.updatedAtEpochMs }
          .thenBy { it.view.id },
      )
      .take(MAX_RECORD_ONLY_RELATED_RECORDS_TOTAL)
  }

  private fun relatedRecordOrNull(
    record: MemoryRecord,
    candidate: StewardableMemoryCandidate,
    evidence: MemoryTurnEvidence,
  ): RelatedMemoryRecord? {
    if (record.hasSoulObjectPayload()) {
      return null
    }
    val metadata = record.parseMemoryMetadata() ?: return null
    if (metadata.status != MemoryStatus.ACTIVE) {
      return null
    }
    if (metadata.kind != candidate.kind) {
      return null
    }
    if (
      memoryRecordExpired(
        ttlMs = metadata.ttlMs,
        lastConfirmedAtEpochMs = metadata.lastConfirmedAtEpochMs,
        updatedAtEpochMs = record.updatedAtEpochMs,
        nowEpochMs = clock(),
      )
    ) {
      return null
    }
    if (!scopeIdentityMatches(metadata = metadata, candidate = candidate)) {
      return null
    }
    if (record.id == stableMemoryRecordId(candidate.toMemoryCandidate())) {
      return null
    }
    if (!preferenceShapeMatches(metadata = metadata, candidate = candidate)) {
      return null
    }
    val score = relatedRecordScore(
      record = record,
      metadata = metadata,
      candidate = candidate,
      evidence = evidence,
    )
    return RelatedMemoryRecord(
      record = record,
      view = StewardableMemoryRecord(
        id = record.id,
        kind = metadata.kind,
        scope = metadata.scope,
        content = record.content,
        source = metadata.source,
        sourceSessionId = metadata.sourceSessionId,
        workspaceId = metadata.workspaceId,
        updatedAtEpochMs = record.updatedAtEpochMs,
        lastConfirmedAtEpochMs = metadata.lastConfirmedAtEpochMs,
        preferenceKey = metadata.preferenceKey,
        preferenceValue = metadata.preferenceValue,
      ),
      score = score,
    )
  }

  private fun recordOnlyRelatedRecordOrNull(
    record: MemoryRecord,
    evidence: MemoryTurnEvidence,
    evidenceTerms: Set<String>,
    evidenceLowered: String,
  ): RelatedMemoryRecord? {
    if (record.hasSoulObjectPayload()) {
      return null
    }
    val metadata = record.parseMemoryMetadata() ?: return null
    if (metadata.status != MemoryStatus.ACTIVE) {
      return null
    }
    if (metadata.kind !in recordOnlyReviewKinds) {
      return null
    }
    if (
      !memoryScopeMatches(
        scope = metadata.scope,
        sourceSessionId = metadata.sourceSessionId,
        recordWorkspaceId = metadata.workspaceId,
        requestSessionId = evidence.sessionId,
        requestWorkspaceId = evidence.workspaceId,
      )
    ) {
      return null
    }
    if (
      memoryRecordExpired(
        ttlMs = metadata.ttlMs,
        lastConfirmedAtEpochMs = metadata.lastConfirmedAtEpochMs,
        updatedAtEpochMs = record.updatedAtEpochMs,
        nowEpochMs = clock(),
      )
    ) {
      return null
    }
    val score = recordOnlyReviewScore(
      record = record,
      metadata = metadata,
      evidenceTerms = evidenceTerms,
      evidenceLowered = evidenceLowered,
    )
    if (score < MIN_RECORD_ONLY_RELATED_RECORD_SCORE) {
      return null
    }
    return RelatedMemoryRecord(
      record = record,
      view = StewardableMemoryRecord(
        id = record.id,
        kind = metadata.kind,
        scope = metadata.scope,
        content = record.content,
        source = metadata.source,
        sourceSessionId = metadata.sourceSessionId,
        workspaceId = metadata.workspaceId,
        updatedAtEpochMs = record.updatedAtEpochMs,
        lastConfirmedAtEpochMs = metadata.lastConfirmedAtEpochMs,
        preferenceKey = metadata.preferenceKey,
        preferenceValue = metadata.preferenceValue,
      ),
      score = score,
    )
  }

  private fun relatedRecordScore(
    record: MemoryRecord,
    metadata: ParsedMemoryMetadata,
    candidate: StewardableMemoryCandidate,
    evidence: MemoryTurnEvidence,
  ): Int {
    var score = when (candidate.kind) {
      MemoryKind.USER_PREFERENCE -> 140
      MemoryKind.DURABLE_INSTRUCTION -> 110
      MemoryKind.PROJECT_FACT -> 90
      MemoryKind.TASK_COMMITMENT -> 0
    }
    if (candidate.preferenceKey != null && metadata.preferenceKey == candidate.preferenceKey) {
      score += 90
    }
    val queryTerms = extractMemoryQueryTerms("${candidate.content} ${evidence.userInput}")
    val contentLowered = record.content.lowercase(Locale.US)
    score += queryTerms.count { term -> contentLowered.contains(term) } * 30
    score += when (ageDays(record.updatedAtEpochMs)) {
      in 0..1 -> 18
      in 2..7 -> 10
      in 8..30 -> 4
      else -> 0
    }
    if (record.content.length <= 140) {
      score += 4
    }
    return score
  }

  private fun recordOnlyReviewScore(
    record: MemoryRecord,
    metadata: ParsedMemoryMetadata,
    evidenceTerms: Set<String>,
    evidenceLowered: String,
  ): Int {
    val recordTerms = topicTerms(record.content)
    val overlap = recordTerms intersect evidenceTerms
    val directMention = when (metadata.kind) {
      MemoryKind.USER_PREFERENCE -> {
        val preferenceValue = metadata.preferenceValue
          ?.trim()
          ?.lowercase(Locale.US)
          ?.takeIf(String::isNotBlank)
        (!preferenceValue.isNullOrBlank() && evidenceLowered.contains(preferenceValue)) ||
          recordTerms.any { term ->
            isHighSignalTopicToken(term) && evidenceLowered.contains(term)
          }
      }

      MemoryKind.DURABLE_INSTRUCTION,
      MemoryKind.PROJECT_FACT,
      -> recordTerms.any { term ->
        isHighSignalTopicToken(term) && evidenceLowered.contains(term)
      }

      MemoryKind.TASK_COMMITMENT -> false
    }
    if (!directMention && !sharesUnderlyingTopic(recordTerms = recordTerms, candidateTerms = evidenceTerms)) {
      return 0
    }
    var score = when (metadata.kind) {
      MemoryKind.USER_PREFERENCE -> 120
      MemoryKind.DURABLE_INSTRUCTION -> 100
      MemoryKind.PROJECT_FACT -> 80
      MemoryKind.TASK_COMMITMENT -> 0
    }
    if (directMention) {
      score += 90
    }
    score += overlap.count(::isHighSignalTopicToken) * 50
    score += overlap.size * 20
    score += when (ageDays(record.updatedAtEpochMs)) {
      in 0..1 -> 18
      in 2..7 -> 10
      in 8..30 -> 4
      else -> 0
    }
    return score
  }

  private fun ageDays(updatedAtEpochMs: Long): Long =
    ((clock() - updatedAtEpochMs).coerceAtLeast(0L)) / DAY_MS

  private fun scopeIdentityMatches(
    metadata: ParsedMemoryMetadata,
    candidate: StewardableMemoryCandidate,
  ): Boolean = when (candidate.scope) {
    MemoryScope.USER -> metadata.scope == MemoryScope.USER
    MemoryScope.SESSION -> {
      metadata.scope == MemoryScope.SESSION &&
        metadata.sourceSessionId == candidate.sourceSessionId
    }
    MemoryScope.WORKSPACE -> {
      metadata.scope == MemoryScope.WORKSPACE &&
        metadata.workspaceId == candidate.workspaceId
    }
  }

  private fun preferenceShapeMatches(
    metadata: ParsedMemoryMetadata,
    candidate: StewardableMemoryCandidate,
  ): Boolean = when (candidate.kind) {
    MemoryKind.USER_PREFERENCE -> {
      val recordPreferenceKey = metadata.preferenceKey
      when {
        candidate.preferenceKey != null && recordPreferenceKey != null ->
          candidate.preferenceKey == recordPreferenceKey

        candidate.preferenceKey == null && recordPreferenceKey == null -> true
        else -> false
      }
    }
    else -> true
  }

  private fun canSupersede(
    record: RelatedMemoryRecord,
    candidate: StewardableMemoryCandidate,
  ): Boolean {
    if (record.view.kind != candidate.kind || record.view.scope != candidate.scope) {
      return false
    }
    return when (candidate.kind) {
      MemoryKind.USER_PREFERENCE -> {
        candidate.preferenceKey != null &&
          candidate.preferenceKey == record.view.preferenceKey
      }
      MemoryKind.DURABLE_INSTRUCTION,
      MemoryKind.PROJECT_FACT,
      -> sharesUnderlyingTopic(
        recordContent = record.view.content,
        candidateContent = candidate.content,
      )
      MemoryKind.TASK_COMMITMENT -> false
    }
  }

  private fun canMerge(
    record: RelatedMemoryRecord,
    candidate: StewardableMemoryCandidate,
  ): Boolean {
    if (record.view.kind != candidate.kind || record.view.scope != candidate.scope) {
      return false
    }
    return when (candidate.kind) {
      MemoryKind.DURABLE_INSTRUCTION,
      MemoryKind.PROJECT_FACT,
      -> sharesUnderlyingTopic(
        recordContent = record.view.content,
        candidateContent = candidate.content,
      ) &&
        !isPureReconfirmation(
          recordContent = record.view.content,
          candidateContent = candidate.content,
        ) &&
        !hasLikelyScalarConflict(
          recordContent = record.view.content,
          candidateContent = candidate.content,
        )

      MemoryKind.USER_PREFERENCE,
      MemoryKind.TASK_COMMITMENT,
      -> false
    }
  }

  private fun canRefresh(
    record: RelatedMemoryRecord,
    candidate: StewardableMemoryCandidate,
  ): Boolean {
    if (record.view.kind != candidate.kind || record.view.scope != candidate.scope) {
      return false
    }
    return when (candidate.kind) {
      MemoryKind.USER_PREFERENCE -> {
        candidate.preferenceKey != null &&
          candidate.preferenceKey == record.view.preferenceKey &&
          candidate.preferenceValue != null &&
          candidate.preferenceValue == record.view.preferenceValue
      }
      MemoryKind.DURABLE_INSTRUCTION,
      MemoryKind.PROJECT_FACT,
      -> isPureReconfirmation(
        recordContent = record.view.content,
        candidateContent = candidate.content,
      )
      MemoryKind.TASK_COMMITMENT -> false
    }
  }

  private fun isPureReconfirmation(
    recordContent: String,
    candidateContent: String,
  ): Boolean {
    val recordTerms = topicTerms(recordContent)
    val candidateTerms = topicTerms(candidateContent)
    if (!sharesUnderlyingTopic(recordTerms = recordTerms, candidateTerms = candidateTerms)) {
      return false
    }
    val candidateUniqueHighSignalTerms = (candidateTerms - recordTerms)
      .filterTo(linkedSetOf(), ::isHighSignalRefreshDeltaToken)
    return candidateUniqueHighSignalTerms.isEmpty()
  }

  private fun hasLikelyScalarConflict(
    recordContent: String,
    candidateContent: String,
  ): Boolean {
    val recordScalarTokens = scalarTokens(recordContent)
    val candidateScalarTokens = scalarTokens(candidateContent)
    return recordScalarTokens.isNotEmpty() &&
      candidateScalarTokens.isNotEmpty() &&
      recordScalarTokens != candidateScalarTokens
  }

  private fun sharesUnderlyingTopic(
    recordContent: String,
    candidateContent: String,
  ): Boolean = sharesUnderlyingTopic(
    recordTerms = topicTerms(recordContent),
    candidateTerms = topicTerms(candidateContent),
  )

  private fun sharesUnderlyingTopic(
    recordTerms: Set<String>,
    candidateTerms: Set<String>,
  ): Boolean {
    if (recordTerms.isEmpty() || candidateTerms.isEmpty()) {
      return false
    }
    val overlap = recordTerms intersect candidateTerms
    if (overlap.size >= 2) {
      return true
    }
    val sharedToken = overlap.singleOrNull() ?: return false
    return isHighSignalTopicToken(sharedToken)
      }

  private fun topicTerms(
    text: String,
  ): Set<String> = extractMemoryQueryTerms(text)
    .mapTo(linkedSetOf()) { token -> token.lowercase(Locale.US) }
    .filterNot { token -> token in LOW_SIGNAL_TOPIC_TERMS }
    .toCollection(linkedSetOf())

  private fun isHighSignalTopicToken(token: String): Boolean =
    token !in NON_DISTINGUISHING_SINGLE_TOPIC_TERMS &&
      (
        token.any(Char::isDigit) ||
          token.length >= 5 ||
          token.any { character ->
            character == '/' || character == '_' || character == '-'
          }
        )

  private fun isHighSignalRefreshDeltaToken(token: String): Boolean =
    token !in NON_DISTINGUISHING_REFRESH_DELTA_TERMS &&
      (
        token.any(Char::isDigit) ||
          token.length >= 4 ||
          token.any { character ->
            character == '/' || character == '_' || character == '-'
          }
      )

  private fun scalarTokens(
    text: String,
  ): Set<String> = extractMemoryQueryTerms(text)
    .mapTo(linkedSetOf()) { token -> token.lowercase(Locale.US) }
    .filterTo(linkedSetOf()) { token ->
      token.any(Char::isDigit)
    }

  private fun shouldReviewCandidateOnly(
    candidates: List<StewardableMemoryCandidate>,
  ): Boolean = candidates.size > 1 || candidates.any { candidate ->
    candidate.kind in candidateOnlyReviewKinds
  }

  private fun recordOnlyEvidenceText(
    evidence: MemoryTurnEvidence,
  ): String = buildString {
    append(evidence.userInput)
    evidence.toolObservations.forEach { observation ->
      append(' ')
      append(observation)
    }
  }.trim()

  private fun MemoryCandidate.toStewardableCandidate(index: Int): StewardableMemoryCandidate? {
    if (kind !in STEWARDABLE_KINDS) {
      return null
    }
    return StewardableMemoryCandidate(
      index = index,
      kind = kind,
      scope = scope,
      content = content,
      source = source,
      sourceSessionId = sourceSessionId,
      sourceTaskId = sourceTaskId,
      workspaceId = workspaceId,
      preferenceKey = normalizeMemoryPreferenceKeyOrNull(
        extensions[MemoryRecordExtensionKeys.PREFERENCE_KEY],
      ),
      preferenceValue = normalizeMemoryPreferenceValueOrNull(
        extensions[MemoryRecordExtensionKeys.PREFERENCE_VALUE],
      ),
    )
  }

  private fun StewardableMemoryCandidate.toMemoryCandidate(): MemoryCandidate = MemoryCandidate(
    kind = kind,
    scope = scope,
    status = policy.defaultStatusFor(kind),
    content = content,
    source = source,
    sourceSessionId = sourceSessionId,
    sourceTaskId = sourceTaskId,
    workspaceId = workspaceId,
    ttlMs = policy.ttlMsFor(kind),
    extensions = buildMap {
      preferenceKey?.let { key -> put(MemoryRecordExtensionKeys.PREFERENCE_KEY, key) }
      preferenceValue?.let { value -> put(MemoryRecordExtensionKeys.PREFERENCE_VALUE, value) }
    },
  )

  private fun mergeCandidate(
    record: RelatedMemoryRecord,
    candidate: MemoryCandidate,
  ): MemoryCandidate? {
    val mergedContent = mergeRecordAndCandidateContent(
      recordContent = record.view.content,
      candidateContent = candidate.content,
    ) ?: return null
    return candidate.copy(
      content = mergedContent,
      extensions = buildMap {
        putAll(candidate.extensions)
        val mergedFromRecordIds = linkedSetOf<String>()
        record.record.extensions[MemoryRecordExtensionKeys.MERGED_FROM_RECORD_IDS]
          ?.split(',')
          ?.map(String::trim)
          ?.filter(String::isNotBlank)
          ?.forEach(mergedFromRecordIds::add)
        mergedFromRecordIds += record.record.id
        put(
          MemoryRecordExtensionKeys.MERGED_FROM_RECORD_IDS,
          mergedFromRecordIds.joinToString(separator = ","),
        )
        put(MemoryRecordExtensionKeys.MERGE_STRATEGY, MERGE_STRATEGY_APPEND_CLAUSES)
      },
    )
  }

  private fun mergeRecordAndCandidateContent(
    recordContent: String,
    candidateContent: String,
  ): String? {
    val recordClause = normalizeMergeClause(recordContent) ?: return null
    val candidateClause = normalizeMergeClause(candidateContent) ?: return null
    if (recordClause.equals(candidateClause, ignoreCase = true)) {
      return recordClause
    }
    val recordLower = recordClause.lowercase(Locale.US)
    val candidateLower = candidateClause.lowercase(Locale.US)
    return when {
      candidateLower.contains(recordLower) -> candidateClause
      recordLower.contains(candidateLower) -> recordClause
      else -> linkedMapOf(
        normalizedMergeClauseKey(recordClause) to recordClause,
        normalizedMergeClauseKey(candidateClause) to candidateClause,
      ).values.joinToString(separator = "; ")
    }
  }

  private fun normalizeMergeClause(text: String): String? = text
    .replace(Regex("\\s+"), " ")
    .trim()
    .trim(';', '；', '.', '。', ',', '，')
    .takeIf(String::isNotBlank)

  private fun normalizedMergeClauseKey(text: String): String = text.lowercase(Locale.US)

  private fun reaffirmRecord(
    record: MemoryRecord,
    nowEpochMs: Long,
  ): MemoryRecord = record.copy(
    recordVersion = record.recordVersion + 1L,
    updatedAtEpochMs = maxOf(record.createdAtEpochMs, nowEpochMs),
    extensions = record.extensions + mapOf(
      MemoryRecordExtensionKeys.LAST_CONFIRMED_AT_EPOCH_MS to nowEpochMs.toString(),
    ),
  )

  private fun resolveRecord(
    record: MemoryRecord,
    nowEpochMs: Long,
    resolutionReason: String,
    supersededBy: String?,
  ): MemoryRecord {
    val nextExtensions = buildMap<String, String> {
      putAll(record.extensions)
      put(MemoryRecordExtensionKeys.STATUS, MemoryStatus.RESOLVED.name.lowercase(Locale.US))
      put(MemoryRecordExtensionKeys.RESOLVED_AT_EPOCH_MS, nowEpochMs.toString())
      put(MemoryRecordExtensionKeys.RESOLUTION_REASON, resolutionReason)
      put(MemoryRecordExtensionKeys.LAST_CONFIRMED_AT_EPOCH_MS, nowEpochMs.toString())
      if (!supersededBy.isNullOrBlank()) {
        put(MemoryRecordExtensionKeys.SUPERSEDED_BY, supersededBy)
      } else {
        remove(MemoryRecordExtensionKeys.SUPERSEDED_BY)
      }
    }
    return record.copy(
      tags = record.tags
        .filterNot { tag -> tag.startsWith("status:") }
        .plus("status:${MemoryStatus.RESOLVED.name.lowercase(Locale.US)}")
        .distinct()
        .sorted(),
      recordVersion = record.recordVersion + 1L,
      updatedAtEpochMs = maxOf(record.createdAtEpochMs, nowEpochMs),
      extensions = nextExtensions,
    )
  }

  private data class RelatedMemoryRecord(
    val record: MemoryRecord,
    val view: StewardableMemoryRecord,
    val score: Int,
  )

  private companion object {
    val STEWARDABLE_KINDS: Set<MemoryKind> = setOf(
      MemoryKind.USER_PREFERENCE,
      MemoryKind.DURABLE_INSTRUCTION,
      MemoryKind.PROJECT_FACT,
    )
    const val RESOLUTION_REASON_MERGED: String = "merged"
    const val RESOLUTION_REASON_SUPERSEDED: String = "superseded"
    const val MERGE_STRATEGY_APPEND_CLAUSES: String = "append_clauses"
    const val DAY_MS: Long = 24L * 60L * 60L * 1000L
    const val MAX_RELATED_RECORDS_PER_CANDIDATE: Int = 3
    const val MAX_RELATED_RECORDS_TOTAL: Int = 8
    const val MAX_RECORD_ONLY_RELATED_RECORDS_TOTAL: Int = 4
    const val MAX_RESOLVED_RECORDS_PER_TURN: Int = 4
    const val MAX_SUPERSEDED_RECORDS_PER_CANDIDATE: Int = 2
    const val MAX_DROPPED_CANDIDATES_PER_TURN: Int = 4
    const val MIN_RECORD_ONLY_RELATED_RECORD_SCORE: Int = 120
    val LOW_SIGNAL_TOPIC_TERMS: Set<String> = setOf(
      "use",
      "uses",
      "using",
      "avoid",
      "please",
      "in",
      "on",
      "for",
      "to",
      "current",
      "default",
      "always",
      "never",
      "remember",
      "should",
      "keep",
      "still",
      "now",
      "以后",
      "现在",
      "记住",
      "继续",
    )
    val NON_DISTINGUISHING_SINGLE_TOPIC_TERMS: Set<String> = setOf(
      "project",
      "repo",
      "repository",
      "workspace",
      "rule",
      "rules",
    )
    val NON_DISTINGUISHING_REFRESH_DELTA_TERMS: Set<String> = setOf(
      "command",
      "commands",
      "listen",
      "listens",
      "listening",
      "path",
      "paths",
      "port",
      "ports",
      "run",
      "runs",
      "running",
      "serve",
      "serves",
      "serving",
    )
  }
}
