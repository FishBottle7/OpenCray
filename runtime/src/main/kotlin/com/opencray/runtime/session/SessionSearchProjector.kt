package com.opencray.runtime.session

import com.opencray.runtime.context.RuntimeConversationAttachment
import com.opencray.runtime.context.RuntimeConversationCommentary
import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.context.RuntimeConversationToolCall
import com.opencray.runtime.context.RuntimeConversationToolResult
import com.opencray.runtime.memory.formatMemoryIsoInstant
import java.util.Locale

data class ProjectedSessionCorpus(
  val files: List<ProjectedSessionFile>,
) {
  fun file(path: String): ProjectedSessionFile? {
    val normalizedPath = normalizeProjectedSessionPath(path)
    return files.firstOrNull { file ->
      normalizeProjectedSessionPath(file.path) == normalizedPath
    }
  }
}

data class ProjectedSessionFile(
  val path: String,
  val lines: List<String>,
  val sections: List<ProjectedSessionSection>,
) {
  init {
    require(path.isNotBlank()) { "ProjectedSessionFile path must not be blank." }
    require(lines.isNotEmpty()) { "ProjectedSessionFile lines must not be empty." }
  }
}

data class ProjectedSessionSection(
  val sessionId: String,
  val title: String?,
  val path: String,
  val startLine: Int,
  val endLine: Int,
  val updatedAtEpochMs: Long,
  val kind: ProjectedSessionSectionKind,
  val searchableText: String,
  val snippet: String,
)

enum class ProjectedSessionSectionKind {
  INDEX,
  SUMMARY,
  MESSAGE,
}

class SessionSearchProjector {
  fun project(context: SessionSearchToolContext): ProjectedSessionCorpus {
    val visibleSessions = visibleSessions(context)
    val files = mutableListOf<ProjectedSessionFile>()
    files += projectIndexFile(visibleSessions)
    visibleSessions.forEach { session ->
      files += projectSessionFile(session)
    }
    return ProjectedSessionCorpus(files = files)
  }

  private fun visibleSessions(context: SessionSearchToolContext): List<SessionSearchSession> =
    context.sessions
      .asSequence()
      .filter { session -> session.sessionId != context.sessionId }
      .groupBy(SessionSearchSession::sessionId)
      .values
      .map { group ->
        checkNotNull(
          group.maxWithOrNull(
            compareBy<SessionSearchSession>(SessionSearchSession::updatedAtEpochMs)
              .thenBy(SessionSearchSession::createdAtEpochMs)
              .thenBy(SessionSearchSession::sessionId),
          ),
        )
      }
      .sortedWith(
        compareByDescending<SessionSearchSession>(SessionSearchSession::updatedAtEpochMs)
          .thenByDescending(SessionSearchSession::createdAtEpochMs)
          .thenBy(SessionSearchSession::sessionId),
      )
      .toList()

  private fun projectIndexFile(
    sessions: List<SessionSearchSession>,
  ): ProjectedSessionFile {
    val lines = mutableListOf(
      "# SESSIONS",
      "",
      "Projected prior-session history visible to this runtime.",
      "",
    )
    val sections = mutableListOf<ProjectedSessionSection>()
    if (sessions.isEmpty()) {
      lines += "No prior session history is available."
      return ProjectedSessionFile(
        path = INDEX_FILE_PATH,
        lines = lines,
        sections = emptyList(),
      )
    }
    sessions.forEach { session ->
      val preview = sessionIndexPreview(session)
      val startLine = lines.size + 1
      val line = buildString {
        append("- [")
        append(session.sessionId)
        append("] ")
        append("title=")
        append(session.title ?: "(untitled)")
        append(" created_at=")
        append(formatMemoryIsoInstant(session.createdAtEpochMs))
        append(" updated_at=")
        append(formatMemoryIsoInstant(session.updatedAtEpochMs))
        append(" path=")
        append(sessionFilePath(session.sessionId))
        if (preview.isNotBlank()) {
          append(" preview=")
          append(preview)
        }
      }
      lines += line
      sections += ProjectedSessionSection(
        sessionId = session.sessionId,
        title = session.title,
        path = INDEX_FILE_PATH,
        startLine = startLine,
        endLine = startLine,
        updatedAtEpochMs = session.updatedAtEpochMs,
        kind = ProjectedSessionSectionKind.INDEX,
        searchableText = line,
        snippet = preview.ifBlank { line },
      )
    }
    return ProjectedSessionFile(
      path = INDEX_FILE_PATH,
      lines = lines,
      sections = sections,
    )
  }

  private fun projectSessionFile(
    session: SessionSearchSession,
  ): ProjectedSessionFile {
    val lines = mutableListOf(
      "# Session ${session.sessionId}",
      "",
      "title: ${session.title ?: "(untitled)"}",
      "created_at: ${formatMemoryIsoInstant(session.createdAtEpochMs)}",
      "updated_at: ${formatMemoryIsoInstant(session.updatedAtEpochMs)}",
      "session_id: ${session.sessionId}",
    )
    val sections = mutableListOf<ProjectedSessionSection>()
    if (session.compactionSummaries.isEmpty() && session.messages.isEmpty()) {
      lines += ""
      lines += "No projected session history entries."
      return ProjectedSessionFile(
        path = sessionFilePath(session.sessionId),
        lines = lines,
        sections = emptyList(),
      )
    }

    session.compactionSummaries.forEachIndexed { index, summary ->
      lines += ""
      val startLine = lines.size + 1
      lines += "## Summary ${index + 1}"
      summary.compactedAtEpochMs?.let { compactedAtEpochMs ->
        lines += "compacted_at: ${formatMemoryIsoInstant(compactedAtEpochMs)}"
      }
      lines += "text:"
      lines += multilineLines(summary.text)
      val endLine = lines.size
      sections += ProjectedSessionSection(
        sessionId = session.sessionId,
        title = session.title,
        path = sessionFilePath(session.sessionId),
        startLine = startLine,
        endLine = endLine,
        updatedAtEpochMs = session.updatedAtEpochMs,
        kind = ProjectedSessionSectionKind.SUMMARY,
        searchableText = lines.subList(startLine - 1, endLine).joinToString(separator = "\n"),
        snippet = summarizeSessionSnippet(summary.text),
      )
    }

    session.messages.forEachIndexed { index, message ->
      lines += ""
      val startLine = lines.size + 1
      lines += "## Message ${index + 1}"
      lines += "role: ${message.role.name.lowercase(Locale.US)}"
      lines += "kind: ${message.kind.name.lowercase(Locale.US)}"
      message.assistantPhase?.let { assistantPhase ->
        lines += "assistant_phase: ${assistantPhase.name.lowercase(Locale.US)}"
      }
      message.toolCall?.appendTo(lines)
      message.toolResult?.appendTo(lines)
      message.commentary?.appendTo(lines)
      lines += "content:"
      lines += messageBodyLines(message)
      val endLine = lines.size
      sections += ProjectedSessionSection(
        sessionId = session.sessionId,
        title = session.title,
        path = sessionFilePath(session.sessionId),
        startLine = startLine,
        endLine = endLine,
        updatedAtEpochMs = session.updatedAtEpochMs,
        kind = ProjectedSessionSectionKind.MESSAGE,
        searchableText = lines.subList(startLine - 1, endLine).joinToString(separator = "\n"),
        snippet = summarizeSessionSnippet(messagePreview(message)),
      )
    }

    return ProjectedSessionFile(
      path = sessionFilePath(session.sessionId),
      lines = lines,
      sections = sections,
    )
  }

  private fun RuntimeConversationToolCall.appendTo(lines: MutableList<String>) {
    lines += "tool_name: $toolName"
    reason
      ?.takeIf(String::isNotBlank)
      ?.let { toolReason ->
        lines += "tool_reason: $toolReason"
      }
  }

  private fun RuntimeConversationToolResult.appendTo(lines: MutableList<String>) {
    lines += "tool_name: $toolName"
    status
      ?.takeIf(String::isNotBlank)
      ?.let { toolStatus ->
        lines += "tool_status: $toolStatus"
      }
    isError?.let { error ->
      lines += "tool_error: $error"
    }
  }

  private fun RuntimeConversationCommentary.appendTo(lines: MutableList<String>) {
    stage
      ?.takeIf(String::isNotBlank)
      ?.let { commentaryStage ->
        lines += "commentary_stage: $commentaryStage"
      }
  }

  private fun sessionIndexPreview(session: SessionSearchSession): String =
    summarizeSessionSnippet(
      session.compactionSummaries.lastOrNull()?.text
        ?: session.messages.lastOrNull()?.let(::messagePreview)
        ?: session.title.orEmpty(),
    )

  private fun messageBodyLines(message: RuntimeConversationMessage): List<String> {
    val contentLines = message.content
      .takeIf(String::isNotBlank)
      ?.let(::multilineLines)
      .orEmpty()
    if (contentLines.isNotEmpty()) {
      return contentLines
    }
    val attachmentLines = message.attachments.map(::renderAttachmentLine)
    return attachmentLines.ifEmpty { listOf("(no text)") }
  }

  private fun messagePreview(message: RuntimeConversationMessage): String =
    message.content
      .takeIf(String::isNotBlank)
      ?: message.attachments.firstOrNull()?.let(::renderAttachmentLine)
      ?: "(no text)"

  private fun renderAttachmentLine(attachment: RuntimeConversationAttachment): String = buildString {
    append("[attachment] ")
    append(attachment.displayName)
    attachment.transcriptText
      ?.takeIf(String::isNotBlank)
      ?.let { transcriptText ->
        append(" transcript=")
        append(transcriptText)
      }
  }

  private fun multilineLines(text: String): List<String> =
    text.replace("\r\n", "\n").split('\n')

  private fun summarizeSessionSnippet(text: String, maxChars: Int = 220): String {
    val collapsed = text.replace(Regex("\\s+"), " ").trim()
    if (collapsed.isBlank()) {
      return "(no text)"
    }
    return if (collapsed.length <= maxChars) {
      collapsed
    } else {
      collapsed.take(maxChars - 1).trimEnd() + "…"
    }
  }

  private fun sessionFilePath(sessionId: String): String = "sessions/$sessionId.md"

  private companion object {
    const val INDEX_FILE_PATH: String = "SESSIONS.md"
  }
}

internal fun normalizeProjectedSessionPath(rawPath: String): String =
  rawPath
    .trim()
    .replace('\\', '/')
    .removePrefix("./")
    .trimStart('/')
    .lowercase(Locale.US)
