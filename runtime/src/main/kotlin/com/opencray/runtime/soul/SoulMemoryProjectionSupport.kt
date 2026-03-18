package com.opencray.runtime.soul

import com.opencray.persistence.model.MemoryRecord
import com.opencray.runtime.memory.MemoryRecordExtensionKeys
import com.opencray.runtime.memory.MemoryScope
import com.opencray.runtime.memory.MemoryStatus
import com.opencray.runtime.memory.memoryRecordExpired
import com.opencray.runtime.memory.parseMemoryEnum

internal data class ParsedSoulMemoryEnvelope(
  val scope: MemoryScope,
  val status: MemoryStatus,
  val sourceSessionId: String?,
  val workspaceId: String?,
  val ttlMs: Long?,
  val referenceEpochMs: Long?,
)

internal fun MemoryRecord.parseSoulMemoryEnvelopeOrNull(
  nowEpochMs: Long,
): ParsedSoulMemoryEnvelope? {
  val scope = parseMemoryEnum(extensions[MemoryRecordExtensionKeys.SCOPE]) { token ->
    MemoryScope.valueOf(token)
  } ?: return null
  val status = parseMemoryEnum(extensions[MemoryRecordExtensionKeys.STATUS]) { token ->
    MemoryStatus.valueOf(token)
  } ?: return null
  if (status != MemoryStatus.ACTIVE) {
    return null
  }
  val sourceSessionId = extensions[MemoryRecordExtensionKeys.SOURCE_SESSION_ID]?.takeIf(String::isNotBlank)
  val workspaceId = extensions[MemoryRecordExtensionKeys.WORKSPACE_ID]?.takeIf(String::isNotBlank)
  val ttlMs = extensions[MemoryRecordExtensionKeys.TTL_MS]?.toLongOrNull()
  val referenceEpochMs = extensions[MemoryRecordExtensionKeys.LAST_CONFIRMED_AT_EPOCH_MS]?.toLongOrNull()
  if (
    memoryRecordExpired(
      ttlMs = ttlMs,
      lastConfirmedAtEpochMs = referenceEpochMs,
      updatedAtEpochMs = updatedAtEpochMs,
      nowEpochMs = nowEpochMs,
    )
  ) {
    return null
  }
  return ParsedSoulMemoryEnvelope(
    scope = scope,
    status = status,
    sourceSessionId = sourceSessionId,
    workspaceId = workspaceId,
    ttlMs = ttlMs,
    referenceEpochMs = referenceEpochMs,
  )
}

internal fun soulProjectionScopeMatches(
  envelope: ParsedSoulMemoryEnvelope,
  scope: MemoryScope,
  sessionId: String? = null,
  workspaceId: String? = null,
): Boolean = when (scope) {
  MemoryScope.USER -> envelope.scope == MemoryScope.USER
  MemoryScope.SESSION -> {
    envelope.scope == MemoryScope.SESSION &&
      !sessionId.isNullOrBlank() &&
      envelope.sourceSessionId == sessionId
  }

  MemoryScope.WORKSPACE -> {
    envelope.scope == MemoryScope.WORKSPACE &&
      envelope.workspaceId?.takeIf(String::isNotBlank) == workspaceId?.takeIf(String::isNotBlank)
  }
}
