package com.opencray.runtime

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile

enum class WorkspacePackageKind {
  ZIP,
  DOCX,
  XLSX,
  PPTX,
  ODT,
  ODS,
  ODP,
}

data class WorkspacePackageEntry(
  val path: String,
  val isDirectory: Boolean,
  val compressedSize: Long? = null,
  val uncompressedSize: Long? = null,
  val mimeType: String? = null,
  val previewable: Boolean = false,
)

data class WorkspacePackageEntryPreview(
  val path: String,
  val content: String,
  val truncated: Boolean = false,
)

data class WorkspacePackageInspectionRequest(
  val glob: String? = null,
  val maxEntries: Int = 50,
  val previewEntries: List<String> = emptyList(),
  val previewChars: Int = 2_000,
  val includeRelationshipHints: Boolean = true,
)

data class WorkspacePackageInspectionResult(
  val packageKind: WorkspacePackageKind,
  val entryCount: Int,
  val matchedEntryCount: Int,
  val entries: List<WorkspacePackageEntry>,
  val previews: List<WorkspacePackageEntryPreview> = emptyList(),
  val mainPartHints: List<String> = emptyList(),
  val relationshipPartHints: List<String> = emptyList(),
  val mediaEntryCount: Int = 0,
  val truncated: Boolean = false,
)

data class WorkspacePackageExtractionRequest(
  val destinationRoot: Path,
  val entries: List<String> = emptyList(),
  val glob: String? = null,
  val stripTopLevel: Boolean = false,
  val overwrite: Boolean = false,
)

data class WorkspacePackageExtractionResult(
  val packageKind: WorkspacePackageKind,
  val entryCount: Int,
  val matchedEntryCount: Int,
  val extractedPaths: List<Path>,
  val strippedTopLevel: String? = null,
)

interface WorkspacePackageProvider {
  fun inspect(
    path: Path,
    request: WorkspacePackageInspectionRequest,
  ): WorkspacePackageInspectionResult

  fun extract(
    path: Path,
    request: WorkspacePackageExtractionRequest,
  ): WorkspacePackageExtractionResult
}

class DefaultWorkspacePackageProvider : WorkspacePackageProvider {
  override fun inspect(
    path: Path,
    request: WorkspacePackageInspectionRequest,
  ): WorkspacePackageInspectionResult {
    requireWorkspacePackageFile(path)
    require(request.maxEntries >= 1) { "maxEntries must be >= 1." }
    require(request.previewChars >= 1) { "previewChars must be >= 1." }

    val matcher = request.glob
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let(::compilePackageGlobMatcher)
    val normalizedPreviewEntries = request.previewEntries
      .map(::normalizePackageEntryPath)
      .filter(String::isNotBlank)
      .distinct()

    ZipFile(path.toFile()).use { zipFile ->
      val indexedEntries = indexedPackageEntries(zipFile)
      val packageKind = detectWorkspacePackageKind(indexedEntries, zipFile)
      val matchedEntries = indexedEntries.filter { indexed ->
        matcher?.matches(indexed.path) ?: true
      }
      val renderedEntries = matchedEntries
        .take(request.maxEntries)
        .map(::renderWorkspacePackageEntry)
      val previews = normalizedPreviewEntries.map { previewPath ->
        val indexed = indexedEntries.firstOrNull { candidate -> candidate.path == previewPath }
          ?: throw IllegalArgumentException("Package entry '$previewPath' was not found.")
        readWorkspacePackagePreview(
          zipFile = zipFile,
          indexed = indexed,
          previewChars = request.previewChars,
        )
      }
      return WorkspacePackageInspectionResult(
        packageKind = packageKind,
        entryCount = indexedEntries.size,
        matchedEntryCount = matchedEntries.size,
        entries = renderedEntries,
        previews = previews,
        mainPartHints = if (request.includeRelationshipHints) mainPartHintsFor(packageKind) else emptyList(),
        relationshipPartHints = if (request.includeRelationshipHints) relationshipPartHintsFor(packageKind) else emptyList(),
        mediaEntryCount = indexedEntries.count { indexed -> isPackageMediaEntry(indexed.path) },
        truncated = matchedEntries.size > renderedEntries.size || previews.any(WorkspacePackageEntryPreview::truncated),
      )
    }
  }

  override fun extract(
    path: Path,
    request: WorkspacePackageExtractionRequest,
  ): WorkspacePackageExtractionResult {
    requireWorkspacePackageFile(path)
    require(request.entries.isNotEmpty() || !request.glob.isNullOrBlank()) {
      "extract_workspace_package requires entries or glob."
    }
    val destinationRoot = request.destinationRoot.toAbsolutePath().normalize()
    if (Files.exists(destinationRoot)) {
      require(Files.isDirectory(destinationRoot)) {
        "Package extraction destination must be a directory: $destinationRoot"
      }
    }

    ZipFile(path.toFile()).use { zipFile ->
      val indexedEntries = indexedPackageEntries(zipFile)
      val packageKind = detectWorkspacePackageKind(indexedEntries, zipFile)
      val matchedEntries = selectExtractableEntries(
        indexedEntries = indexedEntries,
        requestedEntries = request.entries,
        requestedGlob = request.glob,
      )
      require(matchedEntries.isNotEmpty()) {
        "No package entries matched the extraction request."
      }

      val strippedTopLevel = request.stripTopLevel
        .takeIf { it }
        ?.let { findCommonTopLevelSegment(matchedEntries.map(IndexedPackageEntry::path)) }
      val destinationMappings = buildDestinationMappings(
        matchedEntries = matchedEntries,
        destinationRoot = destinationRoot,
        strippedTopLevel = strippedTopLevel,
      )

      destinationMappings.values.forEach { outputPath ->
        if (Files.exists(outputPath) && !request.overwrite) {
          throw IllegalArgumentException("Package extraction destination already exists: $outputPath")
        }
      }

      Files.createDirectories(destinationRoot)
      var totalExtractedBytes = 0L
      val extractedPaths = mutableListOf<Path>()
      destinationMappings.forEach { (indexed, outputPath) ->
        outputPath.parent?.let(Files::createDirectories)
        zipFile.getInputStream(indexed.entry).use { input ->
          val writtenBytes = copyBounded(
            input = input,
            destination = outputPath,
            overwrite = request.overwrite,
          )
          totalExtractedBytes += writtenBytes
          require(totalExtractedBytes <= MAX_TOTAL_EXTRACTED_BYTES) {
            "Package extraction exceeded ${MAX_TOTAL_EXTRACTED_BYTES / (1024 * 1024)} MB total output."
          }
        }
        extractedPaths.add(outputPath)
      }

      return WorkspacePackageExtractionResult(
        packageKind = packageKind,
        entryCount = indexedEntries.size,
        matchedEntryCount = matchedEntries.size,
        extractedPaths = extractedPaths.sortedBy { extracted -> extracted.toString().replace('\\', '/') },
        strippedTopLevel = strippedTopLevel,
      )
    }
  }
}

fun workspacePackageKindFor(path: Path): WorkspacePackageKind? {
  if (!Files.exists(path) || !Files.isRegularFile(path) || !hasZipMagic(path)) {
    return null
  }
  return try {
    ZipFile(path.toFile()).use { zipFile ->
      detectWorkspacePackageKind(
        indexedEntries = indexedPackageEntries(zipFile),
        zipFile = zipFile,
      )
    }
  } catch (_: ZipException) {
    null
  } catch (_: IllegalArgumentException) {
    null
  }
}

private data class IndexedPackageEntry(
  val entry: ZipEntry,
  val path: String,
  val isDirectory: Boolean,
  val compressedSize: Long?,
  val uncompressedSize: Long?,
  val mimeType: String?,
  val previewable: Boolean,
)

private fun indexedPackageEntries(zipFile: ZipFile): List<IndexedPackageEntry> {
  val collected = mutableListOf<IndexedPackageEntry>()
  val iterator = zipFile.entries()
  while (iterator.hasMoreElements()) {
    require(collected.size < MAX_PACKAGE_ENTRY_COUNT) {
      "Workspace package contains too many entries. Limit is $MAX_PACKAGE_ENTRY_COUNT."
    }
    val entry = iterator.nextElement()
    val normalizedPath = normalizePackageEntryPath(entry.name)
    if (normalizedPath.isBlank()) {
      continue
    }
    collected += IndexedPackageEntry(
      entry = entry,
      path = normalizedPath,
      isDirectory = entry.isDirectory,
      compressedSize = entry.compressedSize.takeIf { size -> size >= 0L },
      uncompressedSize = entry.size.takeIf { size -> size >= 0L },
      mimeType = packageEntryMimeType(normalizedPath),
      previewable = isPreviewablePackageEntry(normalizedPath, entry.isDirectory),
    )
  }
  return collected.sortedBy(IndexedPackageEntry::path)
}

private fun detectWorkspacePackageKind(
  indexedEntries: List<IndexedPackageEntry>,
  zipFile: ZipFile,
): WorkspacePackageKind {
  val entryPaths = indexedEntries.map(IndexedPackageEntry::path).toSet()
  if (entryPaths.contains("word/document.xml")) {
    return WorkspacePackageKind.DOCX
  }
  if (entryPaths.contains("xl/workbook.xml")) {
    return WorkspacePackageKind.XLSX
  }
  if (entryPaths.contains("ppt/presentation.xml")) {
    return WorkspacePackageKind.PPTX
  }
  val mimetypeEntry = indexedEntries.firstOrNull { indexed -> indexed.path == "mimetype" }
  if (mimetypeEntry != null && !mimetypeEntry.isDirectory) {
    val mimetype = zipFile.getInputStream(mimetypeEntry.entry).use { input ->
      readAtMostBytes(input = input, maxBytes = MAX_MIMETYPE_BYTES)
        .toString(StandardCharsets.UTF_8)
        .trim()
    }
    when (mimetype) {
      "application/vnd.oasis.opendocument.text" -> return WorkspacePackageKind.ODT
      "application/vnd.oasis.opendocument.spreadsheet" -> return WorkspacePackageKind.ODS
      "application/vnd.oasis.opendocument.presentation" -> return WorkspacePackageKind.ODP
    }
  }
  return WorkspacePackageKind.ZIP
}

private fun renderWorkspacePackageEntry(indexed: IndexedPackageEntry): WorkspacePackageEntry =
  WorkspacePackageEntry(
    path = indexed.path,
    isDirectory = indexed.isDirectory,
    compressedSize = indexed.compressedSize,
    uncompressedSize = indexed.uncompressedSize,
    mimeType = indexed.mimeType,
    previewable = indexed.previewable,
  )

private fun readWorkspacePackagePreview(
  zipFile: ZipFile,
  indexed: IndexedPackageEntry,
  previewChars: Int,
): WorkspacePackageEntryPreview {
  if (indexed.isDirectory) {
    return WorkspacePackageEntryPreview(
      path = indexed.path,
      content = "<directory preview unavailable>",
      truncated = false,
    )
  }
  if (!indexed.previewable) {
    return WorkspacePackageEntryPreview(
      path = indexed.path,
      content = "<binary entry preview unavailable>",
      truncated = false,
    )
  }

  val byteBudget = (previewChars * PREVIEW_BYTE_MULTIPLIER).coerceAtMost(MAX_PREVIEW_ENTRY_BYTES)
  val bytes = zipFile.getInputStream(indexed.entry).use { input ->
    readAtMostBytes(input = input, maxBytes = byteBudget + 1)
  }
  val truncatedByByteBudget = bytes.size > byteBudget
  val decoded = bytes
    .copyOf(minOf(bytes.size, byteBudget))
    .toString(StandardCharsets.UTF_8)
  val truncatedText = decoded.take(previewChars)
  val truncated = truncatedByByteBudget || truncatedText.length < decoded.length
  return WorkspacePackageEntryPreview(
    path = indexed.path,
    content = if (truncated) truncatedText.trimEnd() + "..." else truncatedText,
    truncated = truncated,
  )
}

private fun selectExtractableEntries(
  indexedEntries: List<IndexedPackageEntry>,
  requestedEntries: List<String>,
  requestedGlob: String?,
): List<IndexedPackageEntry> {
  val fileEntries = indexedEntries.filterNot(IndexedPackageEntry::isDirectory)
  val selected = linkedSetOf<IndexedPackageEntry>()

  requestedEntries
    .map(::normalizePackageEntryPath)
    .filter(String::isNotBlank)
    .distinct()
    .forEach { requestedPath ->
      val exactMatches = fileEntries.filter { indexed -> indexed.path == requestedPath }
      if (exactMatches.isNotEmpty()) {
        selected += exactMatches
      } else {
        val prefix = requestedPath.trimEnd('/') + "/"
        val descendants = fileEntries.filter { indexed -> indexed.path.startsWith(prefix) }
        require(descendants.isNotEmpty()) {
          "Package entry '$requestedPath' was not found."
        }
        selected += descendants
      }
    }

  requestedGlob
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?.let(::compilePackageGlobMatcher)
    ?.let { matcher ->
      selected += fileEntries.filter { indexed -> matcher.matches(indexed.path) }
    }

  return selected.toList().sortedBy(IndexedPackageEntry::path)
}

private fun findCommonTopLevelSegment(paths: List<String>): String? {
  val topLevelSegments = paths.map { path ->
    val slashIndex = path.indexOf('/')
    if (slashIndex <= 0) {
      return null
    }
    path.substring(0, slashIndex)
  }.distinct()
  return topLevelSegments.singleOrNull()
}

private fun buildDestinationMappings(
  matchedEntries: List<IndexedPackageEntry>,
  destinationRoot: Path,
  strippedTopLevel: String?,
): Map<IndexedPackageEntry, Path> {
  val mappings = linkedMapOf<IndexedPackageEntry, Path>()
  val claimedTargets = linkedSetOf<Path>()
  matchedEntries.forEach { indexed ->
    val relativeOutputPath = stripPackageTopLevel(indexed.path, strippedTopLevel)
    val outputPath = destinationRoot.resolve(relativeOutputPath).normalize()
    require(outputPath.startsWith(destinationRoot)) {
      "Package extraction would escape the requested destination directory."
    }
    require(claimedTargets.add(outputPath)) {
      "Package extraction would write multiple entries to the same destination path: $outputPath"
    }
    mappings[indexed] = outputPath
  }
  return mappings
}

private fun stripPackageTopLevel(
  entryPath: String,
  strippedTopLevel: String?,
): String {
  if (strippedTopLevel == null) {
    return entryPath
  }
  val prefix = "$strippedTopLevel/"
  return entryPath.removePrefix(prefix).ifBlank {
    throw IllegalArgumentException("Package entry '$entryPath' cannot be extracted after strip_top_level.")
  }
}

private fun copyBounded(
  input: InputStream,
  destination: Path,
  overwrite: Boolean,
): Long {
  val options = if (overwrite) {
    arrayOf(
      StandardOpenOption.CREATE,
      StandardOpenOption.TRUNCATE_EXISTING,
      StandardOpenOption.WRITE,
    )
  } else {
    arrayOf(
      StandardOpenOption.CREATE_NEW,
      StandardOpenOption.WRITE,
    )
  }

  var writtenBytes = 0L
  try {
    Files.newOutputStream(destination, *options).use { output ->
      val buffer = ByteArray(COPY_BUFFER_BYTES)
      while (true) {
        val read = input.read(buffer)
        if (read <= 0) {
          break
        }
        writtenBytes += read.toLong()
        require(writtenBytes <= MAX_EXTRACTED_ENTRY_BYTES) {
          "Package extraction entry exceeded ${MAX_EXTRACTED_ENTRY_BYTES / (1024 * 1024)} MB."
        }
        output.write(buffer, 0, read)
      }
    }
  } catch (error: Throwable) {
    runCatching { Files.deleteIfExists(destination) }
    throw error
  }
  return writtenBytes
}

private fun readAtMostBytes(
  input: InputStream,
  maxBytes: Int,
): ByteArray {
  if (maxBytes <= 0) {
    return ByteArray(0)
  }
  val output = ByteArrayOutputStream(minOf(maxBytes, COPY_BUFFER_BYTES))
  val buffer = ByteArray(minOf(maxBytes, COPY_BUFFER_BYTES))
  var remaining = maxBytes
  while (remaining > 0) {
    val read = input.read(buffer, 0, minOf(buffer.size, remaining))
    if (read <= 0) {
      break
    }
    output.write(buffer, 0, read)
    remaining -= read
  }
  return output.toByteArray()
}

private fun requireWorkspacePackageFile(path: Path) {
  require(Files.exists(path) && Files.isRegularFile(path)) {
    "Workspace package must exist and be a file: $path"
  }
  require(Files.size(path) in 1..MAX_PACKAGE_FILE_BYTES) {
    "Workspace package must be between 1 byte and ${MAX_PACKAGE_FILE_BYTES / (1024 * 1024)} MB: $path"
  }
  require(hasZipMagic(path)) {
    "Workspace package must be a ZIP-based document or archive: $path"
  }
}

private fun hasZipMagic(path: Path): Boolean {
  val header = ByteArray(4)
  Files.newInputStream(path).use { input ->
    val read = input.read(header)
    if (read < 4) {
      return false
    }
  }
  return header[0] == 0x50.toByte() &&
    header[1] == 0x4B.toByte() &&
    (header[2] == 0x03.toByte() || header[2] == 0x05.toByte() || header[2] == 0x07.toByte()) &&
    (header[3] == 0x04.toByte() || header[3] == 0x06.toByte() || header[3] == 0x08.toByte())
}

private fun mainPartHintsFor(kind: WorkspacePackageKind): List<String> = when (kind) {
  WorkspacePackageKind.DOCX -> listOf("word/document.xml")
  WorkspacePackageKind.XLSX -> listOf("xl/workbook.xml")
  WorkspacePackageKind.PPTX -> listOf("ppt/presentation.xml")
  WorkspacePackageKind.ODT,
  WorkspacePackageKind.ODS,
  WorkspacePackageKind.ODP,
  -> listOf("content.xml")
  WorkspacePackageKind.ZIP -> emptyList()
}

private fun relationshipPartHintsFor(kind: WorkspacePackageKind): List<String> = when (kind) {
  WorkspacePackageKind.DOCX -> listOf("_rels/.rels", "word/_rels/document.xml.rels")
  WorkspacePackageKind.XLSX -> listOf("_rels/.rels", "xl/_rels/workbook.xml.rels")
  WorkspacePackageKind.PPTX -> listOf("_rels/.rels", "ppt/_rels/presentation.xml.rels")
  WorkspacePackageKind.ODT,
  WorkspacePackageKind.ODS,
  WorkspacePackageKind.ODP,
  -> listOf("META-INF/manifest.xml")
  WorkspacePackageKind.ZIP -> emptyList()
}

private fun isPackageMediaEntry(path: String): Boolean {
  val normalizedPath = path.lowercase(Locale.US)
  return normalizedPath.startsWith("word/media/") ||
    normalizedPath.startsWith("xl/media/") ||
    normalizedPath.startsWith("ppt/media/") ||
    normalizedPath.startsWith("pictures/")
}

private fun packageEntryMimeType(path: String): String? {
  if (path.equals("mimetype", ignoreCase = true)) {
    return "text/plain"
  }
  return OpenCrayAttachmentArtifacts.mimeTypeForDisplayName(path.substringAfterLast('/'))
    ?: path.takeIf { value -> value.endsWith(".rels", ignoreCase = true) }?.let { "application/xml" }
}

private fun isPreviewablePackageEntry(
  path: String,
  isDirectory: Boolean,
): Boolean {
  if (isDirectory) {
    return false
  }
  if (path.equals("mimetype", ignoreCase = true)) {
    return true
  }
  val extension = path.substringAfterLast('.', "").lowercase(Locale.US)
  return extension in PREVIEWABLE_PACKAGE_ENTRY_EXTENSIONS
}

private fun normalizePackageEntryPath(rawPath: String): String {
  val normalized = rawPath.trim().replace('\\', '/').trimStart('/')
  if (normalized.isBlank()) {
    return ""
  }
  val segments = mutableListOf<String>()
  normalized.split('/').forEach { segment ->
    when {
      segment.isBlank() || segment == "." -> Unit
      segment == ".." -> {
        if (segments.isNotEmpty() && segments.last() != "..") {
          segments.removeAt(segments.lastIndex)
        } else {
          segments += segment
        }
      }
      else -> segments += segment
    }
  }
  return segments.joinToString(separator = "/")
}

private fun compilePackageGlobMatcher(pattern: String): Regex =
  Regex("^${packageGlobPatternToRegex(pattern.replace('\\', '/'))}$")

private fun packageGlobPatternToRegex(pattern: String): String {
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

private val PREVIEWABLE_PACKAGE_ENTRY_EXTENSIONS: Set<String> = setOf(
  "csv",
  "json",
  "md",
  "rels",
  "txt",
  "xml",
  "yaml",
  "yml",
)

private const val COPY_BUFFER_BYTES: Int = 8_192
private const val MAX_EXTRACTED_ENTRY_BYTES: Long = 16L * 1024L * 1024L
private const val MAX_MIMETYPE_BYTES: Int = 512
private const val MAX_PACKAGE_ENTRY_COUNT: Int = 4_000
private const val MAX_PACKAGE_FILE_BYTES: Long = 64L * 1024L * 1024L
private const val MAX_PREVIEW_ENTRY_BYTES: Int = 32_000
private const val MAX_TOTAL_EXTRACTED_BYTES: Long = 128L * 1024L * 1024L
private const val PREVIEW_BYTE_MULTIPLIER: Int = 4
