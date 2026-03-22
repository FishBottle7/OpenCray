package com.opencray.runtime.memory

import com.opencray.persistence.model.MemoryRecord
import com.opencray.persistence.store.MemoryStore
import java.security.MessageDigest
import java.util.Locale

data class TaskCommitmentMaintenanceSummary(
  val resolvedRecords: List<MemoryRecord> = emptyList(),
  val reaffirmedRecords: List<MemoryRecord> = emptyList(),
  val expiredRecordIds: List<String> = emptyList(),
  val droppedProposedCommitmentIndexes: List<Int> = emptyList(),
) {
  val isEmpty: Boolean
    get() = resolvedRecords.isEmpty() &&
      reaffirmedRecords.isEmpty() &&
      expiredRecordIds.isEmpty() &&
      droppedProposedCommitmentIndexes.isEmpty()
}

class TaskCommitmentResolver(
  private val store: MemoryStore,
  private val policy: MemoryPolicy = MemoryPolicy(),
  private val clock: () -> Long = System::currentTimeMillis,
  private val intentInterpreter: TaskCommitmentIntentInterpreter = NoOpTaskCommitmentIntentInterpreter,
) {
  fun maintain(
    evidence: MemoryTurnEvidence,
    proposedCandidates: List<MemoryCandidate> = emptyList(),
  ): TaskCommitmentMaintenanceSummary {
    val now = clock()
    val allRecords = store.list()
    val expiredRecordIds = expireStaleCommitments(records = allRecords, nowEpochMs = now)
    val openCommitments = allRecords.filter { record ->
      isOpenSessionCommitment(record = record, sessionId = evidence.sessionId, nowEpochMs = now)
    }
    val proposedCommitments = proposedCandidates.mapIndexedNotNull { index, candidate ->
      candidate.toProposedTaskCommitmentCandidate(
        candidateIndex = index,
        sessionId = evidence.sessionId,
      )
    }
    if (openCommitments.isEmpty()) {
      return TaskCommitmentMaintenanceSummary(expiredRecordIds = expiredRecordIds)
    }

    val semanticMaintenance = applySemanticMaintenance(
      commitments = openCommitments,
      proposedCommitments = proposedCommitments,
      evidence = evidence,
      nowEpochMs = now,
    )
    semanticMaintenance.resolvedRecords.forEach(store::upsert)
    semanticMaintenance.reaffirmedRecords.forEach(store::upsert)
    return semanticMaintenance.copy(
      expiredRecordIds = expiredRecordIds,
    )
  }

  private fun applySemanticMaintenance(
    commitments: List<MemoryRecord>,
    proposedCommitments: List<ProposedTaskCommitmentCandidate>,
    evidence: MemoryTurnEvidence,
    nowEpochMs: Long,
  ): TaskCommitmentMaintenanceSummary {
    val interpretation = intentInterpreter.interpret(
      TaskCommitmentIntentRequest(
        sessionId = evidence.sessionId,
        commitments = commitments.map { record ->
          OpenTaskCommitment(
            id = record.id,
            content = record.content,
          )
        },
        proposedCommitments = proposedCommitments.map { candidate ->
          ProposedTaskCommitment(
            candidateIndex = candidate.candidateIndex,
            content = candidate.candidate.content,
          )
        },
        userInput = evidence.userInput,
        assistantOutput = evidence.assistantOutput,
        toolObservations = evidence.toolObservations,
      ),
    )
    return when (interpretation) {
      is TaskCommitmentIntentInterpretation.Success -> {
        val commitmentsById = commitments.associateBy(MemoryRecord::id)
        val proposedCommitmentsByIndex = proposedCommitments.associateBy(ProposedTaskCommitmentCandidate::candidateIndex)
        val resolvedRecords = linkedMapOf<String, MemoryRecord>()
        val reaffirmedRecords = linkedMapOf<String, MemoryRecord>()
        val droppedProposedCommitmentIndexes = linkedSetOf<Int>()
        val supersededProposedCommitmentIndexes = linkedSetOf<Int>()

        interpretation.decisions.forEach { decision ->
          when (decision.action) {
            TaskCommitmentIntentAction.RESOLVE -> {
              val commitmentId = decision.commitmentId ?: return@forEach
              val record = commitmentsById[commitmentId] ?: return@forEach
              if (resolvedRecords.size >= MAX_RESOLVED_RECORDS_PER_TURN) {
                return@forEach
              }
              val inserted = resolvedRecords.putIfAbsent(
                commitmentId,
                resolve(
                  record = record,
                  nowEpochMs = nowEpochMs,
                  resolutionReason = RESOLUTION_REASON_COMPLETED,
                ),
              ) == null
              if (inserted) {
                reaffirmedRecords.remove(commitmentId)
              }
            }

            TaskCommitmentIntentAction.REAFFIRM -> {
              val commitmentId = decision.commitmentId ?: return@forEach
              val record = commitmentsById[commitmentId] ?: return@forEach
              if (resolvedRecords.containsKey(commitmentId)) {
                return@forEach
              }
              reaffirmedRecords.putIfAbsent(
                commitmentId,
                reaffirm(record = record, nowEpochMs = nowEpochMs),
              )
            }

            TaskCommitmentIntentAction.ABANDON -> {
              val commitmentId = decision.commitmentId ?: return@forEach
              val record = commitmentsById[commitmentId] ?: return@forEach
              if (resolvedRecords.size >= MAX_RESOLVED_RECORDS_PER_TURN) {
                return@forEach
              }
              val inserted = resolvedRecords.putIfAbsent(
                commitmentId,
                resolve(
                  record = record,
                  nowEpochMs = nowEpochMs,
                  resolutionReason = RESOLUTION_REASON_ABANDONED,
                ),
              ) == null
              if (inserted) {
                reaffirmedRecords.remove(commitmentId)
              }
            }

            TaskCommitmentIntentAction.SUPERSEDE_WITH_PROPOSED -> {
              val commitmentId = decision.commitmentId ?: return@forEach
              val proposedCommitmentIndex = decision.proposedCommitmentIndex ?: return@forEach
              val record = commitmentsById[commitmentId] ?: return@forEach
              val proposedCommitment = proposedCommitmentsByIndex[proposedCommitmentIndex] ?: return@forEach
              if (resolvedRecords.size >= MAX_RESOLVED_RECORDS_PER_TURN) {
                return@forEach
              }
              if (droppedProposedCommitmentIndexes.contains(proposedCommitmentIndex)) {
                return@forEach
              }
              if (supersededProposedCommitmentIndexes.contains(proposedCommitmentIndex)) {
                return@forEach
              }
              if (!canSupersede(record = record, proposedCommitment = proposedCommitment)) {
                return@forEach
              }
              val inserted = resolvedRecords.putIfAbsent(
                commitmentId,
                resolve(
                  record = record,
                  nowEpochMs = nowEpochMs,
                  resolutionReason = RESOLUTION_REASON_SUPERSEDED,
                  supersededBy = stableTaskCommitmentRecordId(proposedCommitment.candidate),
                ),
              ) == null
              if (inserted) {
                reaffirmedRecords.remove(commitmentId)
                supersededProposedCommitmentIndexes += proposedCommitmentIndex
              }
            }

            TaskCommitmentIntentAction.DROP_PROPOSED -> {
              val proposedCommitmentIndex = decision.proposedCommitmentIndex ?: return@forEach
              if (proposedCommitmentsByIndex[proposedCommitmentIndex] == null) {
                return@forEach
              }
              if (droppedProposedCommitmentIndexes.size >= MAX_DROPPED_PROPOSED_COMMITMENTS_PER_TURN) {
                return@forEach
              }
              if (supersededProposedCommitmentIndexes.contains(proposedCommitmentIndex)) {
                return@forEach
              }
              droppedProposedCommitmentIndexes += proposedCommitmentIndex
            }
          }
        }
        TaskCommitmentMaintenanceSummary(
          resolvedRecords = resolvedRecords.values.toList(),
          reaffirmedRecords = reaffirmedRecords
            .filterKeys { commitmentId -> commitmentId !in resolvedRecords }
            .values
            .toList(),
          droppedProposedCommitmentIndexes = droppedProposedCommitmentIndexes.toList(),
        )
      }

      is TaskCommitmentIntentInterpretation.Unavailable -> {
        TaskCommitmentMaintenanceSummary()
      }
    }
  }

  private fun expireStaleCommitments(records: List<MemoryRecord>, nowEpochMs: Long): List<String> {
    val expired = records.filter { record ->
      isTaskCommitment(record) && isExpired(record = record, nowEpochMs = nowEpochMs)
    }
    expired.forEach { record -> store.delete(record.id) }
    return expired.map(MemoryRecord::id)
  }

  private fun isOpenSessionCommitment(
    record: MemoryRecord,
    sessionId: String,
    nowEpochMs: Long,
  ): Boolean = isTaskCommitment(record) &&
    parseStatus(record) == MemoryStatus.OPEN &&
    parseScope(record) == MemoryScope.SESSION &&
    record.extensions[MemoryRecordExtensionKeys.SOURCE_SESSION_ID] == sessionId &&
    !isExpired(record = record, nowEpochMs = nowEpochMs)

  private fun isTaskCommitment(record: MemoryRecord): Boolean =
    parseKind(record) == MemoryKind.TASK_COMMITMENT

  private fun isExpired(record: MemoryRecord, nowEpochMs: Long): Boolean {
    val ttlMs = record.extensions[MemoryRecordExtensionKeys.TTL_MS]?.toLongOrNull()
      ?: policy.ttlMsFor(MemoryKind.TASK_COMMITMENT)
      ?: return false
    val referenceEpochMs = record.extensions[MemoryRecordExtensionKeys.LAST_CONFIRMED_AT_EPOCH_MS]?.toLongOrNull()
      ?: record.updatedAtEpochMs
    return referenceEpochMs + ttlMs < nowEpochMs
  }

  private fun canSupersede(
    record: MemoryRecord,
    proposedCommitment: ProposedTaskCommitmentCandidate,
  ): Boolean = normalizeCommitmentContent(record.content) != normalizeCommitmentContent(proposedCommitment.candidate.content)

  private fun resolve(
    record: MemoryRecord,
    nowEpochMs: Long,
    resolutionReason: String,
    supersededBy: String? = null,
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

  private fun reaffirm(record: MemoryRecord, nowEpochMs: Long): MemoryRecord = record.copy(
    recordVersion = record.recordVersion + 1L,
    updatedAtEpochMs = maxOf(record.createdAtEpochMs, nowEpochMs),
    extensions = record.extensions
      .minus(MemoryRecordExtensionKeys.RESOLVED_AT_EPOCH_MS)
      .minus(MemoryRecordExtensionKeys.RESOLUTION_REASON)
      .minus(MemoryRecordExtensionKeys.SUPERSEDED_BY) + mapOf(
      MemoryRecordExtensionKeys.STATUS to MemoryStatus.OPEN.name.lowercase(Locale.US),
      MemoryRecordExtensionKeys.LAST_CONFIRMED_AT_EPOCH_MS to nowEpochMs.toString(),
    ),
  )

  private fun parseKind(record: MemoryRecord): MemoryKind? =
    parseEnum(record.extensions[MemoryRecordExtensionKeys.KIND]) { token -> MemoryKind.valueOf(token) }

  private fun parseScope(record: MemoryRecord): MemoryScope? =
    parseEnum(record.extensions[MemoryRecordExtensionKeys.SCOPE]) { token -> MemoryScope.valueOf(token) }

  private fun parseStatus(record: MemoryRecord): MemoryStatus? =
    parseEnum(record.extensions[MemoryRecordExtensionKeys.STATUS]) { token -> MemoryStatus.valueOf(token) }

  private fun <T> parseEnum(
    raw: String?,
    parser: (String) -> T,
  ): T? {
    val normalized = raw
      ?.trim()
      ?.replace('-', '_')
      ?.replace(' ', '_')
      ?.uppercase(Locale.US)
      ?.takeIf(String::isNotBlank)
      ?: return null
    return runCatching { parser(normalized) }.getOrNull()
  }

  private fun MemoryCandidate.toProposedTaskCommitmentCandidate(
    candidateIndex: Int,
    sessionId: String,
  ): ProposedTaskCommitmentCandidate? {
    if (kind != MemoryKind.TASK_COMMITMENT || scope != MemoryScope.SESSION) {
      return null
    }
    if (sourceSessionId != sessionId) {
      return null
    }
    return ProposedTaskCommitmentCandidate(
      candidateIndex = candidateIndex,
      candidate = this,
    )
  }

  private fun normalizeCommitmentContent(content: String): String =
    content.trim().lowercase(Locale.US)

  private fun stableTaskCommitmentRecordId(candidate: MemoryCandidate): String {
    val digestSource = buildString {
      append("task_commitment|session:")
      append(candidate.sourceSessionId)
      append("|")
      append(candidate.content.lowercase(Locale.US))
    }
    val digest = MessageDigest.getInstance("SHA-256").digest(digestSource.toByteArray(Charsets.UTF_8))
    return "mem-${digest.joinToString(separator = "") { byte -> "%02x".format(byte) }.take(24)}"
  }

  private data class ProposedTaskCommitmentCandidate(
    val candidateIndex: Int,
    val candidate: MemoryCandidate,
  )

  private companion object {
    const val RESOLUTION_REASON_COMPLETED: String = "completed"
    const val RESOLUTION_REASON_ABANDONED: String = "abandoned"
    const val RESOLUTION_REASON_SUPERSEDED: String = "superseded"
    const val MAX_RESOLVED_RECORDS_PER_TURN: Int = 4
    const val MAX_DROPPED_PROPOSED_COMMITMENTS_PER_TURN: Int = 4
  }
}
