package com.opencray.runtime.memory

import com.opencray.persistence.model.MemoryRecord
import java.util.Locale

internal data class ParsedMemoryMetadata(
  val kind: MemoryKind,
  val scope: MemoryScope,
  val status: MemoryStatus,
  val source: MemoryEvidenceSource?,
  val sourceSessionId: String?,
  val workspaceId: String?,
  val ttlMs: Long?,
  val lastConfirmedAtEpochMs: Long?,
  val preferenceKey: String?,
  val preferenceValue: String?,
)

internal fun MemoryRecord.parseMemoryMetadata(): ParsedMemoryMetadata? {
  val kind = parseMemoryEnumValue(
    extensionValue = extensions[MemoryRecordExtensionKeys.KIND],
    tags = tags,
    tagPrefix = "kind:",
  ) { token -> MemoryKind.valueOf(token) } ?: return null
  val scope = parseMemoryEnumValue(
    extensionValue = extensions[MemoryRecordExtensionKeys.SCOPE],
    tags = tags,
    tagPrefix = "scope:",
  ) { token -> MemoryScope.valueOf(token) } ?: return null
  val status = parseMemoryEnumValue(
    extensionValue = extensions[MemoryRecordExtensionKeys.STATUS],
    tags = tags,
    tagPrefix = "status:",
  ) { token -> MemoryStatus.valueOf(token) } ?: return null
  return ParsedMemoryMetadata(
    kind = kind,
    scope = scope,
    status = status,
    source = parseMemoryEnum(extensions[MemoryRecordExtensionKeys.SOURCE]) { token ->
      MemoryEvidenceSource.valueOf(token)
    },
    sourceSessionId = extensions[MemoryRecordExtensionKeys.SOURCE_SESSION_ID]?.takeIf(String::isNotBlank),
    workspaceId = extensions[MemoryRecordExtensionKeys.WORKSPACE_ID]?.takeIf(String::isNotBlank),
    ttlMs = extensions[MemoryRecordExtensionKeys.TTL_MS]?.toLongOrNull(),
    lastConfirmedAtEpochMs = extensions[MemoryRecordExtensionKeys.LAST_CONFIRMED_AT_EPOCH_MS]?.toLongOrNull(),
    preferenceKey = normalizeMemoryPreferenceKeyOrNull(
      extensions[MemoryRecordExtensionKeys.PREFERENCE_KEY],
    ),
    preferenceValue = normalizeMemoryPreferenceValueOrNull(
      extensions[MemoryRecordExtensionKeys.PREFERENCE_VALUE],
    ),
  )
}

internal fun <T> parseMemoryEnumValue(
  extensionValue: String?,
  tags: List<String>,
  tagPrefix: String,
  parser: (String) -> T,
): T? {
  parseMemoryEnum(extensionValue, parser)?.let { return it }
  return tags.firstOrNull { tag -> tag.startsWith(tagPrefix) }
    ?.substringAfter(tagPrefix)
    ?.let { tagValue -> parseMemoryEnum(tagValue, parser) }
}

internal fun <T> parseMemoryEnum(
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
