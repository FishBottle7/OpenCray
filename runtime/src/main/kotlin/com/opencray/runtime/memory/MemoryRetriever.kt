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

data class MemoryRecallResult(
  val memories: List<RetrievedMemory> = emptyList(),
  val matchedRecordCount: Int = 0,
  val omittedRecordCount: Int = 0,
)

class MemoryRetriever(
  private val policy: MemoryPolicy = MemoryPolicy(),
  private val clock: () -> Long = System::currentTimeMillis,
) {
  fun retrieve(
    records: List<MemoryRecord>,
    request: MemoryRecallRequest,
  ): MemoryRecallResult {
    if (records.isEmpty()) {
      return MemoryRecallResult()
    }

    val queryTerms = extractQueryTerms(request.userInput)
    val matched = records
      .mapNotNull { record ->
        toRetrievedMemory(
          record = record,
          request = request,
          queryTerms = queryTerms,
        )
      }
      .sortedWith(
        compareByDescending<RetrievedMemory> { it.score }
          .thenByDescending { it.lastConfirmedAtEpochMs }
          .thenBy { it.id },
      )
    if (matched.isEmpty()) {
      return MemoryRecallResult()
    }

    val selected = mutableListOf<RetrievedMemory>()
    val selectedByKind = linkedMapOf<MemoryKind, Int>()
    var consumedChars = 0
    matched.forEach { memory ->
      if (selected.size >= policy.recallBudget.maxRecords) {
        return@forEach
      }
      if ((selectedByKind[memory.kind] ?: 0) >= policy.recallBudget.maxRecordsPerKind) {
        return@forEach
      }
      val projectedChars = consumedChars + estimatedPromptChars(memory)
      if (projectedChars > policy.recallBudget.maxChars) {
        return@forEach
      }
      selected += memory
      selectedByKind[memory.kind] = (selectedByKind[memory.kind] ?: 0) + 1
      consumedChars = projectedChars
    }

    return MemoryRecallResult(
      memories = selected,
      matchedRecordCount = matched.size,
      omittedRecordCount = (matched.size - selected.size).coerceAtLeast(0),
    )
  }

  private fun toRetrievedMemory(
    record: MemoryRecord,
    request: MemoryRecallRequest,
    queryTerms: Set<String>,
  ): RetrievedMemory? {
    val metadata = record.metadata() ?: return null
    val normalizedContent = policy.normalizeCandidateContent(record.content) ?: return null
    if (metadata.status == MemoryStatus.RESOLVED) {
      return null
    }
    if (!scopeMatches(metadata = metadata, request = request)) {
      return null
    }
    if (isExpired(metadata = metadata, updatedAtEpochMs = record.updatedAtEpochMs)) {
      return null
    }

    val normalizedContentLowered = normalizedContent.lowercase(Locale.US)
    val matchedTerms = queryTerms.filter { term ->
      normalizedContentLowered.contains(term.lowercase(Locale.US))
    }
    if (metadata.kind == MemoryKind.PROJECT_FACT && matchedTerms.isEmpty()) {
      return null
    }

    return RetrievedMemory(
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
    )
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

  private fun MemoryRecord.metadata(): ParsedMemoryMetadata? {
    val kind = parseEnumValue(
      extensionValue = extensions[MemoryRecordExtensionKeys.KIND],
      tags = tags,
      tagPrefix = "kind:",
      parser = ::parseMemoryKind,
    ) ?: return null
    val scope = parseEnumValue(
      extensionValue = extensions[MemoryRecordExtensionKeys.SCOPE],
      tags = tags,
      tagPrefix = "scope:",
      parser = ::parseMemoryScope,
    ) ?: return null
    val status = parseEnumValue(
      extensionValue = extensions[MemoryRecordExtensionKeys.STATUS],
      tags = tags,
      tagPrefix = "status:",
      parser = ::parseMemoryStatus,
    ) ?: return null
    return ParsedMemoryMetadata(
      kind = kind,
      scope = scope,
      status = status,
      source = parseMemoryEvidenceSource(extensions[MemoryRecordExtensionKeys.SOURCE]),
      sourceSessionId = extensions[MemoryRecordExtensionKeys.SOURCE_SESSION_ID]?.takeIf(String::isNotBlank),
      workspaceId = extensions[MemoryRecordExtensionKeys.WORKSPACE_ID]?.takeIf(String::isNotBlank),
      ttlMs = extensions[MemoryRecordExtensionKeys.TTL_MS]?.toLongOrNull(),
      lastConfirmedAtEpochMs = extensions[MemoryRecordExtensionKeys.LAST_CONFIRMED_AT_EPOCH_MS]?.toLongOrNull(),
    )
  }

  private fun <T> parseEnumValue(
    extensionValue: String?,
    tags: List<String>,
    tagPrefix: String,
    parser: (String) -> T?,
  ): T? {
    parser(extensionValue.orEmpty())?.let { return it }
    return tags.firstOrNull { tag ->
      tag.startsWith(tagPrefix)
    }?.substringAfter(tagPrefix)?.let(parser)
  }

  private fun parseMemoryKind(raw: String): MemoryKind? =
    parseEnum(raw) { token -> MemoryKind.valueOf(token) }

  private fun parseMemoryScope(raw: String): MemoryScope? =
    parseEnum(raw) { token -> MemoryScope.valueOf(token) }

  private fun parseMemoryStatus(raw: String): MemoryStatus? =
    parseEnum(raw) { token -> MemoryStatus.valueOf(token) }

  private fun parseMemoryEvidenceSource(raw: String?): MemoryEvidenceSource? =
    parseEnum(raw.orEmpty()) { token -> MemoryEvidenceSource.valueOf(token) }

  private fun <T> parseEnum(
    raw: String,
    parser: (String) -> T,
  ): T? {
    val normalized = raw
      .trim()
      .replace('-', '_')
      .replace(' ', '_')
      .uppercase(Locale.US)
      .takeIf(String::isNotBlank)
      ?: return null
    return runCatching { parser(normalized) }.getOrNull()
  }

  private data class ParsedMemoryMetadata(
    val kind: MemoryKind,
    val scope: MemoryScope,
    val status: MemoryStatus,
    val source: MemoryEvidenceSource?,
    val sourceSessionId: String?,
    val workspaceId: String?,
    val ttlMs: Long?,
    val lastConfirmedAtEpochMs: Long?,
  )

  private companion object {
    const val DAY_MS: Long = 24L * 60L * 60L * 1000L
    val QUERY_TERM_REGEX: Regex = Regex("[\\p{L}\\p{N}_./:-]{2,}")
    val ENGLISH_STOP_TERMS: Set<String> = setOf(
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
