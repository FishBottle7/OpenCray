package com.opencray.runtime.memory

import com.opencray.persistence.model.MemoryRecord
import com.opencray.persistence.store.MemoryStore
import java.util.Locale

data class TaskCommitmentMaintenanceSummary(
  val resolvedRecords: List<MemoryRecord> = emptyList(),
  val reaffirmedRecords: List<MemoryRecord> = emptyList(),
  val expiredRecordIds: List<String> = emptyList(),
) {
  val isEmpty: Boolean
    get() = resolvedRecords.isEmpty() && reaffirmedRecords.isEmpty() && expiredRecordIds.isEmpty()
}

class TaskCommitmentResolver(
  private val store: MemoryStore,
  private val policy: MemoryPolicy = MemoryPolicy(),
  private val clock: () -> Long = System::currentTimeMillis,
  private val intentInterpreter: TaskCommitmentIntentInterpreter = NoOpTaskCommitmentIntentInterpreter,
) {
  fun maintain(evidence: MemoryTurnEvidence): TaskCommitmentMaintenanceSummary {
    val now = clock()
    val allRecords = store.list()
    val expiredRecordIds = expireStaleCommitments(records = allRecords, nowEpochMs = now)
    val openCommitments = allRecords.filter { record ->
      isOpenSessionCommitment(record = record, sessionId = evidence.sessionId, nowEpochMs = now)
    }
    if (openCommitments.isEmpty()) {
      return TaskCommitmentMaintenanceSummary(expiredRecordIds = expiredRecordIds)
    }
    val maintenanceEvidence = maintenanceEvidenceTexts(evidence)
    if (maintenanceEvidence.isEmpty()) {
      return TaskCommitmentMaintenanceSummary(expiredRecordIds = expiredRecordIds)
    }

    val semanticMaintenance = applySemanticMaintenance(
      commitments = openCommitments,
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
        assistantOutput = evidence.assistantOutput,
        toolObservations = evidence.toolObservations,
      ),
    )
    return when (interpretation) {
      is TaskCommitmentIntentInterpretation.Success -> {
        val decisionsByCommitmentId = linkedMapOf<String, TaskCommitmentIntentDecision>()
        interpretation.decisions.forEach { decision ->
          if (decision.commitmentId.isBlank()) {
            return@forEach
          }
          decisionsByCommitmentId.putIfAbsent(decision.commitmentId, decision)
        }
        val resolvedRecords = commitments
          .mapNotNull { record ->
            when (decisionsByCommitmentId[record.id]?.action) {
              TaskCommitmentIntentAction.RESOLVE -> resolve(record = record, nowEpochMs = nowEpochMs)
              else -> null
            }
          }
        val reaffirmedRecords = commitments
          .mapNotNull { record ->
            when (decisionsByCommitmentId[record.id]?.action) {
              TaskCommitmentIntentAction.REAFFIRM -> reaffirm(record = record, nowEpochMs = nowEpochMs)
              else -> null
            }
          }
        TaskCommitmentMaintenanceSummary(
          resolvedRecords = resolvedRecords,
          reaffirmedRecords = reaffirmedRecords,
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

  private fun resolve(record: MemoryRecord, nowEpochMs: Long): MemoryRecord = record.copy(
    tags = record.tags
      .filterNot { tag -> tag.startsWith("status:") }
      .plus("status:${MemoryStatus.RESOLVED.name.lowercase(Locale.US)}")
      .distinct()
      .sorted(),
    recordVersion = record.recordVersion + 1L,
    updatedAtEpochMs = maxOf(record.createdAtEpochMs, nowEpochMs),
    extensions = record.extensions + mapOf(
      MemoryRecordExtensionKeys.STATUS to MemoryStatus.RESOLVED.name.lowercase(Locale.US),
      MemoryRecordExtensionKeys.RESOLVED_AT_EPOCH_MS to nowEpochMs.toString(),
      MemoryRecordExtensionKeys.RESOLUTION_REASON to RESOLUTION_REASON_COMPLETED,
      MemoryRecordExtensionKeys.LAST_CONFIRMED_AT_EPOCH_MS to nowEpochMs.toString(),
    ),
  )

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

  private fun maintenanceEvidenceTexts(evidence: MemoryTurnEvidence): List<String> =
    listOfNotNull(evidence.assistantOutput) + evidence.toolObservations
      .mapNotNull(policy::normalizeCandidateContent)

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

  private companion object {
    const val RESOLUTION_REASON_COMPLETED: String = "completed"
  }
}
