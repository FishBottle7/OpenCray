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

    val existingById = store.list().associateBy(MemoryRecord::id).toMutableMap()
    val written = mutableListOf<MemoryRecord>()
    candidates.forEach { candidate ->
      val recordId = stableRecordId(candidate)
      val now = clock()
      val existing = existingById[recordId]
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
      existingById[recordId] = record
      written += record
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
  }

  private fun stableRecordId(candidate: MemoryCandidate): String {
    val scopeIdentity = when (candidate.scope) {
      MemoryScope.USER -> "user"
      MemoryScope.WORKSPACE -> "workspace:${candidate.workspaceId?.takeIf(String::isNotBlank) ?: DEFAULT_WORKSPACE_ID}"
      MemoryScope.SESSION -> "session:${candidate.sourceSessionId}"
    }
    val canonical = candidate.content.lowercase(Locale.US)
    val digestSource = "${candidate.kind.name.lowercase(Locale.US)}|$scopeIdentity|$canonical"
    return "mem-${sha256Hex(digestSource).take(24)}"
  }

  private fun sha256Hex(raw: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
  }

  private companion object {
    const val DEFAULT_WORKSPACE_ID: String = "default-workspace"
  }
}
