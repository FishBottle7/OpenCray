package com.opencray.runtime.memory

import com.opencray.persistence.model.MemoryRecord
import com.opencray.runtime.soul.hasSoulObjectPayload
import java.util.Locale

data class MemoryToolContext(
  val sessionId: String,
  val workspaceId: String? = null,
  val records: List<MemoryRecord> = emptyList(),
) {
  init {
    require(sessionId.isNotBlank()) { "MemoryToolContext sessionId must not be blank." }
  }
}

data class ProjectedMemoryCorpus(
  val files: List<ProjectedMemoryFile>,
) {
  fun file(path: String): ProjectedMemoryFile? {
    val normalizedPath = normalizeProjectedMemoryPath(path)
    return files.firstOrNull { file ->
      normalizeProjectedMemoryPath(file.path) == normalizedPath
    }
  }
}

data class ProjectedMemoryFile(
  val path: String,
  val lines: List<String>,
  val sections: List<ProjectedMemorySection>,
) {
  init {
    require(path.isNotBlank()) { "ProjectedMemoryFile path must not be blank." }
    require(lines.isNotEmpty()) { "ProjectedMemoryFile lines must not be empty." }
  }
}

data class ProjectedMemorySection(
  val recordId: String,
  val path: String,
  val startLine: Int,
  val endLine: Int,
  val kind: MemoryKind,
  val scope: MemoryScope,
  val status: MemoryStatus,
  val updatedAtEpochMs: Long,
  val lastConfirmedAtEpochMs: Long,
  val searchableText: String,
  val previewText: String,
)

class MemoryCorpusProjector(
  private val policy: MemoryPolicy = MemoryPolicy(),
  private val clock: () -> Long = System::currentTimeMillis,
) {
  fun project(context: MemoryToolContext): ProjectedMemoryCorpus {
    val visibleRecords = visibleRecords(context)
    val files = mutableListOf<ProjectedMemoryFile>()
    files += projectIndexFile(visibleRecords)
    visibleRecords
      .groupBy { visible -> formatMemoryDateStamp(visible.lastConfirmedAtEpochMs) }
      .toSortedMap(compareByDescending<String> { it })
      .forEach { (dateStamp, recordsForDay) ->
        files += projectDayFile(
          dateStamp = dateStamp,
          visibleRecords = recordsForDay,
        )
      }
    return ProjectedMemoryCorpus(files = files)
  }

  private fun visibleRecords(context: MemoryToolContext): List<VisibleMemoryRecord> {
    val nowEpochMs = clock()
    return context.records
      .mapNotNull { record ->
        val metadata = record.parseMemoryMetadata() ?: return@mapNotNull null
        val normalizedContent = policy.normalizeCandidateContent(record.content) ?: return@mapNotNull null
        if (
          !memoryScopeMatches(
            scope = metadata.scope,
            sourceSessionId = metadata.sourceSessionId,
            recordWorkspaceId = metadata.workspaceId,
            requestSessionId = context.sessionId,
            requestWorkspaceId = context.workspaceId,
          )
        ) {
          return@mapNotNull null
        }
        if (
          memoryRecordExpired(
            ttlMs = metadata.ttlMs,
            lastConfirmedAtEpochMs = metadata.lastConfirmedAtEpochMs,
            updatedAtEpochMs = record.updatedAtEpochMs,
            nowEpochMs = nowEpochMs,
          )
        ) {
          return@mapNotNull null
        }
        if (record.hasSoulObjectPayload()) {
          return@mapNotNull null
        }
        VisibleMemoryRecord(
          id = record.id,
          kind = metadata.kind,
          scope = metadata.scope,
          status = metadata.status,
          source = metadata.source,
          sourceSessionId = metadata.sourceSessionId,
          workspaceId = metadata.workspaceId,
          content = normalizedContent,
          updatedAtEpochMs = record.updatedAtEpochMs,
          lastConfirmedAtEpochMs = metadata.lastConfirmedAtEpochMs ?: record.updatedAtEpochMs,
          preferenceKey = metadata.preferenceKey,
          preferenceValue = metadata.preferenceValue,
        )
      }
      .sortedWith(
        compareByDescending<VisibleMemoryRecord> { visible -> visible.lastConfirmedAtEpochMs }
          .thenByDescending { visible -> visible.updatedAtEpochMs }
          .thenBy { visible -> visible.id },
      )
  }

  private fun projectIndexFile(
    visibleRecords: List<VisibleMemoryRecord>,
  ): ProjectedMemoryFile {
    val lines = mutableListOf(
      "# MEMORY",
      "",
      "Projected memory index visible to this runtime.",
      "",
    )
    val sections = mutableListOf<ProjectedMemorySection>()
    if (visibleRecords.isEmpty()) {
      lines += "No visible memory records."
      return ProjectedMemoryFile(
        path = INDEX_FILE_PATH,
        lines = lines,
        sections = emptyList(),
      )
    }
    visibleRecords.forEach { visible ->
      val startLine = lines.size + 1
      val line = buildString {
        append("- [")
        append(visible.id)
        append("] ")
        append("kind=")
        append(visible.kind.name.lowercase(Locale.US))
        append(" scope=")
        append(visible.scope.name.lowercase(Locale.US))
        append(" status=")
        append(visible.status.name.lowercase(Locale.US))
        append(" confirmed_at=")
        append(formatMemoryDateStamp(visible.lastConfirmedAtEpochMs))
        append(" content=")
        append(visible.content)
      }
      lines += line
      sections += ProjectedMemorySection(
        recordId = visible.id,
        path = INDEX_FILE_PATH,
        startLine = startLine,
        endLine = startLine,
        kind = visible.kind,
        scope = visible.scope,
        status = visible.status,
        updatedAtEpochMs = visible.updatedAtEpochMs,
        lastConfirmedAtEpochMs = visible.lastConfirmedAtEpochMs,
        searchableText = line,
        previewText = visible.content,
      )
    }
    return ProjectedMemoryFile(
      path = INDEX_FILE_PATH,
      lines = lines,
      sections = sections,
    )
  }

  private fun projectDayFile(
    dateStamp: String,
    visibleRecords: List<VisibleMemoryRecord>,
  ): ProjectedMemoryFile {
    val lines = mutableListOf(
      "# Memory $dateStamp",
      "",
      "Projected durable memory entries visible to this runtime.",
      "",
    )
    val sections = mutableListOf<ProjectedMemorySection>()
    visibleRecords.forEachIndexed { index, visible ->
      val startLine = lines.size + 1
      lines += "## ${visible.id}"
      lines += "kind: ${visible.kind.name.lowercase(Locale.US)}"
      lines += "scope: ${visible.scope.name.lowercase(Locale.US)}"
      lines += "status: ${visible.status.name.lowercase(Locale.US)}"
      visible.source?.let { source ->
        lines += "source: ${source.name.lowercase(Locale.US)}"
      }
      visible.sourceSessionId
        ?.takeIf(String::isNotBlank)
        ?.let { sourceSessionId ->
          lines += "source_session_id: $sourceSessionId"
        }
      visible.workspaceId
        ?.takeIf(String::isNotBlank)
        ?.let { workspaceId ->
          lines += "workspace_id: $workspaceId"
        }
      visible.preferenceKey
        ?.takeIf(String::isNotBlank)
        ?.let { preferenceKey ->
          lines += "preference_key: $preferenceKey"
        }
      visible.preferenceValue
        ?.takeIf(String::isNotBlank)
        ?.let { preferenceValue ->
          lines += "preference_value: $preferenceValue"
        }
      lines += "confirmed_at: ${formatMemoryIsoInstant(visible.lastConfirmedAtEpochMs)}"
      lines += "updated_at: ${formatMemoryIsoInstant(visible.updatedAtEpochMs)}"
      lines += "content: ${visible.content}"
      val endLine = lines.size
      sections += ProjectedMemorySection(
        recordId = visible.id,
        path = "memory/$dateStamp.md",
        startLine = startLine,
        endLine = endLine,
        kind = visible.kind,
        scope = visible.scope,
        status = visible.status,
        updatedAtEpochMs = visible.updatedAtEpochMs,
        lastConfirmedAtEpochMs = visible.lastConfirmedAtEpochMs,
        searchableText = lines.subList(startLine - 1, endLine).joinToString(separator = "\n"),
        previewText = visible.content,
      )
      if (index != visibleRecords.lastIndex) {
        lines += ""
      }
    }
    return ProjectedMemoryFile(
      path = "memory/$dateStamp.md",
      lines = lines,
      sections = sections,
    )
  }

  private companion object {
    const val INDEX_FILE_PATH: String = "MEMORY.md"
  }
}

internal fun normalizeProjectedMemoryPath(rawPath: String): String =
  rawPath
    .trim()
    .replace('\\', '/')
    .removePrefix("./")
    .trimStart('/')
    .lowercase(Locale.US)
