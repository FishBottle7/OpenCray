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
    if (semanticMaintenance != null) {
      semanticMaintenance.resolvedRecords.forEach(store::upsert)
      semanticMaintenance.reaffirmedRecords.forEach(store::upsert)
      return semanticMaintenance.copy(
        expiredRecordIds = expiredRecordIds,
      )
    }

    val completionEvidence = maintenanceEvidence.filter(::containsCompletionSignal)
    if (completionEvidence.isEmpty()) {
      return TaskCommitmentMaintenanceSummary(expiredRecordIds = expiredRecordIds)
    }

    val resolvedRecords = openCommitments
      .filter { record -> completionEvidence.any { text -> matchesCompletion(record.content, text) } }
      .map { record -> resolve(record = record, nowEpochMs = now) }

    resolvedRecords.forEach(store::upsert)
    return TaskCommitmentMaintenanceSummary(
      resolvedRecords = resolvedRecords,
      expiredRecordIds = expiredRecordIds,
    )
  }

  private fun applySemanticMaintenance(
    commitments: List<MemoryRecord>,
    evidence: MemoryTurnEvidence,
    nowEpochMs: Long,
  ): TaskCommitmentMaintenanceSummary? {
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
        if (interpretation.allowHeuristicFallback) {
          null
        } else {
          TaskCommitmentMaintenanceSummary()
        }
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

  private fun matchesCompletion(
    commitmentContent: String,
    evidenceText: String,
  ): Boolean {
    val normalizedCommitment = policy.normalizeCandidateContent(commitmentContent) ?: return false
    val normalizedEvidence = policy.normalizeCandidateContent(evidenceText) ?: return false
    if (!containsCompletionSignal(normalizedEvidence)) {
      return false
    }
    val commitmentLower = normalizedCommitment.lowercase(Locale.US)
    val evidenceLower = normalizedEvidence.lowercase(Locale.US)
    if (evidenceLower.contains(commitmentLower)) {
      return true
    }
    val commitmentTerms = extractTerms(commitmentLower)
    val evidenceTerms = extractTerms(evidenceLower)
    if (commitmentTerms.isEmpty() || evidenceTerms.isEmpty()) {
      return false
    }
    val overlapCount = commitmentTerms.intersect(evidenceTerms).size
    return if (commitmentTerms.size <= 2) {
      overlapCount >= commitmentTerms.size
    } else {
      overlapCount >= 2
    }
  }

  private fun containsCompletionSignal(text: String): Boolean {
    val lowered = text.lowercase(Locale.US)
    return COMPLETION_MARKERS.any { marker -> lowered.contains(marker) } ||
      CHINESE_COMPLETION_MARKERS.any { marker -> text.contains(marker) }
  }

  private fun extractTerms(text: String): Set<String> = TERM_REGEX.findAll(text)
    .map { match -> match.value.trim() }
    .filter { token -> token.length >= 2 && token !in STOP_TERMS }
    .toCollection(linkedSetOf())

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
    val TERM_REGEX: Regex = Regex("[\\p{L}\\p{N}_./:-]{2,}")
    val STOP_TERMS: Set<String> = setOf(
      "the",
      "this",
      "that",
      "with",
      "from",
      "into",
      "next",
      "will",
      "then",
      "done",
      "have",
      "has",
      "been",
      "and",
      "for",
      "after",
      "before",
      "already",
      "已",
      "已经",
      "然后",
      "接着",
      "现在",
      "之后",
    )
    val COMPLETION_MARKERS: List<String> = listOf(
      "completed ",
      "finished ",
      "done ",
      "resolved ",
      "fixed ",
      "updated ",
      "verified ",
      "checked ",
      "created ",
      "wrote ",
      "executed ",
      "ran ",
      "passed ",
      "applied ",
      "removed ",
      "saved ",
    )
    val CHINESE_COMPLETION_MARKERS: List<String> = listOf(
      "已完成",
      "完成了",
      "已经完成",
      "修复了",
      "已修复",
      "更新了",
      "已更新",
      "验证了",
      "已验证",
      "运行了",
      "执行了",
      "通过了",
      "已通过",
      "写入了",
      "创建了",
      "保存了",
      "处理完",
    )
  }
}
