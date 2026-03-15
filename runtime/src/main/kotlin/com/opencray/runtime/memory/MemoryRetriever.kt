package com.opencray.runtime.memory

import com.opencray.persistence.model.MemoryRecord
import java.util.Locale

data class MemoryRecallRequest(
  val sessionId: String,
  val userInput: String,
  val workspaceId: String? = null,
) {
  init {
    require(sessionId.isNotBlank()) { "MemoryRecallRequest sessionId must not be blank." }
    require(userInput.isNotBlank()) { "MemoryRecallRequest userInput must not be blank." }
  }
}

data class RetrievedMemory(
  val id: String,
  val kind: MemoryKind,
  val scope: MemoryScope,
  val status: MemoryStatus,
  val content: String,
  val source: MemoryEvidenceSource? = null,
  val sourceSessionId: String? = null,
  val workspaceId: String? = null,
  val lastConfirmedAtEpochMs: Long,
  val matchedTerms: List<String> = emptyList(),
  val score: Int,
)

enum class MemoryRecallOmissionReason {
  MAX_RECORDS,
  MAX_CHARS,
  MAX_PER_KIND,
}

enum class MemoryRecallFilterReason {
  INVALID,
  RESOLVED,
  SCOPE_MISMATCH,
  EXPIRED,
  SOUL_PREFERENCE,
  UNMATCHED_PROJECT_FACT,
}

data class MemoryRecallSelectedTrace(
  val id: String,
  val kind: MemoryKind,
  val scope: MemoryScope,
  val score: Int,
  val matchedTerms: List<String> = emptyList(),
  val contentPreview: String,
)

data class MemoryRecallOmittedTrace(
  val id: String,
  val kind: MemoryKind,
  val scope: MemoryScope,
  val score: Int,
  val matchedTerms: List<String> = emptyList(),
  val omissionReason: MemoryRecallOmissionReason,
  val contentPreview: String,
)

data class MemoryRecallTrace(
  val queryTerms: List<String> = emptyList(),
  val selected: List<MemoryRecallSelectedTrace> = emptyList(),
  val omitted: List<MemoryRecallOmittedTrace> = emptyList(),
  val filteredCounts: Map<MemoryRecallFilterReason, Int> = emptyMap(),
)

data class MemoryRecallResult(
  val memories: List<RetrievedMemory> = emptyList(),
  val matchedRecordCount: Int = 0,
  val omittedRecordCount: Int = 0,
  val trace: MemoryRecallTrace = MemoryRecallTrace(),
)

class MemoryRetriever(
  private val policy: MemoryPolicy = MemoryPolicy(),
  private val clock: () -> Long = System::currentTimeMillis,
) {
  fun retrieve(
    records: List<MemoryRecord>,
    request: MemoryRecallRequest,
  ): MemoryRecallResult {
    val queryTerms = extractQueryTerms(request.userInput)
    if (records.isEmpty()) {
      return MemoryRecallResult(
        trace = MemoryRecallTrace(queryTerms = queryTerms.toList()),
      )
    }

    val filteredCounts = linkedMapOf<MemoryRecallFilterReason, Int>()
    val matched = records
      .mapNotNull { record ->
        when (
          val evaluation = evaluateRecord(
            record = record,
            request = request,
            queryTerms = queryTerms,
          )
        ) {
          is RecallEvaluation.Matched -> evaluation.memory
          is RecallEvaluation.Filtered -> {
            filteredCounts[evaluation.reason] = (filteredCounts[evaluation.reason] ?: 0) + 1
            null
          }
        }
      }
      .sortedWith(
        compareByDescending<RetrievedMemory> { it.score }
          .thenByDescending { it.lastConfirmedAtEpochMs }
          .thenBy { it.id },
      )
    if (matched.isEmpty()) {
      return MemoryRecallResult(
        trace = MemoryRecallTrace(
          queryTerms = queryTerms.toList(),
          filteredCounts = filteredCounts.toMap(),
        ),
      )
    }

    val selected = mutableListOf<RetrievedMemory>()
    val omitted = mutableListOf<MemoryRecallOmittedTrace>()
    val selectedByKind = linkedMapOf<MemoryKind, Int>()
    var consumedChars = 0
    matched.forEach { memory ->
      val omissionReason = when {
        selected.size >= policy.recallBudget.maxRecords -> MemoryRecallOmissionReason.MAX_RECORDS
        (selectedByKind[memory.kind] ?: 0) >= policy.recallBudget.maxRecordsPerKind -> MemoryRecallOmissionReason.MAX_PER_KIND
        consumedChars + estimatedPromptChars(memory) > policy.recallBudget.maxChars -> MemoryRecallOmissionReason.MAX_CHARS
        else -> null
      }
      if (omissionReason != null) {
        omitted += memory.toOmittedTrace(omissionReason)
        return@forEach
      }
      selected += memory
      selectedByKind[memory.kind] = (selectedByKind[memory.kind] ?: 0) + 1
      consumedChars += estimatedPromptChars(memory)
    }

    return MemoryRecallResult(
      memories = selected,
      matchedRecordCount = matched.size,
      omittedRecordCount = (matched.size - selected.size).coerceAtLeast(0),
      trace = MemoryRecallTrace(
        queryTerms = queryTerms.toList(),
        selected = selected.map { memory -> memory.toSelectedTrace() },
        omitted = omitted.take(MAX_OMITTED_TRACE_ENTRIES),
        filteredCounts = filteredCounts.toMap(),
      ),
    )
  }

  private fun evaluateRecord(
    record: MemoryRecord,
    request: MemoryRecallRequest,
    queryTerms: Set<String>,
  ): RecallEvaluation {
    val metadata = record.parseMemoryMetadata()
      ?: return RecallEvaluation.Filtered(MemoryRecallFilterReason.INVALID)
    val normalizedContent = policy.normalizeCandidateContent(record.content)
      ?: return RecallEvaluation.Filtered(MemoryRecallFilterReason.INVALID)
    if (metadata.status == MemoryStatus.RESOLVED) {
      return RecallEvaluation.Filtered(MemoryRecallFilterReason.RESOLVED)
    }
    if (!scopeMatches(metadata = metadata, request = request)) {
      return RecallEvaluation.Filtered(MemoryRecallFilterReason.SCOPE_MISMATCH)
    }
    if (isExpired(metadata = metadata, updatedAtEpochMs = record.updatedAtEpochMs)) {
      return RecallEvaluation.Filtered(MemoryRecallFilterReason.EXPIRED)
    }
    if (metadata.preferenceKey != null) {
      return RecallEvaluation.Filtered(MemoryRecallFilterReason.SOUL_PREFERENCE)
    }

    val normalizedContentLowered = normalizedContent.lowercase(Locale.US)
    val matchedTerms = queryTerms.filter { term ->
      normalizedContentLowered.contains(term.lowercase(Locale.US))
    }
    if (metadata.kind == MemoryKind.PROJECT_FACT && matchedTerms.isEmpty()) {
      return RecallEvaluation.Filtered(MemoryRecallFilterReason.UNMATCHED_PROJECT_FACT)
    }

    return RecallEvaluation.Matched(
      RetrievedMemory(
        id = record.id,
        kind = metadata.kind,
        scope = metadata.scope,
        status = metadata.status,
        content = normalizedContent,
        source = metadata.source,
        sourceSessionId = metadata.sourceSessionId,
        workspaceId = metadata.workspaceId,
        lastConfirmedAtEpochMs = metadata.lastConfirmedAtEpochMs ?: record.updatedAtEpochMs,
        matchedTerms = matchedTerms.sorted(),
        score = score(
          metadata = metadata,
          updatedAtEpochMs = record.updatedAtEpochMs,
          matchedTerms = matchedTerms,
          normalizedContent = normalizedContent,
        ),
      ),
    )
  }

  private fun RetrievedMemory.toSelectedTrace(): MemoryRecallSelectedTrace = MemoryRecallSelectedTrace(
    id = id,
    kind = kind,
    scope = scope,
    score = score,
    matchedTerms = matchedTerms,
    contentPreview = content.take(MAX_TRACE_CONTENT_CHARS),
  )

  private fun RetrievedMemory.toOmittedTrace(
    omissionReason: MemoryRecallOmissionReason,
  ): MemoryRecallOmittedTrace = MemoryRecallOmittedTrace(
    id = id,
    kind = kind,
    scope = scope,
    score = score,
    matchedTerms = matchedTerms,
    omissionReason = omissionReason,
    contentPreview = content.take(MAX_TRACE_CONTENT_CHARS),
  )

  private sealed interface RecallEvaluation {
    data class Matched(val memory: RetrievedMemory) : RecallEvaluation

    data class Filtered(val reason: MemoryRecallFilterReason) : RecallEvaluation
  }

  private fun score(
    metadata: ParsedMemoryMetadata,
    updatedAtEpochMs: Long,
    matchedTerms: List<String>,
    normalizedContent: String,
  ): Int {
    var score = when (metadata.kind) {
      MemoryKind.DURABLE_INSTRUCTION -> 400
      MemoryKind.USER_PREFERENCE -> 340
      MemoryKind.PROJECT_FACT -> 260
      MemoryKind.TASK_COMMITMENT -> 180
    }
    score += when (metadata.scope) {
      MemoryScope.SESSION -> 70
      MemoryScope.WORKSPACE -> 45
      MemoryScope.USER -> 25
    }
    score += matchedTerms.size * 40
    if (metadata.kind == MemoryKind.TASK_COMMITMENT && metadata.status == MemoryStatus.OPEN) {
      score += 25
    }
    score += when (ageDays(updatedAtEpochMs)) {
      in 0..1 -> 30
      in 2..7 -> 20
      in 8..30 -> 10
      else -> 0
    }
    if (normalizedContent.length <= 96) {
      score += 5
    }
    return score
  }

  private fun scopeMatches(
    metadata: ParsedMemoryMetadata,
    request: MemoryRecallRequest,
  ): Boolean = when (metadata.scope) {
    MemoryScope.USER -> true
    MemoryScope.SESSION -> metadata.sourceSessionId == request.sessionId
    MemoryScope.WORKSPACE -> {
      val recordWorkspaceId = metadata.workspaceId?.takeIf(String::isNotBlank)
      val requestWorkspaceId = request.workspaceId?.takeIf(String::isNotBlank)
      when {
        recordWorkspaceId == null && requestWorkspaceId == null -> true
        recordWorkspaceId != null && requestWorkspaceId != null -> recordWorkspaceId == requestWorkspaceId
        else -> false
      }
    }
  }

  private fun isExpired(
    metadata: ParsedMemoryMetadata,
    updatedAtEpochMs: Long,
  ): Boolean {
    val ttlMs = metadata.ttlMs ?: return false
    val referenceEpochMs = metadata.lastConfirmedAtEpochMs ?: updatedAtEpochMs
    return referenceEpochMs + ttlMs < clock()
  }

  private fun ageDays(updatedAtEpochMs: Long): Long =
    ((clock() - updatedAtEpochMs).coerceAtLeast(0L)) / DAY_MS

  private fun estimatedPromptChars(memory: RetrievedMemory): Int =
    memory.content.length + 40

  private fun extractQueryTerms(text: String): Set<String> {
    val normalized = policy.normalizeCandidateContent(text).orEmpty().lowercase(Locale.US)
    if (normalized.isBlank()) {
      return emptySet()
    }
    return QUERY_TERM_REGEX.findAll(normalized)
      .map { match -> match.value.trim() }
      .filter { token ->
        token.length >= 2 &&
          token !in ENGLISH_STOP_TERMS &&
          token !in CHINESE_STOP_TERMS
      }
      .toCollection(linkedSetOf())
  }

  private companion object {
    const val DAY_MS: Long = 24L * 60L * 60L * 1000L
    const val MAX_TRACE_CONTENT_CHARS: Int = 96
    const val MAX_OMITTED_TRACE_ENTRIES: Int = 8
    val QUERY_TERM_REGEX: Regex = Regex("[\\p{L}\\p{N}_./:-]{2,}")
    val ENGLISH_STOP_TERMS: Set<String> = setOf(
      "do",
      "not",
      "the",
      "this",
      "that",
      "with",
      "from",
      "into",
      "your",
      "please",
      "about",
      "what",
      "when",
      "where",
      "which",
    )
    val CHINESE_STOP_TERMS: Set<String> = setOf(
      "这个",
      "那个",
      "一下",
      "现在",
      "继续",
      "当前",
    )
  }
}
