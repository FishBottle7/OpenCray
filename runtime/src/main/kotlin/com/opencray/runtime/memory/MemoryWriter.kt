package com.opencray.runtime.memory

import com.opencray.persistence.model.MemoryRecord
import com.opencray.persistence.store.MemoryStore
import java.security.MessageDigest
import java.util.Locale

data class MemoryWriteSummary(
  val writtenRecords: List<MemoryRecord>,
)

class MemoryWriter(
  private val store: MemoryStore,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  fun write(candidates: List<MemoryCandidate>): MemoryWriteSummary {
    if (candidates.isEmpty()) {
      return MemoryWriteSummary(writtenRecords = emptyList())
    }

    val written = mutableListOf<MemoryRecord>()
    candidates.forEach { candidate ->
      val recordId = stableMemoryRecordId(candidate)
      val now = clock()
      val existing = store.list().associateBy(MemoryRecord::id)[recordId]
      val record = MemoryRecord(
        id = recordId,
        content = candidate.content,
        tags = mergeTags(existing = existing, candidate = candidate),
        recordVersion = (existing?.recordVersion ?: 0L) + 1L,
        createdAtEpochMs = existing?.createdAtEpochMs ?: now,
        updatedAtEpochMs = now,
        termuxMetadata = existing?.termuxMetadata.orEmpty(),
        extensions = mergeExtensions(
          existing = existing,
          candidate = candidate,
          nowEpochMs = now,
        ),
      )
      store.upsert(record)
      written += record
      resolveSupersededPreferenceRecords(
        existingRecords = store.list(),
        candidate = candidate,
        incomingRecordId = recordId,
        nowEpochMs = now,
      ).forEach { supersededRecord ->
        store.upsert(supersededRecord)
      }
    }
    return MemoryWriteSummary(writtenRecords = written)
  }

  private fun mergeTags(
    existing: MemoryRecord?,
    candidate: MemoryCandidate,
  ): List<String> = (existing?.tags.orEmpty() + listOf(
    "kind:${candidate.kind.name.lowercase(Locale.US)}",
    "scope:${candidate.scope.name.lowercase(Locale.US)}",
    "source:${candidate.source.name.lowercase(Locale.US)}",
  )).distinct().sorted()

  private fun mergeExtensions(
    existing: MemoryRecord?,
    candidate: MemoryCandidate,
    nowEpochMs: Long,
  ): Map<String, String> = buildMap {
    putAll(existing?.extensions.orEmpty())
    put(MemoryRecordExtensionKeys.KIND, candidate.kind.name.lowercase(Locale.US))
    put(MemoryRecordExtensionKeys.SCOPE, candidate.scope.name.lowercase(Locale.US))
    put(MemoryRecordExtensionKeys.STATUS, candidate.status.name.lowercase(Locale.US))
    put(MemoryRecordExtensionKeys.SOURCE, candidate.source.name.lowercase(Locale.US))
    put(MemoryRecordExtensionKeys.SOURCE_SESSION_ID, candidate.sourceSessionId)
    if (!candidate.sourceTaskId.isNullOrBlank()) {
      put(MemoryRecordExtensionKeys.SOURCE_TASK_ID, candidate.sourceTaskId)
    } else {
      remove(MemoryRecordExtensionKeys.SOURCE_TASK_ID)
    }
    if (!candidate.workspaceId.isNullOrBlank()) {
      put(MemoryRecordExtensionKeys.WORKSPACE_ID, candidate.workspaceId)
    } else {
      remove(MemoryRecordExtensionKeys.WORKSPACE_ID)
    }
    if (candidate.ttlMs != null) {
      put(MemoryRecordExtensionKeys.TTL_MS, candidate.ttlMs.toString())
    } else {
      remove(MemoryRecordExtensionKeys.TTL_MS)
    }
    put(
      MemoryRecordExtensionKeys.FIRST_CONFIRMED_AT_EPOCH_MS,
      existing?.extensions?.get(MemoryRecordExtensionKeys.FIRST_CONFIRMED_AT_EPOCH_MS) ?: nowEpochMs.toString(),
    )
    put(MemoryRecordExtensionKeys.LAST_CONFIRMED_AT_EPOCH_MS, nowEpochMs.toString())
    candidate.extensions.forEach { (key, value) ->
      if (key.isBlank() || value.isBlank()) {
        return@forEach
      }
      put(key, value)
    }
  }

  private fun resolveSupersededPreferenceRecords(
    existingRecords: List<MemoryRecord>,
    candidate: MemoryCandidate,
    incomingRecordId: String,
    nowEpochMs: Long,
  ): List<MemoryRecord> {
    val preferenceKey = normalizeMemoryPreferenceKeyOrNull(
      candidate.extensions[MemoryRecordExtensionKeys.PREFERENCE_KEY],
    ) ?: return emptyList()
    if (!preferenceSupportsSupersession(preferenceKey)) {
      return emptyList()
    }
    val preferenceValue = normalizeMemoryPreferenceValueOrNull(
      candidate.extensions[MemoryRecordExtensionKeys.PREFERENCE_VALUE],
    ) ?: return emptyList()
    return existingRecords
      .filter { record -> record.id != incomingRecordId }
      .mapNotNull { record ->
        val metadata = parseRecordMetadata(record) ?: return@mapNotNull null
        if (metadata.status != MemoryStatus.ACTIVE) {
          return@mapNotNull null
        }
        if (metadata.preferenceKey != preferenceKey) {
          return@mapNotNull null
        }
        if (!scopeIdentityMatches(metadata = metadata, candidate = candidate)) {
          return@mapNotNull null
        }
        if (metadata.preferenceValue == preferenceValue) {
          return@mapNotNull null
        }
        record.copy(
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
            MemoryRecordExtensionKeys.RESOLUTION_REASON to RESOLUTION_REASON_SUPERSEDED,
            MemoryRecordExtensionKeys.SUPERSEDED_BY to incomingRecordId,
            MemoryRecordExtensionKeys.LAST_CONFIRMED_AT_EPOCH_MS to nowEpochMs.toString(),
          ),
        )
      }
  }

  private fun parseRecordMetadata(record: MemoryRecord): ParsedMemoryMetadata? {
    val kind = parseMemoryEnumValue(
      extensionValue = record.extensions[MemoryRecordExtensionKeys.KIND],
      tags = record.tags,
      tagPrefix = "kind:",
    ) { token -> MemoryKind.valueOf(token) } ?: return null
    val scope = parseMemoryEnumValue(
      extensionValue = record.extensions[MemoryRecordExtensionKeys.SCOPE],
      tags = record.tags,
      tagPrefix = "scope:",
    ) { token -> MemoryScope.valueOf(token) } ?: return null
    val status = parseMemoryEnumValue(
      extensionValue = record.extensions[MemoryRecordExtensionKeys.STATUS],
      tags = record.tags,
      tagPrefix = "status:",
    ) { token -> MemoryStatus.valueOf(token) } ?: return null
    return ParsedMemoryMetadata(
      kind = kind,
      scope = scope,
      status = status,
      source = parseMemoryEnum(record.extensions[MemoryRecordExtensionKeys.SOURCE]) { token ->
        MemoryEvidenceSource.valueOf(token)
      },
      sourceSessionId = record.extensions[MemoryRecordExtensionKeys.SOURCE_SESSION_ID]
        ?.takeIf(String::isNotBlank),
      workspaceId = record.extensions[MemoryRecordExtensionKeys.WORKSPACE_ID]
        ?.takeIf(String::isNotBlank),
      ttlMs = record.extensions[MemoryRecordExtensionKeys.TTL_MS]?.toLongOrNull(),
      lastConfirmedAtEpochMs = record.extensions[MemoryRecordExtensionKeys.LAST_CONFIRMED_AT_EPOCH_MS]
        ?.toLongOrNull(),
      preferenceKey = normalizeMemoryPreferenceKeyOrNull(
        record.extensions[MemoryRecordExtensionKeys.PREFERENCE_KEY],
      ),
      preferenceValue = normalizeMemoryPreferenceValueOrNull(
        record.extensions[MemoryRecordExtensionKeys.PREFERENCE_VALUE],
      ),
    )
  }

  private fun scopeIdentityMatches(
    metadata: ParsedMemoryMetadata,
    candidate: MemoryCandidate,
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

  private companion object {
    const val RESOLUTION_REASON_SUPERSEDED: String = "superseded"
  }
}

internal fun stableMemoryRecordId(candidate: MemoryCandidate): String {
  val scopeIdentity = when (candidate.scope) {
    MemoryScope.USER -> "user"
    MemoryScope.WORKSPACE -> "workspace:${candidate.workspaceId?.takeIf(String::isNotBlank) ?: "default-workspace"}"
    MemoryScope.SESSION -> "session:${candidate.sourceSessionId}"
  }
  val canonical = memoryCandidatePreferenceIdentity(candidate)
    ?: candidate.content.lowercase(Locale.US)
  val digestSource = "${candidate.kind.name.lowercase(Locale.US)}|$scopeIdentity|$canonical"
  val digest = MessageDigest.getInstance("SHA-256").digest(digestSource.toByteArray(Charsets.UTF_8))
  return "mem-${digest.joinToString(separator = "") { byte -> "%02x".format(byte) }.take(24)}"
}

private fun memoryCandidatePreferenceIdentity(candidate: MemoryCandidate): String? {
  val preferenceKey = normalizeMemoryPreferenceKeyOrNull(
    candidate.extensions[MemoryRecordExtensionKeys.PREFERENCE_KEY],
  ) ?: return null
  val preferenceValue = normalizeMemoryPreferenceValueOrNull(
    candidate.extensions[MemoryRecordExtensionKeys.PREFERENCE_VALUE],
  ) ?: return null
  return "pref|$preferenceKey|${preferenceValue.lowercase(Locale.US)}"
}
