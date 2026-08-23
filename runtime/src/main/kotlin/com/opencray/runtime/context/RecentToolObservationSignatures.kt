package com.opencray.runtime.context

import com.opencray.runtime.AgentToolCall

internal fun RecentToolObservationSupport.signatureForCall(call: AgentToolCall): String? {
    val arguments = call.arguments
    return when (canonicalToolName(call.toolName)) {
      "Read" -> buildReadSignature(
        filePath = arguments.stringValueFrom("file_path", "path") ?: return null,
        offset = arguments.intValue("offset") ?: 1,
        limit = arguments.intValue("limit"),
      )

      "LS" -> buildListSignature(
        path = arguments.stringValue("path") ?: ".",
      )

      "Grep" -> buildGrepSignature(
        pattern = arguments.stringValue("pattern") ?: return null,
        path = arguments.stringValue("path") ?: ".",
        glob = arguments.stringValue("glob"),
      )

      "Glob" -> buildGlobSignature(
        pattern = arguments.stringValue("pattern") ?: return null,
        path = arguments.stringValue("path") ?: ".",
      )

      "search_workspace_document" -> buildWorkspaceDocumentSearchSignature(
        path = arguments.stringValue("path") ?: return null,
        query = arguments.stringValue("query"),
        requestedPages = arguments.intArrayCsvValue("pages"),
        pageFrom = arguments.stringValue("page_from"),
        pageTo = arguments.stringValue("page_to"),
      )

      "inspect_workspace_package" -> buildWorkspacePackageInspectSignature(
        path = arguments.stringValue("path") ?: return null,
        glob = arguments.stringValue("glob"),
        previewEntries = arguments.stringArrayCsvValue("preview_entries"),
        maxEntries = arguments.stringValue("max_entries"),
        previewChars = arguments.stringValue("preview_chars"),
        includeRelationshipHints = arguments.stringValue("include_relationship_hints"),
      )

      "SkillsFind" -> buildSkillsFindSignature(
        query = arguments.stringValue("query").orEmpty(),
      )

      "SkillsList" -> buildSkillsListSignature()

      "SkillsInspect" -> buildSkillsInspectSignature(
        sourceRef = arguments.stringValueFrom("source_ref", "sourceRef", "source", "path", "url") ?: return null,
      )

      "SkillsCheck" -> buildSkillsCheckSignature(
        skillId = arguments.stringValueFrom("skill_id", "skillId", "name"),
      )

      "ScheduledTaskCreate" -> buildScheduledTaskCreateSignature(
        scheduleId = null,
        sessionId = arguments.stringValueFrom("session_id", "sessionId"),
        triggerKind = arguments.objectValue("trigger")?.scheduledTaskTriggerKind(),
      )

      "ScheduledTaskList" -> buildScheduledTaskListSignature(
        sessionId = arguments.stringValueFrom("session_id", "sessionId"),
        enabled = arguments.stringValue("enabled"),
        limit = null,
      )

      "ScheduledTaskGet" -> buildScheduledTaskEntitySignature(
        toolName = "ScheduledTaskGet",
        scheduleId = arguments.stringValueFrom("schedule_id", "scheduleId") ?: return null,
        triggerKind = null,
      )

      "ScheduledTaskUpdate" -> buildScheduledTaskEntitySignature(
        toolName = "ScheduledTaskUpdate",
        scheduleId = arguments.stringValueFrom("schedule_id", "scheduleId") ?: return null,
        triggerKind = arguments.objectValue("trigger")?.scheduledTaskTriggerKind(),
      )

      "ScheduledTaskDelete" -> buildScheduledTaskEntitySignature(
        toolName = "ScheduledTaskDelete",
        scheduleId = arguments.stringValueFrom("schedule_id", "scheduleId") ?: return null,
        triggerKind = null,
      )

      else -> null
    }
  }

internal fun RecentToolObservationSupport.buildReadLineSummary(
    offset: Int,
    returnedLineCount: Int?,
    totalLineCount: Int?,
  ): String {
    val lineRange = when {
      returnedLineCount == null -> "lines=$offset+"
      returnedLineCount <= 0 -> "lines=empty@$offset"
      else -> "lines=$offset-${offset + returnedLineCount - 1}"
    }
    return if (totalLineCount != null) {
      "$lineRange/$totalLineCount"
    } else {
      lineRange
    }
  }

internal fun RecentToolObservationSupport.buildReadSignature(
    filePath: String,
    offset: Int,
    limit: Int?,
  ): String = listOf(
    "Read",
    normalizePathValue(filePath),
    offset.toString(),
    limit?.toString().orEmpty(),
  ).joinToString(separator = "|")

internal fun RecentToolObservationSupport.buildListSignature(path: String): String = listOf(
    "LS",
    normalizePathValue(path),
  ).joinToString(separator = "|")

internal fun RecentToolObservationSupport.buildGrepSignature(
    pattern: String,
    path: String,
    glob: String?,
  ): String = listOf(
    "Grep",
    pattern.trim(),
    normalizePathValue(path),
    glob?.trim().orEmpty(),
  ).joinToString(separator = "|")

internal fun RecentToolObservationSupport.buildGlobSignature(
    pattern: String,
    path: String,
  ): String = listOf(
    "Glob",
    pattern.trim(),
    normalizePathValue(path),
  ).joinToString(separator = "|")

internal fun RecentToolObservationSupport.buildWorkspaceDocumentSearchSignature(
    path: String,
    query: String?,
    requestedPages: String?,
    pageFrom: String?,
    pageTo: String?,
  ): String = listOf(
    "search_workspace_document",
    normalizePathValue(path),
    query?.trim().orEmpty(),
    requestedPages?.trim().orEmpty(),
    pageFrom?.trim().orEmpty(),
    pageTo?.trim().orEmpty(),
  ).joinToString(separator = "|")

internal fun RecentToolObservationSupport.buildWorkspacePackageInspectSignature(
    path: String,
    glob: String?,
    previewEntries: String?,
    maxEntries: String?,
    previewChars: String?,
    includeRelationshipHints: String?,
  ): String = listOf(
    "inspect_workspace_package",
    normalizePathValue(path),
    glob?.trim().orEmpty(),
    previewEntries?.trim().orEmpty(),
    maxEntries?.trim().orEmpty(),
    previewChars?.trim().orEmpty(),
    includeRelationshipHints?.trim().orEmpty(),
  ).joinToString(separator = "|")

internal fun RecentToolObservationSupport.buildSkillsFindSignature(query: String): String = listOf(
    "SkillsFind",
    query.trim(),
  ).joinToString(separator = "|")

internal fun RecentToolObservationSupport.buildSkillsListSignature(): String = "SkillsList"

internal fun RecentToolObservationSupport.buildSkillsInspectSignature(sourceRef: String): String = listOf(
    "SkillsInspect",
    sourceRef.trim(),
  ).joinToString(separator = "|")

internal fun RecentToolObservationSupport.buildSkillsCheckSignature(skillId: String?): String = listOf(
    "SkillsCheck",
    skillId?.trim().orEmpty(),
  ).joinToString(separator = "|")

internal fun RecentToolObservationSupport.buildScheduledTaskCreateSignature(
    scheduleId: String?,
    sessionId: String?,
    triggerKind: String?,
  ): String = listOf(
    "ScheduledTaskCreate",
    scheduleId?.trim().orEmpty(),
    sessionId?.trim().orEmpty(),
    triggerKind?.trim().orEmpty(),
  ).joinToString(separator = "|")

internal fun RecentToolObservationSupport.buildScheduledTaskListSignature(
    sessionId: String?,
    enabled: String?,
    limit: String?,
  ): String = listOf(
    "ScheduledTaskList",
    sessionId?.trim().orEmpty(),
    enabled?.trim().orEmpty(),
    limit?.trim().orEmpty(),
  ).joinToString(separator = "|")

internal fun RecentToolObservationSupport.buildScheduledTaskEntitySignature(
    toolName: String,
    scheduleId: String,
    triggerKind: String?,
  ): String = listOf(
    toolName.trim(),
    scheduleId.trim(),
    triggerKind?.trim().orEmpty(),
  ).joinToString(separator = "|")

internal fun RecentToolObservationSupport.buildTaskSignature(
    description: String,
    subagentType: String,
    headline: String,
  ): String = listOf(
    "Task",
    description.trim(),
    subagentType.trim(),
    headline.trim(),
  ).joinToString(separator = "|")

internal fun RecentToolObservationSupport.normalizePathValue(path: String): String = path.trim().ifBlank { "." }.replace('\\', '/')

internal fun RecentToolObservationSupport.compactBody(
    text: String,
  ): String = boundMultiline(
    text = text,
    maxChars = config.maxCompactBodyChars,
    maxLines = config.maxCompactBodyLines,
  )

internal fun RecentToolObservationSupport.boundMultiline(
    text: String,
    maxChars: Int,
    maxLines: Int,
  ): String {
    val normalized = text.trim().ifBlank { "<empty>" }
    val lines = normalized.lineSequence().toList()
    val keptLines = if (lines.size <= maxLines) lines else lines.take(maxLines)
    val joined = keptLines.joinToString(separator = "\n")
    val boundedChars = if (joined.length <= maxChars) joined else joined.take(maxChars).trimEnd() + "…"
    val truncated = keptLines.size < lines.size || boundedChars.length < normalized.length
    return if (truncated) {
      "$boundedChars\n[truncated for recent observation working set]"
    } else {
      boundedChars
    }
  }

internal fun RecentToolObservationSupport.boundRenderedText(text: String): String =
    if (text.length <= config.maxRenderedChars) {
      text
    } else {
      text.take(config.maxRenderedChars).trimEnd() + "\n[recent observation layer truncated]"
    }

internal fun RecentToolObservationSupport.boundExcerpt(text: String): String =
    if (text.length <= config.duplicateExcerptChars) {
      text
    } else {
      text.take(config.duplicateExcerptChars).trimEnd() + "\n[excerpt truncated]"
    }
