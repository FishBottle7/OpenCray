package com.opencray.runtime.memory

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal data class VisibleMemoryRecord(
  val id: String,
  val kind: MemoryKind,
  val scope: MemoryScope,
  val status: MemoryStatus,
  val source: MemoryEvidenceSource?,
  val sourceSessionId: String?,
  val workspaceId: String?,
  val content: String,
  val updatedAtEpochMs: Long,
  val lastConfirmedAtEpochMs: Long,
  val preferenceKey: String? = null,
  val preferenceValue: String? = null,
)

internal fun memoryScopeMatches(
  scope: MemoryScope,
  sourceSessionId: String?,
  recordWorkspaceId: String?,
  requestSessionId: String,
  requestWorkspaceId: String?,
): Boolean = when (scope) {
  MemoryScope.USER -> true
  MemoryScope.SESSION -> sourceSessionId == requestSessionId
  MemoryScope.WORKSPACE -> {
    val normalizedRecordWorkspaceId = recordWorkspaceId?.takeIf(String::isNotBlank)
    val normalizedRequestWorkspaceId = requestWorkspaceId?.takeIf(String::isNotBlank)
    when {
      normalizedRecordWorkspaceId == null && normalizedRequestWorkspaceId == null -> true
      normalizedRecordWorkspaceId != null && normalizedRequestWorkspaceId != null ->
        normalizedRecordWorkspaceId == normalizedRequestWorkspaceId

      else -> false
    }
  }
}

internal fun memoryRecordExpired(
  ttlMs: Long?,
  lastConfirmedAtEpochMs: Long?,
  updatedAtEpochMs: Long,
  nowEpochMs: Long,
): Boolean {
  val resolvedTtlMs = ttlMs ?: return false
  val referenceEpochMs = lastConfirmedAtEpochMs ?: updatedAtEpochMs
  return referenceEpochMs + resolvedTtlMs < nowEpochMs
}

internal fun extractMemoryQueryTerms(
  policy: MemoryPolicy,
  text: String,
): Set<String> {
  val normalized = policy.normalizeCandidateContent(text).orEmpty().lowercase(Locale.US)
  if (normalized.isBlank()) {
    return emptySet()
  }
  return MEMORY_QUERY_TERM_REGEX.findAll(normalized)
    .map { match -> match.value.trim() }
    .filter { token ->
      token.length >= 2 &&
        token !in MEMORY_ENGLISH_STOP_TERMS &&
        token !in MEMORY_CHINESE_STOP_TERMS
    }
    .toCollection(linkedSetOf())
}

internal fun formatMemoryDateStamp(epochMs: Long): String =
  checkNotNull(MEMORY_DATE_STAMP_FORMAT.get()).format(Date(epochMs))

internal fun formatMemoryIsoInstant(epochMs: Long): String =
  checkNotNull(MEMORY_ISO_INSTANT_FORMAT.get()).format(Date(epochMs))

private val MEMORY_DATE_STAMP_FORMAT: ThreadLocal<SimpleDateFormat> = ThreadLocal.withInitial {
  SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("UTC")
  }
}

private val MEMORY_ISO_INSTANT_FORMAT: ThreadLocal<SimpleDateFormat> = ThreadLocal.withInitial {
  SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("UTC")
  }
}

private val MEMORY_QUERY_TERM_REGEX: Regex = Regex("[\\p{L}\\p{N}_./:-]{2,}")

private val MEMORY_ENGLISH_STOP_TERMS: Set<String> = setOf(
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

private val MEMORY_CHINESE_STOP_TERMS: Set<String> = setOf(
  "这个",
  "那个",
  "一下",
  "现在",
  "继续",
  "当前",
)
