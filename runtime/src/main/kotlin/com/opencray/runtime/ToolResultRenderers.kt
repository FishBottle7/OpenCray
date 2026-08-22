package com.opencray.runtime

import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.runtime.policy.ToolTargetResolver
import com.opencray.runtime.process.ManagedProcessSnapshot
import com.opencray.runtime.process.ManagedProcessStatus
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

internal const val MAX_EXECUTION_ATTACHMENT_ARTIFACT_PREVIEW_COUNT: Int = 12
internal val WINDOWS_ABSOLUTE_PATH_REGEX: Regex = Regex("^[A-Za-z]:[\\\\/].+")
internal const val MAX_RENDERED_WORKSPACE_PACKAGE_EXTRACTED_PATHS: Int = 50

internal data class TextEdit(
  val oldString: String,
  val newString: String,
  val replaceAll: Boolean,
)

internal data class TextEditOutcome(
  val content: String,
  val replacementCount: Int,
)

internal data class ShellPlan(
  val executable: String,
  val args: List<String>,
  val kind: String,
)

internal fun truncateToReadBudget(config: OpenCrayToolDispatcherConfig, text: String): Pair<String, Boolean> {
  val bytes = text.toByteArray(StandardCharsets.UTF_8)
  if (bytes.size <= config.maxReadBytes) {
    return text to false
  }
  return bytes.copyOf(config.maxReadBytes).toString(StandardCharsets.UTF_8) to true
}

internal fun splitLines(text: String): List<String> {
  if (text.isEmpty()) {
    return emptyList()
  }
  return text
    .replace("\r\n", "\n")
    .replace('\r', '\n')
    .split('\n')
}

internal fun collectSearchCandidates(root: Path): List<Path> {
  if (!Files.isDirectory(root)) {
    return listOf(root)
  }
  return Files.walk(root).use { stream ->
    val collected = mutableListOf<Path>()
    val iterator = stream.sorted().iterator()
    while (iterator.hasNext()) {
      val candidate = iterator.next()
      if (candidate != root) {
        collected.add(candidate)
      }
    }
    collected
  }
}

internal fun collectRegularFiles(root: Path): List<Path> {
  if (!Files.isDirectory(root)) {
    return if (Files.isRegularFile(root)) listOf(root) else emptyList()
  }
  return Files.walk(root).use { stream ->
    val collected = mutableListOf<Path>()
    val iterator = stream.sorted().iterator()
    while (iterator.hasNext()) {
      val candidate = iterator.next()
      if (Files.isRegularFile(candidate)) {
        collected.add(candidate)
      }
    }
    collected
  }
}

internal fun compileGlobMatcher(pattern: String): Regex =
  Regex("^${globPatternToRegex(normalizeGlobPattern(pattern))}$")

internal fun normalizeGlobPattern(pattern: String): String = pattern.replace('\\', '/')

internal fun globPatternToRegex(pattern: String): String {
  val regex = StringBuilder()
  var index = 0
  while (index < pattern.length) {
    val current = pattern[index]
    when (current) {
      '*' -> {
        val isDoubleStar = index + 1 < pattern.length && pattern[index + 1] == '*'
        if (isDoubleStar) {
          val consumesSlash = index + 2 < pattern.length && pattern[index + 2] == '/'
          regex.append(if (consumesSlash) "(?:.*/)?" else ".*")
          index += if (consumesSlash) 3 else 2
        } else {
          regex.append("[^/]*")
          index += 1
        }
      }

      '?' -> {
        regex.append("[^/]")
        index += 1
      }

      '/', '.', '(', ')', '+', '|', '^', '$', '{', '}', '[', ']', '\\' -> {
        regex.append("\\").append(current)
        index += 1
      }

      else -> {
        regex.append(current)
        index += 1
      }
    }
  }
  return regex.toString()
}

internal fun applyTextEdits(
  source: String,
  edits: List<TextEdit>,
): TextEditOutcome {
  var current = source
  var replacementCount = 0
  edits.forEachIndexed { index, edit ->
    require(edit.oldString.isNotEmpty()) { "Edit ${index + 1} old_string must not be empty." }
    val matchCount = countOccurrences(current, edit.oldString)
    require(matchCount > 0) { "Edit ${index + 1} old_string was not found in the target file." }
    require(matchCount == 1 || edit.replaceAll) {
      "Edit ${index + 1} old_string is ambiguous; found $matchCount matches. Set replace_all=true to replace every match."
    }
    current = if (edit.replaceAll) {
      current.replace(edit.oldString, edit.newString)
    } else {
      current.replaceFirst(edit.oldString, edit.newString)
    }
    replacementCount += if (edit.replaceAll) matchCount else 1
  }
  return TextEditOutcome(content = current, replacementCount = replacementCount)
}

internal fun countOccurrences(text: String, target: String): Int {
  if (target.isEmpty()) {
    return 0
  }
  var count = 0
  var index = text.indexOf(target)
  while (index >= 0) {
    count += 1
    index = text.indexOf(target, startIndex = index + target.length)
  }
  return count
}

internal fun ExecutionResult.toAgentToolResult(config: OpenCrayToolDispatcherConfig, toolName: String): AgentToolResult {
  val status = when (status) {
    ExecutionStatus.SUCCESS -> AgentToolResultStatus.SUCCESS
    ExecutionStatus.DENIED -> AgentToolResultStatus.DENIED
    ExecutionStatus.CANCELLED -> AgentToolResultStatus.CANCELLED
    ExecutionStatus.TIMEOUT -> AgentToolResultStatus.TIMEOUT
    ExecutionStatus.FAILED -> AgentToolResultStatus.FAILED
  }
  val content = when {
    stdout.isNotBlank() -> stdout
    stderr.isNotBlank() -> stderr
    errorMessage != null -> errorMessage.orEmpty()
    else -> "Tool finished with status ${status.name.lowercase()}."
  }
  val renderedContent = appendExecutionAttachmentArtifactSummary(
    config = config,
    toolName = toolName,
    content = content,
    metadata = metadata,
  )
  return AgentToolResult(
    toolName = toolName,
    status = status,
    content = renderedContent,
    exitCode = exitCode,
    stdout = stdout,
    stderr = stderr,
    errorCode = errorCode,
    errorMessage = errorMessage,
    metadata = metadata,
  )
}

internal fun appendExecutionAttachmentArtifactSummary(
  config: OpenCrayToolDispatcherConfig,
  toolName: String,
  content: String,
  metadata: Map<String, String>,
): String {
  if (toolName != "python_exec" && toolName != "command_exec") {
    return content
  }
  val artifacts = OpenCrayAttachmentArtifacts.decodeMetadata(config.json, metadata)
  if (artifacts.isEmpty()) {
    return content
  }
  val previewArtifacts = artifacts.take(MAX_EXECUTION_ATTACHMENT_ARTIFACT_PREVIEW_COUNT)
  if (previewArtifacts.any { artifact -> content.contains("artifact_id=${artifact.artifactId}") }) {
    return content
  }
  val summary = buildString {
    appendLine("Workspace artifact(s) available:")
    previewArtifacts.forEachIndexed { index, artifact ->
      appendLine("${index + 1}. artifact_id=${artifact.artifactId}")
      appendLine("   relative_path=${artifact.relativePath}")
    }
    val remainingCount = artifacts.size - previewArtifacts.size
    if (remainingCount > 0) {
      appendLine("...and $remainingCount more artifact(s).")
    }
    append("You may attach these artifact_id values in the final response attachments array.")
  }.trim()
  return buildString {
    if (content.isNotBlank()) {
      append(content.trimEnd())
      append("\n\n")
    }
    append(summary)
  }
}

internal fun bashStartSummary(
  snapshot: ManagedProcessSnapshot,
  background: Boolean,
): String = when (snapshot.status) {
  ManagedProcessStatus.RUNNING -> if (background) {
    "Shell command started in background."
  } else {
    "Shell command started."
  }

  ManagedProcessStatus.SPAWN_ERROR -> "Shell command failed to start."
  ManagedProcessStatus.CANCELLED -> "Shell command was cancelled."
  ManagedProcessStatus.TIMEOUT -> "Shell command timed out."
  ManagedProcessStatus.SUCCESS -> "Shell command finished."
  ManagedProcessStatus.FAILED -> "Shell command failed."
}

internal fun bashWaitSummary(
  snapshot: ManagedProcessSnapshot,
  waitTimeoutMs: Long,
): String = when (snapshot.status) {
  ManagedProcessStatus.RUNNING ->
    "Shell command is still running after waiting ${waitTimeoutMs}ms. Continue with ProcessRead, ProcessWait, or ProcessTerminate."

  ManagedProcessStatus.SUCCESS -> "Shell command finished."
  ManagedProcessStatus.SPAWN_ERROR -> "Shell command failed to start."
  ManagedProcessStatus.CANCELLED -> "Shell command was cancelled."
  ManagedProcessStatus.TIMEOUT -> "Shell command timed out."
  ManagedProcessStatus.FAILED -> "Shell command failed."
}

internal fun defaultShellPlan(command: String): ShellPlan {
  val osName = System.getProperty("os.name").orEmpty().lowercase()
  return if (osName.contains("win")) {
    ShellPlan(
      executable = "powershell.exe",
      args = listOf("-NoLogo", "-NoProfile", "-Command", command),
      kind = "powershell",
    )
  } else {
    ShellPlan(
      executable = "sh",
      args = listOf("-lc", command),
      kind = "sh",
    )
  }
}

internal fun inlinePreview(
  value: String,
  maxChars: Int = 512,
): String {
  val normalized = value
    .replace("\r", "\\r")
    .replace("\n", "\\n")
  return if (normalized.length <= maxChars) {
    normalized
  } else {
    normalized.take(maxChars - 1).trimEnd() + "…"
  }
}

internal fun renderWorkspaceDocumentSearchResult(
  displayPath: String,
  request: WorkspaceDocumentSearchRequest,
  result: WorkspaceDocumentSearchResult,
): String = buildString {
  appendLine("Workspace document search: $displayPath")
  appendLine(
    buildString {
      append("kind=")
      append(result.documentKind.name.lowercase(Locale.US))
      append(" page_count=")
      append(result.pageCount)
      append(" query=")
      append(result.query ?: "<preview>")
      append(" results=")
      append(result.hits.size)
    },
  )
  renderWorkspaceDocumentPageSelectionSummary(request)?.let(::appendLine)
  if (result.hits.isEmpty()) {
    appendLine(
      if (result.query == null) {
        "No preview pages were returned."
      } else {
        "No matches found."
      },
    )
    return@buildString
  }
  result.hits.forEach { hit ->
    appendLine()
    append("page ")
    append(hit.pageNumber)
    if (hit.matchCount > 0) {
      append(" matches=")
      append(hit.matchCount)
    }
    appendLine()
    appendLine(hit.excerpt)
  }
}.trim()

internal fun renderWorkspaceDocumentPageSelectionSummary(
  request: WorkspaceDocumentSearchRequest,
): String? = when {
  request.pageNumbers.isNotEmpty() -> "requested_pages=${request.pageNumbers.joinToString(separator = ",")}"
  request.pageFrom != null || request.pageTo != null -> "requested_range=${request.pageFrom ?: 1}-${request.pageTo ?: "end"}"
  else -> null
}

internal fun renderWorkspacePackageInspectionResult(
  displayPath: String,
  request: WorkspacePackageInspectionRequest,
  result: WorkspacePackageInspectionResult,
): String = buildString {
  appendLine("Workspace package inspection: $displayPath")
  appendLine(
    buildString {
      append("kind=")
      append(result.packageKind.name.lowercase(Locale.US))
      append(" entry_count=")
      append(result.entryCount)
      append(" matched_entries=")
      append(result.matchedEntryCount)
      append(" returned_entries=")
      append(result.entries.size)
      append(" previews=")
      append(result.previews.size)
      append(" media_entries=")
      append(result.mediaEntryCount)
    },
  )
  request.glob?.let { appendLine("requested_glob=$it") }
  if (request.previewEntries.isNotEmpty()) {
    appendLine("requested_preview_entries=${request.previewEntries.joinToString(separator = ",")}")
  }
  if (result.mainPartHints.isNotEmpty()) {
    appendLine("main_part_hints=${result.mainPartHints.joinToString(separator = ",")}")
  }
  if (result.relationshipPartHints.isNotEmpty()) {
    appendLine("relationship_part_hints=${result.relationshipPartHints.joinToString(separator = ",")}")
  }
  appendLine()
  appendLine("entries:")
  if (result.entries.isEmpty()) {
    appendLine("<no matched entries>")
  } else {
    result.entries.forEachIndexed { index, entry ->
      append("${index + 1}. ")
      append(entry.path)
      append(" type=")
      append(if (entry.isDirectory) "directory" else "file")
      append(" previewable=")
      append(entry.previewable)
      entry.mimeType?.let { append(" mime=").append(it) }
      entry.uncompressedSize?.let { append(" size=").append(it) }
      entry.compressedSize?.let { append(" compressed=").append(it) }
      appendLine()
    }
  }
  if (result.previews.isNotEmpty()) {
    result.previews.forEach { preview ->
      appendLine()
      appendLine("preview: ${preview.path}")
      appendLine(preview.content)
    }
  }
  if (result.truncated) {
    appendLine()
    append("Result was truncated by package inspection limits.")
  }
}.trim()

internal fun renderWorkspacePackageExtractionResult(
  toolTargetResolver: ToolTargetResolver,
  displayPath: String,
  displayDestinationDir: String,
  request: WorkspacePackageExtractionRequest,
  result: WorkspacePackageExtractionResult,
): String = buildString {
  appendLine("Workspace package extraction: $displayPath")
  appendLine(
    buildString {
      append("kind=")
      append(result.packageKind.name.lowercase(Locale.US))
      append(" entry_count=")
      append(result.entryCount)
      append(" matched_entries=")
      append(result.matchedEntryCount)
      append(" extracted_entries=")
      append(result.extractedPaths.size)
    },
  )
  appendLine("destination_dir=$displayDestinationDir")
  if (request.entries.isNotEmpty()) {
    appendLine("requested_entries=${request.entries.joinToString(separator = ",")}")
  }
  request.glob?.let { appendLine("requested_glob=$it") }
  appendLine("overwrite=${request.overwrite}")
  appendLine("strip_top_level=${request.stripTopLevel}")
  result.strippedTopLevel?.let { appendLine("stripped_top_level=$it") }
  appendLine()
  appendLine("extracted_paths:")
  if (result.extractedPaths.isEmpty()) {
    appendLine("<no extracted entries>")
  } else {
    val renderedPaths = result.extractedPaths.take(MAX_RENDERED_WORKSPACE_PACKAGE_EXTRACTED_PATHS)
    renderedPaths.forEachIndexed { index, path ->
      appendLine("${index + 1}. ${toolTargetResolver.displayWritablePath(path)}")
    }
    val omittedCount = result.extractedPaths.size - renderedPaths.size
    if (omittedCount > 0) {
      append("...and $omittedCount more extracted path(s).")
    }
  }
}.trim()
